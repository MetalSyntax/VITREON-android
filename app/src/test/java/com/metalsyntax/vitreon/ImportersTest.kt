package com.metalsyntax.vitreon

import com.metalsyntax.vitreon.importers.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportersTest {
    private fun f(path: String, text: String) = SourceFile(path, text.toByteArray())

    @Test fun keepTakeoutWithChecklistLabelsAndTrash() {
        val files = listOf(
            f("Takeout/Keep/Compras.json", """{"title":"Compras","listContent":[{"text":"Pan","isChecked":true},{"text":"Leche","isChecked":false}],
                "labels":[{"name":"Casa"}],"isPinned":true,"isArchived":false,"isTrashed":false,"createdTimestampUsec":1700000000000000,"userEditedTimestampUsec":1700000500000000}"""),
            f("Takeout/Keep/Vieja.json", """{"title":"x","textContent":"y","isTrashed":true,"userEditedTimestampUsec":1}"""),
            f("Takeout/Keep/Labels.txt", "Casa"),
        )
        assertEquals(ImportSource.KEEP, detect(files))
        val n = KeepImporter.read(files).single()
        assertEquals("Compras", n.title)
        assertEquals("- [x] Pan\n- [ ] Leche", n.content)
        assertEquals(listOf("Casa"), n.tags); assertEquals("Casa", n.folder)
        assertTrue(n.pinned); assertEquals(1_700_000_000_000L, n.createdAt)
    }

    @Test fun notionStripsIdsAndRewritesLinks() {
        val files = listOf(f("Export/Proyectos 0123456789abcdef0123456789abcdef/Plan 89abcdef0123456789abcdef01234567.md",
            "# Plan\n\nTags: trabajo, q4\n\nVer [Notas](Notas%20fedcba9876543210fedcba9876543210.md)"))
        assertEquals(ImportSource.NOTION, detect(files))
        val n = NotionImporter.read(files).single()
        assertEquals("Plan", n.title)
        assertEquals(listOf("trabajo", "q4"), n.tags)
        assertEquals("Proyectos", n.folder)
        assertTrue("[[Notas]]" in n.content)
    }

    @Test fun obsidianVaultFrontmatterTagsAndFolders() {
        val files = listOf(
            f("MiBoveda/.obsidian/app.json", "{}"),
            f("MiBoveda/Diario/2026-11-16.md", "---\ntags:\n  - diario\ncreated: 2026-11-16\n---\nHoy #idea con [[Proyecto]]"),
            f("MiBoveda/Proyecto.md", "Raíz"),
        )
        assertEquals(ImportSource.OBSIDIAN, detect(files))
        val notes = ObsidianImporter.read(files)
        assertEquals(2, notes.size)
        val d = notes.first { it.title == "2026-11-16" }
        assertEquals(listOf("diario", "idea"), d.tags)
        assertEquals("Diario", d.folder)
        assertEquals(null, notes.first { it.title == "Proyecto" }.folder) // la carpeta de la bóveda no es categoría
    }

    @Test fun unzipSkipsSlipAndMacJunk() {
        val bytes = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                for ((n, t) in listOf("ok.md" to "a", "../evil.md" to "b", "__MACOSX/x" to "c", "inner.zip" to "")) {
                    z.putNextEntry(ZipEntry(n))
                    if (n == "inner.zip") z.write(ByteArrayOutputStream().also { ib -> ZipOutputStream(ib).use { iz -> iz.putNextEntry(ZipEntry("deep.md")); iz.write("d".toByteArray()) } }.toByteArray())
                    else z.write(t.toByteArray())
                    z.closeEntry()
                }
            }
        }.toByteArray()
        assertEquals(listOf("ok.md", "inner/deep.md"), unzip(bytes.inputStream()).map { it.path })
    }

    @Test fun tagsIgnoreCodeAndHeadings() {
        assertEquals(listOf("real", "a/b"), Tags.find("# Encabezado\n#real y `#no` y #a/b y #123"))
    }
}
