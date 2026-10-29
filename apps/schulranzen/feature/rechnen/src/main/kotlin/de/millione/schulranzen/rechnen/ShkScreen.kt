package de.millione.schulranzen.rechnen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.millione.schulranzen.core.math.GermanNumber
import de.millione.schulranzen.core.math.MathEngine
import de.millione.schulranzen.core.math.MathOutcome
import de.millione.schulranzen.core.shk.Formula
import de.millione.schulranzen.core.shk.FormulaCategory
import de.millione.schulranzen.core.shk.Formulas
import de.millione.schulranzen.core.shk.Quantity
import de.millione.schulranzen.core.shk.ShkCalcResult
import de.millione.schulranzen.core.shk.ShkCalculator
import de.millione.schulranzen.core.shk.ShkCalculators
import de.millione.schulranzen.core.shk.Units
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.RegisterTabs
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.design.TactileKey
import java.math.BigDecimal
import java.math.MathContext

private const val TAB_CALC = 0
private const val TAB_UNITS = 1
private const val TAB_FORMULAS = 2
private const val TAB_TABLES = 3
private const val TAB_AUFMASS = 4
private const val TAB_MATERIAL = 5
private const val TAB_SCHEMA = 6

/** Formula list filters: all, favourites, or one category. */
private const val FILTER_ALL = "alle"
private const val FILTER_FAV = "favoriten"

/**
 * SHK toolbox: quick calculators with live results, the unit assistant and
 * the formula collection with search, categories and favourites. Landscape
 * shows list and detail side by side; portrait opens the detail over the list.
 */
@Composable
fun ShkScreen(onBack: () -> Unit) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val store = remember { RechnenStore.get(context) }
    val favorites by store.favorites.collectAsState()

    var section by rememberSaveable { mutableIntStateOf(TAB_CALC) }
    var calcId by rememberSaveable { mutableStateOf(ShkCalculators.all.first().id) }
    var quantityName by rememberSaveable { mutableStateOf(Quantity.PRESSURE.name) }
    var formulaId by rememberSaveable { mutableStateOf(Formulas.all.first().id) }
    var formulaFilter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var detailOpen by rememberSaveable { mutableStateOf(false) }
    var tableId by rememberSaveable { mutableStateOf(R_TABLE) }
    var aufmassId by rememberSaveable { mutableStateOf<String?>(null) }
    var schemaId by rememberSaveable { mutableStateOf<String?>(null) }
    var materialSources by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Where "back" in the calculator leads after a jump from the schema or a table.
    var returnTab by rememberSaveable { mutableStateOf<Int?>(null) }
    // Inputs survive switching between calculators and tabs: "calcId.key" → text.
    val calcInputs = remember { mutableStateMapOf<String, String>() }
    val unitValues = remember { mutableStateMapOf<String, String>() }
    val unitFrom = remember { mutableStateMapOf<String, String>() }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.desk)) {
        val wide = maxWidth >= 840.dp
        BackHandler {
            val back = returnTab
            when {
                back != null && section == TAB_CALC -> { section = back; detailOpen = back == TAB_TABLES; returnTab = null }
                !wide && detailOpen -> detailOpen = false
                else -> onBack()
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TactileKey(onClick = { if (!wide && detailOpen) detailOpen = false else onBack() }, contentDescription = "Zurück") {
                    RanzenIcon(RanzenIcons.Back)
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    Caption("Werkzeugkasten")
                    Text("SHK-Werkzeuge", style = Ranzen.type.title, color = c.ink)
                }
            }
            Spacer(Modifier.height(18.dp))
            RegisterTabs(
                tabs = listOf(
                    "Schnellrechner" to SubjectPalette.of(0), "Einheiten" to SubjectPalette.of(2), "Formeln" to SubjectPalette.of(1),
                    "Tabellen" to SubjectPalette.of(5), "Aufmaß" to SubjectPalette.of(3), "Material" to SubjectPalette.of(6), "Schema" to SubjectPalette.of(4),
                ),
                selected = section,
                onSelect = { section = it; detailOpen = false; returnTab = null },
                modifier = Modifier.padding(start = 18.dp).horizontalScroll(rememberScrollState()),
            )
            PaperPanel(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize()) {
                    val open = { detailOpen = true }
                    // Jump into a calculator with values from the schema or a table, and back again.
                    val openCalc = { id: String, values: Map<String, String>, from: Int? ->
                        values.forEach { (k, v) -> calcInputs[id + "." + k] = v }
                        calcId = id; section = TAB_CALC; detailOpen = true; returnTab = from
                    }
                    when (section) {
                        TAB_CALC -> MasterDetail(
                            wide, detailOpen,
                            list = {
                                CalculatorList(calcId) { calcId = it; returnTab = null; open() }
                            },
                            detail = {
                                CalculatorDetail(
                                    ShkCalculators.byId(calcId) ?: ShkCalculators.all.first(),
                                    calcInputs,
                                    onShowFormula = { id -> formulaId = id; formulaFilter = FILTER_ALL; section = TAB_FORMULAS; detailOpen = true },
                                    returnLabel = when (returnTab) { TAB_SCHEMA -> "Zurück zum Schema"; TAB_TABLES -> "Zurück zur Tabelle"; else -> null },
                                    onReturn = { returnTab?.let { section = it; detailOpen = it == TAB_TABLES }; returnTab = null },
                                )
                            },
                        )
                        TAB_TABLES -> TablesTab(
                            wide, detailOpen, tableId,
                            onSelect = { tableId = it; open() },
                            onCalculate = { id -> openCalc(id, emptyMap(), TAB_TABLES) },
                        )
                        TAB_AUFMASS -> AufmassTab(
                            wide, detailOpen, onOpenDetail = open,
                            selectedId = aufmassId, onSelect = { aufmassId = it },
                            onMaterials = { id -> materialSources = listOf(aufmassSource(id)); section = TAB_MATERIAL; detailOpen = true },
                        )
                        TAB_MATERIAL -> MaterialTab(wide, detailOpen, onOpenDetail = open, sources = materialSources, onSources = { materialSources = it })
                        TAB_SCHEMA -> SchemaTab(
                            wide, schemaId, onSelectSchema = { schemaId = it },
                            onCalculate = { link -> openCalc(link.calculatorId, link.inputs, TAB_SCHEMA) },
                        )
                        TAB_UNITS -> MasterDetail(
                            wide, detailOpen,
                            list = { QuantityList(Quantity.valueOf(quantityName)) { quantityName = it.name; open() } },
                            detail = { UnitDetail(Quantity.valueOf(quantityName), unitValues, unitFrom) },
                        )
                        else -> MasterDetail(
                            wide, detailOpen,
                            list = {
                                FormulaList(
                                    selectedId = formulaId,
                                    query = query,
                                    onQuery = { query = it },
                                    filter = formulaFilter,
                                    onFilter = { formulaFilter = it },
                                    favorites = favorites,
                                    onPick = { formulaId = it; open() },
                                )
                            },
                            detail = {
                                FormulaDetail(
                                    Formulas.byId(formulaId) ?: Formulas.all.first(),
                                    favorite = formulaId in favorites,
                                    onToggleFavorite = { store.toggleFavorite(formulaId) },
                                    onCalculate = { id -> calcId = id; section = TAB_CALC; detailOpen = true },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MasterDetail(
    wide: Boolean,
    detailOpen: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    val c = Ranzen.colors
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(360.dp).fillMaxHeight()) { list() }
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 20.dp).background(c.line))
            Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
        }
    } else {
        Box(Modifier.fillMaxSize()) { if (detailOpen) detail() else list() }
    }
}

// ---------------------------------------------------------------- Schnellrechner

@Composable
private fun CalculatorList(selectedId: String, onPick: (String) -> Unit) {
    val groups = remember { ShkCalculators.all.groupBy { it.category } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item(key = "ink") { InkModelPanel(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), compact = true) }
        FormulaCategory.entries.forEach { cat ->
            val calcs = groups[cat].orEmpty()
            if (calcs.isEmpty()) return@forEach
            item(key = "h_${cat.name}") { Caption(cat.title, Modifier.padding(start = 14.dp, top = 14.dp, bottom = 6.dp)) }
            items(calcs, key = { it.id }) { calc ->
                ListRow(calc.title, calc.formula, calc.id == selectedId, cat.accent(), onClick = { onPick(calc.id) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Parses an input field; expressions like "2·15" or "1/3" are allowed. */
private fun parseField(text: String): Double? {
    if (text.isBlank()) return null
    return (MathEngine.tryEvaluate(text) as? MathOutcome.Ok)?.result?.value
}

@Composable
private fun CalculatorDetail(
    calc: ShkCalculator,
    inputs: MutableMap<String, String>,
    onShowFormula: (String) -> Unit,
    returnLabel: String? = null,
    onReturn: () -> Unit = {},
) {
    val c = Ranzen.colors
    val accent = calc.category.accent()
    fun key(k: String) = "${calc.id}.$k"
    // Untouched fields show their default; once edited (even emptied) the user's text wins.
    fun textOf(input: de.millione.schulranzen.core.shk.ShkInput) =
        inputs[key(input.key)] ?: input.default?.let { GermanNumber.formatPlain(it) } ?: ""
    val values = calc.inputs.associate { it.key to parseField(textOf(it)) }
    val result = calc.calculate(values)
    val formula = remember(calc.id) { Formulas.all.firstOrNull { it.calculatorId == calc.id } }

    // Beside the fields when there is room: the keyboard then never covers the result.
    val resultPanel: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().recessed(18.dp).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lamp(if (result is ShkCalcResult.Ok) c.signal else c.inkFaint, size = 7.dp, glow = result is ShkCalcResult.Ok)
                Spacer(Modifier.width(10.dp))
                Caption("Ergebnis")
            }
            Spacer(Modifier.height(8.dp))
            when (result) {
                is ShkCalcResult.Ok -> result.outputs.forEachIndexed { i, out ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                        Text(out.symbol, style = Ranzen.type.mono.copy(fontSize = 16.sp), color = c.inkMuted, modifier = Modifier.width(40.dp))
                        Text(out.label, style = Ranzen.type.body.copy(hyphens = Hyphens.Auto), color = c.inkMuted, modifier = Modifier.weight(1f))
                        Text(
                            GermanNumber.format(out.value, out.decimals),
                            style = Ranzen.type.monoLarge.copy(fontSize = if (i == 0) 30.sp else 22.sp),
                            color = c.ink,
                            textAlign = TextAlign.End,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(out.unit, style = Ranzen.type.mono.copy(fontSize = 16.sp), color = c.inkMuted, modifier = Modifier.widthIn(min = 52.dp))
                    }
                }
                ShkCalcResult.Incomplete -> Text("Werte eingeben, das Ergebnis erscheint sofort.", style = Ranzen.type.body, color = c.inkMuted)
                is ShkCalcResult.Invalid -> Text(result.message, style = Ranzen.type.body, color = c.signal)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = maxWidth >= 760.dp
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp),
            ) {
                if (returnLabel != null) {
                    QuietButton("‹ " + returnLabel, onReturn, color = c.signal)
                    Spacer(Modifier.height(6.dp))
                }
                Caption(calc.category.title, color = accent)
                Spacer(Modifier.height(4.dp))
                Text(calc.title, style = Ranzen.type.title, color = c.ink)
                Spacer(Modifier.height(10.dp))
                Text(calc.formula, style = Ranzen.type.monoLarge.copy(fontSize = 22.sp, lineHeight = 28.sp), color = c.ink)
                Spacer(Modifier.height(24.dp))

                calc.inputs.forEach { input ->
                    val text = textOf(input)
                    val bad = text.isNotBlank() && parseField(text) == null
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(input.symbol, style = Ranzen.type.mono.copy(fontSize = 18.sp), color = accent, modifier = Modifier.width(64.dp))
                        Column(Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(input.label, style = Ranzen.type.label.copy(hyphens = Hyphens.Auto), color = c.ink)
                            input.hint?.let { Text(it, style = Ranzen.type.caption, color = c.inkMuted) }
                        }
                        NumberField(
                            value = text,
                            onValueChange = { inputs[key(input.key)] = it },
                            unit = input.unit,
                            placeholder = "0",
                            error = bad,
                            modifier = Modifier.width(if (side) 210.dp else 250.dp),
                        )
                    }
                }

                if (!side) { Spacer(Modifier.height(20.dp)); resultPanel() }
                Spacer(Modifier.height(16.dp))
                Text(calc.note, style = Ranzen.type.body, color = c.inkMuted)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (formula != null) QuietButton("Formelblatt ansehen", { onShowFormula(formula.id) }, color = c.signal)
                    QuietButton("Felder leeren", { calc.inputs.forEach { inputs[key(it.key)] = "" } })
                }
            }
            if (side) Column(Modifier.width(420.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 28.dp, end = 32.dp, bottom = 28.dp)) {
                resultPanel()
            }
        }
    }
}

// ---------------------------------------------------------------- Einheiten

private val PREFERRED_UNIT = mapOf(
    Quantity.PRESSURE to "bar", Quantity.FLOW to "m³/h", Quantity.POWER to "kW", Quantity.ENERGY to "kWh",
    Quantity.LENGTH to "mm", Quantity.AREA to "m²", Quantity.VOLUME to "l", Quantity.VELOCITY to "m/s",
    Quantity.TEMPERATURE to "°C",
)

private fun Quantity.accent(): Color = SubjectPalette.of(ordinal)

@Composable
private fun QuantityList(selected: Quantity, onPick: (Quantity) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item { Caption("Größen", Modifier.padding(start = 14.dp, top = 14.dp, bottom = 6.dp)) }
        items(Quantity.entries, key = { it.name }) { q ->
            ListRow(q.title, q.units.joinToString(" · ") { it.symbol }, q == selected, q.accent(), onClick = { onPick(q) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Six significant digits: enough for the trade, short enough to read at a glance. */
private fun short(v: Double): String {
    if (!v.isFinite()) return "–"
    if (v == 0.0) return "0"
    val r = BigDecimal(v).round(MathContext(6)).toDouble()
    return GermanNumber.format(r)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitDetail(q: Quantity, values: MutableMap<String, String>, from: MutableMap<String, String>) {
    val c = Ranzen.colors
    val text = values[q.name] ?: "1"
    val fromSymbol = from[q.name] ?: PREFERRED_UNIT[q] ?: q.base.symbol
    val value = parseField(text)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp)) {
        Caption("Einheiten-Assistent", color = q.accent())
        Spacer(Modifier.height(4.dp))
        Text(q.title, style = Ranzen.type.title, color = c.ink)
        Spacer(Modifier.height(20.dp))
        NumberField(
            value = text,
            onValueChange = { values[q.name] = it },
            unit = fromSymbol,
            error = text.isNotBlank() && value == null,
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            q.units.forEach { u ->
                TactileKey(
                    onClick = { from[q.name] = u.symbol },
                    selected = u.symbol == fromSymbol,
                    modifier = Modifier.widthIn(min = 64.dp),
                    size = 48.dp,
                    corner = 12.dp,
                    contentDescription = u.name,
                ) {
                    Text(u.symbol, style = Ranzen.type.mono.copy(fontSize = 15.sp), color = if (u.symbol == fromSymbol) c.signal else c.ink, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Column(Modifier.fillMaxWidth().recessed(18.dp).padding(vertical = 8.dp)) {
            if (value == null) {
                Text("Wert eingeben", style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.padding(20.dp))
            } else {
                Units.convertAll(q, value, fromSymbol).forEach { (u, v) ->
                    val isFrom = u.symbol == fromSymbol
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .clickable {
                                // Tap a row to continue from that unit.
                                values[q.name] = BigDecimal(v).round(MathContext(10)).toDouble().let(GermanNumber::formatPlain)
                                from[q.name] = u.symbol
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(u.symbol, style = Ranzen.type.mono.copy(fontSize = 16.sp), color = if (isFrom) c.signal else c.ink, modifier = Modifier.width(80.dp))
                        Text(u.name, style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.weight(1f))
                        Text(short(v), style = Ranzen.type.monoLarge.copy(fontSize = 22.sp), color = c.ink)
                    }
                }
            }
        }
        if (q == Quantity.TEMPERATURE) {
            Spacer(Modifier.height(12.dp))
            Text("Temperaturdifferenzen (ΔT) sind in K und °C gleich groß.", style = Ranzen.type.body, color = c.inkMuted)
        }
        if (q == Quantity.PRESSURE) {
            Spacer(Modifier.height(12.dp))
            Text("Faustregel: 1 bar ≈ 10 m Wassersäule.", style = Ranzen.type.body, color = c.inkMuted)
        }
    }
}

// ---------------------------------------------------------------- Formeln

@Composable
private fun FormulaList(
    selectedId: String,
    query: String,
    onQuery: (String) -> Unit,
    filter: String,
    onFilter: (String) -> Unit,
    favorites: Set<String>,
    onPick: (String) -> Unit,
) {
    val c = Ranzen.colors
    val base = when (filter) {
        FILTER_ALL -> Formulas.all
        FILTER_FAV -> Formulas.all.filter { it.id in favorites }
        else -> Formulas.byCategory(FormulaCategory.valueOf(filter))
    }
    val shown = Formulas.search(query, base)
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        SearchField(query, onQuery, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
            FilterTab("Alle", null, filter == FILTER_ALL) { onFilter(FILTER_ALL) }
            FilterTab("Favoriten", c.signal, filter == FILTER_FAV) { onFilter(FILTER_FAV) }
            FormulaCategory.entries.forEach { cat -> FilterTab(cat.title, cat.accent(), filter == cat.name) { onFilter(cat.name) } }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    if (filter == FILTER_FAV && query.isBlank()) "Noch keine Favoriten. Tippe im Formelblatt auf den Stern." else "Nichts gefunden.",
                    style = Ranzen.type.body, color = c.inkMuted, textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                items(shown, key = { it.id }) { f ->
                    ListRow(
                        f.title, f.formula, f.id == selectedId, f.category.accent(),
                        onClick = { onPick(f.id) }, modifier = Modifier.fillMaxWidth(),
                        trailing = if (f.id in favorites) ({ RanzenIcon(RechnenIcons.StarFilled, tint = c.signal, size = 16.dp) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val c = Ranzen.colors
    Row(modifier.height(52.dp).recessed(14.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        RanzenIcon(RanzenIcons.Search, tint = c.inkMuted, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Formel suchen", style = Ranzen.type.body, color = c.inkFaint)
            BasicTextField(
                query, onQuery, singleLine = true,
                textStyle = Ranzen.type.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.signal),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable { onQuery("") }, contentAlignment = Alignment.Center) {
                RanzenIcon(RanzenIcons.Close, tint = c.inkMuted, size = 16.dp)
            }
        }
    }
}

/** A filter as a quiet index tab: label with a lamp, underlined in the signal colour when active. */
@Composable
internal fun FilterTab(text: String, color: Color?, selected: Boolean, onClick: () -> Unit) {
    val c = Ranzen.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (color != null) { Lamp(color, size = 7.dp, glow = selected); Spacer(Modifier.width(6.dp)) }
            Text(text, style = Ranzen.type.label, color = if (selected) c.ink else c.inkMuted)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(24.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(if (selected) c.signal else Color.Transparent))
    }
}

@Composable
private fun FormulaDetail(formula: Formula, favorite: Boolean, onToggleFavorite: () -> Unit, onCalculate: (String) -> Unit) {
    val c = Ranzen.colors
    val accent = formula.category.accent()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Caption(formula.category.title, color = accent)
                Spacer(Modifier.height(4.dp))
                Text(formula.title, style = Ranzen.type.title, color = c.ink)
            }
            TactileKey(onClick = onToggleFavorite, selected = favorite, contentDescription = if (favorite) "Favorit entfernen" else "Als Favorit merken") {
                RanzenIcon(if (favorite) RechnenIcons.StarFilled else RechnenIcons.Star, tint = if (favorite) c.signal else c.ink)
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().recessed(18.dp).padding(horizontal = 24.dp, vertical = 22.dp)) {
            Text(formula.formula, style = Ranzen.type.monoLarge.copy(fontSize = 28.sp, lineHeight = 36.sp), color = c.ink)
        }
        Spacer(Modifier.height(22.dp))
        Caption("Größen")
        Spacer(Modifier.height(6.dp))
        formula.variables.forEach { v ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(v.symbol, style = Ranzen.type.mono.copy(fontSize = 18.sp), color = accent, modifier = Modifier.width(96.dp))
                Text(v.meaning, style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
                Text(v.unit, style = Ranzen.type.mono, color = c.inkMuted)
            }
            Box(Modifier.fillMaxWidth().height(0.75.dp).background(c.line))
        }
        Spacer(Modifier.height(20.dp))
        Text(formula.explanation, style = Ranzen.type.body, color = c.ink.copy(alpha = 0.86f))
        formula.calculatorId?.let { id ->
            Spacer(Modifier.height(24.dp))
            SignalButton("Im Schnellrechner rechnen", { onCalculate(id) }, icon = RechnenIcons.Calculator)
        }
    }
}
