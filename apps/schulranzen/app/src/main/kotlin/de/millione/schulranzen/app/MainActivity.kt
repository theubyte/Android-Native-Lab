package de.millione.schulranzen.app

import android.app.Application
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
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.TactileKey
import de.millione.schulranzen.study.StudyHome
import de.millione.schulranzen.books.BookShelf
import de.millione.schulranzen.books.BookScreen
import de.millione.schulranzen.books.BookPane
import de.millione.schulranzen.books.BookLink
import de.millione.schulranzen.study.StudyLinkDraft
import de.millione.schulranzen.study.StudyLinkKind
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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val library by lazy { Library(this) }
    val settings by lazy { Settings(this) }
    val books by lazy { BookShelf(this) }
    val recognizer by lazy { de.millione.schulranzen.handwriting.HandwritingRecognizer(this) }
    val handwriting by lazy { de.millione.schulranzen.handwriting.HandwritingIndex(this, recognizer) }
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
                // Where we are, as a saveable string: "lib", "nb:<id>", "study", "timetable".
                var route by rememberSaveable { mutableStateOf("lib") }
                val subjects by app.library.subjects.collectAsState(initial = emptyList())
                val studySubjects = remember(subjects) { subjects.map { StudySubject(it.id, it.name, it.colorIndex) } }
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
                                Spacer(Modifier.width(16.dp))
                            },
                            today = { TodayStrip(Modifier.fillMaxWidth(), onClick = { route = "timetable" }) },
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
                                onOpenLink = { link ->
                                    val r = link.rect
                                    route = when (link.linkKind) {
                                        StudyLinkKind.BOOK_PAGE -> BookRoute(link.targetId, link.page,
                                            r?.let { android.graphics.RectF(it.x * 1000f, it.y * 1000f, (it.x + it.w) * 1000f, (it.y + it.h) * 1000f) }).encode()
                                        StudyLinkKind.NOTEBOOK_PAGE -> NotebookRoute(link.targetId, link.page,
                                            r?.let { android.graphics.RectF(it.x * PageGeometry.WIDTH, it.y * PageGeometry.HEIGHT, (it.x + it.w) * PageGeometry.WIDTH, (it.y + it.h) * PageGeometry.HEIGHT) }).encode()
                                    }
                                })
                        }
                        r == "timetable" -> {
                            BackHandler { route = "lib" }
                            TimetableScreen(onBack = { route = "lib" }, subjects = studySubjects)
                        }
                        r == "shk" -> ShkScreen(onBack = { route = "lib" })
                        r == "backup" -> {
                            BackHandler { route = "lib" }
                            BackupScreen(
                                onBack = { route = "lib" },
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
                        r.startsWith("book:") -> {
                            BackHandler { route = "lib" }
                            var clip by remember { mutableStateOf<java.io.File?>(null) }
                            var mark by remember { mutableStateOf<StudyLinkDraft?>(null) }
                            val br = BookRoute.parse(r)
                            Box(Modifier.fillMaxSize()) {
                                BookScreen(br.id, onBack = { route = "lib" }, onClip = { clip = it },
                                    onMarkForExam = { mark = it.toDraft() }, startPage = br.page, highlight = br.flash)
                                mark?.let { d -> MarkForExamSheet(d, onDone = { mark = null }, onCancel = { mark = null }) }
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
                            DisposableEffect(session) {
                                session.open()
                                this@MainActivity.session = session
                                canvas.visibility = View.VISIBLE
                                onDispose {
                                    this@MainActivity.session = null
                                    session.close()
                                    canvas.visibility = View.GONE
                                }
                            }
                            var pickBook by remember { mutableStateOf(false) }
                            SplitWithBook(
                                canvas = canvas,
                                bookId = sideBook,
                                notebook = { compact ->
                                    NotebookScreen(session, app.settings, onBack = { route = "lib" },
                                        onBookBeside = { pickBook = true }, compact = compact)
                                },
                                book = { bookId ->
                                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom))) {
                                        BookPane(bookId, Modifier.fillMaxSize(), onClip = { session.insertImageFile(it) },
                                            onClose = { route = NotebookRoute(id).encode() },
                                            onMarkForExam = { session.markDraft = it.toDraft() })
                                    }
                                },
                            )
                            NotebookSheets(session)
                            if (pickBook) BookPickerSheet(app.books, onPick = { b -> pickBook = false; route = NotebookRoute(id, book = b.id).encode() }, onCancel = { pickBook = false })
                        }
                    }
                }
            }
        }
    }

    /**
     * The tablet's task bar and navigation bar stay hidden (swipe up from the
     * bottom edge shows them briefly); the status bar with clock stays.
     */
    private fun hideTaskBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideTaskBar()
    }

    /**
     * Debug builds only: drive the tool ring from adb, since emulators have
     * no pen side button, e.g.
     * `am start -n <app>/.MainActivity --es ring open --ef x 1200 --ef y 700`.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!BuildConfig.DEBUG) return
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
        if (::canvas.isInitialized && canvas.visibility == View.VISIBLE && ev.pointerCount > 0) {
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

/** Book places are stored with both axes divided by the page width (book units are 1000 wide). */
private fun BookLink.toDraft() = StudyLinkDraft(
    StudyLinkKind.BOOK_PAGE, bookId, page,
    rect?.let { LinkRect(it.left / 1000f, it.top / 1000f, it.width() / 1000f, it.height() / 1000f) },
    label,
)
