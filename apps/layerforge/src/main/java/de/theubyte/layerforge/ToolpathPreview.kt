package de.theubyte.layerforge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File

/** Layer preview for LayerForge's generated G-code. Not an independent G-code simulator. */
data class ToolLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
data class ToolLayer(val z: Float, val extrusion: List<ToolLine>, val travel: List<ToolLine>)
data class Toolpath(val layers: List<ToolLayer>)

fun parseToolpath(file: File): Toolpath {
    val result = mutableListOf<ToolLayer>()
    var extrusion = mutableListOf<ToolLine>()
    var travel = mutableListOf<ToolLine>()
    var x = 0f; var y = 0f; var z = 0f
    var started = false
    var totalSegments = 0
    fun complete() {
        if (started) result += ToolLayer(z, extrusion, travel)
        extrusion = mutableListOf(); travel = mutableListOf()
        started = true
    }
    file.bufferedReader().useLines { sequence ->
        sequence.forEach { raw ->
            val line = raw.trim()
            if (line.startsWith(";LAYER:")) { complete(); return@forEach }
            if (!(line.startsWith("G0 ") || line.startsWith("G1 "))) return@forEach
            val fields = line.split(' ').drop(1)
            var nx = x; var ny = y; var nz = z; var e = 0f
            for (word in fields) {
                if (word.length < 2) continue
                val number = word.substring(1).toFloatOrNull() ?: continue
                when (word.first()) {
                    'X' -> nx = number
                    'Y' -> ny = number
                    'Z' -> nz = number
                    'E' -> e = number
                }
            }
            if (started && (nx != x || ny != y) && totalSegments < 1_000_000) {
                val segment = ToolLine(x, y, nx, ny)
                if (line.startsWith("G1 ") && e > 0f) extrusion.add(segment)
                else travel.add(segment)
                totalSegments++
            }
            x = nx; y = ny; z = nz
        }
    }
    if (started) result += ToolLayer(z, extrusion, travel)
    return Toolpath(result)
}

@Composable
fun ToolpathPreview(data: Toolpath, bedX: Double, bedY: Double) {
    if (data.layers.isEmpty()) return
    var layer by remember(data) { mutableIntStateOf(data.layers.size - 1) }
    val item = data.layers[layer.coerceIn(0,data.layers.lastIndex)]
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Werkzeugpfade", style=MaterialTheme.typography.titleMedium)
            Text("Schicht ${layer+1} / ${data.layers.size}", color=Color(0xFF36D6C7))
        }
        Canvas(Modifier.fillMaxWidth().height(290.dp).background(Color(0xFF101E29))) {
            val side = minOf(size.width, size.height) * 0.9f
            val rx = (bedX.toFloat() / maxOf(bedX.toFloat(),bedY.toFloat())) * side
            val ry = (bedY.toFloat() / maxOf(bedX.toFloat(),bedY.toFloat())) * side
            val left=(size.width-rx)/2; val top=(size.height-ry)/2
            drawRect(Color(0xFF284A58),topLeft=Offset(left,top),size=androidx.compose.ui.geometry.Size(rx,ry),style=androidx.compose.ui.graphics.drawscope.Stroke(width=2f))
            fun p(x:Float,y:Float):Offset=Offset(left+x/bedX.toFloat()*rx,top+(1f-y/bedY.toFloat())*ry)
            item.travel.forEach { line ->
                drawLine(Color(0xFF597184).copy(alpha=0.40f),p(line.x1,line.y1),p(line.x2,line.y2),strokeWidth=1f)
            }
            item.extrusion.forEach { line ->
                drawLine(Color(0xFF36D6C7),p(line.x1,line.y1),p(line.x2,line.y2),strokeWidth=2f)
            }
        }
        Slider(
            value = layer.toFloat(),
            onValueChange = { layer=it.toInt().coerceIn(0,data.layers.lastIndex) },
            valueRange = 0f..maxOf(1f,data.layers.lastIndex.toFloat())
        )
        Text("Türkis: Extrusion · Grau: Leerfahrt · Z=${"%.2f".format(item.z)} mm",
            style=MaterialTheme.typography.bodySmall, color=Color(0xFF9FB2C3))
    }
}
