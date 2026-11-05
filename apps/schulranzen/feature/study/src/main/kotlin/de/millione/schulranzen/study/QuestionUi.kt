package de.millione.schulranzen.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.AnswerHint
import de.millione.schulranzen.core.study.AnswerResult
import de.millione.schulranzen.core.study.MatchRound
import de.millione.schulranzen.core.study.Question
import de.millione.schulranzen.core.study.Verdict
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.design.rememberIsPhone
import de.millione.schulranzen.design.softRaise
import de.millione.schulranzen.study.data.Card

/** How right an answer was, in words. */
internal fun verdictText(r: AnswerResult): String {
    val base = when (r.verdict) {
        Verdict.RIGHT -> "Richtig"
        Verdict.TYPO -> "Richtig, mit kleinem Tippfehler"
        Verdict.PARTLY -> if (r.total > 1) "Teilweise: ${r.matched} von ${r.total}" else "Teilweise"
        Verdict.WRONG -> if (r.total > 1 && r.matched > 0) "Falsch: ${r.matched} von ${r.total}" else "Falsch"
        Verdict.UNCHECKABLE -> "Vergleiche selbst"
    }
    val hint = when (r.hint) {
        AnswerHint.UNIT_MISSING -> "die Einheit fehlt"
        AnswerHint.UNIT_WRONG -> "die Einheit stimmt nicht"
        AnswerHint.NUMBER_WRONG -> "der Wert stimmt nicht"
        AnswerHint.NEGATION -> "die Verneinung dreht den Sinn"
        AnswerHint.TOO_MANY_VALUES -> "nenne genau einen Wert"
        null -> null
    }
    return if (hint != null) "$base, $hint" else base
}

internal fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.RIGHT, Verdict.TYPO -> SubjectPalette.of(3)
    Verdict.PARTLY -> SubjectPalette.of(2)
    Verdict.WRONG -> SubjectPalette.of(1)
    Verdict.UNCHECKABLE -> SubjectPalette.of(4)
}

@Composable
internal fun VerdictLine(r: AnswerResult, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Lamp(verdictColor(r.verdict), size = 10.dp)
        Spacer(Modifier.width(10.dp))
        Text(verdictText(r), style = Ranzen.type.heading, color = Ranzen.colors.ink)
    }
}

/**
 * The question side as an index card: a label, then the text and any picture,
 * scrolling when long. Below it [extra] can follow on the same card, like the
 * solution after checking.
 */
@Composable
internal fun QuestionCardFace(
    label: String,
    card: Card,
    modifier: Modifier = Modifier,
    text: String = card.front,
    onZoom: (String) -> Unit = {},
    accent: Color = Ranzen.colors.signal,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val c = Ranzen.colors
    val phone = rememberIsPhone()
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.widthIn(max = 1100.dp).fillMaxWidth()
            .softRaise(14.dp, 1f, c.bevelLight, c.bevelShadow).clip(shape).background(c.raised)
            .indexCard(accent, c.line.copy(alpha = 0.45f), headline = 48f)
            .padding(horizontal = if (phone) 16.dp else 32.dp, vertical = 12.dp),
    ) {
        Text(label, style = Ranzen.type.mono, color = c.inkMuted, modifier = Modifier.height(30.dp).padding(top = 4.dp))
        Spacer(Modifier.height(14.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (text.isNotBlank()) {
                Text(de.millione.schulranzen.core.study.QuestionMaker.plain(text), style = if (phone) Ranzen.type.heading else Ranzen.type.title, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            card.frontImage?.let { img ->
                StudyImage(img, Modifier.fillMaxWidth().height(220.dp).padding(vertical = 8.dp).clickable(onClickLabel = "Bild groß") { onZoom(img) })
            }
            extra()
        }
    }
}

/** The solution, for under a question. */
@Composable
internal fun SolutionBlock(card: Card, solution: String = card.back, onZoom: (String) -> Unit = {}) {
    val c = Ranzen.colors
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    Spacer(Modifier.height(10.dp))
    Text("LÖSUNG", style = Ranzen.type.mono, color = c.inkMuted)
    Spacer(Modifier.height(4.dp))
    if (solution.isNotBlank()) Text(de.millione.schulranzen.core.study.QuestionMaker.plain(solution), style = Ranzen.type.heading, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    card.backImage?.let { img ->
        StudyImage(img, Modifier.fillMaxWidth().height(220.dp).padding(vertical = 8.dp).clickable(onClickLabel = "Bild groß") { onZoom(img) })
    }
    CardProvenance(card)
}

/** Answer options as large keys. Once [revealed], the right one lights up and the wrong pick too. */
@Composable
internal fun ChoiceList(q: Question.Choice, selected: Int, revealed: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Ranzen.colors
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        q.options.forEachIndexed { i, text ->
            val isSel = i == selected
            val edge = when {
                revealed && i == q.correct -> SubjectPalette.of(3)
                revealed && isSel -> SubjectPalette.of(1)
                isSel -> c.signal
                else -> c.line
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
                    .background(if (isSel) c.raised else c.paper)
                    .border(if (isSel || (revealed && i == q.correct)) 2.dp else 1.dp, edge, shape)
                    .clickable(enabled = !revealed, role = Role.Button) { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(('A' + i).toString(), style = Ranzen.type.mono, color = c.inkMuted, modifier = Modifier.width(26.dp))
                Text(text, style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Matching: tap a question on the left, then its answer on the right. Works on a
 * phone too: two columns, each row as tall as its text needs. [onPick] reports each
 * try; [onDone] fires when all are matched, with the card ids missed at first try.
 */
@Composable
internal fun MatchBoard(
    q: Question.Match,
    onDone: (missed: Set<Long>, round: MatchRound) -> Unit,
    onPick: (round: MatchRound) -> Unit = {},
    modifier: Modifier = Modifier,
    initial: MatchRound? = null,
) {
    val c = Ranzen.colors
    val round = remember(q) { initial ?: MatchRound(q.cards) }
    var left by remember(q) { mutableStateOf<Long?>(null) }
    var wrongRight by remember(q) { mutableStateOf<Long?>(null) }
    var tick by remember(q) { mutableIntStateOf(0) }
    val solvedRight = remember(q) { hashSetOf<Long>().apply { initial?.solvedIds?.let(::addAll) } }
    Row(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            q.cards.forEach { card ->
                val solved = tick >= 0 && card.id in round.solvedIds
                MatchCell(card.front, selected = left == card.id, done = solved, wrong = false) {
                    if (!solved) { left = card.id; wrongRight = null }
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            q.rightOrder.forEach { i ->
                val card = q.cards[i]
                val solved = tick >= 0 && card.id in solvedRight
                MatchCell(card.back, selected = false, done = solved, wrong = wrongRight == card.id) {
                    val l = left ?: return@MatchCell
                    if (solved) return@MatchCell
                    if (round.pick(l, card.id)) {
                        solvedRight += card.id
                        left = null
                        wrongRight = null
                        tick++
                        onPick(round)
                        if (round.done) onDone(round.missed, round)
                    } else {
                        wrongRight = card.id
                        tick++
                        onPick(round)
                    }
                }
            }
        }
    }
    if (left == null && !round.done) {
        Text("Links antippen, dann rechts die passende Antwort.", style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun MatchCell(text: String, selected: Boolean, done: Boolean, wrong: Boolean, onClick: () -> Unit) {
    val c = Ranzen.colors
    val shape = RoundedCornerShape(12.dp)
    val edge = when {
        wrong -> SubjectPalette.of(1)
        selected -> c.signal
        done -> SubjectPalette.of(3)
        else -> c.line
    }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (done) 0.5f else 1f).clip(shape)
            .background(if (selected) c.raised else c.paper)
            .border(if (selected || wrong) 2.dp else 1.dp, edge, shape)
            .clickable(enabled = !done, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(de.millione.schulranzen.core.study.QuestionMaker.plain(text), style = Ranzen.type.label, color = c.ink) }
}
