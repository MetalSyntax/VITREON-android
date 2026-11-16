package com.metalsyntax.vitreon.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun now(): Long = System.currentTimeMillis()

enum class AttachmentType { IMAGE, DRAWING, VOICE }

/** Adjunto: los bytes viven cifrados en filesDir/att/<id> (la web los metía en base64 dentro de la nota y todo iba lento). */
data class Attachment(val id: String = newId(), val type: AttachmentType, val mime: String = "image/jpeg")

data class Note(
    val id: String = newId(),
    val title: String = "",
    val content: String = "",
    val category: String = UNCATEGORIZED,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Bloqueada: el contenido real va cifrado con la clave del PIN en [sealed]; [content] queda vacío en disco. */
    val locked: Boolean = false,
    val sealed: String? = null,
    val checklist: Boolean = false,
    val tags: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    /** Orden manual fraccionario: mover entre dos notas = promedio (no reescribe todas como la web). */
    val order: Double = 0.0,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
    /** Huella para no duplicar al importar: se guarda en cada nota (la web solo la ponía al exportar). */
    val importId: String? = null,
    val extra: JSONObject = JSONObject(),
) {
    val trashed get() = deletedAt != null
    val fingerprint get() = importId ?: fingerprintOf(title, content, createdAt)
}

/** title + contenido + creación: estable entre exportaciones (la web usaba solo título+fecha y casi nunca coincidía). */
fun fingerprintOf(title: String, content: String, createdAt: Long): String =
    MessageDigest.getInstance("SHA-256").digest("${title.trim()}|${content.trim()}|$createdAt".toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }

data class Category(val id: String = newId(), val name: String, val color: String = "slate", val icon: String = "folder")

data class Profile(val name: String = "", val email: String = "", val bio: String = "")

const val UNCATEGORIZED = "uncategorized"

val DEFAULT_CATEGORIES = listOf(
    Category("work", "Trabajo", "blue", "work"),
    Category("personal", "Personal", "emerald", "person"),
    Category("ideas", "Ideas", "amber", "lightbulb"),
    Category("travel", "Viajes", "purple", "flight"),
    Category("fitness", "Fitness", "rose", "fitness_center"),
    Category(UNCATEGORIZED, "Sin categoría", "slate", "description"),
)

/** Colores de categoría de la web (Tailwind -500). */
val CATEGORY_COLORS = linkedMapOf(
    "blue" to 0xFF3B82F6, "emerald" to 0xFF10B981, "amber" to 0xFFF59E0B, "purple" to 0xFFA855F7, "rose" to 0xFFF43F5E,
    "slate" to 0xFF64748B, "cyan" to 0xFF06B6D4, "orange" to 0xFFF97316, "pink" to 0xFFEC4899, "lime" to 0xFF84CC16,
    "indigo" to 0xFF6366F1, "violet" to 0xFF8B5CF6, "fuchsia" to 0xFFD946EF, "teal" to 0xFF14B8A6, "red" to 0xFFEF4444, "yellow" to 0xFFEAB308,
)

data class AppData(
    val notes: List<Note> = emptyList(),
    val categories: List<Category> = DEFAULT_CATEGORIES,
    val profile: Profile = Profile(),
) {
    fun note(id: String?) = notes.firstOrNull { it.id == id }
    fun category(id: String) = categories.firstOrNull { it.id == id } ?: categories.first { it.id == UNCATEGORIZED }
    /** Nota por título, para los [[enlaces]] (sin distinguir mayúsculas, como Obsidian). */
    fun byTitle(title: String) = notes.firstOrNull { !it.trashed && it.title.equals(title.trim(), true) }
    fun backlinks(n: Note) = notes.filter { !it.trashed && it.id != n.id && !it.locked && Markdown.links(it.content).any { l -> l.equals(n.title, true) } }
    fun allTags() = notes.filter { !it.trashed }.flatMap { it.tags }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
}

// ================================================================================================ JSON

/**
 * Formato en disco y de exportación = el de Vitreon web ({app:"VitreonNotes", notes:[…]}) con nombres de la web (isPinned…),
 * más `categories` (la web no las exportaba y se perdían al restaurar). Los campos desconocidos se conservan en [Note.extra].
 */
object Json {
    private val KNOWN = setOf("id", "title", "content", "category", "isPinned", "isArchived", "isLocked", "sealed", "isChecklist", "tags",
        "attachments", "order", "createdAt", "updatedAt", "deletedAt", "importId", "images", "drawings", "voiceNotes")

    private fun JSONObject.long(k: String): Long? = when (val v = opt(k)) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: runCatching { java.time.Instant.parse(v).toEpochMilli() }.getOrNull()
        else -> null
    }

    fun note(o: JSONObject): Note {
        val created = o.long("createdAt") ?: now()
        // la web guardaba checklists como líneas «[ ] x»: se pasan a Markdown «- [ ] x» para que se vean como tareas
        var content = o.optString("content")
        if (o.optBoolean("isChecklist")) content = content.lines().joinToString("\n") { l -> if (l.startsWith("[ ]") || l.startsWith("[x]")) "- $l" else l }
        val extra = JSONObject().also { r -> o.keys().forEach { k -> if (k !in KNOWN) r.put(k, o.get(k)) } }
        return Note(
            id = o.optString("id").ifBlank { newId() }, title = o.optString("title"), content = content,
            category = o.optString("category", UNCATEGORIZED).ifBlank { UNCATEGORIZED },
            pinned = o.optBoolean("isPinned"), archived = o.optBoolean("isArchived"), locked = o.optBoolean("isLocked"),
            sealed = o.optString("sealed").ifBlank { null }, checklist = o.optBoolean("isChecklist"),
            tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            attachments = o.optJSONArray("attachments")?.let { a -> (0 until a.length()).mapNotNull { i ->
                val x = a.optJSONObject(i) ?: return@mapNotNull null
                Attachment(x.optString("id").ifBlank { newId() }, runCatching { AttachmentType.valueOf(x.optString("type").uppercase()) }.getOrDefault(AttachmentType.IMAGE),
                    x.optString("mime", "image/jpeg"))
            } }.orEmpty(),
            order = o.optDouble("order", 0.0).takeUnless { it.isNaN() } ?: 0.0,
            createdAt = created, updatedAt = o.long("updatedAt") ?: created, deletedAt = o.long("deletedAt"),
            importId = o.optString("importId").ifBlank { null }, extra = extra,
        )
    }

    fun note(n: Note, withSecrets: Boolean = true) = JSONObject().also { r -> n.extra.keys().forEach { k -> r.put(k, n.extra.get(k)) } }
        .put("id", n.id).put("title", n.title).put("content", n.content).put("category", n.category)
        .put("isPinned", n.pinned).put("isArchived", n.archived).put("isLocked", n.locked).put("isChecklist", n.checklist)
        .put("tags", JSONArray(n.tags)).put("order", n.order).put("createdAt", n.createdAt).put("updatedAt", n.updatedAt)
        .put("importId", n.fingerprint)
        .put("attachments", JSONArray(n.attachments.map { JSONObject().put("id", it.id).put("type", it.type.name.lowercase()).put("mime", it.mime) }))
        .apply { n.deletedAt?.let { put("deletedAt", it) }; if (withSecrets) n.sealed?.let { put("sealed", it) } }

    fun category(o: JSONObject) = Category(o.optString("id").ifBlank { newId() }, o.optString("name"), o.optString("color", "slate"), o.optString("icon", "folder"))
    fun category(c: Category) = JSONObject().put("id", c.id).put("name", c.name).put("color", c.color).put("icon", c.icon)

    fun data(o: JSONObject): AppData {
        val notes = o.optJSONArray("notes")?.let { a -> (0 until a.length()).mapNotNull { runCatching { note(a.getJSONObject(it)) }.getOrNull() } }.orEmpty()
        val cats = o.optJSONArray("categories")?.let { a -> (0 until a.length()).map { category(a.getJSONObject(it)) } }?.takeIf { it.isNotEmpty() } ?: DEFAULT_CATEGORIES
        val p = o.optJSONObject("profile")
        return AppData(notes, cats.let { c -> if (c.none { it.id == UNCATEGORIZED }) c + DEFAULT_CATEGORIES.last() else c },
            Profile(p?.optString("name").orEmpty(), p?.optString("email").orEmpty(), p?.optString("bio").orEmpty()))
    }

    fun data(d: AppData, version: String, withSecrets: Boolean = true): JSONObject = JSONObject()
        .put("app", "VitreonNotes").put("version", version).put("exportedAt", java.time.Instant.now().toString())
        .put("notes", JSONArray(d.notes.map { note(it, withSecrets) }))
        .put("categories", JSONArray(d.categories.map { category(it) }))
        .put("profile", JSONObject().put("name", d.profile.name).put("email", d.profile.email).put("bio", d.profile.bio))
}
