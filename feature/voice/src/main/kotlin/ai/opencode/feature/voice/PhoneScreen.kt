package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the assistant saw on the screen: the app in front (its name, package and activity), the
 * page's web address when the app shared one, and the words on the screen.
 */
data class ScreenInfo(
    val app: String?,
    val packageName: String?,
    val activity: String?,
    val url: String?,
    val text: String,
)

/** The longest side of a screenshot sent to the model, in pixels. */
private const val SCREEN_LONG_SIDE = 1280

/** A fresh read of the screen from Android's assist data: either part can be missing. */
class ScreenRead(val info: ScreenInfo?, val screenshot: Bitmap?)

/**
 * Reads the screen for `read_screen` (Android only), fresh every time, never from a cache.
 *
 * First Android's assist data, which the phone's assistant (OpenCode, when it holds the role) can
 * ask for at any time without a prompt. When that gives nothing (Samsung gives third-party
 * assistants none, or OpenCode isn't the assistant), Android's screen-sharing prompt instead, once
 * per call: the share then lasts until the call ends, the phone locks or the user stops it.
 * Never logs what's on the screen.
 */
internal object PhoneScreen {
    private const val MAX_LENGTH = 12_000
    private const val QUALITY = 80
    private const val PROMPT_OPENS_MILLIS = 3_000L
    private const val ANSWER_MILLIS = 55_000L
    private const val PROMPT_CLOSES_MILLIS = 500L

    /** The call whose assist data came back empty, so its later reads go straight to sharing. */
    @Volatile private var assistEmptyFor: UUID? = null

    /** One read at a time, so two reads never show Android's prompt twice. */
    private val reading = Mutex()

    suspend fun read(): PhoneTools.Result = reading.withLock { readNow() }

    private suspend fun readNow(): PhoneTools.Result {
        val context = dependency(AppContext)
        if (isLocked(context)) return PhoneTools.Result.failure(PhoneTools.LOCKED)
        val call = dependency(VoiceCallCommands).calls.value
        AppLog.app.info { "Screen read asked call=${call != null} sharing=${ScreenShare.isSharing(call)}" }
        if (call == null || assistEmptyFor != call) {
            val assist = dependency(AssistantPanelClient).readScreen()
            val info = assist?.info
            val screenshot = assist?.screenshot
            val hasText = !info?.text.isNullOrBlank()
            if (hasText || screenshot != null) {
                AppLog.app.info { "Screen read source=assist text=$hasText screenshot=${screenshot != null}" }
                if (!hasText && screenshot != null && isBlack(screenshot)) {
                    return result(info, screenshot, PhoneTools.NO_SCREENSHOT, protectedNote(info))
                }
                return result(info, screenshot, PhoneTools.NO_SCREENSHOT)
            }
            if (call != null) assistEmptyFor = call
            return shared(context, call, info)
        }
        return shared(context, call, null)
    }

    private suspend fun shared(context: Context, call: UUID?, info: ScreenInfo?): PhoneTools.Result {
        if (ScreenShare.stoppedByUser(call)) return PhoneTools.Result.failure(PhoneTools.SCREEN_STOPPED_BY_USER)
        if (!ScreenShare.isSharing(call)) {
            val asked = ask(context, call)
            if (asked != null) return asked
            // Android's prompt is still fading out over the screen.
            delay(PROMPT_CLOSES_MILLIS)
        }
        if (!ScreenShare.isVisible()) return PhoneTools.Result.failure(PhoneTools.SCREEN_ONE_APP)
        val frame = ScreenShare.frame()
        // Without a call, nothing ends the share for it, so it lasts for this one read.
        if (call == null) ScreenShare.stop(context, "read")
        if (frame == null) {
            if (!ScreenShare.isSharing(call) && call != null) return PhoneTools.Result.failure(PhoneTools.SCREEN_STOPPED)
            return PhoneTools.Result.failure(PhoneTools.SCREEN_FAILED)
        }
        AppLog.app.info { "Screen read source=share" }
        return result(info, frame, null)
    }

    /** Shows Android's prompt and waits for the share to start; null once it has. */
    private suspend fun ask(context: Context, call: UUID?): PhoneTools.Result? {
        val consent = ScreenShare.expect(call)
        // The panel mustn't cover the prompt's buttons.
        dependency(AssistantPanelClient).showedScreen()
        val intent = Intent(context, ScreenShareActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(ScreenShareActivity.EXTRA_CONSENT, consent.id)
        val launch = PhoneLaunch.start(intent)
        if (launch.status != PhoneLaunch.Status.started) {
            AppLog.app.notice("Screen share prompt blocked")
            ScreenShare.forget(consent)
            return PhoneTools.Result.failure(PhoneTools.SCREEN_PROMPT_BLOCKED)
        }
        // Android drops a blocked start without an error, so the prompt has to show up in time.
        val answer: ScreenShare.Answer?
        try {
            val opened = withTimeoutOrNull(PROMPT_OPENS_MILLIS) { consent.opened.await() }
            if (opened == null) {
                AppLog.app.notice("Screen share prompt never opened")
                ScreenShare.forget(consent)
                return PhoneTools.Result.failure(PhoneTools.SCREEN_PROMPT_BLOCKED)
            }
            answer = withTimeoutOrNull(ANSWER_MILLIS) { consent.answer.await() }
        } catch (cancelled: CancellationException) {
            // The server cancelled the tool call: the prompt goes with it.
            ScreenShare.forget(consent)
            throw cancelled
        }
        return when (answer) {
            null -> {
                AppLog.app.notice("Screen share prompt unanswered")
                ScreenShare.forget(consent)
                PhoneTools.Result.failure(PhoneTools.SCREEN_NO_ANSWER)
            }
            ScreenShare.Answer.declined -> PhoneTools.Result.failure(PhoneTools.SCREEN_DECLINED)
            ScreenShare.Answer.failed -> PhoneTools.Result.failure(PhoneTools.SCREEN_FAILED)
            ScreenShare.Answer.sharing -> null
        }
    }

    /**
     * `{"app":"Messages","package":…,"activity":…,"url":…,"text":"…"}` with the screenshot as a
     * second item; the server turns a JSON text result into an object for the model. A missing
     * part says why, so the model never guesses.
     */
    private fun result(info: ScreenInfo?, screenshot: Bitmap?, noScreenshot: String?, note: String? = null): PhoneTools.Result {
        val jpeg = screenshot?.let { jpeg(it) }
        val fields = mapOf(
            "app" to PhoneDetails.string(info?.app),
            "package" to PhoneDetails.string(info?.packageName),
            "activity" to PhoneDetails.string(info?.activity),
            "url" to PhoneDetails.string(info?.url),
            "text" to PhoneDetails.string(PhoneDetails.cap(info?.text, MAX_LENGTH)),
            "screenshot" to if (jpeg == null && noScreenshot != null) JsonPrimitive(noScreenshot) else null,
            "note" to when {
                note != null -> JsonPrimitive(note)
                info == null || info.text.isBlank() -> JsonPrimitive(PhoneTools.SCREEN_ONLY_PICTURE)
                else -> null
            },
        )
        return PhoneTools.Result(
            PhoneDetails.json(fields),
            action = PhoneTools.Action(PhoneTools.Action.Kind.screen, ""),
            jpeg = jpeg,
        )
    }

    /**
     * Whether the screen's body (below the status bar) is pure black: Android draws a window with
     * FLAG_SECURE (an incognito tab, a banking app) that way, but so can a dark video or wallpaper.
     */
    internal fun isBlack(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 20 || height < 20) return false
        val pixels = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        if (pixels == null) return false
        val startX = (width * 0.1f).toInt()
        val endX = (width * 0.9f).toInt()
        val startY = (height * 0.15f).toInt()
        val endY = (height * 0.85f).toInt()
        val stepX = max(1, (endX - startX) / 8)
        val stepY = max(1, (endY - startY) / 10)
        var y = startY
        while (y <= endY) {
            var x = startX
            while (x <= endX) {
                if ((pixels.getPixel(x, y) and 0x00FFFFFF) != 0) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }

    private fun protectedNote(info: ScreenInfo?): String {
        val app = info?.app ?: "The app on screen"
        return "The screen came back black with no text. $app may hide its content from screenshots and screen reading (for example, an incognito tab or a banking app), or the screen may really be dark."
    }

    /** Scaled down to [SCREEN_LONG_SIDE], so a read never sends more than the model needs. */
    private fun jpeg(bitmap: Bitmap): ByteArray? {
        val longest = max(bitmap.width, bitmap.height)
        val scaled = if (longest <= SCREEN_LONG_SIDE) {
            bitmap
        } else {
            val scale = SCREEN_LONG_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        }
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return null
        return out.toByteArray()
    }

    private fun isLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
}

/**
 * The screen share behind `read_screen` (Android only): one MediaProjection at a time, made for
 * one call (or one read without a call). Frames go to an [ImageReader]; only the latest is kept,
 * and only a read turns it into a picture.
 */
internal object ScreenShare {
    enum class Answer { sharing, declined, failed }

    /** One showing of Android's prompt: it [opened], then the user answered. */
    class Consent(val call: UUID?) {
        val id: String = UUID.randomUUID().toString()
        val opened = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Answer>()
    }

    private const val FIRST_FRAME_MILLIS = 1_500L

    private val lock = Any()
    private var consent: Consent? = null
    private var prompt: WeakReference<ScreenShareActivity>? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var sharingFor: UUID? = null
    private var visible = true
    private var userStoppedFor: UUID? = null
    private var thread: HandlerThread? = null
    private var density = 0
    @Volatile private var stopping = false

    private val running = MutableStateFlow(false)
    /** Whether a share is running, for anything that shows it. */
    val isRunning: StateFlow<Boolean> = running

    fun expect(call: UUID?): Consent {
        val next = Consent(call)
        synchronized(lock) { consent = next }
        return next
    }

    /** Gives up on [expected]: its prompt, if it's still up, goes too. */
    fun forget(expected: Consent) {
        val asking = synchronized(lock) {
            if (consent !== expected) return
            consent = null
            prompt?.get().also { prompt = null }
        }
        asking?.close()
    }

    /** False when the read already gave up on consent [id], so its prompt shouldn't show. */
    fun promptOpened(activity: ScreenShareActivity, id: String?): Boolean {
        val asked = synchronized(lock) {
            val current = consent
            if (current == null || current.id != id) return false
            prompt = WeakReference(activity)
            current
        }
        asked.opened.complete(Unit)
        return true
    }

    fun promptClosed(activity: ScreenShareActivity) {
        synchronized(lock) { if (prompt?.get() === activity) prompt = null }
    }

    fun isSharing(call: UUID?): Boolean = synchronized(lock) { projection != null && sharingFor == call }

    fun isVisible(): Boolean = synchronized(lock) { visible }

    /** Once: the user stopped the share (Stop Sharing or Android's indicator) during [call]; the next read asks again. */
    fun stoppedByUser(call: UUID?): Boolean = synchronized(lock) {
        if (call == null || userStoppedFor != call) return false
        userStoppedFor = null
        true
    }

    /** The user answered Android's prompt. A yes starts the share's foreground service, which [start]s it. */
    /** The user answered the prompt for consent [id]; an answer for a prompt already given up on is dropped. */
    fun answered(context: Context, id: String?, resultCode: Int, data: Intent?) {
        val asked = synchronized(lock) { consent?.takeIf { it.id == id } } ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            AppLog.app.info("Screen share declined")
            finish(asked, Answer.declined)
            return
        }
        val intent = Intent(context, ScreenShareService::class.java)
            .setAction(ScreenShareService.START)
            .putExtra(ScreenShareService.EXTRA_RESULT, resultCode)
            .putExtra(ScreenShareService.EXTRA_DATA, data)
        try {
            context.startForegroundService(intent)
        } catch (error: Exception) {
            AppLog.app.error("Screen share service couldn’t start: ${error.javaClass.simpleName}")
            finish(asked, Answer.failed)
        }
    }

    /** In the service, once it's in the foreground, as Android requires before the projection. */
    fun start(context: Context, resultCode: Int, data: Intent): Boolean {
        val asked = synchronized(lock) { consent }
        val call = asked?.call ?: dependency(VoiceCallCommands).calls.value
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val started = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (error: Exception) {
            AppLog.app.error("Screen share couldn’t start: ${error.javaClass.simpleName}")
            null
        }
        if (started == null) {
            finish(asked, Answer.failed)
            return false
        }
        val handlerThread = HandlerThread("ScreenShare").apply { start() }
        val handler = Handler(handlerThread.looper)
        started.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopped(context, started)
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                resize(started, width, height, handler)
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                synchronized(lock) { if (projection === started) visible = isVisible }
            }
        }, handler)
        synchronized(lock) {
            projection = started
            sharingFor = call
            visible = true
            userStoppedFor = null
            thread = handlerThread
        }
        val metrics = context.resources.displayMetrics
        val (width, height) = scaled(metrics.widthPixels, metrics.heightPixels)
        val dpi = metrics.densityDpi * width / metrics.widthPixels
        val surfaceReader = reader(width, height, handler)
        val virtual = try {
            started.createVirtualDisplay(
                "OpenCode screen", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surfaceReader.surface, null, handler,
            )
        } catch (error: Exception) {
            AppLog.app.error("Screen share display failed: ${error.javaClass.simpleName}")
            null
        }
        if (virtual == null) {
            surfaceReader.close()
            started.stop()
            finish(asked, Answer.failed)
            return false
        }
        synchronized(lock) {
            reader = surfaceReader
            display = virtual
            density = dpi
        }
        running.value = true
        AppLog.app.info { "Screen share started call=${call != null} size=${width}x$height" }
        finish(asked, Answer.sharing)
        return true
    }

    /** The latest frame, waiting a moment for the first one. */
    suspend fun frame(): Bitmap? {
        val waited = withTimeoutOrNull(FIRST_FRAME_MILLIS) {
            while (synchronized(lock) { latest == null && projection != null }) delay(50)
        }
        if (waited == null) AppLog.app.notice("Screen share had no frame")
        synchronized(lock) {
            val image = latest ?: return null
            return bitmap(image)
        }
    }

    /**
     * Ends the share: the call ended, the phone locked, the user tapped Stop, or a read without a call is done.
     * [byUser] when the user tapped Stop Sharing, so the next read says so.
     */
    fun stop(context: Context, reason: String, byUser: Boolean = false) {
        val current = synchronized(lock) {
            if (byUser && projection != null) userStoppedFor = sharingFor
            projection
        } ?: return
        AppLog.app.info("Screen share stopping reason=$reason")
        stopping = true
        try {
            current.stop()
            stopped(context, current)
        } finally {
            stopping = false
        }
    }

    private fun stopped(context: Context, ended: MediaProjection) {
        val byUser: Boolean
        synchronized(lock) {
            if (projection !== ended) return
            // Android stops it from its own indicator, on lock, or for another share; ours went through [stop] first.
            byUser = !stopping
            if (byUser) userStoppedFor = sharingFor
            projection = null
            sharingFor = null
            display?.release()
            display = null
            latest?.close()
            latest = null
            reader?.close()
            reader = null
            thread?.quitSafely()
            thread = null
            visible = true
        }
        running.value = false
        AppLog.app.info("Screen share ended byUser=$byUser")
        context.stopService(Intent(context, ScreenShareService::class.java))
    }

    private fun finish(asked: Consent?, answer: Answer) {
        synchronized(lock) { if (consent === asked) consent = null }
        asked?.answer?.complete(answer)
    }

    private fun resize(started: MediaProjection, width: Int, height: Int, handler: Handler) {
        val (scaledWidth, scaledHeight) = scaled(width, height)
        synchronized(lock) {
            if (projection !== started) return
            val virtual = display ?: return
            val next = reader(scaledWidth, scaledHeight, handler)
            virtual.surface = next.surface
            virtual.resize(scaledWidth, scaledHeight, density)
            latest?.close()
            latest = null
            reader?.close()
            reader = next
        }
    }

    private fun reader(width: Int, height: Int, handler: Handler): ImageReader {
        val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        next.setOnImageAvailableListener({ source ->
            val image = try {
                source.acquireLatestImage()
            } catch (_: IllegalStateException) {
                null
            } ?: return@setOnImageAvailableListener
            synchronized(lock) {
                if (reader !== source) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                latest?.close()
                latest = image
            }
        }, handler)
        return next
    }

    private fun scaled(width: Int, height: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= SCREEN_LONG_SIDE) return Pair(width, height)
        val scale = SCREEN_LONG_SIDE.toFloat() / longest
        return Pair((width * scale).roundToInt(), (height * scale).roundToInt())
    }

    private fun bitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val rowWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(rowWidth, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowWidth == image.width) return padded
        return Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
    }
}

/**
 * Shows Android's screen-sharing prompt with the entire screen chosen (Android 14+ also offers one
 * app), and hands the answer to [ScreenShare]. Translucent, so nothing of OpenCode shows.
 */
class ScreenShareActivity : Activity() {
    private var answered = false
    private var consent: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consent = intent.getStringExtra(EXTRA_CONSENT)
        if (!ScreenShare.promptOpened(this, consent)) {
            answered = true
            finish()
            return
        }
        if (savedInstanceState != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            answered = true
            ScreenShare.answered(this, consent, RESULT_CANCELED, null)
            finish()
            return
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) {
            answered = true
            ScreenShare.answered(applicationContext, consent, resultCode, data)
        }
        finish()
    }

    /** The read stopped waiting for an answer: Android's prompt closes with this. */
    fun close() {
        @Suppress("DEPRECATION")
        finishActivity(REQUEST)
        finish()
    }

    override fun onDestroy() {
        if (!answered && !isChangingConfigurations) {
            ScreenShare.answered(applicationContext, consent, RESULT_CANCELED, null)
        }
        ScreenShare.promptClosed(this)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONSENT = "consent"
        private const val REQUEST = 1
    }
}

/**
 * The foreground service a screen share needs (type `mediaProjection`). Its notification says the
 * screen is being shared and has Stop; it ends the share when the call ends or the screen turns off.
 */
class ScreenShareService : Service() {
    private var scope: CoroutineScope? = null
    private var screenOff: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> {
                ScreenShare.stop(this, "stop", byUser = true)
                stopSelf()
                return START_NOT_STICKY
            }
            START -> {
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                val data = intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                val result = intent.getIntExtra(EXTRA_RESULT, 0)
                if (data == null || !ScreenShare.start(this, result, data)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                watch()
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    /** Ends the share when its call ends or changes, and when the screen turns off. */
    private fun watch() {
        if (scope != null) return
        val calls = dependency(VoiceCallCommands).calls
        val call = calls.value
        val watching = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = watching
        if (call != null) {
            watching.launch {
                calls.collect { current ->
                    if (current != call) ScreenShare.stop(this@ScreenShareService, "call")
                }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                ScreenShare.stop(this@ScreenShareService, "locked")
            }
        }
        registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        screenOff = receiver
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        screenOff?.let { unregisterReceiver(it) }
        screenOff = null
        ScreenShare.stop(this, "service")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Screen Sharing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenShareService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_voice_call_notification)
            .setContentTitle("Sharing your screen with OpenCode")
            .setContentText("Until you stop or the call ends.")
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Stop Sharing", stop).build())
            .build()
    }

    companion object {
        const val START = "ai.opencode.screen-share.START"
        const val STOP = "ai.opencode.screen-share.STOP"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "screen-share"
        private const val NOTIFICATION = 7_302
    }
}
