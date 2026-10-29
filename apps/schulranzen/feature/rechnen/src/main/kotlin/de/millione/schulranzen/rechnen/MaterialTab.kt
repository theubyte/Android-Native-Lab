package de.millione.schulranzen.rechnen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.millione.schulranzen.core.math.GermanNumber
import de.millione.schulranzen.core.shk.AufmassPosition
import de.millione.schulranzen.core.shk.InsulationCase
import de.millione.schulranzen.core.shk.MaterialGroup
import de.millione.schulranzen.core.shk.MaterialInput
import de.millione.schulranzen.core.shk.MaterialList
import de.millione.schulranzen.core.shk.MaterialPlanner
import de.millione.schulranzen.core.shk.MaterialSettings
import de.millione.schulranzen.core.shk.ShkCsv
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SubjectPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun MaterialGroup.accent(): Color = SubjectPalette.of(ordinal + 1)

/** Source keys: "a:<id>" for an Aufmaß project, "s:<id>" for a schema. */
internal fun aufmassSource(id: String) = "a:$id"
internal fun schemaSource(id: String) = "s:$id"

/**
 * Material list from any mix of Aufmaß projects and schemata, with the
 * allowances on the left and the generated list (grouped) on the right.
 */
@Composable
internal fun MaterialTab(
    wide: Boolean,
    detailOpen: Boolean,
    onOpenDetail: () -> Unit,
    sources: List<String>,
    onSources: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val files = remember { ShkFiles.get(context) }
    val projects = files.aufmass.items.collectAsState().value.orEmpty()
    val schemas = files.schemas.items.collectAsState().value.orEmpty()
    val settings by files.material.collectAsState()

    val input = sources.fold(MaterialInput()) { acc, key ->
        val id = key.substringAfter(':')
        acc + when {
            key.startsWith("a:") -> projects.firstOrNull { it.id == id }?.let { MaterialPlanner.fromAufmass(it) } ?: MaterialInput()
            else -> schemas.firstOrNull { it.id == id }?.let { MaterialPlanner.fromSchema(it) } ?: MaterialInput()
        }
    }
    val list = MaterialPlanner.plan(input, settings)
    val title = sources.mapNotNull { key ->
        projects.firstOrNull { aufmassSource(it.id) == key }?.name ?: schemas.firstOrNull { schemaSource(it.id) == key }?.name
    }.joinToString(", ").ifBlank { "Materialliste" }

    MasterDetail(
        wide, detailOpen,
        list = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp)) {
                Caption("Quellen", Modifier.padding(start = 10.dp, bottom = 6.dp))
                if (projects.isEmpty() && schemas.isEmpty()) {
                    Text("Lege zuerst ein Aufmaß oder ein Schema an.", style = Ranzen.type.body, color = Ranzen.colors.inkMuted, modifier = Modifier.padding(10.dp))
                }
                projects.forEach { p ->
                    CheckRow("Aufmaß: ${p.name}", aufmassSource(p.id) in sources, SubjectPalette.of(0)) {
                        onSources(if (aufmassSource(p.id) in sources) sources - aufmassSource(p.id) else sources + aufmassSource(p.id))
                    }
                }
                schemas.forEach { s ->
                    CheckRow("Schema: ${s.name}", schemaSource(s.id) in sources, SubjectPalette.of(4)) {
                        onSources(if (schemaSource(s.id) in sources) sources - schemaSource(s.id) else sources + schemaSource(s.id))
                    }
                }
                if (!wide && sources.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    de.millione.schulranzen.design.SignalButton("Liste ansehen", onOpenDetail, Modifier.padding(start = 10.dp))
                }
                Spacer(Modifier.height(22.dp))
                SettingsPanel(settings) { change -> files.updateMaterial(change) }
            }
        },
        detail = { MaterialResult(title, list, sources.isEmpty()) },
    )
}

@Composable
private fun CheckRow(text: String, checked: Boolean, accent: Color, onToggle: () -> Unit) {
    val c = Ranzen.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggle).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (checked) c.signal else c.raised), contentAlignment = Alignment.Center) {
            if (checked) RanzenIcon(RanzenIcons.Check, tint = c.onSignal, size = 16.dp)
        }
        Spacer(Modifier.width(12.dp))
        Lamp(accent, size = 7.dp, glow = false)
        Spacer(Modifier.width(8.dp))
        Text(text, style = Ranzen.type.label, color = c.ink, maxLines = 2)
    }
}

@Composable
private fun SettingsPanel(settings: MaterialSettings, onChange: ((MaterialSettings) -> MaterialSettings) -> Unit) {
    val c = Ranzen.colors
    // Typed text is kept while editing; only valid numbers are stored.
    val texts = remember { mutableStateMapOf<String, String>() }
    @Composable
    fun field(key: String, label: String, value: Double, unit: String, apply: (MaterialSettings, Double) -> MaterialSettings) {
        val text = texts[key] ?: GermanNumber.formatPlain(value)
        val parsed = AufmassPosition.evaluate(text)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Ranzen.type.label, color = c.ink, modifier = Modifier.weight(1f))
            NumberField(text, { t ->
                texts[key] = t
                AufmassPosition.evaluate(t)?.takeIf { it >= 0 }?.let { v -> onChange { apply(it, v) } }
            }, unit, Modifier.width(150.dp), error = text.isNotBlank() && (parsed == null || parsed < 0))
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Caption("Zuschläge")
        Spacer(Modifier.height(6.dp))
        field("pipe", "Verschnitt Rohr", settings.pipeWastePercent, "%") { s, v -> s.copy(pipeWastePercent = v) }
        field("fit", "Formteile", settings.fittingExtraPercent, "%") { s, v -> s.copy(fittingExtraPercent = v) }
        field("clamp", "Befestigung", settings.clampExtraPercent, "%") { s, v -> s.copy(clampExtraPercent = v) }
        field("ins", "Verschnitt Dämmung", settings.insulationWastePercent, "%") { s, v -> s.copy(insulationWastePercent = v) }
        field("piece", "Länge Dämmschlauch", settings.insulationPieceM, "m") { s, v -> if (v > 0) s.copy(insulationPieceM = v) else s }
        Spacer(Modifier.height(14.dp))
        Caption("Dämmung nach GEG")
        Spacer(Modifier.height(4.dp))
        InsulationCase.entries.forEach { case ->
            CheckRow(case.title, settings.insulationCase == case, SubjectPalette.of(2)) { onChange { it.copy(insulationCase = case) } }
        }
        Spacer(Modifier.height(10.dp))
        Text(de.millione.schulranzen.core.shk.ShkDesign.SOURCE, style = Ranzen.type.caption, color = c.inkMuted)
    }
}

@Composable
private fun MaterialResult(title: String, list: MaterialList, noSources: Boolean) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) { if (notice != null) { delay(3500); notice = null } }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            notice = if (ShkExport.save(context, uri) { it.write(ShkCsv.materials(title, list).toByteArray(Charsets.UTF_8)) }) "CSV gespeichert" else "Speichern fehlgeschlagen"
        }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            notice = if (ShkExport.save(context, uri) { materialPdf(it, title, list) }) "PDF gespeichert" else "Speichern fehlgeschlagen"
        }
    }
    if (noSources) { EmptyHint("Links ein Aufmaß oder ein Schema anhaken. Die Liste entsteht sofort."); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp)) {
        item {
            Caption("Materialliste", color = SubjectPalette.of(1))
            Spacer(Modifier.height(4.dp))
            Text(title, style = Ranzen.type.title, color = c.ink)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietButton("CSV exportieren", { csv.launch(ShkExport.fileName("Material $title", "csv")) }, color = c.ink)
                QuietButton("PDF exportieren", { pdf.launch(ShkExport.fileName("Material $title", "pdf")) }, color = c.ink)
                notice?.let { Lamp(c.signal, size = 7.dp); Spacer(Modifier.width(6.dp)); Text(it, style = Ranzen.type.label, color = c.ink) }
            }
            if (list.lines.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("Die Quellen enthalten noch nichts Zählbares. Im Schema Rohren eine Dimension geben, im Aufmaß Mengen eintragen.", style = Ranzen.type.body, color = c.inkMuted)
            }
        }
        list.byGroup().forEach { (group, lines) ->
            item(key = "g_${group.name}") {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Lamp(group.accent(), size = 8.dp)
                    Spacer(Modifier.width(10.dp))
                    Caption(group.title, color = group.accent())
                }
                Spacer(Modifier.height(4.dp))
            }
            items(lines.size, key = { "l_${group.name}_$it" }) { i ->
                val l = lines[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.text, style = Ranzen.type.body, color = c.ink)
                        if (l.note.isNotBlank()) Text(l.note, style = Ranzen.type.caption, color = c.inkMuted)
                    }
                    Text(GermanNumber.format(l.quantity, 2), style = Ranzen.type.monoLarge.copy(fontSize = 22.sp), color = c.ink)
                    Spacer(Modifier.width(8.dp))
                    Text(l.unit, style = Ranzen.type.mono, color = c.inkMuted, modifier = Modifier.width(130.dp))
                }
                Box(Modifier.fillMaxWidth().height(0.75.dp).background(c.line))
            }
        }
        item { Spacer(Modifier.height(60.dp)) }
    }
}

internal fun materialPdf(out: java.io.OutputStream, title: String, list: MaterialList) {
    val sections = list.byGroup().map { (g, lines) ->
        PdfSection(g.title, listOf("Material", "Menge", "Einheit", "Hinweis"), listOf(2.4f, 0.7f, 1.2f, 2.4f),
            lines.map { listOf(it.text, ShkCsv.num(it.quantity, 2), it.unit, it.note) }, rightAligned = setOf(1))
    }
    ShkExport.tablePdf(out, "Materialliste: $title", "Stand ${ShkExport.stamp()}", sections)
}
