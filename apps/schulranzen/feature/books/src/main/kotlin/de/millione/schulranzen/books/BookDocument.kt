package de.millione.schulranzen.books

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import de.millione.schulranzen.core.books.BookUnits
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * An open PDF. [PdfRenderer] allows one open page at a time and is not
 * thread-safe, so every call runs on this document's own render thread
 * (never the main thread); [close] is queued behind pending renders.
 */
internal class BookDocument private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val executor: ExecutorService,
    /** PDF page sizes in points. */
    val widths: FloatArray,
    val heights: FloatArray,
) {
    val dispatcher = executor.asCoroutineDispatcher()
    val pageCount get() = widths.size
    @Volatile var closed = false
        private set

    /** Unrotated page height in units (width is [BookUnits.WIDTH]). */
    fun unitHeight(page: Int) = BookUnits.height(widths[page], heights[page])

    /** Points -> unrotated page units. */
    fun pointsToUnits(page: Int): Matrix = Matrix().apply { val f = BookUnits.WIDTH / widths[page]; setScale(f, f) }

    /** Runs [block] on the render thread; dropped once the document is closed. */
    fun post(block: () -> Unit) {
        if (closed) return
        runCatching { executor.execute { if (!closed) block() } }
    }

    /**
     * Renders [page] into [bitmap] with [transform] (points -> bitmap pixels)
     * on white paper. Call on the render thread only.
     */
    fun renderBlocking(page: Int, bitmap: Bitmap, transform: Matrix) {
        bitmap.eraseColor(Color.WHITE)
        renderer.openPage(page).use { it.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
    }

    suspend fun <T> onRenderThread(block: () -> T): T = withContext(dispatcher) { block() }

    fun close() {
        if (closed) return
        runCatching {
            executor.execute {
                closed = true
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
        executor.shutdown()
    }

    companion object {
        /** Opens the book's PDF; call off the main thread. */
        fun open(store: ShelfStore, id: String): BookDocument {
            val file: File = store.pdf(id)
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(pfd) } catch (e: Throwable) { pfd.close(); throw e }
            val sizes = store.pageSizes(id, renderer)
            val executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "book-render").apply { priority = Thread.NORM_PRIORITY - 1 }
            }
            return BookDocument(pfd, renderer, executor, sizes.widths.toFloatArray(), sizes.heights.toFloatArray())
        }
    }
}
