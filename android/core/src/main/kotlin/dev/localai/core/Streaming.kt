package dev.localai.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Turns OpenAI-style SSE lines (`data: {...}`) into [ChatEvent]s.
 * Feed it one line at a time; it returns null for lines that carry nothing.
 */
class ChatSseParser {
    var done = false
        private set

    fun parseLine(rawLine: String): ChatEvent? {
        val line = rawLine.trimEnd('\r')
        if (line.isEmpty() || line.startsWith(":") || !line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload == "[DONE]") {
            done = true
            return ChatEvent.Done(null)
        }
        val obj = runCatching { GatewayJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        obj["error"]?.let { throw GatewayException(200, errorMessage(it)) }
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
        val delta = choice["delta"] as? JsonObject
        val content = delta?.str("content").orEmpty()
        val reasoning = delta?.str("reasoning_content") ?: delta?.str("reasoning") ?: ""
        val finish = choice.str("finish_reason")
        return when {
            content.isNotEmpty() || reasoning.isNotEmpty() -> ChatEvent.Delta(content, reasoning)
            finish != null -> ChatEvent.Done(finish)
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

internal fun errorMessage(el: JsonElement): String = when (el) {
    is JsonPrimitive -> el.contentOrNull ?: "unknown error"
    is JsonObject -> (el["message"] as? JsonPrimitive)?.contentOrNull ?: el.toString()
    else -> el.toString()
}

/**
 * Some models (Qwen3, DeepSeek-R1 distills…) put their reasoning inline as
 * `<think>…</think>` in the content instead of a separate field. Split it out.
 * Returns (thinking, answer, stillThinking).
 */
data class ThinkSplit(val thinking: String, val answer: String, val stillThinking: Boolean)

fun splitThinking(content: String): ThinkSplit {
    val start = content.indexOf("<think>")
    if (start < 0) return ThinkSplit("", content, false)
    val end = content.indexOf("</think>", start)
    val before = content.substring(0, start)
    return if (end < 0) {
        ThinkSplit(content.substring(start + 7).trim(), before.trim(), true)
    } else {
        ThinkSplit(content.substring(start + 7, end).trim(), (before + content.substring(end + 8)).trim(), false)
    }
}
