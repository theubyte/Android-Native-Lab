package de.millione.schulranzen.app.about

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.core.update.UpdateManifest
import java.util.Locale

private fun megabytes(bytes: Long): String = String.format(Locale.GERMANY, "%.1f MB", bytes / 1_000_000.0)

/**
 * The update panel at the bottom of the screen; empty unless there is
 * something to decide. Only the panel takes touches, the app stays usable
 * around it, a running download included.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpdateOffer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { UpdateManager.get(context) }
    val state by manager.state.collectAsState()
    LaunchedEffect(Unit) { manager.onAppStart() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) manager.onResume() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun start(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "Dieses Gerät kann das nicht öffnen.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val s = state
    when (s) {
        UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> return
        else -> {}
    }
    // Back answers the notice on top, not the screen under it.
    androidx.activity.compose.BackHandler {
        if (s is UpdateState.Downloading) manager.cancelDownload() else manager.dismiss()
    }
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        PaperPanel(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 460.dp)) {
            Column(Modifier.padding(20.dp)) {
                when (s) {
                    is UpdateState.Available -> {
                        Heading("Neue Version ${s.manifest.versionName}")
                        Changes(s.manifest, Modifier.weight(1f, fill = false))
                        Note("Deine Hefte und Lerndaten bleiben erhalten." + (s.apk.size?.let { " Download: ${megabytes(it)}." } ?: ""))
                        Buttons {
                            SignalButton("Laden", { manager.startDownload(s) })
                            QuietButton("Später", { manager.dismiss() })
                        }
                    }
                    is UpdateState.Downloading -> {
                        Heading("Version ${s.manifest.versionName} wird geladen")
                        ProgressBar(s.done, s.total)
                        Note(s.total?.let { "${megabytes(s.done)} von ${megabytes(it)}" } ?: megabytes(s.done))
                        Buttons { QuietButton("Abbrechen", { manager.cancelDownload() }) }
                    }
                    is UpdateState.NeedsPermission -> {
                        Heading("Einmal erlauben")
                        Note(
                            "Die Datei ist geladen und geprüft. Android fragt einmalig, ob Schulranzen Updates installieren darf. " +
                                "Öffne die Einstellung, schalte „Dieser Quelle vertrauen“ ein und komm mit „Zurück“ wieder hierher.",
                        )
                        Buttons {
                            SignalButton("Zur Einstellung", { start(manager.permissionIntent()) })
                            QuietButton("Verwerfen", { manager.discard() })
                        }
                    }
                    is UpdateState.Ready -> {
                        Heading("Version ${s.manifest.versionName} ist bereit")
                        Note("Die Datei ist geprüft. Android fragt gleich noch einmal nach. Deine Hefte und Lerndaten bleiben erhalten, die App startet danach neu.")
                        Buttons {
                            SignalButton("Installieren", { start(manager.installIntent(s.file)) })
                            QuietButton("Verwerfen", { manager.discard() })
                        }
                    }
                    is UpdateState.Failed -> {
                        Heading("Das Update hat nicht geklappt")
                        Note(s.message)
                        Buttons {
                            s.retry?.let { offer -> SignalButton("Fortsetzen", { manager.startDownload(offer) }) }
                            QuietButton("Schließen", { manager.dismiss() })
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = Ranzen.type.heading, color = Ranzen.colors.ink)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = Ranzen.type.body, color = Ranzen.colors.inkMuted)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Changes(manifest: UpdateManifest, modifier: Modifier = Modifier) {
    if (manifest.changes.isEmpty()) return
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(manifest.changes, style = Ranzen.type.body, color = Ranzen.colors.ink)
    }
    Spacer(Modifier.height(10.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** Own bar, as the design system has none; without a known total it stays empty rather than guessing. */
@Composable
private fun ProgressBar(done: Long, total: Long?) {
    val c = Ranzen.colors
    val shape = RoundedCornerShape(5.dp)
    val fraction = if (total != null && total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(10.dp).clip(shape).background(c.line)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(shape).background(c.signal))
    }
    Spacer(Modifier.height(8.dp))
}
