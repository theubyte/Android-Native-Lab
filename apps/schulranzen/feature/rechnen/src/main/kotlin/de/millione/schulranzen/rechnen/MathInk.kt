package de.millione.schulranzen.rechnen

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
import de.millione.schulranzen.core.math.AngleMode
import de.millione.schulranzen.core.math.MathEngine
import de.millione.schulranzen.core.math.MathResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
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
    /** [message] is German and can be shown as is. [canForce]: only blocked by the Wi-Fi rule. */
    data class Failed(val message: String, val canForce: Boolean = false) : InkModelStatus
}

/** Outcome of [MathInk.solveOrExplain]: a result, nothing readable, or why recognition cannot run. */
sealed interface InkSolve {
    data class Solved(val result: MathResult) : InkSolve
    data object NotRecognized : InkSolve
    /** German message, e.g. the model is missing and the device is offline. */
    data class Unavailable(val message: String) : InkSolve
}

/**
 * Handwritten maths: recognises pen strokes with Google ML Kit Digital Ink
 * Recognition (German model, about 20 MB, downloaded once) and solves the
 * most plausible candidate with the core engine.
 *
 * The model state is shared by the whole process ([modelStatus]); the SHK
 * screen offers a preload button via [ensureModel], so the first circled
 * calculation also works offline later.
 *
 * Strokes are given as FloatArrays of x, y, t triples per point. t is the time
 * in milliseconds, best relative to the first point (floats cannot hold epoch
 * millis precisely); all zeros is fine too.
 *
 * Keep one instance per screen or app and [close] it when done.
 */
class MathInk(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var recognizer: DigitalInkRecognizer? = null

    /** Same as [MathInk.modelStatus]. */
    val status: StateFlow<InkModelStatus> get() = modelStatus

    /** Checks whether the model is already on the device without downloading it. */
    suspend fun refreshStatus(): InkModelStatus = checkModel()

    /**
     * Makes sure the model is on the device, downloading it if needed (any
     * network, since the user asked for a calculation). Returns true when ready.
     */
    suspend fun ensureModel(): Boolean = lock.withLock {
        if (shared.value == InkModelStatus.Ready && recognizer != null) return@withLock true
        val m = modelOrNull() ?: return@withLock false
        if (!download(appContext, m, wifiOnly = false)) return@withLock false
        if (recognizer == null) {
            recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
        }
        true
    }

    /** Like [solve], but says why nothing came out (model missing, offline). */
    suspend fun solveOrExplain(strokes: List<FloatArray>, angle: AngleMode = AngleMode.DEG, ans: Double = 0.0): InkSolve {
        if (!ensureModel()) {
            return InkSolve.Unavailable((shared.value as? InkModelStatus.Failed)?.message ?: "Handschrift-Modell nicht verfügbar")
        }
        return solve(strokes, angle, ans)?.let { InkSolve.Solved(it) } ?: InkSolve.NotRecognized
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
    }

    companion object {
        /** Approximate download size shown in the UI. */
        const val MODEL_SIZE_TEXT = "~20 MB"

        private val shared = MutableStateFlow<InkModelStatus>(InkModelStatus.Unknown)
        private val downloadLock = Mutex()
        @Volatile private var model: DigitalInkRecognitionModel? = null

        /** Process-wide state of the handwriting model. */
        val modelStatus: StateFlow<InkModelStatus> = shared.asStateFlow()

        /** Looks on the device for the model; never downloads. */
        suspend fun checkModel(): InkModelStatus {
            val m = modelOrNull() ?: return shared.value
            if (shared.value == InkModelStatus.Ready || shared.value == InkModelStatus.Downloading) return shared.value
            val downloaded = runCatching { RemoteModelManager.getInstance().isModelDownloaded(m).await() }.getOrDefault(false)
            shared.value = if (downloaded == true) InkModelStatus.Ready else InkModelStatus.NotDownloaded
            return shared.value
        }

        /**
         * Preloads the model and reports progress: checking, downloading,
         * ready or a German error. With [wifiOnly] a metered connection is
         * refused with [InkModelStatus.Failed.canForce] set.
         */
        fun ensureModel(context: Context, wifiOnly: Boolean = true): Flow<InkModelStatus> = flow {
            val m = modelOrNull()
            if (m == null) { emit(shared.value); return@flow }
            emit(checkModel())
            if (shared.value != InkModelStatus.Ready) {
                emit(InkModelStatus.Downloading)
                download(context.applicationContext, m, wifiOnly)
                emit(shared.value)
            }
        }

        private suspend fun download(context: Context, m: DigitalInkRecognitionModel, wifiOnly: Boolean): Boolean = downloadLock.withLock {
            val manager = RemoteModelManager.getInstance()
            val there = runCatching { manager.isModelDownloaded(m).await() }.getOrDefault(false) == true
            if (there) { shared.value = InkModelStatus.Ready; return@withLock true }
            val net = network(context)
            if (net == Net.OFFLINE) {
                shared.value = InkModelStatus.Failed(
                    "Das Handschrift-Modell ist noch nicht geladen und das Tablet ist offline. " +
                        "Einmal mit dem WLAN verbinden und unter SHK-Werkzeuge › Schnellrechner „Handschrift-Modell laden“ tippen.",
                )
                return@withLock false
            }
            if (wifiOnly && net == Net.METERED) {
                shared.value = InkModelStatus.Failed("Nur mobiles Internet verbunden. Das Modell ($MODEL_SIZE_TEXT) besser im WLAN laden.", canForce = true)
                return@withLock false
            }
            shared.value = InkModelStatus.Downloading
            try {
                manager.download(m, DownloadConditions.Builder().build()).await()
                shared.value = InkModelStatus.Ready
                true
            } catch (e: Exception) {
                shared.value = InkModelStatus.Failed("Laden fehlgeschlagen. Internetverbindung prüfen und noch einmal versuchen.")
                false
            }
        }

        private enum class Net { OFFLINE, METERED, FREE }

        private fun network(context: Context): Net = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Net.OFFLINE
                cm.isActiveNetworkMetered -> Net.METERED
                else -> Net.FREE
            }
        } catch (e: SecurityException) {
            Net.FREE // Without permission to look, just try.
        }

        private fun modelOrNull(): DigitalInkRecognitionModel? {
            model?.let { return it }
            val id = listOf("de-DE", "de", "en-US").firstNotNullOfOrNull { tag ->
                runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag) }.getOrNull()
            }
            if (id == null) {
                shared.value = InkModelStatus.Failed("Kein Handschrift-Modell für Deutsch verfügbar")
                return null
            }
            return DigitalInkRecognitionModel.builder(id).build().also { model = it }
        }
    }
}

/** Play-services Task as a suspend call, without pulling in another library. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
