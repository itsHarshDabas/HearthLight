package dev.localai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val GatewayJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

// ---------------------------------------------------------------- chat

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val model: String? = null,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("owned_by") val ownedBy: String? = null,
    val upstream: String? = null,
) {
    val isEmbedding: Boolean get() = id.contains("embed", ignoreCase = true)
}

@Serializable
internal data class ModelList(val data: List<ModelInfo> = emptyList())

sealed interface ChatEvent {
    /** A piece of the answer and/or of the model's reasoning ("thinking"). */
    data class Delta(val content: String = "", val reasoning: String = "") : ChatEvent
    data class Done(val finishReason: String?) : ChatEvent
}

// ---------------------------------------------------------------- status

@Serializable
data class UpstreamStatus(val name: String, val status: String)

@Serializable
data class StatusResponse(
    val version: String = "",
    @SerialName("chat_upstreams") val chatUpstreams: List<UpstreamStatus> = emptyList(),
    val comfyui: JsonObject = JsonObject(emptyMap()),
)

// ---------------------------------------------------------------- images

@Serializable
data class WorkflowInfo(
    val id: String,
    val title: String = id,
    val description: String = "",
    val params: List<String> = emptyList(),
    val defaults: JsonObject = JsonObject(emptyMap()),
) {
    fun supports(param: String) = param in params
}

@Serializable
internal data class WorkflowList(val data: List<WorkflowInfo> = emptyList())

@Serializable
data class ImageJobRequest(
    val prompt: String,
    val workflow: String? = null,
    @SerialName("negative_prompt") val negativePrompt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfg: Double? = null,
    val seed: Long? = null,
    val n: Int = 1,
)

@Serializable
data class JobImage(val url: String = "", val path: String, val seed: Long? = null)

@Serializable
data class ImageJob(
    val id: String,
    val status: String,
    val workflow: String = "",
    val params: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("finished_at") val finishedAt: Long? = null,
    val progress: String? = null,
    val error: String? = null,
    val images: List<JobImage> = emptyList(),
    @SerialName("queue_position") val queuePosition: Int? = null,
) {
    val isFinal: Boolean get() = status in FINAL_STATUSES
    val prompt: String get() = params["prompt"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()

    companion object {
        val FINAL_STATUSES = setOf("succeeded", "failed", "cancelled")
    }
}

@Serializable
data class ImageJobList(
    val data: List<ImageJob> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_before") val nextBefore: Double? = null,
)

class GatewayException(val status: Int, message: String) : Exception(message)
