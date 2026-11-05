package de.millione.schulranzen.core.math

/**
 * Turns what people type or write into one canonical ASCII form the parser
 * understands. Handwriting recognisers return many look-alike symbols
 * (×, ·, ∶, Unicode minus, "x" for times, "O" for zero); folding them here
 * keeps the parser small and strict.
 */
internal object Normalizer {
    /** Marks the exponent of scientific notation for the lexer. */
    const val SCI = 'ᴇ'
    private val TIMES_X = Regex("(?<=[0-9.,)!%]|pi|ans|e)x(?=[0-9.,(]|sqrt|cbrt|sin|cos|tan|asin|acos|atan|arc|ln|log|lg|abs|pi|ans|e)")
    private const val SPACE = "[ \\t\\u00A0\\u2009\\u202F]"
    private val LINE_GAP = Regex("[0-9.,]\\s*[\\r\\n]\\s*[0-9.,]")
    /** A run of digits split by plain spaces; the capture is everything after the first group. */
    private val GAP = Regex("(?<![0-9.,])[0-9]+((?:$SPACE+[0-9]+)+)")
    private val THOUSANDS = Regex("(?:$SPACE+[0-9]{3})+")
    private val CHAR_MAP: Map<Char, String> = buildMap {
        "−–—‐‑‒﹣－".forEach { put(it, "-") }
        "×·∙•⋅✕✖✗＊⨯".forEach { put(it, "*") }
        "÷:∶：／⁄∕".forEach { put(it, "/") }
        "＋".forEach { put(it, "+") }
        "＝≈≃≡".forEach { put(it, "=") }
        "[{（".forEach { put(it, "(") }
        "]}）".forEach { put(it, ")") }
        put('√', "sqrt"); put('∛', "cbrt"); put('π', "pi"); put('Π', "pi")
        put('％', "%"); put('٫', ","); put('，', ","); put('．', ".")
        put('；', ";"); put('！', "!"); put('＾', "^")
    }
    private val SUPER: Map<Char, Char> = mapOf(
        '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4',
        '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9', '⁻' to '-',
    )

    fun normalize(raw: String, handwriting: Boolean): String {
        val sb = StringBuilder()
        var i = 0
        val src = markScientific(rejectGluedNumbers(raw.trim()))
        while (i < src.length) {
            val ch = src[i]
            if (ch in SUPER) {
                // A run of superscripts is one exponent: x⁻¹², not x^-^1^2.
                val run = StringBuilder()
                while (i < src.length && src[i] in SUPER) { run.append(SUPER.getValue(src[i])); i++ }
                sb.append('^')
                if (run.length == 1) sb.append(run) else sb.append('(').append(run).append(')')
                continue
            }
            if (ch.isWhitespace()) { i++; continue }
            sb.append(CHAR_MAP[ch] ?: if (ch == SCI) ch.toString() else ch.lowercaseChar().toString())
            i++
        }
        var s = sb.toString()
        // Words people write instead of symbols.
        s = s.replace("wurzel", "sqrt").replace("mal", "*").replace("durch", "/")
        if (handwriting) s = fixHandwriting(s)
        // An x is a times sign only between two operands ("3x4", "3 x (2+1)"). "2x+3" is an
        // equation with a variable; reading it as 2·3 would be silently wrong.
        s = TIMES_X.replace(s, "*")
        // A colon is sometimes read as a semicolon between digits.
        s = s.replace(Regex("(?<=[0-9)]);(?=[0-9(])"), "/")
        // Only the left side of "a = b" is the task; a trailing "=" just asks for the result.
        val eq = s.indexOf('=')
        if (eq >= 0) {
            val left = s.substring(0, eq)
            s = if (left.isNotEmpty()) left else s.substring(eq + 1).trimEnd('=')
        }
        return s
    }

    /**
     * Whitespace used to vanish, so "12 5" became 125 and two written lines
     * melted into one number. Only real thousands groups ("1 000") may stay apart.
     */
    private fun rejectGluedNumbers(src: String): String {
        if (LINE_GAP.containsMatchIn(src)) throw MathException("Zwei Zeilen: bitte nur eine Rechnung auswählen")
        GAP.findAll(src).forEach { m ->
            if (!m.groupValues[1].matches(THOUSANDS)) throw MathException("Zwischen den Zahlen fehlt ein Rechenzeichen")
        }
        return src
    }

    /** "1e5" and "1,5E-3" are powers of ten, not 1·e·5. A small "2e-1" could be either, so it asks. */
    private fun markScientific(src: String): String {
        if (Regex("[0-9.,]e[+\\-−][0-9]").containsMatchIn(src)) {
            throw MathException("„e“ ist mehrdeutig: 2·e−1 oder 2×10^−1 ausschreiben")
        }
        return src.replace(Regex("(?<=[0-9.,])[eE](?=[0-9])"), SCI.toString())
            .replace(Regex("(?<=[0-9.,])E(?=[+\\-−][0-9])"), SCI.toString())
    }

    /** Conservative OCR fixes: letters only become digits where a digit is expected. */
    private fun fixHandwriting(input: String): String {
        var s = input
        val before = "(?<=[0-9(+\\-*/^.,])"
        val after = "(?=[0-9)+\\-*/^.,%]|$)"
        s = s.replace(Regex("${before}[o](?=[0-9)+\\-*/^.,%]|$)"), "0")
        s = s.replace(Regex("^o(?=[0-9.,+\\-*/^])"), "0")
        s = s.replace(Regex("${before}[l|i]$after"), "1")
        s = s.replace(Regex("^[l|](?=[0-9.,+\\-*/^])"), "1")
        s = s.replace(Regex("(?<=[0-9])s(?=[0-9])"), "5")
        s = s.replace(Regex("(?<=[0-9])z(?=[0-9])"), "2")
        s = s.replace(Regex("(?<=[0-9])b(?=[0-9])"), "6")
        s = s.replace(Regex("(?<=[0-9])g(?=[0-9])"), "9")
        s = s.replace('|', '1')
        return s
    }
}
