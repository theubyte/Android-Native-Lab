package de.millione.schulranzen.core.notebook

/** Where things may lie on a sheet, so nothing ends up where it can't be seen or reached again. */
object PagePlacement {
    /**
     * Shift (dx, dy) that brings a selection's centre back onto the sheet.
     * A lasso only finds what lies mostly on the page; dropped further out,
     * the content would be stored but gone for the user.
     */
    fun centreOnPage(left: Float, top: Float, right: Float, bottom: Float, pageW: Float, pageH: Float, margin: Float = 20f): Pair<Float, Float> {
        val cx = (left + right) / 2; val cy = (top + bottom) / 2
        return (cx.coerceIn(margin, pageW - margin) - cx) to (cy.coerceIn(margin, pageH - margin) - cy)
    }

    /**
     * Box (x, y, w, h) for a picture of [aspect] (height / width) around
     * (cx, cy): 60 % of the page width, never taller than 80 % of the page,
     * and completely on the sheet. Tall photos would otherwise be cut off.
     */
    fun imageBox(aspect: Float, cx: Float, cy: Float, pageW: Float, pageH: Float): FloatArray {
        val a = if (aspect > 0f && aspect.isFinite()) aspect else 0.75f
        val w = minOf(pageW * 0.6f, pageH * 0.8f / a)
        val h = w * a
        return floatArrayOf((cx - w / 2).coerceIn(0f, pageW - w), (cy - h / 2).coerceIn(0f, pageH - h), w, h)
    }

    /**
     * Text placed at a tap (x, y, width): near the right or bottom edge it
     * moves inwards, because the sheet clips what sticks out.
     */
    fun textBox(x: Float, y: Float, fontSize: Float, pageW: Float, pageH: Float, minWidth: Float = 160f, margin: Float = 40f): FloatArray {
        val px = x.coerceIn(0f, pageW - minWidth - margin)
        val py = y.coerceIn(0f, (pageH - fontSize * 1.5f).coerceAtLeast(0f))
        return floatArrayOf(px, py, pageW - px - margin)
    }
}
