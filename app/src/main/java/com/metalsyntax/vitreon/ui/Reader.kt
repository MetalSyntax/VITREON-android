package com.metalsyntax.vitreon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metalsyntax.vitreon.data.Block
import com.metalsyntax.vitreon.data.InlineStyle
import com.metalsyntax.vitreon.data.Markdown

/** Qué hacer al tocar algo en modo lectura. */
class ReaderActions(
    val openLink: (String) -> Unit = {},
    val openNote: (String) -> Unit = {},
    val openTag: (String) -> Unit = {},
    val toggleTask: (Int) -> Unit = {},
)

/** Solo se abren enlaces seguros (la web aceptaba javascript: y rompía comillas → XSS). */
fun safeUrl(u: String): String? = u.trim().takeIf { Regex("^(https?://|mailto:)", RegexOption.IGNORE_CASE).containsMatchIn(it) }

fun styled(src: String, base: Color = Ink): AnnotatedString {
    val i = Markdown.inline(src)
    return buildAnnotatedString {
        append(i.text)
        i.spans.forEach { s ->
            val st = when (s.style) {
                InlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                InlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                InlineStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                InlineStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough, color = Muted)
                InlineStyle.HIGHLIGHT -> SpanStyle(background = Warning.copy(alpha = 0.3f))
                InlineStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = Surface2, fontSize = 0.9.em())
                InlineStyle.LINK -> SpanStyle(color = Brand, textDecoration = TextDecoration.Underline)
                InlineStyle.WIKILINK -> SpanStyle(color = Brand, fontWeight = FontWeight.SemiBold)
                InlineStyle.TAG -> SpanStyle(color = Brand, background = Brand.copy(alpha = 0.12f))
            }
            addStyle(st, s.start, s.end)
            s.target?.let { addStringAnnotation(s.style.name, it, s.start, s.end) }
        }
    }
}

private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

@Composable
private fun Rich(src: String, style: TextStyle, color: Color = Ink, a: ReaderActions) {
    val text = styled(src, color)
    ClickableText(text, style = style.copy(color = color)) { off ->
        text.getStringAnnotations(off, off).firstOrNull()?.let { ann ->
            when (ann.tag) {
                InlineStyle.LINK.name -> safeUrl(ann.item)?.let(a.openLink)
                InlineStyle.WIKILINK.name -> a.openNote(ann.item)
                InlineStyle.TAG.name -> a.openTag(ann.item)
            }
        }
    }
}

private fun calloutColor(kind: String) = when (kind) {
    "tip", "hint", "important", "success", "check", "done" -> Success
    "warning", "caution", "attention", "question", "help", "faq" -> Warning
    "danger", "error", "bug", "failure", "fail", "missing" -> Danger
    "quote", "cite" -> Color(0xFF94A3B8)
    else -> Brand
}

/** Nota en modo lectura. [maxBlocks] para vistas previas en tarjetas. */
@Composable
fun MarkdownView(src: String, a: ReaderActions = ReaderActions(), maxBlocks: Int = Int.MAX_VALUE, compact: Boolean = false) {
    val body = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)) {
        Markdown.blocks(src).take(maxBlocks).forEach { b ->
            when (b) {
                is Block.Heading -> Rich(b.text, when (b.level) {
                    1 -> MaterialTheme.typography.headlineMedium; 2 -> MaterialTheme.typography.headlineSmall; 3 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                }.let { if (compact) MaterialTheme.typography.titleSmall else it }, a = a)
                is Block.Paragraph -> Rich(b.text, body, a = a)
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(Brand.copy(alpha = 0.6f)))
                    Spacer(Modifier.width(12.dp)); Rich(b.text, body.copy(fontStyle = FontStyle.Italic), Muted, a)
                }
                is Block.Callout -> {
                    val c = calloutColor(b.kind)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.copy(alpha = 0.1f)).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (c) { Success -> Icons.Outlined.CheckCircle; Warning -> Icons.Outlined.WarningAmber; Danger -> Icons.Outlined.ErrorOutline; else -> Icons.Outlined.Info },
                                null, tint = c, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text(b.title, style = MaterialTheme.typography.titleSmall, color = c)
                        }
                        if (b.body.isNotBlank()) { Spacer(Modifier.height(6.dp)); Rich(b.body, body, a = a) }
                    }
                }
                is Block.Item -> Row(Modifier.padding(start = (b.depth * 18).dp), verticalAlignment = Alignment.Top) {
                    when {
                        b.checked != null -> Icon(if (b.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null,
                            tint = if (b.checked) Brand else Muted, modifier = Modifier.padding(top = 1.dp, end = 8.dp).size(22.dp).tap(onClick = { a.toggleTask(b.line) }))
                        b.number != null -> Text("${b.number}.", Modifier.width(26.dp), style = body, color = Muted)
                        else -> Box(Modifier.padding(top = 10.dp, start = 6.dp, end = 12.dp).size(6.dp).clip(CircleShape).background(Muted))
                    }
                    Rich(b.text, if (b.checked == true) body.copy(textDecoration = TextDecoration.LineThrough) else body, if (b.checked == true) Muted else Ink, a)
                }
                is Block.Code -> Text(b.text, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).horizontalScroll(rememberScrollState()).padding(14.dp),
                    style = body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp), color = Ink)
                is Block.Table -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1)) {
                    (listOf(b.header) + b.rows).forEachIndexed { i, row ->
                        Row(Modifier.fillMaxWidth().background(if (i == 0) Surface2 else Color.Transparent).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            row.forEach { cell -> Box(Modifier.weight(1f)) { Rich(cell, if (i == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, a = a) } }
                        }
                    }
                }
                is Block.Properties -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.entries.forEach { (k, v) -> Text("$k: $v", Modifier.clip(CircleShape).background(Surface1).padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = Muted) }
                }
                Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(Surface2))
            }
        }
    }
}
