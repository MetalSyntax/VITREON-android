package com.metalsyntax.vitreon.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM con IV aleatorio de 12 bytes por mensaje (nunca se reutiliza). Formato: IV + texto cifrado + tag. */
object Aes {
    fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        return iv + Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(plain)
    }
    fun open(key: SecretKey, data: ByteArray, offset: Int = 0): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, offset, 12)) }
            .doFinal(data, offset + 12, data.size - offset - 12)

    /** Clave a partir de una contraseña (exportaciones .vitreon y PIN de notas bloqueadas). */
    fun derive(secret: String, salt: ByteArray, iterations: Int = 210_000): SecretKey =
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(secret.toCharArray(), salt, iterations, 256)).encoded, "AES")

    fun random(n: Int) = ByteArray(n).also(SecureRandom()::nextBytes)
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
}

/**
 * Cifrado en reposo con clave por dispositivo (la web usaba una clave fija dentro del código). Cifrado de sobre:
 * clave de datos aleatoria guardada en vault.key envuelta por una clave del Android Keystore (no extraíble).
 * Formato de archivo: "VIT1" + IV + cifrado.
 */
object Vault {
    private val MAGIC = "VIT1".toByteArray()
    private const val ALIAS = "vitreon_data"
    internal var enabled = true // pruebas JVM: sin Keystore
    private var dir: java.io.File? = null
    @Volatile private var dataKey: SecretKey? = null

    fun init(filesDir: java.io.File) { dir = filesDir }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    @Synchronized private fun key(): SecretKey {
        dataKey?.let { return it }
        val f = java.io.File(dir ?: error("Vault.init"), "vault.key")
        val raw = if (f.exists()) {
            val w = f.readBytes()
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, w, 0, 12)) }.doFinal(w, 12, w.size - 12)
        } else Aes.random(32).also { k ->
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
            val tmp = java.io.File(f.path + ".tmp"); tmp.writeBytes(c.iv + c.doFinal(k)); tmp.renameTo(f)
        }
        return SecretKeySpec(raw, "AES").also { dataKey = it }
    }

    fun sealed(bytes: ByteArray) = bytes.size >= 16 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)
    fun seal(plain: ByteArray): ByteArray = if (!enabled) plain else MAGIC + Aes.seal(key(), plain)
    fun open(bytes: ByteArray): ByteArray = if (!sealed(bytes)) bytes else Aes.open(key(), bytes, 4)
}

/**
 * PIN de la app: solo se guarda el hash PBKDF2 con sal (la web lo guardaba en claro y nunca lo verificaba). 5 intentos
 * fallidos = 30 s de espera. Al desbloquear se deriva además la clave de las notas bloqueadas (otra sal), que vive solo en memoria.
 */
object Pin {
    private lateinit var sp: android.content.SharedPreferences
    var enabled by mutableStateOf(false); private set
    var biometric by mutableStateOf(false); private set
    var lockOnExit by mutableStateOf(true); private set
    var locked by mutableStateOf(false)
    @Volatile var external = false // la app abrió cámara/archivos: al volver no se bloquea
    var fails by mutableIntStateOf(0); private set
    private var until = 0L
    /** Clave de las notas bloqueadas mientras la sesión está abierta. */
    @Volatile var noteKey: SecretKey? = null; private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("security", Context.MODE_PRIVATE)
        enabled = sp.contains("hash"); biometric = sp.getBoolean("biometric", false); lockOnExit = sp.getBoolean("lockOnExit", true)
        locked = enabled
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hash(pin: String, salt: ByteArray) = Aes.derive(pin, salt, 120_000).encoded
    private fun noteSalt() = sp.getString("noteSalt", null)?.let(::unhex) ?: Aes.random(16).also { sp.edit().putString("noteSalt", hex(it)).apply() }

    /** Define o cambia el PIN. Devuelve la clave vieja y la nueva para volver a cifrar las notas bloqueadas. */
    fun set(pin: String): Pair<SecretKey?, SecretKey> {
        val old = noteKey
        val salt = Aes.random(16)
        sp.edit().putString("salt", hex(salt)).putString("hash", hex(hash(pin, salt))).putString("noteSalt", hex(Aes.random(16))).apply()
        enabled = true; locked = false
        noteKey = Aes.derive(pin, noteSalt())
        return old to noteKey!!
    }

    fun waitSeconds() = ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    fun check(pin: String): Boolean {
        if (waitSeconds() > 0) return false
        val salt = sp.getString("salt", null)?.let(::unhex) ?: return false
        val ok = MessageDigest.isEqual(hash(pin, salt), sp.getString("hash", "")!!.let(::unhex))
        if (ok) { fails = 0; locked = false; noteKey = Aes.derive(pin, noteSalt()) }
        else if (++fails >= 5) { until = System.currentTimeMillis() + 30_000; fails = 0 }
        return ok
    }

    /** Con huella se abre la app, pero la clave de notas bloqueadas solo existe si se escribió el PIN en esta sesión. */
    fun unlockBiometric() { locked = false }

    fun lock() { if (enabled) { locked = true; noteKey = null } }

    @JvmName("applyBiometric") fun setBiometric(v: Boolean) { biometric = v; sp.edit().putBoolean("biometric", v).apply() }
    @JvmName("applyLockOnExit") fun setLockOnExit(v: Boolean) { lockOnExit = v; sp.edit().putBoolean("lockOnExit", v).apply() }
}

/** Notas bloqueadas: el contenido se guarda cifrado con la clave del PIN (la web solo ocultaba la tarjeta). */
object Locker {
    fun seal(content: String, key: SecretKey): String = Aes.b64(Aes.seal(key, content.toByteArray()))
    fun open(sealed: String, key: SecretKey): String? = runCatching { String(Aes.open(key, Aes.unb64(sealed))) }.getOrNull()
}
