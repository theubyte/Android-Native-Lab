package de.millione.schulranzen.core.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal sealed interface Token {
    val pos: Int
    data class Num(val value: Double, override val pos: Int) : Token
    data class Word(val name: String, override val pos: Int) : Token
    data class Sym(val ch: Char, override val pos: Int) : Token
}

/** Splits the canonical string into numbers, known words and operator symbols. */
internal object Lexer {
    /** Longest first, so "arcsin" wins over "sin" and "pi" over "p". */
    private val WORDS = listOf(
        "arcsin", "arccos", "arctan", "asin", "acos", "atan",
        "sqrt", "cbrt", "sin", "cos", "tan", "abs", "ans", "log", "lg", "ln", "pi", "e",
    )
    private const val SYMBOLS = "+-*/^%!()°"

    fun tokenize(s: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch.isDigit() || ch == '.' || ch == ',' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) i++
                    var value = parseNumber(s.substring(start, i), start)
                    if (i < s.length && s[i] == Normalizer.SCI) {
                        i++
                        val expStart = i
                        if (i < s.length && s[i] in "+-") i++
                        while (i < s.length && s[i].isDigit()) i++
                        val exp = s.substring(expStart, i).toIntOrNull() ?: throw MathException("Ungültige Zahl", start)
                        value = "${value}E$exp".toDouble()
                        if (value.isInfinite()) throw MathException("Ergebnis ist zu groß", start)
                    }
                    out += Token.Num(value, start)
                }
                ch in 'a'..'z' -> {
                    val start = i
                    while (i < s.length && s[i] in 'a'..'z') i++
                    splitWords(s.substring(start, i), start, out)
                }
                ch in SYMBOLS -> { out += Token.Sym(ch, i); i++ }
                else -> throw MathException("Unbekanntes Zeichen „$ch“", i)
            }
        }
        return out
    }

    private fun splitWords(run: String, offset: Int, out: MutableList<Token>) {
        var i = 0
        while (i < run.length) {
            val w = WORDS.firstOrNull { run.startsWith(it, i) }
                ?: throw MathException(
                    if (run[i] == 'x') "Unbekannt: „x“. Variablen kann der Rechner nicht ausrechnen" else "Unbekannt: „${run.substring(i)}“",
                    offset + i,
                )
            out += Token.Word(canonicalWord(w), offset + i)
            i += w.length
        }
    }

    private fun canonicalWord(w: String) = when (w) {
        "arcsin" -> "asin"; "arccos" -> "acos"; "arctan" -> "atan"; "lg" -> "log"
        else -> w
    }

    /**
     * Accepts decimal comma and point. With both present the last one is the
     * decimal mark ("1.234,5" and "1,234.5"); a repeated single mark is only
     * valid as thousands grouping ("1.000.000").
     */
    internal fun parseNumber(text: String, pos: Int): Double {
        val bad = { MathException("Ungültige Zahl „$text“", pos) }
        if (text.none { it.isDigit() }) throw bad()
        val commas = text.count { it == ',' }
        val dots = text.count { it == '.' }
        val plain: String = when {
            commas > 0 && dots > 0 -> {
                val decimal = if (text.lastIndexOf(',') > text.lastIndexOf('.')) ',' else '.'
                val group = if (decimal == ',') '.' else ','
                if (text.count { it == decimal } > 1) throw bad()
                val (intPart, frac) = text.split(decimal)
                if (!validGrouping(intPart, group)) throw bad()
                intPart.replace(group.toString(), "") + "." + frac
            }
            commas + dots == 0 -> text
            // "1.234" means 1234 in a German text and 1,234 on a pocket calculator: ask instead of guessing.
            dots == 1 && commas == 0 && AMBIGUOUS_DOT.matches(text) ->
                throw MathException("„$text“ ist mehrdeutig: Dezimalzahlen mit Komma schreiben, Tausender ohne Punkt", pos)
            commas + dots == 1 -> text.replace(',', '.')
            else -> {
                val mark = if (commas > 0) ',' else '.'
                if (!validGrouping(text, mark)) throw bad()
                text.replace(mark.toString(), "")
            }
        }
        val value = plain.toDoubleOrNull() ?: throw bad()
        if (value.isInfinite()) throw MathException("Ergebnis ist zu groß", pos)
        return value
    }

    private val AMBIGUOUS_DOT = Regex("[1-9][0-9]{0,2}\\.[0-9]{3}")

    private fun validGrouping(intPart: String, group: Char): Boolean {
        if (group !in intPart) return true
        val parts = intPart.split(group)
        return parts.first().length in 1..3 && parts.drop(1).all { it.length == 3 }
    }
}

/**
 * Recursive-descent parser that evaluates while it parses.
 *
 * Grammar (lowest to highest binding):
 * ```
 * sum     := product (('+'|'-') product)*        a ± b% means a ± a·b/100
 * product := signed (('*'|'/') signed | signed)*  juxtaposition multiplies: 2π, 3(4+1)
 * signed  := ('+'|'-') signed | power              so -2^2 = -4
 * power   := postfix ('^' signed)?                right associative: 2^3^2 = 2^9
 * postfix := primary ('!' | '%' | '°')*
 * primary := number | pi | e | ans | func arg | '(' sum ')'
 * ```
 * Missing closing brackets at the end are added, as on a pocket calculator.
 */
internal class Parser(
    private val tokens: List<Token>,
    private val angle: AngleMode,
    private val ans: Double,
) {
    private var p = 0
    private var depth = 0

    private class Operand(val v: Double, val percent: Boolean = false)

    fun parse(): Double {
        if (tokens.isEmpty()) throw MathException("Keine Rechnung eingegeben")
        val v = sum().v
        if (p < tokens.size) {
            val t = tokens[p]
            if (t is Token.Sym && t.ch == ')') throw MathException("Zu viele schließende Klammern", t.pos)
            throw MathException("Unerwartet: „${describe(t)}“", t.pos)
        }
        return checked(v)
    }

    private fun sum(): Operand {
        var left = product()
        var acc = left.v
        while (true) {
            val op = peekSym() ?: break
            if (op != '+' && op != '-') break
            p++
            val right = product()
            // Shop arithmetic: 200 + 10 % = 220, as people expect from a calculator.
            val r = if (right.percent) acc * right.v else right.v
            acc = cancelNoise(checked(if (op == '+') acc + r else acc - r), acc, r)
            left = Operand(acc)
        }
        return left
    }

    private fun product(): Operand {
        var left = signed()
        while (true) {
            val op = peekSym()
            left = when {
                op == '*' -> { p++; val r = signed().v; Operand(notVanished(checked(left.v * r), left.v, r)) }
                op == '/' -> {
                    val pos = tokens[p].pos
                    p++
                    val d = signed().v
                    if (d == 0.0) throw MathException("Division durch 0 ist nicht möglich", pos)
                    Operand(notVanished(checked(left.v / d), left.v, d))
                }
                startsPrimary() -> { val r = signed().v; Operand(notVanished(checked(left.v * r), left.v, r)) }
                else -> return left
            }
        }
    }

    private fun signed(): Operand {
        val op = peekSym()
        if (op == '-' || op == '+') {
            p++
            val o = nested { signed() }
            return if (op == '-') Operand(-o.v, o.percent) else o
        }
        return power()
    }

    /** Guards the recursion: 400 opening brackets must give a message, not a stack overflow. */
    private inline fun <T> nested(block: () -> T): T {
        if (++depth > MAX_DEPTH) throw MathException("Die Rechnung ist zu tief verschachtelt")
        try { return block() } finally { depth-- }
    }

    /** a − b with a ≈ b only leaves binary noise (0,1 + 0,2 − 0,3): that is a true 0, anything larger stays. */
    private fun cancelNoise(result: Double, a: Double, b: Double): Double =
        if (result != 0.0 && abs(result) < 4 * Math.ulp(maxOf(abs(a), abs(b)))) 0.0 else result

    private fun notVanished(result: Double, a: Double, b: Double): Double {
        if (result == 0.0 && a != 0.0 && b != 0.0) throw MathException("Ergebnis ist zu klein")
        return result
    }

    private fun power(): Operand {
        val base = postfix()
        if (peekSym() != '^') return base
        val pos = tokens[p].pos
        p++
        if (p >= tokens.size) throw MathException("Nach „^“ fehlt der Exponent", pos)
        val exp = nested { signed() }.v
        return Operand(pow(base.v, exp, pos))
    }

    private fun postfix(): Operand {
        var o = primary()
        while (true) {
            when (peekSym()) {
                '%' -> { p++; o = Operand(o.v / 100.0, percent = true) }
                '!' -> { val pos = tokens[p].pos; p++; o = Operand(factorial(o.v, pos)) }
                // An explicit degree sign is always degrees, also in RAD mode.
                '°' -> { p++; if (angle == AngleMode.RAD) o = Operand(o.v * PI / 180.0) }
                else -> return o
            }
        }
    }

    private fun primary(): Operand {
        if (p >= tokens.size) {
            val pos = tokens.lastOrNull()?.pos ?: 0
            throw MathException("Hier fehlt noch eine Zahl", pos)
        }
        val t = tokens[p]
        return when (t) {
            is Token.Num -> { p++; Operand(t.value) }
            is Token.Sym -> when (t.ch) {
                '(' -> {
                    p++
                    if (peekSym() == ')') throw MathException("Leere Klammer", t.pos)
                    val v = nested { sum() }.v
                    closeBracket()
                    Operand(v)
                }
                ')' -> throw MathException("Klammer „)“ ohne Inhalt davor", t.pos)
                else -> throw MathException("Vor „${opName(t.ch)}“ fehlt eine Zahl", t.pos)
            }
            is Token.Word -> {
                p++
                when (t.name) {
                    "pi" -> Operand(PI)
                    "e" -> Operand(Math.E)
                    "ans" -> Operand(ans)
                    else -> Operand(function(t.name, argument(t), t.pos))
                }
            }
        }
    }

    /** "sin(30)", but also handwritten "sin30" and "√9". */
    private fun argument(fn: Token.Word): Double {
        if (p >= tokens.size) throw MathException("Nach „${fnLabel(fn.name)}“ fehlt der Wert", fn.pos)
        if (peekSym() == '(') {
            val open = tokens[p].pos
            p++
            if (peekSym() == ')') throw MathException("Leere Klammer nach „${fnLabel(fn.name)}“", open)
            val v = nested { sum() }.v
            closeBracket()
            return v
        }
        return nested { signed() }.v
    }

    private fun closeBracket() {
        if (peekSym() == ')') { p++; return }
        if (p >= tokens.size) return // auto-close at the end
        throw MathException("Hier fehlt „)“", tokens[p].pos)
    }

    private fun function(name: String, x: Double, pos: Int): Double {
        val toRad = if (angle == AngleMode.DEG) PI / 180.0 else 1.0
        val v = when (name) {
            "sin" -> if (angle == AngleMode.DEG) degSin(x) else sin(x)
            "cos" -> if (angle == AngleMode.DEG) degSin(x + 90.0) else cos(x)
            "tan" -> {
                if (angle == AngleMode.DEG) {
                    val m = ((x % 180.0) + 180.0) % 180.0
                    if (abs(m - 90.0) < 1e-9) throw MathException("tan(${GermanNumber.format(x)}°) ist nicht definiert", pos)
                    if (abs(m) < 1e-9 || abs(m - 180.0) < 1e-9) 0.0
                    else if (abs(m - 45.0) < 1e-9) 1.0 else if (abs(m - 135.0) < 1e-9) -1.0
                    else tan(x * toRad)
                } else {
                    if (abs(cos(x)) < 1e-12) throw MathException("tan ist hier nicht definiert", pos)
                    tan(x)
                }
            }
            "asin", "acos" -> {
                if (x < -1.0 - 1e-12 || x > 1.0 + 1e-12) throw MathException("${fnLabel(name)} geht nur für Werte von −1 bis 1", pos)
                val c = x.coerceIn(-1.0, 1.0)
                (if (name == "asin") asin(c) else acos(c)) / toRad
            }
            "atan" -> atan(x) / toRad
            "sqrt" -> {
                if (x < 0) throw MathException("Wurzel aus einer negativen Zahl ist nicht reell", pos)
                sqrt(x)
            }
            "cbrt" -> cbrt(x)
            "ln" -> {
                if (x <= 0) throw MathException("ln geht nur für Zahlen größer als 0", pos)
                ln(x)
            }
            "log" -> {
                if (x <= 0) throw MathException("log geht nur für Zahlen größer als 0", pos)
                log10(x)
            }
            "abs" -> abs(x)
            else -> throw MathException("Unbekannte Funktion „$name“", pos)
        }
        return checked(if (name in TRIG) snapTrig(v, x) else v, pos)
    }

    /** sin in degrees with exact values on the axes and at ±30°/150°, which students check by hand. */
    private fun degSin(x: Double): Double {
        val m = ((x % 360.0) + 360.0) % 360.0
        fun near(a: Double) = abs(m - a) < 1e-9
        return when {
            near(0.0) || near(180.0) || near(360.0) -> 0.0
            near(90.0) -> 1.0
            near(270.0) -> -1.0
            near(30.0) || near(150.0) -> 0.5
            near(210.0) || near(330.0) -> -0.5
            else -> sin(m * PI / 180.0)
        }
    }

    private fun pow(b: Double, e: Double, pos: Int): Double {
        if (b == 0.0 && e < 0) throw MathException("Division durch 0 ist nicht möglich", pos)
        if (b == 0.0 && e == 0.0) throw MathException("0 hoch 0 ist nicht festgelegt", pos)
        var r = b.pow(e)
        if (r.isNaN() && b < 0) {
            // (-8)^(1/3) = -2: odd roots of negative numbers are real.
            val k = 1.0 / e
            val kr = round(k)
            if (abs(k - kr) < 1e-9 && kr.toLong() % 2L != 0L) r = -((-b).pow(e))
            else throw MathException("Keine reelle Lösung (negative Basis)", pos)
        }
        if (r == 0.0 && b != 0.0) throw MathException("Ergebnis ist zu klein", pos)
        return checked(r, pos)
    }

    private fun factorial(x: Double, pos: Int): Double {
        if (x < 0 || x != round(x) || x > 170) throw MathException("Fakultät „!“ nur für ganze Zahlen von 0 bis 170", pos)
        var f = 1.0
        for (i in 2..x.toInt()) f *= i
        return f
    }

    /** sin(π) comes out as 1,2·10⁻¹⁶. Only an argument of real size can leave such noise; sin(10⁻²⁰) stays. */
    private fun snapTrig(v: Double, x: Double) = if (abs(v) < 1e-13 && abs(x) > 1e-3) 0.0 else v

    private fun checked(v: Double, pos: Int = -1): Double {
        if (v.isNaN()) throw MathException("Ergebnis ist keine reelle Zahl", pos)
        if (v.isInfinite()) throw MathException("Ergebnis ist zu groß", pos)
        return v
    }

    private fun peekSym(): Char? = (tokens.getOrNull(p) as? Token.Sym)?.ch

    private fun startsPrimary(): Boolean = when (val t = tokens.getOrNull(p)) {
        is Token.Num, is Token.Word -> true
        is Token.Sym -> t.ch == '('
        null -> false
    }

    private companion object {
        const val MAX_DEPTH = 64
        val TRIG = setOf("sin", "cos", "tan")
    }

    private fun describe(t: Token) = when (t) {
        is Token.Num -> GermanNumber.format(t.value)
        is Token.Word -> t.name
        is Token.Sym -> opName(t.ch)
    }

    private fun opName(c: Char) = when (c) { '*' -> "×"; '/' -> "÷"; '-' -> "−"; else -> c.toString() }

    private fun fnLabel(n: String) = when (n) {
        "sqrt" -> "√"; "cbrt" -> "∛"; "asin" -> "sin⁻¹"; "acos" -> "cos⁻¹"; "atan" -> "tan⁻¹"; else -> n
    }
}
