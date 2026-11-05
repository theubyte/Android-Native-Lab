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
import de.millione.schulranzen.core.backup.BackupErrors
import de.millione.schulranzen.core.backup.BackupException
import de.millione.schulranzen.core.backup.BackupHooks
import de.millione.schulranzen.core.backup.BackupManifest
import de.millione.schulranzen.core.backup.BackupMerge
import de.millione.schulranzen.core.backup.BackupNaming
import de.millione.schulranzen.core.backup.BackupRoot
import de.millione.schulranzen.core.backup.FullBackup
import de.millione.schulranzen.core.backup.MergeParticipant
import de.millione.schulranzen.core.backup.HashCache
import de.millione.schulranzen.core.backup.MergeReport
import de.millione.schulranzen.core.backup.Space
import de.millione.schulranzen.core.backup.SyncEngine
import de.millione.schulranzen.core.backup.SyncError
import de.millione.schulranzen.core.backup.SyncPasswordException
import de.millione.schulranzen.core.backup.SyncResult
import de.millione.schulranzen.core.backup.SyncText
import de.millione.schulranzen.core.backup.SyncException
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

    private val dataDir: File get() = dataDirOf(app)
    internal val workDir: File get() = File(app.cacheDir, "backup").also { it.mkdirs() }

    private fun targets(): Map<String, File> = targetsOf(app)

    private fun roots(): List<BackupRoot> {
        val t = targets()
        return listOf(
            BackupRoot("files", t.getValue("files")) { it.endsWith(".tmp") },
            BackupRoot("databases", t.getValue("databases")) { it.endsWith("-shm") || it.startsWith(WORK_DB) },
            BackupRoot("shared_prefs", t.getValue("shared_prefs")) { it in devicePrefs },
        )
    }

    fun resetProgress() { if (_progress.value !is BackupProgress.Working) _progress.value = BackupProgress.Idle }

    /**
     * What a sync carries: only what a participant can merge. The handwriting
     * index for instance is rebuilt on each device and full backups keep it anyway.
     */
    private fun syncRoots(): List<BackupRoot> {
        val t = targets()
        return listOf(
            BackupRoot("files", t.getValue("files")) { rel ->
                // Copies of damaged pages stay where they were found; full backups do carry them.
                rel.substringBefore('/') !in SYNC_FOLDERS || rel.endsWith(".tmp") || rel.contains(".damaged")
            },
            BackupRoot("databases", t.getValue("databases")) { it !in SYNC_DATABASES },
        )
    }

    private val syncState: File get() = File(app.noBackupFilesDir, "sync").also { it.mkdirs() }

    /**
     * "Jetzt abgleichen": fetches the server's state, merges it and sends the
     * result. What the steps guarantee is written at [SyncEngine]. [password] opens
     * and writes an encrypted state; without the right one the run stops with
     * [SyncPasswordException] before anything happened.
     */
    suspend fun syncNow(account: SyncAccount, participants: List<MergeParticipant>, password: CharArray?): SyncResult {
        val settings = BackupSettings.get(app)
        try {
            val result = run("Abgleichen") {
                withContext(Dispatchers.IO) {
                    SyncEngine(
                        account.remote(), syncRoots(), File(workDir, "sync"), File(syncState, "engine.json"), HashCache(File(syncState, "file-hashes.json")),
                        participants, password, settings.state.value.encrypt, "${Build.MANUFACTURER} ${Build.MODEL}",
                        prepare = {
                            hooks.beforeBackup()
                            checkpointDatabases()
                        },
                        exclusive = { block -> EditGate.exclusive { block() } },
                        progress = { step, d, t -> working(step, if (t > 0) frac(d, t) else null, d, t, cancellable = true) },
                    ).run()
                }.also { r -> done(r.headline()) }
            }
            settings.recordSync(result.headline(), null)
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            if (e.error != SyncError.UNREACHABLE) {
                settings.recordSync(null, messageOf(e))
                throw e
            }
            // The worst that can happen in a sync must not sound like it.
            val text = BackupErrors.plain(e) + " Es ist nichts verloren, der nächste Abgleich macht dort weiter."
            settings.recordSync(null, text)
            _progress.value = BackupProgress.Failed(text)
            throw SyncException(e.error, text, e)
        } catch (e: Exception) {
            settings.recordSync(null, messageOf(e))
            throw e
        }
    }

    /**
     * A backup of this device as a file another device can merge, handed to the
     * share sheet. Returns the file; it is replaced by the next one.
     */
    suspend fun createShareFile(password: CharArray?): File {
        val made = createBackup(password)
        return withContext(Dispatchers.IO) {
            val dir = File(workDir, SHARE_DIR).also { it.deleteRecursively(); it.mkdirs() }
            val target = File(dir, BackupNaming.name(System.currentTimeMillis(), password != null, auto = false).replace(BackupNaming.MANUAL_PREFIX, "Schulranzen-Abgleich-"))
            if (!made.renameTo(target)) {
                made.copyTo(target, overwrite = true)
                made.delete()
            }
            target
        }
    }

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
            Space.require(workDir, total, "Das Entschlüsseln")
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
                        hooks.beforeSwap(stage)
                        withContext(Dispatchers.IO) {
                            // What the last sync knew describes data that is about to go.
                            File(syncState, "engine.json").delete()
                            FullBackup.swap(stage, targets(), trashOf(app), preserve = devicePrefs.map { "shared_prefs/$it" })
                        }
                        hooks.afterSwap()
                    } catch (e: Exception) {
                        // The databases are closed: only a restart brings the app back cleanly.
                        _restartRequired.value = true
                        throw e
                    } finally {
                        hooks.afterRestore()
                    }
                }
                done("Wiederhergestellt: Stand vom ${formatDate(manifest.created)}.")
                _restartRequired.value = true
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
                EditGate.exclusive {
                    BackupMerge.run(
                        zip, stage, participants, targets().keys,
                        progress = { d, t -> working("Backup prüfen", frac(d, t), d, t, cancellable = true) },
                        onParticipant = { i, name -> working("Zusammenführen: $name", i.toFloat() / participants.size) },
                    )
                }
            }
            val failed = reports.count { !it.ok }
            done(if (failed == 0) "Zusammengeführt: " + SyncText.headline(reports) else "Zusammengeführt, aber $failed Bereich(e) mit Fehler.")
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
            withContext(Dispatchers.IO) { sweepOld() }
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

    /**
     * Copies a killed run left in the cache, among them decrypted backups. Not
     * the newest: a file can wait in an open dialog, and a broken sync resumes.
     */
    private fun sweepOld() {
        val limit = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        workDir.listFiles()?.filter { f -> f.lastModified() < limit && TEMP_PREFIXES.any { f.name.startsWith(it) } }?.forEach { it.deleteRecursively() }
        File(workDir, "sync").takeIf { it.lastModified() in 1 until limit - 3 * 24 * 60 * 60 * 1000L }?.deleteRecursively()
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
        // study_assistant: the Claude key is Keystore-encrypted and cannot be read on another device.
        private val devicePrefs = listOf(SyncAccount.PREFS + ".xml", BackupSettings.PREFS + ".xml", "study_assistant.xml")

        fun messageOf(e: Throwable): String = BackupErrors.message(e)

        private const val SHARE_DIR = "share"
        private val SYNC_FOLDERS = setOf("ink", "images", "books", "rechnen", "study", "sync")
        private val SYNC_DATABASES = setOf("ranzen.db", "ranzen.db-wal", "study.db", "study.db-wal")
        private val TEMP_PREFIXES = listOf("in-", "dec-", "out-", "auto-", "dl-", "merge-stage", SHARE_DIR)

        private fun dataDirOf(app: Context): File = if (Build.VERSION.SDK_INT >= 24) app.dataDir else app.filesDir.parentFile!!

        private fun targetsOf(app: Context): Map<String, File> = linkedMapOf(
            "files" to app.filesDir,
            "databases" to (app.getDatabasePath("x").parentFile ?: File(dataDirOf(app), "databases")),
            "shared_prefs" to File(dataDirOf(app), "shared_prefs"),
        )

        private fun trashOf(app: Context) = File(dataDirOf(app), "restore-old")

        /**
         * Undoes a restore that was killed half way, so the old data is back
         * whole. Runs at every app start, before anything opens the data.
         */
        fun recoverInterrupted(context: Context): Boolean {
            val app = context.applicationContext
            val restored = FullBackup.recover(trashOf(app), targetsOf(app))
            File(dataDirOf(app), "restore-stage").deleteRecursively()
            return restored
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
