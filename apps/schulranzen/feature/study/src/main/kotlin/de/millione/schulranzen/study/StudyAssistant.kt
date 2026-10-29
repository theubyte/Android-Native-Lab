package de.millione.schulranzen.study

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.millione.schulranzen.core.study.AssistantError
import de.millione.schulranzen.core.study.AssistantException
import de.millione.schulranzen.core.study.CardSuggestion
import de.millione.schulranzen.core.study.ChatTurn
import de.millione.schulranzen.core.study.ClaudeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The learner's Claude API key, encrypted with an AES key that lives in the
 * Android Keystore and never leaves it. A prefs file copied to another
 * device (e.g. by a backup) cannot be decrypted there and simply reads as
 * "no key". Modelled on the backup feature's SyncAccount.
 */
class AssistantKey(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _present = MutableStateFlow(prefs.contains(FIELD))
    /** Whether a key is stored; does not decrypt it. */
    val present: StateFlow<Boolean> = _present.asStateFlow()

    suspend fun save(key: String) = withContext(Dispatchers.IO) {
        val clean = key.trim()
        if (clean.isEmpty()) { clear(); return@withContext }
        prefs.edit().putString(FIELD, encrypt(clean)).commit()
        _present.value = true
    }

    fun clear() {
        prefs.edit().remove(FIELD).apply()
        _present.value = false
    }

    /** The key in plain text, or null. A key that no longer decrypts is dropped. */
    suspend fun read(): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(FIELD, null) ?: return@withContext null
        runCatching { decrypt(stored) }.getOrElse { clear(); null }
    }

    /** "sk-ant-…a1b2" for showing which key is stored. */
    suspend fun masked(): String? = read()?.let { k -> if (k.length <= 12) "••••" else k.take(7) + "…" + k.takeLast(4) }

    private fun secret(): SecretKey {
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
        c.init(Cipher.ENCRYPT_MODE, secret())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, raw, 0, 12))
        return c.doFinal(raw, 12, raw.size - 12).toString(Charsets.UTF_8)
    }

    companion object {
        /** Prefs file name; best kept out of backups (it is useless on another device). */
        const val PREFS = "study_assistant"
        private const val FIELD = "api_key"
        private const val ALIAS = "schulranzen_study_assistant"
    }
}

/**
 * The explanation helper: explains questions and cards for a trainee and
 * turns text into flash cards, via Claude. All calls run on IO; failures
 * come as [AssistantException] with a kind the UI can word
 * ([ClaudeApi.describe]).
 */
class StudyAssistant(context: Context) {
    val key = AssistantKey(context)

    /**
     * Explains [question]. [history] are earlier turns of the same chat
     * (for follow-up questions), oldest first.
     */
    suspend fun explain(question: String, history: List<ChatTurn> = emptyList()): String {
        val k = key.read() ?: throw AssistantException(AssistantError.NO_KEY, "Kein Schlüssel")
        return withContext(Dispatchers.IO) {
            ClaudeApi.send(k, ClaudeApi.SYSTEM_EXPLAIN, history + ChatTurn("user", question.trim())).text
        }
    }

    /** Card proposals for [text] by Claude. Empty when the reply held no usable cards. */
    suspend fun suggestCards(text: String): List<CardSuggestion> {
        val k = key.read() ?: throw AssistantException(AssistantError.NO_KEY, "Kein Schlüssel")
        return withContext(Dispatchers.IO) {
            ClaudeApi.parseCards(ClaudeApi.send(k, ClaudeApi.SYSTEM_CARDS, listOf(ChatTurn("user", text.trim())), maxTokens = 4096).text)
        }
    }

    companion object {
        /** A ready question for "explain this card". */
        fun cardQuestion(front: String, back: String): String =
            "Erklär mir bitte diese Karteikarte, sodass ich sie verstehe und mir merken kann.\n\nFrage: ${front.trim()}\nAntwort: ${back.trim()}"
    }
}
