package de.millione.schulranzen.books

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import de.millione.schulranzen.core.books.BookTextIndex
import de.millione.schulranzen.core.books.BookUnits
import de.millione.schulranzen.core.books.IndexOutline
import de.millione.schulranzen.core.books.Outline
import de.millione.schulranzen.core.books.OutlineEntry
import de.millione.schulranzen.core.books.OutlineSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Table of contents for a book: the PDF's own bookmarks when it has them,
 * otherwise built from the book's text index ([IndexOutline]: printed
 * contents or headings, also from text recognition). Cached next to the
 * book; the slow text pass is done by [BookIndexer].
 */
internal object BookOutlines {

    /** Cached outline, or null. */
    fun cached(store: ShelfStore, id: String): Outline? = runCatching {
        ShelfStore.json.decodeFromString<Outline>(store.outlineFile(id).readText())
    }.getOrNull()?.takeIf { it.version == Outline.VERSION }

    /**
     * Embedded bookmarks only; quick, done when a book is opened. Returns
     * null when the PDF has none (then the text index is used).
     */
    suspend fun embedded(context: Context, store: ShelfStore, id: String): Outline? = withContext(Dispatchers.IO) {
        open(context, store, id) { doc -> embeddedOf(doc)?.also { save(store, id, it) } }
    }

    fun embeddedOf(doc: PDDocument): Outline? {
        val entries = ArrayList<OutlineEntry>()
        runCatching { doc.documentCatalog?.documentOutline?.firstChild?.let { walk(doc, it, 0, entries) } }
        return if (entries.size >= 2) Outline(entries, OutlineSource.EMBEDDED, doc.numberOfPages, hasText = true) else null
    }

    /** Outline from the text index; never replaces embedded bookmarks. */
    fun fromIndex(store: ShelfStore, id: String, index: BookTextIndex): Outline {
        cached(store, id)?.takeIf { it.source == OutlineSource.EMBEDDED }?.let { return it }
        val sizes = store.cachedPageSizes(id)
        val outline = IndexOutline.build(index) { p ->
            if (sizes != null && p in sizes.widths.indices) BookUnits.height(sizes.widths[p], sizes.heights[p]) else BookUnits.WIDTH * 1.414f
        }
        save(store, id, outline)
        return outline
    }

    fun save(store: ShelfStore, id: String, outline: Outline) {
        runCatching { writeAtomically(store.outlineFile(id), ShelfStore.json.encodeToString(Outline.serializer(), outline)) }
    }

    inline fun <T> open(context: Context, store: ShelfStore, id: String, block: (PDDocument) -> T?): T? {
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
