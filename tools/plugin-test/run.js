// Prueba del host de plugins sin teléfono: extrae SHIM de Plugins.kt, carga el plugin de ejemplo y ejecuta un comando.
// Uso: node tools/plugin-test/run.js   (desde la raíz del repo)
const fs = require('fs'), path = require('path');
const kt = fs.readFileSync(path.join(__dirname, '../../app/src/main/java/com/metalsyntax/vitreon/plugins/Plugins.kt'), 'utf8');
const shim = kt.slice(kt.indexOf('private val SHIM = """') + 'private val SHIM = """'.length, kt.lastIndexOf('"""'));
const out = [];
global.window = global; global.document = { createElement: () => ({}) };
window.VitHost = { files: () => '[]', read: () => '', active: () => null, modify() {}, create: p => JSON.stringify({ path: p }), trash() {},
  notice: m => out.push('notice:' + m), loadData: () => null, saveData() {}, command: (p, id) => out.push('cmd:' + id), error: (p, e) => out.push('error:' + e),
  done: j => { out.push('done:' + j); check(); } };
new Function(shim)();
const code = fs.readFileSync(path.join(__dirname, 'MiBoveda/.obsidian/plugins/sort-lines/main.js'), 'utf8');
__vit.load('sort-lines', JSON.stringify({ id: 'sort-lines' }), new Function('module', 'exports', 'require', code));
setTimeout(() => __vit.run('sort-lines', 'sort-lines', 'b\na\nc', 0, 0), 20);
function check() {
  const ok = out.includes('cmd:sort-lines') && out.includes('cmd:daily') && out.some(l => l.includes('"text":"a\\nb\\nc"')) && !out.some(l => l.startsWith('error:'));
  console.log(out.join('\n')); console.log(ok ? 'OK' : 'FALLÓ'); process.exit(ok ? 0 : 1);
}
