package de.millione.schulranzen.core.backup

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotebookMergeTest {
    private fun page(id: String, hash: String? = "h-$id", modified: Long = 100, bookmarked: Boolean = false, paper: String = "RULED") =
        PageState(id, paper = paper, hash = hash, modified = modified, bookmarked = bookmarked)

    private fun nb(id: String = "n", updatedAt: Long = 100, trashedAt: Long? = null, title: String = "Mathe", vararg pages: PageState) =
        NotebookState(id, title, "Mathe", "RULED", 1, updatedAt, 0, trashedAt, pages.toList())

    private fun merge(
        local: List<NotebookState>, remote: List<NotebookState>, base: List<NotebookState>? = null,
        lt: List<Tombstone> = emptyList(), rt: List<Tombstone> = emptyList(),
    ) = NotebookMerge.plan(local, remote, base, lt, rt, now = 1_000_000)

    private fun NotebookMergePlan.only() = notebooks.single()
    private fun NotebookPlan.ids() = pages.map { it.page.id }

    @Test fun phoneScansTabletWrites() {
        val common = nb(updatedAt = 100, pages = arrayOf(page("p1"), page("p2")))
        // The tablet writes on page 2 and adds page 3.
        val tablet = nb(updatedAt = 300, pages = arrayOf(page("p1"), page("p2", "h-p2-neu", 300), page("p3", "h-p3", 300)))
        // The phone scans a worksheet into the same notebook, at the end.
        val phone = nb(updatedAt = 200, pages = arrayOf(page("p1"), page("p2"), page("scan", "h-scan", 200)))

        val onTablet = merge(listOf(tablet), listOf(phone), listOf(common))
        assertEquals(listOf("p1", "p2", "p3", "scan"), onTablet.only().ids())
        assertEquals(1, onTablet.pagesAdded)
        assertEquals(0, onTablet.conflicts.size)
        assertEquals("h-p2-neu", onTablet.only().pages[1].page.hash, "tablet's writing stays")

        val onPhone = merge(listOf(phone), listOf(tablet), listOf(common))
        assertEquals(listOf("p1", "p2", "p3", "scan"), onPhone.only().ids(), "both devices end up with the same order")
        assertEquals(2, onPhone.pagesAdded + onPhone.pagesUpdated - 0)
        assertEquals(PageSource.REMOTE, onPhone.only().pages[1].source)
        assertEquals(0, onPhone.conflicts.size)
    }

    @Test fun onlyOneSideChangedIsNoConflict() {
        val base = listOf(nb(pages = arrayOf(page("a"))))
        val changed = listOf(nb(updatedAt = 200, pages = arrayOf(page("a", "neu", 200))))
        val r = merge(base, changed, base)
        assertEquals(PageSource.REMOTE, r.only().pages.single().source)
        assertEquals(0, r.conflicts.size)
        assertEquals(1, r.pagesUpdated)
        // Mirror image: nothing to do here at all.
        assertTrue(merge(changed, base, base).notebooks.isEmpty())
    }

    @Test fun bothChangedKeepsTheOlderAsCopy() {
        val base = listOf(nb(pages = arrayOf(page("a"))))
        val local = listOf(nb(updatedAt = 200, pages = arrayOf(page("a", "hier", 200))))
        val remote = listOf(nb(updatedAt = 300, pages = arrayOf(page("a", "dort", 300))))
        val r = merge(local, remote, base)
        val pages = r.only().pages
        assertEquals(2, pages.size)
        assertEquals("dort", pages[0].page.hash)
        assertEquals(PageSource.REMOTE, pages[0].source)
        assertEquals("hier", pages[1].page.hash)
        assertEquals(PageSource.COPY_OF_LOCAL, pages[1].source)
        assertEquals("a", pages[1].from)
        assertTrue(pages[1].page.bookmarked, "the copy can be found")
        assertEquals(1, r.conflicts.size)
        assertEquals(1, r.conflicts.single().pageNumber)
    }

    @Test fun conflictResolvesTheSameOnBothDevices() {
        val base = listOf(nb(pages = arrayOf(page("a"))))
        val x = listOf(nb(updatedAt = 200, pages = arrayOf(page("a", "x", 200))))
        val y = listOf(nb(updatedAt = 300, pages = arrayOf(page("a", "y", 300))))
        val onX = merge(x, y, base).only().pages
        val onY = merge(y, x, base).only().pages
        assertEquals(onX.map { it.page.id to it.page.hash }, onY.map { it.page.id to it.page.hash })

        // X has merged and sent; Y merges that and must not get a second copy.
        val xSent = listOf(nb(updatedAt = 300, pages = onX.map { it.page }.toTypedArray()))
        val again = merge(y, xSent, base)
        assertEquals(2, again.only().pages.size)
        assertEquals(0, again.conflicts.size)
        // The same on the sender when the other side merged first.
        val still = merge(xSent, xSent, xSent)
        assertTrue(still.notebooks.isEmpty())
    }

    @Test fun sameTimeStillDecidesTheSameWay() {
        val x = listOf(nb(pages = arrayOf(page("a", "x", 100))))
        val y = listOf(nb(pages = arrayOf(page("a", "y", 100))))
        val onX = merge(x, y).only().pages
        val onY = merge(y, x).only().pages
        assertEquals("y", onX[0].page.hash)
        assertEquals("y", onY[0].page.hash)
        assertEquals(onX.map { it.page.id }, onY.map { it.page.id })
    }

    @Test fun firstSyncNeverLosesEitherVersion() {
        val r = merge(listOf(nb(pages = arrayOf(page("a", "x", 100)))), listOf(nb(pages = arrayOf(page("a", "y", 200)))), base = null)
        assertEquals(setOf("x", "y"), r.only().pages.map { it.page.hash }.toSet())
        assertEquals(1, r.conflicts.size)
    }

    @Test fun blankPageNeverMakesACopy() {
        val r = merge(listOf(nb(pages = arrayOf(page("a", null, 500)))), listOf(nb(pages = arrayOf(page("a", "y", 100)))), base = null)
        assertEquals(listOf("y"), r.only().pages.map { it.page.hash })
        assertEquals(0, r.conflicts.size)
    }

    @Test fun anOldFileNeverOverwritesSilently() {
        // The local page equals the base, the "changed" remote one is older than both: an old backup.
        val base = listOf(nb(pages = arrayOf(page("a", "neu", 500))))
        val old = listOf(nb(pages = arrayOf(page("a", "alt", 100))))
        val pages = merge(base, old, base).only().pages
        assertEquals("neu", pages[0].page.hash)
        assertEquals("alt", pages[1].page.hash)
    }

    @Test fun missingOnTheOtherSideIsNotADeletion() {
        val local = listOf(nb(pages = arrayOf(page("a"), page("b"))))
        val old = listOf(nb(pages = arrayOf(page("a"))))
        val r = merge(local, old, base = local)
        assertTrue(r.notebooks.isEmpty(), "page b stays: only a tombstone deletes")
        assertEquals(0, r.pagesRemoved)
        assertTrue(merge(local, emptyList(), local).removeNotebooks.isEmpty())
    }

    @Test fun deletedPageDoesNotComeBack() {
        val base = listOf(nb(pages = arrayOf(page("a"), page("b"))))
        val here = listOf(nb(updatedAt = 200, pages = arrayOf(page("a"))))
        val r = merge(here, base, base, lt = listOf(Tombstone("b", hash = "h-b", at = 150)))
        assertTrue(r.notebooks.isEmpty() || r.only().ids() == listOf("a"))
        assertEquals(0, r.pagesAdded)
    }

    @Test fun deletionReachesTheOtherDevice() {
        val base = listOf(nb(pages = arrayOf(page("a"), page("b"))))
        val there = listOf(nb(updatedAt = 200, pages = arrayOf(page("a"))))
        val r = merge(base, there, base, rt = listOf(Tombstone("b", hash = "h-b", at = 150)))
        assertEquals(listOf("a"), r.only().ids())
        assertEquals(listOf("b"), r.only().removePages)
        assertEquals(1, r.pagesRemoved)
        assertEquals(listOf("b"), r.tombstones.map { it.id }, "and it keeps travelling")
    }

    @Test fun deletedThereButEditedHereSurvives() {
        val base = listOf(nb(pages = arrayOf(page("a"), page("b"))))
        val here = listOf(nb(updatedAt = 300, pages = arrayOf(page("a"), page("b", "neu", 300))))
        val there = listOf(nb(updatedAt = 200, pages = arrayOf(page("a"))))
        val r = merge(here, there, base, rt = listOf(Tombstone("b", hash = "h-b", at = 150)))
        assertTrue(r.notebooks.isEmpty() || r.only().removePages.isEmpty())
        assertEquals(0, r.pagesRemoved)
        assertEquals(1, r.conflicts.size)
        assertTrue(r.tombstones.isEmpty(), "the page lives, so the deletion is off")
    }

    @Test fun editedThereDeletedHereComesBack() {
        val base = listOf(nb(pages = arrayOf(page("a"), page("b"))))
        val here = listOf(nb(updatedAt = 200, pages = arrayOf(page("a"))))
        val there = listOf(nb(updatedAt = 300, pages = arrayOf(page("a"), page("b", "neu", 300))))
        val r = merge(here, there, base, lt = listOf(Tombstone("b", hash = "h-b", at = 150)))
        assertEquals(listOf("a", "b"), r.only().ids())
        assertEquals(1, r.conflicts.size)
        assertTrue(r.tombstones.isEmpty())
    }

    @Test fun staleDeviceFollowsADeletionAfterMissedEdits() {
        // The page was edited elsewhere after our last sync, then deleted. We never touched it.
        val base = listOf(nb(pages = arrayOf(page("a"), page("b", "v1"))))
        val there = listOf(nb(updatedAt = 300, pages = arrayOf(page("a"))))
        val r = merge(base, there, base, rt = listOf(Tombstone("b", hash = "v2", at = 250)))
        assertEquals(listOf("b"), r.only().removePages)
    }

    @Test fun deletedNotebookStaysDeleted() {
        val a = nb("a", pages = arrayOf(page("p1")))
        val tombs = listOf(Tombstone("a", notebook = true, at = 150), Tombstone("p1", hash = "h-p1", at = 150))
        // Deleted here, the other side still has it unchanged.
        val r = merge(emptyList(), listOf(a), listOf(a), lt = tombs)
        assertTrue(r.notebooks.isEmpty())
        assertEquals(2, r.tombstones.size)
        // Deleted there: it goes here too.
        val r2 = merge(listOf(a), emptyList(), listOf(a), rt = tombs)
        assertEquals(listOf("a"), r2.removeNotebooks)
        assertEquals(1, r2.pagesRemoved)
    }

    @Test fun deletedNotebookWithNewPageHereSurvives() {
        val a = nb("a", pages = arrayOf(page("p1")))
        val tombs = listOf(Tombstone("a", notebook = true, at = 150), Tombstone("p1", hash = "h-p1", at = 150))
        val here = nb("a", updatedAt = 300, pages = arrayOf(page("p1"), page("neu", "x", 300)))
        val r = merge(listOf(here), emptyList(), listOf(a), rt = tombs)
        assertTrue(r.removeNotebooks.isEmpty())
        assertEquals(listOf("neu"), r.only().ids())
        assertEquals(1, r.warnings.size)
    }

    @Test fun massDeletionIsHeldBack() {
        val pages = (1..40).map { page("p$it") }.toTypedArray()
        val local = nb(pages = pages)
        val tombs = pages.map { Tombstone(it.id, hash = it.hash, at = 500) } + Tombstone("n", notebook = true, at = 500)
        val r = merge(listOf(local), emptyList(), listOf(local), rt = tombs)
        assertTrue(r.removeNotebooks.isEmpty())
        assertEquals(0, r.pagesRemoved)
        assertEquals(1, r.warnings.size)
        assertEquals(0, r.conflicts.size)
    }

    @Test fun modestDeletionIsApplied() {
        val pages = (1..100).map { page("p$it") }.toTypedArray()
        val local = nb(pages = pages)
        val after = nb(updatedAt = 200, pages = pages.drop(40).toTypedArray())
        val tombs = pages.take(40).map { Tombstone(it.id, hash = it.hash, at = 500) }
        assertEquals(40, merge(listOf(local), listOf(after), listOf(local), rt = tombs).pagesRemoved)
    }

    @Test fun trashFollowsTheLaterEvent() {
        fun t(l: NotebookState, r: NotebookState) = merge(listOf(l), listOf(r)).notebooks.firstOrNull()?.notebook?.trashedAt
        val alive = nb(updatedAt = 100, pages = arrayOf(page("a")))
        val binned = alive.copy(trashedAt = 200)
        assertEquals(200, t(alive, binned), "binned there")
        assertEquals(200, t(binned, binned.copy(trashedAt = 150)) ?: 200)
        // Written in after it was binned: the notebook comes back.
        val written = alive.copy(updatedAt = 300)
        assertNull(t(binned, written))
        // Taken out of the bin later (touches updatedAt) on the other device.
        val restored = alive.copy(updatedAt = 250)
        assertNull(t(binned, restored))
    }

    @Test fun rearrangedPagesKeepTheirOrderAndNewOnesFindTheirPlace() {
        val base = nb(pages = arrayOf(page("a"), page("b"), page("c")))
        val moved = nb(updatedAt = 100, pages = arrayOf(page("c"), page("a"), page("b")))
        val added = nb(updatedAt = 200, pages = arrayOf(page("a"), page("neu", "x", 200), page("b"), page("c")))
        // Only "moved" rearranged, though the other side is newer: its order wins, the new page follows "a".
        assertEquals(listOf("c", "a", "neu", "b"), merge(listOf(moved), listOf(added), listOf(base)).only().ids())
        assertEquals(listOf("c", "a", "neu", "b"), merge(listOf(added), listOf(moved), listOf(base)).only().ids())
    }

    @Test fun newPageAtTheStartStaysThere() {
        val base = nb(pages = arrayOf(page("a"), page("b")))
        val front = nb(updatedAt = 200, pages = arrayOf(page("neu", "x", 200), page("a"), page("b")))
        assertEquals(listOf("neu", "a", "b"), merge(listOf(base), listOf(front), listOf(base)).only().ids())
    }

    @Test fun deviceStaysOnItsPage() {
        val here = nb(pages = arrayOf(page("a"), page("b"))).copy(lastPage = 1)
        val there = nb(updatedAt = 200, pages = arrayOf(page("neu", "x", 200), page("a"), page("b")))
        assertEquals(2, merge(listOf(here), listOf(there), listOf(nb(pages = arrayOf(page("a"), page("b"))))).only().notebook.lastPage)
    }

    @Test fun fieldsMergeSeparately() {
        val base = nb(title = "Alt", pages = arrayOf(page("a")))
        val renamed = nb(updatedAt = 100, title = "Neu", pages = arrayOf(page("a")))
        val bookmarked = nb(updatedAt = 200, title = "Alt", pages = arrayOf(page("a", bookmarked = true)))
        val r = merge(listOf(renamed), listOf(bookmarked), listOf(base)).only()
        assertEquals("Neu", r.notebook.title, "the older notebook made this change, the newer did not touch it")
        assertTrue(r.pages.single().page.bookmarked)
        assertEquals(200, r.notebook.updatedAt)
    }

    @Test fun aMovedPageHasOneHome() {
        val base = listOf(nb("a", pages = arrayOf(page("p1"), page("p2"))), nb("b", pages = arrayOf(page("p3"))))
        val moved = listOf(nb("a", updatedAt = 200, pages = arrayOf(page("p1"))), nb("b", updatedAt = 200, pages = arrayOf(page("p3"), page("p2"))))
        val r = merge(base, moved, base)
        assertEquals(listOf("p1"), r.notebooks.single { it.notebook.id == "a" }.ids())
        assertEquals(listOf("p3", "p2"), r.notebooks.single { it.notebook.id == "b" }.ids())
        assertTrue(r.removeFiles.isEmpty(), "its files stay")
        assertEquals(0, r.pagesRemoved)
        // The side that moved it has nothing to do.
        assertTrue(merge(moved, base, base).notebooks.isEmpty())
    }

    @Test fun movedTwiceWithoutBaseStillGivesOneHome() {
        val x = listOf(nb("a", updatedAt = 300, pages = arrayOf(page("p"))), nb("b", updatedAt = 100, pages = emptyArray()))
        val y = listOf(nb("a", updatedAt = 300, pages = emptyArray()), nb("b", updatedAt = 100, pages = arrayOf(page("p"))))
        val onX = merge(x, y)
        val onY = merge(y, x)
        fun pagesOf(p: NotebookMergePlan, l: List<NotebookState>) = l.associate { it.id to it.pages.map { q -> q.id } } + p.notebooks.associate { it.notebook.id to it.ids() }
        assertEquals(mapOf("a" to listOf("p"), "b" to emptyList()), pagesOf(onX, x))
        assertEquals(pagesOf(onX, x), pagesOf(onY, y))
    }

    @Test fun removingANotebookKeepsPagesThatMovedOut() {
        val base = listOf(nb("a", pages = arrayOf(page("p1"))), nb("b", pages = arrayOf(page("p2"), page("p3"))))
        val there = listOf(nb("a", updatedAt = 200, pages = arrayOf(page("p1"), page("p2"))))
        val tombs = listOf(Tombstone("b", notebook = true, at = 200), Tombstone("p3", hash = "h-p3", at = 200))
        val r = merge(base, there, base, rt = tombs)
        assertEquals(listOf("b"), r.removeNotebooks)
        assertEquals(listOf("p3"), r.removeFiles)
        assertEquals(listOf("p1", "p2"), r.only().ids())
    }

    @Test fun bookmarkWithoutTimeStampWins() {
        val base = nb(pages = arrayOf(page("a")))
        val marked = nb(pages = arrayOf(page("a", bookmarked = true)))
        val onOther = merge(listOf(base), listOf(marked), listOf(base)).only()
        assertTrue(onOther.pages.single().page.bookmarked, "same updatedAt, still taken")
        // On the side that set it: nothing to do, in particular no loss.
        assertTrue(merge(listOf(marked), listOf(base), listOf(base)).notebooks.isEmpty())
        // Removing is a change as well.
        assertFalse(merge(listOf(marked), listOf(base), listOf(marked)).only().pages.single().page.bookmarked)
        // Without a base it stays, from either side.
        assertTrue(merge(listOf(base), listOf(marked), null).only().pages.single().page.bookmarked)
        assertTrue(merge(listOf(marked), listOf(base), null).notebooks.isEmpty())
    }

    @Test fun mergingTwiceChangesNothing() {
        val l = listOf(nb(updatedAt = 200, pages = arrayOf(page("a", "x", 200), page("b"))))
        val r = listOf(nb(updatedAt = 300, pages = arrayOf(page("a", "y", 300), page("c", "z", 300))))
        val first = merge(l, r).only().notebook
        val second = merge(listOf(first), r, r)
        assertTrue(second.notebooks.isEmpty(), "got ${second.notebooks}")
    }

    @Test fun tombstonesExpire() {
        val r = merge(emptyList(), emptyList(), lt = listOf(Tombstone("x", at = 1_000_000 - NotebookMerge.TOMBSTONE_DAYS * 86_400_000L - 1)))
        assertTrue(r.tombstones.isEmpty())
    }

    @Test fun tombstonesComeFromTheDifference() {
        val base = nb(pages = arrayOf(page("a"), page("b")))
        val state = NotebookSyncState(base = listOf(base), hasBase = true)
        val u = NotebookMerge.updateTombstones(state, listOf(nb(pages = arrayOf(page("a")))), emptyList(), 500)
        assertEquals(listOf(Tombstone("b", false, "h-b", 500)), u.tombstones)
        assertEquals(0, u.held)
        val gone = NotebookMerge.updateTombstones(state, emptyList(), emptyList(), 500)
        assertEquals(setOf("a", "b", "n"), gone.tombstones.map { it.id }.toSet())
        assertTrue(gone.tombstones.single { it.id == "n" }.notebook)
    }

    @Test fun exportedPagesCountAsKnownToo() {
        val state = NotebookMerge.exported(NotebookSyncState(), listOf(nb(pages = arrayOf(page("a")))))
        val u = NotebookMerge.updateTombstones(state, listOf(nb(pages = emptyArray())), emptyList(), 500)
        assertEquals(listOf("a"), u.tombstones.map { it.id })
    }

    @Test fun lostDatabaseMakesNoTombstones() {
        val pages = (1..50).map { page("p$it") }.toTypedArray()
        val state = NotebookSyncState(base = listOf(nb(pages = pages)), hasBase = true)
        val u = NotebookMerge.updateTombstones(state, emptyList(), emptyList(), 500)
        assertTrue(u.tombstones.isEmpty(), "an empty device must not delete everything elsewhere")
        assertEquals(50, u.held)
    }

    @Test fun pageHash() {
        assertNull(NotebookMerge.pageHash(null, 0, null))
        assertNull(NotebookMerge.pageHash("egal", NotebookMerge.EMPTY_INK_SIZE, null))
        val a = assertNotNull(NotebookMerge.pageHash("a", 100, null))
        assertFalse(a == NotebookMerge.pageHash("b", 100, null))
        assertFalse(a == NotebookMerge.pageHash("a", 100, "i"))
        assertEquals(NotebookMerge.pageHash(null, 0, "i"), NotebookMerge.pageHash("x", 8, "i"), "an ink file without strokes is no ink")
        assertNotNull(NotebookMerge.pageHash(null, 0, null, "scan.jpg"), "a scan is content")
    }
}
