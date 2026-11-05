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
    /** Stable id of a notebook page; see [de.millione.schulranzen.study.data.StudyLink.pageId]. */
    val pageId: String? = null,
)

/** Where a marked place is now. */
sealed interface LinkTarget {
    data class Found(val targetId: String, val page: Int) : LinkTarget
    /** The page or the whole notebook or book is gone. */
    data object Missing : LinkTarget
}

/**
 * Finds a marked place again before jumping to it. The study feature cannot look into
 * notebooks; the host may set [resolver] once at start. It should look up [StudyLink.pageId]
 * if there is one (over all notebooks, a page may have moved), else check that [StudyLink.page]
 * still exists. Left unset, jumps use the stored page as before.
 */
object StudyLinkTargets {
    @Volatile var resolver: (suspend (de.millione.schulranzen.study.data.StudyLink) -> LinkTarget)? = null
}

/**
 * Where exam questions get the text of a marked place from. The study feature cannot
 * read notebooks or books itself; the host may set this once at start. Left unset, the
 * "Text aus markiertem Stoff" button simply does not appear and pasting text still works.
 */
object StudyLinkText {
    @Volatile var provider: (suspend (de.millione.schulranzen.study.data.StudyLink) -> String?)? = null
}
