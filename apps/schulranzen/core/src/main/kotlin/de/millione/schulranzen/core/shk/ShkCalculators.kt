package de.millione.schulranzen.core.shk

import kotlin.math.sqrt

/** One input field of a quick calculator. */
data class ShkInput(
    val key: String,
    val label: String,
    val symbol: String,
    val unit: String,
    /** Value pre-filled when the calculator opens (null = empty). */
    val default: Double? = null,
    val hint: String? = null,
    /** Temperatures may be zero or negative; most other inputs must be positive. */
    val allowNonPositive: Boolean = false,
)

/** One line of the result. [decimals] is the sensible rounding for the unit. */
data class ShkOutput(val label: String, val symbol: String, val value: Double, val unit: String, val decimals: Int = 3)

sealed interface ShkCalcResult {
    data class Ok(val outputs: List<ShkOutput>) : ShkCalcResult
    /** Not all inputs are filled yet; nothing to show, no error. */
    data object Incomplete : ShkCalcResult
    data class Invalid(val message: String) : ShkCalcResult
}

/**
 * A quick calculator: labelled inputs with units and a live result. Shown
 * generically by the UI, so new calculators are only data plus a formula.
 */
class ShkCalculator(
    val id: String,
    val title: String,
    val category: FormulaCategory,
    /** Formula as printed in the formula collection. */
    val formula: String,
    val inputs: List<ShkInput>,
    val note: String,
    private val compute: (Map<String, Double>) -> List<ShkOutput>,
) {
    fun calculate(values: Map<String, Double?>): ShkCalcResult {
        val filled = HashMap<String, Double>()
        for (input in inputs) {
            val v = values[input.key] ?: return ShkCalcResult.Incomplete
            if (!v.isFinite()) return ShkCalcResult.Invalid("${input.label}: ungültiger Wert")
            if (!input.allowNonPositive && v <= 0.0) return ShkCalcResult.Invalid("${input.label} muss größer als 0 sein")
            filled[input.key] = v
        }
        return try {
            val out = compute(filled)
            if (out.any { !it.value.isFinite() }) ShkCalcResult.Invalid("Mit diesen Werten nicht berechenbar")
            else ShkCalcResult.Ok(out)
        } catch (e: IllegalArgumentException) {
            ShkCalcResult.Invalid(e.message ?: "Mit diesen Werten nicht berechenbar")
        }
    }
}

object ShkCalculators {
    private val W = FormulaCategory.HEAT
    private val R = FormulaCategory.PIPES
    private val E = FormulaCategory.ELECTRIC
    private val B = FormulaCategory.BASICS

    private fun dT(default: Double? = null) = ShkInput("dt", "Temperaturspreizung", "ΔT", "K", default, "Vorlauf − Rücklauf")

    val all: List<ShkCalculator> = listOf(
        ShkCalculator(
            "flow_from_power", "Volumenstrom aus Heizleistung", R, "V̇ = Φ / (1,163 · ΔT)",
            listOf(ShkInput("p", "Heizleistung", "Φ", "kW"), dT(20.0)),
            "Wasser, c = 1,163 Wh/(kg·K). Typische Spreizung: Heizkörper 10–20 K, Fußbodenheizung 5–8 K.",
        ) { v ->
            val m3h = Shk.flowFromPower(v.getValue("p"), v.getValue("dt"))
            listOf(
                ShkOutput("Volumenstrom", "V̇", m3h, "m³/h"),
                ShkOutput("Volumenstrom", "V̇", m3h * 1000.0 / 60.0, "l/min", 2),
                ShkOutput("Volumenstrom", "V̇", m3h * 1000.0, "l/h", 1),
            )
        },
        ShkCalculator(
            "power_from_flow", "Heizleistung aus Volumenstrom", R, "Φ = 1,163 · V̇ · ΔT",
            listOf(ShkInput("q", "Volumenstrom", "V̇", "l/min"), dT(20.0)),
            "V̇ in m³/h eingesetzt ergibt Φ in kW.",
        ) { v ->
            val kw = Shk.powerFromFlow(v.getValue("q") * 60.0 / 1000.0, v.getValue("dt"))
            listOf(ShkOutput("Heizleistung", "Φ", kw, "kW"), ShkOutput("Heizleistung", "Φ", kw * 1000.0, "W", 0))
        },
        ShkCalculator(
            "pipe_volume", "Rohrinhalt", R, "V = π · dᵢ² / 4 · L",
            listOf(ShkInput("d", "Innendurchmesser", "dᵢ", "mm"), ShkInput("l", "Rohrlänge", "L", "m")),
            "Für Füllmenge, Spülen und Ausstoßzeit. Innendurchmesser = Außendurchmesser − 2 × Wandstärke.",
        ) { v ->
            val l = Shk.pipeVolumeLitres(v.getValue("d"), v.getValue("l"))
            listOf(ShkOutput("Inhalt", "V", l, "l"), ShkOutput("Inhalt je Meter", "V/L", l / v.getValue("l"), "l/m"))
        },
        ShkCalculator(
            "flow_velocity", "Fließgeschwindigkeit", R, "v = V̇ / A",
            listOf(ShkInput("q", "Volumenstrom", "V̇", "l/min"), ShkInput("d", "Innendurchmesser", "dᵢ", "mm")),
            "Richtwerte: Heizung 0,3–1,0 m/s, Trinkwasser bis etwa 2 m/s.",
        ) { v ->
            listOf(
                ShkOutput("Fließgeschwindigkeit", "v", Shk.velocity(v.getValue("q"), v.getValue("d")), "m/s"),
                ShkOutput("Querschnitt", "A", Shk.area(v.getValue("d")) * 1e6, "mm²", 1),
            )
        },
        ShkCalculator(
            "pipe_diameter", "Innendurchmesser aus Volumenstrom", R, "dᵢ = √(4 · V̇ / (π · v))",
            listOf(ShkInput("q", "Volumenstrom", "V̇", "l/min"), ShkInput("v", "Fließgeschwindigkeit", "v", "m/s", 0.8)),
            "Mindestens nötiger Innendurchmesser; die nächstgrößere Rohrdimension wählen.",
        ) { v -> listOf(ShkOutput("Innendurchmesser", "dᵢ", Shk.innerDiameter(v.getValue("q"), v.getValue("v")), "mm", 1)) },
        ShkCalculator(
            "water_energy", "Wasser erwärmen", W, "E = V · ΔT · 1,163 / 1000",
            listOf(ShkInput("v", "Wassermenge", "V", "l"), ShkInput("dt", "Temperaturänderung", "ΔT", "K", hint = "z. B. 10 °C → 60 °C = 50 K")),
            "1 l Wasser ≈ 1 kg. Ohne Verluste.",
        ) { v ->
            val kwh = Shk.waterHeatKwh(v.getValue("v"), v.getValue("dt"))
            listOf(ShkOutput("Energie", "E", kwh, "kWh"), ShkOutput("Energie", "E", kwh * 3600.0, "kJ", 0))
        },
        ShkCalculator(
            "heating_time", "Aufheizzeit", W, "t = V · ΔT · 1,163 / Φ",
            listOf(
                ShkInput("v", "Wassermenge", "V", "l"),
                ShkInput("dt", "Temperaturänderung", "ΔT", "K"),
                ShkInput("p", "Heizleistung", "Φ", "kW"),
            ),
            "Ohne Verluste; reale Zeiten sind länger.",
        ) { v ->
            val hours = Shk.waterHeatKwh(v.getValue("v"), v.getValue("dt")) / v.getValue("p")
            listOf(ShkOutput("Aufheizzeit", "t", hours * 60.0, "min", 1), ShkOutput("Aufheizzeit", "t", hours, "h", 2))
        },
        ShkCalculator(
            "heat_quantity", "Wärmemenge", W, "Q = m · c · ΔT",
            listOf(
                ShkInput("m", "Masse", "m", "kg"),
                ShkInput("c", "Spezifische Wärmekapazität", "c", "kJ/(kg·K)", Shk.WATER_C_KJ, "Wasser 4,19 · Stahl 0,48 · Kupfer 0,39"),
                ShkInput("dt", "Temperaturänderung", "ΔT", "K"),
            ),
            "1 kWh = 3600 kJ.",
        ) { v ->
            val kj = Shk.heatKj(v.getValue("m"), v.getValue("c"), v.getValue("dt"))
            listOf(ShkOutput("Wärmemenge", "Q", kj, "kJ", 1), ShkOutput("Wärmemenge", "Q", kj / 3600.0, "kWh"))
        },
        ShkCalculator(
            "mixing_temperature", "Mischtemperatur", W, "ϑm = (m₁·ϑ₁ + m₂·ϑ₂) / (m₁ + m₂)",
            listOf(
                ShkInput("m1", "Menge 1", "m₁", "l"),
                ShkInput("t1", "Temperatur 1", "ϑ₁", "°C", allowNonPositive = true),
                ShkInput("m2", "Menge 2", "m₂", "l"),
                ShkInput("t2", "Temperatur 2", "ϑ₂", "°C", allowNonPositive = true),
            ),
            "Gleiche Flüssigkeit, keine Verluste (Richmannsche Mischungsregel).",
        ) { v ->
            listOf(ShkOutput("Mischtemperatur", "ϑm", Shk.mixingTemperature(v.getValue("m1"), v.getValue("t1"), v.getValue("m2"), v.getValue("t2")), "°C", 1))
        },
        ShkCalculator(
            "transmission", "Wärmeverlust eines Bauteils", W, "Φ = U · A · ΔT",
            listOf(
                ShkInput("u", "Wärmedurchgangskoeffizient", "U", "W/(m²·K)"),
                ShkInput("a", "Fläche", "A", "m²"),
                ShkInput("dt", "Temperaturdifferenz innen/außen", "ΔT", "K", hint = "z. B. 20 °C − (−12 °C) = 32 K"),
            ),
            "Transmissionswärmeverlust ohne Wärmebrücken.",
        ) { v ->
            val w = Shk.transmissionW(v.getValue("u"), v.getValue("a"), v.getValue("dt"))
            listOf(ShkOutput("Wärmeverlust", "Φ", w, "W", 1), ShkOutput("Wärmeverlust", "Φ", w / 1000.0, "kW"))
        },
        ShkCalculator(
            "expansion", "Längenausdehnung", W, "Δl = l₀ · α · ΔT",
            listOf(
                ShkInput("l", "Rohrlänge", "l₀", "m"),
                ShkInput("a", "Ausdehnungskoeffizient", "α", "mm/(m·K)", 0.0166, "Kupfer 0,0166 · Stahl 0,012 · PE-X 0,2"),
                ShkInput("dt", "Temperaturänderung", "ΔT", "K"),
            ),
            "Wichtig für Dehnungsausgleich (Bögen, Kompensatoren).",
        ) { v -> listOf(ShkOutput("Längenänderung", "Δl", Shk.expansionMm(v.getValue("l"), v.getValue("a"), v.getValue("dt")), "mm", 2)) },
        ShkCalculator(
            "pressure_to_head", "Druck in Förderhöhe", R, "H = p / (ρ · g)",
            listOf(ShkInput("p", "Druck", "p", "bar")),
            "1 bar ≈ 10,2 mWS (Wasser, ρ = 1000 kg/m³).",
        ) { v -> listOf(ShkOutput("Förderhöhe", "H", Shk.barToMws(v.getValue("p")), "mWS", 2), ShkOutput("Druck", "p", v.getValue("p") * 1000.0, "mbar", 0)) },
        ShkCalculator(
            "head_to_pressure", "Förderhöhe in Druck", R, "p = ρ · g · H",
            listOf(ShkInput("h", "Förderhöhe / Höhe", "H", "m")),
            "Statischer Druck einer Wassersäule, z. B. für den Anlagendruck.",
        ) { v ->
            val bar = Shk.mwsToBar(v.getValue("h"))
            listOf(ShkOutput("Druck", "p", bar, "bar"), ShkOutput("Druck", "p", bar * 100.0, "kPa", 2))
        },
        ShkCalculator(
            "pump_power", "Pumpenleistung", R, "P = ρ · g · V̇ · H / η",
            listOf(
                ShkInput("q", "Volumenstrom", "V̇", "m³/h"),
                ShkInput("h", "Förderhöhe", "H", "m"),
                ShkInput("eta", "Wirkungsgrad", "η", "%", 50.0),
            ),
            "Wellenleistung der Pumpe für Wasser.",
        ) { v ->
            val w = Shk.pumpPowerW(v.getValue("q"), v.getValue("h"), v.getValue("eta"))
            listOf(ShkOutput("Leistung", "P", w, "W", 1), ShkOutput("Hydraulische Leistung", "P_hyd", w * v.getValue("eta") / 100.0, "W", 1))
        },
        ShkCalculator(
            "ohm", "Ohmsches Gesetz", E, "U = R · I",
            listOf(ShkInput("r", "Widerstand", "R", "Ω"), ShkInput("i", "Stromstärke", "I", "A")),
            "Umgestellt: I = U / R, R = U / I.",
        ) { v ->
            val u = v.getValue("r") * v.getValue("i")
            listOf(ShkOutput("Spannung", "U", u, "V", 2), ShkOutput("Leistung", "P", u * v.getValue("i"), "W", 2))
        },
        ShkCalculator(
            "power_1p", "Elektrische Leistung 1~", E, "P = U · I · cos φ",
            listOf(ShkInput("u", "Spannung", "U", "V", 230.0), ShkInput("i", "Stromstärke", "I", "A"), ShkInput("cos", "Leistungsfaktor", "cos φ", "", 1.0, "ohmsche Last: 1")),
            "Wechselstrom-Wirkleistung.",
        ) { v ->
            val w = Shk.power1p(v.getValue("u"), v.getValue("i"), v.getValue("cos"))
            listOf(ShkOutput("Wirkleistung", "P", w, "W", 1), ShkOutput("Wirkleistung", "P", w / 1000.0, "kW"))
        },
        ShkCalculator(
            "power_3p", "Elektrische Leistung 3~", E, "P = √3 · U · I · cos φ",
            listOf(ShkInput("u", "Spannung", "U", "V", 400.0), ShkInput("i", "Stromstärke", "I", "A"), ShkInput("cos", "Leistungsfaktor", "cos φ", "", 1.0)),
            "Drehstrom-Wirkleistung mit Außenleiterspannung.",
        ) { v ->
            val w = Shk.power3p(v.getValue("u"), v.getValue("i"), v.getValue("cos"))
            listOf(ShkOutput("Wirkleistung", "P", w, "W", 1), ShkOutput("Wirkleistung", "P", w / 1000.0, "kW"))
        },
        ShkCalculator(
            "current_3p", "Stromstärke 3~", E, "I = P / (√3 · U · cos φ)",
            listOf(ShkInput("p", "Wirkleistung", "P", "kW"), ShkInput("u", "Spannung", "U", "V", 400.0), ShkInput("cos", "Leistungsfaktor", "cos φ", "", 1.0)),
            "Zum Auswählen der Absicherung, z. B. für einen Durchlauferhitzer.",
        ) { v -> listOf(ShkOutput("Stromstärke", "I", Shk.current3p(v.getValue("p") * 1000.0, v.getValue("u"), v.getValue("cos")), "A", 2)) },
        ShkCalculator(
            "efficiency", "Wirkungsgrad", B, "η = P_ab / P_zu",
            listOf(ShkInput("out", "Abgegebene Leistung", "P_ab", "kW"), ShkInput("in", "Zugeführte Leistung", "P_zu", "kW")),
            "Verluste = P_zu − P_ab.",
        ) { v ->
            require(v.getValue("out") <= v.getValue("in")) { "Die abgegebene Leistung kann nicht größer sein als die zugeführte" }
            val eta = v.getValue("out") / v.getValue("in")
            listOf(ShkOutput("Wirkungsgrad", "η", eta * 100.0, "%", 1), ShkOutput("Verluste", "P_v", v.getValue("in") - v.getValue("out"), "kW"))
        },
        ShkCalculator(
            "gradient", "Gefälle", B, "I = Δh / L · 100 %",
            listOf(ShkInput("h", "Höhenunterschied", "Δh", "cm"), ShkInput("l", "Leitungslänge", "L", "m")),
            "Abwasser-Grundleitungen meist mindestens 1 % (1 cm je m), Richtwert 2 %.",
        ) { v ->
            val percent = v.getValue("h") / (v.getValue("l") * 100.0) * 100.0
            listOf(ShkOutput("Gefälle", "I", percent, "%", 2), ShkOutput("Gefälle", "I", v.getValue("h") / v.getValue("l"), "cm/m", 2))
        },
        ShkCalculator(
            "offset", "Etagenlänge (Versatz)", B, "c = √(a² + b²)",
            listOf(ShkInput("a", "Versatz", "a", "mm"), ShkInput("b", "Baulänge", "b", "mm")),
            "Satz des Pythagoras. Bei 45°-Bögen sind a und b gleich: c = a · √2.",
        ) { v ->
            val a = v.getValue("a"); val b = v.getValue("b")
            listOf(ShkOutput("Etagenlänge", "c", sqrt(a * a + b * b), "mm", 1))
        },
    )

    fun byId(id: String): ShkCalculator? = all.firstOrNull { it.id == id }
}
