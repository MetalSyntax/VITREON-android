package com.metalsyntax.vitreon

import com.metalsyntax.vitreon.data.Block
import com.metalsyntax.vitreon.data.InlineStyle
import com.metalsyntax.vitreon.data.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun blocksCoverVitreonAndObsidianSyntax() {
        val b = Markdown.blocks(
            """
            ---
            tags: [a, b]
            ---
            # Título
            Texto **fuerte**
            > cita
            > [!warning] Ojo
            - [ ] tarea
            - [x] hecha
            1. uno
            ---
            ```kotlin
            val x = 1
            ```
            | a | b |
            |---|---|
            | 1 | 2 |
            """.trimIndent()
        )
        assertTrue(b[0] is Block.Properties)
        assertEquals(Block.Heading(1, "Título"), b[1])
        assertTrue(b[2] is Block.Paragraph)
        assertTrue(b[3] is Block.Quote) // la cita y el callout van seguidos: se agrupan como cita normal porque el primero no es [!]
        assertEquals(false, (b[4] as Block.Item).checked)
        assertEquals(true, (b[5] as Block.Item).checked)
        assertEquals(1, (b[6] as Block.Item).number)
        assertEquals(Block.Rule, b[7])
        assertEquals(Block.Code("kotlin", "val x = 1"), b[8])
        assertEquals(listOf(listOf("1", "2")), (b[9] as Block.Table).rows)
    }

    @Test fun calloutWhenFirstLineIsMarker() {
        val c = Markdown.blocks("> [!tip] Consejo\n> cuerpo").single() as Block.Callout
        assertEquals("tip", c.kind); assertEquals("Consejo", c.title); assertEquals("cuerpo", c.body)
    }

    @Test fun inlineStylesNestAndStripMarks() {
        val i = Markdown.inline("Hola **mundo _lindo_** y ++sub++ con [[Nota|alias]] #idea `x*y*`")
        assertEquals("Hola mundo lindo y sub con alias #idea x*y*", i.text)
        assertTrue(i.spans.any { it.style == InlineStyle.BOLD && i.text.substring(it.start, it.end) == "mundo lindo" })
        assertTrue(i.spans.any { it.style == InlineStyle.ITALIC && i.text.substring(it.start, it.end) == "lindo" })
        assertEquals("Nota", i.spans.single { it.style == InlineStyle.WIKILINK }.target)
        assertEquals("idea", i.spans.single { it.style == InlineStyle.TAG }.target)
        assertTrue(i.spans.none { it.style == InlineStyle.ITALIC && i.text.substring(it.start, it.end) == "y" }) // dentro de `código` no hay cursiva
    }

    @Test fun htmlIsNeverInterpreted() {
        val i = Markdown.inline("<script>alert(1)</script> <b>x</b>")
        assertEquals("<script>alert(1)</script> <b>x</b>", i.text)
        assertTrue(i.spans.isEmpty())
    }

    @Test fun toggleTaskAndLinks() {
        val src = "- [ ] a\n- [x] b"
        assertEquals("- [x] a\n- [x] b", Markdown.toggleTask(src, 0))
        assertEquals(listOf("Uno", "Dos"), Markdown.links("ver [[Uno]] y [[Dos|alias]] y [[Uno#sección]]"))
    }

    @Test fun plainTextForPreview() {
        assertEquals("Título\nHola mundo\n✓ hecho", Markdown.plain("---\na: 1\n---\n# Título\nHola **mundo**\n- [x] hecho"))
    }
}
