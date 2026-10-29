package de.millione.schulranzen.rechnen

import android.content.Context
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
import de.millione.schulranzen.core.math.AngleMode
import de.millione.schulranzen.core.math.MathEngine
import de.millione.schulranzen.core.math.MathResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** State of the on-device handwriting model. */
sealed interface InkModelStatus {
    /** Not checked yet. */
    data object Unknown : InkModelStatus
    data object NotDownloaded : InkModelStatus
    data object Downloading : InkModelStatus
    data object Ready : InkModelStatus
    /** [message] is German and can be shown as is. */
    data class Failed(val message: String) : InkModelStatus
}

/**
 * Handwritten maths: recognises pen strokes with Google ML Kit Digital Ink
 * Recognition (German model, about 20 MB, downloaded once on first use; Wi-Fi
 * not required) and solves the most plausible candidate with the core engine.
 *
 * Strokes are given as FloatArrays of x, y, t triples per point. t is the time
 * in milliseconds, best relative to the first point (floats cannot hold epoch
 * millis precisely); all zeros is fine too.
 *
 * Keep one instance per screen or app and [close] it when done.
 */
class MathInk(context: Context) {
    @Suppress("unused")
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var model: DigitalInkRecognitionModel? = null
    private var recognizer: DigitalInkRecognizer? = null

    private val _status = MutableStateFlow<InkModelStatus>(InkModelStatus.Unknown)
    val status: StateFlow<InkModelStatus> = _status.asStateFlow()

    /** Checks whether the model is already on the device without downloading it. */
    suspend fun refreshStatus(): InkModelStatus {
        val m = modelOrNull() ?: return _status.value
        if (_status.value == InkModelStatus.Ready || _status.value == InkModelStatus.Downloading) return _status.value
        val downloaded = runCatching { RemoteModelManager.getInstance().isModelDownloaded(m).await() }.getOrDefault(false)
        _status.value = if (downloaded == true) InkModelStatus.Ready else InkModelStatus.NotDownloaded
        return _status.value
    }

    /** Makes sure the model is on the device, downloading it if needed. Returns true when ready. */
    suspend fun ensureModel(): Boolean = lock.withLock {
        if (_status.value == InkModelStatus.Ready && recognizer != null) return@withLock true
        val m = modelOrNull() ?: return@withLock false
        try {
            val manager = RemoteModelManager.getInstance()
            if (manager.isModelDownloaded(m).await() != true) {
                _status.value = InkModelStatus.Downloading
                manager.download(m, DownloadConditions.Builder().build()).await()
            }
            if (recognizer == null) {
                recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
            }
            _status.value = InkModelStatus.Ready
            true
        } catch (e: Exception) {
            _status.value = InkModelStatus.Failed("Handschriftmodell konnte nicht geladen werden. Internet beim ersten Mal nötig.")
            false
        }
    }

    /**
     * Recognises the strokes and returns the recogniser's candidates, best first.
     * Downloads the model on first use; returns an empty list when that fails.
     */
    suspend fun recognize(strokes: List<FloatArray>): List<String> {
        val points = strokes.filter { it.size >= 3 }
        if (points.isEmpty()) return emptyList()
        if (!ensureModel()) return emptyList()
        val r = recognizer ?: return emptyList()

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        points.forEach { s ->
            var i = 0
            while (i + 1 < s.size) {
                minX = minOf(minX, s[i]); maxX = maxOf(maxX, s[i])
                minY = minOf(minY, s[i + 1]); maxY = maxOf(maxY, s[i + 1])
                i += 3
            }
        }
        // Shift into a padded box; the writing area tells the model the line height.
        val pad = 24f
        val ink = Ink.builder()
        points.forEach { s ->
            val sb = Ink.Stroke.builder()
            var lastT = Long.MIN_VALUE
            var i = 0
            while (i + 2 < s.size) {
                val x = s[i] - minX + pad
                val y = s[i + 1] - minY + pad
                val t = s[i + 2].toLong()
                // Timestamps must not run backwards; without usable times leave them out.
                if (t > 0 && t >= lastT) { sb.addPoint(Ink.Point.create(x, y, t)); lastT = t }
                else sb.addPoint(Ink.Point.create(x, y))
                i += 3
            }
            ink.addStroke(sb.build())
        }
        val area = WritingArea(maxOf(140f, maxX - minX + 2 * pad), maxOf(72f, maxY - minY + 2 * pad))
        val ctx = RecognitionContext.builder()
            // Same arithmetic hint V15 used successfully on the tablet.
            .setPreContext("0123456789 + - * × · / ÷ : [ ] ( ) = ^ sqrt √")
            .setWritingArea(area)
            .build()
        return try {
            r.recognize(ink.build(), ctx).await().candidates.take(24).map { it.text }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Recognises and solves; null when the strokes are no calculation that can be computed. */
    suspend fun solve(strokes: List<FloatArray>, angle: AngleMode = AngleMode.DEG, ans: Double = 0.0): MathResult? {
        val candidates = recognize(strokes)
        return MathEngine.solveHandwriting(candidates, angle, ans)
    }

    fun close() {
        runCatching { recognizer?.close() }
        recognizer = null
        if (_status.value == InkModelStatus.Ready) _status.value = InkModelStatus.Unknown
    }

    private fun modelOrNull(): DigitalInkRecognitionModel? {
        model?.let { return it }
        val id = listOf("de-DE", "de", "en-US").firstNotNullOfOrNull { tag ->
            runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag) }.getOrNull()
        }
        if (id == null) {
            _status.value = InkModelStatus.Failed("Kein Handschriftmodell für Deutsch verfügbar")
            return null
        }
        return DigitalInkRecognitionModel.builder(id).build().also { model = it }
    }
}

/** Play-services Task as a suspend call, without pulling in another library. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
