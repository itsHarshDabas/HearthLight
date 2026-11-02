package dev.localai.core

/**
 * A small, forgiving Markdown parser for chat messages. It handles what LLMs actually
 * produce (fenced code, headings, lists, quotes, tables, emphasis, inline code, links)
 * and copes with half-finished text while a reply is still streaming.
 */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class ListItem(val ordered: Boolean, val marker: String, val indent: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d{1,4}[.)])\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val fence = Regex("^\\s*(```|~~~)\\s*([^`\\s]*)")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    fun parseBlocks(text: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val lines = text.replace("\r\n", "\n").split("\n")
        val para = StringBuilder()
        fun flushPara() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val f = fence.find(line)
            if (f != null) {
                flushPara()
                val marker = f.groupValues[1]
                val lang = f.groupValues[2]
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                out += MdBlock.Code(lang, code.toString())
                i++ // skip closing fence (or run past the end while streaming)
                continue
            }
            if (line.isBlank()) {
                flushPara(); i++; continue
            }
            if (line.trimStart().startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                flushPara()
                val rows = mutableListOf(splitRow(line))
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    rows += splitRow(lines[i]); i++
                }
                out += MdBlock.Table(rows)
                continue
            }
            val h = heading.find(line)
            if (h != null) {
                flushPara()
                out += MdBlock.Heading(h.groupValues[1].length, h.groupValues[2].trim())
                i++
                continue
            }
            when {
                rule.matches(line) -> { flushPara(); out += MdBlock.Rule }
                bullet.matches(line) -> {
                    flushPara()
                    val m = bullet.find(line)!!
                    out += MdBlock.ListItem(false, "•", m.groupValues[1].length / 2, m.groupValues[3])
                }
                ordered.matches(line) -> {
                    flushPara()
                    val m = ordered.find(line)!!
                    out += MdBlock.ListItem(true, m.groupValues[2], m.groupValues[1].length / 2, m.groupValues[3])
                }
                line.trimStart().startsWith(">") -> {
                    flushPara()
                    val q = StringBuilder(line.trimStart().removePrefix(">").trimStart())
                    while (i + 1 < lines.size && lines[i + 1].trimStart().startsWith(">")) {
                        i++
                        q.append('\n').append(lines[i].trimStart().removePrefix(">").trimStart())
                    }
                    out += MdBlock.Quote(q.toString())
                }
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line)
                }
            }
            i++
        }
        flushPara()
        return out
    }

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    fun parseInline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        parseInto(text, MdSpan(""), out)
        // merge neighbours with identical style
        val merged = mutableListOf<MdSpan>()
        for (s in out) {
            val last = merged.lastOrNull()
            if (last != null && last.copy(text = "") == s.copy(text = "")) merged[merged.lastIndex] = last.copy(text = last.text + s.text)
            else if (s.text.isNotEmpty()) merged += s
        }
        return merged
    }

    private fun parseInto(text: String, style: MdSpan, out: MutableList<MdSpan>) {
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) out += style.copy(text = buf.toString())
            buf.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // inline code
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flush(); out += style.copy(text = text.substring(i + 1, end), code = true); i = end + 1; continue
                }
            }
            // bold+italic ***
            if (text.startsWith("***", i)) {
                val end = text.indexOf("***", i + 3)
                if (end > i + 3) {
                    flush(); parseInto(text.substring(i + 3, end), style.copy(bold = true, italic = true), out); i = end + 3; continue
                }
            }
            // bold ** / __, strike ~~
            if (i + 1 < text.length && (text.startsWith("**", i) || text.startsWith("__", i) || text.startsWith("~~", i))) {
                val marker = text.substring(i, i + 2)
                val end = text.indexOf(marker, i + 2)
                if (end > i + 2) {
                    flush()
                    val inner = text.substring(i + 2, end)
                    parseInto(inner, if (marker == "~~") style.copy(strike = true) else style.copy(bold = true), out)
                    i = end + 2; continue
                }
            }
            // italic * / _
            if ((c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace()) {
                val wordBoundary = c == '*' || i == 0 || !text[i - 1].isLetterOrDigit()
                val end = text.indexOf(c, i + 1)
                val endOk = end > i + 1 && !text[end - 1].isWhitespace() &&
                    (c == '*' || end + 1 >= text.length || !text[end + 1].isLetterOrDigit())
                if (wordBoundary && endOk) {
                    flush(); parseInto(text.substring(i + 1, end), style.copy(italic = true), out); i = end + 1; continue
                }
            }
            // [label](url)
            if (c == '[') {
                val close = text.indexOf("](", i + 1)
                val end = if (close > 0) text.indexOf(')', close + 2) else -1
                if (close > i && end > close) {
                    val url = text.substring(close + 2, end).trim()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        flush(); parseInto(text.substring(i + 1, close), style.copy(link = url), out); i = end + 1; continue
                    }
                }
            }
            buf.append(c)
            i++
        }
        flush()
    }
}
