package de.millione.schulranzen.backup

import android.content.Context
import de.millione.schulranzen.core.backup.DiagnoseStep
import de.millione.schulranzen.core.backup.RemoteVersion
import de.millione.schulranzen.core.backup.SyncClient
import de.millione.schulranzen.core.backup.SyncError
import de.millione.schulranzen.core.backup.SyncException
import de.millione.schulranzen.core.backup.SyncSession
import de.millione.schulranzen.core.backup.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Unraid account on this device: server address, e-mail, the server's
 * token and the PIN. Token and PIN are encrypted with an AES key that lives
 * in the Android Keystore and never leaves it, so a copied prefs file is
 * useless. The PIN is kept so a rejected token (server restart, new
 * secret) is repaired by one automatic new login instead of failing the
 * nightly backup. These prefs are kept out of backups and survive a restore.
 */
class SyncAccount(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val box = KeystoreBox(ALIAS)

    data class State(val server: String, val email: String, val loggedIn: Boolean)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun read() = State(prefs.getString("server", "").orEmpty(), prefs.getString("email", "").orEmpty(), prefs.contains("token") || prefs.contains("pin"))

    private fun secret(key: String): String? = prefs.getString(key, null)?.let { runCatching { String(box.open(it), Charsets.UTF_8) }.getOrNull() }

    fun setServer(url: String) {
        val norm = SyncClient.normalizeUrl(url)
        if (norm != prefs.getString("server", "")) {
            // A different server does not know our token.
            prefs.edit().putString("server", norm).remove("token").remove("pin").apply()
            _state.value = read()
        }
    }

    fun logout() {
        prefs.edit().remove("token").remove("pin").apply()
        _state.value = read()
    }

    private fun client(withToken: Boolean = true): SyncClient {
        val server = prefs.getString("server", "").orEmpty()
        return SyncClient(server, if (withToken) secret("token") else null)
    }

    suspend fun checkServer(): Boolean = io { client(false).health() }

    suspend fun login(email: String, pin: String) = io { store(client(false).login(email, pin), pin) }
    suspend fun register(email: String, pin: String) = io { store(client(false).register(email, pin), pin) }

    private fun store(s: SyncSession, pin: String?) {
        val e = prefs.edit().putString("email", s.email).putString("token", box.seal(s.token.toByteArray(Charsets.UTF_8)))
        if (pin != null) e.putString("pin", box.seal(pin.toByteArray(Charsets.UTF_8)))
        e.apply()
        _state.value = read()
    }

    suspend fun upload(zip: File, name: String, progress: (Long, Long) -> Unit): UploadResult = authed { it.upload(zip, name, progress) }
    suspend fun versions(): List<RemoteVersion> = authed { it.versions() }
    suspend fun download(version: String?, target: File, progress: (Long, Long) -> Unit): File = authed { it.download(version, target, progress) }

    /**
     * Health, login and listing, one after the other, for the user's own
     * troubleshooting. Uses [email]/[pin] when given, else the stored ones.
     */
    suspend fun diagnose(server: String, email: String?, pin: String?): List<DiagnoseStep> = io {
        val c = try { SyncClient(server) } catch (e: SyncException) { return@io listOf(DiagnoseStep("Server-Adresse", false, e.message.orEmpty(), 0)) }
        val mail = email?.takeIf { it.isNotBlank() } ?: prefs.getString("email", "").orEmpty()
        val p = pin?.takeIf { it.length == 6 } ?: secret("pin")
        if (mail.isBlank() || p == null) {
            val health = c.diagnose("", "").first()
            return@io listOf(health, DiagnoseStep("Anmelden", false, "Für den Test E-Mail und PIN eintragen.", 0))
        }
        c.diagnose(mail, p)
    }

    /**
     * Runs a call with the token. A rejected token (401) is repaired once by
     * logging in again with the stored PIN; only if that fails too the
     * account is logged out, so the UI asks for the PIN.
     */
    private suspend fun <T> authed(block: (SyncClient) -> T): T = io {
        val c = client()
        val email = prefs.getString("email", null)
        val pin = secret("pin")
        if (c.token == null && pin == null) {
            if (prefs.contains("token")) logout()
            throw SyncException(SyncError.SESSION_EXPIRED, "Bitte zuerst am Server anmelden.")
        }
        try {
            c.withRelogin(email, pin, onLogin = { store(it, null) }, block = block)
        } catch (e: SyncException) {
            if (e.error == SyncError.SESSION_EXPIRED || e.error == SyncError.WRONG_LOGIN) logout()
            throw e
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** Prefs file name; excluded from backups. */
        const val PREFS = "backup_sync"
        private const val ALIAS = "schulranzen_sync_token"
    }
}
