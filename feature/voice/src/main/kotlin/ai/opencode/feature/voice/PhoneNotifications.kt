package ai.opencode.feature.voice

import android.app.Notification
import java.util.Locale

/**
 * The parts of replying to and tapping notifications that don't need Android (Android only):
 * which buttons ask first, finding a button by its title, and what the results say.
 * [PhoneNotificationListener] does the rest.
 */
internal object PhoneNotifications {
    /** What a notification showed a moment after a reply or a tap. */
    enum class Seen {
        /** The reply shows in the conversation. */
        shown,

        /** The app took the notification away. */
        cleared,

        /** The app changed the notification, without showing the reply. */
        changed,

        /** Nothing changed yet. */
        unchanged,
    }

    /**
     * Buttons that only mark, file away, quiet or play something, which run without asking. Any
     * other title (Delete, Like, Call back, Accept, Pay…) asks first.
     */
    private val harmlessTitles = setOf(
        "mark as read", "mark read", "read", "mark as unread", "mark unread",
        "archive", "mute", "unmute", "snooze", "later", "remind me later",
        "pause", "play", "resume", "next", "previous", "skip", "stop",
        "dismiss", "ok", "okay", "got it", "hide", "open", "view", "show", "details",
    )

    /**
     * Whether tapping a button needs the user's yes first. Android's own meaning for it wins
     * (mark read, archive and mute don't; delete, reply, call and thumbs up do); without one,
     * only [harmlessTitles] skip the card.
     */
    fun asksFirst(semantic: Int, title: String): Boolean {
        when (semantic) {
            Notification.Action.SEMANTIC_ACTION_MARK_AS_READ,
            Notification.Action.SEMANTIC_ACTION_MARK_AS_UNREAD,
            Notification.Action.SEMANTIC_ACTION_ARCHIVE,
            Notification.Action.SEMANTIC_ACTION_MUTE,
            Notification.Action.SEMANTIC_ACTION_UNMUTE,
            -> return false
            Notification.Action.SEMANTIC_ACTION_NONE -> return normalize(title) !in harmlessTitles
            else -> return true
        }
    }

    /**
     * The index of the button called [wanted]: its title, ignoring case and a trailing “…”, else
     * the only title that contains it. Null when none matches, or several do.
     */
    fun findButton(titles: List<String>, wanted: String): Int? {
        val target = normalize(wanted)
        if (target.isEmpty()) return null
        for (index in titles.indices) {
            if (normalize(titles[index]) == target) return index
        }
        var found: Int? = null
        for (index in titles.indices) {
            if (!normalize(titles[index]).contains(target)) continue
            if (found != null) return null
            found = index
        }
        return found
    }

    /** “That Gmail notification has no “Delete” button. Its buttons: Archive, Reply.” */
    fun noButton(app: String, wanted: String, titles: List<String>): String {
        if (titles.isEmpty()) return "That $app notification has no buttons. Use “open” to open it."
        return "That $app notification has no “$wanted” button. Its buttons: ${titles.joinToString(", ")}. Or use “open” to open it."
    }

    /** “to Sam in WhatsApp”, or “in WhatsApp” without a conversation. */
    private fun where(app: String, conversation: String?): String =
        if (conversation.isNullOrBlank()) "in $app" else "to $conversation in $app"

    /** The reply's result: “sent” only when the app shows it, or took the notification away. */
    fun replied(app: String, conversation: String?, text: String, seen: Seen): String = when (seen) {
        Seen.shown -> "Replied ${where(app, conversation)}: “$text”. $app shows it in the conversation."
        Seen.cleared -> "Replied ${where(app, conversation)}: “$text”. $app took the reply and cleared the notification."
        Seen.changed, Seen.unchanged -> {
            val forWho = if (conversation.isNullOrBlank()) "" else " for $conversation"
            "Gave $app the reply$forWho: “$text”. $app hasn’t shown it in the conversation yet, so it may still be sending; don’t say it was sent."
        }
    }

    /** “Tapped “Mark as read” on the Messages notification “Sam”. Messages cleared it.” */
    fun tapped(app: String, title: String?, button: String, seen: Seen, opened: Boolean): String {
        val notification = if (title.isNullOrBlank()) "the $app notification" else "the $app notification “$title”"
        val sentence = "Tapped “$button” on $notification."
        if (opened) return "$sentence It opened $app."
        return when (seen) {
            Seen.cleared -> "$sentence $app cleared the notification."
            Seen.shown, Seen.changed -> "$sentence $app updated the notification."
            Seen.unchanged -> "$sentence The notification is still showing, unchanged."
        }
    }

    /** “30 minutes”, “2 hours”, “1 hour 15 minutes”: a snooze as the result says it. */
    fun snoozeLength(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        val parts = mutableListOf<String>()
        if (hours > 0) parts += if (hours == 1) "1 hour" else "$hours hours"
        if (rest > 0) parts += if (rest == 1) "1 minute" else "$rest minutes"
        return parts.joinToString(" ")
    }

    private fun normalize(title: String): String =
        title.trim().trimEnd('…', '.', '!').trim().lowercase(Locale.ROOT)
}
