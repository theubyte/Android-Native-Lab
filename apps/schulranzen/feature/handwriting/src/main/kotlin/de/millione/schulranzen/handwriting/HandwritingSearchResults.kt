package de.millione.schulranzen.handwriting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.engravedLine

/**
 * Hits from the handwriting index for the library search: notebook title
 * (from the integrator's [titleOf]), optional page label (e.g. "Seite 4")
 * and the found line with the word highlighted. A plain column (no lazy
 * list), so it can sit inside the library's own scrolling results.
 */
@Composable
fun HandwritingSearchResults(
    hits: List<Hit>,
    onOpen: (Hit) -> Unit,
    titleOf: (notebookId: String) -> String,
    modifier: Modifier = Modifier,
    pageLabelOf: (Hit) -> String? = { null },
    header: String? = "In der Handschrift",
) {
    if (hits.isEmpty()) return
    val c = Ranzen.colors
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Text(header, style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        hits.forEachIndexed { i, hit ->
            Row(
                Modifier.fillMaxWidth()
                    .then(if (i > 0) Modifier.engravedLine(c.line, c.bevelLight.copy(alpha = 0.4f)) else Modifier)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = "Öffnen") { onOpen(hit) }
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RanzenIcon(RanzenIcons.Notebook, size = 22.dp, tint = c.inkMuted)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val label = pageLabelOf(hit)
                    Text(
                        titleOf(hit.notebookId) + (label?.let { " · $it" } ?: ""),
                        style = Ranzen.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        highlighted(hit.snippet, hit.highlight, c.signal),
                        style = Ranzen.type.body, color = c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                RanzenIcon(RanzenIcons.Back, size = 18.dp, tint = c.inkFaint, modifier = Modifier.graphicsLayer { scaleX = -1f })
            }
        }
    }
}

private fun highlighted(text: String, range: IntRange?, color: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    if (range == null || range.first < 0 || range.last >= text.length || range.isEmpty()) { append(text); return@buildAnnotatedString }
    append(text.substring(0, range.first))
    withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(text.substring(range.first, range.last + 1)) }
    append(text.substring(range.last + 1))
}
