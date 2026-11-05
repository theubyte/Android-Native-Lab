package de.millione.schulranzen.core.study

/** One imported card. [tags] are kept only for reporting; the app has no tags. */
data class CsvCard(val front: String, val back: String, val deck: String? = null, val tags: String = "")

data class CsvParse(
    val cards: List<CsvCard>,
    /** Lines with too few fields or an empty front. */
    val skipped: Int,
    /** Cards that referred to pictures or sounds, which a text file cannot carry. */
    val mediaDropped: Int,
    val delimiter: Char,
)

/**
 * Flash cards as text, compatible with the usual tab-separated export of card
 * programs (Anki and others): one card per line, front and back separated by a
 * tab, optional `#separator:`, `#html:`, `#columns:`, `#deck column:` and
 * `#tags column:` header lines, double quotes around fields with line breaks.
 * Plain CSV with semicolon or comma works too. Scheduling is not part of the
 * format: imported cards start new.
 */
object CardCsv {
    private val headerRe = Regex("""^#\s*([\w ]+?)\s*:\s*(.*)$""")
    private val mediaRe = Regex("""\[sound:[^\]]*]|<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val breakRe = Regex("""<br\s*/?>|</div>|</p>|</li>""", RegexOption.IGNORE_CASE)
    private val tagRe = Regex("""<[^>]+>""")

    fun parse(text: String): CsvParse {
        val clean = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        var delimiter: Char? = null
        var html: Boolean? = null
        var deckCol = -1
        var tagsCol = -1
        var frontCol = 0
        var backCol = 1

        // Headers come first; they may name the columns.
        val lines = clean.split('\n')
        var start = 0
        while (start < lines.size) {
            val line = lines[start]
            if (!line.startsWith("#")) break
            headerRe.find(line)?.let { m ->
                val key = m.groupValues[1].trim().lowercase()
                val value = m.groupValues[2].trim()
                when (key) {
                    "separator" -> delimiter = when (value.lowercase()) {
                        "tab" -> '\t'; "comma" -> ','; "semicolon" -> ';'; "pipe" -> '|'; "space" -> ' '; "colon" -> ':'
                        else -> value.firstOrNull()
                    }
                    "html" -> html = value.equals("true", ignoreCase = true)
                    "deck column" -> deckCol = (value.toIntOrNull() ?: 0) - 1
                    "tags column" -> tagsCol = (value.toIntOrNull() ?: 0) - 1
                    "columns" -> {
                        val names = value.split(Regex("[\\t,;]")).map { it.trim().lowercase() }
                        names.indexOfFirst { it in setOf("front", "vorderseite", "frage", "question") }.takeIf { it >= 0 }?.let { frontCol = it }
                        names.indexOfFirst { it in setOf("back", "rückseite", "antwort", "answer") }.takeIf { it >= 0 }?.let { backCol = it }
                    }
                }
            }
            start++
        }
        val body = lines.drop(start).joinToString("\n")
        val sep = delimiter ?: guessDelimiter(body)
        val rows = rows(body, sep)
        var skipped = 0
        var media = 0
        val cards = mutableListOf<CsvCard>()
        for (row in rows) {
            if (row.size == 1 && row[0].isBlank()) continue
            if (row.size == 1 && row[0].trimStart().startsWith("#")) continue
            val rawFront = row.getOrNull(frontCol)
            val rawBack = row.getOrNull(backCol)
            if (rawFront == null || rawBack == null) { skipped++; continue }
            if (mediaRe.containsMatchIn(rawFront) || mediaRe.containsMatchIn(rawBack)) media++
            val useHtml = html ?: (looksLikeHtml(rawFront) || looksLikeHtml(rawBack))
            val front = cleanField(rawFront, useHtml)
            val back = cleanField(rawBack, useHtml)
            if (front.isEmpty()) { skipped++; continue }
            cards += CsvCard(front, back, row.getOrNull(deckCol)?.trim()?.ifEmpty { null }, row.getOrNull(tagsCol)?.trim().orEmpty())
        }
        return CsvParse(cards, skipped, media, sep)
    }

    /** A known header row like "Frage;Antwort" is not a card. */
    fun dropTitleRow(cards: List<CsvCard>): List<CsvCard> {
        val first = cards.firstOrNull() ?: return cards
        val titles = setOf("front", "back", "vorderseite", "rückseite", "frage", "antwort", "question", "answer")
        return if (first.front.lowercase() in titles && first.back.lowercase() in titles) cards.drop(1) else cards
    }

    private fun looksLikeHtml(s: String) = Regex("""</?(?:br|div|p|b|i|u|span|ul|ol|li)\b[^>]*>""", RegexOption.IGNORE_CASE).containsMatchIn(s)

    private fun cleanField(raw: String, html: Boolean): String {
        var s = mediaRe.replace(raw, " ")
        if (html) {
            s = breakRe.replace(s, "\n")
            s = tagRe.replace(s, "")
            s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        }
        return s.lines().joinToString("\n") { it.replace(Regex(" {2,}"), " ").trim() }.trim()
    }

    /** The separator that gives the steadiest number of fields; tabs win a tie. */
    private fun guessDelimiter(body: String): Char {
        // Separators inside quoted fields say nothing about the format.
        val outside = body.replace(Regex("\"(?:[^\"]|\"\")*\""), "")
        val sample = outside.lineSequence().filter { it.isNotBlank() }.take(20).toList()
        if (sample.any { '\t' in it }) return '\t'
        val semi = sample.count { ';' in it }
        val comma = sample.count { ',' in it }
        return if (semi >= comma && semi > 0) ';' else if (comma > 0) ',' else '\t'
    }

    /** RFC 4180 style: quotes around a field may hold separators and line breaks; "" is a quote. */
    private fun rows(body: String, sep: Char): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            when {
                quoted && ch == '"' && body.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && ch == '"' -> quoted = false
                quoted -> field.append(ch)
                ch == '"' && field.isEmpty() -> quoted = true
                ch == sep -> { row.add(field.toString()); field.clear() }
                ch == '\n' -> { row.add(field.toString()); field.clear(); out.add(row); row = mutableListOf() }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); out.add(row) }
        return out
    }

    /** Tab-separated text that the usual card programs read back, with the headers they expect. */
    fun write(cards: List<Pair<String, String>>): String = buildString {
        append("#separator:tab\n#html:false\n#columns:Front\tBack\n")
        for ((f, b) in cards) {
            append(escape(f, '\t')).append('\t').append(escape(b, '\t')).append('\n')
        }
    }

    /** Excel-friendly variant: semicolons and a byte order mark, as German Excel expects. */
    fun writeCsv(cards: List<Pair<String, String>>): String = buildString {
        append('﻿').append("Frage;Antwort\r\n")
        for ((f, b) in cards) append(escape(f, ';')).append(';').append(escape(b, ';')).append("\r\n")
    }

    private fun escape(s: String, sep: Char): String =
        if (s.any { it == sep || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

/**
 * Exam questions straight from a notebook or book text, without network. Builds on
 * [CardSuggester] for definitions and term lines and adds gap sentences for
 * statements carrying a value or a defined term. Only text the learner brought
 * goes in; the learner confirms each one before it counts.
 */
object TextQuestions {
    const val MAX = 40

    fun fromText(text: String): List<CardSuggestion> {
        val out = LinkedHashMap<String, CardSuggestion>()
        for (s in CardSuggester.suggest(text)) out.putIfAbsent(AnswerCheck.normalize(s.front), s)
        val backs = out.values.map { AnswerCheck.normalize(it.back) }
        for (line in text.replace("\r", "").lines()) {
            for (sentence in CardSuggester.sentences(line.trim())) {
                if (out.size >= MAX) return out.values.toList()
                val s = sentence.trim()
                val words = s.split(Regex("\\s+"))
                if (words.size !in 6..30 || s.endsWith("?") || ':' in s) continue
                // Only statements worth asking: with a number, or with a capitalised term beyond the start.
                val hasValue = s.any(Char::isDigit)
                val hasTerm = words.drop(1).any { w -> w.length >= 6 && w.first().isUpperCase() }
                if (!hasValue && !hasTerm) continue
                if (backs.any { it.isNotEmpty() && it == AnswerCheck.normalize(s) }) continue
                val gap = QuestionMaker.cloze(s) ?: continue
                out.putIfAbsent(AnswerCheck.normalize(gap.text), CardSuggestion("Ergänze: ${gap.text}", gap.answer, SuggestionKind.GAP))
            }
        }
        return out.values.toList()
    }
}
