package de.millione.schulranzen.core.books

import kotlinx.serialization.Serializable

/** Where a table of contents came from; decides how much the reader can trust it. */
@Serializable
enum class OutlineSource {
    /** Bookmarks embedded in the PDF: exact destinations. */
    EMBEDDED,
    /** A printed table of contents found in the text layer, mapped to PDF pages. */
    PRINTED_TOC,
    /** Chapter headings found on the pages themselves. */
    HEADINGS,
    /** Nothing usable (no text layer or no clear headings). */
    NONE,
}

/** One jump target. [page] is the 0-based PDF page index, [level] 0 = chapter. */
@Serializable
data class OutlineEntry(val title: String, val page: Int, val level: Int = 0)

/** The table of contents of a book, cached next to it. */
@Serializable
data class Outline(
    val entries: List<OutlineEntry> = emptyList(),
    val source: OutlineSource = OutlineSource.NONE,
    val pageCount: Int = 0,
    /** False when no page had a text layer (a scanned book). */
    val hasText: Boolean = false,
    /** Built from on-device text recognition (a scanned book). */
    val recognized: Boolean = false,
    val version: Int = VERSION,
) {
    private val via get() = if (recognized) " (Texterkennung)" else ""

    /** Short German line for the panel header. */
    fun summary(): String = when {
        source == OutlineSource.EMBEDDED -> "$pageCount Seiten · ${entries.size} Lesezeichen aus dem PDF"
        source == OutlineSource.PRINTED_TOC -> "$pageCount Seiten · Inhaltsverzeichnis erkannt" + via
        source == OutlineSource.HEADINGS -> "$pageCount Seiten · ${entries.size} Kapitel erkannt" + via
        recognized -> "$pageCount Seiten · Text erkannt, keine sicheren Kapitel"
        !hasText -> "$pageCount Seiten · keine Textschicht (gescannt)"
        else -> "$pageCount Seiten · keine sicheren Kapitel gefunden"
    }

    companion object {
        /** Bump when the heuristics change, so cached results are redone. */
        const val VERSION = 2
        /** At most this many entries; a list longer than this is no longer a table of contents. */
        const val MAX_ENTRIES = 450
    }
}
