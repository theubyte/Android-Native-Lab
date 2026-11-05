package de.millione.schulranzen.study

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import de.millione.schulranzen.core.study.AnswerReview
import de.millione.schulranzen.core.study.AssistantError
import de.millione.schulranzen.core.study.AssistantException
import de.millione.schulranzen.core.study.CardSuggestion
import de.millione.schulranzen.core.study.ChatTurn
import de.millione.schulranzen.core.study.ClaudeApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import kotlin.coroutines.coroutineContext
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

    /** The chosen model; the cheap one unless the learner asked for the best answers, since their own key pays. */
    var model: String
        get() = prefs.getString(FIELD_MODEL, null) ?: ClaudeApi.MODEL_THRIFTY
        set(value) { prefs.edit().putString(FIELD_MODEL, value).apply() }

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
        private const val FIELD_MODEL = "model"
        private const val ALIAS = "schulranzen_study_assistant"
    }
}

/**
 * The explanation helper: explains questions and cards for a trainee, judges
 * free answers and turns text into cards and exam questions, all through the
 * Anthropic SDK. Replies stream; failures come as [AssistantException] with a
 * kind the UI can word ([ClaudeApi.describe]). The key goes into the client and
 * nowhere else: neither log lines nor error texts ever contain it.
 */
class StudyAssistant(context: Context) {
    val key = AssistantKey(context)

    /** Calls Claude and hands out the text as it arrives; returns all of it. */
    private suspend fun ask(
        system: String,
        turns: List<ChatTurn>,
        effort: BetaOutputConfig.Effort,
        onText: (String) -> Unit = {},
    ): String {
        val apiKey = key.read() ?: throw AssistantException(AssistantError.NO_KEY, "Kein Schlüssel")
        val model = key.model
        return withContext(Dispatchers.IO) {
            val job = coroutineContext
            try {
                val builder = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(MAX_TOKENS)
                    .system(system)
                    .outputConfig(BetaOutputConfig.builder().effort(effort).build())
                // A declined question is retried server-side where the model has a fallback.
                if (model != ClaudeApi.MODEL_THRIFTY) {
                    builder.addBeta(ClaudeApi.FALLBACK_BETA).putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
                turns.forEach { if (it.role == "assistant") builder.addAssistantMessage(it.text) else builder.addUserMessage(it.text) }
                val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
                try {
                    val out = StringBuilder()
                    var refused = false
                    client.beta().messages().createStreaming(builder.build()).use { stream ->
                        stream.stream().forEach { event ->
                            job.ensureActive()
                            event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { d ->
                                out.append(d.text())
                                onText(out.toString())
                            }
                            event.messageDelta().flatMap { it.delta().stopReason() }.ifPresent { r ->
                                if (r == BetaStopReason.REFUSAL) refused = true
                            }
                        }
                    }
                    // A refusal can arrive after partial text; that text is not an answer.
                    if (refused) throw AssistantException(AssistantError.REFUSED, "abgelehnt")
                    if (out.isBlank()) throw AssistantException(AssistantError.EMPTY, "leer")
                    out.toString().trim()
                } finally {
                    client.close()
                }
            } catch (e: Exception) {
                throw when (e) {
                    is CancellationException, is AssistantException -> e
                    else -> translate(e)
                }
            } catch (_: LinkageError) {
                throw AssistantException(AssistantError.SERVER, "SDK nicht ladbar")
            }
        }
    }

    /** Message texts of the SDK stay out: only the kind reaches the UI. */
    private fun translate(e: Exception): AssistantException {
        val kind = when (e) {
            is UnauthorizedException, is PermissionDeniedException -> AssistantError.INVALID_KEY
            is RateLimitException -> AssistantError.RATE_LIMIT
            is BadRequestException, is NotFoundException -> AssistantError.BAD_REQUEST
            is AnthropicServiceException -> if (e.statusCode() == 529) AssistantError.OVERLOADED else AssistantError.SERVER
            is AnthropicIoException -> when (e.cause) {
                is SocketTimeoutException, is InterruptedIOException -> AssistantError.TIMEOUT
                is UnknownHostException -> AssistantError.OFFLINE
                else -> AssistantError.OFFLINE
            }
            else -> AssistantError.SERVER
        }
        return AssistantException(kind, kind.name)
    }

    /**
     * Explains [question]. [history] are earlier turns of the same chat (for follow-up
     * questions), oldest first; [onText] gets the answer so far while it is written.
     */
    suspend fun explain(question: String, history: List<ChatTurn> = emptyList(), onText: (String) -> Unit = {}): String =
        ask(ClaudeApi.SYSTEM_EXPLAIN, history + ChatTurn("user", question.trim()), BetaOutputConfig.Effort.LOW, onText)

    /** Card proposals for [text] by Claude. Empty when the reply held no usable cards. */
    suspend fun suggestCards(text: String): List<CardSuggestion> =
        ClaudeApi.parseCards(ask(ClaudeApi.SYSTEM_CARDS, listOf(ChatTurn("user", text.trim())), BetaOutputConfig.Effort.MEDIUM))

    /** Exam questions from notes or a book page; only what the text says. */
    suspend fun suggestQuestions(text: String): List<CardSuggestion> =
        ClaudeApi.parseCards(ask(ClaudeApi.SYSTEM_QUESTIONS, listOf(ChatTurn("user", text.trim())), BetaOutputConfig.Effort.MEDIUM))

    /** Judges a free answer; null when the reply could not be read, then the learner judges. */
    suspend fun review(question: String, solution: String, answer: String): AnswerReview? =
        ClaudeApi.parseReview(
            ask(ClaudeApi.SYSTEM_GRADE, listOf(ChatTurn("user", ClaudeApi.gradeRequest(question, solution, answer))), BetaOutputConfig.Effort.MEDIUM),
        )

    companion object {
        /** Thinking counts towards this; it is a ceiling, not what gets billed. */
        private const val MAX_TOKENS = 16000L

        /** A ready question for "explain this card". */
        fun cardQuestion(front: String, back: String): String =
            "Erklär mir bitte diese Karteikarte, sodass ich sie verstehe und mir merken kann.\n\nFrage: ${front.trim()}\nAntwort: ${back.trim()}"
    }
}
