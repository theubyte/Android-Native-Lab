package de.millione.schulranzen.app.scan

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import de.millione.schulranzen.core.scan.ScanPlan
import java.io.File

/**
 * The ways a sheet comes in. It lives at the top of the activity and not in
 * the scan screen, so a result still arrives wherever the user landed after
 * the system killed the app behind the camera.
 */
class ScanController internal constructor(
    /** Google's scanner: edges, cropping, several pages, gallery. */
    val scan: () -> Unit,
    /** Plain camera app, for phones without Play services. */
    val photo: () -> Unit,
    val gallery: () -> Unit,
    val pdf: () -> Unit,
    private val startingState: () -> Boolean,
) {
    /** The scanner was asked to start and has not answered yet. */
    val starting: Boolean get() = startingState()
}

@Composable
fun rememberScanController(store: ScanStore): ScanController {
    val context = LocalContext.current
    var starting by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        starting = false
        if (r.resultCode == Activity.RESULT_OK) {
            val uris = runCatching { GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pages?.map { it.imageUri } }.getOrNull().orEmpty()
            if (uris.isEmpty()) store.say("Der Scanner hat keine Seiten geliefert") else store.ingest(uris)
        }
    }
    // The path must survive the process: the camera app in front is when phones free memory.
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraPath?.let { File(it) }
        cameraPath = null
        if (file != null) {
            if (ok && file.length() > 0) store.ingest(listOf(Uri.fromFile(file)), deleteAfter = file) else file.delete()
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { store.ingest(it) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) store.ingest(listOf(uri)) }

    return remember(store) {
        ScanController(
            scan = {
                val activity = context.findActivity()
                if (activity == null) store.say("Scanner nicht verfügbar") else if (!starting) {
                    starting = true
                    var answered = false
                    // Some devices without Play services never answer at all.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!answered && starting) { starting = false; store.say(UNAVAILABLE) }
                    }, 20_000)
                    runCatching {
                        client().getStartScanIntent(activity)
                            .addOnCompleteListener { answered = true }
                            .addOnSuccessListener { sender ->
                                runCatching { scanner.launch(IntentSenderRequest.Builder(sender).build()) }
                                    .onFailure { starting = false; store.say(UNAVAILABLE) }
                            }
                            .addOnFailureListener { starting = false; store.say(UNAVAILABLE) }
                            .addOnCanceledListener { starting = false }
                    }.onFailure { starting = false; store.say(UNAVAILABLE) }
                }
            },
            photo = {
                runCatching {
                    val dir = File(context.cacheDir, "camera").also { it.mkdirs() }
                    val file = File(dir, "scan-${System.currentTimeMillis()}.jpg")
                    cameraPath = file.path
                    camera.launch(FileProvider.getUriForFile(context, context.packageName + ".files", file))
                }.onFailure { cameraPath = null; store.say("Keine Kamera-App gefunden") }
            },
            gallery = {
                runCatching { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    .onFailure { store.say("Keine Galerie-App gefunden") }
            },
            pdf = {
                runCatching { document.launch(arrayOf("application/pdf")) }
                    .onFailure { store.say("Keine Datei-App gefunden") }
            },
            startingState = { starting },
        )
    }
}

private const val UNAVAILABLE = "Der Scanner ist hier gerade nicht verfügbar (Google-Play-Dienste). Foto, Galerie und PDF gehen weiter."

private fun client(): GmsDocumentScanner = GmsDocumentScanning.getClient(
    GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(30)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build(),
)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * The Uris another app shares with us in [intent], checked: only foreign
 * content, only pictures and PDFs. Empty for every other intent.
 */
fun sharedUris(context: Context, intent: Intent?): List<Uri> {
    val raw: List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
    val own = context.packageName + ".files"
    return raw.filter { uri ->
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        ScanPlan.acceptsShare(uri.scheme, uri.authority, own, type)
    }
}
