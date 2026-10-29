package de.millione.schulranzen.core.math

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/**
 * German number formatting for results. Twelve significant digits hide the
 * usual binary noise (0,1 + 0,2 = 0,3) while keeping everything a school
 * calculator shows; very large or small values switch to "×10ⁿ".
 */
object GermanNumber {
    /** Narrow no-break space: groups thousands without being mistaken for a decimal point. */
    const val GROUP = ' '
    const val MINUS = '−'

    private const val SIGNIFICANT = 12
    private const val SCI_SIGNIFICANT = 10
    private val SUPERSCRIPT = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', '-' to '⁻',
    )

    /** Display form: decimal comma, grouped thousands from five digits on, typographic minus. */
    fun format(value: Double, grouping: Boolean = true): String = render(value, grouping, MINUS)

    /** Plain form that the calculator can read back in (no grouping, ASCII minus). */
    fun formatPlain(value: Double): String = render(value, false, '-')

    /** Rounds to [decimals] places for fixed-unit outputs (SHK results); trailing zeros dropped. */
    fun format(value: Double, decimals: Int, grouping: Boolean = true): String {
        if (!value.isFinite()) return "–"
        val bd = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros()
        if (bd.signum() == 0) return "0"
        // Too small for the requested places: fall back to significant digits.
        return decorate(bd.toPlainString(), grouping, MINUS)
    }

    private fun render(value: Double, grouping: Boolean, minus: Char): String {
        if (value.isNaN()) return "–"
        if (value.isInfinite()) return if (value > 0) "∞" else "${minus}∞"
        val a = abs(value)
        if (a < 1e-12) return "0"
        if (a >= 1e12 || a < 1e-6) return scientific(value, minus)
        val bd = BigDecimal.valueOf(value).round(MathContext(SIGNIFICANT, RoundingMode.HALF_UP)).stripTrailingZeros()
        if (bd.abs() >= BigDecimal("1E12")) return scientific(value, minus)
        return decorate(bd.toPlainString(), grouping, minus)
    }

    private fun decorate(plain: String, grouping: Boolean, minus: Char): String {
        val negative = plain.startsWith("-")
        val body = if (negative) plain.substring(1) else plain
        val dot = body.indexOf('.')
        val intPart = if (dot >= 0) body.substring(0, dot) else body
        val frac = if (dot >= 0) body.substring(dot + 1) else ""
        val grouped = if (grouping && intPart.length >= 5) group(intPart) else intPart
        val sb = StringBuilder()
        if (negative) sb.append(minus)
        sb.append(grouped)
        if (frac.isNotEmpty()) sb.append(',').append(frac)
        return sb.toString()
    }

    private fun group(digits: String): String {
        val sb = StringBuilder()
        digits.forEachIndexed { i, ch ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(GROUP)
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun scientific(value: Double, minus: Char): String {
        val bd = BigDecimal.valueOf(value).round(MathContext(SCI_SIGNIFICANT, RoundingMode.HALF_UP))
        val exp = bd.precision() - bd.scale() - 1
        val mantissa = bd.movePointLeft(exp).stripTrailingZeros().toPlainString()
        val m = decorate(mantissa, false, minus)
        val e = exp.toString().map { SUPERSCRIPT[it] ?: it }.joinToString("")
        return "$m×10$e"
    }
}
