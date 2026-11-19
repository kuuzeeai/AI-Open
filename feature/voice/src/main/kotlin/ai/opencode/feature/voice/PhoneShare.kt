package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * `share` and `open_link` (Android only): hand text to another app through Android's share
 * (`ACTION_SEND`, text/plain), and open web and app links in their app or the browser. The user
 * finishes in that app, so neither shows a confirm card. Nothing here is logged.
 */
object PhoneShare {
    /** The longest text `share` hands over; Android drops intents much bigger than this. */
    const val MAX_TEXT = 100_000

    private const val MAX_LINK = 8_192
    private val SCHEME = Regex("^[a-z][a-z0-9+.\\-]*$")

    /** Schemes that could name a component, read the phone's files or run script. */
    private val refusedSchemes = setOf(
        "intent", "android-app", "file", "content", "javascript", "vbscript", "data", "blob", "about",
        "opencode", "opencode-ios",
    )

    /** One app's share screen, as Android lists it. */
    private data class Target(val appLabel: String, val activityLabel: String, val packageName: String, val className: String)

    /** Why [raw] can't be opened by `open_link`, or null when it can. */
    fun linkProblem(raw: String): String? {
        if (raw.length > MAX_LINK) return "url is too long."
        val scheme = raw.substringBefore(":", "").lowercase()
        if (!SCHEME.matches(scheme)) return "url must start with its scheme, such as https://example.com or spotify:…"
        if (scheme == "tel" || scheme == "telprompt" || scheme == "voicemail") {
            return "open_link doesn’t open phone numbers: use call with the number instead."
        }
        if (scheme == "sms" || scheme == "smsto" || scheme == "mms" || scheme == "mmsto") {
            return "open_link doesn’t open text messages: use send_text instead (with draft to open it filled in)."
        }
        if (scheme == "mailto") return "open_link doesn’t open email: use draft_email instead."
        if (scheme in refusedSchemes) return "“$scheme:” links can’t be opened: only web links (https) and app links are allowed."
        val url = try {
            URI(raw)
        } catch (_: Exception) {
            return "url must be a whole link, such as https://example.com, with no spaces."
        }
        if ((scheme == "https" || scheme == "http") && url.host.isNullOrEmpty()) {
            return "url must be a whole link, such as https://example.com."
        }
        return null
    }

    /** A Google Maps link to a point, which opens in Maps or any browser. */
    fun mapsUrl(latitude: Double, longitude: Double): String =
        "https://www.google.com/maps/search/?api=1&query=${coordinate(latitude)},${coordinate(longitude)}"

    /** “37.422”, “-122.084”: a coordinate with up to six decimals and no exponent. */
    private fun coordinate(value: Double): String {
        val text = String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')
        if (text == "-0") return "0"
        return text
    }

    fun share(call: PhoneTools.Call.Share): PhoneTools.Result {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, call.text)
        if (call.subject != null) send.putExtra(Intent.EXTRA_SUBJECT, call.subject)
        if (call.app == null) return shareSheet(send)

        val targets = targets()
        if (targets.isEmpty()) return PhoneTools.Result.failure("No app on this phone can take a share.")

        // An app is found by its own name (“Tesla”) or by its share screen's (“Send to Kindle”).
        val apps = mutableListOf<AppMatcher.App>()
        for (target in targets) {
            apps += AppMatcher.App(target.appLabel, target.packageName)
            if (target.activityLabel != target.appLabel) apps += AppMatcher.App(target.activityLabel, target.packageName)
        }
        return when (val match = AppMatcher.match(call.app, apps)) {
            is AppMatcher.Result.Found -> shareTo(send, pick(targets, match.app), call.app, match.app, apps)

            is AppMatcher.Result.Several -> PhoneTools.Result.failure(
                PhoneDetails.text(
                    "Several apps that take a share match “${call.app}”: ${match.apps.joinToString(", ") { it.label }}. Ask the user which one.",
                    mapOf("matches" to JsonArray(match.apps.map { JsonPrimitive("${it.label} (${it.packageName})") })),
                ),
            )

            AppMatcher.Result.None -> {
                val names = appNames(targets)
                val shown = names.take(40)
                val more = if (names.size > shown.size) ", +${names.size - shown.size} more" else ""
                PhoneTools.Result.failure(
                    "No app called “${call.app}” can take a share on this phone, so nothing was opened. " +
                        "These can: ${shown.joinToString(", ")}$more. Use one of them, ask the user, or leave out app to open the share sheet.",
                )
            }
        }
    }

    fun openLink(call: PhoneTools.Call.OpenLink): PhoneTools.Result {
        val context = dependency(AppContext)
        val packages = context.packageManager
        // Browsable: only screens that take links from a browser, as a tapped link would.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(call.url).normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE)
        val handler = packages.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        var app: String? = null
        var packageName: String? = null
        // Android's “Open with” chooser stands in when several apps could; name none then.
        if (handler != null && handler.activityInfo.packageName != "android") {
            app = handler.loadLabel(packages).toString()
            packageName = handler.activityInfo.packageName
        }
        if (packageName == context.packageName) {
            return PhoneTools.Result.failure("That link opens in OpenCode itself, so it wasn’t opened.")
        }
        return when (PhoneLaunch.start(intent).status) {
            PhoneLaunch.Status.started -> {
                if (app == null) {
                    PhoneTools.Result(
                        "Opened the link. Android asked the user which app to open it with.",
                        action = PhoneTools.Action.other("Opened a link"),
                    )
                } else {
                    PhoneTools.Result(
                        PhoneDetails.text(
                            "Opened the link in $app.",
                            mapOf("app" to JsonPrimitive(app), "package" to JsonPrimitive(packageName)),
                        ),
                        action = PhoneTools.Action(PhoneTools.Action.Kind.app, app, packageName = packageName),
                    )
                }
            }
            PhoneLaunch.Status.blocked -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
            PhoneLaunch.Status.noApp -> PhoneTools.Result.failure("No app on this phone opens that link, so nothing was opened.")
        }
    }

    private fun shareSheet(send: Intent): PhoneTools.Result {
        val chooser = Intent.createChooser(send, null)
        return when (PhoneLaunch.start(chooser).status) {
            PhoneLaunch.Status.started -> PhoneTools.Result(
                "Opened the share sheet with the text filled in. The user picks the app and sends it there.",
                action = PhoneTools.Action.other("Opened the share sheet"),
            )
            PhoneLaunch.Status.blocked -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
            PhoneLaunch.Status.noApp -> PhoneTools.Result.failure("No app on this phone can take a share.")
        }
    }

    private fun shareTo(send: Intent, target: Target, query: String, matched: AppMatcher.App, apps: List<AppMatcher.App>): PhoneTools.Result {
        send.setClassName(target.packageName, target.className)
        val exact = AppMatcher.isExact(query, matched)
        return when (PhoneLaunch.start(send).status) {
            PhoneLaunch.Status.started -> PhoneTools.Result(
                PhoneDetails.text(
                    "Opened ${target.appLabel} with the text filled in. The user finishes sending it there.",
                    mapOf(
                        "app" to JsonPrimitive(target.appLabel),
                        "package" to JsonPrimitive(target.packageName),
                        "share_screen" to if (target.activityLabel != target.appLabel) JsonPrimitive(target.activityLabel) else null,
                        "match" to JsonPrimitive(if (exact) "exact" else "close"),
                        "close_alternatives" to if (exact) {
                            null
                        } else {
                            JsonArray(AppMatcher.alternatives(query, apps, matched).map { JsonPrimitive(it.label) })
                        },
                    ),
                ),
                action = PhoneTools.Action(PhoneTools.Action.Kind.app, target.appLabel, packageName = target.packageName),
            )
            PhoneLaunch.Status.blocked -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
            PhoneLaunch.Status.noApp -> PhoneTools.Result.failure("${target.appLabel} couldn’t be opened.")
        }
    }

    /** Every other app's text share screens, in Android's order. */
    private fun targets(): List<Target> {
        val context = dependency(AppContext)
        val packages = context.packageManager
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        val targets = mutableListOf<Target>()
        for (info in packages.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)) {
            val activity = info.activityInfo
            if (activity.packageName == context.packageName) continue
            if (!activity.exported) continue
            val appLabel = activity.applicationInfo.loadLabel(packages).toString()
            val activityLabel = info.loadLabel(packages).toString()
            targets += Target(appLabel, activityLabel, activity.packageName, activity.name)
        }
        return targets
    }

    /**
     * The share screen for a match: the one named as matched, else the one named like its app,
     * else the app's first.
     */
    private fun pick(targets: List<Target>, matched: AppMatcher.App): Target {
        var first: Target? = null
        var namedLikeApp: Target? = null
        for (target in targets) {
            if (target.packageName != matched.packageName) continue
            if (target.activityLabel == matched.label) return target
            if (first == null) first = target
            if (namedLikeApp == null && target.activityLabel == target.appLabel) namedLikeApp = target
        }
        return namedLikeApp ?: first ?: targets[0]
    }

    /** The apps' names, each once, alphabetically. */
    private fun appNames(targets: List<Target>): List<String> {
        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (target in targets) {
            if (seen.add(target.appLabel.lowercase())) names += target.appLabel
        }
        names.sortBy { it.lowercase() }
        return names
    }
}
