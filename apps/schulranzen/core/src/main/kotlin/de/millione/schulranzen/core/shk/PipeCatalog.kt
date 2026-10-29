package de.millione.schulranzen.core.shk

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow

/** Pipe materials of the trade. [roughnessMm] feeds the pressure-drop calculation. */
enum class PipeMaterial(
    val title: String,
    val short: String,
    /** Absolute roughness k in mm (typical values for new pipes). */
    val roughnessMm: Double,
    /** Usual length of one bar in m, used for the material list. */
    val barLengthM: Double,
    /** False for drain pipes: no pressure-drop sizing. */
    val pressurePipe: Boolean = true,
) {
    STEEL("Stahl (Gewinderohr)", "St", 0.045, 6.0),
    COPPER("Kupfer", "Cu", 0.0015, 5.0),
    CSTEEL("C-Stahl (Pressrohr)", "C-St", 0.01, 6.0),
    MLC("Mehrschichtverbund", "MSV", 0.007, 5.0),
    HT("HT-Abwasser", "HT", 0.25, 2.0, pressurePipe = false),
}

/**
 * One pipe dimension.
 * @property dn nominal size to compare across materials.
 * @property clampSpacingM maximum support spacing for horizontal runs (Richtwert).
 */
data class PipeSize(
    val id: String,
    val material: PipeMaterial,
    val outerMm: Double,
    val wallMm: Double,
    val dn: Int,
    val clampSpacingM: Double,
) {
    val innerMm: Double get() = outerMm - 2 * wallMm
    val label: String get() = when (material) {
        PipeMaterial.STEEL -> "DN $dn (${fmt(outerMm)} × ${fmt(wallMm)})"
        PipeMaterial.HT -> "HT DN $dn"
        else -> "${fmt(outerMm)} × ${fmt(wallMm)}"
    }
    /** "Cu 22 × 1" – material and size in one short label. */
    val fullLabel: String get() = if (material == PipeMaterial.HT) label else "${material.short} $label"
    /** Water content in l/m. */
    val litresPerMeter: Double get() = Shk.pipeVolumeLitres(innerMm, 1.0)

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString().replace('.', ',')
}

/** Pipe dimension and pressure drop for one flow. */
data class PipeFlow(val size: PipeSize, val velocityMs: Double, val rPaPerM: Double)

/**
 * The pipe dimensions used in training and on site, with a Darcy–Weisbach
 * pressure drop. Values are simplified (water at about 60 °C, new pipes) and
 * meant for training, not for a certified hydraulic calculation.
 */
object PipeCatalog {
    private fun st(dn: Int, da: Double, s: Double, clamp: Double) = PipeSize("st$dn", PipeMaterial.STEEL, da, s, dn, clamp)
    private fun cu(da: Int, s: Double, dn: Int, clamp: Double) = PipeSize("cu$da", PipeMaterial.COPPER, da.toDouble(), s, dn, clamp)
    private fun cs(da: Int, s: Double, dn: Int, clamp: Double) = PipeSize("cs$da", PipeMaterial.CSTEEL, da.toDouble(), s, dn, clamp)
    private fun ml(da: Int, s: Double, dn: Int, clamp: Double) = PipeSize("ml$da", PipeMaterial.MLC, da.toDouble(), s, dn, clamp)
    private fun ht(dn: Int, da: Double, s: Double, clamp: Double) = PipeSize("ht$dn", PipeMaterial.HT, da, s, dn, clamp)

    val all: List<PipeSize> = listOf(
        st(10, 17.2, 2.35, 2.25), st(15, 21.3, 2.65, 2.75), st(20, 26.9, 2.65, 3.0), st(25, 33.7, 3.25, 3.5),
        st(32, 42.4, 3.25, 3.75), st(40, 48.3, 3.25, 4.25), st(50, 60.3, 3.65, 4.75),
        cu(12, 1.0, 10, 1.25), cu(15, 1.0, 12, 1.25), cu(18, 1.0, 15, 1.5), cu(22, 1.0, 20, 2.0),
        cu(28, 1.5, 25, 2.25), cu(35, 1.5, 32, 2.75), cu(42, 1.5, 40, 3.0), cu(54, 2.0, 50, 3.5),
        cs(15, 1.2, 12, 1.25), cs(18, 1.2, 15, 1.5), cs(22, 1.5, 20, 2.0), cs(28, 1.5, 25, 2.25),
        cs(35, 1.5, 32, 2.75), cs(42, 1.5, 40, 3.0), cs(54, 1.5, 50, 3.5),
        ml(16, 2.0, 12, 1.0), ml(20, 2.0, 15, 1.25), ml(26, 3.0, 20, 1.5), ml(32, 3.0, 25, 2.0),
        ml(40, 3.5, 32, 2.0), ml(50, 4.0, 40, 2.25), ml(63, 4.5, 50, 2.5),
        ht(40, 40.0, 1.8, 0.8), ht(50, 50.0, 1.8, 0.8), ht(70, 75.0, 1.9, 1.1), ht(100, 110.0, 2.7, 1.1),
    )

    fun byId(id: String?): PipeSize? = if (id == null) null else all.firstOrNull { it.id == id }
    fun of(material: PipeMaterial): List<PipeSize> = all.filter { it.material == material }

    /** Kinematic viscosity of water at about 60 °C in m²/s. */
    const val VISCOSITY = 0.475e-6

    /**
     * Pressure drop per metre in Pa/m for [flowM3h] through [size]
     * (Darcy–Weisbach, friction factor after Swamee–Jain, laminar 64/Re).
     */
    fun rValue(size: PipeSize, flowM3h: Double): Double {
        val d = size.innerMm / 1000.0
        val v = flowM3h / 3600.0 / (PI * d * d / 4.0)
        if (v <= 0.0) return 0.0
        val re = v * d / VISCOSITY
        val lambda = if (re < 2300) 64.0 / re
        else 0.25 / log10(size.material.roughnessMm / 1000.0 / (3.7 * d) + 5.74 / re.pow(0.9)).pow(2)
        return lambda / d * Shk.WATER_DENSITY * v * v / 2.0
    }

    fun flow(size: PipeSize, flowM3h: Double): PipeFlow =
        PipeFlow(size, Shk.velocity(flowM3h * 1000.0 / 60.0, size.innerMm), rValue(size, flowM3h))

    /** All sizes of [material] with velocity and R for [flowM3h]. */
    fun table(material: PipeMaterial, flowM3h: Double): List<PipeFlow> = of(material).map { flow(it, flowM3h) }

    /**
     * Smallest size of [material] that stays at or below [maxR] Pa/m and
     * [maxVelocity] m/s; null when even the largest is too small.
     */
    fun choose(material: PipeMaterial, flowM3h: Double, maxR: Double = 100.0, maxVelocity: Double = 1.0): PipeFlow? =
        table(material, flowM3h).firstOrNull { it.rPaPerM <= maxR && it.velocityMs <= maxVelocity }
}
