package com.metalsyntax.vitreon.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.metalsyntax.vitreon.data.Pin
import com.metalsyntax.vitreon.data.Store
import kotlinx.coroutines.delay

/** Teclado de PIN de 4 dígitos. [onEnter] devuelve true si el PIN sirvió (si no, muestra error). */
@Composable
fun PinPad(title: String, subtitle: String, onEnter: (String) -> Boolean, biometric: (() -> Unit)? = null) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var wait by remember { mutableLongStateOf(Pin.waitSeconds()) }
    LaunchedEffect(wait) { if (wait > 0) { delay(1000); wait = Pin.waitSeconds() } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Bubble(Icons.Outlined.Lock, Brand, 64.dp); Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Ink)
        Text(if (wait > 0) L("Demasiados intentos. Espera $wait s", "Too many attempts. Wait $wait s", "Muitas tentativas. Aguarde $wait s")
            else if (error) L("PIN incorrecto", "Wrong PIN", "PIN incorreto") else subtitle,
            style = MaterialTheme.typography.bodySmall, color = if (error || wait > 0) Danger else Muted)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) { i -> Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < pin.length) Brand else Surface2)) }
        }
        Spacer(Modifier.height(22.dp))
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(if (biometric != null) "bio" else "", "0", "⌫")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 7.dp)) {
                row.forEach { k ->
                    Box(Modifier.size(70.dp).clip(CircleShape).background(if (k.isEmpty()) Color.Transparent else Surface1).tap(onClick = when (k) {
                        "" -> null
                        "bio" -> biometric
                        "⌫" -> { { pin = pin.dropLast(1); error = false } }
                        else -> { {
                            if (wait == 0L && pin.length < 4) {
                                pin += k; error = false
                                // 4 dígitos, como la web: al completar se valida
                                if (pin.length == 4) { val ok = onEnter(pin); error = !ok; pin = ""; wait = Pin.waitSeconds() }
                            }
                        } }
                    }), contentAlignment = Alignment.Center) {
                        when (k) {
                            "bio" -> Icon(Icons.Outlined.Fingerprint, L("Huella", "Fingerprint", "Digital"), tint = Brand, modifier = Modifier.size(30.dp))
                            "⌫" -> Icon(Icons.Outlined.Backspace, L("Borrar", "Delete", "Apagar"), tint = Ink)
                            else -> Text(k, style = MaterialTheme.typography.headlineSmall, color = Ink)
                        }
                    }
                }
            }
        }
    }
}

/** Huella/rostro con BiometricPrompt real (la web llamaba credentials.create y no verificaba nada). */
fun biometricAvailable(ctx: android.content.Context) =
    BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

fun askBiometric(activity: FragmentActivity, onOk: () -> Unit) {
    BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
    }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Vitreon").setSubtitle(L("Desbloquear", "Unlock", "Desbloquear"))
        .setNegativeButtonText(L("Usar PIN", "Use PIN", "Usar PIN")).build())
}

/** Pantalla de bloqueo de la app. */
@Composable
fun LockScreen() {
    val act = LocalContext.current as FragmentActivity
    val bio = Pin.biometric && biometricAvailable(act)
    LaunchedEffect(Unit) { if (bio) askBiometric(act) { Pin.unlockBiometric() } }
    Box(Modifier.fillMaxSize().background(Backdrop), contentAlignment = Alignment.Center) {
        PinPad("Vitreon", L("Escribe tu PIN", "Enter your PIN", "Digite seu PIN"), onEnter = { Pin.check(it) },
            biometric = if (bio) { { askBiometric(act) { Pin.unlockBiometric() } } } else null)
    }
}

/** PIN para abrir una nota bloqueada en esta sesión (la huella no da la clave de cifrado de las notas). */
@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    Dialog(onDismiss) {
        Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (Prefs.mode.dark) Color(0xFF0F1220) else Color.White).padding(24.dp)) {
            PinPad(title, L("Escribe tu PIN", "Enter your PIN", "Digite seu PIN"), onEnter = { p -> Pin.check(p).also { if (it) onOk() } })
        }
    }
}

/** Crear o cambiar el PIN: dos veces el mismo. Al cambiarlo se re-cifran las notas bloqueadas. */
@Composable
fun SetPinDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var needOld by remember { mutableStateOf(Pin.enabled && Pin.noteKey == null) }
    Dialog(onDismiss) {
        Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (Prefs.mode.dark) Color(0xFF0F1220) else Color.White).padding(24.dp)) {
            when {
                needOld -> PinPad(L("PIN actual", "Current PIN", "PIN atual"), L("Para cambiarlo", "To change it", "Para alterá-lo"), onEnter = { p -> Pin.check(p).also { if (it) needOld = false } })
                first == null -> key("new") { PinPad(L("PIN nuevo", "New PIN", "PIN novo"), L("4 dígitos", "4 digits", "4 dígitos"),
                    onEnter = { p -> first = p; true }) }
                else -> key("repeat") { PinPad(L("Repite el PIN", "Repeat the PIN", "Repita o PIN"), "", onEnter = { p ->
                    if (p == first) { val (old, new) = Pin.set(p); Store.rekey(old, new); onDone(); true }
                    else { first = null; false }
                }) }
            }
        }
    }
}
