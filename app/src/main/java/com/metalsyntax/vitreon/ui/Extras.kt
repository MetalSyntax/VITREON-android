package com.metalsyntax.vitreon.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Atajos de teclado físico (App.tsx: Ctrl+N, Ctrl+S, Ctrl+F, Esc): la raíz los detecta y estas señales avisan a Inicio y al editor. */
object Shortcuts {
    var searchTick by mutableIntStateOf(0)
    var saveTick by mutableIntStateOf(0)
}

private class Section(val icon: ImageVector, val title: String, val body: String)

/** Política de privacidad (PrivacyPolicyModal.tsx) y términos (TermsModal.tsx), adaptados a la app: todo local, sin nube. */
@Composable
fun LegalSheet(terms: Boolean, onDismiss: () -> Unit) {
    val items = if (terms) listOf(
        Section(Icons.Outlined.CheckCircle, L("Aceptación de los términos", "Acceptance of Terms", "Aceitação dos termos"),
            L("Al usar Vitreon Notes aceptas estos Términos y Condiciones. Si no estás de acuerdo, deja de usar la aplicación.",
                "By using Vitreon Notes, you agree to these Terms and Conditions. If you do not agree, please discontinue use of the application.",
                "Ao usar o Vitreon Notes, você concorda com estes Termos e Condições. Caso não concorde, interrompa o uso do aplicativo.")),
        Section(Icons.Outlined.PersonOutline, L("Uso permitido", "Permitted Use", "Uso permitido"),
            L("Vitreon Notes es para uso personal y no comercial. Eres responsable del contenido que creas y guardas. No lo uses con fines ilegales, dañinos o abusivos.",
                "Vitreon Notes is provided for personal, non-commercial use. You are responsible for all content you create and store. You agree not to use the app for any illegal, harmful, or abusive purpose.",
                "O Vitreon Notes é para uso pessoal e não comercial. Você é responsável pelo conteúdo que cria e armazena. Não o use para fins ilegais, prejudiciais ou abusivos.")),
        Section(Icons.Outlined.Shield, L("Tus datos y propiedad", "Your Data & Ownership", "Seus dados e propriedade"),
            L("Conservas la propiedad de todas tus notas. Vitreon no accede, transmite ni reclama tus datos: se guardan en tu teléfono, cifrados con una clave propia del dispositivo.",
                "You retain full ownership of your notes. Vitreon does not access, transmit, or claim your data: it is stored on your phone, encrypted with a device-bound key.",
                "Você mantém a propriedade de todas as suas notas. O Vitreon não acessa, transmite nem reivindica seus dados: ficam no telefone, criptografados com uma chave do dispositivo.")),
        Section(Icons.Outlined.WarningAmber, L("Limitación de responsabilidad", "Limitation of Liability", "Limitação de responsabilidade"),
            L("Vitreon Notes se ofrece «tal cual», sin garantías. No respondemos por pérdidas de datos por fallo del dispositivo o error del usuario. Haz copias de seguridad con Exportar.",
                "Vitreon Notes is provided \"as is\" without warranties. We are not liable for data loss from device failure or user error. We strongly recommend regular backups using Export.",
                "O Vitreon Notes é fornecido \"como está\", sem garantias. Não nos responsabilizamos por perda de dados por falha do dispositivo ou erro do usuário. Faça backups com Exportar.")),
        Section(Icons.Outlined.Update, L("Cambios en los términos", "Changes to Terms", "Alterações nos termos"),
            L("Podemos actualizar estos Términos en cualquier momento; los cambios importantes se avisan en «Novedades».",
                "We may update these Terms at any time; major changes will be communicated in \"What's New\".",
                "Podemos atualizar estes Termos a qualquer momento; mudanças importantes aparecem em \"Novidades\".")),
    ) else listOf(
        Section(Icons.Outlined.Storage, L("Almacenamiento local", "Local-First Storage", "Armazenamento local"),
            L("Tus notas se guardan solo en tu teléfono. Vitreon no es dueño de tus datos ni tiene acceso a ellos; todo se procesa en el dispositivo.",
                "Your notes are stored only on your phone. Vitreon does not own or have access to your data; everything is processed on the device.",
                "Suas notas ficam apenas no seu telefone. O Vitreon não é dono nem tem acesso aos seus dados; tudo é processado no dispositivo.")),
        Section(Icons.Outlined.Lock, L("Cifrado", "Encryption", "Criptografia"),
            L("Todo se cifra con AES-GCM de 256 bits. Las notas bloqueadas usan además una clave derivada de tu PIN (PBKDF2) que nunca sale del dispositivo.",
                "Everything is encrypted with AES-GCM 256-bit. Locked notes also use a key derived from your PIN (PBKDF2) that never leaves the device.",
                "Tudo é criptografado com AES-GCM de 256 bits. As notas bloqueadas usam ainda uma chave derivada do PIN (PBKDF2) que nunca sai do dispositivo.")),
        Section(Icons.Outlined.CloudOff, L("Sin nube ni rastreo", "No cloud, no tracking", "Sem nuvem nem rastreamento"),
            L("La app no envía nada a internet ni te rastrea. Si exportas un respaldo, tú decides dónde guardarlo.",
                "The app sends nothing to the internet and does not track you. If you export a backup, you decide where it goes.",
                "O app não envia nada à internet nem rastreia você. Se exportar um backup, você decide onde guardá-lo.")),
        Section(Icons.Outlined.DeleteForever, L("Eliminación de datos", "Data Deletion", "Exclusão de dados"),
            L("Tienes el control total: exporta tus notas cuando quieras o usa «Borrar todos los datos» en Ajustes para limpiar el teléfono.",
                "You have full control: export your notes any time or use \"Delete all data\" in Settings to wipe the phone.",
                "Você tem controle total: exporte suas notas quando quiser ou use \"Apagar todos os dados\" em Ajustes para limpar o telefone.")),
    )
    Sheet(onDismiss, if (terms) L("Términos y condiciones", "Terms & Conditions", "Termos e condições") else L("Política de privacidad", "Privacy Policy", "Política de privacidade")) {
        items.forEach { s ->
            Row(Modifier.padding(vertical = 8.dp)) {
                Bubble(s.icon, com.metalsyntax.vitreon.ui.Brand, 36.dp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall, color = Ink)
                    Text(s.body, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
}

private class Release(val version: String, val date: String, val items: List<Section>)

/** «Novedades» (WhatsNewModal.tsx + CHANGELOG_ENTRIES de constants.ts). */
@Composable
fun WhatsNewSheet(onDismiss: () -> Unit) {
    val releases = listOf(
        Release("1.3.0", "2026-05-08", listOf(
            Section(Icons.Outlined.Badge, L("Importación inteligente", "Smart Import", "Importação inteligente"),
                L("Evita notas duplicadas detectando huellas digitales al importar.", "Prevents duplicate notes by detecting fingerprints during import.", "Evita notas duplicadas detectando impressões digitais ao importar.")),
            Section(Icons.Outlined.Compare, L("Resolución de conflictos", "Conflict Resolution", "Resolução de conflitos"),
                L("Resuelve a mano las diferencias entre notas locales y entrantes.", "Manually resolve differences between local and incoming notes.", "Resolva manualmente diferenças entre notas locais e recebidas.")),
            Section(Icons.Outlined.Shield, L("Privacidad y datos", "Privacy & Data", "Privacidade e dados"),
                L("Controles de privacidad y derecho a borrar todos tus datos.", "Privacy controls and the right to delete all your data.", "Controles de privacidade e direito de apagar todos os seus dados.")),
            Section(Icons.Outlined.Keyboard, L("Atajos de teclado", "Power User Shortcuts", "Atalhos de teclado"),
                L("Con teclado físico: Ctrl+N, Ctrl+S, Ctrl+F y Esc.", "With a hardware keyboard: Ctrl+N, Ctrl+S, Ctrl+F and Esc.", "Com teclado físico: Ctrl+N, Ctrl+S, Ctrl+F e Esc.")),
        )),
        Release("1.2.0", "2026-04-05", listOf(
            Section(Icons.Outlined.Search, L("Búsqueda rediseñada", "Redesigned Search", "Busca redesenhada"),
                L("Barra de búsqueda con orden, vista y selección integrados.", "A search bar that integrates sort, layout, and selection controls.", "Barra de busca com ordenação, layout e seleção integrados.")),
            Section(Icons.Outlined.OpenWith, L("Reordenación fluida", "Fluid Reordering", "Reordenação fluida"),
                L("Reorganiza tus notas arrastrándolas.", "Reorganize your notes by dragging.", "Reorganize suas notas arrastando.")),
            Section(Icons.Outlined.GridView, L("Diseños dinámicos", "Dynamic Layouts", "Layouts dinâmicos"),
                L("Cuadrícula, lista o tarjeta según tu estilo.", "Grid, List, or Card views to match your style.", "Grade, lista ou cartão conforme seu estilo.")),
        )),
    )
    Sheet(onDismiss, L("Novedades", "What's New", "Novidades")) {
        releases.forEach { r ->
            Caps("v${r.version} · ${r.date}", Modifier.padding(top = 8.dp, bottom = 4.dp))
            r.items.forEach { s ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    Bubble(s.icon, Brand, 36.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall, color = Ink)
                        Text(s.body, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
        }
    }
}

/** Consejo rotativo de Categorías (CategoriesView.tsx): cambia cada 8 s o al tocarlo. */
@Composable
fun TipBanner(modifier: Modifier = Modifier) {
    val tips = listOf(
        Triple(Icons.Outlined.AutoAwesome, L("Consejo Pro", "Pro Tip", "Dica Pro"), L("Mantén presionada cualquier categoría para editar su color e ícono.", "Tap and hold any category to edit its color and icon.", "Pressione e segure qualquer categoria para editar cor e ícone.")),
        Triple(Icons.Outlined.Archive, L("Movimiento inteligente", "Smart Move", "Movimento inteligente"), L("Archiva notas viejas para mantener tu tablero limpio y enfocado.", "Archive old notes to keep your dashboard clean and focused.", "Arquive notas antigas para manter o painel limpo e focado.")),
        Triple(Icons.Outlined.Lock, L("Bloqueo rápido", "Quick Lock", "Bloqueio rápido"), L("Usa el candado del editor para proteger datos sensibles con tu PIN.", "Use the lock icon in the editor to protect sensitive data with a PIN.", "Use o cadeado do editor para proteger dados sensíveis com um PIN.")),
        Triple(Icons.Outlined.Search, L("Búsqueda+", "Search+", "Busca+"), L("La búsqueda también mira dentro de tus etiquetas; prueba #etiqueta.", "Search also looks inside your tags; try #tag.", "A busca também olha dentro das tags; tente #tag.")),
    )
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(i) { delay(8000); i = (i + 1) % tips.size }
    val (icon, title, text) = tips[i]
    Glass(modifier.fillMaxWidth(), color = Brand.copy(alpha = 0.12f), onClick = { i = (i + 1) % tips.size }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Bubble(icon, Brand, 48.dp); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Ink)
                Text(text, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}
