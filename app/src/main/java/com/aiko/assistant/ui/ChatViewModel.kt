package com.aiko.assistant.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiko.assistant.core.ApiMessage
import com.aiko.assistant.core.ClaudeClient
import com.aiko.assistant.core.ContentBlock
import com.aiko.assistant.core.StoredMessage
import com.aiko.assistant.core.ChatHistory
import com.aiko.assistant.core.SystemPrompt
import com.aiko.assistant.core.ToolResultPayload
import com.aiko.assistant.tools.ToolDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

data class ChatUiMessage(
    val role: String,
    val text: String
)

enum class Mood { HAPPY, SAD, THINKING, EXCITED, LOVING, NEUTRAL, SLEEPY, SURPRISED }

data class ChatUiState(
    val messages: List<ChatUiMessage> = emptyList(),
    val thinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val mood: Mood = Mood.HAPPY
)

object AppContextHolder {
    @Volatile var context: Context? = null
}

class ChatViewModel : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui

    private var history: MutableList<StoredMessage> = mutableListOf()
    private var prefs: SharedPreferences? = null

    private val claude by lazy { ClaudeClient { apiKey() } }

    private fun apiKey(): String = prefs?.getString("api_key", "").orEmpty()
    fun apiKeySet() = apiKey().isNotBlank()
    fun saveApiKey(key: String) { prefs?.edit()?.putString("api_key", key.trim())?.apply() }
    fun displayApiKey(): String = apiKey()

    fun ensureHistoryLoaded(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("aiko", Context.MODE_PRIVATE)
        history = ChatHistory.load(context).toMutableList()
        _ui.value = _ui.value.copy(messages = history.map { ChatUiMessage(it.role, it.text) })
        if (history.isEmpty()) {
            _ui.value = _ui.value.copy(
                messages = listOf(
                    ChatUiMessage(
                        "assistant",
                        "Hi jaan! Main Aiko hoon, tumhari apni AI companion. " +
                        "Bol kya haal? Mujhse files banana, apps kholna, WhatsApp message bhejna, " +
                        "alarms lagana - sab kuch karwa sakte ho. Pehle apni Anthropic API key " +
                        "settings me daal do, phir sab ready!"
                    )
                )
            )
        }
    }

    fun send(userText: String) {
        if (userText.isBlank() || _ui.value.thinking) return
        if (!apiKeySet()) {
            _ui.value = _ui.value.copy(
                messages = _ui.value.messages + ChatUiMessage(
                    "assistant", "Pehle apni Anthropic API key daalo - top-right settings icon dabao"
                )
            )
            return
        }

        _ui.value = _ui.value.copy(
            messages = _ui.value.messages + ChatUiMessage("user", userText),
            thinking = true
        )

        viewModelScope.launch {
            try { runAgentLoop(userText) }
            catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    messages = _ui.value.messages + ChatUiMessage("assistant", "Error: ${e.message}"),
                    thinking = false, isSpeaking = false
                )
            }
        }
    }

    private suspend fun runAgentLoop(userText: String) = withContext(Dispatchers.IO) {
        val appContext = AppContextHolder.context
            ?: throw IllegalStateException("App context not ready")

        val apiHistory = ArrayList<ApiMessage>()
        for (m in history.takeLast(30)) {
            apiHistory.add(ApiMessage(m.role, listOf(ContentBlock(type = "text", text = m.text))))
        }
        apiHistory.add(ApiMessage("user", listOf(ContentBlock(type = "text", text = userText))))

        val dispatcher = ToolDispatcher(appContext)
        var round = 0
        var finalText = ""
        var mood = Mood.NEUTRAL

        while (true) {
            if (++round > 6) {
                finalText = "Bas, bahut saare steps ho gaye - thoda specific bol na"
                mood = Mood.THINKING
                break
            }

            val resp = claude.send(SystemPrompt.TEXT, apiHistory)
            val textParts = resp.content.filter { it.type == "text" }
            val toolUses = resp.content.filter { it.type == "tool_use" }

            textParts.forEach { t ->
                val raw = t.text.orEmpty()
                if (raw.isBlank()) return@forEach
                val (clean, m) = parseMood(raw)
                mood = m
                finalText = clean
            }

            if (toolUses.isEmpty()) break

            apiHistory.add(ApiMessage("assistant", resp.content))

            val results = ArrayList<ToolResultPayload>()
            for (tu in toolUses) {
                val id = tu.id ?: continue
                val input = (tu.input as? JsonObject) ?: JsonObject(emptyMap())

                withContext(Dispatchers.Main) {
                    _ui.value = _ui.value.copy(
                        messages = _ui.value.messages + ChatUiMessage("status", "${tu.name} ..."),
                        thinking = true
                    )
                }

                // execute; sync tools return their payload immediately,
                // async flows (WhatsApp send) register a pending id instead
                val syncPayload = dispatcher.execute(id, tu.name ?: "", input) {}

                val payload: ToolResultPayload = if (ToolDispatcher.isAsyncPending(id)) {
                    var waited = 0
                    var asyncRes: String? = null
                    while (waited < 25000) {
                        Thread.sleep(500); waited += 500
                        asyncRes = ToolDispatcher.takeAsyncResult(id)
                        if (asyncRes != null) break
                    }
                    ToolResultPayload(
                        id,
                        asyncRes ?: "Timeout: flow didn't complete - check the screen and finish manually",
                        isError = asyncRes == null
                    )
                } else syncPayload

                results.add(payload)

                withContext(Dispatchers.Main) {
                    _ui.value = _ui.value.copy(
                        messages = _ui.value.messages.filterNot {
                            it.role == "status" && it.text.contains(tu.name ?: "")
                        }
                    )
                }
            }

            apiHistory.add(claude.toolResultMessage(results))
        }

        history.add(StoredMessage("user", userText))
        history.add(StoredMessage("assistant", finalText))
        ChatHistory.save(appContext, history)

        withContext(Dispatchers.Main) {
            _ui.value = _ui.value.copy(
                messages = _ui.value.messages + ChatUiMessage("assistant", finalText),
                thinking = false, isSpeaking = true, mood = mood
            )
            kotlinx.coroutines.delay((finalText.length * 45L).coerceIn(1500, 9000))
            _ui.value = _ui.value.copy(isSpeaking = false)
        }
    }

    private fun parseMood(raw: String): Pair<String, Mood> {
        val m = Regex("""\[MOOD:\s*(\w+)]""").find(raw)
        val mood = when (m?.groupValues?.get(1)?.lowercase()) {
            "happy" -> Mood.HAPPY
            "sad" -> Mood.SAD
            "thinking" -> Mood.THINKING
            "excited" -> Mood.EXCITED
            "loving" -> Mood.LOVING
            "sleepy" -> Mood.SLEEPY
            "surprised" -> Mood.SURPRISED
            else -> Mood.NEUTRAL
        }
        val clean = raw.replace(Regex("""\s*\[MOOD:\s*\w+]\s*"""), "").trim()
        return clean to mood
    }
}
