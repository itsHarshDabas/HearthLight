package dev.localai.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentsTest {
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
    fun plainTextMessageStaysAString() {
        val m = Prompt.toChatMessage(StoredMessage(role = "user", content = "hi")) { null }!!
        assertEquals(JsonPrimitive("hi"), m.content)
        assertEquals("""{"role":"user","content":"hi"}""", GatewayJson.encodeToString(ChatMessage.serializer(), m))
    }

    @Test
    fun imagesBecomeContentParts() {
        val img = Attachment(kind = Attachment.KIND_IMAGE, name = "a.jpg", localPath = "/x/a.jpg")
        val m = Prompt.toChatMessage(StoredMessage(role = "user", content = "what is it?", attachments = listOf(img))) {
            "data:image/jpeg;base64,AAAA"
        }!!
        val parts = m.content as JsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("what is it?", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AAAA", parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun missingImageFallsBackToText() {
        val img = Attachment(kind = Attachment.KIND_IMAGE, name = "a.jpg")
        val m = Prompt.toChatMessage(StoredMessage(role = "user", content = "x", attachments = listOf(img))) { null }!!
        assertEquals(JsonPrimitive("x"), m.content)
        assertNull(Prompt.toChatMessage(StoredMessage(role = "user", content = "", attachments = listOf(img))) { null })
    }

    @Test
    fun documentsAndVoiceAreInlined() {
        val doc = Attachment(kind = Attachment.KIND_DOCUMENT, name = "notes.md", text = "# Title\nbody\n", truncated = true)
        val voice = Attachment(kind = Attachment.KIND_AUDIO, name = "v.m4a", text = " hello there ", durationMs = 65_000)
        val text = Prompt.textWithAttachments(StoredMessage(role = "user", content = "Summarize", attachments = listOf(voice, doc)))
        assertEquals(
            "Summarize\n\n[Voice note transcript, 1:05]\nhello there\n\n<file name=\"notes.md\">\n# Title\nbody\n[... file truncated ...]\n</file>",
            text,
        )
        val alone = Prompt.textWithAttachments(StoredMessage(role = "user", content = " ", attachments = listOf(voice)))
        assertEquals("hello there", alone)
    }

    @Test
    fun plainTextDetection() {
        assertTrue(Prompt.isPlainText("main.kt", "application/octet-stream"))
        assertTrue(Prompt.isPlainText("x", "text/plain"))
        assertTrue(!Prompt.isPlainText("doc.pdf", "application/pdf"))
    }

    @Test
    fun oldConversationsWithoutAttachmentsStillLoad() {
        val json = """{"id":"c1","title":"t","messages":[{"role":"user","content":"hi"}]}"""
        val c = GatewayJson.decodeFromString(Conversation.serializer(), json)
        assertEquals(emptyList(), c.messages[0].attachments)
    }

    @Test
    fun transcribeSendsMultipart() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"namaste","language":"hi","duration":2.5}"""))
        val t = client.transcribe(byteArrayOf(1, 2, 3), "note.m4a", language = "hi")
        assertEquals("namaste", t.text)
        val req = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", req.path)
        assertEquals("Bearer k1", req.getHeader("Authorization"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = req.body.readUtf8()
        assertTrue("filename=\"note.m4a\"" in body && "name=\"language\"" in body, body)
    }

    @Test
    fun transcribeUnavailableIs501() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(501).setBody(
            """{"error":{"message":"Speech-to-text isn't set up","type":"audio_error","code":"transcription_unavailable"}}"""))
        val e = assertFailsWith<GatewayException> { client.transcribe(byteArrayOf(1), "a.m4a") }
        assertEquals(501, e.status)
        assertTrue(e.message!!.contains("isn't set up"))
    }

    @Test
    fun extractText() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"filename":"a.pdf","kind":"pdf","text":"[page 1]\nhi","chars":11,"truncated":false,"pages":1}"""))
        val ex = client.extractText(byteArrayOf(37, 80, 68, 70), "a.pdf", "application/pdf")
        assertEquals("[page 1]\nhi", ex.text)
        assertEquals(1, ex.pages)
        assertEquals("/v1/attachments/extract", server.takeRequest().path)
    }
}
