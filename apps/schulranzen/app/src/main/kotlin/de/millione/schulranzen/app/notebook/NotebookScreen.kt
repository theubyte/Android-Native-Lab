package de.millione.schulranzen.app.notebook

import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.millione.schulranzen.app.data.Settings
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
import de.millione.schulranzen.ink.PaperStyle
import de.millione.schulranzen.ink.TextItem
import de.millione.schulranzen.ink.ToolKind
import de.millione.schulranzen.rechnen.CalculatorWindow
import de.millione.schulranzen.handwriting.ConvertToTextSheet
import de.millione.schulranzen.handwriting.HandwritingModelCard
import de.millione.schulranzen.study.MarkForExamSheet
import de.millione.schulranzen.study.CardFromClipSheet
import de.millione.schulranzen.study.ExplainSheet
import de.millione.schulranzen.study.SuggestCardsSheet
import de.millione.schulranzen.rechnen.ResultBubble
import kotlin.math.roundToInt

private enum class Panel { NONE, STYLE, MORE, PAGES }

/**
 * The notebook. The writing surface is a native view *below* this screen;
 * this layer only draws the controls, so touches on the paper go straight
 * to the pen engine.
 */
@Composable
fun NotebookScreen(
    session: NotebookSession,
    settings: Settings,
    onBack: () -> Unit,
    /** Opens a book beside the notebook; null hides the entry. */
    onBookBeside: (() -> Unit)? = null,
    /** Narrow pane next to a book: keep the rail vertical even when the pane is taller than wide. */
    compact: Boolean = false,
) {
    val c = Ranzen.colors
    var panel by remember { mutableStateOf(Panel.NONE) }
    session.density = LocalDensity.current.density
    BackHandler {
        when {
            session.textDraft != null -> session.cancelText()
            panel != Panel.NONE -> panel = Panel.NONE
            session.selection != null -> session.canvas.clearSelection()
            else -> onBack()
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) session.insertImage(uri)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickWorksheets = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        session.addWorksheetImages(uris)
    }
    val pickWorksheetPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) session.addWorksheetPdf(uri)
    }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) session.addWorksheetImages(listOf(u))
    }
    var pdfRuling by remember { mutableStateOf(true) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) session.exportPdf(uri, pdfRuling)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = compact || maxWidth > maxHeight
        val safe = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)

        // Page counter, top centre
        Box(safe.fillMaxSize().padding(top = 14.dp), contentAlignment = Alignment.TopCenter) {
            PaperPanel(corner = 14.dp, depth = 0.6f) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(session.title, style = Ranzen.type.label, color = c.inkMuted, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                    Text("%02d / %02d".format(session.page + 1, session.pageCount), style = Ranzen.type.mono, color = c.ink)
                    if (session.currentPage()?.bookmarked == true) {
                        Spacer(Modifier.width(8.dp)); RanzenIcon(RanzenIcons.Bookmark, tint = c.signal, size = 16.dp)
                    }
                }
            }
        }

        // Tool rail
        val railModifier = if (landscape) safe.padding(start = 14.dp).align(Alignment.CenterStart) else safe.padding(top = 64.dp).align(Alignment.TopCenter)
        ToolRail(session, vertical = landscape, modifier = railModifier, dense = landscape && maxHeight < 820.dp,
            onStyle = { panel = if (panel == Panel.STYLE) Panel.NONE else Panel.STYLE },
            onPages = { panel = if (panel == Panel.PAGES) Panel.NONE else Panel.PAGES },
            onMore = { panel = if (panel == Panel.MORE) Panel.NONE else Panel.MORE },
            onBack = onBack)

        // New page, bottom right
        Box(safe.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomEnd) {
            TactileKey(onClick = session::addPage, size = 56.dp, corner = 18.dp, contentDescription = "Neue Seite") {
                RanzenIcon(RanzenIcons.Plus)
            }
        }

        // Selection actions, floating above the selection frame
        session.selection?.let { frame ->
            if (panel == Panel.NONE && session.ring == null) SelectionBar(session, frame)
        }
        // Paste offer while the lasso is active and something is copied
        if (session.tool == ToolKind.LASSO && session.selection == null && session.clip != null && panel == Panel.NONE) {
            Box(safe.fillMaxSize().padding(top = 70.dp), contentAlignment = Alignment.TopCenter) {
                PaperPanel(corner = 14.dp, depth = 0.7f) {
                    Row(Modifier.clickable { session.paste() }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RanzenIcon(RanzenIcons.Copy, size = 18.dp); Spacer(Modifier.width(8.dp))
                        Text("Einfügen", style = Ranzen.type.label, color = c.ink)
                    }
                }
            }
        }

        // Floating calculator above the paper; only the window takes touches.
        if (session.showCalculator) CalculatorWindow(onClose = { session.showCalculator = false }, modifier = Modifier.fillMaxSize())

        // Result of "Ausrechnen", next to the handwriting
        session.mathResult?.let { (result, frame) ->
            val d = LocalDensity.current.density
            Box(Modifier.offset { IntOffset((frame.right + 16 * d).roundToInt(), frame.top.roundToInt()) }) {
                ResultBubble(result, onDismiss = session::dismissMath)
            }
        }

        // Panels
        if (panel != Panel.NONE) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { panel = Panel.NONE } })
        }
        val panelAlign = if (landscape) Modifier.align(Alignment.CenterStart).then(safe).padding(start = 92.dp)
                         else Modifier.align(Alignment.TopCenter).then(safe).padding(top = 140.dp)
        AnimatedVisibility(panel == Panel.STYLE, panelAlign,
            enter = fadeIn() + scaleIn(spring(dampingRatio = 0.7f), initialScale = 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.95f)) {
            StylePanel(session)
        }
        AnimatedVisibility(panel == Panel.MORE, panelAlign,
            enter = fadeIn() + scaleIn(spring(dampingRatio = 0.7f), initialScale = 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.95f)) {
            MorePanel(session, settings, pdfRuling, { pdfRuling = it },
                onImage = { panel = Panel.NONE; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onWorksheetPhotos = { panel = Panel.NONE; pickWorksheets.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onWorksheetPdf = { panel = Panel.NONE; pickWorksheetPdf.launch(arrayOf("application/pdf")) },
                onWorksheetCamera = {
                    panel = Panel.NONE
                    val dir = java.io.File(context.cacheDir, "camera").also { it.mkdirs() }
                    val file = java.io.File(dir, "blatt-${System.currentTimeMillis()}.jpg")
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    cameraUri = uri
                    runCatching { takePhoto.launch(uri) }.onFailure { session.say("Keine Kamera-App gefunden") }
                },
                onPdf = { panel = Panel.NONE; savePdf.launch(session.title.ifBlank { "Heft" } + ".pdf") },
                onBookBeside = onBookBeside?.let { open -> { panel = Panel.NONE; open() } })
        }
        AnimatedVisibility(panel == Panel.PAGES, Modifier.align(Alignment.CenterEnd),
            enter = fadeIn() + slideInHorizontally(spring(dampingRatio = 0.8f)) { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 }) {
            PageOverview(session, Modifier.then(safe).padding(14.dp).fillMaxHeight().width(380.dp)) { panel = Panel.NONE }
        }

        // Text editor
        session.textDraft?.let { draft ->
            Box(Modifier.fillMaxSize().background(c.desk.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { } })
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)).padding(24.dp), contentAlignment = Alignment.Center) {
                TextEditor(draft.old, session)
            }
        }

        // Once on Samsung tablets: how to keep Air Command out of the way of the tool ring.
        var airHint by remember { mutableStateOf(AirCommand.isSamsung && !settings.airHintSeen) }
        if (airHint && panel == Panel.NONE) {
            Box(safe.fillMaxSize().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
                PaperPanel(corner = 18.dp) {
                    Column(Modifier.padding(18.dp).width(460.dp)) {
                        AirCommandHint()
                        Row { Spacer(Modifier.weight(1f)); SignalButton("Verstanden", { airHint = false; settings.airHintSeen = true }) }
                    }
                }
            }
        }

        // Tool ring around the pen tip
        session.ring?.let { r ->
            val color = if (session.tool == ToolKind.MARKER) session.markerColor else session.penColor
            ToolRingOverlay(r, session.tool, color,
                onPick = { session.pick(it); session.closeRing() },
                onDismiss = session::closeRing)
        }

        // Longer work (PDF pages, photos)
        session.busy?.let { msg ->
            Box(Modifier.fillMaxSize().background(c.desk.copy(alpha = 0.3f)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
                PaperPanel(corner = 18.dp) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = c.signal, strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(msg, style = Ranzen.type.label, color = c.ink)
                    }
                }
            }
        }

        // Short messages
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

@Composable
private fun ToolRail(
    session: NotebookSession,
    vertical: Boolean,
    modifier: Modifier,
    onStyle: () -> Unit,
    onPages: () -> Unit,
    onMore: () -> Unit,
    onBack: () -> Unit,
    /** Smaller keys and gaps when the screen is too short for the full rail. */
    dense: Boolean = false,
) {
    val c = Ranzen.colors
    val keySize = if (dense) 42.dp else 48.dp
    // Tool name next to the key the pen hovers over: name and key centre in window pixels.
    var hovered by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    @Composable
    fun Named(name: String, content: @Composable () -> Unit) {
        var centre by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.onGloballyPositioned { centre = it.boundsInWindow().center }
                .onHover { on -> hovered = if (on) name to centre else hovered?.takeIf { it.first != name } },
        ) { content() }
    }
    @Composable
    fun toolKey(kind: ToolKind, icon: ImageVector, name: String, onClick: () -> Unit = { session.select(kind) }, active: Boolean = session.tool == kind) {
        Named(name) {
            TactileKey(onClick, size = keySize, selected = active, contentDescription = name) {
                RanzenIcon(icon, tint = if (active) c.signal else c.ink)
            }
        }
    }
    @Composable
    fun items() {
        Named("Zurück zur Bibliothek") { TactileKey(onBack, size = keySize, contentDescription = "Zurück zur Bibliothek") { RanzenIcon(RanzenIcons.Back) } }
        Gap(vertical, if (dense) 8 else 12)
        Named("Rückgängig") {
            TactileKey(session::undo, size = keySize, contentDescription = "Rückgängig") {
                RanzenIcon(RanzenIcons.Undo, tint = if (session.canUndo) c.ink else c.inkFaint)
            }
        }
        Gap(vertical, if (dense) 4 else 6)
        Named("Wiederholen") {
            TactileKey(session::redo, size = keySize, contentDescription = "Wiederholen") {
                RanzenIcon(RanzenIcons.Redo, tint = if (session.canRedo) c.ink else c.inkFaint)
            }
        }
        Gap(vertical, if (dense) 8 else 12)
        toolKey(ToolKind.PEN, RanzenIcons.Pen, "Stift")
        Gap(vertical, if (dense) 4 else 6)
        toolKey(ToolKind.MARKER, RanzenIcons.Marker, "Marker")
        Gap(vertical, if (dense) 4 else 6)
        // Second tap on the eraser switches between partial and whole-stroke erasing.
        toolKey(ToolKind.ERASER, if (session.tool == ToolKind.STROKE_ERASER) RanzenIcons.Trash else RanzenIcons.Eraser, "Radierer",
            onClick = { session.select(if (session.tool == ToolKind.ERASER) ToolKind.STROKE_ERASER else ToolKind.ERASER) },
            active = session.tool.erases)
        Gap(vertical, if (dense) 4 else 6)
        toolKey(ToolKind.LASSO, RanzenIcons.Lasso, "Lasso")
        Gap(vertical, if (dense) 4 else 6)
        toolKey(ToolKind.RULER, RanzenIcons.Ruler, "Lineal")
        Gap(vertical, if (dense) 4 else 6)
        toolKey(ToolKind.TEXT, RanzenIcons.Text, "Text")
        Gap(vertical, if (dense) 4 else 6)
        Named("Taschenrechner") {
            TactileKey({ session.showCalculator = !session.showCalculator }, size = keySize, selected = session.showCalculator, contentDescription = "Taschenrechner") {
                RanzenIcon(CalcIcon, tint = if (session.showCalculator) c.signal else c.ink)
            }
        }
        Gap(vertical, if (dense) 8 else 12)
        val color = Color(if (session.tool == ToolKind.MARKER) session.markerColor else session.penColor)
        Named("Farbe und Dicke") {
            TactileKey(onStyle, size = keySize, contentDescription = "Farbe und Dicke") {
                val size = if (session.tool == ToolKind.MARKER) 18.dp else (6 + session.penSize * 1.6f).dp.coerceAtMost(20.dp)
                Box(Modifier.size(size).clip(CircleShape).background(color))
            }
        }
        Gap(vertical, if (dense) 8 else 12)
        Named("Seiten") { TactileKey(onPages, size = keySize, contentDescription = "Seiten") { RanzenIcon(RanzenIcons.Pages) } }
        Gap(vertical, if (dense) 4 else 6)
        Named("Mehr") { TactileKey(onMore, size = keySize, contentDescription = "Mehr") { RanzenIcon(RanzenIcons.More) } }
    }
    PaperPanel(modifier.penHoverZone("rail"), corner = 26.dp, depth = 0.8f) {
        // Scrolls only when the screen is too short for all keys (large display size setting).
        if (vertical) Column(Modifier.verticalScroll(rememberScrollState()).padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) { items() }
        else Row(Modifier.horizontalScroll(rememberScrollState()).padding(9.dp), verticalAlignment = Alignment.CenterVertically) { items() }
    }
    // The name label, beside a vertical rail or below a horizontal one.
    hovered?.let { (name, centre) ->
        val d = LocalDensity.current.density
        // Popup anchors at the screen's top-left box, so window pixels work as offset.
        Popup(
            offset = if (vertical) IntOffset((centre.x + 44 * d).roundToInt(), (centre.y - 18 * d).roundToInt())
                     else IntOffset((centre.x - 40 * d).roundToInt(), (centre.y + 40 * d).roundToInt()),
            properties = PopupProperties(focusable = false, clippingEnabled = false),
        ) {
            Text(
                name, style = Ranzen.type.label, color = c.paper,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.ink.copy(alpha = 0.92f)).padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun Gap(vertical: Boolean, dp: Int) = Spacer(if (vertical) Modifier.height(dp.dp) else Modifier.width(dp.dp))

/** Cut, copy, duplicate, recolour, delete – right above the lasso frame. */
@Composable
private fun SelectionBar(session: NotebookSession, frame: android.graphics.RectF) {
    val c = Ranzen.colors
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var colors by remember { mutableStateOf(false) }
    var learn by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val gap = with(density) { 14.dp.toPx() }
        val above = frame.top - size.height - gap
        val y = if (above > gap * 4) above else (frame.bottom + gap).coerceAtMost(h - size.height - gap)
        val x = (frame.centerX() - size.width / 2).coerceIn(gap, (w - size.width - gap).coerceAtLeast(gap))
        PaperPanel(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.onSizeChanged { size = it }, corner = 16.dp) {
            Column {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarButton(RanzenIcons.Scissors, "Ausschneiden", session::cut)
                    BarButton(RanzenIcons.Copy, "Kopieren", session::copy)
                    BarButton(RanzenIcons.Pages, "Duplizieren", session::duplicate)
                    BarButton(null, "Farbe", { colors = !colors }) { Lamp(Color(session.penColor), size = 14.dp) }
                    BarButton(CalcIcon, "Ausrechnen", session::solveSelection)
                    BarButton(RanzenIcons.More, "Lernen", { learn = !learn; colors = false }, tint = if (learn) c.signal else c.ink)
                    BarButton(RanzenIcons.Trash, "Löschen", session::delete, tint = c.signal)
                }
                if (learn) Row(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarButton(RanzenIcons.Text, "Als Text", session::convertSelection)
                    BarButton(RanzenIcons.Search, "Erklären", session::explainSelection)
                    BarButton(RanzenIcons.Copy, "Karten", session::suggestFromSelection)
                    BarButton(RanzenIcons.Image, "Bildkarte", session::cardFromSelection)
                    BarButton(RanzenIcons.Bookmark, "Prüfung", session::markForExam)
                }
                if (colors) Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InkPalette.pen.forEach { argb ->
                        Box(Modifier.size(30.dp).clip(CircleShape).clickable { session.recolor(argb); colors = false }, contentAlignment = Alignment.Center) {
                            Lamp(Color(argb), size = 20.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector?, label: String, onClick: () -> Unit, tint: Color = Ranzen.colors.ink, lead: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (icon != null) RanzenIcon(icon, tint = tint, size = 20.dp) else lead?.invoke()
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = Ranzen.type.caption, color = tint)
    }
}

@Composable
private fun TextEditor(old: TextItem?, session: NotebookSession) {
    val c = Ranzen.colors
    var text by remember { mutableStateOf(old?.text ?: "") }
    var size by remember { mutableFloatStateOf(old?.size ?: TextItem.sizes[1]) }
    var color by remember { mutableIntStateOf(old?.color ?: session.penColor) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    PaperPanel(corner = 24.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(26.dp).width(560.dp)) {
            Text(if (old == null) "Text einfügen" else "Text bearbeiten", style = Ranzen.type.title, color = c.ink)
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                text, { text = it },
                textStyle = Ranzen.type.body.copy(color = Color(color), fontSize = (12 + size / 2).sp, fontFamily = TextFamily, fontWeight = FontWeight(400)),
                cursorBrush = SolidColor(c.signal),
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).focusRequester(focus)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFFEFA)).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(14.dp),
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextItem.sizes.forEachIndexed { i, s ->
                    TactileKey({ size = s }, selected = size == s, size = 52.dp, contentDescription = listOf("Klein", "Normal", "Groß")[i]) {
                        Text("A", style = Ranzen.type.heading.copy(fontSize = (12 + i * 5).sp), color = if (size == s) c.signal else c.ink)
                    }
                    Spacer(Modifier.width(if (i == 2) 20.dp else 8.dp))
                }
                InkPalette.pen.forEach { argb ->
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).border(if (argb == color) 2.dp else 0.dp, if (argb == color) c.ink else Color.Transparent, CircleShape)
                            .clickable { color = argb },
                        contentAlignment = Alignment.Center,
                    ) { Lamp(Color(argb), size = 20.dp) }
                }
            }
            Spacer(Modifier.height(24.dp))
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

@Composable
private fun StylePanel(session: NotebookSession) {
    val c = Ranzen.colors
    val marker = session.tool == ToolKind.MARKER
    PaperPanel(corner = 22.dp) {
        Column(Modifier.padding(22.dp).width(300.dp)) {
            Text(if (marker) "Marker" else "Stift", style = Ranzen.type.heading, color = c.ink)
            Spacer(Modifier.height(16.dp))
            Text("FARBE", style = Ranzen.type.caption, color = c.inkMuted)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val colors = if (marker) InkPalette.marker else InkPalette.pen
                val current = if (marker) session.markerColor else session.penColor
                colors.forEach { argb ->
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .border(if (argb == current) 2.dp else 0.dp, if (argb == current) c.ink else Color.Transparent, CircleShape)
                            .clickable { if (marker) session.setMarker(argb) else session.setPen(argb) },
                        contentAlignment = Alignment.Center,
                    ) { Lamp(Color(argb), size = 26.dp) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("DICKE", style = Ranzen.type.caption, color = c.inkMuted)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val sizes = if (marker) Brushes.markerSizes else Brushes.penSizes
                val current = if (marker) session.markerSize else session.penSize
                sizes.forEach { s ->
                    val selected = s == current
                    TactileKey(
                        { if (marker) session.setMarker(session.markerColor, s) else session.setPen(session.penColor, s) },
                        selected = selected, size = 46.dp,
                    ) {
                        val dot = if (marker) (6 + s * 0.7f).dp else (4 + s * 2.2f).dp
                        Box(Modifier.size(dot).clip(CircleShape).background(Color(if (marker) session.markerColor else session.penColor)))
                    }
                }
            }
        }
    }
}

@Composable
private fun MorePanel(
    session: NotebookSession,
    settings: Settings,
    pdfRuling: Boolean,
    onPdfRuling: (Boolean) -> Unit,
    onImage: () -> Unit,
    onWorksheetPhotos: () -> Unit,
    onWorksheetPdf: () -> Unit,
    onWorksheetCamera: () -> Unit,
    onPdf: () -> Unit,
    onBookBeside: (() -> Unit)?,
) {
    val c = Ranzen.colors
    PaperPanel(corner = 22.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp).width(360.dp)) {
            Text("Seite", style = Ranzen.type.heading, color = c.ink)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val current = session.currentPaper()
                PaperStyle.entries.forEach { style ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { session.setPaper(style) }) {
                        PaperSwatch(style, selected = style == current)
                        Spacer(Modifier.height(6.dp))
                        Text(style.label, style = Ranzen.type.caption, color = if (style == current) c.ink else c.inkMuted)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            @Suppress("UNUSED_VARIABLE") val v = session.pagesVersion
            val page = session.currentPage()
            ToggleRow("Lesezeichen auf dieser Seite", page?.bookmarked == true) { session.toggleBookmark() }
            ToggleRow("Seite sperren", page?.locked == true) { session.toggleLock() }
            Spacer(Modifier.height(16.dp))
            Text("Arbeitsblatt als neue Seite", style = Ranzen.type.heading, color = c.ink)
            Spacer(Modifier.height(4.dp))
            ActionRow(RanzenIcons.Camera, "Fotografieren", onWorksheetCamera)
            ActionRow(RanzenIcons.Image, "Aus Fotos (auch mehrere)", onWorksheetPhotos)
            ActionRow(RanzenIcons.Paper, "Aus PDF – jede Seite wird ein Blatt", onWorksheetPdf)
            Spacer(Modifier.height(16.dp))
            Text("Auf diese Seite", style = Ranzen.type.heading, color = c.ink)
            Spacer(Modifier.height(4.dp))
            ActionRow(RanzenIcons.Image, "Bild aus der Galerie", onImage)
            if (session.clip != null) ActionRow(RanzenIcons.Copy, "Kopierte Auswahl einfügen", session::paste)
            Spacer(Modifier.height(16.dp))
            Text("Lernen", style = Ranzen.type.heading, color = c.ink)
            Spacer(Modifier.height(4.dp))
            ActionRow(RanzenIcons.Bookmark, "Diese Seite für eine Prüfung merken", session::markForExam)
            Spacer(Modifier.height(16.dp))
            if (onBookBeside != null) {
                Text("Nebeneinander", style = Ranzen.type.heading, color = c.ink)
                Spacer(Modifier.height(4.dp))
                ActionRow(RanzenIcons.Book, "Buch daneben öffnen", onBookBeside)
                Spacer(Modifier.height(16.dp))
            }
            Text("Teilen", style = Ranzen.type.heading, color = c.ink)
            Spacer(Modifier.height(4.dp))
            ActionRow(RanzenIcons.Export, "Heft als PDF speichern", onPdf)
            ToggleRow("PDF mit Linien und Karos", pdfRuling, onPdfRuling)
            Spacer(Modifier.height(16.dp))
            Text("Stift", style = Ranzen.type.heading, color = c.ink)
            Spacer(Modifier.height(6.dp))
            var buttonErases by remember { mutableStateOf(settings.buttonErases) }
            ToggleRow("Seitentaste beim Schreiben radiert", buttonErases) {
                buttonErases = it; settings.buttonErases = it; session.canvas.buttonErases = it
            }
            var fingerDraws by remember { mutableStateOf(settings.fingerDraws) }
            ToggleRow("Mit dem Finger schreiben", fingerDraws) {
                fingerDraws = it; settings.fingerDraws = it; session.canvas.fingerDraws = it
            }
            var shapes by remember { mutableStateOf(settings.shapes) }
            ToggleRow("Formen begradigen (am Ende kurz halten)", shapes) { shapes = it; settings.shapes = it }
            var circle by remember { mutableStateOf(settings.circleSolve) }
            ToggleRow("Eingekreiste Rechnung ausrechnen", circle) { circle = it; settings.circleSolve = it }
            Spacer(Modifier.height(10.dp))
            HandwritingModelCard((androidx.compose.ui.platform.LocalContext.current.applicationContext as de.millione.schulranzen.app.RanzenApp).recognizer)
            Spacer(Modifier.height(8.dp))
            Text(
                "Seitentaste im Schweben lang halten öffnet den Werkzeugring. Kurz drücken wechselt zum Radierer und zurück.",
                style = Ranzen.type.caption, color = c.inkMuted,
            )
            if (AirCommand.isSamsung) {
                Spacer(Modifier.height(10.dp))
                AirCommandHint()
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Ranzen.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RanzenIcon(icon, size = 22.dp)
        Spacer(Modifier.width(14.dp))
        Text(label, style = Ranzen.type.body, color = c.ink)
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = Ranzen.colors
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(
            checkedTrackColor = c.signal, checkedThumbColor = c.onSignal, checkedBorderColor = c.signal,
            uncheckedTrackColor = c.line.copy(alpha = 0.5f), uncheckedThumbColor = c.inkFaint, uncheckedBorderColor = c.line,
        ))
    }
}

@Composable
internal fun PaperSwatch(style: PaperStyle, selected: Boolean) {
    val c = Ranzen.colors
    val shape = RoundedCornerShape(8.dp)
    Canvas(
        Modifier.size(width = 60.dp, height = 78.dp).clip(shape)
            .background(Color(0xFFFFFEFA))
            .border(if (selected) 2.dp else 1.dp, if (selected) c.signal else c.line, shape),
    ) {
        val rule = Color(0xFFC7D2DF)
        when (style) {
            PaperStyle.LINED -> {
                var y = 14.dp.toPx(); while (y < size.height - 6) { drawLine(rule, Offset(0f, y), Offset(size.width, y), 1f); y += 8.dp.toPx() }
                drawLine(Color(0xFFE3A99C), Offset(12.dp.toPx(), 0f), Offset(12.dp.toPx(), size.height), 1f)
            }
            PaperStyle.SQUARED -> {
                val s = 6.dp.toPx()
                var x = s; while (x < size.width) { drawLine(rule, Offset(x, 0f), Offset(x, size.height), 1f); x += s }
                var y = s; while (y < size.height) { drawLine(rule, Offset(0f, y), Offset(size.width, y), 1f); y += s }
            }
            PaperStyle.DOTTED -> {
                val s = 6.dp.toPx()
                var y = s; while (y < size.height) { var x = s; while (x < size.width) { drawCircle(rule, 1.2f, Offset(x, y)); x += s }; y += s }
            }
            PaperStyle.BLANK -> Unit
        }
    }
}

/** Pocket calculator key: body, display, keys. */
private val CalcIcon: ImageVector = RanzenIcons.line("calc") {
    moveTo(6f, 3.5f); lineTo(18f, 3.5f); lineTo(18f, 20.5f); lineTo(6f, 20.5f); close()
    moveTo(8.5f, 6.5f); lineTo(15.5f, 6.5f); lineTo(15.5f, 9.5f); lineTo(8.5f, 9.5f); close()
    moveTo(9f, 13f); lineTo(9.01f, 13f); moveTo(12f, 13f); lineTo(12.01f, 13f); moveTo(15f, 13f); lineTo(15.01f, 13f)
    moveTo(9f, 17f); lineTo(9.01f, 17f); moveTo(12f, 17f); lineTo(12.01f, 17f); moveTo(15f, 17f); lineTo(15.01f, 17f)
}

/**
 * Sheets of the learning features (text, exam mark, card, explain). Drawn by
 * the host over the whole window, so with a book beside the notebook they
 * are centred on the screen and not squeezed into the notebook half.
 */
@Composable
fun NotebookSheets(session: NotebookSession) {
    val c = Ranzen.colors
    Box(Modifier.fillMaxSize()) {
        session.convert?.let { cs ->
            Box(Modifier.fillMaxSize().background(c.desk.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { session.convert = null } }
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)), contentAlignment = Alignment.Center) {
                ConvertToTextSheet(cs.text, onInsert = session::insertConverted, onCopy = { session.convert = null; session.say("Text kopiert") },
                    onCancel = { session.convert = null })
            }
        }
        session.markDraft?.let { d -> MarkForExamSheet(d, onDone = { session.markDraft = null; session.say("Für die Prüfung gemerkt") }, onCancel = { session.markDraft = null }) }
        session.cardClip?.let { (file, link) -> CardFromClipSheet(file, link, onDone = { session.cardClip = null; file.delete(); session.say("Karteikarte angelegt") }, onCancel = { session.cardClip = null; file.delete() }) }
        session.explainText?.let { q -> ExplainSheet(q, onClose = { session.explainText = null }) }
        session.suggestText?.let { t -> SuggestCardsSheet(t, onDone = { session.suggestText = null; session.say("Karten gespeichert") }, onCancel = { session.suggestText = null }) }
    }
}
