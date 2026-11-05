package de.millione.schulranzen.ink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import de.millione.schulranzen.core.notebook.SafeFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/** Objects on a page besides ink. Page units, like strokes. */
@Serializable
sealed class PageItem {
    abstract val id: Long
    abstract fun bounds(): RectF
    /** The object moved/scaled by [m] (uniform scale + translation). */
    abstract fun transformed(m: Matrix, newId: Long): PageItem
}

/** Typed text. [size] is the font size in page units. */
@Serializable
@SerialName("text")
data class TextItem(
    override val id: Long,
    val x: Float,
    val y: Float,
    val width: Float,
    val text: String,
    val size: Float,
    val color: Int,
) : PageItem() {
    override fun bounds() = RectF(x, y, x + width, y + ItemPainter.textHeight(this))
    override fun transformed(m: Matrix, newId: Long): PageItem {
        val p = floatArrayOf(x, y).also { m.mapPoints(it) }
        val s = abs(m.mapRadius(1f))
        return copy(id = newId, x = p[0], y = p[1], width = width * s, size = size * s)
    }

    companion object {
        /** Font sizes offered: small, normal, heading (page units, 3.8 / 5.5 / 8.4 mm). */
        val sizes = listOf(18f, 26f, 40f)
    }
}

/** A picture stored in the app's image folder under [file]. */
@Serializable
@SerialName("image")
data class ImageItem(
    override val id: Long,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val file: String,
) : PageItem() {
    override fun bounds() = RectF(x, y, x + w, y + h)
    override fun transformed(m: Matrix, newId: Long): PageItem {
        val r = bounds().also { m.mapRect(it) }
        return copy(id = newId, x = r.left, y = r.top, w = r.width(), h = r.height())
    }
}

/** Page objects as stored next to the ink file. */
object ItemCodec {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    fun write(file: File, items: List<PageItem>) {
        if (items.isEmpty()) { file.delete(); return }
        val text = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PageItem.serializer()), items)
        SafeFile.write(file) { it.write(text.toByteArray()) }
    }

    fun read(file: File): List<PageItem> =
        if (!file.isFile) emptyList()
        else json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PageItem.serializer()), file.readText())
}

/** Draws text and pictures; caches layouts and decoded images. */
object ItemPainter {
    var typeface: Typeface = Typeface.SANS_SERIF
    /** Folder the image files live in; set by the app. */
    var imageDir: File? = null

    private val layouts = LruCache<TextItem, StaticLayout>(200)
    private val images = object : LruCache<String, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val decoding = ConcurrentHashMap.newKeySet<String>()
    private val decoder by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "ink-images").apply { isDaemon = true } } }
    /** A picture asked for with async = true is ready now. Any thread. */
    @Volatile var onImageReady: (() -> Unit)? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private fun layout(t: TextItem): StaticLayout = layouts.get(t) ?: run {
        // Its own paint: a layout keeps the paint it was built with and draws with it.
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = this@ItemPainter.typeface
            textSize = t.size
            color = t.color
        }
        StaticLayout.Builder.obtain(t.text, 0, t.text.length, paint, max(1, t.width.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
            .also { layouts.put(t, it) }
    }

    fun textHeight(t: TextItem): Float = max(t.size * 1.2f, layout(t).height.toFloat())

    /** Picture for a file, decoded at most ~2000 px on the long side. */
    fun image(file: String): Bitmap? = images.get(file) ?: run {
        val f = File(imageDir ?: return null, file)
        if (!f.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        val once = { s: Int -> BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) }
        val bmp = try {
            once(sample)
        } catch (_: OutOfMemoryError) {
            // Low memory costs sharpness, not the page.
            images.evictAll()
            try { once(sample * 2) } catch (_: OutOfMemoryError) { null }
        }
        bmp?.also { images.put(file, it) }
    }

    /** The picture if it is decoded; otherwise decodes it in the background and returns null. */
    private fun imageOrRequest(file: String): Bitmap? = images.get(file) ?: run {
        if (decoding.add(file)) decoder.execute {
            try { if (image(file) != null) onImageReady?.invoke() } finally { decoding.remove(file) }
        }
        null
    }

    /** A deleted picture must not live on in the cache. */
    fun forget(file: String) { images.remove(file) }

    /**
     * A worksheet filling the sheet: scaled to fit A4 with its aspect kept,
     * centred, so a photo or PDF page sits like a printed handout.
     */
    fun drawSheet(canvas: Canvas, file: String, async: Boolean = false): Boolean {
        val bmp = (if (async) imageOrRequest(file) else image(file)) ?: return !async
        val pw = PageGeometry.WIDTH; val ph = PageGeometry.HEIGHT
        val s = minOf(pw / bmp.width, ph / bmp.height)
        val w = bmp.width * s; val h = bmp.height * s
        val l = (pw - w) / 2; val t = (ph - h) / 2
        canvas.drawBitmap(bmp, null, RectF(l, t, l + w, t + h), bitmapPaint)
        return true
    }

    /**
     * Draws [item]. With [async] a picture that is not decoded yet is skipped
     * (the page stays smooth while scrolling) and false is returned; draw
     * again after [onImageReady].
     */
    fun draw(canvas: Canvas, item: PageItem, async: Boolean = false): Boolean {
        when (item) {
            is TextItem -> {
                canvas.save()
                canvas.translate(item.x, item.y)
                layout(item).draw(canvas)
                canvas.restore()
            }
            is ImageItem -> {
                val bmp = (if (async) imageOrRequest(item.file) else image(item.file)) ?: return !async
                canvas.drawBitmap(bmp, null, item.bounds(), bitmapPaint)
            }
        }
        return true
    }
}
