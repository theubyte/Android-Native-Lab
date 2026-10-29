package de.millione.schulranzen.core.math

/**
 * Turns what people type or write into one canonical ASCII form the parser
 * understands. Handwriting recognisers return many look-alike symbols
 * (×, ·, ∶, Unicode minus, "x" for times, "O" for zero); folding them here
 * keeps the parser small and strict.
 */
internal object Normalizer {
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
        val src = raw.trim()
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
            sb.append(CHAR_MAP[ch] ?: ch.lowercaseChar().toString())
            i++
        }
        var s = sb.toString()
        // Words people write instead of symbols.
        s = s.replace("wurzel", "sqrt").replace("mal", "*").replace("durch", "/")
        if (handwriting) s = fixHandwriting(s)
        // No variables exist, so every x is a times sign ("3x4", "3 x (2+1)").
        s = s.replace('x', '*')
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
