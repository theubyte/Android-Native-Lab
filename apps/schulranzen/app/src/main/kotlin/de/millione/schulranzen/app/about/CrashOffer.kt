package de.millione.schulranzen.app.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SignalButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Asks once per crash, at the next start. Declining keeps the report under "Über". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CrashOffer(modifier: Modifier = Modifier, onNothingOpen: () -> Unit) {
    val context = LocalContext.current
    val c = Ranzen.colors
    var report by remember { mutableStateOf<File?>(null) }
    var viewing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            runCatching { CrashCatcher.collectSystemExits(context) }
            CrashCatcher.store(context).open()
        }
        if (report == null) onNothingOpen()
    }
    val file = report ?: return
    fun answered() {
        CrashCatcher.store(context).markAllSeen()
        report = null
        onNothingOpen()
    }
    if (viewing) {
        ReportViewer(file, modifier, onClose = { viewing = false }, onShare = {
            CrashCatcher.share(context, file)
            viewing = false
            answered()
        })
        return
    }
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        PaperPanel(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Die App ist abgestürzt", style = Ranzen.type.heading, color = c.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Das tut uns leid. Du kannst den Bericht teilen, damit der Fehler behoben werden kann. Er enthält nur technische Angaben, und gesendet wird nichts von selbst.",
                    style = Ranzen.type.body, color = c.inkMuted,
                )
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SignalButton("Bericht teilen", { CrashCatcher.share(context, file); answered() })
                    QuietButton("Ansehen", { viewing = true })
                    QuietButton("Nicht teilen", { answered() })
                }
            }
        }
    }
}

/** Shows exactly what would be shared. */
@Composable
internal fun ReportViewer(file: File, modifier: Modifier = Modifier, onClose: () -> Unit, onShare: () -> Unit) {
    val c = Ranzen.colors
    var text by remember(file) { mutableStateOf("") }
    LaunchedEffect(file) { text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrDefault("Der Bericht ließ sich nicht lesen.") } }
    BackHandler(onBack = onClose)
    Box(
        modifier
            .fillMaxSize()
            .background(c.desk)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        PaperPanel(Modifier.widthIn(max = 900.dp).fillMaxWidth().fillMaxHeight()) {
            Column(Modifier.padding(20.dp)) {
                Text("Absturzbericht", style = Ranzen.type.title, color = c.ink)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    SelectionContainer { Text(text, style = Ranzen.type.mono, color = c.ink) }
                }
                Spacer(Modifier.height(12.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                    SignalButton("Bericht teilen", onShare)
                    QuietButton("Schließen", onClose)
                }
            }
        }
    }
}
