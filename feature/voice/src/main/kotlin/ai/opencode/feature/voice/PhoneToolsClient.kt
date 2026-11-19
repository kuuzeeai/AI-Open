package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import android.content.Intent
import android.provider.AlarmClock
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Carries out [PhoneTools] calls (Android only): the Clock app, the phone's own settings and
 * hardware, people, the calendar, places and notifications, and the screen. Inert under tests.
 */
class PhoneToolsClient(
    /** Runs a call. Confirm cards are [AssistantPanelClient]'s, before this. */
    val run: suspend (PhoneTools.Call) -> PhoneTools.Result,
    /**
     * Before the confirm card: looks up what the card shows (the notification's app and
     * conversation, the event being deleted) and fills it into the call, or answers without a card
     * when the call can't work (the notification is gone, OpenCode isn't the assistant, no such
     * event).
     */
    val prepare: suspend (PhoneTools.Call) -> PhoneTools.Prepared = { PhoneTools.ask(it) },
) {
    companion object Key : DependencyKey<PhoneToolsClient> {
        override val liveValue: PhoneToolsClient by lazy {
            PhoneToolsClient(
                run = { call -> withContext(Dispatchers.Main) { unlockedOrSaid(call) } },
                prepare = { call -> withContext(Dispatchers.Main) { prepareLive(call) } },
            )
        }

        private fun prepareLive(call: PhoneTools.Call): PhoneTools.Prepared = when (call) {
            is PhoneTools.Call.ReadTexts, is PhoneTools.Call.ReadCallLog -> PhoneInbox.prepare(call)
            is PhoneTools.Call.DeleteCalendarEvent -> PhoneEvents.prepareDelete(call)
            is PhoneTools.Call.SendText -> PhonePeople.prepareText(call)
            is PhoneTools.Call.ReplyToNotification, is PhoneTools.Call.NotificationAction -> PhoneNotificationListener.prepare(call)
            else -> PhoneTools.ask(call)
        }

        override val testValue: PhoneToolsClient = PhoneToolsClient(
            run = { PhoneTools.Result.failure("Phone tools aren’t available here.") },
        )
        private const val ALARM_SETTLE_MILLIS = 1_000L
        private const val UNLOCK_SETTLE_MILLIS = 1_000L

        /**
         * Runs [call]; when it opened a screen behind the lock screen ([PhoneLaunch.opened] asked
         * Android to unlock) and the phone is still locked a moment later, the result says so.
         * A swipe-only lock goes at once; a PIN or pattern waits for the user.
         */
        private suspend fun unlockedOrSaid(call: PhoneTools.Call): PhoneTools.Result {
            val opensBefore = PhoneLaunch.opensBehindLock
            val result = live(call)
            if (PhoneLaunch.opensBehindLock == opensBefore) return result
            val context = dependency(AppContext)
            var waited = 0L
            while (PhoneLaunch.isLocked(context) && waited < UNLOCK_SETTLE_MILLIS) {
                delay(100)
                waited += 100
            }
            if (!PhoneLaunch.isLocked(context)) return result
            AppLog.app.info { "Phone tool ${call.name} opened behind the lock screen" }
            return PhoneTools.behindLock(result)
        }

        private suspend fun live(call: PhoneTools.Call): PhoneTools.Result = when (call) {
            is PhoneTools.Call.SetTimer -> {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, call.seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (call.label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, call.label)
                clock(intent, PhoneTools.NO_CLOCK) {
                    PhoneTools.Result(
                        PhoneDetails.text(
                            PhoneTools.timerSet(call),
                            mapOf(
                                "seconds" to JsonPrimitive(call.seconds),
                                "label" to PhoneDetails.string(call.label),
                                "rings_at" to JsonPrimitive(PhoneDetails.timerEnds(LocalDateTime.now(), call.seconds)),
                            ),
                        ),
                        action = PhoneTools.Action(PhoneTools.Action.Kind.timer, PhoneTools.shortDuration(call.seconds)),
                    )
                }
            }

            is PhoneTools.Call.SetAlarm -> {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, call.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, call.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (call.label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, call.label)
                if (call.days.isNotEmpty()) {
                    val days = ArrayList<Int>()
                    for (day in call.days) days += calendarDay(day)
                    intent.putExtra(AlarmClock.EXTRA_DAYS, days)
                }
                clock(intent, PhoneTools.NO_CLOCK) {
                    val rings = PhoneDetails.nextRing(LocalDateTime.now(), call.hour, call.minute, call.days)
                    PhoneTools.Result(
                        PhoneDetails.text(
                            PhoneTools.alarmSet(call),
                            mapOf(
                                "time" to JsonPrimitive(PhoneTools.clockTime(call.hour, call.minute)),
                                "repeats" to JsonArray(call.days.map { JsonPrimitive(it) }),
                                "label" to PhoneDetails.string(call.label),
                                "next_ring" to JsonPrimitive(PhoneDetails.minute.format(rings)),
                            ),
                        ),
                        action = PhoneTools.Action(PhoneTools.Action.Kind.alarm, PhoneTools.spokenTime(call.hour, call.minute)),
                    )
                }
            }

            is PhoneTools.Call.CancelAlarm -> cancelAlarm(call)

            PhoneTools.Call.StopTimer -> clock(Intent(AlarmClock.ACTION_DISMISS_TIMER), "The Clock app on this phone can’t stop timers.") {
                PhoneTools.Result("Asked Clock to stop the ringing timers. A timer that’s still counting down keeps running: Android can’t cancel it.", action = PhoneTools.Action.other("Stopped the timers"))
            }

            is PhoneTools.Call.ShowClock -> {
                val timers = call.page == "timers"
                val intent = Intent(if (timers) AlarmClock.ACTION_SHOW_TIMERS else AlarmClock.ACTION_SHOW_ALARMS)
                clock(intent, PhoneTools.NO_CLOCK) {
                    val opened = if (timers) "Opened the timers in Clock." else "Opened the alarms in Clock."
                    PhoneTools.Result(PhoneDetails.text(opened, mapOf("next_alarm" to PhoneDevice.nextAlarm())))
                }
            }

            is PhoneTools.Call.Flashlight -> PhoneDevice.flashlight(call.on)
            is PhoneTools.Call.MediaControl -> PhoneMedia.control(call)
            is PhoneTools.Call.PlayMedia -> PhoneMedia.play(call)
            is PhoneTools.Call.SetRinger -> PhoneDisplay.ringer(call.mode)
            is PhoneTools.Call.SetDisplay -> PhoneDisplay.display(call)
            is PhoneTools.Call.TakePhoto -> PhonePhoto.take()
            PhoneTools.Call.AboutPhone -> PhoneTools.Result(PhoneTools.aboutPhone())
            is PhoneTools.Call.SetVolume -> PhoneDevice.volume(call)
            is PhoneTools.Call.SetDoNotDisturb -> PhoneDevice.doNotDisturb(call.on)
            PhoneTools.Call.PhoneStatus -> PhoneDevice.status()
            is PhoneTools.Call.OpenSettings -> PhoneDevice.settings(call.panel)
            is PhoneTools.Call.CopyToClipboard -> PhoneDevice.copy(call.text)
            is PhoneTools.Call.OpenApp -> openApp(call.query)
            PhoneTools.Call.ReadScreen -> PhoneScreen.read()
            is PhoneTools.Call.FindContact -> PhonePeople.findContact(call.query)
            is PhoneTools.Call.AddContact -> PhonePeople.addContact(call)
            is PhoneTools.Call.PhoneCall -> PhonePeople.call(call)
            PhoneTools.Call.AnswerCall -> PhoneCalls.answer()
            PhoneTools.Call.EndCall -> PhoneCalls.end()
            is PhoneTools.Call.ReadCallLog -> PhoneInbox.readCallLog(call)
            is PhoneTools.Call.SendText -> PhonePeople.sendText(call)
            is PhoneTools.Call.ReadTexts -> PhoneInbox.readTexts(call)
            is PhoneTools.Call.DraftEmail -> PhonePeople.draftEmail(call)
            is PhoneTools.Call.AddCalendarEvent -> PhonePlaces.addEvent(call)
            is PhoneTools.Call.ListCalendarEvents -> PhonePlaces.listEvents(call)
            is PhoneTools.Call.UpdateCalendarEvent -> PhoneEvents.update(call)
            is PhoneTools.Call.DeleteCalendarEvent -> PhoneEvents.delete(call)
            is PhoneTools.Call.OpenMaps -> PhonePlaces.maps(call)
            PhoneTools.Call.GetLocation -> PhonePlaces.location()
            is PhoneTools.Call.ReadNotifications -> PhoneNotificationListener.read(call)
            is PhoneTools.Call.DismissNotification -> PhoneNotificationListener.dismiss(call)
            is PhoneTools.Call.ReplyToNotification -> PhoneNotificationListener.reply(call)
            is PhoneTools.Call.NotificationAction -> PhoneNotificationListener.tap(call)
            is PhoneTools.Call.Share -> PhoneShare.share(call)
            is PhoneTools.Call.OpenLink -> PhoneShare.openLink(call)
        }

        /** Starts a Clock intent: [success] once it started, [noClock] when no app takes it. */
        private fun clock(intent: Intent, noClock: String, success: () -> PhoneTools.Result): PhoneTools.Result {
            val chooser = PhoneLaunch.isChooser(intent)
            return when (PhoneLaunch.start(intent)) {
                PhoneLaunch.Launch.started -> if (chooser) {
                    PhoneTools.Result("The phone is asking the user which Clock app to use.")
                } else {
                    success()
                }
                PhoneLaunch.Launch.blocked -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
                PhoneLaunch.Launch.noApp -> PhoneTools.Result.failure(noClock)
            }
        }

        /**
         * Clock answers nothing back and Android lets no app list the alarms, so the result says
         * what was asked, plus the phone's next alarm before and a moment after.
         */
        private suspend fun cancelAlarm(call: PhoneTools.Call.CancelAlarm): PhoneTools.Result {
            val before = PhoneDevice.nextAlarm()
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            val text: String
            when (call.match) {
                "time" -> {
                    val hour = call.hour ?: 0
                    val minute = call.minute ?: 0
                    intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    text = "Asked Clock to turn off the ${PhoneTools.clockTime(hour, minute)} alarm. If several alarms match, Clock shows them for the user to pick."
                }
                "label" -> {
                    intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, call.label)
                    text = "Asked Clock to turn off the alarms labelled “${call.label}”. If several alarms match, Clock shows them for the user to pick."
                }
                "all" -> {
                    intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
                    text = "Asked Clock to turn off all alarms. Clock may show them for the user to confirm."
                }
                else -> {
                    intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
                    text = "Asked Clock to turn off the next alarm."
                }
            }
            val asked = clock(intent, "The Clock app on this phone can’t turn off alarms.") {
                PhoneTools.Result(text, action = PhoneTools.Action.other("Turned off an alarm"))
            }
            if (asked.isError) return asked
            delay(ALARM_SETTLE_MILLIS)
            val after = PhoneDevice.nextAlarm()
            val details = mapOf(
                "next_alarm_before" to (before ?: JsonPrimitive("none")),
                "next_alarm_now" to (after ?: JsonPrimitive("none")),
            )
            return asked.copy(text = PhoneDetails.text(text, details))
        }

        private fun openApp(query: String): PhoneTools.Result {
            val context = dependency(AppContext)
            val packages = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = mutableListOf<AppMatcher.App>()
            for (info in packages.queryIntentActivities(launcher, 0)) {
                val packageName = info.activityInfo.packageName
                if (packageName == context.packageName) continue
                apps += AppMatcher.App(label = info.loadLabel(packages).toString(), packageName = packageName)
            }
            return when (val match = AppMatcher.match(query, apps)) {
                is AppMatcher.Result.Found -> {
                    val intent = packages.getLaunchIntentForPackage(match.app.packageName)
                    val launch = if (intent == null) {
                        PhoneLaunch.Launch.noApp
                    } else {
                        PhoneLaunch.start(intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                    }
                    when (launch) {
                        PhoneLaunch.Launch.started -> PhoneTools.Result(
                            PhoneDetails.text(
                                "Opened ${match.app.label}.",
                                mapOf(
                                    "app" to JsonPrimitive(match.app.label),
                                    "package" to JsonPrimitive(match.app.packageName),
                                    "match" to JsonPrimitive(if (AppMatcher.isExact(query, match.app)) "exact" else "close"),
                                    "close_alternatives" to if (AppMatcher.isExact(query, match.app)) {
                                        null
                                    } else {
                                        JsonArray(AppMatcher.alternatives(query, apps, match.app).map { JsonPrimitive(it.label) })
                                    },
                                ),
                            ),
                            action = PhoneTools.Action(PhoneTools.Action.Kind.app, match.app.label, packageName = match.app.packageName),
                        )
                        PhoneLaunch.Launch.blocked -> PhoneTools.Result.failure(PhoneTools.BLOCKED)
                        PhoneLaunch.Launch.noApp -> PhoneTools.Result.failure("${match.app.label} couldn’t be opened.")
                    }
                }

                is AppMatcher.Result.Several -> PhoneTools.Result.failure(
                    PhoneDetails.text(
                        "Several apps match “$query”: ${match.apps.joinToString(", ") { it.label }}. Ask the user which one.",
                        mapOf("matches" to JsonArray(match.apps.map { JsonPrimitive("${it.label} (${it.packageName})") })),
                    ),
                )

                AppMatcher.Result.None -> PhoneTools.Result.failure("No app called “$query” is installed.")
            }
        }

        private fun calendarDay(day: String): Int = when (day) {
            "mon" -> Calendar.MONDAY
            "tue" -> Calendar.TUESDAY
            "wed" -> Calendar.WEDNESDAY
            "thu" -> Calendar.THURSDAY
            "fri" -> Calendar.FRIDAY
            "sat" -> Calendar.SATURDAY
            else -> Calendar.SUNDAY
        }
    }
}

/** Matches a spoken app name to a launchable app: exact, then prefix, then word, then a typo. */
object AppMatcher {
    data class App(val label: String, val packageName: String)

    sealed interface Result {
        data class Found(val app: App) : Result
        data class Several(val apps: List<App>) : Result
        data object None : Result
    }

    fun match(query: String, apps: List<App>): Result {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return Result.None
        val wantedCompact = wanted.replace(" ", "")

        var bestTier = Int.MAX_VALUE
        val best = mutableListOf<App>()
        for (app in apps) {
            val tier = tier(wanted, wantedCompact, normalize(app.label)) ?: continue
            if (tier < bestTier) {
                bestTier = tier
                best.clear()
            }
            if (tier == bestTier) best += app
        }

        // The same name twice (a work profile's copy, two activities of one app) is one app.
        val distinct = mutableListOf<App>()
        val seen = mutableSetOf<String>()
        for (app in best) {
            if (seen.add(normalize(app.label))) distinct += app
        }
        return when {
            distinct.isEmpty() -> Result.None
            distinct.size == 1 -> Result.Found(distinct[0])
            else -> Result.Several(distinct.sortedBy { it.label.length }.take(4))
        }
    }

    /** True when [app]'s name is what the user said, not a close guess. */
    fun isExact(query: String, app: App): Boolean {
        val wanted = normalize(query).replace(" ", "")
        return wanted.isNotEmpty() && normalize(app.label).replace(" ", "") == wanted
    }

    /** Up to [limit] other apps that also loosely match [query], best first, for a fuzzy match. */
    fun alternatives(query: String, apps: List<App>, chosen: App, limit: Int = 3): List<App> {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return emptyList()
        val wantedCompact = wanted.replace(" ", "")
        val ranked = mutableListOf<Pair<Int, App>>()
        val seen = mutableSetOf(normalize(chosen.label))
        for (app in apps) {
            val tier = tier(wanted, wantedCompact, normalize(app.label)) ?: continue
            if (!seen.add(normalize(app.label))) continue
            ranked += Pair(tier, app)
        }
        ranked.sortBy { it.first }
        val result = mutableListOf<App>()
        for (pair in ranked) {
            if (result.size >= limit) break
            result += pair.second
        }
        return result
    }

    /** Lower is better; null is no match. */
    private fun tier(wanted: String, wantedCompact: String, label: String): Int? {
        val labelCompact = label.replace(" ", "")
        if (labelCompact.isEmpty()) return null
        if (labelCompact == wantedCompact) return 0
        if (labelCompact.startsWith(wantedCompact)) return 1
        for (word in label.split(" ")) {
            if (word.startsWith(wantedCompact)) return 2
        }
        if (wantedCompact.length >= 3 && labelCompact.contains(wantedCompact)) return 3
        if (labelCompact.length >= 4 && wantedCompact.contains(labelCompact)) return 4
        val allowed = if (wantedCompact.length >= 8) 2 else if (wantedCompact.length >= 4) 1 else 0
        if (allowed > 0 && distance(wantedCompact, labelCompact) <= allowed) return 5
        return null
    }

    private val fillers = setOf("the", "app", "application", "my")

    private fun normalize(text: String): String {
        val cleaned = StringBuilder()
        for (character in text.lowercase(Locale.ROOT)) {
            if (character.isLetterOrDigit()) cleaned.append(character) else cleaned.append(' ')
        }
        val words = mutableListOf<String>()
        for (word in cleaned.split(" ")) {
            if (word.isNotEmpty() && word !in fillers) words += word
        }
        return words.joinToString(" ")
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}

/**
 * The assistant's side of a phone tool call (Android only): the confirm cards (call, text,
 * notifications), listing what a tool did, and fresh assist data for reading the screen. The app's
 * assistant provides it; inert otherwise.
 */
class AssistantPanelClient(
    /**
     * Whether the user lets the phone go ahead: true at once when Settings › Assistant says
     * “without asking” for it, otherwise the panel shows the card. Cancelled with the call.
     */
    val confirm: suspend (PhoneTools.Confirm) -> Boolean,
    /** A tool did something the panel shows as a chip. */
    val acted: (PhoneTools.Action) -> Unit,
    /** A tool put another app or a system screen up, which the panel shouldn't cover. */
    val showedScreen: () -> Unit = {},
    /**
     * Android's assist data for the screen right now, asked for without a prompt; null when
     * OpenCode isn't the phone's assistant or Android gave nothing in time.
     */
    val readScreen: suspend () -> ScreenRead? = { null },
) {
    companion object Key : DependencyKey<AssistantPanelClient> {
        override val liveValue: AssistantPanelClient = AssistantPanelClient(confirm = { false }, acted = {})
        override val testValue: AssistantPanelClient = AssistantPanelClient(confirm = { false }, acted = {})
    }
}

/**
 * Runs one phone tool call from the assistant's session: checks the arguments, shows a confirm
 * card first where the spec asks (with its own [PhoneTools.confirmTimeout]), and gives up after
 * [PhoneTools.timeout]. Logs only the tool's name, status and time: never the screen, names,
 * numbers, messages or places.
 */
object PhoneToolsRunner {
    suspend fun run(
        name: String,
        arguments: JsonObject?,
        callID: String,
        client: PhoneToolsClient,
        panel: AssistantPanelClient,
        clock: Clock,
    ): PhoneTools.Result {
        val started = clock.now()
        val parsed = PhoneTools.parse(name, arguments)
        val status: String
        val result: PhoneTools.Result
        when (parsed) {
            null -> {
                status = "unknown"
                result = PhoneTools.Result.failure("Unknown tool: $name")
            }
            is PhoneTools.Invalid -> {
                status = "invalid"
                result = PhoneTools.Result.failure(parsed.message)
            }
            is PhoneTools.Call -> {
                val outcome = perform(parsed, client, panel)
                status = outcome.first
                result = outcome.second
            }
        }
        if (result.action != null) panel.acted(result.action.copy(id = callID))
        if (status != "declined" && showsScreen(name, result)) panel.showedScreen()
        val elapsed = (clock.now() - started).inWholeMilliseconds
        AppLog.app.info { "Phone tool $name status=$status ms=$elapsed" }
        return result
    }

    /**
     * Whether the tool left another app or a system screen in front: Clock, the dialer, Maps, a
     * draft, a Settings panel, the screen that grants access the tool needed, or Android's permission prompt.
     */
    private fun showsScreen(name: String, result: PhoneTools.Result): Boolean {
        if (name == "call" || name == "cancel_alarm") return !result.isError
        // Android's permission prompt is a screen too, and the panel mustn't cover its buttons.
        return result.text.contains("opened", ignoreCase = true) || result.text.contains("is asking the user")
    }

    private suspend fun perform(asked: PhoneTools.Call, client: PhoneToolsClient, panel: AssistantPanelClient): Pair<String, PhoneTools.Result> {
        val call: PhoneTools.Call
        val card: PhoneTools.Confirm?
        when (val prepared = prepare(asked, client)) {
            is PhoneTools.Prepared.Ask -> {
                call = prepared.call
                card = prepared.card
            }
            is PhoneTools.Prepared.Answer -> return Pair(if (prepared.result.isError) "failed" else "ok", prepared.result)
        }
        // Nothing happens, and no permission is asked for, until the user says yes.
        if (card != null && !ask(card, panel)) return Pair("declined", PhoneTools.Result.failure(PhoneTools.declined(call)))
        val done = attempt(call, client) ?: return Pair("timeout", PhoneTools.Result.failure(PhoneTools.timedOut(call.name)))
        return Pair(if (done.isError) "failed" else "ok", done)
    }

    /** A failed lookup before the card goes ahead with the call as asked, behind its usual card. */
    private suspend fun prepare(call: PhoneTools.Call, client: PhoneToolsClient): PhoneTools.Prepared = try {
        withTimeoutOrNull(PhoneTools.timeout) { client.prepare(call) } ?: PhoneTools.ask(call)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        PhoneTools.ask(call)
    }

    private suspend fun ask(card: PhoneTools.Confirm, panel: AssistantPanelClient): Boolean =
        withTimeoutOrNull(PhoneTools.confirmTimeout) { panel.confirm(card) } ?: false

    /** Null when it ran past [PhoneTools.timeout], or for the screen, which can wait on Android's prompt, [PhoneTools.confirmTimeout]. */
    private suspend fun attempt(call: PhoneTools.Call, client: PhoneToolsClient): PhoneTools.Result? = try {
        var limit = if (call is PhoneTools.Call.ReadScreen) PhoneTools.confirmTimeout else PhoneTools.timeout
        if (call is PhoneTools.Call.TakePhoto) limit = PhoneTools.photoTimeout
        withTimeoutOrNull(limit) { client.run(call) }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        PhoneTools.Result.failure("The phone couldn’t do that.")
    }
}
