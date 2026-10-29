package de.millione.schulranzen.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.millione.schulranzen.core.backup.RemoteVersion
import de.millione.schulranzen.core.backup.SyncClient
import de.millione.schulranzen.core.backup.SyncError
import de.millione.schulranzen.core.backup.SyncException
import de.millione.schulranzen.core.backup.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Unraid account on this device: server address, e-mail and the
 * server's token. The token is encrypted with an AES key that lives in the
 * Android Keystore and never leaves it, so a copied prefs file is useless.
 * These prefs are kept out of backups and survive a restore.
 */
class SyncAccount(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class State(val server: String, val email: String, val loggedIn: Boolean)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun read() = State(prefs.getString("server", "").orEmpty(), prefs.getString("email", "").orEmpty(), prefs.contains("token"))

    private fun token(): String? = prefs.getString("token", null)?.let { runCatching { decrypt(it) }.getOrNull() }

    fun setServer(url: String) {
        val norm = SyncClient.normalizeUrl(url)
        if (norm != prefs.getString("server", "")) {
            // A different server does not know our token.
            prefs.edit().putString("server", norm).remove("token").apply()
            _state.value = read()
        }
    }

    fun logout() {
        prefs.edit().remove("token").apply()
        _state.value = read()
    }

    private fun client(withToken: Boolean = true): SyncClient {
        val server = prefs.getString("server", "").orEmpty()
        return SyncClient(server, if (withToken) token() else null)
    }

    suspend fun checkServer(): Boolean = io { client(false).health() }

    suspend fun login(email: String, pin: String) = io { store(client(false).login(email, pin)) }
    suspend fun register(email: String, pin: String) = io { store(client(false).register(email, pin)) }

    private fun store(s: de.millione.schulranzen.core.backup.SyncSession) {
        prefs.edit().putString("email", s.email).putString("token", encrypt(s.token)).apply()
        _state.value = read()
    }

    suspend fun upload(zip: File, name: String, progress: (Long, Long) -> Unit): UploadResult = authed { it.upload(zip, name, progress) }
    suspend fun versions(): List<RemoteVersion> = authed { it.versions() }
    suspend fun download(version: String?, target: File, progress: (Long, Long) -> Unit): File = authed { it.download(version, target, progress) }

    /** Runs a call with the token; a rejected token logs out so the UI asks for the PIN again. */
    private suspend fun <T> authed(block: (SyncClient) -> T): T = io {
        val c = client()
        if (c.token == null) {
            if (prefs.contains("token")) logout()
            throw SyncException(SyncError.SESSION_EXPIRED, "Bitte zuerst am Server anmelden.")
        }
        try { block(c) } catch (e: SyncException) {
            if (e.error == SyncError.SESSION_EXPIRED) logout()
            throw e
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // --- Keystore -------------------------------------------------------

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return c.doFinal(raw, 12, raw.size - 12).toString(Charsets.UTF_8)
    }

    companion object {
        /** Prefs file name; excluded from backups. */
        const val PREFS = "backup_sync"
        private const val ALIAS = "schulranzen_sync_token"
    }
}
