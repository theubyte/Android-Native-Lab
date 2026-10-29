package de.millione.schulranzen.books

import android.graphics.RectF

/**
 * A place in a book, e.g. marked for an exam. [page] is 0-based; [rect] is
 * a marked area in unrotated page units (the page is 1000 units wide, see
 * `BookUnits`), or null for the whole page. Hand [page] and [rect] back to
 * `BookScreen`/`BookPane` as `startPage`/`highlight` to jump there.
 */
data class BookLink(val bookId: String, val page: Int, val rect: RectF?, val label: String) {
    companion object {
        /** "Fachbuch Heizung, S. 47" or "Fachbuch Heizung, S. 47 (Bereich)". */
        fun label(title: String, page: Int, area: Boolean): String =
            "${title.ifBlank { "Buch" }}, S. ${page + 1}" + if (area) " (Bereich)" else ""
    }
}
