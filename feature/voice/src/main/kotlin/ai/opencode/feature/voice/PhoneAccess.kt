package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the assistant's phone tools may use beyond the basics (Android only): runtime permissions
 * the phone asks for, and the special accesses only a settings screen grants. Settings ›
 * Assistant › Permissions lists them.
 */
enum class PhoneAccess(val title: String, val permissions: List<String>) {
    contacts("Contacts", listOf(Manifest.permission.READ_CONTACTS)),
    calls("Phone Calls", listOf(Manifest.permission.CALL_PHONE)),
    answerCalls("Answer Calls", listOf(Manifest.permission.ANSWER_PHONE_CALLS)),
    callLog("Call Log", listOf(Manifest.permission.READ_CALL_LOG)),
    texts("Texts", listOf(Manifest.permission.SEND_SMS)),
    readTexts("Read Texts", listOf(Manifest.permission.READ_SMS)),
    calendar("Calendar", listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
    location("Location", listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)),
    camera("Camera", listOf(Manifest.permission.CAMERA)),
    notifications("Notifications", emptyList()),
    doNotDisturb("Do Not Disturb", emptyList()),
    systemSettings("Modify System Settings", emptyList()),
    ;

    /** Granted only on a settings screen, never by a dialog. */
    val isSpecial: Boolean get() = permissions.isEmpty()

    /**
     * Google Play allows these SMS and Call Log permissions only for the phone's digital
     * assistant, so the phone uses and asks for them only while OpenCode is it.
     */
    val needsAssistant: Boolean get() = this == texts || this == readTexts || this == callLog

    companion object {
        /** Runtime permissions the user turned down for good: the phone opens OpenCode's app settings instead of asking. */
        private val blockedKey = SharedKey.appStorage("assistant-blocked-permissions", "")

        fun isGranted(context: Context, access: PhoneAccess): Boolean = when (access) {
            notifications -> context.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(ComponentName(context, PhoneNotificationListener::class.java)) == true
            doNotDisturb -> context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
            systemSettings -> Settings.System.canWrite(context)
            // Coarse location is enough to answer; fine only sharpens it.
            location -> granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> {
                var all = true
                for (permission in access.permissions) {
                    if (!granted(context, permission)) all = false
                }
                all
            }
        }

        fun granted(context: Context, permission: String): Boolean =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        /** Whether OpenCode is the phone's digital assistant (the assistant role). */
        fun isAssistant(context: Context): Boolean {
            val roles = context.getSystemService(RoleManager::class.java) ?: return false
            return roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }

        /**
         * Null when [permissions] are granted. Otherwise the phone asks for them without waiting
         * (or opens OpenCode's app settings once Android won't ask any more) and this is the
         * result to answer with: [PhoneTools.needsPermission] or [PhoneTools.permissionBlocked].
         */
        fun require(context: Context, permissions: List<String>, what: String): PhoneTools.Result? {
            val missing = mutableListOf<String>()
            for (permission in permissions) {
                if (!granted(context, permission)) missing += permission
            }
            if (missing.isEmpty()) return null
            if (isBlocked(missing)) {
                return when (PhoneLaunch.start(appDetails(context))) {
                    PhoneLaunch.Launch.started -> PhoneTools.Result.failure(PhoneTools.permissionBlocked(what))
                    else -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
                }
            }
            return when (PhoneLaunch.start(PhonePermissionActivity.intent(context, missing))) {
                PhoneLaunch.Launch.started -> PhoneTools.Result.failure(PhoneTools.needsPermission(what))
                else -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
            }
        }

        /**
         * For tools that fall back instead of failing (`call` opens the dialer, `send_text` a
         * draft): asks for [permissions] for next time, unless Android won't show the dialog again.
         */
        fun askForNextTime(context: Context, permissions: List<String>) {
            if (isBlocked(permissions)) return
            PhoneLaunch.start(PhonePermissionActivity.intent(context, permissions))
        }

        /** Null with the access on; otherwise opens its settings screen and answers [PhoneTools.needsAccess]. */
        fun requireSpecial(context: Context, access: PhoneAccess): PhoneTools.Result? {
            if (isGranted(context, access)) return null
            val name = when (access) {
                doNotDisturb -> "Do Not Disturb access"
                systemSettings -> "access to modify system settings"
                else -> "notification access"
            }
            return when (PhoneLaunch.start(specialSettings(context, access))) {
                PhoneLaunch.Launch.started -> PhoneTools.Result.failure(PhoneTools.needsAccess(name))
                else -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
            }
        }

        /**
         * Settings › Assistant › Permissions rows: the dialog, OpenCode's app settings (once Android won't ask,
         * or to turn an allowed permission off), or the special access screen.
         */
        fun allow(context: Context, access: PhoneAccess) {
            val intent = if (access.isSpecial) {
                specialSettings(context, access)
            } else if (access.needsAssistant && !isAssistant(context)) {
                // Not asked for until OpenCode is the assistant: open where the user picks it.
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            } else if (isBlocked(access.permissions) || isGranted(context, access)) {
                appDetails(context)
            } else {
                PhonePermissionActivity.intent(context, access.permissions)
            }
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                AppLog.app.notice("Couldn’t open the setting for phone access ${access.name}")
            }
        }

        private fun isBlocked(permissions: List<String>): Boolean {
            val blocked = blockedPermissions()
            for (permission in permissions) {
                if (permission in blocked) return true
            }
            return false
        }

        internal fun blockedPermissions(): Set<String> {
            val raw = runCatching { shared(blockedKey).value }.getOrDefault("")
            val set = mutableSetOf<String>()
            for (part in raw.split(',')) {
                if (part.isNotBlank()) set += part
            }
            return set
        }

        internal fun setBlocked(permissions: Set<String>) {
            shared(blockedKey).set(permissions.sorted().joinToString(","))
        }

        private fun appDetails(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

        private fun specialSettings(context: Context, access: PhoneAccess): Intent = if (access == doNotDisturb) {
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        } else if (access == systemSettings) {
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", context.packageName, null))
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, PhoneNotificationListener::class.java).flattenToString(),
                )
        }
    }
}

/** Settings › Assistant › Permissions: whether each access is on, and what a row opens. Inert under tests. */
class PhoneAccessClient(
    val isGranted: (PhoneAccess) -> Boolean,
    val allow: (PhoneAccess) -> Unit,
) {
    companion object Key : DependencyKey<PhoneAccessClient> {
        override val liveValue: PhoneAccessClient by lazy {
            PhoneAccessClient(
                isGranted = { PhoneAccess.isGranted(dependency(AppContext), it) },
                allow = { PhoneAccess.allow(dependency(AppContext), it) },
            )
        }
        override val testValue: PhoneAccessClient = PhoneAccessClient(isGranted = { false }, allow = {})
    }
}

/**
 * Asks for a phone tool's runtime permissions over whatever's on screen, then goes. The tool
 * call doesn't wait for it: the model tries again when the user says they've allowed it.
 */
class PhonePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
        if (permissions.isNullOrEmpty()) {
            finish()
            return
        }
        if (savedInstanceState == null) requestPermissions(permissions, REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val blocked = PhoneAccess.blockedPermissions().toMutableSet()
        var granted = 0
        for (index in permissions.indices) {
            val permission = permissions[index]
            val isGranted = grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
            if (isGranted) {
                granted += 1
                blocked -= permission
            } else if (!shouldShowRequestPermissionRationale(permission)) {
                // Android won't show the dialog for it again.
                blocked += permission
            }
        }
        PhoneAccess.setBlocked(blocked)
        AppLog.app.info { "Phone access asked count=${permissions.size} granted=$granted" }
        finish()
    }

    companion object {
        private const val EXTRA_PERMISSIONS = "ai.opencode.extra.PERMISSIONS"
        private const val REQUEST = 1

        fun intent(context: Context, permissions: List<String>): Intent =
            Intent(context, PhonePermissionActivity::class.java)
                .putExtra(EXTRA_PERMISSIONS, permissions.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}

/**
 * Asks Android to unlock the phone over the lock screen, for a screen a phone tool opened behind
 * it, then goes: Android shows the PIN or pattern, and the screen is there once the user unlocks.
 */
class PhoneUnlockActivity : Activity() {
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        asked = savedInstanceState != null
    }

    // Asking before this window has settled over the lock screen gets the request cancelled.
    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        if (asked) return
        asked = true
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            finish()
            return
        }
        AppLog.app.notice("Phone tool asked to unlock")
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            },
        )
    }
}

/**
 * Starts other apps' screens for the phone tools. Android blocks activity starts from the
 * background without an error, so this checks first: holding the assistant role lets OpenCode
 * start them while it's in the background, and an app on screen always may.
 */
internal object PhoneLaunch {
    enum class Launch { started, blocked, noApp }

    /** How many screens the tools have opened behind the lock screen, so a result can say so. */
    private val behindLock = AtomicInteger(0)

    val opensBehindLock: Int get() = behindLock.get()

    fun start(intent: Intent): Launch {
        val context = dependency(AppContext)
        if (!mayStartActivities(context)) return Launch.blocked
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (showsScreen(intent)) opened(context)
            Launch.started
        } catch (_: ActivityNotFoundException) {
            Launch.noApp
        } catch (_: SecurityException) {
            Launch.noApp
        }
    }

    /**
     * A tool opened a screen: when the phone is locked it sits behind the lock screen, so this
     * asks Android to unlock ([PhoneUnlockActivity]).
     */
    fun opened(context: Context) {
        if (!isLocked(context)) return
        behindLock.incrementAndGet()
        val unlock = Intent(context, PhoneUnlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            context.startActivity(unlock)
        } catch (_: RuntimeException) {
            AppLog.app.error("Phone tool couldn’t ask to unlock")
        }
    }

    /** Whether the lock screen is up. */
    fun isLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /**
     * Whether [intent] puts a screen in front: not the Clock's skip-UI timers and alarms, and not
     * a phone call, whose screen shows over the lock screen.
     */
    private fun showsScreen(intent: Intent): Boolean {
        if (intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) return false
        return intent.action != Intent.ACTION_CALL
    }

    /** The name of the app [intent] opens, such as “Gmail”; null when none does. */
    fun handlerName(intent: Intent): String? {
        val context = dependency(AppContext)
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        // The chooser stands in when several apps could; name none then.
        if (info.activityInfo.packageName == "android") return null
        return info.loadLabel(context.packageManager).toString()
    }

    /** The name of the app with [packageName], such as “Spotify”; the package name when Android won't say. */
    fun appName(packageName: String): String {
        val packages = dependency(AppContext).packageManager
        return runCatching { packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)
    }

    /** Whether Android lets OpenCode open a screen now: as the assistant, or while it's in front. */
    internal fun mayStartActivities(context: Context): Boolean {
        val roles = context.getSystemService(RoleManager::class.java)
        if (roles != null && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) return true
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
