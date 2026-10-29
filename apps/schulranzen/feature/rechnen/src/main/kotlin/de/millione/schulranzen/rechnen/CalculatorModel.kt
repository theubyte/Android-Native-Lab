package de.millione.schulranzen.rechnen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.millione.schulranzen.core.math.AngleMode
import de.millione.schulranzen.core.math.GermanNumber
import de.millione.schulranzen.core.math.MathEngine
import de.millione.schulranzen.core.math.MathOutcome

/** One key press in the input: what the display shows and what the engine reads. */
internal data class Piece(val display: String, val engine: String = display)

/**
 * Input logic of the pocket calculator. The input is a list of key presses
 * rather than a string, so backspace removes "sin(" as a whole, and the
 * display may use symbols (sin⁻¹) the engine spells differently (asin).
 */
internal class CalculatorModel {
    val pieces = mutableStateListOf<Piece>()
    /** Last "=" finished; the next digit starts fresh, the next operator continues with ANS. */
    var evaluated by mutableStateOf(false)
        private set
    var result by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val displayText: String get() = pieces.joinToString("") { it.display }
    private val engineText: String get() = pieces.joinToString("") { it.engine }

    /** Live preview while typing; only for real calculations, not a lone number. */
    fun preview(angle: AngleMode, ans: Double): String? {
        if (evaluated || pieces.isEmpty()) return null
        val text = engineText
        if (text.none { it in "+-−×÷*/^%!²³(" } && pieces.none { it.engine.endsWith("(") || it.engine == "ANS" }) return null
        return (MathEngine.tryEvaluate(text, angle, ans) as? MathOutcome.Ok)?.result?.text
    }

    fun digit(d: String) {
        if (evaluated) clearAll()
        error = null
        pieces += Piece(d)
    }

    fun comma() {
        if (evaluated) clearAll()
        error = null
        val last = pieces.lastOrNull()?.engine
        if (last == null || !last.last().isDigit()) pieces += Piece("0")
        pieces += Piece(",")
    }

    /** Binary/postfix operators continue from the last result, like on a real calculator. */
    fun operator(p: Piece) {
        error = null
        if (evaluated) { pieces.clear(); pieces += ANS; evaluated = false; result = null }
        else if (pieces.isEmpty() && p.engine != "-") pieces += ANS
        pieces += p
    }

    /** Functions, brackets and constants start a new input after "=". */
    fun insert(p: Piece) {
        if (evaluated) clearAll()
        error = null
        pieces += p
    }

    fun backspace() {
        if (evaluated) { evaluated = false; result = null }
        error = null
        if (pieces.isNotEmpty()) pieces.removeAt(pieces.lastIndex)
    }

    fun clearAll() {
        pieces.clear(); evaluated = false; result = null; error = null
    }

    fun load(entry: HistoryEntry) {
        clearAll()
        entry.display.zip(entry.engine).forEach { (d, e) -> pieces += Piece(d, e) }
    }

    /** Evaluates; on success returns the history entry so the caller can store it with the new ANS. */
    fun equals(angle: AngleMode, ans: Double): HistoryEntry? {
        if (pieces.isEmpty()) return null
        if (evaluated) return null
        return when (val out = MathEngine.tryEvaluate(engineText, angle, ans)) {
            is MathOutcome.Ok -> {
                result = out.result.text
                evaluated = true
                error = null
                HistoryEntry(pieces.map { it.display }, pieces.map { it.engine }, out.result.value)
            }
            is MathOutcome.Error -> { error = out.message; null }
        }
    }

    companion object {
        val ANS = Piece("ANS")
        fun format(v: Double) = GermanNumber.format(v)
    }
}
