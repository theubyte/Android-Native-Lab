package de.millione.schulranzen.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.MockExam
import de.millione.schulranzen.core.study.PathStage
import de.millione.schulranzen.core.study.PathState
import de.millione.schulranzen.core.study.StageKind
import de.millione.schulranzen.core.study.StageState
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons

internal fun stageTitle(k: StageKind): String = when (k) {
    StageKind.MATERIAL -> "Stoff durcharbeiten"
    StageKind.CARDS -> "Karten schreiben"
    StageKind.LEARN -> "Karten lernen"
    StageKind.MOCK -> "Probeprüfung bestehen"
    StageKind.FINAL -> "Letzter Durchgang"
}

internal fun stageDetail(s: PathStage): String = when (s.kind) {
    StageKind.MATERIAL -> if (s.total == 0) "Stellen im Heft oder Buch als Prüfungsstoff markieren" else "${s.done} von ${s.total} Stellen erledigt"
    StageKind.CARDS -> "${s.done} von ${s.total} Karten"
    StageKind.LEARN -> if (s.total == 0) "Noch keine Karten" else "${s.done} von ${s.total} Karten sitzen"
    StageKind.MOCK -> if (s.done == 0) "Ziel: mindestens ${MockExam.SOLID_PERCENT.toInt()} %" else "Bestes Ergebnis ${s.done} % · Ziel ${s.total} %"
    StageKind.FINAL -> if (s.state == StageState.OPEN && s.progress == 0f) "Am Tag vor der Prüfung alles Fällige wiederholen" else "${s.done} von ${s.total} wiederholt"
}

/** One line for the home screen: "Karten lernen · 6 von 12 Karten sitzen". */
fun nextStepText(s: PathStage): String = "${stageTitle(s.kind)} · ${stageDetail(s)}"

/** Button text for the action of a stage, or null when the stage has none. */
internal fun stageAction(k: StageKind): String? = when (k) {
    StageKind.MATERIAL -> null
    StageKind.CARDS -> "Karten schreiben"
    StageKind.LEARN -> "Lernen"
    StageKind.MOCK -> "Probeprüfung"
    StageKind.FINAL -> "Wiederholen"
}

/** The learning path as a vertical row of stepping stones with progress. */
@Composable
internal fun PathSection(path: PathState, onAction: (StageKind) -> Unit) {
    val c = Ranzen.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Lernpfad", style = Ranzen.type.heading, color = c.ink)
        Spacer(Modifier.width(10.dp))
        Text("%d %%".format((path.progress * 100).toInt()), style = Ranzen.type.mono, color = c.inkMuted)
    }
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.line)) {
        Box(Modifier.fillMaxWidth(path.progress.coerceIn(0.01f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.signal))
    }
    Spacer(Modifier.height(10.dp))
    path.stages.forEachIndexed { i, s -> StageRow(s, last = i == path.stages.lastIndex, onAction = { onAction(s.kind) }) }
}

@Composable
private fun StageRow(s: PathStage, last: Boolean, onAction: () -> Unit) {
    val c = Ranzen.colors
    val active = s.state == StageState.ACTIVE
    val done = s.state == StageState.DONE
    val action = stageAction(s.kind)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(36.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(if (done) c.signal else c.raised)
                    .border(if (active) 2.dp else 1.dp, if (done || active) c.signal else c.line, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (done) RanzenIcon(RanzenIcons.Check, tint = c.onSignal, size = 14.dp) }
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(if (done) c.signal.copy(alpha = 0.5f) else c.line))
        }
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                .then(if (action != null && !done) Modifier.clickable(onClickLabel = action, onClick = onAction) else Modifier)
                .padding(vertical = 8.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stageTitle(s.kind), style = if (active) Ranzen.type.heading else Ranzen.type.body, color = if (s.state == StageState.OPEN) c.inkMuted else c.ink)
                Text(stageDetail(s), style = Ranzen.type.caption, color = c.inkMuted)
            }
            if (active && action != null) {
                Text(action.uppercase(), style = Ranzen.type.mono, color = c.signal, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}
