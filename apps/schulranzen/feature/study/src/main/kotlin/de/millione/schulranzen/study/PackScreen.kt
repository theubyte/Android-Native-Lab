package de.millione.schulranzen.study

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.ContentPack
import de.millione.schulranzen.core.study.ContentPacks
import de.millione.schulranzen.core.study.PackImportPlan
import de.millione.schulranzen.core.study.PackParse
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.study.data.Card
import de.millione.schulranzen.study.data.ContentPackRow
import de.millione.schulranzen.study.ui.EmptyNote
import de.millione.schulranzen.study.ui.RetroSwitch
import de.millione.schulranzen.study.ui.ScreenHeader
import de.millione.schulranzen.study.ui.SectionHead
import de.millione.schulranzen.study.ui.StudyIcons
import de.millione.schulranzen.study.ui.StudyPage
import de.millione.schulranzen.study.ui.panelPad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Packs carry pictures, so they may be larger than a plain card file. */
private const val MAX_PACK_BYTES = 64L * 1024 * 1024

/** Where a card comes from and whether anyone compared it with that source. Own cards show nothing. */
@Composable
internal fun CardProvenance(card: Card, showSource: Boolean = true, modifier: Modifier = Modifier) {
    val c = Ranzen.colors
    val source = card.source.line.takeIf { showSource }
    if (card.packId == null && source == null) return
    Row(modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (card.packId != null) {
            Lamp(if (card.verified) SubjectPalette.of(3) else c.signal, size = 7.dp, glow = false)
            Spacer(Modifier.width(6.dp))
            Text(if (card.verified) "GEPRÜFT" else "UNGEPRÜFT", style = Ranzen.type.caption, color = if (card.verified) c.inkMuted else c.signal)
            if (source != null) Spacer(Modifier.width(10.dp))
        }
        if (source != null) Text(source, style = Ranzen.type.caption, color = c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

/**
 * Content packs: pick a file, see exactly what it would do, then take it over. A broken
 * file changes nothing and says why. An update keeps the learning state of every card.
 */
@Composable
internal fun PacksPage(repo: StudyRepository, onBack: () -> Unit) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by repo.contentPacks.collectAsState(initial = emptyList())
    var parsed by remember { mutableStateOf<PackParse?>(null) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        parsed = null
        errors = emptyList()
        message = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.Default) { ContentPacks.parse(readText(context, uri, MAX_PACK_BYTES)) }
                if (r.pack == null) errors = r.errors else parsed = r
            } catch (_: IOException) {
                errors = listOf("Die Datei ließ sich nicht lesen oder ist größer als 64 MB.")
            } catch (_: OutOfMemoryError) {
                errors = listOf("Die Datei ist für dieses Gerät zu groß.")
            } finally { busy = false }
        }
    }

    StudyPage(header = { ScreenHeader("Inhaltspakete", "Karten aus Dateien", onBack) }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val p = parsed?.pack
            if (p != null) {
                PackPreview(repo, p, parsed?.warnings.orEmpty(), installed.firstOrNull { it.id == p.id }, onCancel = { parsed = null }) { result ->
                    parsed = null
                    message = result
                }
            } else {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(panelPad)) {
                        Text("Ein Inhaltspaket bringt fertige Stapel mit Quelle und Prüfstatus. Vor dem Übernehmen siehst du genau, was sich ändert. Dein Lernstand bleibt beim Aktualisieren erhalten.", style = Ranzen.type.body, color = c.inkMuted)
                        Spacer(Modifier.height(14.dp))
                        SignalButton(if (busy) "Liest …" else "Datei auswählen", {
                            if (!busy) open.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                        }, icon = StudyIcons.Transfer)
                        if (errors.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text("Diese Datei lässt sich nicht übernehmen", style = Ranzen.type.heading, color = c.signal)
                            errors.take(6).forEach { Text("• $it", style = Ranzen.type.body, color = c.ink, modifier = Modifier.padding(top = 4.dp)) }
                            if (errors.size > 6) Text("und ${errors.size - 6} weitere Fehler.", style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(top = 4.dp))
                            Text("Es wurde nichts geändert.", style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.padding(top = 8.dp))
                        }
                        message?.let { Text(it, style = Ranzen.type.body, color = c.ink, modifier = Modifier.padding(top = 14.dp)) }
                    }
                }
            }
            PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                Column(Modifier.padding(horizontal = panelPad, vertical = 18.dp)) {
                    SectionHead("Vorhanden", installed.size)
                    if (installed.isEmpty()) EmptyNote("Noch kein Paket übernommen.")
                    installed.forEach { row ->
                        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp)) {
                            Text(row.titel, style = Ranzen.type.label, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf("Version ${row.version}", row.stand, row.autor).filter { it.isNotBlank() }.joinToString(" · "),
                                style = Ranzen.type.caption, color = c.inkMuted,
                            )
                            if (row.beispiel) Text("Beispielpaket ohne echten Lernstoff", style = Ranzen.type.caption, color = c.inkMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackPreview(repo: StudyRepository, pack: ContentPack, warnings: List<String>, old: ContentPackRow?, onCancel: () -> Unit, onDone: (String) -> Unit) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var skipUnverified by remember { mutableStateOf(false) }
    var removeMissing by remember { mutableStateOf(false) }
    var stored by remember(pack.id) { mutableStateOf<StoredPack?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(pack.id) { stored = repo.storedPack(pack.id) }
    val s = stored ?: return
    val plan: PackImportPlan = remember(s, skipUnverified, removeMissing) { ContentPacks.plan(pack, s.decks, s.cards, removeMissing, skipUnverified) }
    val sum = plan.summary

    PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
        Column(Modifier.padding(panelPad)) {
            Text(pack.titel, style = Ranzen.type.title, color = c.ink)
            Text(
                listOf("Version ${pack.version}", pack.stand, pack.autor).filter { it.isNotBlank() }.joinToString(" · "),
                style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(top = 4.dp),
            )
            if (pack.beschreibung.isNotBlank()) Text(pack.beschreibung, style = Ranzen.type.body, color = c.ink, modifier = Modifier.padding(top = 8.dp))
            if (pack.lizenz.isNotBlank()) Text("Lizenz: ${pack.lizenz}", style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(top = 4.dp))
            if (pack.beispiel) Text("Beispielpaket ohne echten Lernstoff.", style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.padding(top = 4.dp))
            if (old != null && sum.isUpdate) {
                val cmp = ContentPacks.compareVersions(pack.version, old.version)
                Text(
                    when {
                        cmp > 0 -> "Aktualisiert Version ${old.version}."
                        cmp == 0 -> "Diese Version ist schon vorhanden."
                        else -> "Achtung: Diese Datei ist älter als deine Version ${old.version}."
                    },
                    style = Ranzen.type.body, color = if (cmp < 0) c.signal else c.ink, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            PackLine("Stapel", pack.stapel.size, if (sum.newDecks > 0) "${sum.newDecks} neu" else null)
            PackLine("Karten in der Datei", sum.totalCards)
            PackLine("Neu", sum.newCards)
            if (sum.isUpdate) {
                PackLine("Geändert, Lernstand bleibt", sum.changedCards)
                PackLine("Unverändert", sum.unchangedCards)
                if (sum.keptOwnEdits > 0) PackLine("Deine eigene Fassung bleibt", sum.keptOwnEdits)
                if (sum.removedCards > 0) PackLine("Nicht mehr im Paket", sum.removedCards, if (sum.removedWithProgress > 0) "${sum.removedWithProgress} schon gelernt" else null)
            }
            PackLine("Ungeprüft", sum.unverifiedCards, if (sum.skippedUnverified > 0) "${sum.skippedUnverified} ausgelassen" else null)
            warnings.forEach { Text(it, style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(8.dp))
            if (sum.unverifiedCards > 0) RetroSwitch("Ungeprüfte auslassen", skipUnverified, { skipUnverified = it }, hint = "Neue Karten, die niemand mit der Quelle verglichen hat.")
            if (sum.removedCards > 0) {
                RetroSwitch(
                    "Entfallene Karten löschen", removeMissing, { removeMissing = it },
                    hint = if (removeMissing && sum.removedWithProgress > 0) "Auch ihr Lernstand geht verloren." else "Sonst bleiben sie als deine eigenen Karten.",
                )
            }
            error?.let { Text(it, style = Ranzen.type.body, color = c.signal, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                QuietButton("Abbrechen", onCancel)
                Spacer(Modifier.weight(1f))
                SignalButton(if (saving) "Übernimmt …" else if (sum.isUpdate) "Aktualisieren" else "Übernehmen", {
                    if (saving) return@SignalButton
                    saving = true
                    error = null
                    scope.launch {
                        runCatching { repo.applyPack(context.applicationContext, pack, plan) }
                            .onSuccess {
                                onDone("„${pack.titel}“ übernommen: ${sum.newCards} neue, ${sum.changedCards} geänderte Karten.")
                            }
                            .onFailure {
                                saving = false
                                error = "Das Paket ließ sich nicht übernehmen. Es wurde nichts geändert."
                            }
                    }
                })
            }
        }
    }
}

@Composable
private fun PackLine(label: String, value: Int, note: String? = null) {
    val c = Ranzen.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
        if (note != null) Text(note, style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(end = 10.dp))
        Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterEnd) { Text("$value", style = Ranzen.type.mono, color = c.ink) }
    }
}
