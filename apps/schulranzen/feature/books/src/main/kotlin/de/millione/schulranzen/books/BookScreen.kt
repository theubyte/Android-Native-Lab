package de.millione.schulranzen.books

import android.graphics.RectF
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import de.millione.schulranzen.design.TextFamily
import de.millione.schulranzen.ink.Brushes
import de.millione.schulranzen.ink.TextItem
import java.io.File
import kotlin.math.roundToInt

private enum class Panel { NONE, STYLE, JUMP, CONTENTS, SEARCH, MORE }

/**
 * Reads a book full screen: the PDF with ink, text, notes, bookmarks and
 * marked areas. [onClip] gets a PNG under `filesDir/images/` when the
 * reader takes a clipping into a notebook. [onMarkForExam] (optional) adds
 * "Für Prüfung merken" for the page or a marked area. [startPage] and
 * [highlight] (unrotated page units, as in [BookLink]) jump into the book.
 */
@Composable
fun BookScreen(
    bookId: String,
    onBack: () -> Unit,
    onClip: (File) -> Unit,
    onMarkForExam: ((BookLink) -> Unit)? = null,
    startPage: Int? = null,
    highlight: RectF? = null,
) = BookReader(bookId, Modifier.fillMaxSize(), onClip, onBack, onMarkForExam, startPage, highlight, embedded = false)

/**
 * The same reader for "book and notebook side by side": no full-screen
 * chrome, one compact toolbar on top, made for half the screen (about
 * 600–1000 dp wide) at full height. Touches are handled only inside its
 * own bounds, so a notebook next to it keeps its pen and hand. The host
 * pads it for system bars. [onClose] is the close key in the toolbar.
 */
@Composable
fun BookPane(
    bookId: String,
    modifier: Modifier = Modifier,
    onClip: (File) -> Unit,
    onClose: () -> Unit,
    onMarkForExam: ((BookLink) -> Unit)? = null,
    startPage: Int? = null,
    highlight: RectF? = null,
) = BookReader(bookId, modifier, onClip, onClose, onMarkForExam, startPage, highlight, embedded = true)

@Composable
private fun BookReader(
    bookId: String,
    modifier: Modifier,
    onClip: (File) -> Unit,
    onClose: () -> Unit,
    onMarkForExam: ((BookLink) -> Unit)?,
    startPage: Int?,
    highlight: RectF?,
    embedded: Boolean,
) {
    val context = LocalContext.current
    val c = Ranzen.colors
    val view = remember(bookId) { BookCanvasView(context) }
    val session = remember(bookId) { BookSession(context.applicationContext, bookId, view) }
    DisposableEffect(session) {
        session.open()
        onDispose { session.close() }
    }
    LaunchedEffect(session, startPage, highlight) { if (startPage != null) session.openAt(startPage, highlight) }
    val desk = c.desk.toArgb(); val signal = c.signal.toArgb()
    SideEffect { view.deskColor = desk; view.accentColor = signal }
    var panel by remember { mutableStateOf(Panel.NONE) }
    val busy = session.noteDraft != null || session.textDraft != null || panel != Panel.NONE ||
        session.areaFrame != null || session.selectionFrame != null
    // Embedded, back only closes what is open here; the host decides about the pane.
    BackHandler(enabled = !embedded || busy) {
        when {
            session.textDraft != null -> session.cancelText()
            session.noteDraft != null -> session.cancelNote()
            panel != Panel.NONE -> panel = Panel.NONE
            session.areaFrame != null -> session.cancelArea()
            session.selectionFrame != null -> session.clearSelection()
            else -> onClose()
        }
    }
    val toggle = { p: Panel -> panel = if (panel == p) Panel.NONE else p }

    BoxWithConstraints(modifier.clipToBounds().background(c.desk)) {
        val landscape = !embedded && maxWidth > maxHeight
        val safe = if (embedded) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
        val sheetWidth = 400.dp.coerceAtMost(maxWidth - 28.dp)

        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().penHover(view))

        if (!session.loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PaperPanel(corner = 20.dp) {
                    Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(session.error ?: "Buch wird geöffnet …", style = Ranzen.type.heading, color = c.ink)
                        if (session.error != null) {
                            Spacer(Modifier.height(14.dp))
                            QuietButton(if (embedded) "Schließen" else "Zurück", onClose)
                        }
                    }
                }
            }
        }

        if (session.loaded) {
            if (embedded) {
                PaneBar(session, Modifier.align(Alignment.TopCenter).padding(horizontal = 10.dp, vertical = 10.dp),
                    onClose = onClose, onPanel = toggle, panel = panel)
            } else {
                TopBar(session, safe.align(Alignment.TopCenter).padding(top = 14.dp), onMarkForExam,
                    onJump = { toggle(Panel.JUMP) }, onSearch = { toggle(Panel.SEARCH) })
                val railModifier = if (landscape) safe.padding(start = 14.dp).align(Alignment.CenterStart)
                                   else safe.padding(top = 84.dp).align(Alignment.TopCenter)
                Rail(session, vertical = landscape, modifier = railModifier,
                    onStyle = { toggle(Panel.STYLE) }, onContents = { toggle(Panel.CONTENTS) }, onBack = onClose)
            }
        }

        if (panel == Panel.NONE) {
            session.areaFrame?.let { frame ->
                FloatingBar(frame) {
                    BarButton(RanzenIcons.Scissors, "Ins Heft", { session.clipArea(onClip) }, tint = c.signal)
                    BarButton(BookIcons.Star, "Merken", session::keepArea)
                    if (onMarkForExam != null) BarButton(BookIcons.Exam, "Prüfung", { session.markForExam(onMarkForExam, area = true) })
                    BarButton(RanzenIcons.Close, "Abbrechen", session::cancelArea)
                }
            }
            session.selectionFrame?.let { frame -> SelectionBar(session, frame) }
        }

        // Panels
        if (panel != Panel.NONE) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { panel = Panel.NONE } })
        }
        val popIn = fadeIn() + scaleIn(spring(dampingRatio = 0.7f), initialScale = 0.92f)
        val popOut = fadeOut() + scaleOut(targetScale = 0.95f)
        val topGap = when { embedded -> 80.dp; landscape -> 84.dp; else -> 160.dp }
        val sideAlign = when {
            embedded -> Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = topGap)
            landscape -> Modifier.align(Alignment.CenterStart).then(safe).padding(start = 92.dp)
            else -> Modifier.align(Alignment.TopCenter).then(safe).padding(top = topGap)
        }
        AnimatedVisibility(panel == Panel.STYLE, sideAlign, enter = popIn, exit = popOut) { StylePanel(session) }
        AnimatedVisibility(panel == Panel.JUMP, Modifier.align(Alignment.TopCenter).then(safe).padding(top = topGap),
            enter = popIn, exit = popOut) {
            JumpPanel(session) { panel = Panel.NONE }
        }
        AnimatedVisibility(panel == Panel.MORE, Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = topGap),
            enter = popIn, exit = popOut) {
            MorePanel(session, onMarkForExam, onPanel = { panel = it }, onClose = { panel = Panel.NONE })
        }
        val slideIn = fadeIn() + slideInHorizontally(spring(dampingRatio = 0.8f)) { it / 3 }
        val slideOut = fadeOut() + slideOutHorizontally { it / 3 }
        val sheet = Modifier.then(safe).padding(if (embedded) 10.dp else 14.dp).fillMaxHeight().width(sheetWidth)
        AnimatedVisibility(panel == Panel.CONTENTS, Modifier.align(Alignment.CenterEnd), enter = slideIn, exit = slideOut) {
            ContentsPanel(session, sheet) { panel = Panel.NONE }
        }
        AnimatedVisibility(panel == Panel.SEARCH, Modifier.align(Alignment.CenterEnd), enter = slideIn, exit = slideOut) {
            SearchPanel(session, sheet) { panel = Panel.NONE }
        }

        val dimmed = session.noteDraft != null || session.textDraft != null
        if (dimmed) {
            Box(Modifier.fillMaxSize().background(c.desk.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { } })
            val insets = if (embedded) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
            Box(Modifier.fillMaxSize().then(insets).padding(if (embedded) 14.dp else 24.dp), contentAlignment = Alignment.Center) {
                session.noteDraft?.let { NoteEditor(session, it) }
                session.textDraft?.let { TextEditor(session, it) }
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
private fun PageButton(session: BookSession, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("${session.page + 1} / ${session.pageCount}", style = Ranzen.type.mono, color = Ranzen.colors.ink, maxLines = 1)
    }
}

@Composable
private fun TopBar(session: BookSession, modifier: Modifier, onMarkForExam: ((BookLink) -> Unit)?, onJump: () -> Unit, onSearch: () -> Unit) {
    val c = Ranzen.colors
    PaperPanel(modifier, corner = 16.dp, depth = 0.6f) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(session.title, style = Ranzen.type.label, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp))
            Spacer(Modifier.width(8.dp))
            PageButton(session, onJump)
            Spacer(Modifier.width(4.dp))
            TactileKey(onSearch, contentDescription = "Im Buch suchen") { RanzenIcon(RanzenIcons.Search) }
            Spacer(Modifier.width(6.dp))
            val marked = session.currentBookmarked
            TactileKey({ session.toggleBookmark() }, selected = marked, contentDescription = "Lesezeichen") {
                RanzenIcon(RanzenIcons.Bookmark, tint = if (marked) c.signal else c.ink)
            }
            Spacer(Modifier.width(6.dp))
            TactileKey(session::rotateCurrent, contentDescription = "Seite drehen") { RanzenIcon(BookIcons.Rotate) }
            if (onMarkForExam != null) {
                Spacer(Modifier.width(6.dp))
                TactileKey({ session.markForExam(onMarkForExam, area = false) }, contentDescription = "Seite für Prüfung merken") {
                    RanzenIcon(BookIcons.Exam)
                }
            }
        }
    }
}

@Composable
private fun ToolKey(session: BookSession, t: BookTool, icon: ImageVector, name: String, onClick: () -> Unit = { session.select(t) }, active: Boolean = session.tool == t) {
    val c = Ranzen.colors
    TactileKey(onClick, selected = active, contentDescription = name) {
        RanzenIcon(icon, tint = if (active) c.signal else c.ink)
    }
}

@Composable
private fun UndoKeys(session: BookSession, vertical: Boolean) {
    val c = Ranzen.colors
    TactileKey(session::undo, contentDescription = "Rückgängig") {
        RanzenIcon(RanzenIcons.Undo, tint = if (session.canUndo) c.ink else c.inkFaint)
    }
    Gap(vertical, 4)
    TactileKey(session::redo, contentDescription = "Wiederholen") {
        RanzenIcon(RanzenIcons.Redo, tint = if (session.canRedo) c.ink else c.inkFaint)
    }
}

/** Read, pen, marker, eraser and the style key: the tools used all the time. */
@Composable
private fun WritingKeys(session: BookSession, vertical: Boolean, onStyle: () -> Unit) {
    ToolKey(session, BookTool.READ, BookIcons.Read, "Lesen")
    Gap(vertical, 4)
    ToolKey(session, BookTool.PEN, RanzenIcons.Pen, "Stift")
    Gap(vertical, 4)
    ToolKey(session, BookTool.MARKER, RanzenIcons.Marker, "Marker")
    Gap(vertical, 4)
    // Second tap switches between partial and whole-stroke erasing.
    ToolKey(session, BookTool.ERASER, if (session.tool == BookTool.STROKE_ERASER) RanzenIcons.Trash else RanzenIcons.Eraser, "Radierer",
        onClick = { session.select(if (session.tool == BookTool.ERASER) BookTool.STROKE_ERASER else BookTool.ERASER) },
        active = session.tool.erases)
    Gap(vertical, 4)
    val marker = session.tool == BookTool.MARKER
    TactileKey(onStyle, contentDescription = "Farbe und Dicke") {
        val size = if (marker) 18.dp else (6 + session.penSize * 1.6f).dp.coerceAtMost(20.dp)
        Box(Modifier.size(size).clip(CircleShape).background(Color(if (marker) session.markerColor else session.penColor)))
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
    @Composable
    fun items() {
        TactileKey(onBack, contentDescription = "Zurück") { RanzenIcon(RanzenIcons.Back) }
        Gap(vertical, 10)
        UndoKeys(session, vertical)
        Gap(vertical, 10)
        WritingKeys(session, vertical, onStyle)
        Gap(vertical, 10)
        ToolKey(session, BookTool.LASSO, RanzenIcons.Lasso, "Lasso")
        Gap(vertical, 4)
        ToolKey(session, BookTool.TEXT, RanzenIcons.Text, "Text")
        Gap(vertical, 4)
        ToolKey(session, BookTool.AREA, BookIcons.Area, "Bereich: Ausschnitt oder Markierung")
        Gap(vertical, 4)
        ToolKey(session, BookTool.NOTE, BookIcons.Note, "Notiz")
        Gap(vertical, 10)
        TactileKey(onContents, contentDescription = "Inhalt und Seiten") { RanzenIcon(BookIcons.Contents) }
    }
    PaperPanel(modifier, corner = 26.dp, depth = 0.8f) {
        // Scrolls only when the screen is too short for all keys.
        if (vertical) Column(Modifier.verticalScroll(rememberScrollState()).padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) { items() }
        else Row(Modifier.horizontalScroll(rememberScrollState()).padding(9.dp), verticalAlignment = Alignment.CenterVertically) { items() }
    }
}

/** The pane's one toolbar: close, page, undo, writing tools, lasso, text, search, more. */
@Composable
private fun PaneBar(session: BookSession, modifier: Modifier, onClose: () -> Unit, onPanel: (Panel) -> Unit, panel: Panel) {
    val c = Ranzen.colors
    PaperPanel(modifier, corner = 18.dp, depth = 0.7f) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            TactileKey(onClose, contentDescription = "Buch schließen") { RanzenIcon(RanzenIcons.Close, size = 20.dp) }
            PageButton(session) { onPanel(Panel.JUMP) }
            UndoKeys(session, vertical = false)
            Gap(false, 8)
            WritingKeys(session, vertical = false) { onPanel(Panel.STYLE) }
            Gap(false, 4)
            ToolKey(session, BookTool.LASSO, RanzenIcons.Lasso, "Lasso")
            Gap(false, 4)
            ToolKey(session, BookTool.TEXT, RanzenIcons.Text, "Text")
            Gap(false, 8)
            TactileKey({ onPanel(Panel.SEARCH) }, selected = panel == Panel.SEARCH, contentDescription = "Im Buch suchen") { RanzenIcon(RanzenIcons.Search) }
            Gap(false, 4)
            val moreActive = panel == Panel.MORE || session.tool == BookTool.AREA || session.tool == BookTool.NOTE
            TactileKey({ onPanel(Panel.MORE) }, selected = moreActive, contentDescription = "Mehr") {
                RanzenIcon(RanzenIcons.More, tint = if (moreActive) c.signal else c.ink)
            }
        }
    }
}

/** The pane's less frequent actions, as labelled keys. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MorePanel(session: BookSession, onMarkForExam: ((BookLink) -> Unit)?, onPanel: (Panel) -> Unit, onClose: () -> Unit) {
    val c = Ranzen.colors
    PaperPanel(corner = 20.dp) {
        FlowRow(Modifier.padding(10.dp).widthIn(max = 330.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            fun tool(t: BookTool) = { session.select(t); onClose() }
            BarButton(BookIcons.Area, "Bereich", tool(BookTool.AREA), tint = if (session.tool == BookTool.AREA) c.signal else c.ink)
            BarButton(BookIcons.Note, "Notiz", tool(BookTool.NOTE), tint = if (session.tool == BookTool.NOTE) c.signal else c.ink)
            BarButton(BookIcons.Contents, "Inhalt", { onPanel(Panel.CONTENTS) })
            val marked = session.currentBookmarked
            BarButton(RanzenIcons.Bookmark, if (marked) "Gemerkt" else "Lesezeichen", { session.toggleBookmark() }, tint = if (marked) c.signal else c.ink)
            BarButton(BookIcons.Rotate, "Drehen", session::rotateCurrent)
            if (onMarkForExam != null) BarButton(BookIcons.Exam, "Prüfung", { session.markForExam(onMarkForExam, area = false); onClose() })
        }
    }
}

@Composable
private fun Gap(vertical: Boolean, dp: Int) = Spacer(if (vertical) Modifier.height(dp.dp) else Modifier.width(dp.dp))

/** A small bar right above (or below) a rectangle on the page. */
@Composable
private fun FloatingBar(frame: RectF, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val gap = with(density) { 14.dp.toPx() }
        val above = frame.top - size.height - gap
        val y = if (above > gap * 6) above else (frame.bottom + gap).coerceAtMost(h - size.height - gap).coerceAtLeast(gap)
        val x = (frame.centerX() - size.width / 2).coerceIn(gap, (w - size.width - gap).coerceAtLeast(gap))
        PaperPanel(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.onSizeChanged { size = it }, corner = 16.dp) {
            content()
        }
    }
}

/** Lasso: recolour or delete; moving and scaling happen on the page. */
@Composable
private fun SelectionBar(session: BookSession, frame: RectF) {
    val c = Ranzen.colors
    var colors by remember { mutableStateOf(false) }
    FloatingBar(frame) {
        Column {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton(null, "Farbe", { colors = !colors }) { Lamp(Color(session.penColor), size = 14.dp) }
                BarButton(RanzenIcons.Trash, "Löschen", session::deleteSelection, tint = c.signal)
                BarButton(RanzenIcons.Check, "Fertig", session::clearSelection)
            }
            if (colors) Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkPalette.pen.forEach { argb ->
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { session.recolorSelection(argb); colors = false }, contentAlignment = Alignment.Center) {
                        Lamp(Color(argb), size = 22.dp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun BarButton(icon: ImageVector?, label: String, onClick: () -> Unit, tint: Color = Ranzen.colors.ink, lead: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (icon != null) RanzenIcon(icon, tint = tint, size = 22.dp) else lead?.invoke()
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = Ranzen.type.caption, color = tint, maxLines = 1)
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
        Column(Modifier.verticalScroll(rememberScrollState()).padding(26.dp).widthIn(max = 520.dp)) {
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

/** Typing text onto the page, like in the notebook: size, colour, done. */
@Composable
private fun TextEditor(session: BookSession, draft: TextDraft) {
    val c = Ranzen.colors
    val old = draft.old
    // Stored sizes are page units of this page; the menu offers the notebook's three sizes.
    val oldSize = old?.let { o -> TextItem.sizes.minByOrNull { kotlin.math.abs(session.textUnits(draft.page, it) - o.size) } }
    var text by remember(draft) { mutableStateOf(old?.text ?: "") }
    var size by remember(draft) { mutableFloatStateOf(oldSize ?: TextItem.sizes[1]) }
    var color by remember(draft) { mutableIntStateOf(old?.color ?: session.penColor) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(draft) { runCatching { focus.requestFocus() } }
    PaperPanel(corner = 24.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 560.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (old == null) "Text einfügen" else "Text bearbeiten", style = Ranzen.type.title, color = c.ink, modifier = Modifier.weight(1f))
                Text("Seite ${draft.page + 1}", style = Ranzen.type.mono, color = c.inkMuted)
            }
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                text, { text = it },
                textStyle = Ranzen.type.body.copy(color = Color(color), fontSize = (12 + size / 2).sp, fontFamily = TextFamily, fontWeight = FontWeight(400)),
                cursorBrush = SolidColor(c.signal),
                // The paper stays light in dark mode too, like the page the text lands on.
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(focus)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFFEFA)).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(14.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextItem.sizes.forEachIndexed { i, s ->
                    TactileKey({ size = s }, selected = size == s, size = 52.dp, contentDescription = listOf("Klein", "Normal", "Groß")[i]) {
                        Text("A", style = Ranzen.type.heading.copy(fontSize = (12 + i * 5).sp), color = if (size == s) c.signal else c.ink)
                    }
                    Spacer(Modifier.width(if (i == 2) 16.dp else 6.dp))
                }
                InkPalette.pen.forEach { argb ->
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).border(if (argb == color) 2.dp else 0.dp, if (argb == color) c.ink else Color.Transparent, CircleShape)
                            .clickable { color = argb },
                        contentAlignment = Alignment.Center,
                    ) { Lamp(Color(argb), size = 20.dp) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (old != null) QuietButton("Löschen", session::deleteText, color = c.signal)
                Spacer(Modifier.weight(1f))
                QuietButton("Abbrechen", session::cancelText)
                Spacer(Modifier.width(10.dp))
                SignalButton("Fertig", { session.commitText(text, size, color) })
            }
        }
    }
}
