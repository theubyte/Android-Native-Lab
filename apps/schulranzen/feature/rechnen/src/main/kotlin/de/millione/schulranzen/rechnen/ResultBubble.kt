package de.millione.schulranzen.rechnen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.millione.schulranzen.core.math.MathResult
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons

/**
 * Small paper slip with the result of a circled calculation, placed by the
 * caller next to the handwriting. It springs open; a tap anywhere closes it.
 */
@Composable
fun ResultBubble(result: MathResult, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ranzen.colors
    val pop = remember(result) { Animatable(0.85f) }
    LaunchedEffect(result) { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f)) }
    PaperPanel(
        modifier
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = ((pop.value - 0.85f) / 0.15f).coerceIn(0f, 1f) }
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = "Schließen", onClick = onDismiss),
        corner = 18.dp,
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = 56.dp).padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lamp(c.signal, size = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(result.prettyExpression, style = Ranzen.type.mono.copy(fontSize = 13.sp), color = c.inkMuted, maxLines = 1)
                Text("= ${result.text}", style = Ranzen.type.monoLarge.copy(fontSize = 26.sp, lineHeight = 30.sp), color = c.ink, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            RanzenIcon(RanzenIcons.Close, tint = c.inkFaint, size = 16.dp)
        }
    }
}
