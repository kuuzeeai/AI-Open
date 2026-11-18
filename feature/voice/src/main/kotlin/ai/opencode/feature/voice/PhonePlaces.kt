package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The calendar, maps and the phone's location for [PhoneToolsClient] (Android only). Runs on the main thread. */
internal object PhonePlaces {
    private const val MAX_EVENTS = 50
    private const val MAX_ATTENDEES = 15
    private const val MAX_DESCRIPTION = 600
    private const val FRESH_MILLIS = 2 * 60 * 1000L
    private val fixWait = 10.seconds
    private val addressWait = 3.seconds
    private val day: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** Straight into the calendar with permission; otherwise the calendar app's form, filled in. Never asks for the permission. */
    fun addEvent(call: PhoneTools.Call.AddCalendarEvent): PhoneTools.Result {
        val context = dependency(AppContext)
        val canWrite = PhoneAccess.isGranted(context, PhoneAccess.calendar)
        val zone = ZoneId.systemDefault()
        // All-day events sit on UTC midnights, as the calendar provider wants them.
        val eventZone = if (call.allDay) ZoneOffset.UTC else zone
        val begin = call.start.atZone(eventZone).toInstant().toEpochMilli()
        val end = call.end.atZone(eventZone).toInstant().toEpochMilli()
        val shown = span(call)
        if (canWrite) {
            val calendars = writableCalendars()
            val calendar = pickCalendar(calendars, call.calendar)
            if (calendar == null && calendars.isNotEmpty()) {
                return PhoneTools.Result.failure(whichCalendar(calendars, call.calendar))
            }
            if (calendar != null) {
                val values = ContentValues()
                values.put(CalendarContract.Events.CALENDAR_ID, calendar.id)
                values.put(CalendarContract.Events.TITLE, call.title)
                values.put(CalendarContract.Events.DTSTART, begin)
                values.put(CalendarContract.Events.DTEND, end)
                values.put(CalendarContract.Events.EVENT_TIMEZONE, if (call.allDay) "UTC" else zone.id)
                values.put(CalendarContract.Events.ALL_DAY, if (call.allDay) 1 else 0)
                if (call.location != null) values.put(CalendarContract.Events.EVENT_LOCATION, call.location)
                if (call.notes != null) values.put(CalendarContract.Events.DESCRIPTION, call.notes)
                val added = runCatching { context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
                if (added != null) {
                    val at = if (call.location == null) "" else " at ${call.location}"
                    val details = mapOf(
                        "start" to JsonPrimitive(PhoneDetails.minute.format(call.start)),
                        "end" to JsonPrimitive(PhoneDetails.minute.format(call.end)),
                        "all_day" to JsonPrimitive(call.allDay),
                        "time_zone" to if (call.allDay) null else JsonPrimitive(zone.id),
                        "calendar" to JsonPrimitive(calendar.name),
                        "account" to if (calendar.syncs) JsonPrimitive(calendar.account) else null,
                    )
                    return PhoneTools.Result(
                        PhoneDetails.text("Added “${call.title}” to the calendar (${calendar.label}): $shown$at.", details),
                        action = PhoneTools.Action.other("Added to the calendar"),
                    )
                }
            }
        }
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, call.allDay)
            .putExtra(CalendarContract.Events.TITLE, call.title)
        if (call.location != null) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, call.location)
        if (call.notes != null) intent.putExtra(CalendarContract.Events.DESCRIPTION, call.notes)
        val app = PhoneLaunch.handlerName(intent)
        return when (PhoneLaunch.start(intent)) {
            PhoneLaunch.Launch.started -> PhoneTools.Result(
                if (app == null) {
                    "The phone is asking the user which app to add “${call.title}” ($shown) with. They pick a calendar app, then tap save."
                } else {
                    "Opened $app with “${call.title}” filled in for $shown. The user has to tap save."
                },
                action = PhoneTools.Action.other("Opened a new event"),
            )
            PhoneLaunch.Launch.blocked -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
            PhoneLaunch.Launch.noApp -> PhoneTools.Result.failure("No calendar app on this phone can add events.")
        }
    }

    /**
     * Every visible event in the range, oldest first: times, time zone, place, description, the
     * calendar and its account, organizer, attendees and their answers, how it repeats, busy or
     * free, reminders and any meeting link. Capped at [MAX_EVENTS] with a count of the rest.
     */
    fun listEvents(call: PhoneTools.Call.ListCalendarEvents): PhoneTools.Result {
        val context = dependency(AppContext)
        PhoneAccess.require(context, PhoneAccess.calendar.permissions, "read the calendar")?.let { return it }
        val zone = ZoneId.systemDefault()
        val begin = call.start.atZone(zone).toInstant().toEpochMilli()
        val end = call.end.atZone(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uri, begin)
        ContentUris.appendId(uri, end)
        val events = mutableListOf<JsonObject>()
        var total = 0
        context.contentResolver.query(
            uri.build(),
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.EVENT_TIMEZONE,
                CalendarContract.Instances.OWNER_ACCOUNT,
                CalendarContract.Instances.ORGANIZER,
                CalendarContract.Instances.RRULE,
                CalendarContract.Instances.AVAILABILITY,
                CalendarContract.Instances.STATUS,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            ),
            "${CalendarContract.Instances.VISIBLE} = 1",
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                total += 1
                if (events.size >= MAX_EVENTS) continue
                val allDay = cursor.getInt(3) == 1
                // All-day instances are stored at UTC midnights.
                val shownZone = if (allDay) ZoneOffset.UTC else zone
                val eventID = cursor.getLong(6)
                val location = cursor.getString(4)
                val description = cursor.getString(7)
                val account = cursor.getString(9)
                val organizer = cursor.getString(10)
                // A repeating event's occurrences share its ID, so the occurrence's start picks one.
                val repeats = cursor.getString(11) != null
                val id = PhoneTools.EventID(eventID, if (repeats) cursor.getLong(1) else null)
                val details = mapOf(
                    "event_id" to JsonPrimitive(id.toString()),
                    "title" to JsonPrimitive(cursor.getString(0) ?: ""),
                    "start" to JsonPrimitive(PhoneDetails.local(cursor.getLong(1), shownZone)),
                    "end" to JsonPrimitive(PhoneDetails.local(cursor.getLong(2), shownZone)),
                    "all_day" to JsonPrimitive(allDay),
                    "time_zone" to if (allDay) null else PhoneDetails.string(cursor.getString(8)),
                    "location" to PhoneDetails.string(location),
                    "description" to PhoneDetails.string(PhoneDetails.cap(description, MAX_DESCRIPTION)),
                    "meeting_link" to PhoneDetails.string(PhoneDetails.meetingLink(location, description)),
                    "calendar" to PhoneDetails.string(cursor.getString(5)),
                    "account" to PhoneDetails.string(account),
                    "organizer" to if (organizer != null && organizer != account) PhoneDetails.string(organizer) else null,
                    "repeats" to PhoneDetails.string(cursor.getString(11)),
                    "availability" to JsonPrimitive(
                        when (cursor.getInt(12)) {
                            CalendarContract.Events.AVAILABILITY_FREE -> "free"
                            CalendarContract.Events.AVAILABILITY_TENTATIVE -> "tentative"
                            else -> "busy"
                        },
                    ),
                    "cancelled" to if (cursor.getInt(13) == CalendarContract.Events.STATUS_CANCELED) JsonPrimitive(true) else null,
                    "my_response" to PhoneDetails.string(response(cursor.getInt(14))),
                    "attendees" to attendees(context, eventID),
                    "reminders_minutes_before" to reminders(context, eventID),
                )
                events += JsonObject(PhoneDetails.compact(details))
            }
        }
        val result = linkedMapOf<String, JsonElement>("events" to JsonArray(events))
        val more = PhoneDetails.more(total, MAX_EVENTS)
        if (more != null) result["more_events"] = more
        return PhoneTools.Result(JsonObject(result).toString())
    }

    private fun response(status: Int): String? = when (status) {
        CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> "accepted"
        CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> "declined"
        CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> "maybe"
        CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> "invited"
        else -> null
    }

    /** Up to [MAX_ATTENDEES] people with their answers, then a count of the rest. */
    private fun attendees(context: android.content.Context, eventID: Long): JsonArray {
        val people = mutableListOf<JsonElement>()
        var total = 0
        context.contentResolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(
                CalendarContract.Attendees.ATTENDEE_NAME,
                CalendarContract.Attendees.ATTENDEE_EMAIL,
                CalendarContract.Attendees.ATTENDEE_STATUS,
                CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
            ),
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventID.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                total += 1
                if (people.size >= MAX_ATTENDEES) continue
                val person = mapOf(
                    "name" to PhoneDetails.string(cursor.getString(0)),
                    "email" to PhoneDetails.string(cursor.getString(1)),
                    "response" to PhoneDetails.string(response(cursor.getInt(2))),
                    "organizer" to if (cursor.getInt(3) == CalendarContract.Attendees.RELATIONSHIP_ORGANIZER) JsonPrimitive(true) else null,
                )
                people += JsonObject(PhoneDetails.compact(person))
            }
        }
        val more = PhoneDetails.more(total, MAX_ATTENDEES)
        if (more != null) people += more
        return JsonArray(people)
    }

    private fun reminders(context: android.content.Context, eventID: Long): JsonArray {
        val minutes = mutableListOf<JsonPrimitive>()
        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventID.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext() && minutes.size < 5) {
                val value = cursor.getInt(0)
                if (value >= 0) minutes += JsonPrimitive(value)
            }
        }
        return JsonArray(minutes)
    }

    /** Directions through a Maps URL (Google Maps or the browser); a search or place through `geo:` (any maps app). */
    fun maps(call: PhoneTools.Call.OpenMaps): PhoneTools.Result {
        val search = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(call.place)))
        if (call.directions) {
            val uri = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
                .appendQueryParameter("api", "1")
                .appendQueryParameter("destination", call.place)
                .appendQueryParameter("travelmode", call.mode)
                .build()
            val intent = Intent(Intent.ACTION_VIEW, uri)
            val kind = when (call.mode) {
                "walking" -> "walking"
                "transit" -> "transit"
                "bicycling" -> "cycling"
                else -> "driving"
            }
            val app = PhoneLaunch.handlerName(intent) ?: "Maps"
            when (PhoneLaunch.start(intent)) {
                PhoneLaunch.Launch.started -> return PhoneTools.Result(
                    "Opened $kind directions to “${call.place}” in $app.",
                    action = PhoneTools.Action.other("Opened directions"),
                )
                PhoneLaunch.Launch.blocked -> return PhoneTools.Result.failure(PhoneTools.BLOCKED)
                // Other maps apps don't take the URL: show the place instead.
                PhoneLaunch.Launch.noApp -> Unit
            }
        }
        val app = PhoneLaunch.handlerName(search) ?: "Maps"
        return when (PhoneLaunch.start(search)) {
            PhoneLaunch.Launch.started -> PhoneTools.Result(
                "Opened $app searching for “${call.place}”.",
                action = PhoneTools.Action.other("Opened Maps"),
            )
            PhoneLaunch.Launch.blocked -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
            PhoneLaunch.Launch.noApp -> PhoneTools.Result.failure("No maps app on this phone can show that.")
        }
    }

    /** A fresh fix within [fixWait], or a recent one; coarse permission alone gives a rounded place. */
    suspend fun location(): PhoneTools.Result {
        val context = dependency(AppContext)
        if (!PhoneAccess.isGranted(context, PhoneAccess.location)) {
            PhoneAccess.require(context, PhoneAccess.location.permissions, "use the location")?.let { return it }
        }
        val locations = context.getSystemService(LocationManager::class.java)
            ?: return PhoneTools.Result.failure("The phone couldn’t find its location.")
        if (!locations.isLocationEnabled) return PhoneTools.Result.failure("Location is off on the phone.")
        val fine = PhoneAccess.granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val providers = mutableListOf<String>()
        if (locations.hasProvider(LocationManager.FUSED_PROVIDER)) providers += LocationManager.FUSED_PROVIDER
        providers += LocationManager.NETWORK_PROVIDER
        if (fine) providers += LocationManager.GPS_PROVIDER
        var fix: Location? = null
        try {
            for (provider in providers) {
                if (!locations.isProviderEnabled(provider) && provider != LocationManager.FUSED_PROVIDER) continue
                fix = withTimeoutOrNull(fixWait) { current(locations, provider) }
                if (fix != null) break
            }
            if (fix == null) {
                for (provider in providers) {
                    val last = locations.getLastKnownLocation(provider) ?: continue
                    if (System.currentTimeMillis() - last.time < FRESH_MILLIS) {
                        fix = last
                        break
                    }
                }
            }
        } catch (_: SecurityException) {
            return PhoneTools.Result.failure(PhoneTools.needsPermission("use the location"))
        }
        val found = fix ?: return PhoneTools.Result.failure("The phone couldn’t find its location.")
        var latitude = found.latitude
        var longitude = found.longitude
        if (!fine) {
            latitude = (latitude * 1000).roundToInt() / 1000.0
            longitude = (longitude * 1000).roundToInt() / 1000.0
        }
        val place = withTimeoutOrNull(addressWait) { address(context, latitude, longitude) }
        val ageSeconds = ((System.currentTimeMillis() - found.time) / 1000).coerceAtLeast(0)
        val json = mapOf(
            "latitude" to JsonPrimitive(latitude),
            "longitude" to JsonPrimitive(longitude),
            "accuracy_m" to JsonPrimitive(found.accuracy.roundToInt()),
            "precise" to JsonPrimitive(fine),
            "altitude_m" to if (found.hasAltitude()) JsonPrimitive(found.altitude.roundToInt()) else null,
            "speed_kmh" to if (found.hasSpeed()) JsonPrimitive((found.speed * 3.6).roundToInt()) else null,
            "heading_degrees" to if (found.hasBearing() && found.hasSpeed() && found.speed > 0.5f) JsonPrimitive(found.bearing.roundToInt()) else null,
            "address" to PhoneDetails.string(place?.getAddressLine(0)),
            "place_name" to PhoneDetails.string(placeName(place)),
            "street" to PhoneDetails.string(listOfNotNull(place?.subThoroughfare, place?.thoroughfare).joinToString(" ")),
            "neighborhood" to PhoneDetails.string(place?.subLocality),
            "city" to PhoneDetails.string(place?.locality ?: place?.subAdminArea),
            "region" to PhoneDetails.string(place?.adminArea),
            "postal_code" to PhoneDetails.string(place?.postalCode),
            "country" to PhoneDetails.string(place?.countryName),
            "country_code" to PhoneDetails.string(place?.countryCode),
            "time" to JsonPrimitive(PhoneDetails.local(found.time)),
            "age_seconds" to JsonPrimitive(ageSeconds),
            "source" to PhoneDetails.string(found.provider),
            "maps_url" to JsonPrimitive(PhoneShare.mapsUrl(latitude, longitude)),
        )
        return PhoneTools.Result(PhoneDetails.json(json), action = PhoneTools.Action.other("Shared the location"))
    }

    /** A named place the geocoder knows (a park, a shop), not just the house number again. */
    private fun placeName(place: android.location.Address?): String? {
        val name = place?.featureName?.trim() ?: return null
        if (name.isEmpty() || name == place.subThoroughfare || name == place.postalCode) return null
        if (name.all { it.isDigit() || it == '-' || it == ' ' }) return null
        if (place.getAddressLine(0)?.startsWith(name) == true && name.length < 6) return null
        return name
    }

    private suspend fun current(locations: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                locations.getCurrentLocation(provider, signal, dependency(AppContext).mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (_: SecurityException) {
                continuation.resume(null)
            } catch (_: IllegalArgumentException) {
                continuation.resume(null)
            }
        }

    private suspend fun address(context: android.content.Context, latitude: Double, longitude: Double): android.location.Address? {
        if (!Geocoder.isPresent()) return null
        return suspendCancellableCoroutine { continuation ->
            try {
                Geocoder(context).getFromLocation(
                    latitude,
                    longitude,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) {
                            val first = addresses.firstOrNull()
                            if (continuation.isActive) continuation.resume(first)
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /** A visible calendar the phone may add events to. [syncs] is false for one kept on this phone only. */
    internal data class WritableCalendar(
        val id: Long,
        val name: String,
        val account: String,
        val syncs: Boolean,
        val primary: Boolean,
    ) {
        /** “Family, sam@example.com”, or the name alone when it is the account's own calendar. */
        val label: String get() {
            if (!syncs) return "$name, on this phone only"
            if (name == account) return name
            return "$name, $account"
        }
    }

    private fun writableCalendars(): List<WritableCalendar> {
        val context = dependency(AppContext)
        val calendars = mutableListOf<WritableCalendar>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.IS_PRIMARY,
            ),
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                calendars += WritableCalendar(
                    id = cursor.getLong(0),
                    name = cursor.getString(1) ?: "Calendar",
                    account = cursor.getString(2) ?: "",
                    syncs = cursor.getString(3) != CalendarContract.ACCOUNT_TYPE_LOCAL,
                    primary = cursor.getInt(4) == 1,
                )
            }
        }
        return calendars
    }

    /**
     * The calendar a new event goes in. [wanted] names one by its name, its label or its account
     * (an account means its primary calendar). Without [wanted] it is the only account's primary
     * calendar, and a calendar kept on this phone only when no account has one. Null when
     * [wanted] names none or several, or when several fit and none was named.
     */
    internal fun pickCalendar(calendars: List<WritableCalendar>, wanted: String?): WritableCalendar? {
        if (wanted != null) {
            val named = mutableListOf<WritableCalendar>()
            val inAccount = mutableListOf<WritableCalendar>()
            val primaryInAccount = mutableListOf<WritableCalendar>()
            for (calendar in calendars) {
                if (calendar.name.equals(wanted, ignoreCase = true) || calendar.label.equals(wanted, ignoreCase = true)) {
                    named += calendar
                }
                if (calendar.account.equals(wanted, ignoreCase = true)) {
                    inAccount += calendar
                    if (calendar.primary) primaryInAccount += calendar
                }
            }
            if (named.isNotEmpty()) return named.singleOrNull()
            if (primaryInAccount.isNotEmpty()) return primaryInAccount.singleOrNull()
            return inAccount.singleOrNull()
        }
        val synced = mutableListOf<WritableCalendar>()
        val primarySynced = mutableListOf<WritableCalendar>()
        for (calendar in calendars) {
            if (!calendar.syncs) continue
            synced += calendar
            if (calendar.primary) primarySynced += calendar
        }
        if (primarySynced.isNotEmpty()) return primarySynced.singleOrNull()
        if (synced.isNotEmpty()) return synced.singleOrNull()
        return calendars.singleOrNull()
    }

    /** What the model reads when [pickCalendar] found no single calendar: the choices to ask the user about. */
    internal fun whichCalendar(calendars: List<WritableCalendar>, wanted: String?): String {
        val labels = mutableListOf<String>()
        for (calendar in calendars) {
            labels += calendar.label
        }
        val choices = "The calendars: ${labels.joinToString("; ")}."
        if (wanted == null) {
            return "The phone has several calendars and the user didn’t say which, so nothing was added. " +
                "Ask the user which one, then call again with calendar. $choices"
        }
        return "“$wanted” doesn’t name one calendar on the phone, so nothing was added. " +
            "Ask the user which one they mean, then call again with calendar. $choices"
    }

    /** “Thu 12 Nov, 19:00–20:00”, or “Thu 12 Nov, all day”. */
    private fun span(call: PhoneTools.Call.AddCalendarEvent): String {
        val date = day.format(call.start)
        if (call.allDay) {
            val last = call.end.minusDays(1)
            if (!last.toLocalDate().isAfter(call.start.toLocalDate())) return "$date, all day"
            return "$date to ${day.format(last)}, all day"
        }
        if (call.end.toLocalDate() == call.start.toLocalDate()) {
            return "$date, ${clock.format(call.start)}–${clock.format(call.end)}"
        }
        return "$date ${clock.format(call.start)} to ${day.format(call.end)} ${clock.format(call.end)}"
    }
}
