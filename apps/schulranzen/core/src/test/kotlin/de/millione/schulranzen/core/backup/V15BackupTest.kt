package de.millione.schulranzen.core.backup

import de.millione.schulranzen.core.backup.V15Fixture.Im
import de.millione.schulranzen.core.backup.V15Fixture.Pg
import de.millione.schulranzen.core.backup.V15Fixture.Pt
import de.millione.schulranzen.core.backup.V15Fixture.St
import de.millione.schulranzen.core.backup.V15Fixture.Tx
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V15BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val S = 1000f / 1240f
    private val oldFiles = "/data/user/0/de.millione.schulranzen/files"

    private val items = """[
        {"id":"nb-1","type":"notebook","title":"Mathe <Analysis> & Co","subject":"Mathe","uri":"","color":-13543467,"created":1000,"modified":2000,"deleted":false},
        {"id":"ws/2","type":"worksheet","title":"Blatt","subject":"Physik","uri":"","color":-8696892,"created":1,"modified":2,"deleted":false},
        {"id":"tt","type":"timetable","title":"Stundenplan","subject":"Allgemein","uri":"","color":-9735552,"created":1,"modified":2,"deleted":false},
        {"id":"bk","type":"book","title":"Fachkunde SHK","subject":"SHK","uri":"appfile:books/bk.pdf","color":-15300773,"created":1,"modified":2,"deleted":false},
        {"id":"bk-ext","type":"book","title":"Fremd","subject":"SHK","uri":"content://x/y","color":-15300773,"created":1,"modified":2,"deleted":false},
        {"id":"old","type":"notebook","title":"Gelöscht","subject":"Mathe","uri":"","color":-13543467,"created":1,"modified":2,"deleted":true}
    ]"""
    private val subjects = """[{"name":"Allgemein","color":-9735552},{"name":"Mathe","color":-13543467},{"name":"Deutsch","color":-2602149},{"name":"SHK","color":-15300773},{"name":"Fachkunde","color":-2915548}]"""

    private fun fullBackup(manifest: Boolean = true, tamper: String? = null): File {
        val page1 = Pg(
            bookmark = true, paper = 1, title = "Ableitungen", bg = "",
            strokes = listOf(
                St(0xFF1C1B19.toInt(), 1, 4.2f, listOf(Pt(124f, 175.4f, 0.5f, 0.3f), Pt(248f, 350.8f, 1.4f, 2f), Pt(Float.NaN, 0f))),
                St(0xFFF2D04B.toInt(), 3, 18f, listOf(Pt(0f, 0f), Pt(1240f, 1754f))),
                St(0xFF000000.toInt(), 0, 2f, listOf(Pt(10f, 10f), Pt(20f, 20f))),
                St(0xFF000000.toInt(), 1, 4f, listOf(Pt(10f, 10f))), // single point: V15 never drew it
            ),
            texts = listOf(Tx(124f, 248f, 31f, 0xFF2448A8.toInt(), "f(x) = x²\nf'(x) = 2x")),
            images = listOf(Im("$oldFiles/book-clips/clip-1.png", 124f, 124f, 620f, 310f), Im("$oldFiles/book-clips/missing.png", 0f, 0f, 10f, 10f)),
        )
        val page2 = Pg(paper = 6, bg = "$oldFiles/worksheets/ws_2-0.jpg")
        val entries = linkedMapOf(
            "files/notebook-v15-nb-1.bin" to V15Fixture.notebookV15(listOf(page1, Pg(paper = 2)), trash = listOf(Pg(title = "weg"))),
            // An older file next to the new one must be ignored.
            "files/notebook-v14-nb-1.bin" to V15Fixture.notebookV15(listOf(Pg()), magic = V15Notebook.MAGIC_V14),
            "files/notebook-v13-ws_2.bin" to V15Fixture.notebookV13(listOf(page2)),
            "files/book-clips/clip-1.png" to byteArrayOf(1, 2, 3, 4),
            "files/worksheets/ws_2-0.jpg" to byteArrayOf(5, 6, 7),
            "files/books/bk.pdf" to "%PDF-1.4 test".toByteArray(),
            "files/book-annotations-v12-bk.json" to """{"pages":[{"page":3,"bookmark":true,"strokes":[{"id":"s","tool":2,"color":-256,"width":0.01,"pts":[[0.1,0.2,0.5,0.0],[0.3,0.4,1.0,0.1]]}],"notes":[{"id":"n","x":0.5,"y":0.6,"text":"Merken!","created":5}]},{"page":4,"bookmark":false,"strokes":[],"notes":[]}],"favorites":[{"id":"f","page":3,"l":0.1,"t":0.1,"r":0.5,"b":0.5,"label":"Tabelle","created":7}]}""".toByteArray(),
            "shared_prefs/library_v9.xml" to V15Fixture.prefs(mapOf("items" to items, "subjects" to subjects)),
            "shared_prefs/study_v9.xml" to V15Fixture.prefs(mapOf(
                "exams" to """[{"id":"ex","title":"Gesellenprüfung","subject":"SHK","date":"2027-06-01","sources":["nb-1","bk"]}]""",
                "cards" to """[{"id":"c1","examId":"ex","front":"Was ist ΔT?","back":"Temperaturdifferenz","due":20000,"reps":2,"interval":4,"ease":2.58,"rating":3}]""",
                "marks" to """[{"id":"m","itemId":"bk","page":3,"itemTitle":"Fachkunde SHK","subject":"SHK","label":"Für Prüfung merken","left":-1,"top":-1,"right":-1,"bottom":-1,"created":9}]""",
            )),
            "shared_prefs/bag.xml" to V15Fixture.prefs(mapOf("server" to "192.168.1.20:8788", "mail" to "max@example.org", "token" to "secret")),
        )
        val f = tmp.newFile("v15.zip")
        V15Fixture.zip(f, entries, manifest, tamper)
        return f
    }

    @Test fun readsLibraryNotebooksBooksAndStudy() {
        V15Backup.read(fullBackup()).use { lib ->
            assertTrue(lib.verified, lib.warnings.toString())
            assertEquals(1700000000000, lib.created)
            assertEquals("192.168.1.20:8788", lib.syncServer)
            assertEquals("max@example.org", lib.syncEmail)

            // Subjects: the five from the list plus "Physik" used by an item.
            assertEquals(listOf("Allgemein", "Mathe", "Deutsch", "SHK", "Fachkunde", "Physik"), lib.subjects.map { it.name })
            assertEquals(7, lib.subjects[0].colorIndex) // grey → Graphit
            assertEquals(1, lib.subjects[2].colorIndex) // red → Ziegel

            // Trashed notebook skipped by default.
            assertEquals(listOf("nb-1", "ws/2", "tt"), lib.notebooks.map { it.oldId })
            val nb = lib.notebooks[0]
            assertEquals("Mathe <Analysis> & Co", nb.title)
            assertEquals("Mathe", nb.subjectName)
            assertEquals(NotebookKind.NOTEBOOK, nb.kind)
            assertEquals(ImportedPaper.LINED, nb.paper)
            assertEquals(2, nb.pages.size) // trash page not imported
            val p = nb.pages[0]
            assertTrue(p.bookmarked)
            assertEquals("Ableitungen", p.title)
            assertEquals(3, p.strokes.size)

            val pen = p.strokes[0]
            assertEquals(ImportedTool.PEN, pen.tool)
            assertEquals(2, pen.pointCount) // NaN point dropped
            assertEquals(100f, pen.x(0), 0.01f)
            assertEquals(141.45f, pen.y(0), 0.01f)
            assertEquals(0.5f, pen.pressure(0))
            assertEquals(1f, pen.pressure(1)) // clamped
            assertEquals((Math.PI / 2).toFloat(), pen.tilt(1), 1e-6f) // clamped
            assertEquals(4.2f * S, pen.width, 1e-4f)

            val marker = p.strokes[1]
            assertEquals(ImportedTool.MARKER, marker.tool)
            assertEquals(1000f, marker.x(1), 0.01f)
            assertEquals(1414.5f, marker.y(1), 0.1f)

            val pencil = p.strokes[2]
            assertEquals(220, pencil.color ushr 24) // pencil alpha kept

            val t = p.texts.single()
            assertEquals(100f, t.x, 0.01f)
            assertEquals((248f - 31f * 0.93f) * S, t.y, 0.01f)
            assertEquals(31f * S, t.size, 0.01f)
            assertTrue(t.width > 0f && t.x + t.width <= 1000f)
            assertEquals("f(x) = x²\nf'(x) = 2x", t.text)

            val img = p.images.single()
            assertEquals("files/book-clips/clip-1.png", img.entry)
            assertEquals(100f, img.x, 0.01f); assertEquals(500f, img.w, 0.01f); assertEquals(250f, img.h, 0.01f)
            assertTrue(lib.warnings.any { "missing.png" in it })

            assertEquals(ImportedPaper.DOTTED, nb.pages[1].paper)

            // Worksheet from a V13 file with a full-page background.
            val ws = lib.notebooks[1]
            assertEquals(NotebookKind.WORKSHEET, ws.kind)
            assertEquals(ImportedPaper.BLANK, ws.paper)
            val bg = ws.pages[0].images.single()
            assertTrue(bg.background)
            assertEquals("files/worksheets/ws_2-0.jpg", bg.entry)
            assertEquals(1000f, bg.w); assertEquals(1414.5f, bg.h, 0.1f)

            // Timetable never opened: one empty page.
            assertEquals(NotebookKind.TIMETABLE, lib.notebooks[2].kind)
            assertEquals(1, lib.notebooks[2].pages.size)

            assertEquals(4, lib.pageCount)

            // Books
            assertEquals(2, lib.books.size)
            val book = lib.books[0]
            assertEquals("files/books/bk.pdf", book.pdfEntry)
            assertEquals("%PDF-1.4 test", lib.files.open(book.pdfEntry!!).use { it.readBytes().decodeToString() })
            val bp = book.pages.single() // empty page 4 dropped
            assertEquals(3, bp.index)
            assertTrue(bp.bookmarked)
            assertEquals(ImportedTool.MARKER, bp.strokes.single().tool)
            assertEquals(0.3f, bp.strokes.single().x(1), 1e-6f)
            assertEquals("Merken!", bp.notes.single().text)
            assertEquals("Tabelle", book.favorites.single().label)
            assertNull(lib.books[1].pdfEntry)
            assertTrue(lib.warnings.any { "Fremd" in it })

            // Study
            assertEquals("Gesellenprüfung", lib.study.exams.single().title)
            assertEquals(listOf("nb-1", "bk"), lib.study.exams.single().sourceIds)
            val card = lib.study.cards.single()
            assertEquals("Was ist ΔT?", card.front); assertEquals(2.58, card.ease); assertEquals(20000, card.dueEpochDay)
            assertEquals(-1f, lib.study.marks.single().left)
        }
    }

    @Test fun trashedItemsOnRequest() {
        V15Backup.read(fullBackup(), includeTrashed = true).use { lib ->
            val old = lib.notebooks.single { it.oldId == "old" }
            assertTrue(old.trashed)
        }
    }

    @Test fun checksumMismatchIsAWarning() {
        V15Backup.read(fullBackup(tamper = "files/books/bk.pdf")).use { lib ->
            assertTrue(!lib.verified)
            assertTrue(lib.warnings.any { "Prüfsumme" in it && "bk.pdf" in it })
            assertEquals(3, lib.notebooks.size)
        }
    }

    @Test fun backupWithoutManifestStillReads() {
        V15Backup.read(fullBackup(manifest = false)).use { lib ->
            assertTrue(!lib.verified)
            assertNull(lib.created)
            assertEquals(3, lib.notebooks.size)
        }
    }

    @Test fun brokenNotebookFallsBackToOlderFile() {
        val f = tmp.newFile("broken.zip")
        val v15 = V15Fixture.notebookV15(listOf(Pg(strokes = listOf(St(-1, 1, 3f, listOf(Pt(1f, 1f), Pt(2f, 2f)))))))
        V15Fixture.zip(f, mapOf(
            "files/notebook-v15-a.bin" to v15.copyOf(v15.size - 6), // cut off
            "files/notebook-v9-a.bin" to V15Fixture.notebookV9(listOf(Pg(texts = listOf(Tx(10f, 50f, 20f, -16777216, "alt"))))),
            "files/notebook-v15-b.bin" to byteArrayOf(0x53, 0x42, 0x31, 0x35, 0x7f, 0, 0, 0), // absurd page count
            "shared_prefs/library_v9.xml" to V15Fixture.prefs(mapOf("items" to """[{"id":"a","type":"notebook","title":"A"},{"id":"b","type":"notebook","title":"B"}]""")),
        ))
        V15Backup.read(f).use { lib ->
            assertEquals("alt", lib.notebooks[0].pages.single().texts.single().text)
            assertEquals(1, lib.notebooks[1].pages.size) // empty replacement
            assertTrue(lib.warnings.any { "„B“" in it && "beschädigt" in it })
        }
    }

    @Test fun legacyMainNotebookFormats() {
        val st = listOf(St(-16777216, 3, 0f, listOf(Pt(0f, 0f), Pt(124f, 124f))), St(-16777216, 0, 0f, listOf(Pt(1f, 1f), Pt(2f, 2f))))
        for ((name, data) in listOf(
            "notebook-v8.bin" to V15Fixture.notebookV8(listOf(Pg(bookmark = true, paper = 4, strokes = st), Pg())),
            "notebook-v7.bin" to V15Fixture.notebookV7(listOf(Pg(bookmark = true, paper = 4, strokes = st), Pg())),
            "notebook-v3.bin" to V15Fixture.notebookV3(st),
        )) {
            val f = File(tmp.root, "legacy-$name.zip")
            // No library at all: the very old single-notebook layout.
            V15Fixture.zip(f, mapOf("files/$name" to data, "shared_prefs/bag.xml" to V15Fixture.prefs(mapOf("notebook_name" to "SHK Fachkunde"))), manifest = false)
            V15Backup.read(f).use { lib ->
                val nb = lib.notebooks.single()
                assertEquals("legacy-main", nb.oldId)
                assertEquals("SHK", nb.subjectName)
                val p = nb.pages[0]
                assertEquals(18f * S, p.strokes[0].width, 1e-4f, name) // legacy marker width
                assertEquals(ImportedTool.MARKER, p.strokes[0].tool)
                assertEquals(2f * S, p.strokes[1].width, 1e-4f, name)
                assertEquals(100f, p.strokes[0].x(1), 0.01f)
                if (name != "notebook-v3.bin") {
                    assertEquals(2, nb.pages.size)
                    assertTrue(p.bookmarked)
                    assertEquals(ImportedPaper.LINED, p.paper)
                }
            }
        }
    }

    @Test fun v14AndV9Readers() {
        val pg = Pg(paper = 3, strokes = listOf(St(-1, 2, 3.2f, listOf(Pt(0f, 0f, .7f, .2f), Pt(1240f, 0f, .8f, .1f)))))
        val v14 = V15Notebook.read(V15Fixture.notebookV15(listOf(pg), magic = V15Notebook.MAGIC_V14), V15Notebook.Format.V14)
        assertEquals(.7f, v14[0].strokes[0].points[0].pressure)
        val v9 = V15Notebook.read(V15Fixture.notebookV9(listOf(pg)), V15Notebook.Format.V9)
        assertEquals(1f, v9[0].strokes[0].points[0].pressure)
        assertEquals(3, v9[0].paper)
        assertFailsWith<Exception> { V15Notebook.read(V15Fixture.notebookV9(listOf(pg)), V15Notebook.Format.V15) }
    }

    @Test fun paperMapping() {
        assertEquals(listOf("SQUARED", "LINED", "DOTTED", "SQUARED", "LINED", "SQUARED", "BLANK", "BLANK"), (0..7).map { ImportedPaper.fromV15(it) })
    }

    @Test fun rejectsOtherZips() {
        val f = tmp.newFile("other.zip")
        V15Fixture.zip(f, mapOf("readme.txt" to byteArrayOf(1)), manifest = false)
        assertFailsWith<BackupException> { V15Backup.read(f) }
        val notZip = tmp.newFile("x.zip").apply { writeText("kein zip") }
        assertFailsWith<BackupException> { V15Backup.read(notZip) }
    }

    @Test fun unsafeEntriesAreSkipped() {
        val f = tmp.newFile("slip.zip")
        V15Fixture.zip(f, mapOf(
            "../../evil.bin" to byteArrayOf(1),
            "files/notebook-v15-x.bin" to V15Fixture.notebookV15(listOf(Pg(images = listOf(Im("/data/data/p/files/../../../etc/passwd", 0f, 0f, 1f, 1f))))),
            "shared_prefs/library_v9.xml" to V15Fixture.prefs(mapOf("items" to """[{"id":"x","type":"notebook","title":"X"}]""")),
        ), manifest = false)
        V15Backup.read(f).use { lib ->
            assertTrue(lib.warnings.any { "unsichere" in it })
            assertTrue(lib.notebooks.single().pages.single().images.isEmpty())
        }
    }

    @Test fun prefsXmlTypes() {
        val m = AndroidPrefsXml.parse("""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map><string name="s">a &amp; &lt;b&gt;</string><int name="i" value="-5" /><long name="l" value="12345678901" />
            <boolean name="b" value="true" /><float name="f" value="1.5" /><set name="set"><string>x</string><string>y</string></set></map>""".toByteArray())
        assertEquals("a & <b>", m["s"]); assertEquals(-5, m["i"]); assertEquals(12345678901L, m["l"])
        assertEquals(true, m["b"]); assertEquals(1.5f, m["f"]); assertEquals(setOf("x", "y"), m["set"])
    }

    @Test fun prefsXmlRefusesDoctype() {
        assertFailsWith<Exception> {
            AndroidPrefsXml.parse("""<?xml version="1.0"?><!DOCTYPE map [<!ENTITY x SYSTEM "file:///etc/passwd">]><map><string name="s">&x;</string></map>""".toByteArray())
        }
    }

    @Test fun importRunFeedsTheSink() {
        V15Backup.read(fullBackup()).use { lib ->
            val sink = FakeSink()
            val steps = ArrayList<ImportProgress>()
            val report = blocking { V15Import.run(lib, sink) { steps += it } }
            assertEquals(listOf("Allgemein", "Mathe", "Deutsch", "SHK", "Fachkunde", "Physik"), sink.subjects)
            assertEquals(listOf("v15-clip-1.png", "v15-ws_2-0.jpg"), sink.images.keys.toList())
            assertEquals(listOf<Byte>(1, 2, 3, 4), sink.images["v15-clip-1.png"]!!.toList())
            assertEquals(mapOf("files/book-clips/clip-1.png" to "stored-v15-clip-1.png"), sink.notebookImages["nb-1"])
            assertEquals(listOf("nb-1", "ws/2", "tt"), sink.notebooks)
            assertEquals(2L, sink.notebookSubject["nb-1"]) // Mathe
            assertEquals(listOf("bk" to 13, "bk-ext" to -1), sink.books)
            assertEquals(mapOf("nb-1" to "new-nb-1", "ws/2" to "new-ws/2", "tt" to "new-tt", "bk" to "book-bk", "bk-ext" to "book-bk-ext"), sink.studyIds)
            assertEquals(ImportReport(subjects = 6, notebooks = 3, pages = 4, books = 2, images = 2, cards = 1), report)
            assertEquals(1f, steps.last().fraction)
        }
    }

    @Test fun uniqueImageNames() {
        val used = HashSet<String>()
        assertEquals("v15-a.png", V15Import.uniqueName("files/x/a.png", used))
        assertEquals("v15-a-2.png", V15Import.uniqueName("files/y/a.png", used))
        assertEquals("v15-a_b", V15Import.uniqueName("files/a b", used))
    }

    @Test fun subjectColourMapping() {
        assertEquals(0, SubjectColors.nearest(0xFF2F6F73.toInt()))
        assertEquals(6, SubjectColors.nearest(0xffd38324.toInt())) // orange → Ocker
        assertEquals(5, SubjectColors.nearest(0xff7b4bc4.toInt())) // purple → Pflaume
        assertEquals(4, SubjectColors.nearest(0xff3157d5.toInt())) // blue → Taube
        assertEquals(0, SubjectColors.nearest(0xff16875b.toInt())) // green → Petrol
        assertEquals(1, SubjectColors.nearest(0xffd84b5b.toInt())) // red → Ziegel
        assertEquals(7, SubjectColors.nearest(0xff6b7280.toInt())) // grey → Graphit
    }

    private class FakeSink : ImportSink {
        val subjects = ArrayList<String>()
        val images = LinkedHashMap<String, ByteArray>()
        val notebooks = ArrayList<String>()
        val notebookImages = HashMap<String, Map<String, String>>()
        val notebookSubject = HashMap<String, Long?>()
        val books = ArrayList<Pair<String, Int>>()
        var studyIds: Map<String, String> = emptyMap()

        override suspend fun subject(name: String, colorIndex: Int): Long {
            val i = subjects.indexOfFirst { it.equals(name, true) }
            if (i >= 0) return i + 1L
            subjects += name; return subjects.size.toLong()
        }
        override suspend fun image(suggestedName: String, data: InputStream): String {
            images[suggestedName] = data.readBytes(); return "stored-$suggestedName"
        }
        override suspend fun notebook(notebook: ImportedNotebook, subjectId: Long?, images: Map<String, String>): String {
            notebooks += notebook.oldId; notebookImages[notebook.oldId] = images; notebookSubject[notebook.oldId] = subjectId
            return "new-${notebook.oldId}"
        }
        override suspend fun book(book: ImportedBook, subjectId: Long?, pdf: InputStream?): String {
            books += book.oldId to (pdf?.readBytes()?.size ?: -1); return "book-${book.oldId}"
        }
        override suspend fun study(study: ImportedStudy, ids: Map<String, String>, subjects: Map<String, Long>) {
            assertNotNull(subjects["shk"]); studyIds = ids
        }
    }
}
