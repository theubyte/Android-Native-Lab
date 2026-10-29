package de.millione.schulranzen.core

import kotlin.math.hypot

/** Plane geometry for the eraser and selection tools (page units). */
object Geometry {
    fun pointSegmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Shortest distance between two segments. */
    fun segmentDistance(p1x: Float, p1y: Float, p2x: Float, p2y: Float, q1x: Float, q1y: Float, q2x: Float, q2y: Float): Float {
        if (segmentsIntersect(p1x, p1y, p2x, p2y, q1x, q1y, q2x, q2y)) return 0f
        return minOf(
            pointSegmentDistance(p1x, p1y, q1x, q1y, q2x, q2y),
            pointSegmentDistance(p2x, p2y, q1x, q1y, q2x, q2y),
            pointSegmentDistance(q1x, q1y, p1x, p1y, p2x, p2y),
            pointSegmentDistance(q2x, q2y, p1x, p1y, p2x, p2y),
        )
    }

    private fun segmentsIntersect(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
        fun cross(ox: Float, oy: Float, px: Float, py: Float, qx: Float, qy: Float) = (px - ox) * (qy - oy) - (py - oy) * (qx - ox)
        val d1 = cross(cx, cy, dx, dy, ax, ay); val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy); val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }
}
