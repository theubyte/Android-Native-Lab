package de.millione.schulranzen.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.startup.Initializer
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkManagerInitializer
import androidx.work.WorkerParameters
import de.millione.schulranzen.core.backup.AutoInterval
import de.millione.schulranzen.core.backup.BackupException
import de.millione.schulranzen.core.backup.BackupNaming
import de.millione.schulranzen.core.backup.SyncError
import de.millione.schulranzen.core.backup.SyncException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Automatic backup through WorkManager: daily or weekly, optionally only on
 * Wi-Fi and while charging, into a folder picked with the system dialog
 * and/or onto the Unraid server. Settings and the last result live in
 * [BackupSettings].
 */
object AutoBackup {
    internal const val PERIODIC = "schulranzen-auto-backup"
    internal const val ONCE = "schulranzen-auto-backup-now"

    /** Applies changed settings: reschedules (or cancels) the periodic work. */
    fun apply(context: Context) = schedule(context, ExistingPeriodicWorkPolicy.UPDATE)

    /**
     * Makes sure the periodic work exists when it should, without moving an
     * existing schedule. Runs at every app start ([AutoBackupInitializer]),
     * which also covers a restore: WorkManager's own database is not part of
     * a backup and starts empty afterwards.
     */
    fun ensureScheduled(context: Context) = schedule(context, ExistingPeriodicWorkPolicy.KEEP)

    private fun schedule(context: Context, policy: ExistingPeriodicWorkPolicy) {
        val wm = WorkManager.getInstance(context)
        val s = BackupSettings.get(context).state.value
        if (s.interval == AutoInterval.OFF || !s.hasTarget) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(s.interval.days.toLong(), TimeUnit.DAYS)
            .setConstraints(constraints(s))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, policy, req)
    }

    /** One backup now with the automatic settings (targets, encryption), still under the constraints. */
    fun runNow(context: Context) {
        val s = BackupSettings.get(context).state.value
        val req = OneTimeWorkRequestBuilder<AutoBackupWorker>().setConstraints(constraints(s, now = true)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, req)
    }

    private fun constraints(s: BackupSettings.State, now: Boolean = false): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            when {
                !s.toServer -> NetworkType.NOT_REQUIRED
                s.wifiOnly -> NetworkType.UNMETERED
                else -> NetworkType.CONNECTED
            },
        )
        // "Only while charging" is about the nightly run; a manual start should not wait for the cable.
        .setRequiresCharging(s.chargingOnly && !now)
        .setRequiresStorageNotLow(true)
        .build()

    /** What the screen shows about the work: running now, and the next planned start. */
    data class Status(val running: Boolean, val next: Long?, val waiting: Boolean)

    fun status(context: Context): Flow<Status> =
        WorkManager.getInstance(context).getWorkInfosFlow(
            androidx.work.WorkQuery.fromUniqueWorkNames(PERIODIC, ONCE),
        ).map { list ->
            val running = list.any { it.state == WorkInfo.State.RUNNING }
            val periodic = list.firstOrNull { it.periodicityInfo != null && it.state == WorkInfo.State.ENQUEUED && it.nextScheduleTimeMillis != Long.MAX_VALUE }
            val waiting = list.any { it.state == WorkInfo.State.ENQUEUED && it.periodicityInfo == null }
            Status(running, periodic?.nextScheduleTimeMillis, waiting)
        }

    /** Remembers a folder picked with OpenDocumentTree, with lasting permission. */
    fun setFolder(context: Context, tree: Uri) {
        val cr = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        cr.takePersistableUriPermission(tree, flags)
        val settings = BackupSettings.get(context)
        // Give back the permission of a folder that is no longer used.
        settings.state.value.localTree?.let { old ->
            if (old != tree.toString()) runCatching { cr.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
        val name = DocumentFile.fromTreeUri(context, tree)?.name ?: tree.lastPathSegment
        settings.setLocalFolder(tree.toString(), name)
        apply(context)
    }

    fun clearFolder(context: Context) {
        val settings = BackupSettings.get(context)
        settings.state.value.localTree?.let { old ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        settings.setLocalFolder(null, null)
        apply(context)
    }

    /**
     * Copies [file] into the picked folder under [name], then removes all
     * but the newest [keep] automatic backups there. The copy is first
     * written as "<name>.part" and renamed, so a broken run never leaves a
     * backup that looks complete; providers that cannot rename get the
     * final name directly and the file is deleted on error.
     */
    internal fun writeToFolder(context: Context, treeUri: String, file: File, name: String, keep: Int, progress: (Long, Long) -> Unit) {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        if (tree == null || !tree.exists() || !tree.canWrite()) throw BackupException("Der Backup-Ordner ist nicht mehr erreichbar. Bitte den Ordner neu wählen.")
        val mime = if (name.endsWith(BackupNaming.PLAIN_EXT)) "application/zip" else "application/octet-stream"
        tree.findFile(name)?.delete()
        val doc = tree.createFile("application/octet-stream", "$name.part")
            ?: throw BackupException("Im Backup-Ordner konnte keine Datei angelegt werden.")
        try {
            val total = file.length()
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                file.inputStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            } ?: throw BackupException("Datei im Backup-Ordner konnte nicht geöffnet werden.")
            val renamed = runCatching { DocumentsContract.renameDocument(context.contentResolver, doc.uri, name) }.getOrNull()
            if (renamed == null) {
                // No rename support: write the final name directly instead.
                doc.delete()
                val direct = tree.createFile(mime, name) ?: throw BackupException("Im Backup-Ordner konnte keine Datei angelegt werden.")
                try {
                    context.contentResolver.openOutputStream(direct.uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
                        ?: throw BackupException("Datei im Backup-Ordner konnte nicht geöffnet werden.")
                } catch (e: Throwable) {
                    direct.delete(); throw e
                }
            }
        } catch (e: Throwable) {
            doc.delete()
            throw e
        }
        val children = tree.listFiles()
        val drop = BackupNaming.toDelete(children.mapNotNull { it.name }, keep).toSet()
        children.filter { it.name in drop }.forEach { it.delete() }
        // Leftovers of runs that were killed half way.
        children.filter { it.name?.let { n -> n.startsWith(BackupNaming.AUTO_PREFIX) && n.endsWith(".part") } == true && it.name != "$name.part" }
            .forEach { it.delete() }
    }
}

/**
 * One automatic backup run. A network error on the server leg is retried
 * by WorkManager (with backoff) up to three times; everything else is
 * recorded as the last error and shown on the backup screen.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = BackupSettings.get(ctx)
        val s = settings.state.value
        if (!s.hasTarget) {
            settings.recordRun(null, 0, "", "Automatisches Backup: kein Ziel gewählt (Ordner oder Server).")
            return Result.failure()
        }
        val password = if (s.encrypt) {
            settings.password() ?: run {
                settings.recordRun(null, 0, "", "Automatisches Backup: Das Passwort für die Verschlüsselung ist nicht gemerkt. Bitte unter „Verschlüsselung“ das Passwort merken lassen.")
                return Result.failure()
            }
        } else null
        val manager = BackupManager(ctx)
        val done = ArrayList<String>()
        val errors = ArrayList<String>()
        var size = 0L
        var retry = false
        try {
            manager.runExclusive("Automatisches Backup") {
                val file = manager.createLocked(password, "auto-")
                try {
                    size = file.length()
                    val name = BackupNaming.name(System.currentTimeMillis(), encrypted = password != null, auto = true)
                    s.localTree?.let { tree ->
                        try {
                            withContext(Dispatchers.IO) { AutoBackup.writeToFolder(ctx, tree, file, name, s.keep) { _, _ -> } }
                            done += "Ordner"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            errors += "Ordner: " + BackupManager.messageOf(e)
                        }
                    }
                    if (s.toServer) {
                        try {
                            SyncAccount(ctx).upload(file, name.replace(BackupNaming.ENCRYPTED_EXT, ".zip")) { _, _ -> }
                            done += "Server"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (e is SyncException && e.error == SyncError.UNREACHABLE) retry = true
                            errors += "Server: " + BackupManager.messageOf(e)
                        }
                    }
                } finally {
                    withContext(Dispatchers.IO) { file.delete() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is BackupException && e.message?.startsWith("Es läuft schon") == true) return Result.retry()
            settings.recordRun(null, 0, "", "Automatisches Backup fehlgeschlagen: " + BackupManager.messageOf(e))
            return Result.failure()
        }
        if (retry && errors.size == 1 && done.isEmpty() && runAttemptCount < 3) return Result.retry()
        settings.recordRun(
            okAt = if (done.isNotEmpty()) System.currentTimeMillis() else null,
            size = size,
            targets = done.joinToString(" + "),
            error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
        return if (done.isNotEmpty()) Result.success() else Result.failure()
    }
}

/**
 * Re-creates the periodic work at app start when it is missing (see
 * [AutoBackup.ensureScheduled]). Registered through androidx.startup in
 * this module's manifest, so the app needs no code for it.
 */
class AutoBackupInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        runCatching { AutoBackup.ensureScheduled(context) }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(WorkManagerInitializer::class.java)
}
