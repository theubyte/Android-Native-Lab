package de.millione.schulranzen.books

import de.millione.schulranzen.core.notebook.SafeFile
import de.millione.schulranzen.ink.InkCodec
import de.millione.schulranzen.ink.ItemCodec
import de.millione.schulranzen.ink.PageInk
import de.millione.schulranzen.ink.PageItem
import de.millione.schulranzen.ink.PageStroke
import java.io.File

/**
 * Ink of one book, one file per page (`<page>.rnz`, the notebook's ink
 * format) plus typed text next to it (`<page>.items.json`, the notebook's
 * object format). Pages without ink have no file, so a 600-page book with
 * notes on ten pages has ten small files. Both writers go through a temp
 * file and a rename.
 *
 * Reading never turns a bad file into "empty", which the next save would
 * then write over: what is readable loads, the file keeps a `.damaged` copy
 * next to it, and a page that cannot be read at all is locked ([LoadedPage]).
 */
internal class BookInkStore(private val dir: File) {
    private fun file(page: Int) = File(dir, "$page.rnz")
    private fun itemsFile(page: Int) = File(dir, "$page.items.json")

    /** Pages that have ink or text, without reading them. */
    fun pagesWithInk(): Set<Int> =
        dir.list()?.mapNotNullTo(HashSet()) { name ->
            when {
                name.endsWith(".items.json") -> name.removeSuffix(".items.json").toIntOrNull()
                name.endsWith(".rnz") -> name.removeSuffix(".rnz").toIntOrNull()
                else -> null
            }
        } ?: emptySet()

    fun load(page: Int): LoadedPage {
        var damaged = false
        var unreadable = false
        var strokes: List<PageStroke> = emptyList()
        var items: List<PageItem> = emptyList()
        try {
            val read = InkCodec.readChecked(file(page))
            strokes = read.strokes
            if (read.damaged) { SafeFile.keepCopy(file(page)); damaged = true }
        } catch (_: Throwable) {
            // Read error or no memory: says nothing about the file, so nothing may replace it.
            SafeFile.keepCopy(file(page)); unreadable = true
        }
        try {
            items = ItemCodec.read(itemsFile(page))
        } catch (_: Throwable) {
            SafeFile.keepCopy(itemsFile(page)); unreadable = true
        }
        return LoadedPage(PageInk(strokes, items), damaged, unreadable)
    }

    fun save(page: Int, strokes: List<PageStroke>, items: List<PageItem>) {
        dir.mkdirs()
        // Text first: when typed text replaces handwriting, a crash in between leaves both, not neither.
        if (items.isEmpty()) itemsFile(page).delete() else ItemCodec.write(itemsFile(page), items)
        if (strokes.isEmpty()) file(page).delete() else InkCodec.write(file(page), strokes)
    }
}

/**
 * A page as read from disk. [damaged]: the file was partly broken, [ink] holds
 * what survived. [unreadable]: part of the page could not be read at all, so
 * the page must stay locked; saving [ink] would lose the rest.
 */
internal class LoadedPage(val ink: PageInk, val damaged: Boolean, val unreadable: Boolean)
