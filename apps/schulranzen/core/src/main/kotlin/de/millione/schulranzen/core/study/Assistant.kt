package de.millione.schulranzen.core.study

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Why a call to the assistant failed; the UI words each kind for the learner. */
enum class AssistantError { NO_KEY, INVALID_KEY, RATE_LIMIT, OVERLOADED, REFUSED, OFFLINE, TIMEOUT, BAD_REQUEST, SERVER, EMPTY }

class AssistantException(val error: AssistantError, message: String) : Exception(message)

/** One turn of a conversation; [role] is "user" or "assistant". */
data class ChatTurn(val role: String, val text: String)

/** The verdict of Claude on a free answer. */
data class AnswerReview(val grade: SelfGrade, val feedback: String)

/**
 * What the explanation helper asks and how replies are read: prompts and parsers,
 * tested without network. The call itself goes through the Anthropic SDK in the
 * study feature, so this module stays free of it.
 */
object ClaudeApi {
    /** The default model. */
    const val MODEL = "claude-opus-5-5"
    /** The cheap choice for a learner paying with their own key. */
    const val MODEL_THRIFTY = "claude-haiku-5-5"
    /** Server-side retry on another model after a safety decline. Haiku has none. */
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

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

    /** Judges a learner's free answer against the card's solution. */
    val SYSTEM_GRADE = """
        Du bewertest die frei formulierte Antwort eines Auszubildenden zum Anlagenmechaniker SHK
        wie ein fairer Prüfer. Maßstab ist die Musterlösung der Karte; andere Worte für dieselbe Sache sind richtig.
        Tippfehler zählen nicht. Falsche Zahlen, Einheiten oder Fachbegriffe zählen.
        Antworte ausschließlich mit einem JSON-Objekt der Form
        {"grade": "RIGHT" | "PARTLY" | "WRONG", "feedback": "…"}.
        Das Feedback ist auf Deutsch, höchstens drei Sätze, und sagt konkret, was fehlt oder falsch ist.
    """.trimIndent()

    /** Makes exam questions from notes or a book page, answered as a JSON array. */
    val SYSTEM_QUESTIONS = """
        Du erstellst Prüfungsfragen für einen Auszubildenden zum Anlagenmechaniker SHK aus dem folgenden Text.
        Schreibe 5 bis 20 Fragen, wie sie in einer Klassenarbeit oder Gesellenprüfung vorkommen: Verständnis, Rechnen mit
        Werten aus dem Text, Abläufe, Begründungen, nicht nur Definitionen. Die Antwort ist knapp und vollständig.
        Verwende nur, was im Text steht; was dort nicht steht, wird nicht gefragt und nicht ergänzt.
        Antworte ausschließlich mit einem JSON-Array der Form [{"front": "Frage", "back": "Antwort"}].
    """.trimIndent()

    fun gradeRequest(question: String, solution: String, answer: String): String =
        "Frage:\n${question.trim()}\n\nMusterlösung:\n${solution.trim()}\n\nAntwort des Auszubildenden:\n${answer.trim()}"

    /** Reads the verdict; null when the reply holds none, so the learner judges instead. */
    fun parseReview(text: String): AnswerReview? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        val grade = when ((o["grade"] as? JsonPrimitive)?.contentOrNull?.uppercase()) {
            "RIGHT" -> SelfGrade.RIGHT
            "PARTLY" -> SelfGrade.PARTLY
            "WRONG" -> SelfGrade.WRONG
            else -> return null
        }
        return AnswerReview(grade, (o["feedback"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
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
