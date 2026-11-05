package de.millione.schulranzen.ink

import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import de.millione.schulranzen.core.notebook.InkFileFormat
import de.millione.schulranzen.core.notebook.SafeFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Page file ([InkFileFormat]): per stroke id, tool, colour, size and the
 * Jetpack Ink input batch (its own compact, versioned encoding). The brush
 * is rebuilt from tool/colour/size, so brush tuning applies to old pages
 * too. Written through [SafeFile]: a crash never leaves half a page.
 */
object InkCodec {
    /** What could be read; [damaged] when the file was cut off or partly unreadable. */
    class Loaded(val strokes: List<PageStroke>, val damaged: Boolean)

    fun write(file: File, strokes: List<PageStroke>) {
        SafeFile.write(file) { raw ->
            val out = DataOutputStream(raw)
            InkFileFormat.writeHeader(out, strokes.size)
            val buf = ByteArrayOutputStream()
            for (s in strokes) {
                buf.reset()
                s.stroke.inputs.encode(buf)
                InkFileFormat.writeRecordHead(out, s.id, s.tool.code, s.color, s.size, buf.size())
                buf.writeTo(out)
            }
            out.flush()
        }
    }

    /**
     * Reads as many strokes as the file still holds. A broken stroke costs
     * that stroke, not the page. Device read errors and low memory throw:
     * those say nothing about the file.
     */
    fun readChecked(file: File): Loaded {
        if (!file.isFile) return Loaded(emptyList(), false)
        val out = ArrayList<PageStroke>()
        var bad = false
        val whole = file.inputStream().buffered().use { input ->
            InkFileFormat.parse(input, file.length()) { id, code, color, size, payload ->
                try {
                    val tool = ToolKind.of(code)
                    val inputs = StrokeInputBatch.decode(ByteArrayInputStream(payload))
                    out += PageStroke(id, tool, color, size, Stroke(Brushes.brush(tool, color, size), inputs))
                } catch (_: Exception) {
                    bad = true
                }
            }
        }
        return Loaded(out, bad || !whole)
    }

    /** Strokes of the file; throws only when nothing at all could be read from a damaged file. */
    fun read(file: File): List<PageStroke> {
        val loaded = readChecked(file)
        if (loaded.damaged && loaded.strokes.isEmpty()) throw IOException("Keine Schulranzen-Seite: ${file.name}")
        return loaded.strokes
    }
}
