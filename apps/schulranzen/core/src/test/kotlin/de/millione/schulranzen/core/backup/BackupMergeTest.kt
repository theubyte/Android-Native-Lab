package de.millione.schulranzen.core.backup

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs a suspend block that never really suspends (core has no coroutines library). */
internal fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}

class BackupMergeTest {
    @get:Rule val tmp = TemporaryFolder()

    data class Note(val id: String, val title: String, val updatedAt: Long)

    @Test fun newerVersionWinsPerId() {
        val local = listOf(Note("a", "A lokal", 100), Note("b", "B lokal", 300), Note("c", "C", 50))
        val remote = listOf(Note("a", "A fern", 200), Note("b", "B fern", 200), Note("d", "D fern", 10), Note("c", "C fern", 50))
        val r = mergeById(local, remote, { it.id }, { it.updatedAt })
        assertEquals(listOf("A fern", "B lokal", "C", "D fern"), r.merged.map { it.title })
        assertEquals(listOf("d"), r.added.map { it.id })
        assertEquals(listOf("a"), r.updated.map { it.id })
        assertEquals(listOf("d", "a"), r.toWrite.map { it.id })
        assertEquals(2, r.kept)
    }

    @Test fun mergingTwiceChangesNothing() {
        val local = listOf(Note("a", "A", 1))
        val remote = listOf(Note("a", "A2", 5), Note("b", "B", 1))
        val once = mergeById(local, remote, { it.id }, { it.updatedAt }).merged
        val twice = mergeById(once, remote, { it.id }, { it.updatedAt })
        assertTrue(twice.toWrite.isEmpty())
        assertEquals(once, twice.merged)
    }

    @Test fun duplicateIdsKeepTheNewest() {
        val r = mergeById(emptyList(), listOf(Note("a", "alt", 1), Note("a", "neu", 9), Note("a", "mittel", 5)), { it.id }, { it.updatedAt })
        assertEquals(listOf("neu"), r.merged.map { it.title })
    }

    private fun backupWith(files: Map<String, Pair<String, Long>>): File {
        val src = tmp.newFolder()
        for ((path, v) in files) File(src, path).apply { parentFile.mkdirs(); writeText(v.first); setLastModified(v.second) }
        val zip = tmp.newFile()
        zip.outputStream().use { FullBackup.write(listOf(BackupRoot("files", src)), it, BackupManifest(created = 1)) }
        return zip
    }

    @Test fun folderParticipantTakesMissingAndNewerFiles() {
        val t0 = 1_700_000_000_000L
        val ink = tmp.newFolder("live", "ink")
        File(ink, "same.bin").apply { writeText("gleich"); setLastModified(t0) }
        File(ink, "older-here.bin").apply { writeText("alt"); setLastModified(t0) }
        File(ink, "newer-here.bin").apply { writeText("lokal neu"); setLastModified(t0 + 50_000) }
        File(ink, "only-here.bin").apply { writeText("bleibt"); setLastModified(t0) }
        val zip = backupWith(
            mapOf(
                "ink/same.bin" to ("gleich" to t0 + 10_000), // newer time, same content
                "ink/older-here.bin" to ("neu vom Server" to t0 + 1_234),
                "ink/newer-here.bin" to ("fern alt" to t0 + 1_000),
                "ink/sub/new.bin" to ("ganz neu" to t0),
                "ink/x.tmp" to ("temp" to t0),
                "images/foto.jpg" to ("bild" to t0),
            ),
        )
        val stage = File(tmp.root, "stage")
        val reports = runSuspend { BackupMerge.run(zip, stage, listOf(FolderMergeParticipant(ink, name = "Tinte"))) }
        assertFalse(stage.exists())
        val r = reports.single()
        assertEquals("Tinte", r.name)
        assertEquals(1, r.added)
        assertEquals(1, r.updated)
        assertEquals(2, r.kept)
        assertTrue(r.ok)
        assertEquals("neu vom Server", File(ink, "older-here.bin").readText())
        assertEquals(t0 + 1_234, File(ink, "older-here.bin").lastModified())
        assertEquals("lokal neu", File(ink, "newer-here.bin").readText())
        assertEquals("ganz neu", File(ink, "sub/new.bin").readText())
        assertEquals("bleibt", File(ink, "only-here.bin").readText())
        assertFalse(File(ink, "x.tmp").exists())
        assertEquals("Tinte: 1 neu, 1 aktualisiert", r.summary())
        // Nothing left from the temp files.
        assertTrue(ink.walkTopDown().none { it.name.endsWith(".merge.tmp") })
    }

    @Test fun failingParticipantDoesNotStopTheOthers() {
        val zip = backupWith(mapOf("images/a.jpg" to ("a" to 1_000L)))
        val images = File(tmp.root, "live-images")
        val broken = object : MergeParticipant {
            override val name = "Hefte"
            override suspend fun merge(backupDir: File): MergeReport {
                assertTrue(File(backupDir, "files/images/a.jpg").isFile)
                throw BackupException("Datenbank gesperrt.")
            }
        }
        val names = ArrayList<String>()
        val reports = runSuspend {
            BackupMerge.run(zip, File(tmp.root, "stage"), listOf(broken, FolderMergeParticipant(images, source = "files/images")), onParticipant = { _, n -> names += n })
        }
        assertEquals(listOf("Hefte", "live-images"), names)
        assertEquals("Datenbank gesperrt.", reports[0].error)
        assertNull(reports[1].error)
        assertEquals(1, reports[1].added)
        assertEquals("a", File(images, "a.jpg").readText())
    }

    @Test fun mergeWithoutParticipantsIsRefused() {
        val zip = backupWith(mapOf("a" to ("x" to 1L)))
        assertFailsWith<BackupException> { runSuspend { BackupMerge.run(zip, File(tmp.root, "s"), emptyList()) } }
    }

    @Test fun damagedBackupNeverReachesParticipants() {
        val zip = backupWith(mapOf("ink/a.bin" to ("inhalt" to 1L)))
        // Same archive, but one file's content no longer matches its checksum.
        val bad = tmp.newFile()
        java.util.zip.ZipFile(zip).use { z ->
            java.util.zip.ZipOutputStream(bad.outputStream()).use { o ->
                for (e in z.entries()) {
                    o.putNextEntry(java.util.zip.ZipEntry(e.name))
                    val b = z.getInputStream(e).readBytes()
                    o.write(if (e.name == "files/ink/a.bin") "inhalX".toByteArray() else b)
                    o.closeEntry()
                }
            }
        }
        bad.copyTo(zip, overwrite = true)
        var called = false
        val p = object : MergeParticipant {
            override val name = "x"
            override suspend fun merge(backupDir: File): MergeReport { called = true; return MergeReport(name) }
        }
        assertFailsWith<BackupException> { runSuspend { BackupMerge.run(zip, File(tmp.root, "s"), listOf(p)) } }
        assertFalse(called)
    }

    @Test fun manifestKeepsFileTimes() {
        val zip = backupWith(mapOf("a.txt" to ("x" to 1_600_000_000_123L)))
        val m = FullBackup.readManifest(zip)
        assertEquals(1_600_000_000_123L, m.entries.single().modified)
        val stage = File(tmp.root, "st")
        FullBackup.extractVerified(zip, stage, setOf("files"))
        assertEquals(1_600_000_000_123L, File(stage, "files/a.txt").lastModified())
    }

    @Test fun oldManifestWithoutTimesStillReads() {
        val json = """{"format":1,"app":"schulranzen-neu","entries":[{"path":"files/a","size":1,"sha256":"00"}]}"""
        val m = BackupManifest.json.decodeFromString(BackupManifest.serializer(), json)
        assertEquals(0L, m.entries.single().modified)
        assertNotNull(m)
    }

    @Test fun hooksDefaultToNothing() {
        runSuspend { BackupHooks.None.beforeRestore(); BackupHooks.None.afterRestore(); BackupHooks.None.beforeBackup() }
    }

    // --- naming and retention --------------------------------------------

    @Test fun namesAndRetention() {
        val utc = TimeZone.getTimeZone("UTC")
        val t = 1_761_745_512_000L // 2025-10-29 13:45:12 UTC
        assertEquals("Schulranzen-Auto-2025-10-29-134512.srbk", BackupNaming.name(t, encrypted = true, auto = true, zone = utc))
        assertEquals("Schulranzen-Backup-2025-10-29-1345.zip", BackupNaming.name(t, encrypted = false, auto = false, zone = utc))
        assertTrue(BackupNaming.isAutoBackup("Schulranzen-Auto-2025-10-29-134512.zip"))
        assertFalse(BackupNaming.isAutoBackup("Schulranzen-Backup-2025-10-29-1345.zip"))
        assertFalse(BackupNaming.isAutoBackup("Schulranzen-Auto-irgendwas.zip"))
        assertFalse(BackupNaming.isAutoBackup("Schulranzen-Auto-2025-10-29-134512.pdf"))
        val names = listOf(
            "Schulranzen-Auto-2025-10-27-020000.zip",
            "Schulranzen-Auto-2025-10-29-020000.srbk",
            "Schulranzen-Backup-2020-01-01-0000.zip",
            "Schulranzen-Auto-2025-10-28-020000.zip",
            "Urlaub.jpg",
            "Schulranzen-Auto-2025-10-26-020000.srbk",
        )
        assertEquals(listOf("Schulranzen-Auto-2025-10-27-020000.zip", "Schulranzen-Auto-2025-10-26-020000.srbk"), BackupNaming.toDelete(names, 2))
        assertEquals(3, BackupNaming.toDelete(names, 0).size) // keeps at least one
        assertTrue(BackupNaming.toDelete(names, 10).isEmpty())
        assertEquals(AutoInterval.WEEKLY, AutoInterval.parse("WEEKLY"))
        assertEquals(AutoInterval.OFF, AutoInterval.parse("kaputt"))
    }
}
