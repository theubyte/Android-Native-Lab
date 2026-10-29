package de.millione.schulranzen.core.shk

import de.millione.schulranzen.core.math.GermanNumber
import kotlinx.serialization.Serializable
import kotlin.math.ceil

enum class MaterialGroup(val title: String) {
    PIPE("Rohre"),
    FITTING("Formteile"),
    CLAMP("Befestigung"),
    INSULATION("Dämmung"),
    RADIATOR("Heizflächen"),
    VALVE("Armaturen"),
    COMPONENT("Bauteile"),
    OTHER("Sonstiges"),
}

/** Allowances for the material list, all in percent. */
@Serializable
data class MaterialSettings(
    val pipeWastePercent: Double = 10.0,
    val fittingExtraPercent: Double = 5.0,
    val clampExtraPercent: Double = 10.0,
    val insulationWastePercent: Double = 10.0,
    val insulationCase: InsulationCase = InsulationCase.NORMAL,
    /** Length of one insulation sleeve in m. */
    val insulationPieceM: Double = 2.0,
)

/** One straight-run pipe length of a known dimension. */
data class PipeRun(val pipeId: String, val lengthM: Double, val insulate: Boolean)

/** Counted or measured items that are passed through (with an allowance for fittings). */
data class CountItem(val group: MaterialGroup, val title: String, val quantity: Double, val unit: String)

/** Everything the list is built from; several Aufmaß projects and schemata can be merged. */
data class MaterialInput(
    val runs: List<PipeRun> = emptyList(),
    /** Insulation measured on its own (m per pipe dimension). */
    val insulation: List<PipeRun> = emptyList(),
    val items: List<CountItem> = emptyList(),
) {
    operator fun plus(o: MaterialInput) = MaterialInput(runs + o.runs, insulation + o.insulation, items + o.items)
}

data class MaterialLine(val group: MaterialGroup, val text: String, val quantity: Double, val unit: String, val note: String = "")

data class MaterialList(val lines: List<MaterialLine>) {
    fun byGroup(): Map<MaterialGroup, List<MaterialLine>> = lines.groupBy { it.group }.toSortedMap(compareBy { it.ordinal })
}

/**
 * Builds the material list: pipe in whole bars with an off-cut allowance,
 * clamps from the support spacing per run, GEG insulation in sleeves, and the
 * counted parts. Pure so it can be tested and reused for Aufmaß and schema.
 */
object MaterialPlanner {
    private fun n(v: Double, d: Int = 2) = GermanNumber.format(v, d)
    private fun up(v: Double) = ceil(v - 1e-9)

    fun fromAufmass(project: AufmassProject): MaterialInput {
        val runs = ArrayList<PipeRun>(); val ins = ArrayList<PipeRun>(); val items = ArrayList<CountItem>()
        project.rooms.flatMap { it.positions }.forEach { p ->
            val q = p.quantity ?: return@forEach
            if (q <= 0.0) return@forEach
            val pipe = PipeCatalog.byId(p.pipeId)
            when (p.kind) {
                PositionKind.PIPE ->
                    if (pipe != null) runs += PipeRun(pipe.id, q, p.insulate)
                    else items += CountItem(MaterialGroup.PIPE, p.title + " (ohne Dimension)", q, "m")
                PositionKind.INSULATION ->
                    if (pipe != null) ins += PipeRun(pipe.id, q, true)
                    else items += CountItem(MaterialGroup.INSULATION, p.title, q, "m")
                PositionKind.FITTING -> items += CountItem(MaterialGroup.FITTING, p.title, q, p.unit)
                PositionKind.RADIATOR -> items += CountItem(MaterialGroup.RADIATOR, p.title, q, p.unit)
                PositionKind.VALVE -> items += CountItem(MaterialGroup.VALVE, p.title, q, p.unit)
                PositionKind.LENGTH, PositionKind.AREA, PositionKind.PIECE -> items += CountItem(MaterialGroup.OTHER, p.title, q, p.unit)
            }
        }
        return MaterialInput(runs, ins, items)
    }

    /** Pipes with a dimension become runs; symbols are counted by type (and label, if any). */
    fun fromSchema(schema: Schema): MaterialInput {
        val runs = ArrayList<PipeRun>(); val items = ArrayList<CountItem>()
        schema.pipes.forEach { p ->
            val len = SchemaGeometry.lengthM(schema, p)
            if (len <= 0.0) return@forEach
            if (PipeCatalog.byId(p.pipeId) != null) runs += PipeRun(p.pipeId!!, len, insulate = p.kind != PipeKind.COLD)
            else items += CountItem(MaterialGroup.PIPE, "${p.kind.title} (ohne Dimension)", len, "m")
        }
        schema.symbols.forEach { s ->
            val def = SymbolLibrary.def(s.type)
            items += CountItem(def.materialGroup, def.title, 1.0, "Stk")
        }
        return MaterialInput(runs, emptyList(), items)
    }

    fun plan(input: MaterialInput, settings: MaterialSettings = MaterialSettings()): MaterialList {
        val lines = ArrayList<MaterialLine>()
        val waste = 1.0 + settings.pipeWastePercent / 100.0

        // Pipe in bars and clamps from the spacing of each run.
        input.runs.groupBy { it.pipeId }.toSortedMap(compareBy { id -> PipeCatalog.all.indexOfFirst { it.id == id } }).forEach { (id, runs) ->
            val size = PipeCatalog.byId(id) ?: return@forEach
            val measured = runs.sumOf { it.lengthM }
            val need = measured * waste
            val bars = up(need / size.material.barLengthM)
            lines += MaterialLine(
                MaterialGroup.PIPE, size.fullLabel, bars, "Stangen à ${n(size.material.barLengthM, 1)} m",
                "${n(measured)} m gemessen, ${n(need)} m mit ${n(settings.pipeWastePercent, 1)} % Verschnitt",
            )
            val clamps = runs.sumOf { ShkDesign.clampCount(it.lengthM, size.clampSpacingM) }
            if (clamps > 0) lines += MaterialLine(
                MaterialGroup.CLAMP, "Rohrschelle für ${size.fullLabel}", up(clamps * (1.0 + settings.clampExtraPercent / 100.0)), "Stk",
                "Abstand höchstens ${n(size.clampSpacingM)} m, $clamps Stk + ${n(settings.clampExtraPercent, 1)} %",
            )
        }

        // Insulation: flagged runs plus separately measured insulation.
        val toInsulate = input.runs.filter { it.insulate } + input.insulation
        toInsulate.groupBy { it.pipeId }.toSortedMap(compareBy { id -> PipeCatalog.all.indexOfFirst { it.id == id } }).forEach { (id, runs) ->
            val size = PipeCatalog.byId(id) ?: return@forEach
            if (!size.material.pressurePipe) return@forEach
            val required = ShkDesign.insulationMm(size.innerMm, settings.insulationCase)
            val stock = ShkDesign.insulationStockMm(required)
            val need = runs.sumOf { it.lengthM } * (1.0 + settings.insulationWastePercent / 100.0)
            lines += MaterialLine(
                MaterialGroup.INSULATION, "Dämmschlauch ${size.fullLabel}, $stock mm", up(need / settings.insulationPieceM),
                "Stück à ${n(settings.insulationPieceM, 1)} m",
                "GEG ${settings.insulationCase.title}: mind. ${n(required, 1)} mm, ${n(need)} m inkl. Verschnitt",
            )
        }

        // Counted parts; fittings get the small-parts allowance.
        input.items.groupBy { Triple(it.group, it.title, it.unit) }.forEach { (k, list) ->
            val sum = list.sumOf { it.quantity }
            val (group, title, unit) = k
            val counted = unit == "Stk"
            val q = when {
                group == MaterialGroup.FITTING -> up(sum * (1.0 + settings.fittingExtraPercent / 100.0))
                counted -> up(sum)
                else -> sum
            }
            val note = if (group == MaterialGroup.FITTING && q > sum) "${n(sum, 0)} Stk + ${n(settings.fittingExtraPercent, 1)} %" else ""
            lines += MaterialLine(group, title, q, unit, note)
        }
        return MaterialList(lines.sortedWith(compareBy<MaterialLine> { it.group.ordinal }))
    }
}
