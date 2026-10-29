package de.millione.schulranzen.app

import de.millione.schulranzen.design.RanzenIcons

/** Icons for the areas reachable from the library. */
object AppIcons {
    val Study = RanzenIcons.line("study") {
        // A stack of index cards
        moveTo(4f, 8f); lineTo(17f, 8f); lineTo(17f, 19.5f); lineTo(4f, 19.5f); close()
        moveTo(7f, 5f); lineTo(20f, 5f); lineTo(20f, 16.5f)
        moveTo(7f, 12.5f); lineTo(14f, 12.5f); moveTo(7f, 15.5f); lineTo(12f, 15.5f)
    }
    val Timetable = RanzenIcons.line("timetable") {
        moveTo(3.5f, 5f); lineTo(20.5f, 5f); lineTo(20.5f, 19.5f); lineTo(3.5f, 19.5f); close()
        moveTo(3.5f, 9.5f); lineTo(20.5f, 9.5f)
        moveTo(9f, 5f); lineTo(9f, 19.5f); moveTo(14.75f, 5f); lineTo(14.75f, 19.5f)
        moveTo(3.5f, 14.5f); lineTo(20.5f, 14.5f)
    }
}
