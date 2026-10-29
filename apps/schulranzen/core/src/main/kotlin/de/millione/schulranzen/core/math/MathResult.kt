package de.millione.schulranzen.core.math

/** How sin/cos/tan read their argument (and how the inverse functions answer). */
enum class AngleMode { DEG, RAD }

/**
 * A finished calculation.
 *
 * @property input what the user (or the recogniser) gave us, untouched.
 * @property expression the normalised form that was actually evaluated (ASCII operators).
 * @property value the exact double result; [text] is what to show.
 */
data class MathResult(val input: String, val expression: String, val value: Double) {
    /** German display form, e.g. "1232" or "0,3333333333". */
    val text: String get() = GermanNumber.format(value)

    /** The evaluated expression with school symbols (×, ÷, −, √, π, decimal comma). */
    val prettyExpression: String get() = MathEngine.pretty(expression)
}

/**
 * A calculation that cannot be done. [message] is German and meant for the
 * user; [position] points into the normalised expression when known (-1 otherwise).
 */
class MathException(message: String, val position: Int = -1) : IllegalArgumentException(message)

/** Result of [MathEngine.tryEvaluate]: either a value or a user-facing error. */
sealed interface MathOutcome {
    data class Ok(val result: MathResult) : MathOutcome
    data class Error(val message: String, val position: Int = -1) : MathOutcome
}
