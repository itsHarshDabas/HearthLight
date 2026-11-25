package dev.localai.chat.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import dev.localai.core.Attachment
import dev.localai.core.GatewayClient
import dev.localai.core.GatewayException
import dev.localai.core.Prompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class AttachmentException(message: String) : Exception(message)

/**
 * Turns picked/captured content into [Attachment]s:
 *  - images are downscaled to a JPEG copy in app storage (vision models don't need more),
 *  - text files are read on the phone, other documents are converted to text by the gateway,
 *  - audio is copied into app storage and transcribed by the gateway's Whisper.
 */
class AttachmentRepository(private val context: Context) {
    private val root = File(context.filesDir, "attachments")
    private val resolver: ContentResolver get() = context.contentResolver

    fun dirFor(conversationId: String): File = File(root, conversationId).apply { mkdirs() }

    fun deleteFor(conversationId: String) {
        File(root, conversationId).deleteRecursively()
    }

    /** New file in the cache that the camera app can write to (through our FileProvider). */
    fun newCameraFile(): File = File(context.cacheDir, "camera").apply { mkdirs() }.let { File(it, "photo-${UUID.randomUUID()}.jpg") }

    fun newRecordingFile(): File = File(context.cacheDir, "recordings").apply { mkdirs() }.let { File(it, "voice-${UUID.randomUUID()}.m4a") }

    data class Meta(val name: String, val mimeType: String, val size: Long)

    fun meta(uri: Uri): Meta {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val mime = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.orEmpty().substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        return Meta(name!!, mime, size)
    }

    /** Anything picked with the file picker: routed by type. */
    suspend fun import(uri: Uri, conversationId: String, client: GatewayClient?): Attachment {
        val m = meta(uri)
        return when {
            m.mimeType.startsWith("image/") -> importImage(uri, conversationId, m)
            m.mimeType.startsWith("audio/") || m.mimeType == "application/ogg" -> importAudio(uri, conversationId, client, m)
            else -> importDocument(uri, client, m)
        }
    }

    suspend fun importImage(uri: Uri, conversationId: String, meta: Meta = meta(uri)): Attachment = withContext(Dispatchers.IO) {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = MAX_IMAGE_EDGE.toFloat() / maxOf(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        } catch (e: Exception) {
            throw AttachmentException("Couldn't open this image (${e.message ?: e.javaClass.simpleName})")
        }
        val id = UUID.randomUUID().toString()
        val out = File(dirFor(conversationId), "$id.jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        Attachment(
            id = id, kind = Attachment.KIND_IMAGE, name = meta.name.ifBlank { "photo.jpg" },
            mimeType = "image/jpeg", sizeBytes = out.length(), localPath = out.absolutePath,
        )
    }

    suspend fun importDocument(uri: Uri, client: GatewayClient?, meta: Meta = meta(uri)): Attachment {
        val bytes = readBytes(uri, MAX_DOCUMENT_BYTES, "File")
        val (text, truncated) = if (Prompt.isPlainText(meta.name, meta.mimeType) && !looksBinary(bytes)) {
            val t = decodeText(bytes)
            if (t.length > MAX_TEXT_CHARS) t.take(MAX_TEXT_CHARS) to true else t to false
        } else {
            client ?: throw AttachmentException("Set up your server in Settings to attach documents")
            try {
                val ex = client.extractText(bytes, meta.name, meta.mimeType)
                ex.text to ex.truncated
            } catch (e: GatewayException) {
                throw AttachmentException(e.message ?: "Couldn't read this file")
            }
        }
        if (text.isBlank()) throw AttachmentException("No text found in ${meta.name}")
        return Attachment(
            kind = Attachment.KIND_DOCUMENT, name = meta.name, mimeType = meta.mimeType,
            sizeBytes = bytes.size.toLong(), text = text, truncated = truncated,
        )
    }

    /** Audio picked from storage: copied in, then transcribed. */
    suspend fun importAudio(uri: Uri, conversationId: String, client: GatewayClient?, meta: Meta = meta(uri)): Attachment {
        val bytes = readBytes(uri, MAX_AUDIO_BYTES, "Audio file")
        val ext = meta.name.substringAfterLast('.', "m4a").take(5)
        val id = UUID.randomUUID().toString()
        val out = File(dirFor(conversationId), "$id.$ext")
        withContext(Dispatchers.IO) { out.writeBytes(bytes) }
        return transcribe(out, meta.name, meta.mimeType, id, client)
    }

    /** A voice note recorded in the app: moved in, then transcribed. */
    suspend fun importRecording(file: File, conversationId: String, client: GatewayClient?): Attachment {
        val id = UUID.randomUUID().toString()
        val out = File(dirFor(conversationId), "$id.m4a")
        withContext(Dispatchers.IO) {
            if (!file.renameTo(out)) {
                file.copyTo(out, overwrite = true)
                file.delete()
            }
        }
        return transcribe(out, "Voice note", "audio/mp4", id, client)
    }

    private suspend fun transcribe(file: File, name: String, mime: String, id: String, client: GatewayClient?): Attachment {
        val base = Attachment(
            id = id, kind = Attachment.KIND_AUDIO, name = name, mimeType = mime,
            sizeBytes = file.length(), localPath = file.absolutePath, durationMs = durationOf(file),
        )
        client ?: throw AttachmentException("Set up your server in Settings to send voice notes")
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val t = try {
            client.transcribe(bytes, if (name.contains('.')) name else "$name.m4a".replace(' ', '-'), mime)
        } catch (e: GatewayException) {
            file.delete()
            throw AttachmentException(
                when (e.status) {
                    501 -> "Speech-to-text isn't set up on your PC yet. On the PC run: pip install -e \".[audio]\" in the gateway folder, then restart it."
                    404 -> "Your gateway is too old for voice notes. Update it on the PC."
                    else -> "Transcription failed: ${e.message}"
                }
            )
        }
        if (t.text.isBlank()) {
            file.delete()
            throw AttachmentException("No speech found in the recording")
        }
        return base.copy(text = t.text)
    }

    /** `data:` URL for an image attachment, or null if its file is gone. */
    fun imageDataUrl(a: Attachment): String? {
        val f = a.localPath?.let(::File)?.takeIf { it.isFile } ?: return null
        return "data:${a.mimeType.ifBlank { "image/jpeg" }};base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }

    private suspend fun readBytes(uri: Uri, max: Long, what: String): ByteArray = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: throw AttachmentException("Couldn't open the file")
        input.use { s ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = s.read(chunk)
                if (n < 0) break
                total += n
                if (total > max) throw AttachmentException("$what is larger than ${max / (1024 * 1024)} MB")
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        }
    }

    private fun durationOf(file: File): Long? = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                release()
            }
        }
    }.getOrNull()

    private fun looksBinary(b: ByteArray): Boolean {
        val n = minOf(b.size, 4096)
        if (n >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))) return false
        for (i in 0 until n) if (b[i] == 0.toByte()) return true
        return false
    }

    private fun decodeText(b: ByteArray): String {
        if (b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))) {
            return String(b, Charsets.UTF_16)
        }
        val s = String(b, Charsets.UTF_8)
        return s.removePrefix("﻿")
    }

    companion object {
        const val MAX_IMAGE_EDGE = 1536
        const val MAX_DOCUMENT_BYTES = 25L * 1024 * 1024
        const val MAX_AUDIO_BYTES = 25L * 1024 * 1024
        const val MAX_TEXT_CHARS = 200_000
    }
}
