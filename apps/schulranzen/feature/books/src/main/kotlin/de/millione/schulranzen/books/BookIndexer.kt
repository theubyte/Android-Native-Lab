package de.millione.schulranzen.books

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import de.millione.schulranzen.core.books.BookTextIndex
import de.millione.schulranzen.core.books.BookUnits
import de.millione.schulranzen.core.books.PageText
import de.millione.schulranzen.core.books.PageTextBuilder
import de.millione.schulranzen.core.books.TextIndexCodec
import de.millione.schulranzen.core.books.TextSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the searchable text of a book in the background, once:
 *
 * 1. the PDF's text layer with word positions (pdfbox), which also yields
 *    the table of contents;
 * 2. on request, text recognition (ML Kit, on the device, bundled Latin
 *    model) for pages without a text layer: scanned books.
 *
 * Work runs on the shelf's scope, so it goes on when the book is closed,
 * and is saved in chunks, so a stopped run resumes where it was.
 */
internal object BookIndexer {
    enum class Phase { TEXT, OCR }

    /** Running work of one book: phase and pages done of total. */
    data class Progress(val phase: Phase, val done: Int, val total: Int)

    /** Books with work running. */
    val running = MutableStateFlow<Map<String, Progress>>(emptyMap())

    /** Bumped for a book whenever its index or outline changed; sessions reload then. */
    val revisions = MutableStateFlow<Map<String, Int>>(emptyMap())

    private val jobs = HashMap<String, Job>()
    private val phases = HashMap<String, Phase>()
    /** The last two books' indexes: the open one and one indexing in the background. */
    private val cache = object : LinkedHashMap<String, BookTextIndex>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BookTextIndex>?) = size > 2
    }

    private const val CHUNK_TEXT = 25
    private const val CHUNK_OCR = 6
    /** Pixel width a page is rendered at for recognition (about 200 dpi for A4). */
    private const val OCR_WIDTH = 1700

    /** Starts the text pass unless the index is complete or work is running. */
    fun ensureText(store: ShelfStore, id: String) = start(store, id, Phase.TEXT)

    /** Starts text recognition for pages without text (runs the text pass first if needed). */
    fun startOcr(store: ShelfStore, id: String) {
        // A running text pass is replaced by one that goes on with recognition.
        val previous = synchronized(this) {
            if (jobs[id]?.isActive == true && phases[id] == Phase.TEXT) jobs.remove(id) else null
        }
        start(store, id, Phase.OCR, previous)
    }

    fun stop(id: String) {
        synchronized(this) { jobs.remove(id)?.cancel() }
        running.update { it - id }
    }

    /** The book's index (kept in memory for two books), or null when none was built yet. Call off the main thread. */
    fun index(store: ShelfStore, id: String): BookTextIndex? {
        synchronized(cache) { cache[id] }?.let { return it }
        val loaded = load(store, id) ?: return null
        synchronized(cache) { cache[id] = loaded }
        return loaded
    }

    private fun start(store: ShelfStore, id: String, phase: Phase, previous: Job? = null) {
        synchronized(this) {
            if (jobs[id]?.isActive == true) return
            phases[id] = phase
            val job = store.scope.launch {
                try {
                    // Never two passes writing the same index file.
                    previous?.cancelAndJoin()
                    val count = pageCount(store, id) ?: return@launch
                    var index = index(store, id) ?: BookTextIndex(count, emptyMap(), textDone = false)
                    if (!index.textDone) index = extractText(store, id, index)
                    else if (BookOutlines.cached(store, id) == null) { BookOutlines.fromIndex(store, id, index); changed(id) }
                    if (phase == Phase.OCR) recognize(store, id, index)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // A broken PDF just has no searchable text; reading it still works.
                } finally {
                    running.update { it - id }
                    synchronized(this@BookIndexer) { if (jobs[id] == coroutineContext[Job]) jobs.remove(id) }
                }
            }
            jobs[id] = job
        }
    }

    private suspend fun pageCount(store: ShelfStore, id: String): Int? {
        store.ensureLoaded()
        return store.records.value.firstOrNull { it.id == id }?.pageCount?.takeIf { it > 0 }
    }

    // ------------------------------------------------------------ text layer

    private suspend fun extractText(store: ShelfStore, id: String, start: BookTextIndex): BookTextIndex {
        var index = start
        val context = store.appContext
        val result = BookOutlines.open(context, store, id) { doc ->
            BookOutlines.embeddedOf(doc)?.let { BookOutlines.save(store, id, it) }
            val count = min(doc.numberOfPages, index.pageCount)
            val stripper = WordStripper()
            val batch = ArrayList<PageText>()
            for (page in 0 until count) {
                coroutineContext.ensureActive()
                if (index.page(page) != null) continue
                running.update { it + (id to Progress(Phase.TEXT, page, count)) }
                batch += runCatching { stripper.page(doc, page) }.getOrElse { PageText.empty(page) }
                if (batch.size >= CHUNK_TEXT) { index = index.withAll(batch); batch.clear(); publish(store, id, index) }
            }
            index = index.withAll(batch, textDone = true)
            publish(store, id, index)
            index
        } ?: throw IOException("pdf")
        BookOutlines.fromIndex(store, id, result)
        changed(id)
        return result
    }

    /**
     * Collects words with their boxes. pdfbox calls [writeString] once per
     * word and [writeLineSeparator] between lines; positions are display
     * coordinates (top left origin, page rotation applied) in points.
     */
    private class WordStripper : PDFTextStripper() {
        private var builder: PageTextBuilder? = null
        private var scale = 1f

        init { sortByPosition = true }

        fun page(doc: PDDocument, index: Int): PageText {
            val p = doc.getPage(index)
            val box = p.cropBox
            val turned = (p.rotation % 180) != 0
            val width = if (turned) box.height else box.width
            scale = if (width > 0f) BookUnits.WIDTH / width else 1f
            val b = PageTextBuilder(index, TextSource.PDF)
            builder = b
            startPage = index + 1
            endPage = index + 1
            getText(doc)
            builder = null
            return b.build()
        }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            val b = builder ?: return
            if (text.isNullOrBlank() || textPositions.isNullOrEmpty()) return
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
            for (tp in textPositions) {
                val h = tp.heightDir.takeIf { it > 0f } ?: (tp.fontSizeInPt * 0.7f)
                l = min(l, tp.xDirAdj); r = max(r, tp.xDirAdj + tp.widthDirAdj)
                // pdfbox's height is the x-height region; widen for capitals and descenders.
                t = min(t, tp.yDirAdj - h * 1.25f); bottom = max(bottom, tp.yDirAdj + h * 0.3f)
            }
            b.word(text, l * scale, t * scale, r * scale, bottom * scale)
        }

        override fun writeLineSeparator() { builder?.newLine() }
        override fun writeWordSeparator() {}
        override fun writeParagraphStart() {}
        override fun writeParagraphEnd() { builder?.newLine() }
        override fun writePageStart() {}
        override fun writePageEnd() {}
    }

    // ------------------------------------------------------- recognition

    private suspend fun recognize(store: ShelfStore, id: String, start: BookTextIndex) {
        var index = start
        val todo = index.pagesWithoutText()
        if (todo.isEmpty()) return
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            ParcelFileDescriptor.open(store.pdf(id), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val batch = ArrayList<PageText>()
                    todo.forEachIndexed { k, page ->
                        coroutineContext.ensureActive()
                        running.update { it + (id to Progress(Phase.OCR, k, todo.size)) }
                        if (page >= renderer.pageCount) return@forEachIndexed
                        batch += runCatching { recognizePage(renderer, page, recognizer) }.getOrElse { PageText.empty(page, TextSource.OCR) }
                        if (batch.size >= CHUNK_OCR) { index = index.withAll(batch); batch.clear(); publish(store, id, index) }
                    }
                    index = index.withAll(batch)
                    publish(store, id, index)
                }
            }
        } finally {
            // Whatever was recognised so far is saved; the outline uses it.
            BookOutlines.fromIndex(store, id, index)
            changed(id)
            recognizer.close()
        }
    }

    private fun recognizePage(renderer: PdfRenderer, page: Int, recognizer: com.google.mlkit.vision.text.TextRecognizer): PageText {
        val bmp: Bitmap
        val f: Float
        renderer.openPage(page).use { p ->
            val pw = p.width.toFloat().coerceAtLeast(1f); val ph = p.height.toFloat().coerceAtLeast(1f)
            // Long side at most 2.2× the width, so a strip page cannot eat the memory.
            f = min(OCR_WIDTH / pw, OCR_WIDTH * 2.2f / ph)
            val w = (pw * f).roundToInt().coerceAtLeast(1); val h = (ph * f).roundToInt().coerceAtLeast(1)
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, Matrix().apply { setScale(f, f) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
            val toUnits = BookUnits.WIDTH / bmp.width
            val b = PageTextBuilder(page, TextSource.OCR)
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    for (el in line.elements) {
                        val r = el.boundingBox ?: continue
                        b.word(el.text, r.left * toUnits, r.top * toUnits, r.right * toUnits, r.bottom * toUnits)
                    }
                    b.newLine()
                }
            }
            return b.build()
        } finally {
            bmp.recycle()
        }
    }

    // --------------------------------------------------------------- files

    private fun load(store: ShelfStore, id: String): BookTextIndex? {
        val file = store.indexFile(id)
        if (!file.isFile) return null
        return runCatching { file.inputStream().use { TextIndexCodec.read(it) } }.getOrNull()
    }

    /** Saves atomically and makes the new state visible to searches. */
    private fun publish(store: ShelfStore, id: String, index: BookTextIndex) {
        val file = store.indexFile(id)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.outputStream().use { TextIndexCodec.write(it, index) }
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw IOException("rename") }
        }
        synchronized(cache) { cache[id] = index }
        changed(id)
    }

    private fun changed(id: String) = revisions.update { it + (id to ((it[id] ?: 0) + 1)) }

    /** Forget a deleted book. */
    fun forget(id: String) {
        stop(id)
        synchronized(cache) { cache.remove(id) }
    }
}
