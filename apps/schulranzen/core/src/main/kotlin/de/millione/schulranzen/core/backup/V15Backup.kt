package de.millione.schulranzen.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Reads a full backup ZIP of the old app (V15 BackupManager.java):
 * `files/…` (notebooks, book PDFs, annotations, pictures),
 * `shared_prefs/…` (library_v9: items and subjects, study_v9: exams,
 * cards, marks, bag: server and e-mail) and `backup-manifest.json` with
 * SHA-256 per file. Older backups without manifest are read as well.
 */
object V15Backup {
    private const val MAX_TEXT = 64L * 1024 * 1024

    /** [progress] gets a short German step label and 0…1. */
    fun read(zip: File, verify: Boolean = true, includeTrashed: Boolean = false, progress: (String, Float) -> Unit = { _, _ -> }): ImportedLibrary {
        val z = try { ZipFile(zip) } catch (e: ZipException) { throw BackupException("Die Datei ist kein gültiges ZIP-Backup.", e) }
        try {
            return Reader(z, verify, includeTrashed, progress).read()
        } catch (e: Exception) {
            z.close()
            throw if (e is BackupException) e else BackupException("Backup der alten App konnte nicht gelesen werden: ${e.message}", e)
        }
    }

    private class ZipFiles(private val z: ZipFile, private val entries: Map<String, ZipEntry>) : ArchiveFiles {
        override fun exists(entry: String) = entry in entries
        override fun size(entry: String) = entries[entry]?.size ?: -1
        override fun open(entry: String): InputStream =
            z.getInputStream(entries[entry] ?: throw IOException("Nicht im Backup: $entry"))
        override fun close() = z.close()
    }

    private class Reader(val z: ZipFile, val verify: Boolean, val includeTrashed: Boolean, val progress: (String, Float) -> Unit) {
        val warnings = ArrayList<String>()
        val entries = LinkedHashMap<String, ZipEntry>()
        val files: ZipFiles

        init {
            var unsafe = 0
            for (e in z.entries()) {
                if (e.isDirectory) continue
                if (!ZipPaths.isSafe(e.name)) { unsafe++; continue }
                entries.putIfAbsent(e.name, e)
            }
            if (unsafe > 0) warnings += "$unsafe unsichere Einträge im Backup wurden übersprungen."
            files = ZipFiles(z, entries)
        }

        fun text(name: String): String? {
            val e = entries[name] ?: return null
            if (e.size > MAX_TEXT) { warnings += "Datei zu groß, übersprungen: $name"; return null }
            return z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
        }

        fun bytes(name: String): ByteArray? = entries[name]?.let { e -> z.getInputStream(e).use { it.readBytes() } }

        fun prefs(name: String): Map<String, Any> {
            val b = bytes("shared_prefs/$name.xml") ?: return emptyMap()
            return try { AndroidPrefsXml.parse(b) } catch (e: IOException) { warnings += "Einstellungen „$name“ nicht lesbar."; emptyMap() }
        }

        fun read(): ImportedLibrary {
            val (created, verified) = checkManifest()
            if (entries.keys.none { it.startsWith("files/") || it.startsWith("shared_prefs/") }) {
                throw BackupException("Das ZIP enthält keine Daten der alten App.")
            }
            progress("Bibliothek lesen", 0.3f)
            val lib = prefs("library_v9")
            val bag = prefs("bag")
            val subjects = parseSubjects(lib["subjects"] as? String)
            val items = parseItems(lib["items"] as? String, bag)

            val notebooks = ArrayList<ImportedNotebook>()
            val books = ArrayList<ImportedBook>()
            val usable = items.filter { includeTrashed || !it.deleted }
            usable.forEachIndexed { i, item ->
                progress("Hefte lesen", 0.3f + 0.6f * i / maxOf(1, usable.size))
                when (item.type) {
                    "book" -> books += book(item)
                    "notebook", "worksheet", "timetable" -> notebook(item)?.let { notebooks += it }
                    else -> warnings += "Unbekannter Eintrag „${item.title}“ übersprungen."
                }
            }
            // Subjects used by items but missing in the list (renamed or removed in V15).
            val all = ArrayList(subjects)
            val known = subjects.mapTo(HashSet()) { it.name.lowercase() }
            for (item in usable) if (known.add(item.subject.lowercase())) all += ImportedSubject(item.subject, item.color, SubjectColors.nearest(item.color))

            progress("Lerndaten lesen", 0.95f)
            val study = parseStudy(prefs("study_v9"))
            progress("Fertig", 1f)
            return ImportedLibrary(
                subjects = all, notebooks = notebooks, books = books, study = study, warnings = warnings,
                created = created, verified = verified,
                syncServer = (bag["server"] as? String)?.takeIf { it.isNotBlank() },
                syncEmail = (bag["mail"] as? String)?.takeIf { it.isNotBlank() },
                files = files,
            )
        }

        /** Checks the V15 checksums; mismatches are warnings, the rest is still usable. */
        fun checkManifest(): Pair<Long?, Boolean> {
            val text = text(BackupManifest.V15_FILE_NAME) ?: run {
                warnings += "Backup ohne Prüfsummen (ältere Version): Vollständigkeit nicht prüfbar."
                return null to false
            }
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: run {
                warnings += "Prüfsummen-Liste des Backups ist beschädigt."
                return null to false
            }
            val created = root.long("created")
            if ((root.int("format") ?: 0) < 14) warnings += "Unbekanntes Backup-Format, wird trotzdem versucht."
            if (!verify) return created to false
            val list = (root["entries"] as? JsonArray).orEmpty()
            val total = list.sumOf { (it as? JsonObject)?.long("size") ?: 0L }.coerceAtLeast(1)
            var done = 0L
            var ok = true
            for (el in list) {
                val o = el as? JsonObject ?: continue
                val path = o.str("path") ?: continue
                val size = o.long("size") ?: -1
                val e = entries[path]
                if (e == null) { warnings += "Fehlt im Backup: $path"; ok = false; continue }
                val sum = z.getInputStream(e).use { Hashing.sha256(it) }
                if (!sum.equals(o.str("sha256").orEmpty(), ignoreCase = true) || (size >= 0 && e.size >= 0 && e.size != size)) {
                    warnings += "Prüfsumme stimmt nicht: $path"; ok = false
                }
                done += maxOf(0L, size)
                progress("Backup prüfen", 0.3f * done / total)
            }
            return created to ok
        }

        class Item(
            val id: String, val type: String, val title: String, val subject: String, val uri: String,
            val color: Int, val created: Long, val modified: Long, val deleted: Boolean,
        )

        fun parseItems(json: String?, bag: Map<String, Any>): List<Item> {
            val arr = json?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }
            if (arr == null || arr.isEmpty()) return legacyItems(bag)
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                val created = o.long("created") ?: 0L
                Item(
                    id = id, type = o.str("type") ?: "notebook",
                    title = o.str("title")?.takeIf { it.isNotBlank() } ?: "Unbenannt",
                    subject = o.str("subject")?.takeIf { it.isNotBlank() } ?: "Allgemein",
                    uri = o.str("uri").orEmpty(), color = o.int("color") ?: 0xff3157d5.toInt(),
                    created = created, modified = o.long("modified") ?: created, deleted = o.bool("deleted") ?: false,
                )
            }
        }

        /** Before V9 there was one notebook and maybe one book (LibraryStore.migrateLegacy). */
        fun legacyItems(bag: Map<String, Any>): List<Item> {
            val out = ArrayList<Item>()
            val title = (bag["notebook_name"] as? String ?: "SHK Fachkunde").replace('\n', ' ')
            val subject = if (title.lowercase().contains("shk")) "SHK" else "Allgemein"
            if (V15Notebook.candidates("legacy-main").any { it.first.let { n -> "files/$n" in entries } }) {
                out += Item("legacy-main", "notebook", title, subject, "", 0xff3157d5.toInt(), 0, 0, false)
            }
            val bookUri = bag["book_uri"] as? String
            if (!bookUri.isNullOrEmpty()) out += Item("legacy-book", "book", bag["book_name"] as? String ?: "Schulbuch", subject, bookUri, 0xff6a4d3b.toInt(), 0, 0, false)
            return out
        }

        fun parseSubjects(json: String?): List<ImportedSubject> {
            val arr = json?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: return emptyList()
            val seen = HashSet<String>()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = o.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (!seen.add(name.lowercase())) return@mapNotNull null
                val color = o.int("color") ?: 0xff6b7280.toInt()
                ImportedSubject(name, color, SubjectColors.nearest(color))
            }
        }

        fun notebook(item: Item): ImportedNotebook? {
            val kind = when (item.type) { "worksheet" -> NotebookKind.WORKSHEET; "timetable" -> NotebookKind.TIMETABLE; else -> NotebookKind.NOTEBOOK }
            var pages: List<V15Notebook.Page>? = null
            var lastError: Exception? = null
            for ((name, format) in V15Notebook.candidates(item.id)) {
                val b = bytes("files/$name") ?: continue
                try {
                    val p = V15Notebook.read(b, format)
                    // NotebookView fell through to an older file when a newer one was empty or broken.
                    if (p.isNotEmpty()) { pages = p; break }
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (pages == null) {
                if (lastError != null) warnings += "Heft „${item.title}“ ist beschädigt und wurde übersprungen."
                // Never opened in V15: an empty notebook with one page, as V15 showed it.
                pages = listOf(V15Notebook.Page(false, false, if (kind == NotebookKind.TIMETABLE) 7 else 0, "", "", emptyList(), emptyList(), emptyList()))
            }
            val resolve = { path: String -> V15Notebook.entryFor(path) { it in entries } }
            val converted = pages.map { p -> V15Notebook.convert(p, resolve) { w -> warnings += "${item.title}: $w" } }
            return ImportedNotebook(
                oldId = item.id, title = item.title, subjectName = item.subject, kind = kind,
                paper = converted.firstOrNull()?.paper ?: ImportedPaper.LINED,
                created = item.created, modified = item.modified, trashed = item.deleted, pages = converted,
            )
        }

        fun book(item: Item): ImportedBook {
            val safe = V15Notebook.safeId(item.id)
            var pdf: String? = "files/books/$safe.pdf".takeIf { it in entries }
            if (pdf == null && item.uri.startsWith("appfile:")) {
                val rel = item.uri.removePrefix("appfile:").trimStart('/')
                pdf = "files/$rel".takeIf { ZipPaths.isSafe(it) && it in entries }
            }
            if (pdf == null) warnings += "Buch „${item.title}“: PDF ist nicht im Backup (nur Notizen werden übernommen)."
            val (pages, favorites) = annotations(text("files/book-annotations-v12-$safe.json"), item.title)
            return ImportedBook(
                oldId = item.id, title = item.title, subjectName = item.subject, pdfEntry = pdf,
                pdfSize = pdf?.let { entries[it]?.size } ?: 0L,
                created = item.created, modified = item.modified, trashed = item.deleted,
                pages = pages, favorites = favorites,
            )
        }

        fun annotations(json: String?, title: String): Pair<List<ImportedBookPage>, List<ImportedFavorite>> {
            if (json == null) return emptyList<ImportedBookPage>() to emptyList()
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: run {
                warnings += "Buch „$title“: Notizen sind beschädigt."
                return emptyList<ImportedBookPage>() to emptyList()
            }
            val pages = (root["pages"] as? JsonArray).orEmpty().mapNotNull { el ->
                val po = el as? JsonObject ?: return@mapNotNull null
                val index = po.int("page") ?: return@mapNotNull null
                val strokes = (po["strokes"] as? JsonArray).orEmpty().mapNotNull { s ->
                    val so = s as? JsonObject ?: return@mapNotNull null
                    val pts = (so["pts"] as? JsonArray).orEmpty().mapNotNull { q ->
                        val a = q as? JsonArray ?: return@mapNotNull null
                        val x = a.getOrNull(0).float() ?: return@mapNotNull null
                        val y = a.getOrNull(1).float() ?: return@mapNotNull null
                        floatArrayOf(x, y, (a.getOrNull(2).float() ?: 1f).coerceIn(0f, 1f), (a.getOrNull(3).float() ?: 0f).coerceIn(0f, 1.5707964f))
                    }.filter { it[0].isFinite() && it[1].isFinite() }
                    if (pts.isEmpty()) return@mapNotNull null
                    val tool = if (so.int("tool") == 2) ImportedTool.MARKER else ImportedTool.PEN
                    val arr = FloatArray(pts.size * 4).also { a -> pts.forEachIndexed { i, p -> p.copyInto(a, i * 4) } }
                    ImportedStroke(tool, so.int("color") ?: 0xff000000.toInt(), so.double("width")?.toFloat() ?: 0.004f, arr)
                }
                val notes = (po["notes"] as? JsonArray).orEmpty().mapNotNull { n ->
                    val no = n as? JsonObject ?: return@mapNotNull null
                    val text = no.str("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ImportedNote(no.double("x")?.toFloat() ?: 0f, no.double("y")?.toFloat() ?: 0f, text, no.long("created") ?: 0L)
                }
                val bookmarked = po.bool("bookmark") ?: false
                if (strokes.isEmpty() && notes.isEmpty() && !bookmarked) null
                else ImportedBookPage(index, bookmarked, strokes, notes)
            }.sortedBy { it.index }
            val favorites = (root["favorites"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                ImportedFavorite(
                    o.int("page") ?: 0, o.double("l")?.toFloat() ?: 0f, o.double("t")?.toFloat() ?: 0f,
                    o.double("r")?.toFloat() ?: 0f, o.double("b")?.toFloat() ?: 0f,
                    o.str("label") ?: "Markierung", o.long("created") ?: 0L,
                )
            }
            return pages to favorites
        }

        fun parseStudy(p: Map<String, Any>): ImportedStudy {
            fun arr(key: String) = (p[key] as? String)?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }.orEmpty()
            val exams = arr("exams").mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                ImportedExam(
                    o.str("id") ?: return@mapNotNull null, o.str("title") ?: "Prüfung", o.str("subject") ?: "Allgemein",
                    o.str("date").orEmpty(), (o["sources"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                )
            }
            val cards = arr("cards").mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                ImportedCard(
                    o.str("id") ?: return@mapNotNull null, o.str("examId").orEmpty(), o.str("front").orEmpty(), o.str("back").orEmpty(),
                    o.long("due") ?: 0L, o.int("reps") ?: 0, o.int("interval") ?: 0, o.double("ease") ?: 2.5, o.int("rating") ?: -1,
                )
            }
            val marks = arr("marks").mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                ImportedMark(
                    o.str("id") ?: return@mapNotNull null, o.str("itemId").orEmpty(), o.int("page") ?: 0, o.str("itemTitle").orEmpty(),
                    o.str("subject") ?: "Allgemein", o.str("label") ?: "Für Prüfung merken",
                    o.double("left")?.toFloat() ?: -1f, o.double("top")?.toFloat() ?: -1f,
                    o.double("right")?.toFloat() ?: -1f, o.double("bottom")?.toFloat() ?: -1f, o.long("created") ?: 0L,
                )
            }
            return ImportedStudy(exams, cards, marks)
        }
    }

    private fun JsonObject.prim(k: String) = this[k] as? JsonPrimitive
    private fun JsonObject.str(k: String) = prim(k)?.contentOrNull
    private fun JsonObject.int(k: String) = prim(k)?.intOrNull ?: prim(k)?.doubleOrNull?.toInt()
    private fun JsonObject.long(k: String) = prim(k)?.longOrNull ?: prim(k)?.doubleOrNull?.toLong()
    private fun JsonObject.double(k: String) = prim(k)?.doubleOrNull
    private fun JsonObject.bool(k: String) = prim(k)?.booleanOrNull
    private fun JsonElement?.float(): Float? = (this as? JsonPrimitive)?.doubleOrNull?.toFloat()
}

/**
 * The new app's subject colours (design SubjectPalette, same order), so a
 * V15 colour can be mapped to the nearest palette index without depending
 * on the design module.
 */
object SubjectColors {
    val palette = intArrayOf(
        0xFF2F6F73.toInt(), // Petrol
        0xFFB5523B.toInt(), // Ziegel
        0xFFC99A2E.toInt(), // Senf
        0xFF7C9A6D.toInt(), // Salbei
        0xFF5F7896.toInt(), // Taube
        0xFF7D5A7A.toInt(), // Pflaume
        0xFFB07A3B.toInt(), // Ocker
        0xFF5B5A57.toInt(), // Graphit
    )

    private const val GRAPHITE = 7

    /**
     * Nearest palette entry. The palette is muted, V15 used bright colours,
     * so plain RGB distance picks badly; hue decides instead, and greys
     * (low saturation) become Graphit.
     */
    fun nearest(argb: Int): Int {
        val (h, s) = hueSat(argb)
        if (s < 0.2f) return GRAPHITE
        var best = 0
        var bestD = Float.MAX_VALUE
        palette.forEachIndexed { i, c ->
            if (i == GRAPHITE) return@forEachIndexed
            val d0 = kotlin.math.abs(h - hueSat(c).first)
            val d = minOf(d0, 360f - d0)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun hueSat(argb: Int): Pair<Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f; val g = ((argb shr 8) and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        if (max <= 0f || d <= 0f) return 0f to 0f
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return (if (h < 0) h + 360f else h) to d / max
    }
}
