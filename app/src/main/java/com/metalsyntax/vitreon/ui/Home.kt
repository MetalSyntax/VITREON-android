package com.metalsyntax.vitreon.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metalsyntax.vitreon.data.*
import kotlinx.coroutines.delay

enum class View { ALL, FAVORITES, ARCHIVE, TRASH }

/** Filtro + orden de la lista (puro, con pruebas). Busca en título, etiquetas y contenido de notas no bloqueadas. */
fun visibleNotes(notes: List<Note>, view: View, category: String?, query: String, sort: Sort): List<Note> {
    val q = query.trim().lowercase()
    val tag = q.takeIf { it.startsWith("#") }?.removePrefix("#")
    return notes.filter { n ->
        when (view) {
            View.ALL -> !n.trashed && !n.archived
            View.FAVORITES -> !n.trashed && n.pinned
            View.ARCHIVE -> !n.trashed && n.archived
            View.TRASH -> n.trashed
        } && (category == null || n.category == category) && when {
            q.isEmpty() -> true
            tag != null -> n.tags.any { it == tag || it.startsWith("$tag/") } || (!n.locked && Tags.inContent(n.content, tag))
            else -> n.title.lowercase().contains(q) || n.tags.any { it.contains(q) } || (!n.locked && n.content.lowercase().contains(q))
        }
    }.let { l ->
        when (sort) {
            Sort.NEWEST -> l.sortedWith(compareByDescending<Note> { it.order }.thenByDescending { it.updatedAt })
            Sort.OLDEST -> l.sortedBy { it.createdAt } // la web ordenaba «antiguas» por edición
            Sort.AZ -> l.sortedBy { it.title.lowercase() }
            Sort.ZA -> l.sortedByDescending { it.title.lowercase() }
        }
    }
}

private object Tags { fun inContent(c: String, t: String) = com.metalsyntax.vitreon.importers.Tags.find(c).any { it.lowercase() == t || it.lowercase().startsWith("$t/") } }

class HomeNav(val open: (Note) -> Unit, val snack: (String) -> Unit, val categories: () -> Unit, val profile: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(nav: HomeNav, view: View, setView: (View) -> Unit, query: String, setQuery: (String) -> Unit, category: String?, setCategory: (String?) -> Unit) {
    val ctx = LocalContext.current
    val d = Store.data
    var typed by remember(query) { mutableStateOf(query) }
    // espera de 300 ms antes de filtrar, como la web
    LaunchedEffect(typed) { delay(300); setQuery(typed) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selected.isNotEmpty()
    var bulkCategory by remember { mutableStateOf(false) }
    var bulkTags by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val list = remember(d.notes, view, category, query, Prefs.sort) { visibleNotes(d.notes, view, category, query, Prefs.sort) }
    val pinnedRow = view == View.ALL && query.isEmpty() && Prefs.sort == Sort.NEWEST && category == null
    val pinned = if (pinnedRow) list.filter { it.pinned } else emptyList()
    val rest = if (pinnedRow) list.filter { !it.pinned } else list
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        Pin.external = false
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { typed = it }
    }
    fun toggle(n: Note) { selected = if (n.id in selected) selected - n.id else selected + n.id }
    fun open(n: Note) { if (selecting) toggle(n) else nav.open(n) }

    Column(Modifier.fillMaxSize()) {
        // encabezado: saludo + accesos a Favoritas / Archivo / Papelera
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(when (view) {
                    View.ALL -> "Vitreon"; View.FAVORITES -> L("Favoritas", "Favorites", "Favoritas")
                    View.ARCHIVE -> L("Archivo", "Archive", "Arquivo"); View.TRASH -> L("Papelera", "Trash", "Lixeira")
                }, style = MaterialTheme.typography.displaySmall, color = Ink)
                Text(if (d.profile.name.isNotBlank() && view == View.ALL) L("Hola, ", "Hi, ", "Olá, ") + d.profile.name.substringBefore(' ')
                    else "${list.size} " + L("notas", "notes", "notas"), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            RoundIcon(Icons.Outlined.Star, L("Favoritas", "Favorites", "Favoritas"), { setView(if (view == View.FAVORITES) View.ALL else View.FAVORITES) },
                tint = if (view == View.FAVORITES) Warning else Muted)
            Spacer(Modifier.width(6.dp))
            RoundIcon(Icons.Outlined.Archive, L("Archivo", "Archive", "Arquivo"), { setView(if (view == View.ARCHIVE) View.ALL else View.ARCHIVE) },
                tint = if (view == View.ARCHIVE) Brand else Muted)
            Spacer(Modifier.width(6.dp))
            RoundIcon(Icons.Outlined.Delete, L("Papelera", "Trash", "Lixeira"), { setView(if (view == View.TRASH) View.ALL else View.TRASH) },
                tint = if (view == View.TRASH) Danger else Muted)
        }
        // buscador unificado con orden, vista y selección dentro (como la web)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Surface1).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, tint = Muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            BasicTextField(typed, { typed = it }, Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink), cursorBrush = SolidColor(Brand),
                decorationBox = { inner -> if (typed.isEmpty()) Text(L("Buscar o #etiqueta", "Search or #tag", "Buscar ou #tag"), color = Muted, style = MaterialTheme.typography.bodyLarge); inner() })
            if (typed.isNotEmpty()) IconButton({ typed = "" }) { Icon(Icons.Outlined.Close, L("Limpiar", "Clear", "Limpar"), tint = Muted) }
            else IconButton({
                Pin.external = true
                runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, when (Prefs.lang) { Lang.ES -> "es-ES"; Lang.PT -> "pt-BR"; Lang.EN -> "en-US" })) }
            }) { Icon(Icons.Outlined.Mic, L("Buscar por voz", "Voice search", "Busca por voz"), tint = Muted) }
            IconButton(Prefs::cycleSort) {
                Icon(when (Prefs.sort) { Sort.NEWEST -> Icons.Outlined.Schedule; Sort.OLDEST -> Icons.Outlined.History; Sort.AZ -> Icons.Outlined.SortByAlpha; Sort.ZA -> Icons.Outlined.Sort },
                    L("Ordenar", "Sort", "Ordenar") + ": " + when (Prefs.sort) { Sort.NEWEST -> L("recientes", "newest", "recentes"); Sort.OLDEST -> L("antiguas", "oldest", "antigas"); Sort.AZ -> "A-Z"; Sort.ZA -> "Z-A" },
                    tint = Muted)
            }
            IconButton(Prefs::cycleLayout) {
                Icon(when (Prefs.layout) { Layout.GRID -> Icons.Outlined.GridView; Layout.LIST -> Icons.Outlined.ViewList; Layout.CARD -> Icons.Outlined.ViewAgenda }, L("Vista", "Layout", "Visualização"), tint = Muted)
            }
        }
        // chips de categoría
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Chip(L("Todas", "All", "Todas"), category == null, icon = Icons.Outlined.Apps) { setCategory(null) } }
            items(d.categories, key = { it.id }) { c ->
                Chip(c.name, category == c.id, catColor(c.color), categoryIcon(c.icon)) { setCategory(if (category == c.id) null else c.id) }
            }
        }
        // barra de selección
        if (selecting) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brand.copy(alpha = 0.15f)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton({ selected = emptySet() }) { Icon(Icons.Outlined.Close, L("Cancelar", "Cancel", "Cancelar"), tint = Ink) }
            Text("${selected.size}", style = MaterialTheme.typography.titleMedium, color = Ink)
            Spacer(Modifier.weight(1f))
            // seleccionar todo incluye las fijadas (en la web no)
            IconButton({ selected = list.map { it.id }.toSet() }) { Icon(Icons.Outlined.SelectAll, L("Seleccionar todo", "Select all", "Selecionar tudo"), tint = Ink) }
            if (view == View.TRASH) {
                IconButton({ Store.restore(selected); selected = emptySet() }) { Icon(Icons.Outlined.RestoreFromTrash, L("Restaurar", "Restore", "Restaurar"), tint = Success) }
                IconButton({ confirm = "forever" }) { Icon(Icons.Outlined.DeleteForever, L("Borrar para siempre", "Delete forever", "Apagar para sempre"), tint = Danger) }
            } else {
                IconButton({ val pin = !d.notes.filter { it.id in selected }.all { it.pinned }; Store.updateMany(selected) { it.copy(pinned = pin) }; selected = emptySet() }) {
                    Icon(Icons.Outlined.PushPin, L("Fijar", "Pin", "Fixar"), tint = Ink) }
                IconButton({ bulkCategory = true }) { Icon(Icons.Outlined.Folder, L("Categoría", "Category", "Categoria"), tint = Ink) }
                IconButton({ bulkTags = true }) { Icon(Icons.Outlined.Tag, L("Etiquetas", "Tags", "Tags"), tint = Ink) }
                IconButton({ val arch = view != View.ARCHIVE; Store.updateMany(selected) { it.copy(archived = arch) }; selected = emptySet() }) {
                    Icon(if (view == View.ARCHIVE) Icons.Outlined.Unarchive else Icons.Outlined.Archive, L("Archivar", "Archive", "Arquivar"), tint = Ink) }
                IconButton({ ShareNotes.share(ctx, d.notes.filter { it.id in selected }); selected = emptySet() }) { Icon(Icons.Outlined.Share, L("Exportar", "Export", "Exportar"), tint = Ink) }
                IconButton({ Store.trash(selected); nav.snack(L("${selected.size} a la papelera", "${selected.size} moved to trash", "${selected.size} para a lixeira")); selected = emptySet() }) {
                    Icon(Icons.Outlined.Delete, L("Borrar", "Delete", "Apagar"), tint = Danger) }
            }
        }
        if (view == View.TRASH && list.isNotEmpty() && !selecting) Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(L("Se borran solas a los 30 días", "Deleted automatically after 30 days", "Apagadas após 30 dias"), Modifier.weight(1f).align(Alignment.CenterVertically),
                style = MaterialTheme.typography.bodySmall, color = Muted)
            GhostButton(L("Vaciar", "Empty", "Esvaziar"), Icons.Outlined.DeleteSweep, Danger) { confirm = "empty" }
        }

        if (list.isEmpty()) {
            if (query.isNotEmpty()) Empty(Icons.Outlined.SearchOff, L("Sin resultados", "No results", "Sem resultados"), L("Prueba otra palabra o #etiqueta.", "Try another word or #tag.", "Tente outra palavra ou #tag."))
            else when (view) {
                View.TRASH -> Empty(Icons.Outlined.Delete, L("Papelera vacía", "Trash is empty", "Lixeira vazia"), "")
                View.ARCHIVE -> Empty(Icons.Outlined.Archive, L("Nada archivado", "Nothing archived", "Nada arquivado"), "")
                View.FAVORITES -> Empty(Icons.Outlined.Star, L("Sin favoritas", "No favorites", "Sem favoritas"), L("Fija una nota para verla aquí.", "Pin a note to see it here.", "Fixe uma nota para vê-la aqui."))
                View.ALL -> Empty(Icons.Outlined.EditNote, L("Tu primera nota", "Your first note", "Sua primeira nota"),
                    L("Toca + para escribir, o importa desde Keep, Notion u Obsidian en Ajustes.", "Tap + to write, or import from Keep, Notion or Obsidian in Settings.",
                        "Toque + para escrever, ou importe do Keep, Notion ou Obsidian em Ajustes."))
            }
        } else {

        // reordenar arrastrando: solo en lista, orden manual y sin filtros (como la web)
        val canReorder = Prefs.layout == Layout.LIST && Prefs.sort == Sort.NEWEST && view == View.ALL && query.isEmpty() && category == null && !selecting
        if (canReorder) {
            var order by remember(rest) { mutableStateOf(rest) }
            ReorderList(pinned, order, ::open, { n -> toggle(n) }, onDrop = { moved ->
                val i = order.indexOfFirst { it.id == moved }
                Store.move(moved, order.getOrNull(i - 1), order.getOrNull(i + 1))
            }, setOrder = { order = it })
        } else {
            LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(if (Prefs.layout == Layout.GRID) 2 else 1), modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalItemSpacing = 12.dp) {
                if (pinned.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Caps(L("Fijadas", "Pinned", "Fixadas"), Modifier.weight(1f))
                            Text(if (Prefs.pinnedCarousel) L("Ver en cuadrícula", "Show as grid", "Ver em grade") else L("Ver en carrusel", "Show as carousel", "Ver em carrossel"),
                                Modifier.tap(onClick = Prefs::togglePinnedCarousel), style = MaterialTheme.typography.labelMedium, color = Brand)
                        }
                    }
                    if (Prefs.pinnedCarousel) item(span = StaggeredGridItemSpan.FullLine) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(pinned, key = { "p" + it.id }) { n -> Box(Modifier.width(220.dp)) { NoteCard(n, n.id in selected, ::open) { toggle(n) } } }
                        }
                    } else items(pinned, key = { "p" + it.id }) { n -> NoteCard(n, n.id in selected, ::open) { toggle(n) } }
                    item(span = StaggeredGridItemSpan.FullLine) { Caps(L("Notas", "Notes", "Notas")) }
                }
                items(rest, key = { it.id }) { n -> NoteCard(n, n.id in selected, ::open) { toggle(n) } }
            }
        }
        }
    }

    if (bulkCategory) Sheet({ bulkCategory = false }, L("Mover a categoría", "Move to category", "Mover para categoria")) {
        d.categories.forEach { c -> SettingRow(c.name, null, categoryIcon(c.icon), catColor(c.color), onClick = {
            Store.updateMany(selected) { it.copy(category = c.id) }; selected = emptySet(); bulkCategory = false }) }
    }
    if (bulkTags) {
        var text by remember { mutableStateOf("") }
        Confirm(L("Agregar etiquetas", "Add tags", "Adicionar tags"), L("Separadas por coma", "Comma separated", "Separadas por vírgula"), L("Agregar", "Add", "Adicionar"),
            onDismiss = { bulkTags = false }, extra = { Field(text, { text = it }, L("Etiquetas", "Tags", "Tags")) }) {
            val add = text.split(',').map { it.trim().lowercase().replace(' ', '-').trim('#') }.filter { it.isNotEmpty() }
            Store.updateMany(selected) { it.copy(tags = (it.tags + add).distinct()) }; selected = emptySet(); bulkTags = false
        }
    }
    confirm?.let { c ->
        Confirm(L("¿Borrar para siempre?", "Delete forever?", "Apagar para sempre?"), L("No se puede deshacer.", "This can't be undone.", "Não pode ser desfeito."),
            L("Borrar", "Delete", "Apagar"), danger = true, onDismiss = { confirm = null }) {
            if (c == "empty") Store.emptyTrash() else Store.deleteForever(selected)
            selected = emptySet(); confirm = null
        }
    }
}

/** Lista con asas de arrastre: al soltar se guarda solo la nota movida (orden fraccionario). */
@Composable
private fun ReorderList(pinned: List<Note>, order: List<Note>, open: (Note) -> Unit, select: (Note) -> Unit, onDrop: (String) -> Unit, setOrder: (List<Note>) -> Unit) {
    var last by remember { mutableStateOf<String?>(null) }
    val st = rememberReorder(onMove = { a, b -> last = a as String; setOrder(order.move(a, b) { it.id }) }, onDrop = { last?.let(onDrop); last = null })
    Column(Modifier.fillMaxSize().verticalScrollable().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pinned.isNotEmpty()) { Caps(L("Fijadas", "Pinned", "Fixadas")); pinned.forEach { n -> NoteCard(n, false, open) { select(n) } }; Caps(L("Notas", "Notes", "Notas")) }
        order.forEach { n ->
            key(n.id) {
                Row(Modifier.reorderItem(st, n.id), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { NoteCard(n, false, open) { select(n) } }
                    DragHandle(st, n.id)
                }
            }
        }
    }
}

fun Modifier.verticalScrollable() = composed { verticalScroll(rememberScrollState()) }

/** Tarjeta de nota (NoteCard.tsx): categoría, título, vista previa, tareas tocables, etiquetas y adjuntos. */
@Composable
fun NoteCard(n: Note, selected: Boolean, onOpen: (Note) -> Unit, onSelect: () -> Unit) {
    val c = Store.data.category(n.category)
    val color = catColor(c.color)
    Glass(Modifier.fillMaxWidth().let { if (selected) it.background(Brand.copy(alpha = 0.25f), RoundedCornerShape(28.dp)) else it },
        color = if (selected) Brand.copy(alpha = 0.18f) else Surface1, onClick = { onOpen(n) }, onLongClick = onSelect) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(CircleShape).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(categoryIcon(c.icon), null, tint = color, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp))
                    Text(c.name, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = color, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                if (n.pinned) Icon(Icons.Outlined.PushPin, L("Fijada", "Pinned", "Fixada"), tint = Warning, modifier = Modifier.size(14.dp))
                if (n.locked) Icon(Icons.Outlined.Lock, L("Bloqueada", "Locked", "Bloqueada"), tint = Muted, modifier = Modifier.size(14.dp))
                if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = Brand, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(10.dp))
            val title = n.title.ifBlank { Markdown.plain(n.content).take(40).substringBefore('\n').ifBlank { L("Sin título", "Untitled", "Sem título") } }
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (n.locked) {
                Spacer(Modifier.height(8.dp))
                Text("•••• " + L("Contenido protegido", "Protected content", "Conteúdo protegido"), style = MaterialTheme.typography.bodySmall, color = Muted)
            } else if (n.content.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.heightIn(max = if (Prefs.layout == Layout.CARD) 220.dp else 150.dp)) {
                    MarkdownView(n.content, ReaderActions(toggleTask = { line -> Store.update(n.id) { it.copy(content = Markdown.toggleTask(it.content, line)) } }),
                        maxBlocks = if (Prefs.layout == Layout.LIST) 3 else 8, compact = true)
                }
            }
            if (n.tags.isNotEmpty() || n.attachments.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    n.attachments.map { it.type }.distinct().forEach { t ->
                        Icon(when (t) { AttachmentType.IMAGE -> Icons.Outlined.Image; AttachmentType.DRAWING -> Icons.Outlined.Draw; AttachmentType.VOICE -> Icons.Outlined.Mic }, null,
                            tint = Muted, modifier = Modifier.size(14.dp))
                    }
                    n.tags.take(4).forEach { Text("#$it", style = MaterialTheme.typography.labelMedium, color = Brand) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(n.deletedAt?.let { L("Borrada ", "Deleted ", "Apagada ") + dateLabel(it) } ?: dateLabel(n.updatedAt), style = MaterialTheme.typography.labelMedium, color = Muted.copy(alpha = 0.7f))
        }
    }
}

/** Compartir varias notas como un solo texto Markdown. */
object ShareNotes {
    fun share(ctx: android.content.Context, notes: List<Note>) {
        val text = notes.filter { !it.locked }.joinToString("\n\n---\n\n") { (if (it.title.isNotBlank()) "# ${it.title}\n\n" else "") + it.content }
        Pin.external = true
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_TEXT, text), null))
    }
}
