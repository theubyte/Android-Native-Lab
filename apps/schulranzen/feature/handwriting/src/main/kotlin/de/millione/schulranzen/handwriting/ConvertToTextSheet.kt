package de.millione.schulranzen.handwriting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.TactileKey

/**
 * Panel for "Handschrift → Text": shows what was read, editable, so the
 * pupil can fix a letter before replacing the ink. [recognized] null means
 * recognition is still running. "Kopieren" puts the text on the clipboard
 * itself and then calls [onCopy] (e.g. to show a short note).
 */
@Composable
fun ConvertToTextSheet(
    recognized: String?,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    status: ModelState? = null,
) {
    val c = Ranzen.colors
    val context = LocalContext.current
    var text by rememberSaveable(recognized) { mutableStateOf(recognized.orEmpty()) }
    PaperPanel(modifier.widthIn(max = 620.dp)) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RanzenIcon(RanzenIcons.Text, size = 22.dp, tint = c.signal)
                Spacer(Modifier.width(12.dp))
                Text("Handschrift als Text", style = Ranzen.type.title, color = c.ink, modifier = Modifier.weight(1f))
                TactileKey(onCancel, size = 48.dp, contentDescription = "Schließen") { RanzenIcon(RanzenIcons.Close, size = 20.dp) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    recognized == null -> "Wird gelesen …"
                    recognized.isBlank() -> "Nichts erkannt. Deutlicher schreiben oder nur Text auswählen."
                    else -> "Prüfen und bei Bedarf verbessern."
                },
                style = Ranzen.type.body, color = c.inkMuted,
            )
            if (recognized == null && status != null && status != ModelState.Ready) {
                Spacer(Modifier.height(10.dp))
                ModelStatusLine(status)
            }
            Spacer(Modifier.height(16.dp))
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 360.dp)
                    .clip(shape).background(c.raised).border(1.dp, c.line, shape).padding(16.dp),
            ) {
                if (recognized == null) {
                    Text("…", style = Ranzen.type.body, color = c.inkFaint, textAlign = TextAlign.Start)
                } else {
                    BasicTextField(
                        text, { text = it },
                        textStyle = Ranzen.type.body.copy(color = c.ink),
                        cursorBrush = SolidColor(c.signal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                QuietButton("Abbrechen", onCancel)
                Spacer(Modifier.width(8.dp))
                if (text.isNotBlank()) {
                    QuietButton("Kopieren", { copy(context, text); onCopy(text) }, color = c.ink)
                    Spacer(Modifier.width(12.dp))
                    SignalButton("Als Text einfügen", { onInsert(text.trim()) }, icon = RanzenIcons.Check)
                }
            }
        }
    }
}

/** One line with a lamp and the model state message, for panels and settings. */
@Composable
fun ModelStatusLine(status: ModelState, modifier: Modifier = Modifier) {
    val c = Ranzen.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Lamp(lampColor(status), size = 9.dp, glow = status == ModelState.Downloading || status == ModelState.Ready)
        Spacer(Modifier.width(10.dp))
        Text(status.message, style = Ranzen.type.label, color = c.inkMuted)
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Handschrift", text))
}
