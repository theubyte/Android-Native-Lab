package de.millione.schulranzen.rechnen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.millione.schulranzen.core.math.GermanNumber
import de.millione.schulranzen.core.shk.AufmassMath
import de.millione.schulranzen.core.shk.AufmassPosition
import de.millione.schulranzen.core.shk.AufmassProject
import de.millione.schulranzen.core.shk.AufmassRoom
import de.millione.schulranzen.core.shk.PipeCatalog
import de.millione.schulranzen.core.shk.PipeMaterial
import de.millione.schulranzen.core.shk.PositionKind
import de.millione.schulranzen.core.shk.ShkCsv
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.design.TactileKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun PositionKind.accent(): Color = SubjectPalette.of(ordinal)

/** German quantity with unit, e.g. "8,7 m". */
internal fun qty(v: Double?, unit: String, decimals: Int = 2): String = if (v == null) "–" else "${GermanNumber.format(v, decimals)} $unit"

/**
 * Digital site measurement: projects with rooms and positions. Quantities
 * are typed as calculations ("2,5*3+1,2") and summed live; export as CSV or PDF.
 */
@Composable
internal fun AufmassTab(
    wide: Boolean,
    detailOpen: Boolean,
    onOpenDetail: () -> Unit,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onMaterials: (String) -> Unit,
) {
    val context = LocalContext.current
    val files = remember { ShkFiles.get(context) }
    val projects = files.aufmass.items.collectAsState().value
    val project = projects?.firstOrNull { it.id == selectedId } ?: projects?.firstOrNull().takeIf { wide }

    MasterDetail(
        wide, detailOpen,
        list = {
            ProjectList(
                projects, project?.id,
                onPick = { onSelect(it); onOpenDetail() },
                onNew = {
                    val now = System.currentTimeMillis()
                    val p = AufmassProject(
                        ShkFiles.newId(), "Neues Aufmaß", createdAt = now, updatedAt = now,
                        rooms = listOf(AufmassRoom(ShkFiles.newId(), "Raum 1")),
                    )
                    files.aufmass.put(p); onSelect(p.id); onOpenDetail()
                },
            )
        },
        detail = {
            if (project == null) EmptyHint("Noch kein Aufmaß. Lege links ein Projekt an.")
            else ProjectDetail(
                project,
                onChange = { files.aufmass.put(it.copy(updatedAt = System.currentTimeMillis())) },
                onDelete = { files.aufmass.delete(project.id); onSelect(null) },
                onMaterials = { onMaterials(project.id) },
            )
        },
    )
}

@Composable
internal fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Ranzen.type.body, color = Ranzen.colors.inkMuted, textAlign = TextAlign.Center)
    }
}

private val DAY = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

@Composable
private fun ProjectList(projects: List<AufmassProject>?, selectedId: String?, onPick: (String) -> Unit, onNew: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 26.dp, end = 16.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Caption("Projekte", Modifier.weight(1f))
            SignalButton("Neu", onNew, icon = RanzenIcons.Plus)
        }
        when {
            projects == null -> Unit
            projects.isEmpty() -> EmptyHint("Aufmaß wie auf der Baustelle: Räume anlegen, Längen als Rechnung eintippen, Summen erscheinen sofort.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                items(projects, key = { it.id }) { p ->
                    val n = p.rooms.sumOf { it.positions.size }
                    ListRow(
                        p.name.ifBlank { "Ohne Namen" }, "${p.rooms.size} Räume · $n Pos. · ${DAY.format(Date(p.updatedAt))}",
                        p.id == selectedId, SubjectPalette.of(0), onClick = { onPick(p.id) }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectDetail(
    project: AufmassProject,
    onChange: (AufmassProject) -> Unit,
    onDelete: () -> Unit,
    onMaterials: () -> Unit,
) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var roomId by rememberSaveable(project.id) { mutableStateOf(project.rooms.firstOrNull()?.id) }
    val room = project.rooms.firstOrNull { it.id == roomId } ?: project.rooms.firstOrNull()
    var pickFor by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) { if (notice != null) { delay(3500); notice = null } }

    fun updateRoom(id: String, change: (AufmassRoom) -> AufmassRoom) =
        onChange(project.copy(rooms = project.rooms.map { if (it.id == id) change(it) else it }))
    fun updatePos(posId: String, change: (AufmassPosition) -> AufmassPosition) = room?.let { r ->
        updateRoom(r.id) { it.copy(positions = it.positions.map { p -> if (p.id == posId) change(p) else p }) }
    }

    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val ok = ShkExport.save(context, uri) { it.write(ShkCsv.aufmass(project).toByteArray(Charsets.UTF_8)) }
            notice = if (ok) "CSV gespeichert" else "Speichern fehlgeschlagen"
        }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            val ok = ShkExport.save(context, uri) { aufmassPdf(it, project) }
            notice = if (ok) "PDF gespeichert" else "Speichern fehlgeschlagen"
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp)) {
            item(key = "head") {
                Caption("Aufmaß", color = SubjectPalette.of(0))
                Spacer(Modifier.height(6.dp))
                TextLine(project.name, { onChange(project.copy(name = it)) }, Modifier.fillMaxWidth(), "Projektname", Ranzen.type.heading)
                Spacer(Modifier.height(8.dp))
                TextLine(project.address, { onChange(project.copy(address = it)) }, Modifier.fillMaxWidth(), "Baustelle, Kunde (optional)")
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SignalButton("Materialliste", onMaterials, icon = RechnenIcons.Calculator)
                    QuietButton("CSV exportieren", { csv.launch(ShkExport.fileName("Aufmaß ${project.name}", "csv")) }, color = c.ink)
                    QuietButton("PDF exportieren", { pdf.launch(ShkExport.fileName("Aufmaß ${project.name}", "pdf")) }, color = c.ink)
                    ConfirmButton("Projekt löschen", "Wirklich löschen?", onDelete)
                }
                notice?.let {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) { Lamp(c.signal, size = 7.dp); Spacer(Modifier.width(8.dp)); Text(it, style = Ranzen.type.label, color = c.ink) }
                }
                Spacer(Modifier.height(18.dp))
                // Rooms as index tabs.
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    project.rooms.forEach { r -> FilterTab(r.name.ifBlank { "Raum" }, SubjectPalette.of(3), r.id == room?.id) { roomId = r.id } }
                    QuietButton("+ Raum", {
                        val r = AufmassRoom(ShkFiles.newId(), "Raum ${project.rooms.size + 1}")
                        onChange(project.copy(rooms = project.rooms + r)); roomId = r.id
                    }, color = c.signal)
                }
                Box(Modifier.fillMaxWidth().height(0.75.dp).background(c.line))
            }
            if (room != null) {
                item(key = "room_${room.id}") {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextLine(room.name, { n -> updateRoom(room.id) { it.copy(name = n) } }, Modifier.weight(1f), "Raumname")
                        Spacer(Modifier.width(8.dp))
                        if (project.rooms.size > 1) ConfirmButton("Raum löschen", "Wirklich?", {
                            onChange(project.copy(rooms = project.rooms.filter { it.id != room.id })); roomId = null
                        })
                    }
                    Spacer(Modifier.height(12.dp))
                    if (room.positions.isEmpty()) Text("Noch keine Positionen. Unten eine Art wählen.", style = Ranzen.type.body, color = c.inkMuted)
                }
                items(room.positions, key = { it.id }) { p ->
                    PositionRow(
                        p,
                        onChange = { next -> updatePos(p.id) { next } },
                        onPickPipe = { pickFor = p.id },
                        onDelete = { updateRoom(room.id) { r -> r.copy(positions = r.positions.filter { it.id != p.id }) } },
                    )
                }
                item(key = "add") {
                    Spacer(Modifier.height(12.dp))
                    Caption("Position hinzufügen")
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PositionKind.entries.forEach { k ->
                            AddKindKey(k) {
                                val last = room.positions.lastOrNull { it.kind == k }
                                val pos = AufmassPosition(ShkFiles.newId(), k, pipeId = last?.pipeId ?: if (k == PositionKind.PIPE) "cu22" else null, insulate = last?.insulate ?: false)
                                updateRoom(room.id) { it.copy(positions = it.positions + pos) }
                            }
                        }
                    }
                }
                item(key = "sums") {
                    Spacer(Modifier.height(24.dp))
                    SumsPanel("Summe ${room.name}", AufmassMath.sums(room.positions))
                    Spacer(Modifier.height(14.dp))
                    SumsPanel("Summe Projekt", AufmassMath.sums(project))
                    val bad = AufmassMath.invalid(project)
                    if (bad.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("${bad.size} Rechnung(en) nicht lesbar – sie zählen als 0.", style = Ranzen.type.label, color = c.signal)
                    }
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
        val pickId = pickFor
        if (pickId != null) {
            val current = room?.positions?.firstOrNull { it.id == pickId }
            PipePicker(current?.pipeId, current?.kind == PositionKind.INSULATION, onDismiss = { pickFor = null }) { id ->
                updatePos(pickId) { it.copy(pipeId = id) }; pickFor = null
            }
        }
    }
}

@Composable
private fun AddKindKey(kind: PositionKind, onClick: () -> Unit) {
    val c = Ranzen.colors
    Row(
        Modifier.defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(12.dp)).background(c.raised)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lamp(kind.accent(), size = 7.dp, glow = false)
        Spacer(Modifier.width(8.dp))
        Text("+ ${kind.title}", style = Ranzen.type.label, color = c.ink)
    }
}

@Composable
private fun PositionRow(p: AufmassPosition, onChange: (AufmassPosition) -> Unit, onPickPipe: () -> Unit, onDelete: () -> Unit) {
    val c = Ranzen.colors
    val q = p.quantity
    val bad = p.expression.isNotBlank() && q == null
    val needsPipe = p.kind == PositionKind.PIPE || p.kind == PositionKind.INSULATION
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).recessed(16.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lamp(p.kind.accent(), size = 8.dp, glow = false)
            Spacer(Modifier.width(8.dp))
            Caption(p.kind.title, Modifier.weight(1f), color = p.kind.accent())
            if (p.kind == PositionKind.PIPE) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { onChange(p.copy(insulate = !p.insulate)) }.defaultMinSize(minHeight = 44.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (p.insulate) c.signal else c.raised), contentAlignment = Alignment.Center) {
                        if (p.insulate) RanzenIcon(RanzenIcons.Check, tint = c.onSignal, size = 16.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("dämmen", style = Ranzen.type.label, color = c.ink)
                }
            }
            TactileKey(onClick = onDelete, size = 44.dp, corner = 12.dp, contentDescription = "Position löschen") {
                RanzenIcon(RanzenIcons.Trash, tint = c.inkMuted, size = 18.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (needsPipe) {
                Row(
                    Modifier.widthIn(min = 150.dp).defaultMinSize(minHeight = 52.dp).clip(RoundedCornerShape(12.dp)).background(c.raised)
                        .clickable(onClick = onPickPipe).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(PipeCatalog.byId(p.pipeId)?.fullLabel ?: "Rohr wählen", style = Ranzen.type.mono.copy(fontSize = 15.sp), color = if (p.pipeId == null) c.signal else c.ink)
                }
            }
            TextLine(p.text, { onChange(p.copy(text = it)) }, Modifier.weight(1f), if (needsPipe) "Bemerkung (optional)" else "Bezeichnung, z. B. Bogen 90°")
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberField(
                p.expression, { onChange(p.copy(expression = it)) }, p.unit, Modifier.weight(1f),
                placeholder = "z. B. 2,5*3+1,2", error = bad, keyboard = KeyboardType.Text,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (bad) "nicht lesbar" else "= ${qty(q, p.unit)}",
                style = if (bad) Ranzen.type.label else Ranzen.type.monoLarge.copy(fontSize = 22.sp),
                color = if (bad) c.signal else c.ink,
                modifier = Modifier.widthIn(min = 150.dp), textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
internal fun SumsPanel(title: String, sums: List<de.millione.schulranzen.core.shk.AufmassSum>) {
    val c = Ranzen.colors
    Column(Modifier.fillMaxWidth().recessed(18.dp).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Caption(title)
        Spacer(Modifier.height(6.dp))
        if (sums.isEmpty()) Text("–", style = Ranzen.type.body, color = c.inkMuted)
        sums.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Lamp(s.kind.accent(), size = 6.dp, glow = false)
                Spacer(Modifier.width(10.dp))
                Text(s.title, style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
                Text(qty(s.quantity, s.unit), style = Ranzen.type.mono.copy(fontSize = 17.sp), color = c.ink)
            }
        }
    }
}

/** A delete-style action that asks once: the first tap arms it, the second within 3 s confirms. */
@Composable
internal fun ConfirmButton(text: String, confirmText: String, onConfirm: () -> Unit) {
    val c = Ranzen.colors
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
    QuietButton(if (armed) confirmText else text, { if (armed) { armed = false; onConfirm() } else armed = true }, color = if (armed) c.signal else c.inkMuted)
}

/** Picks a pipe dimension: material tabs on top, sizes below. */
@Composable
internal fun PipePicker(selected: String?, pressureOnly: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val materials = PipeMaterial.entries.filter { !pressureOnly || it.pressurePipe }
    var material by remember { mutableStateOf(PipeCatalog.byId(selected)?.material ?: PipeMaterial.COPPER) }
    SidePanel("Rohr wählen", onDismiss) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            materials.forEach { m -> FilterTab(m.title, SubjectPalette.of(m.ordinal), m == material) { material = m } }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(PipeCatalog.of(material), key = { it.id }) { size ->
                ListRow(
                    size.label, "dᵢ ${GermanNumber.format(size.innerMm, 1)} mm · ${GermanNumber.format(size.litresPerMeter, 3)} l/m",
                    size.id == selected, SubjectPalette.of(material.ordinal), onClick = { onPick(size.id) }, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** PDF report of the Aufmaß: one table per room plus the project sums. */
internal fun aufmassPdf(out: java.io.OutputStream, project: AufmassProject) {
    val sections = project.rooms.map { r ->
        PdfSection(
            r.name, listOf("Art", "Bezeichnung", "Rechnung", "Menge", "Einheit"), listOf(1.1f, 2.4f, 1.9f, 0.9f, 0.6f),
            r.positions.map { p -> listOf(p.kind.title, p.title + if (p.insulate) " (gedämmt)" else "", p.expression, ShkCsv.num(p.quantity, 2), p.unit) },
            rightAligned = setOf(3),
        )
    } + PdfSection(
        "Summen", listOf("Art", "Bezeichnung", "Menge", "Einheit"), listOf(1.1f, 3.6f, 1f, 0.6f),
        AufmassMath.sums(project).map { listOf(it.kind.title, it.title, ShkCsv.num(it.quantity, 2), it.unit) }, rightAligned = setOf(2),
    )
    ShkExport.tablePdf(out, "Aufmaß: ${project.name}", listOf(project.address, "Stand ${ShkExport.stamp()}").filter { it.isNotBlank() }.joinToString(" · "), sections)
}
