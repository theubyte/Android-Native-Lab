package de.millione.schulranzen.app.data

import android.content.Context
import de.millione.schulranzen.ink.CanvasPage
import de.millione.schulranzen.ink.InkCodec
import de.millione.schulranzen.ink.PageInk
import de.millione.schulranzen.ink.PageStroke
import de.millione.schulranzen.ink.PaperStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Library and notebook storage: Room for the structure, one file per page for the ink. */
class Library(context: Context) {
    private val dao = RanzenDatabase.get(context).library()
    private val inkDir = File(context.filesDir, "ink")

    val subjects: Flow<List<Subject>> = dao.subjects()
    val notebooks: Flow<List<Notebook>> = dao.notebooks()

    /** The subjects of the old app, so a fresh install starts organised. */
    suspend fun seed() {
        if (dao.subjectCount() > 0) return
        dao.insertSubjects(
            listOf("Allgemein", "Mathe", "Deutsch", "Englisch", "SHK", "Fachkunde")
                .mapIndexed { i, name -> Subject(name = name, colorIndex = i, position = i) },
        )
    }

    suspend fun addSubject(name: String, colorIndex: Int, position: Int) =
        dao.insertSubject(Subject(name = name.trim(), colorIndex = colorIndex, position = position))

    suspend fun createNotebook(title: String, subjectId: Long?, paper: PaperStyle): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.createNotebook(
            Notebook(id, title.trim().ifEmpty { "Neues Heft" }, subjectId, paper.name, now, now),
            Page(UUID.randomUUID().toString(), id, 0, paper.name),
        )
        return id
    }

    suspend fun notebook(id: String) = dao.notebook(id)

    suspend fun rename(n: Notebook, title: String, subjectId: Long?) =
        dao.updateNotebook(n.copy(title = title.trim().ifEmpty { n.title }, subjectId = subjectId, updatedAt = System.currentTimeMillis()))

    suspend fun trash(n: Notebook) = dao.updateNotebook(n.copy(trashedAt = System.currentTimeMillis()))

    /** All pages with their ink, read off the main thread. */
    suspend fun loadPages(notebookId: String): List<CanvasPage> = withContext(Dispatchers.IO) {
        dao.pages(notebookId).map { p ->
            val strokes = runCatching { InkCodec.read(pageFile(p.id)) }.getOrDefault(emptyList())
            CanvasPage(p.id, PaperStyle.of(p.paper), PageInk(strokes))
        }
    }

    suspend fun savePage(notebookId: String, pageId: String, strokes: List<PageStroke>) = withContext(Dispatchers.IO) {
        InkCodec.write(pageFile(pageId), strokes)
        dao.touch(notebookId, System.currentTimeMillis())
    }

    suspend fun addPage(notebookId: String, position: Int, paper: PaperStyle): CanvasPage {
        val page = Page(UUID.randomUUID().toString(), notebookId, position, paper.name)
        dao.insertPageAt(page)
        dao.touch(notebookId, System.currentTimeMillis())
        return CanvasPage(page.id, paper, PageInk())
    }

    suspend fun setPaper(pageId: String, paper: PaperStyle) = dao.setPaper(pageId, paper.name)

    suspend fun setLastPage(notebookId: String, page: Int) = dao.setLastPage(notebookId, page)

    private fun pageFile(pageId: String) = File(inkDir, "$pageId.rnz")
}
