package de.millione.schulranzen.app.notebook

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.books.BookInfo
import de.millione.schulranzen.books.BookShelf
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.ink.NotebookCanvasView
import kotlin.math.roundToInt

/**
 * Notebook on the left, book on the right, with a divider that can be
 * dragged. The writing view lives outside Compose (under it), so its width
 * is set here to match the notebook pane; with no book it fills the window.
 */
@Composable
fun SplitWithBook(
    canvas: NotebookCanvasView,
    bookId: String?,
    notebook: @Composable (compact: Boolean) -> Unit,
    book: @Composable (String) -> Unit,
) {
    var split by rememberSaveable { mutableFloatStateOf(0.5f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val total = constraints.maxWidth
        val handle = with(density) { 22.dp.roundToPx() }
        val left = if (bookId == null) total else (total * split).roundToInt() - handle / 2
        SideEffect {
            val w = if (bookId == null) MATCH_PARENT else left
            val lp = canvas.layoutParams
            if (lp == null || lp.width != w) canvas.layoutParams = FrameLayout.LayoutParams(w, MATCH_PARENT)
        }
        if (bookId == null) {
            notebook(false)
        } else Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(with(density) { left.toDp() }).fillMaxHeight()) { notebook(true) }
            Divider(onDrag = { dx ->
                // Neither side narrower than about a third of the screen.
                split = (split + dx / total).coerceIn(0.32f, 0.68f)
            }, onReset = { split = 0.5f })
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))) { book(bookId) }
        }
    }
}

/** Grip between the panes: drag to resize, double tap for half and half. */
@Composable
private fun Divider(onDrag: (Float) -> Unit, onReset: () -> Unit) {
    val c = Ranzen.colors
    Box(
        Modifier.width(22.dp).fillMaxHeight().background(c.desk)
            .pointerInput(Unit) { detectHorizontalDragGestures { change, dx -> change.consume(); onDrag(dx) } }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(c.inkFaint)) }
        }
    }
}

/** "Welches Buch daneben?" */
@Composable
fun BookPickerSheet(shelf: BookShelf, onPick: (BookInfo) -> Unit, onCancel: () -> Unit) {
    val c = Ranzen.colors
    val books by shelf.books.collectAsState(initial = emptyList())
    Box(
        Modifier.fillMaxSize().background(c.desk.copy(alpha = 0.55f)).pointerInput(Unit) { detectTapGestures { onCancel() } },
        contentAlignment = Alignment.Center,
    ) {
        PaperPanel(corner = 26.dp) {
            Column(Modifier.padding(28.dp).width(480.dp)) {
                Text("Buch daneben öffnen", style = Ranzen.type.title, color = c.ink)
                Spacer(Modifier.height(6.dp))
                Text("Links schreiben, rechts lesen. Den Trenner ziehen, doppelt tippen für halbe-halbe.", style = Ranzen.type.caption, color = c.inkMuted)
                Spacer(Modifier.height(16.dp))
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    books.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(b) }.padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RanzenIcon(RanzenIcons.Book, size = 22.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(b.title, style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
                            Text("${b.pageCount} S.", style = Ranzen.type.mono, color = c.inkMuted)
                        }
                    }
                    if (books.isEmpty()) Text("Noch kein Buch im Regal. In der Bibliothek „+ Buch (PDF) hinzufügen“.", style = Ranzen.type.body, color = c.inkMuted)
                }
                Spacer(Modifier.height(14.dp))
                Row { Spacer(Modifier.weight(1f)); QuietButton("Abbrechen", onCancel) }
            }
        }
    }
}
