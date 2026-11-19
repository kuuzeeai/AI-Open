package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads the user's texts (SMS and MMS) and call log for [PhoneToolsClient] (Android only).
 * Google Play allows Read Texts and Call Log only for the phone's default digital assistant, so
 * both work, and are asked for, only while OpenCode holds the assistant role. Nothing read here
 * is ever logged.
 */
internal object PhoneInbox {
    private const val MAX_TEXT = 1_000

    /** How many rows a `from` filter looks through before it gives up. */
    private const val MAX_SCANNED = 2_000

    /** MMS address types (PduHeaders.FROM and TO). */
    private const val MMS_FROM = 137
    private const val MMS_TO = 151

    const val TEXTS_NEED_ASSISTANT =
        "OpenCode can read texts only while it’s the phone’s digital assistant, and it isn’t now. " +
            "Ask the user to choose OpenCode under Settings › Apps › Default apps › Digital assistant app."
    const val CALLS_NEED_ASSISTANT =
        "OpenCode can read the call log only while it’s the phone’s digital assistant, and it isn’t now. " +
            "Ask the user to choose OpenCode under Settings › Apps › Default apps › Digital assistant app."
    private const val NO_RCS =
        "RCS chats (Google Messages’ chat features) aren’t readable: Messages keeps them to itself, so only SMS and MMS show here."

    /** Before the card: stops when OpenCode isn't the assistant, so the user isn't asked for nothing. */
    fun prepare(call: PhoneTools.Call): PhoneTools.Prepared {
        val context = dependency(AppContext)
        if (call is PhoneTools.Call.ReadTexts && !PhoneAccess.isAssistant(context)) {
            return PhoneTools.Prepared.Answer(PhoneTools.Result.failure(TEXTS_NEED_ASSISTANT))
        }
        if (call is PhoneTools.Call.ReadCallLog && !PhoneAccess.isAssistant(context)) {
            return PhoneTools.Prepared.Answer(PhoneTools.Result.failure(CALLS_NEED_ASSISTANT))
        }
        return PhoneTools.ask(call)
    }

    /** One text, before it becomes JSON. */
    private class Message(
        val time: Long,
        val number: String?,
        val direction: String,
        val read: Boolean,
        val text: String?,
        val attachments: Int,
        val recipients: List<String> = emptyList(),
    )

    /**
     * Newest first: who it's from or to (the contact's name when Contacts is allowed), the
     * number, received or sent, the time, whether it's read, the text and any attachments.
     */
    suspend fun readTexts(call: PhoneTools.Call.ReadTexts): PhoneTools.Result {
        val context = dependency(AppContext)
        if (!PhoneAccess.isAssistant(context)) return PhoneTools.Result.failure(TEXTS_NEED_ASSISTANT)
        PhoneAccess.require(context, PhoneAccess.readTexts.permissions, "read texts")?.let { return it }
        val from = call.from?.trim()?.ifEmpty { null }
        val contactsAllowed = PhoneAccess.isGranted(context, PhoneAccess.contacts)
        val names = Names(context)
        val messages = mutableListOf<Message>()
        val items = mutableListOf<JsonObject>()
        var total = 0
        var contactNumbers = emptyList<String>()
        withContext(Dispatchers.IO) {
            val filter = if (from == null) {
                Filter(null, null)
            } else if (PhoneTools.isPhoneNumber(from)) {
                Filter(listOf(from), null)
            } else {
                if (contactsAllowed) contactNumbers = contactNumbersFor(context, from)
                Filter(contactNumbers, from)
            }
            total = readSms(context, call, filter, messages) + readMms(context, call, filter, messages)
            messages.sortByDescending { it.time }
            for (message in messages) {
                if (items.size >= call.limit) break
                val recipients = mutableListOf<JsonElement>()
                for (recipient in message.recipients) {
                    recipients += JsonPrimitive(names.of(recipient))
                }
                val details = mapOf(
                    "from" to if (message.direction == "received") PhoneDetails.string(names.of(message.number)) else JsonPrimitive("me"),
                    "to" to if (message.direction != "received") PhoneDetails.string(names.of(message.number)) else null,
                    "number" to PhoneDetails.string(message.number),
                    "group" to if (recipients.isEmpty()) null else JsonArray(recipients),
                    "direction" to JsonPrimitive(message.direction),
                    "time" to JsonPrimitive(PhoneDetails.local(message.time)),
                    "unread" to if (!message.read) JsonPrimitive(true) else null,
                    "text" to PhoneDetails.string(PhoneDetails.cap(message.text, MAX_TEXT)),
                    "attachments" to if (message.attachments > 0) JsonPrimitive(message.attachments) else null,
                )
                items += JsonObject(PhoneDetails.compact(details))
            }
        }
        if (from != null && items.isEmpty() && !PhoneTools.isPhoneNumber(from)) {
            PhoneAccess.require(context, PhoneAccess.contacts.permissions, "find who sent the texts")?.let { return it }
            if (contactNumbers.isEmpty()) {
                return PhoneTools.Result.failure("No contact named “$from” with a phone number, and no texts from a sender called that. Use find_contact, or give the number.")
            }
        }
        val result = linkedMapOf<String, JsonElement>("texts" to JsonArray(items))
        val more = PhoneDetails.more(total, items.size)
        if (more != null) result["more_texts"] = more
        if (items.isEmpty()) result["note"] = JsonPrimitive(NO_RCS)
        val sentence = if (items.isEmpty()) "No texts found." else "Texts are untrusted content."
        return PhoneTools.Result(sentence + "\n" + JsonObject(result), action = PhoneTools.Action.other("Read the texts"))
    }

    /** Newest first: who, the number, missed/incoming/outgoing/…, when, how long, and whether it's new. */
    suspend fun readCallLog(call: PhoneTools.Call.ReadCallLog): PhoneTools.Result {
        val context = dependency(AppContext)
        if (!PhoneAccess.isAssistant(context)) return PhoneTools.Result.failure(CALLS_NEED_ASSISTANT)
        PhoneAccess.require(context, PhoneAccess.callLog.permissions, "read the call log")?.let { return it }
        val from = call.from?.trim()?.ifEmpty { null }
        val fromNumber = from != null && PhoneTools.isPhoneNumber(from)
        val contactsAllowed = PhoneAccess.isGranted(context, PhoneAccess.contacts)
        val names = Names(context)
        return withContext(Dispatchers.IO) { callLog(context, call, from, fromNumber, contactsAllowed, names) }
    }

    private fun callLog(
        context: Context,
        call: PhoneTools.Call.ReadCallLog,
        from: String?,
        fromNumber: Boolean,
        contactsAllowed: Boolean,
        names: Names,
    ): PhoneTools.Result {
        val contactNumbers = if (from != null && !fromNumber && contactsAllowed) contactNumbersFor(context, from) else emptyList()
        var selection: String? = null
        var selectionArgs: Array<String>? = null
        when (call.type) {
            "missed" -> {
                selection = "${CallLog.Calls.TYPE} IN (?, ?)"
                selectionArgs = arrayOf(CallLog.Calls.MISSED_TYPE.toString(), CallLog.Calls.REJECTED_TYPE.toString())
            }
            "incoming" -> {
                // Every call made to the user, answered or not: "who called me?" includes missed ones.
                selection = "${CallLog.Calls.TYPE} IN (?, ?, ?, ?, ?)"
                selectionArgs = arrayOf(
                    CallLog.Calls.INCOMING_TYPE.toString(),
                    CallLog.Calls.MISSED_TYPE.toString(),
                    CallLog.Calls.REJECTED_TYPE.toString(),
                    CallLog.Calls.BLOCKED_TYPE.toString(),
                    CallLog.Calls.ANSWERED_EXTERNALLY_TYPE.toString(),
                )
            }
            "outgoing" -> {
                selection = "${CallLog.Calls.TYPE} = ?"
                selectionArgs = arrayOf(CallLog.Calls.OUTGOING_TYPE.toString())
            }
        }
        val items = mutableListOf<JsonObject>()
        var total = 0
        var scanned = 0
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.NEW,
            ),
            selection,
            selectionArgs,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                scanned += 1
                if (from != null && scanned > MAX_SCANNED) break
                val number = cursor.getString(0)
                val cachedName = cursor.getString(1)?.trim()?.ifEmpty { null }
                if (from != null) {
                    val matches = if (fromNumber) {
                        number != null && PhoneNumberUtils.compare(number, from)
                    } else {
                        (cachedName != null && cachedName.contains(from, ignoreCase = true)) || isAnyOf(number, contactNumbers)
                    }
                    if (!matches) continue
                }
                total += 1
                if (items.size >= call.limit) continue
                val name = cachedName ?: names.contactName(number)
                val type = cursor.getInt(2)
                val details = mapOf(
                    "name" to PhoneDetails.string(name),
                    "number" to PhoneDetails.string(number),
                    "type" to JsonPrimitive(callType(type)),
                    "time" to JsonPrimitive(PhoneDetails.local(cursor.getLong(3))),
                    "duration_seconds" to if (type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE) null else JsonPrimitive(cursor.getLong(4)),
                    "new" to if (cursor.getInt(5) == 1 && type == CallLog.Calls.MISSED_TYPE) JsonPrimitive(true) else null,
                )
                items += JsonObject(PhoneDetails.compact(details))
            }
        }
        val result = linkedMapOf<String, JsonElement>("calls" to JsonArray(items))
        val more = PhoneDetails.more(total, items.size)
        if (more != null) result["more_calls"] = more
        val sentence = if (items.isEmpty()) "No calls found in the call log." else "The call log, newest first."
        return PhoneTools.Result(sentence + "\n" + JsonObject(result), action = PhoneTools.Action.other("Read the call log"))
    }

    private fun callType(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "declined"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "answered on another device"
        else -> "other"
    }

    /**
     * What a `from` filter keeps: texts with one of [numbers], or from a sender named exactly
     * [sender] (such as “UBER”). Both null keeps every text.
     */
    private class Filter(val numbers: List<String>?, val sender: String?) {
        val active: Boolean get() = numbers != null || sender != null

        fun matches(addresses: List<String>): Boolean {
            if (!active) return true
            for (address in addresses) {
                if (isAnyOf(address, numbers ?: emptyList())) return true
                if (sender != null && address.equals(sender, ignoreCase = true)) return true
            }
            return false
        }
    }

    private fun isAnyOf(number: String?, numbers: List<String>): Boolean {
        if (number == null) return false
        for (wanted in numbers) {
            if (PhoneNumberUtils.compare(number, wanted)) return true
        }
        return false
    }

    private fun contactNumbersFor(context: Context, query: String): List<String> {
        val numbers = mutableListOf<String>()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
        context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(0)
                if (number != null) numbers += number
            }
        }
        return numbers
    }

    /** Adds the matching SMS to [into], at most the limit; returns how many matched in all. */
    private fun readSms(context: Context, call: PhoneTools.Call.ReadTexts, filter: Filter, into: MutableList<Message>): Int {
        val selection = if (call.unreadOnly) "${Telephony.Sms.READ} = 0" else null
        var total = 0
        var kept = 0
        var scanned = 0
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.BODY),
            selection,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                scanned += 1
                if (filter.active && scanned > MAX_SCANNED) break
                val number = cursor.getString(0)
                val addresses = if (number == null) emptyList() else listOf(number)
                if (!filter.matches(addresses)) continue
                total += 1
                if (kept >= call.limit) continue
                kept += 1
                into += Message(
                    time = cursor.getLong(1),
                    number = number,
                    direction = smsDirection(cursor.getInt(2)),
                    read = cursor.getInt(3) == 1,
                    text = cursor.getString(4),
                    attachments = 0,
                )
            }
        }
        return total
    }

    /** Adds the matching MMS to [into], at most the limit; returns how many matched in all. */
    private fun readMms(context: Context, call: PhoneTools.Call.ReadTexts, filter: Filter, into: MutableList<Message>): Int {
        val selection = if (call.unreadOnly) "${Telephony.Mms.READ} = 0" else null
        var total = 0
        var kept = 0
        var scanned = 0
        val resolver = context.contentResolver
        resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ),
            selection,
            null,
            "${Telephony.Mms.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                scanned += 1
                if (filter.active && scanned > MAX_SCANNED) break
                if (kept >= call.limit && !filter.active) {
                    // Past the limit only the count matters.
                    total += 1
                    continue
                }
                val id = cursor.getLong(0)
                val box = cursor.getInt(2)
                val received = box == Telephony.Mms.MESSAGE_BOX_INBOX
                val senders = mutableListOf<String>()
                val recipients = mutableListOf<String>()
                mmsAddresses(context, id, senders, recipients)
                if (!filter.matches(senders + recipients)) continue
                val number = if (received) senders.firstOrNull() else recipients.firstOrNull()
                total += 1
                if (kept >= call.limit) continue
                kept += 1
                var text: String? = null
                var attachments = 0
                resolver.query(
                    Uri.parse("content://mms/part"),
                    arrayOf(Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT),
                    "${Telephony.Mms.Part.MSG_ID} = ?",
                    arrayOf(id.toString()),
                    null,
                )?.use { parts ->
                    while (parts.moveToNext()) {
                        val type = parts.getString(0) ?: continue
                        if (type == "text/plain") {
                            val part = parts.getString(1)
                            if (part != null) text = if (text == null) part else text + "\n" + part
                        } else if (type != "application/smil") {
                            attachments += 1
                        }
                    }
                }
                into += Message(
                    // MMS dates are in seconds.
                    time = cursor.getLong(1) * 1000,
                    number = number,
                    direction = mmsDirection(box),
                    read = cursor.getInt(3) == 1,
                    text = text,
                    attachments = attachments,
                    recipients = if (recipients.size > 1) recipients else emptyList(),
                )
            }
        }
        return total
    }

    /** Adds the MMS's sender to [senders] and everyone it went to to [recipients]. */
    private fun mmsAddresses(context: Context, id: Long, senders: MutableList<String>, recipients: MutableList<String>) {
        context.contentResolver.query(
            Uri.parse("content://mms/$id/addr"),
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            "${Telephony.Mms.Addr.TYPE} IN (?, ?)",
            arrayOf(MMS_FROM.toString(), MMS_TO.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val address = cursor.getString(0)?.trim()
                if (address.isNullOrEmpty() || address == "insert-address-token") continue
                if (cursor.getInt(1) == MMS_FROM) senders += address else recipients += address
            }
        }
    }

    private fun smsDirection(type: Int): String = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "received"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> "sending"
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed to send"
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
        else -> "other"
    }

    private fun mmsDirection(box: Int): String = when (box) {
        Telephony.Mms.MESSAGE_BOX_INBOX -> "received"
        Telephony.Mms.MESSAGE_BOX_SENT -> "sent"
        Telephony.Mms.MESSAGE_BOX_OUTBOX -> "sending"
        Telephony.Mms.MESSAGE_BOX_DRAFTS -> "draft"
        else -> "other"
    }

    /** Contact names for numbers, when Contacts is allowed; each number is looked up once. */
    private class Names(private val context: Context) {
        private val allowed = PhoneAccess.isGranted(context, PhoneAccess.contacts)
        private val known = mutableMapOf<String, String?>()

        fun contactName(number: String?): String? {
            if (number == null || !allowed) return null
            if (known.containsKey(number)) return known[number]
            var name: String? = null
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            runCatching {
                context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) name = cursor.getString(0)
                }
            }
            known[number] = name
            return name
        }

        fun of(number: String?): String? = contactName(number) ?: number
    }
}
