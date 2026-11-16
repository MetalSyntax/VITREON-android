package com.metalsyntax.vitreon

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.metalsyntax.vitreon.data.Note
import com.metalsyntax.vitreon.ui.Sort
import com.metalsyntax.vitreon.ui.Toolbar
import com.metalsyntax.vitreon.ui.View
import com.metalsyntax.vitreon.ui.safeUrl
import com.metalsyntax.vitreon.ui.visibleNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiLogicTest {
    private val notes = listOf(
        Note(id = "1", title = "Compras", content = "pan #casa", category = "personal", tags = listOf("hogar"), order = 3.0, createdAt = 10),
        Note(id = "2", title = "Bloqueada", content = "", sealed = "x", locked = true, category = "work", tags = listOf("secreto"), order = 2.0, createdAt = 30),
        Note(id = "3", title = "archivo", content = "pan viejo", archived = true, order = 1.0, createdAt = 20),
        Note(id = "4", title = "Borrada", content = "pan", deletedAt = 5, createdAt = 5),
        Note(id = "5", title = "Viaje", content = "", pinned = true, category = "travel", order = 4.0, createdAt = 40),
    )
    private fun ids(l: List<Note>) = l.map { it.id }

    @Test fun viewsAndSearchRespectCategoryTagsAndLocks() {
        assertEquals(listOf("5", "1", "2"), ids(visibleNotes(notes, View.ALL, null, "", Sort.NEWEST)))
        assertEquals(listOf("1"), ids(visibleNotes(notes, View.ALL, null, "pan", Sort.NEWEST)))      // ni archivadas ni papelera
        assertEquals(emptyList<String>(), ids(visibleNotes(notes, View.ALL, "work", "pan", Sort.NEWEST))) // la búsqueda respeta la categoría
        assertEquals(listOf("2"), ids(visibleNotes(notes, View.ALL, null, "secreto", Sort.NEWEST)))  // etiquetas sí, contenido bloqueado no
        assertEquals(listOf("1"), ids(visibleNotes(notes, View.ALL, null, "#casa", Sort.NEWEST)))    // #etiqueta del contenido
        assertEquals(listOf("1"), ids(visibleNotes(notes, View.ALL, null, "#hogar", Sort.NEWEST)))
        assertEquals(listOf("3"), ids(visibleNotes(notes, View.ARCHIVE, null, "", Sort.NEWEST)))
        assertEquals(listOf("4"), ids(visibleNotes(notes, View.TRASH, null, "", Sort.NEWEST)))
        assertEquals(listOf("5"), ids(visibleNotes(notes, View.FAVORITES, null, "", Sort.NEWEST)))
    }

    @Test fun oldestSortsByCreationAndAlphabetIgnoresCase() {
        assertEquals(listOf("1", "2", "5"), ids(visibleNotes(notes, View.ALL, null, "", Sort.OLDEST)))
        assertEquals(listOf("2", "1", "5"), ids(visibleNotes(notes, View.ALL, null, "", Sort.AZ)))
    }

    @Test fun toolbarWrapsAndTogglesPrefixes() {
        assertEquals("a **b** c", Toolbar.wrap(TextFieldValue("a b c", TextRange(2, 3)), "**").text)
        val list = Toolbar.prefixLines(TextFieldValue("uno\ndos", TextRange(0, 7)), "- ")
        assertEquals("- uno\n- dos", list.text)
        assertEquals("uno\ndos", Toolbar.prefixLines(TextFieldValue(list.text, TextRange(0, list.text.length)), "- ").text)
        assertEquals("## Título", Toolbar.prefixLines(TextFieldValue("# Título", TextRange(3)), Toolbar.nextHeading(TextFieldValue("# Título", TextRange(3)))).text)
    }

    @Test fun onlySafeLinksOpen() {
        assertEquals("https://a.com", safeUrl("https://a.com"))
        assertEquals("mailto:x@y.z", safeUrl("mailto:x@y.z"))
        assertNull(safeUrl("javascript:alert(1)"))
        assertNull(safeUrl("data:text/html,<script>"))
    }
}
