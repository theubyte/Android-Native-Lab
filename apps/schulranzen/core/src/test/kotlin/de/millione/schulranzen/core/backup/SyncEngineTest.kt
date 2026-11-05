package de.millione.schulranzen.core.backup

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The server in memory; the same semantics as `server/unraid-sync/app.py` (tested there separately). */
class FakeRemote : SyncRemote {
    var rev: String? = null
    var manifest: ByteArray? = null
    val blobs = HashMap<String, ByteArray>()
    var commits = 0
    var failNextPut = false
    var failNextGet = false
    var failCommit = false
    /** Runs right before a commit is checked: another device is faster. */
    var beforeCommit: (() -> Unit)? = null
    val getBytes = ArrayList<String>()
    val putIds = ArrayList<String>()

    override fun head(): RemoteHead? = rev?.let { RemoteHead(it, manifest!!) }
    override fun missing(ids: List<String>) = ids.filter { it !in blobs }
    override fun putBlob(id: String, file: File, sha256: String, progress: (Long, Long) -> Unit) {
        if (failNextPut) { failNextPut = false; throw SyncException(SyncError.UNREACHABLE, "Verbindung weg") }
        val b = file.readBytes()
        if (Hashing.sha256(b.inputStream()) != sha256) throw SyncConflictException()
        blobs[id] = b
        putIds += id
        progress(b.size.toLong(), b.size.toLong())
    }
    override fun getBlob(id: String, target: File, progress: (Long, Long) -> Unit) {
        if (failNextGet) { failNextGet = false; throw SyncException(SyncError.UNREACHABLE, "Verbindung weg") }
        val b = blobs[id] ?: throw SyncException(SyncError.NOT_FOUND, "fehlt")
        target.parentFile?.mkdirs()
        target.writeBytes(b)
        getBytes += id
        progress(b.size.toLong(), b.size.toLong())
    }
    override fun commit(expected: String?, manifest: ByteArray, blobs: List<String>, device: String): String {
        beforeCommit?.let { beforeCommit = null; it() }
        if (failCommit) { failCommit = false; throw SyncException(SyncError.UNREACHABLE, "Verbindung weg") }
        if (expected != rev) throw SyncConflictException()
        if (blobs.any { it !in this.blobs }) throw SyncConflictException()
        commits++
        rev = "rev$commits"
        this.manifest = manifest
        return rev!!
    }
}

/**
 * A whole device without Android: the database is a JSON file in
 * `databases/`, everything else the real files and the real merge code.
 */
class Device(root: File, private val remote: SyncRemote, val name: String = "Gerät", var password: CharArray? = null, var encrypt: Boolean = false) {
    val files = File(root, "files").apply { mkdirs() }
    val databases = File(root, "databases").apply { mkdirs() }
    private val noBackup = File(root, "no_backup").apply { mkdirs() }
    private val work = File(root, "cache/sync")
    val db = File(databases, "ranzen.json")
    val ink = File(files, "ink").apply { mkdirs() }
    val images = File(files, "images").apply { mkdirs() }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val list = ListSerializer(NotebookState.serializer())

    private val store = object : NotebookStore {
        override suspend fun readLocal() = notebooks()
        override suspend fun readRemote(backupDir: File): List<NotebookState>? {
            val f = File(backupDir, "databases/ranzen.json")
            return if (f.isFile) json.decodeFromString(list, f.readText()) else null
        }
        override suspend fun apply(write: List<NotebookState>, delete: List<String>) {
            val written = write.map { n -> n.copy(pages = n.pages.map { it.copy(hash = null, modified = 0) }) }
            val taken = written.flatMapTo(HashSet()) { n -> n.pages.map { it.id } }
            val rest = notebooks().filter { n -> n.id !in delete && written.none { it.id == n.id } }
                .map { n -> n.copy(pages = n.pages.filter { it.id !in taken }) }
            val result = rest + written
            val all = result.flatMap { n -> n.pages.map { it.id } }
            check(all.size == all.toSet().size) { "Seite doppelt: $all" }
            save(result)
        }
    }
    companion object {
        /** Eight bytes: as long as a real ink file without strokes, which counts as blank. */
        const val INK_HEAD = "RNZ1\u0000\u0000\u0000\u0001"
    }

    val notebooksParticipant = NotebookSyncParticipant(
        store, ink, images, File(files, "sync/tombstones.json"), File(noBackup, "notebooks.json"), HashCache(File(noBackup, "nb-hashes.json")),
    )
    val participants: List<MergeParticipant> = listOf(
        FolderMergeParticipant(images, name = "Bilder"),
        FolderMergeParticipant(File(files, "books"), name = "Bücher"),
        notebooksParticipant,
    )

    fun notebooks(): List<NotebookState> = if (db.isFile) json.decodeFromString(list, db.readText()) else emptyList()
    private fun save(n: List<NotebookState>) = db.writeText(json.encodeToString(list, n.sortedBy { it.id }))

    private var clock = 1_000_000L
    private fun tick() = (++clock).also { clock += 1000 }

    fun create(id: String, vararg pages: String) = save(notebooks() + NotebookState(id, id, "Mathe", "RULED", 1, tick(), pages = pages.map { PageState(it, "RULED") }))
    private fun change(id: String, f: (NotebookState) -> NotebookState) = save(notebooks().map { if (it.id == id) f(it).copy(updatedAt = tick()) else it })

    /** The app writing on a page. */
    fun write(nb: String, page: String, content: String, at: Long = tick()) {
        File(ink, "$page.rnz").apply { writeText(INK_HEAD + content); setLastModified(at) }
        change(nb) { it }
    }
    fun addPage(nb: String, page: String, content: String? = null) {
        change(nb) { it.copy(pages = it.pages + PageState(page, "RULED")) }
        if (content != null) write(nb, page, content)
    }
    fun removePage(nb: String, page: String) {
        change(nb) { it.copy(pages = it.pages.filter { p -> p.id != page }) }
        File(ink, "$page.rnz").delete()
    }
    fun deleteNotebook(nb: String) {
        notebooks().first { it.id == nb }.pages.forEach { File(ink, "${it.id}.rnz").delete() }
        save(notebooks().filter { it.id != nb })
    }
    fun scan(nb: String, page: String, image: String, bytes: ByteArray) {
        File(images, image).writeBytes(bytes)
        change(nb) { it.copy(pages = it.pages + PageState(page, "BLANK", background = image)) }
    }
    /** Page overview: to the end of another notebook, same id. */
    fun movePage(from: String, to: String, page: String) {
        val p = page(from, page)
        change(from) { it.copy(pages = it.pages.filter { x -> x.id != page }) }
        change(to) { it.copy(pages = it.pages + p) }
    }
    /** "Hefte zusammenführen": all pages to the end of [into], the other one is gone. */
    fun mergeNotebooks(from: String, into: String) {
        val moved = notebooks().first { it.id == from }.pages
        change(into) { it.copy(pages = it.pages + moved) }
        save(notebooks().filter { it.id != from })
    }

    /** Like the page menu: without touching updatedAt. */
    fun setBookmark(nb: String, page: String, on: Boolean) =
        save(notebooks().map { n -> if (n.id != nb) n else n.copy(pages = n.pages.map { if (it.id == page) it.copy(bookmarked = on) else it }) })
    fun setLock(nb: String, page: String, on: Boolean) =
        save(notebooks().map { n -> if (n.id != nb) n else n.copy(pages = n.pages.map { if (it.id == page) it.copy(locked = on) else it }) })
    fun setPaper(nb: String, page: String, paper: String) =
        save(notebooks().map { n -> if (n.id != nb) n else n.copy(pages = n.pages.map { if (it.id == page) it.copy(paper = paper) else it }) })
    /** Inserted behind [after], as the scan flow does. */
    fun scanAfter(nb: String, after: String, page: String, image: String, bytes: ByteArray) {
        File(images, image).writeBytes(bytes)
        change(nb) { n ->
            val i = n.pages.indexOfFirst { it.id == after } + 1
            n.copy(pages = n.pages.take(i) + PageState(page, "BLANK", background = image) + n.pages.drop(i))
        }
    }
    fun page(nb: String, page: String) = notebooks().first { it.id == nb }.pages.first { it.id == page }
    fun pages(nb: String) = notebooks().first { it.id == nb }.pages.map { it.id }
    fun content(page: String) = File(ink, "$page.rnz").takeIf { it.isFile }?.readText()?.removePrefix(INK_HEAD)

    private val hashes = HashCache(File(noBackup, "hashes.json"))

    fun engine() = SyncEngine(
        remote, listOf(BackupRoot("files", files) { it.endsWith(".tmp") }, BackupRoot("databases", databases)),
        work, File(noBackup, "sync.json"), hashes, participants, password, encrypt, name,
    )

    fun sync(): SyncResult = runSuspend { engine().run() }

    /** A full backup of this device as a file, for sharing. */
    fun backupFile(to: File): File {
        runSuspend { participants.forEach { it.prepareExport() } }
        to.outputStream().use { FullBackup.write(listOf(BackupRoot("files", files), BackupRoot("databases", databases)), it, BackupManifest()) }
        return to
    }

    /** Merging a file from another device: the same way as the server's. */
    fun mergeFile(zip: File): List<MergeReport> = runSuspend {
        BackupMerge.run(zip, File(work.parentFile, "stage-file"), participants, setOf("files", "databases"))
    }

}

class SyncEngineTest {
    @Test fun movedPageIsNotDoubled() {
        tablet.create("a", "p1", "p2"); tablet.create("b", "p3")
        tablet.write("a", "p2", "wandert")
        tablet.sync(); phone.sync()

        tablet.movePage("a", "b", "p2")
        tablet.sync()
        val r = phone.sync()
        assertEquals(0, r.conflicts)
        assertEquals(0, r.removed)
        for (d in listOf(tablet, phone)) {
            assertEquals(listOf("p1"), d.pages("a"), d.name)
            assertEquals(listOf("p3", "p2"), d.pages("b"), d.name)
            assertEquals("wandert", d.content("p2"), d.name)
        }
        tablet.sync(); phone.sync()
        assertEquals(listOf("p3", "p2"), tablet.pages("b"))
    }

    @Test fun movedHereAndEditedThereKeepsTheEditAtTheNewPlace() {
        tablet.create("a", "p1", "p2"); tablet.create("b", "p3")
        tablet.write("a", "p2", "Anfang")
        tablet.sync(); phone.sync()
        tablet.movePage("a", "b", "p2")
        phone.write("a", "p2", "auf dem Handy geändert", at = 9_000_000)
        phone.sync(); tablet.sync(); phone.sync()
        for (d in listOf(tablet, phone)) {
            assertFalse("p2" in d.pages("a"), d.name)
            assertTrue("p2" in d.pages("b"), d.name)
            assertEquals("auf dem Handy geändert", d.content("p2"), d.name)
        }
    }

    @Test fun mergedNotebooksKeepEveryPageEverywhere() {
        tablet.create("a", "p1"); tablet.create("b", "p2", "p3")
        listOf("p1", "p2", "p3").forEach { tablet.write(if (it == "p1") "a" else "b", it, "Inhalt $it") }
        tablet.sync(); phone.sync()
        tablet.mergeNotebooks("b", "a")
        tablet.sync()
        val r = phone.sync()
        assertEquals(0, r.removed, "the pages live on in the other notebook")
        for (d in listOf(tablet, phone)) {
            assertEquals(listOf("a"), d.notebooks().map { it.id }, d.name)
            assertEquals(listOf("p1", "p2", "p3"), d.pages("a"), d.name)
            listOf("p1", "p2", "p3").forEach { assertEquals("Inhalt $it", d.content(it), d.name) }
        }
    }

    @Test fun damagedCopiesAreLeftAlone() {
        tablet.create("heft", "p1")
        tablet.write("heft", "p1", "x")
        tablet.sync(); phone.sync()
        val keep = File(phone.ink, "p1.rnz.damaged").apply { writeText("Rettungsgut") }
        tablet.write("heft", "p1", "y")
        tablet.sync(); phone.sync()
        assertEquals("y", phone.content("p1"))
        assertEquals("Rettungsgut", keep.readText())
    }

    @Test fun bookmarksLocksAndPaperTravelWithoutATimeStamp() {
        tablet.create("heft", "p1", "p2")
        tablet.write("heft", "p1", "eins")
        tablet.sync(); phone.sync()

        phone.setBookmark("heft", "p1", true)
        tablet.setLock("heft", "p2", true)
        tablet.setPaper("heft", "p1", "GRID")
        phone.sync(); tablet.sync(); phone.sync()
        for (d in listOf(tablet, phone)) {
            assertTrue(d.page("heft", "p1").bookmarked, d.name + " Lesezeichen")
            assertTrue(d.page("heft", "p2").locked, d.name + " Sperre")
            assertEquals("GRID", d.page("heft", "p1").paper, d.name + " Papier")
        }
        // Taking it away travels as well.
        tablet.setBookmark("heft", "p1", false)
        tablet.sync(); phone.sync()
        assertFalse(phone.page("heft", "p1").bookmarked)
        assertFalse(tablet.page("heft", "p1").bookmarked)
    }

    @Test fun firstMergeKeepsABookmarkFromEitherSide() {
        val offline = FakeRemote()
        val a = Device(tmp.newFolder("a1"), offline, "A")
        a.create("heft", "p1")
        val file = a.backupFile(tmp.newFile("x.zip"))
        val b = Device(tmp.newFolder("b1"), offline, "B")
        b.mergeFile(file)
        val again = Device(tmp.newFolder("c1"), offline, "C")
        again.mergeFile(file)
        again.setBookmark("heft", "p1", true)
        // C knows nothing about A's later state, and A nothing about the bookmark.
        val a2 = Device(tmp.newFolder("a2"), offline, "A2")
        a2.mergeFile(again.backupFile(tmp.newFile("y.zip")))
        assertTrue(a2.page("heft", "p1").bookmarked)
    }

    @Test fun scanBehindAnExistingPageWithItsPicture() {
        tablet.create("heft", "p1", "p2", "p3")
        tablet.write("heft", "p1", "eins")
        tablet.sync(); phone.sync()
        val photo = ByteArray(40_000) { (it % 200).toByte() }
        phone.scanAfter("heft", "p1", "scan", "blatt.jpg", photo)
        tablet.write("heft", "p3", "drei")
        phone.sync(); tablet.sync(); phone.sync()
        for (d in listOf(tablet, phone)) {
            assertEquals(listOf("p1", "scan", "p2", "p3"), d.pages("heft"), d.name)
            assertEquals("blatt.jpg", d.page("heft", "scan").background, d.name)
            assertContentEquals(photo, File(d.images, "blatt.jpg").readBytes(), d.name)
        }
    }

    @Test fun deletedScanStaysDeletedAndCountsAsContent() {
        tablet.create("heft", "p1")
        tablet.scan("heft", "scan", "b.jpg", byteArrayOf(1, 2))
        tablet.sync(); phone.sync()
        assertEquals("b.jpg", phone.page("heft", "scan").background)
        phone.removePage("heft", "scan")
        phone.sync(); tablet.sync(); phone.sync()
        assertEquals(listOf("p1"), tablet.pages("heft"))
        assertEquals(listOf("p1"), phone.pages("heft"))
    }

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: FakeRemote
    private lateinit var tablet: Device
    private lateinit var phone: Device

    @Before fun setUp() {
        server = FakeRemote()
        tablet = Device(tmp.newFolder("tablet"), server, "Tablet")
        phone = Device(tmp.newFolder("phone"), server, "Handy")
    }

    @Test fun phoneScansAndTabletWritesInTheSameNotebook() {
        tablet.create("heft", "p1", "p2")
        tablet.write("heft", "p1", "erste Seite")
        tablet.sync()
        val first = phone.sync()
        assertEquals("erste Seite", phone.content("p1"))
        assertEquals(listOf("p1", "p2"), phone.pages("heft"))
        assertEquals(2, first.reports.first { it.name == "Hefte" }.added)

        // Offline at the same time: the phone scans, the tablet writes.
        val photo = ByteArray(50_000) { it.toByte() }
        phone.scan("heft", "scan", "blatt.jpg", photo)
        tablet.write("heft", "p2", "Hausaufgabe")

        val fromPhone = phone.sync()
        assertEquals(0, fromPhone.conflicts)
        val fromTablet = tablet.sync()
        assertEquals("1 Seite neu, 1 weitere Änderung.", fromTablet.headline())
        phone.sync()

        for (d in listOf(tablet, phone)) {
            assertEquals(setOf("p1", "p2", "scan"), d.pages("heft").toSet(), d.name)
            assertEquals("Hausaufgabe", d.content("p2"), d.name)
            assertContentEquals(photo, File(d.images, "blatt.jpg").readBytes(), d.name)
        }
        assertEquals(tablet.pages("heft"), phone.pages("heft"))
    }

    @Test fun bothWriteOnTheSamePageKeepsBoth() {
        tablet.create("heft", "p1")
        tablet.write("heft", "p1", "Anfang")
        tablet.sync(); phone.sync()

        phone.write("heft", "p1", "vom Handy", at = 5_000_000)
        tablet.write("heft", "p1", "vom Tablet", at = 6_000_000)
        phone.sync()
        val r = tablet.sync()
        assertEquals(1, r.conflicts)
        assertTrue(r.headline().contains("1 Konflikt"))
        phone.sync()

        for (d in listOf(tablet, phone)) {
            assertEquals(2, d.pages("heft").size, d.name)
            assertEquals("vom Tablet", d.content("p1"), d.name)
            val copy = d.pages("heft").first { it != "p1" }
            assertEquals("vom Handy", d.content(copy), d.name)
            assertTrue(d.notebooks().single().pages.first { it.id == copy }.bookmarked)
        }
        // And quiet from here on.
        val calm = tablet.sync()
        assertEquals(0, calm.conflicts)
        assertTrue(calm.upToDate || calm.added == 0)
    }

    @Test fun deletedPageDoesNotComeBackAndReachesTheOtherDevice() {
        tablet.create("heft", "p1", "p2")
        tablet.write("heft", "p1", "eins"); tablet.write("heft", "p2", "zwei")
        tablet.sync(); phone.sync()

        phone.removePage("heft", "p2")
        phone.sync()
        val r = tablet.sync()
        assertEquals(1, r.removed)
        assertEquals(listOf("p1"), tablet.pages("heft"))
        assertNull(tablet.content("p2"))

        tablet.sync(); phone.sync()
        assertEquals(listOf("p1"), phone.pages("heft"), "and it stays gone after more rounds")
        assertEquals(listOf("p1"), tablet.pages("heft"))
    }

    @Test fun deletedNotebookDoesNotComeBack() {
        tablet.create("heft", "p1"); tablet.create("zwei", "p2")
        tablet.write("heft", "p1", "x")
        tablet.sync(); phone.sync()
        tablet.deleteNotebook("heft")
        tablet.sync()
        phone.sync()
        assertEquals(listOf("zwei"), phone.notebooks().map { it.id })
        assertNull(phone.content("p1"))
        phone.sync(); tablet.sync()
        assertEquals(listOf("zwei"), tablet.notebooks().map { it.id })
    }

    @Test fun anOldBackupDeletesNothing() {
        tablet.create("heft", "p1")
        tablet.write("heft", "p1", "alt")
        val old = tablet.backupFile(tmp.newFile("alt.zip"))
        tablet.sync()
        tablet.addPage("heft", "p2", "neu")
        tablet.sync()
        val r = tablet.mergeFile(old)
        assertEquals(0, r.sumOf { it.removed })
        assertEquals(listOf("p1", "p2"), tablet.pages("heft"))
        assertEquals("neu", tablet.content("p2"))
    }

    @Test fun onlyTheDifferenceIsTransferred() {
        tablet.create("heft", "p1")
        tablet.write("heft", "p1", "x")
        val book = ByteArray(2_000_000) { (it % 251).toByte() }
        File(tablet.files, "books/b1").mkdirs()
        File(tablet.files, "books/b1/book.pdf").writeBytes(book)
        tablet.sync()
        assertTrue(server.putIds.size >= 3)
        phone.sync()
        val firstPull = server.getBytes.size
        assertTrue(firstPull >= 3)

        // A small scan: only the picture and the databases travel.
        server.putIds.clear(); server.getBytes.clear()
        phone.scan("heft", "scan", "blatt.jpg", ByteArray(1000) { 7 })
        val up = phone.sync()
        assertTrue(up.uploadedBytes < 100_000, "sent ${up.uploadedBytes}")
        val bookId = Hashing.sha256(book.inputStream())
        assertFalse(bookId in server.putIds)
        val down = tablet.sync()
        assertFalse(bookId in server.getBytes)
        assertTrue(down.downloadedBytes < 100_000, "got ${down.downloadedBytes}")
        assertContentEquals(book, File(phone.files, "books/b1/book.pdf").readBytes())
    }

    @Test fun nothingNewCostsNothing() {
        tablet.create("heft", "p1")
        tablet.sync(); phone.sync(); tablet.sync()
        val commits = server.commits
        server.getBytes.clear()
        assertTrue(tablet.sync().upToDate)
        phone.sync()
        assertTrue(phone.sync().upToDate)
        assertEquals(commits, server.commits, "devices do not take turns uploading the same")
    }

    @Test fun anotherDeviceFasterMeansMergeAgain() {
        tablet.create("heft", "p1")
        tablet.sync(); phone.sync()
        phone.addPage("heft", "vom-handy", "h")
        tablet.addPage("heft", "vom-tablet", "t")
        // The phone gets in between the tablet's merge and its commit.
        server.beforeCommit = { phone.sync() }
        val r = tablet.sync()
        assertTrue(r.added >= 1)
        phone.sync()
        for (d in listOf(tablet, phone)) assertEquals(setOf("p1", "vom-handy", "vom-tablet"), d.pages("heft").toSet(), d.name)
    }

    @Test fun neverOverwritesWithoutMerging() {
        tablet.create("heft", "p1")
        tablet.sync()
        // Someone always faster: gives up with a clear message, the server's state is theirs.
        var turns = 0
        val other = Device(tmp.newFolder("other"), server, "Drittes")
        other.sync()
        fun again() { turns++; other.addPage("heft", "o$turns", "x"); other.sync(); server.beforeCommit = { again() } }
        phone.sync()
        phone.addPage("heft", "h", "h")
        server.beforeCommit = { again() }
        val e = assertFailsWith<SyncException> { phone.sync() }
        assertTrue(e.message!!.contains("anderes Gerät"))
        server.beforeCommit = null
        phone.sync(); other.sync()
        assertEquals(phone.pages("heft").toSet(), other.pages("heft").toSet())
        assertTrue("h" in other.pages("heft"))
        assertTrue("o1" in phone.pages("heft"))
    }

    @Test fun brokenConnectionsLoseNothingAndResume() {
        tablet.create("heft", "p1")
        tablet.write("heft", "p1", "x")
        tablet.sync()

        // While fetching.
        server.failNextGet = true
        assertFailsWith<SyncException> { phone.sync() }
        assertEquals(emptyList(), phone.notebooks().map { it.id }, "nothing half done")
        phone.sync()
        assertEquals("x", phone.content("p1"))

        // While sending: merged here, server unchanged.
        val rev = server.rev
        phone.addPage("heft", "p2", "y")
        server.failNextPut = true
        assertFailsWith<SyncException> { phone.sync() }
        assertEquals(rev, server.rev)
        assertEquals("y", phone.content("p2"))
        phone.sync()
        assertTrue(server.rev != rev)

        // At the commit.
        tablet.sync()
        val rev2 = server.rev
        tablet.addPage("heft", "p3", "z")
        server.failCommit = true
        assertFailsWith<SyncException> { tablet.sync() }
        assertEquals(rev2, server.rev)
        tablet.sync(); phone.sync()
        assertEquals(setOf("p1", "p2", "p3"), phone.pages("heft").toSet())
        assertEquals("z", phone.content("p3"))
    }

    @Test fun aFailingMergeSendsNothing() {
        tablet.create("heft", "p1")
        tablet.sync()
        val rev = server.rev
        val bad = object : MergeParticipant {
            override val name = "Kaputt"
            override suspend fun merge(backupDir: File): MergeReport = throw IOException("geht nicht")
        }
        val phoneRoot = tmp.newFolder("p3")
        val d = Device(phoneRoot, server, "x")
        val engine = SyncEngine(
            server, listOf(BackupRoot("files", d.files), BackupRoot("databases", d.databases)), File(phoneRoot, "w"), File(phoneRoot, "s.json"),
            HashCache(File(phoneRoot, "h.json")), listOf(bad), null, false, "x",
        )
        assertFailsWith<SyncMergeFailedException> { runSuspend { engine.run() } }
        assertEquals(rev, server.rev)
    }

    @Test fun encryptedStateIsUnreadableForTheServer() {
        val pw = "korrekt-pferd".toCharArray()
        val secret = Device(tmp.newFolder("s1"), server, "A", pw, encrypt = true)
        val other = Device(tmp.newFolder("s2"), server, "B", pw, encrypt = false)
        secret.create("heft", "p1")
        secret.write("heft", "p1", "streng geheim")
        secret.sync()
        val all = server.blobs.values.toList() + server.manifest!!
        assertTrue(all.none { b -> String(b, Charsets.ISO_8859_1).contains("streng geheim") })
        assertTrue(all.none { b -> String(b, Charsets.ISO_8859_1).contains("heft") })
        val sha = Hashing.sha256((Device.INK_HEAD + "streng geheim").byteInputStream())
        assertFalse(sha in server.blobs.keys, "names do not give away known files")

        other.sync()
        assertEquals("streng geheim", other.content("p1"))
        // The second device has encryption off, but must never downgrade the state.
        other.addPage("heft", "p2", "auch geheim")
        other.sync()
        assertTrue(BackupCrypto.MAGIC.contentEquals(server.manifest!!.copyOf(8)))
        secret.sync()
        assertEquals("auch geheim", secret.content("p2"))
    }

    @Test fun wrongOrMissingPassword() {
        val a = Device(tmp.newFolder("s1"), server, "A", "richtig-richtig".toCharArray(), encrypt = true)
        a.create("heft", "p1")
        a.sync()
        val rev = server.rev
        val none = Device(tmp.newFolder("s2"), server, "B")
        assertFalse(assertFailsWith<SyncPasswordException> { none.sync() }.wrong)
        val wrong = Device(tmp.newFolder("s3"), server, "C", "falsch-falsch".toCharArray())
        assertTrue(assertFailsWith<SyncPasswordException> { wrong.sync() }.wrong)
        assertEquals(rev, server.rev)
    }

    @Test fun manifestFromTheServerCannotEscape() {
        for (path in listOf("../../evil", "files/../../evil", "/etc/passwd", "shared_prefs/x.xml", "files\\..\\x")) {
            val entry = SyncEntry(path, 1, "00", 1, "a".repeat(64))
            server.blobs["a".repeat(64)] = byteArrayOf(1)
            server.rev = "r"
            server.manifest = Json.encodeToString(SyncManifest.serializer(), SyncManifest(entries = listOf(entry))).toByteArray()
            val d = Device(tmp.newFolder(), server)
            val e = assertFailsWith<BackupException>(path) { d.sync() }
            assertTrue(e.message!!.contains("Unsicher"), path)
        }
    }

    @Test fun damagedFileFromTheServerIsRefused() {
        tablet.create("heft", "p1")
        tablet.write("heft", "p1", "x")
        tablet.sync()
        server.blobs.replaceAll { _, v -> v + 1 }
        val e = assertFailsWith<BackupException> { phone.sync() }
        assertTrue(e.message!!.contains("beschädigt"))
        assertTrue(phone.notebooks().isEmpty())
    }

    @Test fun fileBasedExchangeGoesTheSameWay() {
        val offline = FakeRemote()
        val a = Device(tmp.newFolder("a"), offline, "A")
        val b = Device(tmp.newFolder("b"), offline, "B")
        a.create("heft", "p1", "p2")
        a.write("heft", "p1", "eins"); a.write("heft", "p2", "zwei")
        b.mergeFile(a.backupFile(tmp.newFile("1.zip")))
        assertEquals("eins", b.content("p1"))

        // Both work apart, including a deletion.
        b.scan("heft", "scan", "s.jpg", byteArrayOf(1, 2, 3))
        a.write("heft", "p1", "eins neu")
        a.removePage("heft", "p2")

        val toB = a.backupFile(tmp.newFile("2.zip"))
        val toA = b.backupFile(tmp.newFile("3.zip"))
        val rb = b.mergeFile(toB)
        val ra = a.mergeFile(toA)
        assertEquals(0, ra.sumOf { it.conflicts } + rb.sumOf { it.conflicts })
        for (d in listOf(a, b)) {
            assertEquals(setOf("p1", "scan"), d.pages("heft").toSet(), d.name)
            assertEquals("eins neu", d.content("p1"), d.name)
            assertTrue(File(d.images, "s.jpg").exists(), d.name)
        }
    }

    @Test fun afterARestoreTheNextSyncKnowsWhereItStands() {
        tablet.create("heft", "p1", "p2")
        tablet.write("heft", "p1", "x"); tablet.write("heft", "p2", "y")
        val backup = tablet.backupFile(tmp.newFile("b.zip"))
        tablet.sync()

        // Restored onto the phone. Then the tablet edits; the phone edited nothing.
        val stage = tmp.newFolder("stage")
        FullBackup.extractVerified(backup, stage, setOf("files", "databases"))
        runSuspend { phone.notebooksParticipant.beforeRestore(stage) }
        FullBackup.swap(stage, mapOf("files" to phone.files, "databases" to phone.databases), tmp.newFolder("alt"))
        phone.notebooksParticipant.afterRestore()

        tablet.write("heft", "p1", "x neu")
        tablet.removePage("heft", "p2")
        tablet.sync()
        val r = phone.sync()
        assertEquals(0, r.conflicts, "no false conflicts just because the phone was restored")
        assertEquals("x neu", phone.content("p1"))
        assertEquals(listOf("p1"), phone.pages("heft"))
    }

    @Test fun emptiedDeviceDoesNotWipeTheOthers() {
        tablet.create("heft", *(1..50).map { "p$it" }.toTypedArray())
        (1..50).forEach { tablet.write("heft", "p$it", "Seite $it") }
        tablet.sync(); phone.sync()
        // The phone loses its data (database damaged, files gone).
        phone.db.delete()
        phone.ink.listFiles()!!.forEach { it.delete() }
        val r = phone.sync()
        assertEquals(50, phone.pages("heft").size, "it gets everything back instead")
        assertEquals("Seite 7", phone.content("p7"))
        assertTrue(r.warnings.isEmpty() || r.reports.isNotEmpty())
        tablet.sync()
        assertEquals(50, tablet.pages("heft").size)
    }

    @Test fun threeDevicesConverge() {
        val third = Device(tmp.newFolder("third"), server, "Drittes")
        tablet.create("heft", "p1")
        tablet.sync(); phone.sync(); third.sync()
        tablet.addPage("heft", "t", "t"); phone.addPage("heft", "h", "h"); third.addPage("heft", "d", "d")
        repeat(3) { tablet.sync(); phone.sync(); third.sync() }
        val expected = setOf("p1", "t", "h", "d")
        for (d in listOf(tablet, phone, third)) assertEquals(expected, d.pages("heft").toSet(), d.name)
        assertEquals(tablet.pages("heft"), phone.pages("heft"))
        assertEquals(tablet.pages("heft"), third.pages("heft"))
    }

    @Test fun progressCanCancel() {
        tablet.create("heft", "p1")
        val engine = SyncEngine(
            server, listOf(BackupRoot("files", tablet.files), BackupRoot("databases", tablet.databases)), File(tmp.root, "w"), File(tmp.root, "s.json"),
            HashCache(File(tmp.root, "h.json")), tablet.participants, null, false, "x",
            progress = { step, _, _ -> if (step == "Hochladen") throw kotlin.coroutines.cancellation.CancellationException("Abbruch") },
        )
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { runSuspend { engine.run() } }
        assertNull(server.rev)
    }

    /** Against the real Flask server, when SCHULRANZEN_SYNC_URL is set. */
    @Test fun realServerFlow() {
        val real = System.getenv("SCHULRANZEN_SYNC_URL")
        Assume.assumeTrue("SCHULRANZEN_SYNC_URL nicht gesetzt", !real.isNullOrBlank())
        val client = SyncClient(real!!)
        client.register("sync-${System.nanoTime()}@example.org", "123456")
        assertNull(client.head())
        val a = Device(tmp.newFolder("ra"), client, "A")
        val b = Device(tmp.newFolder("rb"), client, "B")
        a.create("heft", "p1", "p2")
        a.write("heft", "p1", "eins")
        a.sync()
        b.sync()
        assertEquals("eins", b.content("p1"))
        b.scan("heft", "scan", "s.jpg", ByteArray(300_000) { it.toByte() })
        a.write("heft", "p2", "zwei")
        b.sync(); a.sync(); b.sync()
        for (d in listOf(a, b)) {
            assertEquals(setOf("p1", "p2", "scan"), d.pages("heft").toSet())
            assertEquals("zwei", d.content("p2"), d.name)
            assertEquals(300_000, File(d.images, "s.jpg").length())
        }
        // A refused commit.
        val stale = client.head()!!.rev
        a.addPage("heft", "p3", "drei")
        a.sync()
        assertFailsWith<SyncConflictException> { client.commit(stale, "x".toByteArray(), emptyList(), "t") }
        b.sync()
        assertEquals("drei", b.content("p3"))
        assertEquals(emptyList(), client.missing(emptyList()))
    }
}
