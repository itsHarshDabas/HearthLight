package dev.localai.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test against a real running gateway (pointed at fake or real upstreams).
 * Skipped unless GATEWAY_URL and GATEWAY_KEY are set.
 */
class LiveGatewayTest {
    private val url = System.getenv("GATEWAY_URL").orEmpty()
    private val key = System.getenv("GATEWAY_KEY").orEmpty()

    @Test
    fun endToEnd() = runBlocking {
        if (url.isEmpty()) {
            println("LiveGatewayTest skipped (GATEWAY_URL not set)")
            return@runBlocking
        }
        val c = GatewayClient(url, key)
        val status = c.status()
        assertTrue(status.chatUpstreams.isNotEmpty())
        val model = c.models().first { !it.isEmbedding }.id
        val events = c.streamChat(ChatRequest(model, listOf(ChatMessage("user", "ping pong")))).toList()
        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.content }
        println("streamed: $text")
        assertTrue(text.isNotBlank())

        val wf = c.workflows()
        assertTrue(wf.isNotEmpty())
        var job = c.createImageJob(ImageJobRequest(prompt = "a kotlin lizard", workflow = wf.first().id, n = 2))
        repeat(60) {
            if (!job.isFinal) job = c.imageJob(job.id, waitSeconds = 2) else return@repeat
        }
        assertEquals("succeeded", job.status, job.error)
        val bytes = c.download(job.images.first().path)
        assertTrue(bytes.size > 8 && bytes[1] == 'P'.code.toByte(), "expected a PNG")
        assertTrue(c.imageJobs().data.any { it.id == job.id })
        c.deleteImageJob(job.id)
        delay(10)
        println("live test ok: model=$model images=${job.images.size}")
    }
}
