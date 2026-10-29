package de.millione.schulranzen.rechnen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.millione.schulranzen.core.math.GermanNumber
import de.millione.schulranzen.core.shk.PipeCatalog
import de.millione.schulranzen.core.shk.PipeMaterial
import de.millione.schulranzen.core.shk.Shk
import de.millione.schulranzen.core.shk.ShkDesign
import de.millione.schulranzen.core.shk.ShkTable
import de.millione.schulranzen.core.shk.ShkTables
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette

internal const val R_TABLE = "r_table"

/** Reference tables plus a live pressure-drop table; each links to its calculator. */
@Composable
internal fun TablesTab(
    wide: Boolean,
    detailOpen: Boolean,
    selectedId: String,
    onSelect: (String) -> Unit,
    onCalculate: (String) -> Unit,
) {
    MasterDetail(
        wide, detailOpen,
        list = {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                item { Caption("Auslegung", Modifier.padding(start = 14.dp, top = 14.dp, bottom = 6.dp)) }
                item {
                    ListRow("Druckverlust R je Rohr", "Φ, ΔT → v, R für alle Größen", selectedId == R_TABLE, SubjectPalette.of(0), onClick = { onSelect(R_TABLE) }, modifier = Modifier.fillMaxWidth())
                }
                item { Caption("Tabellen", Modifier.padding(start = 14.dp, top = 14.dp, bottom = 6.dp)) }
                items(ShkTables.all, key = { it.id }) { t ->
                    ListRow(t.title, t.columns.joinToString(" · "), t.id == selectedId, SubjectPalette.of(t.id.hashCode()), onClick = { onSelect(t.id) }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        detail = {
            if (selectedId == R_TABLE) PressureDropTable(onCalculate)
            else TableDetail(ShkTables.byId(selectedId) ?: ShkTables.all.first(), onCalculate)
        },
    )
}

@Composable
private fun TableDetail(table: ShkTable, onCalculate: (String) -> Unit) {
    val c = Ranzen.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp)) {
        Caption("Tabelle", color = SubjectPalette.of(table.id.hashCode()))
        Spacer(Modifier.height(4.dp))
        Text(table.title, style = Ranzen.type.title, color = c.ink)
        Spacer(Modifier.height(18.dp))
        Grid(table.columns, table.rows, highlight = -1)
        Spacer(Modifier.height(16.dp))
        Text(table.note, style = Ranzen.type.body, color = c.inkMuted)
        table.calculatorId?.let { id ->
            Spacer(Modifier.height(20.dp))
            SignalButton("Im Schnellrechner rechnen", { onCalculate(id) }, icon = RechnenIcons.Calculator)
        }
    }
}

/** A plain table on paper: caption row, hairlines, mono numbers; [highlight] marks one row. */
@Composable
internal fun Grid(columns: List<String>, rows: List<List<String>>, highlight: Int) {
    val c = Ranzen.colors
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).recessed(16.dp).padding(vertical = 6.dp)) {
        val widths = columns.indices.map { i ->
            val longest = (rows.map { it.getOrElse(i) { "" } } + columns[i]).maxOf { it.length }
            (longest * 9 + 36).coerceIn(90, 340).dp
        }
        val rowWidth = widths.fold(24.dp) { a, b -> a + b }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            columns.forEachIndexed { i, h -> Caption(h, Modifier.width(widths[i])) }
        }
        rows.forEachIndexed { r, row ->
            val hi = r == highlight
            Row(
                Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(10.dp)).background(if (hi) c.raised else c.raised.copy(alpha = 0f))
                    .defaultMinSize(minHeight = 44.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEachIndexed { i, cell ->
                    Row(Modifier.width(widths[i]), verticalAlignment = Alignment.CenterVertically) {
                        if (i == 0 && hi) { Lamp(c.signal, size = 7.dp); Spacer(Modifier.width(8.dp)) }
                        Text(
                            cell,
                            style = if (i == 0) Ranzen.type.body else Ranzen.type.mono.copy(fontSize = 16.sp),
                            color = if (hi) c.ink else c.ink.copy(alpha = 0.88f),
                        )
                    }
                }
            }
            if (r < rows.size - 1) Box(Modifier.padding(horizontal = 12.dp).width(rowWidth).height(0.75.dp).background(c.line.copy(alpha = 0.7f)))
        }
    }
}

@Composable
private fun PressureDropTable(onCalculate: (String) -> Unit) {
    val c = Ranzen.colors
    var power by rememberSaveable { mutableStateOf("10") }
    var spread by rememberSaveable { mutableStateOf("20") }
    var materialName by rememberSaveable { mutableStateOf(PipeMaterial.COPPER.name) }
    val material = PipeMaterial.valueOf(materialName)
    val p = de.millione.schulranzen.core.shk.AufmassPosition.evaluate(power)?.takeIf { it > 0 }
    val dt = de.millione.schulranzen.core.shk.AufmassPosition.evaluate(spread)?.takeIf { it > 0 }
    val flow = if (p != null && dt != null) Shk.flowFromPower(p, dt) else null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp)) {
        Caption("Rohrdimensionierung", color = SubjectPalette.of(0))
        Spacer(Modifier.height(4.dp))
        Text("Druckverlust R je Rohr", style = Ranzen.type.title, color = c.ink)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Φ", style = Ranzen.type.mono.copy(fontSize = 18.sp), color = SubjectPalette.of(0), modifier = Modifier.width(36.dp))
            NumberField(power, { power = it }, "kW", Modifier.width(180.dp), error = power.isNotBlank() && p == null)
            Spacer(Modifier.width(20.dp))
            Text("ΔT", style = Ranzen.type.mono.copy(fontSize = 18.sp), color = SubjectPalette.of(0), modifier = Modifier.width(40.dp))
            NumberField(spread, { spread = it }, "K", Modifier.width(160.dp), error = spread.isNotBlank() && dt == null)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            PipeMaterial.entries.filter { it.pressurePipe }.forEach { m -> FilterTab(m.title, SubjectPalette.of(m.ordinal), m == material) { materialName = m.name } }
        }
        Spacer(Modifier.height(10.dp))
        if (flow == null) {
            Text("Leistung und Spreizung eingeben.", style = Ranzen.type.body, color = c.inkMuted)
        } else {
            Text("V̇ = ${GermanNumber.format(flow, 3)} m³/h  (${GermanNumber.format(flow * 1000 / 60, 2)} l/min)", style = Ranzen.type.mono.copy(fontSize = 17.sp), color = c.ink)
            Spacer(Modifier.height(10.dp))
            val rows = PipeCatalog.table(material, flow)
            val pick = PipeCatalog.choose(material, flow)
            Grid(
                listOf("Abmessung", "dᵢ mm", "v m/s", "R Pa/m"),
                rows.map { listOf(it.size.label, GermanNumber.format(it.size.innerMm, 1), GermanNumber.format(it.velocityMs, 2), GermanNumber.format(it.rPaPerM, 0)) },
                highlight = rows.indexOfFirst { it.size == pick?.size },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (pick == null) "Keine Größe hält R ≤ 100 Pa/m und v ≤ 1 m/s – größeren Werkstoff/Querschnitt wählen."
                else "Markiert: kleinste Größe mit R ≤ 100 Pa/m und v ≤ 1 m/s.",
                style = Ranzen.type.body, color = c.ink, textAlign = TextAlign.Start,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("Wasser 60 °C, Darcy-Weisbach. " + ShkDesign.SOURCE, style = Ranzen.type.caption, color = c.inkMuted)
        Spacer(Modifier.height(20.dp))
        SignalButton("Im Schnellrechner rechnen", { onCalculate("pipe_sizing") }, icon = RechnenIcons.Calculator)
    }
}
