package de.millione.schulranzen.app

import android.app.Application
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
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
import de.millione.schulranzen.app.data.Library
import de.millione.schulranzen.app.data.Settings
import de.millione.schulranzen.app.library.LibraryScreen
import de.millione.schulranzen.app.notebook.NotebookScreen
import de.millione.schulranzen.app.notebook.NotebookSession
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenTheme
import de.millione.schulranzen.design.ThemeMode
import de.millione.schulranzen.ink.NotebookCanvasView
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
    private val app get() = application as RanzenApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(0, 0),
            navigationBarStyle = SystemBarStyle.auto(0, 0),
        )
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        canvas = NotebookCanvasView(this).apply { visibility = View.GONE }
        val ui = ComposeView(this)
        root.addView(canvas, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(ui, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        ui.setContent {
            val mode by app.settings.theme.collectAsState()
            RanzenTheme(mode) {
                val desk = Ranzen.colors.desk
                SideEffect { root.setBackgroundColor(desk.toArgb()) }
                LaunchedEffect(Unit) { app.library.seed() }
                var open by rememberSaveable { mutableStateOf<String?>(null) }
                AnimatedContent(
                    open,
                    transitionSpec = { (fadeIn() + scaleIn(spring(dampingRatio = 0.8f), initialScale = 0.97f)) togetherWith fadeOut() },
                    label = "screen",
                ) { id ->
                    val isDark = Ranzen.colors.isDark
                    if (id == null) {
                        LibraryScreen(
                            app.library,
                            onOpen = { open = it },
                            onToggleTheme = {
                                app.settings.setTheme(if (isDark) ThemeMode.LIGHT else ThemeMode.DARK)
                            },
                        )
                    } else {
                        val session = remember(id) { NotebookSession(id, canvas, app.library, app.settings, app.scope) }
                        DisposableEffect(session) {
                            session.open()
                            canvas.visibility = View.VISIBLE
                            onDispose {
                                session.close()
                                canvas.visibility = View.GONE
                            }
                        }
                        NotebookScreen(session, app.settings, onBack = { open = null })
                    }
                }
            }
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
