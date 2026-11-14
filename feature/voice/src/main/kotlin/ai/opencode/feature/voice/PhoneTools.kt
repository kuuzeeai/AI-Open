package ai.opencode.feature.voice

import ai.opencode.server.clienttools.ClientTool
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Android only (the Assistant experiment): the phone's own actions, offered to the assistant's
 * OpenCode session as client tools (`android_set_timer`, `android_send_text`, …; their
 * descriptions and schemas are `ClientTool`'s, from opencode-phone's ANDROID.md v2). The server
 * passes arguments on unchecked, so [parse] checks them; [PhoneToolsRunner] runs a call through
 * [PhoneToolsClient]. Result strings follow the spec word for word: the model repeats them.
 */
object PhoneTools {
    /** The longest timer the Clock app takes (`AlarmClock.EXTRA_LENGTH`: 1 to 86 400 seconds). */
    private const val MAX_TIMER_SECONDS = 86_400

    /** The longest `dismiss_notification` snooze: a day. */
    private const val MAX_SNOOZE_MINUTES = 1_440

    /** `media_control`'s `seconds`: up to a day into a track or away from where it is. */
    private const val MAX_MEDIA_SECONDS = 86_400

    /** `set_alarm`'s `days`, in the schema's spelling. */
    private val weekdays: List<String> = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    private val settingsPanels: List<String> = listOf(
        "wifi", "bluetooth", "internet", "airplane_mode", "nfc", "location", "hotspot", "volume", "sound", "display",
        "battery", "date_time", "accessibility", "opencode", "all",
    )

    /**
     * How long the phone works on one call before it answers that it ran out of time. The server
     * waits 10 minutes for an attached tool, far too long for a timer. A confirm card's wait
     * doesn't count: it has its own [confirmTimeout].
     */
    val timeout = 30.seconds

    /** How long a confirm card waits for the user before it counts as declined. */
    val confirmTimeout = 60.seconds

    /** What no phone tool can do, because Android doesn't let apps; ends [aboutPhone]'s list. */
    private const val CANT_DO = "Android doesn’t let apps turn Wi‑Fi, Bluetooth, mobile data, airplane mode, the hotspot, NFC, location or dark mode on or off, cancel a timer that’s still counting down, or list alarms and timers: say the phone can’t."

    /** How long `take_photo` waits for the user to take the photo in the camera app. */
    val photoTimeout = 120.seconds

    /** What [parse] makes of a call: a [Call] to run, or [Invalid] arguments. */
    sealed interface Parsed

    /** One phone tool call, its arguments checked. */
    sealed class Call(val name: String) : Parsed {
        data object AboutPhone : Call("about_phone")

        data class SetTimer(val seconds: Int, val label: String?) : Call("set_timer")

        /** [days] come from [weekdays], in week order; empty for a one-off alarm. */
        data class SetAlarm(val hour: Int, val minute: Int, val days: List<String>, val label: String?) : Call("set_alarm")

        /** [match] is next, time, label or all; [hour] and [minute] come with time, [label] with label. */
        data class CancelAlarm(val match: String, val hour: Int?, val minute: Int?, val label: String?) : Call("cancel_alarm")

        data object StopTimer : Call("stop_timer")

        data class ShowClock(val page: String) : Call("show_clock")

        data class Flashlight(val on: Boolean) : Call("flashlight")

        /** [seconds] goes with seek, forward and back; [shuffle] with shuffle; [repeat] (off, one, all) with repeat. */
        data class MediaControl(val action: String, val seconds: Int? = null, val shuffle: Boolean? = null, val repeat: String? = null) : Call("media_control")

        /** [kind]: song, artist, album, playlist, podcast, video or station. */
        data class PlayMedia(val query: String, val app: String?, val kind: String?) : Call("play_media")

        data class SetRinger(val mode: String) : Call("set_ringer")

        /** At least one setting; [autoBrightness] is brightness “auto”, otherwise [brightness] is 0 to 100. */
        data class SetDisplay(val brightness: Int?, val autoBrightness: Boolean, val autoRotate: Boolean?, val timeoutSeconds: Int?) : Call("set_display")

        data object TakePhoto : Call("take_photo")

        /** Exactly one of [level] (0 to 100) and [change] (up or down). */
        data class SetVolume(val stream: String, val level: Int?, val change: String?) : Call("set_volume")

        data class SetDoNotDisturb(val on: Boolean) : Call("set_do_not_disturb")

        data object PhoneStatus : Call("phone_status")

        data class OpenSettings(val panel: String) : Call("open_settings")

        data class CopyToClipboard(val text: String) : Call("copy_to_clipboard")

        data class OpenApp(val query: String) : Call("open_app")

        data object ReadScreen : Call("read_screen")

        data class FindContact(val query: String) : Call("find_contact")

        /** [dialOnly] fills in the dialer; otherwise it calls after the user confirms. */
        data class PhoneCall(val number: String, val contact: String?, val dialOnly: Boolean) : Call("call")

        data class AddContact(
            val contactName: String,
            val phone: String?,
            val email: String?,
            val address: String?,
            val company: String?,
            val notes: String?,
        ) : Call("add_contact")

        data object AnswerCall : Call("answer_call")

        data object EndCall : Call("end_call")

        /** [type] is missed, incoming or outgoing; null for all. [from] is a name or a number. */
        data class ReadCallLog(val type: String?, val from: String?, val limit: Int) : Call("read_call_log")

        /** [draft] opens it filled in; otherwise it sends after the user confirms. Only a draft may leave out [to] or [message]. */
        data class SendText(val to: String?, val message: String?, val contact: String?, val draft: Boolean) : Call("send_text")

        data class DraftEmail(val to: String?, val subject: String?, val body: String?) : Call("draft_email")

        /** [from] is a contact's name or a number. */
        data class ReadTexts(val from: String?, val unreadOnly: Boolean, val limit: Int) : Call("read_texts")

        /** All-day events have no times: [start] and [end] are midnight. [end] is filled in when it was left out. */
        data class AddCalendarEvent(
            val title: String,
            val start: LocalDateTime,
            val end: LocalDateTime,
            val allDay: Boolean,
            val location: String?,
            val notes: String?,
        ) : Call("add_calendar_event")

        data class ListCalendarEvents(val start: LocalDateTime, val end: LocalDateTime) : Call("list_calendar_events")

        /**
         * Null leaves a field as it is; an empty [location] or [notes] clears it. [startIsDate] and
         * [endIsDate] say the time was a date alone. A repeating [event] names the one occurrence to change.
         */
        data class UpdateCalendarEvent(
            val event: EventID,
            val title: String?,
            val start: LocalDateTime?,
            val startIsDate: Boolean,
            val end: LocalDateTime?,
            val endIsDate: Boolean,
            val allDay: Boolean?,
            val location: String?,
            val notes: String?,
        ) : Call("update_calendar_event")

        /** [title] and [shown] are what the card names, filled in by [PhoneToolsClient.prepare]. */
        data class DeleteCalendarEvent(val event: EventID, val title: String? = null, val shown: String? = null) :
            Call("delete_calendar_event")

        /** [mode] only counts with [directions]. */
        data class OpenMaps(val place: String, val directions: Boolean, val mode: String) : Call("open_maps")

        data object GetLocation : Call("get_location")

        data class ReadNotifications(val app: String?, val limit: Int) : Call("read_notifications")

        /** Clears it, or with [snoozeMinutes] hides it for that long. */
        data class DismissNotification(val key: String, val snoozeMinutes: Int? = null) : Call("dismiss_notification")

        data class ReplyToNotification(val key: String, val text: String) : Call("reply_to_notification")

        /** [action] is a button's title, or [OPEN] for the notification itself. */
        data class NotificationAction(val key: String, val action: String) : Call("notification_action") {
            companion object {
                const val OPEN = "open"
            }
        }

        /** [app] null opens Android's share sheet. */
        data class Share(val text: String, val app: String?, val subject: String?) : Call("share")

        data class OpenLink(val url: String) : Call("open_link")
    }

    /**
     * A calendar event as `list_calendar_events` names it: “482” for a one-off event, or
     * “482@1762963200000” for one occurrence of a repeating event (the event, then the
     * occurrence's start in epoch milliseconds).
     */
    data class EventID(val id: Long, val occurrence: Long?) {
        override fun toString(): String = if (occurrence == null) "$id" else "$id@$occurrence"

        companion object {
            fun parse(text: String): EventID? {
                val parts = text.trim().split("@")
                if (parts.size > 2) return null
                val id = parts[0].toLongOrNull() ?: return null
                if (id < 0) return null
                if (parts.size == 1) return EventID(id, null)
                val occurrence = parts[1].toLongOrNull() ?: return null
                return EventID(id, occurrence)
            }
        }
    }

    /** Arguments a tool can't run with; [message] is the error result the session's model reads. */
    data class Invalid(val message: String) : Parsed

    /** What a tool did, as the assistant panel lists it: “Timer set · 10 min”, “Opened Spotify”. */
    data class Action(
        val kind: Kind,
        val detail: String,
        /** The app an [Kind.app] row opens again. */
        val packageName: String? = null,
        /** The tool call it came from. */
        val id: String = "",
    ) {
        /** [Kind.other]'s [detail] is the whole chip. */
        enum class Kind { timer, alarm, app, screen, other }

        val text: String
            get() = when (kind) {
                Kind.timer -> "Timer set · $detail"
                Kind.alarm -> "Alarm set · $detail"
                Kind.app -> "Opened $detail"
                Kind.screen -> "Read the screen"
                Kind.other -> detail
            }

        companion object {
            fun other(text: String): Action = Action(Kind.other, text)
        }
    }

    /**
     * A tool's answer: the text the model reads, whether it failed, what it did when it did
     * something, and a JPEG for `read_screen`'s screenshot.
     */
    data class Result(
        val text: String,
        val isError: Boolean = false,
        val action: Action? = null,
        val jpeg: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean = other is Result && text == other.text && isError == other.isError &&
            action == other.action && jpeg.contentEquals(other.jpeg)

        override fun hashCode(): Int = text.hashCode() * 31 + isError.hashCode()

        companion object {
            fun failure(text: String): Result = Result(text, isError = true)
        }
    }

    /** What a confirm card shows before the phone acts. */
    sealed interface Confirm {
        data class PhoneCall(val contact: String?, val number: String) : Confirm

        data class Text(val contact: String?, val number: String, val message: String) : Confirm

        data object Notifications : Confirm

        /** A reply through [app]'s notification to [conversation] (a group's name or the sender), when known. */
        data class NotificationReply(val app: String, val conversation: String?, val text: String) : Confirm

        /** Tapping [button] on [app]'s notification titled [title], when it has one. */
        data class NotificationAction(val app: String, val title: String?, val button: String) : Confirm

        /** [from] is who the user asked about; null for every conversation. */
        data class Texts(val from: String?) : Confirm

        data class CallLog(val from: String?) : Confirm

        /** [title] and [shown] are null when the phone couldn't read the event first. */
        data class DeleteEvent(val title: String?, val shown: String?) : Confirm
    }

    /**
     * What the phone found before any confirm card ([PhoneToolsClient.prepare]): the call to run,
     * filled in with what the card shows (the event being deleted), and its card (none when null);
     * or the answer that ends the call without a card, like a notification that's already gone.
     */
    sealed interface Prepared {
        data class Ask(val call: Call, val card: Confirm?) : Prepared

        data class Answer(val result: Result) : Prepared
    }

    /** Runs [call] behind its usual card ([confirmFor]). */
    fun ask(call: Call): Prepared = Prepared.Ask(call, confirmFor(call))

    /**
     * A text the phone can't send itself (OpenCode isn't the assistant, or can't send texts)
     * only opens a draft the user sends, so it skips the "Send Text?" card like any draft.
     */
    fun askText(call: Call.SendText, canSend: Boolean): Prepared =
        if (canSend) ask(call) else Prepared.Ask(call, null)

    /** The card a call shows first, or null when it acts without one. */
    fun confirmFor(call: Call): Confirm? = when (call) {
        is Call.PhoneCall -> if (call.dialOnly) null else Confirm.PhoneCall(call.contact, call.number)
        is Call.SendText -> if (call.draft || call.to == null || call.message == null) null else Confirm.Text(call.contact, call.to, call.message)
        is Call.ReadNotifications -> Confirm.Notifications
        // The live client names the app and conversation ([PhoneToolsClient.prepare]); these are the fallbacks.
        is Call.ReplyToNotification -> Confirm.NotificationReply("the app", null, call.text)
        is Call.NotificationAction -> if (call.action.equals(Call.NotificationAction.OPEN, ignoreCase = true)) null else Confirm.NotificationAction("the app", null, call.action)
        is Call.ReadTexts -> Confirm.Texts(call.from)
        is Call.ReadCallLog -> Confirm.CallLog(call.from)
        is Call.DeleteCalendarEvent -> Confirm.DeleteEvent(call.title, call.shown)
        else -> null
    }

    /** The answer when the user turns a card down. */
    fun declined(call: Call): String = when (call) {
        is Call.PhoneCall -> "The user declined. No call was made."
        is Call.SendText -> "The user declined. Nothing was sent."
        is Call.ReadNotifications -> "The user declined. No notifications were shared."
        is Call.ReplyToNotification -> "The user declined. Nothing was sent."
        is Call.NotificationAction -> "The user declined. The button wasn’t tapped."
        is Call.ReadTexts -> "The user declined. No texts were shared."
        is Call.ReadCallLog -> "The user declined. No calls were shared."
        is Call.DeleteCalendarEvent -> "The user declined. The event wasn’t deleted."
        else -> DECLINED
    }

    const val NOT_HERE = "The phone only takes actions for the session it’s talking to."
    const val NO_CLOCK = "The Clock app isn’t available on this phone."
    const val LOCKED = "The screen is locked, so it can’t be read."
    const val DECLINED = "The user declined."
    const val NO_SCREENSHOT = "Screenshots are off in the phone’s assistant settings, so only the screen’s text was read."
    const val SCREEN_DECLINED = "The user didn’t share their screen, so nothing was read. Don’t guess what’s on it."
    const val SCREEN_NO_ANSWER = "The user didn’t answer Android’s prompt to share the screen, so nothing was read."
    const val SCREEN_STOPPED = "Screen sharing stopped before the screen was read, so nothing was read."
    const val SCREEN_STOPPED_BY_USER = "The user stopped sharing their screen, so nothing was read. Reading it again will ask them to share it again."
    const val SCREEN_ONLY_PICTURE = "Only a screenshot: Android didn’t give the screen’s text or app, so read them from the picture."
    const val SCREEN_PROMPT_BLOCKED = "Android didn’t let OpenCode show its screen-sharing prompt from the background, so nothing was read. Ask the user to open OpenCode or hold the side button, then ask again."
    const val SCREEN_ONE_APP = "The user shared only one app, and it isn’t on screen now, so nothing was read. Ask them to switch to that app, or to stop sharing so the next read asks again."
    const val SCREEN_FAILED = "The phone couldn’t capture the screen, so nothing was read."
    const val BAD_EVENT_ID = "event_id must be an event_id from list_calendar_events, such as “482” or “482@1762963200000”."
    const val BLOCKED = "Android didn’t let OpenCode open that from the background. Ask the user to hold the side button and try again."
    const val OPENED_LOCKED = "The phone is locked, so the user has to unlock it to see it."

    /**
     * [result] for a tool that opened a screen while the phone stayed locked: [OPENED_LOCKED] follows its
     * first line, before any details line.
     */
    fun behindLock(result: Result): Result {
        val lines = result.text.split("\n", limit = 2)
        var text = lines[0] + " " + OPENED_LOCKED
        if (lines.size > 1) text += "\n" + lines[1]
        return result.copy(text = text)
    }

    fun needsPermission(what: String): String =
        "OpenCode doesn’t have permission to $what, so nothing was done. The phone is asking the user to allow it; try again only after they say they have."

    fun permissionBlocked(what: String): String =
        "OpenCode isn’t allowed to $what, so nothing was done. The phone opened OpenCode’s app settings so the user can allow it under Permissions."

    fun needsAccess(access: String): String =
        "OpenCode doesn’t have $access, so nothing was done. The phone opened that setting for the user; try again only after they say it’s on."

    /**
     * `about_phone`'s answer: every other phone tool with its argument names and the first line of
     * its description, since Code Mode's catalog shows only a few of them.
     */
    fun aboutPhone(): String {
        val lines = mutableListOf("Phone tools, called as tools.android.<name>(args):")
        for (tool in ClientTool.entries) {
            if (!tool.isPhoneTool || tool == ClientTool.aboutPhone) continue
            val required = mutableSetOf<String>()
            val requiredList = tool.inputSchema["required"] as? JsonArray
            if (requiredList != null) {
                for (item in requiredList) required += (item as JsonPrimitive).content
            }
            val names = mutableListOf<String>()
            for (argument in argumentNames(tool)) {
                names += if (argument in required) argument else "$argument?"
            }
            val signature = if (names.isEmpty()) "" else "{ ${names.joinToString(", ")} }"
            lines += "- ${tool.rawValue}($signature): ${tool.description.substringBefore("\n")}"
        }
        lines += CANT_DO
        return lines.joinToString("\n")
    }

    private fun argumentNames(tool: ClientTool): List<String> {
        val properties = tool.inputSchema["properties"] as? JsonObject ?: return emptyList()
        return properties.keys.toList()
    }

    /** The error when a call runs past [timeout]. */
    fun timedOut(name: String): String = "The phone didn’t finish ${doing(name)} in time."

    private fun doing(name: String): String = when (ClientTool.fromRawValue(name)) {
        ClientTool.setTimer -> "setting the timer"
        ClientTool.setAlarm -> "setting the alarm"
        ClientTool.cancelAlarm -> "turning off the alarm"
        ClientTool.stopTimer -> "stopping the timer"
        ClientTool.showClock -> "opening Clock"
        ClientTool.flashlight -> "switching the flashlight"
        ClientTool.mediaControl -> "controlling the media"
        ClientTool.playMedia -> "starting the media"
        ClientTool.setRinger -> "changing the ringer"
        ClientTool.setDisplay -> "changing the display"
        ClientTool.takePhoto -> "taking the photo"
        ClientTool.setVolume -> "changing the volume"
        ClientTool.setDoNotDisturb -> "changing Do Not Disturb"
        ClientTool.phoneStatus -> "reading its status"
        ClientTool.openSettings -> "opening Settings"
        ClientTool.copyToClipboard -> "copying the text"
        ClientTool.readScreen -> "reading the screen"
        ClientTool.findContact -> "looking up the contact"
        ClientTool.aboutPhone -> "listing its tools"
        ClientTool.call -> "placing the call"
        ClientTool.sendText -> "sending the text"
        ClientTool.draftEmail -> "opening the email"
        ClientTool.addCalendarEvent -> "adding the event"
        ClientTool.listCalendarEvents -> "reading the calendar"
        ClientTool.openMaps -> "opening Maps"
        ClientTool.getLocation -> "finding its location"
        ClientTool.readNotifications -> "reading the notifications"
        ClientTool.dismissNotification -> "dismissing the notification"
        ClientTool.replyToNotification -> "sending the reply"
        ClientTool.notificationAction -> "tapping the notification"
        ClientTool.share -> "sharing the text"
        ClientTool.openLink -> "opening the link"
        ClientTool.addContact -> "opening Contacts"
        ClientTool.answerCall -> "answering the call"
        ClientTool.endCall -> "ending the call"
        ClientTool.readCallLog -> "reading the call log"
        ClientTool.readTexts -> "reading the texts"
        ClientTool.updateCalendarEvent -> "changing the event"
        ClientTool.deleteCalendarEvent -> "deleting the event"
        else -> "opening the app"
    }

    /**
     * Checks a call's arguments against its schema (whole numbers in range, known words, the
     * fields it needs). Numbers and booleans sent as strings are read too. Null when [name] isn't
     * a phone tool.
     */
    fun parse(name: String, arguments: JsonObject?): Parsed? {
        val args = arguments ?: JsonObject(emptyMap())
        val tool = ClientTool.fromRawValue(name)
        if (tool == null || !tool.isPhoneTool) return null
        // A guessed argument name would be dropped silently, so it's refused instead.
        val known = argumentNames(tool)
        for (key in args.keys) {
            if (key !in known) {
                val names = if (known.isEmpty()) "no arguments" else known.joinToString(", ")
                return Invalid("There’s no “$key” argument. This tool takes: $names.")
            }
        }
        return when (tool) {
            ClientTool.aboutPhone -> Call.AboutPhone

            ClientTool.setTimer -> {
                val seconds = args.wholeNumber("seconds")
                if (seconds == null || seconds < 1 || seconds > MAX_TIMER_SECONDS) {
                    return Invalid("seconds must be a whole number from 1 to $MAX_TIMER_SECONDS.")
                }
                Call.SetTimer(seconds, args.label())
            }

            ClientTool.setAlarm -> {
                val hour = args.wholeNumber("hour")
                if (hour == null || hour !in 0..23) return Invalid("hour must be a whole number from 0 to 23.")
                val minute = args.wholeNumber("minute")
                if (minute == null || minute !in 0..59) return Invalid("minute must be a whole number from 0 to 59.")
                val days = mutableSetOf<String>()
                if (args.hasValue("days")) {
                    val rawDays = args["days"]
                    if (rawDays !is JsonArray) return Invalid("days must be a list such as [\"mon\", \"tue\"].")
                    for (raw in rawDays) {
                        val day = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase(Locale.ROOT)
                        if (day == null || day !in weekdays) {
                            return Invalid("days can only hold ${weekdays.joinToString(", ")}.")
                        }
                        days += day
                    }
                }
                val ordered = mutableListOf<String>()
                for (day in weekdays) {
                    if (day in days) ordered += day
                }
                Call.SetAlarm(hour, minute, ordered, args.label())
            }

            ClientTool.cancelAlarm -> {
                val match = args.choice("match", listOf("next", "time", "label", "all"))
                    ?: return Invalid("match must be one of next, time, label, all.")
                when (match) {
                    "time" -> {
                        val hour = args.wholeNumber("hour") ?: return Invalid("hour is required when match is “time”.")
                        if (hour !in 0..23) return Invalid("hour must be a whole number from 0 to 23.")
                        val minute = args.wholeNumber("minute") ?: 0
                        if (minute !in 0..59) return Invalid("minute must be a whole number from 0 to 59.")
                        Call.CancelAlarm(match, hour, minute, null)
                    }
                    "label" -> {
                        val label = args.label() ?: return Invalid("label is required when match is “label”.")
                        Call.CancelAlarm(match, null, null, label)
                    }
                    else -> Call.CancelAlarm(match, null, null, null)
                }
            }

            ClientTool.stopTimer -> Call.StopTimer

            ClientTool.showClock -> Call.ShowClock(args.choice("page", listOf("alarms", "timers")) ?: return Invalid("page must be alarms or timers."))

            ClientTool.flashlight -> Call.Flashlight(args.flag("on") ?: return Invalid("on must be true or false."))

            ClientTool.mediaControl -> mediaControl(args)

            ClientTool.playMedia -> {
                val query = args.text("query")?.take(200) ?: return Invalid("query must say what to play.")
                val app = args.text("app")
                val kinds = listOf("song", "artist", "album", "playlist", "podcast", "video", "station")
                val kind = if (args.hasValue("kind")) {
                    args.choice("kind", kinds) ?: return Invalid("kind must be one of ${kinds.joinToString(", ")}.")
                } else {
                    null
                }
                Call.PlayMedia(query, app, kind)
            }

            ClientTool.setRinger -> Call.SetRinger(
                args.choice("mode", listOf("normal", "vibrate", "silent")) ?: return Invalid("mode must be normal, vibrate or silent."),
            )

            ClientTool.setDisplay -> setDisplay(args)

            ClientTool.takePhoto -> Call.TakePhoto

            ClientTool.setVolume -> {
                val stream = args.choice("stream", listOf("media", "ring", "notification", "alarm", "call"))
                    ?: return Invalid("stream must be one of media, ring, notification, alarm, call.")
                val hasLevel = args.hasValue("level")
                val hasChange = args.hasValue("change")
                if (hasLevel == hasChange) return Invalid("Give exactly one of level or change.")
                if (hasLevel) {
                    val level = args.wholeNumber("level")
                    if (level == null || level !in 0..100) return Invalid("level must be a whole number from 0 to 100.")
                    Call.SetVolume(stream, level, null)
                } else {
                    val change = args.choice("change", listOf("up", "down")) ?: return Invalid("change must be up or down.")
                    Call.SetVolume(stream, null, change)
                }
            }

            ClientTool.setDoNotDisturb -> Call.SetDoNotDisturb(args.flag("on") ?: return Invalid("on must be true or false."))

            ClientTool.phoneStatus -> Call.PhoneStatus

            ClientTool.openSettings -> Call.OpenSettings(
                args.choice("panel", settingsPanels) ?: return Invalid("panel must be one of ${settingsPanels.joinToString(", ")}."),
            )

            ClientTool.copyToClipboard -> {
                val text = args.raw("text") ?: return Invalid("text is required.")
                Call.CopyToClipboard(text)
            }

            ClientTool.openApp -> {
                val query = args.text("name") ?: return Invalid("name must be the app’s name, such as “Spotify”.")
                Call.OpenApp(query)
            }

            ClientTool.readScreen -> Call.ReadScreen

            ClientTool.findContact -> Call.FindContact(args.text("name") ?: return Invalid("name is required."))

            ClientTool.call -> {
                if (args.text("number") == null) return Invalid("number is required.")
                Call.PhoneCall(
                    args.number("number") ?: return Invalid(notANumber("number")),
                    args.text("name"),
                    args.flag("dial_only") ?: false,
                )
            }

            ClientTool.sendText -> {
                val draft = args.flag("draft") ?: false
                val hasTo = args.text("to") != null
                val to = if (hasTo) args.number("to") ?: return Invalid(notANumber("to")) else null
                val name = args.text("name")
                if (name != null && to == null) {
                    return Invalid("name was given without to. Look $name up with find_contact and pass the number as to.")
                }
                val message = args.text("message")
                if (!draft && to == null) return Invalid("to is required unless draft is true.")
                if (!draft && message == null) return Invalid("message is required unless draft is true.")
                Call.SendText(to, message, name, draft)
            }

            ClientTool.draftEmail -> Call.DraftEmail(args.text("to"), args.text("subject"), args.text("body"))

            ClientTool.addCalendarEvent -> {
                val title = args.text("title") ?: return Invalid("title is required.")
                val start = moment(args.text("start")) ?: return Invalid("start must look like 2026-11-12T19:00 or 2026-11-12.")
                val allDay = args.flag("all_day") == true || start.second
                val rawEnd = args.text("end")
                var end: LocalDateTime
                if (rawEnd == null) {
                    end = if (allDay) start.first.toLocalDate().plusDays(1).atStartOfDay() else start.first.plusHours(1)
                } else {
                    val parsed = moment(rawEnd) ?: return Invalid("end must look like 2026-11-12T20:00 or 2026-11-12.")
                    end = if (allDay) parsed.first.toLocalDate().plusDays(1).atStartOfDay() else parsed.first
                }
                val begin = if (allDay) start.first.toLocalDate().atStartOfDay() else start.first
                if (!end.isAfter(begin)) return Invalid("end must be after start.")
                Call.AddCalendarEvent(title, begin, end, allDay, args.text("location"), args.text("notes"))
            }

            ClientTool.listCalendarEvents -> {
                val start = moment(args.text("start")) ?: return Invalid("start must look like 2026-11-12 or 2026-11-12T15:00.")
                val rawEnd = args.text("end")
                val end = if (rawEnd == null) {
                    start.first.toLocalDate().plusDays(1).atStartOfDay()
                } else {
                    val parsed = moment(rawEnd) ?: return Invalid("end must look like 2026-11-12 or 2026-11-12T15:00.")
                    // A date alone as the end means the whole of that day.
                    if (parsed.second) parsed.first.toLocalDate().plusDays(1).atStartOfDay() else parsed.first
                }
                if (!end.isAfter(start.first)) return Invalid("end must be after start.")
                Call.ListCalendarEvents(start.first, end)
            }

            ClientTool.openMaps -> {
                val place = args.text("place") ?: return Invalid("place is required.")
                val modes = listOf("driving", "walking", "transit", "bicycling")
                val mode = if (!args.hasValue("mode")) {
                    "driving"
                } else {
                    args.choice("mode", modes) ?: return Invalid("mode must be one of ${modes.joinToString(", ")}.")
                }
                Call.OpenMaps(place, args.flag("directions") ?: false, mode)
            }

            ClientTool.getLocation -> Call.GetLocation

            ClientTool.readNotifications -> {
                var limit = 10
                if (args.hasValue("limit")) {
                    val given = args.wholeNumber("limit")
                    if (given == null || given !in 1..20) return Invalid("limit must be a whole number from 1 to 20.")
                    limit = given
                }
                Call.ReadNotifications(args.text("app"), limit)
            }

            ClientTool.dismissNotification -> {
                val key = args.text("key") ?: return Invalid("key is required.")
                var snooze: Int? = null
                if (args.hasValue("snooze_minutes")) {
                    val given = args.wholeNumber("snooze_minutes")
                    if (given == null || given !in 1..MAX_SNOOZE_MINUTES) {
                        return Invalid("snooze_minutes must be a whole number from 1 to $MAX_SNOOZE_MINUTES.")
                    }
                    snooze = given
                }
                Call.DismissNotification(key, snooze)
            }

            ClientTool.replyToNotification -> {
                val key = args.text("key") ?: return Invalid("key is required.")
                val text = args.text("text") ?: return Invalid("text is required.")
                Call.ReplyToNotification(key, text)
            }

            ClientTool.notificationAction -> {
                val key = args.text("key") ?: return Invalid("key is required.")
                val action = args.text("action") ?: return Invalid("action is required: a button’s title, or “open”.")
                Call.NotificationAction(key, action)
            }

            ClientTool.share -> {
                val text = args.text("text") ?: return Invalid("text is required: what to share.")
                if (text.length > PhoneShare.MAX_TEXT) return Invalid("text is too long to share; the most is ${PhoneShare.MAX_TEXT} characters.")
                Call.Share(text, args.text("app"), args.text("subject"))
            }

            ClientTool.openLink -> {
                val url = args.text("url") ?: return Invalid("url is required.")
                val problem = PhoneShare.linkProblem(url)
                if (problem != null) return Invalid(problem)
                Call.OpenLink(url)
            }

            ClientTool.addContact -> {
                val contactName = args.text("name") ?: return Invalid("name is required.")
                val phone = if (args.text("phone") == null) null else args.number("phone") ?: return Invalid("phone must be a phone number.")
                val email = args.text("email")
                if (email != null && !email.contains('@')) return Invalid("email must be an email address.")
                Call.AddContact(contactName, phone, email, args.text("address"), args.text("company"), args.text("notes"))
            }

            ClientTool.answerCall -> Call.AnswerCall

            ClientTool.endCall -> Call.EndCall

            ClientTool.readCallLog -> {
                val types = listOf("missed", "incoming", "outgoing")
                val type = if (!args.hasValue("type")) {
                    null
                } else {
                    args.choice("type", types) ?: return Invalid("type must be one of ${types.joinToString(", ")}.")
                }
                var limit = 10
                if (args.hasValue("limit")) {
                    val given = args.wholeNumber("limit")
                    if (given == null || given !in 1..50) return Invalid("limit must be a whole number from 1 to 50.")
                    limit = given
                }
                Call.ReadCallLog(type, args.text("from"), limit)
            }

            ClientTool.readTexts -> {
                var limit = 10
                if (args.hasValue("limit")) {
                    val given = args.wholeNumber("limit")
                    if (given == null || given !in 1..30) return Invalid("limit must be a whole number from 1 to 30.")
                    limit = given
                }
                if (args.hasValue("unread_only") && args.flag("unread_only") == null) return Invalid("unread_only must be true or false.")
                Call.ReadTexts(args.text("from"), args.flag("unread_only") ?: false, limit)
            }

            ClientTool.updateCalendarEvent -> {
                val event = EventID.parse(args.text("event_id") ?: return Invalid("event_id is required."))
                    ?: return Invalid(BAD_EVENT_ID)
                var start: Pair<LocalDateTime, Boolean>? = null
                if (args.hasValue("start")) {
                    start = moment(args.text("start")) ?: return Invalid("start must look like 2026-11-12T19:00 or 2026-11-12.")
                }
                var end: Pair<LocalDateTime, Boolean>? = null
                if (args.hasValue("end")) {
                    end = moment(args.text("end")) ?: return Invalid("end must look like 2026-11-12T20:00 or 2026-11-12.")
                }
                if (args.hasValue("all_day") && args.flag("all_day") == null) return Invalid("all_day must be true or false.")
                val allDay = args.flag("all_day")
                if (allDay == false && (start == null || start.second)) {
                    return Invalid("start with a time, such as 2026-11-12T19:00, is required to make an all-day event timed.")
                }
                // An empty location or notes clears it; null leaves it alone.
                val location = if (args.hasValue("location")) args.raw("location")?.trim() ?: "" else null
                val notes = if (args.hasValue("notes")) args.raw("notes")?.trim() ?: "" else null
                val title = args.text("title")
                if (args.hasValue("title") && title == null) return Invalid("title can’t be empty.")
                if (title == null && start == null && end == null && allDay == null && location == null && notes == null) {
                    return Invalid("Nothing to change: give at least one of title, start, end, all_day, location or notes.")
                }
                if (start != null && end != null && !start.second && !end.second && !end.first.isAfter(start.first)) {
                    return Invalid("end must be after start.")
                }
                Call.UpdateCalendarEvent(
                    event,
                    title,
                    start?.first,
                    start?.second ?: false,
                    end?.first,
                    end?.second ?: false,
                    allDay,
                    location,
                    notes,
                )
            }

            ClientTool.deleteCalendarEvent -> {
                val event = EventID.parse(args.text("event_id") ?: return Invalid("event_id is required."))
                    ?: return Invalid(BAD_EVENT_ID)
                Call.DeleteCalendarEvent(event)
            }

            else -> null
        }
    }

    /** `Timer set for 5 minutes (Pasta).` */
    fun timerSet(call: Call.SetTimer): String = "Timer set for ${spokenDuration(call.seconds)}${labelled(call.label)}."

    /** `Alarm set for 07:30 repeating mon, tue (Gym).` */
    fun alarmSet(call: Call.SetAlarm): String {
        val repeats = if (call.days.isEmpty()) "" else " repeating ${call.days.joinToString(", ")}"
        return "Alarm set for ${clockTime(call.hour, call.minute)}$repeats${labelled(call.label)}."
    }

    /** “07:30”: a time as the results say it. */
    fun clockTime(hour: Int, minute: Int): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

    /** “10 minutes”, “1 hour 30 minutes”, “45 seconds”: how a result says a timer's length. */
    private fun spokenDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val rest = seconds % 60
        val parts = mutableListOf<String>()
        if (hours > 0) parts += plural(hours, "hour")
        if (minutes > 0) parts += plural(minutes, "minute")
        if (rest > 0) parts += plural(rest, "second")
        return parts.joinToString(" ")
    }

    /** “7:00 AM”, “6:30 PM”: an alarm as the panel shows it. */
    fun spokenTime(hour: Int, minute: Int): String {
        val twelve = if (hour % 12 == 0) 12 else hour % 12
        val half = if (hour < 12) "AM" else "PM"
        return "$twelve:${minute.toString().padStart(2, '0')} $half"
    }

    /** “10 min”, “1 h 30 min”, “45 s”: a timer as the panel shows it. */
    fun shortDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val rest = seconds % 60
        val parts = mutableListOf<String>()
        if (hours > 0) parts += "$hours h"
        if (minutes > 0) parts += "$minutes min"
        if (rest > 0) parts += "$rest s"
        return parts.joinToString(" ")
    }

    /** “2026-11-12T19:00” or “2026-11-12” (seconds allowed): the time, and whether it was a date alone. */
    private fun moment(text: String?): Pair<LocalDateTime, Boolean>? {
        if (text == null) return null
        val trimmed = text.trim()
        try {
            if (!trimmed.contains('T')) return Pair(LocalDate.parse(trimmed).atStartOfDay(), true)
            return Pair(LocalDateTime.parse(trimmed.removeSuffix("Z")).withSecond(0).withNano(0), false)
        } catch (_: DateTimeParseException) {
            return null
        }
    }

    private fun labelled(label: String?): String = if (label == null) "" else " ($label)"

    private fun plural(count: Int, unit: String): String = if (count == 1) "1 $unit" else "$count ${unit}s"

    private fun mediaControl(args: JsonObject): Parsed {
        val actions = listOf("play", "pause", "next", "previous", "stop", "seek", "forward", "back", "shuffle", "repeat")
        val action = args.choice("action", actions) ?: return Invalid("action must be one of ${actions.joinToString(", ")}.")
        val takesSeconds = action == "seek" || action == "forward" || action == "back"
        if (args.hasValue("seconds") && !takesSeconds) return Invalid("seconds goes only with seek, forward or back.")
        if (args.hasValue("shuffle") && action != "shuffle") return Invalid("shuffle goes only with action shuffle.")
        if (args.hasValue("repeat") && action != "repeat") return Invalid("repeat goes only with action repeat.")
        var seconds: Int? = null
        if (args.hasValue("seconds") || action == "seek") {
            seconds = args.wholeNumber("seconds")
            if (seconds == null || seconds < 0 || seconds > MAX_MEDIA_SECONDS) {
                return Invalid("seconds must be a whole number from 0 to $MAX_MEDIA_SECONDS.")
            }
        }
        var shuffle: Boolean? = null
        if (action == "shuffle") shuffle = args.flag("shuffle") ?: return Invalid("shuffle must be true or false.")
        var repeat: String? = null
        if (action == "repeat") repeat = args.choice("repeat", listOf("off", "one", "all")) ?: return Invalid("repeat must be off, one or all.")
        return Call.MediaControl(action, seconds, shuffle, repeat)
    }

    private fun setDisplay(args: JsonObject): Parsed {
        var brightness: Int? = null
        var autoBrightness = false
        if (args.hasValue("brightness")) {
            if (args.text("brightness")?.lowercase(Locale.ROOT) == "auto") {
                autoBrightness = true
            } else {
                brightness = args.wholeNumber("brightness")
                if (brightness == null || brightness !in 0..100) return Invalid("brightness must be a whole number from 0 to 100, or \"auto\".")
            }
        }
        var autoRotate: Boolean? = null
        if (args.hasValue("auto_rotate")) autoRotate = args.flag("auto_rotate") ?: return Invalid("auto_rotate must be true or false.")
        var timeout: Int? = null
        if (args.hasValue("screen_timeout_seconds")) {
            timeout = args.wholeNumber("screen_timeout_seconds")
            if (timeout == null || timeout < PhoneDisplay.MIN_TIMEOUT_SECONDS || timeout > PhoneDisplay.MAX_TIMEOUT_SECONDS) {
                return Invalid("screen_timeout_seconds must be a whole number from ${PhoneDisplay.MIN_TIMEOUT_SECONDS} to ${PhoneDisplay.MAX_TIMEOUT_SECONDS}.")
            }
        }
        if (brightness == null && !autoBrightness && autoRotate == null && timeout == null) {
            return Invalid("Give at least one of brightness, auto_rotate or screen_timeout_seconds.")
        }
        return Call.SetDisplay(brightness, autoBrightness, autoRotate, timeout)
    }

    /** Whether [key] was given, as anything but null. */
    private fun JsonObject.hasValue(key: String): Boolean {
        val value = this[key]
        return value != null && value !is JsonNull
    }

    private fun JsonObject.wholeNumber(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val value = primitive.doubleOrNull ?: return null
        if (value != Math.floor(value) || value > Int.MAX_VALUE || value < Int.MIN_VALUE) return null
        return value.toInt()
    }

    /** Text as sent, untrimmed, but not empty. */
    private fun JsonObject.raw(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull || !primitive.isString) return null
        return primitive.content.ifEmpty { null }
    }

    private fun JsonObject.text(key: String): String? = raw(key)?.trim()?.ifEmpty { null }

    private fun JsonObject.flag(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.booleanOrNull
    }

    private fun JsonObject.choice(key: String, allowed: List<String>): String? {
        val value = text(key)?.lowercase(Locale.ROOT) ?: return null
        return if (value in allowed) value else null
    }

    private fun notANumber(field: String): String =
        "$field must be a phone number, not a name. Look the person up with find_contact first."

    /** A phone number as the user or a contact gives it: digits (at least three) and dialling punctuation. */
    private fun JsonObject.number(key: String): String? {
        val value = text(key) ?: return null
        return if (isPhoneNumber(value)) value else null
    }

    /** Whether [value] is a phone number rather than a name: digits (at least three) and dialling punctuation. */
    fun isPhoneNumber(value: String): Boolean {
        var digits = 0
        for (character in value) {
            if (character.isDigit()) {
                digits += 1
            } else if (character !in " +-().*#/") {
                return false
            }
        }
        return digits >= 3
    }

    /** A label, cut to what the Clock app shows. */
    private fun JsonObject.label(): String? = text("label")?.take(60)
}
