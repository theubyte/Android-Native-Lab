package de.millione.schulranzen.rechnen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.design.RanzenIcons

/** Line icons of the calculator and SHK screens, drawn in the house style. */
internal object RechnenIcons {
    val History: ImageVector = RanzenIcons.line("history") {
        moveTo(4.6f, 12f); arcToRelative(7.4f, 7.4f, 0f, true, true, 2.2f, 5.2f)
        moveTo(4.6f, 12f); lineTo(2.8f, 10.2f); moveTo(4.6f, 12f); lineTo(6.4f, 10.2f)
        moveTo(12f, 8f); lineTo(12f, 12.4f); lineTo(15f, 14.2f)
    }
    val Grip: ImageVector = RanzenIcons.line("grip") {
        moveTo(19f, 9f); lineTo(9f, 19f)
        moveTo(19f, 13.5f); lineTo(13.5f, 19f)
        moveTo(19f, 18f); lineTo(18f, 19f)
    }
    val Star: ImageVector = RanzenIcons.line("star") {
        moveTo(12f, 3.8f); lineTo(14.5f, 9f); lineTo(20.2f, 9.6f); lineTo(15.9f, 13.4f); lineTo(17.1f, 19f)
        lineTo(12f, 16.2f); lineTo(6.9f, 19f); lineTo(8.1f, 13.4f); lineTo(3.8f, 9.6f); lineTo(9.5f, 9f); close()
    }
    /** Filled star for favourites: same outline, solid inside. */
    val StarFilled: ImageVector = ImageVector.Builder("star_filled", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 3.8f); lineTo(14.5f, 9f); lineTo(20.2f, 9.6f); lineTo(15.9f, 13.4f); lineTo(17.1f, 19f)
            lineTo(12f, 16.2f); lineTo(6.9f, 19f); lineTo(8.1f, 13.4f); lineTo(3.8f, 9.6f); lineTo(9.5f, 9f); close()
        }
    }.build()
    val Calculator: ImageVector = RanzenIcons.line("calculator") {
        moveTo(6.5f, 3.5f); lineTo(17.5f, 3.5f); lineTo(17.5f, 20.5f); lineTo(6.5f, 20.5f); close()
        moveTo(8.8f, 6f); lineTo(15.2f, 6f); lineTo(15.2f, 9f); lineTo(8.8f, 9f); close()
        moveTo(9f, 12.5f); lineTo(9.1f, 12.5f); moveTo(12f, 12.5f); lineTo(12.1f, 12.5f); moveTo(15f, 12.5f); lineTo(15.1f, 12.5f)
        moveTo(9f, 15.5f); lineTo(9.1f, 15.5f); moveTo(12f, 15.5f); lineTo(12.1f, 15.5f); moveTo(15f, 15.5f); lineTo(15.1f, 18f)
        moveTo(9f, 18f); lineTo(9.1f, 18f); moveTo(12f, 18f); lineTo(12.1f, 18f)
    }
    val Swap: ImageVector = RanzenIcons.line("swap") {
        moveTo(5f, 8.5f); lineTo(18.5f, 8.5f); moveTo(15f, 5f); lineTo(18.5f, 8.5f); lineTo(15f, 12f)
        moveTo(19f, 15.5f); lineTo(5.5f, 15.5f); moveTo(9f, 12f); lineTo(5.5f, 15.5f); lineTo(9f, 19f)
    }
    val Formula: ImageVector = RanzenIcons.line("formula") {
        moveTo(17f, 5f); lineTo(7f, 5f); lineTo(12.5f, 12f); lineTo(7f, 19f); lineTo(17f, 19f)
    }
}
