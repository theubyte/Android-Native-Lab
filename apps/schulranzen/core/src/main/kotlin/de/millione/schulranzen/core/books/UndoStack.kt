package de.millione.schulranzen.core.books

/**
 * Undo and redo over any kind of step. A new step clears what could be
 * redone; the oldest steps fall off beyond [limit].
 */
class UndoStack<T>(private val limit: Int = 200) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun push(step: T) {
        undo.addLast(step)
        while (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    /** The step to revert now, or null. It moves to the redo side. */
    fun undo(): T? = undo.removeLastOrNull()?.also { redo.addLast(it) }

    /** The step to apply again, or null. It moves back to the undo side. */
    fun redo(): T? = redo.removeLastOrNull()?.also { undo.addLast(it) }

    fun clear() { undo.clear(); redo.clear() }
}
