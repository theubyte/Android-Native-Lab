package de.millione.schulranzen.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.PeriodTime
import de.millione.schulranzen.core.study.Timetable
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.study.data.Exam
import de.millione.schulranzen.study.data.Lesson
import de.millione.schulranzen.study.ui.Fmt
import java.time.LocalDate
import java.util.Locale

/** Consecutive periods of the same lesson, shown as one block ("1–2"). */
internal data class LessonBlock(val lesson: Lesson, val first: Int, val last: Int, val start: Int?, val end: Int?) {
    val label: String get() = if (first == last) "$first." else "$first.–$last."
}

internal fun blocksOf(slots: List<LessonSlot>): List<LessonBlock> {
    val out = mutableListOf<LessonBlock>()
    for (s in slots) {
        val prev = out.lastOrNull()
        if (prev != null && prev.last == s.lesson.period - 1 && sameBlock(prev.lesson, s.lesson)) {
            out[out.lastIndex] = prev.copy(last = s.lesson.period, end = s.period?.endMinute ?: prev.end)
        } else {
            out += LessonBlock(s.lesson, s.lesson.period, s.lesson.period, s.period?.startMinute, s.period?.endMinute)
        }
    }
    return out
}

/**
 * Compact strip for the home screen: today's lessons in order (the current
 * one ringed, past ones faded) and the next exam with its countdown.
 * [onClick] opens the timetable when given.
 */
@Composable
fun TodayStrip(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    val repo = remember { StudyRepository(context.applicationContext) }
    val slots by repo.todaysLessons.collectAsState(initial = emptyList())
    val exams by repo.nextExams.collectAsState(initial = emptyList())
    val today by repo.today.collectAsState(initial = LocalDate.now())
    val next by repo.nextStep.collectAsState(initial = null)
    val minute = rememberMinuteOfDay()
    val c = Ranzen.colors

    PaperPanel(modifier, corner = 20.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(78.dp)) {
                Text("HEUTE", style = Ranzen.type.caption, color = c.inkMuted)
                Text(Fmt.dayShort(today.dayOfWeek.value).uppercase(Locale.GERMANY) + " " + today.format(Fmt.shortDate), style = Ranzen.type.mono, color = c.ink)
            }
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val blocks = blocksOf(slots)
                if (blocks.isEmpty()) Text("Kein Unterricht eingetragen", style = Ranzen.type.body, color = c.inkMuted)
                blocks.forEach { b -> LessonTicket(b, minute) }
            }
            exams.firstOrNull()?.let { e ->
                Spacer(Modifier.width(14.dp))
                Box(Modifier.width(1.dp).height(44.dp).background(c.line))
                Spacer(Modifier.width(14.dp))
                ExamTicket(e, today, next?.takeIf { it.first.id == e.id }?.second)
            }
        }
    }
}

@Composable
private fun LessonTicket(b: LessonBlock, minute: Int) {
    val c = Ranzen.colors
    val now = b.start != null && b.end != null && minute in b.start until b.end
    val past = b.end != null && minute >= b.end
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.alpha(if (past) 0.45f else 1f).height(52.dp).clip(shape)
            .background(if (now) c.raised else androidx.compose.ui.graphics.Color.Transparent)
            .border(if (now) 1.5.dp else 1.dp, if (now) c.signal else c.line, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(SubjectPalette.of(b.lesson.colorIndex)))
        Column(Modifier.padding(horizontal = 10.dp).widthIn(max = 180.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.label, style = Ranzen.type.mono, color = if (now) c.signal else c.inkMuted)
                Spacer(Modifier.width(6.dp))
                Text(b.lesson.subject, style = Ranzen.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val meta = listOfNotNull(b.start?.let(Timetable::formatTime), b.lesson.room.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = Ranzen.type.mono.copy(fontSize = Ranzen.type.caption.fontSize), color = c.inkMuted, maxLines = 1)
        }
    }
}

@Composable
private fun ExamTicket(e: Exam, today: LocalDate, step: de.millione.schulranzen.core.study.PathStage?) {
    val c = Ranzen.colors
    val date = LocalDate.ofEpochDay(e.dateEpochDay)
    val days = Fmt.daysUntil(today, date)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Lamp(SubjectPalette.of(e.colorIndex), size = 9.dp, glow = days <= 3)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.widthIn(max = 220.dp)) {
            Text("${ExamKind.of(e.kind).label} · ${e.subject}", style = Ranzen.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Fmt.countdown(today, date).uppercase(Locale.GERMANY), style = Ranzen.type.mono, color = if (days <= 3) c.signal else c.inkMuted)
            // The next step of the learning path, so the home screen says what to do, not only when.
            if (step != null) Text("→ " + stageTitle(step.kind), style = Ranzen.type.caption, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Times of a period list by index, for blocks without slot data. */
internal fun List<PeriodTime>.byIndex(): Map<Int, PeriodTime> = associateBy { it.index }
