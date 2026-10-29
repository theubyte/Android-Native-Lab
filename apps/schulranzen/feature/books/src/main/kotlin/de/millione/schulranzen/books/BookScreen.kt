package de.millione.schulranzen.books

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.millione.schulranzen.core.books.PageJump
import de.millione.schulranzen.design.InkPalette
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.TactileKey
import de.millione.schulranzen.ink.Brushes
import java.io.File
import kotlin.math.roundToInt

private enum class Panel { NONE, STYLE, JUMP, CONTENTS }

/**
 * Reads a book: the PDF with ink, notes, bookmarks and marked areas.
 * [onClip] gets a PNG under `filesDir/images/` when the reader takes a
 * clipping into a notebook.
 */
@Composable
fun BookScreen(bookId: String, onBack: () -> Unit, onClip: (File) -> Unit) {
    val context = LocalContext.current
    val c = Ranzen.colors
    val view = remember(bookId) { BookCanvasView(context) }
    val session = remember(bookId) { BookSession(context.applicationContext, bookId, view) }
    DisposableEffect(session) {
        session.open()
        onDispose { session.close() }
    }
    val desk = c.desk.toArgb(); val signal = c.signal.toArgb()
    SideEffect { view.deskColor = desk; view.accentColor = signal }
    var panel by remember { mutableStateOf(Panel.NONE) }
    BackHandler {
        when {
            session.noteDraft != null -> session.cancelNote()
            panel != Panel.NONE -> panel = Panel.NONE
            session.areaFrame != null -> session.cancelArea()
            else -> onBack()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.desk)) {
        val landscape = maxWidth > maxHeight
        val safe = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)

        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().penHover(view))

        if (!session.loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PaperPanel(corner = 20.dp) {
                    Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(session.error ?: "Buch wird geöffnet …", style = Ranzen.type.heading, color = c.ink)
                        if (session.error != null) {
                            Spacer(Modifier.height(14.dp))
                            QuietButton("Zurück", onBack)
                        }
                    }
                }
            }
        }

        if (session.loaded) {
            TopBar(session, safe.align(Alignment.TopCenter).padding(top = 14.dp)) {
                panel = if (panel == Panel.JUMP) Panel.NONE else Panel.JUMP
            }
            val railModifier = if (landscape) safe.padding(start = 14.dp).align(Alignment.CenterStart)
                               else safe.padding(top = 84.dp).align(Alignment.TopCenter)
            Rail(session, vertical = landscape, modifier = railModifier,
                onStyle = { panel = if (panel == Panel.STYLE) Panel.NONE else Panel.STYLE },
                onContents = { panel = if (panel == Panel.CONTENTS) Panel.NONE else Panel.CONTENTS },
                onBack = onBack)
        }

        session.areaFrame?.let { frame ->
            if (panel == Panel.NONE) AreaBar(frame, onClip = { session.clipArea(onClip) }, onKeep = session::keepArea, onCancel = session::cancelArea)
        }

        // Panels
        if (panel != Panel.NONE) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { panel = Panel.NONE } })
        }
        val popIn = fadeIn() + scaleIn(spring(dampingRatio = 0.7f), initialScale = 0.92f)
        val popOut = fadeOut() + scaleOut(targetScale = 0.95f)
        val sideAlign = if (landscape) Modifier.align(Alignment.CenterStart).then(safe).padding(start = 92.dp)
                        else Modifier.align(Alignment.TopCenter).then(safe).padding(top = 160.dp)
        AnimatedVisibility(panel == Panel.STYLE, sideAlign, enter = popIn, exit = popOut) { StylePanel(session) }
        AnimatedVisibility(panel == Panel.JUMP, Modifier.align(Alignment.TopCenter).then(safe).padding(top = if (landscape) 84.dp else 160.dp),
            enter = popIn, exit = popOut) {
            JumpPanel(session) { panel = Panel.NONE }
        }
        AnimatedVisibility(panel == Panel.CONTENTS, Modifier.align(Alignment.CenterEnd),
            enter = fadeIn() + slideInHorizontally(spring(dampingRatio = 0.8f)) { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 }) {
            ContentsPanel(session, Modifier.then(safe).padding(14.dp).fillMaxHeight().width(400.dp)) { panel = Panel.NONE }
        }

        session.noteDraft?.let { draft ->
            Box(Modifier.fillMaxSize().background(c.desk.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { } })
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)).padding(24.dp), contentAlignment = Alignment.Center) {
                NoteEditor(session, draft)
            }
        }

        session.toast?.let { msg ->
            Box(safe.fillMaxSize().padding(bottom = 28.dp), contentAlignment = Alignment.BottomCenter) {
                Text(
                    msg, style = Ranzen.type.label, color = c.paper,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.ink.copy(alpha = 0.9f)).padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Pen hover reaches Compose first; pass it on for the hover dot and palm rejection. */
private fun Modifier.penHover(view: BookCanvasView) = pointerInput(view) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val ch = ev.changes.firstOrNull() ?: continue
            if (ch.type != PointerType.Stylus && ch.type != PointerType.Eraser) continue
            when (ev.type) {
                PointerEventType.Enter, PointerEventType.Move -> if (!ch.pressed) view.penHover(ch.position.x, ch.position.y)
                PointerEventType.Exit -> view.penHoverExit()
            }
        }
    }
}

@Composable
private fun TopBar(session: BookSession, modifier: Modifier, onJump: () -> Unit) {
    val c = Ranzen.colors
    PaperPanel(modifier, corner = 16.dp, depth = 0.6f) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(session.title, style = Ranzen.type.label, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp))
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onJump).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("${session.page + 1} / ${session.pageCount}", style = Ranzen.type.mono, color = c.ink)
            }
            Spacer(Modifier.width(4.dp))
            val marked = session.currentBookmarked
            TactileKey({ session.toggleBookmark() }, selected = marked, contentDescription = "Lesezeichen") {
                RanzenIcon(RanzenIcons.Bookmark, tint = if (marked) c.signal else c.ink)
            }
            Spacer(Modifier.width(6.dp))
            TactileKey(session::rotateCurrent, contentDescription = "Seite drehen") { RanzenIcon(BookIcons.Rotate) }
        }
    }
}

@Composable
private fun Rail(
    session: BookSession,
    vertical: Boolean,
    modifier: Modifier,
    onStyle: () -> Unit,
    onContents: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Ranzen.colors
    @Composable
    fun toolKey(t: BookTool, icon: ImageVector, name: String, onClick: () -> Unit = { session.select(t) }, active: Boolean = session.tool == t) {
        TactileKey(onClick, selected = active, contentDescription = name) {
            RanzenIcon(icon, tint = if (active) c.signal else c.ink)
        }
    }
    @Composable
    fun items() {
        TactileKey(onBack, contentDescription = "Zurück") { RanzenIcon(RanzenIcons.Back) }
        Gap(vertical, 12)
        TactileKey(session::undo, contentDescription = "Rückgängig") {
            RanzenIcon(RanzenIcons.Undo, tint = if (session.canUndo) c.ink else c.inkFaint)
        }
        Gap(vertical, 6)
        TactileKey(session::redo, contentDescription = "Wiederholen") {
            RanzenIcon(RanzenIcons.Redo, tint = if (session.canRedo) c.ink else c.inkFaint)
        }
        Gap(vertical, 12)
        toolKey(BookTool.READ, BookIcons.Read, "Lesen")
        Gap(vertical, 6)
        toolKey(BookTool.PEN, RanzenIcons.Pen, "Stift")
        Gap(vertical, 6)
        toolKey(BookTool.MARKER, RanzenIcons.Marker, "Marker")
        Gap(vertical, 6)
        // Second tap switches between partial and whole-stroke erasing.
        toolKey(BookTool.ERASER, if (session.tool == BookTool.STROKE_ERASER) RanzenIcons.Trash else RanzenIcons.Eraser, "Radierer",
            onClick = { session.select(if (session.tool == BookTool.ERASER) BookTool.STROKE_ERASER else BookTool.ERASER) },
            active = session.tool.erases)
        Gap(vertical, 6)
        val marker = session.tool == BookTool.MARKER
        TactileKey(onStyle, contentDescription = "Farbe und Dicke") {
            val size = if (marker) 18.dp else (6 + session.penSize * 1.6f).dp.coerceAtMost(20.dp)
            Box(Modifier.size(size).clip(CircleShape).background(Color(if (marker) session.markerColor else session.penColor)))
        }
        Gap(vertical, 12)
        toolKey(BookTool.AREA, BookIcons.Area, "Bereich: Ausschnitt oder Markierung")
        Gap(vertical, 6)
        toolKey(BookTool.NOTE, BookIcons.Note, "Notiz")
        Gap(vertical, 12)
        TactileKey(onContents, contentDescription = "Inhalt und Seiten") { RanzenIcon(BookIcons.Contents) }
    }
    PaperPanel(modifier, corner = 26.dp, depth = 0.8f) {
        if (vertical) Column(Modifier.padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) { items() }
        else Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) { items() }
    }
}

@Composable
private fun Gap(vertical: Boolean, dp: Int) = Spacer(if (vertical) Modifier.height(dp.dp) else Modifier.width(dp.dp))

/** Into the notebook, keep as marked area, or drop – right above the rectangle. */
@Composable
private fun AreaBar(frame: android.graphics.RectF, onClip: () -> Unit, onKeep: () -> Unit, onCancel: () -> Unit) {
    val c = Ranzen.colors
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val gap = with(density) { 14.dp.toPx() }
        val above = frame.top - size.height - gap
        val y = if (above > gap * 6) above else (frame.bottom + gap).coerceAtMost(h - size.height - gap)
        val x = (frame.centerX() - size.width / 2).coerceIn(gap, (w - size.width - gap).coerceAtLeast(gap))
        PaperPanel(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.onSizeChanged { size = it }, corner = 16.dp) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton(RanzenIcons.Scissors, "Ins Heft", onClip, tint = c.signal)
                BarButton(BookIcons.Star, "Merken", onKeep)
                BarButton(RanzenIcons.Close, "Abbrechen", onCancel)
            }
        }
    }
}

@Composable
internal fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Ranzen.colors.ink) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RanzenIcon(icon, tint = tint, size = 22.dp)
        Spacer(Modifier.height(4.dp))
        Text(label, style = Ranzen.type.caption, color = tint)
    }
}

@Composable
private fun StylePanel(session: BookSession) {
    val c = Ranzen.colors
    val marker = session.tool == BookTool.MARKER
    PaperPanel(corner = 22.dp) {
        Column(Modifier.padding(22.dp).width(300.dp)) {
            Text(if (marker) "Marker" else "Stift", style = Ranzen.type.heading, color = c.ink)
            Spacer(Modifier.height(16.dp))
            Text("FARBE", style = Ranzen.type.caption, color = c.inkMuted)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val colors = if (marker) InkPalette.marker else InkPalette.pen
                val current = if (marker) session.markerColor else session.penColor
                colors.forEach { argb ->
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .border(if (argb == current) 2.dp else 0.dp, if (argb == current) c.ink else Color.Transparent, CircleShape)
                            .clickable { if (marker) session.setMarker(argb) else session.setPen(argb) },
                        contentAlignment = Alignment.Center,
                    ) { Lamp(Color(argb), size = 26.dp) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("DICKE", style = Ranzen.type.caption, color = c.inkMuted)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val sizes = if (marker) Brushes.markerSizes else Brushes.penSizes
                val current = if (marker) session.markerSize else session.penSize
                sizes.forEach { s ->
                    TactileKey(
                        { if (marker) session.setMarker(session.markerColor, s) else session.setPen(session.penColor, s) },
                        selected = s == current, size = 48.dp,
                    ) {
                        val dot = if (marker) (6 + s * 0.7f).dp else (4 + s * 2.2f).dp
                        Box(Modifier.size(dot).clip(CircleShape).background(Color(if (marker) session.markerColor else session.penColor)))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            var buttonErases by remember { mutableStateOf(session.buttonErases) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Seitentaste radiert", style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
                Switch(buttonErases, { buttonErases = it; session.buttonErases = it }, colors = SwitchDefaults.colors(
                    checkedTrackColor = c.signal, checkedThumbColor = c.onSignal, checkedBorderColor = c.signal,
                    uncheckedTrackColor = c.line.copy(alpha = 0.5f), uncheckedThumbColor = c.inkFaint, uncheckedBorderColor = c.line,
                ))
            }
        }
    }
}

@Composable
private fun JumpPanel(session: BookSession, onClose: () -> Unit) {
    val c = Ranzen.colors
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = {
        val target = PageJump.parse(text, session.pageCount)
        if (target != null) { session.goTo(target); onClose() } else session.say("Seite 1 bis ${session.pageCount}")
    }
    PaperPanel(corner = 22.dp) {
        Column(Modifier.padding(22.dp).width(340.dp)) {
            Text("Gehe zu Seite", style = Ranzen.type.heading, color = c.ink)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    text, { v -> text = v.filter { it.isDigit() }.take(5) },
                    textStyle = Ranzen.type.monoLarge.copy(color = c.ink),
                    singleLine = true,
                    cursorBrush = SolidColor(c.signal),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.width(150.dp).focusRequester(focus).clip(RoundedCornerShape(12.dp))
                        .background(c.raised).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text("von ${session.pageCount}", style = Ranzen.type.mono, color = c.inkMuted)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietButton("Anfang", { session.goTo(0); onClose() })
                QuietButton("Ende", { session.goTo(session.pageCount - 1); onClose() })
                Spacer(Modifier.weight(1f))
                SignalButton("Springen", { submit() })
            }
        }
    }
}

@Composable
private fun NoteEditor(session: BookSession, draft: NoteDraft) {
    val c = Ranzen.colors
    var text by remember(draft) { mutableStateOf(draft.old?.text ?: "") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(draft) { runCatching { focus.requestFocus() } }
    PaperPanel(corner = 24.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(26.dp).width(520.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RanzenIcon(BookIcons.Note, tint = Color(0xFFC99A2E))
                Spacer(Modifier.width(10.dp))
                Text("Notiz", style = Ranzen.type.title, color = c.ink)
                Spacer(Modifier.weight(1f))
                Text("Seite ${draft.page + 1}", style = Ranzen.type.mono, color = c.inkMuted)
            }
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                text, { text = it },
                textStyle = Ranzen.type.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.signal),
                modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp).focusRequester(focus)
                    .clip(RoundedCornerShape(12.dp)).background(c.raised).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(14.dp),
            )
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                draft.old?.let { old -> QuietButton("Löschen", { session.deleteNote(old.id) }, color = c.signal) }
                Spacer(Modifier.weight(1f))
                QuietButton("Abbrechen", session::cancelNote)
                Spacer(Modifier.width(10.dp))
                SignalButton("Fertig", { session.commitNote(text) })
            }
        }
    }
}
