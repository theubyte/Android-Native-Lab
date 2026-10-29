package de.millione.schulranzen.core.math

/**
 * The calculator core: normalise, parse, evaluate, format. Pure Kotlin so the
 * pocket calculator, the handwriting solver and the SHK input fields all share
 * one tested implementation.
 */
object MathEngine {
    private const val MAX_LENGTH = 400

    /**
     * Evaluates [input] such as "336*(55:15)", "2,5²", "sin(30)" or "200+10%".
     * @param ans value of the ANS key (last result).
     * @param handwriting apply look-alike fixes for recognised handwriting (O→0, l→1 …).
     * @throws MathException with a German message when the input is not a valid calculation.
     */
    fun evaluate(
        input: String,
        angle: AngleMode = AngleMode.DEG,
        ans: Double = 0.0,
        handwriting: Boolean = false,
    ): MathResult {
        val expr = normalize(input, handwriting)
        if (expr.isEmpty()) throw MathException("Keine Rechnung eingegeben")
        if (expr.length > MAX_LENGTH) throw MathException("Die Rechnung ist zu lang")
        val value = Parser(Lexer.tokenize(expr), angle, ans).parse()
        return MathResult(input, expr, value)
    }

    /** Like [evaluate] but never throws; for live previews while typing. */
    fun tryEvaluate(
        input: String,
        angle: AngleMode = AngleMode.DEG,
        ans: Double = 0.0,
        handwriting: Boolean = false,
    ): MathOutcome = try {
        MathOutcome.Ok(evaluate(input, angle, ans, handwriting))
    } catch (e: MathException) {
        MathOutcome.Error(e.message ?: "Fehler", e.position)
    } catch (e: RuntimeException) {
        MathOutcome.Error("Rechnung nicht verständlich")
    }

    /** Canonical ASCII form ("3×4" → "3*4", "5:2" → "5/2", "√9" → "sqrt9"). */
    fun normalize(input: String, handwriting: Boolean = false): String = Normalizer.normalize(input, handwriting)

    /** Canonical form back to school symbols for display. */
    fun pretty(expression: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < expression.length) {
            val rest = expression.substring(i)
            val word = PRETTY_WORDS.firstOrNull { rest.startsWith(it.first) }
            if (word != null) { sb.append(word.second); i += word.first.length; continue }
            sb.append(
                when (val c = expression[i]) {
                    '*' -> '×'; '/' -> '÷'; '-' -> '−'; '.' -> ','
                    else -> c
                },
            )
            i++
        }
        return sb.toString()
    }

    private val PRETTY_WORDS = listOf(
        "sqrt" to "√", "cbrt" to "∛", "asin" to "sin⁻¹", "acos" to "cos⁻¹", "atan" to "tan⁻¹",
        "ans" to "ANS", "pi" to "π",
    )

    /**
     * Picks the most plausible calculation among handwriting candidates and
     * solves it. Returns null when none of them is a calculation (plain words,
     * a lone number, a formula with variables).
     *
     * Every candidate is tried as is and in a few repaired variants; the score
     * prefers many digits and operators, an explicit "=", and the recogniser's
     * own ranking.
     */
    fun solveHandwriting(
        candidates: List<String>,
        angle: AngleMode = AngleMode.DEG,
        ans: Double = 0.0,
    ): MathResult? {
        var best: MathResult? = null
        var bestScore = Int.MIN_VALUE
        candidates.forEachIndexed { rank, raw ->
            variants(raw).forEachIndexed { variantIndex, candidate ->
                val expr = try { normalize(candidate, handwriting = true) } catch (e: RuntimeException) { return@forEachIndexed }
                if (expr.isEmpty() || expr.length > 160 || expr.none { it.isDigit() } || !looksLikeCalculation(expr)) return@forEachIndexed
                val result = try { evaluate(candidate, angle, ans, handwriting = true) } catch (e: RuntimeException) { return@forEachIndexed }
                val score = score(raw, expr) - rank * 2 - variantIndex
                if (score > bestScore) {
                    bestScore = score
                    best = MathResult(raw, result.expression, result.value)
                }
            }
        }
        return best
    }

    private fun looksLikeCalculation(expr: String): Boolean =
        expr.any { it in "+-*/^%!" } || FUNCTIONS.any { expr.contains(it) }

    private val FUNCTIONS = listOf("sqrt", "cbrt", "sin", "cos", "tan", "ln", "log")

    private fun score(raw: String, expr: String): Int {
        var s = expr.count { it.isDigit() } * 4
        s += expr.count { it in "+-*/^%()" } * 3
        if (raw.contains('=')) s += 2
        s -= maxOf(0, expr.length - 45)
        return s
    }

    /** Raw candidate first, then each line on its own, then look-alike letter fixes. */
    private fun variants(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        if (raw.isBlank()) return emptyList()
        out += raw
        raw.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }.forEach { out += it }
        out += raw.replace("=", "")
        val low = raw.lowercase()
        if (FUNCTIONS.none { low.contains(it) }) {
            val common = low.map { LOOKALIKE[it] ?: it }.joinToString("")
            out += common
            out += common.replace('b', '6')
            out += common.replace('b', '8')
        }
        return out.toList()
    }

    private val LOOKALIKE = mapOf('o' to '0', 'l' to '1', 'i' to '1', 'z' to '2', 's' to '5', 'g' to '9', 't' to '+')
}
