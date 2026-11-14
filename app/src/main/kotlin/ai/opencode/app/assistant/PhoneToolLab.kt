package ai.opencode.app.assistant

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.feature.voice.AssistantPanelClient
import ai.opencode.feature.voice.PhoneToolsClient
import ai.opencode.feature.voice.PhoneToolsRunner
import ai.opencode.feature.voice.VoiceLabDemo
import ai.opencode.feature.voice.VoiceLabTrigger
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The voice lab's phone tool triggers (debug builds with the lab only), so an emulator test can
 * run a phone tool as a call would, from wherever the user is, without a server:
 * - [VoiceLabTrigger.readScreen] runs `read_screen` and keeps the screenshot in the app's cache
 *   (`cache/lab-read-screen.jpg`).
 * - [VoiceLabTrigger.phoneTool] runs the tool named by the `tool` extra with the `args` extra
 *   (a JSON object, optional) and writes `{"tool", "is_error", "text"}` to
 *   `cache/lab-phone-tool.json` (and any screenshot to `cache/lab-phone-tool.jpg`).
 */
internal object PhoneToolLab {
    private val scope = MainScope()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val readScreen = intent.action == VoiceLabTrigger.readScreen.notification
            val tool = if (readScreen) "read_screen" else intent.getStringExtra("tool") ?: return
            val args: JsonObject? = intent.getStringExtra("args")?.let { raw ->
                runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            }
            scope.launch {
                val result = PhoneToolsRunner.run(
                    tool,
                    args,
                    "lab_$tool",
                    dependency(PhoneToolsClient),
                    dependency(AssistantPanelClient),
                    dependency(Clock),
                )
                val jpeg = result.jpeg
                val base = if (readScreen) "lab-read-screen" else "lab-phone-tool"
                val picture = File(context.cacheDir, "$base.jpg")
                if (jpeg != null) picture.writeBytes(jpeg) else picture.delete()
                if (!readScreen) {
                    val report = buildJsonObject {
                        put("tool", JsonPrimitive(tool))
                        put("is_error", JsonPrimitive(result.isError))
                        put("text", JsonPrimitive(result.text))
                    }
                    File(context.cacheDir, "$base.json").writeText(report.toString())
                }
                // Lab only: the result's text is an emulator test's.
                AppLog.app.info { "Lab $tool error=${result.isError} jpeg=${jpeg?.size ?: 0} text=${result.text.take(300)}" }
            }
        }
    }

    fun register(context: Context) {
        if (registered || !VoiceLabDemo.isEnabled) return
        registered = true
        val filter = IntentFilter(VoiceLabTrigger.readScreen.notification)
        filter.addAction(VoiceLabTrigger.phoneTool.notification)
        runCatching { context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) }
    }
}
