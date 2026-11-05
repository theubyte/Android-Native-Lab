package de.millione.schulranzen.core.notebook

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Framing of a page file: "RNZ1", stroke count, then per stroke id, tool,
 * colour, size and the length-prefixed Jetpack Ink payload. The reader takes
 * what is still readable from a damaged file instead of giving up on all of
 * it, and never trusts a length field more than the file's size.
 */
object InkFileFormat {
    private const val MAGIC = 0x524E5A31 // "RNZ1"
    /** id + tool + colour + size + payload length. */
    private const val MIN_RECORD = 8 + 1 + 4 + 4 + 4

    fun writeHeader(out: DataOutputStream, count: Int) {
        out.writeInt(MAGIC)
        out.writeInt(count)
    }

    fun writeRecordHead(out: DataOutputStream, id: Long, tool: Byte, color: Int, size: Float, payloadSize: Int) {
        out.writeLong(id)
        out.writeByte(tool.toInt())
        out.writeInt(color)
        out.writeFloat(size)
        out.writeInt(payloadSize)
    }

    /**
     * Reads a file of [length] bytes and hands each complete record to
     * [onRecord]. Returns false when the file was damaged (bad header, cut
     * off, impossible length), true when everything was there. Read errors
     * of the device itself still throw.
     */
    fun parse(input: InputStream, length: Long, onRecord: (id: Long, tool: Byte, color: Int, size: Float, payload: ByteArray) -> Unit): Boolean {
        val data = DataInputStream(input)
        if (length < 8) return false
        var left = length - 8
        val count = try {
            if (data.readInt() != MAGIC) return false
            data.readInt()
        } catch (_: EOFException) {
            return false
        }
        if (count < 0) return false
        var read = 0
        while (read < count) {
            if (left < MIN_RECORD) return false
            try {
                val id = data.readLong()
                val tool = data.readByte()
                val color = data.readInt()
                val size = data.readFloat()
                val n = data.readInt()
                left -= MIN_RECORD
                if (n < 0 || n > left) return false
                val payload = ByteArray(n)
                data.readFully(payload)
                left -= n
                onRecord(id, tool, color, size, payload)
            } catch (_: EOFException) {
                return false
            }
            read++
        }
        return true
    }
}
