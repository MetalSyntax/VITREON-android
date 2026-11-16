package com.metalsyntax.vitreon.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metalsyntax.vitreon.data.Note
import com.metalsyntax.vitreon.data.Store
import com.metalsyntax.vitreon.data.now
import com.metalsyntax.vitreon.importers.SourceFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Plugin instalado (manifest.json de Obsidian). */
data class PluginInfo(val id: String, val name: String, val version: String, val author: String, val description: String, val dir: File)

/** Comando registrado por un plugin con addCommand / addRibbonIcon. [editor] = usa el editor (editorCallback). */
data class PluginCommand(val pluginId: String, val id: String, val name: String, val editor: Boolean)

/** Resultado de ejecutar un comando de editor: texto y selección nuevos (null = no cambió). */
data class EditResult(val text: String?, val selStart: Int, val selEnd: Int)

/**
 * Plugins de Obsidian en un WebView invisible y aislado (sin red, sin archivos). Se carga [SHIM] como módulo `obsidian`
 * y cada main.js (CommonJS) se evalúa con un `require` propio. La bóveda que ven los plugins son las notas de Vitreon,
 * con rutas «Categoría/Título.md».
 */
object Plugins {
    private lateinit var root: File
    private lateinit var sp: android.content.SharedPreferences
    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    var installed by mutableStateOf(emptyList<PluginInfo>()); private set
    var enabled by mutableStateOf(emptySet<String>()); private set
    var commands by mutableStateOf(emptyList<PluginCommand>()); private set
    /** Errores por plugin (API no compatible, excepción al cargar). */
    var errors by mutableStateOf(emptyMap<String, String>()); private set
    /** Avisos `new Notice()` para mostrar como snackbar. */
    var notice: (String) -> Unit = {}
    /** Editor abierto en este momento (para app.workspace.getActiveFile / activeEditor). */
    var active: Note? = null

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "plugins").apply { mkdirs() }
        sp = ctx.getSharedPreferences("plugins", Context.MODE_PRIVATE)
        enabled = sp.getStringSet("enabled", emptySet())!!.toSet()
        scan()
    }

    private fun scan() {
        installed = root.listFiles().orEmpty().filter { File(it, "manifest.json").exists() && File(it, "main.js").exists() }.mapNotNull { d ->
            runCatching {
                val m = JSONObject(File(d, "manifest.json").readText())
                PluginInfo(m.getString("id"), m.optString("name", m.getString("id")), m.optString("version"), m.optString("author"), m.optString("description"), d)
            }.getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Instala desde lo elegido por el usuario: una bóveda (.obsidian/plugins/<id>/…), una carpeta de plugin o un zip.
     * Solo se copian manifest.json, main.js, styles.css y data.json. Devuelve los ids instalados.
     */
    fun install(files: List<SourceFile>): List<String> {
        val manifests = files.filter { it.name == "manifest.json" && files.any { f -> f.path == it.path.removeSuffix("manifest.json") + "main.js" } }
        return manifests.mapNotNull { m ->
            val id = runCatching { JSONObject(m.text).getString("id") }.getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) } ?: return@mapNotNull null
            val base = m.path.removeSuffix("manifest.json")
            val dir = File(root, id).apply { deleteRecursively(); mkdirs() }
            for (n in listOf("manifest.json", "main.js", "styles.css", "data.json")) files.firstOrNull { it.path == base + n }?.let { File(dir, n).writeBytes(it.bytes) }
            id
        }.also { scan() }
    }

    fun uninstall(id: String) { File(root, id).deleteRecursively(); setEnabled(id, false); scan() }

    fun setEnabled(id: String, on: Boolean) {
        enabled = if (on) enabled + id else enabled - id
        sp.edit().putStringSet("enabled", enabled).apply()
        reload()
    }

    /** (Re)crea el WebView y carga los plugins activos. Llamar con la Activity creada. */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(ctx: Context) {
        web?.destroy()
        web = WebView(ctx.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.blockNetworkLoads = true      // los plugins no salen a internet
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(Bridge, "VitHost")
        }
        reload()
    }

    fun detach() { web?.destroy(); web = null }

    private fun reload() {
        val w = web ?: return
        commands = emptyList(); errors = emptyMap()
        val active = installed.filter { it.id in enabled }
        val script = StringBuilder(SHIM)
        active.forEach { p ->
            val code = File(p.dir, "main.js").readText()
            script.append("\n__vit.load(").append(JSONObject.quote(p.id)).append(",").append(JSONObject.quote(File(p.dir, "manifest.json").readText()))
                .append(", function(module, exports, require){\n").append(code).append("\n});")
        }
        w.loadDataWithBaseURL("https://plugins.vitreon.invalid/", "<!doctype html><html><body><script>$script</script></body></html>", "text/html", "utf-8", null)
    }

    /** Ejecuta un comando. Si es de editor, recibe el texto y la selección actuales y devuelve los cambios. */
    suspend fun run(c: PluginCommand, text: String = "", selStart: Int = 0, selEnd: Int = 0): EditResult? = suspendCancellableCoroutine { k ->
        val w = web ?: return@suspendCancellableCoroutine k.resume(null)
        val js = "__vit.run(${JSONObject.quote(c.pluginId)},${JSONObject.quote(c.id)},${JSONObject.quote(text)},$selStart,$selEnd)"
        pending = { json -> k.resume(json?.let { o -> EditResult(o.optString("text").takeIf { o.has("text") && !o.isNull("text") }, o.optInt("s"), o.optInt("e")) }) }
        main.post { w.evaluateJavascript(js, null) }
    }

    @Volatile private var pending: ((JSONObject?) -> Unit)? = null

    // ---------------------------------------------------------------------------------------- bóveda para los plugins

    private fun pathOf(n: Note) = Store.safeName(Store.data.category(n.category).name) + "/" + Store.safeName(n.title.ifBlank { "Nota" }) + ".md"
    private fun byPath(path: String) = Store.data.notes.firstOrNull { !it.trashed && !it.locked && pathOf(it) == path }
        ?: Store.data.notes.firstOrNull { !it.trashed && !it.locked && it.title.equals(path.substringAfterLast('/').removeSuffix(".md"), true) }

    private fun fileJson(n: Note) = JSONObject().put("path", pathOf(n)).put("name", pathOf(n).substringAfterLast('/'))
        .put("basename", n.title).put("extension", "md").put("ctime", n.createdAt).put("mtime", n.updatedAt).put("size", n.content.length)

    /** Lo que el JavaScript puede llamar. Solo notas (no bloqueadas ni en la papelera) y los datos del propio plugin. */
    object Bridge {
        @JavascriptInterface fun files(): String = JSONArray(Store.data.notes.filter { !it.trashed && !it.locked }.map { fileJson(it) }).toString()
        @JavascriptInterface fun read(path: String): String? = byPath(path)?.content
        @JavascriptInterface fun active(): String? = active?.let { fileJson(it).toString() }
        @JavascriptInterface fun modify(path: String, text: String): Boolean {
            val n = byPath(path) ?: return false
            main.post { Store.update(n.id) { it.copy(content = text) } }; return true
        }
        @JavascriptInterface fun create(path: String, text: String): String {
            val title = path.substringAfterLast('/').removeSuffix(".md")
            val cat = path.substringBeforeLast('/', "").substringAfterLast('/')
            val n = Note(title = title, content = text, createdAt = now())
            main.post { Store.save(n.copy(category = Store.categoryFor(cat.ifBlank { null }))) }
            return fileJson(n).put("path", path).toString()
        }
        @JavascriptInterface fun trash(path: String): Boolean { val n = byPath(path) ?: return false; main.post { Store.trash(setOf(n.id)) }; return true }
        @JavascriptInterface fun notice(msg: String) { main.post { notice(msg) } }
        @JavascriptInterface fun loadData(id: String): String? = File(root, "$id/data.json").takeIf { it.exists() }?.readText()
        @JavascriptInterface fun saveData(id: String, json: String) { File(root, id).takeIf { it.isDirectory }?.let { File(it, "data.json").writeText(json) } }
        @JavascriptInterface fun command(pluginId: String, id: String, name: String, editor: Boolean) {
            main.post { commands = commands.filterNot { it.pluginId == pluginId && it.id == id } + PluginCommand(pluginId, id, name, editor) }
        }
        @JavascriptInterface fun error(pluginId: String, msg: String) { main.post { errors = errors + (pluginId to msg) } }
        @JavascriptInterface fun done(json: String?) { val p = pending; pending = null; main.post { p?.invoke(json?.let { runCatching { JSONObject(it) }.getOrNull() }) } }
    }
}

/**
 * Módulo `obsidian` mínimo. Lo que no existe en un teléfono (vistas, paneles, CodeMirror, DOM de Obsidian) queda como
 * función vacía para que el plugin cargue; si el plugin depende de eso, sus comandos simplemente no hacen nada visible.
 */
private val SHIM = """
(function(){
  const H = window.VitHost;
  const plugins = {}; const cmds = {};
  const ok = v => Promise.resolve(v);
  function tfile(o){ if(!o) return null; o = typeof o === 'string' ? JSON.parse(o) : o;
    return Object.assign(Object.create(TFile.prototype), o, {stat:{ctime:o.ctime, mtime:o.mtime, size:o.size}}); }
  class TAbstractFile {} class TFile extends TAbstractFile {} class TFolder extends TAbstractFile {}
  class Events { on(){ return {}; } off(){} offref(){} trigger(){} }
  const vault = Object.assign(new Events(), {
    getMarkdownFiles(){ return JSON.parse(H.files()).map(tfile); },
    getFiles(){ return this.getMarkdownFiles(); },
    getAbstractFileByPath(p){ return this.getMarkdownFiles().find(f => f.path === p) || null; },
    getFileByPath(p){ return this.getAbstractFileByPath(p); },
    read(f){ return ok(H.read(f.path)); }, cachedRead(f){ return ok(H.read(f.path)); },
    modify(f, t){ H.modify(f.path, t); return ok(); },
    process(f, fn){ const t = fn(H.read(f.path)); H.modify(f.path, t); return ok(t); },
    append(f, t){ H.modify(f.path, (H.read(f.path)||'') + t); return ok(); },
    create(p, t){ return ok(tfile(H.create(p, t||''))); },
    delete(f){ H.trash(f.path); return ok(); }, trash(f){ H.trash(f.path); return ok(); },
    getName(){ return 'Vitreon'; }, adapter: { exists: p => ok(!!H.read(p)), read: p => ok(H.read(p)||''), write: (p,t) => ok(H.modify(p,t)) },
  });
  const workspace = Object.assign(new Events(), {
    getActiveFile(){ return tfile(H.active()); }, onLayoutReady(cb){ setTimeout(cb, 0); },
    getActiveViewOfType(){ return window.__vit && window.__vit.view; },
    getLeavesOfType(){ return []; }, getLeaf(){ return { openFile(){ return ok(); }, setViewState(){ return ok(); } }; },
    openLinkText(){ return ok(); }, iterateAllLeaves(){},
  });
  // getter aparte: Object.assign lo evaluaría antes de que exista __vit
  Object.defineProperty(workspace, 'activeEditor', { get: () => window.__vit && window.__vit.view });
  const app = { vault, workspace, metadataCache: Object.assign(new Events(), { getFileCache(){ return null; }, getFirstLinkpathDest(){ return null; } }),
    fileManager: { processFrontMatter(f, fn){ return ok(); }, renameFile(){ return ok(); } }, commands: { executeCommandById(){} } };
  class Component { load(){} onload(){} unload(){} onunload(){} addChild(c){ return c; } register(){} registerEvent(){} registerDomEvent(){} registerInterval(i){ return i; } }
  class Plugin extends Component {
    constructor(app, manifest){ super(); this.app = app; this.manifest = manifest; }
    addCommand(c){ cmds[this.manifest.id + ':' + c.id] = c; H.command(this.manifest.id, c.id, c.name || c.id, !!(c.editorCallback || c.editorCheckCallback)); return c; }
    addRibbonIcon(icon, title, cb){ this.addCommand({ id: 'ribbon-' + title, name: title, callback: cb }); return document.createElement('div'); }
    addStatusBarItem(){ return document.createElement('div'); }
    addSettingTab(){} registerView(){} registerMarkdownPostProcessor(){} registerMarkdownCodeBlockProcessor(){} registerEditorExtension(){}
    registerObsidianProtocolHandler(){} registerEditorSuggest(){}
    loadData(){ const d = H.loadData(this.manifest.id); return ok(d ? JSON.parse(d) : null); }
    saveData(d){ H.saveData(this.manifest.id, JSON.stringify(d)); return ok(); }
  }
  class Notice { constructor(m){ H.notice(typeof m === 'string' ? m : (m && m.textContent) || ''); } setMessage(m){ H.notice(m); return this; } hide(){} }
  class Modal { constructor(app){ this.app = app; this.contentEl = document.createElement('div'); this.titleEl = document.createElement('div'); } open(){ this.onOpen && this.onOpen(); } close(){ this.onClose && this.onClose(); } }
  class PluginSettingTab { constructor(app, plugin){ this.app = app; this.plugin = plugin; this.containerEl = document.createElement('div'); } display(){} hide(){} }
  class Setting { constructor(){ const s = this; return new Proxy(this, { get: (t, k) => k in t ? t[k] : () => s }); } }
  class MarkdownView {} class ItemView {} class Menu { addItem(){ return this; } showAtMouseEvent(){} showAtPosition(){} }
  const obsidian = { Plugin, Component, Notice, Modal, PluginSettingTab, Setting, MarkdownView, ItemView, Menu, TFile, TFolder, TAbstractFile, Events,
    normalizePath: p => p.replace(/\\/g,'/').replace(/\/+/g,'/').replace(/^\/|\/$/g,''),
    debounce: (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms||0); }; },
    Platform: { isMobile: true, isMobileApp: true, isDesktop: false, isDesktopApp: false, isAndroidApp: true, isIosApp: false },
    requestUrl: () => Promise.reject(new Error('Sin red en Vitreon')), setIcon(){}, addIcon(){}, getAllTags: () => [],
    parseYaml: s => { const o = {}; (s||'').split('\n').forEach(l => { const i = l.indexOf(':'); if (i > 0) o[l.slice(0,i).trim()] = l.slice(i+1).trim(); }); return o; },
    stringifyYaml: o => Object.entries(o||{}).map(([k,v]) => k + ': ' + v).join('\n'),
    moment: (d) => { const x = d ? new Date(d) : new Date(); return { format: () => x.toISOString().slice(0,10), toDate: () => x }; },
  };
  function editorFor(text, s, e){
    const st = { text, s, e };
    const pos = off => { const before = st.text.slice(0, off).split('\n'); return { line: before.length - 1, ch: before[before.length-1].length }; };
    const off = p => { const ls = st.text.split('\n'); let o = 0; for (let i = 0; i < p.line && i < ls.length; i++) o += ls[i].length + 1; return Math.min(o + p.ch, st.text.length); };
    const ed = {
      getValue: () => st.text, setValue: t => { st.text = t; st.s = st.e = Math.min(st.s, t.length); },
      getSelection: () => st.text.slice(st.s, st.e), somethingSelected: () => st.e > st.s,
      replaceSelection: t => { st.text = st.text.slice(0, st.s) + t + st.text.slice(st.e); st.s = st.e = st.s + t.length; },
      getCursor: w => pos(w === 'from' || w === 'anchor' ? st.s : st.e), setCursor: p => { st.s = st.e = off(typeof p === 'number' ? {line:p, ch:0} : p); },
      setSelection: (a, b) => { st.s = off(a); st.e = off(b || a); }, getLine: n => st.text.split('\n')[n] || '', setLine: (n, t) => { const ls = st.text.split('\n'); ls[n] = t; st.text = ls.join('\n'); },
      lineCount: () => st.text.split('\n').length, lastLine: () => st.text.split('\n').length - 1,
      getRange: (a, b) => st.text.slice(off(a), off(b)), replaceRange: (t, a, b) => { const x = off(a), y = b ? off(b) : x; st.text = st.text.slice(0, x) + t + st.text.slice(y); },
      offsetToPos: pos, posToOffset: off, focus(){}, blur(){}, scrollIntoView(){}, refresh(){}, transaction(){},
    };
    return { ed, st };
  }
  window.__vit = {
    view: null,
    load(id, manifest, fn){
      try {
        const module = { exports: {} };
        fn(module, module.exports, name => { if (name === 'obsidian') return obsidian; throw new Error('Módulo no disponible: ' + name); });
        const C = module.exports.default || module.exports;
        const p = new C(app, JSON.parse(manifest));
        plugins[id] = p;
        Promise.resolve(p.onload && p.onload()).catch(e => H.error(id, String(e && e.message || e)));
      } catch (e) { H.error(id, String(e && e.message || e)); }
    },
    async run(pid, cid, text, s, e){
      const c = cmds[pid + ':' + cid];
      try {
        if (!c) return H.done(null);
        if (c.editorCallback || c.editorCheckCallback) {
          const { ed, st } = editorFor(text, s, e);
          const view = { editor: ed, file: tfile(H.active()), getViewType: () => 'markdown' };
          this.view = view;
          if (c.editorCallback) await c.editorCallback(ed, view); else await c.editorCheckCallback(false, ed, view);
          this.view = null;
          return H.done(JSON.stringify({ text: st.text === text ? null : st.text, s: st.s, e: st.e }));
        }
        if (c.callback) await c.callback(); else if (c.checkCallback) await c.checkCallback(false);
        H.done(JSON.stringify({ text: null, s, e }));
      } catch (err) { H.error(pid, String(err && err.message || err)); H.done(null); }
    },
  };
})();
"""
