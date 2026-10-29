package de.millione.schulranzen.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's own line icons on a 24 grid: 1.7 stroke, round ends, drawn in
 * one colour so they take the theme's ink. No stock Material symbols.
 */
object RanzenIcons {
    /** Line icon in the house style; feature modules use this for their own icons. */
    fun line(name: String, block: PathBuilder.() -> Unit): ImageVector = icon(name, block)

    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
            )
        }.build()

    private fun filled(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = block)
        }.build()

    val Pen = icon("pen") {
        moveTo(4.5f, 19.5f); lineTo(5.5f, 15.5f); lineTo(15.8f, 5.2f)
        arcToRelative(1.9f, 1.9f, 0f, false, true, 2.7f, 0f); lineToRelative(0.3f, 0.3f)
        arcToRelative(1.9f, 1.9f, 0f, false, true, 0f, 2.7f); lineTo(8.5f, 18.5f); close()
        moveTo(14f, 7f); lineTo(17f, 10f)
    }
    val Marker = icon("marker") {
        moveTo(6f, 20f); lineTo(18.5f, 20f)
        moveTo(7.5f, 16.5f); lineTo(9.8f, 12.2f); lineTo(15.6f, 4.8f); lineTo(19.2f, 8.4f); lineTo(11.8f, 14.2f); close()
        moveTo(7.5f, 16.5f); lineTo(5.6f, 18.6f)
    }
    val Eraser = icon("eraser") {
        moveTo(9f, 19.5f); lineTo(19.5f, 19.5f)
        moveTo(9f, 19.5f); lineTo(4.9f, 15.4f); arcToRelative(1.6f, 1.6f, 0f, false, true, 0f, -2.3f)
        lineTo(13.1f, 4.9f); arcToRelative(1.6f, 1.6f, 0f, false, true, 2.3f, 0f); lineTo(19.1f, 8.6f)
        arcToRelative(1.6f, 1.6f, 0f, false, true, 0f, 2.3f); lineTo(10.5f, 19.5f)
        moveTo(8.6f, 9.4f); lineTo(14.6f, 15.4f)
    }
    val Lasso = icon("lasso") {
        moveTo(8f, 15.5f)
        curveTo(4.5f, 14.6f, 3.2f, 12.5f, 4.2f, 10f); curveTo(5.6f, 6.4f, 11.6f, 4.6f, 16.2f, 5.9f)
        curveTo(20.4f, 7.1f, 21f, 10.4f, 18.2f, 12.6f); curveTo(16.4f, 14.1f, 13.2f, 14.8f, 10.4f, 14.6f)
        moveTo(10.4f, 14.6f); curveTo(8.4f, 14.6f, 7.8f, 16.8f, 9.6f, 17.6f); lineTo(8.4f, 20.4f)
    }
    val Ruler = icon("ruler") {
        moveTo(3.8f, 16.2f); lineTo(16.2f, 3.8f); lineTo(20.2f, 7.8f); lineTo(7.8f, 20.2f); close()
        moveTo(7.6f, 12.4f); lineTo(9.4f, 14.2f)
        moveTo(10.4f, 9.6f); lineTo(12.2f, 11.4f)
        moveTo(13.2f, 6.8f); lineTo(15f, 8.6f)
    }
    val Text = icon("text") {
        moveTo(5f, 6.5f); lineTo(5f, 5f); lineTo(19f, 5f); lineTo(19f, 6.5f)
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(9.5f, 19f); lineTo(14.5f, 19f)
    }
    val Undo = icon("undo") {
        moveTo(8.5f, 9f); lineTo(4.5f, 9f); lineTo(4.5f, 5f)
        moveTo(4.8f, 9f); curveTo(6.6f, 6.4f, 9.4f, 5f, 12.5f, 5f)
        curveTo(16.6f, 5f, 19.5f, 8.1f, 19.5f, 12f); curveTo(19.5f, 15.9f, 16.6f, 19f, 12.5f, 19f); lineTo(9f, 19f)
    }
    val Redo = icon("redo") {
        moveTo(15.5f, 9f); lineTo(19.5f, 9f); lineTo(19.5f, 5f)
        moveTo(19.2f, 9f); curveTo(17.4f, 6.4f, 14.6f, 5f, 11.5f, 5f)
        curveTo(7.4f, 5f, 4.5f, 8.1f, 4.5f, 12f); curveTo(4.5f, 15.9f, 7.4f, 19f, 11.5f, 19f); lineTo(15f, 19f)
    }
    val Plus = icon("plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Back = icon("back") { moveTo(14.5f, 5.5f); lineTo(8f, 12f); lineTo(14.5f, 18.5f) }
    val Close = icon("close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Check = icon("check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }
    val Search = icon("search") {
        moveTo(10.5f, 4.5f); arcToRelative(6f, 6f, 0f, true, true, 0f, 12f); arcToRelative(6f, 6f, 0f, true, true, 0f, -12f)
        moveTo(15f, 15f); lineTo(19.5f, 19.5f)
    }
    val Settings = icon("settings") {
        moveTo(12f, 9f); arcToRelative(3f, 3f, 0f, true, true, 0f, 6f); arcToRelative(3f, 3f, 0f, true, true, 0f, -6f)
        moveTo(12f, 3.5f); lineTo(12f, 5.5f); moveTo(12f, 18.5f); lineTo(12f, 20.5f)
        moveTo(3.5f, 12f); lineTo(5.5f, 12f); moveTo(18.5f, 12f); lineTo(20.5f, 12f)
        moveTo(6f, 6f); lineTo(7.4f, 7.4f); moveTo(16.6f, 16.6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(16.6f, 7.4f); moveTo(7.4f, 16.6f); lineTo(6f, 18f)
    }
    val Notebook = icon("notebook") {
        moveTo(6.5f, 3.5f); lineTo(18f, 3.5f); arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f)
        lineTo(19.5f, 19f); arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f); lineTo(6.5f, 20.5f); close()
        moveTo(9.5f, 3.5f); lineTo(9.5f, 20.5f)
        moveTo(4.5f, 7.5f); lineTo(6.5f, 7.5f); moveTo(4.5f, 12f); lineTo(6.5f, 12f); moveTo(4.5f, 16.5f); lineTo(6.5f, 16.5f)
    }
    val Book = icon("book") {
        moveTo(12f, 6.5f); curveTo(10f, 5f, 7f, 4.6f, 4f, 5.2f); lineTo(4f, 18.6f); curveTo(7f, 18f, 10f, 18.4f, 12f, 19.8f)
        curveTo(14f, 18.4f, 17f, 18f, 20f, 18.6f); lineTo(20f, 5.2f); curveTo(17f, 4.6f, 14f, 5f, 12f, 6.5f); close()
        moveTo(12f, 6.5f); lineTo(12f, 19.8f)
    }
    val Pages = icon("pages") {
        moveTo(8f, 3.5f); lineTo(16f, 3.5f); lineTo(19.5f, 7f); lineTo(19.5f, 17.5f); lineTo(8f, 17.5f); close()
        moveTo(5f, 7f); lineTo(5f, 20.5f); lineTo(15.5f, 20.5f)
    }
    val Paper = icon("paper") {
        moveTo(5.5f, 3.5f); lineTo(18.5f, 3.5f); lineTo(18.5f, 20.5f); lineTo(5.5f, 20.5f); close()
        moveTo(8f, 8f); lineTo(16f, 8f); moveTo(8f, 11.5f); lineTo(16f, 11.5f); moveTo(8f, 15f); lineTo(13f, 15f)
    }
    val More = filled("more") {
        moveTo(6f, 10.4f); arcToRelative(1.6f, 1.6f, 0f, true, true, 0f, 3.2f); arcToRelative(1.6f, 1.6f, 0f, true, true, 0f, -3.2f)
        moveTo(12f, 10.4f); arcToRelative(1.6f, 1.6f, 0f, true, true, 0f, 3.2f); arcToRelative(1.6f, 1.6f, 0f, true, true, 0f, -3.2f)
        moveTo(18f, 10.4f); arcToRelative(1.6f, 1.6f, 0f, true, true, 0f, 3.2f); arcToRelative(1.6f, 1.6f, 0f, true, true, 0f, -3.2f)
    }
    val Trash = icon("trash") {
        moveTo(4.5f, 6.5f); lineTo(19.5f, 6.5f); moveTo(9.5f, 6.5f); lineTo(9.5f, 4f); lineTo(14.5f, 4f); lineTo(14.5f, 6.5f)
        moveTo(6.5f, 6.5f); lineTo(7.5f, 20f); lineTo(16.5f, 20f); lineTo(17.5f, 6.5f)
    }
    val Moon = icon("moon") {
        moveTo(19.5f, 14.5f); curveTo(17.9f, 15.3f, 16f, 15.5f, 14.2f, 15f); curveTo(10.4f, 13.9f, 8.1f, 10f, 9f, 6.1f)
        curveTo(9.2f, 5.4f, 9.5f, 4.7f, 9.8f, 4.1f); curveTo(5.8f, 5.1f, 3.3f, 9.1f, 4.2f, 13.2f)
        curveTo(5.2f, 17.8f, 9.8f, 20.6f, 14.4f, 19.6f); curveTo(16.6f, 19.1f, 18.4f, 17.1f, 19.5f, 14.5f); close()
    }
    val Bookmark = icon("bookmark") {
        moveTo(7f, 3.5f); lineTo(17f, 3.5f); lineTo(17f, 20.5f); lineTo(12f, 16.5f); lineTo(7f, 20.5f); close()
    }
    val Lock = icon("lock") {
        moveTo(6f, 10.5f); lineTo(18f, 10.5f); lineTo(18f, 20f); lineTo(6f, 20f); close()
        moveTo(8.5f, 10.5f); lineTo(8.5f, 7.5f); arcToRelative(3.5f, 3.5f, 0f, false, true, 7f, 0f); lineTo(15.5f, 10.5f)
        moveTo(12f, 14f); lineTo(12f, 16.5f)
    }
    val Image = icon("image") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(4f, 16f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(15.5f, 12.5f); lineTo(20f, 17f)
        moveTo(15.5f, 8f); arcToRelative(1.3f, 1.3f, 0f, true, true, 0f, 2.6f); arcToRelative(1.3f, 1.3f, 0f, true, true, 0f, -2.6f)
    }
    val Export = icon("export") {
        moveTo(12f, 14.5f); lineTo(12f, 3.5f); moveTo(8f, 7.5f); lineTo(12f, 3.5f); lineTo(16f, 7.5f)
        moveTo(6f, 11f); lineTo(4.5f, 11f); lineTo(4.5f, 20.5f); lineTo(19.5f, 20.5f); lineTo(19.5f, 11f); lineTo(18f, 11f)
    }
    val Copy = icon("copy") {
        moveTo(8.5f, 8.5f); lineTo(19.5f, 8.5f); lineTo(19.5f, 19.5f); lineTo(8.5f, 19.5f); close()
        moveTo(15.5f, 8.5f); lineTo(15.5f, 4.5f); lineTo(4.5f, 4.5f); lineTo(4.5f, 15.5f); lineTo(8.5f, 15.5f)
    }
    val Scissors = icon("scissors") {
        moveTo(6.5f, 4.5f); arcToRelative(2.5f, 2.5f, 0f, true, true, 0f, 5f); arcToRelative(2.5f, 2.5f, 0f, true, true, 0f, -5f)
        moveTo(6.5f, 14.5f); arcToRelative(2.5f, 2.5f, 0f, true, true, 0f, 5f); arcToRelative(2.5f, 2.5f, 0f, true, true, 0f, -5f)
        moveTo(8.6f, 8.4f); lineTo(20f, 17.5f); moveTo(8.6f, 15.6f); lineTo(20f, 6.5f)
    }
    val Camera = icon("camera") {
        moveTo(4f, 8f); lineTo(7.5f, 8f); lineTo(9f, 5.5f); lineTo(15f, 5.5f); lineTo(16.5f, 8f); lineTo(20f, 8f); lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(12f, 10f); arcToRelative(3.3f, 3.3f, 0f, true, true, 0f, 6.6f); arcToRelative(3.3f, 3.3f, 0f, true, true, 0f, -6.6f)
    }
    val ArrowUp = icon("up") { moveTo(12f, 19f); lineTo(12f, 5f); moveTo(6.5f, 10.5f); lineTo(12f, 5f); lineTo(17.5f, 10.5f) }
    val ArrowDown = icon("down") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(6.5f, 13.5f); lineTo(12f, 19f); lineTo(17.5f, 13.5f) }
}
