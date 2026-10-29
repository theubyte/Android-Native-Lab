package de.millione.schulranzen.core.backup

import java.io.Closeable
import java.io.InputStream

/*
 * Neutral model of everything the old app (V15) knew, ready for the new app.
 *
 * Coordinates: V15 pages are 1240 × 1754 units (A4 at 150 dpi). The new app
 * uses 1000 × 1414 page units for A4 (PageGeometry). Both are A4, so one
 * uniform factor maps them: S = 1000 / 1240 ≈ 0.8065 (1754 · S = 1414.5).
 * Positions, stroke widths, text sizes and image boxes are multiplied by S.
 * Book annotations stay normalised (0…1 of the PDF page) because PDF pages
 * have their own sizes; see [ImportedBookPage].
 */

/** Paper names as used by the new app (PaperStyle.name). */
object ImportedPaper {
    const val LINED = "LINED"
    const val SQUARED = "SQUARED"
    const val DOTTED = "DOTTED"
    const val BLANK = "BLANK"

    /**
     * V15 paper kinds: 0 kariert, 1 liniert mit Rand, 2 gepunktet, 3 groß kariert,
     * 4 eng liniert, 5 technisches Raster, 6 blanko, 7 Stundenplan-Raster.
     */
    fun fromV15(kind: Int): String = when (kind) {
        1, 4 -> LINED
        0, 3, 5 -> SQUARED
        2 -> DOTTED
        else -> BLANK
    }
}

enum class ImportedTool { PEN, MARKER }

/**
 * One stroke. [points] holds x, y, pressure (0…1), tilt (radians, 0…π/2)
 * per point. [width] is the brush size in the same units as the points.
 */
class ImportedStroke(val tool: ImportedTool, val color: Int, val width: Float, val points: FloatArray) {
    val pointCount: Int get() = points.size / 4
    fun x(i: Int) = points[i * 4]
    fun y(i: Int) = points[i * 4 + 1]
    fun pressure(i: Int) = points[i * 4 + 2]
    fun tilt(i: Int) = points[i * 4 + 3]
}

/** Typed text; [x], [y] is the top left corner, [size] the font size, [width] the wrap width. */
data class ImportedText(val x: Float, val y: Float, val width: Float, val size: Float, val color: Int, val text: String)

/**
 * A picture on the page. [entry] is its path in the V15 archive. A
 * [background] fills the whole sheet (scanned worksheet, pasted book clip)
 * and belongs below everything else.
 */
data class ImportedImage(val entry: String, val x: Float, val y: Float, val w: Float, val h: Float, val background: Boolean = false)

class ImportedPage(
    val paper: String,
    val title: String,
    val bookmarked: Boolean,
    val locked: Boolean,
    val strokes: List<ImportedStroke>,
    val texts: List<ImportedText>,
    /** Bottom to top: background first. */
    val images: List<ImportedImage>,
)

enum class NotebookKind { NOTEBOOK, WORKSHEET, TIMETABLE }

class ImportedNotebook(
    /** V15 item id (exams refer to it). */
    val oldId: String,
    val title: String,
    val subjectName: String,
    val kind: NotebookKind,
    /** Ruling of the first page, used as the notebook default. */
    val paper: String,
    val created: Long,
    val modified: Long,
    val trashed: Boolean,
    val pages: List<ImportedPage>,
) {
    val imageEntries: Set<String> get() = pages.flatMapTo(LinkedHashSet()) { p -> p.images.map { it.entry } }
}

data class ImportedSubject(val name: String, val colorArgb: Int, /** Index into SubjectPalette. */ val colorIndex: Int)

data class ImportedNote(val x: Float, val y: Float, val text: String, val created: Long)
data class ImportedFavorite(val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float, val label: String, val created: Long)

/**
 * Annotations on one PDF page ([index] from 0). Stroke points and notes are
 * normalised to the page (0…1 in x and y); stroke [ImportedStroke.width]
 * is a fraction of the page width, as in V15.
 */
class ImportedBookPage(val index: Int, val bookmarked: Boolean, val strokes: List<ImportedStroke>, val notes: List<ImportedNote>)

class ImportedBook(
    val oldId: String,
    val title: String,
    val subjectName: String,
    /** Path of the PDF in the archive, or null when the backup has no copy. */
    val pdfEntry: String?,
    val pdfSize: Long,
    val created: Long,
    val modified: Long,
    val trashed: Boolean,
    val pages: List<ImportedBookPage>,
    val favorites: List<ImportedFavorite>,
)

data class ImportedExam(val oldId: String, val title: String, val subjectName: String, /** ISO date. */ val date: String, val sourceIds: List<String>)
data class ImportedCard(
    val oldId: String, val examId: String, val front: String, val back: String,
    val dueEpochDay: Long, val reps: Int, val interval: Int, val ease: Double, val lastRating: Int,
)
/** "Für Prüfung merken" on a page or region (normalised; left < 0 = whole page). */
data class ImportedMark(
    val oldId: String, val itemId: String, val page: Int, val itemTitle: String, val subjectName: String, val label: String,
    val left: Float, val top: Float, val right: Float, val bottom: Float, val created: Long,
)

class ImportedStudy(val exams: List<ImportedExam>, val cards: List<ImportedCard>, val marks: List<ImportedMark>) {
    val isEmpty get() = exams.isEmpty() && cards.isEmpty() && marks.isEmpty()
}

/** Read access to files in the V15 archive (PDFs, pictures). */
interface ArchiveFiles : Closeable {
    fun exists(entry: String): Boolean
    fun size(entry: String): Long
    fun open(entry: String): InputStream
}

/**
 * Everything read from a V15 backup. Holds the archive open for PDFs and
 * pictures until [close].
 */
class ImportedLibrary(
    val subjects: List<ImportedSubject>,
    val notebooks: List<ImportedNotebook>,
    val books: List<ImportedBook>,
    val study: ImportedStudy,
    /** Things that could not be read; shown to the user, nothing fatal. */
    val warnings: List<String>,
    /** Backup time from the V15 manifest (epoch millis), if any. */
    val created: Long?,
    /** True when a V15 manifest was present and all checksums matched. */
    val verified: Boolean,
    /** Sync server and e-mail the old app used, to prefill the login. */
    val syncServer: String?,
    val syncEmail: String?,
    val files: ArchiveFiles,
) : Closeable {
    val pageCount: Int get() = notebooks.sumOf { it.pages.size }
    fun notebooksOf(kind: NotebookKind) = notebooks.filter { it.kind == kind }
    override fun close() = files.close()
}
