package de.millione.schulranzen.books

import de.millione.schulranzen.design.RanzenIcons
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The reader's own line icons, drawn in the house style. */
internal object BookIcons {
    /** Reading: an open hand would be fussy; an eye on a page says "just look". */
    val Read = RanzenIcons.line("read") {
        moveTo(2.8f, 12f)
        curveTo(5f, 7.6f, 8.3f, 5.5f, 12f, 5.5f); curveTo(15.7f, 5.5f, 19f, 7.6f, 21.2f, 12f)
        curveTo(19f, 16.4f, 15.7f, 18.5f, 12f, 18.5f); curveTo(8.3f, 18.5f, 5f, 16.4f, 2.8f, 12f); close()
        moveTo(12f, 9f); arcToRelative(3f, 3f, 0f, true, true, 0f, 6f); arcToRelative(3f, 3f, 0f, true, true, 0f, -6f)
    }

    /** Turn the page clockwise. */
    val Rotate = RanzenIcons.line("rotate") {
        moveTo(19.5f, 12.5f)
        arcToRelative(7.5f, 7.5f, 0f, true, true, -2.6f, -5.7f)
        moveTo(17.4f, 3.2f); lineTo(17.4f, 7.2f); lineTo(13.4f, 7.2f)
        moveTo(9.5f, 10f); lineTo(14.5f, 10f); lineTo(14.5f, 16f); lineTo(9.5f, 16f); close()
    }

    /** A sticky note with a folded corner. */
    val Note = RanzenIcons.line("note") {
        moveTo(5f, 4.5f); lineTo(19f, 4.5f); lineTo(19f, 14.5f); lineTo(14f, 19.5f); lineTo(5f, 19.5f); close()
        moveTo(19f, 14.5f); lineTo(14f, 14.5f); lineTo(14f, 19.5f)
        moveTo(8f, 9f); lineTo(16f, 9f); moveTo(8f, 12f); lineTo(12.5f, 12f)
    }

    /** Marked area: crop corners around a dashed middle. */
    val Area = RanzenIcons.line("area") {
        moveTo(4f, 8.5f); lineTo(4f, 4f); lineTo(8.5f, 4f)
        moveTo(15.5f, 4f); lineTo(20f, 4f); lineTo(20f, 8.5f)
        moveTo(20f, 15.5f); lineTo(20f, 20f); lineTo(15.5f, 20f)
        moveTo(8.5f, 20f); lineTo(4f, 20f); lineTo(4f, 15.5f)
        moveTo(9f, 12f); lineTo(10.5f, 12f); moveTo(13.5f, 12f); lineTo(15f, 12f)
    }

    /** A five-pointed star for kept areas. */
    val Star = RanzenIcons.line("star") {
        for (k in 0 until 10) {
            val r = if (k % 2 == 0) 8.6f else 3.7f
            val a = -PI / 2 + k * PI / 5
            val x = 12f + (r * cos(a)).toFloat(); val y = 12.6f + (r * sin(a)).toFloat()
            if (k == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    /** Contents: a list with bullets. */
    val Contents = RanzenIcons.line("contents") {
        moveTo(4.5f, 6.5f); lineTo(5.5f, 6.5f); moveTo(9f, 6.5f); lineTo(19.5f, 6.5f)
        moveTo(4.5f, 12f); lineTo(5.5f, 12f); moveTo(9f, 12f); lineTo(19.5f, 12f)
        moveTo(7f, 17.5f); lineTo(8f, 17.5f); moveTo(11.5f, 17.5f); lineTo(19.5f, 17.5f)
    }

    /** Marked for the exam: a pennant on a pole. */
    val Exam = RanzenIcons.line("exam") {
        moveTo(6f, 20.5f); lineTo(6f, 3.5f)
        moveTo(6f, 4.5f); lineTo(18.5f, 8.2f); lineTo(6f, 12f)
    }

    val Prev = RanzenIcons.line("prev") { moveTo(14.5f, 5.5f); lineTo(8f, 12f); lineTo(14.5f, 18.5f) }
    val Next = RanzenIcons.line("next") { moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f) }
}
