package dev.localai.chat.ui.images

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localai.chat.LocalAiApp
import dev.localai.core.GatewayClient
import dev.localai.core.ImageJob
import dev.localai.core.ImageJobRequest
import dev.localai.core.JobImage
import dev.localai.core.WorkflowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SizePreset(val label: String, val width: Int, val height: Int) {
    Square("Square", 1024, 1024),
    Portrait("Portrait", 832, 1216),
    Landscape("Landscape", 1216, 832),
}

data class ImagesUiState(
    val workflows: List<WorkflowInfo> = emptyList(),
    val selectedWorkflow: String? = null,
    val prompt: String = "",
    val negativePrompt: String = "",
    val size: SizePreset = SizePreset.Square,
    val steps: String = "",
    val seed: String = "",
    val count: Int = 1,
    val jobs: List<ImageJob> = emptyList(),
    val hasMore: Boolean = false,
    val nextBefore: Double? = null,
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val workflow: WorkflowInfo? get() = workflows.firstOrNull { it.id == selectedWorkflow }
}

class ImagesViewModel(private val app: Application) : AndroidViewModel(app) {
    private val container = (app as LocalAiApp).container
    private val _state = MutableStateFlow(ImagesUiState(selectedWorkflow = container.settings.state.value.lastWorkflow))
    val state: StateFlow<ImagesUiState> = _state.asStateFlow()
    private var pollJob: Job? = null

    fun client(): GatewayClient? = container.client()

    fun refresh() {
        val client = client() ?: run {
            _state.update { it.copy(error = "Set up your server in Settings") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val wfs = client.workflows()
                val list = client.imageJobs(limit = 40)
                _state.update { s ->
                    s.copy(
                        workflows = wfs,
                        selectedWorkflow = s.selectedWorkflow?.takeIf { id -> wfs.any { it.id == id } } ?: wfs.firstOrNull()?.id,
                        jobs = list.data, hasMore = list.hasMore, nextBefore = list.nextBefore, loading = false,
                        error = if (wfs.isEmpty()) "No workflows on the PC yet. See docs/workflows.md." else null,
                    )
                }
                ensurePolling()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun loadMore() {
        val client = client() ?: return
        val before = _state.value.nextBefore ?: return
        viewModelScope.launch {
            runCatching { client.imageJobs(limit = 40, before = before) }.onSuccess { list ->
                _state.update { s ->
                    s.copy(jobs = s.jobs + list.data.filter { j -> s.jobs.none { it.id == j.id } },
                        hasMore = list.hasMore, nextBefore = list.nextBefore)
                }
            }
        }
    }

    fun edit(transform: (ImagesUiState) -> ImagesUiState) = _state.update(transform)

    fun selectWorkflow(id: String) {
        _state.update { it.copy(selectedWorkflow = id) }
        container.settings.update { it.copy(lastWorkflow = id) }
    }

    fun generate() {
        val s = _state.value
        val client = client() ?: return
        if (s.prompt.isBlank() || s.submitting) return
        val wf = s.workflow
        val req = ImageJobRequest(
            prompt = s.prompt.trim(),
            workflow = s.selectedWorkflow,
            negativePrompt = s.negativePrompt.trim().takeIf { it.isNotEmpty() && wf?.supports("negative_prompt") != false },
            width = s.size.width.takeIf { wf?.supports("width") != false },
            height = s.size.height.takeIf { wf?.supports("height") != false },
            steps = s.steps.toIntOrNull(),
            seed = s.seed.toLongOrNull(),
            n = s.count,
        )
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val job = client.createImageJob(req)
                _state.update { it.copy(submitting = false, jobs = listOf(job) + it.jobs) }
                ensurePolling()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, error = e.message) }
            }
        }
    }

    /** While any job is unfinished, refresh those jobs every ~1.5 s. The PC keeps working even if the app closes. */
    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val active = _state.value.jobs.filter { !it.isFinal }
                if (active.isEmpty()) break
                val client = client() ?: break
                for (job in active) {
                    val fresh = runCatching { client.imageJob(job.id) }.getOrNull() ?: continue
                    _state.update { s -> s.copy(jobs = s.jobs.map { if (it.id == fresh.id) fresh else it }) }
                }
                delay(1500)
            }
        }
    }

    fun cancel(job: ImageJob) {
        val client = client() ?: return
        viewModelScope.launch {
            runCatching { client.cancelImageJob(job.id) }.onSuccess { fresh ->
                _state.update { s -> s.copy(jobs = s.jobs.map { if (it.id == fresh.id) fresh else it }) }
            }
        }
    }

    fun delete(job: ImageJob) {
        val client = client() ?: return
        viewModelScope.launch {
            runCatching { client.deleteImageJob(job.id) }
                .onSuccess { _state.update { s -> s.copy(jobs = s.jobs.filter { it.id != job.id }) } }
                .onFailure { e -> _state.update { it.copy(message = "Delete failed: ${e.message}") } }
        }
    }

    fun reuse(job: ImageJob) {
        _state.update { it.copy(prompt = job.prompt, selectedWorkflow = job.workflow.takeIf { id -> it.workflows.any { w -> w.id == id } } ?: it.selectedWorkflow) }
    }

    fun saveToGallery(image: JobImage) {
        val client = client() ?: return
        viewModelScope.launch {
            val result = runCatching {
                val bytes = client.download(image.path)
                withContext(Dispatchers.IO) {
                    val resolver = app.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "localai_${System.currentTimeMillis()}.${extFor(image.path)}")
                        put(MediaStore.Images.Media.MIME_TYPE, mimeFor(image.path))
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LocalAI")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("Couldn't create the gallery entry")
                    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Couldn't write the image")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
            _state.update { it.copy(message = result.fold({ "Saved to Pictures/LocalAI" }, { e -> "Save failed: ${e.message}" })) }
        }
    }

    fun share(image: JobImage, onIntent: (Intent) -> Unit) {
        val client = client() ?: return
        viewModelScope.launch {
            runCatching {
                val bytes = client.download(image.path)
                val file = withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "shared").apply { mkdirs() }
                    File(dir, "image.${extFor(image.path)}").apply { writeBytes(bytes) }
                }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeFor(image.path)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }.onSuccess { onIntent(Intent.createChooser(it, "Share image")) }
                .onFailure { e -> _state.update { it.copy(message = "Share failed: ${e.message}") } }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun extFor(path: String): String = path.substringBefore('?').substringAfterLast('.', "png").lowercase()
    private fun mimeFor(path: String): String = when (extFor(path)) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }
}
