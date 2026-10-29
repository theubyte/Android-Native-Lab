package de.millione.schulranzen.study

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.max

/**
 * Pictures on flash cards. They are copied into `filesDir/study/images`
 * so a card keeps its picture when the source (gallery, cut-out) goes
 * away; cards store the path relative to `filesDir/study`. Large photos
 * are scaled to [MAX_EDGE], which is the tablet's long side: sharp at full
 * screen, without keeping 12-MP originals.
 */
object StudyImages {
    const val MAX_EDGE = 2560
    private const val DIR = "images"

    private fun root(context: Context) = File(context.applicationContext.filesDir, "study")

    fun file(context: Context, relative: String): File = File(root(context), relative)

    /** Copies a picture from the gallery (or any content URI). Returns the relative path. */
    suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val mime = cr.getType(uri).orEmpty()
        store(context, mime) { cr.openInputStream(uri) ?: error("Bild nicht lesbar") }
    }

    /** Copies a picture file (e.g. a cut-out from a notebook or book). Returns the relative path. */
    suspend fun import(context: Context, source: File): String = withContext(Dispatchers.IO) {
        val mime = when (source.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
        store(context, mime) { source.inputStream() }
    }

    private fun store(context: Context, mime: String, open: () -> InputStream): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Kein lesbares Bild")
        val orientation = if (mime == "image/jpeg" || bounds.outMimeType == "image/jpeg") {
            runCatching { open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } else ExifInterface.ORIENTATION_NORMAL
        val png = (bounds.outMimeType ?: mime) == "image/png"
        val ext = if (png) "png" else "jpg"
        val dir = File(root(context), DIR).apply { mkdirs() }
        val name = "${UUID.randomUUID()}.$ext"
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        val simple = max(bounds.outWidth, bounds.outHeight) <= MAX_EDGE && orientation == ExifInterface.ORIENTATION_NORMAL &&
            (bounds.outMimeType == "image/png" || bounds.outMimeType == "image/jpeg")
        try {
            if (simple) {
                open().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } else {
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
                var bmp = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                    ?: error("Kein lesbares Bild")
                val edge = max(bmp.width, bmp.height)
                val matrix = Matrix()
                if (edge > MAX_EDGE) { val f = MAX_EDGE.toFloat() / edge; matrix.postScale(f, f) }
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                }
                if (!matrix.isIdentity) {
                    val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                    if (turned !== bmp) bmp.recycle()
                    bmp = turned
                }
                tmp.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
                bmp.recycle()
            }
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        return "$DIR/$name"
    }

    /** Removes pictures no card uses any more. Errors are ignored: a stray file does no harm. */
    suspend fun delete(context: Context, relatives: Collection<String?>) = withContext(Dispatchers.IO) {
        relatives.filterNotNull().forEach { rel ->
            runCatching { file(context, rel).delete() }
            synchronized(cache) { cache.snapshot().keys.filter { it.startsWith("$rel@") }.forEach(cache::remove) }
        }
    }

    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget [delete], for clean-up when a screen goes away and its own scope is gone. */
    fun discard(context: Context, relatives: Collection<String?>) {
        if (relatives.all { it == null }) return
        val app = context.applicationContext
        val list = relatives.toList()
        background.launch { delete(app, list) }
    }

    // Decoded pictures by "path@edge"; sized in kilobytes.
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }

    /** Decodes a picture no larger than [maxEdge] pixels on its long side, off the main thread. */
    suspend fun load(context: Context, relative: String, maxEdge: Int): ImageBitmap? = load(file(context, relative), maxEdge, "$relative@$maxEdge")

    /** Same for any picture file, e.g. a cut-out the host passes in. */
    suspend fun load(f: File, maxEdge: Int, key: String = "${f.path}@$maxEdge"): ImageBitmap? {
        synchronized(cache) { cache.get(key) }?.let { return it }
        return withContext(Dispatchers.IO) {
            if (!f.exists()) return@withContext null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
            bmp.asImageBitmap().also { synchronized(cache) { cache.put(key, it) } }
        }
    }
}

/** A card picture, decoded off the main thread and drawn with high filtering so text stays crisp. */
@Composable
internal fun StudyImage(relative: String, modifier: Modifier = Modifier, maxEdge: Int = StudyImages.MAX_EDGE, contentScale: ContentScale = ContentScale.Fit) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, relative, maxEdge) { value = StudyImages.load(context, relative, maxEdge) }
    bmp?.let { Image(it, contentDescription = "Bild der Karte", modifier = modifier, contentScale = contentScale, filterQuality = FilterQuality.High) }
}

/** A picture file outside the card store (e.g. a fresh cut-out), decoded off the main thread. */
@Composable
internal fun FileImage(file: File, modifier: Modifier = Modifier, maxEdge: Int = StudyImages.MAX_EDGE) {
    val bmp by produceState<ImageBitmap?>(null, file.path, maxEdge) { value = StudyImages.load(file, maxEdge) }
    bmp?.let { Image(it, contentDescription = "Ausschnitt", modifier = modifier, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High) }
}
