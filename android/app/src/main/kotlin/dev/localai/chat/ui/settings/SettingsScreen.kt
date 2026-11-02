package dev.localai.chat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localai.chat.data.AppContainer
import dev.localai.core.GatewayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onOpenDrawer: () -> Unit, onSaved: () -> Unit) {
    val current by container.settings.state.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(current.serverUrl) }
    var key by rememberSaveable { mutableStateOf(current.apiKey) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var systemPrompt by rememberSaveable { mutableStateOf(current.systemPrompt) }
    var useTemp by rememberSaveable { mutableStateOf(current.temperature != null) }
    var temp by rememberSaveable { mutableStateOf(current.temperature ?: 0.7f) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val urlError = remember(url) {
        if (url.isBlank()) null else runCatching { GatewayClient.normalizeBaseUrl(url); null }.getOrElse { "Not a valid URL" }
    }
    val insecure = remember(url) { url.trim().startsWith("http://") && !looksPrivate(url) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Your PC", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                label = { Text("Gateway URL") },
                placeholder = { Text("https://my-pc.tailnet-name.ts.net") },
                singleLine = true,
                isError = urlError != null,
                supportingText = {
                    Text(
                        urlError ?: if (insecure) "Plain http over the internet exposes your key. Use Tailscale or https."
                        else "Your Tailscale Serve or Cloudflare Tunnel address (see the README)."
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = "Show key")
                    }
                },
                supportingText = { Text("On the PC run: local-ai-gateway --show-key") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = url.isNotBlank() && urlError == null && !testing,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            try {
                                val st = GatewayClient(url, key).status()
                                testOk = true
                                testResult = describe(st.version, st.chatUpstreams.map { "${it.name}: ${it.status}" }, st.comfyui)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                testOk = false
                                testResult = e.message ?: "Failed"
                            } finally {
                                testing = false
                            }
                        }
                    },
                ) { Text("Test connection") }
                if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            testResult?.let {
                Text(
                    it,
                    color = if (testOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            HorizontalDivider()
            Text("Chat", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System prompt (optional)") },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useTemp, onCheckedChange = { useTemp = it })
                Text(if (useTemp) "Temperature: ${"%.2f".format(temp)}" else "Temperature: model default")
            }
            if (useTemp) {
                Slider(value = temp, onValueChange = { temp = it }, valueRange = 0f..2f, steps = 39)
            }

            Spacer(Modifier.size(8.dp))
            Button(
                onClick = {
                    container.settings.update {
                        it.copy(
                            serverUrl = if (url.isBlank()) "" else GatewayClient.normalizeBaseUrl(url),
                            apiKey = key,
                            systemPrompt = systemPrompt,
                            temperature = if (useTemp) temp else null,
                        )
                    }
                    onSaved()
                },
                enabled = urlError == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            Text(
                "Chats are stored only on this phone. Images stay on your PC and are listed from the gateway.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun describe(version: String, upstreams: List<String>, comfy: JsonObject): String {
    val sb = StringBuilder("Connected to gateway $version\n")
    upstreams.forEach { sb.append("• Chat ").append(it).append('\n') }
    val comfyStatus = (comfy["status"] as? JsonPrimitive)?.content
    val enabled = (comfy["enabled"] as? JsonPrimitive)?.content == "true"
    if (!enabled) {
        sb.append("• ComfyUI: disabled")
    } else {
        sb.append("• ComfyUI: ").append(comfyStatus ?: "unknown")
        val gpu = ((comfy["devices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("name") as? JsonPrimitive
        gpu?.let { sb.append(" (").append(it.content).append(')') }
    }
    return sb.toString().trim()
}

private fun looksPrivate(url: String): Boolean {
    val host = url.trim().removePrefix("http://").substringBefore('/').substringBefore(':')
    if (host == "localhost" || host.endsWith(".ts.net") || host.endsWith(".local")) return true
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    return parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31) ||
        (parts[0] == 100 && parts[1] in 64..127) // Tailscale CGNAT range
}
