package dev.localai.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {
    @Test
    fun blocks() {
        val md = """
            # Title
            Some text
            continues here.

            - one
              - nested
            1. first
            > quoted
            > more

            ```kotlin
            val x = 1
            ```
            ---
            | a | b |
            |---|:-:|
            | 1 | 2 |
        """.trimIndent()
        val b = Markdown.parseBlocks(md)
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"),
                MdBlock.Paragraph("Some text\ncontinues here."),
                MdBlock.ListItem(false, "•", 0, "one"),
                MdBlock.ListItem(false, "•", 1, "nested"),
                MdBlock.ListItem(true, "1.", 0, "first"),
                MdBlock.Quote("quoted\nmore"),
                MdBlock.Code("kotlin", "val x = 1"),
                MdBlock.Rule,
                MdBlock.Table(listOf(listOf("a", "b"), listOf("1", "2"))),
            ),
            b,
        )
    }

    @Test
    fun unterminatedFenceWhileStreaming() {
        assertEquals(listOf(MdBlock.Paragraph("Here:"), MdBlock.Code("py", "print(1)\nprint(2")),
            Markdown.parseBlocks("Here:\n```py\nprint(1)\nprint(2"))
    }

    @Test
    fun inline() {
        assertEquals(
            listOf(
                MdSpan("a "), MdSpan("bold", bold = true), MdSpan(" and "), MdSpan("it", italic = true),
                MdSpan(" "), MdSpan("x = 1", code = true), MdSpan(" "), MdSpan("site", link = "https://e.com"),
                MdSpan(" "), MdSpan("gone", strike = true),
            ),
            Markdown.parseInline("a **bold** and *it* `x = 1` [site](https://e.com) ~~gone~~"),
        )
        // snake_case and lone asterisks stay literal
        assertEquals(listOf(MdSpan("my_var_name and 2 * 3 = 6")), Markdown.parseInline("my_var_name and 2 * 3 = 6"))
        // unclosed markers while streaming stay literal
        assertEquals(listOf(MdSpan("**half")), Markdown.parseInline("**half"))
        assertEquals(listOf(MdSpan("x", bold = true, italic = true)), Markdown.parseInline("***x***").let { listOf(it.single()) })
    }
}
