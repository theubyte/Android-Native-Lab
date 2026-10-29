package de.millione.schulranzen.backup

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.millione.schulranzen.core.backup.AutoInterval
import de.millione.schulranzen.core.backup.BackupNaming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small values encrypted with an AES key that lives in the Android Keystore
 * and never leaves it, so a copied prefs file reveals nothing.
 */
internal class KeystoreBox(private val alias: String) {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain), Base64.NO_WRAP)
    }

    fun open(stored: String): ByteArray {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return c.doFinal(raw, 12, raw.size - 12)
    }
}

/**
 * Backup settings of this device: encryption, the remembered password and
 * the automatic backup with its last result. Kept out of backups and
 * preserved on restore, because the folder permission and the Keystore key
 * only exist on this device. One instance per process ([get]), so the
 * worker's status reaches an open screen at once.
 */
class BackupSettings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val box = KeystoreBox(ALIAS)

    data class State(
        /** New backups are encrypted with the user's password. */
        val encrypt: Boolean = false,
        /** The password is stored on this device (Keystore). */
        val passwordSaved: Boolean = false,
        val interval: AutoInterval = AutoInterval.OFF,
        val wifiOnly: Boolean = true,
        val chargingOnly: Boolean = false,
        /** SAF tree URI of the local target folder. */
        val localTree: String? = null,
        val localName: String? = null,
        val toServer: Boolean = false,
        val keep: Int = 5,
        val lastOk: Long = 0,
        val lastSize: Long = 0,
        val lastTargets: String = "",
        val lastError: String? = null,
        val lastErrorAt: Long = 0,
    ) {
        val hasTarget: Boolean get() = localTree != null || toServer
        /** An encrypted automatic backup cannot ask for the password. */
        val autoNeedsPassword: Boolean get() = encrypt && !passwordSaved
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    // Held as a field: SharedPreferences keeps listeners only weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _state.value = read() }

    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    private fun read() = State(
        encrypt = prefs.getBoolean("encrypt", false),
        passwordSaved = prefs.contains("password"),
        interval = AutoInterval.parse(prefs.getString("interval", null)),
        wifiOnly = prefs.getBoolean("wifi_only", true),
        chargingOnly = prefs.getBoolean("charging_only", false),
        localTree = prefs.getString("local_tree", null),
        localName = prefs.getString("local_name", null),
        toServer = prefs.getBoolean("to_server", false),
        keep = prefs.getInt("keep", 5).coerceIn(BackupNaming.MIN_KEEP, BackupNaming.MAX_KEEP),
        lastOk = prefs.getLong("last_ok", 0),
        lastSize = prefs.getLong("last_size", 0),
        lastTargets = prefs.getString("last_targets", "").orEmpty(),
        lastError = prefs.getString("last_error", null),
        lastErrorAt = prefs.getLong("last_error_at", 0),
    )

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).commit()
        _state.value = read()
    }

    /**
     * Switches encryption on with [password] (remembered when [remember]),
     * or off with null. Turning it off also forgets the password.
     */
    fun setEncryption(password: CharArray?, remember: Boolean) = edit {
        putBoolean("encrypt", password != null)
        if (password != null && remember) putString("password", box.seal(String(password).toByteArray(Charsets.UTF_8)))
        else remove("password")
    }

    fun forgetPassword() = edit { remove("password") }

    /** The remembered password, or null (none, or the Keystore key is gone). */
    fun password(): CharArray? {
        val stored = prefs.getString("password", null) ?: return null
        return runCatching { String(box.open(stored), Charsets.UTF_8).toCharArray() }.getOrNull()
    }

    fun setInterval(i: AutoInterval) = edit { putString("interval", i.name) }
    fun setWifiOnly(v: Boolean) = edit { putBoolean("wifi_only", v) }
    fun setChargingOnly(v: Boolean) = edit { putBoolean("charging_only", v) }
    fun setToServer(v: Boolean) = edit { putBoolean("to_server", v) }
    fun setKeep(n: Int) = edit { putInt("keep", n.coerceIn(BackupNaming.MIN_KEEP, BackupNaming.MAX_KEEP)) }
    fun setLocalFolder(uri: String?, name: String?) = edit {
        if (uri == null) { remove("local_tree"); remove("local_name") } else { putString("local_tree", uri); putString("local_name", name) }
    }

    /** Status after an automatic run; [error] null = everything worked. */
    internal fun recordRun(okAt: Long?, size: Long, targets: String, error: String?) = edit {
        if (okAt != null) { putLong("last_ok", okAt); putLong("last_size", size); putString("last_targets", targets) }
        if (error != null) { putString("last_error", error); putLong("last_error_at", System.currentTimeMillis()) } else remove("last_error")
    }

    fun clearError() = edit { remove("last_error") }

    companion object {
        /** Prefs file name; excluded from backups, kept on restore. */
        const val PREFS = "backup_settings"
        private const val ALIAS = "schulranzen_backup_password"

        @Volatile private var instance: BackupSettings? = null

        fun get(context: Context): BackupSettings =
            instance ?: synchronized(this) { instance ?: BackupSettings(context).also { instance = it } }
    }
}
