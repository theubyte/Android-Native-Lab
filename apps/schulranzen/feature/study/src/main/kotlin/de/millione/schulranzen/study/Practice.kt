package de.millione.schulranzen.study

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.ErrorBook
import de.millione.schulranzen.core.study.FocusPlan
import de.millione.schulranzen.core.study.FocusTimer
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.design.rememberIsPhone
import de.millione.schulranzen.study.data.DeckCounts
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The error list: what keeps going wrong, in one place, with a round just for it. */
@Composable
internal fun ErrorBookPage(repo: StudyRepository, onBack: () -> Unit, onLearn: (List<Long>) -> Unit, today: LocalDate) {
    val c = Ranzen.colors
    val items by repo.errorBook.collectAsState(initial = null)
    StudyPage(header = {
        ScreenHeader("Fehlerheft", items?.let { "${it.size} Karten" } ?: "", onBack)
    }) {
        val list = items ?: return@StudyPage
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                Column(Modifier.padding(horizontal = panelPad, vertical = 18.dp)) {
                    Text(
                        "Hier landen Karten von selbst, wenn sie an zwei Tagen oder dreimal falsch waren, auch in Probeprüfungen. " +
                            "Nach ${ErrorBook.CLEAR_AFTER} richtigen Antworten in Folge gehen sie wieder raus.",
                        style = Ranzen.type.body, color = c.inkMuted,
                    )
                    Spacer(Modifier.height(14.dp))
                    if (list.isEmpty()) EmptyNote("Das Fehlerheft ist leer. Gut so.")
                    else SignalButton("Alle üben (${list.size})", { onLearn(list.map { it.card.id }) }, icon = StudyIcons.Play)
                    Spacer(Modifier.height(10.dp))
                    list.forEach { item ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(4.dp).height(40.dp).background(SubjectPalette.of(item.colorIndex)))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.card.front.ifBlank { "Bildkarte" }, style = Ranzen.type.label, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(item.card.back, style = Ranzen.type.caption, color = c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${item.deckName} · ${item.entry.wrong}× falsch · zuletzt ${Fmt.countdown(today, LocalDate.ofEpochDay(item.entry.lastWrongDay))}" +
                                        if (item.entry.rightInARow > 0) " · ${item.entry.rightInARow}× richtig in Folge" else "",
                                    style = Ranzen.type.caption, color = c.inkFaint,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How much, how right, per day and per deck. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatsPage(repo: StudyRepository, decks: List<DeckCounts>, onBack: () -> Unit) {
    val c = Ranzen.colors
    var span by remember { mutableIntStateOf(30) }
    val stats by remember(span) { repo.statistics(span) }.collectAsState(initial = null)
    val overview by repo.overview.collectAsState(initial = StudyOverview(0, 0, 0, 0))
    StudyPage(header = {
        ScreenHeader("Statistik", "Letzte $span Tage", onBack)
    }) {
        val s = stats ?: return@StudyPage
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val top: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(panelPad)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(7, 30, 90).forEach { d -> OptionPlate("$d Tage", span == d, { span = d }) }
                        }
                        Spacer(Modifier.height(16.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Counter("%d".format(s.answers), "Antworten")
                            Counter(if (s.answers == 0) "--" else "%d %%".format(s.right * 100 / s.answers), "Treffer")
                            Counter("%d".format(s.activeDays), "Lerntage")
                            Counter("%d".format(overview.streak), "Serie")
                            Counter("%d".format(s.focusMinutes), "Fokus-Min.")
                        }
                        Spacer(Modifier.height(20.dp))
                        SectionHead("Antworten am Tag", null)
                        if (s.answers == 0 && s.focusMinutes == 0) EmptyNote("In diesem Zeitraum wurde noch nicht gelernt.")
                        else {
                            val maxA = (s.days.maxOfOrNull { it.answers } ?: 1).coerceAtLeast(1)
                            Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                s.days.forEach { d ->
                                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                                        val height = d.answers.toFloat() / maxA
                                        if (d.answers > 0) {
                                            val rightShare = d.right.toFloat() / d.answers
                                            // Wrong answers stacked on the right ones.
                                            Box(Modifier.fillMaxWidth().weight((height * (1 - rightShare)).coerceAtLeast(0.0001f)).background(SubjectPalette.of(1)))
                                            Box(Modifier.fillMaxWidth().weight((height * rightShare).coerceAtLeast(0.0001f)).background(SubjectPalette.of(3)))
                                            if (height < 1f) Spacer(Modifier.weight(0.0001f))
                                        } else {
                                            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Text(s.days.first().date.format(Fmt.shortDate), style = Ranzen.type.caption, color = c.inkFaint, modifier = Modifier.weight(1f))
                                Text("heute", style = Ranzen.type.caption, color = c.inkFaint)
                            }
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Lamp(SubjectPalette.of(3), size = 8.dp, glow = false)
                                Spacer(Modifier.width(6.dp))
                                Text("gewusst", style = Ranzen.type.caption, color = c.inkMuted)
                                Spacer(Modifier.width(14.dp))
                                Lamp(SubjectPalette.of(1), size = 8.dp, glow = false)
                                Spacer(Modifier.width(6.dp))
                                Text("nochmal", style = Ranzen.type.caption, color = c.inkMuted)
                            }
                        }
                    }
                }
            }
            val perDeck: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(horizontal = panelPad, vertical = 18.dp)) {
                        SectionHead("Trefferquote je Stapel", s.decks.size)
                        if (s.decks.isEmpty()) EmptyNote("Noch keine Antworten.")
                        s.decks.forEach { d ->
                            TopicBar(decks.firstOrNull { it.id == d.deckId }?.name ?: "Stapel", d.percent.toDouble(), d.answers)
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1.3f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { top() }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { perDeck() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { top(); perDeck() }
            }
        }
    }
}

/** Keeps a running timer across leaving the screen and restarts of the app. */
internal class FocusStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("study_focus", Context.MODE_PRIVATE)

    var startedAt: Long
        get() = prefs.getLong("startedAt", 0)
        set(v) { prefs.edit().putLong("startedAt", v).apply() }
    /** When paused: the moment of the pause; 0 while running. */
    var pausedAt: Long
        get() = prefs.getLong("pausedAt", 0)
        set(v) { prefs.edit().putLong("pausedAt", v).apply() }
    var creditedSeconds: Int
        get() = prefs.getInt("credited", 0)
        set(v) { prefs.edit().putInt("credited", v).apply() }
    var focusMinutes: Int
        get() = prefs.getInt("focus", 25)
        set(v) { prefs.edit().putInt("focus", v).apply() }
    var breakMinutes: Int
        get() = prefs.getInt("break", 5)
        set(v) { prefs.edit().putInt("break", v).apply() }
    var blocks: Int
        get() = prefs.getInt("blocks", 4)
        set(v) { prefs.edit().putInt("blocks", v).apply() }

    val plan: FocusPlan get() = FocusPlan(focusMinutes, breakMinutes, breakMinutes * 3, 4)
    val running: Boolean get() = startedAt > 0

    fun clear() { prefs.edit().remove("startedAt").remove("pausedAt").remove("credited").apply() }
}

/**
 * Focus timer: learning blocks with breaks. It measures from the start time, so it
 * stays right in the background; a notification announces the end of a phase (if
 * notifications are allowed). Focus time goes into the statistics.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FocusPage(repo: StudyRepository, onBack: () -> Unit, onLearn: () -> Unit) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val phone = rememberIsPhone()
    val scope = rememberCoroutineScope()
    val store = remember { FocusStore(context) }
    var running by remember { mutableStateOf(store.running) }
    var paused by remember { mutableStateOf(store.pausedAt > 0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var focusLen by remember { mutableIntStateOf(store.focusMinutes) }
    var breakLen by remember { mutableIntStateOf(store.breakMinutes) }
    var blocks by remember { mutableIntStateOf(store.blocks) }
    var lastCredit by remember { mutableIntStateOf(0) }

    fun elapsed(): Long = ((if (paused) store.pausedAt else now) - store.startedAt) / 1000

    /** Whole minutes not yet booked go into the statistics. */
    fun credit(focusSeconds: Int) {
        val capped = focusSeconds.coerceAtMost(store.blocks * store.focusMinutes * 60)
        val fresh = (capped - store.creditedSeconds) / 60
        if (fresh >= 1) {
            store.creditedSeconds += fresh * 60
            lastCredit += fresh
            scope.launch { repo.addFocus(fresh) }
        }
    }
    fun stop() {
        credit(FocusTimer.at(store.plan, elapsed()).focusSeconds)
        store.clear()
        FocusNotice.cancel(context)
        running = false
        paused = false
    }

    LaunchedEffect(running, paused) {
        if (!running || paused) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            val state = FocusTimer.at(store.plan, elapsed())
            if (state.inBreak) credit(state.focusSeconds)
            if (FocusTimer.finished(store.plan, store.blocks, elapsed())) { stop(); return@LaunchedEffect }
            delay(500)
        }
    }

    StudyPage(header = { ScreenHeader("Fokus-Timer", if (running) "Läuft" else "Lernblöcke mit Pausen", onBack) }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                Column(Modifier.padding(panelPad).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (running) {
                        val s = FocusTimer.at(store.plan, elapsed())
                        Text(
                            if (s.inBreak) (if (s.longBreak) "Lange Pause" else "Pause") else "Block ${s.block} von ${store.blocks}",
                            style = Ranzen.type.heading, color = c.ink,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "%02d:%02d".format(s.secondsLeft / 60, s.secondsLeft % 60),
                            style = Ranzen.type.hero, color = if (s.inBreak) SubjectPalette.of(3) else c.ink,
                        )
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.line)) {
                            Box(
                                Modifier.fillMaxWidth((1f - s.secondsLeft.toFloat() / s.phaseSeconds.coerceAtLeast(1)).coerceIn(0.01f, 1f)).height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)).background(if (s.inBreak) SubjectPalette.of(3) else c.signal),
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!s.inBreak && !paused) SignalButton("Karten lernen", onLearn, icon = StudyIcons.Play)
                            QuietButton(if (paused) "Weiter" else "Anhalten", {
                                if (paused) {
                                    // The pause does not count: move the start forward by its length.
                                    store.startedAt += System.currentTimeMillis() - store.pausedAt
                                    store.pausedAt = 0
                                    paused = false
                                    now = System.currentTimeMillis()
                                    FocusNotice.schedule(context, FocusTimer.at(store.plan, elapsed()))
                                } else {
                                    now = System.currentTimeMillis()
                                    store.pausedAt = now
                                    paused = true
                                    FocusNotice.cancel(context)
                                }
                            }, color = c.ink)
                            QuietButton("Beenden", ::stop, color = c.signal)
                        }
                    } else {
                        Text(
                            "Konzentriert lernen, dann echte Pause. Handy weg, nur diese eine Sache.",
                            style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.fillMaxWidth(),
                        )
                        if (lastCredit > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text("$lastCredit Minuten zählen in die Statistik.", style = Ranzen.type.body, color = c.ink, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.fillMaxWidth()) {
                            FieldLabel("Lernblock")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15, 25, 45).forEach { m -> OptionPlate("$m min", focusLen == m, { focusLen = m; store.focusMinutes = m }) }
                            }
                            Spacer(Modifier.height(14.dp))
                            FieldLabel("Pause")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(5, 10, 15).forEach { m -> OptionPlate("$m min", breakLen == m, { breakLen = m; store.breakMinutes = m }) }
                            }
                            Spacer(Modifier.height(14.dp))
                            FieldLabel("Blöcke")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1, 2, 3, 4, 6).forEach { n -> OptionPlate("$n", blocks == n, { blocks = n; store.blocks = n }) }
                            }
                            Spacer(Modifier.height(18.dp))
                            SignalButton("Starten", {
                                store.startedAt = System.currentTimeMillis()
                                store.pausedAt = 0
                                store.creditedSeconds = 0
                                lastCredit = 0
                                now = store.startedAt
                                running = true
                                FocusNotice.schedule(context, FocusTimer.at(store.plan, 0))
                            }, icon = StudyIcons.Clock)
                        }
                    }
                }
            }
            if (phone) Spacer(Modifier.height(4.dp))
        }
    }
}
