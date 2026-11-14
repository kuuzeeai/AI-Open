package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import java.net.URLEncoder
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Contacts, calls, texts and email for [PhoneToolsClient] (Android only). Runs on the main thread. */
internal object PhonePeople {
    private const val MAX_CONTACTS = 5
    private const val MAX_PER_KIND = 10
    private const val MAX_NOTE = 500
    private val sendWait = 20.seconds

    fun findContact(query: String): PhoneTools.Result {
        val context = dependency(AppContext)
        PhoneAccess.require(context, PhoneAccess.contacts.permissions, "read contacts")?.let { return it }
        val resolver = context.contentResolver
        // Name matches first (given, family and display names), then nicknames such as “Mom”.
        val ids = mutableListOf<Long>()
        val starred = mutableSetOf<Long>()
        val names = mutableMapOf<Long, String>()
        // Each row is a contact's ID, name and starred flag; a contact both queries find counts once.
        fun collect(cursor: Cursor) {
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                if (id in names) continue
                ids += id
                names[id] = cursor.getString(1) ?: ""
                if (cursor.getInt(2) == 1) starred += id
            }
        }
        val filter = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        resolver.query(
            filter,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.STARRED),
            null,
            null,
            null,
        )?.use { cursor -> collect(cursor) }
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME_PRIMARY, ContactsContract.Data.STARRED),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Nickname.NAME} LIKE ?",
            arrayOf(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, query),
            null,
        )?.use { cursor -> collect(cursor) }
        val ordered = mutableListOf<Long>()
        for (id in ids) if (id in starred) ordered += id
        for (id in ids) if (id !in starred) ordered += id

        val contacts = mutableListOf<JsonObject>()
        for (id in ordered.take(MAX_CONTACTS)) {
            contacts += contact(context, id, names[id] ?: "", id in starred)
        }
        val result = linkedMapOf<String, JsonElement>("contacts" to JsonArray(contacts))
        val more = PhoneDetails.more(ordered.size, MAX_CONTACTS)
        if (more != null) result["more_contacts"] = more
        return PhoneTools.Result(JsonObject(result).toString())
    }

    /**
     * One contact's card: numbers, emails and addresses with their labels, nickname, work,
     * birthday and other dates, relationships, websites, starred and notes. Every contact has
     * `phones`, `emails` and `addresses`, empty when none are saved. No photo.
     */
    private fun contact(context: Context, id: Long, name: String, starred: Boolean): JsonObject {
        val resources = context.resources
        val phones = mutableListOf<JsonObject>()
        val emails = mutableListOf<JsonObject>()
        val addresses = mutableListOf<JsonObject>()
        val dates = mutableListOf<JsonObject>()
        val relations = mutableListOf<JsonObject>()
        val websites = mutableListOf<JsonPrimitive>()
        var nickname: String? = null
        var company: String? = null
        var jobTitle: String? = null
        var department: String? = null
        var birthday: String? = null
        var notes: String? = null
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4,
                ContactsContract.Data.DATA5,
            ),
            "${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(id.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val value = cursor.getString(1)?.trim()
                if (value.isNullOrEmpty()) continue
                val type = if (cursor.isNull(2)) 0 else cursor.getInt(2)
                val label = cursor.getString(3)
                when (cursor.getString(0)) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> if (phones.size < MAX_PER_KIND) {
                        phones += labelled(
                            "number",
                            value,
                            ContactsContract.CommonDataKinds.Phone.getTypeLabel(resources, type, label),
                        )
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (emails.size < MAX_PER_KIND) {
                        emails += labelled(
                            "email",
                            value,
                            ContactsContract.CommonDataKinds.Email.getTypeLabel(resources, type, label),
                        )
                    }
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val line = addressLine(value)
                        if (line != null && addresses.size < MAX_PER_KIND) {
                            val address = labelled(
                                "address",
                                line,
                                ContactsContract.CommonDataKinds.StructuredPostal.getTypeLabel(resources, type, label),
                            )
                            addresses += JsonObject(address + ("maps_url" to JsonPrimitive(mapsUrl(line))))
                        }
                    }
                    ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> if (nickname == null) nickname = value
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        company = company ?: value
                        jobTitle = jobTitle ?: cursor.getString(4)?.trim()?.ifEmpty { null }
                        department = department ?: cursor.getString(5)?.trim()?.ifEmpty { null }
                    }
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                        if (type == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY && birthday == null) {
                            birthday = value
                        } else if (dates.size < MAX_PER_KIND) {
                            val shown = ContactsContract.CommonDataKinds.Event.getTypeResource(type).let { resources.getString(it) }
                            dates += labelled("date", value, if (type == ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM) label ?: shown else shown)
                        }
                    }
                    ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE -> if (relations.size < MAX_PER_KIND) {
                        relations += labelled(
                            "name",
                            value,
                            ContactsContract.CommonDataKinds.Relation.getTypeLabel(resources, type, label),
                        )
                    }
                    ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE -> if (websites.size < MAX_PER_KIND) {
                        websites += JsonPrimitive(value)
                    }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> if (notes == null) notes = PhoneDetails.cap(value, MAX_NOTE)
                }
            }
        }
        val card = PhoneDetails.compact(
            mapOf(
                "name" to JsonPrimitive(name),
                "nickname" to PhoneDetails.string(nickname),
                "company" to PhoneDetails.string(company),
                "job_title" to PhoneDetails.string(jobTitle),
                "department" to PhoneDetails.string(department),
                "birthday" to PhoneDetails.string(birthday),
                "dates" to JsonArray(dates),
                "relationships" to JsonArray(relations),
                "websites" to JsonArray(websites),
                "starred" to if (starred) JsonPrimitive(true) else null,
                "notes" to PhoneDetails.string(notes),
            ),
        )
        // The three lists are always there, so an empty one reads as “none saved”.
        val ordered = linkedMapOf<String, JsonElement>()
        ordered["name"] = JsonPrimitive(name)
        ordered["phones"] = JsonArray(phones)
        ordered["emails"] = JsonArray(emails)
        ordered["addresses"] = JsonArray(addresses)
        for ((key, value) in card) {
            if (key != "name") ordered[key] = value
        }
        return JsonObject(ordered)
    }

    /** `{"number":"+1 555-0100","type":"mobile"}`, with Android's own label in lower case. */
    private fun labelled(key: String, value: String, label: CharSequence?): JsonObject {
        val type = label?.toString()?.trim()?.lowercase()?.ifEmpty { null } ?: "other"
        return JsonObject(mapOf(key to JsonPrimitive(value), "type" to JsonPrimitive(type)))
    }

    /** A saved address on one line (“12 Elm St, Springfield, IL 62701”), or null when it's empty. */
    internal fun addressLine(formatted: String?): String? {
        if (formatted == null) return null
        val parts = mutableListOf<String>()
        for (line in formatted.split("\n")) {
            val part = line.trim().trimEnd(',')
            if (part.isNotEmpty()) parts += part
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(", ")
    }

    /** A Google Maps search link for [address], which opens in Maps or any browser. */
    internal fun mapsUrl(address: String): String =
        "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(address, Charsets.UTF_8)

    /**
     * Opens Contacts' new-contact form with what's known filled in. Needs no permission: nothing
     * is saved until the user taps Save, and the form says so.
     */
    fun addContact(call: PhoneTools.Call.AddContact): PhoneTools.Result {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION)
            .setType(ContactsContract.RawContacts.CONTENT_TYPE)
            .putExtra(ContactsContract.Intents.Insert.NAME, call.contactName)
        if (call.phone != null) intent.putExtra(ContactsContract.Intents.Insert.PHONE, call.phone)
        if (call.email != null) intent.putExtra(ContactsContract.Intents.Insert.EMAIL, call.email)
        if (call.address != null) intent.putExtra(ContactsContract.Intents.Insert.POSTAL, call.address)
        if (call.company != null) intent.putExtra(ContactsContract.Intents.Insert.COMPANY, call.company)
        if (call.notes != null) intent.putExtra(ContactsContract.Intents.Insert.NOTES, call.notes)
        // Back from the form returns to where the user was, not into the Contacts app.
        intent.putExtra("finishActivityOnSaveCompleted", true)
        val app = PhoneLaunch.handlerName(intent) ?: "Contacts"
        return when (PhoneLaunch.start(intent)) {
            PhoneLaunch.Launch.started -> PhoneTools.Result(
                "Opened $app with a new contact for “${call.contactName}” filled in. The user has to tap Save; it isn’t saved yet.",
                action = PhoneTools.Action.other("Opened a new contact"),
            )
            PhoneLaunch.Launch.blocked -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
            PhoneLaunch.Launch.noApp -> PhoneTools.Result.failure("No contacts app on this phone can add contacts.")
        }
    }

    fun call(call: PhoneTools.Call.PhoneCall): PhoneTools.Result {
        val context = dependency(AppContext)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)) {
            return PhoneTools.Result.failure("This phone can’t make phone calls.")
        }
        if (call.dialOnly) return dial(call.number, "Opened the dialer with ${call.number}. The user has to press call.")
        // Android won't place an emergency call for an app: the dialer gets the number instead.
        val emergency = runCatching {
            context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(call.number) == true
        }.getOrDefault(false)
        if (emergency) return dial(call.number, "Opened the dialer with ${call.number}. The user has to press call.")
        if (!PhoneAccess.granted(context, Manifest.permission.CALL_PHONE)) {
            val result = dial(
                call.number,
                "OpenCode doesn’t have permission to make phone calls, so it opened the dialer with ${call.number} instead. " +
                    "The user has to press call. The phone is asking the user to allow calls.",
            )
            if (!result.isError) PhoneAccess.askForNextTime(context, PhoneAccess.calls.permissions)
            return result
        }
        return when (PhoneLaunch.start(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", call.number, null)))) {
            PhoneLaunch.Launch.started -> PhoneTools.Result(
                "Calling ${who(call.contact, call.number)}.",
                action = PhoneTools.Action.other("Calling ${call.contact ?: call.number}"),
            )
            PhoneLaunch.Launch.blocked -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
            PhoneLaunch.Launch.noApp -> PhoneTools.Result.failure("This phone can’t make phone calls.")
        }
    }

    private fun dial(number: String, success: String): PhoneTools.Result =
        when (PhoneLaunch.start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))) {
            PhoneLaunch.Launch.started -> PhoneTools.Result(success, action = PhoneTools.Action.other("Opened the dialer"))
            PhoneLaunch.Launch.blocked -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
            PhoneLaunch.Launch.noApp -> PhoneTools.Result.failure("This phone can’t make phone calls.")
        }

    /** No card when [sendText] would only open a draft (or can't text at all). */
    fun prepareText(call: PhoneTools.Call.SendText): PhoneTools.Prepared {
        val context = dependency(AppContext)
        val canSend = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) &&
            PhoneAccess.isAssistant(context) &&
            PhoneAccess.granted(context, Manifest.permission.SEND_SMS)
        return PhoneTools.askText(call, canSend)
    }

    /** Waits for the radio's answer, so the result says whether it really went. */
    suspend fun sendText(call: PhoneTools.Call.SendText): PhoneTools.Result {
        val context = dependency(AppContext)
        if (call.draft || call.to == null || call.message == null) return draftText(call.to, call.message, null)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            return PhoneTools.Result.failure("This phone can’t send texts.")
        }
        if (!PhoneAccess.isAssistant(context)) {
            // Play allows SEND_SMS only for the default assistant, so it isn't used or asked for otherwise.
            return draftText(
                call.to,
                call.message,
                "OpenCode can send texts itself only while it’s the phone’s digital assistant, so it opened Messages with the text filled in instead. " +
                    "The user has to tap send.",
            )
        }
        if (!PhoneAccess.granted(context, Manifest.permission.SEND_SMS)) {
            val result = draftText(
                call.to,
                call.message,
                "OpenCode doesn’t have permission to send texts, so it opened Messages with the text filled in instead. " +
                    "The user has to tap send. The phone is asking the user to allow texts.",
            )
            if (!result.isError) PhoneAccess.askForNextTime(context, PhoneAccess.texts.permissions)
            return result
        }
        val sms = context.getSystemService(SmsManager::class.java) ?: return PhoneTools.Result.failure("This phone can’t send texts.")
        val parts = sms.divideMessage(call.message)
        val action = "ai.opencode.SMS_SENT." + UUID.randomUUID()
        val outcome = CompletableDeferred<Int>()
        var remaining = parts.size
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (resultCode != Activity.RESULT_OK) {
                    outcome.complete(resultCode)
                    return
                }
                remaining -= 1
                if (remaining <= 0) outcome.complete(Activity.RESULT_OK)
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            val sent = ArrayList<PendingIntent>()
            for (index in parts.indices) {
                val intent = Intent(action).setPackage(context.packageName)
                sent += PendingIntent.getBroadcast(context, index, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            }
            try {
                sms.sendMultipartTextMessage(call.to, null, parts, sent, null)
            } catch (_: IllegalArgumentException) {
                return PhoneTools.Result.failure("The text didn’t send: the network refused it.")
            }
            val code = withTimeoutOrNull(sendWait) { outcome.await() }
            return when (code) {
                Activity.RESULT_OK -> PhoneTools.Result(
                    PhoneDetails.text(
                        "Sent to ${who(call.contact, call.to)}: “${call.message}”.",
                        mapOf("characters" to JsonPrimitive(call.message.length), "parts" to JsonPrimitive(parts.size)),
                    ),
                    action = PhoneTools.Action.other("Texted ${call.contact ?: call.to}"),
                )
                SmsManager.RESULT_ERROR_NO_SERVICE -> PhoneTools.Result.failure("The text didn’t send: no mobile service.")
                SmsManager.RESULT_ERROR_RADIO_OFF -> PhoneTools.Result.failure("The text didn’t send: airplane mode is on.")
                else -> PhoneTools.Result.failure("The text didn’t send: the network refused it.")
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    /** Opens the messaging app filled in; [instead] replaces the usual answer. */
    private fun draftText(to: String?, message: String?, instead: String?): PhoneTools.Result {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(to ?: "")))
        if (message != null) intent.putExtra("sms_body", message)
        val app = PhoneLaunch.handlerName(intent) ?: "Messages"
        return when (PhoneLaunch.start(intent)) {
            PhoneLaunch.Launch.started -> {
                val text = if (instead != null) {
                    instead
                } else if (to == null) {
                    "Opened $app with a text filled in. The user has to pick who it’s to and tap send."
                } else {
                    "Opened $app with a text to $to filled in. The user has to tap send."
                }
                PhoneTools.Result(text, action = PhoneTools.Action.other("Drafted a text"))
            }
            PhoneLaunch.Launch.blocked -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
            PhoneLaunch.Launch.noApp -> PhoneTools.Result.failure("No app on this phone can send texts.")
        }
    }

    fun draftEmail(call: PhoneTools.Call.DraftEmail): PhoneTools.Result {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        if (call.to != null) intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(call.to))
        if (call.subject != null) intent.putExtra(Intent.EXTRA_SUBJECT, call.subject)
        if (call.body != null) intent.putExtra(Intent.EXTRA_TEXT, call.body)
        val app = PhoneLaunch.handlerName(intent) ?: "the email app"
        return when (PhoneLaunch.start(intent)) {
            PhoneLaunch.Launch.started -> {
                val to = if (call.to == null) "" else " to ${call.to}"
                PhoneTools.Result(
                    "Opened $app with an email$to filled in. The user has to tap send.",
                    action = PhoneTools.Action.other("Drafted an email"),
                )
            }
            PhoneLaunch.Launch.blocked -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
            PhoneLaunch.Launch.noApp -> PhoneTools.Result.failure("No app on this phone can send email.")
        }
    }

    private fun who(name: String?, number: String): String = if (name == null) number else "$name ($number)"
}
