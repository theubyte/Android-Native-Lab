package de.millione.schulranzen.books

import android.view.MotionEvent

/**
 * Keeps a view to its own pointers. When the book sits next to a notebook,
 * a touch event can carry pointers that went down on the other side (the
 * writing hand there, the pen here). [split] builds a copy of the event
 * with only the [owned] pointers, history included, so ink stays smooth.
 */
internal object TouchFence {
    /** The event reduced to [owned] pointers, or null when it is only about other pointers. */
    fun split(e: MotionEvent, owned: Set<Int>): MotionEvent? {
        val keep = (0 until e.pointerCount).filter { e.getPointerId(it) in owned }
        if (keep.isEmpty()) return null
        val action = e.actionMasked
        val actionId = e.getPointerId(e.actionIndex)
        val newAction = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (actionId !in owned) return null
                if (keep.size == 1) MotionEvent.ACTION_DOWN else pointerAction(MotionEvent.ACTION_POINTER_DOWN, keep, e, actionId)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (actionId !in owned) return null
                if (keep.size == 1) MotionEvent.ACTION_UP else pointerAction(MotionEvent.ACTION_POINTER_UP, keep, e, actionId)
            }
            else -> action
        }
        val n = keep.size
        val props = Array(n) { MotionEvent.PointerProperties().also { p -> e.getPointerProperties(keep[it], p) } }
        val coords = Array(n) { MotionEvent.PointerCoords() }
        val history = e.historySize
        for (k in 0 until n) {
            if (history > 0) e.getHistoricalPointerCoords(keep[k], 0, coords[k]) else e.getPointerCoords(keep[k], coords[k])
        }
        val first = if (history > 0) e.getHistoricalEventTime(0) else e.eventTime
        val out = MotionEvent.obtain(
            e.downTime, first, newAction, n, props, coords, e.metaState, e.buttonState,
            e.xPrecision, e.yPrecision, e.deviceId, e.edgeFlags, e.source, e.flags,
        )
        for (h in 1 until history) {
            for (k in 0 until n) e.getHistoricalPointerCoords(keep[k], h, coords[k])
            out.addBatch(e.getHistoricalEventTime(h), coords, e.metaState)
        }
        if (history > 0) {
            for (k in 0 until n) e.getPointerCoords(keep[k], coords[k])
            out.addBatch(e.eventTime, coords, e.metaState)
        }
        return out
    }

    private fun pointerAction(base: Int, keep: List<Int>, e: MotionEvent, id: Int): Int {
        val index = keep.indexOfFirst { e.getPointerId(it) == id }.coerceAtLeast(0)
        return base or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    }
}
