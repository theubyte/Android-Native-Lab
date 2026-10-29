package de.millione.schulranzen.core.books

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max
import kotlin.math.min

/** Where the text of a page came from. Stored as a byte, so never reorder. */
enum class TextSource(val code: Int) {
    /** Looked at, nothing readable (a scanned page before text recognition). */
    NONE(0),
    /** The PDF's own text layer. */
    PDF(1),
    /** On-device text recognition of the rendered page. */
    OCR(2);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/**
 * The words of one page with their boxes. [text] is the page as words
 * joined by single spaces, lines separated by '\n'; word `i` spans
 * `text[starts[i] until ends[i]]` and its box (unrotated page units, see
 * [BookUnits]) is `boxes[4i .. 4i+3]` = left, top, right, bottom.
 * Flat arrays instead of objects: a 600-page book has ~300k words.
 */
class PageText(
    val page: Int,
    val source: TextSource,
    val text: String,
    val starts: IntArray,
    val ends: IntArray,
    val boxes: FloatArray,
) {
    val wordCount get() = starts.size
    val hasText get() = starts.isNotEmpty()

    fun box(word: Int) = UnitRect(boxes[word * 4], boxes[word * 4 + 1], boxes[word * 4 + 2], boxes[word * 4 + 3])

    /** The page's lines with the box around their words. */
    fun lines(): List<TextLine> {
        val out = ArrayList<TextLine>()
        var first = 0
        for (i in 0 until wordCount) {
            val lineEnds = i == wordCount - 1 || text.substring(ends[i], starts[i + 1]).contains('\n')
            if (lineEnds) {
                var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
                for (k in first..i) {
                    l = min(l, boxes[k * 4]); t = min(t, boxes[k * 4 + 1]); r = max(r, boxes[k * 4 + 2]); b = max(b, boxes[k * 4 + 3])
                }
                out += TextLine(text.substring(starts[first], ends[i]), UnitRect(l, t, r, b), first, i + 1)
                first = i + 1
            }
        }
        return out
    }

    companion object {
        fun empty(page: Int, source: TextSource = TextSource.NONE) = PageText(page, source, "", IntArray(0), IntArray(0), FloatArray(0))
    }
}

/** One line of a page: its text, box and word range `[firstWord, endWord)`. */
data class TextLine(val text: String, val box: UnitRect, val firstWord: Int, val endWord: Int) {
    val height get() = box.height
}

/**
 * Collects the words of a page in reading order. Empty words are skipped;
 * very long pages are cut at [MAX_CHARS] so one broken PDF page cannot blow
 * up the index.
 */
class PageTextBuilder(private val page: Int, private val source: TextSource) {
    private val sb = StringBuilder()
    private val starts = ArrayList<Int>()
    private val ends = ArrayList<Int>()
    private val boxes = ArrayList<Float>()
    private var lineBreak = false

    fun word(text: String, left: Float, top: Float, right: Float, bottom: Float) {
        val w = text.trim().replace(Regex("\\s+"), " ")
        if (w.isEmpty() || sb.length + w.length > MAX_CHARS) return
        if (sb.isNotEmpty()) sb.append(if (lineBreak) '\n' else ' ')
        lineBreak = false
        starts += sb.length
        sb.append(w)
        ends += sb.length
        boxes += min(left, right); boxes += min(top, bottom); boxes += max(left, right); boxes += max(top, bottom)
    }

    /** The next word starts a new line. */
    fun newLine() { if (sb.isNotEmpty()) lineBreak = true }

    fun build(): PageText = PageText(
        page, if (starts.isEmpty() && source == TextSource.PDF) TextSource.NONE else source,
        sb.toString(), starts.toIntArray(), ends.toIntArray(), boxes.toFloatArray(),
    )

    companion object {
        const val MAX_CHARS = 60_000
    }
}

/**
 * The searchable text of a whole book. [pages] holds every page that was
 * looked at (also those without text); [textDone] says the PDF text pass
 * went through all pages.
 */
class BookTextIndex(val pageCount: Int, pages: Map<Int, PageText>, val textDone: Boolean) {
    private val map = HashMap(pages)
    val pages: Map<Int, PageText> get() = map

    fun page(i: Int): PageText? = map[i]

    /** Pages in order, for searching and outlines. */
    fun ordered(): List<PageText> = map.values.sortedBy { it.page }

    val pagesWithText get() = map.values.count { it.hasText }

    /** Pages still without any text (candidates for text recognition). */
    fun pagesWithoutText(): List<Int> = (0 until pageCount).filter { map[it]?.hasText != true }

    /** No page has a text layer: a scanned book. */
    val looksScanned get() = textDone && map.values.none { it.source == TextSource.PDF && it.hasText }

    val hasOcr get() = map.values.any { it.source == TextSource.OCR }

    fun with(page: PageText): BookTextIndex = BookTextIndex(pageCount, map + (page.page to page), textDone)

    fun withAll(added: Collection<PageText>, textDone: Boolean = this.textDone): BookTextIndex =
        BookTextIndex(pageCount, map + added.associateBy { it.page }, textDone)
}

/**
 * Index file: gzip of "RIX1", version, page count, done flag, page entries.
 * Each entry: page, source, text (UTF-8, length-prefixed), word count, then
 * per word start, end and the box as four shorts in quarter units.
 */
object TextIndexCodec {
    private const val MAGIC = 0x52495831 // "RIX1"
    private const val VERSION = 1
    private const val Q = 4f

    fun write(out: OutputStream, index: BookTextIndex) {
        val gz = GZIPOutputStream(out.buffered(64 * 1024))
        val d = DataOutputStream(gz)
        d.writeInt(MAGIC); d.writeInt(VERSION)
        d.writeInt(index.pageCount)
        d.writeBoolean(index.textDone)
        val pages = index.ordered()
        d.writeInt(pages.size)
        for (p in pages) {
            d.writeInt(p.page)
            d.writeByte(p.source.code)
            val bytes = p.text.toByteArray(Charsets.UTF_8)
            d.writeInt(bytes.size); d.write(bytes)
            d.writeInt(p.wordCount)
            for (i in 0 until p.wordCount) {
                d.writeInt(p.starts[i]); d.writeInt(p.ends[i])
                for (k in 0 until 4) d.writeShort(quant(p.boxes[i * 4 + k]))
            }
        }
        d.flush()
        gz.finish()
        gz.flush()
    }

    fun read(input: InputStream): BookTextIndex {
        val d = DataInputStream(GZIPInputStream(input.buffered(64 * 1024)))
        if (d.readInt() != MAGIC) throw IOException("not an index")
        if (d.readInt() != VERSION) throw IOException("index version")
        val pageCount = d.readInt()
        val done = d.readBoolean()
        val n = d.readInt()
        if (n < 0 || n > 100_000) throw IOException("bad page count")
        val pages = HashMap<Int, PageText>(n * 2)
        repeat(n) {
            val page = d.readInt()
            val source = TextSource.of(d.readByte().toInt())
            val len = d.readInt()
            if (len < 0 || len > 4 * PageTextBuilder.MAX_CHARS + 16) throw IOException("bad text")
            val bytes = ByteArray(len).also { d.readFully(it) }
            val text = String(bytes, Charsets.UTF_8)
            val words = d.readInt()
            if (words < 0 || words > PageTextBuilder.MAX_CHARS) throw IOException("bad words")
            val starts = IntArray(words); val ends = IntArray(words); val boxes = FloatArray(words * 4)
            for (i in 0 until words) {
                starts[i] = d.readInt(); ends[i] = d.readInt()
                if (starts[i] < 0 || ends[i] > text.length || starts[i] > ends[i]) throw IOException("bad word")
                for (k in 0 until 4) boxes[i * 4 + k] = d.readShort() / Q
            }
            pages[page] = PageText(page, source, text, starts, ends, boxes)
        }
        return BookTextIndex(pageCount, pages, done)
    }

    private fun quant(v: Float): Int = (v * Q).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
}
