package dev.localai.chat.ui.images

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.localai.core.ImageJob
import dev.localai.core.JobImage

private sealed interface Tile {
    val key: String

    data class Picture(val job: ImageJob, val image: JobImage) : Tile {
        override val key: String get() = job.id + image.path.substringBefore('?')
    }

    data class Status(val job: ImageJob) : Tile {
        override val key: String get() = job.id
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesScreen(vm: ImagesViewModel, onOpenDrawer: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var viewing by remember { mutableStateOf<Tile.Picture?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val tiles = remember(state.jobs) {
        state.jobs.flatMap { job ->
            if (job.status == "succeeded" && job.images.isNotEmpty()) job.images.map { Tile.Picture(job, it) }
            else listOf(Tile.Status(job))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Images") },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") } },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { GenerateForm(state, vm) }
            if (tiles.isEmpty() && !state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Your generated images will show up here.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                    )
                }
            }
            items(tiles, key = { it.key }) { tile ->
                when (tile) {
                    is Tile.Picture -> AsyncImage(
                        model = vm.client()?.resolve(tile.image.path),
                        contentDescription = tile.job.prompt,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { viewing = tile },
                    )
                    is Tile.Status -> StatusTile(tile.job, onCancel = { vm.cancel(tile.job) }, onDelete = { vm.delete(tile.job) })
                }
            }
            if (state.hasMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = vm::loadMore) { Text("Load more") }
                }
            }
        }
    }

    viewing?.let { tile ->
        ImageViewer(
            url = vm.client()?.resolve(tile.image.path),
            job = tile.job,
            image = tile.image,
            onDismiss = { viewing = null },
            onSave = { vm.saveToGallery(tile.image) },
            onShare = { vm.share(tile.image) { intent -> context.startActivity(intent) } },
            onReuse = { vm.reuse(tile.job); viewing = null },
            onDelete = { vm.delete(tile.job); viewing = null },
        )
    }
}

@Composable
private fun GenerateForm(state: ImagesUiState, vm: ImagesViewModel) {
    var advanced by remember { mutableStateOf(false) }
    val wf = state.workflow
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WorkflowPicker(state, vm::selectWorkflow)
            OutlinedTextField(
                value = state.prompt,
                onValueChange = { v -> vm.edit { it.copy(prompt = v) } },
                label = { Text("Describe the image") },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            if (wf?.supports("negative_prompt") != false) {
                OutlinedTextField(
                    value = state.negativePrompt,
                    onValueChange = { v -> vm.edit { it.copy(negativePrompt = v) } },
                    label = { Text("Avoid (negative prompt)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (wf?.supports("width") != false) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SizePreset.entries.forEach { p ->
                        FilterChip(
                            selected = state.size == p,
                            onClick = { vm.edit { it.copy(size = p) } },
                            label = { Text(p.label) },
                        )
                    }
                }
            }
            TextButton(onClick = { advanced = !advanced }) {
                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (advanced) "Hide options" else "More options")
            }
            if (advanced) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.steps,
                        onValueChange = { v -> vm.edit { it.copy(steps = v.filter(Char::isDigit).take(3)) } },
                        label = { Text("Steps") },
                        placeholder = { Text(wf?.defaults?.get("steps")?.toString() ?: "auto") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.seed,
                        onValueChange = { v -> vm.edit { it.copy(seed = v.filter(Char::isDigit).take(15)) } },
                        label = { Text("Seed") },
                        placeholder = { Text("random") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Images", style = MaterialTheme.typography.bodyMedium)
                    (1..4).forEach { n ->
                        FilterChip(selected = state.count == n, onClick = { vm.edit { it.copy(count = n) } }, label = { Text("$n") })
                    }
                }
            }
            state.error?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(
                onClick = vm::generate,
                enabled = state.prompt.isNotBlank() && !state.submitting && state.selectedWorkflow != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.size(8.dp))
                Text("Generate")
            }
        }
    }
}

@Composable
private fun WorkflowPicker(state: ImagesUiState, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                state.workflow?.title ?: if (state.loading) "Loading workflows…" else "Pick a workflow",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.workflows.forEach { w ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(w.title)
                            if (w.description.isNotEmpty()) {
                                Text(w.description, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = { onSelect(w.id); open = false },
                )
            }
        }
    }
}

@Composable
private fun StatusTile(job: ImageJob, onCancel: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.aspectRatio(1f),
    ) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (job.status) {
                "queued", "running" -> {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        when {
                            job.status == "queued" && (job.queuePosition ?: 0) > 0 -> "Queued (#${job.queuePosition!! + 1})"
                            job.status == "queued" -> "Queued"
                            else -> job.progress ?: "Generating"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                else -> {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (job.status == "cancelled") "Cancelled" else job.error ?: "Failed",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
                }
            }
        }
    }
}

@Composable
private fun ImageViewer(
    url: String?,
    job: ImageJob,
    image: JobImage,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onReuse: () -> Unit,
    onDelete: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                }
                AsyncImage(
                    model = url,
                    contentDescription = job.prompt,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                Column(Modifier.padding(16.dp)) {
                    Text(job.prompt, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 4,
                        overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(job.workflow, image.seed?.let { "seed $it" }).joinToString(" · "),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ViewerAction(Icons.Outlined.Download, "Save", onSave)
                        ViewerAction(Icons.Outlined.Share, "Share", onShare)
                        ViewerAction(Icons.Outlined.Replay, "Reuse prompt", onReuse)
                        ViewerAction(Icons.Outlined.Delete, "Delete", onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = Color.White) }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
