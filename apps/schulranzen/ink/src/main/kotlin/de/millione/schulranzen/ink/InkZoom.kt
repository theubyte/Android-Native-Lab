package de.millione.schulranzen.ink

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The zoom a page's ink is recorded for. The renderer smooths the edges for
 * the size it is told, so a page's display list is recorded again when the
 * zoom moves by a step (× 1.2).
 *
 * Recording costs time for every stroke on the page. Recording every visible
 * page in the frame where a pinch crossed a step made zooming out stutter:
 * zoomed out, many full pages are on screen at once. So:
 * - while the fingers zoom out, a page keeps the list it has. That list was
 *   recorded for a closer zoom, so the ink is still sharp; only its edge
 *   smoothing is finer than needed;
 * - lists due for the new zoom are recorded a few per frame within [FrameBudget].
 *   The other pages keep their old list until their turn.
 * Once the zoom is at rest, every page is again recorded for exactly its zoom.
 */
object InkZoom {
    private val STEP_LN = ln(1.2)

    fun step(scale: Float): Int = (ln(scale.toDouble()) / STEP_LN).roundToInt()

    fun scaleOf(step: Int): Float = exp(step * STEP_LN).toFloat()

    /** A list recorded for zoom step [recorded] may stay while the fingers zoom out past it. */
    fun mayWait(recorded: Int, current: Int, zooming: Boolean): Boolean = zooming && recorded > current
}

/** Time per frame for recording pages whose only change is the zoom. */
class FrameBudget(private val nanos: Long = 4_000_000L, private val clock: () -> Long = System::nanoTime) {
    private var start = 0L

    /** A page was put off to the next frame: the caller asks for one. */
    var later = false
        private set

    /** A page waits until the zoom rests. */
    var afterZoom = false
        private set

    fun begin() { start = clock(); later = false; afterZoom = false }

    /** Whether a page whose zoom step changed is recorded now. */
    fun refresh(recorded: Int, current: Int, zooming: Boolean): Boolean {
        if (recorded == current) return false
        if (InkZoom.mayWait(recorded, current, zooming)) { afterZoom = true; return false }
        if (clock() - start < nanos) return true
        later = true
        return false
    }
}
