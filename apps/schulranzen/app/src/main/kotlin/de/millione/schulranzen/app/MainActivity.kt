package de.millione.schulranzen.app

import android.app.Application
import android.content.res.Configuration
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableIntStateOf
import de.millione.schulranzen.app.phone.PhoneBooksTab
import de.millione.schulranzen.app.phone.PhoneNotebookScreen
import de.millione.schulranzen.app.phone.PhoneNotebooksTab
import de.millione.schulranzen.app.phone.PhoneShell
import de.millione.schulranzen.app.phone.PhoneSyncTab
import de.millione.schulranzen.app.phone.PhoneTab
import de.millione.schulranzen.app.scan.ScanController
import de.millione.schulranzen.app.scan.ScanFlow
import de.millione.schulranzen.app.scan.ScanRoute
import de.millione.schulranzen.app.scan.ScanStore
import de.millione.schulranzen.app.scan.rememberScanController
import de.millione.schulranzen.app.scan.sharedUris
import de.millione.schulranzen.design.isPhone
import de.millione.schulranzen.design.rememberIsPhone
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.TactileKey
import de.millione.schulranzen.study.StudyHome
import de.millione.schulranzen.books.BookShelf
import de.millione.schulranzen.books.BookScreen
import de.millione.schulranzen.books.BookPane
import de.millione.schulranzen.books.BookLink
import de.millione.schulranzen.study.StudyLinkDraft
import de.millione.schulranzen.study.StudyLinkKind
import de.millione.schulranzen.study.LinkTarget
import de.millione.schulranzen.study.StudyLinkTargets
import de.millione.schulranzen.study.StudyLinkText
import de.millione.schulranzen.study.StudyReminders
import de.millione.schulranzen.study.CardFromClipSheet
import de.millione.schulranzen.study.SuggestCardsSheet
import de.millione.schulranzen.books.BookLearn
import de.millione.schulranzen.books.ContinueReadingCard
import de.millione.schulranzen.backup.EditGate
import de.millione.schulranzen.study.LinkRect
import de.millione.schulranzen.study.MarkForExamSheet
import de.millione.schulranzen.ink.PageGeometry
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import de.millione.schulranzen.app.notebook.SplitWithBook
import de.millione.schulranzen.app.notebook.NotebookSheets
import de.millione.schulranzen.app.notebook.BookPickerSheet
import de.millione.schulranzen.app.library.ClipTargetSheet
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.launch
import de.millione.schulranzen.rechnen.ShkScreen
import de.millione.schulranzen.backup.BackupScreen
import de.millione.schulranzen.core.backup.V15Import
import de.millione.schulranzen.app.data.V15Sink
import de.millione.schulranzen.app.data.appBackupHooks
import de.millione.schulranzen.app.data.appMergeParticipants
import de.millione.schulranzen.study.StudySubject
import de.millione.schulranzen.study.TimetableScreen
import de.millione.schulranzen.study.TodayStrip
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import de.millione.schulranzen.app.data.Library
import de.millione.schulranzen.app.data.Settings
import de.millione.schulranzen.app.library.LibraryScreen
import de.millione.schulranzen.app.notebook.NotebookScreen
import de.millione.schulranzen.app.notebook.NotebookSession
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenTheme
import de.millione.schulranzen.design.ThemeMode
import de.millione.schulranzen.ink.NotebookCanvasView
import de.millione.schulranzen.ink.RingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide objects; saving runs in [scope] so leaving a screen never drops a write. */
class RanzenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StudyLinkTargets.resolver = ::resolveLink
        StudyLinkText.provider = { link ->
            link.note.takeIf { it.isNotBlank() } ?: link.pageId?.let { handwriting.pageText(it) }
        }
        de.millione.schulranzen.app.data.installBackupSync(this)
    }

    /** Pages can move between notebooks and books can go: a link follows or says so, never the wrong place. */
    private suspend fun resolveLink(link: de.millione.schulranzen.study.data.StudyLink): LinkTarget {
        if (StudyLinkKind.of(link.kind) == StudyLinkKind.BOOK_PAGE) {
            val book = books.book(link.targetId) ?: return LinkTarget.Missing
            return if (link.page < book.pageCount) LinkTarget.Found(book.id, link.page) else LinkTarget.Missing
        }
        val pageId = link.pageId
        if (pageId != null) return library.locatePage(pageId)?.let { (nb, index) -> LinkTarget.Found(nb, index) } ?: LinkTarget.Missing
        val nb = library.notebook(link.targetId)?.takeIf { it.trashedAt == null } ?: return LinkTarget.Missing
        return if (link.page < nb.pageCount) LinkTarget.Found(nb.id, link.page) else LinkTarget.Missing
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val library by lazy { Library(this) }
    val settings by lazy { Settings(this) }
    val books by lazy { BookShelf(this) }
    val recognizer by lazy { de.millione.schulranzen.handwriting.HandwritingRecognizer(this) }
    val handwriting by lazy { de.millione.schulranzen.handwriting.HandwritingIndex(this, recognizer) }
    val scan by lazy { ScanStore(this, library, scope) }
}

/**
 * Route of a notebook: "nb:<id>", optionally "|page:<n>" (open there),
 * "|flash:l,t,r,b" (mark that area, page units) and "|book:<id>" (book beside).
 */
data class NotebookRoute(val id: String, val page: Int? = null, val flash: android.graphics.RectF? = null, val book: String? = null) {
    fun encode() = buildString {
        append("nb:").append(id)
        page?.let { append("|page:").append(it) }
        flash?.let { append("|flash:${it.left},${it.top},${it.right},${it.bottom}") }
        book?.let { append("|book:").append(it) }
    }
    companion object {
        fun parse(r: String): NotebookRoute {
            val parts = r.removePrefix("nb:").split("|")
            fun v(k: String) = parts.firstOrNull { it.startsWith("$k:") }?.substringAfter(":")
            val f = v("flash")?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 }
            return NotebookRoute(parts[0], v("page")?.toIntOrNull(), f?.let { android.graphics.RectF(it[0], it[1], it[2], it[3]) }, v("book"))
        }
    }
}

/** Book route: "book:<id>", optionally "|page:<n>|flash:l,t,r,b". */
data class BookRoute(val id: String, val page: Int? = null, val flash: android.graphics.RectF? = null) {
    fun encode() = buildString {
        append("book:").append(id)
        page?.let { append("|page:").append(it) }
        flash?.let { append("|flash:${it.left},${it.top},${it.right},${it.bottom}") }
    }
    companion object {
        fun parse(r: String): BookRoute {
            val n = NotebookRoute.parse("nb:" + r.removePrefix("book:"))
            return BookRoute(n.id, n.page, n.flash)
        }
    }
}

/**
 * One activity. The writing surface is a plain view at the bottom of the
 * window and the Compose UI lies on top; on the notebook screen the Compose
 * layer is transparent and only its controls take touches, so pen input
 * reaches the ink engine without any interop layer in between.
 */
class MainActivity : ComponentActivity() {
    private lateinit var canvas: NotebookCanvasView
    /** The open notebook, for the debug hooks below. */
    private var session: NotebookSession? = null
    private val app get() = application as RanzenApp
    /** Goes up when another app shared sheets with us: the UI then shows the scan flow. */
    private val shares = mutableIntStateOf(0)
    /** Goes up when a reminder was tapped: the UI then shows Lernen. */
    private val studyCalls = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(0, 0),
            navigationBarStyle = SystemBarStyle.auto(0, 0),
        )
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        canvas = NotebookCanvasView(this).apply {
            visibility = View.GONE
            ResourcesCompat.getFont(this@MainActivity, de.millione.schulranzen.design.R.font.inter)?.let { typeface = it }
        }
        val ui = ComposeView(this)
        root.addView(canvas, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(ui, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        hideTaskBar()
        // After a restore the intent is the old one; its sheets are already in the draft.
        if (savedInstanceState == null) { receiveShare(intent); receiveReminder(intent) }

        ui.setContent {
            val mode by app.settings.theme.collectAsState()
            RanzenTheme(mode) {
                val desk = Ranzen.colors.desk
                val dark = Ranzen.colors.isDark
                SideEffect {
                    root.setBackgroundColor(desk.toArgb())
                    // The app's own light/dark switch, not the system's, decides the bar icons.
                    WindowCompat.getInsetsController(window, root).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                LaunchedEffect(Unit) { app.library.seed() }
                // Pages written while the handwriting model was missing get indexed once it is there.
                LaunchedEffect(Unit) {
                    app.recognizer.refreshStatus()
                    app.recognizer.status.collect { st ->
                        if (st == de.millione.schulranzen.handwriting.ModelState.Ready) runCatching {
                            app.handwriting.indexPending { ref ->
                                app.library.loadPages(ref.notebookId).firstOrNull { it.id == ref.pageId }
                                    ?.let { de.millione.schulranzen.handwriting.InkInput.handwriting(it.ink.strokes) }
                            }
                            // Once: pages written before the search existed (or imported from V15).
                            if (!app.settings.handwritingIndexedAll) {
                                for (nb in app.library.allNotebookIds()) for (page in app.library.loadPages(nb)) {
                                    val strokes = de.millione.schulranzen.handwriting.InkInput.handwriting(page.ink.strokes)
                                    if (strokes.isNotEmpty() && app.handwriting.pageText(page.id) == null) app.handwriting.indexPage(nb, page.id, strokes)
                                }
                                app.settings.handwritingIndexedAll = true
                            }
                        }
                    }
                }
                val subjects by app.library.subjects.collectAsState(initial = emptyList())
                val studySubjects = remember(subjects) { subjects.map { StudySubject(it.id, it.name, it.colorIndex) } }
                val scanner = rememberScanController(app.scan)
                if (rememberIsPhone()) PhoneApp(scanner, studySubjects) else {
                // Where we are, as a saveable string: "lib", "nb:<id>", "study", "timetable", "scan".
                var route by rememberSaveable { mutableStateOf("lib") }
                LaunchedEffect(shares.intValue) { if (shares.intValue > 0) route = ScanRoute().encode() }
                LaunchedEffect(studyCalls.intValue) { if (studyCalls.intValue > 0) route = "study" }
                AnimatedContent(
                    route,
                    transitionSpec = { (fadeIn() + scaleIn(spring(dampingRatio = 0.8f), initialScale = 0.97f)) togetherWith fadeOut() },
                    // Opening a book beside the notebook keeps the notebook (same session, no fade).
                    contentKey = { it.substringBefore("|") },
                    label = "screen",
                ) { r ->
                    val isDark = Ranzen.colors.isDark
                    when {
                        r == "lib" -> LibraryScreen(
                            app.library,
                            onOpen = { route = "nb:$it" },
                            onToggleTheme = {
                                app.settings.setTheme(if (isDark) ThemeMode.LIGHT else ThemeMode.DARK)
                            },
                            tools = {
                                TactileKey({ route = "study" }, contentDescription = "Lernen") { RanzenIcon(AppIcons.Study) }
                                Spacer(Modifier.width(10.dp))
                                TactileKey({ route = "timetable" }, contentDescription = "Stundenplan") { RanzenIcon(AppIcons.Timetable) }
                                Spacer(Modifier.width(10.dp))
                                TactileKey({ route = "shk" }, contentDescription = "SHK-Werkzeuge") { RanzenIcon(AppIcons.Shk) }
                                Spacer(Modifier.width(10.dp))
                                TactileKey({ route = "backup" }, contentDescription = "Sichern und übernehmen") { RanzenIcon(AppIcons.Backup) }
                                Spacer(Modifier.width(10.dp))
                                TactileKey({ route = ScanRoute().encode() }, contentDescription = "Blätter scannen") { RanzenIcon(AppIcons.Scan) }
                                Spacer(Modifier.width(10.dp))
                                TactileKey({ route = "about" }, contentDescription = "Über Schulranzen") { RanzenIcon(de.millione.schulranzen.design.RanzenIcons.Settings) }
                                Spacer(Modifier.width(16.dp))
                            },
                            today = {
                                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                                    TodayStrip(Modifier.fillMaxWidth(), onClick = { route = "timetable" })
                                    ContinueReadingCard(app.books, { route = "book:${it.id}" }, Modifier.fillMaxWidth())
                                }
                            },
                            shelf = app.books,
                            onOpenBook = { route = "book:$it" },
                            searchExtra = { q ->
                                val notebooks by app.library.notebooks.collectAsState(initial = emptyList())
                                var hits by remember { mutableStateOf(emptyList<de.millione.schulranzen.handwriting.Hit>()) }
                                LaunchedEffect(q) { kotlinx.coroutines.delay(250); hits = runCatching { app.handwriting.search(q) }.getOrDefault(emptyList()) }
                                de.millione.schulranzen.handwriting.HandwritingSearchResults(
                                    hits,
                                    onOpen = { hit ->
                                        app.scope.launch {
                                            val index = app.library.pageIndex(hit.notebookId, hit.pageId)
                                            route = NotebookRoute(hit.notebookId, index, hit.rect).encode()
                                        }
                                    },
                                    titleOf = { id -> notebooks.firstOrNull { it.id == id }?.title ?: "Heft" },
                                )
                            },
                        )
                        r == "study" -> {
                            BackHandler { route = "lib" }
                            StudyHome(onBack = { route = "lib" }, onOpenTimetable = { route = "timetable" }, subjects = studySubjects,
                                onOpenLink = { link -> route = routeOf(link) })
                        }
                        r == "timetable" -> {
                            BackHandler { route = "lib" }
                            TimetableScreen(onBack = { route = "lib" }, subjects = studySubjects)
                        }
                        r == "shk" -> ShkScreen(onBack = { route = "lib" })
                        r == "about" -> {
                            BackHandler { route = "lib" }
                            de.millione.schulranzen.app.about.AboutScreen(theme = mode, onTheme = app.settings::setTheme, onBack = { route = "lib" })
                        }
                        r == "backup" -> {
                            BackHandler { route = "lib" }
                            BackupRoute(onBack = { route = "lib" })
                        }
                        r.startsWith("scan") -> {
                            val sr = ScanRoute.parse(r)
                            val origin = sr.notebookId?.let { NotebookRoute(it).encode() } ?: "lib"
                            BackHandler { route = origin }
                            ScanFlow(app.scan, scanner, app.library, sr, onBack = { route = origin },
                                onOpen = { nb, page -> route = NotebookRoute(nb, page).encode() })
                        }
                        r.startsWith("book:") -> {
                            BackHandler { route = "lib" }
                            var clip by remember { mutableStateOf<java.io.File?>(null) }
                            var mark by remember { mutableStateOf<BookLink?>(null) }
                            var learn by remember { mutableStateOf<BookLearn?>(null) }
                            val br = BookRoute.parse(r)
                            val gated = rememberEditGate()
                            Box(Modifier.fillMaxSize()) {
                                if (gated) BookScreen(br.id, onBack = { route = "lib" }, onClip = { clip = it },
                                    onMarkForExam = { mark = it }, startPage = br.page, highlight = br.flash,
                                    onLearn = { learn = it })
                                mark?.let { d ->
                                    MarkForExamSheet(d.toDraft(), onDone = {
                                        mark = null
                                        Toast.makeText(this@MainActivity, "Für die Prüfung gemerkt", Toast.LENGTH_SHORT).show()
                                    }, onCancel = { mark = null }, initialNote = d.text.orEmpty())
                                }
                                when (val l = learn) {
                                    is BookLearn.ImageCard -> CardFromClipSheet(l.image, l.link.toDraft(), onDone = { learn = null }, onCancel = { learn = null })
                                    is BookLearn.TextCards -> SuggestCardsSheet(l.text, onDone = { learn = null }, onCancel = { learn = null }, source = l.link.toDraft())
                                    null -> Unit
                                }
                                clip?.let { file ->
                                    ClipTargetSheet(app.library, onPick = { nb ->
                                        app.scope.launch {
                                            val newPage = app.library.addImageToNotebook(nb.id, file.name, canvas.newId())
                                            val where = if (newPage) " auf einer neuen Seite (die Seite war gesperrt)" else ""
                                            Toast.makeText(this@MainActivity, "Ausschnitt in „${nb.title}“$where eingefügt", Toast.LENGTH_SHORT).show()
                                        }
                                        clip = null
                                    }, onCancel = { clip = null })
                                }
                            }
                        }
                        r.startsWith("nb:") -> {
                            val nr = NotebookRoute.parse(r)
                            val id = nr.id
                            val sideBook = nr.book
                            val session = remember(id) {
                                NotebookSession(id, canvas, app.library, app.settings, app.scope, app.handwriting, app.recognizer, nr.page, nr.flash)
                            }
                            BindCanvas(session, readOnly = false)
                            var pickBook by remember { mutableStateOf(false) }
                            SplitWithBook(
                                canvas = canvas,
                                bookId = sideBook,
                                notebook = { compact ->
                                    NotebookScreen(session, app.settings, onBack = { route = "lib" },
                                        onBookBeside = { pickBook = true }, compact = compact,
                                        onScan = { route = ScanRoute(id, session.page + 1).encode() })
                                },
                                book = { bookId ->
                                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom))) {
                                        BookPane(bookId, Modifier.fillMaxSize(), onClip = { session.insertImageFile(it) },
                                            onClose = { route = NotebookRoute(id).encode() },
                                            onMarkForExam = { session.markDraft = it.toDraft() },
                                            onLearn = { l ->
                                                when (l) {
                                                    is BookLearn.ImageCard -> session.cardClip = l.image to l.link.toDraft()
                                                    is BookLearn.TextCards -> session.suggestText = l.text
                                                }
                                            })
                                    }
                                },
                            )
                            NotebookSheets(session)
                            if (pickBook) BookPickerSheet(app.books, onPick = { b -> pickBook = false; route = NotebookRoute(id, book = b.id).encode() }, onCancel = { pickBook = false })
                        }
                    }
                }
                }
                de.millione.schulranzen.app.about.StartupOverlays()
            }
        }
    }

    /** Backup, restore, sync and the V15 import, on both kinds of device. */
    @Composable
    private fun BackupRoute(onBack: () -> Unit) {
        BackupScreen(
            onBack = onBack,
            // The notebook saves itself when it closes, so nothing is pending here.
            hooks = remember { appBackupHooks(this@MainActivity) {} },
            mergeParticipants = remember { appMergeParticipants(this@MainActivity) },
            onImportV15 = { lib ->
                V15Import.run(lib, V15Sink(this@MainActivity, app.library, canvas::newId) { book, subjectId, pdf ->
                    if (pdf == null) null else {
                        // The shelf imports from a Uri: hand the PDF over through a cache file.
                        val tmp = java.io.File(cacheDir, "v15-book.pdf")
                        tmp.outputStream().use { pdf.copyTo(it) }
                        val info = app.books.import(android.net.Uri.fromFile(tmp))
                        tmp.delete()
                        if (book.title.isNotBlank()) app.books.rename(info.id, book.title)
                        app.books.setSubject(info.id, subjectId)
                        info.id
                    }
                })
            },
        )
    }

    /**
     * Puts the canvas in the hands of [session] while it is on screen. A
     * screen fading out is disposed after the next one came up, so the old
     * session must not take the canvas away from the new one.
     */
    @Composable
    private fun BindCanvas(session: NotebookSession, readOnly: Boolean) {
        DisposableEffect(session) {
            canvas.readOnly = readOnly
            if (!readOnly) canvas.topGapPx = 24f * resources.displayMetrics.density
            // Its pending pages must be written while the canvas still holds them.
            this@MainActivity.session?.close()
            session.open()
            this@MainActivity.session = session
            canvas.visibility = View.VISIBLE
            onDispose {
                val stillOurs = this@MainActivity.session === session
                val next = canvas.listener
                session.close()
                if (stillOurs) {
                    this@MainActivity.session = null
                    canvas.visibility = View.GONE
                } else canvas.listener = next
            }
        }
    }

    /**
     * The phone: reading, scanning and learning. No writing, no calculator:
     * the tablet's notebook screen with its tools is never composed here.
     */
    @Composable
    private fun PhoneApp(scanner: ScanController, studySubjects: List<StudySubject>) {
        var tab by rememberSaveable { mutableStateOf(PhoneTab.LEARN) }
        var detail by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(shares.intValue) { if (shares.intValue > 0) { tab = PhoneTab.SCAN; detail = null } }
        LaunchedEffect(studyCalls.intValue) { if (studyCalls.intValue > 0) { tab = PhoneTab.LEARN; detail = null } }
        val syncRoute = "backup"
        PhoneShell(
            tab, { tab = it }, detail,
            learn = {
                StudyHome(onBack = { tab = PhoneTab.NOTEBOOKS }, onOpenTimetable = { detail = "timetable" }, subjects = studySubjects,
                    onOpenLink = { link -> detail = routeOf(link) })
            },
            tabContent = { t ->
                when (t) {
                    PhoneTab.NOTEBOOKS -> PhoneNotebooksTab(app.library, onOpen = { detail = "nb:$it" }, onScan = { tab = PhoneTab.SCAN }, onSync = { detail = syncRoute },
                        searchExtra = { q ->
                            val notebooks by app.library.notebooks.collectAsState(initial = emptyList())
                            var hits by remember { mutableStateOf(emptyList<de.millione.schulranzen.handwriting.Hit>()) }
                            LaunchedEffect(q) { kotlinx.coroutines.delay(250); hits = runCatching { app.handwriting.search(q) }.getOrDefault(emptyList()) }
                            de.millione.schulranzen.handwriting.HandwritingSearchResults(
                                hits,
                                onOpen = { hit ->
                                    app.scope.launch {
                                        val index = app.library.pageIndex(hit.notebookId, hit.pageId)
                                        detail = NotebookRoute(hit.notebookId, index, hit.rect).encode()
                                    }
                                },
                                titleOf = { id -> notebooks.firstOrNull { it.id == id }?.title ?: "Heft" },
                            )
                        })
                    PhoneTab.BOOKS -> PhoneBooksTab(app.books, app.library, onOpen = { detail = "book:$it" })
                    PhoneTab.SCAN -> ScanFlow(app.scan, scanner, app.library,
                        onOpen = { nb, page -> detail = NotebookRoute(nb, page).encode() }, onSync = { detail = syncRoute })
                    PhoneTab.SYNC -> PhoneSyncTab(app.settings, onSync = { detail = syncRoute }, onAbout = { detail = "about" })
                    PhoneTab.LEARN -> Unit
                }
            },
            detailContent = { d ->
                when {
                    d == "about" -> {
                        BackHandler { detail = null }
                        val theme by app.settings.theme.collectAsState()
                        de.millione.schulranzen.app.about.AboutScreen(theme = theme, onTheme = app.settings::setTheme, onBack = { detail = null })
                    }
                    d == "timetable" -> {
                        BackHandler { detail = null }
                        TimetableScreen(onBack = { detail = null }, subjects = studySubjects)
                    }
                    d == "backup" -> {
                        BackHandler { detail = null }
                        BackupRoute(onBack = { detail = null })
                    }
                    d.startsWith("scan") -> {
                        val sr = ScanRoute.parse(d)
                        // Started from a notebook: back leads there again.
                        val origin = sr.notebookId?.let { NotebookRoute(it).encode() }
                        BackHandler { detail = origin }
                        ScanFlow(app.scan, scanner, app.library, sr, onBack = { detail = origin },
                            onOpen = { nb, page -> detail = NotebookRoute(nb, page).encode() }, onSync = { detail = syncRoute })
                    }
                    d.startsWith("book:") -> {
                        BackHandler { detail = null }
                        var mark by remember { mutableStateOf<StudyLinkDraft?>(null) }
                        val br = BookRoute.parse(d)
                        Box(Modifier.fillMaxSize()) {
                            // Clippings go into a notebook, and notebooks are not written on the phone.
                            BookScreen(br.id, onBack = { detail = null }, onClip = { }, onMarkForExam = { mark = it.toDraft() }, startPage = br.page, highlight = br.flash)
                            BackHandler(enabled = mark != null) { mark = null }
                            mark?.let { m -> MarkForExamSheet(m, onDone = { mark = null }, onCancel = { mark = null }) }
                        }
                    }
                    d.startsWith("nb:") -> {
                        val nr = NotebookRoute.parse(d)
                        val session = remember(nr.id) {
                            NotebookSession(nr.id, canvas, app.library, app.settings, app.scope, app.handwriting, app.recognizer, nr.page, nr.flash)
                        }
                        // A book beside the notebook on an unfolded foldable may have left it narrow.
                        SideEffect {
                            if (canvas.layoutParams?.width != FrameLayout.LayoutParams.MATCH_PARENT) canvas.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        }
                        BindCanvas(session, readOnly = true)
                        PhoneNotebookScreen(session, app.library, onBack = { detail = null },
                            onScanHere = { after -> detail = ScanRoute(nr.id, after).encode() })
                        NotebookSheets(session)
                    }
                }
            },
        )
    }

    private fun receiveReminder(intent: Intent?) {
        if (intent?.getBooleanExtra(StudyReminders.EXTRA_OPEN_STUDY, false) != true) return
        intent.removeExtra(StudyReminders.EXTRA_OPEN_STUDY)
        studyCalls.intValue++
    }

    /** Sheets shared from another app go into the scan draft right away, while the grant lasts. */
    private fun receiveShare(intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = sharedUris(this, intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "Hier gehen nur Bilder und PDF-Dateien", Toast.LENGTH_LONG).show()
            return
        }
        app.scan.ingest(uris)
        shares.intValue++
    }

    /**
     * The tablet's task bar and navigation bar stay hidden (swipe up from the
     * bottom edge shows them briefly); the status bar with clock stays. A
     * phone keeps its navigation bar, people rely on it there.
     */
    private fun hideTaskBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            if (isPhone(this@MainActivity)) {
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                return
            }
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideTaskBar()
    }

    /** A foldable opened or closed changes the kind of device. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideTaskBar()
    }

    /**
     * Debug builds only: drive the tool ring from adb, since emulators have
     * no pen side button, e.g.
     * `am start -n <app>/.MainActivity --es ring open --ef x 1200 --ef y 700`.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
        receiveReminder(intent)
        if (!BuildConfig.DEBUG || canvas.readOnly) return
        val s = session ?: return
        val x = intent.getFloatExtra("x", 0f); val y = intent.getFloatExtra("y", 0f)
        when (intent.getStringExtra("ring")) {
            "open" -> s.onRing(RingEvent.Open(x, y))
            "move" -> s.onRing(RingEvent.Move(x, y))
            "release" -> s.onRing(RingEvent.Release(x, y))
            "cancel" -> s.onRing(RingEvent.Cancel)
        }
    }

    /**
     * Pen hover and side-button events go to the writing surface even though
     * the Compose layer lies on top (it would otherwise take them).
     */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (::canvas.isInitialized && !canvas.readOnly && canvas.visibility == View.VISIBLE && ev.pointerCount > 0) {
            val t = ev.getToolType(0)
            val loc = IntArray(2).also { canvas.getLocationInWindow(it) }
            // With a book beside the notebook, only hover over the notebook half is ours;
            // over the tool rail the pen belongs to Compose (tool names).
            val inside = ev.x >= loc[0] && ev.x < loc[0] + canvas.width &&
                !de.millione.schulranzen.app.notebook.PenHoverZones.contains(ev.x, ev.y)
            if (inside && (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER)) {
                val local = MotionEvent.obtain(ev).apply { offsetLocation(-loc[0].toFloat(), -loc[1].toFloat()) }
                try { if (canvas.handleHover(local)) return true } finally { local.recycle() }
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }
}

/** Where a marked passage of exam material leads. */
private fun routeOf(link: de.millione.schulranzen.study.data.StudyLink): String {
    val r = link.rect
    return when (link.linkKind) {
        StudyLinkKind.BOOK_PAGE -> BookRoute(link.targetId, link.page,
            r?.let { android.graphics.RectF(it.x * 1000f, it.y * 1000f, (it.x + it.w) * 1000f, (it.y + it.h) * 1000f) }).encode()
        StudyLinkKind.NOTEBOOK_PAGE -> NotebookRoute(link.targetId, link.page,
            r?.let { android.graphics.RectF(it.x * PageGeometry.WIDTH, it.y * PageGeometry.HEIGHT, (it.x + it.w) * PageGeometry.WIDTH, (it.y + it.h) * PageGeometry.HEIGHT) }).encode()
    }
}

/** Book places are stored with both axes divided by the page width (book units are 1000 wide). */
/**
 * Holds off a sync merge while a book with its ink is open (see [EditGate]);
 * false until a running merge is through, the book opens after that.
 */
@androidx.compose.runtime.Composable
private fun rememberEditGate(): Boolean {
    var free by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var gate: AutoCloseable? = null
        scope.launch { gate = EditGate.enterWhenFree(); free = true }
        onDispose { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); gate?.close() }
    }
    return free
}

private fun BookLink.toDraft() = StudyLinkDraft(
    StudyLinkKind.BOOK_PAGE, bookId, page,
    rect?.let { LinkRect(it.left / 1000f, it.top / 1000f, it.width() / 1000f, it.height() / 1000f) },
    label,
)
