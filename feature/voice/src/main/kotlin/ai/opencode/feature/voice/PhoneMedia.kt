package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.app.SearchManager
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive

/**
 * `play_media` and `media_control` for [PhoneToolsClient] (Android only). With notification access
 * the phone drives the active media session's own controls and reads its state back; without it,
 * play, pause, skip and stop fall back to media keys. Runs on the main thread.
 */
internal object PhoneMedia {
    private const val SETTLE_MILLIS = 700L
    private const val WAIT_MILLIS = 2_500L
    private const val PLAY_WAIT_MILLIS = 6_000L
    private const val POLL_MILLIS = 100L
    private const val FORWARD_SECONDS = 30
    private const val BACK_SECONDS = 10

    // MediaSessionCompat's (and Media3's) shuffle and repeat, which framework controllers send as custom actions.
    private const val ACTION_SET_SHUFFLE = 1L shl 21
    private const val ACTION_SET_REPEAT = 1L shl 18
    private const val CUSTOM_SHUFFLE = "android.support.v4.media.session.action.SET_SHUFFLE_MODE"
    private const val CUSTOM_SHUFFLE_ARGUMENT = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE"
    private const val CUSTOM_REPEAT = "android.support.v4.media.session.action.SET_REPEAT_MODE"
    private const val CUSTOM_REPEAT_ARGUMENT = "android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE"

    private const val NOTHING_READY = "no media app is ready. Ask the user which app to open."

    suspend fun control(call: PhoneTools.Call.MediaControl): PhoneTools.Result {
        val context = dependency(AppContext)
        val session = PhoneDevice.mediaSession()
        if (session == null) {
            // Keys reach the app that played last even when no session is active.
            when (call.action) {
                "play", "pause", "next", "previous" -> return PhoneDevice.media(call.action)
                "stop" -> return stopWithKey()
            }
            // Seeking, shuffle and repeat need the session itself.
            PhoneAccess.requireSpecial(context, PhoneAccess.notifications)?.let { return it }
            return PhoneTools.Result.failure("No media app is playing or paused, so there’s nothing to ${verb(call.action)}. Ask the user what to play.")
        }
        val app = PhoneLaunch.appName(session.packageName)
        val controls = session.transportControls
        return when (call.action) {
            "play" -> {
                controls.play()
                settled(session, wantsPlaying = true, "Playing.", "Nothing is playing after asking $app to play: $NOTHING_READY")
            }
            "pause" -> {
                controls.pause()
                settled(session, wantsPlaying = false, "Paused.", "$app is still playing after asking it to pause.")
            }
            "next" -> {
                controls.skipToNext()
                settled(session, wantsPlaying = true, "Skipped to the next track.", "Nothing is playing after asking $app to skip: $NOTHING_READY")
            }
            "previous" -> {
                controls.skipToPrevious()
                settled(session, wantsPlaying = true, "Went back a track.", "Nothing is playing after asking $app to go back: $NOTHING_READY")
            }
            "stop" -> {
                controls.stop()
                settled(session, wantsPlaying = false, "Stopped.", "$app is still playing after asking it to stop.")
            }
            "seek" -> seek(session, app, (call.seconds ?: 0) * 1000L)
            "forward" -> {
                val seconds = call.seconds ?: FORWARD_SECONDS
                seek(session, app, position(session) + seconds * 1000L)
            }
            "back" -> {
                val seconds = call.seconds ?: BACK_SECONDS
                seek(session, app, position(session) - seconds * 1000L)
            }
            "shuffle" -> shuffle(session, app, call.shuffle == true)
            else -> repeat(session, app, call.repeat ?: "off")
        }
    }

    /** Waits for the session to play (or stop playing), then answers with what's playing now. */
    private suspend fun settled(session: MediaController, wantsPlaying: Boolean, done: String, failed: String): PhoneTools.Result {
        delay(SETTLE_MILLIS)
        var waited = SETTLE_MILLIS
        while (isPlaying(session) != wantsPlaying && waited < WAIT_MILLIS) {
            delay(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        val details = mapOf("now_playing" to PhoneDevice.nowPlaying())
        if (isPlaying(session) != wantsPlaying) return PhoneTools.Result.failure(PhoneDetails.text(failed, details))
        return PhoneTools.Result(PhoneDetails.text(done, details), action = PhoneTools.Action.other(done.removeSuffix(".")))
    }

    private suspend fun seek(session: MediaController, app: String, wanted: Long): PhoneTools.Result {
        val actions = session.playbackState?.actions ?: 0L
        if (actions and PlaybackState.ACTION_SEEK_TO == 0L) {
            return PhoneTools.Result.failure("$app doesn’t let other apps move through what it’s playing.")
        }
        var target = if (wanted < 0) 0L else wanted
        val length = session.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (length > 0 && target > length) target = length
        session.transportControls.seekTo(target)
        delay(SETTLE_MILLIS)
        val text = "Jumped to ${PhoneDevice.clockLength(position(session))}."
        val details = mapOf("now_playing" to PhoneDevice.nowPlaying())
        return PhoneTools.Result(PhoneDetails.text(text, details), action = PhoneTools.Action.other(text.removeSuffix(".")))
    }

    /** Where playback is now: the last reported position plus the time it has played since. */
    private fun position(session: MediaController): Long {
        val state = session.playbackState ?: return 0L
        if (state.position < 0) return 0L
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        val since = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return state.position + (since * state.playbackSpeed).toLong()
    }

    private fun shuffle(session: MediaController, app: String, on: Boolean): PhoneTools.Result {
        val actions = session.playbackState?.actions ?: 0L
        if (actions and ACTION_SET_SHUFFLE == 0L) return PhoneTools.Result.failure("$app doesn’t let other apps change shuffle.")
        val extras = Bundle()
        // PlaybackStateCompat.SHUFFLE_MODE_ALL is 1, SHUFFLE_MODE_NONE 0.
        extras.putInt(CUSTOM_SHUFFLE_ARGUMENT, if (on) 1 else 0)
        session.transportControls.sendCustomAction(CUSTOM_SHUFFLE, extras)
        val text = if (on) "Asked $app to turn shuffle on." else "Asked $app to turn shuffle off."
        return PhoneTools.Result(
            "$text Android doesn’t show other apps whether it did.",
            action = PhoneTools.Action.other(if (on) "Shuffle on" else "Shuffle off"),
        )
    }

    private fun repeat(session: MediaController, app: String, mode: String): PhoneTools.Result {
        val actions = session.playbackState?.actions ?: 0L
        if (actions and ACTION_SET_REPEAT == 0L) return PhoneTools.Result.failure("$app doesn’t let other apps change repeat.")
        // PlaybackStateCompat.REPEAT_MODE_NONE is 0, ONE 1 and ALL 2.
        val value = when (mode) {
            "one" -> 1
            "all" -> 2
            else -> 0
        }
        val extras = Bundle()
        extras.putInt(CUSTOM_REPEAT_ARGUMENT, value)
        session.transportControls.sendCustomAction(CUSTOM_REPEAT, extras)
        val text = when (mode) {
            "one" -> "Asked $app to repeat this track."
            "all" -> "Asked $app to repeat everything."
            else -> "Asked $app to turn repeat off."
        }
        val chip = when (mode) {
            "one" -> "Repeat one"
            "all" -> "Repeat all"
            else -> "Repeat off"
        }
        return PhoneTools.Result("$text Android doesn’t show other apps whether it did.", action = PhoneTools.Action.other(chip))
    }

    private suspend fun stopWithKey(): PhoneTools.Result {
        val audio = dependency(AppContext).getSystemService(AudioManager::class.java)
            ?: return PhoneTools.Result.failure("Nothing is playing.")
        if (!PhoneDevice.isPlaying(audio)) return PhoneTools.Result("Nothing is playing.")
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))
        var waited = 0L
        while (PhoneDevice.isPlaying(audio) && waited < WAIT_MILLIS) {
            delay(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        if (PhoneDevice.isPlaying(audio)) return PhoneTools.Result.failure("Media is still playing after sending “stop”.")
        return PhoneTools.Result("Stopped.", action = PhoneTools.Action.other("Stopped"))
    }

    private fun isPlaying(session: MediaController): Boolean {
        val state = session.playbackState?.state
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    private fun verb(action: String): String = when (action) {
        "seek", "forward", "back" -> "move through"
        "shuffle" -> "shuffle"
        else -> "repeat"
    }

    /**
     * Plays [PhoneTools.Call.PlayMedia.query] in the app the user named, else the app that played
     * last, else the phone's one media app. A running session that takes searches plays it without
     * opening anything; otherwise the app opens with Android's play-from-search request.
     */
    suspend fun play(call: PhoneTools.Call.PlayMedia): PhoneTools.Result {
        val context = dependency(AppContext)
        val packages = context.packageManager
        val handlers = mutableListOf<AppMatcher.App>()
        val seen = mutableSetOf<String>()
        for (info in packages.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)) {
            val packageName = info.activityInfo.packageName
            if (packageName == context.packageName || !seen.add(packageName)) continue
            handlers += AppMatcher.App(label = PhoneLaunch.appName(packageName), packageName = packageName)
        }
        val session = PhoneDevice.mediaSession()

        var chosen: String? = null
        if (call.app != null) {
            when (val match = AppMatcher.match(call.app, handlers)) {
                is AppMatcher.Result.Found -> chosen = match.app.packageName
                is AppMatcher.Result.Several -> {
                    val names = mutableListOf<String>()
                    for (app in match.apps) names += app.label
                    return PhoneTools.Result.failure("Several apps match “${call.app}”: ${names.joinToString(", ")}. Ask the user which one.")
                }
                AppMatcher.Result.None -> {
                    if (session != null && AppMatcher.isExact(call.app, AppMatcher.App(PhoneLaunch.appName(session.packageName), session.packageName))) {
                        chosen = session.packageName
                    } else {
                        return PhoneTools.Result.failure(noSuchPlayer(call.app, handlers))
                    }
                }
            }
        } else if (session != null && (takesSearches(session) || session.packageName in seen)) {
            chosen = session.packageName
        } else if (handlers.size == 1) {
            chosen = handlers[0].packageName
        } else if (handlers.isEmpty()) {
            return PhoneTools.Result.failure("No app on this phone can play something by name. Ask the user which app to open.")
        } else {
            val names = mutableListOf<String>()
            for (app in handlers) names += app.label
            return PhoneTools.Result.failure("Which app should play it? This phone has ${names.joinToString(", ")}. Ask the user, then pass app.")
        }
        val packageName = chosen ?: return PhoneTools.Result.failure("No app on this phone can play something by name. Ask the user which app to open.")
        val app = PhoneLaunch.appName(packageName)
        val extras = searchExtras(call.query, call.kind)
        val startedAt = SystemClock.elapsedRealtime()

        val running = if (session != null && session.packageName == packageName && takesSearches(session)) session else null
        val opened: Boolean
        if (running != null) {
            running.transportControls.playFromSearch(call.query, extras)
            opened = false
        } else {
            if (packageName !in seen) {
                return PhoneTools.Result.failure("$app can’t be asked to play something by name. Open it with open_app and let the user pick.")
            }
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(packageName).putExtras(extras)
            when (PhoneLaunch.start(intent)) {
                PhoneLaunch.Launch.started -> opened = true
                PhoneLaunch.Launch.blocked -> return PhoneTools.Result.failure(PhoneTools.BLOCKED)
                PhoneLaunch.Launch.noApp -> return PhoneTools.Result.failure("$app couldn’t take the request to play something. Open it with open_app instead.")
            }
        }

        val playing = waitForPlaying(packageName, startedAt)
        val chip = PhoneTools.Action.other("Playing in $app")
        if (playing) {
            val text = if (opened) "Opened $app and it’s playing." else "Playing in $app."
            val details = mapOf("app" to JsonPrimitive(app), "now_playing" to PhoneDevice.nowPlaying())
            return PhoneTools.Result(PhoneDetails.text(text, details), action = chip)
        }
        // What played last may be another app, so it isn't reported here.
        val details = mapOf("app" to JsonPrimitive(app))
        if (!PhoneAccess.isGranted(context, PhoneAccess.notifications)) {
            val text = if (opened) "Opened $app and asked it to play that." else "Asked $app to play that."
            return PhoneTools.Result(
                PhoneDetails.text("$text The phone can’t see whether it started without notification access.", details),
                action = chip,
            )
        }
        if (opened) {
            return PhoneTools.Result.failure(
                PhoneDetails.text("Opened $app and asked it to play that, but nothing is playing yet: it may be showing results for the user to pick from.", details),
            )
        }
        return PhoneTools.Result.failure(PhoneDetails.text("Asked $app to play that, but nothing is playing yet.", details))
    }

    /** True once [packageName]'s session plays something it started after [since]. */
    private suspend fun waitForPlaying(packageName: String, since: Long): Boolean {
        var waited = 0L
        while (waited < PLAY_WAIT_MILLIS) {
            delay(POLL_MILLIS * 3)
            waited += POLL_MILLIS * 3
            val session = PhoneDevice.mediaSession() ?: continue
            if (session.packageName != packageName) continue
            val state = session.playbackState ?: continue
            if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime >= since) return true
        }
        return false
    }

    private fun takesSearches(session: MediaController): Boolean {
        val actions = session.playbackState?.actions ?: 0L
        return actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L
    }

    private fun noSuchPlayer(name: String, handlers: List<AppMatcher.App>): String {
        if (handlers.isEmpty()) return "No app called “$name” can be asked to play something by name; no app on this phone can. Use open_app to open it instead."
        val names = mutableListOf<String>()
        for (app in handlers) names += app.label
        return "No app called “$name” can be asked to play something by name. These can: ${names.joinToString(", ")}. If $name is installed, open it with open_app instead."
    }

    /** Android's play-from-search extras: the query, and what kind of thing it names. */
    @Suppress("DEPRECATION")
    internal fun searchExtras(query: String, kind: String?): Bundle {
        val extras = Bundle()
        extras.putString(SearchManager.QUERY, query)
        when (kind) {
            "song" -> {
                extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                extras.putString(MediaStore.EXTRA_MEDIA_TITLE, query)
            }
            "artist" -> {
                extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                extras.putString(MediaStore.EXTRA_MEDIA_ARTIST, query)
            }
            "album" -> {
                extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
                extras.putString(MediaStore.EXTRA_MEDIA_ALBUM, query)
            }
            "playlist" -> {
                extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
                extras.putString(MediaStore.EXTRA_MEDIA_PLAYLIST, query)
            }
            "station" -> {
                extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Radio.ENTRY_CONTENT_TYPE)
                extras.putString(MediaStore.EXTRA_MEDIA_RADIO_CHANNEL, query)
            }
            // Podcasts, videos and anything unsaid are a free-form search.
            else -> {}
        }
        return extras
    }
}
