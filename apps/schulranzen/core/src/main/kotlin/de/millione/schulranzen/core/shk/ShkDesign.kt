package de.millione.schulranzen.core.shk

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Where a pipe runs; decides the GEG insulation share. */
enum class InsulationCase(val title: String, val share: Double) {
    NORMAL("Normal (100 %)", 1.0),
    HALF("Durchbrüche, Verteiler, zwischen Nutzern (50 %)", 0.5),
    FLOOR("Im Fußbodenaufbau (6 mm)", 0.0),
}

/** Draw-off point for the drinking-water peak flow with its design flow V̇R in l/s. */
enum class TapPoint(val title: String, val flowLs: Double) {
    BASIN("Waschtisch", 0.07),
    SINK("Spüle", 0.07),
    SHOWER("Dusche", 0.15),
    BATH("Badewanne", 0.15),
    WC("WC-Spülkasten", 0.13),
    WASHER("Waschmaschine", 0.15),
    DISHWASHER("Geschirrspüler", 0.07),
    URINAL("Urinal (Spülventil)", 0.30),
    TAP15("Auslaufventil DN 15", 0.30),
}

/** Building type with the peak-flow constants a, b, c of DIN 1988-300. */
enum class BuildingType(val title: String, val a: Double, val b: Double, val c: Double) {
    RESIDENTIAL("Wohngebäude", 1.48, 0.19, 0.94),
    CARE("Pflegeheim", 1.40, 0.14, 0.92),
    HOSPITAL("Bettenhaus Krankenhaus", 0.75, 0.44, 0.18),
    HOTEL("Hotel", 0.70, 0.48, 0.13),
    SCHOOL("Schule", 0.91, 0.31, 0.38),
    OFFICE("Verwaltungsgebäude", 0.91, 0.31, 0.38),
}

/** Result of the expansion vessel sizing; volumes in l, pressures in bar. */
data class MagResult(
    val expansionPercent: Double,
    val expansionL: Double,
    val reserveL: Double,
    val preChargeBar: Double,
    val finalBar: Double,
    val fillBar: Double,
    val nominalMinL: Double,
    /** Next catalogue size, or null when above the largest. */
    val standardL: Int?,
)

/**
 * Design aids for training: expansion vessel, safety valve, insulation (GEG),
 * drinking-water peak flow (DIN 1988-300), radiator output and rough heat load.
 * Every rule is simplified; the UI says so ("vereinfacht, für Ausbildung").
 */
object ShkDesign {
    const val SOURCE = "Vereinfacht, für die Ausbildung. Für Planung und Abnahme gelten Norm und Herstellerangaben."

    // ---------------------------------------------------------------- Wasser

    /** Density of water in kg/m³ at 4 … 100 °C (rounded table values). */
    private val DENSITY = listOf(
        4.0 to 999.97, 10.0 to 999.70, 20.0 to 998.20, 30.0 to 995.65, 40.0 to 992.22, 50.0 to 988.04,
        60.0 to 983.20, 70.0 to 977.76, 80.0 to 971.79, 90.0 to 965.31, 100.0 to 958.35,
    )

    fun waterDensity(t: Double): Double {
        val tt = t.coerceIn(DENSITY.first().first, DENSITY.last().first)
        val i = DENSITY.indexOfLast { it.first <= tt }.coerceAtMost(DENSITY.size - 2)
        val (t0, d0) = DENSITY[i]; val (t1, d1) = DENSITY[i + 1]
        return d0 + (d1 - d0) * (tt - t0) / (t1 - t0)
    }

    /** Expansion of water from 10 °C to [t] in percent. */
    fun expansionPercent(t: Double, from: Double = 10.0): Double = (waterDensity(from) / waterDensity(t) - 1.0) * 100.0

    // ---------------------------------------------------------------- MAG

    val MAG_SIZES = listOf(8, 12, 18, 25, 35, 50, 80, 100, 140, 200, 250, 300, 400, 500, 600, 800, 1000)

    /**
     * Membrane expansion vessel for a closed heating system (simplified EN 12828).
     * @param systemL water content of the system in l
     * @param staticM static height above the vessel in m
     * @param maxFlowC highest flow temperature in °C
     * @param safetyBar opening pressure of the safety valve in bar
     */
    fun mag(systemL: Double, staticM: Double, maxFlowC: Double, safetyBar: Double): MagResult {
        require(maxFlowC in 20.0..110.0) { "Vorlauftemperatur zwischen 20 und 110 °C" }
        val n = expansionPercent(maxFlowC)
        val ve = systemL * n / 100.0
        val vv = max(systemL * 0.005, 3.0)
        val p0 = max(staticM / 10.0 + 0.2, 0.7)
        val pe = if (safetyBar <= 5.0) safetyBar - 0.5 else safetyBar * 0.9
        require(pe > p0) { "Sicherheitsventil zu niedrig für diese Anlagenhöhe" }
        val vn = (ve + vv) * (pe + 1.0) / (pe - p0)
        return MagResult(n, ve, vv, p0, pe, p0 + 0.3, vn, MAG_SIZES.firstOrNull { it >= vn })
    }

    // ---------------------------------------------------------------- Sicherheitsventil

    /** Inlet size of the safety valve for a heat generator (DIN EN 12828, simplified table). */
    val SAFETY_VALVES = listOf(50.0 to 15, 100.0 to 20, 200.0 to 25, 350.0 to 32, 600.0 to 40, 900.0 to 50)

    /** Minimum inlet DN for [powerKw]; null above the table. */
    fun safetyValveDn(powerKw: Double): Int? = SAFETY_VALVES.firstOrNull { powerKw <= it.first }?.second

    // ---------------------------------------------------------------- Dämmung (GEG)

    /** GEG minimum insulation thickness in mm at λ = 0,035 W/(m·K) for [innerMm]. */
    fun insulationMm(innerMm: Double, case: InsulationCase = InsulationCase.NORMAL): Double {
        if (case == InsulationCase.FLOOR) return 6.0
        val full = when {
            innerMm <= 22.0 -> 20.0
            innerMm <= 35.0 -> 30.0
            innerMm <= 100.0 -> innerMm
            else -> 100.0
        }
        return full * case.share
    }

    /** Usual insulation thicknesses on the market; the next one up is ordered. */
    val INSULATION_STOCK = listOf(6, 9, 13, 20, 25, 30, 40, 50, 60, 80, 100)

    fun insulationStockMm(required: Double): Int = INSULATION_STOCK.firstOrNull { it >= required - 1e-9 } ?: INSULATION_STOCK.last()

    // ---------------------------------------------------------------- Trinkwasser

    /**
     * Peak flow in l/s after DIN 1988-300: V̇S = a · (ΣV̇R)^b − c, never more
     * than the sum and never less than the largest single draw-off.
     */
    fun peakFlow(sumLs: Double, largestLs: Double, type: BuildingType = BuildingType.RESIDENTIAL): Double {
        if (sumLs <= 0.0) return 0.0
        val formula = type.a * sumLs.pow(type.b) - type.c
        return max(largestLs, min(sumLs, formula))
    }

    fun peakFlow(counts: Map<TapPoint, Int>, type: BuildingType = BuildingType.RESIDENTIAL): Double {
        val used = counts.filterValues { it > 0 }
        val sum = used.entries.sumOf { it.key.flowLs * it.value }
        val largest = used.keys.maxOfOrNull { it.flowLs } ?: 0.0
        return peakFlow(sum, largest, type)
    }

    // ---------------------------------------------------------------- Heizkörper und Heizlast

    /** Logarithmic mean temperature difference in K. */
    fun logDelta(flowC: Double, returnC: Double, roomC: Double): Double {
        val a = flowC - roomC; val b = returnC - roomC
        require(a > 0 && b > 0) { "Vor- und Rücklauf müssen über der Raumtemperatur liegen" }
        require(flowC >= returnC) { "Der Vorlauf muss wärmer sein als der Rücklauf" }
        return if (a - b < 1e-9) a else (a - b) / ln(a / b)
    }

    /** ΔT_ln at the standard rating 75/65/20 (EN 442). */
    val NORM_DELTA: Double get() = logDelta(75.0, 65.0, 20.0)

    /** Radiator output at other temperatures: Φ = Φ_N · (ΔT_ln / ΔT_ln,N)^n. */
    fun radiatorOutput(normW: Double, flowC: Double, returnC: Double, roomC: Double, exponent: Double = 1.3): Double =
        normW * (logDelta(flowC, returnC, roomC) / NORM_DELTA).pow(exponent)

    /** Specific heat load by building standard in W/m² (rough values for living space). */
    val SPECIFIC_LOAD = listOf(
        "Altbau, unsaniert (vor 1977)" to 150.0,
        "Altbau, teilsaniert" to 110.0,
        "WSchV 1984/1995" to 80.0,
        "EnEV 2002–2009" to 60.0,
        "EnEV 2014 / GEG-Neubau" to 45.0,
        "KfW-Effizienzhaus 55/40" to 30.0,
        "Passivhaus" to 15.0,
    )

    // ---------------------------------------------------------------- Pumpe

    /**
     * Pump head in m for the index circuit: Δp = R · L · (1 + Z) + Δp_extra.
     * @param lengthM flow and return of the longest circuit
     * @param extraKpa extra components (valves, boiler, mixer)
     */
    fun pumpHeadM(lengthM: Double, rPaPerM: Double, fittingsPercent: Double, extraKpa: Double): Double {
        val pa = rPaPerM * lengthM * (1.0 + fittingsPercent / 100.0) + extraKpa * 1000.0
        return pa / (Shk.WATER_DENSITY * Shk.G)
    }

    // ---------------------------------------------------------------- Befestigung

    /** Number of clamps for a straight run: one at each end plus every [spacingM]. */
    fun clampCount(lengthM: Double, spacingM: Double): Int =
        if (lengthM <= 0.0) 0 else ceil(lengthM / spacingM - 1e-9).toInt() + 1
}
