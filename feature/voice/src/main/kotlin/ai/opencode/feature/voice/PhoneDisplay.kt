package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonPrimitive

/**
 * `set_ringer` and `set_display` for [PhoneToolsClient] (Android only). Every answer reads the
 * setting back, so it says what the phone did rather than what was asked.
 */
internal object PhoneDisplay {
    const val MIN_TIMEOUT_SECONDS = 15
    const val MAX_TIMEOUT_SECONDS = 1_800

    /** Silent needs Do Not Disturb access; leaving it may too, which Android says by refusing. */
    fun ringer(mode: String): PhoneTools.Result {
        val context = dependency(AppContext)
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return PhoneTools.Result.failure("This phone’s ringer can’t be changed by apps.")
        val wanted = when (mode) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        if (mode == "silent") {
            PhoneAccess.requireSpecial(context, PhoneAccess.doNotDisturb)?.let { return it }
        }
        try {
            audio.ringerMode = wanted
        } catch (_: SecurityException) {
            PhoneAccess.requireSpecial(context, PhoneAccess.doNotDisturb)?.let { return it }
            return PhoneTools.Result.failure("Android didn’t let OpenCode change the ringer.")
        }
        val now = PhoneDevice.ringer(audio)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val filter = notifications?.currentInterruptionFilter
        val dnd = filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val details = mapOf(
            "ringer" to JsonPrimitive(now),
            "do_not_disturb" to JsonPrimitive(dnd),
            "volume" to PhoneDevice.volumes(audio),
        )
        if (now != mode) {
            val hint = if (dnd) " Do Not Disturb is on, which can hold the ringer; turning it off may help." else ""
            return PhoneTools.Result.failure(PhoneDetails.text("The ringer is still on $now.$hint", details))
        }
        val text = when (now) {
            "silent" -> "The phone is on silent."
            "vibrate" -> "The phone is on vibrate."
            else -> "The ringer is on."
        }
        val chip = when (now) {
            "silent" -> "Silent"
            "vibrate" -> "Vibrate"
            else -> "Ringer on"
        }
        return PhoneTools.Result(PhoneDetails.text(text, details), action = PhoneTools.Action.other(chip))
    }

    /** Brightness, auto-rotate and screen timeout, which need Modify System Settings. */
    fun display(call: PhoneTools.Call.SetDisplay): PhoneTools.Result {
        val context = dependency(AppContext)
        PhoneAccess.requireSpecial(context, PhoneAccess.systemSettings)?.let { return it }
        val resolver = context.contentResolver
        val said = mutableListOf<String>()
        val chips = mutableListOf<String>()
        try {
            if (call.autoBrightness) {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            } else if (call.brightness != null) {
                // Auto brightness would move it again straight away.
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                val level = (call.brightness * 255f / 100).roundToInt().coerceIn(1, 255)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
            }
            if (call.autoRotate != null) {
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, if (call.autoRotate) 1 else 0)
            }
            if (call.timeoutSeconds != null) {
                Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, call.timeoutSeconds * 1000)
            }
        } catch (_: SecurityException) {
            return PhoneTools.Result.failure("Android didn’t let OpenCode change that setting.")
        } catch (_: IllegalArgumentException) {
            return PhoneTools.Result.failure("Android didn’t let OpenCode change that setting.")
        }

        val auto = autoBrightness(context)
        val percent = brightnessPercent(context)
        val rotate = autoRotate(context)
        val timeout = timeoutSeconds(context)
        var missed = false
        if (call.autoBrightness) {
            if (auto == true) {
                said += "Brightness is automatic."
                chips += "Auto brightness"
            } else {
                missed = true
            }
        } else if (call.brightness != null) {
            if (auto == false && percent != null) {
                said += "Brightness is now $percent%."
                chips += "Brightness $percent%"
            } else {
                missed = true
            }
        }
        if (call.autoRotate != null) {
            if (rotate == call.autoRotate) {
                said += if (rotate) "Auto-rotate is on." else "Auto-rotate is off."
                chips += if (rotate) "Auto-rotate on" else "Auto-rotate off"
            } else {
                missed = true
            }
        }
        if (call.timeoutSeconds != null) {
            if (timeout == call.timeoutSeconds) {
                said += "The screen now turns off after ${spokenSeconds(timeout)}."
                chips += "Screen timeout ${spokenSeconds(timeout)}"
            } else {
                missed = true
            }
        }
        val details = mapOf(
            "screen_brightness_percent" to percent?.let { JsonPrimitive(it) },
            "auto_brightness" to auto?.let { JsonPrimitive(it) },
            "auto_rotate" to rotate?.let { JsonPrimitive(it) },
            "screen_timeout_seconds" to timeout?.let { JsonPrimitive(it) },
        )
        if (missed) {
            val done = if (said.isEmpty()) "" else " " + said.joinToString(" ")
            return PhoneTools.Result.failure(PhoneDetails.text("The phone didn’t keep every change, so check the settings below.$done", details))
        }
        return PhoneTools.Result(PhoneDetails.text(said.joinToString(" "), details), action = PhoneTools.Action.other(chips.joinToString(", ")))
    }

    // Reading these needs no access, so phone_status gives them too.

    fun brightnessPercent(context: Context): Int? {
        val level = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull() ?: return null
        return (level * 100f / 255).roundToInt()
    }

    fun autoBrightness(context: Context): Boolean? {
        val mode = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrNull() ?: return null
        return mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }

    fun autoRotate(context: Context): Boolean? {
        val value = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION) }.getOrNull() ?: return null
        return value == 1
    }

    fun timeoutSeconds(context: Context): Int? {
        val millis = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull() ?: return null
        return millis / 1000
    }

    /** “30 seconds”, “2 minutes”, “1 minute 30 seconds”. */
    fun spokenSeconds(seconds: Int): String {
        val minutes = seconds / 60
        val rest = seconds % 60
        val parts = mutableListOf<String>()
        if (minutes > 0) parts += if (minutes == 1) "1 minute" else "$minutes minutes"
        if (rest > 0 || minutes == 0) parts += if (rest == 1) "1 second" else "$rest seconds"
        return parts.joinToString(" ")
    }
}
