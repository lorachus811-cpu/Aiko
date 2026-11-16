package com.aiko.assistant.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// ---------- API DTOs ----------

@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    @SerialName("tool_use_id") val toolUseId: String? = null,
    @SerialName("is_error") val isError: Boolean? = null
)

@Serializable
data class ApiMessage(
    val role: String,
    val content: List<ContentBlock>
)

@Serializable
data class ClaudeRequest(
    val model: String = "claude-sonnet-4-20250514",
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    val system: String,
    val messages: List<ApiMessage>,
    val tools: List<JsonElement>
)

@Serializable
data class ClaudeResponse(
    val id: String? = null,
    val content: List<ContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null
)

object ClaudeJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

// ---------- Tool definitions ----------

object ToolDefinitions {

    val toolsJson: String = """
[
 {"name":"open_app","description":"Open an app on the user's phone by its display name.",
  "input_schema":{"type":"object","properties":{
    "app_name":{"type":"string","description":"App display name, e.g. WhatsApp, Chrome, Spck, Camera, YouTube"}}, "required":["app_name"]}},
 {"name":"close_app","description":"Close the current app (go home). Ask user confirmation first.",
  "input_schema":{"type":"object","properties":{},"required":[]}},
 {"name":"send_message","description":"Open a messaging app and auto-send a text to a contact using the accessibility service. Ask confirmation with exact contact + text before calling.",
  "input_schema":{"type":"object","properties":{
    "app_name":{"type":"string","description":"e.g. WhatsApp"},
    "contact":{"type":"string","description":"Contact name exactly as shown in the app's search"},
    "text":{"type":"string"}}, "required":["app_name","contact","text"]}},
 {"name":"take_screenshot","description":"Capture and read the current screen text via the accessibility tree.",
  "input_schema":{"type":"object","properties":{},"required":[]}},
 {"name":"press_key","description":"Press a system key.",
  "input_schema":{"type":"object","properties":{
    "key":{"type":"string","enum":["back","home","recents","notifications"]}},"required":["key"]}},
 {"name":"toggle_setting","description":"Turn a phone setting on/off or set its value.",
  "input_schema":{"type":"object","properties":{
    "setting":{"type":"string","enum":["wifi","bluetooth","flashlight","ringer","brightness","volume","dnd"]},
    "value":{"type":"string","description":"'on' or 'off'; a number 0-100 for brightness/volume"}}, "required":["setting","value"]}},
 {"name":"file_list","description":"List files and folders in a directory.",
  "input_schema":{"type":"object","properties":{
    "directory":{"type":"string","description":"e.g. 'Documents', 'Download', or a full path"}}, "required":["directory"]}},
 {"name":"file_read","description":"Read a text file's content.",
  "input_schema":{"type":"object","properties":{
    "path":{"type":"string","description":"Full path, e.g. 'Download/notes.txt"}}, "required":["path"]}},
 {"name":"file_write","description":"Create or overwrite a text file with content. Parent folders are created automatically.",
  "input_schema":{"type":"object","properties":{
    "path":{"type":"string","description":"Full path, e.g. 'Documents/hello.py'"},
    "content":{"type":"string"}}, "required":["path","content"]}},
 {"name":"file_delete","description":"Delete a file. ALWAYS get explicit user confirmation before calling.",
  "input_schema":{"type":"object","properties":{
    "path":{"type":"string"}}, "required":["path"]}},
 {"name":"spck_write_code","description":"Write code into a Spck Editor project. Creates project files in a folder Spck can import. Optionally opens Spck afterwards.",
  "input_schema":{"type":"object","properties":{
    "project":{"type":"string","description":"Spck project name, created if missing"},
    "file_path":{"type":"string","description":"File inside the project, e.g. 'js/main.js'"},
    "content":{"type":"string"},
    "open_editor":{"type":"boolean","description":"true to launch Spck afterwards"}}, "required":["project","file_path","content"]}},
 {"name":"set_clipboard","description":"Copy text to the system clipboard.",
  "input_schema":{"type":"object","properties":{
    "text":{"type":"string"}}, "required":["text"]}},
 {"name":"web_search","description":"Open a web search or URL in the browser.",
  "input_schema":{"type":"object","properties":{
    "query_or_url":{"type":"string"}}, "required":["query_or_url"]}},
 {"name":"set_alarm","description":"Set an alarm in the clock app.",
  "input_schema":{"type":"object","properties":{
    "label":{"type":"string"},
    "hour":{"type":"integer","minimum":0,"maximum":23},
    "minute":{"type":"integer","minimum":0,"maximum":59}}, "required":["label","hour","minute"]}}
]
"""

    fun asJsonElement(): List<JsonElement> =
        ClaudeJson.json.decodeFromString<JsonElement>(toolsJson).jsonArray
}

// ---------- HTTP client ----------

class ClaudeClient(private val apiKeyProvider: () -> String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    fun send(system: String, history: List<ApiMessage>): ClaudeResponse {
        val body = ClaudeRequest(
            system = system,
            messages = history,
            tools = ToolDefinitions.asJsonElement()
        )
        val payload = ClaudeJson.json.encodeToString(ClaudeRequest.serializer(), body)
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKeyProvider())
            .header("anthropic-version", "2023-06-01")
            .post(payload.toByteArray().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("Claude API ${resp.code}: ${text.take(400)}")
            return ClaudeJson.json.decodeFromString(ClaudeResponse.serializer(), text)
        }
    }

    fun toolResultMessage(toolResults: List<ToolResultPayload>): ApiMessage {
        val blocks = toolResults.map { r ->
            ContentBlock(
                type = "tool_result",
                toolUseId = r.toolUseId,
                text = r.content.take(4000),
                isError = r.isError
            )
        }
        return ApiMessage(role = "user", content = blocks)
    }
}

data class ToolResultPayload(
    val toolUseId: String,
    val content: String,
    val isError: Boolean = false
)

// ---------- Chat history persistence ----------

@Serializable
data class StoredMessage(
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

object ChatHistory {
    private const val MAX_MESSAGES = 120

    fun file(context: android.content.Context) =
        java.io.File(context.filesDir, "aiko_history.json")

    fun load(context: android.content.Context): MutableList<StoredMessage> {
        return try {
            val f = file(context)
            if (f.exists())
                ClaudeJson.json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(StoredMessage.serializer()), f.readText()
                ).toMutableList()
            else mutableListOf()
        } catch (e: Exception) { mutableListOf() }
    }

    fun save(context: android.content.Context, msgs: List<StoredMessage>) {
        try {
            file(context).writeText(
                ClaudeJson.json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(StoredMessage.serializer()), msgs.takeLast(MAX_MESSAGES)
                )
            )
        } catch (_: Exception) {}
    }
}
