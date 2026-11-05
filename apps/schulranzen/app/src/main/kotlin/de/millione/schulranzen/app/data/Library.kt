package de.millione.schulranzen.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import de.millione.schulranzen.core.notebook.ImageRefs
import de.millione.schulranzen.core.notebook.NotebookNames
import de.millione.schulranzen.core.notebook.PagePlacement
import de.millione.schulranzen.core.notebook.SafeFile
import de.millione.schulranzen.core.notebook.UserError
import de.millione.schulranzen.ink.CanvasPage
import de.millione.schulranzen.ink.ImageItem
import de.millione.schulranzen.ink.InkCodec
import de.millione.schulranzen.ink.ItemCodec
import de.millione.schulranzen.ink.ItemPainter
import de.millione.schulranzen.ink.PageGeometry
import de.millione.schulranzen.ink.PageInk
import de.millione.schulranzen.ink.PageItem
import de.millione.schulranzen.ink.PageStroke
import de.millione.schulranzen.ink.PaperStyle
import de.millione.schulranzen.ink.PdfExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Library and notebook storage: Room for the structure, per page one file
 * for the ink and one for objects (text, pictures); pictures in their own
 * folder.
 *
 * All page files go through [files]: it hands out in call order, so a later
 * save can never be overtaken by an earlier one, and reading waits for the
 * saves already under way.
 */
class Library(private val context: Context) {
    private val dao = RanzenDatabase.get(context).library()
    private val inkDir = File(context.filesDir, "ink")
    val imageDir = File(context.filesDir, "images").also { it.mkdirs(); ItemPainter.imageDir = it }
    private val files = Mutex()

    val subjects: Flow<List<Subject>> = dao.subjects()
    val notebooks: Flow<List<Notebook>> = dao.notebooks()
    val trashed: Flow<List<Notebook>> = dao.trashed()

    /** A notebook is gone for good (search index and the like clean up). */
    @Volatile var onNotebookGone: (suspend (String) -> Unit)? = null

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

    suspend fun updateSubject(s: Subject, name: String, colorIndex: Int) =
        dao.updateSubject(s.copy(name = name.trim().ifEmpty { s.name }, colorIndex = colorIndex))

    /** The subject goes; its notebooks stay, without a subject. */
    suspend fun deleteSubject(s: Subject) = dao.deleteSubject(s.id)

    suspend fun createNotebook(title: String, subjectId: Long?, paper: PaperStyle): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.createNotebook(
            Notebook(id, title.trim().ifEmpty { "Neues Heft" }, subjectId, paper.name, now, now),
            Page(UUID.randomUUID().toString(), id, 0, paper.name),
        )
        return id
    }

    /**
     * A whole notebook from a PDF (e.g. a stack of worksheets): one page per
     * PDF page, titled after the file. Returns the new notebook's id.
     */
    suspend fun createNotebookFromPdf(uri: Uri, subjectId: Long?, progress: (Int, Int) -> Unit): String {
        val name = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        }?.substringBeforeLast('.')?.replace('_', ' ')?.trim().orEmpty()
        val files = importPdfPages(uri, progress)
        if (files.isEmpty()) throw UserError("Das PDF hat keine Seiten.")
        val id = createNotebook(name.ifEmpty { "Arbeitsblätter" }, subjectId, PaperStyle.BLANK)
        val blank = dao.pages(id)
        addWorksheetPages(id, 0, files)
        // The empty first page createNotebook made is not wanted here.
        blank.forEach { p -> removePage(id, p.id, dao.pages(id).map { it.id }.filter { it != p.id }) }
        return id
    }

    suspend fun notebook(id: String) = dao.notebook(id)

    /** Ids of every notebook, trashed ones included. */
    suspend fun allNotebookIds(): List<String> = dao.allNotebooks().map { it.id }

    /** Where page [pageId] is now, wherever it was moved; null if it is gone or in the trash. */
    suspend fun locatePage(pageId: String): Pair<String, Int>? {
        val page = dao.pageById(pageId) ?: return null
        val nb = dao.notebook(page.notebookId)?.takeIf { it.trashedAt == null } ?: return null
        val index = dao.pages(nb.id).indexOfFirst { it.id == pageId }
        return if (index >= 0) nb.id to index else null
    }

    /** 0-based position of page [pageId] in its notebook, or 0. */
    suspend fun pageIndex(notebookId: String, pageId: String): Int =
        dao.pages(notebookId).indexOfFirst { it.id == pageId }.coerceAtLeast(0)

    suspend fun rename(n: Notebook, title: String, subjectId: Long?) =
        dao.renameNotebook(n.id, title.trim().ifEmpty { n.title }, subjectId, System.currentTimeMillis())

    suspend fun trash(n: Notebook) = dao.setTrashed(n.id, System.currentTimeMillis())

    suspend fun restore(n: Notebook) {
        dao.setTrashed(n.id, null)
        dao.touch(n.id, System.currentTimeMillis())
    }

    /**
     * Removes a notebook for good: its pages, ink and object files, and the
     * pictures only it used. The row goes first: a crash leaves spare files,
     * never a notebook whose pages have lost their ink.
     */
    suspend fun deleteForever(n: Notebook) {
        files.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                val pages = dao.pages(n.id)
                val pictures = picturesOf(pages)
                dao.deleteNotebook(n.id)
                pages.forEach { deletePageFiles(it.id) }
                releasePictures(pictures)
            }
        }
        runCatching { onNotebookGone?.invoke(n.id) }
    }

    /** Notebooks in the bin longer than [days] are deleted for good. */
    suspend fun emptyOldTrash(days: Int = 30) {
        val limit = System.currentTimeMillis() - days * 86_400_000L
        dao.trashed().first().filter { (it.trashedAt ?: 0) < limit }.forEach { deleteForever(it) }
    }

    /**
     * A full copy under a free name. The files are copied byte by byte; the
     * pictures are shared (they are only removed when nobody uses them).
     * Returns the new id.
     */
    suspend fun duplicateNotebook(n: Notebook): String = files.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            val copies = copyPages(dao.pages(n.id), id, 0)
            val title = NotebookNames.copyTitle(n.title, dao.allNotebooks().map { it.title })
            dao.insertNotebookWithPages(
                n.copy(id = id, title = title, createdAt = now, updatedAt = now, lastPage = 0, pageCount = copies.size, trashedAt = null),
                copies,
            )
            id
        }
    }

    /**
     * Appends a copy of [source]'s pages to [target]; [source] goes to the
     * bin, so a wrong merge costs nothing for 30 days. Returns the new pages' ids.
     */
    suspend fun mergeNotebooks(source: Notebook, target: Notebook): List<String> = files.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val copies = copyPages(dao.pages(source.id), target.id, dao.pageCountOf(target.id))
            dao.appendPages(target.id, copies, now)
            dao.setTrashed(source.id, now)
            copies.map { it.id }
        }
    }

    /** New rows for [pages] with their files copied; a failed copy leaves nothing behind. */
    private fun copyPages(pages: List<Page>, notebookId: String, start: Int): List<Page> {
        val out = ArrayList<Page>(pages.size)
        try {
            pages.forEachIndexed { i, p ->
                val copy = p.copy(id = UUID.randomUUID().toString(), notebookId = notebookId, position = start + i)
                out += copy
                copyIfThere(inkFile(p.id), inkFile(copy.id))
                copyIfThere(itemFile(p.id), itemFile(copy.id))
            }
        } catch (t: Throwable) {
            out.forEach { deletePageFiles(it.id) }
            throw t
        }
        return out
    }

    private fun copyIfThere(from: File, to: File) {
        if (from.isFile) SafeFile.write(to) { out -> from.inputStream().use { it.copyTo(out) } }
    }

    /** A notebook's pages and how the reading went. */
    class LoadedPages(val pages: List<CanvasPage>, val damaged: List<Int>, val unreadable: List<Int>)

    /** All pages with ink and objects, read off the main thread. Pictures decode when a page is shown. */
    suspend fun loadPages(notebookId: String): List<CanvasPage> = loadPagesChecked(notebookId).pages

    /**
     * Like [loadPages], and tells which pages (0-based) had trouble. Damaged
     * files give what is still readable. A file that could not be read at
     * all shows as a locked empty page, so nothing overwrites it; a copy of
     * either kind stays beside the original.
     */
    suspend fun loadPagesChecked(notebookId: String): LoadedPages {
        files.withLock { } // everything saved so far is on the disk before we read
        return withContext(Dispatchers.IO) {
            val rows = dao.pages(notebookId)
            val damaged = Collections.synchronizedList(ArrayList<Int>())
            val unreadable = Collections.synchronizedList(ArrayList<Int>())
            // Building strokes is the slow part; a few at a time keeps memory and cores calm.
            val workers = Dispatchers.IO.limitedParallelism(4)
            val pages = coroutineScope {
                rows.mapIndexed { i, p -> async(workers) { loadPage(i, p, damaged, unreadable) } }.awaitAll()
            }
            LoadedPages(pages, damaged.sorted(), unreadable.sorted())
        }
    }

    private fun loadPage(index: Int, p: Page, damaged: MutableList<Int>, unreadable: MutableList<Int>): CanvasPage {
        var locked = p.locked
        var strokes: List<PageStroke> = emptyList()
        var items: List<PageItem> = emptyList()
        val ink = inkFile(p.id)
        val objects = itemFile(p.id)
        try {
            val read = InkCodec.readChecked(ink)
            strokes = read.strokes
            if (read.damaged) { SafeFile.keepCopy(ink); damaged += index }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            SafeFile.keepCopy(ink); unreadable += index; locked = true
        }
        try {
            items = ItemCodec.read(objects)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            SafeFile.keepCopy(objects); unreadable += index; locked = true
        }
        return CanvasPage(p.id, PaperStyle.of(p.paper), PageInk(strokes, items), p.bookmarked, locked, p.background)
    }

    /**
     * Writes a page. Objects first: when typed text replaces handwriting, a
     * crash between the two files leaves both instead of neither.
     */
    suspend fun savePage(notebookId: String, pageId: String, strokes: List<PageStroke>, items: List<PageItem>) = files.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            ItemCodec.write(itemFile(pageId), items)
            InkCodec.write(inkFile(pageId), strokes)
            dao.touch(notebookId, System.currentTimeMillis())
        }
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
     * (done, total). Fails as a whole: pictures of a half-read PDF are removed
     * again. Password-protected and broken PDFs throw from the renderer (see
     * [de.millione.schulranzen.core.notebook.UserMessages.ofPdf]).
     */
    suspend fun importPdfPages(uri: Uri, progress: (Int, Int) -> Unit = { _, _ -> }): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        try {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw UserError("Die PDF-Datei konnte nicht geöffnet werden.")
            fd.use {
                android.graphics.pdf.PdfRenderer(fd).use { pdf ->
                    for (i in 0 until pdf.pageCount) {
                        ensureActive()
                        pdf.openPage(i).use { page ->
                            // A4 width; a strip of a page must not ask for a bitmap of many metres.
                            val scale = min(1654f / max(page.width, 1), 3000f / max(page.height, 1))
                            val w = (page.width * scale).toInt().coerceAtLeast(1); val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            try {
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val name = UUID.randomUUID().toString() + ".jpg"
                                out += name
                                writeJpeg(bmp, File(imageDir, name), 88)
                            } finally {
                                bmp.recycle()
                            }
                        }
                        progress(i + 1, pdf.pageCount)
                    }
                }
            }
        } catch (t: Throwable) {
            out.forEach { File(imageDir, it).delete() }
            throw t
        }
        out
    }

    private fun writeJpeg(bmp: Bitmap, file: File, quality: Int) {
        SafeFile.write(file) { if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, it)) throw IOException("JPEG") }
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

    suspend fun removePage(notebookId: String, pageId: String, remaining: List<String>) = files.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            val row = dao.pages(notebookId).firstOrNull { it.id == pageId }
            val pictures = if (row != null) picturesOf(listOf(row)) else emptySet()
            dao.removePage(notebookId, pageId, remaining)
            deletePageFiles(pageId)
            releasePictures(pictures)
            dao.touch(notebookId, System.currentTimeMillis())
        }
    }

    /** Page [pageId] goes to the end of notebook [to]; its files stay where they are (named by page). */
    suspend fun movePage(pageId: String, from: String, to: String, remaining: List<String>) = files.withLock {
        dao.movePage(pageId, from, to, remaining, System.currentTimeMillis())
    }

    suspend fun reorder(notebookId: String, ids: List<String>) = files.withLock { dao.reorder(notebookId, ids) }

    suspend fun setPaper(pageId: String, paper: PaperStyle) = dao.setPaper(pageId, paper.name)
    suspend fun setBookmarked(pageId: String, on: Boolean) = dao.setBookmarked(pageId, on)
    suspend fun setLocked(pageId: String, on: Boolean) = dao.setLocked(pageId, on)

    /** Every page without a worksheet or lock, and every new page from now on. */
    suspend fun setPaperForAll(notebookId: String, paper: PaperStyle) {
        dao.setPaperAll(notebookId, paper.name)
        dao.setDefaultPaper(notebookId, paper.name)
    }

    suspend fun setLastPage(notebookId: String, page: Int) = dao.setLastPage(notebookId, page)

    /**
     * Copies a picture into the app (at most 2400 px, JPEG) so the notebook
     * never depends on the gallery. Returns the file name and its aspect;
     * null when the picture cannot be decoded. A full disk throws.
     */
    suspend fun importImage(uri: Uri): Pair<String, Float>? = withContext(Dispatchers.IO) {
        val src = runCatching {
            if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                val longSide = max(info.size.width, info.size.height)
                if (longSide > 2400) d.setTargetSampleSize((longSide + 2399) / 2400)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            } else context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return@withContext null
        // JPEG has no transparency: without white below, a PNG's clear parts turn black.
        val solid = if (src.hasAlpha()) Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        } else src
        val name = UUID.randomUUID().toString() + ".jpg"
        try {
            writeJpeg(solid, File(imageDir, name), 90)
            name to (src.height.toFloat() / src.width)
        } finally {
            if (solid !== src) solid.recycle()
            src.recycle()
        }
    }

    /**
     * Puts a picture (already in the image folder, e.g. a book clipping) on
     * the page the notebook was last open at, centred, 60 % page width.
     */
    suspend fun addImageToNotebook(notebookId: String, file: String, id: Long): Boolean = files.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            val nb = dao.notebook(notebookId) ?: return@withContext false
            val pages = dao.pages(notebookId)
            val last = pages.getOrNull(nb.lastPage) ?: pages.lastOrNull() ?: return@withContext false
            // A locked page stays as it is, and so does a page whose objects can't be read:
            // writing them back would lose them. The clipping gets a fresh page right after it.
            val existing = if (last.locked) null else runCatching { ItemCodec.read(itemFile(last.id)) }.getOrNull()
            val page = if (existing != null) last else
                Page(UUID.randomUUID().toString(), notebookId, last.position + 1, last.paper).also { dao.insertPageAt(it) }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(File(imageDir, file).path, opts)
            val aspect = if (opts.outWidth > 0) opts.outHeight.toFloat() / opts.outWidth else 0.75f
            val box = PagePlacement.imageBox(aspect, PageGeometry.WIDTH / 2, PageGeometry.HEIGHT / 2, PageGeometry.WIDTH, PageGeometry.HEIGHT)
            ItemCodec.write(itemFile(page.id), existing.orEmpty() + ImageItem(id, box[0], box[1], box[2], box[3], file))
            dao.touch(notebookId, System.currentTimeMillis())
            page !== last
        }
    }

    /**
     * Writes the notebook as PDF to [uri] (from the system save dialog).
     * The PDF is finished first, so a failure leaves no half file: the empty
     * document the dialog created is removed again.
     */
    suspend fun exportPdf(pages: List<CanvasPage>, uri: Uri, ruling: Boolean) = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "export-${System.nanoTime()}.pdf")
        try {
            tmp.outputStream().use { PdfExporter.export(pages, it, ruling) }
            val out = context.contentResolver.openOutputStream(uri) ?: throw UserError("Die Datei konnte nicht geöffnet werden.")
            out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw t
        } finally {
            tmp.delete()
        }
    }

    /**
     * The notebook as a PDF file to hand to another app. Lies in the one
     * folder the app shares through its FileProvider; replaces the last one.
     */
    suspend fun pdfForSharing(pages: List<CanvasPage>, title: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "camera/teilen")
        dir.deleteRecursively()
        val file = File(dir, NotebookNames.fileName(title, "pdf"))
        SafeFile.write(file) { PdfExporter.export(pages, it, true) }
        file
    }

    private fun picturesOf(pages: List<Page>): Set<String> {
        val out = HashSet<String>()
        for (p in pages) {
            p.background?.let { out += it }
            runCatching { ItemCodec.read(itemFile(p.id)) }.getOrNull()?.forEach { if (it is ImageItem) out += it.file }
        }
        return out
    }

    /**
     * Removes those of [pictures] nothing refers to any more: not another
     * page (also in the bin), not a page of a book (they share the folder).
     */
    private suspend fun releasePictures(pictures: Set<String>) {
        if (pictures.isEmpty()) return
        val backgrounds = dao.backgrounds().asSequence()
        val objectFiles = sequenceOf(inkDir, File(context.filesDir, "books")).flatMap { it.walkTopDown() }
            .filter { it.isFile && it.name.contains(".items.json") && !it.name.endsWith(".tmp") }
            .map { f -> runCatching { f.readText() }.getOrDefault(pictures.joinToString()) } // unreadable: keep them all
        for (name in ImageRefs.orphans(pictures, backgrounds + objectFiles)) {
            if (File(name).name != name) continue // never leave the image folder
            File(imageDir, name).delete()
            ItemPainter.forget(name)
        }
    }

    private fun deletePageFiles(pageId: String) {
        inkDir.listFiles { f -> f.name.startsWith(pageId) }?.forEach { it.delete() }
    }

    private fun inkFile(pageId: String) = File(inkDir, "$pageId.rnz")
    private fun itemFile(pageId: String) = File(inkDir, "$pageId.items.json")
}
