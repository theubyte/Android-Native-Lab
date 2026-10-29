package de.millione.schulranzen.ink

/**
 * Undo/redo over page edits. One user action (a stroke, one eraser gesture)
 * is one step, even when it touched several pages.
 */
class InkHistory(private val limit: Int = 200) {
    private val undo = ArrayDeque<List<PageEdit>>()
    private val redo = ArrayDeque<List<PageEdit>>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun push(step: List<PageEdit>) {
        if (step.isEmpty()) return
        undo.addLast(step)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    /** Reverts the last step on [pages] (by page id); returns the page ids touched. */
    fun undo(pages: (String) -> PageInk?): Set<String> {
        val step = undo.removeLastOrNull() ?: return emptySet()
        for (edit in step.asReversed()) pages(edit.page)?.revert(edit)
        redo.addLast(step)
        return step.mapTo(HashSet()) { it.page }
    }

    fun redo(pages: (String) -> PageInk?): Set<String> {
        val step = redo.removeLastOrNull() ?: return emptySet()
        for (edit in step) pages(edit.page)?.apply(edit)
        undo.addLast(step)
        return step.mapTo(HashSet()) { it.page }
    }

    fun clear() { undo.clear(); redo.clear() }
}
