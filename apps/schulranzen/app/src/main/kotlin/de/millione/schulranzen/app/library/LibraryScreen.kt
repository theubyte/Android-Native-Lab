package de.millione.schulranzen.app.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.app.data.Library
import de.millione.schulranzen.app.data.Notebook
import de.millione.schulranzen.app.data.Subject
import de.millione.schulranzen.app.notebook.PaperSwatch
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
import de.millione.schulranzen.design.softRaise
import de.millione.schulranzen.ink.PaperStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Sheet {
    data object None : Sheet
    data object New : Sheet
    data class Edit(val notebook: Notebook) : Sheet
    data object NewSubject : Sheet
}

/** Home: the satchel. Subjects as register tabs, notebooks as real notebooks. */
@Composable
fun LibraryScreen(library: Library, onOpen: (String) -> Unit, onToggleTheme: () -> Unit) {
    val c = Ranzen.colors
    val subjects by library.subjects.collectAsState(initial = emptyList())
    val notebooks by library.notebooks.collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) }
    var sheet by remember { mutableStateOf<Sheet>(Sheet.None) }
    val scope = rememberCoroutineScope()

    val filter: Long? = if (tab == 0) null else subjects.getOrNull(tab - 1)?.id
    val shown = notebooks.filter { filter == null || it.subjectId == filter }
    val subjectOf = subjects.associateBy { it.id }

    Box(Modifier.fillMaxSize().background(c.desk)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 40.dp)) {
            Spacer(Modifier.height(28.dp))
            Header(onToggleTheme)
            Spacer(Modifier.height(26.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.Bottom) {
                RegisterTabs(
                    tabs = listOf("Alle" to null) + subjects.map { it.name to SubjectPalette.of(it.colorIndex) },
                    selected = tab,
                    onSelect = { tab = it },
                )
                Box(
                    Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp)).clickable { sheet = Sheet.NewSubject }.padding(10.dp),
                ) { RanzenIcon(RanzenIcons.Plus, tint = c.inkMuted, size = 20.dp) }
            }
            // The open drawer of the satchel
            PaperPanel(Modifier.fillMaxSize().padding(bottom = 24.dp), corner = 26.dp) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(176.dp),
                    contentPadding = PaddingValues(28.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalArrangement = Arrangement.spacedBy(30.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Hefte", style = Ranzen.type.title, color = c.ink)
                            Spacer(Modifier.width(12.dp))
                            Text("%02d".format(shown.size), style = Ranzen.type.mono, color = c.inkMuted)
                            Spacer(Modifier.weight(1f))
                            SignalButton("Neues Heft", { sheet = Sheet.New }, icon = RanzenIcons.Plus)
                        }
                    }
                    items(shown, key = { it.id }) { nb ->
                        NotebookCover(nb, nb.subjectId?.let(subjectOf::get), onClick = { onOpen(nb.id) }, onLongClick = { sheet = Sheet.Edit(nb) })
                    }
                    item { NewTile { sheet = Sheet.New } }
                }
            }
        }

        // Sheets
        if (sheet != Sheet.None) {
            Box(Modifier.fillMaxSize().background(c.desk.copy(alpha = 0.55f)).pointerInput(Unit) { detectTapGestures { sheet = Sheet.None } })
        }
        val current = sheet
        AnimatedVisibility(
            current is Sheet.New || current is Sheet.Edit, Modifier.align(Alignment.Center),
            enter = fadeIn() + scaleIn(spring(dampingRatio = 0.7f), initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.95f),
        ) {
            val editing = (current as? Sheet.Edit)?.notebook
            NotebookSheet(
                subjects = subjects,
                editing = editing,
                presetSubject = filter,
                onCancel = { sheet = Sheet.None },
                onTrash = editing?.let { { scope.launch { library.trash(it) }; sheet = Sheet.None } },
                onSave = { title, subject, paper ->
                    scope.launch {
                        if (editing != null) library.rename(editing, title, subject)
                        else onOpen(library.createNotebook(title, subject, paper))
                    }
                    sheet = Sheet.None
                },
            )
        }
        AnimatedVisibility(
            current is Sheet.NewSubject, Modifier.align(Alignment.Center),
            enter = fadeIn() + scaleIn(spring(dampingRatio = 0.7f), initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.95f),
        ) {
            SubjectSheet(nextColor = subjects.size, onCancel = { sheet = Sheet.None }) { name, color ->
                scope.launch { library.addSubject(name, color, subjects.size) }
                sheet = Sheet.None
            }
        }
    }
}

@Composable
private fun Header(onToggleTheme: () -> Unit) {
    val c = Ranzen.colors
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = Date() } }
    val day = remember(now) { SimpleDateFormat("EEEE · dd.MM.yyyy", Locale.GERMANY).format(now).uppercase(Locale.GERMANY) }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.GERMANY).format(now) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Schulranzen", style = Ranzen.type.hero, color = c.ink)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lamp(c.signal, size = 8.dp)
                Spacer(Modifier.width(10.dp))
                Text(day, style = Ranzen.type.mono, color = c.inkMuted)
            }
        }
        // A small retro display for the time
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).background(c.ink.copy(alpha = if (c.isDark) 0.08f else 0.88f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(time, style = Ranzen.type.monoLarge, color = if (c.isDark) c.ink else c.paper) }
        Spacer(Modifier.width(16.dp))
        TactileKey(onToggleTheme, contentDescription = "Hell / Dunkel") { RanzenIcon(RanzenIcons.Moon) }
    }
}

/**
 * A school notebook seen from the front: cloth spine in the subject colour,
 * a label plate with the title, page count and date in small type.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookCover(nb: Notebook, subject: Subject?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Ranzen.colors
    val accent = subject?.let { SubjectPalette.of(it.colorIndex) } ?: c.inkFaint
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "s")
    val depth by animateFloatAsState(if (pressed) 0.3f else 1f, label = "d")
    val shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 16.dp, bottomEnd = 16.dp)
    val date = remember(nb.updatedAt) { SimpleDateFormat("dd.MM.", Locale.GERMANY).format(Date(nb.updatedAt)) }
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .scale(s)
                .softRaise(16.dp, depth, c.bevelLight, c.bevelShadow)
                .clip(shape)
                .background(c.raised)
                .combinedClickable(source, indication = null, onClick = onClick, onLongClick = onLongClick)
                .drawBehind {
                    // spine with stitching
                    val spine = 16.dp.toPx()
                    drawRect(accent, size = androidx.compose.ui.geometry.Size(spine, size.height))
                    drawRect(Color.Black.copy(alpha = 0.10f), topLeft = Offset(spine, 0f), size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
                    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                    drawLine(Color.White.copy(alpha = 0.55f), Offset(spine / 2, 8.dp.toPx()), Offset(spine / 2, size.height - 8.dp.toPx()), 1.2.dp.toPx(), pathEffect = dash)
                    // fine horizontal lines like pressed board
                    var y = 14.dp.toPx()
                    while (y < size.height) {
                        drawLine(c.line.copy(alpha = 0.25f), Offset(spine + 6.dp.toPx(), y), Offset(size.width, y), 0.6.dp.toPx())
                        y += 9.dp.toPx()
                    }
                },
        ) {
            // label plate
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 30.dp, end = 14.dp, top = 30.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.paper)
                    .border(1.dp, c.line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(nb.title, style = Ranzen.type.heading, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                Spacer(Modifier.height(6.dp))
                Text(subject?.name ?: "Ohne Fach", style = Ranzen.type.caption, color = c.inkMuted)
            }
            Row(Modifier.align(Alignment.BottomStart).padding(start = 30.dp, bottom = 14.dp, end = 14.dp)) {
                Text("${nb.pageCount} S.", style = Ranzen.type.mono, color = c.inkMuted)
                Spacer(Modifier.weight(1f))
                Text(date, style = Ranzen.type.mono, color = c.inkFaint)
            }
        }
    }
}

@Composable
private fun NewTile(onClick: () -> Unit) {
    val c = Ranzen.colors
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    c.inkFaint, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RanzenIcon(RanzenIcons.Plus, tint = c.inkMuted, size = 30.dp)
            Spacer(Modifier.height(8.dp))
            Text("Neues Heft", style = Ranzen.type.label, color = c.inkMuted)
        }
    }
}

@Composable
private fun NotebookSheet(
    subjects: List<Subject>,
    editing: Notebook?,
    presetSubject: Long?,
    onCancel: () -> Unit,
    onTrash: (() -> Unit)?,
    onSave: (String, Long?, PaperStyle) -> Unit,
) {
    val c = Ranzen.colors
    var title by remember(editing) { mutableStateOf(editing?.title ?: "") }
    var subject by remember(editing) { mutableStateOf(editing?.subjectId ?: presetSubject ?: subjects.firstOrNull()?.id) }
    var paper by remember(editing) { mutableStateOf(PaperStyle.of(editing?.paper)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    PaperPanel(corner = 26.dp) {
        Column(Modifier.padding(30.dp).width(520.dp)) {
            Text(if (editing == null) "Neues Heft" else "Heft bearbeiten", style = Ranzen.type.title, color = c.ink)
            Spacer(Modifier.height(22.dp))
            Text("TITEL", style = Ranzen.type.caption, color = c.inkMuted)
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                title, { title = it }, singleLine = true,
                textStyle = Ranzen.type.title.copy(color = c.ink),
                cursorBrush = SolidColor(c.signal),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .drawBehind { drawLine(c.line, Offset(0f, size.height + 6.dp.toPx()), Offset(size.width, size.height + 6.dp.toPx()), 1.5.dp.toPx()) },
                decorationBox = { inner ->
                    if (title.isEmpty()) Text("z. B. Mathe Klasse 11", style = Ranzen.type.title, color = c.inkFaint)
                    inner()
                },
            )
            Spacer(Modifier.height(26.dp))
            Text("FACH", style = Ranzen.type.caption, color = c.inkMuted)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                subjects.forEach { s ->
                    val active = s.id == subject
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (active) c.raised else Color.Transparent)
                            .border(1.dp, if (active) SubjectPalette.of(s.colorIndex) else c.line, RoundedCornerShape(12.dp))
                            .clickable { subject = s.id }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Lamp(SubjectPalette.of(s.colorIndex), size = 8.dp, glow = active)
                        Spacer(Modifier.width(8.dp))
                        Text(s.name, style = Ranzen.type.label, color = if (active) c.ink else c.inkMuted)
                    }
                }
            }
            if (editing == null) {
                Spacer(Modifier.height(26.dp))
                Text("PAPIER", style = Ranzen.type.caption, color = c.inkMuted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PaperStyle.entries.forEach { style ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { paper = style }) {
                            PaperSwatch(style, selected = style == paper)
                            Spacer(Modifier.height(6.dp))
                            Text(style.label, style = Ranzen.type.caption, color = if (style == paper) c.ink else c.inkMuted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onTrash != null) QuietButton("In den Papierkorb", onTrash, color = c.signal)
                Spacer(Modifier.weight(1f))
                QuietButton("Abbrechen", onCancel)
                Spacer(Modifier.width(10.dp))
                SignalButton(if (editing == null) "Anlegen" else "Speichern", { onSave(title, subject, paper) })
            }
        }
    }
}

@Composable
private fun SubjectSheet(nextColor: Int, onCancel: () -> Unit, onSave: (String, Int) -> Unit) {
    val c = Ranzen.colors
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(nextColor % SubjectPalette.colors.size) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    PaperPanel(corner = 26.dp) {
        Column(Modifier.padding(30.dp).width(440.dp)) {
            Text("Neues Fach", style = Ranzen.type.title, color = c.ink)
            Spacer(Modifier.height(20.dp))
            BasicTextField(
                name, { name = it }, singleLine = true,
                textStyle = Ranzen.type.title.copy(color = c.ink), cursorBrush = SolidColor(c.signal),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .drawBehind { drawLine(c.line, Offset(0f, size.height + 6.dp.toPx()), Offset(size.width, size.height + 6.dp.toPx()), 1.5.dp.toPx()) },
                decorationBox = { inner ->
                    if (name.isEmpty()) Text("z. B. Physik", style = Ranzen.type.title, color = c.inkFaint)
                    inner()
                },
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SubjectPalette.colors.forEachIndexed { i, col ->
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .border(if (i == color) 2.dp else 0.dp, if (i == color) c.ink else Color.Transparent, CircleShape)
                            .clickable { color = i },
                        contentAlignment = Alignment.Center,
                    ) { Lamp(col, size = 22.dp, glow = i == color) }
                }
            }
            Spacer(Modifier.height(28.dp))
            Row {
                Spacer(Modifier.weight(1f))
                QuietButton("Abbrechen", onCancel)
                Spacer(Modifier.width(10.dp))
                SignalButton("Anlegen", { if (name.isNotBlank()) onSave(name, color) })
            }
        }
    }
}
