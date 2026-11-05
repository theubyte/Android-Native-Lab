package de.millione.schulranzen.app.about

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.app.AppIcons
import de.millione.schulranzen.app.BuildConfig
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenColors
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.TactileKey
import de.millione.schulranzen.design.ThemeMode
import de.millione.schulranzen.design.engravedLine
import de.millione.schulranzen.design.rememberIsPhone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Icons of this screen, in the house style. */
private object AboutIcons {
    val Help = RanzenIcons.line("help") {
        moveTo(12f, 3.5f); arcToRelative(8.5f, 8.5f, 0f, true, true, 0f, 17f); arcToRelative(8.5f, 8.5f, 0f, true, true, 0f, -17f)
        moveTo(9.5f, 9.5f); arcToRelative(2.5f, 2.5f, 0f, true, true, 3.6f, 2.2f); curveTo(12.4f, 12.1f, 12f, 12.7f, 12f, 13.6f)
        moveTo(12f, 16.6f); lineTo(12f, 16.7f)
    }
    val Licenses = RanzenIcons.line("licenses") {
        moveTo(6f, 3.5f); lineTo(15f, 3.5f); lineTo(19f, 7.5f); lineTo(19f, 20.5f); lineTo(6f, 20.5f); close()
        moveTo(9f, 11f); lineTo(16f, 11f); moveTo(9f, 14f); lineTo(16f, 14f); moveTo(9f, 17f); lineTo(13f, 17f)
    }
    val Report = RanzenIcons.line("report") {
        moveTo(12f, 4f); lineTo(21f, 19.5f); lineTo(3f, 19.5f); close()
        moveTo(12f, 10f); lineTo(12f, 14.5f); moveTo(12f, 17f); lineTo(12f, 17.1f)
    }
    val Intro = RanzenIcons.line("intro") {
        moveTo(5f, 4.5f); lineTo(19f, 12f); lineTo(5f, 19.5f); close()
    }
}

/** Where the about screen is; a string so it survives rotation. */
private const val HOME = "home"
private const val HELP = "help"
private const val PRIVACY = "privacy"
private const val LICENSES = "licenses"
private const val NOTICES = "notices"
private const val REPORT = "report"
private const val INTRO = "intro"

/**
 * Settings and about: light/dark, version, help, privacy, licences, crash
 * reports. Takes the theme as parameters, so it needs nothing from the app's
 * Settings class.
 */
@Composable
fun AboutScreen(theme: ThemeMode, onTheme: (ThemeMode) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ranzen.colors
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(HOME) }
    var license by rememberSaveable { mutableStateOf<String?>(null) }
    val up: () -> Unit = {
        when {
            license != null -> license = null
            page == NOTICES -> page = LICENSES
            page != HOME -> page = HOME
            else -> onBack()
        }
    }
    BackHandler(onBack = up)
    if (page == INTRO) {
        Introduction(modifier, onDone = { page = HOME })
        return
    }
    val report = remember(page) { CrashCatcher.store(context).latest() }
    if (page == REPORT && report != null) {
        ReportViewer(report, modifier, onClose = { page = HOME }, onShare = { CrashCatcher.share(context, report) })
        return
    }
    Column(
        modifier
            .fillMaxSize()
            .background(c.desk)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TactileKey(up, contentDescription = "Zurück") { RanzenIcon(RanzenIcons.Back) }
                Spacer(Modifier.width(16.dp))
                val title = when {
                    license != null -> "Lizenztext"
                    page == HELP -> "Hilfe"
                    page == PRIVACY -> "Datenschutz"
                    page == LICENSES -> "Lizenzen"
                    page == NOTICES -> "Hinweise von Google"
                    else -> "Über Schulranzen"
                }
                Text(title, style = Ranzen.type.title, color = c.ink, maxLines = 1, modifier = Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val open = license
                when {
                    open != null -> TextAsset("legal/licenses/$open")
                    page == HELP -> HelpPage()
                    page == PRIVACY -> PrivacyPage()
                    page == LICENSES -> LicensesPage(onOpen = { license = it }, onNotices = { page = NOTICES })
                    page == NOTICES -> TextAsset("legal/third_party_notices.txt")
                    else -> HomePage(theme, onTheme, report, go = { page = it }, shareReport = { f -> CrashCatcher.share(context, f) })
                }
            }
        }
    }
}

@Composable
private fun HomePage(theme: ThemeMode, onTheme: (ThemeMode) -> Unit, report: File?, go: (String) -> Unit, shareReport: (File) -> Unit) {
    val c = Ranzen.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PaperPanel(Modifier.fillMaxWidth().padding(4.dp), corner = 18.dp) {
            Column(Modifier.padding(18.dp)) {
                Text("Darstellung", style = Ranzen.type.heading, color = c.ink)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChoice("System", theme == ThemeMode.SYSTEM, Modifier.weight(1f)) { onTheme(ThemeMode.SYSTEM) }
                    ThemeChoice("Hell", theme == ThemeMode.LIGHT, Modifier.weight(1f)) { onTheme(ThemeMode.LIGHT) }
                    ThemeChoice("Dunkel", theme == ThemeMode.DARK, Modifier.weight(1f)) { onTheme(ThemeMode.DARK) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        UpdatePanel()
        Spacer(Modifier.height(12.dp))
        PaperPanel(Modifier.fillMaxWidth().padding(4.dp), corner = 18.dp) {
            Column {
                NavRow(AboutIcons.Help, "Hilfe und häufige Fragen") { go(HELP) }
                Divider(c)
                NavRow(AboutIcons.Intro, "Einführung noch einmal ansehen") { go(INTRO) }
                Divider(c)
                NavRow(RanzenIcons.Lock, "Datenschutz") { go(PRIVACY) }
                Divider(c)
                NavRow(AboutIcons.Licenses, "Lizenzen") { go(LICENSES) }
                Divider(c)
                if (report != null) {
                    NavRow(AboutIcons.Report, "Letzten Absturzbericht ansehen") { go(REPORT) }
                    Divider(c)
                    NavRow(AppIcons.Backup, "Bericht teilen") { shareReport(report) }
                } else {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        RanzenIcon(AboutIcons.Report, tint = c.inkFaint)
                        Spacer(Modifier.width(14.dp))
                        Text("Bericht teilen: kein Absturz gespeichert", style = Ranzen.type.body, color = c.inkMuted)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PaperPanel(Modifier.fillMaxWidth().padding(4.dp), corner = 18.dp) {
            Column(Modifier.padding(18.dp)) {
                Text("Gut zu wissen", style = Ranzen.type.heading, color = c.ink)
                Spacer(Modifier.height(8.dp))
                Text(AboutTexts.SHK_NOTICE, style = Ranzen.type.body, color = c.inkMuted)
                Spacer(Modifier.height(8.dp))
                Text(AboutTexts.AI_NOTICE, style = Ranzen.type.body, color = c.inkMuted)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (BuildConfig.DEBUG) " · Debug" else "",
            style = Ranzen.type.mono, color = c.inkMuted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

/** The offer itself appears in the update layer (UpdateOffer), like after the automatic check. */
@Composable
private fun UpdatePanel() {
    val c = Ranzen.colors
    val context = LocalContext.current
    val manager = remember { UpdateManager.get(context) }
    val state by manager.state.collectAsState()
    var auto by remember { mutableStateOf(manager.autoCheck) }
    PaperPanel(Modifier.fillMaxWidth().padding(4.dp), corner = 18.dp) {
        Column(Modifier.padding(18.dp)) {
            Text("Updates", style = Ranzen.type.heading, color = c.ink)
            Spacer(Modifier.height(10.dp))
            SignalButton("Nach Updates suchen", { manager.checkNow() })
            val status = when (state) {
                UpdateState.Checking -> "Wird geprüft …"
                UpdateState.UpToDate -> "Du hast die neueste Version."
                else -> null
            }
            if (status != null) {
                Spacer(Modifier.height(10.dp))
                Text(status, style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Spacer(Modifier.height(14.dp))
            Text("Automatisch einmal am Tag prüfen", style = Ranzen.type.body, color = c.ink)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice("An", auto, Modifier.weight(1f)) { manager.autoCheck = true; auto = true }
                ThemeChoice("Aus", !auto, Modifier.weight(1f)) { manager.autoCheck = false; auto = false }
            }
        }
    }
}

@Composable
private fun Divider(c: RanzenColors) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(2.dp).engravedLine(c.line, c.bevelLight))
}

@Composable
private fun ThemeChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Ranzen.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) c.signal else c.raised)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Ranzen.type.label, color = if (selected) c.onSignal else c.ink, maxLines = 1)
    }
}

@Composable
private fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Ranzen.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RanzenIcon(icon, tint = c.inkMuted)
        Spacer(Modifier.width(14.dp))
        Text(label, style = Ranzen.type.body, color = c.ink)
    }
}

@Composable
private fun HelpPage() {
    val c = Ranzen.colors
    val faq = (if (rememberIsPhone()) AboutTexts.phoneFaq else AboutTexts.tabletFaq) + AboutTexts.commonFaq
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
        items(faq) { item ->
            PaperPanel(Modifier.fillMaxWidth(), corner = 16.dp) {
                Column(Modifier.padding(16.dp)) {
                    Text(item.question, style = Ranzen.type.heading, color = c.ink)
                    Spacer(Modifier.height(6.dp))
                    Text(item.answer, style = Ranzen.type.body, color = c.inkMuted)
                }
            }
        }
    }
}

private suspend fun readAsset(context: Context, path: String): String? = withContext(Dispatchers.IO) {
    runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
}

@Composable
private fun PrivacyPage() {
    val c = Ranzen.colors
    val context = LocalContext.current
    val blocks by produceState<List<Block>?>(null) {
        value = withContext(Dispatchers.Default) { readAsset(context, "legal/datenschutz.md")?.let(MiniMarkdown::parse) ?: emptyList() }
    }
    fun styled(spans: List<Span>): AnnotatedString = buildAnnotatedString {
        for (s in spans) when {
            // Open placeholders of the draft must stay impossible to miss.
            s.code && s.text.startsWith("[PLATZHALTER") -> withStyle(SpanStyle(color = c.signal, fontWeight = FontWeight.SemiBold)) { append(s.text) }
            s.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.ink)) { append(s.text) }
            else -> append(s.text)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
        items(blocks.orEmpty()) { b ->
            when (b) {
                is Block.Title -> Text(b.text, style = Ranzen.type.title, color = c.ink)
                is Block.Heading -> Text(b.text, style = Ranzen.type.heading, color = c.ink, modifier = Modifier.padding(top = 10.dp))
                is Block.Paragraph -> Text(styled(b.spans), style = Ranzen.type.body, color = c.inkMuted)
                is Block.Bullet -> Row {
                    Text("•", style = Ranzen.type.body, color = c.inkMuted)
                    Spacer(Modifier.width(8.dp))
                    Text(styled(b.spans), style = Ranzen.type.body, color = c.inkMuted)
                }
            }
        }
    }
}

@Serializable
internal data class LibraryEntry(
    val id: String,
    val title: String,
    val version: String = "",
    val license: String,
    val url: String? = null,
    val licenseUrl: String? = null,
)

/** Maps a declared licence to the full text shipped in assets/legal/licenses, or null for a link only. */
internal fun licenseFile(name: String): String? {
    val n = name.lowercase()
    return when {
        "apache" in n -> "apache-2.0.txt"
        "open font" in n -> "ofl-1.1.txt"
        "bouncy" in n -> "bouncycastle.txt"
        else -> null
    }
}

@Composable
private fun LicensesPage(onOpen: (String) -> Unit, onNotices: () -> Unit) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val libs by produceState<List<LibraryEntry>>(emptyList()) {
        value = withContext(Dispatchers.Default) {
            readAsset(context, "legal/libraries.json")?.let {
                runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<List<LibraryEntry>>(it) }.getOrNull()
            }.orEmpty()
        }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(4.dp)) {
        item {
            Text("Schulranzen baut auf diesen Bibliotheken und Schriften auf. Die Liste entsteht beim Bauen aus den echten Abhängigkeiten.", style = Ranzen.type.body, color = c.inkMuted)
            Spacer(Modifier.height(8.dp))
            QuietButton("Hinweise von Google zu ML Kit und Play-Diensten", onNotices, color = c.signal)
            Spacer(Modifier.height(8.dp))
        }
        items(libs, key = { it.id }) { lib ->
            val file = licenseFile(lib.license)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .then(if (file != null) Modifier.clickable(role = Role.Button) { onOpen(file) } else Modifier)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(lib.title + if (lib.version.isNotEmpty()) " ${lib.version}" else "", style = Ranzen.type.label, color = c.ink)
                Text(lib.license, style = Ranzen.type.caption, color = if (file != null) c.signal else c.inkMuted)
                Text(if (file == null && lib.licenseUrl != null) lib.licenseUrl else lib.id, style = Ranzen.type.caption, color = c.inkFaint)
            }
        }
    }
}

/** Long plain text, shown lazily: the notices run to thousands of lines. */
@Composable
private fun TextAsset(path: String) {
    val c = Ranzen.colors
    val context = LocalContext.current
    var lines by remember(path) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(path) {
        lines = readAsset(context, path)?.lines() ?: listOf("Der Text ließ sich nicht laden.")
    }
    val list = lines
    if (list == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Wird geladen …", style = Ranzen.type.body, color = c.inkMuted)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp)) {
            items(list) { line ->
                Text(line.ifEmpty { " " }, style = Ranzen.type.caption, color = c.inkMuted)
            }
        }
    }
}
