package dev.localai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingTest {
    @Test
    fun parsesDeltasReasoningAndDone() {
        val p = ChatSseParser()
        assertNull(p.parseLine(""))
        assertNull(p.parseLine(": keep-alive"))
        assertNull(p.parseLine("""data: {"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}"""))
        assertEquals(ChatEvent.Delta("Hel"), p.parseLine("""data: {"choices":[{"delta":{"content":"Hel"}}]}"""))
        assertEquals(ChatEvent.Delta("", "hmm"), p.parseLine("""data: {"choices":[{"delta":{"reasoning_content":"hmm"}}]}"""))
        assertEquals(ChatEvent.Delta("", "r"), p.parseLine("""data:{"choices":[{"delta":{"reasoning":"r"}}]}"""))
        assertEquals(ChatEvent.Done("stop"), p.parseLine("""data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
        assertEquals(ChatEvent.Done(null), p.parseLine("data: [DONE]\r"))
        assertTrue(p.done)
    }

    @Test
    fun inBandErrorThrows() {
        val p = ChatSseParser()
        val e = assertFailsWith<GatewayException> { p.parseLine("""data: {"error":{"message":"boom"}}""") }
        assertEquals("boom", e.message)
    }

    @Test
    fun splitsThinkTags() {
        assertEquals(ThinkSplit("", "plain", false), splitThinking("plain"))
        assertEquals(ThinkSplit("working on it", "", true), splitThinking("<think>working on it"))
        assertEquals(ThinkSplit("a", "The answer", false), splitThinking("<think>a</think>\n\nThe answer"))
    }

    @Test
    fun normalizesBaseUrl() {
        assertEquals("http://100.1.2.3:8787", GatewayClient.normalizeBaseUrl("100.1.2.3:8787/"))
        assertEquals("https://pc.tail.ts.net", GatewayClient.normalizeBaseUrl(" https://pc.tail.ts.net/v1 "))
        assertFailsWith<IllegalArgumentException> { GatewayClient.normalizeBaseUrl("") }
        assertFailsWith<IllegalArgumentException> { GatewayClient.normalizeBaseUrl("http://") }
    }
}
