package com.metalsyntax.vitreon.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Texto en el idioma actual (es, en, pt). Leer Prefs.lang lo vuelve reactivo: cambiar idioma recompone todo al momento. */
fun L(es: String, en: String, pt: String = en): String = when (Prefs.lang) { Lang.ES -> es; Lang.EN -> en; Lang.PT -> pt }

// ================================================================================================ interacción

/** active:scale-95 de la web: el elemento se encoge con un resorte al tocarlo. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tap(onLongClick: (() -> Unit)? = null, onClick: (() -> Unit)?): Modifier = if (onClick == null && onLongClick == null) this else composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "tap")
    graphicsLayer { scaleX = s; scaleY = s }.combinedClickable(interactionSource = src, indication = androidx.compose.material.ripple.rememberRipple(color = Ink),
        onClick = { onClick?.invoke() }, onLongClick = onLongClick)
}

/** Reordenar arrastrando (asa ⋮⋮ con pulsación larga). Igual que en Parity, para Column/LazyColumn. */
class ReorderState {
    var onMove: (Any, Any) -> Unit = { _, _ -> }
    var onDrop: () -> Unit = {}
    var dragging by mutableStateOf<Any?>(null); private set
    var offset by mutableFloatStateOf(0f); private set
    internal val placed = HashMap<Any, Pair<Int, Int>>()
    fun start(key: Any) { dragging = key; offset = 0f }
    fun end() { if (dragging != null) onDrop(); dragging = null; offset = 0f }
    fun drag(dy: Float) {
        offset += dy
        val cur = placed[dragging ?: return] ?: return
        val mid = cur.first + offset + cur.second / 2f
        val target = placed.entries.firstOrNull { it.key != dragging && mid >= it.value.first && mid <= it.value.first + it.value.second } ?: return
        onMove(dragging!!, target.key)
        offset += cur.first - target.value.first
    }
}

@Composable
fun rememberReorder(onMove: (Any, Any) -> Unit, onDrop: () -> Unit) = remember { ReorderState() }.also { it.onMove = onMove; it.onDrop = onDrop }

fun Modifier.reorderItem(st: ReorderState, key: Any) = composed {
    val on = st.dragging == key
    val lift by animateFloatAsState(if (on) 1.03f else 1f, label = "lift")
    onPlaced { st.placed[key] = it.positionInParent().y.toInt() to it.size.height }.zIndex(if (on) 1f else 0f)
        .graphicsLayer { translationY = if (on) st.offset else 0f; scaleX = lift; scaleY = lift; shadowElevation = if (on) 24f else 0f }
}

@Composable
fun DragHandle(st: ReorderState, key: Any) {
    Icon(Icons.Outlined.DragIndicator, L("Mover", "Move", "Mover"), tint = Muted.copy(alpha = 0.6f), modifier = Modifier.size(32.dp).pointerInput(key) {
        detectDragGesturesAfterLongPress(onDragStart = { st.start(key) }, onDragEnd = st::end, onDragCancel = st::end) { c, d -> c.consume(); st.drag(d.y) }
    }.padding(4.dp))
}

fun <T> List<T>.move(from: Any, to: Any, key: (T) -> Any): List<T> {
    val a = indexOfFirst { key(it) == from }; val b = indexOfFirst { key(it) == to }
    if (a < 0 || b < 0) return this
    return toMutableList().apply { add(b, removeAt(a)) }
}

// ================================================================================================ superficies

/** Tarjeta de vidrio: superficie translúcida con radio grande (32 en la web), sin borde fuerte. */
@Composable
fun Glass(modifier: Modifier = Modifier, radius: Dp = 28.dp, color: Color = Surface1, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null,
          content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.tap(onLongClick, onClick).clip(RoundedCornerShape(radius)).background(color), content = content)
}

@Composable
fun Caps(text: String, modifier: Modifier = Modifier, color: Color = Muted.copy(alpha = 0.8f)) =
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
fun RoundIcon(icon: ImageVector, desc: String?, onClick: () -> Unit, tint: Color = Ink, size: Dp = 44.dp, bg: Color = Surface1, onLongClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Box(modifier.size(size).tap(onLongClick, onClick).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.48f))
    }
}

@Composable
fun BrandButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, color: Color = Brand, onClick: () -> Unit) {
    Row(modifier.height(50.dp).tap(onClick = if (enabled) onClick else null).clip(RoundedCornerShape(16.dp)).background(if (enabled) color else Surface2)
        .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        icon?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = if (enabled) Color.White else Muted, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun GhostButton(text: String, icon: ImageVector? = null, color: Color = Ink, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.height(46.dp).tap(onClick = onClick).clip(RoundedCornerShape(14.dp)).background(Surface1).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun Bubble(icon: ImageVector, tint: Color, size: Dp = 40.dp) =
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }

@Composable
fun Chip(text: String, selected: Boolean, color: Color = Brand, icon: ImageVector? = null, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) color else Surface1, label = "chip")
    Row(Modifier.height(38.dp).tap(onClick = onClick).clip(CircleShape).background(bg).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, tint = if (selected) Color.White else color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = if (selected) Color.White else Ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(checked, onChange,
    colors = SwitchDefaults.colors(checkedTrackColor = Brand, checkedThumbColor = Color.White, uncheckedTrackColor = Surface2,
        uncheckedThumbColor = Muted, uncheckedBorderColor = Color.Transparent, checkedBorderColor = Color.Transparent))

@Composable
fun SettingRow(title: String, desc: String? = null, icon: ImageVector? = null, tint: Color = Brand, titleColor: Color = Ink,
               onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().tap(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Bubble(it, tint); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
            desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
        Spacer(Modifier.width(12.dp)); trailing()
    }
}

@Composable
fun Chevron() = Icon(Icons.Outlined.ChevronRight, null, tint = Muted)

@Composable
fun Group(label: String, icon: ImageVector, tint: Color = Brand, content: @Composable ColumnScope.() -> Unit) {
    Glass(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(8.dp)); Caps(label)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun Header(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { RoundIcon(Icons.Outlined.ArrowBack, L("Volver", "Back", "Voltar"), it); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun Empty(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Bubble(icon, Brand, 72.dp); Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ================================================================================================ hojas y diálogos

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) =
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (Prefs.mode.dark) Color(0xFF0F1220) else Color.White,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), dragHandle = {
            Box(Modifier.padding(vertical = 12.dp).size(40.dp, 4.dp).clip(CircleShape).background(Surface2))
        }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding().verticalScroll(rememberScrollState())) {
            title?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, color = Ink)
                    RoundIcon(Icons.Outlined.Close, L("Cerrar", "Close", "Fechar"), onDismiss, tint = Muted, size = 40.dp)
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }

@Composable
fun Confirm(title: String, text: String, action: String, danger: Boolean = false, onDismiss: () -> Unit, extra: (@Composable () -> Unit)? = null, onConfirm: () -> Unit) =
    AlertDialog(onDismissRequest = onDismiss, containerColor = if (Prefs.mode.dark) Color(0xFF0F1220) else Color.White, shape = RoundedCornerShape(24.dp),
        title = { Text(title, color = Ink, style = MaterialTheme.typography.titleLarge) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { if (text.isNotEmpty()) Text(text, color = Muted); extra?.invoke() } },
        confirmButton = { BrandButton(action, color = if (danger) Danger else Brand, onClick = onConfirm) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancelar", "Cancel", "Cancelar"), color = Muted) } })

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Surface1, unfocusedContainerColor = Surface1, focusedBorderColor = Brand, unfocusedBorderColor = Color.Transparent,
    focusedTextColor = Ink, unfocusedTextColor = Ink, cursorColor = Brand, focusedLabelColor = Brand, unfocusedLabelColor = Muted,
    focusedPlaceholderColor = Muted, unfocusedPlaceholderColor = Muted, focusedLeadingIconColor = Muted, unfocusedLeadingIconColor = Muted,
    focusedTrailingIconColor = Muted, unfocusedTrailingIconColor = Muted)

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(), singleLine: Boolean = true,
          password: Boolean = false, number: Boolean = false) =
    OutlinedTextField(value, onChange, modifier.padding(vertical = 4.dp), label = { Text(label) }, singleLine = singleLine, shape = RoundedCornerShape(16.dp),
        colors = fieldColors(),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = when {
            number -> androidx.compose.ui.text.input.KeyboardType.NumberPassword
            password -> androidx.compose.ui.text.input.KeyboardType.Password
            else -> androidx.compose.ui.text.input.KeyboardType.Text
        }))

fun toast(ctx: android.content.Context, msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

fun dateLabel(ms: Long): String {
    val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when (d) {
        today -> L("Hoy", "Today", "Hoje")
        today.minusDays(1) -> L("Ayer", "Yesterday", "Ontem")
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMM" else "d MMM yyyy",
            java.util.Locale(Prefs.lang.code)))
    }
}
