package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Changing and deleting calendar events for [PhoneToolsClient] (Android only). A repeating
 * event changes or loses only the one occurrence its `event_id` names, through an exception
 * (`Events.CONTENT_EXCEPTION_URI`), as the calendar apps' “this event” does. Runs on the main thread.
 */
internal object PhoneEvents {
    private val day: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private const val NOT_FOUND =
        "No event on the calendar has that event_id. It may have been deleted or moved: list the events again for a fresh one."
    private const val NEEDS_OCCURRENCE =
        "That event repeats, and the phone can only change or delete one occurrence at a time. " +
            "Use the event_id list_calendar_events gives for the occurrence, such as “482@1762963200000”."

    /** One event, or one occurrence of a repeating event, as the calendar holds it. */
    data class Event(
        val id: Long,
        val title: String,
        /** The event's (or the occurrence's) start and end, epoch milliseconds. */
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val timeZone: String?,
        val repeats: Boolean,
        val calendar: String,
        val canWrite: Boolean,
        /** An occurrence already changed on its own (an exception to a repeating event). */
        val isException: Boolean,
        /**
         * Whether the series has synced to its account (it has a sync id). Android's calendar
         * only keeps a changed occurrence apart from its series once it has; before that it
         * drops the rest of the series from the calendar.
         */
        val synced: Boolean,
    )

    /**
     * Before the delete card: the event's title and time for the card, or the answer that ends
     * the call without one (no such event, a read-only calendar). Without calendar access the
     * card shows no details, and the call asks for access after the user says yes.
     */
    fun prepareDelete(call: PhoneTools.Call.DeleteCalendarEvent): PhoneTools.Prepared {
        val context = dependency(AppContext)
        if (!PhoneAccess.isGranted(context, PhoneAccess.calendar)) return PhoneTools.ask(call)
        val event = find(context, call.event) ?: return PhoneTools.Prepared.Answer(PhoneTools.Result.failure(NOT_FOUND))
        val refused = refusal(call.event, event)
        if (refused != null) return PhoneTools.Prepared.Answer(refused)
        return PhoneTools.ask(call.copy(title = event.title, shown = shown(event)))
    }

    fun delete(call: PhoneTools.Call.DeleteCalendarEvent): PhoneTools.Result {
        val context = dependency(AppContext)
        PhoneAccess.require(context, PhoneAccess.calendar.permissions, "change the calendar")?.let { return it }
        val event = find(context, call.event) ?: return PhoneTools.Result.failure(NOT_FOUND)
        val refused = refusal(call.event, event)
        if (refused != null) return refused
        val resolver = context.contentResolver
        val deleted: Boolean
        if (event.repeats) {
            // A cancelled exception takes just this occurrence out of the series.
            val values = ContentValues()
            values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, event.begin)
            values.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, event.id)
            deleted = runCatching { resolver.insert(uri, values) }.getOrNull() != null
        } else if (event.isException) {
            // Deleting the exception would bring back the original occurrence; cancelling it doesn't.
            val values = ContentValues()
            values.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id)
            deleted = runCatching { resolver.update(uri, values, null, null) }.getOrDefault(0) > 0
        } else {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id)
            deleted = runCatching { resolver.delete(uri, null, null) }.getOrDefault(0) > 0
        }
        if (!deleted) return PhoneTools.Result.failure("The calendar didn’t let the phone delete “${event.title}”.")
        val text = if (event.repeats) {
            "Deleted the ${shown(event)} occurrence of “${event.title}” from the calendar (${event.calendar}). The rest of the series stays."
        } else {
            "Deleted “${event.title}” (${shown(event)}) from the calendar (${event.calendar})."
        }
        return PhoneTools.Result(text, action = PhoneTools.Action.other("Deleted an event"))
    }

    /**
     * Changes the event straight away with calendar access. Without it, opens the event in the
     * calendar app for the user to change, and asks for access for next time.
     */
    fun update(call: PhoneTools.Call.UpdateCalendarEvent): PhoneTools.Result {
        val context = dependency(AppContext)
        if (!PhoneAccess.isGranted(context, PhoneAccess.calendar)) return openToEdit(context, call.event)
        val event = find(context, call.event) ?: return PhoneTools.Result.failure(NOT_FOUND)
        val refused = refusal(call.event, event)
        if (refused != null) return refused

        // The event's times as the user sees them: all-day events sit on UTC midnights.
        val zone = ZoneId.systemDefault()
        val oldZone = if (event.allDay) ZoneOffset.UTC else zone
        val oldStart = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.begin), oldZone)
        val oldEnd = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.end), oldZone)

        // A date alone as the new start makes it all-day, as add_calendar_event does.
        val allDay = call.allDay ?: if (call.start != null) call.startIsDate else event.allDay
        val start: LocalDateTime
        val end: LocalDateTime
        if (allDay) {
            val firstDay = (call.start ?: oldStart).toLocalDate()
            val lastDay: LocalDate
            if (call.end != null) {
                lastDay = call.end.toLocalDate()
            } else if (event.allDay) {
                // Keep how many days it spans.
                val days = maxOf(1L, ChronoUnit.DAYS.between(oldStart.toLocalDate(), oldEnd.toLocalDate()))
                lastDay = firstDay.plusDays(days - 1)
            } else {
                lastDay = firstDay
            }
            start = firstDay.atStartOfDay()
            end = lastDay.plusDays(1).atStartOfDay()
        } else {
            start = call.start ?: oldStart
            if (call.end != null) {
                if (call.endIsDate) {
                    return PhoneTools.Result.failure("end needs a time, such as 2026-11-12T20:00, for an event that isn’t all day.")
                }
                end = call.end
            } else {
                // Keep its length; an all-day event becoming timed gets an hour.
                val length = if (event.allDay) Duration.ofHours(1) else Duration.between(oldStart, oldEnd)
                end = start.plus(length)
            }
        }
        if (!end.isAfter(start)) return PhoneTools.Result.failure("end must be after start.")

        val newZone = if (allDay) ZoneOffset.UTC else zone
        val begin = start.atZone(newZone).toInstant().toEpochMilli()
        val finish = end.atZone(newZone).toInstant().toEpochMilli()
        val timeZone = if (allDay) "UTC" else if (!event.allDay && event.timeZone != null) event.timeZone else zone.id
        val timesChanged = call.start != null || call.end != null || call.allDay != null

        val values = ContentValues()
        val changed = mutableListOf<String>()
        if (call.title != null) {
            values.put(CalendarContract.Events.TITLE, call.title)
            changed += "title"
        }
        if (call.location != null) {
            values.put(CalendarContract.Events.EVENT_LOCATION, call.location)
            changed += "location"
        }
        if (call.notes != null) {
            values.put(CalendarContract.Events.DESCRIPTION, call.notes)
            changed += "notes"
        }
        if (timesChanged) changed += "time"
        // An exception always carries its own times; a one-off event only when they change.
        if (timesChanged || event.repeats) {
            values.put(CalendarContract.Events.DTSTART, begin)
            if (event.repeats) {
                // The provider refuses an end time on an exception; it takes a length (RFC 5545).
                values.put(CalendarContract.Events.DURATION, if (allDay) "P${(finish - begin) / 86_400_000}D" else "P${(finish - begin) / 1000}S")
            } else {
                values.put(CalendarContract.Events.DTEND, finish)
            }
            values.put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
        }

        val resolver = context.contentResolver
        var newID = event.id
        val saved: Boolean
        if (event.repeats) {
            values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, event.begin)
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, event.id)
            val added = runCatching { resolver.insert(uri, values) }.getOrNull()
            val addedID = added?.lastPathSegment?.toLongOrNull()
            saved = addedID != null
            if (addedID != null) newID = addedID
        } else {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id)
            saved = runCatching { resolver.update(uri, values, null, null) }.getOrDefault(0) > 0
        }
        if (!saved) return PhoneTools.Result.failure("The calendar didn’t let the phone change “${event.title}”.")

        val title = call.title ?: event.title
        val shownSpan = span(start, end, allDay)
        val sentence = if (event.repeats) {
            "Changed this occurrence of “$title”: $shownSpan. The rest of the series stays as it was."
        } else {
            "Changed “$title”: $shownSpan."
        }
        val details = mapOf(
            "event_id" to JsonPrimitive(newID.toString()),
            "changed" to JsonArray(changed.map { JsonPrimitive(it) }),
            "start" to JsonPrimitive(PhoneDetails.minute.format(start)),
            "end" to JsonPrimitive(PhoneDetails.minute.format(if (allDay) end.minusDays(1) else end)),
            "all_day" to JsonPrimitive(allDay),
            "calendar" to JsonPrimitive(event.calendar),
        )
        return PhoneTools.Result(PhoneDetails.text(sentence, details), action = PhoneTools.Action.other("Changed an event"))
    }

    /** The event or occurrence [id] names; null when it's gone. */
    fun find(context: Context, id: PhoneTools.EventID): Event? {
        val resolver = context.contentResolver
        var event: Event? = null
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.id),
            arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_TIMEZONE,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.RDATE,
                CalendarContract.Events.CALENDAR_DISPLAY_NAME,
                CalendarContract.Events.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Events.DELETED,
                CalendarContract.Events.STATUS,
                CalendarContract.Events.ORIGINAL_ID,
                CalendarContract.Events._SYNC_ID,
                CalendarContract.Events.ORIGINAL_SYNC_ID,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            if (cursor.getInt(9) == 1) return null
            if (!cursor.isNull(10) && cursor.getInt(10) == CalendarContract.Events.STATUS_CANCELED) return null
            val begin = cursor.getLong(1)
            val end = if (cursor.isNull(2)) begin + Duration.ofHours(1).toMillis() else cursor.getLong(2)
            event = Event(
                id = id.id,
                title = cursor.getString(0) ?: "",
                begin = begin,
                end = end,
                allDay = cursor.getInt(3) == 1,
                timeZone = cursor.getString(4),
                repeats = cursor.getString(5) != null || cursor.getString(6) != null,
                calendar = cursor.getString(7) ?: "Calendar",
                canWrite = cursor.getInt(8) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                isException = !cursor.isNull(11),
                // A series has its own sync id; an exception names its series' one.
                synced = if (cursor.isNull(11)) !cursor.isNull(12) else !cursor.isNull(13),
            )
        }
        val found = event ?: return null
        if (!found.repeats || id.occurrence == null) return found

        // The occurrence's own times, from the expanded instances.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uri, id.occurrence)
        ContentUris.appendId(uri, id.occurrence + 1)
        resolver.query(
            uri.build(),
            arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END),
            "${CalendarContract.Instances.EVENT_ID} = ? AND ${CalendarContract.Instances.BEGIN} = ?",
            arrayOf(id.id.toString(), id.occurrence.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return found.copy(begin = cursor.getLong(0), end = cursor.getLong(1))
        }
        return null
    }

    /** Why [event] can't be changed or deleted through [id]; null when it can. */
    private fun refusal(id: PhoneTools.EventID, event: Event): PhoneTools.Result? {
        if (event.repeats && id.occurrence == null) return PhoneTools.Result.failure(NEEDS_OCCURRENCE)
        if ((event.repeats || event.isException) && !event.synced) {
            return PhoneTools.Result.failure(
                "“${event.title}” repeats, and its calendar (${event.calendar}) hasn’t synced the series to an account, " +
                    "so the phone can’t change or delete one occurrence without losing the rest. " +
                    "The user can change it in their calendar app.",
            )
        }
        if (!event.canWrite) {
            return PhoneTools.Result.failure(
                "“${event.title}” is on ${event.calendar}, a read-only calendar, so the phone can’t change or delete it.",
            )
        }
        return null
    }

    /** The calendar app's own screen for the event, when the phone can't change it itself. */
    private fun openToEdit(context: Context, id: PhoneTools.EventID): PhoneTools.Result {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.id)
        var intent = Intent(Intent.ACTION_EDIT, uri)
        if (id.occurrence != null) intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, id.occurrence)
        var launch = PhoneLaunch.start(intent)
        if (launch == PhoneLaunch.Launch.noApp) {
            // Some calendar apps only show an event; editing is a tap away there.
            intent = Intent(Intent.ACTION_VIEW, uri)
            if (id.occurrence != null) intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, id.occurrence)
            launch = PhoneLaunch.start(intent)
        }
        return when (launch) {
            PhoneLaunch.Launch.started -> {
                PhoneAccess.askForNextTime(context, PhoneAccess.calendar.permissions)
                val app = PhoneLaunch.handlerName(intent) ?: "the calendar app"
                PhoneTools.Result(
                    "OpenCode doesn’t have calendar access, so it opened the event in $app for the user to change. " +
                        "Nothing has changed yet. The phone is asking the user to allow calendar access.",
                    action = PhoneTools.Action.other("Opened the event"),
                )
            }
            PhoneLaunch.Launch.blocked -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
            PhoneLaunch.Launch.noApp -> PhoneTools.Result.failure("No calendar app on this phone can open events.")
        }
    }

    /** “Thu 12 Nov, 19:00–20:00”, or “Thu 12 Nov, all day”, in the phone's time zone. */
    private fun shown(event: Event): String {
        val zone = if (event.allDay) ZoneOffset.UTC else ZoneId.systemDefault()
        val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.begin), zone)
        val end = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.end), zone)
        return span(start, end, event.allDay)
    }

    private fun span(start: LocalDateTime, end: LocalDateTime, allDay: Boolean): String {
        val date = day.format(start)
        if (allDay) {
            val last = end.minusDays(1)
            if (!last.toLocalDate().isAfter(start.toLocalDate())) return "$date, all day"
            return "$date to ${day.format(last)}, all day"
        }
        if (end.toLocalDate() == start.toLocalDate()) return "$date, ${clock.format(start)}–${clock.format(end)}"
        return "$date ${clock.format(start)} to ${day.format(end)} ${clock.format(end)}"
    }
}
