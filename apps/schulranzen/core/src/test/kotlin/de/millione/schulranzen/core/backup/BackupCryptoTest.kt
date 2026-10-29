package de.millione.schulranzen.core.backup

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupCryptoTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Low cost, so the tests stay fast; the format is the same. */
    private val iter = BackupCrypto.MIN_ITERATIONS
    private val chunk = 4096
    private val pw = "Ranzen-2026!".toCharArray()

    private fun data(n: Int, seed: Long = 7) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun encrypt(plain: ByteArray, password: CharArray = pw, chunkSize: Int = chunk): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, password, iter, chunkSize).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun decrypt(sealed: ByteArray, password: CharArray = pw): ByteArray =
        BackupCrypto.decryptingStream(ByteArrayInputStream(sealed), password).use { it.readBytes() }

    @Test fun roundTripVariousSizes() {
        // Empty, below one chunk, exactly one chunk, exact multiple, odd tail.
        for (n in listOf(0, 1, chunk - 1, chunk, chunk + 1, 3 * chunk, 3 * chunk + 17)) {
            val plain = data(n, n.toLong())
            val sealed = encrypt(plain)
            assertTrue(BackupCrypto.isEncrypted(ByteArrayInputStream(sealed)))
            assertContentEquals(plain, decrypt(sealed), "size $n")
            // Header + one tag per chunk; a full last chunk carries the flag itself.
            val chunks = maxOf(1, (n + chunk - 1) / chunk)
            assertEquals(CryptoHeader.SIZE + n + 16 * chunks, sealed.size, "size $n")
        }
    }

    @Test fun sameInputTwiceGivesDifferentCiphertext() {
        val plain = data(1000)
        assertFalse(encrypt(plain).contentEquals(encrypt(plain)))
    }

    @Test fun wrongPasswordIsReportedAsSuch() {
        val sealed = encrypt(data(10_000))
        assertFailsWith<WrongPasswordException> { decrypt(sealed, "ranzen-2026!".toCharArray()) }
        assertFailsWith<BackupException> { decrypt(sealed, CharArray(0)) }
    }

    @Test fun flippedByteInDataIsDetected() {
        val sealed = encrypt(data(3 * chunk + 5))
        for (pos in listOf(CryptoHeader.SIZE, CryptoHeader.SIZE + chunk + 3, sealed.size - 1)) {
            val bad = sealed.copyOf().also { it[pos] = (it[pos].toInt() xor 0x01).toByte() }
            assertFailsWith<TamperedBackupException>("pos $pos") { decrypt(bad) }
        }
    }

    @Test fun damagedHeaderIsDetected() {
        val sealed = encrypt(data(100))
        // Chunk size field (CRC catches it before the password is tried).
        val bad = sealed.copyOf().also { it[32] = (it[32].toInt() xor 0x40).toByte() }
        assertFailsWith<TamperedBackupException> { decrypt(bad) }
    }

    @Test fun headerWithFixedCrcStillFailsTheChunks() {
        // An attacker who recomputes the CRC: changing the nonce prefix keeps
        // the key check valid, but every chunk tag breaks.
        val sealed = encrypt(data(2 * chunk))
        val bad = sealed.copyOf()
        bad[35] = (bad[35].toInt() xor 1).toByte() // first nonce prefix byte
        val crc = java.util.zip.CRC32().apply { update(bad, 0, CryptoHeader.SIZE - 4) }.value.toInt()
        bad[CryptoHeader.SIZE - 4] = (crc ushr 24).toByte(); bad[CryptoHeader.SIZE - 3] = (crc ushr 16).toByte()
        bad[CryptoHeader.SIZE - 2] = (crc ushr 8).toByte(); bad[CryptoHeader.SIZE - 1] = crc.toByte()
        assertFailsWith<TamperedBackupException> { decrypt(bad) }
    }

    @Test fun truncationAndAppendingAreDetected() {
        val sealed = encrypt(data(3 * chunk + 100))
        // Cut exactly at a chunk boundary: the remaining last chunk lacks the "last" flag.
        val atBoundary = sealed.copyOf(CryptoHeader.SIZE + 2 * (chunk + 16))
        assertFailsWith<TamperedBackupException> { decrypt(atBoundary) }
        assertFailsWith<TamperedBackupException> { decrypt(sealed.copyOf(sealed.size - 7)) }
        assertFailsWith<TamperedBackupException> { decrypt(sealed + ByteArray(20)) }
        assertFailsWith<TamperedBackupException> { decrypt(sealed.copyOf(CryptoHeader.SIZE - 3)) }
    }

    @Test fun swappedChunksAreDetected() {
        val sealed = encrypt(data(3 * chunk))
        val a = CryptoHeader.SIZE
        val len = chunk + 16
        val bad = sealed.copyOf()
        sealed.copyInto(bad, a, a + len, a + 2 * len)
        sealed.copyInto(bad, a + len, a, a + len)
        assertFailsWith<TamperedBackupException> { decrypt(bad) }
    }

    @Test fun notOurFormat() {
        assertFalse(BackupCrypto.isEncrypted(ByteArrayInputStream("PK\u0003\u0004 zip".toByteArray())))
        assertFailsWith<BackupException> { decrypt("PK\u0003\u0004 und noch mehr Bytes für den Kopf, lang genug.......".toByteArray()) }
    }

    @Test fun unicodePasswordIsNormalised() {
        val composed = "Übungä".toCharArray()
        val decomposed = "Übungä".toCharArray()
        assertContentEquals(data(50), decrypt(encrypt(data(50), composed), decomposed))
    }

    @Test fun pbkdf2MatchesTheJdk() {
        val salt = data(16, 3)
        for (c in listOf(1, 2, 1000)) {
            val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec("passwort".toCharArray(), salt, c, 256)).encoded
            assertContentEquals(jdk, Pbkdf2.sha256("passwort".toByteArray(), salt, c, 32), "c=$c")
        }
        // RFC 7914 §11, PBKDF2-HMAC-SHA256 ("passwd", "salt", 1, 64).
        val expected = "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"
        assertEquals(expected, Hashing.hex(Pbkdf2.sha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)))
    }

    @Test fun fileRoundTripWithFullBackupAndProgress() {
        val src = tmp.newFolder("files")
        File(src, "ink").mkdirs()
        File(src, "ink/p1.rnz").writeBytes(data(300_000))
        File(src, "note.txt").writeText("Hallo")
        val enc = tmp.newFile("b.srbk")
        enc.outputStream().buffered().use { fo ->
            val e = BackupCrypto.encryptingStream(fo, pw, iter)
            FullBackup.write(listOf(BackupRoot("files", src)), e, BackupManifest(created = 1))
            e.finish()
        }
        assertTrue(BackupCrypto.isEncrypted(enc))
        assertEquals(ArchiveKind.ENCRYPTED, FullBackup.detect(enc))
        assertTrue(BackupCrypto.checkPassword(enc, pw))
        assertFalse(BackupCrypto.checkPassword(enc, "falsch".toCharArray()))
        val zip = File(tmp.root, "out.zip")
        var last = 0L
        BackupCrypto.decryptFile(enc, zip, pw) { d, t -> assertTrue(d <= t); last = d }
        assertEquals(enc.length(), last)
        assertEquals(ArchiveKind.OWN, FullBackup.detect(zip))
        val stage = File(tmp.root, "stage")
        FullBackup.extractVerified(zip, stage, setOf("files"))
        assertContentEquals(File(src, "ink/p1.rnz").readBytes(), File(stage, "files/ink/p1.rnz").readBytes())
        // A wrong password leaves no output file behind.
        val other = File(tmp.root, "other.zip")
        assertFailsWith<WrongPasswordException> { BackupCrypto.decryptFile(enc, other, "falsch!!".toCharArray()) }
        assertFalse(other.exists())
        assertFalse(File(tmp.root, "other.zip.tmp").exists())
    }

    @Test fun defaultIterationsAreStrong() {
        assertTrue(BackupCrypto.DEFAULT_ITERATIONS >= 600_000)
        assertFailsWith<IllegalArgumentException> { BackupCrypto.encryptingStream(ByteArrayOutputStream(), pw, 1000) }
    }
}
