package de.millione.schulranzen.rechnen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.millione.schulranzen.core.math.GermanNumber
import de.millione.schulranzen.core.shk.CalcLink
import de.millione.schulranzen.core.shk.PipeCatalog
import de.millione.schulranzen.core.shk.PipeKind
import de.millione.schulranzen.core.shk.Pt
import de.millione.schulranzen.core.shk.Schema
import de.millione.schulranzen.core.shk.SchemaGeometry
import de.millione.schulranzen.core.shk.SchemaHit
import de.millione.schulranzen.core.shk.SchemaLabel
import de.millione.schulranzen.core.shk.SchemaLinks
import de.millione.schulranzen.core.shk.SchemaOps
import de.millione.schulranzen.core.shk.SchemaPipe
import de.millione.schulranzen.core.shk.SchemaSymbol
import de.millione.schulranzen.core.shk.ShkCalcResult
import de.millione.schulranzen.core.shk.ShkCalculators
import de.millione.schulranzen.core.shk.ShkInput
import de.millione.schulranzen.core.shk.SymbolGroup
import de.millione.schulranzen.core.shk.SymbolLibrary
import de.millione.schulranzen.core.shk.SymbolType
import de.millione.schulranzen.core.shk.UndoStack
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Tool { SELECT, PIPE, TEXT, PLACE }

/**
 * Holds one open schema with undo/redo. Every committed step is saved;
 * [coalesce] merges a run of edits to the same field into one undo step.
 */
private class SchemaSession(initial: Schema, private val save: (Schema) -> Unit) {
    private val stack = UndoStack(initial)
    private var lastKey: String? = null
    var schema by mutableStateOf(initial); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    fun commit(next: Schema, coalesce: String? = null) {
        val stamped = next.copy(updatedAt = System.currentTimeMillis())
        if (coalesce != null && coalesce == lastKey) stack.replace(stamped) else stack.push(stamped)
        lastKey = coalesce
        sync(); save(schema)
    }

    fun undo() { stack.undo(); lastKey = null; sync(); save(schema) }
    fun redo() { stack.redo(); lastKey = null; sync(); save(schema) }

    private fun sync() { schema = stack.current; canUndo = stack.canUndo; canRedo = stack.canRedo }
}

/**
 * SHK schema editor: symbols from the library on a grid, pipes as orthogonal
 * lines that snap to ports, labels, undo, several saved schemata, PNG/PDF
 * export. Tapping a symbol or pipe shows its values and live result and
 * opens the matching calculator.
 */
@Composable
internal fun SchemaTab(
    wide: Boolean,
    schemaId: String?,
    onSelectSchema: (String?) -> Unit,
    onCalculate: (CalcLink) -> Unit,
) {
    val context = LocalContext.current
    val files = remember { ShkFiles.get(context) }
    val schemas = files.schemas.items.collectAsState().value ?: return
    val open = schemas.firstOrNull { it.id == schemaId } ?: schemas.firstOrNull()
    var listOpen by remember { mutableStateOf(false) }

    fun create(template: Boolean) {
        val base = Schema(ShkFiles.newId(), "Schema ${schemas.size + 1}", updatedAt = System.currentTimeMillis())
        val s = if (template) exampleSchema(base) else base
        files.schemas.put(s); onSelectSchema(s.id); listOpen = false
    }

    Box(Modifier.fillMaxSize()) {
        if (open == null) {
            Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Noch kein Schema.", style = Ranzen.type.heading, color = Ranzen.colors.ink)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Symbole aus der Bibliothek platzieren, Rohre ziehen – sie rasten an den Anschlüssen ein. Antippen zeigt Werte und Rechnung.",
                    style = Ranzen.type.body, color = Ranzen.colors.inkMuted,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SignalButton("Leeres Schema", { create(false) }, icon = RanzenIcons.Plus)
                    QuietButton("Mit Beispiel-Heizkreis", { create(true) }, color = Ranzen.colors.signal)
                }
            }
        } else {
            // A fresh session per schema; external changes (other tab) are not expected while open.
            val session = remember(open.id) { SchemaSession(open) { files.schemas.put(it) } }
            SchemaEditor(
                wide, session,
                onShowList = { listOpen = true },
                onCalculate = onCalculate,
                onDelete = { files.schemas.delete(open.id); onSelectSchema(schemas.firstOrNull { it.id != open.id }?.id) },
            )
        }
        if (listOpen) SidePanel("Schemata", { listOpen = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalButton("Neu", { create(false) }, icon = RanzenIcons.Plus)
                QuietButton("Mit Beispiel", { create(true) }, color = Ranzen.colors.signal)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(schemas, key = { it.id }) { s ->
                    ListRow(
                        s.name, "${s.symbols.size} Symbole · ${s.pipes.size} Rohre", s.id == open?.id, SubjectPalette.of(4),
                        onClick = { onSelectSchema(s.id); listOpen = false }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SchemaEditor(
    wide: Boolean,
    session: SchemaSession,
    onShowList: () -> Unit,
    onCalculate: (CalcLink) -> Unit,
    onDelete: () -> Unit,
) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val schema = session.schema

    var tool by remember { mutableStateOf(Tool.SELECT) }
    var placeType by remember { mutableStateOf<SymbolType?>(null) }
    var pipeKind by remember { mutableStateOf(PipeKind.FLOW) }
    var lastPipeId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var propsOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) { if (notice != null) { delay(3000); notice = null } }

    // View: screen = world · scale + offset.
    var scale by remember { mutableFloatStateOf(22f * density) }
    var offset by remember { mutableStateOf(Offset(120f * density, 120f * density)) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var preview by remember { mutableStateOf<Schema?>(null) }
    var draft by remember { mutableStateOf<List<Pt>?>(null) }
    var snapAt by remember { mutableStateOf<Pt?>(null) }

    fun fit() {
        val b = SchemaGeometry.bounds(schema) ?: return
        if (canvasSize.width <= 0f) return
        val pad = 2.0
        val s = minOf(canvasSize.width / (b.width + 2 * pad), canvasSize.height / (b.height + 2 * pad)).toFloat().coerceIn(8f * density, 60f * density)
        scale = s
        offset = Offset(
            (canvasSize.width / 2 - ((b.left + b.right) / 2).toFloat() * s),
            (canvasSize.height / 2 - ((b.top + b.bottom) / 2).toFloat() * s),
        )
    }
    LaunchedEffect(schema.id, canvasSize) { if (canvasSize.width > 0f) fit() }

    val png = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch { notice = if (ShkExport.save(context, uri) { ShkExport.schemaPng(it, schema) }) "PNG gespeichert" else "Speichern fehlgeschlagen" }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch { notice = if (ShkExport.save(context, uri) { ShkExport.schemaPdf(it, schema) }) "PDF gespeichert" else "Speichern fehlgeschlagen" }
    }

    val selSymbol = schema.symbols.firstOrNull { it.id == selected }
    val selPipe = schema.pipes.firstOrNull { it.id == selected }
    val selLabel = schema.labels.firstOrNull { it.id == selected }
    if (selected != null && selSymbol == null && selPipe == null && selLabel == null) selected = null

    fun deleteSelected() {
        val id = selected ?: return
        session.commit(
            when {
                selSymbol != null -> SchemaOps.deleteSymbol(schema, id)
                selPipe != null -> SchemaOps.deletePipe(schema, id)
                else -> SchemaOps.deleteLabel(schema, id)
            },
        )
        selected = null
    }

    Column(Modifier.fillMaxSize()) {
        // ---- toolbar
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onShowList).defaultMinSize(minHeight = 48.dp).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Caption("Schema ▾")
                Text(schema.name, style = Ranzen.type.heading, color = c.ink, maxLines = 1)
            }
            Spacer(Modifier.width(14.dp))
            ToolKey(RechnenIcons.Select, "Auswahl", { tool = Tool.SELECT; placeType = null }, selected = tool == Tool.SELECT)
            ToolKey(RechnenIcons.Pipe, "Rohr", { tool = Tool.PIPE; placeType = null; selected = null }, selected = tool == Tool.PIPE, tint = SchemaStyleFor().pipe(pipeKind))
            ToolKey(RechnenIcons.Label, "Text", { tool = Tool.TEXT; placeType = null }, selected = tool == Tool.TEXT)
            Spacer(Modifier.width(12.dp))
            ToolKey(RechnenIcons.Rotate, "Drehen", { selSymbol?.let { session.commit(SchemaOps.rotate(schema, it.id)) } }, enabled = selSymbol != null)
            ToolKey(RechnenIcons.Mirror, "Spiegeln", { selSymbol?.let { session.commit(SchemaOps.mirror(schema, it.id)) } }, enabled = selSymbol != null)
            ToolKey(RanzenIcons.Trash, "Löschen", { deleteSelected() }, enabled = selected != null)
            Spacer(Modifier.width(12.dp))
            ToolKey(RanzenIcons.Undo, "Zurück", { session.undo() }, enabled = session.canUndo)
            ToolKey(RanzenIcons.Redo, "Vor", { session.redo() }, enabled = session.canRedo)
            ToolKey(RechnenIcons.Fit, "Einpassen", { fit() })
            Spacer(Modifier.width(12.dp))
            ToolKey(RanzenIcons.Image, "PNG", { png.launch(ShkExport.fileName(schema.name, "png")) })
            ToolKey(RanzenIcons.Export, "PDF", { pdf.launch(ShkExport.fileName(schema.name, "pdf")) })
            if (!wide && selected != null) {
                Spacer(Modifier.width(12.dp))
                SignalButton("Eigenschaften", { propsOpen = true })
            }
        }
        if (tool == Tool.PIPE) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                PipeKind.entries.forEach { k -> FilterTab("${k.short} · ${k.title}", SchemaStyleFor().pipe(k), k == pipeKind) { pipeKind = k } }
                Spacer(Modifier.width(10.dp))
                Text("Mit Stift oder Finger von Anschluss zu Anschluss ziehen.", style = Ranzen.type.caption, color = c.inkMuted)
            }
        }
        notice?.let {
            Row(Modifier.padding(horizontal = 26.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Lamp(c.signal, size = 7.dp); Spacer(Modifier.width(8.dp)); Text(it, style = Ranzen.type.label, color = c.ink)
            }
        }
        Box(Modifier.fillMaxWidth().height(0.75.dp).background(c.line))

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // ---- symbol library
            SymbolPalette(
                Modifier.width(if (wide) 196.dp else 112.dp).fillMaxHeight(), compact = !wide, armed = placeType.takeIf { tool == Tool.PLACE },
            ) { type ->
                if (tool == Tool.PLACE && placeType == type) { tool = Tool.SELECT; placeType = null } else { tool = Tool.PLACE; placeType = type; selected = null }
            }
            Box(Modifier.width(0.75.dp).fillMaxHeight().background(c.line))

            // ---- canvas
            val style = SchemaStyleFor()
            val state by rememberUpdatedState(EditorState(schema, tool, placeType, pipeKind, lastPipeId, selected, scale, offset))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Canvas(
                    Modifier.fillMaxSize().clipToBounds().pointerInput(session, wide) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val st = state
                            fun toWorld(p: Offset) = Pt(((p.x - offset.x) / scale).toDouble(), ((p.y - offset.y) / scale).toDouble())
                            val startWorld = toWorld(down.position)
                            val tol = (26f * density / st.scale).toDouble()
                            val startHit = SchemaGeometry.hit(st.schema, startWorld, tol)
                            val dragId = when (startHit) {
                                is SchemaHit.Symbol -> startHit.id.takeIf { st.tool == Tool.SELECT }
                                is SchemaHit.Label -> startHit.id.takeIf { st.tool == Tool.SELECT }
                                else -> null
                            }
                            val pipeStart = if (st.tool == Tool.PIPE) SchemaGeometry.snap(st.schema, startWorld, radius = maxOf(0.8, tol)) else null
                            var multi = false
                            var moved = false
                            val slop = viewConfiguration.touchSlop
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    if (!multi) { multi = true; preview = null; draft = null; snapAt = null }
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid()
                                    val newScale = (scale * zoom).coerceIn(6f * density, 90f * density)
                                    offset = centroid - (centroid - offset) * (newScale / scale) + pan
                                    scale = newScale
                                    event.changes.forEach { it.consume() }
                                    continue
                                }
                                if (multi) continue
                                val ch = pressed.first()
                                if (!moved && (ch.position - down.position).getDistance() > slop) moved = true
                                if (!moved) continue
                                val world = toWorld(ch.position)
                                when {
                                    pipeStart != null -> {
                                        val end = SchemaGeometry.snap(st.schema, world, radius = maxOf(0.8, tol))
                                        val horizontal = abs(world.x - startWorld.x) >= abs(world.y - startWorld.y)
                                        draft = SchemaGeometry.route(pipeStart.at, end.at, horizontal)
                                        snapAt = end.at.takeIf { end.port != null }
                                    }
                                    dragId != null -> {
                                        val dx = world.x - startWorld.x; val dy = world.y - startWorld.y
                                        val sym = st.schema.symbols.firstOrNull { it.id == dragId }
                                        preview = if (sym != null) SchemaOps.moveSymbol(st.schema, dragId, Pt(sym.at.x + dx, sym.at.y + dy))
                                        else SchemaOps.updateLabel(st.schema, dragId) { l ->
                                            l.copy(at = Pt(((l.at.x + dx) * 2).roundToInt() / 2.0, ((l.at.y + dy) * 2).roundToInt() / 2.0))
                                        }
                                        selected = dragId
                                    }
                                    else -> offset += ch.positionChange()
                                }
                                ch.consume()
                            }
                            if (multi) return@awaitEachGesture
                            val endPreview = preview
                            preview = null
                            if (!moved) {
                                // Tap.
                                when (st.tool) {
                                    Tool.PLACE -> st.placeType?.let { type ->
                                        val s = SchemaSymbol(ShkFiles.newId(), type, SchemaGeometry.grid(startWorld))
                                        session.commit(SchemaOps.addSymbol(st.schema, s))
                                        selected = s.id
                                    }
                                    Tool.TEXT -> {
                                        val hitLabel = startHit as? SchemaHit.Label
                                        if (hitLabel != null) selected = hitLabel.id else {
                                            val l = SchemaLabel(ShkFiles.newId(), Pt((startWorld.x * 2).roundToInt() / 2.0, (startWorld.y * 2).roundToInt() / 2.0), "Text")
                                            session.commit(st.schema.copy(labels = st.schema.labels + l))
                                            selected = l.id
                                            if (!wide) propsOpen = true
                                        }
                                    }
                                    else -> selected = when (startHit) {
                                        is SchemaHit.Symbol -> startHit.id
                                        is SchemaHit.Pipe -> startHit.id
                                        is SchemaHit.Label -> startHit.id
                                        null -> null
                                    }
                                }
                            } else if (pipeStart != null) {
                                val d = draft
                                draft = null; snapAt = null
                                if (d != null && d.size >= 2) {
                                    val id = ShkFiles.newId()
                                    val next = SchemaOps.addPipe(st.schema, id, st.pipeKind, startWorld, d.last(), st.lastPipeId)
                                    if (next !== st.schema) { session.commit(next); selected = id }
                                }
                            } else if (endPreview != null) {
                                session.commit(endPreview)
                            }
                        }
                    },
                ) {
                    canvasSize = size
                    drawRect(style.paper)
                    val shown = preview ?: schema
                    withTransform({ translate(offset.x, offset.y); scale(scale, scale, Offset.Zero) }) {
                        val left = (-offset.x / scale).toDouble(); val top = (-offset.y / scale).toDouble()
                        drawSchemaGrid(style, left, top, left + size.width / scale, top + size.height / scale, scale)
                        drawSchemaUnits(
                            shown, style,
                            SchemaOverlay(selected, showPorts = tool == Tool.PIPE || selSymbol != null, snapAt = snapAt, draft = draft, draftKind = pipeKind),
                        )
                    }
                }
                if (schema.symbols.isEmpty() && schema.pipes.isEmpty()) {
                    Text(
                        "Links ein Symbol antippen, dann auf das Blatt tippen.\nZwei Finger: zoomen und verschieben.",
                        style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                } else if (tool == Tool.PLACE && placeType != null) {
                    Text(
                        "Tippen platziert: ${SymbolLibrary.def(placeType!!).title}",
                        style = Ranzen.type.label, color = c.ink,
                        modifier = Modifier.align(Alignment.TopCenter).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(c.raised).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            if (wide) {
                Box(Modifier.width(0.75.dp).fillMaxHeight().background(c.line))
                Box(Modifier.width(340.dp).fillMaxHeight()) {
                    PropertiesPanel(session, selected, onCalculate, onPipeChosen = { lastPipeId = it }, onDeleteSchema = onDelete)
                }
            }
        }
    }
    if (!wide && propsOpen && selected != null) {
        SidePanel("Eigenschaften", { propsOpen = false }, width = 400.dp) {
            PropertiesPanel(session, selected, onCalculate, onPipeChosen = { lastPipeId = it }, onDeleteSchema = onDelete)
        }
    }
}

/** Snapshot of what the gesture handler needs, refreshed every composition. */
private data class EditorState(
    val schema: Schema,
    val tool: Tool,
    val placeType: SymbolType?,
    val pipeKind: PipeKind,
    val lastPipeId: String?,
    val selected: String?,
    val scale: Float,
    val offset: Offset,
)

@Composable
internal fun SchemaStyleFor(): SchemaStyle {
    val c = Ranzen.colors
    return SchemaStyle(
        ink = c.ink,
        paper = if (c.isDark) Color(0xFF1B1A18) else Color(0xFFFCFAF5),
        grid = if (c.isDark) Color(0xFF34322E) else Color(0xFFE6E0D3),
        accent = c.signal,
        dark = c.isDark,
    )
}

@Composable
private fun SymbolPalette(modifier: Modifier, compact: Boolean, armed: SymbolType?, onPick: (SymbolType) -> Unit) {
    val c = Ranzen.colors
    val style = SchemaStyleFor()
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 10.dp)) {
        SymbolGroup.entries.forEach { group ->
            Caption(group.title, Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp))
            SymbolLibrary.all.filter { it.group == group }.chunked(if (compact) 1 else 2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { def ->
                        val active = def.type == armed
                        Column(
                            Modifier.width(88.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (active) c.raised else Color.Transparent)
                                .clickable { onPick(def.type) }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val tile = remember(def.type) { Schema("tile", "", symbols = listOf(SchemaSymbol("t", def.type, Pt(0.0, 0.0)))) }
                            Canvas(Modifier.size(56.dp)) { drawSchemaFitted(tile, style.copy(paper = Color.Transparent), size.width, size.height, 2f) }
                            Text(def.title, style = Ranzen.type.caption.copy(fontSize = 11.sp, lineHeight = 13.sp), color = if (active) c.signal else c.ink, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/** Values of the selected element, its live result and the link to the full calculator. */
@Composable
private fun PropertiesPanel(
    session: SchemaSession,
    selected: String?,
    onCalculate: (CalcLink) -> Unit,
    onPipeChosen: (String) -> Unit,
    onDeleteSchema: () -> Unit,
) {
    val c = Ranzen.colors
    val schema = session.schema
    val sym = schema.symbols.firstOrNull { it.id == selected }
    val pipe = schema.pipes.firstOrNull { it.id == selected }
    val label = schema.labels.firstOrNull { it.id == selected }
    var picking by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        when {
            sym != null -> {
                val def = SymbolLibrary.def(sym.type)
                Caption(def.group.title, color = SubjectPalette.of(4))
                Text(def.title, style = Ranzen.type.heading, color = c.ink)
                Spacer(Modifier.height(10.dp))
                TextLine(sym.label, { t -> session.commit(SchemaOps.updateSymbol(schema, sym.id) { it.copy(label = t) }, "label_${sym.id}") }, Modifier.fillMaxWidth(), "Beschriftung, z. B. HK 1")
                val inputs = SchemaLinks.propertiesOf(sym.type)
                if (inputs.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    PropFields(inputs, sym.props) { key, v ->
                        session.commit(SchemaOps.updateSymbol(schema, sym.id) { it.copy(props = it.props + (key to v)) }, "prop_${sym.id}_$key")
                    }
                    SchemaLinks.forSymbol(sym)?.let { link -> LiveResult(link, sym.props, onCalculate) }
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text("Für dieses Symbol gibt es keine Rechnung.", style = Ranzen.type.body, color = c.inkMuted)
                }
            }
            pipe != null -> {
                Caption("Rohrleitung", color = SchemaStyleFor().pipe(pipe.kind))
                Text(pipe.kind.title, style = Ranzen.type.heading, color = c.ink)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    PipeKind.entries.forEach { k ->
                        FilterTab(k.short, SchemaStyleFor().pipe(k), k == pipe.kind) { session.commit(SchemaOps.updatePipe(schema, pipe.id) { it.copy(kind = k) }) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).clip(RoundedCornerShape(12.dp)).background(c.raised).clickable { picking = true }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Rohr", style = Ranzen.type.label, color = c.inkMuted, modifier = Modifier.width(70.dp))
                    Text(PipeCatalog.byId(pipe.pipeId)?.fullLabel ?: "wählen (für Materialliste)", style = Ranzen.type.mono.copy(fontSize = 15.sp), color = if (pipe.pipeId == null) c.signal else c.ink)
                }
                Spacer(Modifier.height(8.dp))
                val drawn = SchemaGeometry.length(pipe.points) * schema.metersPerUnit
                var lenText by remember(pipe.id) { mutableStateOf(pipe.lengthM?.let { GermanNumber.formatPlain(it) } ?: "") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Länge", style = Ranzen.type.label, color = c.ink, modifier = Modifier.weight(1f))
                    NumberField(lenText, { t ->
                        lenText = t
                        val v = de.millione.schulranzen.core.shk.AufmassPosition.evaluate(t)
                        if (t.isBlank() || (v != null && v > 0)) session.commit(SchemaOps.updatePipe(schema, pipe.id) { it.copy(lengthM = v) }, "len_${pipe.id}")
                    }, "m", Modifier.width(170.dp), placeholder = GermanNumber.format(drawn, 1))
                }
                Text("Leer = gezeichnete Länge (${GermanNumber.format(drawn, 1)} m)", style = Ranzen.type.caption, color = c.inkMuted)
                Spacer(Modifier.height(8.dp))
                TextLine(pipe.label, { t -> session.commit(SchemaOps.updatePipe(schema, pipe.id) { it.copy(label = t) }, "plabel_${pipe.id}") }, Modifier.fillMaxWidth(), "Beschriftung (optional)")
                Spacer(Modifier.height(12.dp))
                val inputs = SchemaLinks.pipeProperties()
                PropFields(inputs, pipe.props) { key, v ->
                    session.commit(SchemaOps.updatePipe(schema, pipe.id) { it.copy(props = it.props + (key to v)) }, "pprop_${pipe.id}_$key")
                }
                LiveResult(SchemaLinks.forPipe(pipe), pipe.props, onCalculate)
            }
            label != null -> {
                Caption("Beschriftung")
                Spacer(Modifier.height(8.dp))
                TextLine(label.text, { t -> session.commit(SchemaOps.updateLabel(schema, label.id) { it.copy(text = t) }, "text_${label.id}") }, Modifier.fillMaxWidth(), "Text")
            }
            else -> SchemaInfo(session, onDeleteSchema)
        }
    }
    // Outside the scrolling column: the picker needs bounded height for its list.
    if (picking && pipe != null) PipePicker(pipe.pipeId, pressureOnly = false, onDismiss = { picking = false }) { id ->
        session.commit(SchemaOps.updatePipe(schema, pipe.id) { it.copy(pipeId = id) }); onPipeChosen(id); picking = false
    }
    }
}

@Composable
private fun PropFields(inputs: List<ShkInput>, props: Map<String, String>, onChange: (String, String) -> Unit) {
    val c = Ranzen.colors
    inputs.forEach { input ->
        val text = props[input.key] ?: input.default?.let { GermanNumber.formatPlain(it) } ?: ""
        val bad = text.isNotBlank() && de.millione.schulranzen.core.shk.AufmassPosition.evaluate(text) == null
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(input.label, style = Ranzen.type.label, color = c.ink, maxLines = 2)
            }
            NumberField(text, { onChange(input.key, it) }, input.unit, Modifier.width(150.dp), placeholder = "0", error = bad)
        }
    }
}

@Composable
private fun LiveResult(link: CalcLink, props: Map<String, String>, onCalculate: (CalcLink) -> Unit) {
    val c = Ranzen.colors
    val calc = ShkCalculators.byId(link.calculatorId) ?: return
    val values = calc.inputs.associate { i ->
        val text = props[i.key] ?: i.default?.let { GermanNumber.formatPlain(it) }
        i.key to text?.let { de.millione.schulranzen.core.shk.AufmassPosition.evaluate(it) }
    }
    val result = calc.calculate(values)
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().recessed(14.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Caption(calc.title)
        Spacer(Modifier.height(4.dp))
        when (result) {
            is ShkCalcResult.Ok -> result.outputs.take(3).forEach { o ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Bottom) {
                    Text(o.label, style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.weight(1f), maxLines = 2)
                    Text("${GermanNumber.format(o.value, o.decimals)} ${o.unit}", style = Ranzen.type.mono.copy(fontSize = 16.sp), color = c.ink)
                }
            }
            ShkCalcResult.Incomplete -> Text("Werte eingeben für das Ergebnis.", style = Ranzen.type.caption, color = c.inkMuted)
            is ShkCalcResult.Invalid -> Text(result.message, style = Ranzen.type.caption, color = c.signal)
        }
    }
    Spacer(Modifier.height(10.dp))
    val all = calc.inputs.mapNotNull { i -> (props[i.key] ?: i.default?.let { GermanNumber.formatPlain(it) })?.let { i.key to it } }.toMap()
    SignalButton("Im Rechner öffnen", { onCalculate(link.copy(inputs = all)) }, icon = RechnenIcons.Calculator)
}

@Composable
private fun SchemaInfo(session: SchemaSession, onDelete: () -> Unit) {
    val c = Ranzen.colors
    val schema = session.schema
    Caption("Schema")
    Spacer(Modifier.height(6.dp))
    TextLine(schema.name, { t -> session.commit(schema.copy(name = t), "name") }, Modifier.fillMaxWidth(), "Name")
    Spacer(Modifier.height(12.dp))
    var scaleText by remember(schema.id) { mutableStateOf(GermanNumber.formatPlain(schema.metersPerUnit)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("1 Kästchen =", style = Ranzen.type.label, color = c.ink, modifier = Modifier.weight(1f))
        NumberField(scaleText, { t ->
            scaleText = t
            de.millione.schulranzen.core.shk.AufmassPosition.evaluate(t)?.takeIf { it > 0 }?.let { session.commit(schema.copy(metersPerUnit = it), "scale") }
        }, "m", Modifier.width(150.dp))
    }
    Spacer(Modifier.height(14.dp))
    val total = schema.pipes.groupBy { it.kind }.mapValues { (_, l) -> l.sumOf { SchemaGeometry.lengthM(schema, it) } }
    Caption("Rohrlängen")
    if (total.isEmpty()) Text("Noch keine Rohre.", style = Ranzen.type.body, color = c.inkMuted)
    total.forEach { (k, m) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Lamp(SchemaStyleFor().pipe(k), size = 7.dp, glow = false)
            Spacer(Modifier.width(8.dp))
            Text(k.title, style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
            Text("${GermanNumber.format(m, 1)} m", style = Ranzen.type.mono, color = c.ink)
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(
        "Tippen wählt ein Element. Symbole und Texte mit dem Finger oder Stift verschieben. " +
            "Rohre mit Dimension fließen in die Materialliste.",
        style = Ranzen.type.caption, color = c.inkMuted,
    )
    Spacer(Modifier.height(14.dp))
    ConfirmButton("Schema löschen", "Wirklich löschen?", onDelete)
}

/** A small heating circuit as a starting point: boiler, pump, MAG, valves, two radiators. */
private fun exampleSchema(base: Schema): Schema {
    fun id() = ShkFiles.newId()
    val boiler = SchemaSymbol(id(), SymbolType.BOILER, Pt(0.0, 0.0), label = "Kessel", props = mapOf("p" to "15"))
    val pump = SchemaSymbol(id(), SymbolType.PUMP, Pt(5.0, -1.0), label = "P1", props = mapOf("p" to "15", "l" to "60"))
    val sv = SchemaSymbol(id(), SymbolType.SAFETY_VALVE, Pt(3.0, -3.0), props = mapOf("p" to "15"))
    val mag = SchemaSymbol(id(), SymbolType.MAG, Pt(3.0, 4.0), label = "MAG", props = mapOf("va" to "150"))
    val r1 = SchemaSymbol(id(), SymbolType.RADIATOR, Pt(11.0, -4.0), label = "HK 1", props = mapOf("pn" to "1200"))
    val r2 = SchemaSymbol(id(), SymbolType.RADIATOR, Pt(16.0, -4.0), label = "HK 2", props = mapOf("pn" to "900"))
    var s = base.copy(symbols = listOf(boiler, pump, sv, mag, r1, r2))
    val ports = { sym: SchemaSymbol -> SchemaGeometry.ports(sym) }
    fun pipe(kind: PipeKind, a: Pt, b: Pt) { s = SchemaOps.addPipe(s, id(), kind, a, b, "cu22") }
    pipe(PipeKind.FLOW, ports(boiler)[0], ports(pump)[0])
    pipe(PipeKind.FLOW, ports(pump)[1], Pt(10.0, -1.0))
    pipe(PipeKind.FLOW, Pt(10.0, -1.0), ports(r1)[0])
    pipe(PipeKind.FLOW, Pt(10.0, -1.0), ports(r2)[0])
    pipe(PipeKind.RETURN, ports(r1)[1], Pt(12.0, 1.0))
    pipe(PipeKind.RETURN, ports(r2)[1], Pt(17.0, 1.0))
    pipe(PipeKind.RETURN, Pt(17.0, 1.0), ports(boiler)[1])
    pipe(PipeKind.FLOW, ports(sv)[0], Pt(3.0, -1.0))
    pipe(PipeKind.RETURN, ports(mag)[0], Pt(3.0, 1.0))
    // Sizing values for every pipe, so tapping one shows a result at once.
    s = s.copy(pipes = s.pipes.map { it.copy(props = mapOf("p" to "15", "dt" to "20")) })
    return s.copy(name = "Beispiel Heizkreis", labels = listOf(SchemaLabel(id(), Pt(8.0, -7.0), "Heizkreis mit zwei Heizkörpern")))
}
