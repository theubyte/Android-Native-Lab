package de.millione.schulranzen.core.backup

import kotlinx.serialization.json.Json
import java.io.File

/** The structure of the notebooks on this device and in a backup. */
interface NotebookStore {
    /** Everything including the bin, pages in reading order. Hashes and file times are filled in by the caller. */
    suspend fun readLocal(): List<NotebookState>
    /** The same from an extracted backup; null when it has no notebooks. */
    suspend fun readRemote(backupDir: File): List<NotebookState>?
    /**
     * In one transaction: replaces (or creates) each notebook in [write] with
     * exactly these pages in this order, a page coming from another notebook
     * moving along, and removes the notebooks in [delete]. It must be one,
     * because a page moved between two notebooks may never be in neither of them:
     * it would count as deleted by the user.
     */
    suspend fun apply(write: List<NotebookState>, delete: List<String>)
}

/**
 * Applies [NotebookMerge] to real data: files first (each whole, through a temp
 * file), then the structure, and the base last. Dying anywhere in between is
 * harmless: the same merge runs again next time and finds the files in place,
 * and files without a row in the database are simply not read.
 *
 * Page files are `<ink>/<id>.rnz` and `<id>.items.json`; `<sync>` is a folder
 * that belongs to the backup ([TOMBSTONE_FILE] travels in it), [stateFile] does not.
 */
class NotebookSyncParticipant(
    private val store: NotebookStore,
    private val inkDir: File,
    private val imageDir: File,
    private val tombstoneFile: File,
    private val stateFile: File,
    private val hashes: HashCache,
    private val clock: () -> Long = System::currentTimeMillis,
) : MergeParticipant {
    override val name = SyncText.NOTEBOOK_REPORT

    private var pendingBase: List<NotebookState>? = null
    private var pendingRebase: List<NotebookState>? = null

    override suspend fun merge(backupDir: File): MergeReport {
        val rawRemote = store.readRemote(backupDir)
            ?: return MergeReport(name, warnings = listOf("Keine Hefte im Backup"))
        val index = RemoteIndex.read(backupDir)
        val remote = rawRemote.map { n -> n.copy(pages = n.pages.map { remotePage(it, backupDir, index) }) }
        val local = localStates()
        val state = NotebookSyncState.read(stateFile)

        val update = NotebookMerge.updateTombstones(state, local, readTombstones(tombstoneFile), clock())
        val warnings = ArrayList<String>()
        if (update.held > 0) warnings += heldWarning(update.held)
        val remoteTombstones = readTombstones(File(backupDir, TOMBSTONE_PATH))

        val plan = NotebookMerge.plan(local, remote, state.base.takeIf { state.hasBase }, update.tombstones, remoteTombstones, clock(), state.exportedPages)
        for (n in plan.notebooks) applyFiles(n, backupDir, index)
        store.apply(plan.notebooks.map { it.notebook }, plan.removeNotebooks)
        for (id in plan.removeFiles) deletePageFiles(id)
        writeTombstones(plan.tombstones)
        // The other side's state is what both devices know now, even if sending ours fails later.
        NotebookSyncState.write(stateFile, state.copy(base = remote, hasBase = true))
        hashes.save()
        return MergeReport(
            name,
            added = plan.pagesAdded,
            updated = plan.pagesUpdated,
            kept = plan.pagesKept,
            removed = plan.pagesRemoved,
            conflicts = plan.conflicts.size,
            warnings = warnings + plan.warnings + plan.conflicts.map { c ->
                if (c.copyId != null) "„${c.notebookTitle}“, Seite ${c.pageNumber}: auf beiden Geräten geändert. Die ältere Fassung liegt als Lesezeichen dahinter."
                else "„${c.notebookTitle}“, Seite ${c.pageNumber}: auf einem Gerät gelöscht, auf dem anderen geändert. Sie bleibt erhalten."
            },
        )
    }

    override suspend fun prepareExport(): List<String> {
        val local = localStates()
        val state = NotebookSyncState.read(stateFile)
        val existing = readTombstones(tombstoneFile)
        val update = NotebookMerge.updateTombstones(state, local, existing, clock())
        if (update.tombstones != existing) writeTombstones(update.tombstones)
        NotebookSyncState.write(stateFile, NotebookMerge.exported(state, local))
        pendingBase = local
        hashes.save()
        return if (update.held > 0) listOf(heldWarning(update.held)) else emptyList()
    }

    override suspend fun committed() {
        val base = pendingBase ?: return
        NotebookSyncState.write(stateFile, NotebookSyncState.read(stateFile).copy(base = base, hasBase = true))
        pendingBase = null
    }

    /**
     * A full restore is coming from [stage]. The old state describes data that
     * is about to go; left in place it would make the restored data look like
     * mass deletions. It goes first, so a crash just means "no base yet".
     */
    suspend fun beforeRestore(stage: File) {
        stateFile.delete()
        pendingRebase = runCatching {
            val index = RemoteIndex.read(stage)
            store.readRemote(stage)?.map { n -> n.copy(pages = n.pages.map { remotePage(it, stage, index) }) }
        }.getOrNull()
    }

    /** The restored data is what that backup held, so the next sync starts from it. */
    fun afterRestore() {
        val base = pendingRebase ?: return
        NotebookSyncState.write(stateFile, NotebookMerge.exported(NotebookSyncState(base = base, hasBase = true), base))
        pendingRebase = null
    }

    private fun heldWarning(n: Int) =
        "$n Seiten fehlen hier auf einmal. Das sieht nach einem Datenproblem aus, deshalb wird das Löschen nicht an andere Geräte weitergegeben."

    private suspend fun localStates(): List<NotebookState> = store.readLocal().map { n ->
        n.copy(pages = n.pages.map { localPage(it) })
    }

    private fun localPage(p: PageState): PageState {
        val ink = inkFile(inkDir, p.id)
        val items = itemsFile(inkDir, p.id)
        val inkSha = if (ink.isFile) hashes.sha256(ink) else null
        val itemsSha = if (items.isFile) hashes.sha256(items) else null
        return p.copy(
            hash = NotebookMerge.pageHash(inkSha, if (ink.isFile) ink.length() else 0, itemsSha, p.background),
            modified = maxOf(ink.lastModified(), items.lastModified()),
        )
    }

    private fun remotePage(p: PageState, backupDir: File, index: RemoteIndex?): PageState {
        val inkPath = "$INK_PATH/${p.id}.rnz"
        val itemsPath = "$INK_PATH/${p.id}.items.json"
        if (index != null) {
            val ink = index[inkPath]
            val items = index[itemsPath]
            return p.copy(
                hash = NotebookMerge.pageHash(ink?.sha256, ink?.size ?: 0, items?.sha256, p.background),
                modified = maxOf(timeOf(ink, File(backupDir, inkPath)), timeOf(items, File(backupDir, itemsPath))),
            )
        }
        val ink = File(backupDir, inkPath)
        val items = File(backupDir, itemsPath)
        return p.copy(
            hash = NotebookMerge.pageHash(if (ink.isFile) Hashing.sha256(ink) else null, if (ink.isFile) ink.length() else 0, if (items.isFile) Hashing.sha256(items) else null, p.background),
            modified = maxOf(ink.lastModified(), items.lastModified()),
        )
    }

    private fun timeOf(entry: BackupEntry?, file: File): Long = when {
        entry == null -> 0
        entry.modified > 0 -> entry.modified
        else -> file.lastModified()
    }

    private fun applyFiles(n: NotebookPlan, backupDir: File, index: RemoteIndex?) {
        // Copies first: a copy of the local version must be made before it is replaced.
        for (p in n.pages) when (p.source) {
            PageSource.COPY_OF_LOCAL -> {
                copyIfThere(inkFile(inkDir, p.from), inkFile(inkDir, p.page.id))
                copyIfThere(itemsFile(inkDir, p.from), itemsFile(inkDir, p.page.id))
            }
            PageSource.COPY_OF_REMOTE -> {
                remoteCopy("${p.from}.rnz", "${p.page.id}.rnz", backupDir, index)
                remoteCopy("${p.from}.items.json", "${p.page.id}.items.json", backupDir, index)
            }
            else -> {}
        }
        for (p in n.pages) if (p.source == PageSource.REMOTE) {
            take("${p.page.id}.rnz", backupDir, index)
            take("${p.page.id}.items.json", backupDir, index)
            p.page.background?.let { bg ->
                val target = ZipPaths.resolve(imageDir, bg)
                if (!target.exists()) copyIfThere(ZipPaths.resolve(backupDir, "files/images/$bg"), target)
            }
        }
    }

    /** Makes the local file the other side's: from the stage when it differs, removed when the other side has none. */
    private fun take(name: String, backupDir: File, index: RemoteIndex?) {
        val target = ZipPaths.resolve(inkDir, name)
        val staged = ZipPaths.resolve(backupDir, "$INK_PATH/$name")
        when {
            staged.isFile -> copyIfThere(staged, target)
            index != null && index["$INK_PATH/$name"] != null -> {} // Listed but not fetched: same as here.
            else -> target.delete()
        }
    }

    private fun remoteCopy(fromName: String, toName: String, backupDir: File, index: RemoteIndex?) {
        val staged = ZipPaths.resolve(backupDir, "$INK_PATH/$fromName")
        val target = ZipPaths.resolve(inkDir, toName)
        when {
            staged.isFile -> copyIfThere(staged, target)
            index != null && index["$INK_PATH/$fromName"] != null -> copyIfThere(ZipPaths.resolve(inkDir, fromName), target)
        }
    }

    /** The old page stays until the new one is complete. `.damaged` copies have other names and are never touched. */
    private fun copyIfThere(src: File, dst: File) {
        if (src.isFile) atomicCopy(src, dst)
    }

    private fun deletePageFiles(id: String) {
        inkFile(inkDir, id).delete()
        itemsFile(inkDir, id).delete()
    }

    private fun writeTombstones(list: List<Tombstone>) {
        if (list.isEmpty() && !tombstoneFile.exists()) return
        val text = json.encodeToString(TombstoneFile.serializer(), TombstoneFile(tombstones = list))
        if (tombstoneFile.isFile && tombstoneFile.readText() == text) return
        atomicWrite(tombstoneFile, text)
    }

    companion object {
        /** In the data, so it is part of every backup and sync. */
        const val TOMBSTONE_PATH = "files/sync/tombstones.json"
        private const val INK_PATH = "files/ink"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private fun inkFile(dir: File, id: String) = ZipPaths.resolve(dir, "$id.rnz")
        private fun itemsFile(dir: File, id: String) = ZipPaths.resolve(dir, "$id.items.json")

        fun readTombstones(file: File): List<Tombstone> =
            runCatching { json.decodeFromString(TombstoneFile.serializer(), file.readText()).tombstones }.getOrDefault(emptyList())
    }
}
