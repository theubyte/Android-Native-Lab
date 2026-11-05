package de.millione.schulranzen.core.notebook

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

class NotebookFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun page(vararg payloads: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        InkFileFormat.writeHeader(out, payloads.size)
        payloads.forEachIndexed { i, p ->
            InkFileFormat.writeRecordHead(out, i.toLong(), 1, 0xFF112233.toInt(), 3f, p.size)
            out.write(p)
        }
        return bytes.toByteArray()
    }

    private fun parse(data: ByteArray): Pair<List<ByteArray>, Boolean> {
        val got = ArrayList<ByteArray>()
        val ok = InkFileFormat.parse(ByteArrayInputStream(data), data.size.toLong()) { _, _, _, _, p -> got += p }
        return got to ok
    }

    @Test fun safeWrite_replacesAndLeavesNoTemp() {
        val f = File(tmp.root, "sub/dir/a.rnz")
        SafeFile.write(f) { it.write(byteArrayOf(1, 2)) }
        SafeFile.write(f) { it.write(byteArrayOf(3)) }
        assertArrayEquals(byteArrayOf(3), f.readBytes())
        assertEquals(listOf("a.rnz"), f.parentFile.list()!!.toList())
    }

    @Test fun safeWrite_failureKeepsOldFile() {
        val f = File(tmp.root, "a.rnz")
        SafeFile.write(f) { it.write(byteArrayOf(1, 2, 3)) }
        runCatching { SafeFile.write(f) { it.write(9); throw IOException("ENOSPC") } }
        assertArrayEquals(byteArrayOf(1, 2, 3), f.readBytes())
        assertEquals(listOf("a.rnz"), tmp.root.list()!!.toList())
    }

    @Test fun keepCopy_doesNotPileUp() {
        val f = File(tmp.root, "a.rnz").apply { writeBytes(byteArrayOf(1, 2)) }
        val first = SafeFile.keepCopy(f)
        assertEquals("a.rnz.damaged", first?.name)
        assertEquals("a.rnz.damaged", SafeFile.keepCopy(f)?.name)
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals("a.rnz.damaged2", SafeFile.keepCopy(f)?.name)
        assertEquals(null, SafeFile.keepCopy(File(tmp.root, "fehlt")))
    }

    @Test fun parse_wholeFile() {
        val (got, ok) = parse(page(byteArrayOf(1), byteArrayOf(2, 2)))
        assertTrue(ok)
        assertEquals(2, got.size)
        assertArrayEquals(byteArrayOf(2, 2), got[1])
    }

    @Test fun parse_truncatedKeepsFrontStrokes() {
        val whole = page(byteArrayOf(1, 1, 1), byteArrayOf(2, 2, 2))
        val (got, ok) = parse(whole.copyOf(whole.size - 2))
        assertFalse(ok)
        assertEquals(1, got.size)
    }

    @Test fun parse_emptyAndForeignFiles() {
        assertFalse(parse(ByteArray(0)).second)
        assertFalse(parse(ByteArray(40) { 7 }).second)
    }

    @Test fun parse_hugeLengthsDoNotAllocate() {
        // Stroke count and payload length that would need gigabytes.
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        InkFileFormat.writeHeader(out, Int.MAX_VALUE)
        InkFileFormat.writeRecordHead(out, 1, 1, 0, 1f, Int.MAX_VALUE)
        val (got, ok) = parse(bytes.toByteArray())
        assertFalse(ok)
        assertTrue(got.isEmpty())
        val negative = ByteArrayOutputStream()
        InkFileFormat.writeHeader(DataOutputStream(negative), -5)
        assertFalse(parse(negative.toByteArray()).second)
    }

    @Test fun messages_arePlainGerman() {
        assertTrue(UserMessages.of(IOException("write failed: ENOSPC"), "Speichern").contains("voll"))
        assertTrue(UserMessages.of(IOException("x", IOException("No space left on device")), "Speichern").contains("voll"))
        assertTrue(UserMessages.ofPdf(SecurityException("password required or incorrect password")).contains("Passwort"))
        assertTrue(UserMessages.ofPdf(IOException("file not in PDF format or corrupted")).contains("beschädigt"))
        assertEquals("Das Heft hat keine Seiten", UserMessages.of(UserError("Das Heft hat keine Seiten"), "X"))
        assertFalse(UserMessages.of(IllegalStateException("EIO 0x34"), "Speichern").contains("EIO"))
        assertNotNull(UserMessages.of(OutOfMemoryError(), "PDF"))
    }

    @Test fun orphans_onlyUnreferencedCandidates() {
        val left = ImageRefs.orphans(setOf("a.jpg", "b.jpg", "c.jpg"), sequenceOf("""[{"file":"a.jpg"}]""", "c.jpg"))
        assertEquals(setOf("b.jpg"), left)
        assertTrue(ImageRefs.orphans(emptySet(), sequenceOf("x")).isEmpty())
    }
}
