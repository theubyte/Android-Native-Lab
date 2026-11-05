package de.millione.schulranzen.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * One page as the merge sees it. [hash] stands for the page's content (ink and
 * objects), null for a blank page, so a page that was saved again unchanged
 * never counts as edited. [modified] is the newest file time and only decides
 * which side wins a real conflict.
 */
@Serializable
data class PageState(
    val id: String,
    val paper: String = "",
    val bookmarked: Boolean = false,
    val locked: Boolean = false,
    val background: String? = null,
    val hash: String? = null,
    val modified: Long = 0,
)

/** One notebook; [pages] are in reading order, [subject] is by name because ids differ between devices. */
@Serializable
data class NotebookState(
    val id: String,
    val title: String = "",
    val subject: String? = null,
    val paper: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val lastPage: Int = 0,
    val trashedAt: Long? = null,
    val pages: List<PageState> = emptyList(),
)

/**
 * Something that was deleted for good. Notebooks and pages have no row left
 * after that, so "not there" could also mean "not there yet"; only a tombstone
 * tells the other device to delete. [hash] is the last version other devices
 * can know: a copy with different content has changes of its own and survives.
 */
@Serializable
data class Tombstone(
    val id: String,
    val notebook: Boolean = false,
    val hash: String? = null,
    val at: Long = 0,
)

/** Stored with the data ("files/sync/…") so tombstones travel in every backup and sync. */
@Serializable
data class TombstoneFile(val format: Int = 1, val tombstones: List<Tombstone> = emptyList())

/**
 * What this device believes the others know, kept outside the data (it must
 * neither be backed up nor restored): [base] is the last state of the other
 * side that was merged, [exported] every page id ever handed out with the
 * content it had then.
 */
@Serializable
data class NotebookSyncState(
    val format: Int = 1,
    val base: List<NotebookState> = emptyList(),
    /** False until the first merge: there is nothing to compare with yet. */
    val hasBase: Boolean = false,
    val exportedPages: Map<String, String?> = emptyMap(),
    val exportedNotebooks: Set<String> = emptySet(),
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun read(file: File): NotebookSyncState =
            runCatching { json.decodeFromString(serializer(), file.readText()) }.getOrDefault(NotebookSyncState())

        fun write(file: File, state: NotebookSyncState) = atomicWrite(file, json.encodeToString(serializer(), state))
    }
}

/** Where the bytes of a page in the merged notebook come from. */
enum class PageSource { LOCAL, REMOTE, COPY_OF_LOCAL, COPY_OF_REMOTE }

data class PagePlan(
    /** The final page; for a copy with the new id. */
    val page: PageState,
    val source: PageSource,
    /** The page whose files are copied; for LOCAL and REMOTE its own id. */
    val from: String = page.id,
)

data class NotebookPlan(
    val notebook: NotebookState,
    val pages: List<PagePlan>,
    /** Pages of this notebook on this device that go away, with their files. */
    val removePages: List<String>,
    val isNew: Boolean,
)

/** A real conflict that was resolved; the older version stays as a bookmarked copy. */
data class PageConflict(val notebookTitle: String, val pageNumber: Int, val copyId: String?)

data class NotebookMergePlan(
    /** Only notebooks that change on this device. */
    val notebooks: List<NotebookPlan>,
    val removeNotebooks: List<String>,
    val tombstones: List<Tombstone>,
    val pagesAdded: Int,
    val pagesUpdated: Int,
    val pagesRemoved: Int,
    val pagesKept: Int,
    val conflicts: List<PageConflict>,
    val warnings: List<String>,
)

/**
 * Merges notebooks page by page, three way: this device, the other side and
 * the last state both knew ([base]). Pure and deterministic, and symmetric: two
 * devices merging each other's state get the same notebooks, so a conflict
 * resolved on both does not double.
 *
 * The rules, most important first:
 *
 * - **No page content ever disappears silently.** When both sides changed a
 *   page, the newer version takes the page's place and the older one stays as
 *   a bookmarked copy right behind it.
 * - **Only a tombstone deletes.** A page the other side lacks is kept: it may
 *   just be new here, or the other file may be an old backup.
 * - **A tombstone only deletes what has no changes of its own.** A copy edited
 *   since then survives, and the deletion counts as a conflict.
 * - **Mass deletions are held back**: when more than [MASS_DELETE_SHARE] of
 *   the pages (and at least [MASS_DELETE_MIN]) would go, a damaged device is more likely
 *   than a user cleaning up, so nothing is deleted and a warning says so.
 * - Trash: the later event wins, trashing or working in the notebook.
 */
object NotebookMerge {
    const val MASS_DELETE_MIN = 30
    const val MASS_DELETE_SHARE = 0.6
    /** After this time a device that was away so long may bring something back; better than growing forever. */
    const val TOMBSTONE_DAYS = 365L

    fun plan(
        local: List<NotebookState>,
        remote: List<NotebookState>,
        base: List<NotebookState>?,
        localTombstones: List<Tombstone>,
        remoteTombstones: List<Tombstone>,
        now: Long,
    ): NotebookMergePlan {
        val lById = local.associateBy { it.id }
        val rById = remote.associateBy { it.id }
        val bById = base?.associateBy { it.id }.orEmpty()
        val bPages = HashMap<String, PageState>()
        base?.forEach { n -> n.pages.forEach { bPages[it.id] = it } }
        val lTomb = localTombstones.filterNot { it.notebook }.associateBy { it.id }
        val rTomb = remoteTombstones.filterNot { it.notebook }.associateBy { it.id }
        val lNbTomb = localTombstones.filter { it.notebook }.associateBy { it.id }
        val rNbTomb = remoteTombstones.filter { it.notebook }.associateBy { it.id }

        /** A tombstone applies when the copy here has nothing the deleting side did not know. */
        fun dead(t: Tombstone?, page: PageState): Boolean {
            if (t == null) return false
            if (t.hash == page.hash || page.hash == null) return true
            val b = bPages[page.id] ?: return false
            return b.hash == page.hash
        }

        // Mass deletion check over everything the other side wants to delete here.
        val localContentPages = local.sumOf { n -> n.pages.count { it.hash != null } }
        val wanted = local.sumOf { n ->
            n.pages.count { p -> p.hash != null && p.id !in (rById[n.id]?.pages?.map { it.id }?.toSet().orEmpty()) && dead(rTomb[p.id], p) }
        }
        val hold = wanted >= MASS_DELETE_MIN && wanted > localContentPages * MASS_DELETE_SHARE

        val warnings = ArrayList<String>()
        if (hold) warnings += "Ein anderes Gerät hat $wanted Seiten gelöscht, das ist die Mehrheit. Zur Sicherheit bleiben sie hier erhalten. Falls das gewollt war: hier noch einmal löschen."

        var added = 0
        var updated = 0
        var removed = 0
        var kept = 0
        val conflicts = ArrayList<PageConflict>()
        val plans = ArrayList<NotebookPlan>()
        val removeNotebooks = ArrayList<String>()
        val alive = HashSet<String>()

        for (id in (local.map { it.id } + remote.map { it.id }).distinct()) {
            val l = lById[id]
            val r = rById[id]
            val b = bById[id]
            val lPages = l?.pages.orEmpty().associateBy { it.id }
            val rPages = r?.pages.orEmpty().associateBy { it.id }
            val allIds = lPages.keys + rPages.keys

            val decided = LinkedHashMap<String, PagePlan>()
            val copies = LinkedHashMap<String, PagePlan>() // original id -> copy behind it
            val removeHere = ArrayList<String>()
            val nbConflicts = ArrayList<Pair<String, String?>>()
            var nAdded = 0; var nUpdated = 0; var nRemoved = 0; var nKept = 0

            for (pid in allIds) {
                val lp = lPages[pid]
                val rp = rPages[pid]
                when {
                    lp != null && rp != null -> {
                        val outcome = mergeContent(lp, rp, bPages[pid], allIds)
                        val meta = mergeMeta(lp, rp, bPages[pid], newer = newer(l!!, r!!))
                        decided[pid] = when (outcome.winner) {
                            PageSource.REMOTE -> {
                                nUpdated++
                                PagePlan(meta.copy(hash = rp.hash, modified = rp.modified), PageSource.REMOTE)
                            }
                            else -> {
                                nKept++
                                PagePlan(meta.copy(hash = lp.hash, modified = lp.modified), PageSource.LOCAL)
                            }
                        }
                        outcome.copy?.let { c -> copies[pid] = c }
                        if (outcome.conflict) nbConflicts += pid to outcome.copy?.page?.id
                    }
                    lp != null -> {
                        if (!hold && dead(rTomb[pid], lp)) {
                            removeHere += pid
                            nRemoved++
                        } else {
                            if (rTomb[pid] != null && !hold) nbConflicts += pid to null
                            decided[pid] = PagePlan(lp, PageSource.LOCAL)
                            nKept++
                        }
                    }
                    else -> {
                        if (!dead(lTomb[pid], rp!!)) {
                            if (lTomb[pid] != null) nbConflicts += pid to null
                            decided[pid] = PagePlan(rp, PageSource.REMOTE)
                            nAdded++
                        }
                    }
                }
            }

            // Whole notebook deleted on one side, and nothing here outlives it.
            val deletedThere = l != null && r == null && rNbTomb[id] != null && !hold
            val deletedHere = l == null && r != null && lNbTomb[id] != null
            if (decided.isEmpty() && (deletedThere || deletedHere || (l != null && r == null && l.pages.isNotEmpty()) || l == null)) {
                if (l != null) { removeNotebooks += id; removed += nRemoved }
                continue
            }
            if (deletedThere || deletedHere) {
                val title = (l ?: r)!!.title
                warnings += "„$title“ wurde auf einem Gerät gelöscht, auf dem anderen aber noch bearbeitet. Es bleibt erhalten."
            }

            val order = mergeOrder(l, r, b, decided.keys)
            val finalPages = ArrayList<PagePlan>()
            for (pid in order) {
                finalPages += decided.getValue(pid)
                copies[pid]?.let { finalPages += it }
            }
            val merged = mergeNotebook(l, r, b, finalPages.map { it.page })
            alive += id
            finalPages.forEach { alive += it.page.id }

            val title = merged.title
            for ((pid, copyId) in nbConflicts) {
                val idx = finalPages.indexOfFirst { it.page.id == pid }
                conflicts += PageConflict(title, if (idx >= 0) idx + 1 else 0, copyId)
            }
            val unchanged = l != null && removeHere.isEmpty() && finalPages.all { it.source == PageSource.LOCAL } &&
                finalPages.map { it.page } == l.pages && merged.copy(lastPage = l.lastPage, pages = emptyList()) == l.copy(pages = emptyList())
            if (!unchanged) plans += NotebookPlan(merged, finalPages, removeHere, isNew = l == null)
            added += nAdded; updated += nUpdated; removed += nRemoved; kept += nKept
        }

        val limit = now - TOMBSTONE_DAYS * 86_400_000L
        val tombstones = (localTombstones + remoteTombstones)
            .groupBy { it.notebook to it.id }.values.map { same -> same.maxBy { it.at } }
            .filter { it.id !in alive && it.at >= limit }
            .sortedWith(compareBy({ it.notebook }, { it.id }))
        return NotebookMergePlan(plans, removeNotebooks, tombstones, added, updated, removed, kept, conflicts, warnings)
    }

    private class Outcome(val winner: PageSource, val copy: PagePlan?, val conflict: Boolean)

    private fun mergeContent(l: PageState, r: PageState, b: PageState?, existing: Set<String>): Outcome {
        if (l.hash == r.hash) return Outcome(PageSource.LOCAL, null, false)
        if (b != null) {
            // Only one side moved since the common state. An "edit" that is older
            // than the state both knew is an old file or a wrong clock: not trusted.
            if (l.hash == b.hash && r.modified >= l.modified) return Outcome(PageSource.REMOTE, null, false)
            if (r.hash == b.hash) return Outcome(PageSource.LOCAL, null, false)
        } else {
            if (l.hash == null) return Outcome(PageSource.REMOTE, null, false)
            if (r.hash == null) return Outcome(PageSource.LOCAL, null, false)
        }
        // Both changed. Same choice on both devices: newer file, then hash.
        val remoteWins = r.modified > l.modified || (r.modified == l.modified && (r.hash ?: "") > (l.hash ?: ""))
        val loser = if (remoteWins) l else r
        val winner = if (remoteWins) PageSource.REMOTE else PageSource.LOCAL
        if (loser.hash == null) return Outcome(winner, null, false)
        val copyId = copyIdOf(loser.id, loser.hash)
        // A device that resolved this before already has the copy; then it is not news.
        if (copyId in existing) return Outcome(winner, null, false)
        val copy = PagePlan(
            loser.copy(id = copyId, bookmarked = true),
            if (remoteWins) PageSource.COPY_OF_LOCAL else PageSource.COPY_OF_REMOTE,
            from = loser.id,
        )
        return Outcome(winner, copy, true)
    }

    /** The same conflict gives the same id on every device. */
    fun copyIdOf(pageId: String, hash: String): String =
        UUID.nameUUIDFromBytes("schulranzen-konflikt|$pageId|$hash".toByteArray(Charsets.UTF_8)).toString()

    /** Above 0: the other side is newer; 0: same time. */
    private fun newer(l: NotebookState, r: NotebookState) = r.updatedAt.compareTo(l.updatedAt)

    /**
     * Field by field: a value only one side changed since the base wins, otherwise
     * the newer notebook, and at the same time the larger value, so both devices
     * agree. Setting a bookmark, a lock or the paper does not touch updatedAt, so
     * the time alone would lose them; only the comparison with the base sees them.
     */
    private fun <T> pick(l: T, r: T, b: T?, hasBase: Boolean, newer: Int): T = when {
        l == r -> l
        hasBase && l == b -> r
        hasBase && r == b -> l
        newer > 0 -> r
        newer < 0 -> l
        l.toString() >= r.toString() -> l
        else -> r
    }

    private fun mergeMeta(l: PageState, r: PageState, b: PageState?, newer: Int): PageState {
        val has = b != null
        return l.copy(
            paper = pick(l.paper, r.paper, b?.paper, has, newer),
            // Without a base a bookmark or lock on either side stays: losing one is worse than an extra one.
            bookmarked = if (has) pick(l.bookmarked, r.bookmarked, b?.bookmarked, true, newer) else l.bookmarked || r.bookmarked,
            locked = if (has) pick(l.locked, r.locked, b?.locked, true, newer) else l.locked || r.locked,
            background = pick(l.background, r.background, b?.background, has, newer),
        )
    }

    private fun mergeNotebook(l: NotebookState?, r: NotebookState?, b: NotebookState?, pages: List<PageState>): NotebookState {
        if (l == null) return r!!.copy(pages = pages, lastPage = r.lastPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
        if (r == null) return l.copy(pages = pages, lastPage = keepPage(l, pages))
        val n = newer(l, r)
        val has = b != null
        return l.copy(
            title = pick(l.title, r.title, b?.title, has, n),
            subject = pick(l.subject, r.subject, b?.subject, has, n),
            paper = pick(l.paper, r.paper, b?.paper, has, n),
            createdAt = minOf(l.createdAt, r.createdAt),
            updatedAt = maxOf(l.updatedAt, r.updatedAt),
            trashedAt = mergeTrash(l, r),
            lastPage = keepPage(l, pages),
            pages = pages,
        )
    }

    /**
     * The later event counts: being put in the bin, or being worked on (and
     * taking a notebook out of the bin touches updatedAt too). Writing in a
     * notebook after the other device binned it brings it back.
     */
    private fun mergeTrash(l: NotebookState, r: NotebookState): Long? {
        val lt = l.trashedAt
        val rt = r.trashedAt
        return when {
            lt == null && rt == null -> null
            lt != null && rt != null -> maxOf(lt, rt)
            lt != null -> if (r.updatedAt > lt) null else lt
            else -> if (l.updatedAt > rt!!) null else rt
        }
    }

    /** This device stays on the page it was on, even when pages were added before it. */
    private fun keepPage(l: NotebookState, pages: List<PageState>): Int {
        val current = l.pages.getOrNull(l.lastPage)?.id
        val i = pages.indexOfFirst { it.id == current }
        return if (i >= 0) i else l.lastPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    }

    /**
     * The order of whichever side rearranged the pages (the newer one if both
     * did); pages only the other side has go behind the page they followed
     * there, after any new pages already at that spot, so two devices adding
     * at the same place keep both.
     */
    internal fun mergeOrder(l: NotebookState?, r: NotebookState?, b: NotebookState?, keep: Set<String>): List<String> {
        val lo = l?.pages.orEmpty().map { it.id }.filter { it in keep }
        val ro = r?.pages.orEmpty().map { it.id }.filter { it in keep }
        if (l == null) return ro
        if (r == null) return lo
        val common = lo.toSet() intersect ro.toSet()
        val lc = lo.filter { it in common }
        val rc = ro.filter { it in common }
        val bc = b?.pages.orEmpty().map { it.id }.filter { it in common }
        val localLeads = when {
            lc != rc && b != null && bc == lc -> false // only the other side rearranged
            lc != rc && b != null && bc == rc -> true
            r.updatedAt != l.updatedAt -> l.updatedAt > r.updatedAt
            else -> lo.joinToString() >= ro.joinToString()
        }
        val primary = if (localLeads) lo else ro
        val other = if (localLeads) ro else lo
        val result = ArrayList(primary)
        val inPrimary = primary.toSet()
        var previous: String? = null
        for (id in other) {
            if (id !in inPrimary) {
                var at = previous?.let { result.indexOf(it) + 1 } ?: 0
                // Behind the new pages of the leading side at this spot.
                while (at < result.size && result[at] !in common && result[at] in inPrimary) at++
                result.add(at, id)
            }
            previous = id
        }
        return result
    }

    /**
     * New tombstones: everything handed out earlier that is gone now, with the
     * content it had then. With too many at once the device more likely lost
     * its data than the user deleted it, so none are made ([TombstoneUpdate.held]).
     */
    fun updateTombstones(state: NotebookSyncState, current: List<NotebookState>, existing: List<Tombstone>, now: Long): TombstoneUpdate {
        val pageIds = current.flatMapTo(HashSet()) { n -> n.pages.map { it.id } }
        val nbIds = current.mapTo(HashSet()) { it.id }
        val known = LinkedHashMap<String, String?>(state.exportedPages)
        state.base.forEach { n -> n.pages.forEach { p -> if (p.id !in known) known[p.id] = p.hash } }
        val knownNotebooks = state.exportedNotebooks + state.base.map { it.id }
        val have = existing.mapTo(HashSet()) { it.notebook to it.id }
        val gonePages = known.filter { (id, _) -> id !in pageIds && (false to id) !in have }
        val goneContent = gonePages.count { it.value != null }
        val knownContent = known.count { it.value != null }
        if (goneContent >= MASS_DELETE_MIN && goneContent > knownContent * MASS_DELETE_SHARE) {
            return TombstoneUpdate(existing.filter { (it.notebook && it.id !in nbIds) || (!it.notebook && it.id !in pageIds) }, held = goneContent)
        }
        val added = gonePages.map { (id, hash) -> Tombstone(id, false, hash, now) } +
            knownNotebooks.filter { it !in nbIds && (true to it) !in have }.map { Tombstone(it, true, null, now) }
        val alive = existing.filter { (it.notebook && it.id !in nbIds) || (!it.notebook && it.id !in pageIds) }
        return TombstoneUpdate(alive + added, held = 0)
    }

    /** Updates what has been handed out: called right before an export or upload. */
    fun exported(state: NotebookSyncState, current: List<NotebookState>): NotebookSyncState {
        val pages = LinkedHashMap<String, String?>()
        current.forEach { n -> n.pages.forEach { pages[it.id] = it.hash } }
        return state.copy(exportedPages = pages, exportedNotebooks = current.mapTo(LinkedHashSet()) { it.id })
    }

    /**
     * Stands for a page's content. Blank (null): no strokes in the ink file, no
     * object file and no background. A scanned worksheet without writing is
     * content: it counts for conflicts and for the mass deletion check.
     */
    fun pageHash(inkSha: String?, inkSize: Long, itemsSha: String?, background: String? = null): String? {
        val noInk = inkSize <= EMPTY_INK_SIZE
        if (noInk && itemsSha == null && background == null) return null
        return Hashing.sha256(("ink:${if (noInk) "-" else inkSha}|items:${itemsSha ?: "-"}|bg:${background ?: "-"}").byteInputStream())
    }

    /** Size of an ink file with no strokes: magic and count. */
    const val EMPTY_INK_SIZE = 8L
}

/** Result of [NotebookMerge.updateTombstones]. [held] pages were not marked as deleted because too many went at once. */
data class TombstoneUpdate(val tombstones: List<Tombstone>, val held: Int)

/** Writes a text file whole or not at all. */
internal fun atomicWrite(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.delete()
        if (!tmp.renameTo(file)) throw BackupException("${file.name} konnte nicht gespeichert werden.")
    }
}
