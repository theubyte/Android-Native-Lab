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
                // Where we are, as a saveable string: "lib", "nb:<id>", "study", "timetable".
                var route by rememberSaveable { mutableStateOf("lib") }
                val subjects by app.library.subjects.collectAsState(initial = emptyList())
                val studySubjects = remember(subjects) { subjects.map { StudySubject(it.id, it.name, it.colorIndex) } }
                AnimatedContent(
                    route,
                    transitionSpec = { (fadeIn() + scaleIn(spring(dampingRatio = 0.8f), initialScale = 0.97f)) togetherWith fadeOut() },
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
                                Spacer(Modifier.width(16.dp))
                            },
                            today = { TodayStrip(Modifier.fillMaxWidth(), onClick = { route = "timetable" }) },
                        )
                        r == "study" -> {
                            BackHandler { route = "lib" }
                            StudyHome(onBack = { route = "lib" }, onOpenTimetable = { route = "timetable" }, subjects = studySubjects)
                        }
                        r == "timetable" -> {
                            BackHandler { route = "lib" }
                            TimetableScreen(onBack = { route = "lib" }, subjects = studySubjects)
                        }
                        r.startsWith("nb:") -> {
                            val id = r.removePrefix("nb:")
                            val session = remember(id) { NotebookSession(id, canvas, app.library, app.settings, app.scope) }
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
                            NotebookScreen(session, app.settings, onBack = { route = "lib" })
                        }
                    }
                }
            }
        }
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
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) {
                val loc = IntArray(2).also { canvas.getLocationInWindow(it) }
                val local = MotionEvent.obtain(ev).apply { offsetLocation(-loc[0].toFloat(), -loc[1].toFloat()) }
                try { if (canvas.handleHover(local)) return true } finally { local.recycle() }
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }
}
