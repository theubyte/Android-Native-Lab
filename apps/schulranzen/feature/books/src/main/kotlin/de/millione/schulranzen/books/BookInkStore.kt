package de.millione.schulranzen.books

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

    fun load(page: Int): PageInk = PageInk(
        runCatching { InkCodec.read(file(page)) }.getOrDefault(emptyList()),
        runCatching { ItemCodec.read(itemsFile(page)) }.getOrDefault(emptyList()),
    )

    fun save(page: Int, strokes: List<PageStroke>, items: List<PageItem>) {
        dir.mkdirs()
        if (strokes.isEmpty()) file(page).delete() else InkCodec.write(file(page), strokes)
        ItemCodec.write(itemsFile(page), items)
    }
}
