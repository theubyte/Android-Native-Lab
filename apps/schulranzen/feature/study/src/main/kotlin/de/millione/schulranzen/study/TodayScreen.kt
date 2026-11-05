package de.millione.schulranzen.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.Confidence
import de.millione.schulranzen.core.study.DayLoad
import de.millione.schulranzen.core.study.GapKind
import de.millione.schulranzen.core.study.Readiness
import de.millione.schulranzen.core.study.ReadinessGap
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.study.data.StudyLink
import de.millione.schulranzen.study.ui.Counter
import de.millione.schulranzen.study.ui.EmptyNote
import de.millione.schulranzen.study.ui.FieldLabel
import de.millione.schulranzen.study.ui.Fmt
import de.millione.schulranzen.study.ui.OptionPlate
import de.millione.schulranzen.study.ui.ScreenHeader
import de.millione.schulranzen.study.ui.SectionHead
import de.millione.schulranzen.study.ui.StudyIcons
import de.millione.schulranzen.study.ui.StudyPage
import de.millione.schulranzen.study.ui.panelPad
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/**
 * "Jetzt lernen": what today asks for over all coming exams, as one session. The
 * learner sets the time; the plan says what fits, what stays for tomorrow, and how
 * tight the days up to the exams are.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodayPage(
    repo: StudyRepository,
    today: LocalDate,
    onBack: () -> Unit,
    onStart: (List<Long>) -> Unit,
    onOpenLink: (StudyLink) -> Unit,
    onMock: (Long) -> Unit,
    onExam: (Long) -> Unit,
) {
    val c = Ranzen.colors
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableIntStateOf(-1) }
    var bundle by remember { mutableStateOf<TodayBundle?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(today, minutes, refresh) {
        if (minutes < 0) minutes = repo.dailyMinutes.now()
        bundle = repo.todayPlan(minutes)
    }
    val b = bundle
    StudyPage(header = {
        ScreenHeader("Jetzt lernen", b?.let { "Etwa ${it.plan.minutes} Minuten" } ?: "Plan entsteht", onBack)
    }) {
        if (b == null) return@StudyPage
        val plan = b.plan
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val session: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(panelPad)) {
                        if (plan.isEmpty) {
                            Text("Für heute ist alles getan", style = Ranzen.type.title, color = c.ink)
                            Spacer(Modifier.height(8.dp))
                            EmptyNote(if (b.exams.isEmpty()) "Trag eine Prüfung ein und leg Karten an, dann entsteht hier der Tagesplan." else "Nichts ist fällig. Morgen geht es weiter.")
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Counter("%03d".format(plan.cards.size), "Karten", accent = if (plan.cards.isNotEmpty()) c.signal else null)
                                Counter("%02d".format(plan.links.size), "Stellen")
                                Counter("%02d".format(plan.minutes), "Minuten")
                            }
                            Spacer(Modifier.height(18.dp))
                            if (plan.cards.isNotEmpty()) {
                                SignalButton("Sitzung starten", { onStart(plan.cards.map { it.cardId }) }, icon = StudyIcons.Play)
                            }
                            if (plan.deferred > 0) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "${plan.deferred} Karten passen heute nicht in die Zeit. Sie bleiben fällig und kommen morgen zuerst.",
                                    style = Ranzen.type.body, color = c.inkMuted,
                                )
                                QuietButton("Alles heute schaffen", { minutes = 600 }, color = c.ink)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        FieldLabel("Zeit heute")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 45, 60, 90).forEach { m ->
                                OptionPlate("$m min", minutes == m, {
                                    minutes = m
                                    scope.launch { repo.saveDailyMinutes(m) }
                                })
                            }
                        }
                    }
                }
            }
            val steps: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(horizontal = panelPad, vertical = 18.dp)) {
                        SectionHead("Nach Prüfung", plan.shares.size)
                        if (plan.shares.isEmpty()) EmptyNote("Keine Prüfung geplant. Es kommen nur fällige Karten dran.")
                        plan.shares.forEach { s ->
                            val e = b.exams[s.examId] ?: return@forEach
                            val date = LocalDate.ofEpochDay(e.dateEpochDay)
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).clickable { onExam(e.id) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Lamp(SubjectPalette.of(e.colorIndex), size = 9.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${e.subject} · ${Fmt.countdown(today, date)}", style = Ranzen.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val parts = listOfNotNull(
                                        s.due.takeIf { it > 0 }?.let { "$it wiederholen" },
                                        s.fresh.takeIf { it > 0 }?.let { "$it neu" },
                                        s.weak.takeIf { it > 0 }?.let { "$it Schwächen" },
                                        s.links.takeIf { it > 0 }?.let { "$it Stoff" },
                                        s.deferred.takeIf { it > 0 }?.let { "$it später" },
                                    )
                                    Text(parts.joinToString(" · ").ifEmpty { "Heute nichts offen" }, style = Ranzen.type.caption, color = c.inkMuted)
                                }
                                ChevronIcon()
                            }
                        }
                        plan.mockExamId?.let { id ->
                            val e = b.exams[id]
                            if (e != null) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable { onMock(id) }.padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RanzenIcon(StudyIcons.Test, tint = SubjectPalette.of(e.colorIndex))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Probeprüfung ${e.subject}", style = Ranzen.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Der beste Test, was schon sitzt", style = Ranzen.type.caption, color = c.inkMuted)
                                    }
                                    ChevronIcon()
                                }
                            }
                        }
                        if (plan.links.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SectionHead("Stoff durcharbeiten", plan.links.size)
                            plan.links.forEach { (_, linkId) ->
                                val l = b.links[linkId] ?: return@forEach
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Als erledigt markieren") {
                                            scope.launch { repo.setLinkDone(l, true); refresh++ }
                                        },
                                        contentAlignment = Alignment.Center,
                                    ) { RanzenIcon(RanzenIcons.Check, tint = c.inkMuted, size = 20.dp) }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        l.label, style = Ranzen.type.body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onOpenLink(l) }.padding(vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val load: @Composable () -> Unit = {
                if (plan.forecast.size > 1) {
                    PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                        Column(Modifier.padding(horizontal = panelPad, vertical = 18.dp)) {
                            SectionHead("Die Tage bis zur Prüfung", null)
                            ForecastBars(plan.forecast.take(21), plan.budgetMinutes)
                            Spacer(Modifier.height(8.dp))
                            val tight = plan.minutesNeededPerDay > plan.budgetMinutes
                            Text(
                                if (tight) "An deinem vollsten Tag wären es etwa ${plan.minutesNeededPerDay} Minuten, mehr als du eingeplant hast. Früher anfangen hilft."
                                else "Bleibst du dran, reichen deine ${plan.budgetMinutes} Minuten am Tag. Das ist eine Schätzung.",
                                style = Ranzen.type.caption, color = c.inkMuted,
                            )
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) { session(); load() }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { steps() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { session(); steps(); load() }
            }
        }
    }
}

/** Work per day as columns, today left; days above the planned time stand out. */
@Composable
private fun ForecastBars(days: List<DayLoad>, budget: Int) {
    val c = Ranzen.colors
    val top = (days.maxOfOrNull { it.minutes } ?: 1).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        days.forEach { d ->
            val share = d.minutes.toFloat() / top
            Box(
                Modifier.weight(1f).fillMaxHeight(share.coerceIn(0.03f, 1f)).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(if (d.minutes > budget) c.signal else c.inkMuted),
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("heute", style = Ranzen.type.caption, color = c.inkFaint, modifier = Modifier.weight(1f))
        days.lastOrNull()?.let { Text(it.date.format(Fmt.shortDate), style = Ranzen.type.caption, color = c.inkFaint) }
    }
}

internal fun confidenceText(c: Confidence): String = when (c) {
    Confidence.NONE -> "Noch keine Grundlage"
    Confidence.LOW -> "Grobe Schätzung ohne Probeprüfung"
    Confidence.MEDIUM -> "Schätzung mit wenig Prüfungsdaten"
    Confidence.HIGH -> "Schätzung mit Probeprüfungen"
}

/** One line per gap: what is missing, in the words a learner uses. */
internal fun gapText(g: ReadinessGap): String = when (g.kind) {
    GapKind.NO_CARDS -> "Noch keine Karten zu dieser Prüfung."
    GapKind.FEW_CARDS -> "Etwa ${g.count} Karten fehlen noch, um den Stoff abzudecken."
    GapKind.NEW_CARDS -> if (g.count == 1) "1 Karte noch nie gelernt." else "${g.count} Karten noch nie gelernt."
    GapKind.DUE_CARDS -> if (g.count == 1) "1 Karte ist fällig." else "${g.count} Karten sind fällig."
    GapKind.SHAKY_CARDS -> if (g.count == 1) "1 Karte sitzt bis zur Prüfung wohl nicht." else "${g.count} Karten sitzen bis zur Prüfung wohl nicht."
    GapKind.TOPICS_MISSING -> "Ohne Karten: ${g.names.joinToString(", ")}."
    GapKind.TOPICS_WEAK -> "Noch nicht gelernt: ${g.names.joinToString(", ")}."
    GapKind.LINKS_OPEN -> if (g.count == 1) "1 markierte Stelle ist noch offen." else "${g.count} markierte Stellen sind noch offen."
    GapKind.NO_MOCK -> "Noch keine Probeprüfung geschrieben."
    GapKind.OLD_MOCK -> "Die letzte Probeprüfung ist ${g.count} Tage her."
    GapKind.MOCK_LOW -> "Die letzten Probeprüfungen lagen bei ${g.count} %."
}

/** The forecast for one exam: percent, grade, how sure it is, and what is still missing. */
@Composable
internal fun ReadinessSection(r: Readiness, onMock: () -> Unit) {
    val c = Ranzen.colors
    SectionHead("Prüfungsreife", null)
    if (r.percent == null) {
        Text("Zu wenig gelernt für eine ehrliche Prognose.", style = Ranzen.type.body, color = c.inkMuted)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
            Counter("%02d %%".format(r.percent), "Erwartet", accent = if ((r.grade ?: 6) >= 5) c.signal else null)
            Counter("%d".format(r.grade), "Note")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Spanne ${r.low}–${r.high} %. ${confidenceText(r.confidence)}. " +
                "Gedächtnis ${r.memoryPercent} %, Abdeckung ${r.coveragePercent} %" + (r.mockPercent?.let { ", Probeprüfungen $it %" } ?: "") + ".",
            style = Ranzen.type.caption, color = c.inkMuted,
        )
    }
    if (r.gaps.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("WAS NOCH FEHLT", style = Ranzen.type.caption, color = c.inkMuted)
        r.gaps.forEach { g ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 7.dp)) { Lamp(c.signal, size = 6.dp, glow = false) }
                Spacer(Modifier.width(10.dp))
                Text(gapText(g), style = Ranzen.type.body, color = c.ink)
            }
        }
        if (r.gaps.any { it.kind == GapKind.NO_MOCK || it.kind == GapKind.OLD_MOCK }) QuietButton("Probeprüfung schreiben", onMock, color = c.ink)
    }
}
