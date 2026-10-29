package de.millione.schulranzen.core.backup

import java.io.File
import java.io.IOException

/**
 * Hooks around backup and restore, implemented by the app (the integrator):
 * close the Room databases in [beforeRestore], so no open connection keeps
 * writing into files that are about to be swapped. [afterRestore] runs in
 * every case, also when the restore failed and the old data was put back;
 * after a successful restore the app restarts anyway.
 */
interface BackupHooks {
    /** Flush pending saves before a backup is taken. */
    suspend fun beforeBackup() {}
    /** Close every database and stop writers; the data folders are replaced next. */
    suspend fun beforeRestore() {}
    /** Called after the swap (or its rollback). */
    suspend fun afterRestore() {}

    companion object {
        /** Does nothing. */
        val None: BackupHooks = object : BackupHooks {}
    }
}

/** What one [MergeParticipant] did. */
data class MergeReport(
    val name: String,
    /** Items that were only in the backup and are now here too. */
    val added: Int = 0,
    /** Items where the backup had the newer version. */
    val updated: Int = 0,
    /** Items where this device had the same or a newer version. */
    val kept: Int = 0,
    val warnings: List<String> = emptyList(),
    /** Set when the participant failed; the others still ran. */
    val error: String? = null,
) {
    val ok: Boolean get() = error == null

    /** One German line for the UI. */
    fun summary(): String = when {
        error != null -> "$name: Fehler – $error"
        added == 0 && updated == 0 -> "$name: nichts Neues"
        else -> "$name: $added neu, $updated aktualisiert"
    }
}

/**
 * One part of the app's data that can take over newer items from a backup
 * instead of being replaced. [merge] gets the extracted backup: the folder
 * holds `files/`, `databases/` and `shared_prefs/` exactly as on the device
 * that made it. It must only read there and write into the live data.
 */
interface MergeParticipant {
    /** German, shown in the result, e.g. "Hefte". */
    val name: String
    suspend fun merge(backupDir: File): MergeReport
}

/** Result of [mergeById]. */
data class MergeResult<T>(
    /** Local items with every newer or new remote item applied, local order first. */
    val merged: List<T>,
    /** Remote items that are not here yet. */
    val added: List<T>,
    /** Remote items that replace an older local one. */
    val updated: List<T>,
    /** Number of local items that stay as they are. */
    val kept: Int,
) {
    /** Everything that has to be written: [added] + [updated]. */
    val toWrite: List<T> get() = added + updated
}

/**
 * "Newer version wins per ID": items with the same [id] are compared by
 * [updatedAt]; the remote one wins only when it is strictly newer, so a tie
 * keeps the local item and merging the same backup twice changes nothing.
 * Duplicate IDs inside one list: the newest one counts.
 */
fun <T, K> mergeById(local: List<T>, remote: List<T>, id: (T) -> K, updatedAt: (T) -> Long): MergeResult<T> {
    fun newestById(list: List<T>): LinkedHashMap<K, T> {
        val m = LinkedHashMap<K, T>()
        for (item in list) {
            val k = id(item)
            val old = m[k]
            if (old == null || updatedAt(item) > updatedAt(old)) m[k] = item
        }
        return m
    }
    val mine = newestById(local)
    val theirs = newestById(remote)
    val added = ArrayList<T>()
    val updated = ArrayList<T>()
    val result = LinkedHashMap<K, T>(mine)
    for ((k, r) in theirs) {
        val l = mine[k]
        when {
            l == null -> { added += r; result[k] = r }
            updatedAt(r) > updatedAt(l) -> { updated += r; result[k] = r }
        }
    }
    return MergeResult(result.values.toList(), added, updated, kept = mine.size - updated.size)
}

/**
 * Merges a plain folder of files, e.g. `filesDir/ink` or `filesDir/images`:
 * a file from the backup is taken over when it is missing here or the local
 * copy is older (and different). Nothing is deleted. Each file is written
 * through a temp file and renamed, so a file is always whole.
 *
 * [source] is the folder inside the extracted backup, by default
 * `files/<name of dir>`, which fits every folder directly in `filesDir`.
 */
class FolderMergeParticipant(
    private val dir: File,
    private val source: String = "files/" + dir.name,
    override val name: String = dir.name,
    /** Relative paths ("a/b.bin") that are left alone, e.g. temp files. */
    private val exclude: (String) -> Boolean = { it.endsWith(".tmp") },
) : MergeParticipant {
    init { require(ZipPaths.isSafe(source)) { "Ungültiger Pfad: $source" } }

    override suspend fun merge(backupDir: File): MergeReport {
        val from = ZipPaths.resolve(backupDir, source)
        if (!from.isDirectory) return MergeReport(name)
        var added = 0
        var updated = 0
        var kept = 0
        val warnings = ArrayList<String>()
        val base = from.canonicalFile
        from.walkTopDown().onEnter { d -> d == from || d.canonicalFile.parentFile == d.parentFile?.canonicalFile }.forEach { f ->
            if (!f.isFile) return@forEach
            val rel = f.relativeTo(base).invariantSeparatorsPath
            if (!ZipPaths.isSafe(rel) || exclude(rel)) return@forEach
            val target = ZipPaths.resolve(dir, rel)
            try {
                when {
                    !target.exists() -> { copyAtomic(f, target); added++ }
                    target.isDirectory -> warnings += "$rel: lokal ein Ordner, übersprungen"
                    isNewer(f, target) -> { copyAtomic(f, target); updated++ }
                    else -> kept++
                }
            } catch (e: IOException) {
                warnings += "$rel: ${e.message ?: "Dateifehler"}"
            }
        }
        return MergeReport(name, added, updated, kept, warnings)
    }

    /**
     * Strictly newer and different. The backup keeps each file's time to the
     * millisecond (see [BackupEntry.modified]); equal content is never copied.
     */
    private fun isNewer(remote: File, local: File): Boolean {
        if (remote.lastModified() <= local.lastModified()) return false
        return remote.length() != local.length() || Hashing.sha256(remote) != Hashing.sha256(local)
    }

    private fun copyAtomic(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".merge.tmp")
        try {
            src.copyTo(tmp, overwrite = true)
            tmp.setLastModified(src.lastModified())
            if (!tmp.renameTo(dst)) {
                // Some file systems refuse to rename over an existing file.
                dst.delete()
                if (!tmp.renameTo(dst)) throw IOException("konnte nicht ersetzt werden")
            }
        } finally {
            tmp.delete()
        }
    }
}

/**
 * Merge instead of replace: extract and verify the backup into [stage],
 * then let every participant take what it wants. A failing participant does
 * not stop the others; its report carries the error. [stage] is removed
 * afterwards in every case.
 */
object BackupMerge {
    suspend fun run(
        zip: File,
        stage: File,
        participants: List<MergeParticipant>,
        allowedRoots: Set<String> = setOf("files", "databases", "shared_prefs"),
        progress: FullBackup.Progress = FullBackup.Progress { _, _ -> },
        onParticipant: (index: Int, name: String) -> Unit = { _, _ -> },
    ): List<MergeReport> {
        if (participants.isEmpty()) throw BackupException("Zusammenführen ist in dieser Version noch nicht möglich.")
        try {
            FullBackup.extractVerified(zip, stage, allowedRoots, progress)
            return mergeExtracted(stage, participants, onParticipant)
        } finally {
            stage.deleteTree()
        }
    }

    /** Calls every participant on an already extracted backup. */
    suspend fun mergeExtracted(
        backupDir: File,
        participants: List<MergeParticipant>,
        onParticipant: (index: Int, name: String) -> Unit = { _, _ -> },
    ): List<MergeReport> = participants.mapIndexed { i, p ->
        onParticipant(i, p.name)
        try {
            p.merge(backupDir)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            MergeReport(p.name, error = (e as? BackupException)?.message ?: e.message ?: e.javaClass.simpleName)
        }
    }
}
