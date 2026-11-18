package com.metalsyntax.vitreon.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.metalsyntax.vitreon.data.*
import com.metalsyntax.vitreon.plugins.Plugins
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

/** Navegación que el editor necesita de la app. */
class EditorNav(val close: () -> Unit, val openNote: (String) -> Unit, val openTag: (String) -> Unit, val snack: (String) -> Unit)

/**
 * Nota a pantalla completa (NoteEditor.tsx). Las existentes abren en lectura y las nuevas en edición. Se guarda sola al salir
 * (la web perdía lo escrito con Atrás/Esc). Fijar, archivar y bloquear NO cierran el editor (en la web sí).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteEditor(noteId: String?, initialCategory: String?, initialText: String? = null, nav: EditorNav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(noteId) { Store.data.note(noteId) }
    // id estable aunque la nota sea nueva, para guardar adjuntos antes del primer guardado
    val id = rememberSaveable { existing?.id ?: newId() }
    val note = Store.data.note(id) ?: existing
    var askPin by remember { mutableStateOf(note?.locked == true && Pin.noteKey == null) }
    val revealed = remember(id, Pin.noteKey) { note?.let { Store.reveal(it) } ?: initialText.orEmpty() }
    var title by rememberSaveable { mutableStateOf(note?.title.orEmpty()) }
    var body by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(revealed)) }
    LaunchedEffect(revealed) { if (body.text.isEmpty() && revealed.isNotEmpty()) body = TextFieldValue(revealed) }
    var category by rememberSaveable { mutableStateOf(note?.category ?: initialCategory ?: UNCATEGORIZED) }
    var tags by remember { mutableStateOf(note?.tags.orEmpty()) }
    var color by remember { mutableStateOf(note?.color) }
    var attachments by remember { mutableStateOf(note?.attachments.orEmpty()) }
    var editing by rememberSaveable { mutableStateOf(note == null) }
    var menu by remember { mutableStateOf(false) }
    var palette by remember { mutableStateOf(false) }
    var drawing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }
    var recorder by remember { mutableStateOf<Pair<MediaRecorder, File>?>(null) }

    Plugins.active = note
    DisposableEffect(Unit) { onDispose { Plugins.active = null } }

    fun current(): Note? {
        val base = Store.data.note(id) ?: Note(id = id, category = category)
        return base.copy(title = title.trim(), category = category, tags = tags, attachments = attachments, color = color)
    }
    /** Guardar: notas bloqueadas se re-cifran; una nota nueva vacía no se guarda. */
    fun persist() {
        val n = current() ?: return
        val changed = Store.data.note(id).let { old ->
            old == null || old.title != n.title || old.category != n.category || old.tags != n.tags || old.color != n.color || old.attachments != n.attachments ||
                (if (old.locked) Store.reveal(old) != body.text else old.content != body.text)
        }
        if (!changed) return
        if (Store.data.note(id) == null && title.isBlank() && body.text.isBlank() && attachments.isEmpty()) return
        if (n.locked) { if (!Store.saveLocked(n, body.text)) nav.snack(L("Escribe el PIN para guardar la nota bloqueada", "Enter the PIN to save the locked note", "Digite o PIN para salvar a nota bloqueada")) }
        else Store.save(n.copy(content = body.text, updatedAt = now()))
    }
    fun closeAndSave() { persist(); nav.close() }
    // Ctrl+S con teclado físico
    LaunchedEffect(Shortcuts.saveTick) { if (Shortcuts.saveTick > 0) { persist(); nav.snack(L("Nota guardada", "Note saved", "Nota salva")) } }
    BackHandler { if (editing && note != null) { persist(); editing = false } else closeAndSave() }
    // la app pasa a segundo plano: se guarda igual
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) persist() }
        lifecycle.lifecycle.addObserver(obs); onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }

    // ------------------------------------------------------------------------- adjuntos
    fun addAttachment(type: AttachmentType, bytes: ByteArray, mime: String) {
        val a = Attachment(type = type, mime = mime); Store.writeAttachment(a, bytes); attachments = attachments + a; persist()
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        Pin.external = false
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()?.let { addAttachment(AttachmentType.IMAGE, downscale(it), "image/jpeg") }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp: Bitmap? ->
        Pin.external = false
        bmp?.let { addAttachment(AttachmentType.IMAGE, ByteArrayOutputStream().also { o -> it.compress(Bitmap.CompressFormat.JPEG, 90, o) }.toByteArray(), "image/jpeg") }
    }
    fun startRecording() {
        val f = File(ctx.cacheDir, "rec_${now()}.m4a")
        val r = (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(f.path); prepare(); start()
        }
        recorder = r to f
    }
    fun stopRecording() {
        val (r, f) = recorder ?: return
        runCatching { r.stop() }; r.release(); recorder = null
        if (f.length() > 0) addAttachment(AttachmentType.VOICE, f.readBytes(), "audio/mp4")
        f.delete() // el audio queda solo cifrado
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startRecording() }

    fun wrap(left: String, right: String = left) { body = Toolbar.wrap(body, left, right); }
    fun prefix(p: String) { body = Toolbar.prefixLines(body, p) }

    Box(Modifier.fillMaxSize().background(Backdrop)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // encabezado
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Outlined.ArrowBack, L("Volver", "Back", "Voltar"), ::closeAndSave)
                Spacer(Modifier.weight(1f))
                if (Plugins.commands.isNotEmpty()) RoundIcon(Icons.Outlined.Extension, L("Comandos de plugins", "Plugin commands", "Comandos de plugins"), { palette = true })
                Spacer(Modifier.width(8.dp))
                // lectura ⇄ edición
                RoundIcon(if (editing) Icons.Outlined.Visibility else Icons.Outlined.Edit, if (editing) L("Leer", "Read", "Ler") else L("Editar", "Edit", "Editar"),
                    { if (editing) persist(); editing = !editing }, tint = Color.White, bg = Brand)
                Spacer(Modifier.width(8.dp))
                Box {
                    RoundIcon(Icons.Outlined.MoreVert, L("Más", "More", "Mais"), { menu = true })
                    NoteMenu(menu, note, onDismiss = { menu = false }, onPin = { persist(); Store.update(id) { it.copy(pinned = !it.pinned) } },
                        onArchive = { persist(); Store.update(id) { it.copy(archived = !it.archived) }; nav.snack(L("Listo", "Done", "Pronto")) },
                        onLock = {
                            persist()
                            val n = Store.data.note(id)
                            when {
                                n == null -> Unit
                                !Pin.enabled -> nav.snack(L("Primero crea un PIN en Ajustes → Seguridad", "First create a PIN in Settings → Security", "Primeiro crie um PIN em Ajustes → Segurança"))
                                n.locked -> if (!Store.unlock(id)) askPin = true
                                else -> if (!Store.lock(id)) askPin = true else nav.snack(L("Nota bloqueada y cifrada", "Note locked and encrypted", "Nota bloqueada e criptografada"))
                            }
                        },
                        onShare = {
                            persist()
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/markdown")
                                .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, (if (title.isNotBlank()) "# $title\n\n" else "") + body.text), null))
                        },
                        onDelete = { confirmDelete = true })
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                // título
                BasicTextField(title, { title = it }, Modifier.fillMaxWidth(), readOnly = !editing,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = Ink), cursorBrush = SolidColor(Brand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    decorationBox = { inner -> if (title.isEmpty()) Text(L("Título", "Title", "Título"), style = MaterialTheme.typography.headlineMedium, color = Muted.copy(alpha = 0.5f)); inner() })
                Spacer(Modifier.height(6.dp))
                Text(note?.let { "${dateLabel(it.updatedAt)} · ${Markdown.plain(body.text).split(Regex("\\s+")).count { w -> w.isNotBlank() }} ${L("palabras", "words", "palavras")}" }
                    ?: L("Nota nueva", "New note", "Nota nova"), style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.height(12.dp))
                // categoría
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Store.data.categories.forEach { c ->
                        Chip(c.name, c.id == category, catColor(c.color), categoryIcon(c.icon)) { category = c.id }
                    }
                }
                // color de la nota (opcional)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Palette, L("Color", "Color", "Cor"), tint = Muted, modifier = Modifier.size(18.dp))
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Surface2).tap(onClick = { color = null }), contentAlignment = Alignment.Center) {
                        if (color == null) Icon(Icons.Outlined.Check, null, tint = Ink, modifier = Modifier.size(16.dp)) else Icon(Icons.Outlined.FormatColorReset, null, tint = Muted, modifier = Modifier.size(16.dp))
                    }
                    NOTE_COLORS.forEach { (k, v) ->
                        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(v)).tap(onClick = { color = k }), contentAlignment = Alignment.Center) {
                            if (color == k) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (askPin) {
                    Empty(Icons.Outlined.Lock, L("Nota bloqueada", "Locked note", "Nota bloqueada"), L("Escribe el PIN para verla.", "Enter the PIN to view it.", "Digite o PIN para vê-la."))
                } else if (editing) {
                    BasicTextField(body, { body = it }, Modifier.fillMaxWidth().heightIn(min = 280.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink), cursorBrush = SolidColor(Brand),
                        decorationBox = { inner -> if (body.text.isEmpty()) Text(L("Escribe aquí… usa **negrita**, [[enlaces]] y #etiquetas",
                            "Write here… use **bold**, [[links]] and #tags", "Escreva aqui… use **negrito**, [[links]] e #tags"), style = MaterialTheme.typography.bodyLarge, color = Muted.copy(alpha = 0.5f)); inner() })
                } else {
                    if (body.text.isBlank()) Text(L("Nota vacía. Toca ✎ para escribir.", "Empty note. Tap ✎ to write.", "Nota vazia. Toque ✎ para escrever."), color = Muted)
                    MarkdownView(body.text, ReaderActions(
                        openLink = { u -> Pin.external = true; ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) },
                        openNote = { t -> persist(); nav.openNote(t) },
                        openTag = { t -> persist(); nav.openTag(t) },
                        toggleTask = { line -> body = TextFieldValue(Markdown.toggleTask(body.text, line)); persist() },
                    ))
                }
                // adjuntos
                if (attachments.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp)); Caps(L("Adjuntos", "Attachments", "Anexos")); Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        attachments.forEach { a -> AttachmentThumb(a, editing) { attachments = attachments - a; Store.attachmentFile(a).delete(); persist() } }
                    }
                }
                // etiquetas
                Spacer(Modifier.height(18.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tg ->
                        Row(Modifier.clip(CircleShape).background(Brand.copy(alpha = 0.12f)).tap(onClick = { if (editing) tags = tags - tg else { persist(); nav.openTag(tg) } })
                            .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#$tg", style = MaterialTheme.typography.labelMedium, color = Brand)
                            if (editing) { Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.Close, null, tint = Brand, modifier = Modifier.size(14.dp)) }
                        }
                    }
                    if (editing) BasicTextField(tagInput, { v -> if (v.endsWith(" ") || v.endsWith(",")) { normTag(v)?.let { tags = (tags + it).distinct() }; tagInput = "" } else tagInput = v },
                        Modifier.width(140.dp).padding(vertical = 5.dp), singleLine = true, textStyle = MaterialTheme.typography.labelMedium.copy(color = Ink), cursorBrush = SolidColor(Brand),
                        keyboardActions = KeyboardActions(onDone = { normTag(tagInput)?.let { tags = (tags + it).distinct() }; tagInput = "" }),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        decorationBox = { inner -> if (tagInput.isEmpty()) Text(L("+ etiqueta", "+ tag", "+ tag"), style = MaterialTheme.typography.labelMedium, color = Muted); inner() })
                }
                // notas que enlazan a esta (Obsidian)
                note?.takeIf { !editing && it.title.isNotBlank() }?.let { n ->
                    val back = Store.data.backlinks(n)
                    if (back.isNotEmpty()) {
                        Spacer(Modifier.height(22.dp)); Caps(L("Enlazada desde", "Linked from", "Ligada de") + " (${back.size})"); Spacer(Modifier.height(6.dp))
                        back.forEach { b -> Text("↳ ${b.title.ifBlank { L("Sin título", "Untitled", "Sem título") }}", Modifier.tap(onClick = { persist(); nav.openNote(b.title) }).padding(vertical = 6.dp),
                            style = MaterialTheme.typography.bodyMedium, color = Brand) }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
            // barra de formato y adjuntos (solo editando)
            if (editing && !askPin) Row(Modifier.fillMaxWidth().background(Surface1).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ToolBtn(Icons.Outlined.FormatBold, "B") { wrap("**") }
                ToolBtn(Icons.Outlined.FormatItalic, "I") { wrap("*") }
                ToolBtn(Icons.Outlined.FormatUnderlined, "U") { wrap("++") }
                ToolBtn(Icons.Outlined.FormatStrikethrough, "S") { wrap("~~") }
                ToolBtn(Icons.Outlined.Title, "H") { prefix(Toolbar.nextHeading(body)) }
                ToolBtn(Icons.Outlined.FormatQuote, ">") { prefix("> ") }
                ToolBtn(Icons.Outlined.FormatListBulleted, "-") { prefix("- ") }
                ToolBtn(Icons.Outlined.CheckBox, "[ ]") { prefix("- [ ] ") }
                ToolBtn(Icons.Outlined.Code, "`") { wrap("`") }
                ToolBtn(Icons.Outlined.HorizontalRule, "---") { body = Toolbar.insert(body, "\n---\n") }
                ToolBtn(Icons.Outlined.Link, "[[ ]]") { wrap("[[", "]]") }
                ToolBtn(Icons.Outlined.Image, L("Imagen", "Image", "Imagem")) { Pin.external = true; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                ToolBtn(Icons.Outlined.PhotoCamera, L("Cámara", "Camera", "Câmera")) { Pin.external = true; camera.launch(null) }
                ToolBtn(if (recorder != null) Icons.Outlined.Stop else Icons.Outlined.Mic, L("Voz", "Voice", "Voz"), tint = if (recorder != null) Danger else Ink) {
                    if (recorder != null) stopRecording()
                    else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                ToolBtn(Icons.Outlined.Draw, L("Dibujo", "Drawing", "Desenho")) { drawing = true }
            }
        }
    }

    if (askPin) PinDialog(L("Nota bloqueada", "Locked note", "Nota bloqueada"), onDismiss = { askPin = false; if (note?.locked == true) nav.close() }) { askPin = false }
    if (drawing) DrawingSheet(onDismiss = { drawing = false }) { png -> addAttachment(AttachmentType.DRAWING, png, "image/png"); drawing = false }
    if (palette) Sheet({ palette = false }, L("Comandos de plugins", "Plugin commands", "Comandos de plugins")) {
        Plugins.commands.forEach { c ->
            val p = Plugins.installed.firstOrNull { it.id == c.pluginId }
            SettingRow(c.name, p?.name, Icons.Outlined.Extension, onClick = {
                palette = false
                scope.launch {
                    val r = Plugins.run(c, body.text, body.selection.min, body.selection.max)
                    r?.text?.let { t -> body = TextFieldValue(t, TextRange(r.selStart.coerceIn(0, t.length), r.selEnd.coerceIn(0, t.length))); persist() }
                }
            })
        }
    }
    if (confirmDelete) Confirm(L("¿Mover a la papelera?", "Move to trash?", "Mover para a lixeira?"),
        L("Se borra del todo a los 30 días.", "It's deleted for good after 30 days.", "É apagada de vez após 30 dias."), L("Mover", "Move", "Mover"), danger = true,
        onDismiss = { confirmDelete = false }) { persist(); Store.trash(setOf(id)); confirmDelete = false; nav.close() }
}

private fun normTag(s: String) = s.trim().trim(',', '#').lowercase().replace(' ', '-').takeIf { it.isNotEmpty() }

/** Reduce fotos grandes a 1600 px (la web guardaba la imagen completa en base64 dentro de la nota). */
private fun downscale(bytes: ByteArray): ByteArray {
    val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
    val s = minOf(1f, 1600f / maxOf(b.width, b.height))
    val out = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
    return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
}

/** Edición de Markdown sobre la selección del campo (puras, con pruebas). */
object Toolbar {
    fun wrap(v: TextFieldValue, l: String, r: String = l): TextFieldValue {
        val s = v.selection.min; val e = v.selection.max
        val t = v.text.substring(0, s) + l + v.text.substring(s, e) + r + v.text.substring(e)
        return TextFieldValue(t, if (s == e) TextRange(s + l.length) else TextRange(s + l.length, e + l.length))
    }
    /** Antepone [p] a las líneas seleccionadas; si ya lo tienen, lo quita (alternar). */
    fun prefixLines(v: TextFieldValue, p: String): TextFieldValue {
        val start = v.text.lastIndexOf('\n', (v.selection.min - 1).coerceAtLeast(0)).let { if (it < 0 || v.selection.min == 0) 0 else it + 1 }
        val end = v.text.indexOf('\n', v.selection.max).let { if (it < 0) v.text.length else it }
        val block = v.text.substring(start, end).split('\n')
        val heading = p.startsWith("#")
        val all = block.all { it.startsWith(p) }
        val out = block.map { l ->
            when {
                all -> l.removePrefix(p)
                heading -> p + l.replaceFirst(Regex("^#{1,6} "), "")
                else -> p + l
            }
        }.joinToString("\n")
        val t = v.text.substring(0, start) + out + v.text.substring(end)
        return TextFieldValue(t, TextRange(start + out.length))
    }
    fun insert(v: TextFieldValue, s: String): TextFieldValue {
        val t = v.text.substring(0, v.selection.min) + s + v.text.substring(v.selection.max)
        return TextFieldValue(t, TextRange(v.selection.min + s.length))
    }
    /** H1 → H2 → H3 → sin encabezado, según la línea actual. */
    fun nextHeading(v: TextFieldValue): String {
        val start = v.text.lastIndexOf('\n', (v.selection.min - 1).coerceAtLeast(0)).let { if (it < 0 || v.selection.min == 0) 0 else it + 1 }
        val n = Regex("^(#{1,6}) ").find(v.text.substring(start))?.groupValues?.get(1)?.length ?: 0
        return if (n >= 3) "#".repeat(n) + " " else "#".repeat(n + 1) + " "
    }
}

@Composable
private fun ToolBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color = Ink, onClick: () -> Unit) =
    Box(Modifier.size(44.dp).tap(onClick = onClick).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp))
    }

@Composable
private fun NoteMenu(open: Boolean, note: Note?, onDismiss: () -> Unit, onPin: () -> Unit, onArchive: () -> Unit, onLock: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    DropdownMenu(open, onDismiss, Modifier.background(if (Prefs.mode.dark) Color(0xFF0F1220) else Color.White)) {
        @Composable fun item(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color = Ink, f: () -> Unit) =
            DropdownMenuItem(text = { Text(text, color = color) }, leadingIcon = { Icon(icon, null, tint = color) }, onClick = { onDismiss(); f() })
        item(Icons.Outlined.PushPin, if (note?.pinned == true) L("Quitar de fijadas", "Unpin", "Desafixar") else L("Fijar", "Pin", "Fixar"), f = onPin)
        item(Icons.Outlined.Archive, if (note?.archived == true) L("Sacar del archivo", "Unarchive", "Desarquivar") else L("Archivar", "Archive", "Arquivar"), f = onArchive)
        item(if (note?.locked == true) Icons.Outlined.LockOpen else Icons.Outlined.Lock, if (note?.locked == true) L("Desbloquear", "Unlock", "Desbloquear") else L("Bloquear con PIN", "Lock with PIN", "Bloquear com PIN"), f = onLock)
        item(Icons.Outlined.Share, L("Compartir como Markdown", "Share as Markdown", "Compartilhar como Markdown"), f = onShare)
        item(Icons.Outlined.Delete, L("Mover a la papelera", "Move to trash", "Mover para a lixeira"), Danger, onDelete)
    }
}

/** Miniatura de adjunto: imagen/dibujo (se descifra al vuelo) o nota de voz con reproducir. */
@Composable
private fun AttachmentThumb(a: Attachment, editing: Boolean, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.size(120.dp).clip(RoundedCornerShape(20.dp)).background(Surface1)) {
        if (a.type == AttachmentType.VOICE) {
            var player by remember { mutableStateOf<MediaPlayer?>(null) }
            DisposableEffect(a.id) { onDispose { player?.release() } }
            Column(Modifier.fillMaxSize().tap(onClick = {
                player?.let { it.release(); player = null } ?: run {
                    val tmp = File(ctx.cacheDir, "play_${a.id}.m4a").apply { writeBytes(Store.readAttachment(a) ?: return@run); deleteOnExit() }
                    player = MediaPlayer().apply { setDataSource(tmp.path); prepare(); start(); setOnCompletionListener { it.release(); player = null; tmp.delete() } }
                }
            }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(if (player != null) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null, tint = Brand, modifier = Modifier.size(40.dp))
                Text(L("Nota de voz", "Voice note", "Nota de voz"), style = MaterialTheme.typography.labelMedium, color = Muted)
            }
        } else {
            val bmp = remember(a.id) { Store.readAttachment(a)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().background(if (a.type == AttachmentType.DRAWING) Color.White else Color.Transparent), contentScale = ContentScale.Crop) }
        }
        if (editing) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).tap(onClick = onRemove),
            contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Close, L("Quitar", "Remove", "Remover"), tint = Color.White, modifier = Modifier.size(16.dp)) }
    }
}

/** Lienzo de dibujo (DrawingModal de la web): trazos con el dedo, deshacer, borrar y guardar como PNG. */
@Composable
private fun DrawingSheet(onDismiss: () -> Unit, onSave: (ByteArray) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var color by remember { mutableStateOf(Color.Black) }
    val colors = remember { mutableStateListOf<Color>() }
    Sheet(onDismiss, L("Dibujo", "Drawing", "Desenho")) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.White).pointerInput(Unit) {
            size = androidx.compose.ui.geometry.Size(this.size.width.toFloat(), this.size.height.toFloat())
            detectDragGestures(onDragStart = { current = listOf(it) }, onDragEnd = { strokes += current; colors += color; current = emptyList() }) { ch, _ -> current = current + ch.position }
        }) {
            (strokes.zip(colors) + (current to color)).forEach { (pts, c) ->
                if (pts.size > 1) drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }, c,
                    style = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(Color.Black, Brand, Danger, Success, Warning).forEach { c ->
                Box(Modifier.size(32.dp).clip(CircleShape).background(c).tap(onClick = { color = c }), contentAlignment = Alignment.Center) {
                    if (c == color) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            RoundIcon(Icons.Outlined.Undo, L("Deshacer", "Undo", "Desfazer"), { if (strokes.isNotEmpty()) { strokes.removeAt(strokes.lastIndex); colors.removeAt(colors.lastIndex) } })
        }
        Spacer(Modifier.height(12.dp))
        BrandButton(L("Guardar dibujo", "Save drawing", "Salvar desenho"), Modifier.fillMaxWidth(), Icons.Outlined.Check, enabled = strokes.isNotEmpty()) {
            val w = size.width.toInt().coerceAtLeast(1); val h = size.height.toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(bmp); cv.drawColor(android.graphics.Color.WHITE)
            strokes.zip(colors).forEach { (pts, c) ->
                val p = android.graphics.Paint().apply { this.color = android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
                    strokeWidth = 6f; style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND; isAntiAlias = true }
                val path = android.graphics.Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                cv.drawPath(path, p)
            }
            onSave(ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
        }
    }
}
