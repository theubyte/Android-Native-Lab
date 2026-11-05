package de.millione.schulranzen.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import de.millione.schulranzen.app.data.Library
import de.millione.schulranzen.app.data.RanzenDatabase
import de.millione.schulranzen.core.scan.ScanEnhance
import de.millione.schulranzen.core.scan.ScanMode
import de.millione.schulranzen.core.scan.ScanPlan
import de.millione.schulranzen.ink.PaperStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** One scanned sheet waiting for its notebook. */
@Serializable
data class ScanSheet(val id: String, val file: String, val rotation: Int = 0)

/** Where the sheets go: an existing notebook (after [after] pages, null = end) or a new one. */
sealed interface ScanTarget {
    data class Existing(val notebookId: String, val after: Int?) : ScanTarget
    data class New(val title: String, val subjectId: Long?) : ScanTarget
}

/** What the confirmation says and where it jumps to. */
data class ScanDone(val notebookId: String, val title: String, val firstIndex: Int, val count: Int)

@Serializable
private data class Draft(val sheets: List<ScanSheet> = emptyList(), val mode: ScanMode = ScanMode.ORIGINAL)

/**
 * The scan in progress, outside the notebook. Sheets are copied in at once
 * (scanner and share Uris stop working soon) and kept on disk with their order,
 * so a killed process, which is likely while the camera is in front, costs
 * nothing. Only when confirmed do they become pages. Existing pages are never
 * touched: a scan only ever adds, so locked pages stay as they are.
 */
class ScanStore(private val context: Context, private val library: Library, private val scope: CoroutineScope) {
    private val dir = File(context.noBackupFilesDir, "scan-draft").also { it.mkdirs() }
    private val draftFile = File(dir, "draft.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val work = Mutex()
    private val writing = Mutex()

    private val _sheets = MutableStateFlow<List<ScanSheet>>(emptyList())
    val sheets: StateFlow<List<ScanSheet>> = _sheets.asStateFlow()
    private val _mode = MutableStateFlow(ScanMode.ORIGINAL)
    val mode: StateFlow<ScanMode> = _mode.asStateFlow()
    /** Longer work in progress ("Seite 3 von 8 …"), or null. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        scope.launch {
            work.withLock {
                withContext(Dispatchers.IO) {
                    val d = runCatching { json.decodeFromString<Draft>(draftFile.readText()) }.getOrNull() ?: Draft()
                    _sheets.value = d.sheets.filter { File(dir, it.file).isFile }
                    _mode.value = d.mode
                    // Camera hand-overs nobody picked up.
                    val old = System.currentTimeMillis() - 86_400_000L
                    File(context.cacheDir, "camera").listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
                }
            }
        }
    }

    fun fileOf(sheet: ScanSheet) = File(dir, sheet.file)

    fun say(text: String) {
        _message.value = text
        scope.launch { delay(3500); if (_message.value == text) _message.value = null }
    }

    /**
     * Copies pictures and PDFs in as sheets, off the main thread, one at a time.
     * [deleteAfter] is a hand-over file of ours that is no longer needed then.
     */
    fun ingest(uris: List<Uri>, deleteAfter: File? = null) {
        if (uris.isEmpty()) return
        scope.launch {
            work.withLock {
                var failed = 0
                var protected = false
                var full = false
                uris.forEachIndexed { i, uri ->
                    if (_sheets.value.size >= ScanPlan.MAX_SHEETS) { full = true; return@forEachIndexed }
                    _busy.value = if (uris.size == 1) "Blatt wird übernommen …" else "Blatt ${i + 1} von ${uris.size} …"
                    if (isPdf(uri)) {
                        when (runCatching { ingestPdf(uri) }.exceptionOrNull()) {
                            null -> Unit
                            is SecurityException -> protected = true
                            else -> failed++
                        }
                    } else {
                        val name = runCatching { withContext(Dispatchers.IO) { copyImage(uri) } }.getOrNull()
                        if (name != null) add(name) else failed++
                    }
                }
                withContext(Dispatchers.IO) { deleteAfter?.delete() }
                _busy.value = null
                when {
                    protected -> say("Das PDF ist mit einem Passwort geschützt")
                    full -> say("Mehr als ${ScanPlan.MAX_SHEETS} Blätter gehen auf einmal nicht. Erst einfügen, dann weiter scannen.")
                    failed > 0 -> say(if (failed == 1) "Eine Datei konnte nicht gelesen werden" else "$failed Dateien konnten nicht gelesen werden")
                }
            }
        }
    }

    fun move(from: Int, to: Int) = update(ScanPlan.moved(_sheets.value, from, to))

    fun turn(id: String) = update(_sheets.value.map { if (it.id == id) it.copy(rotation = ScanPlan.turned(it.rotation)) else it })

    fun remove(id: String) {
        val gone = _sheets.value.firstOrNull { it.id == id } ?: return
        update(_sheets.value - gone)
        scope.launch(Dispatchers.IO) { fileOf(gone).delete() }
    }

    fun setMode(mode: ScanMode) { _mode.value = mode; persist() }

    /** Throws the draft away. */
    fun discard() {
        val all = _sheets.value
        update(emptyList())
        scope.launch(Dispatchers.IO) { all.forEach { fileOf(it).delete() } }
    }

    /**
     * Makes pages of the sheets, in the order shown. On any failure the draft
     * stays complete and nothing is half inserted. Runs in the app's scope, so
     * leaving the screen does not cut it off.
     */
    suspend fun commit(target: ScanTarget): Result<ScanDone> = withContext(scope.coroutineContext) {
        work.withLock {
            val sheets = _sheets.value
            if (sheets.isEmpty()) return@withLock Result.failure(IllegalStateException("Keine Blätter"))
            val moved = ArrayList<File>()
            val result = runCatching {
                val dao = RanzenDatabase.get(context).library()
                // Resolve the notebook before any file moves.
                val existing = target as? ScanTarget.Existing
                if (existing != null) dao.notebook(existing.notebookId) ?: error("Das Heft gibt es nicht mehr")
                val mode = _mode.value
                val files = sheets.mapIndexed { i, s ->
                    _busy.value = "Seite ${i + 1} von ${sheets.size} wird vorbereitet …"
                    withContext(Dispatchers.IO) { finish(s, mode) }.also { moved += it }.name
                }
                _busy.value = "Seiten werden eingefügt …"
                val done = when (target) {
                    is ScanTarget.Existing -> {
                        val nb = dao.notebook(target.notebookId) ?: error("Das Heft gibt es nicht mehr")
                        val pages = dao.pages(target.notebookId)
                        val at = ScanPlan.insertPosition(pages.map { it.position }, target.after)
                        library.addWorksheetPages(target.notebookId, at, files)
                        ScanDone(nb.id, nb.title, ScanPlan.firstIndex(pages.size, target.after), files.size)
                    }
                    is ScanTarget.New -> {
                        val id = library.createNotebook(target.title, target.subjectId, PaperStyle.BLANK)
                        val blank = dao.pages(id)
                        library.addWorksheetPages(id, blank.size, files)
                        // The empty first page a new notebook comes with is not wanted here.
                        blank.forEach { p -> library.removePage(id, p.id, dao.pages(id).map { it.id }.filter { it != p.id }) }
                        ScanDone(id, dao.notebook(id)?.title ?: target.title, 0, files.size)
                    }
                }
                // The notebook opens at the new pages next time, on this device.
                library.setLastPage(done.notebookId, done.firstIndex)
                done
            }
            if (result.isSuccess) {
                withContext(Dispatchers.IO) { sheets.forEach { fileOf(it).delete() } }
                _sheets.value = emptyList()
                _mode.value = ScanMode.ORIGINAL
                persist()
            } else {
                // Not referenced by any page: the originals are still in the draft.
                withContext(Dispatchers.IO) { moved.forEach { it.delete() } }
            }
            _busy.value = null
            result
        }
    }

    private fun update(list: List<ScanSheet>) { _sheets.value = list; persist() }

    private fun add(name: String) { _sheets.update { it + ScanSheet(UUID.randomUUID().toString(), name) }; persist() }

    private fun persist() {
        val text = json.encodeToString(Draft(_sheets.value, _mode.value))
        scope.launch(Dispatchers.IO) {
            writing.withLock {
                val tmp = File(dir, "draft.json.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(draftFile)) { draftFile.delete(); tmp.renameTo(draftFile) }
            }
        }
    }

    private fun isPdf(uri: Uri): Boolean {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return type == "application/pdf" || (type == null && uri.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true)
    }

    /** At most 4000 px, upright, JPEG. Null if it is no readable picture. */
    private fun copyImage(uri: Uri): String? {
        val bmp = decode(uri) ?: return null
        val name = UUID.randomUUID().toString() + ".jpg"
        try {
            writeJpeg(bmp, File(dir, name))
        } finally { bmp.recycle() }
        return name
    }

    private fun decode(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { d, info, _ ->
                    val (w, h) = ScanPlan.fit(info.size.width, info.size.height)
                    d.setTargetSize(w, h)
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val opts = BitmapFactory.Options().apply { inSampleSize = ScanPlan.sampleSize(bounds.outWidth, bounds.outHeight, ScanPlan.MAX_LONG_SIDE) }
                val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
                val degrees = runCatching {
                    resolver.openInputStream(uri)?.use {
                        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                    } ?: 0
                }.getOrDefault(0)
                val (w, h) = ScanPlan.fit(raw.width, raw.height)
                val small = if (w == raw.width) raw else Bitmap.createScaledBitmap(raw, w, h, true).also { if (it !== raw) raw.recycle() }
                upright(small, degrees)
            }
        } catch (_: OutOfMemoryError) { null } catch (_: Exception) { null }
    }

    private fun upright(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (out !== src) src.recycle()
        return out
    }

    /** Every PDF page becomes a sheet, as far as it fits into the limit. */
    private suspend fun ingestPdf(uri: Uri) {
        // A local copy: providers often hand out pipes, and the renderer needs to seek.
        val tmp = File(context.cacheDir, "scan-${System.nanoTime()}.pdf")
        try {
            withContext(Dispatchers.IO) {
                (context.contentResolver.openInputStream(uri) ?: error("PDF nicht lesbar")).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
            }
            withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { pdf ->
                        for (i in 0 until pdf.pageCount) {
                            if (_sheets.value.size >= ScanPlan.MAX_SHEETS) break
                            _busy.value = "PDF-Seite ${i + 1} von ${pdf.pageCount} …"
                            pdf.openPage(i).use { page ->
                                val s = ScanPlan.pdfScale(page.width, page.height)
                                val bmp = Bitmap.createBitmap((page.width * s).toInt().coerceAtLeast(1), (page.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                try {
                                    bmp.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    val name = UUID.randomUUID().toString() + ".jpg"
                                    writeJpeg(bmp, File(dir, name))
                                    add(name)
                                } finally { bmp.recycle() }
                            }
                        }
                    }
                }
            }
        } finally { withContext(Dispatchers.IO) { tmp.delete() } }
    }

    /** The final picture in the image folder: turned and improved if asked, otherwise the draft file itself. */
    private fun finish(sheet: ScanSheet, mode: ScanMode): File {
        val out = File(library.imageDir, UUID.randomUUID().toString() + ".jpg")
        val src = fileOf(sheet)
        if (sheet.rotation == 0 && mode == ScanMode.ORIGINAL) {
            src.copyTo(out, overwrite = true)
            return out
        }
        val raw = BitmapFactory.decodeFile(src.path) ?: error("Blatt nicht lesbar")
        val bmp = process(raw, sheet.rotation, mode)
        try { writeJpeg(bmp, out) } finally { bmp.recycle() }
        return out
    }

    private fun writeJpeg(bmp: Bitmap, file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)) error("Bild nicht speicherbar") }
        if (!tmp.renameTo(file)) { tmp.delete(); error("Bild nicht speicherbar") }
    }

    companion object {
        /**
         * Rotates and improves [src] (which is consumed). If the memory does
         * not suffice for the improvement, the sheet just stays as it is.
         */
        fun process(src: Bitmap, rotation: Int, mode: ScanMode): Bitmap {
            val turned = if (rotation == 0) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it !== src) src.recycle() }
            if (mode == ScanMode.ORIGINAL) return turned
            try {
                val w = turned.width; val h = turned.height
                val px = IntArray(w * h)
                turned.getPixels(px, 0, w, 0, 0, w, h)
                ScanEnhance.apply(px, w, h, mode)
                val out = if (turned.isMutable) turned else turned.copy(Bitmap.Config.ARGB_8888, true).also { turned.recycle() }
                out.setPixels(px, 0, w, 0, 0, w, h)
                return out
            } catch (_: OutOfMemoryError) {
                return turned
            }
        }

        /** Smaller version of a sheet for the screen. */
        fun preview(file: File, rotation: Int, mode: ScanMode, maxLong: Int): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = ScanPlan.sampleSize(bounds.outWidth, bounds.outHeight, maxLong) })
            raw?.let { process(it, rotation, mode) }
        } catch (_: OutOfMemoryError) { null }
    }
}
