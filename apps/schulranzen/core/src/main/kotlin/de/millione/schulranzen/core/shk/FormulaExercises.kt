package de.millione.schulranzen.core.shk

import de.millione.schulranzen.core.math.GermanNumber
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A practice task for one formula with random values.
 * @property expression the substituted calculation in the calculator's syntax; the
 *   tests evaluate it with the math engine as a second, independent path to [answer].
 * @property steps the worked solution, shown on request.
 */
data class Exercise(
    val formulaId: String,
    val question: String,
    val steps: List<String>,
    val expression: String,
    val answer: Double,
    val unit: String,
    val decimals: Int,
) {
    /** Answer as printed in the solution. */
    val answerText: String get() = "${GermanNumber.format(answer, decimals)} $unit".trim()

    /**
     * Accepts rounding by hand: within half a unit of the last shown place or 1 %,
     * whichever is larger. Tables and calculators in class round differently.
     */
    fun accepts(value: Double): Boolean =
        abs(value - answer) <= max(0.5 * 10.0.pow(-decimals), abs(answer) * 0.01)
}

/**
 * One practice task per formula of the collection, for exam preparation.
 * Values come from short lists of realistic round numbers, so a task can be
 * done on paper and never leaves the range the formula is meant for.
 */
object FormulaExercises {
    private fun n(v: Double) = GermanNumber.formatPlain(v)
    private fun Random.pick(vararg values: Double) = values[nextInt(values.size)]

    private class Spec(
        val question: String,
        val formula: String,
        val expression: String,
        val answer: Double,
        val unit: String,
        val decimals: Int,
        val given: List<String> = emptyList(),
    )

    private val GENERATORS: Map<String, (Random) -> Spec> = mapOf(
        "heat_quantity" to { r ->
            val m = r.pick(50.0, 80.0, 120.0, 200.0, 300.0); val dt = r.pick(15.0, 25.0, 35.0, 40.0, 50.0)
            Spec(
                "${n(m)} kg Wasser werden um ${n(dt)} K erwärmt (c = 4,19 kJ/(kg·K)). Wie viel Wärme ist nötig?",
                "Q = m · c · ΔT", "${n(m)}*4,19*${n(dt)}", Shk.heatKj(m, Shk.WATER_C_KJ, dt), "kJ", 0,
            )
        },
        "water_energy" to { r ->
            val v = r.pick(80.0, 120.0, 150.0, 200.0, 300.0); val dt = r.pick(30.0, 40.0, 45.0, 50.0)
            Spec(
                "Ein Speicher mit ${n(v)} l soll um ${n(dt)} K erwärmt werden. Wie viel Energie braucht das ohne Verluste?",
                "E = V · ΔT · 1,163 / 1000", "${n(v)}*${n(dt)}*1,163/1000", Shk.waterHeatKwh(v, dt), "kWh", 2,
            )
        },
        "heating_time" to { r ->
            val q = r.pick(6.0, 9.0, 12.0, 15.0, 18.0); val p = r.pick(3.0, 6.0, 8.0, 12.0)
            Spec(
                "Ein Speicher braucht ${n(q)} kWh, die Heizleistung beträgt ${n(p)} kW. Wie lange dauert das Aufheizen ohne Verluste?",
                "t = Q / Φ", "${n(q)}/${n(p)}", q / p, "h", 2,
            )
        },
        "heat_flow" to { r ->
            val q = r.pick(3600.0, 7200.0, 18000.0); val t = r.pick(600.0, 900.0, 1800.0)
            Spec(
                "In ${n(t)} s werden ${n(q)} kJ Wärme übertragen. Wie groß ist die Wärmeleistung?",
                "Φ = Q / t (kJ/s = kW)", "${n(q)}/${n(t)}", q / t, "kW", 2,
            )
        },
        "mixing" to { r ->
            val m1 = r.pick(10.0, 20.0, 40.0); val t1 = r.pick(55.0, 60.0, 70.0); val m2 = r.pick(10.0, 30.0, 60.0); val t2 = r.pick(8.0, 10.0, 12.0)
            Spec(
                "${n(m1)} l Wasser mit ${n(t1)} °C werden mit ${n(m2)} l Wasser mit ${n(t2)} °C gemischt. Welche Mischtemperatur entsteht?",
                "ϑm = (m₁·ϑ₁ + m₂·ϑ₂) / (m₁ + m₂)", "(${n(m1)}*${n(t1)}+${n(m2)}*${n(t2)})/(${n(m1)}+${n(m2)})",
                Shk.mixingTemperature(m1, t1, m2, t2), "°C", 1,
            )
        },
        "transmission" to { r ->
            val u = r.pick(0.24, 0.28, 1.1, 1.3, 1.4); val a = r.pick(6.0, 8.5, 12.0, 20.0); val dt = r.pick(30.0, 32.0, 34.0)
            Spec(
                "Ein Bauteil hat U = ${n(u)} W/(m²·K) und ${n(a)} m². Die Temperaturdifferenz beträgt ${n(dt)} K. Wie groß ist der Wärmeverlust?",
                "Φ = U · A · ΔT", "${n(u)}*${n(a)}*${n(dt)}", Shk.transmissionW(u, a, dt), "W", 1,
            )
        },
        "expansion" to { r ->
            val l = r.pick(5.0, 8.0, 12.0, 20.0); val dt = r.pick(40.0, 50.0, 60.0)
            Spec(
                "Ein Kupferrohr (α = 0,0166 mm/(m·K)) ist ${n(l)} m lang und wird um ${n(dt)} K wärmer. Um wie viel dehnt es sich?",
                "Δl = l₀ · α · ΔT", "${n(l)}*0,0166*${n(dt)}", Shk.expansionMm(l, 0.0166, dt), "mm", 2,
            )
        },
        "kelvin" to { r ->
            val t = r.pick(-12.0, 0.0, 20.0, 55.0, 70.0)
            Spec("Rechne ${n(t)} °C in Kelvin um.", "T = ϑ + 273,15", "(${n(t)})+273,15", t + 273.15, "K", 2)
        },
        "flow_from_power" to { r ->
            val p = r.pick(5.0, 8.0, 10.0, 15.0, 24.0); val dt = r.pick(5.0, 7.0, 10.0, 15.0, 20.0)
            Spec(
                "Ein Heizkreis überträgt ${n(p)} kW bei ${n(dt)} K Spreizung. Welcher Volumenstrom ist nötig?",
                "V̇ = Φ / (1,163 · ΔT)", "${n(p)}/(1,163*${n(dt)})", Shk.flowFromPower(p, dt), "m³/h", 3,
            )
        },
        "power_from_flow" to { r ->
            val q = r.pick(0.3, 0.5, 0.8, 1.2); val dt = r.pick(5.0, 10.0, 15.0, 20.0)
            Spec(
                "Durch einen Kreis fließen ${n(q)} m³/h bei ${n(dt)} K Spreizung. Welche Leistung wird übertragen?",
                "Φ = 1,163 · V̇ · ΔT", "1,163*${n(q)}*${n(dt)}", Shk.powerFromFlow(q, dt), "kW", 2,
            )
        },
        "mass_flow" to { r ->
            val rho = r.pick(1000.0, 983.0, 972.0); val q = r.pick(0.4, 0.8, 1.5)
            Spec(
                "Volumenstrom ${n(q)} m³/h, Dichte des Wassers ${n(rho)} kg/m³. Wie groß ist der Massenstrom?",
                "ṁ = ρ · V̇", "${n(rho)}*${n(q)}", rho * q, "kg/h", 1,
            )
        },
        "pipe_volume" to { r ->
            val d = r.pick(16.0, 20.0, 26.0, 33.0, 39.0); val l = r.pick(5.0, 10.0, 18.0, 25.0)
            Spec(
                "Eine Leitung hat dᵢ = ${n(d)} mm und ist ${n(l)} m lang. Wie viel Wasser fasst sie in Litern?",
                "V = π · dᵢ² / 4 · L", "pi*(${n(d)}/1000)^2/4*${n(l)}*1000", Shk.pipeVolumeLitres(d, l), "l", 2,
                listOf("dᵢ = ${n(d)} mm = ${n(d / 1000.0)} m, 1 m³ = 1000 l"),
            )
        },
        "velocity" to { r ->
            val q = r.pick(10.0, 15.0, 20.0, 30.0); val d = r.pick(16.0, 20.0, 26.0)
            Spec(
                "Durch ein Rohr mit dᵢ = ${n(d)} mm fließen ${n(q)} l/min. Wie schnell fließt das Wasser?",
                "v = V̇ / A, A = π · dᵢ² / 4", "(${n(q)}/60000)/(pi*(${n(d)}/1000)^2/4)", Shk.velocity(q, d), "m/s", 2,
                listOf("V̇ = ${n(q)} l/min = ${n(q)} / 60000 m³/s"),
            )
        },
        "diameter" to { r ->
            val q = r.pick(10.0, 15.0, 20.0, 30.0); val v = r.pick(0.5, 0.8, 1.0)
            Spec(
                "Für ${n(q)} l/min ist v = ${n(v)} m/s gewählt. Welcher Innendurchmesser ist mindestens nötig?",
                "dᵢ = √(4 · V̇ / (π · v))", "sqrt(4*(${n(q)}/60000)/(pi*${n(v)}))*1000", Shk.innerDiameter(q, v), "mm", 1,
                listOf("V̇ = ${n(q)} l/min = ${n(q)} / 60000 m³/s, Ergebnis mal 1000 ergibt mm"),
            )
        },
        "continuity" to { r ->
            val d1 = r.pick(20.0, 26.0, 33.0); val v1 = r.pick(0.5, 0.8); val d2 = r.pick(13.0, 16.0)
            Spec(
                "Ein Rohr verengt sich von dᵢ = ${n(d1)} mm auf ${n(d2)} mm. Vorher fließt das Wasser mit ${n(v1)} m/s. Wie schnell danach?",
                "A₁ · v₁ = A₂ · v₂ → v₂ = v₁ · (d₁ / d₂)²", "${n(v1)}*(${n(d1)}/${n(d2)})^2", v1 * (d1 / d2).pow(2), "m/s", 2,
            )
        },
        "hydrostatic" to { r ->
            val h = r.pick(6.0, 9.0, 12.0, 15.0, 20.0)
            Spec(
                "Wie groß ist der Druck einer ${n(h)} m hohen Wassersäule in bar (g = 9,81 m/s², ρ = 1000 kg/m³)?",
                "p = ρ · g · h", "1000*9,81*${n(h)}/100000", 1000.0 * 9.81 * h / 100_000.0, "bar", 3,
                listOf("100 000 Pa = 1 bar"),
            )
        },
        "bar_mws" to { r ->
            val p = r.pick(1.5, 2.0, 2.5, 3.0)
            Spec("Wie viele Meter Wassersäule entsprechen ${n(p)} bar?", "1 bar ≈ 10,2 mWS", "${n(p)}*10,2", p * 10.2, "mWS", 1)
        },
        "pump_power" to { r ->
            val q = r.pick(1.8, 3.6, 7.2); val h = r.pick(3.0, 4.0, 6.0, 8.0); val eta = r.pick(40.0, 50.0, 60.0)
            Spec(
                "Eine Pumpe fördert ${n(q)} m³/h auf ${n(h)} m Förderhöhe bei η = ${n(eta)} %. Wie groß ist die Wellenleistung (g = 9,80665 m/s²)?",
                "P = ρ · g · V̇ · H / η", "1000*9,80665*(${n(q)}/3600)*${n(h)}/(${n(eta)}/100)", Shk.pumpPowerW(q, h, eta), "W", 1,
                listOf("V̇ = ${n(q)} m³/h = ${n(q)} / 3600 m³/s"),
            )
        },
        "ohm" to { r ->
            val res = r.pick(23.0, 46.0, 115.0); val i = r.pick(2.0, 5.0, 10.0)
            Spec("Durch einen Widerstand von ${n(res)} Ω fließen ${n(i)} A. Wie groß ist die Spannung?", "U = R · I", "${n(res)}*${n(i)}", res * i, "V", 1)
        },
        "power_dc" to { r ->
            val u = r.pick(12.0, 24.0, 230.0); val i = r.pick(1.5, 4.0, 10.0)
            Spec("Ein Heizstab liegt an ${n(u)} V und nimmt ${n(i)} A auf. Wie groß ist die Leistung?", "P = U · I", "${n(u)}*${n(i)}", u * i, "W", 1)
        },
        "power_1p" to { r ->
            val i = r.pick(0.5, 2.0, 6.0, 16.0); val cos = r.pick(0.8, 0.85, 0.9, 1.0)
            Spec(
                "Ein Gerät an 230 V nimmt ${n(i)} A auf, cos φ = ${n(cos)}. Wie groß ist die Wirkleistung?",
                "P = U · I · cos φ", "230*${n(i)}*${n(cos)}", Shk.power1p(230.0, i, cos), "W", 1,
            )
        },
        "power_3p" to { r ->
            val i = r.pick(10.0, 16.0, 25.0, 32.0); val cos = r.pick(0.85, 0.9, 1.0)
            Spec(
                "Ein Drehstromgerät an 400 V nimmt ${n(i)} A je Leiter auf, cos φ = ${n(cos)}. Wie groß ist die Wirkleistung?",
                "P = √3 · U · I · cos φ", "sqrt(3)*400*${n(i)}*${n(cos)}", Shk.power3p(400.0, i, cos), "W", 0,
            )
        },
        "current_3p" to { r ->
            val p = r.pick(6.0, 11.0, 18.0, 21.0, 24.0)
            Spec(
                "Ein Durchlauferhitzer hat ${n(p)} kW an 400 V Drehstrom (cos φ = 1). Wie groß ist der Strom je Leiter?",
                "I = P / (√3 · U · cos φ)", "${n(p)}*1000/(sqrt(3)*400*1)", Shk.current3p(p * 1000.0, 400.0, 1.0), "A", 2,
            )
        },
        "work" to { r ->
            val p = r.pick(0.5, 2.0, 3.5); val t = r.pick(2.0, 4.0, 8.0)
            Spec("Ein Gerät mit ${n(p)} kW läuft ${n(t)} h. Wie viel Energie verbraucht es?", "W = P · t", "${n(p)}*${n(t)}", p * t, "kWh", 2)
        },
        "resistors" to { r ->
            val r1 = r.pick(10.0, 22.0, 47.0); val r2 = r.pick(33.0, 68.0, 100.0)
            Spec(
                "${n(r1)} Ω und ${n(r2)} Ω sind parallel geschaltet. Wie groß ist der Gesamtwiderstand?",
                "1/R = 1/R₁ + 1/R₂", "1/(1/${n(r1)}+1/${n(r2)})", 1.0 / (1.0 / r1 + 1.0 / r2), "Ω", 2,
            )
        },
        "circle_area" to { r ->
            val d = r.pick(20.0, 50.0, 100.0, 150.0)
            Spec("Wie groß ist die Kreisfläche bei d = ${n(d)} mm?", "A = π · d² / 4", "pi*${n(d)}^2/4", Shk.area(d) * 1e6, "mm²", 1)
        },
        "density" to { r ->
            val m = r.pick(5.0, 12.0, 50.0); val v = r.pick(0.01, 0.02, 0.05)
            Spec("Ein Körper hat ${n(m)} kg und ${n(v)} m³. Wie groß ist seine Dichte?", "ρ = m / V", "${n(m)}/${n(v)}", m / v, "kg/m³", 1)
        },
        "pressure" to { r ->
            val f = r.pick(500.0, 1000.0, 2500.0); val a = r.pick(0.01, 0.02, 0.05)
            Spec("Eine Kraft von ${n(f)} N wirkt auf ${n(a)} m². Wie groß ist der Druck?", "p = F / A", "${n(f)}/${n(a)}", f / a, "Pa", 0)
        },
        "efficiency" to { r ->
            val out = r.pick(18.0, 20.0, 23.0); val input = out + r.pick(1.0, 2.0, 3.0)
            Spec(
                "Ein Kessel gibt ${n(out)} kW ab und nimmt ${n(input)} kW auf. Wie groß ist der Wirkungsgrad?",
                "η = P_ab / P_zu", "${n(out)}/${n(input)}*100", out / input * 100.0, "%", 1,
            )
        },
        "percent" to { r ->
            val g = r.pick(240.0, 480.0, 1250.0); val p = r.pick(5.0, 10.0, 19.0)
            Spec("Wie viel sind ${n(p)} % von ${n(g)} €?", "W = G · p / 100", "${n(g)}*${n(p)}/100", g * p / 100.0, "€", 2)
        },
        "gradient" to { r ->
            val h = r.pick(0.05, 0.1, 0.2); val l = r.pick(5.0, 10.0, 20.0)
            Spec(
                "Eine ${n(l)} m lange Leitung fällt um ${n(h)} m. Wie groß ist das Gefälle?",
                "I = Δh / L · 100 %", "${n(h)}/${n(l)}*100", h / l * 100.0, "%", 2,
            )
        },
        "pythagoras" to { r ->
            val a = r.pick(300.0, 400.0, 500.0, 600.0); val b = r.pick(400.0, 500.0, 800.0)
            Spec(
                "Ein Etagenstück hat ${n(a)} mm Versatz und ${n(b)} mm Baulänge. Wie lang ist die schräge Seite?",
                "c = √(a² + b²)", "sqrt(${n(a)}^2+${n(b)}^2)", sqrt(a * a + b * b), "mm", 1,
            )
        },
        "mag" to { r ->
            val va = r.pick(150.0, 200.0, 300.0, 500.0); val h = r.pick(6.0, 8.0, 10.0); val t = r.pick(60.0, 70.0)
            val m = ShkDesign.mag(va, h, t, 3.0)
            Spec(
                "Heizung mit ${n(va)} l Inhalt, ${n(h)} m statische Höhe, ${n(t)} °C Vorlauf, Sicherheitsventil 3 bar. Wie groß muss das MAG mindestens sein?",
                "V_N = (V_e + V_V) · (p_e + 1) / (p_e − p₀)",
                "(${n(m.expansionL)}+${n(m.reserveL)})*(${n(m.finalBar)}+1)/(${n(m.finalBar)}-${n(m.preChargeBar)})",
                m.nominalMinL, "l", 1,
                listOf(
                    "n = ${GermanNumber.format(m.expansionPercent, 2)} %, V_e = ${GermanNumber.format(m.expansionL, 2)} l",
                    "V_V = ${GermanNumber.format(m.reserveL, 2)} l, p₀ = ${GermanNumber.format(m.preChargeBar, 2)} bar, p_e = ${GermanNumber.format(m.finalBar, 2)} bar",
                ),
            )
        },
        "radiator" to { r ->
            val pn = r.pick(800.0, 1200.0, 1600.0, 2000.0)
            val (tv, tr) = listOf(55.0 to 45.0, 65.0 to 55.0, 45.0 to 35.0)[r.nextInt(3)]
            val dl = ShkDesign.logDelta(tv, tr, 20.0)
            Spec(
                "Ein Heizkörper leistet ${n(pn)} W bei 75/65/20. Was leistet er bei ${n(tv)}/${n(tr)}/20 (n = 1,3)?",
                "Φ = Φ_N · (ΔT_ln / ΔT_ln,N)ⁿ", "${n(pn)}*(${n(dl)}/${n(ShkDesign.NORM_DELTA)})^1,3",
                ShkDesign.radiatorOutput(pn, tv, tr, 20.0), "W", 0,
                listOf("ΔT_ln = (${n(tv)} − ${n(tr)}) / ln((${n(tv)} − 20) / (${n(tr)} − 20)) = ${GermanNumber.format(dl, 2)} K, ΔT_ln,N = 49,83 K"),
            )
        },
        "tw_peak" to { r ->
            val sum = r.pick(0.5, 0.79, 1.2, 2.5, 4.0)
            Spec(
                "Wohngebäude, Summendurchfluss ${n(sum)} l/s (a = 1,48, b = 0,19, c = 0,94). Wie groß ist der Spitzendurchfluss?",
                "V̇_S = a · (ΣV̇_R)^b − c", "1,48*${n(sum)}^0,19-0,94", ShkDesign.peakFlow(sum, 0.15), "l/s", 3,
            )
        },
        "pump_head" to { r ->
            val res = r.pick(80.0, 100.0, 150.0); val l = r.pick(40.0, 60.0, 80.0); val dp = r.pick(5.0, 10.0, 15.0)
            Spec(
                "Ungünstigster Kreis ${n(l)} m, R = ${n(res)} Pa/m, Zuschlag 30 %, weitere Bauteile ${n(dp)} kPa. Welche Förderhöhe ist nötig (g = 9,80665 m/s²)?",
                "H = (R · L · (1 + Z) + Δp_z) / (ρ · g)", "(${n(res)}*${n(l)}*(1+30/100)+${n(dp)}*1000)/(1000*9,80665)",
                ShkDesign.pumpHeadM(l, res, 30.0, dp), "m", 2,
            )
        },
    )

    /** Formulas with a generator; the test keeps this equal to the whole collection. */
    val formulaIds: Set<String> get() = GENERATORS.keys

    /** A fresh task for [formulaId], or null when the formula has no generator. */
    fun generate(formulaId: String, random: Random = Random.Default): Exercise? {
        val spec = GENERATORS[formulaId]?.invoke(random) ?: return null
        val shown = spec.expression.replace("*", " · ").replace("/", " / ").replace("+", " + ").replace("sqrt", "√").replace("pi", "π")
        val result = "${GermanNumber.format(spec.answer, spec.decimals)} ${spec.unit}".trim()
        val steps = buildList {
            add("Formel: ${spec.formula}")
            spec.given.forEach { add(it) }
            add("Einsetzen: $shown")
            add("Ergebnis: $result")
        }
        return Exercise(formulaId, spec.question, steps, spec.expression, spec.answer, spec.unit, spec.decimals)
    }
}
