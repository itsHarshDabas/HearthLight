package dev.localai.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class StoredMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" | "assistant"
    val content: String,
    val reasoning: String = "",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val model: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<StoredMessage> = emptyList(),
)

data class ConversationSummary(val id: String, val title: String, val updatedAt: Long)

/**
 * Chats are stored on the phone, one JSON file per conversation. They never leave the
 * device except as the request sent to your own gateway.
 */
class ConversationStore(private val dir: File) {
    private val mutex = Mutex()
    private val _summaries = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val summaries: StateFlow<List<ConversationSummary>> = _summaries.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.mkdirs()
            _summaries.value = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
                .mapNotNull { f -> runCatching { read(f) }.getOrNull() }
                .map { ConversationSummary(it.id, it.title, it.updatedAt) }
                .sortedByDescending { it.updatedAt }
        }
    }

    suspend fun get(id: String): Conversation? = withContext(Dispatchers.IO) {
        mutex.withLock { file(id).takeIf { it.exists() }?.let { runCatching { read(it) }.getOrNull() } }
    }

    suspend fun save(c: Conversation) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.mkdirs()
            val tmp = File(dir, "${c.id}.json.tmp")
            tmp.writeText(GatewayJson.encodeToString(Conversation.serializer(), c))
            val target = file(c.id)
            if (!tmp.renameTo(target)) { // renameTo can't replace on some filesystems
                target.delete()
                tmp.renameTo(target)
            }
            val s = ConversationSummary(c.id, c.title, c.updatedAt)
            _summaries.value = (listOf(s) + _summaries.value.filter { it.id != c.id }).sortedByDescending { it.updatedAt }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            file(id).delete()
            _summaries.value = _summaries.value.filter { it.id != id }
        }
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "bad conversation id" }
        return File(dir, "$id.json")
    }

    private fun read(f: File): Conversation = GatewayJson.decodeFromString(Conversation.serializer(), f.readText())

    companion object {
        fun titleFrom(firstMessage: String): String {
            val oneLine = firstMessage.trim().replace(Regex("\\s+"), " ")
            return if (oneLine.length <= 40) oneLine.ifEmpty { "New chat" } else oneLine.take(40).trimEnd() + "…"
        }
    }
}
