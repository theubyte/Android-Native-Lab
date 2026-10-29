package de.millione.schulranzen.study.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.design.TactileKey
import de.millione.schulranzen.design.softRaise
import de.millione.schulranzen.study.StudySubject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayTextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Own line icons of the study feature, drawn in the house style. */
internal object StudyIcons {
    val Grid = RanzenIcons.line("grid") {
        moveTo(4f, 4.5f); lineTo(20f, 4.5f); lineTo(20f, 19.5f); lineTo(4f, 19.5f); close()
        moveTo(4f, 9.5f); lineTo(20f, 9.5f); moveTo(4f, 14.5f); lineTo(20f, 14.5f)
        moveTo(9.5f, 4.5f); lineTo(9.5f, 19.5f); moveTo(15f, 4.5f); lineTo(15f, 19.5f)
    }
    val Cards = RanzenIcons.line("cards") {
        moveTo(7f, 7.5f); lineTo(19.5f, 7.5f); lineTo(19.5f, 18.5f); lineTo(7f, 18.5f); close()
        moveTo(4.5f, 15.5f); lineTo(4.5f, 5f); lineTo(16.5f, 5f)
    }
    val Calendar = RanzenIcons.line("calendar") {
        moveTo(4.5f, 6f); lineTo(19.5f, 6f); lineTo(19.5f, 20f); lineTo(4.5f, 20f); close()
        moveTo(4.5f, 10f); lineTo(19.5f, 10f); moveTo(8.5f, 3.5f); lineTo(8.5f, 7.5f); moveTo(15.5f, 3.5f); lineTo(15.5f, 7.5f)
        moveTo(8f, 14f); lineTo(9f, 14f); moveTo(11.5f, 14f); lineTo(12.5f, 14f); moveTo(15f, 14f); lineTo(16f, 14f)
    }
    val Flip = RanzenIcons.line("flip") {
        moveTo(4.5f, 9f); curveTo(5.8f, 5.8f, 8.7f, 4f, 12f, 4f); curveTo(15.3f, 4f, 18.2f, 5.8f, 19.5f, 9f)
        moveTo(19.5f, 4.5f); lineTo(19.5f, 9f); lineTo(15f, 9f)
        moveTo(19.5f, 15f); curveTo(18.2f, 18.2f, 15.3f, 20f, 12f, 20f); curveTo(8.7f, 20f, 5.8f, 18.2f, 4.5f, 15f)
        moveTo(4.5f, 19.5f); lineTo(4.5f, 15f); lineTo(9f, 15f)
    }
    val Play = RanzenIcons.line("play") { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }
    val Edit = RanzenIcons.line("edit") {
        moveTo(4.5f, 19.5f); lineTo(5.3f, 15.7f); lineTo(15.6f, 5.4f); lineTo(18.6f, 8.4f); lineTo(8.3f, 18.7f); close()
        moveTo(13.6f, 7.4f); lineTo(16.6f, 10.4f)
    }
    val Clock = RanzenIcons.line("clock") {
        moveTo(12f, 4f); arcToRelative(8f, 8f, 0f, true, true, 0f, 16f); arcToRelative(8f, 8f, 0f, true, true, 0f, -16f)
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
    }
    val Chevron = RanzenIcons.line("chevron") { moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f) }
}

internal object Fmt {
    private val de = Locale.GERMANY
    val date: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", de)
    val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.", de)
    val dayDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EE dd.MM.", de)
    val longDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", de)
    val month: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", de)

    fun dayShort(isoDay: Int): String = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")[isoDay - 1]
    fun dayLong(isoDay: Int): String = java.time.DayOfWeek.of(isoDay).getDisplayName(DayTextStyle.FULL, de)

    fun daysUntil(today: LocalDate, date: LocalDate): Long = ChronoUnit.DAYS.between(today, date)

    /** "heute", "morgen", "in 5 Tagen", "vorbei". */
    fun countdown(today: LocalDate, date: LocalDate): String = when (val d = daysUntil(today, date)) {
        0L -> "heute"
        1L -> "morgen"
        2L -> "übermorgen"
        in 3L..Long.MAX_VALUE -> "in $d Tagen"
        -1L -> "gestern"
        else -> "vor ${-d} Tagen"
    }

    /** Short interval for the rating keys: "heute", "1 T", "3 W", "2 M". */
    fun interval(days: Int): String = when {
        days <= 0 -> "heute"
        days < 14 -> "$days T"
        days < 60 -> "${days / 7} W"
        days < 365 -> "${days / 30} M"
        else -> "${days / 365} J"
    }
}

/** Screen title row: back key, title with a small mono subtitle, actions on the right. */
@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    accent: Color? = null,
    actions: @Composable () -> Unit = {},
) {
    val c = Ranzen.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        TactileKey(onBack, contentDescription = "Zurück") { RanzenIcon(RanzenIcons.Back) }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Ranzen.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Lamp(accent ?: c.signal, size = 7.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(subtitle.uppercase(Locale.GERMANY), style = Ranzen.type.mono, color = c.inkMuted, maxLines = 1)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** Page frame: desk background, safe-area padding, a header and the content below. */
@Composable
internal fun StudyPage(header: @Composable () -> Unit, content: @Composable () -> Unit) {
    val c = Ranzen.colors
    Box(Modifier.fillMaxSize().background(c.desk)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 32.dp)) {
            Spacer(Modifier.height(22.dp))
            header()
            Spacer(Modifier.height(20.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/**
 * A sheet of paper that slides up over a dimmed desk, used instead of
 * Android dialogs. Tapping beside it or pressing back closes it.
 */
@Composable
internal fun SheetHost(visible: Boolean, onDismiss: () -> Unit, width: Dp = 560.dp, content: @Composable () -> Unit) {
    val c = Ranzen.colors
    if (visible) {
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().background(c.desk.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { onDismiss() } })
    }
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible,
            enter = fadeIn() + scaleIn(spring(dampingRatio = 0.7f), initialScale = 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f),
        ) {
            PaperPanel(corner = 26.dp) {
                Column(
                    // Swallow taps on the sheet so they do not reach the scrim below.
                    Modifier.pointerInput(Unit) { detectTapGestures { } }
                        .widthIn(max = width).verticalScroll(rememberScrollState()).padding(30.dp),
                ) { content() }
            }
        }
    }
}

/** Buttons at the foot of a sheet. */
@Composable
internal fun SheetActions(
    primary: String,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "Löschen",
    cancelLabel: String = "Abbrechen",
) {
    val c = Ranzen.colors
    var confirm by remember { mutableStateOf(false) }
    Spacer(Modifier.height(28.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onDelete != null) {
            // Two taps to delete: no extra dialog, but no accident either.
            QuietButton(if (confirm) "Wirklich löschen?" else deleteLabel, { if (confirm) onDelete() else confirm = true }, color = c.signal)
        }
        Spacer(Modifier.weight(1f))
        QuietButton(cancelLabel, onCancel)
        Spacer(Modifier.width(10.dp))
        SignalButton(primary, onPrimary)
    }
}

@Composable
internal fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(Locale.GERMANY), style = Ranzen.type.caption, color = Ranzen.colors.inkMuted, modifier = modifier.padding(bottom = 8.dp))
}

/** Text field as a written line on paper: no box, just a rule beneath. */
@Composable
internal fun LineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    style: TextStyle = Ranzen.type.body,
    minLines: Int = 1,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
) {
    val c = Ranzen.colors
    BasicTextField(
        value, onValueChange,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        keyboardOptions = KeyboardOptions(capitalization = capitalization),
        textStyle = style.copy(color = c.ink),
        cursorBrush = SolidColor(c.signal),
        modifier = modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 8.dp)
            .drawBehind { drawLine(c.line, Offset(0f, size.height + 6.dp.toPx()), Offset(size.width, size.height + 6.dp.toPx()), 1.5.dp.toPx()) },
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style, color = c.inkFaint)
                inner()
            }
        },
    )
}

/** A selectable plate with a lamp, used for options instead of chips. */
@Composable
internal fun OptionPlate(text: String, selected: Boolean, onClick: () -> Unit, lamp: Color? = null, modifier: Modifier = Modifier) {
    val c = Ranzen.colors
    val edge by animateColorAsState(if (selected) (lamp ?: c.signal) else c.line, label = "edge")
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.heightIn(min = 48.dp).clip(shape)
            .background(if (selected) c.raised else Color.Transparent)
            .border(if (selected) 1.5.dp else 1.dp, edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lamp != null) {
            Lamp(lamp, size = 8.dp, glow = selected)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Ranzen.type.label, color = if (selected) c.ink else c.inkMuted)
    }
}

/** Subject name with colour: free text, quick picks from known subjects, colour lamps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubjectPicker(
    name: String,
    colorIndex: Int,
    known: List<StudySubject>,
    onChange: (name: String, colorIndex: Int, subjectId: Long?) -> Unit,
    placeholder: String = "z. B. Lernfeld 3",
) {
    FieldLabel("Fach")
    LineField(name, { onChange(it, colorIndex, null) }, placeholder, style = Ranzen.type.heading)
    if (known.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            known.forEach { s ->
                OptionPlate(s.name, s.name.equals(name.trim(), ignoreCase = true), { onChange(s.name, s.colorIndex, s.id) }, SubjectPalette.of(s.colorIndex))
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    FieldLabel("Farbe")
    ColorRow(colorIndex) { onChange(name, it, null) }
}

@Composable
internal fun ColorRow(selected: Int, onSelect: (Int) -> Unit) {
    val c = Ranzen.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SubjectPalette.colors.forEachIndexed { i, col ->
            val active = Math.floorMod(selected, SubjectPalette.colors.size) == i
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable { onSelect(i) }
                    .border(if (active) 1.5.dp else 0.dp, if (active) c.ink else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Lamp(col, size = 22.dp, glow = active) }
        }
    }
}

/** A sliding switch like on an old device: a small key in a recessed track. */
@Composable
internal fun RetroSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    val c = Ranzen.colors
    val x by animateDpAsState(if (checked) 22.dp else 0.dp, spring(dampingRatio = 0.6f), label = "x")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Ranzen.type.body, color = c.ink)
            if (hint != null) Text(hint, style = Ranzen.type.caption, color = c.inkMuted)
        }
        Box(
            Modifier.size(width = 52.dp, height = 30.dp).clip(RoundedCornerShape(15.dp))
                .background(if (checked) c.signal.copy(alpha = 0.85f) else c.line)
                .padding(3.dp),
        ) {
            Box(
                Modifier.offset(x = x).size(24.dp)
                    .softRaise(12.dp, 0.6f, c.bevelLight, c.bevelShadow)
                    .clip(CircleShape).background(c.raised),
            )
        }
    }
}

/**
 * A small inset display with monospace digits, like the counter window of
 * an old device. [value] is the big number, [label] the engraved caption.
 */
@Composable
internal fun Counter(value: String, label: String, modifier: Modifier = Modifier, accent: Color? = null) {
    val c = Ranzen.colors
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Box(
            Modifier.clip(RoundedCornerShape(10.dp))
                .background(c.ink.copy(alpha = if (c.isDark) 0.10f else 0.88f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) { Text(value, style = Ranzen.type.monoLarge, color = accent ?: if (c.isDark) c.ink else c.paper) }
        Spacer(Modifier.height(6.dp))
        Text(label.uppercase(Locale.GERMANY), style = Ranzen.type.caption, color = c.inkMuted)
    }
}

/** Section heading inside a panel: title, mono count, optional action. */
@Composable
internal fun SectionHead(title: String, count: Int?, action: (@Composable () -> Unit)? = null) {
    val c = Ranzen.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Ranzen.type.heading, color = c.ink)
        if (count != null) {
            Spacer(Modifier.width(10.dp))
            Text("%02d".format(count), style = Ranzen.type.mono, color = c.inkMuted)
        }
        Spacer(Modifier.weight(1f))
        action?.invoke()
    }
}

/** Quiet hint text for empty lists. */
@Composable
internal fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Ranzen.type.body, color = Ranzen.colors.inkMuted, modifier = modifier.padding(vertical = 12.dp))
}

/** Small icon key with a text, for secondary actions in headers. */
@Composable
internal fun IconKey(icon: ImageVector, description: String, onClick: () -> Unit, selected: Boolean = false) {
    TactileKey(onClick, selected = selected, contentDescription = description) { RanzenIcon(icon) }
}

/**
 * Month calendar for choosing a date, drawn like a desk calendar page.
 * Weeks start on Monday; [today] is ringed, the chosen day filled.
 */
@Composable
internal fun DatePad(selected: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val c = Ranzen.colors
    var month by remember { mutableStateOf(selected.withDayOfMonth(1)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable { month = month.minusMonths(1) }, contentAlignment = Alignment.Center) {
                RanzenIcon(RanzenIcons.Back, tint = c.inkMuted, size = 20.dp)
            }
            Text(
                month.format(Fmt.month).replaceFirstChar { it.titlecase(Locale.GERMANY) },
                style = Ranzen.type.heading, color = c.ink, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
            )
            Box(Modifier.size(48.dp).clip(CircleShape).clickable { month = month.plusMonths(1) }, contentAlignment = Alignment.Center) {
                RanzenIcon(StudyIcons.Chevron, tint = c.inkMuted, size = 20.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            (1..7).forEach { d ->
                Text(Fmt.dayShort(d).uppercase(Locale.GERMANY), style = Ranzen.type.caption, color = if (d >= 6) c.inkFaint else c.inkMuted,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
        val lead = month.dayOfWeek.value - 1
        val days = month.lengthOfMonth()
        val rows = (lead + days + 6) / 7
        for (r in 0 until rows) {
            Row {
                for (col in 0 until 7) {
                    val n = r * 7 + col - lead + 1
                    Box(Modifier.weight(1f).height(46.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..days) {
                            val d = month.withDayOfMonth(n)
                            val isSel = d == selected
                            val isToday = d == today
                            Box(
                                Modifier.size(42.dp).clip(CircleShape)
                                    .background(if (isSel) c.signal else Color.Transparent)
                                    .border(if (isToday && !isSel) 1.dp else 0.dp, if (isToday && !isSel) c.ink else Color.Transparent, CircleShape)
                                    .clickable { onSelect(d) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$n", style = Ranzen.type.mono,
                                    color = when { isSel -> c.onSignal; d < today -> c.inkFaint; col >= 5 -> c.inkMuted; else -> c.ink },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The last non-null [value]. Sheets keep showing their content while they
 * animate out, after the caller already cleared what they were showing.
 */
@Composable
internal fun <T : Any> latestNonNull(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
}
