package de.millione.schulranzen.ink

import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Page file: "RNZ1", stroke count, then per stroke id, tool, colour, size
 * and the Jetpack Ink input batch (its own compact, versioned encoding).
 * The brush is rebuilt from tool/colour/size, so brush tuning applies to
 * old pages too. Written to a temp file and renamed: a crash never leaves
 * half a page.
 */
object InkCodec {
    private const val MAGIC = 0x524E5A31 // "RNZ1"

    fun write(file: File, strokes: List<PageStroke>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        file.parentFile?.mkdirs()
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(strokes.size)
            val buf = ByteArrayOutputStream()
            for (s in strokes) {
                out.writeLong(s.id)
                out.writeByte(s.tool.code.toInt())
                out.writeInt(s.color)
                out.writeFloat(s.size)
                buf.reset()
                s.stroke.inputs.encode(buf)
                out.writeInt(buf.size())
                buf.writeTo(out)
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Seite konnte nicht gespeichert werden: ${file.name}")
        }
    }

    fun read(file: File): List<PageStroke> {
        if (!file.isFile) return emptyList()
        DataInputStream(file.inputStream().buffered()).use { inp ->
            if (inp.readInt() != MAGIC) throw IOException("Keine Schulranzen-Seite: ${file.name}")
            val n = inp.readInt()
            val out = ArrayList<PageStroke>(n)
            repeat(n) {
                val id = inp.readLong()
                val tool = ToolKind.of(inp.readByte())
                val color = inp.readInt()
                val size = inp.readFloat()
                val bytes = ByteArray(inp.readInt()).also { inp.readFully(it) }
                val inputs = StrokeInputBatch.decode(ByteArrayInputStream(bytes))
                out += PageStroke(id, tool, color, size, Stroke(Brushes.brush(tool, color, size), inputs))
            }
            return out
        }
    }
}
