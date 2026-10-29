package de.millione.schulranzen.app.notebook

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import java.util.concurrent.ConcurrentHashMap

/**
 * Window areas where a hovering pen belongs to Compose controls (the tool
 * rail) instead of the writing surface underneath, so tool names can show.
 */
object PenHoverZones {
    val zones = ConcurrentHashMap<String, RectF>()
    fun contains(x: Float, y: Float) = zones.values.any { it.contains(x, y) }
}

/** Registers this element's window bounds as a pen hover zone while it is shown. */
@Composable
fun Modifier.penHoverZone(key: String): Modifier {
    DisposableEffect(key) { onDispose { PenHoverZones.zones.remove(key) } }
    return onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        PenHoverZones.zones[key] = RectF(b.left, b.top, b.right, b.bottom)
    }
}

/** Calls [onHover] with true when a pen (or mouse) hovers over the element, false when it leaves. */
fun Modifier.onHover(onHover: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            when (e.type) {
                PointerEventType.Enter -> onHover(true)
                PointerEventType.Exit -> onHover(false)
            }
        }
    }
}
