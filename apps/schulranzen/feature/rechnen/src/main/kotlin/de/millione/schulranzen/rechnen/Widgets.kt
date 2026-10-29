package de.millione.schulranzen.rechnen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.core.shk.FormulaCategory

/**
 * A surface set into the paper, the opposite of a raised key: the calculator
 * display and input fields. A soft inner shadow along the top edge sells the depth.
 */
internal fun Modifier.recessed(corner: Dp, fill: Color, edge: Color, shadow: Color): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .clip(shape)
        .background(fill)
        .drawWithContent {
            drawContent()
            val h = 7.dp.toPx()
            drawRect(Brush.verticalGradient(listOf(shadow, Color.Transparent), 0f, h), size = size.copy(height = h))
            drawRect(Brush.horizontalGradient(listOf(shadow.copy(alpha = shadow.alpha * 0.6f), Color.Transparent), 0f, h * 0.7f), size = size.copy(width = h * 0.7f))
        }
        .border(0.75.dp, edge, shape)
}

@Composable
internal fun Modifier.recessed(corner: Dp = 14.dp): Modifier {
    val c = Ranzen.colors
    val fill = if (c.isDark) Color(0xFF151412) else c.desk
    return recessed(corner, fill, c.line, c.bevelShadow.copy(alpha = if (c.isDark) 0.55f else 0.22f))
}

/** Subject-style accent per formula category: edges and lamps only. */
internal fun FormulaCategory.accent(): Color = when (this) {
    FormulaCategory.HEAT -> SubjectPalette.of(1)
    FormulaCategory.PIPES -> SubjectPalette.of(0)
    FormulaCategory.ELECTRIC -> SubjectPalette.of(2)
    FormulaCategory.BASICS -> SubjectPalette.of(4)
    FormulaCategory.DESIGN -> SubjectPalette.of(5)
}

/** Numeric input in a recessed well with its unit beside it. Accepts expressions like "2·15". */
@Composable
internal fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Decimal,
) {
    val c = Ranzen.colors
    Row(
        modifier
            .defaultMinSize(minHeight = 52.dp)
            .recessed(12.dp)
            .then(if (error) Modifier.border(1.dp, c.signal, RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = Ranzen.type.mono.copy(fontSize = 18.sp), color = c.inkFaint)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Ranzen.type.mono.copy(fontSize = 18.sp, color = c.ink),
                cursorBrush = SolidColor(c.signal),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
            )
        }
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Text(unit, style = Ranzen.type.mono, color = c.inkMuted)
        }
    }
}

/** A list row in the left column: lamp, title, optional mono subtitle; the chosen one sits on paper. */
@Composable
internal fun ListRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = Ranzen.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .defaultMinSize(minHeight = 56.dp)
            .clip(shape)
            .background(if (selected) c.raised else Color.Transparent)
            .border(if (selected) 0.75.dp else 0.dp, if (selected) c.line else Color.Transparent, shape)
            .drawWithContent {
                drawContent()
                if (selected) drawLine(accent, Offset(1.5.dp.toPx(), 12.dp.toPx()), Offset(1.5.dp.toPx(), size.height - 12.dp.toPx()), 3.dp.toPx())
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lamp(accent, size = 8.dp, glow = selected)
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = Ranzen.type.label, color = if (selected) c.ink else c.ink.copy(alpha = 0.86f))
            if (subtitle != null) Text(subtitle, style = Ranzen.type.mono.copy(fontSize = 12.sp), color = c.inkMuted, maxLines = 1)
        }
        if (trailing != null) trailing()
    }
}

/** Small upper-case section caption. */
@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Ranzen.colors.inkMuted) {
    Text(text.uppercase(), style = Ranzen.type.caption.copy(letterSpacing = 1.2.sp), color = color, modifier = modifier)
}

/** Text that sits inside a TactileKey. */
@Composable
internal fun KeyText(text: String, style: TextStyle, color: Color) {
    Text(text, style = style, color = color, maxLines = 1)
}

/** Plain text input in a recessed well (names, labels, notes). */
@Composable
internal fun TextLine(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    style: TextStyle = Ranzen.type.body,
) {
    val c = Ranzen.colors
    Box(modifier.defaultMinSize(minHeight = 52.dp).recessed(12.dp).padding(horizontal = 14.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style, color = c.inkFaint, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style.copy(color = c.ink),
            cursorBrush = SolidColor(c.signal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        )
    }
}

/** A small tactile tool key with an icon and a short label beneath (toolbars). */
@Composable
internal fun ToolKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val c = Ranzen.colors
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 3.dp)) {
        de.millione.schulranzen.design.TactileKey(
            onClick = { if (enabled) onClick() }, selected = selected, size = 48.dp, corner = 14.dp, contentDescription = label,
        ) {
            de.millione.schulranzen.design.RanzenIcon(icon, tint = (tint ?: c.ink).copy(alpha = if (enabled) 1f else 0.35f), size = 22.dp)
        }
        Text(label, style = Ranzen.type.caption, color = if (selected) c.ink else c.inkMuted, maxLines = 1)
    }
}

/**
 * Panel that slides in from the right edge over a dimmed backdrop: the house
 * replacement for dialogs and pickers. Tapping the backdrop closes it.
 */
@Composable
internal fun SidePanel(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 440.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val c = Ranzen.colors
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (c.isDark) 0.45f else 0.18f))
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        de.millione.schulranzen.design.PaperPanel(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(max = width).fillMaxWidth(0.92f).padding(vertical = 12.dp, horizontal = 12.dp),
            corner = 22.dp,
        ) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = Ranzen.type.heading, color = c.ink, modifier = Modifier.weight(1f))
                    de.millione.schulranzen.design.TactileKey(onClick = onDismiss, size = 48.dp, contentDescription = "Schließen") {
                        de.millione.schulranzen.design.RanzenIcon(de.millione.schulranzen.design.RanzenIcons.Close, size = 20.dp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}
