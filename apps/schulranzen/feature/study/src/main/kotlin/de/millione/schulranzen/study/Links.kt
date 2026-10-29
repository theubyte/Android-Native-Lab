package de.millione.schulranzen.study

/** What a study link points to. */
enum class StudyLinkKind(val label: String) {
    NOTEBOOK_PAGE("Heft"),
    BOOK_PAGE("Buch");

    companion object {
        fun of(name: String?): StudyLinkKind = entries.firstOrNull { it.name == name } ?: NOTEBOOK_PAGE
    }
}

/** An area on a page in page fractions (0..1), independent of zoom and render size. */
data class LinkRect(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * A place the host app wants to remember: a notebook or book page,
 * optionally an area on it. [targetId] is the host's id of the notebook or
 * book (as a string, so any id scheme fits); [page] is 0-based; [label] is
 * what the learner reads in lists, e.g. "Fachkunde SHK · S. 112".
 */
data class StudyLinkDraft(
    val kind: StudyLinkKind,
    val targetId: String,
    val page: Int,
    val rect: LinkRect? = null,
    val label: String,
)
