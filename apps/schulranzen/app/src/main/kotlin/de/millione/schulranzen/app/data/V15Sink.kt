package de.millione.schulranzen.app.data

import android.content.Context
import de.millione.schulranzen.backup.V15Ink
import de.millione.schulranzen.core.backup.ImportSink
import de.millione.schulranzen.core.backup.ImportedBook
import de.millione.schulranzen.core.backup.ImportedNotebook
import de.millione.schulranzen.core.backup.ImportedStudy
import de.millione.schulranzen.ink.InkCodec
import de.millione.schulranzen.ink.ItemCodec
import de.millione.schulranzen.study.data.Exam
import de.millione.schulranzen.study.ExamKind
import de.millione.schulranzen.study.StudyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * Takes the old app's data (V15 backup) into this app: subjects, pictures,
 * notebooks with every page, exams and cards. Books follow once the books
 * module stores them ([books] hook).
 */
class V15Sink(
    private val context: Context,
    private val library: Library,
    private val nextId: () -> Long,
    /** Stores a book; null = not supported (the import goes on without it). */
    private val books: (suspend (ImportedBook, Long?, InputStream?) -> String?)? = null,
) : ImportSink {
    private val dao = RanzenDatabase.get(context).library()
    private val inkDir = File(context.filesDir, "ink").also { it.mkdirs() }

    override suspend fun subject(name: String, colorIndex: Int): Long {
        val existing = library.subjects.first().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        return existing?.id ?: library.addSubject(name, colorIndex, library.subjects.first().size)
    }

    override suspend fun image(suggestedName: String, data: InputStream): String = withContext(Dispatchers.IO) {
        val ext = suggestedName.substringAfterLast('.', "jpg").lowercase(Locale.ROOT).take(4)
        val name = UUID.randomUUID().toString() + "." + ext
        File(library.imageDir, name).outputStream().use { data.copyTo(it) }
        name
    }

    override suspend fun notebook(notebook: ImportedNotebook, subjectId: Long?, images: Map<String, String>): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val pages = notebook.pages.ifEmpty { null }
        dao.insertNotebook(
            Notebook(
                id, notebook.title.ifBlank { "Heft" }, subjectId, notebook.paper,
                createdAt = notebook.created, updatedAt = notebook.modified,
                pageCount = pages?.size ?: 1,
            ),
        )
        if (pages == null) {
            dao.insertPage(Page(UUID.randomUUID().toString(), id, 0, notebook.paper))
        } else pages.forEachIndexed { i, p ->
            val content = V15Ink.toPageContent(p, images, nextId)
            val pageId = UUID.randomUUID().toString()
            dao.insertPage(Page(pageId, id, i, content.paper.name, bookmarked = p.bookmarked, locked = p.locked))
            InkCodec.write(File(inkDir, "$pageId.rnz"), content.strokes)
            ItemCodec.write(File(inkDir, "$pageId.items.json"), content.items)
        }
        id
    }

    override suspend fun book(book: ImportedBook, subjectId: Long?, pdf: InputStream?): String? =
        books?.invoke(book, subjectId, pdf)

    override suspend fun study(study: ImportedStudy, ids: Map<String, String>, subjects: Map<String, Long>) {
        val repo = StudyRepository(context)
        val examDeck = HashMap<String, String>()
        for (e in study.exams) {
            val date = runCatching { LocalDate.parse(e.date) }.getOrNull() ?: continue
            val subjectId = subjects[e.subjectName.lowercase()]
            val colorIndex = library.subjects.first().firstOrNull { it.id == subjectId }?.colorIndex ?: 0
            repo.saveExam(
                Exam(
                    subject = e.subjectName.ifBlank { e.title }, colorIndex = colorIndex, subjectId = subjectId,
                    dateEpochDay = date.toEpochDay(), kind = ExamKind.SONSTIGE.name, topics = e.title,
                ),
            )
            examDeck[e.oldId] = e.subjectName.ifBlank { e.title }
        }
        // Cards keep their text; their schedule starts fresh (V15 used another algorithm).
        for (c in study.cards) {
            if (c.front.isBlank() && c.back.isBlank()) continue
            repo.addCard(examDeck[c.examId] ?: "Aus alter App", c.front, c.back)
        }
    }
}
