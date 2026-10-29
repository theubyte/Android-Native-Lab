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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PageItem.serializer()), items))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
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

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val layouts = LruCache<TextItem, StaticLayout>(200)
    private val images = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private fun layout(t: TextItem): StaticLayout = layouts.get(t) ?: run {
        paint.typeface = typeface
        paint.textSize = t.size
        paint.color = t.color
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
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.also { images.put(file, it) }
    }

    fun draw(canvas: Canvas, item: PageItem) {
        when (item) {
            is TextItem -> {
                canvas.save()
                canvas.translate(item.x, item.y)
                layout(item).draw(canvas)
                canvas.restore()
            }
            is ImageItem -> {
                val bmp = image(item.file) ?: return
                canvas.drawBitmap(bmp, null, item.bounds(), bitmapPaint)
            }
        }
    }
}
