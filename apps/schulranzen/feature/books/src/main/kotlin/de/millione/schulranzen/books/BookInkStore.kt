package de.millione.schulranzen.books

import de.millione.schulranzen.ink.InkCodec
import de.millione.schulranzen.ink.PageInk
import de.millione.schulranzen.ink.PageStroke
import java.io.File

/**
 * Ink of one book, one file per page (`<page>.rnz`, the notebook's ink
 * format). Pages without ink have no file, so a 600-page book with notes
 * on ten pages has ten small files.
 */
internal class BookInkStore(private val dir: File) {
    private fun file(page: Int) = File(dir, "$page.rnz")

    /** Pages that have ink, without reading them. */
    fun pagesWithInk(): Set<Int> =
        dir.list()?.mapNotNullTo(HashSet()) { it.removeSuffix(".rnz").takeIf { n -> n != it }?.toIntOrNull() } ?: emptySet()

    fun load(page: Int): PageInk = PageInk(runCatching { InkCodec.read(file(page)) }.getOrDefault(emptyList()))

    fun save(page: Int, strokes: List<PageStroke>) {
        if (strokes.isEmpty()) file(page).delete() else InkCodec.write(file(page), strokes)
    }
}
