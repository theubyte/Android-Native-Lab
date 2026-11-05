package de.millione.schulranzen.core.study

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

data class PackSource(val buch: String = "", val seite: String = "") {
    /** "Fachkunde, S. 14", or null without any source. */
    val line: String?
        get() = when {
            buch.isNotEmpty() && seite.isNotEmpty() -> "$buch, S. $seite"
            buch.isNotEmpty() -> buch
            seite.isNotEmpty() -> "S. $seite"
            else -> null
        }
}

data class PackCard(
    val id: String,
    val vorne: String,
    val hinten: String,
    val vorneBild: String? = null,
    val hintenBild: String? = null,
    val thema: String = "",
    val quelle: PackSource = PackSource(),
    val geprueft: Boolean = false,
)

data class PackDeck(val id: String, val name: String, val fach: String = "", val thema: String = "", val karten: List<PackCard>)

data class ContentPack(
    val id: String,
    val titel: String,
    val version: String,
    val beschreibung: String = "",
    val autor: String = "",
    val lizenz: String = "",
    val stand: String = "",
    val beispiel: Boolean = false,
    val stapel: List<PackDeck>,
)

/** [pack] is null as soon as there is one error: a broken file never reaches the database. */
data class PackParse(val pack: ContentPack?, val errors: List<String>, val warnings: List<String>)

/** A deck of this pack as stored. */
data class StoredPackDeck(val id: Long, val key: String, val fach: String = "")

/** A card of this pack as stored; [key] is "stapel.id/karte.id". */
data class StoredPackCard(
    val id: Long,
    val key: String,
    val front: String,
    val back: String,
    val packFront: String,
    val packBack: String,
    val verified: Boolean,
    val topic: String = "",
    val source: PackSource = PackSource(),
    /** Has been learnt at least once. */
    val learnt: Boolean = false,
)

data class PackSummary(
    val isUpdate: Boolean,
    val newDecks: Int,
    val newCards: Int,
    val changedCards: Int,
    val unchangedCards: Int,
    val keptOwnEdits: Int,
    val removedCards: Int,
    val removedWithProgress: Int,
    val skippedUnverified: Int,
    val unverifiedCards: Int,
    val totalCards: Int,
)

/** A deck to create ([existingId] null) or to bring up to date. */
data class PackDeckPut(val existingId: Long?, val deck: PackDeck, val fach: String)

data class PackNewCard(val deckKey: String, val key: String, val card: PackCard)

/** New text and metadata of a known card. It has no schedule fields, so it cannot touch the learning state. */
data class PackCardUpdate(
    val id: Long,
    val front: String,
    val back: String,
    val verified: Boolean,
    val topic: String,
    val source: PackSource,
    val packFront: String,
    val packBack: String,
)

data class PackImportPlan(
    val summary: PackSummary,
    val decks: List<PackDeckPut>,
    val newCards: List<PackNewCard>,
    val updates: List<PackCardUpdate>,
    /** Cards the new version no longer has: they stay as own cards. */
    val detach: List<Long>,
    val deletes: List<Long>,
)

/**
 * Content packs: JSON files with decks and cards, shared with the web app (format 1, see
 * apps/schulranzen-web/docs/INHALTSPAKET.md). This object only checks files and works out
 * what an import would do, so the preview shows the exact truth. Same checks and same
 * matching as `contentpack.js` there.
 */
object ContentPacks {
    const val FORMAT = "schulranzen-inhaltspaket"
    const val FORMAT_VERSION = 1
    const val MAX_IMAGE_CHARS = 4 * 1024 * 1024

    private val imagePrefix = Regex("^data:image/(png|jpeg|webp);base64,")
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

    /** A value the format allows as text or number. */
    private fun JsonElement?.textOrNumber(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> content.trim()
        else -> ""
    }

    private fun JsonElement?.bool(): Boolean? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let { c -> if (c == "true") true else if (c == "false") false else null }

    /** Small stable hash for cards that came without an id; the same as in the web app. */
    private fun hash(text: String): String {
        var h = -0x7ee3623b // 0x811c9dc5
        for (ch in text) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return Integer.toUnsignedLong(h).toString(36)
    }

    private fun checkImage(value: JsonElement?, where: String, errors: MutableList<String>): String? {
        if (value == null || value is JsonNull) return null
        val s = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (s != null && s.isEmpty()) return null
        val prefix = s?.let { imagePrefix.find(it) }
        if (s == null || prefix == null || decodeBody(s.substring(prefix.range.last + 1)) == null) {
            errors += "$where: Bild muss eine data-URL mit PNG, JPEG oder WebP sein."
            return null
        }
        if (s.length > MAX_IMAGE_CHARS) {
            errors += "$where: Bild ist zu groß (höchstens 3 MB)."
            return null
        }
        return s
    }

    private fun decodeBody(body: String): ByteArray? {
        if (body.isEmpty() || body.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=') }) return null
        return runCatching { Base64.getDecoder().decode(body) }.getOrNull()
    }

    /** File ending and bytes of a checked picture; null if it is none. */
    fun decodeImage(dataUrl: String): Pair<String, ByteArray>? {
        val m = imagePrefix.find(dataUrl) ?: return null
        val bytes = decodeBody(dataUrl.substring(m.range.last + 1)) ?: return null
        return (if (m.groupValues[1] == "jpeg") "jpg" else m.groupValues[1]) to bytes
    }

    fun parse(text: String): PackParse {
        val root = try {
            json.parseToJsonElement(text.removePrefix("﻿"))
        } catch (_: SerializationException) {
            return PackParse(null, listOf("Die Datei ist kein gültiges JSON."), emptyList())
        } catch (_: IllegalArgumentException) {
            return PackParse(null, listOf("Die Datei ist kein gültiges JSON."), emptyList())
        }
        val raw = root as? JsonObject ?: return PackParse(null, listOf("Die Datei enthält kein Inhaltspaket."), emptyList())
        if (raw["format"].text() != FORMAT) {
            return PackParse(null, listOf("Das ist kein Inhaltspaket (Feld \"format\" muss \"$FORMAT\" sein)."), emptyList())
        }
        val fv = (raw["formatVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        if (fv != FORMAT_VERSION.toDouble()) {
            return PackParse(null, listOf("Formatversion ${raw["formatVersion"].textOrNumber()} kennt diese App nicht. Bitte die App aktualisieren."), emptyList())
        }
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (raw["id"].text().isEmpty()) errors += "Feld \"id\" fehlt."
        if (raw["titel"].text().isEmpty()) errors += "Feld \"titel\" fehlt."
        if (raw["version"].textOrNumber().isEmpty()) errors += "Feld \"version\" fehlt."
        val rawDecks = raw["stapel"] as? JsonArray
        if (rawDecks == null || rawDecks.isEmpty()) errors += "Das Paket enthält keine Stapel."
        if (errors.isNotEmpty() || rawDecks == null) return PackParse(null, errors, warnings)

        val deckIds = HashSet<String>()
        var derived = 0
        var noStatus = 0
        val stapel = mutableListOf<PackDeck>()
        rawDecks.forEachIndexed { di, de ->
            val where = "Stapel ${di + 1}"
            val d = de as? JsonObject
            if (d == null) { errors += "$where: kein Objekt."; return@forEachIndexed }
            val id = d["id"].text()
            val name = d["name"].text()
            if (id.isEmpty()) errors += "$where: Feld \"id\" fehlt."
            if (name.isEmpty()) errors += "$where: Feld \"name\" fehlt."
            if (id.isNotEmpty() && !deckIds.add(id)) errors += "$where: id \"$id\" kommt doppelt vor."
            val rawCards = d["karten"] as? JsonArray
            if (rawCards == null) { errors += "$where: Feld \"karten\" fehlt."; return@forEachIndexed }
            val cardIds = HashSet<String>()
            val karten = mutableListOf<PackCard>()
            rawCards.forEachIndexed { ki, ke ->
                val at = "$where, Karte ${ki + 1}"
                val k = ke as? JsonObject
                if (k == null) { errors += "$at: kein Objekt."; return@forEachIndexed }
                val vorne = k["vorne"].text()
                val hinten = k["hinten"].text()
                val vorneBild = checkImage(k["vorneBild"], at, errors)
                val hintenBild = checkImage(k["hintenBild"], at, errors)
                if (vorne.isEmpty() && vorneBild == null) errors += "$at: Vorderseite fehlt."
                if (hinten.isEmpty() && hintenBild == null) errors += "$at: Rückseite fehlt."
                var cid = k["id"].text()
                if (cid.isEmpty()) { cid = "auto-" + hash(AnswerCheck.normalize(vorne)); derived++ }
                if (!cardIds.add(cid)) errors += "$at: id \"$cid\" kommt doppelt vor."
                // The key may arrive with a decomposed umlaut, depending on the editor.
                val status = (k["geprüft"] ?: k["geprüft"]).bool()
                if (status == null) noStatus++
                val q = k["quelle"] as? JsonObject ?: d["quelle"] as? JsonObject
                karten += PackCard(
                    cid, vorne, hinten, vorneBild, hintenBild,
                    thema = k["thema"].text().ifEmpty { d["thema"].text() },
                    quelle = PackSource(q?.get("buch").text(), q?.get("seite").textOrNumber()),
                    geprueft = status == true,
                )
            }
            stapel += PackDeck(id, name, d["fach"].text(), d["thema"].text(), karten)
        }
        if (derived > 0) warnings += "$derived Karten haben keine eigene id. Ändert sich ihre Vorderseite, gilt der Lernfortschritt als neue Karte."
        if (noStatus > 0) warnings += "$noStatus Karten haben keinen Prüfstatus und gelten als ungeprüft."
        if (errors.isNotEmpty()) return PackParse(null, errors, warnings)
        return PackParse(
            ContentPack(
                raw["id"].text(), raw["titel"].text(), raw["version"].textOrNumber(),
                raw["beschreibung"].text(), raw["autor"].text(), raw["lizenz"].text(), raw["stand"].text(),
                raw["beispiel"].bool() == true, stapel,
            ),
            errors, warnings,
        )
    }

    /** "1.10.0" against "1.9": runs of digits count as numbers. Negative when [a] is older. */
    fun compareVersions(a: String, b: String): Int {
        val run = Regex("\\d+|\\D+")
        val x = run.findAll(a.trim()).map { it.value }.toList()
        val y = run.findAll(b.trim()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) p.toBigInteger().compareTo(q.toBigInteger()) else p.lowercase().compareTo(q.lowercase())
            if (c != 0) return c
        }
        return x.size - y.size
    }

    /**
     * What importing [pack] would do. [decks] and [cards] are what is stored of this same pack.
     * [removeMissing] deletes cards the new version no longer has (otherwise they become own
     * cards); [skipUnverified] leaves out new cards that are not marked as checked.
     */
    fun plan(
        pack: ContentPack,
        decks: List<StoredPackDeck>,
        cards: List<StoredPackCard>,
        removeMissing: Boolean = false,
        skipUnverified: Boolean = false,
    ): PackImportPlan {
        var newDecks = 0
        var newCards = 0
        var changed = 0
        var unchanged = 0
        var keptOwn = 0
        var skipped = 0
        var unverified = 0
        var total = 0
        val deckPuts = mutableListOf<PackDeckPut>()
        val news = mutableListOf<PackNewCard>()
        val updates = mutableListOf<PackCardUpdate>()
        val seen = HashSet<String>()
        val byKey = cards.associateBy { it.key }
        for (d in pack.stapel) {
            val old = decks.firstOrNull { it.key == d.id }
            if (old == null) newDecks++
            deckPuts += PackDeckPut(old?.id, d, d.fach.ifEmpty { old?.fach.orEmpty() })
            for (k in d.karten) {
                total++
                if (!k.geprueft) unverified++
                val key = "${d.id}/${k.id}"
                seen += key
                val c = byKey[key]
                if (c == null) {
                    if (skipUnverified && !k.geprueft) { skipped++; continue }
                    newCards++
                    news += PackNewCard(d.id, key, k)
                    continue
                }
                val packChanged = c.packFront != k.vorne || c.packBack != k.hinten
                val ownEdit = c.front != c.packFront || c.back != c.packBack
                var front = c.front
                var back = c.back
                var verified = c.verified
                when {
                    packChanged && !ownEdit -> {
                        front = k.vorne
                        back = k.hinten
                        verified = k.geprueft
                        changed++
                    }
                    // The learner's own wording wins; the new text becomes the baseline.
                    packChanged -> keptOwn++
                    else -> {
                        // Same text: a learner's own "geprüft" stays, the pack may only add one.
                        verified = c.verified || k.geprueft
                        if (verified != c.verified || c.topic != k.thema || c.source != k.quelle) changed++ else unchanged++
                    }
                }
                updates += PackCardUpdate(c.id, front, back, verified, k.thema, k.quelle, k.vorne, k.hinten)
            }
        }
        val gone = cards.filter { it.key !in seen }
        return PackImportPlan(
            PackSummary(
                isUpdate = decks.isNotEmpty(), newDecks = newDecks, newCards = newCards, changedCards = changed, unchangedCards = unchanged,
                keptOwnEdits = keptOwn, removedCards = gone.size, removedWithProgress = gone.count { it.learnt },
                skippedUnverified = skipped, unverifiedCards = unverified, totalCards = total,
            ),
            deckPuts, news, updates,
            detach = if (removeMissing) emptyList() else gone.map { it.id },
            deletes = if (removeMissing) gone.map { it.id } else emptyList(),
        )
    }
}
