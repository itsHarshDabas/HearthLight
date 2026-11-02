package dev.localai.chat.data

import android.content.Context
import dev.localai.core.ConversationStore
import dev.localai.core.GatewayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Simple manual dependency container (one per process). */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val conversations = ConversationStore(File(context.filesDir, "conversations"))
    private val http = GatewayClient.defaultHttpClient()

    @Volatile
    private var cached: Pair<Pair<String, String>, GatewayClient>? = null

    init {
        appScope.launch { conversations.load() }
    }

    /** Client for the current settings, or null if the server isn't configured / URL is invalid. */
    fun client(): GatewayClient? {
        val s = settings.state.value
        if (s.serverUrl.isBlank()) return null
        val key = s.serverUrl to s.apiKey
        cached?.let { (k, c) -> if (k == key) return c }
        return runCatching { GatewayClient(s.serverUrl, s.apiKey, http) }.getOrNull()?.also { cached = key to it }
    }
}
