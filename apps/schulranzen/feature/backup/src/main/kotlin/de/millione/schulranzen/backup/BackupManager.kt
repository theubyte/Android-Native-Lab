package de.millione.schulranzen.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import de.millione.schulranzen.core.backup.ArchiveKind
import de.millione.schulranzen.core.backup.BackupCrypto
import de.millione.schulranzen.core.backup.BackupException
import de.millione.schulranzen.core.backup.BackupHooks
import de.millione.schulranzen.core.backup.BackupManifest
import de.millione.schulranzen.core.backup.BackupMerge
import de.millione.schulranzen.core.backup.BackupNaming
import de.millione.schulranzen.core.backup.BackupRoot
import de.millione.schulranzen.core.backup.FullBackup
import de.millione.schulranzen.core.backup.MergeParticipant
import de.millione.schulranzen.core.backup.MergeReport
import de.millione.schulranzen.core.backup.SyncException
import de.millione.schulranzen.core.backup.TamperedBackupException
import de.millione.schulranzen.core.backup.WrongPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the backup is doing right now. */
sealed interface BackupProgress {
    data object Idle : BackupProgress

    /**
     * [fraction] null = unknown length. [done]/[total] in bytes (-1 =
     * unknown) for the size display. [cancellable] shows "Abbrechen".
     */
    data class Working(
        val label: String,
        val fraction: Float?,
        val done: Long = -1,
        val total: Long = -1,
        val cancellable: Boolean = false,
    ) : BackupProgress

    data class Done(val message: String) : BackupProgress
    data class Failed(val message: String) : BackupProgress
}

/** A picked or downloaded file after a first look. [manifest] only for [ArchiveKind.OWN]. */
data class ArchiveInfo(val file: File, val kind: ArchiveKind, val manifest: BackupManifest?)

/**
 * Full backup of everything the app keeps: `filesDir`, `databases/` and
 * `shared_prefs/`, whoever created the files (library, books, study…).
 * Left out: SQLite `-shm` files (rebuilt on open), WorkManager's own
 * database and the device-bound prefs ([SyncAccount.PREFS],
 * [BackupSettings.PREFS]), which stay as they are on restore.
 *
 * Backups are optionally encrypted ([BackupCrypto]); the ZIP is then
 * written straight into the encrypting stream, so no plain copy touches
 * the disk while saving.
 *
 * Restore extracts into a staging folder, proves every checksum and only
 * then swaps the folders (see [FullBackup.swap]), wrapped in
 * [BackupHooks.beforeRestore] / [BackupHooks.afterRestore]; afterwards
 * [restartRequired] is true and the app must restart ([restartApp]).
 */
class BackupManager(context: Context, var hooks: BackupHooks = BackupHooks.None) {
    private val app = context.applicationContext

    private val _progress = MutableStateFlow<BackupProgress>(BackupProgress.Idle)
    val progress: StateFlow<BackupProgress> = _progress.asStateFlow()

    private val _restartRequired = MutableStateFlow(false)
    val restartRequired: StateFlow<Boolean> = _restartRequired.asStateFlow()

    /** Job of the running operation; progress callbacks stop when it is cancelled. */
    @Volatile private var activeJob: Job? = null

    private val dataDir: File get() = if (Build.VERSION.SDK_INT >= 24) app.dataDir else app.filesDir.parentFile!!
    internal val workDir: File get() = File(app.cacheDir, "backup").also { it.mkdirs() }

    private fun targets(): Map<String, File> = linkedMapOf(
        "files" to app.filesDir,
        "databases" to (app.getDatabasePath("x").parentFile ?: File(dataDir, "databases")),
        "shared_prefs" to File(dataDir, "shared_prefs"),
    )

    private fun roots(): List<BackupRoot> {
        val t = targets()
        return listOf(
            BackupRoot("files", t.getValue("files")) { it.endsWith(".tmp") },
            BackupRoot("databases", t.getValue("databases")) { it.endsWith("-shm") || it.startsWith(WORK_DB) },
            BackupRoot("shared_prefs", t.getValue("shared_prefs")) { it in devicePrefs },
        )
    }

    fun resetProgress() { if (_progress.value !is BackupProgress.Working) _progress.value = BackupProgress.Idle }

    /** File name for a backup made now, e.g. "Schulranzen-Backup-2026-10-29-1405.zip" (".srbk" encrypted). */
    fun suggestedName(encrypted: Boolean = false): String = BackupNaming.name(System.currentTimeMillis(), encrypted, auto = false)

    /**
     * Creates a full backup in the cache folder and returns it; encrypted
     * when [password] is given. The caller deletes the file when done.
     */
    suspend fun createBackup(password: CharArray? = null): File = run("Sichern") { createLocked(password, "out-") }

    /** For callers that already hold the lock ([runExclusive]). */
    internal suspend fun createLocked(password: CharArray?, prefix: String): File {
        working("Daten vorbereiten", null)
        hooks.beforeBackup()
        withContext(Dispatchers.IO) { checkpointDatabases() }
        return withContext(Dispatchers.IO) {
            workDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
            val out = File(workDir, prefix + System.currentTimeMillis() + if (password != null) BackupNaming.ENCRYPTED_EXT else BackupNaming.PLAIN_EXT)
            try {
                out.outputStream().buffered(256 * 1024).use { file ->
                    val sink: OutputStream = if (password != null) {
                        working("Schlüssel aus dem Passwort berechnen", null)
                        BackupCrypto.encryptingStream(file, password)
                    } else file
                    val label = if (password != null) "Sichern und verschlüsseln" else "Sichern"
                    FullBackup.write(roots(), sink, info()) { done, total -> working(label, frac(done, total), done, total, cancellable = true) }
                    sink.close()
                }
            } catch (e: Throwable) {
                out.delete(); throw e
            }
            out
        }
    }

    /** Backup straight into a file chosen with the system dialog (SAF). */
    suspend fun exportTo(uri: Uri, password: CharArray? = null) {
        run("Sichern") {
            var zip: File? = null
            try {
                val made = createLocked(password, "out-").also { zip = it }
                withContext(Dispatchers.IO) {
                    val total = made.length()
                    app.contentResolver.openOutputStream(uri, "wt")?.use { o -> copy(made, o, "Datei schreiben", total) }
                        ?: throw BackupException("Zieldatei konnte nicht geöffnet werden.")
                }
                done("Backup gespeichert (${formatSize(made.length())}${if (password != null) ", verschlüsselt" else ""}).")
            } catch (e: Throwable) {
                // The system dialog already created the file: leave no empty or half written one behind.
                withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) } }
                throw e
            } finally {
                zip?.delete()
            }
        }
    }

    private fun copy(src: File, out: OutputStream, label: String, total: Long) {
        src.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                working(label, frac(done, total), done, total, cancellable = true)
            }
        }
    }

    /** Copies a picked file (SAF) into the cache so it can be read, and looks at it. */
    suspend fun open(uri: Uri): ArchiveInfo = run("Datei lesen") {
        val f = withContext(Dispatchers.IO) {
            val target = File(workDir, "in-" + System.currentTimeMillis())
            val total = runCatching { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
            try {
                app.contentResolver.openInputStream(uri)?.use { i ->
                    target.outputStream().use { o ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        while (true) {
                            val n = i.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            working("Datei lesen", if (total > 0) frac(done, total) else null, done, total, cancellable = true)
                        }
                    }
                } ?: throw BackupException("Datei konnte nicht geöffnet werden.")
            } catch (e: Throwable) {
                target.delete(); throw e
            }
            target
        }
        inspectLocked(f).also { _progress.value = BackupProgress.Idle }
    }

    /** Looks at a file already on disk (e.g. downloaded from the server). */
    suspend fun inspect(file: File): ArchiveInfo = run("Prüfen") { inspectLocked(file).also { _progress.value = BackupProgress.Idle } }

    private suspend fun inspectLocked(f: File): ArchiveInfo = withContext(Dispatchers.IO) {
        when (val kind = FullBackup.detect(f)) {
            ArchiveKind.OWN -> ArchiveInfo(f, kind, FullBackup.readManifest(f))
            ArchiveKind.V15, ArchiveKind.ENCRYPTED -> ArchiveInfo(f, kind, null)
            ArchiveKind.UNKNOWN -> {
                f.delete()
                throw BackupException("Das ist kein Schulranzen-Backup.")
            }
        }
    }

    /**
     * Decrypts an [ArchiveKind.ENCRYPTED] file and looks at the result.
     * A wrong password throws [WrongPasswordException] and keeps [info]
     * so the user can try again; on success the encrypted copy is deleted.
     */
    suspend fun unlock(info: ArchiveInfo, password: CharArray): ArchiveInfo = run("Entschlüsseln") {
        val plain = File(workDir, "dec-" + System.currentTimeMillis() + ".zip")
        withContext(Dispatchers.IO) {
            working("Passwort prüfen", null)
            val total = info.file.length()
            BackupCrypto.decryptFile(info.file, plain, password) { d, _ -> working("Entschlüsseln", frac(d, total), d, total, cancellable = true) }
            info.file.delete()
        }
        val result = try { inspectLocked(plain) } catch (e: Throwable) { plain.delete(); throw e }
        if (result.kind == ArchiveKind.ENCRYPTED) {
            plain.delete()
            throw BackupException("Das Backup ist doppelt verschlüsselt und kann nicht gelesen werden.")
        }
        _progress.value = BackupProgress.Idle
        result
    }

    /** Tries the remembered password; null when there is none or it does not fit. */
    suspend fun unlockWithSaved(info: ArchiveInfo): ArchiveInfo? {
        val pw = BackupSettings.get(app).password() ?: return null
        val fits = withContext(Dispatchers.Default) { runCatching { BackupCrypto.checkPassword(info.file, pw) }.getOrDefault(false) }
        return if (fits) unlock(info, pw) else null
    }

    /**
     * Restores one of our backups. Nothing on the device changes unless the
     * archive is complete and every checksum matches. Calls the hooks around
     * the swap. Deletes [zip] after.
     */
    suspend fun restore(zip: File) {
        run("Wiederherstellen") {
            try {
                val stage = File(dataDir, "restore-stage")
                val manifest = withContext(Dispatchers.IO) {
                    FullBackup.extractVerified(zip, stage, targets().keys) { d, t -> working("Backup prüfen", frac(d, t), d, t, cancellable = true) }
                }
                working("Daten ersetzen", null)
                // From here on, never stop half way.
                withContext(NonCancellable) {
                    hooks.beforeRestore()
                    try {
                        withContext(Dispatchers.IO) {
                            FullBackup.swap(stage, targets(), File(dataDir, "restore-old"), preserve = devicePrefs.map { "shared_prefs/$it" })
                        }
                    } finally {
                        hooks.afterRestore()
                    }
                }
                _restartRequired.value = true
                done("Wiederhergestellt: Stand vom ${formatDate(manifest.created)}.")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    zip.delete()
                    File(dataDir, "restore-stage").deleteRecursively()
                }
            }
        }
    }

    /**
     * Merges a backup into the live data instead of replacing it: the
     * archive is verified and extracted to a temp folder, then every
     * participant takes over what is newer. Deletes [zip] after.
     */
    suspend fun merge(zip: File, participants: List<MergeParticipant>): List<MergeReport> = run("Zusammenführen") {
        val stage = File(workDir, "merge-stage")
        try {
            val reports = withContext(Dispatchers.IO) {
                BackupMerge.run(
                    zip, stage, participants, targets().keys,
                    progress = { d, t -> working("Backup prüfen", frac(d, t), d, t, cancellable = true) },
                    onParticipant = { i, name -> working("Zusammenführen: $name", i.toFloat() / participants.size) },
                )
            }
            val failed = reports.count { !it.ok }
            done(if (failed == 0) "Zusammengeführt." else "Zusammengeführt, aber $failed Bereich(e) mit Fehler.")
            reports
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { zip.delete(); stage.deleteRecursively() }
        }
    }

    /** Runs [block] under the process-wide backup lock with progress and error reporting. */
    internal suspend fun <T> runExclusive(label: String, block: suspend () -> T): T = run(label, block)

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
        dir.listFiles()?.filter { File(it.path + "-wal").isFile && !it.name.startsWith(WORK_DB) }?.forEach { db ->
            runCatching {
                SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { d ->
                    d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                }
            }
        }
    }

    private suspend fun <T> run(label: String, block: suspend () -> T): T {
        if (!lock.tryLock()) throw BackupException("Es läuft schon eine Sicherung oder Wiederherstellung.")
        activeJob = currentCoroutineContext()[Job]
        try {
            working(label, null)
            return block()
        } catch (e: CancellationException) {
            _progress.value = BackupProgress.Done("Abgebrochen.")
            throw e
        } catch (e: Exception) {
            _progress.value = BackupProgress.Failed(messageOf(e))
            throw e
        } finally {
            activeJob = null
            lock.unlock()
        }
    }

    /** Also the cancellation point of every long loop: throws once the job is cancelled. */
    private fun working(label: String, fraction: Float?, done: Long = -1, total: Long = -1, cancellable: Boolean = false) {
        activeJob?.ensureActive()
        _progress.value = BackupProgress.Working(label, fraction, done, total, cancellable)
    }

    private fun done(message: String) { _progress.value = BackupProgress.Done(message) }
    internal fun report(p: BackupProgress) { _progress.value = p }

    companion object {
        /** One backup or restore at a time in the whole process, screen and worker alike. */
        private val lock = Mutex()

        /** WorkManager's database: device state, never part of a backup. */
        private const val WORK_DB = "androidx.work.workdb"

        /** Prefs bound to this device (Keystore keys, folder permissions). */
        private val devicePrefs = listOf(SyncAccount.PREFS + ".xml", BackupSettings.PREFS + ".xml")

        fun messageOf(e: Throwable): String = when (e) {
            is WrongPasswordException -> "Falsches Passwort. Bitte noch einmal versuchen."
            is BackupException -> e.message ?: "Fehler beim Backup."
            is SyncException -> e.message ?: "Serverfehler."
            is SecurityException -> "Kein Zugriff auf die Datei oder den Ordner. Bitte neu auswählen."
            is java.io.FileNotFoundException -> "Datei oder Ordner nicht gefunden. Bitte neu auswählen."
            is IOException -> when {
                e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true -> "Kein Speicherplatz mehr frei."
                else -> "Dateifehler: ${e.message ?: e.javaClass.simpleName}"
            }
            is OutOfMemoryError -> "Zu wenig Arbeitsspeicher."
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
         * Starts the app afresh after a restore: the launch intent in a new
         * task, then the process ends, so every database and cache is opened
         * again from the restored files.
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
