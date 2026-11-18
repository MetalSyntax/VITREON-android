package com.metalsyntax.vitreon.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metalsyntax.vitreon.importers.ImportedNote
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Todos los datos en filesDir/vitreon.json, cifrado completo (Vault) y escritura atómica (tmp + rename) en un solo hilo.
 * Los adjuntos van como archivos cifrados en filesDir/att. El estado es de Compose: cambiar [data] recompone.
 */
object Store {
    var data by mutableStateOf(AppData()); private set
    private lateinit var file: File
    private lateinit var attDir: File
    private val writer = Executors.newSingleThreadExecutor()
    var version = "0.1.0"
    /** Error al leer el archivo: no se sobrescribe (se aparta) para no perder nada. */
    var loadError by mutableStateOf<String?>(null)

    fun init(dir: File) {
        Vault.init(dir)
        file = File(dir, "vitreon.json"); attDir = File(dir, "att").apply { mkdirs() }
        if (file.exists()) {
            runCatching { data = Json.data(JSONObject(String(Vault.open(file.readBytes())))) }.onFailure {
                // archivo ilegible: se aparta en vez de pisarlo con datos vacíos
                file.renameTo(File(dir, "vitreon.unreadable.${now()}")); loadError = it.message
            }
        }
        purgeTrash()
    }

    /** Pruebas JVM: sin Keystore ni Compose runtime de Android. */
    internal fun initForTest(dir: File) { Vault.enabled = false; file = File(dir, "vitreon.json"); attDir = File(dir, "att").apply { mkdirs() }; data = AppData() }

    private fun commit(d: AppData) {
        data = d
        val bytes = Vault.seal(Json.data(d, version).toString().toByteArray())
        writer.execute {
            val tmp = File(file.path + ".tmp"); tmp.writeBytes(bytes); tmp.renameTo(file)
        }
    }

    /** Espera a que termine la escritura pendiente (al salir de la app y en pruebas). */
    fun flush() { writer.submit {}.get() }

    // ------------------------------------------------------------------------------------------ notas

    fun save(n: Note) {
        val exists = data.notes.any { it.id == n.id }
        val note = n.copy(
            // título vacío: primeras palabras del contenido, pero solo para mostrar (no cambia la huella de la nota)
            order = if (exists) n.order else (data.notes.maxOfOrNull { it.order } ?: 0.0) + 1,
            importId = n.importId ?: fingerprintOf(n.title, n.content, n.createdAt),
        )
        commit(data.copy(notes = if (exists) data.notes.map { if (it.id == n.id) note else it } else data.notes + note))
    }

    fun update(id: String, f: (Note) -> Note) { data.note(id)?.let { save(f(it).copy(updatedAt = now())) } }
    fun updateMany(ids: Set<String>, f: (Note) -> Note) {
        val t = now(); commit(data.copy(notes = data.notes.map { if (it.id in ids) f(it).copy(updatedAt = t) else it }))
    }

    fun trash(ids: Set<String>) = updateMany(ids) { it.copy(deletedAt = now(), pinned = false) }
    fun restore(ids: Set<String>) = updateMany(ids) { it.copy(deletedAt = null) }

    fun deleteForever(ids: Set<String>) {
        data.notes.filter { it.id in ids }.flatMap { it.attachments }.forEach { File(attDir, it.id).delete() }
        commit(data.copy(notes = data.notes.filter { it.id !in ids }))
    }

    fun emptyTrash() = deleteForever(data.notes.filter { it.trashed }.map { it.id }.toSet())

    /** La papelera se vacía sola a los 30 días, como la web. */
    fun purgeTrash(days: Int = 30) {
        val limit = now() - days * 86_400_000L
        val old = data.notes.filter { (it.deletedAt ?: Long.MAX_VALUE) < limit }.map { it.id }.toSet()
        if (old.isNotEmpty()) deleteForever(old)
    }

    /**
     * Mueve [id] para que quede entre [before] y [after] (vecinos en la lista visible). Orden fraccionario: solo cambia esa nota
     * (la web reescribía y re-cifraba todas, con choques entre fijadas y el resto).
     */
    fun move(id: String, before: Note?, after: Note?) {
        val o = when {
            before != null && after != null -> (before.order + after.order) / 2
            before != null -> before.order + 1
            after != null -> after.order - 1
            else -> return
        }
        commit(data.copy(notes = data.notes.map { if (it.id == id) it.copy(order = o) else it }))
    }

    // ------------------------------------------------------------------------------------------ bloqueo

    /** Bloquear: el contenido pasa cifrado con la clave del PIN a [Note.sealed]. Requiere la sesión desbloqueada con PIN. */
    fun lock(id: String): Boolean {
        val key = Pin.noteKey ?: return false
        update(id) { it.copy(locked = true, sealed = Locker.seal(it.content, key), content = "") }
        return true
    }

    fun unlock(id: String): Boolean {
        val key = Pin.noteKey ?: return false
        val n = data.note(id) ?: return false
        val plain = n.sealed?.let { Locker.open(it, key) } ?: return false
        update(id) { it.copy(locked = false, sealed = null, content = plain) }
        return true
    }

    /** Contenido de una nota bloqueada para verla (no se guarda descifrado). */
    fun reveal(n: Note): String? = if (!n.locked) n.content else Pin.noteKey?.let { k -> n.sealed?.let { Locker.open(it, k) } }

    /** Guardar cambios de una nota bloqueada abierta: se vuelve a cifrar. */
    fun saveLocked(n: Note, plain: String): Boolean {
        val key = Pin.noteKey ?: return false
        save(n.copy(sealed = Locker.seal(plain, key), content = "", updatedAt = now())); return true
    }

    /** Cambio de PIN: las notas bloqueadas se re-cifran con la clave nueva. */
    fun rekey(old: javax.crypto.SecretKey?, new: javax.crypto.SecretKey) {
        if (old == null) return
        commit(data.copy(notes = data.notes.map { n ->
            if (n.locked && n.sealed != null) Locker.open(n.sealed, old)?.let { n.copy(sealed = Locker.seal(it, new)) } ?: n else n
        }))
    }

    // ------------------------------------------------------------------------------------------ categorías

    fun saveCategory(c: Category) = commit(data.copy(categories =
        if (data.categories.any { it.id == c.id }) data.categories.map { if (it.id == c.id) c else it } else data.categories + c))

    /** Borrar categoría: sus notas pasan a «Sin categoría» (en la web quedaban huérfanas e inalcanzables). */
    fun deleteCategory(id: String) {
        if (id == UNCATEGORIZED) return
        commit(data.copy(categories = data.categories.filter { it.id != id },
            notes = data.notes.map { if (it.category == id) it.copy(category = UNCATEGORIZED) else it }))
    }

    private val NEW_COLORS = listOf("slate", "indigo", "violet", "teal", "orange", "pink", "cyan", "lime")

    /** Categoría por nombre (plugins): existe → la misma; si no, una nueva con color e ícono de carpeta. */
    fun categoryFor(name: String?): String {
        val (ids, created) = resolveCategories(listOf(name))
        created.forEach { saveCategory(it) }
        return ids.single()
    }

    /**
     * Nombres de categoría → ids, sin guardar nada: los que ya existen (por nombre o id, sin distinguir mayúsculas) se reutilizan y
     * los que faltan vuelven en la lista de nuevas (una sola por nombre). Así cancelar una importación no deja categorías vacías.
     */
    fun resolveCategories(names: List<String?>, existing: List<Category> = data.categories): Pair<List<String>, List<Category>> {
        val created = mutableListOf<Category>()
        val ids = names.map { raw ->
            val n = raw?.trim().orEmpty()
            if (n.isEmpty()) UNCATEGORIZED
            else (existing + created).firstOrNull { it.name.equals(n, true) || it.id.equals(n, true) }?.id
                ?: Category(name = n, color = NEW_COLORS[(existing.size + created.size) % NEW_COLORS.size]).also { created += it }.id
        }
        return ids to created
    }

    fun saveProfile(p: Profile) = commit(data.copy(profile = p))

    // ------------------------------------------------------------------------------------------ adjuntos

    fun attachmentFile(a: Attachment) = File(attDir, a.id)
    fun writeAttachment(a: Attachment, bytes: ByteArray) { File(attDir, a.id).writeBytes(Vault.seal(bytes)) }
    fun readAttachment(a: Attachment): ByteArray? = File(attDir, a.id).takeIf { it.exists() }?.let { Vault.open(it.readBytes()) }

    // ------------------------------------------------------------------------------------------ foto de perfil

    private val avatarFile get() = File(attDir.parentFile, "avatar")
    fun writeAvatar(bytes: ByteArray) { avatarFile.writeBytes(Vault.seal(bytes)) }
    fun readAvatar(): ByteArray? = avatarFile.takeIf { it.exists() }?.let { runCatching { Vault.open(it.readBytes()) }.getOrNull() }
    fun clearAvatar() { avatarFile.delete() }

    // ------------------------------------------------------------------------------------------ importar

    data class Conflict(val incoming: Note, val local: Note)
    data class Analysis(val new: List<Note>, val updates: List<Note>, val conflicts: List<Conflict>, val categories: List<Category>)

    /**
     * Clasifica lo que llega: nuevas, actualizaciones (mismo id y la entrante es más nueva) y conflictos (mismo id con la local
     * más nueva, o misma huella con otro id). La web pisaba la local aunque fuera más reciente.
     */
    fun analyze(incomingRaw: List<Note>, categories: List<Category> = emptyList()): Analysis {
        // categorías del respaldo que ya existen con otro id (mismo nombre) → se reutiliza la local; ids desconocidos (la web usaba el nombre) → se crean
        val local = data.categories
        val remap = mutableMapOf<String, String>()
        val missing = categories.filter { c -> local.none { it.id == c.id } }.toMutableList()
        missing.toList().forEach { c -> local.firstOrNull { it.name.equals(c.name, true) }?.let { remap[c.id] = it.id; missing -= c } }
        val known = local.map { it.id }.toSet() + categories.map { it.id }
        incomingRaw.map { it.category }.distinct().filter { it !in known && it != UNCATEGORIZED && it.isNotBlank() }.forEach { id ->
            local.firstOrNull { it.name.equals(id, true) }?.let { remap[id] = it.id } ?: missing.add(Category(id, id, NEW_COLORS[(local.size + missing.size) % NEW_COLORS.size]))
        }
        val incoming = incomingRaw.map { n -> remap[n.category]?.let { n.copy(category = it) } ?: n }
        val byId = data.notes.associateBy { it.id }
        val byPrint = data.notes.associateBy { it.fingerprint }
        val new = mutableListOf<Note>(); val upd = mutableListOf<Note>(); val conf = mutableListOf<Conflict>()
        incoming.distinctBy { it.id }.forEach { n ->
            val same = byId[n.id]
            val twin = byPrint[n.fingerprint]
            when {
                same != null && same.updatedAt == n.updatedAt && same.content == n.content -> Unit // idéntica: nada que hacer
                same != null && n.updatedAt > same.updatedAt -> upd += n
                same != null -> conf += Conflict(n, same)
                twin != null -> if (twin.content == n.content && twin.title == n.title) Unit else conf += Conflict(n, twin)
                else -> new += n
            }
        }
        return Analysis(new, upd, conf, missing)
    }

    enum class Resolution { KEEP_LOCAL, REPLACE, KEEP_BOTH }

    fun apply(a: Analysis, resolutions: Map<String, Resolution>) {
        var notes = data.notes
        val cats = data.categories + a.categories
        val catIds = cats.map { it.id }.toSet()
        fun fix(n: Note) = if (n.category in catIds) n else n.copy(category = UNCATEGORIZED)
        notes = notes.map { l -> a.updates.firstOrNull { it.id == l.id }?.let(::fix) ?: l }
        a.conflicts.forEach { c ->
            when (resolutions[c.incoming.id] ?: Resolution.KEEP_LOCAL) {
                Resolution.KEEP_LOCAL -> Unit
                Resolution.REPLACE -> notes = notes.map { if (it.id == c.local.id) fix(c.incoming).copy(id = c.local.id) else it }
                // conservar ambas: id y huella nuevos (la web dejaba dos notas con la misma huella)
                Resolution.KEEP_BOTH -> notes = notes + fix(c.incoming).copy(id = newId(), importId = null, title = c.incoming.title + " (2)")
            }
        }
        val top = notes.maxOfOrNull { it.order } ?: 0.0
        notes = notes + a.new.mapIndexed { i, n -> fix(n).copy(order = top + a.new.size - i) }
        commit(data.copy(notes = notes, categories = cats))
    }

    /**
     * Notas de Keep / Notion / Obsidian / Markdown → notas de Vitreon + categorías nuevas por crear (van a [analyze] y se guardan al aplicar).
     * Categoría = la explícita (etiqueta de Keep, propiedad de Notion, `category:` de Obsidian) → carpeta de origen → primera etiqueta.
     */
    fun fromImported(list: List<ImportedNote>): Pair<List<Note>, List<Category>> {
        val (ids, created) = resolveCategories(list.map { i -> i.category ?: i.folder ?: i.tags.firstOrNull()?.substringBefore('/') })
        return list.mapIndexed { k, i ->
            Note(title = i.title, content = i.content, category = ids[k], pinned = i.pinned, archived = i.archived, color = i.color,
                tags = i.tags.map { it.lowercase().replace(' ', '-') }.distinct(),
                createdAt = i.createdAt, updatedAt = i.updatedAt, importId = fingerprintOf(i.title, i.content, i.createdAt))
        } to created
    }

    // ------------------------------------------------------------------------------------------ exportar

    /** JSON compatible con Vitreon web (+ categorías). Las notas bloqueadas salen cifradas, nunca con el PIN. */
    fun exportJson(notes: List<Note> = data.notes.filter { !it.trashed }): String =
        Json.data(data.copy(notes = notes), version).toString(2)

    /**
     * .vitreon v2: el JSON cifrado con una contraseña que elige el usuario (PBKDF2-SHA256 210k + sal aleatoria en la cabecera),
     * así se puede abrir en otro teléfono. (La v1 de la web usaba una clave fija del código.)
     */
    fun exportVitreon(password: String, notes: List<Note> = data.notes.filter { !it.trashed }): String {
        val salt = Aes.random(16)
        val sealed = Aes.seal(Aes.derive(password, salt), exportJson(notes).toByteArray())
        return JSONObject().put("__vitreon_encrypted__", true).put("v", 2).put("kdf", "PBKDF2-SHA256").put("iter", 210_000)
            .put("salt", Aes.b64(salt)).put("data", Aes.b64(sealed)).toString()
    }

    /** Abre un .vitreon v2; null si la contraseña es incorrecta. v1 (web) no se puede: su clave estaba fija en el código. */
    fun openVitreon(text: String, password: String): String? = runCatching {
        val o = JSONObject(text)
        require(o.optInt("v") == 2)
        String(Aes.open(Aes.derive(password, Aes.unb64(o.getString("salt")), o.optInt("iter", 210_000)), Aes.unb64(o.getString("data"))))
    }.getOrNull()

    fun isVitreon(text: String) = runCatching { JSONObject(text).optBoolean("__vitreon_encrypted__") }.getOrDefault(false)

    /** Todas las notas como .md (con frontmatter) en un .zip, una carpeta por categoría; sin papelera ni bloqueadas. */
    fun exportMarkdownZip(notes: List<Note> = data.notes.filter { !it.trashed && !it.locked }): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            val used = mutableSetOf<String>()
            notes.forEach { n ->
                val folder = safeName(data.category(n.category).name)
                var name = "$folder/${safeName(n.title.ifBlank { "Nota" })}.md"
                var k = 2; while (!used.add(name)) name = "$folder/${safeName(n.title.ifBlank { "Nota" })} ${k++}.md"
                z.putNextEntry(ZipEntry(name))
                val front = "---\ntags: [${n.tags.joinToString(", ")}]\ncreated: ${java.time.Instant.ofEpochMilli(n.createdAt)}\n---\n"
                z.write((front + n.content).toByteArray()); z.closeEntry()
            }
        }
        return bo.toByteArray()
    }

    fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(80).ifBlank { "Nota" }
}
