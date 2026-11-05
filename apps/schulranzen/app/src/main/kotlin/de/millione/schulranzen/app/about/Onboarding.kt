package de.millione.schulranzen.app.about

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.app.AppIcons
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.rememberIsPhone

/** Own small file, so this never collides with the app's Settings class. */
internal class FirstRun(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("first_run", Context.MODE_PRIVATE)
    var introSeen: Boolean
        get() = prefs.getBoolean("introSeen", false)
        set(v) { prefs.edit().putBoolean("introSeen", v).apply() }
}

private data class IntroPage(val icon: ImageVector, val title: String, val text: String)

private val BackupPage = IntroPage(
    AppIcons.Backup, "Sichern nicht vergessen",
    "Deine Sachen liegen nur auf diesem Gerät und in keiner Cloud. Richte unter „Sichern“ ein Backup ein, " +
        "dann sind sie auch bei einem Defekt oder Gerätewechsel nicht weg.",
)

private val TabletPages = listOf(
    IntroPage(RanzenIcons.Notebook, "Dein Ranzen auf dem Tablet", "Hefte, Bücher, Rechnen und Lernen an einem Ort. Kein Konto, keine Werbung."),
    IntroPage(RanzenIcons.Pen, "Schreib mit dem Stift", "Der Stift schreibt, die Finger blättern und zoomen. Mit der Seitentaste radierst du. Das Lasso wählt aus, verschiebt und rechnet."),
    IntroPage(RanzenIcons.Book, "Lies und lerne daneben", "Öffne ein PDF-Buch neben dem Heft. Aus Ausschnitten werden Heftseiten, Karteikarten und Prüfungsstoff."),
    BackupPage,
)

private val PhonePages = listOf(
    IntroPage(AppIcons.Study, "Dein Ranzen für unterwegs", "Unten findest du Lernen, Hefte, Bücher, Scannen und Sichern. Du startest bei Lernen, mit Stundenplan, Prüfungen und Karteikarten."),
    IntroPage(RanzenIcons.Book, "Hefte und Bücher lesen", "Lies deine Hefte und Bücher und markiere Stellen für die Prüfung. Geschrieben und gerechnet wird auf dem Tablet."),
    IntroPage(RanzenIcons.Camera, "Scan deine Blätter", "Unter „Scannen“ fotografierst du Arbeitsblätter und legst sie in ein Heft. Bilder und PDFs aus anderen Apps kannst du auch hierher teilen."),
    BackupPage,
)

/**
 * Everything a start may have to say, as one layer over the app, one thing at
 * a time: the short introduction, the offer to share a crash report, then
 * updates. Empty when there is nothing to say, and only its panels take touches.
 */
@Composable
fun StartupOverlays(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val firstRun = remember { FirstRun(context) }
    var introSeen by rememberSaveable { mutableStateOf(firstRun.introSeen) }
    var crashAnswered by remember { mutableStateOf(false) }
    if (!introSeen) {
        Introduction(modifier, onDone = { firstRun.introSeen = true; introSeen = true })
    } else if (!crashAnswered) {
        CrashOffer(modifier, onNothingOpen = { crashAnswered = true })
    } else {
        UpdateOffer(modifier)
    }
}

/** The introduction; phones and tablets get their own pages. Skippable on every page. */
@Composable
fun Introduction(modifier: Modifier = Modifier, onDone: () -> Unit) {
    val c = Ranzen.colors
    val pages = if (rememberIsPhone()) PhonePages else TabletPages
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index.coerceIn(pages.indices)]
    val last = index >= pages.lastIndex
    BackHandler { if (index > 0) index-- else onDone() }
    Box(
        modifier
            .fillMaxSize()
            .background(c.desk)
            // Nothing below may react while this lies on top.
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        PaperPanel(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pages.indices.forEach { i ->
                            Lamp(if (i == index) c.signal else c.line, size = 9.dp, glow = i == index)
                        }
                    }
                    if (!last) QuietButton("Überspringen", onDone)
                }
                Spacer(Modifier.height(20.dp))
                RanzenIcon(page.icon, tint = c.signal, size = 44.dp)
                Spacer(Modifier.height(16.dp))
                Text(page.title, style = Ranzen.type.title, color = c.ink, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(10.dp))
                Text(page.text, style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.heightIn(min = 88.dp))
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) QuietButton("Zurück", { index-- })
                    Spacer(Modifier.weight(1f))
                    SignalButton(if (last) "Los geht’s" else "Weiter", { if (last) onDone() else index++ })
                }
            }
        }
    }
}
