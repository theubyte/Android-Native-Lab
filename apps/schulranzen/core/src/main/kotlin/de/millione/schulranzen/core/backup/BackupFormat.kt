package de.millione.schulranzen.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Errors with a German message that can go straight into the UI. */
open class BackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The archive is a full backup of the old app (V15): restore does not apply, import does. */
class V15BackupException : BackupException("Das ist ein Backup der alten App (V15). Bitte über „Aus alter App übernehmen“ einlesen.")

/**
 * Table of contents of a full backup, stored as [FILE_NAME] inside the ZIP.
 * Every data file is listed with size and SHA-256, so a restore can prove the
 * archive is complete before it touches the live data.
 */
@Serializable
data class BackupManifest(
    val format: Int = FORMAT,
    val app: String = APP_ID,
    val appVersion: String = "",
    val versionCode: Long = 0,
    /** Epoch millis. */
    val created: Long = 0,
    val device: String = "",
    val entries: List<BackupEntry> = emptyList(),
) {
    val totalSize: Long get() = entries.sumOf { it.size }

    companion object {
        const val FORMAT = 1
        const val APP_ID = "schulranzen-neu"
        const val FILE_NAME = "schulranzen-backup.json"
        /** Name of the old app's manifest (V15 BackupManager). */
        const val V15_FILE_NAME = "backup-manifest.json"

        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    }
}

@Serializable
data class BackupEntry(val path: String, val size: Long, val sha256: String)

/** Paths inside an archive: only plain relative names, never "..", absolute paths or backslashes. */
object ZipPaths {
    private val drive = Regex("^[A-Za-z]:")

    fun isSafe(name: String): Boolean {
        if (name.isEmpty() || name.length > 1024) return false
        if (name.startsWith("/") || name.contains('\\') || name.contains('\u0000') || drive.containsMatchIn(name)) return false
        val parts = name.trimEnd('/').split('/')
        return parts.none { it.isEmpty() || it == "." || it == ".." }
    }

    /** [name] below [root]; throws when it would leave the folder (zip slip). */
    fun resolve(root: File, name: String): File {
        if (!isSafe(name)) throw BackupException("Unsicherer Pfad im Backup: $name")
        val base = root.canonicalFile
        val f = File(base, name)
        val canon = f.canonicalFile
        if (canon != base && !canon.path.startsWith(base.path + File.separator)) throw BackupException("Unsicherer Pfad im Backup: $name")
        return f
    }
}

object Hashing {
    fun sha256(file: File): String = file.inputStream().buffered().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
}

/** Deletes a file or folder tree; true when nothing is left. */
internal fun File.deleteTree(): Boolean = !exists() || deleteRecursively()
