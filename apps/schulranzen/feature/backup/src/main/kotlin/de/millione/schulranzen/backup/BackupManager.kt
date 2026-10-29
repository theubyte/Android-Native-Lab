package de.millione.schulranzen.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import de.millione.schulranzen.core.backup.ArchiveKind
import de.millione.schulranzen.core.backup.BackupException
import de.millione.schulranzen.core.backup.BackupManifest
import de.millione.schulranzen.core.backup.BackupRoot
import de.millione.schulranzen.core.backup.FullBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the backup is doing right now. */
sealed interface BackupProgress {
    data object Idle : BackupProgress
    /** [fraction] null = unknown length. */
    data class Working(val label: String, val fraction: Float?) : BackupProgress
    data class Done(val message: String) : BackupProgress
    data class Failed(val message: String) : BackupProgress
}

/** A picked or downloaded ZIP after a first look. */
data class ArchiveInfo(val file: File, val kind: ArchiveKind, val manifest: BackupManifest?)

/**
 * Full backup of everything the app keeps: `filesDir`, `databases/` and
 * `shared_prefs/`, whoever created the files (library, books, study…).
 * Left out: SQLite `-shm` files (rebuilt on open) and the sync account
 * ([SyncAccount.PREFS]), which is bound to this device's key store and
 * stays as it is on restore.
 *
 * Restore extracts into a staging folder, proves every checksum and only
 * then swaps the folders (see [FullBackup.swap]); afterwards
 * [restartRequired] is true and the app must restart ([restartApp]),
 * because open databases and caches still point at the old files.
 */
class BackupManager(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()

    /** Called before a backup; e.g. close Room databases or flush pending saves. */
    var beforeBackup: suspend () -> Unit = {}
    /** Called before files are replaced on restore; close all databases here. */
    var beforeRestore: suspend () -> Unit = {}

    private val _progress = MutableStateFlow<BackupProgress>(BackupProgress.Idle)
    val progress: StateFlow<BackupProgress> = _progress.asStateFlow()

    private val _restartRequired = MutableStateFlow(false)
    val restartRequired: StateFlow<Boolean> = _restartRequired.asStateFlow()

    private val dataDir: File get() = if (Build.VERSION.SDK_INT >= 24) app.dataDir else app.filesDir.parentFile!!
    private val workDir: File get() = File(app.cacheDir, "backup").also { it.mkdirs() }

    private fun targets(): Map<String, File> = linkedMapOf(
        "files" to app.filesDir,
        "databases" to (app.getDatabasePath("x").parentFile ?: File(dataDir, "databases")),
        "shared_prefs" to File(dataDir, "shared_prefs"),
    )

    private fun roots(): List<BackupRoot> {
        val t = targets()
        return listOf(
            BackupRoot("files", t.getValue("files")) { it.endsWith(".tmp") },
            BackupRoot("databases", t.getValue("databases")) { it.endsWith("-shm") },
            BackupRoot("shared_prefs", t.getValue("shared_prefs")) { it == SyncAccount.PREFS + ".xml" },
        )
    }

    fun resetProgress() { if (_progress.value !is BackupProgress.Working) _progress.value = BackupProgress.Idle }

    /** File name for a backup made now, e.g. "Schulranzen-Backup-2026-10-29-1405.zip". */
    fun suggestedName(): String = "Schulranzen-Backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date()) + ".zip"

    /** Creates a full backup in the cache folder and returns it. The caller deletes it when done. */
    suspend fun createBackup(): File = run("Sichern") { createLocked() }

    private suspend fun createLocked(): File {
        working("Daten vorbereiten", null)
        beforeBackup()
        withContext(Dispatchers.IO) { checkpointDatabases() }
        return withContext(Dispatchers.IO) {
            workDir.listFiles()?.filter { it.name.startsWith("out-") }?.forEach { it.delete() }
            val out = File(workDir, "out-" + System.currentTimeMillis() + ".zip")
            try {
                out.outputStream().buffered(256 * 1024).use { o ->
                    FullBackup.write(roots(), o, info()) { done, total -> working("Sichern", frac(done, total)) }
                }
            } catch (e: Exception) {
                out.delete(); throw e
            }
            out
        }
    }

    /** Backup straight into a file chosen with the system dialog (SAF). */
    suspend fun exportTo(uri: Uri) {
        run("Sichern") {
            val zip = createLocked()
            try {
                withContext(Dispatchers.IO) {
                    working("Datei schreiben", null)
                    app.contentResolver.openOutputStream(uri, "w")?.use { o -> zip.inputStream().use { it.copyTo(o, 256 * 1024) } }
                        ?: throw BackupException("Zieldatei konnte nicht geöffnet werden.")
                }
                done("Backup gespeichert (${formatSize(zip.length())}).")
            } finally {
                zip.delete()
            }
        }
    }

    /** Copies a picked file (SAF) into the cache so it can be read as ZIP, and looks at it. */
    suspend fun open(uri: Uri): ArchiveInfo = run("Datei lesen") {
        val f = withContext(Dispatchers.IO) {
            working("Datei lesen", null)
            val target = File(workDir, "in-" + System.currentTimeMillis() + ".zip")
            app.contentResolver.openInputStream(uri)?.use { i -> target.outputStream().use { i.copyTo(it, 256 * 1024) } }
                ?: throw BackupException("Datei konnte nicht geöffnet werden.")
            target
        }
        inspectLocked(f).also { _progress.value = BackupProgress.Idle }
    }

    /** Looks at a ZIP already on disk (e.g. downloaded from the server). */
    suspend fun inspect(zip: File): ArchiveInfo = run("Prüfen") { inspectLocked(zip).also { _progress.value = BackupProgress.Idle } }

    private suspend fun inspectLocked(f: File): ArchiveInfo = withContext(Dispatchers.IO) {
        when (val kind = FullBackup.detect(f)) {
            ArchiveKind.OWN -> ArchiveInfo(f, kind, FullBackup.readManifest(f))
            ArchiveKind.V15 -> ArchiveInfo(f, kind, null)
            ArchiveKind.UNKNOWN -> {
                f.delete()
                throw BackupException("Das ist kein Schulranzen-Backup.")
            }
        }
    }

    /**
     * Restores one of our backups. Nothing on the device changes unless the
     * archive is complete and every checksum matches. Deletes [zip] after.
     */
    suspend fun restore(zip: File) {
        run("Wiederherstellen") {
            try {
                val stage = File(dataDir, "restore-stage")
                val manifest = withContext(Dispatchers.IO) {
                    FullBackup.extractVerified(zip, stage, targets().keys) { d, t -> working("Backup prüfen", frac(d, t)) }
                }
                working("Daten ersetzen", null)
                // From here on, never stop half way.
                withContext(NonCancellable) {
                    beforeRestore()
                    withContext(Dispatchers.IO) {
                        FullBackup.swap(stage, targets(), File(dataDir, "restore-old"), preserve = listOf("shared_prefs/${SyncAccount.PREFS}.xml"))
                    }
                }
                _restartRequired.value = true
                done("Wiederhergestellt: Stand vom ${formatDate(manifest.created)}. Die App startet neu.")
            } finally {
                withContext(Dispatchers.IO) {
                    zip.delete()
                    File(dataDir, "restore-stage").deleteRecursively()
                }
            }
        }
    }

    /** Removes temporary files of an archive that is not used after all. */
    fun discard(info: ArchiveInfo) { info.file.delete() }

    private fun info() = BackupManifest(
        appVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty(),
        versionCode = runCatching {
            val p = app.packageManager.getPackageInfo(app.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
        }.getOrDefault(0),
        created = System.currentTimeMillis(),
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    /**
     * Moves the WAL of every SQLite database into the main file, so the copy
     * is complete even when Room keeps the database open. The connection is
     * opened in WAL mode too, so it never changes the journal mode.
     */
    private fun checkpointDatabases() {
        val dir = targets().getValue("databases")
        dir.listFiles()?.filter { File(it.path + "-wal").isFile }?.forEach { db ->
            runCatching {
                SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { d ->
                    d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                }
            }
        }
    }

    private suspend fun <T> run(label: String, block: suspend () -> T): T {
        if (!mutex.tryLock()) throw BackupException("Es läuft schon eine Sicherung oder Wiederherstellung.")
        try {
            working(label, null)
            return block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            _progress.value = BackupProgress.Idle
            throw e
        } catch (e: Exception) {
            _progress.value = BackupProgress.Failed(messageOf(e))
            throw e
        } finally {
            mutex.unlock()
        }
    }

    private fun working(label: String, fraction: Float?) { _progress.value = BackupProgress.Working(label, fraction) }
    private fun done(message: String) { _progress.value = BackupProgress.Done(message) }
    internal fun report(p: BackupProgress) { _progress.value = p }

    companion object {
        fun messageOf(e: Throwable): String = when (e) {
            is BackupException -> e.message ?: "Fehler beim Backup."
            is de.millione.schulranzen.core.backup.SyncException -> e.message ?: "Serverfehler."
            is IOException -> if (e.message?.contains("ENOSPC") == true) "Kein Speicherplatz mehr frei." else "Dateifehler: ${e.message ?: e.javaClass.simpleName}"
            else -> e.message ?: e.javaClass.simpleName
        }

        private fun frac(done: Long, total: Long) = if (total <= 0) 1f else (done.toFloat() / total).coerceIn(0f, 1f)

        fun formatSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(Locale.GERMANY, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1L shl 20 -> String.format(Locale.GERMANY, "%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format(Locale.GERMANY, "%d KB", (bytes + 1023) / 1024)
        }

        fun formatDate(millis: Long): String = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).format(Date(millis))

        /**
         * Starts the app afresh after a restore: new task, then the process
         * ends so no open database or cache survives.
         */
        fun restartApp(activity: Activity) {
            val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                ?.let { Intent.makeRestartActivityTask(it.component) }
            if (intent != null) activity.startActivity(intent)
            activity.finishAffinity()
            Runtime.getRuntime().exit(0)
        }
    }
}
