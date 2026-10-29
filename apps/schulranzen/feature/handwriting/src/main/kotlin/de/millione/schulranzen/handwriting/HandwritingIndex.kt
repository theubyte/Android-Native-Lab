package de.millione.schulranzen.handwriting

import android.content.Context
import android.graphics.RectF
import de.millione.schulranzen.core.shapes.Bounds
import de.millione.schulranzen.core.shapes.TextSearch
import de.millione.schulranzen.ink.PageStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A search hit in a notebook's handwriting. [rect] is the found word (or
 * line) in page units, to scroll to and flash; [highlight] is the range of
 * the found word inside [snippet].
 */
data class Hit(
    val notebookId: String,
    val pageId: String,
    val snippet: String,
    val rect: RectF,
    val score: Float,
    val highlight: IntRange? = null,
)

/** A page whose handwriting is waiting to be indexed (model missing when it changed). */
@Serializable
data class PageRef(val notebookId: String, val pageId: String)

/**
 * Full-text index over handwriting, one small JSON file per page under
 * `filesDir/handwriting/pages/`, written atomically.
 *
 * Recognition is slow compared with writing, so pages are indexed in the
 * background: [schedule] waits until the page has been left alone for
 * [quietMs] and skips pages whose strokes did not change. Without the model
 * (not downloaded, no Wi-Fi) pages are remembered as pending and indexed by
 * [indexPending] later. The model is downloaded automatically only on Wi-Fi.
 */
class HandwritingIndex(
    context: Context,
    private val recognizer: HandwritingRecognizer = HandwritingRecognizer(context),
    private val quietMs: Long = 3_000,
) {
    private val dir = File(context.applicationContext.filesDir, "handwriting").also { it.mkdirs() }
    private val pagesDir = File(dir, "pages").also { it.mkdirs() }
    private val pendingFile = File(dir, "pending.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cache = ConcurrentHashMap<String, PageEntry>()
    private val io = Mutex()
    @Volatile private var loaded = false

    /** Model state for status displays (shared with [HandwritingRecognizer]). */
    val status get() = recognizer.status

    /**
     * Indexes the page after [quietMs] without further calls for it. Call it
     * whenever the page is saved; pass a snapshot of the strokes (the list is
     * read later on a background thread).
     */
    fun schedule(notebookId: String, pageId: String, strokes: List<PageStroke>) {
        val snapshot = strokes.toList()
        scheduleRaw(notebookId, pageId) { InkInput.handwriting(snapshot) }
    }

    /** [schedule] for strokes already given as (stroke id, x/y/t points). */
    fun schedulePoints(notebookId: String, pageId: String, strokes: List<Pair<Long, FloatArray>>) =
        scheduleRaw(notebookId, pageId) { strokes }

    private fun scheduleRaw(notebookId: String, pageId: String, strokes: () -> List<Pair<Long, FloatArray>>) {
        jobs.remove(pageId)?.cancel()
        jobs[pageId] = scope.launch {
            delay(quietMs)
            runCatching { indexPage(notebookId, pageId, strokes()) }
            jobs.remove(pageId)
        }
    }

    /** Recognises and stores the page now. Empty strokes remove it from the index. */
    suspend fun indexPage(notebookId: String, pageId: String, strokes: List<Pair<Long, FloatArray>>) = withContext(Dispatchers.Default) {
        ensureLoaded()
        if (strokes.isEmpty()) { remove(pageId); return@withContext }
        val sig = signature(strokes)
        val old = cache[pageId]
        if (old != null && old.signature == sig && old.notebookId == notebookId) { dropPending(pageId); return@withContext }
        // Download on Wi-Fi only; otherwise keep the page for later.
        val lines = recognizer.recognizeLines(strokes.map { it.second }, downloadIfNeeded = true)
        if (lines == null) { addPending(PageRef(notebookId, pageId)); return@withContext }
        val entry = PageEntry(
            notebookId, pageId, sig, System.currentTimeMillis(),
            lines.map { l -> LineEntry(l.text, Box.of(l.bounds), l.words.map { w -> WordEntry(w.text, Box.of(w.bounds)) }) },
        )
        cache[pageId] = entry
        io.withLock { writeAtomic(pageFile(pageId), json.encodeToString(PageEntry.serializer(), entry)) }
        dropPending(pageId)
    }

    /** Forgets a page (deleted page or emptied). */
    suspend fun remove(pageId: String) = withContext(Dispatchers.IO) {
        ensureLoaded()
        jobs.remove(pageId)?.cancel()
        cache.remove(pageId)
        io.withLock { pageFile(pageId).delete() }
        dropPending(pageId)
    }

    /** Forgets all pages of a notebook (notebook deleted). */
    suspend fun removeNotebook(notebookId: String) {
        ensureLoaded()
        cache.values.filter { it.notebookId == notebookId }.forEach { remove(it.pageId) }
        pending().filter { it.notebookId == notebookId }.forEach { dropPending(it.pageId) }
    }

    /** Recognised text of a page, lines separated by line breaks; null when not indexed. */
    suspend fun pageText(pageId: String): String? {
        ensureLoaded()
        return cache[pageId]?.lines?.joinToString("\n") { it.text }
    }

    /**
     * Searches all indexed handwriting. Forgiving: umlauts/ß in any
     * spelling, prefixes, parts of compounds and small typos (see
     * [TextSearch]). Best hits first, at most [limit], at most 5 per page.
     */
    suspend fun search(query: String, limit: Int = 50): List<Hit> = withContext(Dispatchers.Default) {
        ensureLoaded()
        if (TextSearch.tokens(query).isEmpty()) return@withContext emptyList()
        val hits = ArrayList<Hit>()
        for (page in cache.values) {
            val pageHits = ArrayList<Hit>()
            for (line in page.lines) {
                val words = line.words.map { it.text }.ifEmpty { line.text.split(' ') }
                val m = TextSearch.match(query, words) ?: continue
                val (snippet, range) = TextSearch.snippet(words, m.words)
                val rect = m.words.mapNotNull { line.words.getOrNull(it)?.box?.bounds() }
                    .reduceOrNull { a, b -> a.union(b) } ?: line.box.bounds()
                pageHits += Hit(page.notebookId, page.pageId, snippet, rect.toRectF(), m.score, range)
            }
            if (pageHits.isEmpty()) {
                // Words of a multi-word query on different lines of the same page.
                val lineWords = page.lines.map { l -> l.words.map { it.text }.ifEmpty { l.text.split(' ') } }
                val where = lineWords.flatMapIndexed { li, ws -> ws.indices.map { li to it } }
                val m = TextSearch.match(query, lineWords.flatten())
                if (m != null) {
                    val (li, wi) = where[m.words.first()]
                    val (snippet, range) = TextSearch.snippet(lineWords[li], listOf(wi))
                    pageHits += Hit(page.notebookId, page.pageId, snippet, page.lines[li].box.bounds().toRectF(), m.score * 0.85f, range)
                }
            }
            hits += pageHits.sortedByDescending { it.score }.take(5)
        }
        hits.sortedByDescending { it.score }.take(limit)
    }

    /** Pages that changed while the model was missing. */
    fun pending(): List<PageRef> =
        runCatching { json.decodeFromString(ListSerializer(PageRef.serializer()), pendingFile.readText()) }.getOrDefault(emptyList())

    /**
     * Indexes the pending pages; [load] returns a page's strokes (null when
     * the page no longer exists). Call when the model became ready, e.g.
     * after the download button or at app start.
     */
    suspend fun indexPending(load: suspend (PageRef) -> List<Pair<Long, FloatArray>>?) {
        for (ref in pending()) {
            val strokes = load(ref)
            if (strokes == null) { remove(ref.pageId); continue }
            indexPage(ref.notebookId, ref.pageId, strokes)
            if (status.value != ModelState.Ready) return
        }
    }

    /** Stops background work; the index on disk stays. */
    fun close() { jobs.values.forEach { it.cancel() }; jobs.clear() }

    // ---- storage ----

    private suspend fun ensureLoaded() {
        if (loaded) return
        io.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) {
                pagesDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
                    runCatching { json.decodeFromString(PageEntry.serializer(), f.readText()) }.getOrNull()?.let { cache[it.pageId] = it }
                }
            }
            loaded = true
        }
    }

    private suspend fun addPending(ref: PageRef) = io.withLock {
        val list = pending().filter { it.pageId != ref.pageId } + ref
        writeAtomic(pendingFile, json.encodeToString(ListSerializer(PageRef.serializer()), list))
    }

    private suspend fun dropPending(pageId: String) = io.withLock {
        val list = pending()
        if (list.none { it.pageId == pageId }) return@withLock
        writeAtomic(pendingFile, json.encodeToString(ListSerializer(PageRef.serializer()), list.filter { it.pageId != pageId }))
    }

    private fun pageFile(pageId: String) = File(pagesDir, pageId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** Changes when strokes are added, removed or replaced (moved strokes get new ids). */
    private fun signature(strokes: List<Pair<Long, FloatArray>>): Long {
        var h = 1125899906842597L
        for ((id, pts) in strokes.sortedBy { it.first }) { h = 31 * h + id; h = 31 * h + pts.size }
        return h
    }

    @Serializable
    private data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        fun bounds() = Bounds(l, t, r, b)
        companion object { fun of(b: Bounds) = Box(b.left, b.top, b.right, b.bottom) }
    }

    @Serializable private data class WordEntry(val text: String, val box: Box)
    @Serializable private data class LineEntry(val text: String, val box: Box, val words: List<WordEntry>)
    @Serializable private data class PageEntry(
        val notebookId: String,
        val pageId: String,
        val signature: Long,
        val updated: Long,
        val lines: List<LineEntry>,
    )

    private fun Bounds.toRectF() = RectF(left, top, right, bottom)
}
