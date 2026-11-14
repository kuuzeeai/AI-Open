package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlinx.coroutines.CompletableDeferred

/**
 * `take_photo` for [PhoneToolsClient] (Android only): opens the phone's camera app, the user takes
 * the photo, and it comes back to the model as a JPEG, scaled like `read_screen`'s screenshot.
 * The photo stays in OpenCode's cache only until it's sent.
 */
internal object PhonePhoto {
    private const val LONG_SIDE = 1280
    private const val QUALITY = 80

    /** The camera app's answer, from [PhonePhotoActivity]; null while none is open. */
    private var answer: CompletableDeferred<Int>? = null

    suspend fun take(): PhoneTools.Result {
        val context = dependency(AppContext)
        // OpenCode declares the camera (for the composer), so Android makes the camera app check it.
        PhoneAccess.require(context, PhoneAccess.camera.permissions, "use the camera")?.let { return it }
        val file = photoFile(context)
        file.delete()
        val waiting = CompletableDeferred<Int>()
        answer?.cancel()
        answer = waiting
        // The panel mustn't cover the camera's shutter button.
        dependency(AssistantPanelClient).showedScreen()
        try {
            when (PhoneLaunch.start(Intent(context, PhonePhotoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))) {
                PhoneLaunch.Launch.started -> {}
                PhoneLaunch.Launch.blocked -> return PhoneTools.Result.failure(PhoneTools.BLOCKED)
                PhoneLaunch.Launch.noApp -> return PhoneTools.Result.failure(NO_CAMERA)
            }
            val result = waiting.await()
            if (result == RESULT_NO_CAMERA) return PhoneTools.Result.failure(NO_CAMERA)
            if (result != Activity.RESULT_OK || !file.exists() || file.length() == 0L) {
                return PhoneTools.Result.failure("The user closed the camera without taking a photo.")
            }
            val jpeg = jpeg(file) ?: return PhoneTools.Result.failure("The phone couldn’t read the photo the camera took.")
            return PhoneTools.Result(
                "The user took this photo with the phone’s camera.",
                action = PhoneTools.Action.other("Took a photo"),
                jpeg = jpeg,
            )
        } finally {
            if (answer === waiting) answer = null
            // Closing the camera if the call gave up first, so it doesn't wait on a photo no one gets.
            screen?.get()?.close()
            file.delete()
        }
    }

    internal fun answered(result: Int) {
        answer?.complete(result)
    }

    internal fun photoUri(context: Context): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.phone.photos", photoFile(context))

    private fun photoFile(context: Context): File {
        val folder = File(context.cacheDir, "phone-photos")
        folder.mkdirs()
        return File(folder, "photo.jpg")
    }

    /** Upright (cameras often save sideways with an EXIF turn) and scaled down to [LONG_SIDE]. */
    private fun jpeg(file: File): ByteArray? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_SIDE) sample *= 2
        val options = BitmapFactory.Options()
        options.inSampleSize = sample
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null

        val degrees = when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) }.getOrDefault(0)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val longest = max(decoded.width, decoded.height)
        val scale = if (longest > LONG_SIDE) LONG_SIDE.toFloat() / longest else 1f
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postRotate(degrees)
        val upright = if (scale == 1f && degrees == 0f) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        val out = ByteArrayOutputStream()
        if (!upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return null
        return out.toByteArray()
    }

    const val RESULT_NO_CAMERA = 1_000
    private const val NO_CAMERA = "This phone has no camera app that can take a photo for OpenCode."

    /** The open camera screen, to close when the call gives up first. */
    private var screen: WeakReference<PhonePhotoActivity>? = null

    internal fun opened(activity: PhonePhotoActivity) {
        screen = WeakReference(activity)
    }

    internal fun closed(activity: PhonePhotoActivity) {
        if (screen?.get() === activity) screen = null
    }
}

/**
 * Opens the camera app for [PhonePhoto] and hands back whether the user took the photo.
 * Translucent, so nothing of OpenCode shows around the camera.
 */
class PhonePhotoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhonePhoto.opened(this)
        if (savedInstanceState != null) return
        val uri = PhonePhoto.photoUri(this)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST)
        } catch (_: ActivityNotFoundException) {
            PhonePhoto.answered(PhonePhoto.RESULT_NO_CAMERA)
            finish()
        } catch (_: SecurityException) {
            PhonePhoto.answered(PhonePhoto.RESULT_NO_CAMERA)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) PhonePhoto.answered(resultCode)
        finish()
    }

    /** The call stopped waiting: the camera closes with this. */
    fun close() {
        @Suppress("DEPRECATION")
        finishActivity(REQUEST)
        finish()
    }

    override fun onDestroy() {
        PhonePhoto.closed(this)
        // Gone without an answer (the system closed it): the user didn't take one.
        if (isFinishing) PhonePhoto.answered(RESULT_CANCELED)
        super.onDestroy()
    }

    private companion object {
        const val REQUEST = 1
    }
}

/** Lets the camera app write the one photo `take_photo` is waiting on, and nothing else. */
class PhonePhotoProvider : FileProvider(R.xml.phone_photo_paths)
