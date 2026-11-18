package com.metalsyntax.vitreon

import com.metalsyntax.vitreon.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class StoreTest {
    @Before fun setUp() { Store.initForTest(Files.createTempDirectory("vit").toFile()) }

    private fun note(title: String, content: String = "c", updated: Long = 1000) = Note(title = title, content = content, createdAt = 500, updatedAt = updated)

    @Test fun importNeverOverwritesNewerLocalSilently() {
        val local = note("A", "local", updated = 2000).also(Store::save)
        val olderIncoming = local.copy(content = "vieja", updatedAt = 1500)
        val newerIncoming = local.copy(content = "nueva", updatedAt = 3000)
        assertEquals(1, Store.analyze(listOf(olderIncoming)).conflicts.size)    // la local es más nueva → conflicto
        assertEquals(1, Store.analyze(listOf(newerIncoming)).updates.size)      // la entrante es más nueva → actualizar
        assertTrue(Store.analyze(listOf(Store.data.notes.single())).let { it.new.isEmpty() && it.updates.isEmpty() && it.conflicts.isEmpty() })
    }

    @Test fun keepBothCreatesDistinctFingerprint() {
        val local = note("A", "local").also(Store::save)
        val a = Store.analyze(listOf(local.copy(content = "otra", updatedAt = 10)))
        Store.apply(a, mapOf(local.id to Store.Resolution.KEEP_BOTH))
        assertEquals(2, Store.data.notes.size)
        assertEquals(2, Store.data.notes.map { it.fingerprint }.distinct().size)
    }

    @Test fun reorderOnlyTouchesMovedNote() {
        val (a, b, c) = listOf("a", "b", "c").map { note(it).also(Store::save) }.map { n -> Store.data.note(n.id)!! }
        Store.move(c.id, before = b, after = a) // lista visible por orden desc: c b a → poner c entre b y a
        val o = Store.data.notes.associate { it.title to it.order }
        assertTrue(o["b"]!! > o["c"]!! && o["c"]!! > o["a"]!!)
        assertEquals(a.order, o["a"]!!, 0.0); assertEquals(b.order, o["b"]!!, 0.0)
    }

    @Test fun deletingCategoryMovesNotesToUncategorized() {
        val cat = Category(name = "Temp").also(Store::saveCategory)
        val n = note("x").copy(category = cat.id).also(Store::save)
        Store.deleteCategory(cat.id)
        assertEquals(UNCATEGORIZED, Store.data.note(n.id)!!.category)
        Store.deleteCategory(UNCATEGORIZED)
        assertTrue(Store.data.categories.any { it.id == UNCATEGORIZED })
    }

    @Test fun vitreonFileNeedsTheRightPassword() {
        note("secreta", "hola").also(Store::save)
        val file = Store.exportVitreon("clave-larga")
        assertTrue(Store.isVitreon(file))
        assertNull(Store.openVitreon(file, "otra"))
        val json = Store.openVitreon(file, "clave-larga")!!
        assertEquals("hola", Json.data(JSONObject(json)).notes.single().content)
        assertFalse("hola" in file)
    }

    @Test fun exportIncludesCategoriesAndWebFieldNames() {
        Store.saveCategory(Category(name = "Libros"))
        note("n").also(Store::save)
        val o = JSONObject(Store.exportJson())
        assertEquals("VitreonNotes", o.getString("app"))
        assertTrue(o.getJSONArray("categories").length() >= 7)
        assertTrue(o.getJSONArray("notes").getJSONObject(0).has("isPinned"))
    }

    @Test fun webChecklistLinesBecomeMarkdownTasks() {
        val n = Json.note(JSONObject("""{"id":"1","title":"t","content":"[ ] a\n[x] b","isChecklist":true,"createdAt":1}"""))
        assertEquals("- [ ] a\n- [x] b", n.content)
    }

    @Test fun lockedContentIsCiphertext() {
        val key = Aes.derive("1234", Aes.random(16), 1000)
        val sealed = Locker.seal("secreto", key)
        assertFalse("secreto" in sealed)
        assertEquals("secreto", Locker.open(sealed, key))
        assertNull(Locker.open(sealed, Aes.derive("0000", Aes.random(16), 1000)))
    }

    @Test fun trashPurgeAfter30Days() {
        val n = note("vieja").also(Store::save)
        Store.updateMany(setOf(n.id)) { it.copy(deletedAt = now() - 31L * 86_400_000) }
        Store.purgeTrash()
        assertNull(Store.data.note(n.id))
    }

    @Test fun markdownZipSanitizesNamesAndSkipsTrash() {
        note("a/b:c").also(Store::save)
        note("borrada").also(Store::save).let { Store.trash(setOf(it.id)) }
        val names = java.util.zip.ZipInputStream(Store.exportMarkdownZip().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("Sin categoría/a_b_c.md"), names)
    }

    @Test fun importedCategoryPrefersExplicitThenFolderThenTagAndNeverDuplicates() {
        val imp = listOf(
            com.metalsyntax.vitreon.importers.ImportedNote("a", "x", tags = listOf("t1"), folder = "carpeta", category = "TRABAJO", source = "t"),
            com.metalsyntax.vitreon.importers.ImportedNote("b", "x", tags = listOf("t1"), folder = "CARPETA", source = "t"),
            com.metalsyntax.vitreon.importers.ImportedNote("c", "x", tags = listOf("Proy/web"), source = "t"),
            com.metalsyntax.vitreon.importers.ImportedNote("d", "x", source = "t"),
        )
        val (notes, created) = Store.fromImported(imp)
        assertEquals("work", notes[0].category)                        // «TRABAJO» = categoría «Trabajo» existente (sin distinguir mayúsculas)
        assertEquals(listOf("CARPETA", "Proy"), created.map { it.name }) // la carpeta gana a la etiqueta; la etiqueta anidada aporta su raíz
        assertEquals(UNCATEGORIZED, notes[3].category); assertEquals(notes[1].category, created[0].id) // «carpeta» y «CARPETA» son una sola categoría
        assertEquals(DEFAULT_CATEGORIES.size, Store.data.categories.size) // nada se guardó antes de aplicar
        Store.apply(Store.analyze(notes, created), emptyMap())
        assertEquals(DEFAULT_CATEGORIES.size + 2, Store.data.categories.size)
    }

    @Test fun backupCategoriesWithSameNameAreMergedAndUnknownIdsCreated() {
        val incoming = listOf(note("x").copy(category = "old-id"), note("y", "d").copy(category = "Cocina"))
        val a = Store.analyze(incoming, listOf(Category("old-id", "TRABAJO")))
        assertEquals("work", a.new.first { it.title == "x" }.category)
        assertEquals(listOf("Cocina"), a.categories.map { it.name })
    }
}
