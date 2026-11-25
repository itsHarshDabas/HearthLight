package dev.localai.chat.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records a voice note as AAC in an .m4a file (16 kHz mono: what Whisper wants anyway). */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    var startedAt: Long = 0L
        private set

    val isRecording: Boolean get() = recorder != null

    fun start(output: File) {
        stopQuietly()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(48_000)
            r.setOutputFile(output.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            output.delete()
            throw AttachmentException("Couldn't start recording (${e.message ?: e.javaClass.simpleName})")
        }
        recorder = r
        file = output
        startedAt = System.currentTimeMillis()
    }

    /** Stops and returns the file, or null if the recording was too short to be valid. */
    fun stop(): File? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        file = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) { // thrown when stopped right after start (no data)
            false
        } finally {
            r.release()
        }
        if (!ok) f?.delete()
        return if (ok) f else null
    }

    fun cancel() {
        stop()?.delete()
    }

    private fun stopQuietly() {
        runCatching { cancel() }
    }
}
