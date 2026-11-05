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
    /** SHK: a pipe with a valve wheel. */
    val Shk = RanzenIcons.line("shk") {
        moveTo(3f, 15f); lineTo(21f, 15f); moveTo(3f, 19f); lineTo(21f, 19f)
        moveTo(12f, 15f); lineTo(12f, 10f)
        moveTo(8f, 10f); lineTo(16f, 10f)
        moveTo(12f, 3.5f); arcToRelative(3f, 3f, 0f, true, true, 0f, 6f); arcToRelative(3f, 3f, 0f, true, true, 0f, -6f)
    }
    /** Backup: an archive box with an arrow. */
    val Backup = RanzenIcons.line("backup") {
        moveTo(4f, 8f); lineTo(20f, 8f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 8f); lineTo(3f, 8f); close()
        moveTo(12f, 11f); lineTo(12f, 17f); moveTo(9.5f, 14.5f); lineTo(12f, 17f); lineTo(14.5f, 14.5f)
    }
    /** Scan: the corners of a viewfinder around a sheet. */
    val Scan = RanzenIcons.line("scan") {
        moveTo(4f, 8.5f); lineTo(4f, 4f); lineTo(8.5f, 4f)
        moveTo(15.5f, 4f); lineTo(20f, 4f); lineTo(20f, 8.5f)
        moveTo(20f, 15.5f); lineTo(20f, 20f); lineTo(15.5f, 20f)
        moveTo(8.5f, 20f); lineTo(4f, 20f); lineTo(4f, 15.5f)
        moveTo(8f, 12f); lineTo(16f, 12f)
    }
    /** Two devices exchanging: arrows in a loop. */
    val Sync = RanzenIcons.line("sync") {
        moveTo(4.5f, 11f); curveTo(5f, 7f, 8.2f, 4.5f, 12f, 4.5f); curveTo(14.6f, 4.5f, 16.9f, 5.7f, 18.4f, 7.7f)
        moveTo(19f, 3.8f); lineTo(19f, 8f); lineTo(14.8f, 8f)
        moveTo(19.5f, 13f); curveTo(19f, 17f, 15.8f, 19.5f, 12f, 19.5f); curveTo(9.4f, 19.5f, 7.1f, 18.3f, 5.6f, 16.3f)
        moveTo(5f, 20.2f); lineTo(5f, 16f); lineTo(9.2f, 16f)
    }
    /** A quarter turn clockwise. */
    val Turn = RanzenIcons.line("turn") {
        moveTo(19.5f, 12f); curveTo(19.5f, 16.1f, 16.1f, 19.5f, 12f, 19.5f); curveTo(7.9f, 19.5f, 4.5f, 16.1f, 4.5f, 12f)
        curveTo(4.5f, 7.9f, 7.9f, 4.5f, 12f, 4.5f); lineTo(17.5f, 4.5f)
        moveTo(14.5f, 1.5f); lineTo(17.5f, 4.5f); lineTo(14.5f, 7.5f)
    }
    val Timetable = RanzenIcons.line("timetable") {
        moveTo(3.5f, 5f); lineTo(20.5f, 5f); lineTo(20.5f, 19.5f); lineTo(3.5f, 19.5f); close()
        moveTo(3.5f, 9.5f); lineTo(20.5f, 9.5f)
        moveTo(9f, 5f); lineTo(9f, 19.5f); moveTo(14.75f, 5f); lineTo(14.75f, 19.5f)
        moveTo(3.5f, 14.5f); lineTo(20.5f, 14.5f)
    }
}
