package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lets the assistant's session read, reply to, tap, snooze and dismiss the phone's notifications
 * (Android only), once the user turns on notification access for OpenCode. Nothing is read until
 * a tool asks, and never logged. Replies go through the app's own Reply button, as a watch's do.
 */
class PhoneNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    internal companion object {
        private const val MAX_TEXT = 500
        private const val MAX_TITLE = 200
        private const val MAX_MESSAGE = 300
        private const val MAX_MESSAGES = 5
        private const val MAX_ACTIONS = 5
        private const val GONE = "That notification is already gone."
        private const val RECONNECTING = "Notification access is reconnecting right now. Try again in a moment."
        private const val NOT_CLEARABLE = "That notification can’t be dismissed: it stays while its app is doing something (like playing music or a call)."

        /** How long a reply or a tap waits for the app to update or clear its notification. */
        private const val SETTLE_MILLIS = 3_000L
        private const val SETTLE_STEP_MILLIS = 250L

        @Volatile private var connected: PhoneNotificationListener? = null

        /**
         * Newest first: app and package, title, text and the longer text, sub-text, the
         * conversation's recent messages with their senders, time, category, whether it's ongoing,
         * its buttons and its key. Capped at the limit, with a count of the rest.
         */
        fun read(call: PhoneTools.Call.ReadNotifications): PhoneTools.Result {
            return withNotifications { context, _, active ->
                val sorted = active.sortedByDescending { it.postTime }
                val wanted = call.app?.lowercase()?.trim()
                val items = mutableListOf<JsonObject>()
                var total = 0
                for (posted in sorted) {
                    if (!shows(posted, context.packageName)) continue
                    val app = PhoneLaunch.appName(posted.packageName)
                    if (wanted != null && !app.lowercase().contains(wanted) && !posted.packageName.lowercase().contains(wanted)) continue
                    total += 1
                    if (items.size < call.limit) items += describe(posted, app)
                }
                val result = linkedMapOf<String, JsonElement>("notifications" to JsonArray(items))
                val more = PhoneDetails.more(total, call.limit)
                if (more != null) result["more_notifications"] = more
                val action = PhoneTools.Action.other("Read the notifications")
                PhoneTools.Result(JsonObject(result).toString(), action = action)
            }
        }

        private fun describe(posted: StatusBarNotification, app: String): JsonObject {
            val notification = posted.notification
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            val longer = when {
                big != null && big.trim() != text?.trim() -> big
                lines != null && lines.isNotEmpty() -> lines.joinToString("\n")
                else -> null
            }
            val actions = mutableListOf<JsonPrimitive>()
            for (button in notification.actions ?: emptyArray()) {
                val name = button.title?.toString()?.trim()
                if (!name.isNullOrEmpty() && actions.size < MAX_ACTIONS) actions += JsonPrimitive(name)
            }
            val details = mapOf(
                "key" to JsonPrimitive(posted.key),
                "app" to JsonPrimitive(app),
                "package" to JsonPrimitive(posted.packageName),
                "title" to PhoneDetails.string(PhoneDetails.cap(title, MAX_TITLE)),
                "text" to PhoneDetails.string(PhoneDetails.cap(text, MAX_TEXT)),
                "more_text" to PhoneDetails.string(PhoneDetails.cap(longer, MAX_TEXT)),
                "sub_text" to PhoneDetails.string(PhoneDetails.cap(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(), MAX_TITLE)),
                "conversation" to PhoneDetails.string(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()),
                "messages" to messages(extras),
                "time" to JsonPrimitive(PhoneDetails.local(posted.postTime)),
                "category" to PhoneDetails.string(notification.category),
                "ongoing" to if (posted.isOngoing) JsonPrimitive(true) else null,
                "can_dismiss" to if (!posted.isClearable) JsonPrimitive(false) else null,
                "actions" to JsonArray(actions),
                "can_reply" to if (replyButton(notification) != null) JsonPrimitive(true) else null,
            )
            return JsonObject(PhoneDetails.compact(details))
        }

        /** A messaging notification's last few messages, oldest first, each with who sent it. */
        private fun messages(extras: Bundle): JsonArray {
            val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java) ?: return JsonArray(emptyList())
            val messages = mutableListOf<JsonElement>()
            val start = (bundles.size - MAX_MESSAGES).coerceAtLeast(0)
            if (start > 0) messages += JsonPrimitive("+$start earlier")
            for (index in start until bundles.size) {
                val bundle = bundles[index] as? Bundle ?: continue
                val text = bundle.getCharSequence("text")?.toString() ?: continue
                val person = bundle.getParcelable("sender_person", Person::class.java)
                val sender = person?.name?.toString() ?: bundle.getCharSequence("sender")?.toString()
                val time = bundle.getLong("time", 0L)
                val message = mapOf(
                    "from" to PhoneDetails.string(sender ?: "me"),
                    "text" to JsonPrimitive(PhoneDetails.cap(text, MAX_MESSAGE) ?: ""),
                    "time" to if (time > 0) JsonPrimitive(PhoneDetails.local(time)) else null,
                )
                messages += JsonObject(PhoneDetails.compact(message))
            }
            return JsonArray(messages)
        }

        /** Clears the notification, or with a snooze hides it for that long. */
        fun dismiss(call: PhoneTools.Call.DismissNotification): PhoneTools.Result {
            val found = when (val lookup = lookUp(call.key)) {
                is Lookup.Found -> lookup
                is Lookup.Missing -> return lookup.result
            }
            val posted = found.posted
            if (!posted.isClearable) return PhoneTools.Result.failure(NOT_CLEARABLE)
            val app = PhoneLaunch.appName(posted.packageName)
            val title = title(posted.notification)
            val notification = if (title.isNullOrBlank()) "the $app notification" else "the $app notification “$title”"
            val minutes = call.snoozeMinutes
            if (minutes == null) {
                found.listener.cancelNotification(call.key)
                return PhoneTools.Result("Dismissed $notification.", action = PhoneTools.Action.other("Dismissed a notification"))
            }
            val millis = minutes * 60_000L
            found.listener.snoozeNotification(call.key, millis)
            val back = PhoneDetails.local(System.currentTimeMillis() + millis)
            return PhoneTools.Result(
                PhoneDetails.text(
                    "Snoozed $notification for ${PhoneNotifications.snoozeLength(minutes)}.",
                    mapOf("comes_back" to JsonPrimitive(back)),
                ),
                action = PhoneTools.Action.other("Snoozed a notification"),
            )
        }

        /**
         * What a reply or a tap shows on its card, looked up before the card: the app, the
         * conversation and the button. Answers at once, without a card, when the notification is
         * gone, has no reply or no such button, or needs nothing confirmed (opening it, a harmless
         * button).
         */
        fun prepare(call: PhoneTools.Call): PhoneTools.Prepared {
            return try {
                when (call) {
                    is PhoneTools.Call.ReplyToNotification -> prepareReply(call)
                    is PhoneTools.Call.NotificationAction -> prepareTap(call)
                    else -> PhoneTools.ask(call)
                }
            } catch (_: RuntimeException) {
                PhoneTools.Prepared.Answer(PhoneTools.Result.failure("The phone couldn’t do that."))
            }
        }

        private fun prepareReply(call: PhoneTools.Call.ReplyToNotification): PhoneTools.Prepared {
            val found = when (val lookup = lookUp(call.key)) {
                is Lookup.Found -> lookup
                is Lookup.Missing -> return PhoneTools.Prepared.Answer(lookup.result)
            }
            val notification = found.posted.notification
            val app = PhoneLaunch.appName(found.posted.packageName)
            if (replyButton(notification) == null) return PhoneTools.Prepared.Answer(PhoneTools.Result.failure(noReply(app)))
            return PhoneTools.Prepared.Ask(call, PhoneTools.Confirm.NotificationReply(app, conversation(notification), call.text))
        }

        private fun prepareTap(call: PhoneTools.Call.NotificationAction): PhoneTools.Prepared {
            val found = when (val lookup = lookUp(call.key)) {
                is Lookup.Found -> lookup
                is Lookup.Missing -> return PhoneTools.Prepared.Answer(lookup.result)
            }
            // Opening it only shows it, as tapping it in the shade would.
            if (call.action.equals(PhoneTools.Call.NotificationAction.OPEN, ignoreCase = true)) return PhoneTools.Prepared.Ask(call, null)
            val notification = found.posted.notification
            val app = PhoneLaunch.appName(found.posted.packageName)
            val buttons = buttons(notification)
            val index = PhoneNotifications.findButton(titles(buttons), call.action)
            if (index == null) {
                return PhoneTools.Prepared.Answer(PhoneTools.Result.failure(PhoneNotifications.noButton(app, call.action, titles(buttons))))
            }
            val button = buttons[index]
            val buttonTitle = button.title.toString().trim()
            if (!PhoneNotifications.asksFirst(button.semanticAction, buttonTitle)) return PhoneTools.Prepared.Ask(call, null)
            return PhoneTools.Prepared.Ask(call, PhoneTools.Confirm.NotificationAction(app, title(notification), buttonTitle))
        }

        /**
         * Fills the notification's Reply button with [PhoneTools.Call.ReplyToNotification.text]
         * and presses it, then watches a moment for the app to show the reply.
         */
        suspend fun reply(call: PhoneTools.Call.ReplyToNotification): PhoneTools.Result {
            val found = when (val lookup = lookUp(call.key)) {
                is Lookup.Found -> lookup
                is Lookup.Missing -> return lookup.result
            }
            val posted = found.posted
            val notification = posted.notification
            val app = PhoneLaunch.appName(posted.packageName)
            val button = replyButton(notification) ?: return PhoneTools.Result.failure(noReply(app))
            val pending = button.actionIntent ?: return PhoneTools.Result.failure(noReply(app))
            if (pending.isImmutable) {
                return PhoneTools.Result.failure("$app’s Reply button doesn’t take text from other apps, so nothing was sent. The user can reply in $app.")
            }
            if (pending.isActivity && !PhoneLaunch.mayStartActivities(found.context)) return PhoneTools.Result.failure(PhoneTools.BLOCKED)

            val inputs = button.remoteInputs ?: emptyArray()
            val results = Bundle()
            for (input in inputs) {
                if (input.allowFreeFormInput) results.putCharSequence(input.resultKey, call.text)
            }
            val fill = Intent()
            RemoteInput.addResultsToIntent(inputs, fill, results)
            RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
            if (!send(found.context, pending, fill)) {
                return PhoneTools.Result.failure("$app didn’t take the reply: its Reply button no longer works. Nothing was sent; the user can reply in $app.")
            }
            val seen = watch(found.listener, call.key, posted.postTime, call.text)
            val text = PhoneNotifications.replied(app, conversation(notification), call.text, seen)
            if (seen == PhoneNotifications.Seen.shown || seen == PhoneNotifications.Seen.cleared) {
                return PhoneTools.Result(text, action = PhoneTools.Action.other("Replied in $app"))
            }
            return PhoneTools.Result(text)
        }

        /** Presses one of the notification's buttons by its title, or opens the notification. */
        suspend fun tap(call: PhoneTools.Call.NotificationAction): PhoneTools.Result {
            val found = when (val lookup = lookUp(call.key)) {
                is Lookup.Found -> lookup
                is Lookup.Missing -> return lookup.result
            }
            val posted = found.posted
            val notification = posted.notification
            val app = PhoneLaunch.appName(posted.packageName)
            if (call.action.equals(PhoneTools.Call.NotificationAction.OPEN, ignoreCase = true)) return open(found, app)

            val buttons = buttons(notification)
            val index = PhoneNotifications.findButton(titles(buttons), call.action)
                ?: return PhoneTools.Result.failure(PhoneNotifications.noButton(app, call.action, titles(buttons)))
            val button = buttons[index]
            val buttonTitle = button.title.toString().trim()
            if (takesText(button)) return PhoneTools.Result.failure("“$buttonTitle” takes typed text: use reply_to_notification instead.")
            val pending = button.actionIntent ?: return PhoneTools.Result.failure("“$buttonTitle” on that $app notification doesn’t do anything.")
            if (pending.isActivity && !PhoneLaunch.mayStartActivities(found.context)) return PhoneTools.Result.failure(PhoneTools.BLOCKED)
            if (!send(found.context, pending, null)) {
                return PhoneTools.Result.failure("$app didn’t take the tap: “$buttonTitle” no longer works. Nothing happened.")
            }
            val seen = if (pending.isActivity) PhoneNotifications.Seen.unchanged else watch(found.listener, call.key, posted.postTime, null)
            val text = PhoneNotifications.tapped(app, title(notification), buttonTitle, seen, opened = pending.isActivity)
            return PhoneTools.Result(text, action = PhoneTools.Action.other("Tapped “$buttonTitle”"))
        }

        /** Opens the notification as tapping it would, and clears it when its app asked for that. */
        private fun open(found: Lookup.Found, app: String): PhoneTools.Result {
            val posted = found.posted
            val notification = posted.notification
            val pending = notification.contentIntent
                ?: return PhoneTools.Result.failure("That $app notification doesn’t open anything. To open $app itself, use open_app.")
            if (!PhoneLaunch.mayStartActivities(found.context)) return PhoneTools.Result.failure(PhoneTools.BLOCKED)
            if (!send(found.context, pending, null)) {
                return PhoneTools.Result.failure("That $app notification no longer opens anything. To open $app itself, use open_app.")
            }
            if (notification.flags and Notification.FLAG_AUTO_CANCEL != 0) found.listener.cancelNotification(posted.key)
            val title = title(notification)
            val text = if (title.isNullOrBlank()) "Opened the $app notification." else "Opened the $app notification “$title”."
            return PhoneTools.Result(text, action = PhoneTools.Action(PhoneTools.Action.Kind.app, app, packageName = posted.packageName))
        }

        /**
         * Sends a notification's [pending] intent as OpenCode. Android 14 and later only let it
         * open a screen when the sender says so (OpenCode may itself, as the assistant). False
         * when the app has cancelled it.
         */
        private fun send(context: Context, pending: PendingIntent, fill: Intent?): Boolean {
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            return try {
                pending.send(context, 0, fill, null, null, null, options.toBundle())
                if (pending.isActivity) PhoneLaunch.opened(context)
                true
            } catch (_: PendingIntent.CanceledException) {
                false
            }
        }

        /**
         * Watches the notification for up to [SETTLE_MILLIS] after a reply or a tap: whether the
         * app cleared it, showed [reply] in it, or changed it (a new post time).
         */
        private suspend fun watch(listener: PhoneNotificationListener, key: String, postedAt: Long, reply: String?): PhoneNotifications.Seen {
            var seen = PhoneNotifications.Seen.unchanged
            var waited = 0L
            while (waited < SETTLE_MILLIS) {
                delay(SETTLE_STEP_MILLIS)
                waited += SETTLE_STEP_MILLIS
                val active = runCatching { listener.activeNotifications }.getOrNull() ?: return seen
                var now: StatusBarNotification? = null
                for (posted in active) {
                    if (posted.key == key) now = posted
                }
                if (now == null) return PhoneNotifications.Seen.cleared
                if (reply != null && shows(now.notification, reply)) return PhoneNotifications.Seen.shown
                if (now.postTime != postedAt) {
                    seen = PhoneNotifications.Seen.changed
                    // A tap has done all it'll show; a reply may still appear in the conversation.
                    if (reply == null) return seen
                }
            }
            return seen
        }

        /** Whether the notification shows [reply]: as its last message, its text, or its reply history. */
        private fun shows(notification: Notification, reply: String): Boolean {
            val wanted = reply.trim()
            val extras = notification.extras
            val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
            if (bundles != null && bundles.isNotEmpty()) {
                val last = bundles[bundles.size - 1] as? Bundle
                if (last?.getCharSequence("text")?.toString()?.trim() == wanted) return true
            }
            if (extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.contains(wanted) == true) return true
            @Suppress("DEPRECATION")
            val history = extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
            for (line in history ?: emptyArray()) {
                if (line.toString().trim() == wanted) return true
            }
            return false
        }

        /** The notification's buttons, as its app gave them. */
        private fun buttons(notification: Notification): List<Notification.Action> {
            val buttons = mutableListOf<Notification.Action>()
            for (button in notification.actions ?: emptyArray()) {
                if (!button.title.isNullOrBlank()) buttons += button
            }
            return buttons
        }

        private fun titles(buttons: List<Notification.Action>): List<String> {
            val titles = mutableListOf<String>()
            for (button in buttons) titles += button.title.toString().trim()
            return titles
        }

        /**
         * The button that takes a typed reply: the one Android marks as Reply, else the first that
         * takes text. Some apps only give a watch one, so a watch's buttons count too.
         */
        private fun replyButton(notification: Notification): Notification.Action? {
            val candidates = mutableListOf<Notification.Action>()
            for (button in notification.actions ?: emptyArray()) candidates += button
            @Suppress("DEPRECATION")
            for (button in Notification.WearableExtender(notification).actions) candidates += button
            var first: Notification.Action? = null
            for (button in candidates) {
                if (!takesText(button)) continue
                if (button.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY) return button
                if (first == null) first = button
            }
            return first
        }

        private fun takesText(button: Notification.Action): Boolean {
            for (input in button.remoteInputs ?: emptyArray()) {
                if (input.allowFreeFormInput) return true
            }
            return false
        }

        private fun noReply(app: String): String =
            "That $app notification has no Reply button, so it can’t be answered from here. Use send_text for a text, or open the notification."

        private fun title(notification: Notification): String? =
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.ifEmpty { null }

        /** Who a reply goes to: a group's name, else the title (the sender, in a one-to-one chat). */
        private fun conversation(notification: Notification): String? {
            val group = notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
            if (!group.isNullOrEmpty()) return group
            return title(notification)
        }

        /** The notification with a key, with the listener that sees it, or the result that says why not. */
        private sealed interface Lookup {
            class Found(val context: Context, val listener: PhoneNotificationListener, val posted: StatusBarNotification) : Lookup

            class Missing(val result: PhoneTools.Result) : Lookup
        }

        private fun lookUp(key: String): Lookup {
            val context = dependency(AppContext)
            PhoneAccess.requireSpecial(context, PhoneAccess.notifications)?.let { return Lookup.Missing(it) }
            val listener = connected ?: return Lookup.Missing(PhoneTools.Result.failure(RECONNECTING))
            val active = runCatching { listener.activeNotifications }.getOrNull()
                ?: return Lookup.Missing(PhoneTools.Result.failure(RECONNECTING))
            for (posted in active) {
                if (posted.key == key) return Lookup.Found(context, listener, posted)
            }
            return Lookup.Missing(PhoneTools.Result.failure(GONE))
        }

        /**
         * Runs [use] with the connected listener and the notifications it sees now. Without
         * notification access, or before Android connects the listener, answers why instead.
         */
        private inline fun withNotifications(
            use: (context: Context, listener: PhoneNotificationListener, active: Array<StatusBarNotification>) -> PhoneTools.Result,
        ): PhoneTools.Result {
            val context = dependency(AppContext)
            PhoneAccess.requireSpecial(context, PhoneAccess.notifications)?.let { return it }
            val listener = connected ?: return PhoneTools.Result.failure(RECONNECTING)
            val active = runCatching { listener.activeNotifications }.getOrNull()
                ?: return PhoneTools.Result.failure(RECONNECTING)
            return use(context, listener, active)
        }

        /** Group summaries and OpenCode's own are left out; ongoing ones (calls, music) are marked. */
        private fun shows(posted: StatusBarNotification, own: String): Boolean {
            if (posted.packageName == own) return false
            if (posted.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
            return true
        }
    }
}
