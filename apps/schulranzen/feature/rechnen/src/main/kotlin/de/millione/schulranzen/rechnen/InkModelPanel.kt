package de.millione.schulranzen.rechnen

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Status and preload button of the handwriting model. Shown in the SHK
 * calculator tab; the integrator can place it in settings or the notebook too.
 * [compact] hides the explanation once the model is ready.
 */
@Composable
fun InkModelPanel(modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by MathInk.modelStatus.collectAsState()
    LaunchedEffect(Unit) { MathInk.checkModel() }

    fun load(wifiOnly: Boolean) = scope.launch { MathInk.ensureModel(context, wifiOnly).collect() }

    val (lamp, title) = when (val s = status) {
        InkModelStatus.Ready -> c.signal to "Handschrift-Rechnen bereit"
        InkModelStatus.Downloading -> SubjectPalette.of(2) to "Handschrift-Modell wird geladen …"
        InkModelStatus.NotDownloaded -> c.inkFaint to "Handschrift-Modell noch nicht geladen"
        InkModelStatus.Unknown -> c.inkFaint to "Handschrift-Modell wird geprüft …"
        is InkModelStatus.Failed -> c.signal.copy(alpha = 0.6f) to s.message
    }
    if (compact && status == InkModelStatus.Ready) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Lamp(lamp, size = 8.dp)
            Spacer(Modifier.width(10.dp))
            Text(title, style = Ranzen.type.label, color = c.inkMuted)
        }
        return
    }
    Column(modifier.fillMaxWidth().recessed(16.dp).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lamp(lamp, size = 8.dp, glow = status == InkModelStatus.Ready || status == InkModelStatus.Downloading)
            Spacer(Modifier.width(10.dp))
            Text(title, style = Ranzen.type.label, color = c.ink, modifier = Modifier.weight(1f))
        }
        if (status != InkModelStatus.Ready) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Für „Ausrechnen“ mit dem Stift erkennt das Tablet Handschrift selbst. Das Modell (${MathInk.MODEL_SIZE_TEXT}) " +
                    "wird einmal geladen und funktioniert danach ohne Internet.",
                style = Ranzen.type.caption, color = c.inkMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (val s = status) {
                    InkModelStatus.Downloading -> Text("Bitte warten, das dauert je nach WLAN eine Minute.", style = Ranzen.type.caption, color = c.inkMuted)
                    is InkModelStatus.Failed -> {
                        SignalButton("Noch einmal versuchen", { load(true) }, icon = RechnenIcons.Download)
                        if (s.canForce) { Spacer(Modifier.width(8.dp)); QuietButton("Trotzdem laden", { load(false) }, color = c.signal) }
                    }
                    else -> SignalButton("Handschrift-Modell laden (${MathInk.MODEL_SIZE_TEXT}, WLAN)", { load(true) }, icon = RechnenIcons.Download)
                }
            }
        }
    }
}
