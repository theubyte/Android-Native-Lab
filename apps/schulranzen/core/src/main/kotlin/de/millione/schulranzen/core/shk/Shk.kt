package de.millione.schulranzen.core.shk

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * SHK formulas as plain functions in the units used on the job (kW, K, mm,
 * l/min …). The constants follow the trade's tables: water with
 * c = 4,19 kJ/(kg·K), which is the familiar 1,163 Wh/(kg·K), and ρ = 1000 kg/m³.
 */
object Shk {
    /** Standard gravity in m/s². */
    const val G = 9.80665
    const val WATER_DENSITY = 1000.0
    /** Specific heat of water in kJ/(kg·K). */
    const val WATER_C_KJ = 4.19
    /** Specific heat of water in Wh/(kg·K); 4,1868 / 3,6. */
    const val WATER_C_WH = 1.163
    const val BAR_IN_MWS = 100_000.0 / (G * WATER_DENSITY)

    /** Volume flow in m³/h that carries [powerKw] at a spread of [deltaK]: V̇ = Φ / (1,163 · ΔT). */
    fun flowFromPower(powerKw: Double, deltaK: Double): Double = powerKw / (WATER_C_WH * deltaK)

    /** Heat output in kW of [flowM3h] at a spread of [deltaK]: Φ = 1,163 · V̇ · ΔT. */
    fun powerFromFlow(flowM3h: Double, deltaK: Double): Double = WATER_C_WH * flowM3h * deltaK

    /** Cross section in m² of a pipe with inner diameter [innerMm]. */
    fun area(innerMm: Double): Double { val d = innerMm / 1000.0; return PI * d * d / 4.0 }

    /** Water content in litres of [lengthM] of pipe with inner diameter [innerMm]. */
    fun pipeVolumeLitres(innerMm: Double, lengthM: Double): Double = area(innerMm) * lengthM * 1000.0

    /** Flow velocity in m/s: v = V̇ / A. */
    fun velocity(flowLmin: Double, innerMm: Double): Double = flowLmin / 60_000.0 / area(innerMm)

    /** Inner diameter in mm needed for [flowLmin] at [velocityMs]: d = √(4 · V̇ / (π · v)). */
    fun innerDiameter(flowLmin: Double, velocityMs: Double): Double =
        sqrt(4.0 * (flowLmin / 60_000.0) / (PI * velocityMs)) * 1000.0

    /** Energy in kWh to heat [litres] of water by [deltaK]. */
    fun waterHeatKwh(litres: Double, deltaK: Double): Double = litres * deltaK * WATER_C_WH / 1000.0

    /** Heat in kJ: Q = m · c · ΔT. */
    fun heatKj(massKg: Double, cKj: Double, deltaK: Double): Double = massKg * cKj * deltaK

    /** Mixing temperature of two water amounts. */
    fun mixingTemperature(m1: Double, t1: Double, m2: Double, t2: Double): Double = (m1 * t1 + m2 * t2) / (m1 + m2)

    /** Heat loss in W through a component: Φ = U · A · ΔT. */
    fun transmissionW(u: Double, areaM2: Double, deltaK: Double): Double = u * areaM2 * deltaK

    /** Thermal length change in mm with α given in mm/(m·K). */
    fun expansionMm(lengthM: Double, alphaMmPerMK: Double, deltaK: Double): Double = lengthM * alphaMmPerMK * deltaK

    fun barToMws(bar: Double): Double = bar * BAR_IN_MWS
    fun mwsToBar(mws: Double): Double = mws / BAR_IN_MWS

    /** Shaft power in W of a pump: P = ρ · g · V̇ · H / η. */
    fun pumpPowerW(flowM3h: Double, headM: Double, efficiencyPercent: Double): Double =
        WATER_DENSITY * G * (flowM3h / 3600.0) * headM / (efficiencyPercent / 100.0)

    /** Single-phase active power in W. */
    fun power1p(u: Double, i: Double, cosPhi: Double): Double = u * i * cosPhi

    /** Three-phase active power in W. */
    fun power3p(u: Double, i: Double, cosPhi: Double): Double = sqrt(3.0) * u * i * cosPhi

    /** Three-phase current in A for an active power in W. */
    fun current3p(powerW: Double, u: Double, cosPhi: Double): Double = powerW / (sqrt(3.0) * u * cosPhi)
}
