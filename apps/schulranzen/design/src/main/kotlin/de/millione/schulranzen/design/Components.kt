package de.millione.schulranzen.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter

/**
 * Soft 3D: a light edge above-left and a blurred shadow below-right, like a
 * moulded key. [depth] 0 = flush, 1 = fully raised; a pressed key sinks.
 */
fun Modifier.softRaise(radius: Dp, depth: Float, light: Color, shadow: Color): Modifier = drawBehind {
    if (depth <= 0.01f) return@drawBehind
    val r = radius.toPx()
    val blur = 10.dp.toPx() * depth
    val off = 3.dp.toPx() * depth
    drawIntoCanvas { canvas ->
        val paint = Paint()
        val fp = paint.asFrameworkPaint()
        fp.isAntiAlias = true
        fp.color = android.graphics.Color.TRANSPARENT
        fp.setShadowLayer(blur, off, off, shadow.copy(alpha = shadow.alpha * depth).toArgb())
        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, fp)
        fp.setShadowLayer(blur * 0.7f, -off * 0.6f, -off * 0.6f, light.copy(alpha = light.alpha * depth).toArgb())
        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, fp)
    }
}

/**
 * The basic control: a moulded key that sinks when pressed and glows at its
 * edge in the signal colour when selected.
 */
@Composable
fun TactileKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: Dp = 48.dp,
    corner: Dp = 14.dp,
    accent: Color = Ranzen.colors.signal,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val c = Ranzen.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val depth by animateFloatAsState(
        when { pressed -> 0.15f; selected -> 0.35f; else -> 1f },
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), label = "depth",
    )
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.5f), label = "scale")
    val edge by animateColorAsState(if (selected) accent else Color.Transparent, label = "edge")
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .size(size)
            .scale(scale)
            .softRaise(corner, depth, c.bevelLight, c.bevelShadow)
            .clip(shape)
            .background(if (selected) c.paper else c.raised)
            .border(if (selected) 1.5.dp else 0.5.dp, if (selected) edge else c.line.copy(alpha = 0.6f), shape)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A line icon in the current ink colour (or [tint]). */
@Composable
fun RanzenIcon(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = Ranzen.colors.ink, size: Dp = 24.dp) {
    Image(icon, contentDescription = null, modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/** A sheet of paper: panels, menus, dialogs. Raised a little above the desk. */
@Composable
fun PaperPanel(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    depth: Float = 1f,
    content: @Composable () -> Unit,
) {
    val c = Ranzen.colors
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .softRaise(corner, depth, c.bevelLight, c.bevelShadow)
            .clip(shape)
            .background(c.paper)
            .border(0.5.dp, c.line, shape),
    ) { content() }
}

/** A small colour lamp, used for subject accents and the current pen colour. */
@Composable
fun Lamp(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp, glow: Boolean = true) {
    Box(
        modifier
            .size(size)
            .drawBehind {
                if (glow) drawCircle(color.copy(alpha = 0.28f), radius = this.size.minDimension * 0.95f)
            }
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * Folder register: the subjects as tabs along the top edge, like the index
 * tabs of a ring binder. The selected tab stands up and shows its colour.
 */
@Composable
fun RegisterTabs(
    tabs: List<Pair<String, Color?>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ranzen.colors
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        tabs.forEachIndexed { i, (label, color) ->
            val active = i == selected
            val lift by animateDpAsState(if (active) 0.dp else 6.dp, spring(dampingRatio = 0.6f), label = "lift")
            val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
            Box(
                Modifier
                    .padding(top = lift, end = 6.dp)
                    .clip(shape)
                    .background(if (active) c.paper else c.raised.copy(alpha = if (c.isDark) 0.6f else 0.7f))
                    .border(0.5.dp, c.line, shape)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (color != null) {
                        Lamp(color, size = 8.dp, glow = active)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(label, style = Ranzen.type.label, color = if (active) c.ink else c.inkMuted)
                }
            }
        }
    }
}

/** Signal-coloured main action, e.g. "Neues Heft". */
@Composable
fun SignalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Ranzen.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val depth by animateFloatAsState(if (pressed) 0.15f else 1f, spring(dampingRatio = 0.55f), label = "d")
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .height(48.dp)
            .softRaise(16.dp, depth, Color.Transparent, c.signal.copy(alpha = 0.45f))
            .clip(shape)
            .background(c.signal)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            RanzenIcon(icon, tint = c.onSignal, size = 20.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = Ranzen.type.label, color = c.onSignal)
    }
}

/** Quiet text button for secondary actions. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ranzen.colors.inkMuted) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) { Text(text, style = Ranzen.type.label, color = color) }
}

/** Thin engraved divider line. */
fun Modifier.engravedLine(color: Color, light: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
    drawLine(light, Offset(0f, 1.dp.toPx()), Offset(size.width, 1.dp.toPx()), 1.dp.toPx())
}
