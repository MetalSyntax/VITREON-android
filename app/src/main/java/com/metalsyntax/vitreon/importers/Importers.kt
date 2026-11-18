package com.metalsyntax.vitreon.importers

import org.json.JSONObject
import java.io.InputStream
import java.time.Instant
import java.util.zip.ZipInputStream

/**
 * Nota leída de otra app, antes de convertirla en [com.metalsyntax.vitreon.data.Note]. Todo queda en Markdown:
 * las listas de Keep pasan a `- [ ]` / `- [x]`, las etiquetas a [tags] y la carpeta de origen a [folder] (→ categoría).
 */
data class ImportedNote(
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val folder: String? = null,
    /** Categoría explícita de la app de origen (propiedad «Category», `category:` del frontmatter, etiqueta de Keep); gana sobre [folder]. */
    val category: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val source: String,
    val color: String? = null,
)

/** Un archivo dentro de lo que el usuario eligió (zip, carpeta o archivo suelto): ruta relativa + bytes. */
class SourceFile(val path: String, val bytes: ByteArray) {
    val name get() = path.substringAfterLast('/')
    val text get() = bytes.toString(Charsets.UTF_8).removePrefix("﻿")
}

/** Descomprime un zip en memoria (exportaciones de Takeout y de Notion). Ignora carpetas y basura de macOS. */
fun unzip(input: InputStream, maxBytes: Long = 200L * 1024 * 1024): List<SourceFile> {
    val out = mutableListOf<SourceFile>()
    var total = 0L
    ZipInputStream(input.buffered()).use { z ->
        while (true) {
            val e = z.nextEntry ?: break
            val p = e.name.replace('\\', '/')
            // zip slip: nada de rutas que suban de carpeta; tampoco __MACOSX ni ocultos
            if (e.isDirectory || ".." in p.split('/') || p.startsWith("__MACOSX/") || p.substringAfterLast('/').startsWith("._")) continue
            val b = z.readBytes()
            total += b.size
            require(total <= maxBytes) { "zip demasiado grande" }
            // Notion mete zips dentro del zip (exportaciones grandes): se abren también
            if (p.endsWith(".zip", true)) out += unzip(b.inputStream(), maxBytes - total).map { SourceFile(p.removeSuffix(".zip") + "/" + it.path, it.bytes) }
            else out += SourceFile(p, b)
        }
    }
    return out
}

// ============================================================================================ Google Keep (Takeout)

/**
 * Google Takeout → Keep: un .json por nota (title, textContent, listContent, labels, isPinned, isArchived, isTrashed,
 * createdTimestampUsec, userEditedTimestampUsec). Las notas en la papelera no se importan.
 */
object KeepImporter {
    /** Color de Keep (DEFAULT, RED, … GRAY) → clave de [com.metalsyntax.vitreon.data.NOTE_COLORS]; DEFAULT y desconocidos = sin color. */
    fun color(keep: String): String? = keep.lowercase().takeIf { it in com.metalsyntax.vitreon.data.NOTE_COLORS }

    fun canRead(files: List<SourceFile>) = files.any { it.path.endsWith(".json", true) && it.text.contains("\"userEditedTimestampUsec\"") }

    fun read(files: List<SourceFile>): List<ImportedNote> = files.filter { it.path.endsWith(".json", true) }.mapNotNull { f ->
        val o = runCatching { JSONObject(f.text) }.getOrNull() ?: return@mapNotNull null
        if (!o.has("userEditedTimestampUsec") || o.optBoolean("isTrashed")) return@mapNotNull null
        val body = StringBuilder(o.optString("textContent"))
        o.optJSONArray("listContent")?.let { list ->
            if (body.isNotEmpty()) body.append("\n\n")
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                body.append(if (item.optBoolean("isChecked")) "- [x] " else "- [ ] ").append(item.optString("text").trim()).append('\n')
            }
        }
        // las URL anotadas de Keep (annotations) se agregan al final como enlaces
        o.optJSONArray("annotations")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).optString("url").takeIf { it.isNotBlank() }?.let { u ->
                body.append("\n[").append(a.getJSONObject(i).optString("title", u).ifBlank { u }).append("](").append(u).append(")")
            }
        }
        val labels = o.optJSONArray("labels")?.let { l -> (0 until l.length()).map { l.getJSONObject(it).optString("name") } }.orEmpty().filter { it.isNotBlank() }
        val created = o.optLong("createdTimestampUsec", 0) / 1000
        val edited = o.optLong("userEditedTimestampUsec", 0) / 1000
        ImportedNote(
            title = o.optString("title").ifBlank { f.name.removeSuffix(".json") },
            content = body.toString().trimEnd(),
            tags = labels,
            category = labels.firstOrNull(),
            pinned = o.optBoolean("isPinned"), archived = o.optBoolean("isArchived"),
            createdAt = if (created > 0) created else edited, updatedAt = if (edited > 0) edited else created,
            source = "keep", color = color(o.optString("color")),
        )
    }
}

// ============================================================================================ Notion

/**
 * Exportación de Notion «Markdown & CSV»: páginas como «Título 32hex.md» dentro de carpetas con el mismo nombre.
 * Se quita el id de 32 caracteres de títulos, carpetas y enlaces internos; las bases de datos (.csv) se ignoran porque
 * sus filas ya vienen como páginas .md. Las propiedades («Tags: a, b» al inicio) se convierten en etiquetas.
 */
object NotionImporter {
    private val ID = Regex("""\s+[0-9a-f]{32}(?=$|\.|/)""")
    fun clean(s: String) = s.replace("%20", " ").replace(ID, "")

    private val TAG_KEYS = setOf("tags", "etiquetas", "labels", "tag", "etiqueta")
    private val CATEGORY_KEYS = setOf("category", "categoría", "categoria", "categories", "categorías", "categorias", "type", "tipo", "área", "area")
    private val DATE_KEYS = setOf("created", "created time", "creado", "fecha de creación", "date", "fecha")

    fun canRead(files: List<SourceFile>) = files.any { it.path.endsWith(".md", true) && ID.containsMatchIn(it.path) }

    fun read(files: List<SourceFile>): List<ImportedNote> = files.filter { it.path.endsWith(".md", true) }.map { f ->
        val path = clean(f.path.removeSuffix(".md"))
        var text = f.text
        // título = primer «# » (Notion siempre lo escribe), si no el nombre del archivo
        val lines = text.lines()
        val h1 = lines.firstOrNull { it.isNotBlank() }?.takeIf { it.startsWith("# ") }
        val title = h1?.removePrefix("# ")?.trim() ?: path.substringAfterLast('/')
        if (h1 != null) text = text.substringAfter(h1).trimStart('\n')
        // propiedades: bloque de «Clave: valor» al principio
        val props = text.lines().takeWhile { it.isNotBlank() && Regex("^[\\p{L} ]{1,30}: .+").matches(it) }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val tags = props.filterKeys { it in TAG_KEYS }.values.flatMap { v -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        val category = props.filterKeys { it in CATEGORY_KEYS }.values.firstNotNullOfOrNull { v -> v.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() } }
        val created = props.filterKeys { it in DATE_KEYS }.values.firstNotNullOfOrNull(::parseDate)
        // enlaces internos [Texto](Otra%20pagina%20<id>.md) → [[Otra pagina]]
        text = Regex("""\[([^\]]+)]\(([^)]+?)\.md\)""").replace(text) { m -> "[[" + clean(m.groupValues[2]).substringAfterLast('/') + "]]" }
        val folder = path.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { null }
        ImportedNote(title, text.trim(), tags, folder, category, createdAt = created ?: System.currentTimeMillis(), source = "notion")
    }
}

// ============================================================================================ Obsidian

/**
 * Bóveda de Obsidian (carpeta o zip): cada .md es una nota; el frontmatter YAML aporta tags/aliases/created; las #etiquetas
 * del texto también cuentan. La carpeta pasa a categoría. `.obsidian/` no son notas (de ahí se leen los plugins aparte).
 */
object ObsidianImporter {
    fun canRead(files: List<SourceFile>) = files.any { it.path.contains(".obsidian/") } ||
        files.any { it.path.endsWith(".md", true) && ("[[" in it.text || it.text.startsWith("---")) }

    fun read(files: List<SourceFile>): List<ImportedNote> {
        val notes = files.filter { it.path.endsWith(".md", true) && "/.obsidian/" !in "/" + it.path && "/.trash/" !in "/" + it.path }
        // la carpeta raíz común (el nombre de la bóveda) no es una categoría
        val root = notes.map { it.path.substringBefore('/', "") }.distinct().singleOrNull()?.takeIf { r -> notes.all { it.path.startsWith("$r/") } }
        return notes.map { f ->
            val rel = root?.let { f.path.removePrefix("$it/") } ?: f.path
            val (front, body) = Frontmatter.split(f.text)
            val tags = (Frontmatter.list(front, "tags") + Frontmatter.list(front, "tag") + Tags.find(body)).map { it.removePrefix("#") }.distinct()
            val created = (Frontmatter.value(front, "created") ?: Frontmatter.value(front, "date"))?.let(::parseDate)
            val updated = (Frontmatter.value(front, "updated") ?: Frontmatter.value(front, "modified"))?.let(::parseDate)
            val category = listOf("category", "categories", "categoría", "categoria").firstNotNullOfOrNull { k ->
                Frontmatter.value(front, k)?.takeIf { !it.startsWith("[") && !it.contains(',') } ?: Frontmatter.list(front, k).firstOrNull() }
            ImportedNote(
                title = rel.substringAfterLast('/').removeSuffix(".md"),
                // el frontmatter se conserva: es parte de la nota en Obsidian y lo usan los plugins
                content = f.text.trimEnd(),
                tags = tags, folder = rel.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { null },
                category = category,
                pinned = Frontmatter.value(front, "pinned").toBool(), archived = Frontmatter.value(front, "archived").toBool(),
                createdAt = created ?: System.currentTimeMillis(), updatedAt = updated ?: created ?: System.currentTimeMillis(), source = "obsidian",
            )
        }
    }

    private fun String?.toBool() = this?.lowercase() in setOf("true", "yes", "si", "sí")
}

/** ISO instant o fecha `2026-11-16…`; null si no se entiende. */
fun parseDate(s: String): Long? = runCatching { Instant.parse(s.trim()).toEpochMilli() }.getOrNull()
    ?: runCatching { java.time.LocalDate.parse(s.trim().take(10)).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

/** Frontmatter YAML mínimo (lo que usan las notas: escalares y listas `[a, b]` o con guiones). */
object Frontmatter {
    fun split(text: String): Pair<String, String> {
        if (!text.startsWith("---")) return "" to text
        val end = text.indexOf("\n---", 3)
        if (end < 0) return "" to text
        return text.substring(3, end).trim('\n', '\r') to text.substring(end + 4).trimStart('\n', '\r')
    }

    fun value(front: String, key: String): String? = front.lines().firstOrNull { it.startsWith("$key:") }
        ?.substringAfter(':')?.trim()?.trim('"', '\'')?.ifBlank { null }

    fun list(front: String, key: String): List<String> {
        val lines = front.lines()
        val i = lines.indexOfFirst { it.startsWith("$key:") }
        if (i < 0) return emptyList()
        val inline = lines[i].substringAfter(':').trim()
        if (inline.startsWith("[")) return inline.trim('[', ']').split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
        if (inline.isNotEmpty()) return inline.split(',', ' ').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
        return lines.drop(i + 1).takeWhile { it.trimStart().startsWith("- ") }.map { it.trimStart().removePrefix("- ").trim().trim('"', '\'') }
    }
}

/** #etiquetas del texto, como Obsidian: letras, números, _, -, / (anidadas); no números solos ni encabezados. */
object Tags {
    private val TAG = Regex("""(?<![\p{L}\p{N}_/#&])#([\p{L}_][\p{L}\p{N}_/\-]*)""")
    fun find(text: String): List<String> {
        val clean = text.lines().filterNot { it.trimStart().startsWith("```") }.joinToString("\n").replace(Regex("`[^`]*`"), "")
        return TAG.findAll(clean).map { it.groupValues[1] }.distinct().toList()
    }
}

/** Detecta el formato de lo elegido. */
enum class ImportSource { KEEP, NOTION, OBSIDIAN, VITREON, MARKDOWN }

fun detect(files: List<SourceFile>): ImportSource = when {
    files.any { it.name.endsWith(".vitreon", true) } -> ImportSource.VITREON
    KeepImporter.canRead(files) -> ImportSource.KEEP
    NotionImporter.canRead(files) -> ImportSource.NOTION
    ObsidianImporter.canRead(files) -> ImportSource.OBSIDIAN
    else -> ImportSource.MARKDOWN
}

/** .md / .txt sueltos (cualquier otra app): el nombre del archivo es el título. */
fun readPlain(files: List<SourceFile>) = files.filter { it.path.endsWith(".md", true) || it.path.endsWith(".txt", true) }.map {
    ImportedNote(it.name.substringBeforeLast('.'), it.text.trimEnd(), Tags.find(it.text), source = "markdown")
}
