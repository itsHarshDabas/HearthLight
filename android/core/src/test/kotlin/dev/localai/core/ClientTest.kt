package dev.localai.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GatewayClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = GatewayClient(server.url("/").toString(), "k1")
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    @Test
    fun streamsAndSendsAuth() = runBlocking {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\" there\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        val events = client.streamChat(ChatRequest("m", listOf(ChatMessage("user", "yo")))).toList()
        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.content }
        assertEquals("Hi there", text)
        val req = server.takeRequest()
        assertEquals("Bearer k1", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue("\"stream\":true" in body && "\"model\":\"m\"" in body, body)
        assertTrue("max_tokens" !in body, "nulls must be omitted: $body")
    }

    @Test
    fun httpErrorsBecomeReadable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid or missing API key."}}"""))
        val e = assertFailsWith<GatewayException> { client.models() }
        assertEquals(401, e.status)
        assertTrue(e.message!!.contains("check the API key"))

        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"model 'x' not found"}"""))
        val e2 = assertFailsWith<GatewayException> {
            client.streamChat(ChatRequest("x", listOf(ChatMessage("user", "q")))).toList()
        }
        assertEquals("model 'x' not found", e2.message)
    }

    @Test
    fun connectionFailureIsFriendly() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val e = assertFailsWith<GatewayException> { client.status() }
        assertTrue(e.message!!.startsWith("Can't reach the gateway"))
    }

    @Test
    fun cancellingCollectorClosesStream() = runBlocking {
        val chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"
        server.enqueue(MockResponse().setBody(chunk.repeat(2000)).throttleBody(200, 50, TimeUnit.MILLISECONDS))
        val first = withTimeout(5000) { client.streamChat(ChatRequest("m", listOf(ChatMessage("user", "q")))).first() }
        assertEquals(ChatEvent.Delta("x"), first)
    }

    @Test
    fun parsesImageJob() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"img_1","object":"image.job","status":"succeeded","workflow":"sdxl",
            "params":{"prompt":"a cat","n":1},"created_at":1700000000,"progress":null,"error":null,
            "images":[{"url":"http://x/v1/files/img_1/0_0.png?exp=1&sig=a","path":"/v1/files/img_1/0_0.png?exp=1&sig=a","seed":281474976710655}],
            "extra_field":true}"""))
        val job = client.imageJob("img_1")
        assertTrue(job.isFinal)
        assertEquals("a cat", job.prompt)
        assertEquals(281474976710655L, job.images[0].seed)
        assertEquals(client.baseUrl + "/v1/files/img_1/0_0.png?exp=1&sig=a", client.resolve(job.images[0].path))
    }

    @Test
    fun conversationStoreRoundTrip() = runBlocking {
        val dir: File = Files.createTempDirectory("conv").toFile()
        val store = ConversationStore(dir)
        store.load()
        val c = Conversation(title = ConversationStore.titleFrom("  hello\n world  "), messages = listOf(StoredMessage(role = "user", content = "hello")))
        store.save(c)
        store.save(c.copy(title = "renamed", updatedAt = c.updatedAt + 1))
        val again = ConversationStore(dir).also { it.load() }
        assertEquals(listOf("renamed"), again.summaries.value.map { it.title })
        assertEquals("hello", again.get(c.id)!!.messages.single().content)
        again.delete(c.id)
        assertEquals(emptyList(), again.summaries.value)
        assertEquals("hello world", ConversationStore.titleFrom("  hello\n world  "))
    }
}
