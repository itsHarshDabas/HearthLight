package dev.localai.core

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client for the Local AI Gateway. Thread-safe; create one per (baseUrl, apiKey).
 *
 * @param baseUrl e.g. "https://my-pc.tail1234.ts.net" or "http://100.101.102.103:8787" (no /v1)
 */
class GatewayClient(
    baseUrl: String,
    private val apiKey: String,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)

    // ------------------------------------------------------------------ chat

    suspend fun models(): List<ModelInfo> = get("/v1/models", ModelList.serializer()).data

    suspend fun status(): StatusResponse = get("/v1/status", StatusResponse.serializer())

    /** Non-streaming completion; returns the assistant text. */
    suspend fun chat(request: ChatRequest): String {
        val body = GatewayJson.encodeToString(ChatRequest.serializer(), request.copy(stream = false))
        val obj = execute(post("/v1/chat/completions", body)).let { GatewayJson.parseToJsonElement(it).jsonObject }
        val msg = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        return (msg?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    /**
     * Streams a chat completion. Cancelling the collector cancels the HTTP call, which makes
     * the gateway (and LM Studio) stop generating.
     */
    fun streamChat(request: ChatRequest): Flow<ChatEvent> = callbackFlow {
        val body = GatewayJson.encodeToString(ChatRequest.serializer(), request.copy(stream = true))
        val call = http.newCall(post("/v1/chat/completions", body, accept = "text/event-stream"))
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(if (call.isCanceled()) null else friendlyIo(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    try {
                        if (!resp.isSuccessful) {
                            close(httpError(resp.code, resp.body?.string().orEmpty()))
                            return
                        }
                        val parser = ChatSseParser()
                        val source = resp.body!!.source()
                        while (!parser.done) {
                            val line = source.readUtf8Line() ?: break
                            val ev = parser.parseLine(line) ?: continue
                            if (trySendBlocking(ev).isFailure) break // collector went away
                        }
                        close()
                    } catch (e: GatewayException) {
                        close(e)
                    } catch (e: IOException) {
                        close(if (call.isCanceled()) null else friendlyIo(e))
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    // ------------------------------------------------------------------ images

    suspend fun workflows(): List<WorkflowInfo> = get("/v1/workflows", WorkflowList.serializer()).data

    suspend fun createImageJob(req: ImageJobRequest): ImageJob =
        parse(execute(post("/v1/images/jobs", GatewayJson.encodeToString(ImageJobRequest.serializer(), req))), ImageJob.serializer())

    /** Long-polls up to [waitSeconds] for the job to finish. */
    suspend fun imageJob(id: String, waitSeconds: Int = 0): ImageJob =
        get("/v1/images/jobs/$id" + if (waitSeconds > 0) "?wait=$waitSeconds" else "", ImageJob.serializer())

    suspend fun imageJobs(limit: Int = 50, before: Double? = null): ImageJobList =
        get("/v1/images/jobs?limit=$limit" + (before?.let { "&before=$it" } ?: ""), ImageJobList.serializer())

    suspend fun cancelImageJob(id: String): ImageJob =
        parse(execute(post("/v1/images/jobs/$id/cancel", "{}")), ImageJob.serializer())

    suspend fun deleteImageJob(id: String) {
        execute(builder("/v1/images/jobs/$id").delete().build())
    }

    /** Absolute URL for a gateway path such as the `path` of a [JobImage]. */
    fun resolve(pathOrUrl: String): String =
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) pathOrUrl else baseUrl + pathOrUrl

    /** Downloads an image (signed path or URL). */
    suspend fun download(pathOrUrl: String): ByteArray {
        val req = Request.Builder().url(resolve(pathOrUrl)).header("Authorization", "Bearer $apiKey").build()
        return http.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string().orEmpty())
            resp.body!!.bytes()
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun builder(path: String): Request.Builder =
        Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $apiKey")

    private fun post(path: String, json: String, accept: String = "application/json"): Request =
        builder(path).header("Accept", accept).post(json.toRequestBody(JSON)).build()

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        parse(execute(builder(path).get().build()), serializer)

    private fun <T> parse(body: String, serializer: KSerializer<T>): T =
        try {
            GatewayJson.decodeFromString(serializer, body)
        } catch (e: Exception) {
            throw GatewayException(0, "Unexpected response from gateway: ${body.take(200)}")
        }

    private suspend fun execute(request: Request): String =
        http.newCall(request).await().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw httpError(resp.code, text)
            text
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES) // long generations / long-poll
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun normalizeBaseUrl(url: String): String {
            val t = url.trim()
            val scheme = if (t.startsWith("https://", ignoreCase = true)) "https://" else "http://"
            val rest = t.replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "")
                .trimEnd('/').removeSuffix("/v1").trimEnd('/')
            require(rest.isNotEmpty()) { "Server URL is empty" }
            val u = scheme + rest
            u.toHttpUrl() // validates; throws IllegalArgumentException
            return u
        }

        internal fun httpError(code: Int, body: String): GatewayException {
            val msg = runCatching {
                val el = GatewayJson.parseToJsonElement(body)
                (el as? JsonObject)?.get("error")?.let { errorMessage(it) }
                    ?: (el as? JsonObject)?.get("detail")?.toString()
            }.getOrNull() ?: body.take(300).ifBlank { "HTTP $code" }
            val hint = when (code) {
                401 -> " (check the API key in Settings)"
                502, 503 -> " (is LM Studio / ComfyUI running on the PC?)"
                else -> ""
            }
            return GatewayException(code, msg + hint)
        }

        internal fun friendlyIo(e: IOException): GatewayException =
            GatewayException(0, "Can't reach the gateway: ${e.message ?: e.javaClass.simpleName}. Is the PC on and Tailscale connected?")
    }
}

/** Suspends until the call completes; cancels the call if the coroutine is cancelled. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(GatewayClient.friendlyIo(e))
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
