package dev.localai.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localai.chat.ui.common.MarkdownText
import dev.localai.core.StoredMessage
import dev.localai.core.splitThinking

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenDrawer: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val messages = state.conversation.messages

    // Keep the newest content in view while streaming, unless the user scrolled up to read.
    val itemCount = messages.size + if (state.streaming) 1 else 0
    LaunchedEffect(itemCount, state.streamingContent.length / 200) {
        if (itemCount > 0) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (!state.streaming || lastVisible >= itemCount - 2) listState.animateScrollToItem(itemCount - 1, Int.MAX_VALUE / 2)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                },
                title = { ModelPicker(state, onSelect = vm::selectModel, onRefresh = vm::refreshModels) },
                actions = {
                    IconButton(onClick = vm::newChat) { Icon(Icons.Outlined.EditNote, contentDescription = "New chat") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty() && !state.streaming) {
                    EmptyState(state.modelsError, onSuggestion = { input = it }, onRetry = vm::refreshModels)
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(messages, key = { it.id }) { m ->
                            if (m.role == "user") UserBubble(m.content)
                            else AssistantMessage(
                                content = m.content,
                                reasoning = m.reasoning,
                                error = m.error,
                                streaming = false,
                                isLast = m.id == messages.last().id,
                                onRegenerate = vm::regenerate,
                            )
                        }
                        if (state.streaming) {
                            item(key = "streaming") {
                                AssistantMessage(
                                    content = state.streamingContent,
                                    reasoning = state.streamingReasoning,
                                    error = null,
                                    streaming = true,
                                    isLast = true,
                                    onRegenerate = {},
                                )
                            }
                        }
                    }
                }
            }
            Composer(
                value = input,
                onValueChange = { input = it },
                streaming = state.streaming,
                onSend = {
                    vm.send(input)
                    input = ""
                },
                onStop = vm::stop,
            )
        }
    }
}

@Composable
private fun ModelPicker(state: ChatUiState, onSelect: (String) -> Unit, onRefresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true; if (state.models.isEmpty()) onRefresh() }) {
            Text(
                state.selectedModel?.substringAfterLast('/') ?: if (state.modelsLoading) "Loading…" else "Pick a model",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.models.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onSelect(m); open = false },
                    trailingIcon = { if (m == state.selectedModel) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                )
            }
            if (state.models.isNotEmpty()) HorizontalDivider()
            state.modelsError?.let {
                DropdownMenuItem(text = { Text(it, color = MaterialTheme.colorScheme.error) }, onClick = {}, enabled = false)
            }
            DropdownMenuItem(
                text = { Text("Refresh models") },
                leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                onClick = { onRefresh(); open = false },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(error: String?, onSuggestion: (String) -> Unit, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("What can I help with?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(8.dp))
        Text(
            "Running on your own PC.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            listOf(
                "Explain this concept simply: ",
                "Write a Python script that ",
                "Summarize the following: ",
                "Brainstorm 5 ideas for ",
            ).forEach { s -> AssistChip(onClick = { onSuggestion(s) }, label = { Text(s.trim().removeSuffix(":")) }) }
        }
        if (error != null) {
            Spacer(Modifier.size(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    content: String,
    reasoning: String,
    error: String?,
    streaming: Boolean,
    isLast: Boolean,
    onRegenerate: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val split = remember(content) { splitThinking(content) }
    val thinking = (reasoning + if (split.thinking.isNotEmpty() && reasoning.isNotEmpty()) "\n" + split.thinking else split.thinking).trim()
    val answer = split.answer
    val stillThinking = streaming && (split.stillThinking || (answer.isEmpty() && thinking.isNotEmpty()))

    Column(Modifier.fillMaxWidth()) {
        if (thinking.isNotEmpty()) ThinkingBlock(thinking, stillThinking)
        if (answer.isNotEmpty()) {
            SelectionContainer { MarkdownText(answer) }
        } else if (streaming && thinking.isEmpty()) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.size(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (!streaming) {
            Row {
                if (answer.isNotEmpty()) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(answer)) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                    }
                }
                if (isLast) {
                    IconButton(onClick = onRegenerate) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Regenerate", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String, active: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(
            Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(6.dp))
            Text(
                if (active) "Thinking…" else if (expanded) "Hide reasoning" else "Show reasoning",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(expanded || active) {
            Surface(color = Color.Transparent) {
                Text(
                    if (active && !expanded) text.takeLast(400) else text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("Message") },
            maxLines = 6,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        if (streaming) {
            FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop")
            }
        } else {
            FilledIconButton(onClick = onSend, enabled = value.isNotBlank(), modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
