package de.millione.schulranzen.core.shapes

import kotlin.math.abs
import kotlin.math.max

/**
 * One line of handwriting: [words] are stroke indices grouped by word gaps,
 * left to right; inside a word the strokes keep their writing order (i dots
 * and t bars come last, as the recogniser expects). [block] numbers the
 * paragraph/column the line belongs to.
 */
data class TextLine(val words: List<List<Int>>, val bounds: Bounds, val wordBounds: List<Bounds>, val block: Int) {
    /** All strokes of the line in recognition order. */
    val strokes: List<Int> get() = words.flatten()
}

/**
 * Groups the strokes of a page (or a selection) into lines and words of
 * text, so multi-line handwriting can be recognised line by line.
 *
 * Works on stroke bounds only and adapts to the writing size via the median
 * stroke height. Lines may slope: a stroke joins the line whose nearest
 * strokes to the left sit at the same height. Small strokes (dots, accents,
 * commas) are attached afterwards to the nearest line.
 */
object LineGrouper {

    fun group(strokes: List<Bounds>): List<TextLine> {
        if (strokes.isEmpty()) return emptyList()
        val h = typicalHeight(strokes)
        val small = strokes.indices.filter { max(strokes[it].width, strokes[it].height) < 0.4f * h }.toSet()

        // Pass 1: normal strokes, left to right, each joins the line at its height.
        class Building { val members = ArrayList<Int>(); val recent = ArrayList<Float>() }
        val lines = ArrayList<Building>()
        val order = strokes.indices.filter { it !in small }.sortedBy { strokes[it].left }
        for (i in order) {
            val b = strokes[i]
            val cy = b.centerY
            var best: Building? = null; var bestD = Float.MAX_VALUE
            for (l in lines) {
                val ref = l.recent.takeLast(3).average().toFloat()
                val d = abs(ref - cy)
                if (d < bestD) { bestD = d; best = l }
            }
            val target = if (best != null && bestD <= 0.6f * h) best else Building().also { lines += it }
            target.members += i
            // Very tall or flat strokes (brackets, fraction bars) do not move the line's height.
            if (b.height in 0.4f * h..2.2f * h) target.recent += cy
            else if (target.recent.isEmpty()) target.recent += cy
        }
        // Pass 2: dots and other small marks join the nearest line band.
        for (i in small.sorted()) {
            val b = strokes[i]
            var best: Building? = null; var bestD = Float.MAX_VALUE
            for (l in lines) {
                val lb = Bounds.unionOf(l.members.map { strokes[it] })!!
                val dy = when { b.centerY < lb.top -> lb.top - b.centerY; b.centerY > lb.bottom -> b.centerY - lb.bottom; else -> 0f }
                val dx = when { b.centerX < lb.left -> lb.left - b.centerX; b.centerX > lb.right -> b.centerX - lb.right; else -> 0f }
                val d = dy + dx * 0.5f
                if (d < bestD) { bestD = d; best = l }
            }
            if (best != null && bestD <= 0.9f * h) best.members += i
            else lines += Building().also { it.members += i; it.recent += b.centerY }
        }

        // Words by horizontal gaps; very wide gaps split a line into separate columns.
        val pieces = ArrayList<List<List<Int>>>()
        for (l in lines) {
            val segment = ArrayList<List<Int>>()
            var current: MutableList<Int>? = null
            var right = -Float.MAX_VALUE
            for (i in l.members.sortedBy { strokes[it].left }) {
                val b = strokes[i]
                val gap = b.left - right
                if (current == null || gap > 0.6f * h) {
                    if (current != null) segment += current
                    if (current != null && gap > 4f * h) { pieces += segment.toList(); segment.clear() }
                    current = mutableListOf()
                }
                current += i
                right = max(right, b.right)
            }
            if (current != null) segment += current
            if (segment.isNotEmpty()) pieces += segment.toList()
        }

        class Piece(val words: List<List<Int>>, val wordBounds: List<Bounds>) { val bounds = Bounds.unionOf(wordBounds)!! }
        val built = pieces.map { ws ->
            val words = ws.map { it.sorted() }
            Piece(words, words.map { w -> Bounds.unionOf(w.map { strokes[it] })!! })
        }.sortedBy { it.bounds.centerY }

        // Blocks: a line continues the block whose last line sits just above it and overlaps it sideways.
        val blocks = ArrayList<MutableList<Piece>>()
        for (p in built) {
            val target = blocks.filter { bl ->
                val last = bl.last().bounds
                p.bounds.centerY - last.centerY <= 3.5f * h && p.bounds.left < last.right && p.bounds.right > last.left
            }.minByOrNull { p.bounds.centerY - it.last().bounds.centerY }
            if (target != null) target += p else blocks += mutableListOf(p)
        }
        // Reading order: blocks top to bottom (left to right when level), lines inside top to bottom.
        val ordered = blocks.sortedWith(compareBy<MutableList<Piece>>({ (it.first().bounds.top / (2 * h)).toInt() }, { it.first().bounds.left }))
        return ordered.flatMapIndexed { bi, bl -> bl.map { TextLine(it.words, it.bounds, it.wordBounds, bi) } }
    }

    /** Median height of the strokes that are not tiny marks; the "x-height plus" of this writer. */
    fun typicalHeight(strokes: List<Bounds>): Float {
        val hs = strokes.map { max(it.height, 1f) }.sorted()
        val big = hs.filter { it >= hs[hs.size / 2] * 0.3f }
        return max(big[big.size / 2], 6f)
    }
}
