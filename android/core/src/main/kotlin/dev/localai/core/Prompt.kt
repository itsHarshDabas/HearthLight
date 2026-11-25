package dev.localai.core

/**
 * Turns stored messages (with attachments) into what the model sees:
 *  - documents are inlined as `<file name="...">...</file>` blocks after the message text,
 *  - voice notes become their transcript,
 *  - images become OpenAI `image_url` parts (only then is the content an array).
 */
object Prompt {
    /**
     * @param imageDataUrl loads an image attachment as a `data:` URL; return null to skip it
     *   (e.g. the file was deleted).
     */
    fun toChatMessage(m: StoredMessage, imageDataUrl: (Attachment) -> String?): ChatMessage? {
        val text = textWithAttachments(m)
        val images = m.attachments.filter { it.kind == Attachment.KIND_IMAGE }.mapNotNull(imageDataUrl)
        if (images.isEmpty()) return if (text.isBlank()) null else ChatMessage(m.role, text)
        val parts = buildList {
            if (text.isNotBlank()) add(ContentPart.Text(text))
            images.forEach { add(ContentPart.ImageUrl(it)) }
        }
        return ChatMessage.withParts(m.role, parts)
    }

    fun textWithAttachments(m: StoredMessage): String = buildString {
        val voice = m.attachments.filter { it.kind == Attachment.KIND_AUDIO && !it.text.isNullOrBlank() }
        val docs = m.attachments.filter { it.kind == Attachment.KIND_DOCUMENT && it.text != null }
        val typed = m.content.trim()
        if (typed.isEmpty() && voice.size == 1 && docs.isEmpty()) {
            // A lone voice note *is* the message.
            append(voice.single().text!!.trim())
            return@buildString
        }
        append(typed)
        voice.forEach { a ->
            if (isNotEmpty()) append("\n\n")
            append("[Voice note transcript")
            a.durationMs?.let { append(", ").append(formatDuration(it)) }
            append("]\n").append(a.text!!.trim())
        }
        docs.forEach { a ->
            if (isNotEmpty()) append("\n\n")
            append("<file name=\"").append(a.name.replace("\"", "'")).append("\">\n")
            append(a.text!!.trimEnd())
            if (a.truncated) append("\n[... file truncated ...]")
            append("\n</file>")
        }
    }

    fun formatDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Text files the app can read itself, without the gateway. */
    fun isPlainText(name: String, mimeType: String?): Boolean {
        val mt = mimeType.orEmpty().lowercase()
        if (mt.startsWith("text/") || mt in TEXT_MIME) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS
    }

    private val TEXT_MIME = setOf(
        "application/json", "application/xml", "application/x-yaml", "application/yaml", "application/javascript",
        "application/x-sh", "application/toml", "application/x-subrip",
    )

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf", "log",
        "xml", "html", "htm", "css", "js", "mjs", "ts", "tsx", "jsx", "py", "kt", "kts", "java", "c", "h", "cpp",
        "hpp", "cs", "go", "rs", "rb", "php", "swift", "sh", "bat", "ps1", "sql", "gradle", "properties", "tex",
        "srt", "vtt", "r", "lua", "dart", "scala", "vue", "svelte",
    )
}
