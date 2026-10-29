package de.millione.schulranzen.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import de.millione.schulranzen.ink.CanvasPage
import de.millione.schulranzen.ink.InkCodec
import de.millione.schulranzen.ink.ItemCodec
import de.millione.schulranzen.ink.ItemPainter
import de.millione.schulranzen.ink.PageInk
import de.millione.schulranzen.ink.PageItem
import de.millione.schulranzen.ink.PageStroke
import de.millione.schulranzen.ink.PaperStyle
import de.millione.schulranzen.ink.PdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Library and notebook storage: Room for the structure, per page one file
 * for the ink and one for objects (text, pictures); pictures in their own
 * folder.
 */
class Library(private val context: Context) {
    private val dao = RanzenDatabase.get(context).library()
    private val inkDir = File(context.filesDir, "ink")
    val imageDir = File(context.filesDir, "images").also { it.mkdirs(); ItemPainter.imageDir = it }

    val subjects: Flow<List<Subject>> = dao.subjects()
    val notebooks: Flow<List<Notebook>> = dao.notebooks()
    val trashed: Flow<List<Notebook>> = dao.trashed()

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

    suspend fun restore(n: Notebook) = dao.updateNotebook(n.copy(trashedAt = null, updatedAt = System.currentTimeMillis()))

    /** Removes a notebook for good: its pages, ink and object files. Pictures stay (they may be shared). */
    suspend fun deleteForever(n: Notebook) = withContext(Dispatchers.IO) {
        for (p in dao.pages(n.id)) { inkFile(p.id).delete(); itemFile(p.id).delete() }
        dao.deleteNotebook(n.id)
    }

    /** Notebooks in the bin longer than [days] are deleted for good. */
    suspend fun emptyOldTrash(days: Int = 30) {
        val limit = System.currentTimeMillis() - days * 86_400_000L
        dao.trashed().first().filter { (it.trashedAt ?: 0) < limit }.forEach { deleteForever(it) }
    }

    /** All pages with ink and objects, read off the main thread; pictures are decoded ahead. */
    suspend fun loadPages(notebookId: String): List<CanvasPage> = withContext(Dispatchers.IO) {
        dao.pages(notebookId).map { p ->
            val strokes = runCatching { InkCodec.read(inkFile(p.id)) }.getOrDefault(emptyList())
            val items = runCatching { ItemCodec.read(itemFile(p.id)) }.getOrDefault(emptyList())
            items.filterIsInstance<de.millione.schulranzen.ink.ImageItem>().forEach { ItemPainter.image(it.file) }
            p.background?.let { ItemPainter.image(it) }
            CanvasPage(p.id, PaperStyle.of(p.paper), PageInk(strokes, items), p.bookmarked, p.locked, p.background)
        }
    }

    suspend fun savePage(notebookId: String, pageId: String, strokes: List<PageStroke>, items: List<PageItem>) = withContext(Dispatchers.IO) {
        InkCodec.write(inkFile(pageId), strokes)
        ItemCodec.write(itemFile(pageId), items)
        dao.touch(notebookId, System.currentTimeMillis())
    }

    suspend fun addPage(notebookId: String, position: Int, paper: PaperStyle): CanvasPage {
        val page = Page(UUID.randomUUID().toString(), notebookId, position, paper.name)
        dao.insertPageAt(page)
        dao.touch(notebookId, System.currentTimeMillis())
        return CanvasPage(page.id, paper, PageInk())
    }

    /**
     * Worksheets as new pages from [position] on, one page per picture
     * file (already in the image folder). Returns the pages in order.
     */
    suspend fun addWorksheetPages(notebookId: String, position: Int, files: List<String>): List<CanvasPage> =
        files.mapIndexed { i, file ->
            val page = Page(UUID.randomUUID().toString(), notebookId, position + i, PaperStyle.BLANK.name, background = file)
            dao.insertPageAt(page)
            CanvasPage(page.id, PaperStyle.BLANK, PageInk(), background = file)
        }.also { dao.touch(notebookId, System.currentTimeMillis()) }

    /**
     * Renders every page of a PDF (a worksheet from the teacher) at print
     * quality (A4 at 200 dpi) into the image folder. [progress] gets
     * (done, total).
     */
    suspend fun importPdfPages(uri: Uri, progress: (Int, Int) -> Unit = { _, _ -> }): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { pdf ->
                for (i in 0 until pdf.pageCount) {
                    pdf.openPage(i).use { page ->
                        val scale = 1654f / max(page.width, 1)      // 8.27 in × 200 dpi
                        val w = (page.width * scale).toInt(); val h = (page.height * scale).toInt()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val name = UUID.randomUUID().toString() + ".jpg"
                        File(imageDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                        bmp.recycle()
                        out += name
                    }
                    progress(i + 1, pdf.pageCount)
                }
            }
        } ?: error("PDF konnte nicht geöffnet werden")
        out
    }

    /** A new page right after [source] with a copy of its content. */
    suspend fun duplicatePage(notebookId: String, position: Int, source: CanvasPage, idFor: () -> Long): CanvasPage {
        val copy = source.background?.let { addWorksheetPages(notebookId, position, listOf(it)).first() }
            ?: addPage(notebookId, position, source.paper)
        val m = android.graphics.Matrix()
        val ink = PageInk(source.ink.strokes.map { it.transformed(m, idFor()) }, source.ink.items.map { it.transformed(m, idFor()) })
        savePage(notebookId, copy.id, ink.strokes, ink.items)
        return CanvasPage(copy.id, source.paper, ink, background = source.background)
    }

    suspend fun removePage(notebookId: String, pageId: String, remaining: List<String>) = withContext(Dispatchers.IO) {
        dao.removePage(notebookId, pageId, remaining)
        inkFile(pageId).delete(); itemFile(pageId).delete()
        dao.touch(notebookId, System.currentTimeMillis())
    }

    suspend fun reorder(notebookId: String, ids: List<String>) = dao.reorder(notebookId, ids)

    suspend fun setPaper(pageId: String, paper: PaperStyle) = dao.setPaper(pageId, paper.name)
    suspend fun setBookmarked(pageId: String, on: Boolean) = dao.setBookmarked(pageId, on)
    suspend fun setLocked(pageId: String, on: Boolean) = dao.setLocked(pageId, on)

    suspend fun setLastPage(notebookId: String, page: Int) = dao.setLastPage(notebookId, page)

    /**
     * Copies a picture into the app (at most 2400 px, JPEG) so the notebook
     * never depends on the gallery. Returns the file name and its aspect.
     */
    suspend fun importImage(uri: Uri): Pair<String, Float>? = withContext(Dispatchers.IO) {
        val src = runCatching {
            if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                val longSide = max(info.size.width, info.size.height)
                if (longSide > 2400) d.setTargetSampleSize((longSide + 2399) / 2400)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            } else context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return@withContext null
        val name = UUID.randomUUID().toString() + ".jpg"
        File(imageDir, name).outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        name to (src.height.toFloat() / src.width)
    }

    /**
     * Puts a picture (already in the image folder, e.g. a book clipping) on
     * the page the notebook was last open at, centred, 60 % page width.
     */
    suspend fun addImageToNotebook(notebookId: String, file: String, id: Long) = withContext(Dispatchers.IO) {
        val nb = dao.notebook(notebookId) ?: return@withContext
        val pages = dao.pages(notebookId)
        val page = pages.getOrNull(nb.lastPage) ?: pages.lastOrNull() ?: return@withContext
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(File(imageDir, file).path, opts)
        val aspect = if (opts.outWidth > 0) opts.outHeight.toFloat() / opts.outWidth else 0.75f
        val w = de.millione.schulranzen.ink.PageGeometry.WIDTH * 0.6f
        val h = w * aspect
        val x = (de.millione.schulranzen.ink.PageGeometry.WIDTH - w) / 2
        val y = ((de.millione.schulranzen.ink.PageGeometry.HEIGHT - h) / 2).coerceAtLeast(40f)
        val items = runCatching { ItemCodec.read(itemFile(page.id)) }.getOrDefault(emptyList())
        ItemCodec.write(itemFile(page.id), items + de.millione.schulranzen.ink.ImageItem(id, x, y, w, h, file))
        dao.touch(notebookId, System.currentTimeMillis())
    }

    /** Writes the notebook as PDF to [uri] (from the system save dialog). */
    suspend fun exportPdf(pages: List<CanvasPage>, uri: Uri, ruling: Boolean) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { PdfExporter.export(pages, it, ruling) }
            ?: error("Datei konnte nicht geöffnet werden")
    }

    private fun inkFile(pageId: String) = File(inkDir, "$pageId.rnz")
    private fun itemFile(pageId: String) = File(inkDir, "$pageId.items.json")
}
