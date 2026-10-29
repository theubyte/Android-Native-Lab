package de.millione.schulranzen.rechnen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.millione.schulranzen.core.math.AngleMode
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.TactileKey
import kotlin.math.roundToInt

private const val MIN_W = 300f
private const val MIN_H = 460f
private const val HISTORY_MAX = 40
private val GRIP_STRIP = 34.dp

/**
 * Floating scientific calculator. It fills its parent but only the window
 * itself takes touches, so it can lie over the notebook: drag it by its head,
 * resize it at the lower right corner. Position, size, angle mode, ANS and
 * the tape are remembered under filesDir/rechnen.
 */
@Composable
fun CalculatorWindow(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { RechnenStore.get(context) }
    val prefs = store.calculator.collectAsState().value
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (prefs == null) return@BoxWithConstraints // loads in a few ms
        val boundsW = maxWidth.value
        val boundsH = maxHeight.value
        val margin = 16f

        // Geometry in dp, as stored; what is shown is clamped to the current screen,
        // so a rotation or a smaller window never pushes the calculator out of reach.
        var w by remember { mutableStateOf(prefs.width) }
        var h by remember { mutableStateOf(prefs.height) }
        var x by remember { mutableStateOf(if (prefs.x < 0) boundsW - prefs.width - 32f else prefs.x) }
        var y by remember { mutableStateOf(if (prefs.y < 0) 88f else prefs.y) }
        val bounds by rememberUpdatedState(Pair(boundsW, boundsH))
        fun cw() = clampSize(w, MIN_W, bounds.first, margin)
        fun ch() = clampSize(h, MIN_H, bounds.second, margin)
        fun cx() = x.coerceIn(0f, (bounds.first - cw()).coerceAtLeast(0f))
        fun cy() = y.coerceIn(0f, (bounds.second - ch()).coerceAtLeast(0f))
        val density = LocalDensity.current.density

        val persist = {
            w = cw(); h = ch(); x = cx(); y = cy()
            store.updateCalculator { it.copy(x = x, y = y, width = w, height = h) }
        }
        val shownW = cw()
        val shownH = ch()
        val shownX = cx()
        val shownY = cy()

        Box(
            Modifier
                .offset { IntOffset((shownX * density).roundToInt(), (shownY * density).roundToInt()) }
                .size(shownW.dp, shownH.dp),
        ) {
            PaperPanel(Modifier.fillMaxSize().swallowTouches(), corner = 26.dp) {
                CalculatorBody(
                    store = store,
                    width = shownW.dp,
                    height = shownH.dp,
                    onClose = onClose,
                    dragHandle = Modifier.pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { persist() }, onDragCancel = { persist() }) { change, drag ->
                            change.consume()
                            x = (cx() + drag.x / density).coerceIn(0f, (bounds.first - cw()).coerceAtLeast(0f))
                            y = (cy() + drag.y / density).coerceIn(0f, (bounds.second - ch()).coerceAtLeast(0f))
                        }
                    },
                )
            }
            // Resize grip in the bottom strip below the keys, so it never covers "=".
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(width = 72.dp, height = GRIP_STRIP)
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { persist() }, onDragCancel = { persist() }) { change, drag ->
                            change.consume()
                            w = (cw() + drag.x / density).coerceIn(minOf(MIN_W, bounds.first), (bounds.first - cx()).coerceAtLeast(minOf(MIN_W, bounds.first)))
                            h = (ch() + drag.y / density).coerceIn(minOf(MIN_H, bounds.second), (bounds.second - cy()).coerceAtLeast(minOf(MIN_H, bounds.second)))
                        }
                    },
                contentAlignment = Alignment.BottomEnd,
            ) {
                RanzenIcon(RechnenIcons.Grip, Modifier.padding(end = 6.dp, bottom = 4.dp), tint = Ranzen.colors.inkFaint, size = 22.dp)
            }
        }
    }
}

private fun clampSize(v: Float, min: Float, bound: Float, margin: Float): Float {
    val lo = minOf(min, bound)
    return v.coerceIn(lo, maxOf(lo, bound - 2 * margin))
}

/**
 * Makes the whole window a touch target so nothing reaches the page beneath:
 * Compose reports an event as handled as soon as a pointer-input node is hit.
 * It only listens; consuming here would cancel the head's drag detection.
 */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

@Composable
private fun CalculatorBody(
    store: RechnenStore,
    width: Dp,
    height: Dp,
    onClose: () -> Unit,
    dragHandle: Modifier,
) {
    val c = Ranzen.colors
    val prefs = store.calculator.collectAsState().value ?: CalculatorPrefs()
    val model = remember { CalculatorModel() }
    var inverse by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val angle = if (prefs.radians) AngleMode.RAD else AngleMode.DEG

    // Scale type with the window so a small calculator stays readable and a big one fills out.
    val scale = (minOf(width.value / 380f, height.value / 600f)).coerceIn(0.8f, 1.6f)

    Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, bottom = GRIP_STRIP)) {
        // Head: the drag handle, with the mode switches and close.
        Row(
            dragHandle.fillMaxWidth().height(60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GripDots()
            Spacer(Modifier.width(10.dp))
            Caption("Rechner", color = c.inkMuted)
            Spacer(Modifier.weight(1f))
            ModeKey(if (prefs.radians) "RAD" else "DEG", selected = false) {
                store.updateCalculator { it.copy(radians = !it.radians) }
            }
            Spacer(Modifier.width(6.dp))
            ModeKey("INV", selected = inverse) { inverse = !inverse }
            Spacer(Modifier.width(6.dp))
            TactileKey(onClick = { showHistory = !showHistory }, selected = showHistory, size = 44.dp, corner = 12.dp, contentDescription = "Verlauf") {
                RanzenIcon(RechnenIcons.History, size = 20.dp)
            }
            Spacer(Modifier.width(6.dp))
            TactileKey(onClick = onClose, size = 44.dp, corner = 12.dp, contentDescription = "Rechner schließen") {
                RanzenIcon(RanzenIcons.Close, size = 20.dp)
            }
        }

        Display(model, angle, prefs.ans, inverse, scale)
        Spacer(Modifier.height(12.dp))

        AnimatedContent(showHistory, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pad") { history ->
            if (history) {
                HistoryList(
                    entries = prefs.history,
                    onPick = { model.load(it); showHistory = false },
                    onClear = { store.updateCalculator { p -> p.copy(history = emptyList()) } },
                )
            } else {
                Keypad(model, inverse, scale) {
                    model.equals(angle, prefs.ans)?.let { entry ->
                        store.updateCalculator { p -> p.copy(ans = entry.value, history = (listOf(entry) + p.history).take(HISTORY_MAX)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GripDots() {
    val col = Ranzen.colors.inkFaint
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) { Box(Modifier.size(3.dp).clip(RoundedCornerShape(2.dp)).background(col)) }
            }
        }
    }
}

@Composable
private fun ModeKey(text: String, selected: Boolean, onClick: () -> Unit) {
    TactileKey(onClick = onClick, selected = selected, size = 44.dp, corner = 12.dp, modifier = Modifier.width(54.dp), contentDescription = text) {
        Text(text, style = Ranzen.type.mono.copy(fontSize = 13.sp), color = if (selected) Ranzen.colors.signal else Ranzen.colors.ink)
    }
}

/** The LCD: annunciators on top, the input line, then the result in large figures. */
@Composable
private fun Display(model: CalculatorModel, angle: AngleMode, ans: Double, inverse: Boolean, scale: Float) {
    val c = Ranzen.colors
    val preview = model.preview(angle, ans)
    val big = Ranzen.type.monoLarge.copy(fontSize = (34 * scale).sp, lineHeight = (40 * scale).sp, textAlign = TextAlign.End)
    Column(
        Modifier
            .fillMaxWidth()
            .recessed(16.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lamp(c.signal, size = 6.dp, glow = true)
            Spacer(Modifier.width(8.dp))
            Annunciator(if (angle == AngleMode.DEG) "DEG" else "RAD", true)
            Annunciator("INV", inverse)
            Spacer(Modifier.weight(1f))
            Text("ANS ${CalculatorModel.format(ans)}", style = Ranzen.type.mono.copy(fontSize = 11.sp), color = c.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        // Input line; scrolls so the end (where you type) stays visible.
        val scroll = rememberScrollState()
        LaunchedEffect(model.displayText) { scroll.scrollTo(scroll.maxValue) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                model.displayText.ifEmpty { " " },
                style = Ranzen.type.mono.copy(fontSize = (18 * scale).sp, lineHeight = (24 * scale).sp),
                color = if (model.evaluated) c.inkMuted else c.ink,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.horizontalScroll(scroll),
            )
        }
        Spacer(Modifier.height(2.dp))
        val err = model.error
        when {
            err != null -> Text(
                err, style = Ranzen.type.label.copy(fontSize = (14 * scale).sp, textAlign = TextAlign.End),
                color = c.signal, modifier = Modifier.fillMaxWidth().height((40 * scale).dp), maxLines = 2,
            )
            else -> AutoFitText(
                text = model.result?.let { "= $it" } ?: preview?.let { "= $it" } ?: if (model.pieces.isEmpty()) "0" else " ",
                style = big,
                color = if (model.result != null || model.pieces.isEmpty()) c.ink else c.inkFaint,
            )
        }
    }
}

@Composable
private fun Annunciator(text: String, on: Boolean) {
    Text(
        text,
        style = Ranzen.type.mono.copy(fontSize = 11.sp),
        color = if (on) Ranzen.colors.ink else Ranzen.colors.inkFaint.copy(alpha = 0.35f),
        modifier = Modifier.padding(end = 10.dp),
    )
}

/** One line of large digits that shrinks to fit rather than wrapping or clipping. */
@Composable
private fun AutoFitText(text: String, style: TextStyle, color: Color) {
    var size by remember(text, style) { mutableStateOf(style.fontSize) }
    Text(
        text,
        style = style.copy(fontSize = size),
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.fillMaxWidth(),
        onTextLayout = { if (it.hasVisualOverflow && size.value > 12f) size = (size.value * 0.9f).sp },
    )
}

private enum class KeyKind { DIGIT, OP, FUNC, ACTION, EQUALS }

private data class KeyDef(val label: String, val kind: KeyKind, val weight: Float = 1f, val action: CalculatorModel.() -> Unit)

private fun digit(d: String) = KeyDef(d, KeyKind.DIGIT) { digit(d) }
private fun fn(label: String, display: String, engine: String) = KeyDef(label, KeyKind.FUNC) { insert(Piece(display, engine)) }

@Composable
private fun Keypad(model: CalculatorModel, inverse: Boolean, scale: Float, onEquals: () -> Unit) {
    val row1 = if (!inverse) listOf(
        fn("sin", "sin(", "sin("), fn("cos", "cos(", "cos("), fn("tan", "tan(", "tan("),
        fn("ln", "ln(", "ln("), fn("log", "log(", "log("),
    ) else listOf(
        fn("sin⁻¹", "sin⁻¹(", "asin("), fn("cos⁻¹", "cos⁻¹(", "acos("), fn("tan⁻¹", "tan⁻¹(", "atan("),
        fn("eˣ", "e^(", "e^("), fn("10ˣ", "10^(", "10^("),
    )
    val row2 = if (!inverse) listOf(
        fn("√", "√(", "√("), KeyDef("x²", KeyKind.OP) { operator(Piece("²")) }, KeyDef("xʸ", KeyKind.OP) { operator(Piece("^")) },
        fn("π", "π", "π"), fn("e", "e", "e"),
    ) else listOf(
        fn("∛", "∛(", "∛("), KeyDef("x³", KeyKind.OP) { operator(Piece("³")) }, KeyDef("x⁻¹", KeyKind.OP) { operator(Piece("⁻¹", "^(-1)")) },
        fn("π", "π", "π"), fn("e", "e", "e"),
    )
    val row3 = listOf(
        fn("(", "(", "("), KeyDef(")", KeyKind.FUNC) { operator(Piece(")")) },
        KeyDef("%", KeyKind.OP) { operator(Piece("%")) }, KeyDef("n!", KeyKind.OP) { operator(Piece("!")) },
        KeyDef("ANS", KeyKind.FUNC) { insert(CalculatorModel.ANS) },
    )
    val row4 = listOf(digit("7"), digit("8"), digit("9"), KeyDef("÷", KeyKind.OP) { operator(Piece("÷")) }, KeyDef("⌫", KeyKind.ACTION) { backspace() })
    val row5 = listOf(digit("4"), digit("5"), digit("6"), KeyDef("×", KeyKind.OP) { operator(Piece("×")) }, KeyDef("AC", KeyKind.ACTION) { clearAll() })
    val row6 = listOf(digit("1"), digit("2"), digit("3"), KeyDef("−", KeyKind.OP) { operator(Piece("−")) })
    val row7 = listOf(KeyDef("0", KeyKind.DIGIT, 2f) { digit("0") }, KeyDef(",", KeyKind.DIGIT) { comma() }, KeyDef("+", KeyKind.OP) { operator(Piece("+")) })

    val gap = 8.dp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        // Scientific rows are a little lower than the number block.
        listOf(row1, row2, row3).forEach { KeyRow(it, model, scale, Modifier.weight(0.82f), gap) }
        listOf(row4, row5).forEach { KeyRow(it, model, scale, Modifier.weight(1f), gap) }
        Row(Modifier.weight(2f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Column(Modifier.weight(4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                KeyRow(row6, model, scale, Modifier.weight(1f), gap)
                KeyRow(row7, model, scale, Modifier.weight(1f), gap)
            }
            CalcKey(KeyDef("=", KeyKind.EQUALS) { }, scale, Modifier.weight(1f).fillMaxHeight()) { onEquals() }
        }
    }
}

@Composable
private fun KeyRow(keys: List<KeyDef>, model: CalculatorModel, scale: Float, modifier: Modifier, gap: Dp) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        keys.forEach { k -> CalcKey(k, scale, Modifier.weight(k.weight).fillMaxHeight()) { k.action(model) } }
    }
}

@Composable
private fun CalcKey(key: KeyDef, scale: Float, modifier: Modifier, onClick: () -> Unit) {
    val c = Ranzen.colors
    val mono = Ranzen.type.mono
    val (style, color) = when (key.kind) {
        KeyKind.DIGIT -> mono.copy(fontSize = (22 * scale).sp) to c.ink
        KeyKind.OP -> mono.copy(fontSize = (21 * scale).sp) to c.signal
        KeyKind.FUNC -> mono.copy(fontSize = (15 * scale).sp) to c.ink.copy(alpha = 0.8f)
        KeyKind.ACTION -> mono.copy(fontSize = (17 * scale).sp) to c.signal
        KeyKind.EQUALS -> mono.copy(fontSize = (28 * scale).sp) to c.onSignal
    }
    TactileKey(onClick = onClick, modifier = modifier, corner = 14.dp, contentDescription = key.label) {
        if (key.kind == KeyKind.EQUALS) {
            Box(Modifier.fillMaxSize().background(c.signal), contentAlignment = Alignment.Center) { KeyText(key.label, style, color) }
        } else {
            KeyText(key.label, style, color)
        }
    }
}

@Composable
private fun HistoryList(entries: List<HistoryEntry>, onPick: (HistoryEntry) -> Unit, onClear: () -> Unit) {
    val c = Ranzen.colors
    Column(Modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Noch keine Rechnungen", style = Ranzen.type.body, color = c.inkMuted)
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(entries) { e ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPick(e) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(e.display.joinToString(""), style = Ranzen.type.mono, color = c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("= ${CalculatorModel.format(e.value)}", style = Ranzen.type.mono.copy(fontSize = 20.sp), color = c.ink, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            QuietButton("Verlauf leeren", onClear, color = c.signal)
        }
    }
}
