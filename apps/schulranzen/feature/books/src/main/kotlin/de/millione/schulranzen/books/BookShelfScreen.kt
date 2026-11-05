package de.millione.schulranzen.books

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Weiterlesen": the book read last on this device, with its page and how far
 * through it is. Shows nothing until a book was opened. For the tablet's
 * library as well as [BookShelfGrid].
 */
@Composable
fun ContinueReadingCard(shelf: BookShelf, onOpen: (BookInfo) -> Unit, modifier: Modifier = Modifier) {
    val book by shelf.lastRead.collectAsState(initial = null)
    val b = book ?: return
    val c = Ranzen.colors
    val cover by produceState<ImageBitmap?>(null, b.cover?.path) {
        value = b.cover?.let { f -> withContext(Dispatchers.IO) { runCatching { android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() } }
    }
    PaperPanel(modifier.fillMaxWidth(), corner = 18.dp, depth = 0.7f) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(18.dp))
                .clickable(onClickLabel = "Weiterlesen") { onOpen(b) }
                .semantics(mergeDescendants = true) {
                    contentDescription = "Weiterlesen: ${b.title}, Seite ${b.lastPage + 1} von ${b.pageCount}, ${b.percent} Prozent gelesen"
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 56.dp, height = 76.dp).clip(RoundedCornerShape(6.dp)).background(c.raised), contentAlignment = Alignment.Center) {
                val image = cover
                if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else RanzenIcon(BookIcons.Read, tint = c.inkFaint)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("WEITERLESEN", style = Ranzen.type.caption, color = c.inkMuted, maxLines = 1)
                Text(b.title, style = Ranzen.type.heading, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text("Seite ${b.lastPage + 1} von ${b.pageCount} · ${b.percent} %", style = Ranzen.type.caption, color = c.inkMuted, maxLines = 1)
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
                    Box(Modifier.fillMaxWidth(b.progress.coerceIn(0.02f, 1f)).height(3.dp).background(c.signal))
                }
            }
        }
    }
}

/**
 * All books as a grid, "Weiterlesen" on top; fits from 360 dp upwards. The
 * long press belongs to the host (edit the book) and does nothing on a
 * phone, see [BookCover]. [accent] and [subject] give a book its subject's
 * colour and name, when the host knows them.
 */
@Composable
fun BookShelfGrid(
    shelf: BookShelf,
    onOpen: (BookInfo) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (BookInfo) -> Unit = {},
    accent: (BookInfo) -> androidx.compose.ui.graphics.Color? = { null },
    subject: (BookInfo) -> String? = { null },
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val c = Ranzen.colors
    val books by shelf.books.collectAsState(initial = null)
    val list = books
    if (list != null && list.isEmpty()) {
        Box(modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            Text("Noch keine Bücher.", style = Ranzen.type.body, color = c.inkMuted)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "continue") { ContinueReadingCard(shelf, onOpen) }
        items(list.orEmpty(), key = { it.id }) { b ->
            BookCover(b, onClick = { onOpen(b) }, onLongClick = { onLongClick(b) }, accent = accent(b), subject = subject(b))
        }
    }
}
