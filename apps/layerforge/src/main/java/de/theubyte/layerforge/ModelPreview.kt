package de.theubyte.layerforge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Interactive sampled wireframe: visual reference, NOT toolpath simulation. */
@Composable
fun ModelPreview(model: LoadedModel?, modifier: Modifier = Modifier) {
    var yaw by remember { mutableFloatStateOf(-0.6f) }
    var pitch by remember { mutableFloatStateOf(0.55f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    Column(modifier = modifier) {
        Box(Modifier.fillMaxWidth().height(265.dp).background(Color(0xFF111D2A))) {
            if (model == null) {
                Text(
                    "STL oder OBJ importieren, um das 3D-Modell anzuzeigen",
                    modifier = Modifier.fillMaxWidth().padding(36.dp),
                    color = Color(0xFF91A8BD), textAlign = TextAlign.Center
                )
            } else {
                val verts = model.previewVertices
                Canvas(Modifier.fillMaxSize().pointerInput(model.name) {
                    detectTransformGestures { _, pan, scale, rotation ->
                        yaw += pan.x * 0.008f + rotation * 0.4f
                        pitch = (pitch - pan.y * 0.008f).coerceIn(-1.4f, 1.4f)
                        zoom = (zoom * scale).coerceIn(0.25f, 5f)
                    }
                }) {
                    val maxDim = max(1f, max(model.width, max(model.depth, model.height)))
                    val s = min(size.width, size.height) * 0.74f / maxDim * zoom
                    val cy = cos(yaw); val sy = sin(yaw)
                    val cp = cos(pitch); val sp = sin(pitch)
                    fun project(x: Float, y: Float, z: Float): Offset {
                        val rx = cy*x - sy*y
                        val ry = sy*x + cy*y
                        val tiltedY = cp*ry - sp*z
                        return Offset(size.width/2 + rx*s, size.height/2 - tiltedY*s)
                    }
                    val linePath = Path()
                    for (i in verts.indices step 9) {
                        if (i + 8 >= verts.size) break
                        val a = project(verts[i], verts[i+1], verts[i+2])
                        val b = project(verts[i+3], verts[i+4], verts[i+5])
                        val c = project(verts[i+6], verts[i+7], verts[i+8])
                        linePath.moveTo(a.x, a.y)
                        linePath.lineTo(b.x, b.y)
                        linePath.lineTo(c.x, c.y)
                        linePath.close()
                    }
                    drawPath(linePath, Color(0xFF6EDFE8).copy(alpha=0.55f), style=Stroke(width=1.2f))
                }
            }
        }
        if (model != null) {
            Text(
                "${model.width.format1()} × ${model.depth.format1()} × ${model.height.format1()} mm · ${model.triangles} Dreiecke",
                Modifier.fillMaxWidth().padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
            )
        }
    }
}
private fun Float.format1(): String = "%.1f".format(java.util.Locale.US, this)
