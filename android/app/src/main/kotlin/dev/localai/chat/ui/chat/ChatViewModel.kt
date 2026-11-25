package dev.localai.chat.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localai.chat.LocalAiApp
import dev.localai.chat.data.AttachmentException
import dev.localai.core.Attachment
import dev.localai.core.ChatEvent
import dev.localai.core.ChatMessage
import dev.localai.core.ChatRequest
import dev.localai.core.Conversation
import dev.localai.core.ConversationStore
import dev.localai.core.ConversationSummary
import dev.localai.core.GatewayException
import dev.localai.core.Prompt
import dev.localai.core.StoredMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** An attachment in the composer, before the message is sent. */
data class PendingAttachment(
    val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val name: String,
    val attachment: Attachment? = null,
    val error: String? = null,
) {
    val loading: Boolean get() = attachment == null && error == null
}

data class ChatUiState(
    val conversation: Conversation = Conversation(),
    val streaming: Boolean = false,
    val streamingContent: String = "",
    val streamingReasoning: String = "",
    val models: List<String> = emptyList(),
    val selectedModel: String? = null,
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    val pending: List<PendingAttachment> = emptyList(),
    val recordingSince: Long? = null,
    val notice: String? = null,
) {
    val attachmentsLoading: Boolean get() = pending.any { it.loading }
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LocalAiApp).container
    val conversations: StateFlow<List<ConversationSummary>> = container.conversations.summaries

    private val _state = MutableStateFlow(ChatUiState(selectedModel = container.settings.state.value.lastModel))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var streamJob: Job? = null

    val attachmentRepository get() = container.attachments

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
        discardPending()
        _state.update { it.copy(conversation = Conversation()) }
    }

    fun open(id: String) {
        if (id == _state.value.conversation.id) return
        stop()
        discardPending()
        viewModelScope.launch {
            val c = container.conversations.get(id) ?: return@launch
            _state.update { s -> s.copy(conversation = c, selectedModel = c.model?.takeIf { it in s.models } ?: s.selectedModel) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            container.conversations.delete(id)
            withContext(Dispatchers.IO) { container.attachments.deleteFor(id) }
            if (_state.value.conversation.id == id) newChat()
        }
    }

    // ------------------------------------------------------------------ attachments

    /** Photos from the gallery, files from the file picker, or audio files. */
    fun attach(uris: List<Uri>, kindHint: String) {
        val repo = container.attachments
        uris.take(MAX_ATTACHMENTS).forEach { uri ->
            val meta = runCatching { repo.meta(uri) }.getOrNull()
            val kind = when {
                kindHint == Attachment.KIND_IMAGE || meta?.mimeType?.startsWith("image/") == true -> Attachment.KIND_IMAGE
                kindHint == Attachment.KIND_AUDIO || meta?.mimeType?.startsWith("audio/") == true -> Attachment.KIND_AUDIO
                else -> Attachment.KIND_DOCUMENT
            }
            load(kind, meta?.name ?: "file") { convId ->
                when (kind) {
                    Attachment.KIND_IMAGE -> repo.importImage(uri, convId)
                    Attachment.KIND_AUDIO -> repo.importAudio(uri, convId, container.client())
                    else -> repo.import(uri, convId, container.client())
                }
            }
        }
    }

    /** A photo the camera app just wrote to [file]. */
    fun attachCameraPhoto(file: File) {
        load(Attachment.KIND_IMAGE, "Photo") { convId ->
            try {
                container.attachments.importImage(Uri.fromFile(file), convId).copy(name = "Photo")
            } finally {
                file.delete()
            }
        }
    }

    fun startRecording() {
        if (_state.value.recordingSince != null) return
        try {
            container.recorder.start(container.attachments.newRecordingFile())
            _state.update { it.copy(recordingSince = System.currentTimeMillis()) }
        } catch (e: AttachmentException) {
            showNotice(e.message)
        }
    }

    fun stopRecording() {
        if (_state.value.recordingSince == null) return
        val file = container.recorder.stop()
        _state.update { it.copy(recordingSince = null) }
        if (file == null) {
            showNotice("Recording was too short")
            return
        }
        load(Attachment.KIND_AUDIO, "Voice note") { convId -> container.attachments.importRecording(file, convId, container.client()) }
    }

    fun cancelRecording() {
        container.recorder.cancel()
        _state.update { it.copy(recordingSince = null) }
    }

    fun removePending(id: String) {
        val p = _state.value.pending.find { it.id == id } ?: return
        _state.update { s -> s.copy(pending = s.pending.filter { it.id != id }) }
        p.attachment?.localPath?.let { path -> viewModelScope.launch(Dispatchers.IO) { File(path).delete() } }
    }

    fun showNotice(message: String?) = _state.update { it.copy(notice = message) }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun load(kind: String, name: String, block: suspend (conversationId: String) -> Attachment) {
        if (_state.value.pending.size >= MAX_ATTACHMENTS) {
            showNotice("Up to $MAX_ATTACHMENTS attachments per message")
            return
        }
        val convId = _state.value.conversation.id
        val p = PendingAttachment(kind = kind, name = name)
        _state.update { it.copy(pending = it.pending + p) }
        viewModelScope.launch {
            val result = try {
                p.copy(attachment = block(convId), name = name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AttachmentException) {
                p.copy(error = e.message)
            } catch (e: Exception) {
                p.copy(error = e.message ?: e.javaClass.simpleName)
            }
            val final = result.attachment?.let { result.copy(name = it.name) } ?: result
            var kept = false
            _state.update { s ->
                if (s.conversation.id != convId || s.pending.none { it.id == p.id }) s // removed or chat switched meanwhile
                else s.copy(pending = s.pending.map { if (it.id == p.id) final else it }).also { kept = true }
            }
            if (!kept) final.attachment?.localPath?.let { File(it).delete() }
        }
    }

    private fun discardPending() {
        if (_state.value.recordingSince != null) cancelRecording()
        val paths = _state.value.pending.mapNotNull { it.attachment?.localPath }
        _state.update { it.copy(pending = emptyList()) }
        if (paths.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { paths.forEach { File(it).delete() } }
    }

    // ------------------------------------------------------------------ sending

    fun send(text: String): Boolean {
        val s = _state.value
        val trimmed = text.trim()
        if (s.streaming || s.attachmentsLoading) return false
        val attachments = s.pending.mapNotNull { it.attachment }
        if (trimmed.isEmpty() && attachments.isEmpty()) return false
        val conv = s.conversation
        val titleSource = trimmed.ifEmpty {
            attachments.firstNotNullOfOrNull { a -> a.text?.takeIf { a.kind == Attachment.KIND_AUDIO } } ?: attachments.first().name
        }
        val updated = conv.copy(
            title = if (conv.messages.isEmpty()) ConversationStore.titleFrom(titleSource) else conv.title,
            messages = conv.messages + StoredMessage(role = "user", content = trimmed, attachments = attachments),
            updatedAt = System.currentTimeMillis(),
        )
        _state.update { it.copy(pending = emptyList()) } // failed ones are dropped
        generate(updated)
        return true
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
                val history = withContext(Dispatchers.IO) {
                    buildList {
                        if (settings.systemPrompt.isNotBlank()) add(ChatMessage("system", settings.systemPrompt))
                        conv.messages.forEach { m ->
                            if (m.role == "assistant") {
                                if (m.content.isNotBlank()) add(ChatMessage(m.role, m.content))
                            } else {
                                Prompt.toChatMessage(m, container.attachments::imageDataUrl)?.let { add(it) }
                            }
                        }
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
                val hasImages = conv.messages.any { m -> m.attachments.any { it.kind == Attachment.KIND_IMAGE } }
                val status = (e as? GatewayException)?.status
                if (hasImages && content.isEmpty() && (status == 400 || status == 422 || status == 500)) {
                    error = error + "\n\nThis model may not understand images. Load a vision model in LM Studio " +
                        "(for example Qwen2.5-VL, Gemma 3 or LLaVA) and pick it above."
                }
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
        if (_state.value.recordingSince != null) container.recorder.cancel()
    }

    companion object {
        const val MAX_ATTACHMENTS = 10
    }
}
