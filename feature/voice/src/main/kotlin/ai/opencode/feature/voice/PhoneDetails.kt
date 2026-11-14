package ai.opencode.feature.voice

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How the phone tools shape what they return (Android only): a sentence saying what happened,
 * then everything useful the phone knows about it as one line of JSON. Lists and long text are
 * capped and say how much was left out. Nothing here is ever logged.
 */
object PhoneDetails {
    val minute: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    /** “Paused.” then the details on the next line; the sentence alone when there are none. */
    fun text(sentence: String, details: Map<String, JsonElement?>): String {
        val kept = compact(details)
        if (kept.isEmpty()) return sentence
        return sentence + "\n" + JsonObject(kept)
    }

    /** The details without empty values, so the model never reads a null as a fact. */
    fun compact(details: Map<String, JsonElement?>): Map<String, JsonElement> {
        val kept = linkedMapOf<String, JsonElement>()
        for ((key, value) in details) {
            if (value == null) continue
            if (value is JsonPrimitive && value.isString && value.content.isBlank()) continue
            if (value is JsonArray && value.isEmpty()) continue
            if (value is JsonObject && value.isEmpty()) continue
            kept[key] = value
        }
        return kept
    }

    fun json(details: Map<String, JsonElement?>): String = JsonObject(compact(details)).toString()

    /** [text] cut to [max] characters, saying how many more there were. */
    fun cap(text: String?, max: Int): String? {
        val trimmed = text?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        if (trimmed.length <= max) return trimmed
        return trimmed.take(max).trimEnd() + "… (+${trimmed.length - max} more characters)"
    }

    /** “+3 more” for a list cut to [shown] of [total], else null. */
    fun more(total: Int, shown: Int): JsonPrimitive? {
        if (total <= shown) return null
        return JsonPrimitive("+${total - shown} more")
    }

    fun string(value: String?): JsonPrimitive? = value?.trim()?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }

    fun local(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String = minute.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** When a timer started [now] for [seconds] rings. */
    fun timerEnds(now: LocalDateTime, seconds: Int): String = minute.format(now.plusSeconds(seconds.toLong()))

    /**
     * When an alarm for [hour]:[minute] next rings after [now]: today if it's still ahead, else
     * tomorrow; with [days] (mon…sun), the first of those days.
     */
    fun nextRing(now: LocalDateTime, hour: Int, minute: Int, days: List<String>): LocalDateTime {
        val today = now.toLocalDate().atTime(hour, minute)
        for (offset in 0..7) {
            val candidate = today.plusDays(offset.toLong())
            if (!candidate.isAfter(now)) continue
            if (days.isEmpty() || dayName(candidate.dayOfWeek) in days) return candidate
        }
        return today.plusDays(1)
    }

    private fun dayName(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "mon"
        DayOfWeek.TUESDAY -> "tue"
        DayOfWeek.WEDNESDAY -> "wed"
        DayOfWeek.THURSDAY -> "thu"
        DayOfWeek.FRIDAY -> "fri"
        DayOfWeek.SATURDAY -> "sat"
        DayOfWeek.SUNDAY -> "sun"
    }

    /** A meeting link in an event's location or notes (Meet, Zoom, Teams, Webex, …), else null. */
    fun meetingLink(vararg texts: String?): String? {
        val pattern = Regex(
            "https?://[^\\s<>\"')]*(meet\\.google\\.com|zoom\\.us|teams\\.microsoft\\.com|teams\\.live\\.com|webex\\.com|whereby\\.com|gotomeeting\\.com|chime\\.aws|meet\\.jit\\.si)[^\\s<>\"')]*",
            RegexOption.IGNORE_CASE,
        )
        for (text in texts) {
            if (text == null) continue
            val found = pattern.find(text) ?: continue
            return found.value.trimEnd('.', ',', ';')
        }
        return null
    }
}
