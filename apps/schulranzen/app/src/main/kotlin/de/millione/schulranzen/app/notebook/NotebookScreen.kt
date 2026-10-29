package de.millione.schulranzen.app.notebook

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.app.data.Settings
import de.millione.schulranzen.design.InkPalette
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.TactileKey
import de.millione.schulranzen.ink.Brushes
import de.millione.schulranzen.ink.PaperStyle
import de.millione.schulranzen.ink.ToolKind

private enum class Panel { NONE, STYLE, MORE }

/**
 * The notebook. The writing surface is a native view *below* this screen;
 * this layer only draws the controls, so touches on the paper go straight
 * to the pen engine.
 */
@Composable
fun NotebookScreen(session: NotebookSession, settings: Settings, onBack: () -> Unit) {
    val c = Ranzen.colors
    var panel by remember { mutableStateOf(Panel.NONE) }
    session.density = LocalDensity.current.density
    BackHandler { if (panel != Panel.NONE) panel = Panel.NONE else onBack() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val safe = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)

        // Page counter, top centre
        Box(safe.fillMaxSize().padding(top = 14.dp), contentAlignment = Alignment.TopCenter) {
            PaperPanel(corner = 14.dp, depth = 0.6f) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(session.title, style = Ranzen.type.label, color = c.inkMuted, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                    Text("%02d / %02d".format(session.page + 1, session.pageCount), style = Ranzen.type.mono, color = c.ink)
                }
            }
        }

        // Tool rail
        val railModifier = if (landscape) safe.padding(start = 14.dp).align(Alignment.CenterStart) else safe.padding(top = 64.dp).align(Alignment.TopCenter)
        ToolRail(session, vertical = landscape, modifier = railModifier,
            onStyle = { panel = if (panel == Panel.STYLE) Panel.NONE else Panel.STYLE },
            onMore = { panel = if (panel == Panel.MORE) Panel.NONE else Panel.MORE },
            onBack = onBack)

        // New page, bottom right
        Box(safe.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomEnd) {
            TactileKey(onClick = session::addPage, size = 56.dp, corner = 18.dp, contentDescription = "Neue Seite") {
                RanzenIcon(RanzenIcons.Plus)
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
            MorePanel(session, settings) { panel = Panel.NONE }
        }

        // Tool ring around the pen tip
        session.ring?.let { r ->
            val color = if (session.tool == ToolKind.MARKER) session.markerColor else session.penColor
            ToolRingOverlay(r, session.tool, color,
                onPick = { session.pick(it); session.closeRing() },
                onDismiss = session::closeRing)
        }
    }
}

@Composable
private fun ToolRail(
    session: NotebookSession,
    vertical: Boolean,
    modifier: Modifier,
    onStyle: () -> Unit,
    onMore: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Ranzen.colors
    @Composable
    fun items() {
        TactileKey(onBack, contentDescription = "Zurück zur Bibliothek") { RanzenIcon(RanzenIcons.Back) }
        Gap(vertical, 14)
        TactileKey(session::undo, contentDescription = "Rückgängig") {
            RanzenIcon(RanzenIcons.Undo, tint = if (session.canUndo) c.ink else c.inkFaint)
        }
        Gap(vertical, 8)
        TactileKey(session::redo, contentDescription = "Wiederholen") {
            RanzenIcon(RanzenIcons.Redo, tint = if (session.canRedo) c.ink else c.inkFaint)
        }
        Gap(vertical, 14)
        TactileKey({ session.select(ToolKind.PEN) }, selected = session.tool == ToolKind.PEN, contentDescription = "Stift") {
            RanzenIcon(RanzenIcons.Pen, tint = if (session.tool == ToolKind.PEN) c.signal else c.ink)
        }
        Gap(vertical, 8)
        TactileKey({ session.select(ToolKind.MARKER) }, selected = session.tool == ToolKind.MARKER, contentDescription = "Marker") {
            RanzenIcon(RanzenIcons.Marker, tint = if (session.tool == ToolKind.MARKER) c.signal else c.ink)
        }
        Gap(vertical, 8)
        val erasing = session.tool.erases
        TactileKey(
            {
                // Second tap on the eraser switches between partial and whole-stroke erasing.
                session.select(if (session.tool == ToolKind.ERASER) ToolKind.STROKE_ERASER else ToolKind.ERASER)
            },
            selected = erasing, contentDescription = "Radierer",
        ) {
            RanzenIcon(if (session.tool == ToolKind.STROKE_ERASER) RanzenIcons.Trash else RanzenIcons.Eraser, tint = if (erasing) c.signal else c.ink)
        }
        Gap(vertical, 14)
        val color = Color(if (session.tool == ToolKind.MARKER) session.markerColor else session.penColor)
        TactileKey(onStyle, contentDescription = "Farbe und Dicke") {
            val size = if (session.tool == ToolKind.MARKER) 18.dp else (6 + session.penSize * 1.6f).dp.coerceAtMost(20.dp)
            Box(Modifier.size(size).clip(CircleShape).background(color))
        }
        Gap(vertical, 14)
        TactileKey(onMore, contentDescription = "Mehr") { RanzenIcon(RanzenIcons.More) }
    }
    PaperPanel(modifier, corner = 26.dp, depth = 0.8f) {
        if (vertical) Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) { items() }
        else Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { items() }
    }
}

@Composable
private fun Gap(vertical: Boolean, dp: Int) = Spacer(if (vertical) Modifier.height(dp.dp) else Modifier.width(dp.dp))

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
                        val dot = if (marker) (8 + s / 2).dp else (3 + s * 1.5f).dp
                        Box(Modifier.size(dot).clip(CircleShape).background(Color(if (marker) session.markerColor else session.penColor)))
                    }
                }
            }
        }
    }
}

@Composable
private fun MorePanel(session: NotebookSession, settings: Settings, onClose: () -> Unit) {
    val c = Ranzen.colors
    PaperPanel(corner = 22.dp) {
        Column(Modifier.padding(22.dp).width(340.dp)) {
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
            Spacer(Modifier.height(22.dp))
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
            Spacer(Modifier.height(8.dp))
            Text(
                "Seitentaste im Schweben lang halten öffnet den Werkzeugring. Kurz drücken wechselt zum Radierer und zurück.",
                style = Ranzen.type.caption, color = c.inkMuted,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = Ranzen.colors
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.signal, checkedThumbColor = c.onSignal))
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
