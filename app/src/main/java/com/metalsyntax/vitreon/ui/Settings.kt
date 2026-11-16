package com.metalsyntax.vitreon.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.metalsyntax.vitreon.BuildConfig
import com.metalsyntax.vitreon.data.*
import com.metalsyntax.vitreon.importers.*
import com.metalsyntax.vitreon.plugins.Plugins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Lee lo elegido con el selector del sistema: archivos sueltos (zip se descomprime) o una carpeta entera (bóveda). */
object Picker {
    fun files(ctx: android.content.Context, uris: List<Uri>): List<SourceFile> = uris.flatMap { u ->
        val name = DocumentFile.fromSingleUri(ctx, u)?.name ?: u.lastPathSegment.orEmpty()
        val bytes = ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() }
        if (name.endsWith(".zip", true)) unzip(bytes.inputStream()) else listOf(SourceFile(name, bytes))
    }

    fun folder(ctx: android.content.Context, tree: Uri): List<SourceFile> {
        val out = mutableListOf<SourceFile>()
        fun walk(d: DocumentFile, prefix: String) {
            d.listFiles().forEach { f ->
                val n = f.name ?: return@forEach
                if (f.isDirectory) { if (n != ".trash" && n != ".git") walk(f, "$prefix$n/") }
                // solo lo que sirve: notas y archivos de plugins (no las imágenes de la bóveda)
                else if (n.endsWith(".md", true) || n.endsWith(".json", true) || n.endsWith(".js", true) || n.endsWith(".css", true) || n.endsWith(".txt", true))
                    ctx.contentResolver.openInputStream(f.uri)?.use { out += SourceFile(prefix + n, it.readBytes()) }
            }
        }
        DocumentFile.fromTreeUri(ctx, tree)?.let { root -> walk(root, (root.name ?: "vault") + "/") }
        return out
    }
}

@Composable
fun SettingsScreen(snack: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var setPin by remember { mutableStateOf(false) }
    var removePin by remember { mutableStateOf(false) }
    var exportPass by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<SourceFile>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var deleteAll by remember { mutableStateOf(false) }
    var plugins by remember { mutableStateOf(false) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        Pin.external = false
        val b = exportBytes; exportBytes = null
        if (uri != null && b != null) runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(b) } }
            .onSuccess { snack(L("Exportado", "Exported", "Exportado")) }.onFailure { snack(L("No se pudo guardar", "Couldn't save", "Não foi possível salvar")) }
    }
    fun export(bytes: ByteArray, name: String) { exportBytes = bytes; Pin.external = true; saver.launch(name) }
    val stamp = java.time.LocalDate.now().toString()

    val openFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        Pin.external = false
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            pending = withContext(Dispatchers.IO) { runCatching { Picker.files(ctx, uris) }.getOrNull() }
            busy = false
            if (pending == null) snack(L("No se pudo leer el archivo", "Couldn't read the file", "Não foi possível ler o arquivo"))
        }
    }
    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        Pin.external = false
        if (uri != null) scope.launch { busy = true; pending = withContext(Dispatchers.IO) { runCatching { Picker.folder(ctx, uri) }.getOrNull() }; busy = false }
    }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)) {
        item { Header(L("Ajustes", "Settings", "Ajustes")) }
        item {
            Group(L("Apariencia", "Appearance", "Aparência"), Icons.Outlined.Palette) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mode.entries.forEach { m ->
                        Chip(when (m) { Mode.LIGHT -> L("Claro", "Light", "Claro"); Mode.DARK -> L("Oscuro", "Dark", "Escuro"); Mode.BLACK -> L("Negro", "Black", "Preto") },
                            Prefs.mode == m, icon = when (m) { Mode.LIGHT -> Icons.Outlined.LightMode; Mode.DARK -> Icons.Outlined.DarkMode; Mode.BLACK -> Icons.Outlined.Contrast }) { Prefs.applyMode(m) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Lang.entries.forEach { l -> Chip(when (l) { Lang.ES -> "Español"; Lang.EN -> "English"; Lang.PT -> "Português" }, Prefs.lang == l) { Prefs.applyLang(l) } }
                }
            }
        }
        item {
            Group(L("Seguridad", "Security", "Segurança"), Icons.Outlined.Shield) {
                SettingRow(if (Pin.enabled) L("Cambiar PIN", "Change PIN", "Alterar PIN") else L("Crear PIN", "Create PIN", "Criar PIN"),
                    L("Bloquea la app y cifra las notas bloqueadas", "Locks the app and encrypts locked notes", "Bloqueia o app e criptografa as notas bloqueadas"),
                    Icons.Outlined.Pin, onClick = { setPin = true }) { Chevron() }
                if (Pin.enabled) {
                    if (biometricAvailable(ctx)) SettingRow(L("Huella o rostro", "Fingerprint or face", "Digital ou rosto"), L("Para abrir la app; las notas bloqueadas siguen pidiendo PIN",
                        "To open the app; locked notes still ask for the PIN", "Para abrir o app; notas bloqueadas ainda pedem o PIN"), Icons.Outlined.Fingerprint) { Toggle(Pin.biometric, Pin::setBiometric) }
                    SettingRow(L("Bloquear al salir", "Lock when leaving", "Bloquear ao sair"), null, Icons.Outlined.LockClock) { Toggle(Pin.lockOnExit, Pin::setLockOnExit) }
                    SettingRow(L("Quitar PIN", "Remove PIN", "Remover PIN"), null, Icons.Outlined.LockOpen, Danger, onClick = { removePin = true }) { Chevron() }
                }
                Text("ⓘ " + L("Todo se guarda cifrado en el teléfono con una clave propia del dispositivo.", "Everything is stored encrypted on the phone with a device-bound key.",
                    "Tudo é salvo criptografado no telefone com uma chave do dispositivo."), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        item {
            Group(L("Importar", "Import", "Importar"), Icons.Outlined.Download) {
                SettingRow(L("Archivos", "Files", "Arquivos"), L("Google Keep (zip de Takeout), Notion (zip), Vitreon (.json/.vitreon) o .md",
                    "Google Keep (Takeout zip), Notion (zip), Vitreon (.json/.vitreon) or .md", "Google Keep (zip do Takeout), Notion (zip), Vitreon (.json/.vitreon) ou .md"),
                    Icons.Outlined.UploadFile, onClick = { Pin.external = true; openFiles.launch(arrayOf("*/*")) }) { Chevron() }
                SettingRow(L("Bóveda de Obsidian", "Obsidian vault", "Cofre do Obsidian"), L("Elige la carpeta: notas, etiquetas, carpetas y plugins",
                    "Pick the folder: notes, tags, folders and plugins", "Escolha a pasta: notas, tags, pastas e plugins"),
                    Icons.Outlined.FolderOpen, Purple, onClick = { Pin.external = true; openFolder.launch(null) }) { Chevron() }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Brand)
            }
        }
        item {
            Group(L("Exportar", "Export", "Exportar"), Icons.Outlined.Upload) {
                SettingRow(L("Respaldo .vitreon cifrado", "Encrypted .vitreon backup", "Backup .vitreon criptografado"), L("Con contraseña; se abre en otro teléfono",
                    "Password protected; opens on another phone", "Com senha; abre em outro telefone"), Icons.Outlined.EnhancedEncryption, onClick = { exportPass = true }) { Chevron() }
                SettingRow("JSON", L("Compatible con Vitreon web", "Compatible with Vitreon web", "Compatível com Vitreon web"), Icons.Outlined.DataObject, onClick = {
                    export(Store.exportJson().toByteArray(), "vitreon_$stamp.json") }) { Chevron() }
                SettingRow(L("Markdown (.zip)", "Markdown (.zip)", "Markdown (.zip)"), L("Una carpeta por categoría; sirve para Obsidian", "One folder per category; works with Obsidian",
                    "Uma pasta por categoria; serve para o Obsidian"), Icons.Outlined.FolderZip, onClick = { export(Store.exportMarkdownZip(), "vitreon_$stamp.zip") }) { Chevron() }
            }
        }
        item {
            Group(L("Plugins de Obsidian", "Obsidian plugins", "Plugins do Obsidian"), Icons.Outlined.Extension, Purple) {
                SettingRow(L("Plugins instalados", "Installed plugins", "Plugins instalados"), "${Plugins.installed.size} · ${Plugins.enabled.size} " + L("activos", "on", "ativos"),
                    Icons.Outlined.Extension, Purple, onClick = { plugins = true }) { Chevron() }
            }
        }
        item {
            Group(L("Datos", "Data", "Dados"), Icons.Outlined.Storage, Danger) {
                val d = Store.data
                Text("${d.notes.count { !it.trashed }} ${L("notas", "notes", "notas")} · ${d.categories.size} ${L("categorías", "categories", "categorias")} · ${d.allTags().size} ${L("etiquetas", "tags", "tags")}",
                    style = MaterialTheme.typography.bodyMedium, color = Muted)
                SettingRow(L("Borrar todos los datos", "Delete all data", "Apagar todos os dados"), null, Icons.Outlined.DeleteForever, Danger, Danger, onClick = { deleteAll = true }) { Chevron() }
                Text("Vitreon ${BuildConfig.VERSION_NAME}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, color = Muted)
            }
        }
    }

    if (setPin) SetPinDialog({ setPin = false }) { setPin = false; snack(L("PIN guardado", "PIN saved", "PIN salvo")) }
    if (removePin) {
        val hasLocked = Store.data.notes.any { it.locked }
        Confirm(L("¿Quitar el PIN?", "Remove the PIN?", "Remover o PIN?"),
            if (hasLocked) L("Primero desbloquea tus notas bloqueadas: sin PIN no se podrían abrir.", "First unlock your locked notes: without a PIN they couldn't be opened.",
                "Primeiro desbloqueie suas notas: sem PIN não poderiam ser abertas.") else "",
            L("Quitar", "Remove", "Remover"), danger = true, onDismiss = { removePin = false }) {
            if (!hasLocked) { ctx.getSharedPreferences("security", 0).edit().clear().apply(); Pin.init(ctx) }
            removePin = false
        }
    }
    if (exportPass) {
        var p1 by remember { mutableStateOf("") }; var p2 by remember { mutableStateOf("") }
        Confirm(L("Contraseña del respaldo", "Backup password", "Senha do backup"), L("Mínimo 8 caracteres. Sin ella no se puede abrir: guárdala bien.",
            "At least 8 characters. Without it the file can't be opened.", "Mínimo 8 caracteres. Sem ela o arquivo não abre."), L("Exportar", "Export", "Exportar"),
            onDismiss = { exportPass = false }, extra = {
                Field(p1, { p1 = it }, L("Contraseña", "Password", "Senha"), password = true)
                Field(p2, { p2 = it }, L("Repetir", "Repeat", "Repetir"), password = true)
            }) {
            if (p1.length >= 8 && p1 == p2) { exportPass = false; scope.launch { export(withContext(Dispatchers.Default) { Store.exportVitreon(p1) }.toByteArray(), "vitreon_$stamp.vitreon") } }
            else snack(L("Las contraseñas no coinciden o son cortas", "Passwords don't match or are too short", "Senhas não coincidem ou são curtas"))
        }
    }
    if (deleteAll) Confirm(L("¿Borrar todo?", "Delete everything?", "Apagar tudo?"), L("Notas, categorías, adjuntos y plugins. No se puede deshacer.",
        "Notes, categories, attachments and plugins. This can't be undone.", "Notas, categorias, anexos e plugins. Não pode ser desfeito."), L("Borrar todo", "Delete all", "Apagar tudo"),
        danger = true, onDismiss = { deleteAll = false }) {
        Store.deleteForever(Store.data.notes.map { it.id }.toSet())
        Store.data.categories.filter { it.id != UNCATEGORIZED && DEFAULT_CATEGORIES.none { d -> d.id == it.id } }.forEach { Store.deleteCategory(it.id) }
        Plugins.installed.forEach { Plugins.uninstall(it.id) }
        deleteAll = false; snack(L("Datos borrados", "Data deleted", "Dados apagados"))
    }
    pending?.let { files -> ImportFlow(files, snack) { pending = null } }
    if (plugins) PluginsSheet { plugins = false }
}

/**
 * Importar: detecta el formato, pide contraseña si es .vitreon, ofrece instalar los plugins de la bóveda y resuelve
 * conflictos (mantener local / reemplazar / ambas, con «aplicar a todos»), como ConflictResolutionModal.tsx.
 */
@Composable
fun ImportFlow(files: List<SourceFile>, snack: (String) -> Unit, onDone: () -> Unit) {
    val src = remember(files) { detect(files) }
    var password by remember { mutableStateOf("") }
    var needPassword by remember { mutableStateOf(src == ImportSource.VITREON) }
    var analysis by remember { mutableStateOf<Store.Analysis?>(null) }
    var res by remember { mutableStateOf(emptyMap<String, Store.Resolution>()) }
    val pluginFiles = remember(files) { files.filter { "/.obsidian/plugins/" in "/" + it.path } }
    var installPlugins by remember { mutableStateOf(pluginFiles.any { it.name == "manifest.json" }) }

    fun analyzeJson(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (o == null || !o.has("notes")) { snack(L("Formato no reconocido", "Unknown format", "Formato não reconhecido")); onDone(); return }
        val d = Json.data(o)
        analysis = Store.analyze(d.notes, o.optJSONArray("categories")?.let { d.categories }.orEmpty())
    }
    LaunchedEffect(src) {
        if (src == ImportSource.VITREON) return@LaunchedEffect
        val json = files.firstOrNull { it.name.endsWith(".json", true) && it.text.contains("\"VitreonNotes\"") }
        when {
            json != null -> analyzeJson(json.text)
            src == ImportSource.KEEP -> analysis = Store.analyze(Store.fromImported(KeepImporter.read(files)))
            src == ImportSource.NOTION -> analysis = Store.analyze(Store.fromImported(NotionImporter.read(files)))
            src == ImportSource.OBSIDIAN -> analysis = Store.analyze(Store.fromImported(ObsidianImporter.read(files)))
            else -> analysis = Store.analyze(Store.fromImported(readPlain(files)))
        }
    }

    if (needPassword) {
        val file = files.first { it.name.endsWith(".vitreon", true) }
        Confirm(L("Respaldo cifrado", "Encrypted backup", "Backup criptografado"), L("Escribe la contraseña con la que se exportó.", "Enter the password it was exported with.",
            "Digite a senha usada na exportação."), L("Abrir", "Open", "Abrir"), onDismiss = onDone, extra = { Field(password, { password = it }, L("Contraseña", "Password", "Senha"), password = true) }) {
            val text = Store.openVitreon(file.text, password)
            when {
                text != null -> { needPassword = false; analyzeJson(text) }
                runCatching { JSONObject(file.text).optInt("v") }.getOrDefault(0) != 2 -> {
                    snack(L("Es un .vitreon de la web (clave fija): exporta como JSON desde la web", "It's a web .vitreon (fixed key): export JSON from the web", "É um .vitreon da web: exporte JSON pela web"))
                    onDone()
                }
                else -> snack(L("Contraseña incorrecta", "Wrong password", "Senha incorreta"))
            }
        }
        return
    }
    if (installPlugins) {
        val ids = pluginFiles.filter { it.name == "manifest.json" }.mapNotNull { runCatching { JSONObject(it.text).optString("name") }.getOrNull() }
        Confirm(L("Plugins encontrados", "Plugins found", "Plugins encontrados"), ids.joinToString(", ") + "\n\n" + L(
            "Se instalan desactivados. Corren aislados (sin internet) y solo ven tus notas no bloqueadas.",
            "They're installed disabled. They run sandboxed (no internet) and only see your unlocked notes.",
            "São instalados desativados. Rodam isolados (sem internet) e só veem notas desbloqueadas."), L("Instalar", "Install", "Instalar"),
            onDismiss = { installPlugins = false }) { Plugins.install(pluginFiles); installPlugins = false }
        return
    }
    val a = analysis ?: return
    if (a.conflicts.isEmpty()) {
        LaunchedEffect(a) {
            Store.apply(a, emptyMap())
            snack(L("Importadas ${a.new.size}, actualizadas ${a.updates.size}", "Imported ${a.new.size}, updated ${a.updates.size}", "Importadas ${a.new.size}, atualizadas ${a.updates.size}"))
            onDone()
        }
        return
    }
    Sheet(onDone, L("Conflictos", "Conflicts", "Conflitos")) {
        Text(L("${a.new.size} nuevas · ${a.updates.size} actualizadas · ${a.conflicts.size} en conflicto", "${a.new.size} new · ${a.updates.size} updated · ${a.conflicts.size} in conflict",
            "${a.new.size} novas · ${a.updates.size} atualizadas · ${a.conflicts.size} em conflito"), style = MaterialTheme.typography.bodyMedium, color = Muted)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Store.Resolution.entries.forEach { r -> Chip(resLabel(r) + " " + L("(todas)", "(all)", "(todas)"), false) { res = a.conflicts.associate { it.incoming.id to r } } }
        }
        Spacer(Modifier.height(10.dp))
        a.conflicts.forEach { c ->
            Glass(Modifier.fillMaxWidth().padding(vertical = 5.dp), color = Surface1) {
                Column(Modifier.padding(14.dp)) {
                    Text(c.incoming.title.ifBlank { L("Sin título", "Untitled", "Sem título") }, style = MaterialTheme.typography.titleSmall, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(L("Local: ", "Local: ", "Local: ") + dateLabel(c.local.updatedAt) + " · " + L("Entrante: ", "Incoming: ", "Recebida: ") + dateLabel(c.incoming.updatedAt),
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Store.Resolution.entries.forEach { r -> Chip(resLabel(r), (res[c.incoming.id] ?: Store.Resolution.KEEP_LOCAL) == r) { res = res + (c.incoming.id to r) } }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BrandButton(L("Importar", "Import", "Importar"), Modifier.fillMaxWidth(), Icons.Outlined.Check) {
            Store.apply(a, res); snack(L("Importación lista", "Import done", "Importação concluída")); onDone()
        }
    }
}

private fun resLabel(r: Store.Resolution) = when (r) {
    Store.Resolution.KEEP_LOCAL -> L("Mantener local", "Keep local", "Manter local")
    Store.Resolution.REPLACE -> L("Reemplazar", "Replace", "Substituir")
    Store.Resolution.KEEP_BOTH -> L("Ambas", "Both", "Ambas")
}

/** Plugins instalados: activar/desactivar, errores de compatibilidad, desinstalar e instalar desde carpeta o zip. */
@Composable
fun PluginsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        Pin.external = false
        if (uri != null) scope.launch { val f = withContext(Dispatchers.IO) { runCatching { Picker.folder(ctx, uri) }.getOrDefault(emptyList()) }
            val ids = Plugins.install(f)
            toast(ctx, if (ids.isEmpty()) L("No se encontró manifest.json + main.js", "No manifest.json + main.js found", "manifest.json + main.js não encontrado") else ids.joinToString()) }
    }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        Pin.external = false
        if (uri != null) scope.launch { val f = withContext(Dispatchers.IO) { runCatching { Picker.files(ctx, listOf(uri)) }.getOrDefault(emptyList()) }
            val ids = Plugins.install(f); toast(ctx, if (ids.isEmpty()) L("No es un plugin de Obsidian", "Not an Obsidian plugin", "Não é um plugin do Obsidian") else ids.joinToString()) }
    }
    Sheet(onDismiss, L("Plugins de Obsidian", "Obsidian plugins", "Plugins do Obsidian")) {
        Text(L("Funcionan los plugins que agregan comandos (transformar texto, plantillas, ordenar listas, crear notas). Los que dibujan paneles o vistas de escritorio no hacen nada visible. Corren sin internet.",
            "Plugins that add commands work (text transforms, templates, sorting, creating notes). Ones that draw desktop panels or views won't show anything. They run offline.",
            "Funcionam plugins que adicionam comandos (transformar texto, modelos, ordenar listas, criar notas). Os que desenham painéis de desktop não mostram nada. Rodam sem internet."),
            style = MaterialTheme.typography.bodySmall, color = Muted)
        Spacer(Modifier.height(12.dp))
        if (Plugins.installed.isEmpty()) Text(L("Ninguno instalado. Importa una bóveda o elige la carpeta de un plugin.", "None installed. Import a vault or pick a plugin folder.",
            "Nenhum instalado. Importe um cofre ou escolha a pasta de um plugin."), color = Muted)
        Plugins.installed.forEach { p ->
            Glass(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, color = Ink)
                            Text("${p.version} · ${p.author}", style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1)
                        }
                        Toggle(p.id in Plugins.enabled) { Plugins.setEnabled(p.id, it) }
                    }
                    if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 3)
                    val cmds = Plugins.commands.filter { it.pluginId == p.id }
                    if (cmds.isNotEmpty()) Text(L("Comandos: ", "Commands: ", "Comandos: ") + cmds.joinToString { it.name }, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Success)
                    Plugins.errors[p.id]?.let { Text("⚠ $it", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Warning) }
                    Text(L("Desinstalar", "Uninstall", "Desinstalar"), Modifier.padding(top = 8.dp).tap(onClick = { Plugins.uninstall(p.id) }), style = MaterialTheme.typography.labelMedium, color = Danger)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(L("Carpeta", "Folder", "Pasta"), Icons.Outlined.FolderOpen, modifier = Modifier.weight(1f)) { Pin.external = true; folder.launch(null) }
            GhostButton("Zip", Icons.Outlined.FolderZip, modifier = Modifier.weight(1f)) { Pin.external = true; zip.launch(arrayOf("application/zip", "*/*")) }
        }
    }
}

/** Perfil: nombre, correo, bio y estadísticas. */
@Composable
fun ProfileScreen(snack: (String) -> Unit) {
    val d = Store.data
    var name by remember { mutableStateOf(d.profile.name) }
    var email by remember { mutableStateOf(d.profile.email) }
    var bio by remember { mutableStateOf(d.profile.bio) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)) {
        item { Header(L("Perfil", "Profile", "Perfil")) }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(FabGradient), contentAlignment = Alignment.Center) {
                    Text(name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "V" },
                        style = MaterialTheme.typography.headlineMedium, color = Color.White)
                }
            }
        }
        item {
            Group(L("Datos", "Details", "Dados"), Icons.Outlined.Person) {
                Field(name, { name = it }, L("Nombre", "Name", "Nome"))
                Field(email, { email = it }, L("Correo", "Email", "E-mail"))
                Field(bio, { bio = it }, L("Bio", "Bio", "Bio"), singleLine = false)
                Spacer(Modifier.height(8.dp))
                BrandButton(L("Guardar", "Save", "Salvar"), Modifier.fillMaxWidth(), Icons.Outlined.Check,
                    enabled = name != d.profile.name || email != d.profile.email || bio != d.profile.bio) {
                    Store.saveProfile(Profile(name.trim(), email.trim(), bio.trim())); snack(L("Perfil guardado", "Profile saved", "Perfil salvo"))
                }
            }
        }
        item {
            Group(L("Estadísticas", "Stats", "Estatísticas"), Icons.Outlined.Insights) {
                val live = d.notes.filter { !it.trashed }
                listOf(L("Notas", "Notes", "Notas") to live.size, L("Fijadas", "Pinned", "Fixadas") to live.count { it.pinned },
                    L("Bloqueadas", "Locked", "Bloqueadas") to live.count { it.locked }, L("Categorías", "Categories", "Categorias") to d.categories.size,
                    L("Etiquetas", "Tags", "Tags") to d.allTags().size, L("Palabras", "Words", "Palavras") to live.filter { !it.locked }.sumOf { n -> n.content.split(Regex("\\s+")).count { it.isNotBlank() } },
                ).chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (k, v) ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Surface1).padding(12.dp)) {
                                Text("$v", style = MaterialTheme.typography.titleLarge, color = Ink); Text(k, style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                    }
                }
                val tags = d.allTags().take(12)
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp)); Caps(L("Etiquetas más usadas", "Top tags", "Tags mais usadas"))
                    Text(tags.joinToString("  ") { "#${it.first} ${it.second}" }, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Brand)
                }
            }
        }
    }
}
