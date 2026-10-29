package de.millione.schulranzen.books

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import de.millione.schulranzen.core.books.Outline
import de.millione.schulranzen.core.books.OutlineEntry
import de.millione.schulranzen.core.books.OutlineSource
import de.millione.schulranzen.core.books.TextOutlineBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Table of contents for a book: the PDF's own bookmarks when it has them,
 * otherwise found in the text layer ([TextOutlineBuilder]). Results are
 * cached next to the book, so the slow text pass runs once per book.
 */
internal object BookOutlines {

    /** Cached outline, or null. */
    fun cached(store: ShelfStore, id: String): Outline? = runCatching {
        ShelfStore.json.decodeFromString<Outline>(store.outlineFile(id).readText())
    }.getOrNull()?.takeIf { it.version == Outline.VERSION }

    /**
     * Embedded bookmarks only; quick, done when a book is opened. Returns
     * null when the PDF has none (then [analyze] can look at the text).
     */
    suspend fun embedded(context: Context, store: ShelfStore, id: String): Outline? = withContext(Dispatchers.IO) {
        open(context, store, id) { doc ->
            val entries = ArrayList<OutlineEntry>()
            doc.documentCatalog?.documentOutline?.firstChild?.let { walk(doc, it, 0, entries) }
            if (entries.size >= 2) {
                Outline(entries, OutlineSource.EMBEDDED, doc.numberOfPages, hasText = true).also { save(store, id, it) }
            } else null
        }
    }

    /**
     * Full analysis: bookmarks, else printed contents or headings from the
     * text layer. [progress] gets (page, count). Cancellable between pages.
     */
    suspend fun analyze(context: Context, store: ShelfStore, id: String, progress: (Int, Int) -> Unit): Outline =
        withContext(Dispatchers.IO) {
            open(context, store, id) { doc ->
                val count = doc.numberOfPages
                val embedded = ArrayList<OutlineEntry>()
                runCatching { doc.documentCatalog?.documentOutline?.firstChild?.let { walk(doc, it, 0, embedded) } }
                if (embedded.size >= 2) return@open Outline(embedded, OutlineSource.EMBEDDED, count, hasText = true)
                val builder = TextOutlineBuilder(count)
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                for (page in 0 until count) {
                    coroutineContext.ensureActive()
                    if (page % 4 == 0) progress(page, count)
                    val text = runCatching {
                        stripper.startPage = page + 1
                        stripper.endPage = page + 1
                        stripper.getText(doc)?.take(16_000)
                    }.getOrNull()
                    builder.addPage(page, text)
                }
                builder.build()
            }?.also { save(store, id, it) } ?: Outline()
        }

    private fun save(store: ShelfStore, id: String, outline: Outline) {
        runCatching { writeAtomically(store.outlineFile(id), ShelfStore.json.encodeToString(Outline.serializer(), outline)) }
    }

    private inline fun <T> open(context: Context, store: ShelfStore, id: String, block: (PDDocument) -> T?): T? {
        PDFBoxResourceLoader.init(context.applicationContext)
        // Mixed memory: big school books are not pulled completely into RAM.
        val memory = MemoryUsageSetting.setupMixed(12L * 1024 * 1024).setTempDir(context.cacheDir)
        val doc = runCatching { PDDocument.load(store.pdf(id), memory) }.getOrNull() ?: return null
        return doc.use { block(it) }
    }

    private fun walk(doc: PDDocument, first: PDOutlineItem, level: Int, out: MutableList<OutlineEntry>) {
        var item: PDOutlineItem? = first
        while (item != null && out.size < Outline.MAX_ENTRIES * 2) {
            runCatching {
                val title = item!!.title?.trim().orEmpty()
                val page = item!!.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } ?: -1
                if (title.isNotEmpty() && page >= 0) out += OutlineEntry(title, page, minOf(5, level))
                item!!.firstChild?.let { walk(doc, it, level + 1, out) }
            }
            item = item.nextSibling
        }
    }
}
