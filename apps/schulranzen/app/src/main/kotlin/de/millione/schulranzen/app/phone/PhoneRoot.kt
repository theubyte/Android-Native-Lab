package de.millione.schulranzen.app.phone

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.app.AppIcons
import de.millione.schulranzen.app.data.Library
import de.millione.schulranzen.app.data.Notebook
import de.millione.schulranzen.app.data.Settings
import de.millione.schulranzen.app.data.Subject
import de.millione.schulranzen.books.BookCover
import de.millione.schulranzen.books.BookShelf
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.design.ThemeMode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The areas of the phone, in the order of the bottom bar. */
enum class PhoneTab(val label: String) { LEARN("Lernen"), NOTEBOOKS("Hefte"), BOOKS("Bücher"), SCAN("Scannen"), SYNC("Sichern") }

/**
 * Lays out but does not place its content: it stays composed (the state of a
 * running learning session lives there) yet draws nothing and takes no touch.
 * The notebook needs this, its canvas lies below the Compose layer.
 */
private fun Modifier.hiddenWhen(hidden: Boolean): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height) { if (!hidden) p.place(0, 0) }
}

/**
 * The phone's shell. [detail] is a full-screen route above the tabs (notebook,
 * book, scan, ...), null shows the tabs. [learn] is always kept composed so a
 * jump from an exam to a notebook page returns to that very exam.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneShell(
    tab: PhoneTab,
    onTab: (PhoneTab) -> Unit,
    detail: String?,
    learn: @Composable () -> Unit,
    tabContent: @Composable (PhoneTab) -> Unit,
    detailContent: @Composable (String) -> Unit,
) {
    val c = Ranzen.colors
    val holder = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().hiddenWhen(detail != null).background(c.desk)) {
            Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(if (WindowInsets.isImeVisible) WindowInsets(0) else WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))) {
                Box(Modifier.fillMaxSize().hiddenWhen(tab != PhoneTab.LEARN)) { learn() }
                // After the learning area's own handler, so here it wins, until we are back on Lernen.
                BackHandler(enabled = tab != PhoneTab.LEARN && detail == null) { onTab(PhoneTab.LEARN) }
                if (tab != PhoneTab.LEARN) holder.SaveableStateProvider(tab.name) { tabContent(tab) }
            }
            if (!WindowInsets.isImeVisible) BottomBar(tab, onTab)
        }
        AnimatedContent(
            detail,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { it?.substringBefore("|") },
            label = "detail",
        ) { d -> if (d != null) detailContent(d) }
    }
}

@Composable
private fun BottomBar(tab: PhoneTab, onTab: (PhoneTab) -> Unit) {
    val c = Ranzen.colors
    Column(Modifier.fillMaxWidth().background(c.paper)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.line))
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(horizontal = 4.dp)) {
            PhoneTab.entries.forEach { t ->
                val on = t == tab
                val icon = when (t) {
                    PhoneTab.LEARN -> AppIcons.Study
                    PhoneTab.NOTEBOOKS -> RanzenIcons.Notebook
                    PhoneTab.BOOKS -> RanzenIcons.Book
                    PhoneTab.SCAN -> AppIcons.Scan
                    PhoneTab.SYNC -> AppIcons.Sync
                }
                Column(
                    Modifier.weight(1f).heightIn(min = 60.dp).semantics { selected = on; contentDescription = t.label }
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onTab(t) }
                        .padding(top = 8.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.width(52.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) c.signal.copy(alpha = 0.14f) else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) { RanzenIcon(icon, tint = if (on) c.signal else c.inkMuted, size = 22.dp) }
                    Spacer(Modifier.height(2.dp))
                    Text(t.label, style = Ranzen.type.caption, color = if (on) c.ink else c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
    }
}

// ------------------------------------------------------------------ Hefte

/** Notebooks by subject, with search over titles and (when indexed) handwriting. */
@Composable
fun PhoneNotebooksTab(
    library: Library,
    onOpen: (String) -> Unit,
    onScan: () -> Unit,
    onSync: () -> Unit,
    /** More results for a search (hits in the handwriting). */
    searchExtra: @Composable (String) -> Unit = {},
) {
    val c = Ranzen.colors
    val notebooksOrNull by library.notebooks.collectAsState(initial = null)
    val subjects by library.subjects.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf<Long?>(null) }
    val q = query.trim().lowercase(Locale.GERMANY)
    val subjectOf = subjects.associateBy { it.id }
    val shown = notebooksOrNull.orEmpty().filter { nb ->
        (subject == null || nb.subjectId == subject) &&
            (q.isEmpty() || nb.title.lowercase(Locale.GERMANY).contains(q) ||
                nb.subjectId?.let(subjectOf::get)?.name?.lowercase(Locale.GERMANY)?.contains(q) == true)
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TabTitle("Hefte", notebooksOrNull?.size)
        SearchLine(query, { query = it }, "Hefte suchen")
        Spacer(Modifier.height(10.dp))
        SubjectFilter(subjects, subject) { subject = it }
        if (notebooksOrNull != null) LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown, key = { it.id }) { nb -> NotebookRow(nb, nb.subjectId?.let(subjectOf::get)) { onOpen(nb.id) } }
            if (q.isNotEmpty()) item { searchExtra(query.trim()) }
            if (shown.isEmpty() && q.isEmpty()) item {
                if (notebooksOrNull.orEmpty().isEmpty()) EmptyHint(
                    "Noch keine Hefte auf diesem Gerät",
                    "Scanne ein Blatt oder hol die Hefte vom Tablet. Geschrieben wird auf dem Tablet.",
                ) {
                    SignalButton("Blatt scannen", onScan, icon = AppIcons.Scan)
                    Spacer(Modifier.height(4.dp))
                    QuietButton("Abgleichen", onSync, color = c.ink)
                } else Text("In diesem Fach gibt es noch kein Heft.", style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.padding(8.dp))
            }
            if (shown.isEmpty() && q.isNotEmpty()) item {
                Text("Kein Heft mit „$query“ im Titel.", style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun NotebookRow(nb: Notebook, subject: Subject?, onClick: () -> Unit) {
    val c = Ranzen.colors
    val accent = subject?.let { SubjectPalette.of(it.colorIndex) } ?: c.inkFaint
    val date = remember(nb.updatedAt) { SimpleDateFormat("dd.MM.", Locale.GERMANY).format(Date(nb.updatedAt)) }
    PaperPanel(Modifier.fillMaxWidth().clickable(onClick = onClick), corner = 14.dp, depth = 0.6f) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically) {
            // The spine in the subject's colour, as on the tablet's covers.
            Box(Modifier.width(12.dp).fillMaxHeight().background(accent))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(nb.title, style = Ranzen.type.heading, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(subject?.name ?: "Ohne Fach", style = Ranzen.type.caption, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.padding(end = 14.dp), horizontalAlignment = Alignment.End) {
                Text("${nb.pageCount} S.", style = Ranzen.type.mono, color = c.inkMuted)
                Text(date, style = Ranzen.type.caption, color = c.inkFaint)
            }
        }
    }
}

// ----------------------------------------------------------------- Bücher

@Composable
fun PhoneBooksTab(shelf: BookShelf, library: Library, onOpen: (String) -> Unit) {
    val c = Ranzen.colors
    val booksOrNull by shelf.books.collectAsState(initial = null)
    val subjects by library.subjects.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = "Buch wird übernommen …"
            message = runCatching { shelf.import(uri) }.fold({ "„${it.title}“ ins Regal gestellt" }, { it.message ?: "Buch konnte nicht übernommen werden" })
            kotlinx.coroutines.delay(3000); message = null
        }
    }
    val q = query.trim().lowercase(Locale.GERMANY)
    val subjectOf = subjects.associateBy { it.id }
    val shown = booksOrNull.orEmpty().filter { b -> (subject == null || b.subjectId == subject) && (q.isEmpty() || b.title.lowercase(Locale.GERMANY).contains(q)) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TabTitle("Bücher", booksOrNull?.size)
        de.millione.schulranzen.books.ContinueReadingCard(shelf, { onOpen(it.id) }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        SearchLine(query, { query = it }, "Bücher suchen")
        Spacer(Modifier.height(10.dp))
        SubjectFilter(subjects, subject) { subject = it }
        if (booksOrNull != null) LazyVerticalGrid(
            GridCells.Adaptive(148.dp), Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(shown, key = { it.id }) { b ->
                val s = b.subjectId?.let(subjectOf::get)
                BookCover(b, onClick = { onOpen(b.id) }, onLongClick = { }, accent = s?.let { SubjectPalette.of(it.colorIndex) }, subject = s?.name)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    if (booksOrNull.orEmpty().isEmpty()) {
                        Text("Noch keine Bücher auf diesem Gerät", style = Ranzen.type.heading, color = c.ink)
                        Spacer(Modifier.height(4.dp))
                        Text("Hol sie mit Abgleichen vom Tablet oder füge eine PDF-Datei hinzu.", style = Ranzen.type.body, color = c.inkMuted)
                        Spacer(Modifier.height(8.dp))
                    } else if (shown.isEmpty()) {
                        Text("Nichts gefunden.", style = Ranzen.type.body, color = c.inkMuted)
                        Spacer(Modifier.height(8.dp))
                    }
                    message?.let { Text(it, style = Ranzen.type.caption, color = c.inkMuted); Spacer(Modifier.height(6.dp)) }
                    QuietButton("+ Buch (PDF) hinzufügen", { runCatching { import.launch(arrayOf("application/pdf")) } }, color = c.ink)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Sichern

/** Getting data between the devices, and the few settings a phone has. */
@Composable
fun PhoneSyncTab(settings: Settings, onSync: () -> Unit, onAbout: () -> Unit) {
    val c = Ranzen.colors
    val theme by settings.theme.collectAsState()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())) {
        TabTitle("Sichern", null)
        PaperPanel(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), corner = 22.dp) {
            Column(Modifier.padding(20.dp)) {
                RanzenIcon(AppIcons.Sync, tint = c.signal, size = 36.dp)
                Spacer(Modifier.height(10.dp))
                Text("Abgleichen", style = Ranzen.type.title, color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text("Bringt deine Scans aufs Tablet und holt Hefte, Bücher und den Lernstand aufs Handy.", style = Ranzen.type.body, color = c.inkMuted)
                Spacer(Modifier.height(16.dp))
                SignalButton("Abgleichen", onSync, Modifier.fillMaxWidth(), icon = AppIcons.Sync)
            }
        }
        Spacer(Modifier.height(16.dp))
        QuietButton("Über Schulranzen", onAbout, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = c.signal)
        Spacer(Modifier.height(16.dp))
        PaperPanel(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), corner = 18.dp, depth = 0.6f) {
            Column(Modifier.padding(16.dp)) {
                Text("Darstellung", style = Ranzen.type.heading, color = c.ink)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Hell", ThemeMode.DARK to "Dunkel").forEach { (m, label) ->
                        val on = m == theme
                        Box(
                            Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) c.raised else Color.Transparent)
                                .border(if (on) 1.5.dp else 1.dp, if (on) c.signal else c.line, RoundedCornerShape(12.dp))
                                .clickable { settings.setTheme(m) },
                            contentAlignment = Alignment.Center,
                        ) { Text(label, style = Ranzen.type.label, color = if (on) c.ink else c.inkMuted) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Schreiben und Rechnen gibt es auf dem Tablet. Das Handy ist zum Lesen, Scannen und Lernen da.", style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(16.dp))
    }
}

// ------------------------------------------------------------ shared parts

@Composable
private fun TabTitle(title: String, count: Int?) {
    val c = Ranzen.colors
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Ranzen.type.title, color = c.ink)
        if (count != null) {
            Spacer(Modifier.width(10.dp))
            Text("%02d".format(count), style = Ranzen.type.mono, color = c.inkMuted)
        }
    }
}

@Composable
private fun SearchLine(query: String, onQuery: (String) -> Unit, hint: String) {
    val c = Ranzen.colors
    val focus = LocalFocusManager.current
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(c.raised)
            .border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RanzenIcon(RanzenIcons.Search, tint = c.inkMuted, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                query, onQuery, singleLine = true,
                textStyle = Ranzen.type.body.copy(color = c.ink), cursorBrush = SolidColor(c.signal),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.isEmpty()) Text(hint, style = Ranzen.type.body, color = c.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (query.isNotEmpty()) Box(Modifier.size(36.dp).clip(CircleShape).clickable { onQuery("") }, contentAlignment = Alignment.Center) {
            RanzenIcon(RanzenIcons.Close, tint = c.inkMuted, size = 18.dp)
        }
    }
}

@Composable
private fun SubjectFilter(subjects: List<Subject>, selected: Long?, onSelect: (Long?) -> Unit) {
    val c = Ranzen.colors
    if (subjects.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable
        fun Chip(label: String, color: Color?, on: Boolean, onClick: () -> Unit) {
            Row(
                Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) c.raised else Color.Transparent)
                    .border(1.dp, if (on) color ?: c.signal else c.line, RoundedCornerShape(12.dp))
                    .clickable(onClick = onClick).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (color != null) { Lamp(color, size = 8.dp, glow = on); Spacer(Modifier.width(8.dp)) }
                Text(label, style = Ranzen.type.label, color = if (on) c.ink else c.inkMuted, maxLines = 1)
            }
        }
        Chip("Alle", null, selected == null) { onSelect(null) }
        subjects.forEach { s -> Chip(s.name, SubjectPalette.of(s.colorIndex), s.id == selected) { onSelect(s.id) } }
    }
}

@Composable
private fun EmptyHint(title: String, text: String, actions: @Composable () -> Unit) {
    val c = Ranzen.colors
    PaperPanel(Modifier.fillMaxWidth(), corner = 18.dp, depth = 0.5f) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = Ranzen.type.heading, color = c.ink)
            Spacer(Modifier.height(4.dp))
            Text(text, style = Ranzen.type.body, color = c.inkMuted)
            Spacer(Modifier.height(14.dp))
            actions()
        }
    }
}
