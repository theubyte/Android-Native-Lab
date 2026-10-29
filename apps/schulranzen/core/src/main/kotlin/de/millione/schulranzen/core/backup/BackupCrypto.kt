package de.millione.schulranzen.core.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.zip.CRC32
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The password does not open this backup. */
class WrongPasswordException : BackupException("Falsches Passwort.")

/** The encrypted container is damaged or was changed after it was written. */
class TamperedBackupException(detail: String, cause: Throwable? = null) :
    BackupException("Das verschlüsselte Backup ist beschädigt oder wurde verändert ($detail).", cause)

/**
 * Header of an encrypted backup. Layout (big endian, [SIZE] bytes):
 *
 * ```
 * magic "SRZBKENC" (8) | version u8 | kdf u8 | iterations i32 | salt length u8 | salt (16)
 * | chunk size i32 | nonce prefix (7) | key check (16) | CRC32 of all bytes before (4)
 * ```
 *
 * The CRC tells a damaged header apart from a wrong password; the key check
 * (an HMAC derived from the password) tells a wrong password apart from
 * damaged data. The whole header is also the associated data of every chunk,
 * so changing any field breaks the first chunk.
 */
class CryptoHeader internal constructor(
    val version: Int,
    val iterations: Int,
    val salt: ByteArray,
    val chunkSize: Int,
    val noncePrefix: ByteArray,
    val keyCheck: ByteArray,
) {
    /** The exact header bytes, CRC included. */
    val bytes: ByteArray = encode()

    private fun encode(): ByteArray {
        val bo = ByteArrayOutputStream(SIZE)
        val o = DataOutputStream(bo)
        o.write(BackupCrypto.MAGIC)
        o.writeByte(version)
        o.writeByte(BackupCrypto.KDF_PBKDF2_SHA256)
        o.writeInt(iterations)
        o.writeByte(salt.size)
        o.write(salt)
        o.writeInt(chunkSize)
        o.write(noncePrefix)
        o.write(keyCheck)
        val crc = CRC32().apply { update(bo.toByteArray()) }.value
        o.writeInt(crc.toInt())
        return bo.toByteArray()
    }

    companion object {
        const val SALT_SIZE = 16
        const val PREFIX_SIZE = 7
        const val CHECK_SIZE = 16
        const val SIZE = 8 + 1 + 1 + 4 + 1 + SALT_SIZE + 4 + PREFIX_SIZE + CHECK_SIZE + 4

        /** Reads and checks the header; [input] is left at the first chunk. */
        fun read(input: InputStream): CryptoHeader {
            val raw = ByteArray(SIZE)
            try {
                DataInputStream(input).readFully(raw)
            } catch (e: EOFException) {
                throw TamperedBackupException("Kopf unvollständig", e)
            }
            if (!raw.copyOfRange(0, 8).contentEquals(BackupCrypto.MAGIC)) throw BackupException("Das ist kein verschlüsseltes Schulranzen-Backup.")
            val d = DataInputStream(raw.inputStream())
            d.skipBytes(8)
            val version = d.readUnsignedByte()
            val kdf = d.readUnsignedByte()
            val iterations = d.readInt()
            val saltLen = d.readUnsignedByte()
            val salt = ByteArray(SALT_SIZE).also { d.readFully(it) }
            val chunk = d.readInt()
            val prefix = ByteArray(PREFIX_SIZE).also { d.readFully(it) }
            val check = ByteArray(CHECK_SIZE).also { d.readFully(it) }
            val crc = d.readInt()
            val expected = CRC32().apply { update(raw, 0, SIZE - 4) }.value.toInt()
            if (crc != expected) throw TamperedBackupException("Prüfsumme des Kopfes")
            if (version > BackupCrypto.VERSION) throw BackupException("Das Backup stammt aus einer neueren App-Version. Bitte zuerst die App aktualisieren.")
            if (version < 1 || kdf != BackupCrypto.KDF_PBKDF2_SHA256 || saltLen != SALT_SIZE) throw TamperedBackupException("unbekanntes Verfahren")
            if (iterations !in BackupCrypto.MIN_ITERATIONS..BackupCrypto.MAX_ITERATIONS) throw TamperedBackupException("Schlüsselableitung")
            if (chunk !in BackupCrypto.MIN_CHUNK..BackupCrypto.MAX_CHUNK) throw TamperedBackupException("Blockgröße")
            return CryptoHeader(version, iterations, salt, chunk, prefix, check)
        }
    }
}

/**
 * Password encryption for backups: PBKDF2-HMAC-SHA256 → AES-256-GCM.
 *
 * GCM on Android and the JDK buffers the whole ciphertext until the tag is
 * checked, so a multi-gigabyte backup cannot be one GCM message. The data is
 * cut into chunks of [CryptoHeader.chunkSize] bytes instead, each sealed on
 * its own (segmented AEAD, like Tink's streaming AEAD): nonce = random prefix
 * ‖ chunk counter ‖ last-chunk flag, associated data = header. Reordering,
 * dropping, truncating or appending chunks breaks a tag, and nothing is ever
 * handed out before its tag has been verified.
 */
object BackupCrypto {
    /** "SRZBKENC". */
    val MAGIC = byteArrayOf(0x53, 0x52, 0x5A, 0x42, 0x4B, 0x45, 0x4E, 0x43)
    const val VERSION = 1
    const val KDF_PBKDF2_SHA256 = 1
    /** OWASP 2023 recommendation for PBKDF2-HMAC-SHA256; about 2–3 s on the Tab S7 FE. */
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_ITERATIONS = 10_000
    const val MAX_ITERATIONS = 20_000_000
    const val DEFAULT_CHUNK = 1 shl 20
    internal const val MIN_CHUNK = 1 shl 10
    internal const val MAX_CHUNK = 16 shl 20
    private const val TAG_BYTES = 16
    /** Shortest password the UI accepts. */
    const val MIN_PASSWORD = 8

    private val random = SecureRandom()

    /** True when [file] starts with the container magic. */
    fun isEncrypted(file: File): Boolean = try {
        file.inputStream().use { isEncrypted(it) }
    } catch (_: IOException) { false }

    /** Reads up to 8 bytes from [input] and compares them with the magic. */
    fun isEncrypted(input: InputStream): Boolean {
        val b = ByteArray(MAGIC.size)
        var n = 0
        while (n < b.size) {
            val r = input.read(b, n, b.size - n)
            if (r < 0) return false
            n += r
        }
        return b.contentEquals(MAGIC)
    }

    /**
     * Stream that encrypts everything written to it into [out]. Deriving the
     * key takes a moment (PBKDF2), so create it off the main thread. [close]
     * seals the last chunk and closes [out]; [EncryptingOutputStream.finish]
     * seals without closing.
     */
    fun encryptingStream(
        out: OutputStream,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        chunkSize: Int = DEFAULT_CHUNK,
    ): EncryptingOutputStream {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations" }
        require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "chunkSize" }
        val salt = ByteArray(CryptoHeader.SALT_SIZE).also(random::nextBytes)
        val prefix = ByteArray(CryptoHeader.PREFIX_SIZE).also(random::nextBytes)
        val keys = Keys.derive(password, salt, iterations)
        val header = CryptoHeader(VERSION, iterations, salt, chunkSize, prefix, keys.check)
        return EncryptingOutputStream(out, header, keys.enc)
    }

    /**
     * Stream that decrypts [input]. The header is read and the password
     * checked right here, so a wrong password throws [WrongPasswordException]
     * before any data is read; damaged data throws [TamperedBackupException]
     * while reading.
     */
    fun decryptingStream(input: InputStream, password: CharArray): InputStream {
        val header = CryptoHeader.read(input)
        val keys = Keys.derive(password, header.salt, header.iterations)
        if (!MessageDigest.isEqual(keys.check, header.keyCheck)) throw WrongPasswordException()
        return DecryptingInputStream(input, header, keys.enc)
    }

    /** Only checks the password against [file]'s header (one key derivation). */
    fun checkPassword(file: File, password: CharArray): Boolean = file.inputStream().buffered().use { i ->
        val header = CryptoHeader.read(i)
        MessageDigest.isEqual(Keys.derive(password, header.salt, header.iterations).check, header.keyCheck)
    }

    /** Encrypts [input] completely into [output]; neither is closed. */
    fun encrypt(
        input: InputStream,
        output: OutputStream,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        progress: FullBackup.Progress = FullBackup.Progress { _, _ -> },
        total: Long = -1,
    ) {
        val enc = encryptingStream(NonClosing(output), password, iterations)
        copy(input, enc, total, progress)
        enc.close()
    }

    /** Decrypts [input] completely into [output]; neither is closed. */
    fun decrypt(input: InputStream, output: OutputStream, password: CharArray, progress: FullBackup.Progress = FullBackup.Progress { _, _ -> }, total: Long = -1) {
        val dec = decryptingStream(input, password)
        copy(dec, output, total, progress)
    }

    /**
     * Decrypts [src] into [dst] through a temp file, so [dst] only ever
     * exists complete and verified. [progress] counts encrypted bytes read.
     */
    fun decryptFile(src: File, dst: File, password: CharArray, progress: FullBackup.Progress = FullBackup.Progress { _, _ -> }) {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        try {
            val total = src.length()
            val counting = CountingInputStream(src.inputStream().buffered(256 * 1024)) { progress.update(it, total) }
            counting.use { input ->
                tmp.outputStream().buffered(256 * 1024).use { out -> decrypt(input, out, password) }
            }
            dst.delete()
            if (!tmp.renameTo(dst)) throw BackupException("Entschlüsselte Datei konnte nicht abgelegt werden.")
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun copy(input: InputStream, output: OutputStream, total: Long, progress: FullBackup.Progress) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            done += n
            progress.update(done, total)
        }
    }

    internal fun nonce(prefix: ByteArray, counter: Long, last: Boolean): ByteArray {
        if (counter > 0xFFFF_FFFFL) throw BackupException("Backup ist zu groß für die Verschlüsselung.")
        val n = ByteArray(12)
        prefix.copyInto(n, 0)
        n[7] = (counter ushr 24).toByte(); n[8] = (counter ushr 16).toByte()
        n[9] = (counter ushr 8).toByte(); n[10] = counter.toByte()
        n[11] = if (last) 1 else 0
        return n
    }

    internal fun gcm(mode: Int, key: SecretKeySpec, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(aad)
        }

    internal const val TAG = TAG_BYTES

    /** Keys derived from one password: AES key and the stored check value. */
    internal class Keys(val enc: SecretKeySpec, val check: ByteArray) {
        companion object {
            fun derive(password: CharArray, salt: ByteArray, iterations: Int): Keys {
                val master = Pbkdf2.sha256(passwordBytes(password), salt, iterations, 32)
                val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(master, "HmacSHA256")) }
                val enc = mac.doFinal("schulranzen-backup/aes-gcm".toByteArray(Charsets.US_ASCII))
                val check = mac.doFinal("schulranzen-backup/key-check".toByteArray(Charsets.US_ASCII)).copyOf(CryptoHeader.CHECK_SIZE)
                master.fill(0)
                return Keys(SecretKeySpec(enc, "AES"), check).also { enc.fill(0) }
            }
        }
    }

    /**
     * Passwords as NFC-normalised UTF-8, so "ä" typed as one or as two code
     * points opens the same backup on every device.
     */
    fun passwordBytes(password: CharArray): ByteArray =
        Normalizer.normalize(String(password), Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
}

/**
 * PBKDF2 (RFC 8018) with HMAC-SHA256, written out because the platform
 * factories disagree on how they turn chars into bytes (Android's older
 * providers used only the low 8 bits). Same result on JVM and Android.
 */
object Pbkdf2 {
    fun sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(iterations >= 1 && length >= 1)
        if (password.isEmpty()) throw BackupException("Bitte ein Passwort eingeben.")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val out = ByteArray(length)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        var block = 1
        var pos = 0
        while (pos < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            mac.doFinal(u, 0)
            u.copyInto(t)
            for (i in 1 until iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in 0 until hLen) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(hLen, length - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            block++
        }
        u.fill(0); t.fill(0)
        return out
    }
}

/** See [BackupCrypto.encryptingStream]. */
class EncryptingOutputStream internal constructor(
    private val out: OutputStream,
    private val header: CryptoHeader,
    private val key: SecretKeySpec,
) : OutputStream() {
    private val buf = ByteArray(header.chunkSize)
    private var filled = 0
    private var counter = 0L
    private var finished = false
    private var headerWritten = false

    private fun writeHeader() {
        if (!headerWritten) { out.write(header.bytes); headerWritten = true }
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!finished) { "finished" }
        var o = off
        var left = len
        while (left > 0) {
            // A full chunk is only sealed once more data follows: the last
            // chunk must carry the "last" flag, even when it is full.
            if (filled == buf.size) seal(last = false)
            val n = minOf(left, buf.size - filled)
            System.arraycopy(b, o, buf, filled, n)
            filled += n; o += n; left -= n
        }
    }

    private fun seal(last: Boolean) {
        writeHeader()
        val c = BackupCrypto.gcm(Cipher.ENCRYPT_MODE, key, BackupCrypto.nonce(header.noncePrefix, counter, last), header.bytes)
        val sealed = try { c.doFinal(buf, 0, filled) } catch (e: GeneralSecurityException) { throw IOException("Verschlüsselung fehlgeschlagen.", e) }
        out.write(sealed)
        counter++
        filled = 0
    }

    /** Seals the last chunk; the stream accepts no more data. [out] stays open. */
    fun finish() {
        if (finished) return
        seal(last = true)
        finished = true
        out.flush()
    }

    override fun flush() { out.flush() }

    override fun close() {
        try { finish() } finally { out.close() }
    }
}

private class DecryptingInputStream(
    private val input: InputStream,
    private val header: CryptoHeader,
    private val key: SecretKeySpec,
) : InputStream() {
    private val sealed = ByteArray(header.chunkSize + BackupCrypto.TAG)
    private var plain = ByteArray(0)
    private var pos = 0
    private var counter = 0L
    private var done = false
    /** One byte read ahead to know whether the current chunk is the last. */
    private var ahead = -2

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= plain.size) {
            if (done) return -1
            next()
        }
        val n = minOf(len, plain.size - pos)
        System.arraycopy(plain, pos, b, off, n)
        pos += n
        return n
    }

    private fun next() {
        var n = 0
        if (ahead == -2) ahead = input.read()
        if (ahead >= 0) { sealed[0] = ahead.toByte(); n = 1 }
        while (n < sealed.size) {
            val r = input.read(sealed, n, sealed.size - n)
            if (r < 0) break
            n += r
        }
        ahead = if (n == sealed.size) input.read() else -1
        val last = ahead < 0
        if (n < BackupCrypto.TAG) throw TamperedBackupException("Datei abgeschnitten")
        val c = BackupCrypto.gcm(Cipher.DECRYPT_MODE, key, BackupCrypto.nonce(header.noncePrefix, counter, last), header.bytes)
        plain = try {
            c.doFinal(sealed, 0, n)
        } catch (e: AEADBadTagException) {
            throw TamperedBackupException("Block ${counter + 1}", e)
        } catch (e: GeneralSecurityException) {
            throw TamperedBackupException("Block ${counter + 1}", e)
        }
        pos = 0
        counter++
        if (last) done = true
    }

    override fun close() { input.close() }
}

/** Keeps [close] from reaching the wrapped stream. */
internal class NonClosing(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
    override fun close() { flush() }
}

/** Reports the number of bytes read so far. */
class CountingInputStream(private val inner: InputStream, private val onRead: (Long) -> Unit) : InputStream() {
    var count = 0L
        private set

    override fun read(): Int = inner.read().also { if (it >= 0) { count++; onRead(count) } }
    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) { count += it; onRead(count) } }
    override fun close() { inner.close() }
}
