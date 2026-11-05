package de.millione.schulranzen.core.study

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cases of tests/data.test.js of the web app, on the same rules. */
class ContentPackTest {
    private fun card(id: String?, vorne: String, hinten: String, extra: String = ",\"geprüft\":true") =
        "{" + (id?.let { "\"id\":\"$it\"," } ?: "") + "\"vorne\":\"$vorne\",\"hinten\":\"$hinten\"$extra}"

    private fun pack(version: String, vararg karten: String, id: String = "test.paket") =
        """{"format":"schulranzen-inhaltspaket","formatVersion":1,"id":"$id","titel":"Testpaket","version":"$version",
            "stapel":[{"id":"s1","name":"Stapel Eins","fach":"Fach","karten":[${karten.joinToString(",")}]}]}"""

    private fun parsed(text: String) = assertNotNull(ContentPacks.parse(text).pack)

    /** The first import as stored: ids count up, text and baseline are the same. */
    private fun stored(p: PackImportPlan) = p.newCards.mapIndexed { i, n ->
        StoredPackCard(i + 1L, n.key, n.card.vorne, n.card.hinten, n.card.vorne, n.card.hinten, n.card.geprueft, n.card.thema, n.card.quelle)
    }
    private val oneDeck = listOf(StoredPackDeck(1, "s1", "Fach"))

    // ---- Checking the file

    @Test fun `valid pack is read`() {
        val r = ContentPacks.parse(pack("1", card("k1", "V", "H", ""","thema":"T","quelle":{"buch":"Buch","seite":12},"geprüft":true""")))
        assertEquals(emptyList(), r.errors)
        val k = r.pack!!.stapel[0].karten[0]
        assertEquals(PackSource("Buch", "12"), k.quelle)
        assertEquals("Buch, S. 12", k.quelle.line)
        assertTrue(k.geprueft)
        assertEquals("T", k.thema)
    }

    @Test fun `broken files are refused with a reason`() {
        assertEquals(listOf("Die Datei ist kein gültiges JSON."), ContentPacks.parse("{kaputt").errors)
        assertNull(ContentPacks.parse("""{"format":"anderes"}""").pack)
        assertEquals(1, ContentPacks.parse("""[1,2]""").errors.size)
        assertTrue(ContentPacks.parse(pack("1").replace("\"formatVersion\":1", "\"formatVersion\":99")).errors.single().contains("99"))
        assertNull(ContentPacks.parse(pack("1", card("k", "V", "H"), id = "")).pack)
        assertNull(ContentPacks.parse(pack("1", card("k", "", "H"))).pack)
        assertNull(ContentPacks.parse(pack("1", card("k", "V", "H"), card("k", "W", "H"))).pack)
        assertNull(ContentPacks.parse(pack("1", "7")).pack)
        assertNull(ContentPacks.parse("""{"format":"schulranzen-inhaltspaket","formatVersion":1,"id":"a","titel":"b","version":"1","stapel":[]}""").pack)
    }

    @Test fun `errors name deck and card`() {
        val e = ContentPacks.parse(pack("1", card("a", "V", "H"), card("b", "", "H"))).errors
        assertEquals(listOf("Stapel 1, Karte 2: Vorderseite fehlt."), e)
    }

    @Test fun `script urls and svg are no images`() {
        fun withImage(url: String) = pack("1", card("k", "V", "H", ""","vorneBild":"$url","geprüft":true"""))
        assertNull(ContentPacks.parse(withImage("javascript:alert(1)")).pack)
        assertNull(ContentPacks.parse(withImage("data:image/svg+xml;base64,AAAA")).pack)
        assertNull(ContentPacks.parse(withImage("data:image/png;base64,###")).pack)
        assertNotNull(ContentPacks.parse(withImage("data:image/png;base64,AAAA")).pack)
    }

    @Test fun `too large pictures are refused and pictures decode`() {
        val big = "data:image/png;base64," + "A".repeat(ContentPacks.MAX_IMAGE_CHARS)
        assertTrue(ContentPacks.parse(pack("1", card("k", "V", "H", ""","vorneBild":"$big""""))).errors.single().contains("zu groß"))
        val (ext, bytes) = assertNotNull(ContentPacks.decodeImage("data:image/jpeg;base64,AAAA"))
        assertEquals("jpg", ext)
        assertEquals(3, bytes.size)
        assertNull(ContentPacks.decodeImage("data:text/plain;base64,AAAA"))
    }

    @Test fun `a side may be only a picture`() {
        val r = ContentPacks.parse(pack("1", card("k", "", "H", ""","vorneBild":"data:image/png;base64,AAAA","geprüft":true""")))
        assertEquals("", r.pack!!.stapel[0].karten[0].vorne)
    }

    @Test fun `missing status counts as unverified and is said`() {
        val r = ContentPacks.parse(pack("1", card("k", "V", "H", "")))
        assertFalse(r.pack!!.stapel[0].karten[0].geprueft)
        assertTrue(r.warnings.any { "ungeprüft" in it })
    }

    @Test fun `missing card id is derived stably and warned about`() {
        val a = ContentPacks.parse(pack("1", card(null, "Vörne", "H")))
        val b = ContentPacks.parse(pack("2", card(null, "Vörne", "anders")))
        assertTrue(a.warnings.any { "keine eigene id" in it })
        assertEquals(a.pack!!.stapel[0].karten[0].id, b.pack!!.stapel[0].karten[0].id)
        assertTrue(a.pack!!.stapel[0].karten[0].id.startsWith("auto-"))
    }

    @Test fun `deck values are defaults for its cards and numbers work as version`() {
        val r = ContentPacks.parse(
            """{"format":"schulranzen-inhaltspaket","formatVersion":1,"id":"p","titel":"T","version":2,"unbekannt":true,
               "stapel":[{"id":"s","name":"N","thema":"Th","quelle":{"buch":"B"},"karten":[{"id":"k","vorne":"V","hinten":"H","geprüft":true}]}]}""",
        )
        assertEquals("2", r.pack!!.version)
        assertEquals("Th", r.pack!!.stapel[0].karten[0].thema)
        assertEquals("B", r.pack!!.stapel[0].karten[0].quelle.line)
    }

    @Test fun `byte order mark is fine`() = assertNotNull(ContentPacks.parse("﻿" + pack("1", card("k", "V", "H"))).pack).let { }

    @Test fun `versions compare as numbers`() {
        assertTrue(ContentPacks.compareVersions("1.9", "1.10") < 0)
        assertEquals(0, ContentPacks.compareVersions("2", "2"))
        assertTrue(ContentPacks.compareVersions("2026-11", "2026-10") > 0)
        assertTrue(ContentPacks.compareVersions("1.2", "1.2.1") < 0)
    }

    // ---- Import and update

    @Test fun `first import makes decks and new cards`() {
        val p = ContentPacks.plan(parsed(pack("1", card("k1", "V1", "H1"), card("k2", "V2", "H2", ",\"geprüft\":false"))), emptyList(), emptyList())
        assertFalse(p.summary.isUpdate)
        assertEquals(1, p.summary.newDecks)
        assertEquals(2, p.summary.newCards)
        assertEquals(1, p.summary.unverifiedCards)
        assertEquals("s1/k1", p.newCards[0].key)
        assertFalse(p.newCards[1].card.geprueft)
    }

    @Test fun `an update changes text only and adds what is new`() {
        val first = stored(ContentPacks.plan(parsed(pack("1", card("k1", "V1", "H1"), card("k2", "V2", "H2"))), emptyList(), emptyList()))
        val learnt = first.map { it.copy(learnt = true) }
        val p = ContentPacks.plan(parsed(pack("2", card("k1", "V1 neu", "H1 neu"), card("k2", "V2", "H2"), card("k3", "V3", "H3"))), oneDeck, learnt)
        assertTrue(p.summary.isUpdate)
        assertEquals(0, p.summary.newDecks)
        assertEquals(1, p.summary.newCards)
        assertEquals(1, p.summary.changedCards)
        assertEquals(1, p.summary.unchangedCards)
        val k1 = p.updates.first { it.id == 1L }
        assertEquals("V1 neu", k1.front)
        assertEquals("H1 neu", k1.back)
        assertEquals("V1 neu", k1.packFront)
    }

    @Test fun `own edits win over a new pack text`() {
        val first = stored(ContentPacks.plan(parsed(pack("1", card("k1", "V1", "H1"))), emptyList(), emptyList()))
        val mine = listOf(first[0].copy(back = "Meine Antwort"))
        val p = ContentPacks.plan(parsed(pack("2", card("k1", "V1", "H1 neu"))), oneDeck, mine)
        assertEquals(1, p.summary.keptOwnEdits)
        assertEquals("Meine Antwort", p.updates[0].back)
        assertEquals("H1 neu", p.updates[0].packBack)
    }

    @Test fun `a corrected card takes the status of the pack`() {
        val first = stored(ContentPacks.plan(parsed(pack("1", card("k1", "V1", "H1"))), emptyList(), emptyList()))
        val p = ContentPacks.plan(parsed(pack("2", card("k1", "V1", "H1 neu", ",\"geprüft\":false"))), oneDeck, first)
        assertFalse(p.updates[0].verified)
    }

    @Test fun `same text keeps an own check and the pack may only add one`() {
        val first = stored(ContentPacks.plan(parsed(pack("1", card("k1", "V1", "H1", ",\"geprüft\":false"))), emptyList(), emptyList()))
        val checked = listOf(first[0].copy(verified = true))
        val keep = ContentPacks.plan(parsed(pack("2", card("k1", "V1", "H1", ",\"geprüft\":false"))), oneDeck, checked)
        assertTrue(keep.updates[0].verified)
        assertEquals(1, keep.summary.unchangedCards)
        val add = ContentPacks.plan(parsed(pack("2", card("k1", "V1", "H1"))), oneDeck, first)
        assertTrue(add.updates[0].verified)
        assertEquals(1, add.summary.changedCards)
    }

    @Test fun `a card the new version dropped is kept as an own card unless asked`() {
        val first = stored(ContentPacks.plan(parsed(pack("1", card("k1", "V1", "H1"), card("k2", "V2", "H2"))), emptyList(), emptyList())).map { it.copy(learnt = true) }
        val next = parsed(pack("2", card("k1", "V1", "H1")))
        val keep = ContentPacks.plan(next, oneDeck, first)
        assertEquals(1, keep.summary.removedCards)
        assertEquals(1, keep.summary.removedWithProgress)
        assertEquals(emptyList(), keep.deletes)
        assertEquals(listOf(2L), keep.detach)
        val del = ContentPacks.plan(next, oneDeck, first, removeMissing = true)
        assertEquals(listOf(2L), del.deletes)
        assertEquals(emptyList(), del.detach)
    }

    @Test fun `unverified new cards can be left out`() {
        val p = ContentPacks.plan(parsed(pack("1", card("k1", "V", "H", ",\"geprüft\":false"), card("k2", "W", "H"))), emptyList(), emptyList(), skipUnverified = true)
        assertEquals(1, p.summary.newCards)
        assertEquals(1, p.summary.skippedUnverified)
    }

    @Test fun `a renamed deck keeps its cards and fach survives when left out`() {
        val first = stored(ContentPacks.plan(parsed(pack("1", card("k1", "V1", "H1"))), emptyList(), emptyList()))
        val next = pack("2", card("k1", "V1", "H1")).replace("Stapel Eins", "Neuer Name").replace(",\"fach\":\"Fach\"", "")
        val p = ContentPacks.plan(parsed(next), oneDeck, first)
        assertEquals(1L, p.decks[0].existingId)
        assertEquals("Neuer Name", p.decks[0].deck.name)
        assertEquals("Fach", p.decks[0].fach)
        assertEquals(0, p.summary.newCards)
    }

    @Test fun `importing the same file twice changes nothing`() {
        val file = parsed(pack("1", card("k1", "V1", "H1"), card("k2", "V2", "H2")))
        val first = stored(ContentPacks.plan(file, emptyList(), emptyList()))
        val again = ContentPacks.plan(file, oneDeck, first).summary
        assertEquals(0, again.newCards)
        assertEquals(0, again.changedCards)
        assertEquals(2, again.unchangedCards)
    }

    @Test fun `another pack is planned on its own`() {
        // The caller passes only what is stored of the pack in question.
        val p = ContentPacks.plan(parsed(pack("1", card("k1", "X", "Y"), id = "anderes.paket")), emptyList(), emptyList())
        assertFalse(p.summary.isUpdate)
        assertEquals(1, p.summary.newCards)
        assertEquals(0, p.summary.removedCards)
    }
}

class IntroducedTodayTest {
    private val today = java.time.LocalDate.of(2026, 11, 5)
    private val d = today.toEpochDay()
    private var id = 1L
    private fun fresh() = PlanCard(id++, CardSchedule(dueEpochDay = d))
    private fun started() = PlanCard(id++, CardSchedule(reps = 1, intervalDays = 1, dueEpochDay = d + 1, lastReviewEpochDay = d), introducedToday = true)
    private fun exam(cards: List<PlanCard>) = PlanExam(1, today.plusDays(5), 1.0, cards)

    @Test fun `new cards already started today use up the quota`() {
        val p = TodayPlanner.build(listOf(exam(List(15) { fresh() } + List(5) { started() })), emptyList(), today, 600)
        assertEquals(0, p.cards.count { it.tier == SessionTier.NEW }, "20 over 4 days: 5 are done")
        val q = TodayPlanner.build(listOf(exam(List(17) { fresh() } + List(3) { started() })), emptyList(), today, 600)
        assertEquals(2, q.cards.count { it.tier == SessionTier.NEW })
    }

    @Test fun `without the flag the quota stays`() {
        assertEquals(4, TodayPlanner.build(listOf(exam(List(15) { fresh() })), emptyList(), today, 600).cards.size)
    }
}
