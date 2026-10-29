package de.millione.schulranzen.core.backup

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var data: File

    private fun app(): Map<String, File> {
        data = tmp.newFolder("data")
        val files = File(data, "files").apply { mkdirs() }
        File(files, "ink").mkdirs()
        File(files, "ink/p1.rnz").writeBytes(ByteArray(5000) { (it % 7).toByte() })
        File(files, "ink/p1.items.json").writeText("[]")
        File(files, "books/b.pdf").apply { parentFile.mkdirs(); writeText("%PDF") }
        File(files, "study").mkdirs() // empty folder
        val db = File(data, "databases").apply { mkdirs() }
        File(db, "ranzen.db").writeText("sqlite")
        File(db, "ranzen.db-wal").writeText("")
        File(db, "ranzen.db-shm").writeText("shm")
        val prefs = File(data, "shared_prefs").apply { mkdirs() }
        File(prefs, "settings.xml").writeText("<map/>")
        File(prefs, "backup_sync.xml").writeText("<map><string name=\"token\">geheim</string></map>")
        return mapOf("files" to files, "databases" to db, "shared_prefs" to prefs)
    }

    private fun roots(t: Map<String, File>) = listOf(
        BackupRoot("files", t.getValue("files")),
        BackupRoot("databases", t.getValue("databases")) { it.endsWith("-shm") },
        BackupRoot("shared_prefs", t.getValue("shared_prefs")) { it == "backup_sync.xml" },
    )

    private fun backup(t: Map<String, File>): File {
        val zip = tmp.newFile("backup.zip")
        var last = 0L to 0L
        val m = zip.outputStream().use { FullBackup.write(roots(t), it, BackupManifest(appVersion = "0.3.0", versionCode = 3, created = 42)) { d, total -> last = d to total } }
        assertEquals(last.first, last.second)
        assertEquals(listOf("databases/ranzen.db", "databases/ranzen.db-wal", "files/books/b.pdf", "files/ink/p1.items.json", "files/ink/p1.rnz", "shared_prefs/settings.xml"), m.entries.map { it.path })
        return zip
    }

    @Test fun roundTripReplacesEverything() {
        val t = app()
        val zip = backup(t)
        assertEquals(ArchiveKind.OWN, FullBackup.detect(zip))
        val m = FullBackup.readManifest(zip)
        assertEquals("0.3.0", m.appVersion)
        assertEquals(Hashing.sha256(File(t["files"], "ink/p1.rnz")), m.entries.single { it.path == "files/ink/p1.rnz" }.sha256)

        // Change the live data after the backup.
        File(t["files"], "ink/p1.rnz").writeText("neu")
        File(t["files"], "ink/p2.rnz").writeText("neue Seite")
        File(t["databases"], "books.db").writeText("x")
        File(t["shared_prefs"], "backup_sync.xml").writeText("<map>neues Konto</map>")

        val stage = File(data, "restore-stage")
        FullBackup.extractVerified(zip, stage, t.keys)
        FullBackup.swap(stage, t, File(data, "restore-old"), preserve = listOf("shared_prefs/backup_sync.xml"))

        assertEquals(5000, File(t["files"], "ink/p1.rnz").length())
        assertFalse(File(t["files"], "ink/p2.rnz").exists())
        assertFalse(File(t["databases"], "books.db").exists())
        assertFalse(File(t["databases"], "ranzen.db-shm").exists())
        assertEquals("sqlite", File(t["databases"], "ranzen.db").readText())
        // The account stays as it is on this device.
        assertEquals("<map>neues Konto</map>", File(t["shared_prefs"], "backup_sync.xml").readText())
        assertFalse(stage.exists())
        assertFalse(File(data, "restore-old").exists())
    }

    @Test fun tamperedEntryIsRejectedBeforeAnythingChanges() {
        val t = app()
        val zip = backup(t)
        val bad = tmp.newFile("bad.zip")
        rewrite(zip, bad) { name, bytes -> if (name == "files/books/b.pdf") "%PDX".toByteArray() else bytes }
        val e = assertFailsWith<BackupException> { FullBackup.extractVerified(bad, File(data, "stage"), t.keys) }
        assertTrue("Prüfsumme" in e.message!!)
        assertEquals("%PDF", File(t["files"], "books/b.pdf").readText())
    }

    @Test fun missingAndExtraEntriesAreRejected() {
        val t = app()
        val zip = backup(t)
        val missing = tmp.newFile("missing.zip")
        rewrite(zip, missing) { name, bytes -> if (name == "files/ink/p1.items.json") null else bytes }
        assertTrue("unvollständig" in assertFailsWith<BackupException> { FullBackup.extractVerified(missing, File(data, "s1"), t.keys) }.message!!)

        val extra = tmp.newFile("extra.zip")
        rewrite(zip, extra, add = mapOf("files/evil.sh" to byteArrayOf(1)))
        assertTrue("nicht verzeichnete" in assertFailsWith<BackupException> { FullBackup.extractVerified(extra, File(data, "s2"), t.keys) }.message!!)
    }

    @Test fun zipSlipIsRejected() {
        val t = app()
        val evil = tmp.newFile("slip.zip")
        val payload = byteArrayOf(1, 2)
        val manifest = BackupManifest(entries = listOf(BackupEntry("files/../../outside.txt", 2, FullBackupTestSha.of(payload))))
        ZipOutputStream(evil.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("files/../../outside.txt")); z.write(payload); z.closeEntry()
            z.putNextEntry(ZipEntry(BackupManifest.FILE_NAME)); z.write(BackupManifest.json.encodeToString(BackupManifest.serializer(), manifest).toByteArray()); z.closeEntry()
        }
        assertFailsWith<BackupException> { FullBackup.extractVerified(evil, File(data, "stage"), t.keys) }
        assertFalse(File(data, "outside.txt").exists())
        assertFalse(File(tmp.root, "outside.txt").exists())
    }

    @Test fun pathRules() {
        assertTrue(ZipPaths.isSafe("files/ink/a.rnz"))
        for (bad in listOf("", "/etc/passwd", "files/../x", "files\\x", "C:/x", "files//x", "./x", "files/./x", "a\u0000b")) {
            assertFalse(ZipPaths.isSafe(bad), bad)
        }
        assertFailsWith<BackupException> { ZipPaths.resolve(tmp.root, "../x") }
    }

    @Test fun unexpectedRootIsRejected() {
        val t = app()
        val zip = backup(t)
        assertFailsWith<BackupException> { FullBackup.extractVerified(zip, File(data, "stage"), setOf("files", "shared_prefs")) }
    }

    @Test fun foreignAndV15ArchivesAreRecognised() {
        val v15 = tmp.newFile("v15.zip")
        V15Fixture.zip(v15, mapOf("files/notebook-v15-a.bin" to byteArrayOf(1)))
        assertEquals(ArchiveKind.V15, FullBackup.detect(v15))
        assertFailsWith<V15BackupException> { FullBackup.readManifest(v15) }

        val other = tmp.newFile("other.zip")
        V15Fixture.zip(other, mapOf("x.txt" to byteArrayOf(1)), manifest = false)
        assertEquals(ArchiveKind.UNKNOWN, FullBackup.detect(other))
        assertFailsWith<BackupException> { FullBackup.readManifest(other) }

        val text = tmp.newFile("x.zip").apply { writeText("kein zip") }
        assertEquals(ArchiveKind.UNKNOWN, FullBackup.detect(text))
        assertFailsWith<BackupException> { FullBackup.readManifest(text) }

        val newer = tmp.newFile("newer.zip")
        ZipOutputStream(newer.outputStream()).use { z ->
            z.putNextEntry(ZipEntry(BackupManifest.FILE_NAME))
            z.write("""{"format":99,"app":"schulranzen-neu","entries":[]}""".toByteArray()); z.closeEntry()
        }
        assertTrue("neueren" in assertFailsWith<BackupException> { FullBackup.readManifest(newer) }.message!!)
    }

    @Test fun swapRollsBackWhenAStepFails() {
        val t = app()
        val stage = File(data, "stage").apply { mkdirs() }
        File(stage, "files").mkdirs(); File(stage, "files/new.txt").writeText("neu")
        // "databases" in the stage is a file, not a folder: renaming the
        // live folder over it works, but we make the second rename fail by
        // pointing the target into a non-existing, non-creatable place.
        val blocker = File(data, "blocker").apply { writeText("ich bin eine Datei") }
        val targets = linkedMapOf("files" to t.getValue("files"), "databases" to File(blocker, "databases"))
        File(stage, "databases").mkdirs()
        assertFailsWith<BackupException> { FullBackup.swap(stage, targets, File(data, "old")) }
        // files/ is the old version again.
        assertTrue(File(t["files"], "ink/p1.rnz").exists())
        assertFalse(File(t["files"], "new.txt").exists())
    }

    private fun rewrite(src: File, dst: File, add: Map<String, ByteArray> = emptyMap(), change: (String, ByteArray) -> ByteArray? = { _, b -> b }) {
        ZipFile(src).use { z ->
            ZipOutputStream(dst.outputStream()).use { out ->
                for (e in z.entries()) {
                    val b = change(e.name, z.getInputStream(e).readBytes()) ?: continue
                    out.putNextEntry(ZipEntry(e.name)); out.write(b); out.closeEntry()
                }
                for ((n, b) in add) { out.putNextEntry(ZipEntry(n)); out.write(b); out.closeEntry() }
            }
        }
    }
}

private object FullBackupTestSha { fun of(b: ByteArray) = V15Fixture.sha(b) }
