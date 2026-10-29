package de.millione.schulranzen.handwriting

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import kotlinx.coroutines.launch

/** Lamp colour per model state: sage ready, mustard loading, brick when it needs attention. */
internal fun lampColor(s: ModelState): Color = when (s) {
    ModelState.Ready -> SubjectPalette.colors[3]
    ModelState.Downloading -> SubjectPalette.colors[2]
    ModelState.Unknown, ModelState.Missing -> SubjectPalette.colors[7]
    ModelState.WaitingForWifi, ModelState.Offline -> SubjectPalette.colors[4]
    is ModelState.Failed -> SubjectPalette.colors[1]
}

/**
 * Status and download button for the handwriting model, for the settings
 * or the first use of "Handschrift → Text" / search. Checks the state when
 * shown; the button loads over Wi-Fi, a second one allows mobile data.
 */
@Composable
fun HandwritingModelCard(recognizer: HandwritingRecognizer, modifier: Modifier = Modifier) {
    val status by recognizer.status.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(recognizer) { recognizer.refreshStatus() }
    HandwritingModelCard(
        status,
        onDownload = { scope.launch { recognizer.download() } },
        onDownloadMobile = { scope.launch { recognizer.download(allowMobileData = true) } },
        modifier,
    )
}

/** Stateless variant of [HandwritingModelCard]. */
@Composable
fun HandwritingModelCard(
    status: ModelState,
    onDownload: () -> Unit,
    onDownloadMobile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ranzen.colors
    PaperPanel(modifier.fillMaxWidth(), corner = 18.dp) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pulse = if (status == ModelState.Downloading) {
                    val t = rememberInfiniteTransition(label = "pulse")
                    t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a").value
                } else 1f
                Lamp(lampColor(status), Modifier.alpha(pulse), size = 12.dp)
                Spacer(Modifier.width(12.dp))
                Text("Schrifterkennung", style = Ranzen.type.heading, color = c.ink)
            }
            Spacer(Modifier.height(8.dp))
            Text(status.message, style = Ranzen.type.body, color = c.inkMuted)
            if (status == ModelState.Ready) {
                Spacer(Modifier.height(4.dp))
                Text("Handschrift in Text umwandeln und in Heften suchen.", style = Ranzen.type.caption, color = c.inkFaint)
            }
            val canLoad = status == ModelState.Missing || status == ModelState.Offline || status is ModelState.Failed
            if (canLoad || status == ModelState.WaitingForWifi) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canLoad) SignalButton("Laden (ca. 20 MB, WLAN)", onDownload)
                    if (status == ModelState.WaitingForWifi) {
                        SignalButton("Erneut prüfen", onDownload)
                        Spacer(Modifier.width(8.dp))
                        QuietButton("Mit mobilen Daten laden", onDownloadMobile, color = c.ink)
                    }
                }
            }
        }
    }
}
