package com.metalsyntax.vitreon

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.metalsyntax.vitreon.data.*
import com.metalsyntax.vitreon.plugins.Plugins
import com.metalsyntax.vitreon.ui.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

enum class Tab { HOME, CATEGORIES, PROFILE, SETTINGS }

class MainActivity : FragmentActivity() {
    /** Texto compartido desde otra app: se abre como nota nueva. */
    private var shared by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Prefs.init(this); Pin.init(this)
        Store.version = BuildConfig.VERSION_NAME
        Store.init(filesDir)
        Plugins.init(this); Plugins.attach(this)
        readShare(intent)
        setContent { VitreonTheme { App(shared) { shared = null } } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); readShare(intent) }

    private fun readShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { shared = (i.getStringExtra(Intent.EXTRA_SUBJECT)?.let { s -> "# $s\n\n" } ?: "") + it }
    }

    override fun onStop() {
        super.onStop()
        Store.flush()
        // al salir se bloquea (salvo que la app haya abierto cámara, archivos, etc.)
        if (Pin.lockOnExit && !Pin.external) Pin.lock()
    }

    override fun onResume() { super.onResume(); Pin.external = false }

    override fun onDestroy() { Plugins.detach(); super.onDestroy() }
}

@Composable
private fun App(shared: String?, consumeShared: () -> Unit) {
    if (Pin.locked) { LockScreen(); return }
    if (!Prefs.onboarded) { Onboarding(); return }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var view by rememberSaveable { mutableStateOf(View.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    // editor: id de la nota abierta, "new" para una nueva
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var newText by remember { mutableStateOf<String?>(null) }
    var newCategory by remember { mutableStateOf<String?>(null) }
    var linkAsk by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun snack(m: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(m) } }
    Plugins.notice = ::snack
    LaunchedEffect(shared) { shared?.let { newText = it; editing = "new"; consumeShared() } }
    Store.loadError?.let { e -> LaunchedEffect(e) { snack("No se pudo leer el archivo de datos; se guardó aparte: $e"); Store.loadError = null } }
    BackHandler(enabled = editing == null && (tab != Tab.HOME || view != View.ALL || query.isNotEmpty())) {
        when { query.isNotEmpty() -> query = ""; view != View.ALL -> view = View.ALL; else -> tab = Tab.HOME }
    }

    // atajos de teclado físico (App.tsx): Ctrl+N nota nueva, Ctrl+F buscar, Ctrl+S guardar, Esc atrás
    val rootFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Box(Modifier.fillMaxSize().background(Bg).background(Backdrop).focusRequester(rootFocus).focusable().onPreviewKeyEvent { e ->
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            e.key == androidx.compose.ui.input.key.Key.Escape -> { backDispatcher?.onBackPressed(); true }
            !e.isCtrlPressed -> false
            e.key == androidx.compose.ui.input.key.Key.N -> { newText = null; newCategory = category; editing = "new"; true }
            e.key == androidx.compose.ui.input.key.Key.S && editing != null -> { Shortcuts.saveTick++; true }
            e.key == androidx.compose.ui.input.key.Key.F && editing == null -> { tab = Tab.HOME; Shortcuts.searchTick++; true }
            else -> false
        }
    }) {
        Scaffold(containerColor = Color.Transparent,
            bottomBar = { BottomBar(tab, { tab = it; if (it == Tab.HOME) view = View.ALL }) { newText = null; newCategory = category; editing = "new" } }) { pad ->
            AnimatedContent(tab, Modifier.padding(pad).statusBarsPadding(), label = "tab", transitionSpec = { fadeIn() togetherWith fadeOut() }) { t ->
                when (t) {
                    Tab.HOME -> HomeScreen(HomeNav(open = { editing = it.id }, snack = ::snack, categories = { tab = Tab.CATEGORIES }, profile = { tab = Tab.PROFILE }),
                        view, { view = it }, query, { query = it }, category, { category = it })
                    Tab.CATEGORIES -> CategoriesScreen { id -> category = id; tab = Tab.HOME; view = View.ALL; query = "" }
                    Tab.PROFILE -> ProfileScreen(::snack)
                    Tab.SETTINGS -> SettingsScreen(::snack)
                }
            }
        }
        editing?.let { id ->
            key(id) {
                NoteEditor(if (id == "new") null else id, newCategory, newText, EditorNav(
                    close = { editing = null; newText = null },
                    openNote = { title ->
                        val target = Store.data.byTitle(title)
                        if (target != null) editing = target.id else linkAsk = title
                    },
                    openTag = { tg -> editing = null; tab = Tab.HOME; view = View.ALL; query = "#$tg" },
                    snack = ::snack,
                ))
            }
        }
        // avisos por encima de todo (también del editor y de los plugins)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp)) {
            Snackbar(it, containerColor = Brand, contentColor = Color.White, shape = RoundedCornerShape(18.dp))
        }
    }
    // [[enlace]] a una nota que no existe: crearla (como Obsidian)
    linkAsk?.let { title ->
        Confirm(L("Crear «$title»", "Create «$title»", "Criar «$title»"), L("Esa nota todavía no existe.", "That note doesn't exist yet.", "Essa nota ainda não existe."),
            L("Crear", "Create", "Criar"), onDismiss = { linkAsk = null }) {
            val n = Note(title = title, category = Store.data.note(editing)?.category ?: UNCATEGORIZED)
            Store.save(n); linkAsk = null; editing = n.id
        }
    }
}

/** Barra inferior de vidrio con el botón + en degradado índigo → violeta (App.tsx). */
@Composable
private fun BottomBar(tab: Tab, go: (Tab) -> Unit, onAdd: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp).clip(RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp))
            .background(if (Prefs.mode.dark) Color(0xE60B0F1F) else Color(0xEBFFFFFF)).navigationBarsPadding().height(68.dp),
            verticalAlignment = Alignment.CenterVertically) {
            NavItem(Icons.Outlined.Home, L("Inicio", "Home", "Início"), tab == Tab.HOME) { go(Tab.HOME) }
            NavItem(Icons.Outlined.Folder, L("Categorías", "Categories", "Categorias"), tab == Tab.CATEGORIES) { go(Tab.CATEGORIES) }
            Spacer(Modifier.width(76.dp))
            NavItem(Icons.Outlined.Person, L("Perfil", "Profile", "Perfil"), tab == Tab.PROFILE) { go(Tab.PROFILE) }
            NavItem(Icons.Outlined.Settings, L("Ajustes", "Settings", "Ajustes"), tab == Tab.SETTINGS) { go(Tab.SETTINGS) }
        }
        Box(Modifier.align(Alignment.TopCenter).size(64.dp).shadow(18.dp, CircleShape, spotColor = Brand).clip(CircleShape).background(FabGradient).tap(onClick = onAdd),
            contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, L("Nueva nota", "New note", "Nova nota"), tint = Color.White, modifier = Modifier.size(32.dp)) }
    }
}

@Composable
private fun RowScope.NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxHeight().tap(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, label, tint = if (selected) Brand else Muted, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = if (selected) Ink else Muted, maxLines = 1)
    }
}

/** Bienvenida: qué es Vitreon y desde dónde importar. Sin pantallas de cookies: nada sale del teléfono. */
@Composable
private fun Onboarding() {
    Box(Modifier.fillMaxSize().background(Backdrop).statusBarsPadding().navigationBarsPadding().padding(28.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(32.dp)).background(FabGradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.EditNote, null, tint = Color.White, modifier = Modifier.size(52.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Vitreon", style = MaterialTheme.typography.displaySmall, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text(L("Notas en Markdown, cifradas en tu teléfono. Trae las tuyas de Google Keep, Notion u Obsidian desde Ajustes → Importar.",
                "Markdown notes, encrypted on your phone. Bring yours from Google Keep, Notion or Obsidian in Settings → Import.",
                "Notas em Markdown, criptografadas no seu telefone. Traga as suas do Google Keep, Notion ou Obsidian em Ajustes → Importar."),
                style = MaterialTheme.typography.bodyLarge, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Lang.entries.forEach { l -> Chip(l.code.uppercase(), Prefs.lang == l) { Prefs.applyLang(l) } }
            }
            Spacer(Modifier.height(28.dp))
            BrandButton(L("Empezar", "Get started", "Começar"), Modifier.fillMaxWidth(), Icons.Outlined.ArrowForward) { Prefs.finishOnboarding() }
        }
    }
}
