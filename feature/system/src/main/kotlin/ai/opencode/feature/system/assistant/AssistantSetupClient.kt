package ai.opencode.feature.system.assistant

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android only: setting OpenCode up as the phone's assistant (Settings › Assistant). The
 * assistant role can't be requested by an app, so the page opens the system screens where
 * the person picks it, and checks the role and the side button again when they come back.
 */
class AssistantSetupClient(
    /** Whether OpenCode holds the assistant role. */
    val isAssistant: () -> Boolean,
    /** Opens the screen that picks the default assistant. */
    val openAssistantSettings: suspend () -> Unit,
    /** Opens the screen that sets what holding the side button does. */
    val openSideButtonSettings: suspend () -> Unit,
    /**
     * Whether holding the side button opens the assistant, from the phone's own setting; null
     * where the phone doesn't say (Samsung keeps Side Button in its own settings).
     */
    val sideButtonOpensAssistant: () -> Boolean?,
    /** Whether Use Text From Screen is on, which the assistant needs to read the screen. */
    val usesTextFromScreen: () -> Boolean,
) {
    companion object Key : DependencyKey<AssistantSetupClient> {
        override val liveValue: AssistantSetupClient by lazy {
            AssistantSetupClient(
                isAssistant = {
                    val context = dependency(AppContext)
                    val roles = context.getSystemService(RoleManager::class.java)
                    roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                },
                openAssistantSettings = {
                    withContext(Dispatchers.Main) {
                        openFirst(
                            "assistant settings",
                            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                            Intent(Settings.ACTION_SETTINGS),
                        )
                    }
                },
                openSideButtonSettings = {
                    withContext(Dispatchers.Main) {
                        val samsung = Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$FunctionKeySettingsActivity"))
                        val pixel = Intent("android.settings.ACTION_POWER_MENU_SETTINGS")
                        val candidates = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                            arrayOf(samsung, pixel)
                        } else {
                            arrayOf(pixel)
                        }
                        openFirst("side button settings", *candidates, Intent(Settings.ACTION_SETTINGS))
                    }
                },
                sideButtonOpensAssistant = {
                    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                        null
                    } else {
                        val behavior = longPressPowerBehavior(dependency(AppContext))
                        if (behavior == null) null else behavior == LONG_PRESS_POWER_ASSISTANT
                    }
                },
                usesTextFromScreen = {
                    val resolver = dependency(AppContext).contentResolver
                    try {
                        // Unset means on, as Android's assistant settings show it.
                        Settings.Secure.getInt(resolver, ASSIST_STRUCTURE_ENABLED, 1) != 0
                    } catch (_: SecurityException) {
                        true
                    }
                },
            )
        }

        override val testValue: AssistantSetupClient = AssistantSetupClient(
            isAssistant = { false },
            openAssistantSettings = {},
            openSideButtonSettings = {},
            sideButtonOpensAssistant = { null },
            usesTextFromScreen = { true },
        )

        /**
         * Whether a press opened the assistant by holding the side button: Android says so in
         * `invocation_type`; where it doesn't, a hardware assist press that no app, activity or
         * gesture asked for.
         */
        fun isSideButtonPress(args: Bundle?, showFlags: Int): Boolean {
            if (args != null && args.containsKey(INVOCATION_TYPE)) {
                return args.getInt(INVOCATION_TYPE) == INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS
            }
            val otherSources = VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE or
                VoiceInteractionSession.SHOW_SOURCE_APPLICATION or
                VoiceInteractionSession.SHOW_SOURCE_ACTIVITY or
                VoiceInteractionSession.SHOW_SOURCE_NOTIFICATION or
                VoiceInteractionSession.SHOW_SOURCE_AUTOMOTIVE_SYSTEM_UI
            val withAssist = showFlags and VoiceInteractionSession.SHOW_WITH_ASSIST != 0
            return withAssist && showFlags and otherSources == 0
        }

        /** `Settings.Global.POWER_BUTTON_LONG_PRESS` (hidden, readable by apps); unset falls back to the phone's default. */
        private fun longPressPowerBehavior(context: Context): Int? {
            try {
                return Settings.Global.getInt(context.contentResolver, POWER_BUTTON_LONG_PRESS)
            } catch (_: Settings.SettingNotFoundException) {
                // Not changed on this phone: its built-in default applies.
            } catch (_: SecurityException) {
                return null
            }
            val resources = Resources.getSystem()
            @SuppressLint("DiscouragedApi")
            val id = resources.getIdentifier("config_longPressOnPowerBehavior", "integer", "android")
            if (id == 0) return null
            return resources.getInteger(id)
        }

        private const val POWER_BUTTON_LONG_PRESS = "power_button_long_press"
        /** `PhoneWindowManager.LONG_PRESS_POWER_ASSISTANT`. */
        private const val LONG_PRESS_POWER_ASSISTANT = 5
        /** `Settings.Secure.ASSIST_STRUCTURE_ENABLED`: Use Text From Screen. */
        private const val ASSIST_STRUCTURE_ENABLED = "assist_structure_enabled"
        /** `AssistUtils.INVOCATION_TYPE_KEY` and its power button long press value. */
        private const val INVOCATION_TYPE = "invocation_type"
        private const val INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6

        /** Opens the first screen this phone has; logs which step it fell back to, never more. */
        private fun openFirst(what: String, vararg intents: Intent) {
            val context: Context = dependency(AppContext)
            for ((index, intent) in intents.withIndex()) {
                try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    AppLog.app.info { "Opened $what (option ${index + 1} of ${intents.size})" }
                    return
                } catch (_: ActivityNotFoundException) {
                    continue
                } catch (_: SecurityException) {
                    continue
                }
            }
            AppLog.app.notice("Couldn’t open $what")
        }
    }
}
