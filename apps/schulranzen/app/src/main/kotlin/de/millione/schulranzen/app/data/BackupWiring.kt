package de.millione.schulranzen.app.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import de.millione.schulranzen.backup.AutoSync
import de.millione.schulranzen.core.backup.BackupHooks
import de.millione.schulranzen.core.backup.FolderMergeParticipant
import de.millione.schulranzen.core.backup.HashCache
import de.millione.schulranzen.core.backup.MergeParticipant
import de.millione.schulranzen.core.backup.NotebookState
import de.millione.schulranzen.core.backup.NotebookStore
import de.millione.schulranzen.core.backup.NotebookSyncParticipant
import de.millione.schulranzen.core.backup.PageState
import de.millione.schulranzen.study.closeStudyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Closes the databases before a restore swaps the data folders (the app
 * restarts afterwards), and tells the notebook sync what happens to the data:
 * before anything leaves the device it records what was deleted, and after a
 * restore it starts from the restored state.
 */
fun appBackupHooks(context: Context, flush: () -> Unit): BackupHooks = object : BackupHooks {
    override suspend fun beforeBackup() {
        flush()
        // A backup must not fail because of this: it would only mean fewer deletions travel with it.
        runCatching { notebookSync(context).prepareExport() }
    }
    override suspend fun beforeRestore() {
        flush()
        runCatching { RanzenDatabase.get(context).close() }
        closeStudyDatabase(context)
    }
    override suspend fun beforeSwap(stage: File) = notebookSync(context).beforeRestore(stage)
    override suspend fun afterSwap() = notebookSync(context).afterRestore()
}

/**
 * What "Zusammenführen" and "Jetzt abgleichen" merge: plain folders (newer file
 * wins), the notebooks page by page ([NotebookSyncParticipant]) and the study
 * data by meaning (see [de.millione.schulranzen.study.StudyMergeParticipant]).
 * The pictures come first, so a merged page finds its background.
 */
fun appMergeParticipants(context: Context): List<MergeParticipant> {
    val files = context.filesDir
    return listOf(
        FolderMergeParticipant(File(files, "images"), name = "Bilder"),
        FolderMergeParticipant(File(files, "books"), name = "Bücher"),
        FolderMergeParticipant(File(files, "rechnen"), name = "SHK-Werkzeuge"),
        notebookSync(context),
        de.millione.schulranzen.study.StudyMergeParticipant(context),
    )
}

/** For RanzenApp.onCreate: enables automatic sync on opening and in the background. */
fun installBackupSync(app: Application) = AutoSync.install(
    app,
    participants = { appMergeParticipants(it) },
    hooks = { appBackupHooks(it) {} },
)

@Volatile private var sync: NotebookSyncParticipant? = null

/** One per process: it holds what is between "about to send" and "sent". */
private fun notebookSync(context: Context): NotebookSyncParticipant = sync ?: synchronized(RoomNotebookStore::class.java) {
    sync ?: run {
        val app = context.applicationContext
        val state = File(app.noBackupFilesDir, "sync").also { it.mkdirs() }
        NotebookSyncParticipant(
            RoomNotebookStore(app),
            inkDir = File(app.filesDir, "ink").also { it.mkdirs() },
            imageDir = File(app.filesDir, "images").also { it.mkdirs() },
            tombstoneFile = File(app.filesDir, "sync/tombstones.json"),
            stateFile = File(state, "notebooks.json"),
            hashes = HashCache(File(state, "notebook-hashes.json")),
        ).also { sync = it }
    }
}

/** The notebooks in Room, and in the database file of a backup. */
private class RoomNotebookStore(private val context: Context) : NotebookStore {
    private val db get() = RanzenDatabase.get(context)
    private val dao get() = db.library()

    override suspend fun readLocal(): List<NotebookState> = withContext(Dispatchers.IO) {
        val subjects = dao.allSubjects().associate { it.id to it.name }
        dao.allNotebooks().map { n ->
            NotebookState(
                n.id, n.title, n.subjectId?.let { subjects[it] }, n.paper, n.createdAt, n.updatedAt, n.lastPage, n.trashedAt,
                dao.pages(n.id).map { PageState(it.id, it.paper, it.bookmarked, it.locked, it.background) },
            )
        }
    }

    override suspend fun readRemote(backupDir: File): List<NotebookState>? = withContext(Dispatchers.IO) {
        val file = File(backupDir, "databases/ranzen.db")
        if (!file.exists()) return@withContext null
        // Read and write: a journal of the backup is applied to this copy on opening.
        val remote = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val subjects = HashMap<Long, String>()
            remote.rawQuery("SELECT id, name FROM subject", null).use { c -> while (c.moveToNext()) subjects[c.getLong(0)] = c.getString(1) }
            val notebooks = ArrayList<NotebookState>()
            remote.rawQuery("SELECT id, title, subjectId, paper, createdAt, updatedAt, lastPage, trashedAt FROM notebook", null).use { c ->
                while (c.moveToNext()) notebooks += NotebookState(
                    c.getString(0), c.getString(1), if (c.isNull(2)) null else subjects[c.getLong(2)], c.getString(3),
                    c.getLong(4), c.getLong(5), c.getInt(6), if (c.isNull(7)) null else c.getLong(7),
                )
            }
            notebooks.map { n -> n.copy(pages = remotePages(remote, n.id)) }
        } finally {
            remote.close()
        }
    }

    /** With SELECT *: backups of older versions lack the later columns. */
    private fun remotePages(remote: SQLiteDatabase, notebookId: String): List<PageState> {
        val pages = ArrayList<PageState>()
        remote.rawQuery("SELECT * FROM page WHERE notebookId = ? ORDER BY position", arrayOf(notebookId)).use { c ->
            while (c.moveToNext()) pages += PageState(
                c.text("id")!!, c.text("paper").orEmpty(),
                bookmarked = c.flag("bookmarked"), locked = c.flag("locked"), background = c.text("background"),
            )
        }
        return pages
    }

    private fun Cursor.text(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }
    private fun Cursor.flag(column: String): Boolean = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) != 0 } ?: false

    override suspend fun apply(write: List<NotebookState>, delete: List<String>) {
        if (write.isEmpty() && delete.isEmpty()) return
        db.withTransaction {
            // Pages moved in from another notebook: their row goes first, the ids are unique.
            for (n in write) for (p in n.pages) dao.deletePage(p.id)
            for (n in write) {
                val subjectId = n.subject?.let { name ->
                    val all = dao.allSubjects()
                    // Subjects are matched by name; a missing one is created here.
                    all.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
                        ?: dao.insertSubject(Subject(name = name, colorIndex = all.size % 8, position = all.size))
                }
                dao.replaceNotebook(
                    Notebook(n.id, n.title, subjectId, n.paper, n.createdAt, n.updatedAt, n.lastPage, n.pages.size, n.trashedAt),
                    n.pages.mapIndexed { i, p -> Page(p.id, n.id, i, p.paper, p.bookmarked, p.locked, p.background) },
                )
            }
            for (id in delete) dao.deleteNotebook(id)
        }
    }
}
