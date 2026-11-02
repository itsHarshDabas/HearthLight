package dev.localai.chat.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.localai.core.Markdown
import dev.localai.core.MdBlock

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val blocks = remember(text) { Markdown.parseBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> Text(inline(block.text), style = style)
                is MdBlock.Heading -> Text(
                    inline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }.copy(fontWeight = FontWeight.SemiBold),
                )
                is MdBlock.ListItem -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    Text(block.marker, style = style, modifier = Modifier.width(if (block.ordered) 28.dp else 18.dp))
                    Text(inline(block.text), style = style)
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Text(
                        inline(block.text),
                        style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is MdBlock.Code -> CodeBlock(block.language, block.code)
                is MdBlock.Table -> TableBlock(block.rows, style)
                MdBlock.Rule -> HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    language.ifEmpty { "code" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy code", modifier = Modifier.size(18.dp))
                }
            }
            Text(
                code,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>, style: TextStyle) {
    val cols = rows.maxOfOrNull { it.size } ?: 0
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        Column {
            rows.forEachIndexed { r, row ->
                Row(
                    Modifier.background(
                        if (r == 0) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent
                    )
                ) {
                    for (c in 0 until cols) {
                        Text(
                            inline(row.getOrElse(c) { "" }),
                            style = if (r == 0) style.copy(fontWeight = FontWeight.SemiBold) else style,
                            modifier = Modifier
                                .width(140.dp)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                if (r == 0) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun inline(text: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, codeBg, linkColor) {
        buildAnnotatedString {
            for (span in Markdown.parseInline(text)) {
                val st = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) codeBg else Color.Unspecified,
                    textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                )
                val link = span.link
                if (link != null) {
                    withLink(
                        LinkAnnotation.Url(
                            link,
                            TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        )
                    ) { withStyle(st) { append(span.text) } }
                } else {
                    withStyle(st) { append(span.text) }
                }
            }
        }
    }
}
