package de.millione.schulranzen.books

import android.graphics.RectF
import de.millione.schulranzen.core.books.AreaText
import java.io.File

/**
 * A place in a book, e.g. marked for an exam. [page] is 0-based; [rect] is
 * a marked area in unrotated page units (the page is 1000 units wide, see
 * `BookUnits`), or null for the whole page. Hand [page] and [rect] back to
 * `BookScreen`/`BookPane` as `startPage`/`highlight` to jump there. [text] is
 * the text read from the marked area, when it has any.
 */
data class BookLink(val bookId: String, val page: Int, val rect: RectF?, val label: String, val text: String? = null) {
    companion object {
        /**
         * "Fachbuch Heizung, S. 47", "Fachbuch Heizung, S. 47 (Bereich)", or
         * with the start of the area's [text]: "Fachbuch Heizung, S. 47: Ein MAG nimmt …".
         */
        fun label(title: String, page: Int, area: Boolean, text: String? = null): String {
            val place = "${title.ifBlank { "Buch" }}, S. ${page + 1}"
            return when {
                !text.isNullOrBlank() -> "$place: ${AreaText.quote(text)}"
                area -> "$place (Bereich)"
                else -> place
            }
        }
    }
}

/** A marked place of a book handed over to the learning side, see `onLearn` of [BookScreen]. */
sealed interface BookLearn {
    val link: BookLink

    /**
     * Picture card: [image] is a PNG of the marked area (PDF, ink and text) in
     * the cache; fits `CardFromClipSheet(image, source, …)`, which copies it.
     */
    data class ImageCard(val image: File, override val link: BookLink) : BookLearn

    /** Cards from the text of the marked area; fits `SuggestCardsSheet(text, …)`. */
    data class TextCards(val text: String, override val link: BookLink) : BookLearn
}
