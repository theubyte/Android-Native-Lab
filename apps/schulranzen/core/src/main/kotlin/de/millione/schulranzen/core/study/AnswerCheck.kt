package de.millione.schulranzen.core.study

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Outcome of checking a typed answer. */
enum class Verdict(val grade: SelfGrade) {
    /** Matches. */
    RIGHT(SelfGrade.RIGHT),
    /** Matches apart from a typing slip; counts as right, the UI shows the clean spelling. */
    TYPO(SelfGrade.RIGHT),
    /** Part of it: some list items, a missing unit. */
    PARTLY(SelfGrade.PARTLY),
    WRONG(SelfGrade.WRONG),
    /** Too free for a machine (a whole explanation): the learner or Claude has to judge it. */
    UNCHECKABLE(SelfGrade.SKIPPED),
}

enum class AnswerHint { UNIT_MISSING, UNIT_WRONG, NUMBER_WRONG, NEGATION, TOO_MANY_VALUES }

data class AnswerResult(
    val verdict: Verdict,
    /** How many required parts were met, for lists ("2 von 3"). */
    val matched: Int = 0,
    val total: Int = 0,
    val hint: AnswerHint? = null,
)

/**
 * Tolerant checking of typed answers. It forgives what an exam corrector would
 * forgive (umlaut spelling, capitals, articles, typing slips, 1,5 vs 1.5, 1,5 bar
 * vs 150 kPa) and nothing that changes the meaning: digits are never fuzzy
 * ("60" is not "80" although they differ by one letter), and "nicht" counts.
 */
object AnswerCheck {
    /** Longer free answers cannot be judged by comparing words. */
    private const val MAX_CHECKABLE_WORDS = 8
    private const val MAX_CHECKABLE_PARTS = 12

    private val fillers = setOf(
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
        "ca", "circa", "etwa", "rund", "ungefaehr", "mindestens", "mind", "hoechstens", "max", "min", "maximal", "minimal",
        "und", "sowie", "von", "im", "in", "am", "an", "auf", "zu", "zum", "zur", "fuer", "mit", "bei", "beim", "ist", "sind", "wird", "werden",
    )
    private val negations = setOf("nicht", "kein", "keine", "keinen", "keinem", "keiner", "nie", "niemals", "ohne")

    /** Lower case, umlauts spelled out, punctuation gone. Digits stay. */
    fun normalize(text: String): String = text.lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** Damerau-Levenshtein with transposition, the usual typing slips. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** Short words must be exact ("warm"/"kalt", "Ohr"/"Rohr"); longer ones allow more slips. */
    private fun allowedSlips(len: Int): Int = when {
        len <= 4 -> 0
        len <= 7 -> 1
        len <= 12 -> 2
        else -> 3
    }

    private fun wordsMatch(expected: String, given: String): Verdict? {
        if (expected == given) return Verdict.RIGHT
        return if (distance(expected, given) <= allowedSlips(expected.length)) Verdict.TYPO else null
    }

    /**
     * "1,5" and "1.5" → 1.5; "1.500" and "1.500,5" use the German thousands dot;
     * "1,500" stays one and a half. Null when it is no number.
     */
    fun parseNumber(raw: String): Double? {
        val t = raw.trim().replace('−', '-').replace(" ", "")
        val german = when {
            ',' in t && '.' in t -> t.replace(".", "").replace(',', '.')
            ',' in t -> t.replace(',', '.')
            Regex("-?\\d{1,3}(\\.\\d{3})+").matches(t) -> t.replace(".", "")
            else -> t
        }
        return german.toDoubleOrNull()
    }

    /** A known unit: family plus factor to the family's base. */
    private data class UnitInfo(val family: String, val factor: Double)

    private val units: Map<String, UnitInfo> = buildMap {
        fun add(family: String, factor: Double, vararg names: String) = names.forEach { put(it, UnitInfo(family, factor)) }
        add("temp", 1.0, "°c", "°", "grad", "gradcelsius", "celsius", "gradc")
        add("kelvin", 1.0, "k", "kelvin")
        add("press", 1.0, "pa", "pascal")
        add("press", 100.0, "hpa", "mbar", "millibar")
        add("press", 1000.0, "kpa")
        add("press", 1e5, "bar")
        add("press", 1e6, "mpa")
        add("len", 1e-3, "mm", "millimeter")
        add("len", 1e-2, "cm", "zentimeter")
        add("len", 0.1, "dm", "dezimeter")
        add("len", 1.0, "m", "meter")
        add("len", 1000.0, "km", "kilometer")
        add("vol", 1e-3, "ml", "milliliter")
        add("vol", 1.0, "l", "liter")
        add("vol", 1000.0, "m3", "m³", "kubikmeter")
        add("area", 1e-4, "cm2", "cm²")
        add("area", 1.0, "m2", "m²", "quadratmeter")
        add("power", 1.0, "w", "watt")
        add("power", 1000.0, "kw", "kilowatt")
        add("energy", 1.0, "wh")
        add("energy", 1000.0, "kwh")
        add("time", 1.0, "s", "sek", "sekunde", "sekunden")
        add("time", 60.0, "min", "minute", "minuten")
        add("time", 3600.0, "h", "std", "stunde", "stunden")
        add("time", 86400.0, "d", "tag", "tage", "tagen")
        add("mass", 1e-3, "g", "gramm")
        add("mass", 1.0, "kg", "kilogramm")
        add("mass", 1000.0, "t", "tonne", "tonnen")
        add("pct", 1.0, "%", "prozent")
        add("volt", 1.0, "v", "volt")
        add("amp", 1.0, "a", "ampere")
    }

    private data class Quantity(val value: Double, val unit: String?) {
        val info: UnitInfo? get() = unit?.let { units[it] }
    }

    private val quantityRe = Regex("""(?<![\p{L}\d.,])([-−]?\d[\d.,]*\d|[-−]?\d)\s*(°\s*[cC]|°|%|[\p{L}][\p{L}²³\d]*(?:/[\p{L}²³\d]+)?)?""")

    private fun quantities(text: String): List<Quantity> = quantityRe.findAll(text).mapNotNull { m ->
        val v = parseNumber(m.groupValues[1]) ?: return@mapNotNull null
        val raw = m.groupValues[2].lowercase().replace(" ", "")
        Quantity(v, raw.ifEmpty { null })
    }.toList()

    private fun withoutQuantities(text: String): String = quantityRe.replace(text) { m ->
        // Unknown words after a number ("3 Schritte") stay words.
        val raw = m.groupValues[2].lowercase().replace(" ", "")
        if (raw.isNotEmpty() && raw !in units && '/' !in raw) " ${m.groupValues[2]} " else " "
    }

    private fun sameValue(a: Double, b: Double) = abs(a - b) <= 1e-9 * max(1.0, max(abs(a), abs(b)))

    /** Remarks in brackets are extras on a card: "60 °C (Legionellenschutz)". */
    private fun stripBrackets(s: String) = s.replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\[[^\\]]*\\]"), " ")

    private fun parts(expected: String): List<String> =
        expected.split('\n').map { it.trim().trimStart('•', '-', '–', '*', '·', ' ').trim() }.filter { it.isNotEmpty() }

    private fun alternatives(part: String): List<String> =
        part.split(Regex("\\s+oder\\s+|\\s+bzw\\.?\\s+|\\s+/\\s+|;")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun contentWords(text: String): List<String> {
        val n = normalize(text).split(' ').filter { it.isNotEmpty() }
        val content = n.filter { it !in fillers }
        return content.ifEmpty { n }
    }

    /** Whether [expected] can be compared by machine at all. */
    fun canCheck(expected: String): Boolean {
        val clean = stripBrackets(expected)
        if (clean.isBlank()) return false
        val ps = parts(clean)
        if (ps.isEmpty() || ps.size > MAX_CHECKABLE_PARTS) return false
        return ps.all { p -> alternatives(p).all { contentWords(withoutQuantities(it)).size <= MAX_CHECKABLE_WORDS } }
    }

    fun check(given: String, expected: String): AnswerResult {
        @Suppress("NAME_SHADOWING") val expected = QuestionMaker.plain(expected)
        if (!canCheck(expected)) return AnswerResult(Verdict.UNCHECKABLE)
        val required = parts(stripBrackets(expected))
        if (given.isBlank()) return AnswerResult(Verdict.WRONG, 0, required.size)
        if (required.size == 1) return one(given, required[0]).let { it.copy(matched = if (it.verdict.grade == SelfGrade.RIGHT) 1 else 0, total = 1) }

        // A list: every item must be named somewhere, in any order.
        val pieces = given.split(Regex("[\\n;,]|\\s+und\\s+|\\s+sowie\\s+")).map { it.trim().trimStart('•', '-', '–', '*', '·', ' ') }.filter { it.isNotEmpty() }
        var matched = 0
        var typo = false
        val free = pieces.toMutableList()
        for (item in required) {
            val hit = free.firstOrNull { one(it, item).verdict.grade == SelfGrade.RIGHT } ?: continue
            matched++
            if (one(hit, item).verdict == Verdict.TYPO) typo = true
            free.remove(hit)
        }
        val verdict = when {
            matched == required.size -> if (typo) Verdict.TYPO else Verdict.RIGHT
            matched == 0 -> Verdict.WRONG
            else -> Verdict.PARTLY
        }
        return AnswerResult(verdict, matched, required.size)
    }

    private fun one(given: String, expected: String): AnswerResult {
        val alts = (alternatives(expected) + expected).distinct()
        return alts.map { single(given, it) }.minByOrNull { it.verdict.ordinal } ?: AnswerResult(Verdict.WRONG)
    }

    private fun single(given: String, expected: String): AnswerResult {
        val wantQ = quantities(expected)
        val haveQ = quantities(given)
        val wantWords = contentWords(withoutQuantities(expected))
        val haveWords = contentWords(withoutQuantities(given))
        // "mindestens 60 °C": around a value, filler words are not asked for.
        val wantPlain = normalize(withoutQuantities(expected)).split(' ').filter { it.isNotEmpty() }
        val hasWantWords = wantPlain.isNotEmpty() && (wantQ.isEmpty() || wantPlain.any { it !in fillers })

        // Meaning flips on a negation: never forgive that.
        val wantNeg = normalize(expected).split(' ').any { it in negations }
        val haveNeg = normalize(given).split(' ').any { it in negations }
        if (wantNeg != haveNeg) return AnswerResult(Verdict.WRONG, hint = AnswerHint.NEGATION)

        var unitMissing = false
        if (wantQ.isNotEmpty()) {
            val pool = haveQ.toMutableList()
            for (w in wantQ) {
                val exact = pool.firstOrNull { h -> matches(w, h) == QMatch.OK }
                if (exact != null) { pool.remove(exact); continue }
                val bare = pool.firstOrNull { h -> matches(w, h) == QMatch.NO_UNIT }
                if (bare != null) { pool.remove(bare); unitMissing = true; continue }
                val wrongUnit = pool.any { h -> matches(w, h) == QMatch.WRONG_UNIT }
                return AnswerResult(Verdict.WRONG, hint = if (wrongUnit) AnswerHint.UNIT_WRONG else AnswerHint.NUMBER_WRONG)
            }
            // "60 oder 80" must not score on 60: hedging is no answer.
            if (pool.isNotEmpty()) return AnswerResult(Verdict.WRONG, hint = AnswerHint.TOO_MANY_VALUES)
        } else if (haveQ.isNotEmpty() && !hasWantWords) {
            return AnswerResult(Verdict.WRONG, hint = AnswerHint.NUMBER_WRONG)
        }

        var verdict = Verdict.RIGHT
        if (hasWantWords && wantWords.isNotEmpty()) {
            // Compound spelling: "Membran Ausdehnungsgefäß" and "Membranausdehnungsgefäß" are the same.
            val joined = wordsMatch(wantWords.joinToString(""), haveWords.joinToString(""))
            if (joined != null) {
                verdict = joined
            } else {
                var found = 0
                var slip = false
                val pool = haveWords.toMutableList()
                for (w in wantWords) {
                    val hit = pool.mapNotNull { h -> wordsMatch(w, h)?.let { h to it } }.minByOrNull { it.second.ordinal } ?: continue
                    found++
                    if (hit.second == Verdict.TYPO) slip = true
                    pool.remove(hit.first)
                }
                val share = found.toDouble() / wantWords.size
                verdict = when {
                    found == wantWords.size -> if (slip) Verdict.TYPO else Verdict.RIGHT
                    wantWords.size >= 3 && share >= 0.6 -> Verdict.PARTLY
                    else -> return AnswerResult(Verdict.WRONG)
                }
            }
        }
        if (unitMissing) return AnswerResult(Verdict.PARTLY, hint = AnswerHint.UNIT_MISSING)
        return AnswerResult(verdict)
    }

    private enum class QMatch { OK, NO_UNIT, WRONG_UNIT, DIFFERENT }

    private fun matches(want: Quantity, have: Quantity): QMatch {
        val wi = want.info
        val hi = have.info
        // A word after the number that is no unit ("3 Schritte") is judged as a word, not here.
        val wantUnit = want.unit?.takeIf { wi != null || '/' in it }
        val haveUnit = have.unit?.takeIf { hi != null || '/' in it }
        val same = sameValue(want.value, have.value)
        if (wantUnit == null) return if (same) QMatch.OK else QMatch.DIFFERENT
        if (haveUnit == null) return if (same) QMatch.NO_UNIT else QMatch.DIFFERENT
        if (wi != null && hi != null) {
            if (wi.family != hi.family) return if (same) QMatch.WRONG_UNIT else QMatch.DIFFERENT
            return when {
                sameValue(want.value * wi.factor, have.value * hi.factor) -> QMatch.OK
                same -> QMatch.WRONG_UNIT
                else -> QMatch.DIFFERENT
            }
        }
        // Compound units are compared as written: "m³/h", "l/min".
        return when {
            !same -> QMatch.DIFFERENT
            wantUnit == haveUnit -> QMatch.OK
            else -> QMatch.WRONG_UNIT
        }
    }
}
