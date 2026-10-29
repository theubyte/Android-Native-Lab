package de.millione.schulranzen.handwriting

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import de.millione.schulranzen.core.shapes.Bounds
import de.millione.schulranzen.core.shapes.LineGrouper
import de.millione.schulranzen.core.shapes.TextLine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** State of the on-device German handwriting model; [message] is German and ready to show. */
sealed interface ModelState {
    val message: String

    data object Unknown : ModelState { override val message = "Schrifterkennung wird geprüft …" }
    data object Missing : ModelState { override val message = "Schriftmodell noch nicht geladen (ca. 20 MB, einmalig)." }
    data object WaitingForWifi : ModelState {
        override val message = "Kein WLAN. Das Schriftmodell (ca. 20 MB) wird geladen, sobald WLAN da ist."
    }
    data object Offline : ModelState {
        override val message = "Keine Internetverbindung. Das Schriftmodell (ca. 20 MB) braucht einmalig Internet, danach geht alles offline."
    }
    data object Downloading : ModelState { override val message = "Schriftmodell wird geladen (ca. 20 MB) …" }
    data object Ready : ModelState { override val message = "Schrifterkennung bereit, auch offline." }
    data class Failed(override val message: String) : ModelState
}

/** One recognised word with its place on the page (page units). */
data class RecognizedWord(val text: String, val bounds: Bounds)

/** One recognised line: its text, its box and its words. [strokes] index the input list. */
data class RecognizedLine(val text: String, val bounds: Bounds, val words: List<RecognizedWord>, val strokes: List<Int>, val block: Int)

/**
 * Handwriting to text with Google ML Kit Digital Ink Recognition (German).
 *
 * The model is shared by the whole app (ML Kit keeps one copy on the device,
 * the same one the maths recogniser uses) and fetched once, by default only
 * over Wi-Fi. Strokes are FloatArrays of x, y, t triples in page units, t in
 * ms (zeros are fine). Lines are found geometrically first ([LineGrouper]),
 * then recognised one by one, so a selection over several lines reads
 * correctly and keeps its line breaks.
 */
class HandwritingRecognizer(context: Context) {
    private val app = context.applicationContext

    /** App-wide model state, shared by all instances. */
    val status: StateFlow<ModelState> get() = Shared.status

    /** Checks whether the model is on the device, without downloading anything. */
    suspend fun refreshStatus(): ModelState = Shared.refresh()

    /**
     * Fetches the model if needed. Without [allowMobileData] it waits for
     * Wi-Fi (state [ModelState.WaitingForWifi]) instead of using mobile data.
     * Returns true when the model is ready.
     */
    suspend fun download(allowMobileData: Boolean = false): Boolean = Shared.ensure(app, download = true, allowMobileData)

    /** True when the model is ready; downloads only when [download] and the network allows it. */
    suspend fun ensureReady(download: Boolean = true): Boolean = Shared.ensure(app, download, allowMobileData = false)

    /**
     * Recognised text of the strokes, lines joined with line breaks; null when
     * nothing could be read or the model is not available (see [status]).
     */
    suspend fun recognize(strokes: List<FloatArray>): String? =
        recognizeLines(strokes)?.joinToString("\n") { it.text }?.takeIf { it.isNotBlank() }

    /** Like [recognize] but with lines, words and their boxes; null when the model is missing. */
    suspend fun recognizeLines(strokes: List<FloatArray>, downloadIfNeeded: Boolean = true): List<RecognizedLine>? {
        val usable = strokes.indices.filter { strokes[it].size >= 3 }
        if (usable.isEmpty()) return emptyList()
        if (!Shared.ensure(app, downloadIfNeeded, allowMobileData = false)) return null
        val r = Shared.recognizer ?: return null
        val bounds = usable.map { Bounds.ofStroke(strokes[it])!! }
        val lines = LineGrouper.group(bounds)
        val out = ArrayList<RecognizedLine>()
        var before = ""
        for (line in lines) {
            val order = line.strokes.map { usable[it] }
            val text = try {
                r.recognize(ink(order.map { strokes[it] }, line.bounds), context(line, before)).await()
                    .candidates.firstOrNull()?.text?.trim()
            } catch (e: Exception) {
                null
            }
            if (text.isNullOrEmpty()) continue
            out += RecognizedLine(text, line.bounds, words(text, line), order, line.block)
            before = text
        }
        return out
    }

    /** Builds the ML Kit ink, shifted near the origin, with times running forward across strokes. */
    private fun ink(strokes: List<FloatArray>, box: Bounds): Ink {
        val pad = 16f
        val ink = Ink.builder()
        var clock = 0L
        for (s in strokes) {
            val sb = Ink.Stroke.builder()
            val t0 = s[2]
            var last = clock
            var i = 0
            while (i + 2 < s.size) {
                val t = clock + max(0f, s[i + 2] - t0).toLong()
                last = max(last, t)
                sb.addPoint(Ink.Point.create(s[i] - box.left + pad, s[i + 1] - box.top + pad, last))
                i += 3
            }
            ink.addStroke(sb.build())
            clock = last + 150
        }
        return ink.build()
    }

    private fun context(line: TextLine, before: String): RecognitionContext {
        val b = line.bounds
        return RecognitionContext.builder()
            // Previous line as context helps with words split across lines.
            .setPreContext(before.takeLast(20))
            .setWritingArea(WritingArea(max(140f, b.width + 32f), max(48f, b.height + 32f)))
            .build()
    }

    /** Places the recognised words on the word clusters; proportional to their length when counts differ. */
    private fun words(text: String, line: TextLine): List<RecognizedWord> {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        if (tokens.size == line.wordBounds.size) return tokens.zip(line.wordBounds) { t, b -> RecognizedWord(t, b) }
        val b = line.bounds
        val total = tokens.sumOf { it.length + 1 }.toFloat()
        var x = b.left
        return tokens.map { t ->
            val w = b.width * (t.length + 1) / total
            RecognizedWord(t, Bounds(x, b.top, x + w, b.bottom)).also { x += w }
        }
    }

    /** One model and one recogniser for the whole process. */
    private object Shared {
        val status = MutableStateFlow<ModelState>(ModelState.Unknown)
        private val lock = Mutex()
        private var model: DigitalInkRecognitionModel? = null
        @Volatile var recognizer: DigitalInkRecognizer? = null

        private fun model(): DigitalInkRecognitionModel? {
            model?.let { return it }
            val id = listOf("de-DE", "de").firstNotNullOfOrNull { tag ->
                runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag) }.getOrNull()
            } ?: return null
            return DigitalInkRecognitionModel.builder(id).build().also { model = it }
        }

        suspend fun refresh(): ModelState {
            if (status.value == ModelState.Ready || status.value == ModelState.Downloading) return status.value
            val m = model() ?: return ModelState.Failed("Für Deutsch gibt es kein Schriftmodell.").also { status.value = it }
            val have = runCatching { RemoteModelManager.getInstance().isModelDownloaded(m).await() }.getOrDefault(false) == true
            status.value = if (have) ModelState.Ready else ModelState.Missing
            return status.value
        }

        suspend fun ensure(context: Context, download: Boolean, allowMobileData: Boolean): Boolean = lock.withLock {
            if (recognizer != null && status.value == ModelState.Ready) return@withLock true
            val m = model() ?: run {
                status.value = ModelState.Failed("Für Deutsch gibt es kein Schriftmodell.")
                return@withLock false
            }
            try {
                val manager = RemoteModelManager.getInstance()
                if (manager.isModelDownloaded(m).await() != true) {
                    if (!download) { status.value = ModelState.Missing; return@withLock false }
                    when (network(context)) {
                        Net.NONE -> { status.value = ModelState.Offline; return@withLock false }
                        Net.METERED -> if (!allowMobileData) { status.value = ModelState.WaitingForWifi; return@withLock false }
                        Net.UNMETERED -> Unit
                    }
                    status.value = ModelState.Downloading
                    val conditions = DownloadConditions.Builder().apply { if (!allowMobileData) requireWifi() }.build()
                    manager.download(m, conditions).await()
                }
                if (recognizer == null) recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
                status.value = ModelState.Ready
                true
            } catch (e: Exception) {
                status.value = when (network(context)) {
                    Net.NONE -> ModelState.Offline
                    else -> ModelState.Failed("Schriftmodell konnte nicht geladen werden. Später noch einmal versuchen.")
                }
                false
            }
        }
    }

    private enum class Net { NONE, METERED, UNMETERED }

    private companion object {
        fun network(context: Context): Net {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Net.NONE
            val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return Net.NONE
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Net.NONE
            return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) Net.UNMETERED else Net.METERED
        }
    }
}

/** Play-services Task as a suspend call. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
