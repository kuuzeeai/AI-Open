package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.app.AlarmManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.KeyEvent
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The phone's own hardware and settings for [PhoneToolsClient] (Android only). Runs on the main thread. */
internal object PhoneDevice {
    private const val MEDIA_SETTLE_MILLIS = 700L
    private const val MEDIA_WAIT_MILLIS = 2_500L
    private const val MEDIA_POLL_MILLIS = 100L

    fun flashlight(on: Boolean): PhoneTools.Result {
        val cameras = dependency(AppContext).getSystemService(CameraManager::class.java)
            ?: return PhoneTools.Result.failure("This phone has no flashlight.")
        try {
            var torch: String? = null
            for (id in cameras.cameraIdList) {
                val info = cameras.getCameraCharacteristics(id)
                if (info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
                if (torch == null) torch = id
                if (info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    torch = id
                    break
                }
            }
            if (torch == null) return PhoneTools.Result.failure("This phone has no flashlight.")
            cameras.setTorchMode(torch, on)
        } catch (_: CameraAccessException) {
            return PhoneTools.Result.failure("The flashlight is busy: another app is using the camera.")
        }
        val text = if (on) "Flashlight on." else "Flashlight off."
        return PhoneTools.Result(text, action = PhoneTools.Action.other(text.removeSuffix(".")))
    }

    /** Android hands the key to the active or most recent media session; whether music plays after says if it worked. */
    suspend fun media(action: String): PhoneTools.Result {
        val audio = dependency(AppContext).getSystemService(AudioManager::class.java)
            ?: return PhoneTools.Result.failure("Nothing is playing after sending “$action”: no media app is ready. Ask the user which app to open.")
        val key = when (action) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        delay(MEDIA_SETTLE_MILLIS)
        // A paused player can keep its audio track open for seconds (Chrome holds it about ten), so
        // the media session's own state is the better answer when notification access lets the
        // phone see it. Otherwise wait a little for the audio the key asked for.
        val wantsPlaying = action != "pause"
        var waited = MEDIA_SETTLE_MILLIS
        while (isPlaying(audio) != wantsPlaying && waited < MEDIA_WAIT_MILLIS) {
            delay(MEDIA_POLL_MILLIS)
            waited += MEDIA_POLL_MILLIS
        }
        val playing = isPlaying(audio)
        val details = mapOf("now_playing" to nowPlaying())
        if (action == "pause") {
            if (playing) return PhoneTools.Result.failure(PhoneDetails.text("Media is still playing after sending “pause”.", details))
            return PhoneTools.Result(PhoneDetails.text("Paused.", details), action = PhoneTools.Action.other("Paused"))
        }
        if (!playing) {
            return PhoneTools.Result.failure("Nothing is playing after sending “$action”: no media app is ready. Ask the user which app to open.")
        }
        val text = when (action) {
            "next" -> "Skipped to the next track."
            "previous" -> "Went back a track."
            else -> "Playing."
        }
        return PhoneTools.Result(PhoneDetails.text(text, details), action = PhoneTools.Action.other(text.removeSuffix(".")))
    }

    /** The newest active media session, which only notification access lets an app see. */
    internal fun mediaSession(): MediaController? {
        val context = dependency(AppContext)
        if (!PhoneAccess.isGranted(context, PhoneAccess.notifications)) return null
        val sessions = context.getSystemService(MediaSessionManager::class.java)
        val controllers = runCatching {
            sessions?.getActiveSessions(ComponentName(context, PhoneNotificationListener::class.java))
        }.getOrNull()
        return controllers?.firstOrNull()
    }

    /**
     * What's playing: title, artist, album, app, state, position and length. Without notification
     * access Android hides media sessions, so it says that instead of guessing.
     */
    internal fun nowPlaying(): JsonElement {
        val controller = mediaSession() ?: return JsonPrimitive("unknown: OpenCode needs notification access to see what’s playing")
        val metadata = controller.metadata
        val state = controller.playbackState
        val app = PhoneLaunch.appName(controller.packageName)
        val length = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = state?.position ?: -1L
        val stateName = when (state?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING -> "buffering"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_ERROR -> "error"
            else -> null
        }
        return JsonObject(
            PhoneDetails.compact(
                mapOf(
                    "title" to PhoneDetails.string(metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)),
                    "artist" to PhoneDetails.string(
                        metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    ),
                    "album" to PhoneDetails.string(metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)),
                    "app" to JsonPrimitive(app),
                    "package" to JsonPrimitive(controller.packageName),
                    "state" to PhoneDetails.string(stateName),
                    "position" to if (position >= 0) JsonPrimitive(clockLength(position)) else null,
                    "length" to if (length > 0) JsonPrimitive(clockLength(length)) else null,
                ),
            ),
        )
    }

    /** “3:07” or “1:02:45”. */
    internal fun clockLength(millis: Long): String {
        val total = millis / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        val tail = minutes.toString().padStart(if (hours > 0) 2 else 1, '0') + ":" + seconds.toString().padStart(2, '0')
        return if (hours > 0) "$hours:$tail" else tail
    }

    /** The newest active media session's state with notification access, else whether any audio plays. */
    internal fun isPlaying(audio: AudioManager): Boolean {
        val state = mediaSession()?.playbackState?.state
        if (state != null) {
            return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        }
        return audio.isMusicActive
    }

    fun volume(call: PhoneTools.Call.SetVolume): PhoneTools.Result {
        val audio = dependency(AppContext).getSystemService(AudioManager::class.java)
            ?: return PhoneTools.Result.failure("This phone’s volume can’t be changed by apps.")
        if (audio.isVolumeFixed) return PhoneTools.Result.failure("This phone’s volume can’t be changed by apps.")
        val stream = stream(call.stream)
        try {
            if (call.level != null) {
                val max = audio.getStreamMaxVolume(stream)
                audio.setStreamVolume(stream, (call.level / 100f * max).roundToInt(), 0)
            } else {
                val direction = if (call.change == "down") AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                audio.adjustStreamVolume(stream, direction, 0)
            }
        } catch (_: SecurityException) {
            return PhoneTools.Result.failure("Android didn’t let OpenCode change the ${call.stream} volume while Do Not Disturb is on.")
        }
        val now = percent(audio, stream)
        val name = call.stream.replaceFirstChar { it.uppercase() }
        val text = if (now == 0) "$name volume is now 0% (muted)." else "$name volume is now $now%."
        val details = mapOf("volume" to volumes(audio), "ringer" to JsonPrimitive(ringer(audio)))
        return PhoneTools.Result(PhoneDetails.text(text, details), action = PhoneTools.Action.other("$name volume $now%"))
    }

    suspend fun doNotDisturb(on: Boolean): PhoneTools.Result {
        val context = dependency(AppContext)
        PhoneAccess.requireSpecial(context, PhoneAccess.doNotDisturb)?.let { return it }
        val notifications = context.getSystemService(NotificationManager::class.java)
            ?: return PhoneTools.Result.failure("This phone’s Do Not Disturb can’t be changed by apps.")
        // On, the user's own Do Not Disturb rules apply; off lets everything through.
        val filter = if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        notifications.setInterruptionFilter(filter)
        var current = notifications.currentInterruptionFilter
        if (isDoNotDisturbOn(current) != on) {
            delay(150)
            current = notifications.currentInterruptionFilter
        }
        val details = mapOf("lets_through" to JsonPrimitive(interruptions(current)))
        if (isDoNotDisturbOn(current) != on) {
            val reason = if (!on) {
                "Do Not Disturb is still on: Android only lets OpenCode turn off Do Not Disturb that OpenCode turned on itself. The user can turn it off in Quick Settings."
            } else {
                "Do Not Disturb didn’t turn on."
            }
            return PhoneTools.Result.failure(PhoneDetails.text(reason, details))
        }
        val text = if (on) "Do Not Disturb is on." else "Do Not Disturb is off."
        return PhoneTools.Result(PhoneDetails.text(text, details), action = PhoneTools.Action.other(text.removeSuffix(".")))
    }

    private fun isDoNotDisturbOn(filter: Int): Boolean =
        filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    /**
     * Everything the phone tells apps about itself without asking: battery, power, network,
     * radios, sound, Do Not Disturb, alarms, display, storage, the device and the time. The Wi‑Fi
     * name only when location permission is already granted (Android hides it otherwise).
     */
    fun status(): PhoneTools.Result {
        val context = dependency(AppContext)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charger = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
            else -> null
        }
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val health = when (battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failing"
            else -> null
        }
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val batteries = context.getSystemService(BatteryManager::class.java)
        val untilFull = batteries?.computeChargeTimeRemaining() ?: -1L

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        val network = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "none"
        }
        val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val carrier = runCatching { telephony?.networkOperatorName }.getOrNull()
        val signal = runCatching { telephony?.signalStrength?.level }.getOrNull()
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val zone = ZoneId.systemDefault()
        val autoBrightness = PhoneDisplay.autoBrightness(context)
        val storage = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val json = mapOf(
            "battery_percent" to if (level >= 0 && scale > 0) JsonPrimitive((level * 100f / scale).roundToInt()) else null,
            "charging" to JsonPrimitive(charging),
            "charger" to PhoneDetails.string(charger),
            "full_in_minutes" to if (charging && untilFull > 0) JsonPrimitive((untilFull / 60_000).toInt()) else null,
            "battery_health" to PhoneDetails.string(health),
            "battery_temperature_c" to if (tenths != Int.MIN_VALUE) JsonPrimitive(tenths / 10.0) else null,
            "battery_saver" to JsonPrimitive(context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true),
            "network" to JsonPrimitive(network),
            "online" to JsonPrimitive(online),
            "metered" to JsonPrimitive(connectivity?.isActiveNetworkMetered == true),
            "wifi_on" to context.getSystemService(WifiManager::class.java)?.isWifiEnabled?.let { JsonPrimitive(it) },
            "wifi_name" to PhoneDetails.string(wifiName(context, network)),
            "carrier" to PhoneDetails.string(carrier),
            "signal_bars" to if (signal != null && telephony?.phoneType != TelephonyManager.PHONE_TYPE_NONE) JsonPrimitive("$signal of 4") else null,
            "airplane_mode" to JsonPrimitive(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1),
            "bluetooth_on" to bluetoothOn(context)?.let { JsonPrimitive(it) },
            "audio_devices" to audio?.let { audioDevices(it) },
            "location_on" to context.getSystemService(LocationManager::class.java)?.isLocationEnabled?.let { JsonPrimitive(it) },
            "do_not_disturb" to JsonPrimitive(
                notifications?.currentInterruptionFilter.let {
                    it != null && it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                },
            ),
            "interruptions" to notifications?.let { JsonPrimitive(interruptions(it.currentInterruptionFilter)) },
            "ringer" to audio?.let { JsonPrimitive(ringer(it)) },
            "volume" to audio?.let { volumes(it) },
            "next_alarm" to nextAlarm(),
            "screen_brightness_percent" to PhoneDisplay.brightnessPercent(context)?.let { JsonPrimitive(it) },
            "auto_brightness" to autoBrightness?.let { JsonPrimitive(it) },
            "auto_rotate" to PhoneDisplay.autoRotate(context)?.let { JsonPrimitive(it) },
            "screen_timeout_seconds" to PhoneDisplay.timeoutValue(context),
            "storage_free_gb" to storage?.let { JsonPrimitive(gigabytes(it.availableBytes)) },
            "storage_total_gb" to storage?.let { JsonPrimitive(gigabytes(it.totalBytes)) },
            "device" to JsonPrimitive("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"),
            "android_version" to JsonPrimitive(Build.VERSION.RELEASE),
            "local_time" to JsonPrimitive(PhoneDetails.minute.format(ZonedDateTime.now(zone))),
            "timezone" to JsonPrimitive(zone.id),
        )
        return PhoneTools.Result(PhoneDetails.json(json))
    }

    /** The phone's next alarm and the app that set it, as Android reports it (any app's alarm clock). */
    fun nextAlarm(): JsonElement? {
        val context = dependency(AppContext)
        val next = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        val creator = next.showIntent?.creatorPackage
        val app = creator?.let { PhoneLaunch.appName(it) }
        return JsonObject(
            PhoneDetails.compact(
                mapOf(
                    "time" to JsonPrimitive(PhoneDetails.local(next.triggerTime)),
                    "app" to PhoneDetails.string(app),
                ),
            ),
        )
    }

    internal fun volumes(audio: AudioManager): JsonObject = JsonObject(
        mapOf(
            "media" to JsonPrimitive(percent(audio, AudioManager.STREAM_MUSIC)),
            "ring" to JsonPrimitive(percent(audio, AudioManager.STREAM_RING)),
            "notification" to JsonPrimitive(percent(audio, AudioManager.STREAM_NOTIFICATION)),
            "alarm" to JsonPrimitive(percent(audio, AudioManager.STREAM_ALARM)),
            "call" to JsonPrimitive(percent(audio, AudioManager.STREAM_VOICE_CALL)),
        ),
    )

    internal fun ringer(audio: AudioManager): String = when (audio.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }

    private fun interruptions(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority only"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms only"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "nothing"
        else -> "everything"
    }

    /** Headphones, speakers and car audio the phone is playing to (names as the devices report them). */
    private fun audioDevices(audio: AudioManager): JsonArray {
        val devices = mutableListOf<JsonPrimitive>()
        val seen = mutableSetOf<String>()
        for (device in audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            val kind = when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "bluetooth"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
                AudioDeviceInfo.TYPE_HDMI -> "hdmi"
                else -> null
            } ?: continue
            val name = device.productName?.toString()?.trim().orEmpty()
            val shown = if (name.isEmpty()) kind else "$name ($kind)"
            if (seen.add(shown)) devices += JsonPrimitive(shown)
        }
        return JsonArray(devices)
    }

    private fun bluetoothOn(context: android.content.Context): Boolean? =
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull()

    @Suppress("DEPRECATION")
    private fun wifiName(context: android.content.Context, network: String): String? {
        if (network != "wifi") return null
        if (!PhoneAccess.granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val ssid = runCatching { context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }.getOrNull() ?: return null
        val name = ssid.trim('"')
        if (name.isEmpty() || name == "<unknown ssid>") return null
        return name
    }

    private fun gigabytes(bytes: Long): Double = (bytes / 100_000_000L) / 10.0

    fun settings(panel: String): PhoneTools.Result {
        val context = dependency(AppContext)
        val name = settingsName(panel)
        val intents = when (panel) {
            "wifi" -> listOf(Intent(Settings.Panel.ACTION_WIFI))
            "internet" -> listOf(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            "nfc" -> listOf(Intent(Settings.Panel.ACTION_NFC))
            "volume" -> listOf(Intent(Settings.Panel.ACTION_VOLUME))
            "bluetooth" -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "airplane_mode" -> listOf(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            "location" -> listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            "sound" -> listOf(Intent(Settings.ACTION_SOUND_SETTINGS))
            "display" -> listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            "battery" -> listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            "date_time" -> listOf(Intent(Settings.ACTION_DATE_SETTINGS))
            "accessibility" -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "opencode" -> listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            // Hotspot has no public screen: tethering where the phone has it, else the network settings.
            "hotspot" -> listOf(Intent("android.settings.TETHER_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            else -> listOf(Intent(Settings.ACTION_SETTINGS))
        }
        for ((index, intent) in intents.withIndex()) {
            when (PhoneLaunch.start(intent)) {
                PhoneLaunch.Launch.started -> {
                    if (panel == "all") {
                        return PhoneTools.Result("Opened Settings. The user can change it there.", action = PhoneTools.Action.other("Opened Settings"))
                    }
                    val opened = if (panel == "hotspot" && index > 0) "network" else name
                    return PhoneTools.Result(
                        PhoneDetails.text("Opened the $opened settings. The user can change it there.", settingState(panel)),
                        action = PhoneTools.Action.other("Opened ${opened.replaceFirstChar { it.uppercase() }} settings"),
                    )
                }
                PhoneLaunch.Launch.blocked -> return PhoneTools.Result.failure(PhoneTools.BLOCKED)
                PhoneLaunch.Launch.noApp -> continue
            }
        }
        return PhoneTools.Result.failure("This phone has no $name settings.")
    }

    /** How the setting stands now, where Android lets an app read it, so the session can tell the user. */
    private fun settingState(panel: String): Map<String, JsonElement?> {
        val context = dependency(AppContext)
        return when (panel) {
            "wifi" -> mapOf("wifi_on" to context.getSystemService(WifiManager::class.java)?.isWifiEnabled?.let { JsonPrimitive(it) })
            "bluetooth" -> mapOf("bluetooth_on" to bluetoothOn(context)?.let { JsonPrimitive(it) })
            "airplane_mode" -> mapOf(
                "airplane_mode" to JsonPrimitive(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1),
            )
            "location" -> mapOf("location_on" to context.getSystemService(LocationManager::class.java)?.isLocationEnabled?.let { JsonPrimitive(it) })
            "nfc" -> mapOf(
                "nfc_on" to runCatching { android.nfc.NfcAdapter.getDefaultAdapter(context)?.isEnabled }.getOrNull()?.let { JsonPrimitive(it) },
            )
            "volume", "sound" -> {
                val audio = context.getSystemService(AudioManager::class.java)
                mapOf("volume" to audio?.let { volumes(it) }, "ringer" to audio?.let { JsonPrimitive(ringer(it)) })
            }
            else -> emptyMap()
        }
    }

    fun copy(text: String): PhoneTools.Result {
        val clipboard = dependency(AppContext).getSystemService(ClipboardManager::class.java)
            ?: return PhoneTools.Result.failure("The phone couldn’t do that.")
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenCode", text))
        val count = text.codePointCount(0, text.length)
        val characters = if (count == 1) "1 character" else "$count characters"
        return PhoneTools.Result("Copied $characters to the phone’s clipboard.", action = PhoneTools.Action.other("Copied to the clipboard"))
    }

    private fun stream(name: String): Int = when (name) {
        "ring" -> AudioManager.STREAM_RING
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "alarm" -> AudioManager.STREAM_ALARM
        "call" -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC
    }

    private fun percent(audio: AudioManager, stream: Int): Int {
        val max = audio.getStreamMaxVolume(stream)
        if (max <= 0) return 0
        return (audio.getStreamVolume(stream) * 100f / max).roundToInt()
    }

    private fun settingsName(panel: String): String = when (panel) {
        "wifi" -> "Wi‑Fi"
        "bluetooth" -> "Bluetooth"
        "internet" -> "internet"
        "airplane_mode" -> "airplane mode"
        "nfc" -> "NFC"
        "location" -> "location"
        "hotspot" -> "hotspot"
        "volume" -> "volume"
        "sound" -> "sound"
        "display" -> "display"
        "battery" -> "battery"
        "date_time" -> "date and time"
        "accessibility" -> "accessibility"
        "opencode" -> "OpenCode app"
        else -> "phone’s"
    }
}
