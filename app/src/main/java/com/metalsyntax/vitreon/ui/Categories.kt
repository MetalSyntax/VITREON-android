package com.metalsyntax.vitreon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.unit.dp
import com.metalsyntax.vitreon.data.*

/** Categorías (CategoriesView.tsx): tarjetas con conteo; tocar = ver sus notas, mantener = editar. */
@Composable
fun CategoriesScreen(openCategory: (String) -> Unit) {
    val d = Store.data
    var edit by remember { mutableStateOf<Category?>(null) }
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Header(L("Categorías", "Categories", "Categorias"), L("Mantén presionada una para editarla", "Long-press one to edit it", "Pressione e segure para editar")) {
            RoundIcon(Icons.Outlined.Add, L("Nueva categoría", "New category", "Nova categoria"), { creating = true }, tint = Color.White, bg = Brand)
        }
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(d.categories, key = { it.id }) { c ->
                val color = catColor(c.color)
                val count = d.notes.count { !it.trashed && it.category == c.id }
                Glass(Modifier.fillMaxWidth().height(150.dp), onClick = { openCategory(c.id) }, onLongClick = { edit = c }) {
                    Column(Modifier.padding(18.dp).fillMaxSize()) {
                        Bubble(categoryIcon(c.icon), color, 48.dp)
                        Spacer(Modifier.weight(1f))
                        Text(c.name, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1)
                        Text("$count " + L("notas", "notes", "notas"), style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) { TipBanner() }
        }
    }
    if (creating || edit != null) CategorySheet(edit, onDismiss = { creating = false; edit = null })
}

/** Crear/editar categoría: nombre, 16 colores y 70 íconos. Id = uuid (la web usaba el nombre y se pisaban). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorySheet(initial: Category?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember { mutableStateOf(initial?.color ?: "indigo") }
    var icon by remember { mutableStateOf(initial?.icon ?: "folder") }
    var confirmDelete by remember { mutableStateOf(false) }
    Sheet(onDismiss, if (initial == null) L("Nueva categoría", "New category", "Nova categoria") else L("Editar categoría", "Edit category", "Editar categoria")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Bubble(categoryIcon(icon), catColor(color), 56.dp); Spacer(Modifier.width(14.dp))
            Field(name, { name = it }, L("Nombre", "Name", "Nome"), Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp)); Caps(L("Color", "Color", "Cor")); Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CATEGORY_COLORS.keys.forEach { k ->
                Box(Modifier.size(36.dp).clip(CircleShape).background(catColor(k)).tap(onClick = { color = k }), contentAlignment = Alignment.Center) {
                    if (k == color) Icon(Icons.Outlined.Check, k, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp)); Caps(L("Ícono", "Icon", "Ícone")); Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CATEGORY_ICONS.forEach { (k, v) ->
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (k == icon) catColor(color) else Surface1).tap(onClick = { icon = k }),
                    contentAlignment = Alignment.Center) { Icon(v, k, tint = if (k == icon) Color.White else Muted, modifier = Modifier.size(22.dp)) }
            }
        }
        Spacer(Modifier.height(18.dp))
        BrandButton(L("Guardar", "Save", "Salvar"), Modifier.fillMaxWidth(), Icons.Outlined.Check, enabled = name.isNotBlank()) {
            Store.saveCategory((initial ?: Category(name = name)).copy(name = name.trim(), color = color, icon = icon)); onDismiss()
        }
        if (initial != null && initial.id != UNCATEGORIZED) {
            Spacer(Modifier.height(8.dp))
            GhostButton(L("Eliminar categoría", "Delete category", "Excluir categoria"), Icons.Outlined.Delete, Danger, Modifier.fillMaxWidth()) { confirmDelete = true }
        }
    }
    if (confirmDelete && initial != null) Confirm(L("¿Eliminar «${initial.name}»?", "Delete «${initial.name}»?", "Excluir «${initial.name}»?"),
        L("Sus notas pasan a «Sin categoría».", "Its notes move to «Uncategorized».", "Suas notas vão para «Sem categoria»."), L("Eliminar", "Delete", "Excluir"), danger = true,
        onDismiss = { confirmDelete = false }) { Store.deleteCategory(initial.id); confirmDelete = false; onDismiss() }
}
