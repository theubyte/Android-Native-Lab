package de.millione.schulranzen.core.math

import kotlin.math.abs

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
    } catch (e: StackOverflowError) {
        MathOutcome.Error("Die Rechnung ist zu tief verschachtelt")
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
        "ans" to "ANS", "pi" to "π", Normalizer.SCI.toString() to "×10^",
    )

    /**
     * Solves what the recogniser read. Returns null when no candidate is a
     * calculation (plain words, a lone number, a formula with variables).
     *
     * Follows the recogniser's own ranking instead of guessing from how many
     * digits a candidate has. The result is [MathResult.uncertain] as soon as
     * letters had to be repaired, text was skipped, or another highly ranked
     * reading gives a different number: the UI then asks to check the reading,
     * because a wrong result that looks sure is the worst outcome here.
     */
    fun solveHandwriting(
        candidates: List<String>,
        angle: AngleMode = AngleMode.DEG,
        ans: Double = 0.0,
    ): MathResult? {
        val readings = ArrayList<Reading>()
        candidates.forEachIndexed { rank, raw ->
            variants(raw).forEach { (text, repaired) ->
                val expr = runCatching { normalize(text, handwriting = true) }.getOrNull() ?: return@forEach
                if (expr.isEmpty() || expr.length > 160 || expr.none { it.isDigit() } || !looksLikeCalculation(expr)) return@forEach
                val value = runCatching { evaluate(text, angle, ans, handwriting = true).value }.getOrNull() ?: return@forEach
                val plain = runCatching { normalize(text, handwriting = false) }.getOrNull()
                readings += Reading(raw, expr, value, rank, repaired || plain != expr)
            }
        }
        // A clean reading always beats a repaired one; among equals the recogniser's order decides.
        val best = readings.minWithOrNull(compareBy({ it.repaired }, { it.rank })) ?: return null
        val others = readings.filter { abs(it.value - best.value) > RESULT_TOLERANCE * maxOf(1.0, abs(best.value)) }
            .filter { it.rank <= best.rank + CLOSE_RANKS }
            .distinctBy { it.expression }
        val alternatives = others.take(3).map { "${pretty(it.expression)} = ${GermanNumber.format(it.value)}" }
        return MathResult(best.raw, best.expression, best.value, uncertain = best.repaired || others.isNotEmpty(), alternatives = alternatives)
    }

    private data class Reading(val raw: String, val expression: String, val value: Double, val rank: Int, val repaired: Boolean)

    private const val RESULT_TOLERANCE = 1e-9
    /** Readings this close behind the best one count as real doubt; ML Kit lists up to 24, most far-fetched. */
    private const val CLOSE_RANKS = 3

    private fun looksLikeCalculation(expr: String): Boolean =
        expr.any { it in "+-*/^%!" } || FUNCTIONS.any { expr.contains(it) }

    private val FUNCTIONS = listOf("sqrt", "cbrt", "sin", "cos", "tan", "ln", "log")

    /**
     * The candidate as is, then each line on its own, then look-alike letter
     * fixes. The flag says the text was changed, so the result is only a guess.
     * Lines are joined only for column sums, where the next line starts with an
     * operator; a removed "=" must never glue the answer onto the task (3+4=7 → 3+47).
     */
    private fun variants(raw: String): List<Pair<String, Boolean>> {
        if (raw.isBlank()) return emptyList()
        val out = LinkedHashMap<String, Boolean>()
        val lines = raw.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 1) {
            if (lines.drop(1).all { it.first() in "+-−–*×·/:÷=" }) out[lines.joinToString("")] = false
            else lines.forEach { out.putIfAbsent(it, true) }
        } else {
            out[lines.first()] = false
        }
        out.keys.toList().forEach { text ->
            val low = text.lowercase()
            val letters = low.count { it in 'a'..'z' }
            val hasOperands = low.count { it.isDigit() } >= letters
            if (FUNCTIONS.none { low.contains(it) } && letters > 0 && hasOperands) {
                val common = low.map { LOOKALIKE[it] ?: it }.joinToString("")
                out.putIfAbsent(common, true)
                out.putIfAbsent(common.replace('b', '6'), true)
                out.putIfAbsent(common.replace('b', '8'), true)
            }
        }
        return out.map { it.key to it.value }
    }

    private val LOOKALIKE = mapOf('o' to '0', 'l' to '1', 'i' to '1', 'z' to '2', 's' to '5', 'g' to '9', 't' to '+')
}
