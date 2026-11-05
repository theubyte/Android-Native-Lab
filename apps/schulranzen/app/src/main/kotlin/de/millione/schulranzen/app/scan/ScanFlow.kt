package de.millione.schulranzen.app.scan

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.app.AppIcons
import de.millione.schulranzen.app.data.Library
import de.millione.schulranzen.core.scan.ScanMode
import de.millione.schulranzen.core.scan.ScanPlan
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.design.TactileKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scan route, "scan" with optional "|nb:<id>" (notebook it was started from)
 * and "|after:<n>" (insert behind the first n pages).
 */
data class ScanRoute(val notebookId: String? = null, val after: Int? = null) {
    fun encode() = buildString {
        append("scan")
        notebookId?.let { append("|nb:").append(it) }
        after?.let { append("|after:").append(it) }
    }
    companion object {
        fun parse(r: String): ScanRoute {
            val parts = r.split("|")
            fun v(k: String) = parts.firstOrNull { it.startsWith("$k:") }?.substringAfter(":")
            return ScanRoute(v("nb"), v("after")?.toIntOrNull())
        }
    }
}

/**
 * Scanning from sheet to page: get sheets in, check them, choose the
 * notebook, confirm. Which step shows follows from the draft, so it is the
 * same after a process death. [onBack] null = no back key (start of a tab).
 */
@Composable
fun ScanFlow(
    store: ScanStore,
    controller: ScanController,
    library: Library,
    route: ScanRoute = ScanRoute(),
    onBack: (() -> Unit)? = null,
    onOpen: (notebookId: String, page: Int) -> Unit,
    /** Entry to the synchronisation, shown after a scan on a phone. */
    onSync: (() -> Unit)? = null,
) {
    val c = Ranzen.colors
    val sheets by store.sheets.collectAsState()
    val busy by store.busy.collectAsState()
    val message by store.message.collectAsState()
    var picking by rememberSaveable { mutableStateOf(false) }
    // Confirmation: notebook id, title, first page, count.
    var doneId by rememberSaveable { mutableStateOf<String?>(null) }
    var doneTitle by rememberSaveable { mutableStateOf("") }
    var doneFirst by rememberSaveable { mutableStateOf(0) }
    var doneCount by rememberSaveable { mutableStateOf(0) }
    var preview by remember { mutableStateOf<ScanSheet?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = preview != null || (picking && sheets.isNotEmpty()) || doneId != null) {
        when {
            preview != null -> preview = null
            doneId != null -> doneId = null
            else -> picking = false
        }
    }

    Box(Modifier.fillMaxSize().background(c.desk)) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                val id = doneId
                when {
                    id != null -> DoneStep(
                        doneTitle, doneFirst, doneCount, onSync,
                        onOpen = { doneId = null; onOpen(id, doneFirst) },
                        onAgain = { doneId = null },
                        onBack = onBack?.let { b -> { doneId = null; b() } },
                    )
                    sheets.isEmpty() -> SourceStep(controller, onBack)
                    !picking -> ReviewStep(store, sheets, controller, onBack, onPreview = { preview = it }, onNext = { picking = true })
                    else -> TargetStep(library, sheets.size, route, onBack = { picking = false }) { target ->
                        scope.launch {
                            store.commit(target)
                                .onSuccess { d ->
                                    picking = false
                                    doneTitle = d.title; doneFirst = d.firstIndex; doneCount = d.count; doneId = d.notebookId
                                }
                                .onFailure { store.say(it.message ?: "Einfügen hat nicht geklappt") }
                        }
                    }
                }
            }
        }
        preview?.let { s -> SheetPreview(store, s) { preview = null } }
        busy?.let { BusyOverlay(it) }
        message?.let { m ->
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, bottom = 92.dp), contentAlignment = Alignment.BottomCenter) {
                Text(m, style = Ranzen.type.label, color = c.paper, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.ink.copy(alpha = 0.92f)).padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun StepHeader(title: String, sub: String? = null, onBack: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    val c = Ranzen.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            TactileKey(onBack, contentDescription = "Zurück") { RanzenIcon(RanzenIcons.Back) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Ranzen.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = Ranzen.type.caption, color = c.inkMuted)
        }
        trailing()
    }
}

// ---------------------------------------------------------------- 1: source

@Composable
private fun SourceStep(controller: ScanController, onBack: (() -> Unit)?) {
    val c = Ranzen.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepHeader("Scannen", "Blätter werden zu Heftseiten", onBack)
        Spacer(Modifier.height(8.dp))
        PaperPanel(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), corner = 22.dp) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RanzenIcon(AppIcons.Scan, tint = c.signal, size = 56.dp)
                Spacer(Modifier.height(12.dp))
                Text("Blatt auf einen ruhigen Untergrund legen und von oben aufnehmen.", style = Ranzen.type.body, color = c.inkMuted)
                Spacer(Modifier.height(4.dp))
                Text("Kanten und Zuschnitt erkennt der Scanner, auch mehrere Seiten hintereinander.", style = Ranzen.type.body, color = c.inkMuted)
                Spacer(Modifier.height(18.dp))
                SignalButton(if (controller.starting) "Scanner startet …" else "Blätter scannen", controller.scan, Modifier.fillMaxWidth(), icon = AppIcons.Scan)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("ODER", style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(6.dp))
        SourceRow(RanzenIcons.Camera, "Foto aufnehmen", "Ohne Kantenerkennung", controller.photo)
        SourceRow(RanzenIcons.Image, "Aus der Galerie", "Auch mehrere Bilder", controller.gallery)
        SourceRow(RanzenIcons.Paper, "PDF-Datei", "Jede Seite wird ein Blatt", controller.pdf)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SourceRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val c = Ranzen.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RanzenIcon(icon)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = Ranzen.type.body, color = c.ink)
            Text(sub, style = Ranzen.type.caption, color = c.inkMuted)
        }
    }
}

// ---------------------------------------------------------------- 2: review

@Composable
private fun ReviewStep(
    store: ScanStore,
    sheets: List<ScanSheet>,
    controller: ScanController,
    onBack: (() -> Unit)?,
    onPreview: (ScanSheet) -> Unit,
    onNext: () -> Unit,
) {
    val c = Ranzen.colors
    val mode by store.mode.collectAsState()
    var confirmDiscard by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        StepHeader(ScanPlan.pages(sheets.size), "Reihenfolge prüfen", onBack) {
            QuietButton(if (confirmDiscard) "Wirklich verwerfen?" else "Verwerfen", {
                if (confirmDiscard) store.discard() else confirmDiscard = true
            }, color = c.signal)
        }
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanMode.entries.forEach { m ->
                val on = m == mode
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) c.raised else Color.Transparent)
                        .border(if (on) 1.5.dp else 1.dp, if (on) c.signal else c.line, RoundedCornerShape(12.dp))
                        .clickable { store.setMode(m) }.padding(horizontal = 4.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(m.label, style = Ranzen.type.caption, color = if (on) c.ink else c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(sheets, key = { _, s -> s.id }) { i, s ->
                SheetRow(store, s, i, sheets.size, mode, onPreview, Modifier.animateItem())
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietButton("Weitere scannen", controller.scan, color = c.ink)
            QuietButton("Foto", controller.photo, color = c.inkMuted)
            Spacer(Modifier.weight(1f))
            SignalButton("Weiter", onNext)
        }
    }
}

@Composable
private fun SheetRow(store: ScanStore, sheet: ScanSheet, index: Int, count: Int, mode: ScanMode, onPreview: (ScanSheet) -> Unit, modifier: Modifier = Modifier) {
    val c = Ranzen.colors
    PaperPanel(modifier.fillMaxWidth(), corner = 16.dp, depth = 0.5f) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SheetImage(
                store, sheet, mode, 220,
                Modifier.width(64.dp).aspectRatio(1 / 1.414f).clip(RoundedCornerShape(6.dp)).background(Color(0xFFFFFEFA))
                    .border(1.dp, c.line, RoundedCornerShape(6.dp)).clickable { onPreview(sheet) },
            )
            Spacer(Modifier.width(10.dp))
            Text("%02d".format(index + 1), style = Ranzen.type.mono, color = c.ink)
            Spacer(Modifier.weight(1f))
            RowKey(RanzenIcons.ArrowUp, "Nach vorn", enabled = index > 0) { store.move(index, index - 1) }
            RowKey(RanzenIcons.ArrowDown, "Nach hinten", enabled = index < count - 1) { store.move(index, index + 1) }
            RowKey(AppIcons.Turn, "Drehen") { store.turn(sheet.id) }
            RowKey(RanzenIcons.Trash, "Entfernen", tint = c.signal) { store.remove(sheet.id) }
        }
    }
}

@Composable
private fun RowKey(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color = Ranzen.colors.ink, onClick: () -> Unit) {
    val c = Ranzen.colors
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { RanzenIcon(icon, tint = if (enabled) tint else c.inkFaint.copy(alpha = 0.5f), size = 22.dp) }
}

@Composable
private fun SheetImage(store: ScanStore, sheet: ScanSheet, mode: ScanMode, maxLong: Int, modifier: Modifier, scale: ContentScale = ContentScale.Fit) {
    val bmp by produceState<Bitmap?>(null, sheet.file, sheet.rotation, mode, maxLong) {
        value = withContext(Dispatchers.Default) { ScanStore.preview(store.fileOf(sheet), sheet.rotation, mode, maxLong) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), "Blatt", Modifier.fillMaxSize(), contentScale = scale) }
    }
}

/** A sheet large, to check it can be read. Pinch zooms, tap closes. */
@Composable
private fun SheetPreview(store: ScanStore, sheet: ScanSheet, onClose: () -> Unit) {
    val c = Ranzen.colors
    val mode by store.mode.collectAsState()
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val long = with(LocalDensity.current) { 900.dp.roundToPx() }.coerceAtMost(1800)
    Box(
        Modifier.fillMaxSize().background(c.desk)
            .pointerInput(Unit) { detectTapGestures { onClose() } }
            .pointerInput(Unit) {
                detectTransformGestures { _, p, z, _ ->
                    zoom = (zoom * z).coerceIn(1f, 6f)
                    pan = if (zoom == 1f) Offset.Zero else pan + p
                }
            }
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        SheetImage(
            store, sheet, mode, long,
            Modifier.fillMaxSize().padding(8.dp).graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y },
        )
    }
}

// ---------------------------------------------------------------- 3: target

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetStep(library: Library, count: Int, route: ScanRoute, onBack: () -> Unit, onConfirm: (ScanTarget) -> Unit) {
    val c = Ranzen.colors
    val notebooks by library.notebooks.collectAsState(initial = emptyList())
    val subjects by library.subjects.collectAsState(initial = emptyList())
    var targetId by rememberSaveable { mutableStateOf(route.notebookId) }
    var wantsNew by rememberSaveable { mutableStateOf(false) }
    // With no notebook yet there is nothing else to choose.
    val creating = wantsNew || (notebooks.isEmpty() && targetId == null)
    var title by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf<Long?>(null) }
    var atEnd by rememberSaveable { mutableStateOf(route.after == null) }
    var after by rememberSaveable { mutableStateOf(route.after ?: 1) }
    var query by rememberSaveable { mutableStateOf("") }
    val target = notebooks.firstOrNull { it.id == targetId }
    val suggestion = remember { "Scan " + SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date()) }

    Column(Modifier.fillMaxSize()) {
        StepHeader("Wohin?", ScanPlan.pages(count) + " einfügen", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            PaperPanel(Modifier.fillMaxWidth(), corner = 16.dp, depth = 0.5f) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { wantsNew = true; targetId = null }.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RanzenIcon(RanzenIcons.Plus, tint = if (creating) c.signal else c.ink)
                        Spacer(Modifier.width(14.dp))
                        Text("Neues Heft", style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
                        if (creating) RanzenIcon(RanzenIcons.Check, tint = c.signal)
                    }
                    if (creating) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                        LineField(title, { title = it }, suggestion)
                        Spacer(Modifier.height(14.dp))
                        Text("FACH", style = Ranzen.type.caption, color = c.inkMuted)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            subjects.forEach { s ->
                                val on = s.id == subject
                                Row(
                                    Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(if (on) c.raised else Color.Transparent)
                                        .border(1.dp, if (on) SubjectPalette.of(s.colorIndex) else c.line, RoundedCornerShape(12.dp))
                                        .clickable { subject = if (on) null else s.id }.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Lamp(SubjectPalette.of(s.colorIndex), size = 8.dp, glow = on)
                                    Spacer(Modifier.width(8.dp))
                                    Text(s.name, style = Ranzen.type.label, color = if (on) c.ink else c.inkMuted)
                                }
                            }
                        }
                    }
                }
            }
            if (notebooks.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("VORHANDENES HEFT", style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.height(8.dp))
                if (notebooks.size > 6) { LineField(query, { query = it }, "Heft suchen"); Spacer(Modifier.height(12.dp)) }
                val q = query.trim().lowercase(Locale.GERMANY)
                val shown = notebooks.filter { q.isEmpty() || it.title.lowercase(Locale.GERMANY).contains(q) || it.id == targetId }
                val subjectOf = subjects.associateBy { it.id }
                PaperPanel(Modifier.fillMaxWidth(), corner = 16.dp, depth = 0.5f) {
                    Column {
                        shown.forEachIndexed { i, nb ->
                            val on = nb.id == targetId
                            if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.line))
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    targetId = nb.id; wantsNew = false
                                    // A position from another notebook means nothing here.
                                    if (nb.id != route.notebookId) { atEnd = true; after = nb.pageCount.coerceAtLeast(1) }
                                }.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val s = nb.subjectId?.let(subjectOf::get)
                                Lamp(s?.let { SubjectPalette.of(it.colorIndex) } ?: c.inkFaint, size = 10.dp, glow = on)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(nb.title, style = Ranzen.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text((s?.name ?: "Ohne Fach") + " · ${nb.pageCount} S.", style = Ranzen.type.caption, color = c.inkMuted)
                                }
                                if (on) RanzenIcon(RanzenIcons.Check, tint = c.signal)
                            }
                        }
                        if (shown.isEmpty()) Text("Nichts gefunden.", style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.padding(14.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        // The choice of position stays reachable above the long list.
        if (target != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                PlaceRow("Ans Ende", "Ab Seite ${target.pageCount + 1}", atEnd, { atEnd = true })
                val pages = target.pageCount.coerceAtLeast(1)
                val shownAfter = after.coerceIn(1, pages)
                PlaceRow("Hinter Seite $shownAfter", "Bestehende Seiten bleiben, wie sie sind", !atEnd, { atEnd = false }) {
                    TactileKey({ atEnd = false; after = (shownAfter - 1).coerceAtLeast(1) }, size = 44.dp, contentDescription = "Eine Seite früher") { Text("−", style = Ranzen.type.heading, color = c.ink) }
                    Spacer(Modifier.width(8.dp))
                    TactileKey({ atEnd = false; after = (shownAfter + 1).coerceAtMost(pages) }, size = 44.dp, contentDescription = "Eine Seite später") { RanzenIcon(RanzenIcons.Plus, size = 20.dp) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            if (creating || target != null) SignalButton(ScanPlan.pages(count) + " einfügen", {
                if (creating) onConfirm(ScanTarget.New(title.ifBlank { suggestion }, subject))
                else target?.let { t -> onConfirm(ScanTarget.Existing(t.id, if (atEnd) null else after.coerceIn(1, t.pageCount.coerceAtLeast(1)))) }
            })
            else Text("Heft wählen", style = Ranzen.type.label, color = c.inkMuted, modifier = Modifier.padding(vertical = 14.dp))
        }
    }
}

@Composable
private fun PlaceRow(title: String, sub: String, on: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val c = Ranzen.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Lamp(if (on) c.signal else c.line, size = 12.dp, glow = on)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Ranzen.type.label, color = c.ink)
            Text(sub, style = Ranzen.type.caption, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun LineField(value: String, onValue: (String) -> Unit, hint: String) {
    val c = Ranzen.colors
    BasicTextField(
        value, onValue, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        textStyle = Ranzen.type.body.copy(color = c.ink), cursorBrush = SolidColor(c.signal),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 10.dp)
            .drawBehind { drawLine(c.line, Offset(0f, size.height + 4.dp.toPx()), Offset(size.width, size.height + 4.dp.toPx()), 1.5.dp.toPx()) },
        decorationBox = { inner ->
            if (value.isEmpty()) Text(hint, style = Ranzen.type.body, color = c.inkFaint)
            inner()
        },
    )
}

// ---------------------------------------------------------------- 4: done

@Composable
private fun DoneStep(title: String, first: Int, count: Int, onSync: (() -> Unit)?, onOpen: () -> Unit, onAgain: () -> Unit, onBack: (() -> Unit)?) {
    val c = Ranzen.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepHeader("Eingefügt", null, onBack)
        Spacer(Modifier.height(8.dp))
        PaperPanel(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), corner = 22.dp) {
            Column(Modifier.padding(20.dp)) {
                RanzenIcon(RanzenIcons.Check, tint = c.signal, size = 40.dp)
                Spacer(Modifier.height(10.dp))
                Text(ScanPlan.pages(count) + " in „$title“", style = Ranzen.type.heading, color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text(if (count == 1) "Als Seite ${first + 1}" else "Ab Seite ${first + 1}", style = Ranzen.type.body, color = c.inkMuted)
                Spacer(Modifier.height(18.dp))
                SignalButton("Heft öffnen", onOpen, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                QuietButton("Noch ein Blatt scannen", onAgain, color = c.ink)
            }
        }
        if (onSync != null) {
            Spacer(Modifier.height(14.dp))
            PaperPanel(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onSync), corner = 16.dp, depth = 0.5f) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RanzenIcon(AppIcons.Sync)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Abgleichen", style = Ranzen.type.label, color = c.ink)
                        Text("Bringt die Seiten aufs Tablet", style = Ranzen.type.caption, color = c.inkMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun BusyOverlay(text: String) {
    val c = Ranzen.colors
    Box(Modifier.fillMaxSize().background(c.desk.copy(alpha = 0.55f)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        PaperPanel(Modifier.padding(24.dp), corner = 18.dp) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = c.signal, strokeWidth = 2.5.dp)
                Spacer(Modifier.width(14.dp))
                Text(text, style = Ranzen.type.label, color = c.ink)
            }
        }
    }
}
