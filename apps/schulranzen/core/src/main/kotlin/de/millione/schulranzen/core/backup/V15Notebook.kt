package de.millione.schulranzen.core.backup

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * Reader for the old notebook files (NotebookView.java), all versions the
 * old app could still migrate. Files live in `files/` of the V15 backup:
 *
 * | File | Magic | Content per page |
 * |---|---|---|
 * | `notebook-v15-<id>.bin` | SB15 | bookmark, locked, paper, title, background, strokes (x, y, pressure, tilt), texts, images |
 * | `notebook-v14-<id>.bin` | SB14 | same as V15 |
 * | `notebook-v13-<id>.bin` | SB13 | as V14 but points only x, y |
 * | `notebook-v9-<id>.bin`  | SB91 | as V13 without images |
 * | `notebook-v8.bin` (only "legacy-main") | SB81 | bookmark, paper, strokes (x, y), widths by tool |
 * | `notebook-v7.bin` (only "legacy-main") | SB71 | as V8, header without current page |
 * | `notebook-v3.bin` (only "legacy-main") | none | one page of strokes |
 *
 * Header from V9 on: magic, page count, current page, pages, trash count,
 * trash pages (the page trash is not imported). Strings are Java
 * `writeUTF`. Coordinates are in V15 units (1240 × 1754).
 */
object V15Notebook {
    const val PAGE_W = 1240f
    const val PAGE_H = 1754f

    const val MAGIC_V15 = 0x53423135
    const val MAGIC_V14 = 0x53423134
    const val MAGIC_V13 = 0x53423133
    const val MAGIC_V9 = 0x53423931
    const val MAGIC_V8 = 0x53423831
    const val MAGIC_V7 = 0x53423731

    /** V15 tools: 0 pencil, 1 ballpoint, 2 fountain pen, 3 marker, 6 ruler line. */
    const val TOOL_PENCIL = 0
    const val TOOL_MARKER = 3

    enum class Format { V15, V14, V13, V9, V8, V7, V3 }

    class Point(val x: Float, val y: Float, val pressure: Float = 1f, val tilt: Float = 0f)
    class Stroke(val color: Int, val tool: Int, val width: Float, val points: List<Point>)
    class Text(val x: Float, val y: Float, val size: Float, val color: Int, val text: String)
    class Image(val path: String, val x: Float, val y: Float, val w: Float, val h: Float, val anchored: Boolean)
    class Page(
        val bookmark: Boolean, val locked: Boolean, val paper: Int, val title: String, val background: String,
        val strokes: List<Stroke>, val texts: List<Text>, val images: List<Image>,
    )

    /** Same as NotebookView.safe(): the id as used in file names. */
    fun safeId(id: String): String = id.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    /** Candidate files for a notebook id, best first, as NotebookView tried them. */
    fun candidates(id: String): List<Pair<String, Format>> {
        val s = safeId(id)
        val list = mutableListOf(
            "notebook-v15-$s.bin" to Format.V15,
            "notebook-v14-$s.bin" to Format.V14,
            "notebook-v13-$s.bin" to Format.V13,
            "notebook-v9-$s.bin" to Format.V9,
        )
        if (id == "legacy-main") {
            list += "notebook-v8.bin" to Format.V8
            list += "notebook-v7.bin" to Format.V7
            list += "notebook-v3.bin" to Format.V3
        }
        return list
    }

    /** The live pages of a notebook file (trash skipped). Throws on a broken file. */
    fun read(bytes: ByteArray, format: Format): List<Page> {
        val d = Reader(bytes)
        return when (format) {
            Format.V15, Format.V14, Format.V13, Format.V9 -> {
                val magic = when (format) { Format.V15 -> MAGIC_V15; Format.V14 -> MAGIC_V14; Format.V13 -> MAGIC_V13; else -> MAGIC_V9 }
                if (d.int() != magic) throw IOException("Unbekanntes Heftformat")
                val n = d.count(MIN_PAGE)
                d.int() // current page
                List(n) { d.page(format) }
            }
            Format.V8 -> {
                if (d.int() != MAGIC_V8) throw IOException("Unbekanntes Heftformat")
                val n = d.count(MIN_LEGACY_PAGE)
                d.int()
                List(n) { d.legacyPage() }
            }
            Format.V7 -> {
                if (d.int() != MAGIC_V7) throw IOException("Unbekanntes Heftformat")
                val n = d.count(MIN_LEGACY_PAGE)
                List(n) { d.legacyPage() }
            }
            Format.V3 -> listOf(Page(false, false, 0, "", "", d.legacyStrokes(), emptyList(), emptyList()))
        }
    }

    /** Widths before V9 were not stored: the defaults per tool from NotebookView.legacyWidth(). */
    fun legacyWidth(tool: Int): Float = when (tool) { 0 -> 2f; 2 -> 3.2f; 3 -> 18f; else -> 4.2f }

    private const val MIN_PAGE = 2 + 4 + 2 + 2 + 4 + 4
    private const val MIN_LEGACY_PAGE = 1 + 4 + 4

    private class Reader(private val bytes: ByteArray) {
        private val input = DataInputStream(ByteArrayInputStream(bytes))
        private var pos = 0
        private fun need(n: Int) { if (bytes.size - pos < n) throw EOFException("Heftdatei ist abgeschnitten") }
        fun int(): Int { need(4); pos += 4; return input.readInt() }
        fun float(): Float { need(4); pos += 4; return input.readFloat() }
        fun bool(): Boolean { need(1); pos += 1; return input.readBoolean() }
        fun utf(): String {
            need(2)
            val len = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            need(2 + len)
            pos += 2 + len
            return input.readUTF()
        }

        /** A count that must fit into the rest of the file, so a broken file cannot ask for gigabytes. */
        fun count(minItemSize: Int): Int {
            val n = int()
            if (n < 0 || n.toLong() * minItemSize > bytes.size - pos) throw IOException("Heftdatei ist beschädigt")
            return n
        }

        fun page(format: Format): Page {
            val bookmark = bool(); val locked = bool(); val paper = int(); val title = utf(); val bg = utf()
            val strokes = List(count(16)) {
                val color = int(); val tool = int(); val width = float()
                val withPressure = format == Format.V15 || format == Format.V14
                val pts = List(count(if (withPressure) 16 else 8)) {
                    if (withPressure) Point(float(), float(), float(), float()) else Point(float(), float())
                }
                Stroke(color, tool, width, pts)
            }
            val texts = List(count(14)) { Text(float(), float(), float(), int(), utf()) }
            val images = if (format == Format.V9) emptyList() else List(count(19)) {
                Image(utf(), float(), float(), float(), float(), bool())
            }
            return Page(bookmark, locked, paper, title, bg, strokes, texts, images)
        }

        fun legacyPage(): Page {
            val bookmark = bool(); val paper = int()
            return Page(bookmark, false, paper, "", "", legacyStrokes(), emptyList(), emptyList())
        }

        fun legacyStrokes(): List<Stroke> = List(count(12)) {
            val color = int(); val tool = int()
            val pts = List(count(8)) { Point(float(), float()) }
            Stroke(color, tool, legacyWidth(tool), pts)
        }
    }

    // --- conversion to the new app's page units -------------------------

    /** Factor from V15 units to the new app's page units (see V15Model). */
    const val SCALE = 1000f / PAGE_W

    /** Maps a V15 file path (absolute, on the old tablet) to its entry in the backup. */
    fun entryFor(path: String, exists: (String) -> Boolean): String? {
        if (path.isBlank()) return null
        val i = path.indexOf("/files/")
        if (i >= 0) {
            val e = "files/" + path.substring(i + 7)
            if (ZipPaths.isSafe(e) && exists(e)) return e
        }
        return null
    }

    fun convert(page: Page, resolve: (String) -> String?, warn: (String) -> Unit): ImportedPage {
        val s = SCALE
        val strokes = page.strokes.mapNotNull { st -> convertStroke(st) }
        val texts = page.texts.filter { it.text.isNotEmpty() && it.x.isFinite() && it.y.isFinite() && it.size > 0f }.map { t ->
            val size = t.size * s
            val lines = t.text.split('\n')
            val longest = lines.maxOf { it.length }
            val x = t.x * s
            // V15 drew each line at its baseline, 1.25 × size apart. The new
            // TextItem starts at the top of the layout: move up by the ascent.
            val top = (t.y - t.size * ASCENT) * s
            val estimate = longest * size * 0.62f + size
            val room = max(size * 2, 1000f - x)
            ImportedText(x, max(0f, top), min(max(estimate, size * 2), room), size, t.color, t.text)
        }
        val images = ArrayList<ImportedImage>()
        if (page.background.isNotBlank()) {
            val e = resolve(page.background)
            if (e != null) images += ImportedImage(e, 0f, 0f, 1000f, PAGE_H * s, background = true)
            else warn("Hintergrundbild fehlt im Backup: ${page.background.substringAfterLast('/')}")
        }
        for (im in page.images) {
            if (!(im.w > 0f && im.h > 0f)) continue
            val e = resolve(im.path)
            if (e != null) images += ImportedImage(e, im.x * s, im.y * s, im.w * s, im.h * s)
            else warn("Bild fehlt im Backup: ${im.path.substringAfterLast('/')}")
        }
        return ImportedPage(ImportedPaper.fromV15(page.paper), page.title, page.bookmark, page.locked, strokes, texts, images)
    }

    private fun convertStroke(st: Stroke): ImportedStroke? {
        val pts = st.points.filter { it.x.isFinite() && it.y.isFinite() }
        // V15 never drew strokes with fewer than two points.
        if (pts.size < 2) return null
        val tool = if (st.tool == TOOL_MARKER) ImportedTool.MARKER else ImportedTool.PEN
        var color = st.color
        if (st.tool == TOOL_PENCIL) {
            // The pencil was drawn at alpha 220 of the colour.
            val a = (color ushr 24) * 220 / 255
            color = (a shl 24) or (color and 0x00FFFFFF)
        }
        val width = (if (st.width > 0f && st.width.isFinite()) st.width else 4.2f) * SCALE
        val arr = FloatArray(pts.size * 4)
        pts.forEachIndexed { i, p ->
            arr[i * 4] = p.x * SCALE
            arr[i * 4 + 1] = p.y * SCALE
            arr[i * 4 + 2] = if (p.pressure.isFinite()) p.pressure.coerceIn(0f, 1f) else 1f
            arr[i * 4 + 3] = if (p.tilt.isFinite()) p.tilt.coerceIn(0f, HALF_PI) else 0f
        }
        return ImportedStroke(tool, color, width, arr)
    }

    /** Ascent of the default Android sans font relative to its size. */
    private const val ASCENT = 0.93f
    private const val HALF_PI = (Math.PI / 2).toFloat()
}
