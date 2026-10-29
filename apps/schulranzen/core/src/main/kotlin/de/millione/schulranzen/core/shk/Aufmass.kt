package de.millione.schulranzen.core.shk

import de.millione.schulranzen.core.math.GermanNumber
import de.millione.schulranzen.core.math.MathEngine
import de.millione.schulranzen.core.math.MathOutcome
import kotlinx.serialization.Serializable

/** What a measured position is; decides unit and how it enters the material list. */
@Serializable
enum class PositionKind(val title: String, val unit: String) {
    PIPE("Rohrleitung", "m"),
    FITTING("Formteil", "Stk"),
    RADIATOR("Heizkörper", "Stk"),
    VALVE("Armatur", "Stk"),
    INSULATION("Dämmung", "m"),
    LENGTH("Länge", "m"),
    AREA("Fläche", "m²"),
    PIECE("Sonstiges", "Stk"),
}

/**
 * One measured line. [expression] is the measurement as written on site
 * ("2,5*3+1,2"); [pipeId] refers to [PipeCatalog] for pipes and insulation.
 */
@Serializable
data class AufmassPosition(
    val id: String,
    val kind: PositionKind,
    val text: String = "",
    val expression: String = "",
    val pipeId: String? = null,
    /** Pipes only: also insulate this run (material list adds GEG insulation). */
    val insulate: Boolean = false,
    val note: String = "",
) {
    val unit: String get() = kind.unit

    /** The evaluated quantity, or null while the expression is empty or not a calculation. */
    val quantity: Double? get() = evaluate(expression)

    /** Readable name: own text, else pipe label, else kind. */
    val title: String get() = text.ifBlank { PipeCatalog.byId(pipeId)?.fullLabel ?: kind.title }

    companion object {
        fun evaluate(expression: String): Double? {
            if (expression.isBlank()) return null
            return (MathEngine.tryEvaluate(expression) as? MathOutcome.Ok)?.result?.value?.takeIf { it.isFinite() }
        }
    }
}

@Serializable
data class AufmassRoom(val id: String, val name: String, val positions: List<AufmassPosition> = emptyList())

@Serializable
data class AufmassProject(
    val id: String,
    val name: String,
    val address: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val rooms: List<AufmassRoom> = emptyList(),
)

/** A summed line: same kind, pipe and text across rooms. */
data class AufmassSum(val kind: PositionKind, val title: String, val pipeId: String?, val quantity: Double, val unit: String)

object AufmassMath {
    /** Groups equal positions (kind, pipe, text) and sums them; unreadable expressions count as 0. */
    fun sums(positions: List<AufmassPosition>): List<AufmassSum> =
        positions.groupBy { Triple(it.kind, it.pipeId, it.title) }
            .map { (k, list) -> AufmassSum(k.first, k.third, k.second, list.sumOf { it.quantity ?: 0.0 }, k.first.unit) }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.title }))

    fun sums(project: AufmassProject): List<AufmassSum> = sums(project.rooms.flatMap { it.positions })

    /** Positions whose expression is filled but not readable – shown as a warning. */
    fun invalid(project: AufmassProject): List<AufmassPosition> =
        project.rooms.flatMap { it.positions }.filter { it.expression.isNotBlank() && it.quantity == null }
}

/** CSV for spreadsheets: semicolon separated, decimal comma, UTF-8 with BOM so Excel reads umlauts. */
object ShkCsv {
    const val BOM = "﻿"

    fun cell(s: String): String =
        if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun row(vararg cells: String): String = cells.joinToString(";") { cell(it) }

    fun num(v: Double?, decimals: Int = 3): String = if (v == null) "" else GermanNumber.format(v, decimals, grouping = false).replace(GermanNumber.MINUS, '-')

    fun aufmass(project: AufmassProject): String = buildString {
        append(BOM)
        appendLine(row("Aufmaß", project.name))
        if (project.address.isNotBlank()) appendLine(row("Baustelle", project.address))
        appendLine()
        appendLine(row("Raum", "Art", "Bezeichnung", "Rohr", "Rechnung", "Menge", "Einheit", "Gedämmt", "Bemerkung"))
        project.rooms.forEach { room ->
            room.positions.forEach { p ->
                appendLine(
                    row(
                        room.name, p.kind.title, p.title, PipeCatalog.byId(p.pipeId)?.fullLabel ?: "",
                        p.expression, num(p.quantity), p.unit, if (p.insulate) "ja" else "", p.note,
                    ),
                )
            }
        }
        appendLine()
        appendLine(row("Summen"))
        appendLine(row("Art", "Bezeichnung", "Menge", "Einheit"))
        AufmassMath.sums(project).forEach { appendLine(row(it.kind.title, it.title, num(it.quantity), it.unit)) }
    }

    fun materials(title: String, list: MaterialList): String = buildString {
        append(BOM)
        appendLine(row("Materialliste", title))
        appendLine()
        appendLine(row("Gruppe", "Material", "Menge", "Einheit", "Hinweis"))
        list.lines.forEach { appendLine(row(it.group.title, it.text, num(it.quantity, 2), it.unit, it.note)) }
    }
}
