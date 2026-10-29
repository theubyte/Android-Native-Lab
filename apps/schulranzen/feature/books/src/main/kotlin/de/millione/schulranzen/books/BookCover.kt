package de.millione.schulranzen.books

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.softRaise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A school book on the shelf: a hardcover with a cloth spine in the subject
 * colour ([accent]), the first page as its cover, the page block showing at
 * the edges and a label with title and reading progress. Same size and
 * press feel as the notebook covers, but clearly a book.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCover(
    book: BookInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    subject: String? = null,
) {
    val c = Ranzen.colors
    val spineColor = accent ?: Color(0xFF5B5A57)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "s")
    val depth by animateFloatAsState(if (pressed) 0.3f else 1f, label = "d")
    val coverFile = book.cover
    val stamp = coverFile?.lastModified() ?: 0L
    val image by produceState<ImageBitmap?>(null, coverFile?.path, stamp) {
        value = coverFile?.let { f -> withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() } }
    }
    val board = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .scale(s)
            .combinedClickable(source, indication = null, onClick = onClick, onLongClick = onLongClick),
    ) {
        // The page block, peeking out right and below the boards.
        Box(
            Modifier.fillMaxSize().padding(start = 10.dp, top = 6.dp)
                .drawBehind {
                    val r = CornerRadius(6.dp.toPx())
                    drawRoundRect(lerp(c.paper, Color.White, 0.4f), cornerRadius = r)
                    val step = 2.2.dp.toPx()
                    var k = 1
                    while (k <= 3) {
                        val o = k * step
                        drawLine(c.line, Offset(size.width - o, 8.dp.toPx()), Offset(size.width - o, size.height - o), 0.8.dp.toPx())
                        drawLine(c.line, Offset(12.dp.toPx(), size.height - o), Offset(size.width - o, size.height - o), 0.8.dp.toPx())
                        k++
                    }
                },
        )
        // Front board
        Box(
            Modifier
                .fillMaxSize()
                .padding(end = 7.dp, bottom = 7.dp)
                .softRaise(10.dp, depth, c.bevelLight, c.bevelShadow)
                .clip(board)
                .background(lerp(spineColor, c.raised, 0.82f)),
        ) {
            val spine = 15.dp
            Box(Modifier.fillMaxSize().padding(start = spine + 2.dp)) {
                image?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                }
                // A soft sheen like a laminated cover.
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent, Color.Black.copy(alpha = 0.06f)))))
            }
            // Cloth spine with two ribs and the hinge groove.
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    val w = spine.toPx()
                    drawRect(spineColor, size = Size(w, size.height))
                    drawRect(Color.White.copy(alpha = 0.10f), size = Size(w * 0.35f, size.height))
                    val rib = 2.dp.toPx()
                    for (y in listOf(16.dp.toPx(), 22.dp.toPx(), size.height - 22.dp.toPx() - rib, size.height - 16.dp.toPx() - rib)) {
                        drawRect(Color(0xFFE8D9A8).copy(alpha = 0.75f), topLeft = Offset(0f, y), size = Size(w, rib))
                    }
                    drawRect(Color.Black.copy(alpha = 0.22f), topLeft = Offset(w, 0f), size = Size(1.5.dp.toPx(), size.height))
                    drawRect(Color.White.copy(alpha = 0.18f), topLeft = Offset(w + 1.5.dp.toPx(), 0f), size = Size(1.dp.toPx(), size.height))
                    drawContent()
                },
            )
            // Title label at the bottom, like a library sticker.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = spine + 10.dp, end = 10.dp, bottom = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.paper.copy(alpha = 0.96f))
                    .border(1.dp, c.line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(book.title, style = Ranzen.type.label, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(subject ?: "${book.pageCount} S.", style = Ranzen.type.caption, color = c.inkMuted, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (book.openedAt > 0) Text("S. ${book.lastPage + 1}", style = Ranzen.type.caption, color = c.inkFaint)
                }
                if (book.openedAt > 0) {
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
                        Box(Modifier.fillMaxWidth(book.progress.coerceIn(0.02f, 1f)).height(3.dp).background(spineColor))
                    }
                }
            }
        }
    }
}
