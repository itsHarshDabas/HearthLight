package dev.localai.chat.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import dev.localai.core.Attachment
import dev.localai.core.Prompt
import kotlinx.coroutines.delay
import java.io.File
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
import androidx.compose.ui.unit.sp
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
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showAttachSheet by remember { mutableStateOf(false) }
    var cameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            vm.clearNotice()
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ChatViewModel.MAX_ATTACHMENTS)) { uris ->
        if (uris.isNotEmpty()) vm.attach(uris, Attachment.KIND_IMAGE)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.attach(uris, Attachment.KIND_DOCUMENT)
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.attach(uris, Attachment.KIND_AUDIO)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFilePath?.let(::File)
        cameraFilePath = null
        if (f != null) {
            if (ok && f.length() > 0) vm.attachCameraPhoto(f) else f.delete()
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else vm.showNotice("Microphone permission is needed to record voice notes")
    }

    fun launchCamera() {
        val repo = vm.attachmentRepository
        val f = repo.newCameraFile()
        cameraFilePath = f.absolutePath
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        runCatching { camera.launch(uri) }.onFailure {
            cameraFilePath = null
            vm.showNotice("No camera app found")
        }
    }

    fun record() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startRecording()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (showAttachSheet) {
        AttachSheet(
            onDismiss = { showAttachSheet = false },
            onCamera = { showAttachSheet = false; launchCamera() },
            onPhotos = {
                showAttachSheet = false
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onFiles = { showAttachSheet = false; runCatching { filePicker.launch(arrayOf("*/*")) } },
            onRecord = { showAttachSheet = false; record() },
            onAudioFile = { showAttachSheet = false; runCatching { audioPicker.launch(arrayOf("audio/*")) } },
        )
    }

    // Keep the newest content in view while streaming, unless the user scrolled up to read.
    val itemCount = messages.size + if (state.streaming) 1 else 0
    LaunchedEffect(itemCount, state.streamingContent.length / 200) {
        if (itemCount > 0) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (!state.streaming || lastVisible >= itemCount - 2) listState.animateScrollToItem(itemCount - 1, Int.MAX_VALUE / 2)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
                            if (m.role == "user") UserMessage(m)
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
            if (state.pending.isNotEmpty()) PendingStrip(state.pending, onRemove = vm::removePending)
            val since = state.recordingSince
            if (since != null) {
                RecordingBar(since, onCancel = vm::cancelRecording, onDone = vm::stopRecording)
            } else {
                Composer(
                    value = input,
                    onValueChange = { input = it },
                    streaming = state.streaming,
                    canSend = !state.attachmentsLoading && (input.isNotBlank() || state.pending.any { it.attachment != null }),
                    onAttach = { showAttachSheet = true },
                    onMic = ::record,
                    onSend = { if (vm.send(input)) input = "" },
                    onStop = vm::stop,
                )
            }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserMessage(m: StoredMessage) {
    var fullImage by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        val images = m.attachments.filter { it.kind == Attachment.KIND_IMAGE }
        if (images.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.widthIn(max = 320.dp).padding(bottom = 6.dp),
            ) {
                images.forEach { a ->
                    AsyncImage(
                        model = a.localPath?.let(::File),
                        contentDescription = a.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(if (images.size == 1) 220.dp else 104.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { fullImage = a.localPath },
                    )
                }
            }
        }
        m.attachments.filter { it.kind != Attachment.KIND_IMAGE }.forEach { a ->
            AttachmentCard(a, Modifier.padding(bottom = 6.dp))
        }
        if (m.content.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                SelectionContainer {
                    Text(m.content, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    fullImage?.let { path ->
        Dialog(onDismissRequest = { fullImage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { fullImage = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** A sent document or voice note inside a user message. */
@Composable
private fun AttachmentCard(a: Attachment, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.widthIn(max = 320.dp),
    ) {
        Column(Modifier.clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.kind == Attachment.KIND_AUDIO) {
                    PlayButton(a.localPath)
                } else {
                    Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(attachmentSubtitle(a), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            val text = a.text
            if (text != null && a.kind == Attachment.KIND_AUDIO) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else if (text != null && expanded) {
                Text(
                    text.take(4000) + if (text.length > 4000) "\n…" else "",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun attachmentSubtitle(a: Attachment): String = when (a.kind) {
    Attachment.KIND_AUDIO -> listOfNotNull("Voice note", a.durationMs?.let(Prompt::formatDuration), "transcribed").joinToString(" · ")
    else -> listOfNotNull(
        a.name.substringAfterLast('.', "").uppercase().ifBlank { null },
        a.text?.let { "${"%,d".format(it.length)} chars" },
        if (a.truncated) "truncated" else null,
    ).joinToString(" · ")
}

@Composable
private fun PlayButton(path: String?) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(path) { onDispose { player?.release(); player = null } }
    val file = path?.let(::File)?.takeIf { it.isFile }
    IconButton(
        enabled = file != null,
        onClick = {
            val p = player
            if (p != null) {
                p.release()
                player = null
            } else if (file != null) {
                player = runCatching {
                    MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener { mp -> mp.release(); player = null }
                        prepare()
                        start()
                    }
                }.getOrNull()
            }
        },
        modifier = Modifier.size(32.dp),
    ) {
        Icon(if (player != null) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = if (player != null) "Stop" else "Play")
    }
}

/** Attachments waiting in the composer. */
@Composable
private fun PendingStrip(items: List<PendingAttachment>, onRemove: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        items(items, key = { it.id }) { p ->
            Box {
                val a = p.attachment
                if (p.kind == Attachment.KIND_IMAGE && a != null) {
                    AsyncImage(
                        model = a.localPath?.let(::File),
                        contentDescription = a.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (p.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.height(64.dp).widthIn(min = 64.dp, max = 240.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            when {
                                p.loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                p.error != null -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                else -> Icon(kindIcon(p.kind), contentDescription = null)
                            }
                            Spacer(Modifier.size(8.dp))
                            Column {
                                Text(p.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    p.error ?: when {
                                        p.loading && p.kind == Attachment.KIND_AUDIO -> "Transcribing…"
                                        p.loading -> "Preparing…"
                                        a != null -> attachmentSubtitle(a)
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (p.error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (!p.loading) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.inverseSurface,
                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clickable { onRemove(p.id) },
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(3.dp))
                    }
                }
            }
        }
    }
}

private fun kindIcon(kind: String) = when (kind) {
    Attachment.KIND_IMAGE -> Icons.Outlined.Image
    Attachment.KIND_AUDIO -> Icons.Outlined.Mic
    else -> Icons.Outlined.Description
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onRecord: () -> Unit,
    onAudioFile: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text("Attach", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp, start = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SheetOption(Icons.Outlined.PhotoCamera, "Camera", onCamera)
                SheetOption(Icons.Outlined.Image, "Photos", onPhotos)
                SheetOption(Icons.Outlined.Description, "Files", onFiles)
            }
            Spacer(Modifier.size(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SheetOption(Icons.Outlined.Mic, "Record audio", onRecord)
                SheetOption(Icons.Outlined.AudioFile, "Audio file", onAudioFile)
                Spacer(Modifier.size(88.dp))
            }
            Text(
                "Photos need a vision model (e.g. Qwen2.5-VL, Gemma 3). Files are turned into text. Audio is transcribed on your PC.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, start = 8.dp, end = 8.dp),
            )
        }
    }
}

@Composable
private fun SheetOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .size(width = 88.dp, height = 88.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
        }
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun RecordingBar(since: Long, onCancel: () -> Unit, onDone: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Discard recording") }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f).height(52.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                val blink = ((now - since) / 500) % 2 == 0L
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (blink) MaterialTheme.colorScheme.error else Color.Transparent)
                )
                Spacer(Modifier.size(10.dp))
                Text("Recording  " + Prompt.formatDuration(now - since), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.size(8.dp))
        FilledIconButton(onClick = onDone, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.Check, contentDescription = "Finish recording")
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
    canSend: Boolean,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        IconButton(onClick = onAttach, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = "Attach")
        }
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
            trailingIcon = if (value.isEmpty() && !streaming) {
                { IconButton(onClick = onMic) { Icon(Icons.Filled.Mic, contentDescription = "Record voice note") } }
            } else null,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        if (streaming) {
            FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop")
            }
        } else {
            FilledIconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
