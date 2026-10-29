package de.millione.schulranzen.core.study

/** Where a suggested card came from; the UI may show it as a small hint. */
enum class SuggestionKind { DEFINITION, TERM, LIST, ASSISTANT }

/** A card proposal the learner still has to confirm. */
data class CardSuggestion(val front: String, val back: String, val kind: SuggestionKind)

/**
 * Offline card suggestions from plain text (notes, a book page, an
 * explanation). Looks for the three shapes school texts use for facts:
 * definitions ("Ein MAG ist …", "Unter X versteht man …"), term lines
 * ("Vorlauftemperatur: 70 °C") and lists under a heading. It is a
 * heuristic: the learner confirms every card, so recall beats precision
 * only up to [MAX_SUGGESTIONS].
 */
object CardSuggester {
    const val MAX_SUGGESTIONS = 60

    private val bullet = Regex("""^\s*(?:[-–•*·▪►]|\d{1,2}[.)]|[a-zA-Z][)])\s+(.+)$""")
    private val termLine = Regex("""^\s*([^:]{2,60}?)\s*:\s+(.{2,})$""")
    private val understand = Regex("""^[Uu]nter\s+(?:dem\s+Begriff\s+|einer?m?\s+|der\s+|dem\s+|den\s+)?(.{2,60}?)\s+versteht\s+man\s+(.{3,})$""")
    private val calledBy = Regex("""^(.{3,})\s+(?:nennt|bezeichnet)\s+man\s+(?:als\s+)?(.{2,60})$""")
    private val definition = Regex("""^(.{2,70}?)\s+(ist|sind|bezeichnet|bedeutet|heißt|heißen|nennt\s+man)\s+(.{3,})$""")

    /** Subjects that make no sense on their own ("Es ist …", "Das ist …"). */
    private val pronouns = setOf(
        "es", "das", "dies", "dieses", "diese", "dieser", "er", "sie", "man", "hier", "dann", "damit", "dabei",
        "dort", "da", "so", "was", "wer", "wie", "wo", "jetzt", "nun", "heute", "wichtig", "außerdem", "auch",
        "der", "die", "ein", "eine", "welche", "welcher", "alles", "nichts", "dazu", "daher", "deshalb",
    )

    fun suggest(text: String): List<CardSuggestion> {
        val out = LinkedHashMap<String, CardSuggestion>()
        fun add(s: CardSuggestion) {
            val front = s.front.trim(); val back = s.back.trim()
            if (front.length < 2 || back.length < 2 || out.size >= MAX_SUGGESTIONS) return
            out.putIfAbsent(front.lowercase(), CardSuggestion(front, back, s.kind))
        }

        val lines = text.replace("\r", "").lines()
        val usedByList = HashSet<Int>()
        // Lists: a heading line followed by at least two bullet lines.
        var i = 0
        while (i < lines.size) {
            val head = lines[i].trim()
            if (head.isNotEmpty() && bullet.matchEntire(head) == null && head.length <= 90) {
                val items = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size) {
                    val m = bullet.matchEntire(lines[j]) ?: break
                    items += m.groupValues[1].trim()
                    j++
                }
                if (items.size >= 2) {
                    val topic = head.trimEnd(':', ' ', '.')
                    add(CardSuggestion("$topic – nenne die ${items.size} Punkte.", items.joinToString("\n") { "• $it" }, SuggestionKind.LIST))
                    for (k in i until j) usedByList += k
                    i = j
                    continue
                }
            }
            i++
        }

        // Term lines and definitions, sentence by sentence.
        lines.forEachIndexed { idx, raw ->
            if (idx in usedByList && bullet.matchEntire(raw) != null) {
                // A bullet may itself be "Term: meaning"; that is worth its own card.
                val item = bullet.matchEntire(raw)!!.groupValues[1]
                termLine.matchEntire(item)?.let { term(it)?.let(::add) }
                return@forEachIndexed
            }
            if (idx in usedByList) return@forEachIndexed
            val line = (bullet.matchEntire(raw)?.groupValues?.get(1) ?: raw).trim()
            if (line.isEmpty()) return@forEachIndexed
            termLine.matchEntire(line)?.let { m -> term(m)?.let { add(it); return@forEachIndexed } }
            sentences(line).forEach { s -> definitionOf(s)?.let(::add) }
        }
        return out.values.toList()
    }

    private fun term(m: MatchResult): CardSuggestion? {
        val name = m.groupValues[1].trim()
        val meaning = m.groupValues[2].trim()
        // "Beispiel: Das Ventil ist …" is prose, not a term.
        if (name.split(Regex("\\s+")).size > 6 || name.lowercase() in setOf("beispiel", "z. b", "hinweis", "merke", "achtung", "aufgabe", "lösung")) return null
        return CardSuggestion(name, meaning, SuggestionKind.TERM)
    }

    /** Splits prose into sentences without breaking "z. B." or "ca. 60 °C". */
    internal fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for ((k, w) in words.withIndex()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(w)
            val next = words.getOrNull(k + 1)
            val ends = (w.endsWith(".") || w.endsWith("!") || w.endsWith("?")) && !isAbbreviation(w)
            if (ends && next != null && next.first().isUpperCase()) { out += sb.toString(); sb.clear() }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun isAbbreviation(w: String): Boolean {
        val bare = w.trimEnd('.').lowercase()
        return bare.length == 1 || bare in setOf("bzw", "ca", "evtl", "ggf", "inkl", "max", "min", "usw", "vgl", "bspw", "etc", "nr", "abb", "kap", "tab", "zzgl", "allg") ||
            bare.all { it.isDigit() }
    }

    private fun definitionOf(sentence: String): CardSuggestion? {
        val s = sentence.trim().trimEnd('.', '!').trim()
        if (s.endsWith("?") || s.length < 8) return null
        understand.matchEntire(s)?.let { m ->
            return CardSuggestion("Was versteht man unter ${m.groupValues[1].trim()}?", capitalize(m.groupValues[2]) + ".", SuggestionKind.DEFINITION)
        }
        calledBy.matchEntire(s)?.let { m ->
            val what = m.groupValues[1].trim(); val name = m.groupValues[2].trim()
            if (name.split(' ').size <= 5 && !startsWithPronoun(what)) {
                return CardSuggestion("Wie nennt man ${lowerFirst(what)}?", capitalize(name) + ".", SuggestionKind.DEFINITION)
            }
        }
        val m = definition.matchEntire(s) ?: return null
        val subject = m.groupValues[1].trim().trimEnd(',')
        val verb = m.groupValues[2].replace(Regex("\\s+"), " ")
        val rest = m.groupValues[3].trim()
        if (startsWithPronoun(subject) || subject.split(' ').size > 7 || subject.contains(',')) return null
        if (!subject.first().isUpperCase() && !subject.first().isDigit()) return null
        if (rest.split(' ').size < 2) return null
        val subj = lowerFirst(subject)
        val front = when (verb) {
            "heißt", "heißen" -> "Wie $verb $subj?"
            "nennt man" -> "Wie nennt man $subj?"
            else -> "Was $verb $subj?"
        }
        return CardSuggestion(front, capitalize(rest) + ".", SuggestionKind.DEFINITION)
    }

    private fun startsWithPronoun(s: String): Boolean {
        val words = s.trim().split(' ')
        val first = words.first().lowercase()
        // "Das Ventil" is fine, "Das" alone (or "Das ist") is not.
        if (first in setOf("der", "die", "das", "ein", "eine", "einer", "einem") && words.size >= 2 && words[1].firstOrNull()?.isUpperCase() == true) return false
        return first in pronouns
    }

    private fun capitalize(s: String) = s.trim().replaceFirstChar { it.uppercaseChar() }
    private fun lowerFirst(s: String): String {
        val w = s.split(' ', limit = 2)
        // Nouns keep their capital letter; only articles are lowered.
        return if (w[0].lowercase() in setOf("der", "die", "das", "ein", "eine", "einen")) w[0].lowercase() + (if (w.size > 1) " " + w[1] else "") else s
    }
}
