package ai.opencode.feature.settings

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.AssistantExperiment
import ai.opencode.core.experiments.AssistantExperiment.SessionChoice
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.ui.OpenCodeSearchField
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.system.ScopedID
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.assistant.AssistantSetupClient
import ai.opencode.feature.voice.PhoneAccess
import ai.opencode.feature.voice.PhoneAccessClient
import ai.opencode.feature.voice.VoiceFeature
import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Settings › Assistant (Android only; iOS can't replace Siri). The first card makes holding the
 * side button start a call: OpenCode as the assistant app, the side button pointed at the
 * assistant, and Voice. Android doesn't let an app take the assistant role, so the switch opens
 * the system screen that picks it; the role and the side button are read again each time the
 * page comes back. Its footer names only the next missing step.
 */
@Composable
fun AssistantSettingsView(store: SettingsStore) {
    val setup = remember { dependency(AssistantSetupClient) }
    var isAssistant by remember { mutableStateOf(setup.isAssistant()) }
    var sideButtonSetting by remember { mutableStateOf(setup.sideButtonOpensAssistant()) }
    LifecycleResumeEffect(Unit) {
        isAssistant = setup.isAssistant()
        sideButtonSetting = setup.sideButtonOpensAssistant()
        onPauseOrDispose {}
    }
    val sideButtonUsed by remember { shared(AssistantExperiment.sideButtonUsed).flow }.collectAsState()
    val voiceModeEnabled by remember { shared(SharedKeys.voiceModeEnabled).flow }.collectAsState()
    val allCalls by remember { shared(AssistantExperiment.phoneToolsInAllCalls).flow }.collectAsState()
    val choiceRaw by remember { shared(AssistantExperiment.sessionChoice).flow }.collectAsState()
    val pinnedTitle by remember { shared(AssistantExperiment.pinnedSessionTitle).flow }.collectAsState()
    val pinnedMissing by remember { shared(AssistantExperiment.pinnedSessionMissing).flow }.collectAsState()
    val choice = SessionChoice.fromRawValue(choiceRaw)
    // Where the phone's own setting can't be read, holding the button once marks it set.
    val sideButtonSet = sideButtonSetting ?: sideButtonUsed
    val hint = setupHint(isAssistant, sideButtonSet, sideButtonKnown = sideButtonSetting != null, voiceModeEnabled)
    val send = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }
    val push = { page: SettingsPage -> store.send(SettingsFeature.Action.PagePushed(page)) }

    SettingsPageScaffold(title = "Assistant", store = store) {
        settingsSection(key = "setup", footer = hint?.let { footerText(it) }) {
            SettingsToggleRow(
                "Use OpenCode as Assistant",
                checked = isAssistant,
                onCheckedChange = { send(HomeSettingsFeature.Action.AssistantSettingsTapped) },
            )
            SettingsDivider()
            SetupStatusRow("Side Button", done = sideButtonSet, doneLabel = "Set", missingLabel = "Set Up") {
                send(HomeSettingsFeature.Action.SideButtonSettingsTapped)
            }
            SettingsDivider()
            SetupStatusRow("Voice", done = voiceModeEnabled, doneLabel = "Ready", missingLabel = "Off") {
                push(SettingsPage.Voice)
            }
        }
        settingsSection(
            key = "behavior",
            footer = if (choice == SessionChoice.pinned && pinnedMissing) footerText("Couldn’t load the pinned session, so calls start a new session.") else null,
        ) {
            SettingsMenuRow(
                "Session",
                value = choice.title,
                selection = choice,
                options = sessionOptions,
                onSelect = { picked ->
                    if (picked == SessionChoice.pinned && pinnedTitle.isEmpty()) {
                        // Nothing pinned yet: picking a session selects Pinned Session.
                        push(SettingsPage.AssistantSession)
                    } else {
                        send(HomeSettingsFeature.Action.AssistantSessionChoiceChanged(picked))
                    }
                },
            )
            if (choice == SessionChoice.pinned) {
                SettingsDivider()
                SettingsRow(pinnedTitle.ifEmpty { "Choose a Session" }, symbol = null) { push(SettingsPage.AssistantSession) }
            }
            SettingsDivider()
            SettingsMenuRow(
                "Phone Tools",
                value = if (allCalls) "All Calls" else "Assistant Calls Only",
                selection = allCalls,
                options = phoneToolsOptions,
                onSelect = { send(HomeSettingsFeature.Action.AssistantPhoneToolsReachChanged(allCalls = it)) },
            )
            SettingsDivider()
            SettingsRow("Permissions", symbol = null) { push(SettingsPage.AssistantPermissions) }
        }
    }
}

/** Settings › Assistant › Session's menu. */
private val sessionOptions = listOf(
    SettingsMenuOption(SessionChoice.newSession, SessionChoice.newSession.title),
    SettingsMenuOption(SessionChoice.mostRecent, SessionChoice.mostRecent.title),
    SettingsMenuOption(SessionChoice.pinned, SessionChoice.pinned.title),
)

/** Settings › Assistant › Phone Tools' menu: whether calls started in the app get the phone tools too. */
private val phoneToolsOptions = listOf(
    SettingsMenuOption(false, "Assistant Calls Only"),
    SettingsMenuOption(true, "All Calls"),
)

/** The setup card's footer: the next missing step only, or nothing once all three are done. */
private fun setupHint(isAssistant: Boolean, sideButtonSet: Boolean, sideButtonKnown: Boolean, voiceOn: Boolean): String? {
    if (!isAssistant) return "Choose OpenCode as Digital Assistant App."
    if (!sideButtonSet) {
        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val step = if (samsung) {
            "Under Side Button, set Press and Hold to Digital Assistant"
        } else {
            "Set Press & Hold Power Button to Digital Assistant"
        }
        // Without the phone's setting, the row turns set once the button has opened OpenCode.
        return if (sideButtonKnown) "$step." else "$step, then try it."
    }
    if (!voiceOn) return "Turn on Voice to talk instead of type."
    return null
}

/** A setup row: the title, then [doneLabel] once done, or [missingLabel], as a status badge. */
@Composable
private fun SetupStatusRow(title: String, done: Boolean, doneLabel: String, missingLabel: String, onClick: () -> Unit) {
    SettingsRow(title, symbol = null, badge = if (done) doneLabel else missingLabel, onClick = onClick)
}

/**
 * Settings › Assistant › Permissions, laid out like Android's per-app permissions: what a call
 * does without asking first (each Ask First or Always), then the runtime permissions and special
 * access the phone tools use, Allowed or Not Allowed, each opening the screen that changes it.
 * Use Text From Screen shows only while it's off, since reading the screen needs it. Both are
 * read again each time the page comes back.
 */
@Composable
internal fun AssistantPermissionsView(store: SettingsStore) {
    val setup = remember { dependency(AssistantSetupClient) }
    val access = remember { dependency(PhoneAccessClient) }
    var usesTextFromScreen by remember { mutableStateOf(setup.usesTextFromScreen()) }
    var granted by remember { mutableStateOf(grantedAccess(access)) }
    LifecycleResumeEffect(Unit) {
        usesTextFromScreen = setup.usesTextFromScreen()
        granted = grantedAccess(access)
        onPauseOrDispose {}
    }
    val calls by remember { shared(AssistantExperiment.callsWithoutAsking).flow }.collectAsState()
    val texts by remember { shared(AssistantExperiment.textsWithoutAsking).flow }.collectAsState()
    val readsNotifications by remember { shared(AssistantExperiment.readsNotificationsWithoutAsking).flow }.collectAsState()
    val send = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }

    SettingsPageScaffold(title = "Permissions", store = store) {
        settingsSection(
            key = "asking",
            header = "Actions",
            footer = footerText(
                if (usesTextFromScreen) {
                    "Ask First shows what it’ll do and waits for you."
                } else {
                    "Without Use Text From Screen, Android asks before a call first sees your screen."
                },
            ),
        ) {
            if (!usesTextFromScreen) {
                SetupStatusRow("Use Text From Screen", done = false, doneLabel = "On", missingLabel = "Off") {
                    send(HomeSettingsFeature.Action.AssistantSettingsTapped)
                }
                SettingsDivider()
            }
            AskFirstRow("Make Calls", always = calls) {
                send(HomeSettingsFeature.Action.AssistantAskingChanged(AssistantAsking.calls, it))
            }
            SettingsDivider()
            AskFirstRow("Send Texts", always = texts) {
                send(HomeSettingsFeature.Action.AssistantAskingChanged(AssistantAsking.texts, it))
            }
            SettingsDivider()
            AskFirstRow("Read Notifications", always = readsNotifications) {
                send(HomeSettingsFeature.Action.AssistantAskingChanged(AssistantAsking.notifications, it))
            }
        }
        settingsSection(
            key = "android-permissions",
            header = "Android Permissions",
            footer = footerText("Timers, alarms, apps and media need nothing here. Texts, Read Texts and Call Log work only while OpenCode is the digital assistant."),
        ) {
            PhoneAccessRows(PhoneAccess.entries.filter { !it.isSpecial }, granted, send)
        }
        settingsSection(
            key = "special-access",
            header = "Special App Access",
            footer = footerText("To read notifications, turn Do Not Disturb on and off, and change brightness, rotation and screen timeout."),
        ) {
            PhoneAccessRows(PhoneAccess.entries.filter { it.isSpecial }, granted, send)
        }
    }
}

/** A Permissions action: Ask First (the call shows a card first), or Always (without asking). */
@Composable
private fun AskFirstRow(title: String, always: Boolean, onChange: (Boolean) -> Unit) {
    SettingsMenuRow(
        title,
        value = if (always) "Always" else "Ask First",
        selection = always,
        options = askFirstOptions,
        onSelect = onChange,
    )
}

private val askFirstOptions = listOf(
    SettingsMenuOption(false, "Ask First"),
    SettingsMenuOption(true, "Always"),
)

@Composable
private fun PhoneAccessRows(items: List<PhoneAccess>, granted: Set<PhoneAccess>, send: (HomeSettingsFeature.Action) -> Unit) {
    var first = true
    for (item in items) {
        if (!first) SettingsDivider()
        first = false
        PhoneAccessRow(item, isGranted = item in granted) {
            send(HomeSettingsFeature.Action.AssistantAllowTapped(item))
        }
    }
}

/**
 * Settings › Assistant › Pinned Session: the connected server's sessions, newest first, as Voice ›
 * Delegator lists them. Picking one pins it and selects Pinned Session.
 */
@Composable
internal fun AssistantSessionPickerView(store: SettingsStore) {
    val voice = rememberVoiceStore(store)
    val state = rememberVoiceState(voice)
    var searchQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(voice) { voice.send(VoiceSettingsFeature.Action.DelegatorPageAppeared) }
    val pinnedRaw by remember { shared(AssistantExperiment.pinnedSession).flow }.collectAsState()
    val choiceRaw by remember { shared(AssistantExperiment.sessionChoice).flow }.collectAsState()
    val pinned = if (SessionChoice.fromRawValue(choiceRaw) == SessionChoice.pinned) pinnedRaw else ""
    val server = state.connectedServer
    val query = searchQuery.trim()
    val sessions = state.delegatorSessions.filter { session ->
        query.isEmpty() ||
            session.title.contains(query, ignoreCase = true) ||
            lastPathComponent(session.directory).contains(query, ignoreCase = true)
    }

    SettingsPageScaffold(title = "Pinned Session", store = store) {
        if (server == null) {
            settingsSection(key = "no-server") {
                DelegatorNote("Connect to a server to pick a session.", SFSymbol.InfoCircle, OpenCodeTheme.colors.secondaryText)
            }
            return@SettingsPageScaffold
        }
        val serverKey = SessionDirectory.serverKey(server.persistenceID)
        item(key = "search") {
            OpenCodeSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Filter sessions",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        settingsSection(
            key = "sessions",
            footer = footerText("Holding the side button talks to this session until you pick another."),
        ) {
            if (sessions.isEmpty()) {
                SettingsRowFrame {
                    Text(
                        if (query.isEmpty()) "No Sessions on This Server" else "No Matching Sessions",
                        style = SettingsText.title,
                        color = OpenCodeTheme.colors.secondaryText,
                    )
                }
            }
            sessions.forEachIndexed { index, session ->
                if (index > 0) SettingsDivider()
                val folder = lastPathComponent(session.directory)
                val title = VoiceFeature.State.usableTitle(session.title) ?: "New session"
                val scoped = ScopedID(serverKey, session.id).rawValue
                ModeRow(
                    title = title,
                    subtitle = if (folder.isEmpty() || folder == "/") null else folder,
                    isSelected = pinned == scoped,
                ) {
                    store.send(SettingsFeature.Action.Settings(HomeSettingsFeature.Action.AssistantSessionPinned(scoped, title)))
                }
            }
        }
    }
}

private fun grantedAccess(client: PhoneAccessClient): Set<PhoneAccess> {
    val granted = mutableSetOf<PhoneAccess>()
    for (item in PhoneAccess.entries) {
        if (client.isGranted(item)) granted += item
    }
    return granted
}

/**
 * A permission or special access: Allowed or Not Allowed as a status badge; the row opens what
 * grants or changes it. Play allows the [PhoneAccess.needsAssistant] ones only for the default
 * digital assistant, which the section footer says.
 */
@Composable
private fun PhoneAccessRow(access: PhoneAccess, isGranted: Boolean, onClick: () -> Unit) {
    SettingsRow(access.title, symbol = null, badge = if (isGranted) "Allowed" else "Not Allowed", onClick = onClick)
}
