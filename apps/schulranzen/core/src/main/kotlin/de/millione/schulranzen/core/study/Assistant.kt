package de.millione.schulranzen.core.study

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Why a call to the assistant failed; the UI words each kind for the learner. */
enum class AssistantError { NO_KEY, INVALID_KEY, RATE_LIMIT, OVERLOADED, REFUSED, OFFLINE, TIMEOUT, BAD_REQUEST, SERVER, EMPTY }

class AssistantException(val error: AssistantError, message: String) : Exception(message)

/** One turn of a conversation; [role] is "user" or "assistant". */
data class ChatTurn(val role: String, val text: String)

data class AssistantReply(val text: String, val stopReason: String?)

/**
 * The explanation helper on top of the Claude Messages API, as plain HTTP so
 * the app needs no SDK. Requests and replies are built and parsed here
 * (tested without network); [send] does the blocking call and must run on
 * an IO thread.
 */
object ClaudeApi {
    const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    const val VERSION = "2023-06-01"
    const val MODEL = "claude-haiku-5-5"

    private val json = Json { ignoreUnknownKeys = true }

    /** Explains for a trainee in plumbing and heating (SHK) at vocational school. */
    val SYSTEM_EXPLAIN = """
        Du bist ein geduldiger Lernhelfer für einen Auszubildenden zum Anlagenmechaniker SHK
        (Sanitär-, Heizungs- und Klimatechnik) an der Berufsschule.
        Erkläre auf Deutsch, schülergerecht und fachlich richtig: erst die Kernaussage in ein bis zwei Sätzen,
        dann das Wichtigste in kurzen Absätzen oder Stichpunkten, mit einem Beispiel aus der Praxis
        (Baustelle, Heizungskeller, Bad), wenn es passt. Nenne Formeln mit Einheiten und rechne Beispiele vor.
        Beziehe dich, wo es passt, auf gängige Regeln (z. B. DIN EN 806, DIN 1988, DIN EN 12828, TRGI, VDI 2035),
        aber erfinde keine Normnummern oder Zahlenwerte; sag ehrlich, wenn du dir unsicher bist.
        Halte dich kurz (höchstens etwa 250 Wörter), außer der Schüler bittet ausdrücklich um mehr.
        Schreibe schlichten Text ohne Markdown-Überschriften; Stichpunkte mit „•“ sind erlaubt.
    """.trimIndent()

    /** Turns study text into flash cards, answered as a JSON array. */
    val SYSTEM_CARDS = """
        Du erstellst Karteikarten für einen Auszubildenden zum Anlagenmechaniker SHK an der Berufsschule.
        Lies den Text und schreibe 3 bis 15 Karten zu den prüfungsrelevanten Fakten: Begriffe, Definitionen,
        Grenzwerte mit Einheiten, Abläufe, Aufzählungen. Vorderseite: eine klare, kurze Frage.
        Rückseite: die knappe, richtige Antwort (höchstens drei Sätze oder eine kurze Liste).
        Verwende nur Inhalte aus dem Text. Antworte ausschließlich mit einem JSON-Array der Form
        [{"front": "…", "back": "…"}] und sonst nichts.
    """.trimIndent()

    fun requestBody(system: String, turns: List<ChatTurn>, maxTokens: Int = 2048, model: String = MODEL): String =
        buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            // Short school answers need little deliberation; low effort keeps them quick and cheap.
            put("output_config", buildJsonObject { put("effort", "low") })
            put("messages", buildJsonArray {
                turns.forEach { t -> add(buildJsonObject { put("role", t.role); put("content", t.text) }) }
            })
        }.toString()

    /** Text of a successful reply. Thinking blocks and other non-text blocks are skipped. */
    fun parseReply(body: String): AssistantReply {
        val root = json.parseToJsonElement(body).jsonObject
        val stop = (root["stop_reason"] as? JsonPrimitive)?.contentOrNull
        if (stop == "refusal") throw AssistantException(AssistantError.REFUSED, "Dazu kann der Assistent nichts sagen.")
        val text = (root["content"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            .joinToString("") { (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
            .trim()
        if (text.isEmpty()) throw AssistantException(AssistantError.EMPTY, "Die Antwort war leer.")
        return AssistantReply(text, stop)
    }

    /** Maps an HTTP error to a kind; the API's own message is kept for details. */
    fun parseError(status: Int, body: String?): AssistantException {
        val msg = runCatching {
            json.parseToJsonElement(body.orEmpty()).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()
        val kind = when (status) {
            401, 403 -> AssistantError.INVALID_KEY
            429 -> AssistantError.RATE_LIMIT
            529 -> AssistantError.OVERLOADED
            400, 404, 413, 422 -> AssistantError.BAD_REQUEST
            else -> if (status >= 500) AssistantError.SERVER else AssistantError.BAD_REQUEST
        }
        return AssistantException(kind, msg.ifEmpty { "HTTP $status" })
    }

    /**
     * Reads cards from the assistant's answer. Tolerates code fences and
     * text around the array; drops entries without both sides.
     */
    fun parseCards(text: String): List<CardSuggestion> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val f = (o["front"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val b = (o["back"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (f.isEmpty() || b.isEmpty()) null else CardSuggestion(f, b, SuggestionKind.ASSISTANT)
        }.distinctBy { it.front.lowercase() }
    }

    /** Blocking call. Run it on Dispatchers.IO. */
    fun send(apiKey: String, system: String, turns: List<ChatTurn>, maxTokens: Int = 2048, endpoint: String = ENDPOINT): AssistantReply {
        if (apiKey.isBlank()) throw AssistantException(AssistantError.NO_KEY, "Kein API-Schlüssel hinterlegt.")
        val conn = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey.trim())
            conn.setRequestProperty("anthropic-version", VERSION)
            conn.outputStream.use { it.write(requestBody(system, turns, maxTokens).toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (status !in 200..299) throw parseError(status, body)
            return parseReply(body.orEmpty())
        } catch (e: AssistantException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AssistantException(AssistantError.TIMEOUT, e.message ?: "Zeitüberschreitung")
        } catch (e: UnknownHostException) {
            throw AssistantException(AssistantError.OFFLINE, e.message ?: "Kein Netz")
        } catch (e: IOException) {
            throw AssistantException(AssistantError.OFFLINE, e.message ?: "Verbindungsfehler")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw AssistantException(AssistantError.SERVER, "Antwort nicht lesbar")
        } catch (e: IllegalArgumentException) {
            throw AssistantException(AssistantError.SERVER, "Antwort nicht lesbar")
        } finally {
            conn.disconnect()
        }
    }

    /** Learner-facing German text for an error. */
    fun describe(e: AssistantError): String = when (e) {
        AssistantError.NO_KEY -> "Für die Erklärhilfe wird ein API-Schlüssel gebraucht. Trag ihn in den Lern-Einstellungen ein."
        AssistantError.INVALID_KEY -> "Der API-Schlüssel wurde abgelehnt. Bitte in den Einstellungen prüfen."
        AssistantError.RATE_LIMIT -> "Zu viele Anfragen in kurzer Zeit. Kurz warten und nochmal versuchen."
        AssistantError.OVERLOADED -> "Der Dienst ist gerade überlastet. Gleich nochmal versuchen."
        AssistantError.REFUSED -> "Dazu gibt der Assistent keine Antwort. Frag anders oder enger."
        AssistantError.OFFLINE -> "Keine Verbindung. Ist das WLAN an?"
        AssistantError.TIMEOUT -> "Die Antwort hat zu lange gedauert. Nochmal versuchen."
        AssistantError.BAD_REQUEST -> "Die Anfrage wurde nicht angenommen."
        AssistantError.SERVER -> "Beim Dienst ist ein Fehler aufgetreten. Später nochmal versuchen."
        AssistantError.EMPTY -> "Es kam keine Antwort zurück. Nochmal versuchen."
    }
}
