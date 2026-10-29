package de.millione.schulranzen.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import de.millione.schulranzen.core.backup.BackupHooks
import de.millione.schulranzen.core.backup.FolderMergeParticipant
import de.millione.schulranzen.core.backup.MergeParticipant
import de.millione.schulranzen.core.backup.MergeReport
import de.millione.schulranzen.core.backup.mergeById
import de.millione.schulranzen.study.closeStudyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Closes the databases before a restore swaps the data folders (the app restarts afterwards). */
fun appBackupHooks(context: Context, flush: () -> Unit): BackupHooks = object : BackupHooks {
    override suspend fun beforeBackup() = flush()
    override suspend fun beforeRestore() {
        flush()
        runCatching { RanzenDatabase.get(context).close() }
        closeStudyDatabase(context)
    }
}

/**
 * What "Zusammenführen" merges: plain folders (newer file wins), the
 * notebooks by their change time and the study data by meaning (see
 * [de.millione.schulranzen.study.StudyMergeParticipant]).
 */
fun appMergeParticipants(context: Context): List<MergeParticipant> {
    val files = context.filesDir
    return listOf(
        FolderMergeParticipant(File(files, "images"), name = "Bilder"),
        FolderMergeParticipant(File(files, "books"), name = "Bücher"),
        FolderMergeParticipant(File(files, "rechnen"), name = "SHK-Werkzeuge"),
        NotebookMergeParticipant(context),
        de.millione.schulranzen.study.StudyMergeParticipant(context),
    )
}

/** Notebooks from the backup that are new or newer than here, with their pages and ink. */
class NotebookMergeParticipant(private val context: Context) : MergeParticipant {
    override val name = "Hefte"

    override suspend fun merge(backupDir: File): MergeReport = withContext(Dispatchers.IO) {
        val dbFile = File(backupDir, "databases/ranzen.db")
        if (!dbFile.exists()) return@withContext MergeReport(name, warnings = listOf("Keine Hefte im Backup"))
        val dao = RanzenDatabase.get(context).library()
        val remote = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val subjects = HashMap<Long, String>()
            remote.rawQuery("SELECT id, name FROM subject", null).use { c -> while (c.moveToNext()) subjects[c.getLong(0)] = c.getString(1) }
            val notebooks = ArrayList<Notebook>()
            remote.rawQuery("SELECT id, title, subjectId, paper, createdAt, updatedAt, lastPage, pageCount, trashedAt FROM notebook", null).use { c ->
                while (c.moveToNext()) notebooks += Notebook(
                    c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2), c.getString(3),
                    c.getLong(4), c.getLong(5), c.getInt(6), c.getInt(7), if (c.isNull(8)) null else c.getLong(8),
                )
            }
            val result = mergeById(dao.allNotebooks(), notebooks, { it.id }, { it.updatedAt })
            val localSubjects = dao.allSubjects().associateBy { it.name.lowercase() }.toMutableMap()
            val ink = File(context.filesDir, "ink").also { it.mkdirs() }
            val images = File(context.filesDir, "images").also { it.mkdirs() }
            for (nb in result.added + result.updated) {
                val pages = ArrayList<Page>()
                remote.rawQuery("SELECT id, notebookId, position, paper, bookmarked, locked, background FROM page WHERE notebookId = ? ORDER BY position", arrayOf(nb.id)).use { c ->
                    while (c.moveToNext()) pages += Page(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getInt(4) != 0, c.getInt(5) != 0, if (c.isNull(6)) null else c.getString(6))
                }
                // Subjects are matched by name; a missing one is created here.
                val subjectId = nb.subjectId?.let { subjects[it] }?.let { sName ->
                    localSubjects[sName.lowercase()]?.id ?: dao.insertSubject(Subject(name = sName, colorIndex = localSubjects.size % 8, position = localSubjects.size))
                        .also { id -> localSubjects[sName.lowercase()] = Subject(id, sName, 0, 0) }
                }
                for (p in pages) {
                    copyIn(File(backupDir, "files/ink/${p.id}.rnz"), File(ink, "${p.id}.rnz"))
                    copyIn(File(backupDir, "files/ink/${p.id}.items.json"), File(ink, "${p.id}.items.json"))
                    p.background?.let { copyIn(File(backupDir, "files/images/$it"), File(images, it)) }
                }
                dao.replaceNotebook(nb.copy(subjectId = subjectId), pages)
            }
            MergeReport(name, added = result.added.size, updated = result.updated.size, kept = result.kept)
        } finally {
            remote.close()
        }
    }

    private fun copyIn(src: File, dst: File) {
        if (!src.exists()) return
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        src.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
    }
}
