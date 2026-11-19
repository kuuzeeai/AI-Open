package ai.opencode.feature.voice

import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.PhoneToolSpecs
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Android only: the side-button assistant's phone tools (no iOS counterpart). */
class PhoneToolsTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun args(vararg pairs: Pair<String, Any>): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        for ((key, value) in pairs) {
            map[key] = when (value) {
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is List<*> -> JsonArray(value.map { JsonPrimitive(it as String) })
                else -> error("unsupported")
            }
        }
        return JsonObject(map)
    }

    private fun client(run: suspend (PhoneTools.Call) -> PhoneTools.Result) = PhoneToolsClient(run = run)

    @Test fun timerReadsSecondsAndLabel() {
        assertEquals(PhoneTools.Call.SetTimer(300, "Pasta"), PhoneTools.parse("set_timer", args("seconds" to 300, "label" to "Pasta")))
        // Models sometimes send numbers as strings.
        assertEquals(PhoneTools.Call.SetTimer(90, null), PhoneTools.parse("set_timer", args("seconds" to "90")))
        assertEquals(PhoneTools.Call.SetTimer(60, null), PhoneTools.parse("set_timer", args("seconds" to 60, "label" to "  ")))
    }

    @Test fun timerRefusesWhatClockCantTake() {
        assertTrue(PhoneTools.parse("set_timer", args()) is PhoneTools.Invalid)
        assertTrue(PhoneTools.parse("set_timer", args("seconds" to 0)) is PhoneTools.Invalid)
        assertTrue(PhoneTools.parse("set_timer", args("seconds" to 86_401)) is PhoneTools.Invalid)
        assertTrue(PhoneTools.parse("set_timer", args("seconds" to 1.5)) is PhoneTools.Invalid)
        assertTrue(PhoneTools.parse("set_timer", args("seconds" to "ten")) is PhoneTools.Invalid)
    }

    @Test fun alarmReadsTimeAndDaysInWeekOrder() {
        assertEquals(
            PhoneTools.Call.SetAlarm(7, 30, listOf("mon", "tue"), "Gym"),
            PhoneTools.parse("set_alarm", args("hour" to 7, "minute" to 30, "days" to listOf("tue", "MON", "tue"), "label" to "Gym")),
        )
        assertEquals(PhoneTools.Call.SetAlarm(19, 0, emptyList(), null), PhoneTools.parse("set_alarm", args("hour" to 19, "minute" to 0)))
        assertTrue(PhoneTools.parse("set_alarm", args("hour" to 24, "minute" to 0)) is PhoneTools.Invalid)
        assertTrue(PhoneTools.parse("set_alarm", args("hour" to 7)) is PhoneTools.Invalid)
        assertTrue(PhoneTools.parse("set_alarm", args("hour" to 7, "minute" to 0, "days" to listOf("monday"))) is PhoneTools.Invalid)
    }

    @Test fun screenAndAppArguments() {
        assertEquals(PhoneTools.Call.ReadScreen, PhoneTools.parse("read_screen", null))
        assertEquals(PhoneTools.Call.OpenApp("Spotify"), PhoneTools.parse("open_app", args("name" to " Spotify ")))
        assertTrue(PhoneTools.parse("open_app", args()) is PhoneTools.Invalid)
        assertEquals(null, PhoneTools.parse("make_coffee", args()))
    }

    @Test fun resultsSayWhatThePhoneDid() {
        assertEquals("Timer set for 5 minutes (Pasta).", PhoneTools.timerSet(PhoneTools.Call.SetTimer(300, "Pasta")))
        assertEquals("Timer set for 1 hour 30 minutes.", PhoneTools.timerSet(PhoneTools.Call.SetTimer(5400, null)))
        assertEquals("Alarm set for 07:30 repeating mon, tue (Gym).", PhoneTools.alarmSet(PhoneTools.Call.SetAlarm(7, 30, listOf("mon", "tue"), "Gym")))
        assertEquals("Alarm set for 19:00.", PhoneTools.alarmSet(PhoneTools.Call.SetAlarm(19, 0, emptyList(), null)))
        assertEquals("The phone didn’t finish setting the timer in time.", PhoneTools.timedOut("set_timer"))
    }

    @Test fun actionRowsReadShortly() {
        assertEquals("10 min", PhoneTools.shortDuration(600))
        assertEquals("1 h 30 min", PhoneTools.shortDuration(5400))
        assertEquals("45 s", PhoneTools.shortDuration(45))
        assertEquals("7:00 AM", PhoneTools.spokenTime(7, 0))
        assertEquals("12:05 AM", PhoneTools.spokenTime(0, 5))
        assertEquals("Timer set · 10 min", PhoneTools.Action(PhoneTools.Action.Kind.timer, "10 min").text)
        assertEquals("Opened Spotify", PhoneTools.Action(PhoneTools.Action.Kind.app, "Spotify").text)
    }

    @Test fun appMatcherPrefersExactThenPrefixThenTypos() {
        val apps = listOf(
            AppMatcher.App("Spotify", "com.spotify.music"),
            AppMatcher.App("Camera", "com.android.camera"),
            AppMatcher.App("Google Maps", "com.google.android.apps.maps"),
            AppMatcher.App("Messages", "com.google.android.apps.messaging"),
            AppMatcher.App("Messenger", "com.facebook.orca"),
        )
        assertEquals(AppMatcher.Result.Found(apps[0]), AppMatcher.match("spotify", apps))
        assertEquals(AppMatcher.Result.Found(apps[0]), AppMatcher.match("the Spotify app", apps))
        assertEquals(AppMatcher.Result.Found(apps[2]), AppMatcher.match("maps", apps))
        assertEquals(AppMatcher.Result.Found(apps[1]), AppMatcher.match("camra", apps))
        assertEquals(AppMatcher.Result.Found(apps[3]), AppMatcher.match("Messages", apps))
        assertTrue(AppMatcher.match("mess", apps) is AppMatcher.Result.Several)
        assertEquals(AppMatcher.Result.None, AppMatcher.match("Photoshop", apps))
    }

    @Test fun runnerRefusesBadArgumentsWithoutRunning() = runTest {
        var ran = false
        val result = PhoneToolsRunner.run(
            "set_alarm",
            args("hour" to 25, "minute" to 0),
            "call_1",
            client { ran = true; PhoneTools.Result("ran") },
            AssistantPanelClient.testValue,
            ImmediateClock(),
        )
        assertTrue(result.isError)
        assertEquals("hour must be a whole number from 0 to 23.", result.text)
        assertFalse(ran)
        assertTrue(PhoneToolsRunner.run("send_text", null, "call_2", client { PhoneTools.Result("ran") }, AssistantPanelClient.testValue, ImmediateClock()).isError)
    }

    @Test fun runnerShowsTheChipForWhatItDid() = runTest {
        val acted = mutableListOf<PhoneTools.Action>()
        val panel = AssistantPanelClient(confirm = { true }, acted = { acted.add(it) })
        val timer = PhoneTools.Action(PhoneTools.Action.Kind.timer, "5 min")
        val result = PhoneToolsRunner.run(
            "set_timer",
            args("seconds" to 300),
            "call_timer",
            client { PhoneTools.Result("Timer set for 5 minutes.", action = timer) },
            panel,
            ImmediateClock(),
        )
        assertEquals("Timer set for 5 minutes.", result.text)
        assertFalse(result.isError)
        assertEquals(listOf(timer.copy(id = "call_timer")), acted)
    }

    @Test fun runnerGivesUpAfterThirtySeconds() = runTest {
        val result = PhoneToolsRunner.run(
            "set_timer",
            args("seconds" to 300),
            "call_slow",
            client { awaitCancellation() },
            AssistantPanelClient.testValue,
            ImmediateClock(),
        )
        assertTrue(result.isError)
        assertEquals("The phone didn’t finish setting the timer in time.", result.text)
        assertEquals(PhoneTools.timeout.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test fun screenIsReadWithoutACard() = runTest {
        val screen = PhoneTools.Result("{\"app\":\"Messages\",\"text\":\"See you at 6\"}", action = PhoneTools.Action(PhoneTools.Action.Kind.screen, ""))
        var asked = 0
        val read = PhoneToolsRunner.run(
            "read_screen",
            null,
            "call_screen",
            client { screen },
            AssistantPanelClient(confirm = { asked += 1; false }, acted = {}),
            ImmediateClock(),
        )
        assertEquals(screen.text, read.text)
        assertFalse(read.isError)
        assertEquals(0, asked)
    }

    @Test fun volumeTextAndEventToolsCheckTheirArguments() {
        assertEquals(PhoneTools.Call.Flashlight(true), PhoneTools.parse("flashlight", args("on" to true)))
        assertEquals(PhoneTools.Call.SetVolume("media", 40, null), PhoneTools.parse("set_volume", args("stream" to "media", "level" to 40)))
        assertEquals(PhoneTools.Invalid("Give exactly one of level or change."), PhoneTools.parse("set_volume", args("stream" to "ring", "level" to 3, "change" to "up")))
        assertEquals(PhoneTools.Call.SendText("+15550100", "I’m running late", "Sam Lee", draft = false), PhoneTools.parse("send_text", args("to" to "+15550100", "message" to "I’m running late", "name" to "Sam Lee")))
        assertTrue(PhoneTools.parse("send_text", args("to" to "+15550100")) is PhoneTools.Invalid)
        val event = PhoneTools.parse("add_calendar_event", args("title" to "Dinner", "start" to "2026-11-12T19:00")) as PhoneTools.Call.AddCalendarEvent
        assertEquals(java.time.LocalDateTime.of(2026, 11, 12, 20, 0), event.end)
        assertFalse(event.allDay)
        val allDay = PhoneTools.parse("add_calendar_event", args("title" to "Trip", "start" to "2026-11-12")) as PhoneTools.Call.AddCalendarEvent
        assertTrue(allDay.allDay)
        assertTrue(PhoneTools.parse("add_calendar_event", args("title" to "Bad", "start" to "2026-11-12T19:00", "end" to "2026-11-12T18:00")) is PhoneTools.Invalid)
        assertNull(event.calendar)
        val named = PhoneTools.parse("add_calendar_event", args("title" to "Dinner", "start" to "2026-11-12T19:00", "calendar" to " Work ")) as PhoneTools.Call.AddCalendarEvent
        assertEquals("Work", named.calendar)
    }

    @Test fun newEventsGoToTheOnlyAccountsCalendarNotThePhoneOnlyOne() {
        val onPhone = PhonePlaces.WritableCalendar(1, "My calendar", "My calendar", syncs = false, primary = true)
        val sam = PhonePlaces.WritableCalendar(4, "sam@example.com", "sam@example.com", syncs = true, primary = true)
        val family = PhonePlaces.WritableCalendar(13, "Family", "sam@example.com", syncs = true, primary = false)
        val work = PhonePlaces.WritableCalendar(20, "sam@work.example", "sam@work.example", syncs = true, primary = true)

        assertEquals(sam, PhonePlaces.pickCalendar(listOf(onPhone, family, sam), null))
        assertEquals(onPhone, PhonePlaces.pickCalendar(listOf(onPhone), null))
        assertEquals(family, PhonePlaces.pickCalendar(listOf(onPhone, family), null))
        assertNull(PhonePlaces.pickCalendar(listOf(onPhone, sam, family, work), null))
        assertNull(PhonePlaces.pickCalendar(emptyList(), null))
    }

    @Test fun aNamedCalendarIsFoundByNameOrAccount() {
        val onPhone = PhonePlaces.WritableCalendar(1, "My calendar", "My calendar", syncs = false, primary = true)
        val sam = PhonePlaces.WritableCalendar(4, "sam@example.com", "sam@example.com", syncs = true, primary = true)
        val family = PhonePlaces.WritableCalendar(13, "Family", "sam@example.com", syncs = true, primary = false)
        val work = PhonePlaces.WritableCalendar(20, "sam@work.example", "sam@work.example", syncs = true, primary = true)
        val all = listOf(onPhone, sam, family, work)

        assertEquals(work, PhonePlaces.pickCalendar(all, "SAM@work.example"))
        assertEquals(sam, PhonePlaces.pickCalendar(all, "sam@example.com"))
        assertEquals(family, PhonePlaces.pickCalendar(all, "family"))
        assertEquals(family, PhonePlaces.pickCalendar(all, "Family, sam@example.com"))
        assertEquals(onPhone, PhonePlaces.pickCalendar(all, "My calendar"))
        assertNull(PhonePlaces.pickCalendar(all, "Holidays"))
        assertEquals(
            "The phone has several calendars and the user didn’t say which, so nothing was added. " +
                "Ask the user which one, then call again with calendar. " +
                "The calendars: My calendar, on this phone only; sam@example.com; Family, sam@example.com; sam@work.example.",
            PhonePlaces.whichCalendar(all, null),
        )
        assertEquals(
            "“Holidays” doesn’t name one calendar on the phone, so nothing was added. " +
                "Ask the user which one they mean, then call again with calendar. " +
                "The calendars: My calendar, on this phone only; sam@example.com.",
            PhonePlaces.whichCalendar(listOf(onPhone, sam), "Holidays"),
        )
    }

    @Test fun callsAndTextsWaitForTheUser() = runTest {
        var ran = false
        val cards = mutableListOf<PhoneTools.Confirm>()
        val declined = PhoneToolsRunner.run(
            "send_text",
            args("to" to "+15550100", "message" to "Hi"),
            "call_text",
            client { ran = true; PhoneTools.Result("Sent.") },
            AssistantPanelClient(confirm = { cards.add(it); false }, acted = {}),
            ImmediateClock(),
        )
        assertEquals("The user declined. Nothing was sent.", declined.text)
        assertTrue(declined.isError)
        assertFalse(ran)
        assertEquals(1, cards.size)
        assertTrue(cards[0] is PhoneTools.Confirm.Text)
    }

    @Test fun callTextAndMapsReadTheirModes() {
        assertEquals(PhoneTools.Call.PhoneCall("+15550100", null, dialOnly = true), PhoneTools.parse("call", args("number" to "+15550100", "dial_only" to true)))
        assertEquals(PhoneTools.Call.SendText(null, "On my way", null, draft = true), PhoneTools.parse("send_text", args("message" to "On my way", "draft" to true)))
        assertEquals(PhoneTools.Invalid("to is required unless draft is true."), PhoneTools.parse("send_text", args("message" to "On my way")))
        assertEquals(PhoneTools.Call.OpenMaps("coffee", directions = false, mode = "driving"), PhoneTools.parse("open_maps", args("place" to "coffee")))
        assertEquals(PhoneTools.Call.AboutPhone, PhoneTools.parse("about_phone", args()))
        assertEquals(
            PhoneTools.Invalid("number must be a phone number, not a name. Look the person up with find_contact first."),
            PhoneTools.parse("call", args("number" to "Mom")),
        )
        assertEquals(
            PhoneTools.Invalid("name was given without to. Look Sam up with find_contact and pass the number as to."),
            PhoneTools.parse("send_text", args("name" to "Sam", "message" to "Hi")),
        )
        // A dialled call and a draft go straight to the user's own screen, so nothing asks first.
        assertEquals(null, PhoneTools.confirmFor(PhoneTools.Call.PhoneCall("+15550100", null, dialOnly = true)))
        assertEquals(null, PhoneTools.confirmFor(PhoneTools.Call.SendText(null, "On my way", null, draft = true)))
    }

    @Test fun unknownArgumentsAreNamed() {
        assertEquals(
            PhoneTools.Invalid("There’s no “minutes” argument. This tool takes: seconds, label."),
            PhoneTools.parse("set_timer", args("minutes" to 5)),
        )
        assertEquals(
            PhoneTools.Invalid("There’s no “all” argument. This tool takes: no arguments."),
            PhoneTools.parse("phone_status", args("all" to true)),
        )
    }

    @Test fun aboutPhoneListsEveryOtherTool() {
        val text = PhoneTools.aboutPhone()
        assertTrue(text.startsWith("Phone tools, called as tools.android.<name>(args):"))
        assertTrue(text.contains("- set_timer({ seconds, label? }): "))
        assertFalse(text.contains("- about_phone("))
    }

    @Test fun contactAddressesReadOnOneLine() {
        assertEquals("418 Maple Ave, Oak Park, IL 60302", PhonePeople.addressLine("418 Maple Ave\nOak Park, IL 60302\n"))
        assertEquals("12 Elm St, Springfield", PhonePeople.addressLine("  12 Elm St,\n\n Springfield "))
        assertNull(PhonePeople.addressLine(" \n "))
        assertNull(PhonePeople.addressLine(null))
    }

    @Test fun notificationToolsReadTheirArguments() {
        assertEquals(PhoneTools.Call.DismissNotification("0|a|1", null), PhoneTools.parse("dismiss_notification", args("key" to "0|a|1")))
        assertEquals(PhoneTools.Call.DismissNotification("0|a|1", 30), PhoneTools.parse("dismiss_notification", args("key" to "0|a|1", "snooze_minutes" to "30")))
        assertEquals(
            PhoneTools.Invalid("snooze_minutes must be a whole number from 1 to 1440."),
            PhoneTools.parse("dismiss_notification", args("key" to "0|a|1", "snooze_minutes" to 0)),
        )
        assertTrue(PhoneTools.parse("dismiss_notification", args("key" to "0|a|1", "snooze_minutes" to 1441)) is PhoneTools.Invalid)
        assertEquals(PhoneTools.Call.ReplyToNotification("0|a|1", "On my way"), PhoneTools.parse("reply_to_notification", args("key" to "0|a|1", "text" to " On my way ")))
        assertEquals(PhoneTools.Invalid("text is required."), PhoneTools.parse("reply_to_notification", args("key" to "0|a|1", "text" to " ")))
        assertEquals(PhoneTools.Invalid("key is required."), PhoneTools.parse("reply_to_notification", args("text" to "Hi")))
        assertEquals(PhoneTools.Call.NotificationAction("0|a|1", "Mark as read"), PhoneTools.parse("notification_action", args("key" to "0|a|1", "action" to "Mark as read")))
        assertEquals(
            PhoneTools.Invalid("action is required: a button’s title, or “open”."),
            PhoneTools.parse("notification_action", args("key" to "0|a|1")),
        )
        assertEquals(
            PhoneTools.Invalid("There’s no “message” argument. This tool takes: key, text."),
            PhoneTools.parse("reply_to_notification", args("key" to "0|a|1", "message" to "Hi")),
        )
    }

    @Test fun notificationCardsAndDeclines() {
        // The live client names the app and conversation; without it the card still shows the text.
        assertEquals(
            PhoneTools.Confirm.NotificationReply("the app", null, "On my way"),
            PhoneTools.confirmFor(PhoneTools.Call.ReplyToNotification("k", "On my way")),
        )
        assertNull(PhoneTools.confirmFor(PhoneTools.Call.NotificationAction("k", "Open")))
        assertEquals(
            PhoneTools.Confirm.NotificationAction("the app", null, "Delete"),
            PhoneTools.confirmFor(PhoneTools.Call.NotificationAction("k", "Delete")),
        )
        assertNull(PhoneTools.confirmFor(PhoneTools.Call.DismissNotification("k", 30)))
        assertEquals("The user declined. Nothing was sent.", PhoneTools.declined(PhoneTools.Call.ReplyToNotification("k", "Hi")))
        assertEquals("The user declined. The button wasn’t tapped.", PhoneTools.declined(PhoneTools.Call.NotificationAction("k", "Delete")))
        assertEquals("The phone didn’t finish sending the reply in time.", PhoneTools.timedOut("reply_to_notification"))
    }

    @Test fun aReplyShowsItsCardAndAGoneNotificationAnswersWithoutOne() = runTest {
        val cards = mutableListOf<PhoneTools.Confirm>()
        var ran = 0
        val card = PhoneTools.Confirm.NotificationReply("WhatsApp", "Sam", "On my way")
        val ready = PhoneToolsClient(run = { ran += 1; PhoneTools.Result("Replied.") }, prepare = { PhoneTools.Prepared.Ask(it, card) })
        val declined = PhoneToolsRunner.run(
            "reply_to_notification",
            args("key" to "k", "text" to "On my way"),
            "call_reply",
            ready,
            AssistantPanelClient(confirm = { cards.add(it); false }, acted = {}),
            ImmediateClock(),
        )
        assertEquals("The user declined. Nothing was sent.", declined.text)
        assertTrue(declined.isError)
        assertEquals(listOf<PhoneTools.Confirm>(card), cards)
        assertEquals(0, ran)

        val gone = PhoneToolsClient(
            run = { ran += 1; PhoneTools.Result("Replied.") },
            prepare = { PhoneTools.Prepared.Answer(PhoneTools.Result.failure("That notification is already gone.")) },
        )
        val answer = PhoneToolsRunner.run(
            "reply_to_notification",
            args("key" to "k", "text" to "On my way"),
            "call_gone",
            gone,
            AssistantPanelClient(confirm = { cards.add(it); true }, acted = {}),
            ImmediateClock(),
        )
        assertEquals("That notification is already gone.", answer.text)
        assertTrue(answer.isError)
        assertEquals(1, cards.size)
        assertEquals(0, ran)
    }

    @Test fun aTextThatCanOnlyBeADraftSkipsItsCard() = runTest {
        val text = PhoneTools.Call.SendText("+15550100", "On my way", "Sam", draft = false)
        assertEquals(
            PhoneTools.Prepared.Ask(text, PhoneTools.Confirm.Text("Sam", "+15550100", "On my way")),
            PhoneTools.askText(text, canSend = true),
        )
        assertEquals(PhoneTools.Prepared.Ask(text, null), PhoneTools.askText(text, canSend = false))

        val cards = mutableListOf<PhoneTools.Confirm>()
        val drafts = PhoneToolsClient(
            run = { PhoneTools.Result("Opened Messages with the text filled in instead. The user has to tap send.") },
            prepare = { PhoneTools.askText(it as PhoneTools.Call.SendText, canSend = false) },
        )
        val result = PhoneToolsRunner.run(
            "send_text",
            args("to" to "+15550100", "message" to "On my way"),
            "call_draft",
            drafts,
            AssistantPanelClient(confirm = { cards.add(it); false }, acted = {}),
            ImmediateClock(),
        )
        assertEquals("Opened Messages with the text filled in instead. The user has to tap send.", result.text)
        assertFalse(result.isError)
        assertTrue(cards.isEmpty())
    }

    @Test fun harmlessButtonsRunWithoutACard() {
        val none = android.app.Notification.Action.SEMANTIC_ACTION_NONE
        assertFalse(PhoneNotifications.asksFirst(none, "Mark as read"))
        assertFalse(PhoneNotifications.asksFirst(none, "Archive"))
        assertFalse(PhoneNotifications.asksFirst(none, "Pause…"))
        assertTrue(PhoneNotifications.asksFirst(none, "Delete"))
        assertTrue(PhoneNotifications.asksFirst(none, "Like"))
        assertTrue(PhoneNotifications.asksFirst(none, "Call back"))
        // Android's own meaning wins over the title.
        assertFalse(PhoneNotifications.asksFirst(android.app.Notification.Action.SEMANTIC_ACTION_MARK_AS_READ, "Done"))
        assertTrue(PhoneNotifications.asksFirst(android.app.Notification.Action.SEMANTIC_ACTION_DELETE, "Archive"))
        assertTrue(PhoneNotifications.asksFirst(android.app.Notification.Action.SEMANTIC_ACTION_THUMBS_UP, "OK"))
    }

    @Test fun buttonsAreFoundByTitle() {
        val titles = listOf("Reply", "Mark as read", "Mute")
        assertEquals(1, PhoneNotifications.findButton(titles, "mark as read"))
        assertEquals(1, PhoneNotifications.findButton(titles, "Mark As Read."))
        assertEquals(2, PhoneNotifications.findButton(titles, "mute"))
        assertEquals(1, PhoneNotifications.findButton(titles, "read"))
        assertEquals(1, PhoneNotifications.findButton(listOf("Mark as read", "Read aloud"), "read a"))
        assertNull(PhoneNotifications.findButton(listOf("Mark as read", "Mark as unread"), "mark"))
        assertNull(PhoneNotifications.findButton(titles, "Delete"))
        assertEquals(
            "That Gmail notification has no “Delete” button. Its buttons: Archive, Reply. Or use “open” to open it.",
            PhoneNotifications.noButton("Gmail", "Delete", listOf("Archive", "Reply")),
        )
    }

    @Test fun notificationResultsSayOnlyWhatTheAppShowed() {
        assertEquals(
            "Replied to Sam in WhatsApp: “On my way”. WhatsApp shows it in the conversation.",
            PhoneNotifications.replied("WhatsApp", "Sam", "On my way", PhoneNotifications.Seen.shown),
        )
        assertEquals(
            "Replied in Slack: “Done”. Slack took the reply and cleared the notification.",
            PhoneNotifications.replied("Slack", null, "Done", PhoneNotifications.Seen.cleared),
        )
        assertEquals(
            "Gave Messages the reply for Sam: “Yes”. Messages hasn’t shown it in the conversation yet, so it may still be sending; don’t say it was sent.",
            PhoneNotifications.replied("Messages", "Sam", "Yes", PhoneNotifications.Seen.unchanged),
        )
        assertEquals(
            "Tapped “Mark as read” on the Messages notification “Sam”. Messages cleared the notification.",
            PhoneNotifications.tapped("Messages", "Sam", "Mark as read", PhoneNotifications.Seen.cleared, opened = false),
        )
        assertEquals(
            "Tapped “View” on the Gmail notification. It opened Gmail.",
            PhoneNotifications.tapped("Gmail", null, "View", PhoneNotifications.Seen.unchanged, opened = true),
        )
        assertEquals("30 minutes", PhoneNotifications.snoozeLength(30))
        assertEquals("1 hour 15 minutes", PhoneNotifications.snoozeLength(75))
        assertEquals("24 hours", PhoneNotifications.snoozeLength(1440))
    }

    @Test fun notificationSpecsPointAtEachOther() {
        assertTrue(ClientTool.readNotifications.description.contains("can_reply"))
        assertTrue(ClientTool.readNotifications.description.contains("Android 15"))
        assertTrue(ClientTool.replyToNotification.description.substringBefore("\n").contains("The phone asks to confirm first."))
        assertTrue(ClientTool.dismissNotification.description.contains("snooze_minutes"))
        val about = PhoneTools.aboutPhone()
        assertTrue(about.contains("- reply_to_notification({ key, text }): "))
        assertTrue(about.contains("- notification_action({ key, action }): "))
        assertTrue(about.contains("- dismiss_notification({ key, snooze_minutes? }): "))
    }

    @Test fun theSpecSaysAddressesAreIncluded() {
        assertTrue(ClientTool.findContact.description.contains("postal addresses"))
        assertTrue(ClientTool.openMaps.description.contains("find_contact"))
    }

    @Test fun shareReadsTextAppAndSubject() {
        assertEquals(
            PhoneTools.Call.Share("418 Maple Ave, Oak Park, IL 60302", "Tesla", null),
            PhoneTools.parse("share", args("text" to " 418 Maple Ave, Oak Park, IL 60302 ", "app" to "Tesla")),
        )
        // No app opens Android's share sheet.
        assertEquals(PhoneTools.Call.Share("https://opencode.ai", null, "OpenCode"), PhoneTools.parse("share", args("text" to "https://opencode.ai", "app" to " ", "subject" to "OpenCode")))
        assertEquals(PhoneTools.Invalid("text is required: what to share."), PhoneTools.parse("share", args("app" to "WhatsApp")))
        assertEquals(PhoneTools.Invalid("text is required: what to share."), PhoneTools.parse("share", args("text" to "  ")))
        assertTrue(PhoneTools.parse("share", args("text" to "a".repeat(PhoneShare.MAX_TEXT + 1))) is PhoneTools.Invalid)
        assertEquals(
            PhoneTools.Invalid("There’s no “to” argument. This tool takes: text, app, subject."),
            PhoneTools.parse("share", args("text" to "Hi", "to" to "Mom")),
        )
    }

    @Test fun openLinkTakesWebAndAppLinks() {
        val links = listOf(
            "https://opencode.ai/docs",
            "http://example.com",
            "HTTPS://Example.com/Path",
            "https://wa.me/15551234567?text=Hi",
            "https://t.me/opencode",
            "https://m.uber.com/ul/?action=setPickup&pickup=my_location",
            "whatsapp://send?phone=15551234567",
            "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
            "geo:37.422,-122.084?q=coffee",
            "uber://?action=setPickup",
        )
        for (link in links) {
            assertEquals(link, PhoneTools.Call.OpenLink(link), PhoneTools.parse("open_link", args("url" to link)))
        }
        assertEquals(PhoneTools.Invalid("url is required."), PhoneTools.parse("open_link", args()))
    }

    @Test fun openLinkRefusesUnsafeSchemesAndPointsToTheRightTool() {
        val refused = listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "file:///sdcard/Download/secret.pdf",
            "content://contacts/people/1",
            "javascript:alert(1)",
            "data:text/html,<b>hi</b>",
            "opencode://pair?code=1234",
        )
        for (link in refused) {
            val parsed = PhoneTools.parse("open_link", args("url" to link)) as PhoneTools.Invalid
            assertTrue(parsed.message, parsed.message.endsWith("links can’t be opened: only web links (https) and app links are allowed."))
        }
        assertEquals(
            PhoneTools.Invalid("open_link doesn’t open phone numbers: use call with the number instead."),
            PhoneTools.parse("open_link", args("url" to "tel:+15550100")),
        )
        assertEquals(
            PhoneTools.Invalid("open_link doesn’t open text messages: use send_text instead (with draft to open it filled in)."),
            PhoneTools.parse("open_link", args("url" to "smsto:+15550100")),
        )
        assertTrue((PhoneTools.parse("open_link", args("url" to "sms:+15550100")) as PhoneTools.Invalid).message.contains("send_text"))
        assertEquals(
            PhoneTools.Invalid("open_link doesn’t open email: use draft_email instead."),
            PhoneTools.parse("open_link", args("url" to "mailto:sam@example.com")),
        )
        // Not whole links.
        assertTrue(PhoneTools.parse("open_link", args("url" to "opencode.ai")) is PhoneTools.Invalid)
        assertTrue(PhoneTools.parse("open_link", args("url" to "https://")) is PhoneTools.Invalid)
        assertTrue(PhoneTools.parse("open_link", args("url" to "https://example.com/a b")) is PhoneTools.Invalid)
        assertTrue(PhoneTools.parse("open_link", args("url" to "https://example.com/" + "a".repeat(9_000))) is PhoneTools.Invalid)
    }

    @Test fun sharingAndLinksOpenWithoutACard() = runTest {
        assertNull(PhoneTools.confirmFor(PhoneTools.Call.Share("Hi", "Tesla", null)))
        assertNull(PhoneTools.confirmFor(PhoneTools.Call.OpenLink("https://opencode.ai")))
        val acted = mutableListOf<PhoneTools.Action>()
        var asked = 0
        var shown = 0
        val tesla = PhoneTools.Action(PhoneTools.Action.Kind.app, "Tesla", packageName = "com.teslamotors.tesla")
        var ran: PhoneTools.Call? = null
        val result = PhoneToolsRunner.run(
            "share",
            args("text" to "418 Maple Ave", "app" to "Tesla"),
            "call_share",
            client { call -> ran = call; PhoneTools.Result("Opened Tesla with the text filled in. The user finishes sending it there.", action = tesla) },
            AssistantPanelClient(confirm = { asked += 1; false }, acted = { acted.add(it) }, showedScreen = { shown += 1 }),
            ImmediateClock(),
        )
        assertFalse(result.isError)
        assertEquals(0, asked)
        // The panel steps aside so the user can finish in Tesla.
        assertEquals(1, shown)
        assertEquals(PhoneTools.Call.Share("418 Maple Ave", "Tesla", null), ran)
        assertEquals(listOf(tesla.copy(id = "call_share")), acted)
        assertEquals("Opened Tesla", acted[0].text)
        assertEquals("The phone didn’t finish sharing the text in time.", PhoneTools.timedOut("share"))
        assertEquals("The phone didn’t finish opening the link in time.", PhoneTools.timedOut("open_link"))
    }

    @Test fun locationGivesAMapsLinkToShare() {
        assertEquals("https://www.google.com/maps/search/?api=1&query=37.422,-122.084", PhoneShare.mapsUrl(37.422, -122.084))
        assertEquals("https://www.google.com/maps/search/?api=1&query=41.878114,-87.629798", PhoneShare.mapsUrl(41.87811412, -87.6297982))
        assertEquals("https://www.google.com/maps/search/?api=1&query=0,0.0001", PhoneShare.mapsUrl(-0.0000001, 0.0001))
        assertTrue(ClientTool.getLocation.description.contains("maps_url"))
    }

    @Test fun theSpecPointsContentToShare() {
        val text = PhoneTools.aboutPhone()
        assertTrue(text.contains("- share({ text, app?, subject? }): Share text"))
        assertTrue(text.contains("- open_link({ url }): Open a web or app link"))
        assertTrue(PhoneToolSpecs.INSTRUCTION.contains("To get something to another app or person (an address to the car, a link to a chat, their location), share it"))
        assertTrue(PhoneToolSpecs.INSTRUCTION.contains("don’t open a related app or screen instead"))
        assertTrue(ClientTool.findContact.description.contains("pass it to share"))
        assertTrue(ClientTool.sendText.description.contains("use share"))
        assertTrue(ClientTool.copyToClipboard.description.contains("use share"))
        assertTrue(ClientTool.draftEmail.description.contains("use share"))
    }

    @Test fun mediaControlReadsSeekShuffleAndRepeat() {
        assertEquals(PhoneTools.Call.MediaControl("pause"), PhoneTools.parse("media_control", args("action" to "pause")))
        assertEquals(PhoneTools.Call.MediaControl("stop"), PhoneTools.parse("media_control", args("action" to "Stop")))
        assertEquals(PhoneTools.Call.MediaControl("seek", seconds = 90), PhoneTools.parse("media_control", args("action" to "seek", "seconds" to 90)))
        assertEquals(PhoneTools.Call.MediaControl("forward"), PhoneTools.parse("media_control", args("action" to "forward")))
        assertEquals(PhoneTools.Call.MediaControl("back", seconds = 15), PhoneTools.parse("media_control", args("action" to "back", "seconds" to 15)))
        assertEquals(PhoneTools.Call.MediaControl("shuffle", shuffle = false), PhoneTools.parse("media_control", args("action" to "shuffle", "shuffle" to false)))
        assertEquals(PhoneTools.Call.MediaControl("repeat", repeat = "one"), PhoneTools.parse("media_control", args("action" to "repeat", "repeat" to "one")))
        assertEquals(
            PhoneTools.Invalid("action must be one of play, pause, next, previous, stop, seek, forward, back, shuffle, repeat."),
            PhoneTools.parse("media_control", args("action" to "rewind")),
        )
        assertEquals(PhoneTools.Invalid("seconds must be a whole number from 0 to 86400."), PhoneTools.parse("media_control", args("action" to "seek")))
        assertEquals(PhoneTools.Invalid("seconds must be a whole number from 0 to 86400."), PhoneTools.parse("media_control", args("action" to "forward", "seconds" to -5)))
        assertEquals(PhoneTools.Invalid("seconds goes only with seek, forward or back."), PhoneTools.parse("media_control", args("action" to "pause", "seconds" to 5)))
        assertEquals(PhoneTools.Invalid("shuffle must be true or false."), PhoneTools.parse("media_control", args("action" to "shuffle")))
        assertEquals(PhoneTools.Invalid("shuffle goes only with action shuffle."), PhoneTools.parse("media_control", args("action" to "play", "shuffle" to true)))
        assertEquals(PhoneTools.Invalid("repeat must be off, one or all."), PhoneTools.parse("media_control", args("action" to "repeat", "repeat" to "twice")))
    }

    @Test fun playMediaReadsQueryAppAndKind() {
        assertEquals(PhoneTools.Call.PlayMedia("Bohemian Rhapsody", null, null), PhoneTools.parse("play_media", args("query" to " Bohemian Rhapsody ")))
        assertEquals(
            PhoneTools.Call.PlayMedia("Discover Weekly", "Spotify", "playlist"),
            PhoneTools.parse("play_media", args("query" to "Discover Weekly", "app" to "Spotify", "kind" to "Playlist")),
        )
        assertEquals(PhoneTools.Invalid("query must say what to play."), PhoneTools.parse("play_media", args("query" to "  ")))
        assertEquals(
            PhoneTools.Invalid("kind must be one of song, artist, album, playlist, podcast, video, station."),
            PhoneTools.parse("play_media", args("query" to "jazz", "kind" to "genre")),
        )
        // Nothing to confirm: the user asked for it, and it's undone with a pause.
        assertNull(PhoneTools.confirmFor(PhoneTools.Call.PlayMedia("jazz", null, null)))
    }

    @Test fun ringerAndDisplayCheckTheirArguments() {
        assertEquals(PhoneTools.Call.SetRinger("vibrate"), PhoneTools.parse("set_ringer", args("mode" to "vibrate")))
        assertEquals(PhoneTools.Invalid("mode must be normal, vibrate or silent."), PhoneTools.parse("set_ringer", args("mode" to "loud")))
        assertEquals(PhoneTools.Call.SetDisplay(30, autoBrightness = false, autoRotate = null, timeoutSeconds = null), PhoneTools.parse("set_display", args("brightness" to 30)))
        assertEquals(PhoneTools.Call.SetDisplay(null, autoBrightness = true, autoRotate = false, timeoutSeconds = 120), PhoneTools.parse("set_display", args("brightness" to "Auto", "auto_rotate" to false, "screen_timeout_seconds" to 120)))
        assertEquals(PhoneTools.Invalid("brightness must be a whole number from 0 to 100, or \"auto\"."), PhoneTools.parse("set_display", args("brightness" to 150)))
        assertEquals(PhoneTools.Invalid("brightness must be a whole number from 0 to 100, or \"auto\"."), PhoneTools.parse("set_display", args("brightness" to "max")))
        assertEquals(PhoneTools.Invalid("screen_timeout_seconds must be a whole number from 15 to 1800."), PhoneTools.parse("set_display", args("screen_timeout_seconds" to 5)))
        assertEquals(PhoneTools.Invalid("auto_rotate must be true or false."), PhoneTools.parse("set_display", args("auto_rotate" to "yes")))
        assertEquals(PhoneTools.Invalid("Give at least one of brightness, auto_rotate or screen_timeout_seconds."), PhoneTools.parse("set_display", args()))
    }

    @Test fun photoWaitsLongerThanOtherTools() = runTest {
        assertEquals(PhoneTools.Call.TakePhoto, PhoneTools.parse("take_photo", args()))
        val result = PhoneToolsRunner.run(
            "take_photo",
            args(),
            "call_photo",
            client { awaitCancellation() },
            AssistantPanelClient.testValue,
            ImmediateClock(),
        )
        assertEquals("The phone didn’t finish taking the photo in time.", result.text)
        assertEquals(PhoneTools.photoTimeout.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test fun aboutPhoneListsTheNewToolsAndWhatThePhoneCantDo() {
        val text = PhoneTools.aboutPhone()
        assertTrue(text.contains("- play_media({ query, app?, kind? }): "))
        assertTrue(text.contains("- media_control({ action, seconds?, shuffle?, repeat? }): "))
        assertTrue(text.contains("- set_ringer({ mode }): "))
        assertTrue(text.contains("- set_display({ brightness?, auto_rotate?, screen_timeout_seconds? }): "))
        assertTrue(text.contains("- take_photo(): "))
        assertTrue(text.endsWith("cancel a timer that’s still counting down, or list alarms and timers: say the phone can’t."))
        assertFalse(ClientTool.mediaControl.description.contains("can’t choose a song"))
    }

    @Test fun contactAddressesGetAMapsLink() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=418+Maple+Ave%2C+Oak+Park%2C+IL+60302",
            PhonePeople.mapsUrl("418 Maple Ave, Oak Park, IL 60302"),
        )
        assertTrue(ClientTool.findContact.description.contains("maps_url"))
    }

    @Test fun addContactReadsItsFields() {
        assertEquals(
            PhoneTools.Call.AddContact("Sam Lee", "+1 555 0100", "sam@example.com", "12 Elm St", "Acme", "Met at the conference"),
            PhoneTools.parse(
                "add_contact",
                args(
                    "name" to "Sam Lee",
                    "phone" to "+1 555 0100",
                    "email" to "sam@example.com",
                    "address" to "12 Elm St",
                    "company" to "Acme",
                    "notes" to "Met at the conference",
                ),
            ),
        )
        assertEquals(PhoneTools.Call.AddContact("Sam", null, null, null, null, null), PhoneTools.parse("add_contact", args("name" to "Sam")))
        assertEquals(PhoneTools.Invalid("name is required."), PhoneTools.parse("add_contact", args("phone" to "+15550100")))
        assertEquals(PhoneTools.Invalid("phone must be a phone number."), PhoneTools.parse("add_contact", args("name" to "Sam", "phone" to "Mom")))
        assertEquals(PhoneTools.Invalid("email must be an email address."), PhoneTools.parse("add_contact", args("name" to "Sam", "email" to "sam")))
        // The user saves it in Contacts, so nothing asks first.
        assertNull(PhoneTools.confirmFor(PhoneTools.Call.AddContact("Sam", null, null, null, null, null)))
    }

    @Test fun callControlsTakeNoArguments() {
        assertEquals(PhoneTools.Call.AnswerCall, PhoneTools.parse("answer_call", args()))
        assertEquals(PhoneTools.Call.EndCall, PhoneTools.parse("end_call", args()))
        assertTrue(PhoneTools.parse("end_call", args("number" to "+15550100")) is PhoneTools.Invalid)
        assertNull(PhoneTools.confirmFor(PhoneTools.Call.AnswerCall))
        assertNull(PhoneTools.confirmFor(PhoneTools.Call.EndCall))
    }

    @Test fun textsAndCallLogReadTheirFilters() {
        assertEquals(PhoneTools.Call.ReadTexts(null, false, 10), PhoneTools.parse("read_texts", args()))
        assertEquals(PhoneTools.Call.ReadTexts("Mum", true, 5), PhoneTools.parse("read_texts", args("from" to "Mum", "unread_only" to true, "limit" to 5)))
        assertEquals(PhoneTools.Invalid("limit must be a whole number from 1 to 30."), PhoneTools.parse("read_texts", args("limit" to 31)))
        assertEquals(PhoneTools.Invalid("unread_only must be true or false."), PhoneTools.parse("read_texts", args("unread_only" to "maybe")))
        assertEquals(PhoneTools.Call.ReadCallLog(null, null, 10), PhoneTools.parse("read_call_log", args()))
        assertEquals(PhoneTools.Call.ReadCallLog("missed", "+15550100", 3), PhoneTools.parse("read_call_log", args("type" to "missed", "from" to "+15550100", "limit" to 3)))
        assertEquals(PhoneTools.Invalid("type must be one of missed, incoming, outgoing."), PhoneTools.parse("read_call_log", args("type" to "voicemail")))
        assertEquals(PhoneTools.Invalid("limit must be a whole number from 1 to 50."), PhoneTools.parse("read_call_log", args("limit" to 0)))
        assertEquals(PhoneTools.Confirm.Texts("Mum"), PhoneTools.confirmFor(PhoneTools.Call.ReadTexts("Mum", false, 10)))
        assertEquals(PhoneTools.Confirm.CallLog(null), PhoneTools.confirmFor(PhoneTools.Call.ReadCallLog(null, null, 10)))
    }

    @Test fun phoneNumbersAreToldFromNames() {
        assertTrue(PhoneTools.isPhoneNumber("+1 (555) 010-0100"))
        assertTrue(PhoneTools.isPhoneNumber("911"))
        assertFalse(PhoneTools.isPhoneNumber("Mum"))
        assertFalse(PhoneTools.isPhoneNumber("12"))
        assertFalse(PhoneTools.isPhoneNumber("Flat 2"))
    }

    @Test fun eventIDsNameAnEventOrOneOccurrence() {
        assertEquals(PhoneTools.EventID(482, null), PhoneTools.EventID.parse("482"))
        assertEquals(PhoneTools.EventID(482, 1762963200000), PhoneTools.EventID.parse(" 482@1762963200000 "))
        assertEquals("482@1762963200000", PhoneTools.EventID(482, 1762963200000).toString())
        assertEquals("482", PhoneTools.EventID(482, null).toString())
        assertNull(PhoneTools.EventID.parse("Dentist"))
        assertNull(PhoneTools.EventID.parse("482@"))
        assertNull(PhoneTools.EventID.parse("1@2@3"))
        assertNull(PhoneTools.EventID.parse("-4"))
        assertTrue(ClientTool.listCalendarEvents.description.contains("event_id"))
    }

    @Test fun eventChangesCheckTheirArguments() {
        val moved = PhoneTools.parse("update_calendar_event", args("event_id" to "482@1762963200000", "start" to "2026-11-13T10:00")) as PhoneTools.Call.UpdateCalendarEvent
        assertEquals(PhoneTools.EventID(482, 1762963200000), moved.event)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 13, 10, 0), moved.start)
        assertFalse(moved.startIsDate)
        assertNull(moved.title)
        assertNull(moved.location)
        val cleared = PhoneTools.parse("update_calendar_event", args("event_id" to "482", "location" to "")) as PhoneTools.Call.UpdateCalendarEvent
        assertEquals("", cleared.location)
        assertEquals(
            PhoneTools.Invalid("Nothing to change: give at least one of title, start, end, all_day, location or notes."),
            PhoneTools.parse("update_calendar_event", args("event_id" to "482")),
        )
        assertEquals(PhoneTools.Invalid(PhoneTools.BAD_EVENT_ID), PhoneTools.parse("update_calendar_event", args("event_id" to "Dentist", "title" to "Dentist")))
        assertEquals(
            PhoneTools.Invalid("end must be after start."),
            PhoneTools.parse("update_calendar_event", args("event_id" to "482", "start" to "2026-11-13T10:00", "end" to "2026-11-13T09:00")),
        )
        assertEquals(PhoneTools.Call.DeleteCalendarEvent(PhoneTools.EventID(482, null)), PhoneTools.parse("delete_calendar_event", args("event_id" to "482")))
        assertEquals(PhoneTools.Invalid("event_id is required."), PhoneTools.parse("delete_calendar_event", args()))
        // Changing an event shows no card, as adding one doesn't; deleting always asks.
        assertNull(PhoneTools.confirmFor(moved))
        assertEquals(
            PhoneTools.Confirm.DeleteEvent("Dentist", "Thu 12 Nov, 19:00–20:00"),
            PhoneTools.confirmFor(PhoneTools.Call.DeleteCalendarEvent(PhoneTools.EventID(482, null), "Dentist", "Thu 12 Nov, 19:00–20:00")),
        )
    }

    @Test fun readingTextsWaitsForTheUser() = runTest {
        var ran = false
        val cards = mutableListOf<PhoneTools.Confirm>()
        val declined = PhoneToolsRunner.run(
            "read_texts",
            args("from" to "Mum"),
            "call_texts",
            client { ran = true; PhoneTools.Result("Texts.") },
            AssistantPanelClient(confirm = { cards.add(it); false }, acted = {}),
            ImmediateClock(),
        )
        assertEquals("The user declined. No texts were shared.", declined.text)
        assertTrue(declined.isError)
        assertFalse(ran)
        assertEquals(listOf<PhoneTools.Confirm>(PhoneTools.Confirm.Texts("Mum")), cards)
    }

    @Test fun preparingCanEndTheCallBeforeTheCard() = runTest {
        var ran = false
        val cards = mutableListOf<PhoneTools.Confirm>()
        val client = PhoneToolsClient(
            run = { ran = true; PhoneTools.Result("Calls.") },
            prepare = { PhoneTools.Prepared.Answer(PhoneTools.Result.failure("OpenCode isn’t the assistant.")) },
        )
        val result = PhoneToolsRunner.run(
            "read_call_log",
            args(),
            "call_log",
            client,
            AssistantPanelClient(confirm = { cards.add(it); true }, acted = {}),
            ImmediateClock(),
        )
        assertEquals("OpenCode isn’t the assistant.", result.text)
        assertTrue(result.isError)
        assertFalse(ran)
        assertTrue(cards.isEmpty())
    }

    @Test fun deletingAnEventNamesItOnTheCard() = runTest {
        var deleted: PhoneTools.Call? = null
        val cards = mutableListOf<PhoneTools.Confirm>()
        val client = PhoneToolsClient(
            run = { deleted = it; PhoneTools.Result("Deleted “Dentist”.") },
            prepare = { call ->
                val delete = call as PhoneTools.Call.DeleteCalendarEvent
                PhoneTools.ask(delete.copy(title = "Dentist", shown = "Thu 12 Nov, 19:00–20:00"))
            },
        )
        val result = PhoneToolsRunner.run(
            "delete_calendar_event",
            args("event_id" to "482"),
            "call_delete",
            client,
            AssistantPanelClient(confirm = { cards.add(it); true }, acted = {}),
            ImmediateClock(),
        )
        assertFalse(result.isError)
        assertEquals(listOf<PhoneTools.Confirm>(PhoneTools.Confirm.DeleteEvent("Dentist", "Thu 12 Nov, 19:00–20:00")), cards)
        assertEquals(PhoneTools.Call.DeleteCalendarEvent(PhoneTools.EventID(482, null), "Dentist", "Thu 12 Nov, 19:00–20:00"), deleted)
    }

    @Test fun behindLockSaysTheUserHasToUnlock() {
        val opened = PhoneTools.Result("Opened Messages.", action = PhoneTools.Action.other("Opened Messages"))
        val locked = PhoneTools.behindLock(opened)
        assertEquals("Opened Messages. The phone is locked, so the user has to unlock it to see it.", locked.text)
        assertFalse(locked.isError)
        assertEquals(opened.action, locked.action)
    }

    @Test fun behindLockKeepsTheDetailsLineLast() {
        val opened = PhoneTools.Result("Opened Gmail with a draft to Sam.\n{\"app\":\"Gmail\"}")
        assertEquals(
            "Opened Gmail with a draft to Sam. The phone is locked, so the user has to unlock it to see it.\n{\"app\":\"Gmail\"}",
            PhoneTools.behindLock(opened).text,
        )
    }

    @Test fun behindLockKeepsAFailure() {
        val asked = PhoneTools.Result.failure(PhoneTools.needsPermission("read the calendar"))
        val locked = PhoneTools.behindLock(asked)
        assertTrue(locked.isError)
        assertTrue(locked.text.endsWith(PhoneTools.OPENED_LOCKED))
    }

    @Test fun allDayEventsStayOnTheirOwnCalendarDay() {
        val nov18Utc = java.time.LocalDate.of(2026, 11, 18).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val nov19Utc = java.time.LocalDate.of(2026, 11, 19).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val nov20Utc = java.time.LocalDate.of(2026, 11, 20).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

        val chicago = java.time.ZoneId.of("America/Chicago")
        val chiStart = java.time.LocalDateTime.of(2026, 11, 18, 0, 0)
        val chiEnd = java.time.LocalDateTime.of(2026, 11, 19, 0, 0)
        assertTrue(PhonePlaces.instanceInRange(nov18Utc, nov19Utc, true, chiStart, chiEnd, chicago))
        assertFalse(PhonePlaces.instanceInRange(nov19Utc, nov20Utc, true, chiStart, chiEnd, chicago))

        val tokyo = java.time.ZoneId.of("Asia/Tokyo")
        val tokStart = java.time.LocalDateTime.of(2026, 11, 19, 0, 0)
        val tokEnd = java.time.LocalDateTime.of(2026, 11, 19, 23, 59)
        assertFalse(PhonePlaces.instanceInRange(nov18Utc, nov19Utc, true, tokStart, tokEnd, tokyo))
        assertTrue(PhonePlaces.instanceInRange(nov19Utc, nov20Utc, true, tokStart, tokEnd, tokyo))
        assertTrue(PhonePlaces.instanceInRange(nov18Utc, nov20Utc, true, tokStart, tokEnd, tokyo))
    }

    @Test fun aPointEventAtMidnightIsOnOneDayOnly() {
        val chicago = java.time.ZoneId.of("America/Chicago")
        val midnight = java.time.LocalDateTime.of(2026, 11, 19, 0, 0).atZone(chicago).toInstant().toEpochMilli()
        val nov18 = java.time.LocalDateTime.of(2026, 11, 18, 0, 0)
        val nov19 = java.time.LocalDateTime.of(2026, 11, 19, 0, 0)
        val nov20 = java.time.LocalDateTime.of(2026, 11, 20, 0, 0)

        assertFalse(PhonePlaces.instanceInRange(midnight, midnight, false, nov18, nov19, chicago))
        assertTrue(PhonePlaces.instanceInRange(midnight, midnight, false, nov19, nov20, chicago))
    }

    @Test fun calendarsInSeveralAccountsNeedAName() {
        val sam = PhonePlaces.WritableCalendar(4, "sam@example.com", "sam@example.com", syncs = true, primary = true)
        val shared = PhonePlaces.WritableCalendar(30, "Team", "sam@work.example", syncs = true, primary = false)

        assertNull(PhonePlaces.pickCalendar(listOf(sam, shared), null))
        assertEquals(shared, PhonePlaces.pickCalendar(listOf(sam, shared), "Team"))
    }

    @Test fun aNameAndAnAccountPointingAtDifferentCalendarsIsAmbiguous() {
        val named = PhonePlaces.WritableCalendar(5, "Work", "sam@example.com", syncs = true, primary = false)
        val account = PhonePlaces.WritableCalendar(6, "Work calendar", "Work", syncs = true, primary = true)

        assertNull(PhonePlaces.pickCalendar(listOf(named, account), "work"))
        assertEquals("Work calendar", PhonePlaces.WritableCalendar(7, "Work calendar", "", syncs = true, primary = true).label)
    }

    @Test fun brightnessIsTheRawSettingLevel() {
        assertEquals(1, PhoneDisplay.percentToLevel(0))
        assertEquals(0, PhoneDisplay.levelToPercent(0))
        assertEquals(128, PhoneDisplay.percentToLevel(50))
        assertEquals(50, PhoneDisplay.levelToPercent(128))
        assertEquals(255, PhoneDisplay.percentToLevel(100))
        assertEquals(100, PhoneDisplay.levelToPercent(255))
    }

    @Test fun nicknameMatchesOnlyItself() {
        assertEquals("Al", PhonePeople.likeExactly("Al"))
        assertEquals("100\\% \\_x\\\\", PhonePeople.likeExactly("100% _x\\"))
    }
}
