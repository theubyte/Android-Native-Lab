package de.millione.schulranzen.books

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import de.millione.schulranzen.core.books.BookTitle
import de.millione.schulranzen.core.books.BookUnits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

/** A book on the shelf, as the library shows it. */
data class BookInfo(
    val id: String,
    val title: String,
    val pageCount: Int,
    /** Subject of the app's library, or null. */
    val subjectId: Long?,
    /** 0-based page the reader was on last. */
    val lastPage: Int,
    val addedAt: Long,
    /** Last time the book was opened, 0 = never. */
    val openedAt: Long,
    /** Small picture of the first page (PNG), null while it is missing. */
    val cover: File?,
) {
    /** Reading progress 0..1 for the cover. */
    val progress: Float get() = if (pageCount <= 1) 0f else lastPage / (pageCount - 1f)
}

/** A sticky note on a book page, in unrotated page units. */
@Serializable
data class BookNote(val id: Long, val page: Int, val x: Float, val y: Float, val text: String, val created: Long)

/** A marked ("favourite") area of a page, in unrotated page units. */
@Serializable
data class BookArea(
    val id: Long,
    val page: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String,
    val created: Long,
)

/** Everything a reader adds to a book besides ink. */
@Serializable
data class BookMarks(
    /** Page index -> clockwise quarter turns in degrees. */
    val rotations: Map<Int, Int> = emptyMap(),
    val bookmarks: Set<Int> = emptySet(),
    val notes: List<BookNote> = emptyList(),
    val areas: List<BookArea> = emptyList(),
) {
    fun rotation(page: Int) = rotations[page] ?: 0
}

@Serializable
internal data class BookRecord(
    val id: String,
    val title: String,
    val pageCount: Int,
    val subjectId: Long? = null,
    val lastPage: Int = 0,
    val addedAt: Long,
    val openedAt: Long = 0,
    val sourceName: String = "",
)

/** PDF page sizes in points, stored once at import (reading 600 pages takes a moment). */
@Serializable
internal data class PageSizes(val widths: List<Float>, val heights: List<Float>)

/**
 * The bookshelf: imported PDFs and what belongs to them.
 *
 * Layout under `filesDir/books/`:
 * - `shelf.json` – the list of books;
 * - `<id>.pdf` – the copied PDF (nothing depends on the file manager later);
 * - `<id>/cover.png`, `<id>/pages.json`, `<id>/marks.json`, `<id>/outline.json`;
 * - `<id>/ink/<page>.rnz` – ink per page in the app's ink format.
 *
 * Every write goes to a temp file first and is renamed into place. Any
 * number of instances share one state, so the library and the reader always
 * agree.
 */
class BookShelf(context: Context) {
    private val store = ShelfStore.of(context)

    /** All books, most recently used first. */
    val books: Flow<List<BookInfo>> = store.records
        .onStart { store.ensureLoaded() }
        .map { list -> list.sortedByDescending { maxOf(it.openedAt, it.addedAt) }.map(store::info) }

    suspend fun book(id: String): BookInfo? {
        store.ensureLoaded()
        return store.records.value.firstOrNull { it.id == id }?.let(store::info)
    }

    /**
     * Copies the PDF behind [uri] into the app, reads title and page sizes
     * and draws the cover. Throws [IOException] with a German message when
     * the file is not a readable PDF.
     */
    suspend fun import(uri: Uri): BookInfo = store.import(uri)

    suspend fun rename(id: String, title: String) =
        store.update(id) { it.copy(title = title.trim().ifEmpty { it.title }) }

    suspend fun setSubject(id: String, subjectId: Long?) = store.update(id) { it.copy(subjectId = subjectId) }

    /** Removes the book with its ink, notes and marks. */
    suspend fun delete(id: String) = store.delete(id)

    internal val internal: ShelfStore get() = store
}

/** Writes [text] to [file] through a temp file and a rename. */
internal fun writeAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.delete()
        if (!tmp.renameTo(file)) throw IOException("Datei konnte nicht gespeichert werden: ${file.name}")
    }
}

internal class ShelfStore private constructor(private val context: Context) {
    val root = File(context.filesDir, "books").also { it.mkdirs() }
    val imageDir = File(context.filesDir, "images").also { it.mkdirs() }
    private val shelfFile = File(root, "shelf.json")
    private val mutex = Mutex()
    @Volatile private var loaded = false
    val records = MutableStateFlow<List<BookRecord>>(emptyList())

    /** Saves outlive the screen that started them. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun pdf(id: String) = File(root, "$id.pdf")
    fun dir(id: String) = File(root, id)
    fun inkDir(id: String) = File(dir(id), "ink")
    fun cover(id: String) = File(dir(id), "cover.png")
    fun outlineFile(id: String) = File(dir(id), "outline.json")
    private fun marksFile(id: String) = File(dir(id), "marks.json")
    private fun sizesFile(id: String) = File(dir(id), "pages.json")

    fun info(r: BookRecord) = BookInfo(
        r.id, r.title, r.pageCount, r.subjectId, r.lastPage.coerceIn(0, (r.pageCount - 1).coerceAtLeast(0)),
        r.addedAt, r.openedAt, cover(r.id).takeIf { it.isFile },
    )

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val list = withContext(Dispatchers.IO) {
                runCatching { json.decodeFromString<List<BookRecord>>(shelfFile.readText()) }.getOrDefault(emptyList())
                    .filter { pdf(it.id).isFile }
            }
            records.value = list
            loaded = true
        }
    }

    private suspend fun mutate(change: (List<BookRecord>) -> List<BookRecord>) {
        ensureLoaded()
        mutex.withLock {
            val next = change(records.value)
            withContext(Dispatchers.IO) { writeAtomically(shelfFile, json.encodeToString(next)) }
            records.value = next
        }
    }

    suspend fun update(id: String, change: (BookRecord) -> BookRecord) =
        mutate { list -> list.map { if (it.id == id) change(it) else it } }

    suspend fun delete(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
        withContext(Dispatchers.IO) {
            pdf(id).delete()
            dir(id).deleteRecursively()
        }
    }

    suspend fun import(uri: Uri): BookInfo {
        ensureLoaded()
        val id = UUID.randomUUID().toString()
        val record = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
            val target = pdf(id)
            val tmp = File(root, "$id.pdf.tmp")
            try {
                val input = resolver.openInputStream(uri) ?: throw IOException("Die Datei lässt sich nicht öffnen.")
                input.use { inp -> tmp.outputStream().use { inp.copyTo(it, 256 * 1024) } }
                if (!tmp.renameTo(target)) throw IOException("Das Buch konnte nicht gespeichert werden.")
                val sizes = readSizesAndCover(id, target)
                val meta = metaTitle(target)
                BookRecord(id, BookTitle.choose(meta, name), sizes.widths.size, addedAt = System.currentTimeMillis(), sourceName = name.orEmpty())
            } catch (e: Throwable) {
                tmp.delete(); target.delete(); dir(id).deleteRecursively()
                throw when (e) {
                    is SecurityException -> IOException("Das PDF ist mit einem Passwort geschützt.", e)
                    is IOException -> if (e.message?.startsWith("Die") == true || e.message?.startsWith("Das") == true) e
                                      else IOException("Das ist keine lesbare PDF-Datei.", e)
                    else -> IOException("Das ist keine lesbare PDF-Datei.", e)
                }
            }
        }
        mutate { it + record }
        return info(record)
    }

    /** Page sizes for an open book; recreated if the cache file is missing. */
    fun pageSizes(id: String, renderer: PdfRenderer): PageSizes {
        runCatching { json.decodeFromString<PageSizes>(sizesFile(id).readText()) }.getOrNull()
            ?.takeIf { it.widths.size == renderer.pageCount }?.let { return it }
        return measure(renderer).also { runCatching { writeAtomically(sizesFile(id), json.encodeToString(it)) } }
    }

    private fun measure(renderer: PdfRenderer): PageSizes {
        val w = ArrayList<Float>(renderer.pageCount); val h = ArrayList<Float>(renderer.pageCount)
        for (i in 0 until renderer.pageCount) renderer.openPage(i).use { w += it.width.toFloat(); h += it.height.toFloat() }
        return PageSizes(w, h)
    }

    private fun readSizesAndCover(id: String, file: File): PageSizes {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) throw IOException("Das PDF hat keine Seiten.")
                val sizes = measure(renderer)
                writeAtomically(sizesFile(id), json.encodeToString(sizes))
                renderer.openPage(0).use { page -> writeCover(id, page) }
                return sizes
            }
        }
    }

    private fun writeCover(id: String, page: PdfRenderer.Page) {
        val w = COVER_WIDTH
        val h = (w * BookUnits.height(page.width.toFloat(), page.height.toFloat()) / BookUnits.WIDTH).roundToInt().coerceIn(1, w * 3)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, Matrix().apply { setScale(w / page.width.toFloat(), h / page.height.toFloat()) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val out = cover(id)
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
    }

    /** The title stored in the PDF; any failure just means "use the file name". */
    private fun metaTitle(file: File): String? = runCatching {
        PDFBoxResourceLoader.init(context)
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { doc ->
            doc.documentInformation?.title
        }
    }.getOrNull()

    fun loadMarks(id: String): BookMarks =
        runCatching { json.decodeFromString<BookMarks>(marksFile(id).readText()) }.getOrDefault(BookMarks())

    fun saveMarks(id: String, marks: BookMarks) = writeAtomically(marksFile(id), json.encodeToString(marks))

    companion object {
        const val COVER_WIDTH = 360
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        @Volatile private var instance: ShelfStore? = null
        fun of(context: Context): ShelfStore =
            instance ?: synchronized(this) { instance ?: ShelfStore(context.applicationContext).also { instance = it } }
    }
}
