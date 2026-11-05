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
import de.millione.schulranzen.core.books.ReadingPosition
import de.millione.schulranzen.core.books.ReadingProgress
import de.millione.schulranzen.design.isPhone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
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
    /** 0-based page the reader was on last, on this device. */
    val lastPage: Int,
    val addedAt: Long,
    /** Last time the book was opened on this device (else as the backup says), 0 = never. */
    val openedAt: Long,
    /** Small picture of the first page (PNG), null while it is missing. */
    val cover: File?,
) {
    /** Reading progress 0..1 for the cover. */
    val progress: Float get() = ReadingProgress.fraction(lastPage, pageCount)

    /** Reading progress in percent. */
    val percent: Int get() = ReadingProgress.percent(lastPage, pageCount)
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
 * Where the reader stands is kept per device in `noBackupFilesDir`, so
 * it never travels with a backup: a phone and a tablet each keep their place.
 *
 * Every write goes to a temp file first and is renamed into place. Any
 * number of instances share one state, so the library and the reader always
 * agree. On a phone the shelf is for reading: books can be added, but not
 * renamed, moved or deleted ([canEdit]).
 */
class BookShelf(context: Context) {
    private val store = ShelfStore.of(context)
    private val appContext = context.applicationContext

    /** False on a phone; the calls that change a book then do nothing, so hosts should not offer them. */
    val canEdit: Boolean get() = !isPhone(appContext)

    /** All books, most recently used first. */
    val books: Flow<List<BookInfo>> = combine(store.records, store.positions) { list, _ -> list.map(store::info) }
        .onStart { store.ensureLoaded() }
        .map { list -> list.sortedByDescending { maxOf(it.openedAt, it.addedAt) } }

    /** The book read last on this device, for "Weiterlesen"; null when none was opened yet. */
    val lastRead: Flow<BookInfo?> = books.map { list -> list.filter { it.openedAt > 0 }.maxByOrNull { it.openedAt } }

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

    suspend fun rename(id: String, title: String) {
        if (canEdit) store.update(id) { it.copy(title = title.trim().ifEmpty { it.title }) }
    }

    suspend fun setSubject(id: String, subjectId: Long?) {
        if (canEdit) store.update(id) { it.copy(subjectId = subjectId) }
    }

    /**
     * Removes the book with its ink, notes, marks, text index and reading
     * position. Waits for background text work on it to stop first, or that
     * work would write its files back into the deleted folder.
     */
    suspend fun delete(id: String) {
        if (!canEdit) return
        BookIndexer.forget(id)
        store.delete(id)
    }

    internal val internal: ShelfStore get() = store
}

/** Writes [text] to [file] through a temp file and a rename. */
internal fun writeAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    // Own name per write: two writers of one file (contents from the reader and the indexer) must not mix.
    val tmp = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
    try {
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Datei konnte nicht gespeichert werden: ${file.name}")
        }
    } finally {
        tmp.delete()
    }
}

internal class ShelfStore private constructor(private val context: Context) {
    val root = File(context.filesDir, "books").also { it.mkdirs() }
    val imageDir = File(context.filesDir, "images").also { it.mkdirs() }
    private val shelfFile = File(root, "shelf.json")
    private val mutex = Mutex()
    @Volatile private var loaded = false
    val records = MutableStateFlow<List<BookRecord>>(emptyList())

    /** Reading positions of this device by book. Outside `filesDir`, so backups leave them alone. */
    val positions = MutableStateFlow<Map<String, ReadingPosition>>(emptyMap())
    private val positionsFile = File(context.noBackupFilesDir, "book-positions.json")
    private val positionsLock = Mutex()

    /** Saves outlive the screen that started them. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun pdf(id: String) = File(root, "$id.pdf")
    fun dir(id: String) = File(root, id)
    fun inkDir(id: String) = File(dir(id), "ink")
    fun cover(id: String) = File(dir(id), "cover.png")
    fun outlineFile(id: String) = File(dir(id), "outline.json")
    /** Searchable text of the book (words with boxes), see [BookIndexer]. */
    fun indexFile(id: String) = File(dir(id), "text.idx")
    val appContext: Context get() = context
    private fun marksFile(id: String) = File(dir(id), "marks.json")
    private fun sizesFile(id: String) = File(dir(id), "pages.json")

    fun info(r: BookRecord): BookInfo {
        val here = positions.value[r.id]
        return BookInfo(
            r.id, r.title, r.pageCount, r.subjectId,
            (here?.page ?: r.lastPage).coerceIn(0, (r.pageCount - 1).coerceAtLeast(0)),
            r.addedAt, here?.readAt ?: r.openedAt, cover(r.id).takeIf { it.isFile },
        )
    }

    /** Where to start reading on this device: its own place, else the page the backup carries. */
    fun startOf(r: BookRecord): ReadingPosition =
        (positions.value[r.id] ?: ReadingPosition(r.lastPage, 0f, r.openedAt)).within(r.pageCount)

    /** Remembers the place right away in memory; the file follows in the background. */
    fun savePosition(id: String, position: ReadingPosition) {
        positions.value = positions.value + (id to position)
        scope.launch { writePositions() }
    }

    private suspend fun writePositions() = positionsLock.withLock {
        // The newest state, not the state of this call: a late write can never bring back an old place.
        runCatching { writeAtomically(positionsFile, json.encodeToString(positions.value)) }
    }

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val list = withContext(Dispatchers.IO) {
                positions.value = runCatching { json.decodeFromString<Map<String, ReadingPosition>>(positionsFile.readText()) }.getOrDefault(emptyMap())
                // An import cut off by the app being killed leaves the copy behind, maybe hundreds of MB.
                root.listFiles { f -> f.name.endsWith(".pdf.tmp") }?.forEach { it.delete() }
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
        positions.value = positions.value - id
        withContext(Dispatchers.IO) { writePositions() }
    }

    /** False once the book is gone: late saves must not bring its folder back. */
    fun exists(id: String) = records.value.any { it.id == id }

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
                copyIn(uri, tmp)
                if (!tmp.renameTo(target)) throw IOException("Das Buch konnte nicht gespeichert werden.")
                val sizes = readSizesAndCover(id, target)
                val meta = metaTitle(target)
                BookRecord(id, BookTitle.choose(meta, name), sizes.widths.size, addedAt = System.currentTimeMillis(), sourceName = name.orEmpty())
            } catch (e: Throwable) {
                tmp.delete(); target.delete(); dir(id).deleteRecursively()
                throw when {
                    e is CopyFailed -> IOException(e.message, e)
                    e is SecurityException -> IOException("Das PDF ist mit einem Passwort geschützt. Bitte das Passwort entfernen und erneut importieren.", e)
                    e is OutOfMemoryError -> IOException("Für dieses PDF reicht der Arbeitsspeicher nicht.", e)
                    e is IOException && (e.message?.startsWith("Die") == true || e.message?.startsWith("Das") == true) -> e
                    else -> IOException("Das ist keine lesbare PDF-Datei.", e)
                }
            }
        }
        mutate { it + record }
        return info(record)
    }

    private class CopyFailed(message: String) : IOException(message)

    /** Copying fails for its own reasons: they say nothing about the PDF, and must not be reported as if. */
    private fun copyIn(uri: Uri, tmp: File) {
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw CopyFailed("Die Datei lässt sich nicht öffnen.")
            input.use { inp -> tmp.outputStream().use { inp.copyTo(it, 256 * 1024) } }
        } catch (e: CopyFailed) {
            throw e
        } catch (e: SecurityException) {
            throw CopyFailed("Kein Zugriff mehr auf die Datei. Bitte noch einmal auswählen.")
        } catch (e: IOException) {
            val full = root.usableSpace < 1024 * 1024 || e.message.orEmpty().contains("ENOSPC") || e.message.orEmpty().contains("No space")
            throw CopyFailed(if (full) "Nicht genug Speicherplatz für dieses Buch." else "Die Datei konnte nicht kopiert werden.")
        }
    }

    /** Page sizes for an open book; recreated if the cache file is missing. */
    fun pageSizes(id: String, renderer: PdfRenderer): PageSizes {
        runCatching { json.decodeFromString<PageSizes>(sizesFile(id).readText()) }.getOrNull()
            ?.takeIf { it.widths.size == renderer.pageCount }?.let { return it }
        return measure(renderer).also { runCatching { writeAtomically(sizesFile(id), json.encodeToString(it)) } }
    }

    /** Page sizes from the cache file, without opening the PDF; null when missing. */
    fun cachedPageSizes(id: String): PageSizes? =
        runCatching { json.decodeFromString<PageSizes>(sizesFile(id).readText()) }.getOrNull()

    /**
     * Page sizes in points. One broken page does not cost the whole book: it
     * takes the size of the page before and shows a message when read.
     */
    private fun measure(renderer: PdfRenderer): PageSizes {
        val w = ArrayList<Float>(renderer.pageCount); val h = ArrayList<Float>(renderer.pageCount)
        var good = 0
        for (i in 0 until renderer.pageCount) {
            val size = runCatching { renderer.openPage(i).use { it.width.toFloat() to it.height.toFloat() } }.getOrNull()
                ?.takeIf { it.first > 0f && it.second > 0f }
            if (size != null) good++
            w += size?.first ?: w.lastOrNull() ?: A4_WIDTH
            h += size?.second ?: h.lastOrNull() ?: A4_HEIGHT
        }
        if (good == 0 && w.isNotEmpty()) throw IOException("Das ist keine lesbare PDF-Datei.")
        return PageSizes(w, h)
    }

    private fun readSizesAndCover(id: String, file: File): PageSizes {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) throw IOException("Das PDF hat keine Seiten.")
                val sizes = measure(renderer)
                writeAtomically(sizesFile(id), json.encodeToString(sizes))
                // Without a cover the book is still a book.
                runCatching { renderer.openPage(0).use { page -> writeCover(id, page) } }
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
        private const val A4_WIDTH = 595f
        private const val A4_HEIGHT = 842f
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        @Volatile private var instance: ShelfStore? = null
        fun of(context: Context): ShelfStore =
            instance ?: synchronized(this) { instance ?: ShelfStore(context.applicationContext).also { instance = it } }
    }
}
