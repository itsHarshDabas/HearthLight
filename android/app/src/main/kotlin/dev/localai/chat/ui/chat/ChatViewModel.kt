package dev.localai.chat.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localai.chat.LocalAiApp
import dev.localai.core.ChatEvent
import dev.localai.core.ChatMessage
import dev.localai.core.ChatRequest
import dev.localai.core.Conversation
import dev.localai.core.ConversationStore
import dev.localai.core.ConversationSummary
import dev.localai.core.StoredMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversation: Conversation = Conversation(),
    val streaming: Boolean = false,
    val streamingContent: String = "",
    val streamingReasoning: String = "",
    val models: List<String> = emptyList(),
    val selectedModel: String? = null,
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LocalAiApp).container
    val conversations: StateFlow<List<ConversationSummary>> = container.conversations.summaries

    private val _state = MutableStateFlow(ChatUiState(selectedModel = container.settings.state.value.lastModel))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var streamJob: Job? = null

    init {
        refreshModels()
    }

    fun refreshModels() {
        val client = container.client() ?: run {
            _state.update { it.copy(modelsError = "Set up your server in Settings") }
            return
        }
        _state.update { it.copy(modelsLoading = true, modelsError = null) }
        viewModelScope.launch {
            try {
                val models = client.models().filterNot { it.isEmbedding }.map { it.id }
                _state.update { s ->
                    val keep = s.selectedModel?.takeIf { it in models } ?: models.firstOrNull()
                    s.copy(models = models, selectedModel = keep, modelsLoading = false,
                        modelsError = if (models.isEmpty()) "No models loaded in LM Studio" else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(modelsLoading = false, modelsError = e.message ?: "Couldn't load models") }
            }
        }
    }

    fun selectModel(model: String) {
        _state.update { it.copy(selectedModel = model) }
        container.settings.update { it.copy(lastModel = model) }
    }

    fun newChat() {
        stop()
        _state.update { it.copy(conversation = Conversation()) }
    }

    fun open(id: String) {
        if (id == _state.value.conversation.id) return
        stop()
        viewModelScope.launch {
            val c = container.conversations.get(id) ?: return@launch
            _state.update { s -> s.copy(conversation = c, selectedModel = c.model?.takeIf { it in s.models } ?: s.selectedModel) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            container.conversations.delete(id)
            if (_state.value.conversation.id == id) newChat()
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.streaming) return
        val conv = _state.value.conversation
        val updated = conv.copy(
            title = if (conv.messages.isEmpty()) ConversationStore.titleFrom(trimmed) else conv.title,
            messages = conv.messages + StoredMessage(role = "user", content = trimmed),
            updatedAt = System.currentTimeMillis(),
        )
        generate(updated)
    }

    /** Drops the last assistant reply (if any) and asks again. */
    fun regenerate() {
        if (_state.value.streaming) return
        val conv = _state.value.conversation
        val msgs = conv.messages.dropLastWhile { it.role == "assistant" }
        if (msgs.isEmpty()) return
        generate(conv.copy(messages = msgs))
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    private fun generate(conversation: Conversation) {
        val client = container.client()
        val model = _state.value.selectedModel
        val conv = conversation.copy(model = model ?: conversation.model)
        _state.update { it.copy(conversation = conv, streaming = true, streamingContent = "", streamingReasoning = "") }
        viewModelScope.launch { container.conversations.save(conv) }

        streamJob = viewModelScope.launch {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var error: String? = null
            try {
                if (client == null) throw IllegalStateException("Set up your server in Settings first")
                val settings = container.settings.state.value
                val history = buildList {
                    if (settings.systemPrompt.isNotBlank()) add(ChatMessage("system", settings.systemPrompt))
                    conv.messages.forEach { m ->
                        if (m.content.isNotBlank()) add(ChatMessage(m.role, m.content))
                    }
                }
                val request = ChatRequest(model = model, messages = history, temperature = settings.temperature?.toDouble())
                var lastUi = 0L
                client.streamChat(request).collect { ev ->
                    if (ev is ChatEvent.Delta) {
                        content.append(ev.content)
                        reasoning.append(ev.reasoning)
                        // ~30 UI updates/sec is plenty and keeps long replies smooth
                        val now = System.nanoTime()
                        if (now - lastUi > 33_000_000L) {
                            lastUi = now
                            _state.update { it.copy(streamingContent = content.toString(), streamingReasoning = reasoning.toString()) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // stopped by the user: keep what we have
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                val reply = StoredMessage(
                    role = "assistant",
                    content = content.toString(),
                    reasoning = reasoning.toString(),
                    error = error,
                )
                val finalConv = conv.copy(
                    messages = if (reply.content.isEmpty() && reply.reasoning.isEmpty() && reply.error == null) conv.messages else conv.messages + reply,
                    updatedAt = System.currentTimeMillis(),
                )
                _state.update {
                    if (it.conversation.id != conv.id) it.copy(streaming = false) // user switched chats meanwhile
                    else it.copy(conversation = finalConv, streaming = false, streamingContent = "", streamingReasoning = "")
                }
                container.appScope.launch { container.conversations.save(finalConv) }
            }
        }
    }

    override fun onCleared() {
        stop()
    }
}
