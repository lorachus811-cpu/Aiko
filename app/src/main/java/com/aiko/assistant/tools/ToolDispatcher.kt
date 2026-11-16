package com.aiko.assistant.tools

import android.content.Context
import com.aiko.assistant.core.ToolResultPayload
import com.aiko.assistant.service.AgentAccessibilityService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ToolDispatcher(private val context: Context) {

    fun execute(
        toolUseId: String,
        name: String,
        inputJson: JsonObject,
        onAsyncDone: (String) -> Unit
    ): ToolResultPayload {

        fun str(key: String): String = inputJson[key]?.jsonPrimitive?.content ?: ""
        fun bool(key: String, def: Boolean = false): Boolean =
            inputJson[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: def
        fun int(key: String): Int = inputJson[key]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        val a11y: AgentAccessibilityService? = AgentAccessibilityService.instance

        return try {
            when (name) {
                "open_app" -> ToolResultPayload(toolUseId, DeviceTools.openApp(context, str("app_name")))
                "close_app" -> ToolResultPayload(toolUseId, DeviceTools.closeApp(context))

                "send_message" -> {
                    if (a11y == null) ToolResultPayload(
                        toolUseId,
                        "Error: accessibility service not enabled - ask the user to enable 'Aiko Control' in Settings > Accessibility",
                        isError = true
                    ) else {
                        when (str("app_name").lowercase().ifEmpty { "whatsapp" }) {
                            "whatsapp" -> {
                                pendingAsyncIds.add(toolUseId)
                                a11y.sendWhatsAppMessage(str("contact"), str("text")) { res ->
                                    asyncResults[toolUseId] = res
                                    onAsyncDone(res)
                                }
                                ToolResultPayload(toolUseId, "")
                            }
                            else -> ToolResultPayload(
                                toolUseId,
                                "Error: auto-send currently only supports WhatsApp in v1",
                                isError = true
                            )
                        }
                    }
                }

                "take_screenshot", "read_screen" ->
                    ToolResultPayload(
                        toolUseId,
                        if (a11y != null) a11y.screenshotText()
                        else "Error: accessibility service not enabled (Settings > Accessibility > Aiko Control)",
                        isError = a11y == null
                    )

                "press_key" ->
                    ToolResultPayload(
                        toolUseId,
                        if (a11y != null) a11y.pressKey(str("key"))
                        else "Error: accessibility service not enabled",
                        isError = a11y == null
                    )

                "toggle_setting" -> ToolResultPayload(toolUseId, DeviceTools.toggleSetting(context, str("setting"), str("value")))
                "file_list" -> ToolResultPayload(toolUseId, DeviceTools.fileList(str("directory")))
                "file_read" -> ToolResultPayload(toolUseId, DeviceTools.fileRead(str("path")))
                "file_write" -> ToolResultPayload(toolUseId, DeviceTools.fileWrite(str("path"), str("content")))
                "file_delete" -> ToolResultPayload(toolUseId, DeviceTools.fileDelete(str("path")))
                "spck_write_code" -> {
                    DeviceTools.init(context)
                    ToolResultPayload(
                        toolUseId,
                        DeviceTools.spckWriteCode(str("project"), str("file_path"), str("content"), bool("open_editor", true))
                    )
                }
                "set_clipboard" -> ToolResultPayload(toolUseId, DeviceTools.setClipboard(context, str("text")))
                "web_search" -> ToolResultPayload(toolUseId, DeviceTools.webSearch(context, str("query_or_url")))
                "set_alarm" -> ToolResultPayload(toolUseId, DeviceTools.setAlarm(context, str("label"), int("hour"), int("minute")))
                else -> ToolResultPayload(toolUseId, "Error: unknown tool: $name", isError = true)
            }
        } catch (e: Exception) {
            ToolResultPayload(toolUseId, "Error executing $name: ${e.message}", isError = true)
        }
    }

    companion object {
        val pendingAsyncIds = mutableSetOf<String>()
        val asyncResults = mutableMapOf<String, String>()

        fun isAsyncPending(toolUseId: String) = pendingAsyncIds.contains(toolUseId)
        fun takeAsyncResult(toolUseId: String): String? {
            val r = asyncResults.remove(toolUseId)
            pendingAsyncIds.remove(toolUseId)
            return r
        }
    }
}
