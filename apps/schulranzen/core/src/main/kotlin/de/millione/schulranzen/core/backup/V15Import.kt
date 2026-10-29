package de.millione.schulranzen.core.backup

import java.io.InputStream

/**
 * Where an import goes. Implemented by the app (Room, ink files, the book
 * and study modules); [V15Import.run] calls it in this order:
 * subjects → pictures → notebooks → books → study data.
 *
 * Every call may suspend and runs on the caller's dispatcher; do the
 * actual IO in `withContext(Dispatchers.IO)`. Throwing aborts the import
 * (what was stored so far stays).
 */
interface ImportSink {
    /**
     * Returns the id of the subject called [name]: an existing one when the
     * name already exists (case-insensitive, the new app seeds the same
     * defaults as V15), otherwise a new one with [colorIndex] (SubjectPalette).
     */
    suspend fun subject(name: String, colorIndex: Int): Long

    /**
     * Stores one picture (JPEG/PNG bytes from [data], not closed by the sink)
     * in the app's image folder and returns its stored file name, as used
     * by ImageItem.file. [suggestedName] is unique within this import.
     */
    suspend fun image(suggestedName: String, data: InputStream): String

    /**
     * Creates a notebook with all its pages. [images] maps
     * [ImportedImage.entry] to the stored file name from [image]; turn the
     * pages into ink with the backup module's `V15Ink.toPageContent`.
     * Returns the new notebook id.
     */
    suspend fun notebook(notebook: ImportedNotebook, subjectId: Long?, images: Map<String, String>): String

    /**
     * Creates a book. [pdf] is the PDF (null when the backup has none);
     * the sink copies it and does not close it. Returns the new book id, or
     * null when books are not supported yet (the import continues).
     */
    suspend fun book(book: ImportedBook, subjectId: Long?, pdf: InputStream?): String?

    /**
     * Exams, cards and marks. [ids] maps V15 item ids (notebooks and books,
     * used by [ImportedExam.sourceIds] and [ImportedMark.itemId]) to the
     * new ids; [subjects] maps subject names (lower case) to ids.
     */
    suspend fun study(study: ImportedStudy, ids: Map<String, String>, subjects: Map<String, Long>)
}

data class ImportProgress(val label: String, val done: Int, val total: Int) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

data class ImportReport(val subjects: Int, val notebooks: Int, val pages: Int, val books: Int, val images: Int, val cards: Int)

object V15Import {
    /**
     * Feeds [library] into [sink]. Trashed notebooks and books are skipped
     * unless [includeTrashed]. Does not close [library].
     */
    suspend fun run(
        library: ImportedLibrary,
        sink: ImportSink,
        includeTrashed: Boolean = false,
        progress: (ImportProgress) -> Unit = {},
    ): ImportReport {
        val notebooks = library.notebooks.filter { includeTrashed || !it.trashed }
        val books = library.books.filter { includeTrashed || !it.trashed }
        val total = library.subjects.size + notebooks.size + books.size + 1
        var done = 0
        fun step(label: String) = progress(ImportProgress(label, done, total))

        val subjectIds = LinkedHashMap<String, Long>()
        for (s in library.subjects) {
            step("Fächer")
            subjectIds[s.name.lowercase()] = sink.subject(s.name, s.colorIndex)
            done++
        }
        suspend fun subjectFor(name: String): Long? {
            val key = name.trim().lowercase()
            if (key.isEmpty()) return null
            return subjectIds[key] ?: sink.subject(name.trim(), 7).also { subjectIds[key] = it }
        }

        val ids = LinkedHashMap<String, String>()
        val stored = HashMap<String, String>()
        val usedNames = HashSet<String>()
        var pages = 0
        for (nb in notebooks) {
            step("Heft „${nb.title}“")
            for (entry in nb.imageEntries) {
                if (entry in stored || !library.files.exists(entry)) continue
                val name = uniqueName(entry, usedNames)
                stored[entry] = library.files.open(entry).use { sink.image(name, it) }
            }
            val images = nb.imageEntries.mapNotNull { e -> stored[e]?.let { e to it } }.toMap()
            ids[nb.oldId] = sink.notebook(nb, subjectFor(nb.subjectName), images)
            pages += nb.pages.size
            done++
        }

        var bookCount = 0
        for (b in books) {
            step("Buch „${b.title}“")
            val id = if (b.pdfEntry != null && library.files.exists(b.pdfEntry)) {
                library.files.open(b.pdfEntry).use { sink.book(b, subjectFor(b.subjectName), it) }
            } else sink.book(b, subjectFor(b.subjectName), null)
            if (id != null) { ids[b.oldId] = id; bookCount++ }
            done++
        }

        step("Lerndaten")
        if (!library.study.isEmpty) {
            for (e in library.study.exams) subjectFor(e.subjectName)
            sink.study(library.study, ids, subjectIds)
        }
        done++
        progress(ImportProgress("Fertig", total, total))
        return ImportReport(subjectIds.size, ids.size - bookCount, pages, bookCount, stored.size, library.study.cards.size)
    }

    /** "files/book-clips/clip-1.png" → "v15-clip-1.png", unique within the run. */
    internal fun uniqueName(entry: String, used: MutableSet<String>): String {
        val base = entry.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "bild" }
        var name = "v15-$base"
        var i = 2
        while (!used.add(name)) {
            val dot = base.lastIndexOf('.')
            name = if (dot > 0) "v15-${base.substring(0, dot)}-$i${base.substring(dot)}" else "v15-$base-$i"
            i++
        }
        return name
    }
}
