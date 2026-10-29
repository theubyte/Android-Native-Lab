package de.millione.schulranzen.core.shk

import de.millione.schulranzen.core.math.GermanNumber

/**
 * Design calculators ("Auslegung"). Their input keys double as property keys
 * of the schema symbols, so a tapped pump or radiator opens its calculator
 * pre-filled (see [SchemaLinks]).
 */
internal object DesignCalculators {
    private val D = FormulaCategory.DESIGN

    val all: List<ShkCalculator> = listOf(
        ShkCalculator(
            "pipe_sizing", "Rohrdimensionierung Heizung", D, "V̇ = Φ / (1,163 · ΔT) → R ≤ R_max",
            listOf(
                ShkInput("p", "Heizleistung der Teilstrecke", "Φ", "kW"),
                ShkInput("dt", "Temperaturspreizung", "ΔT", "K", 20.0, "Vorlauf − Rücklauf"),
                ShkInput("r", "Höchster Druckverlust", "R_max", "Pa/m", 100.0, "üblich 100–200 Pa/m"),
                ShkInput("v", "Höchste Geschwindigkeit", "v_max", "m/s", 1.0),
            ),
            "Kleinste Dimension je Werkstoff, die R und v einhält. R nach Darcy-Weisbach, Wasser 60 °C. " + ShkDesign.SOURCE,
        ) { v ->
            val q = Shk.flowFromPower(v.getValue("p"), v.getValue("dt"))
            val out = mutableListOf(ShkOutput("Volumenstrom", "V̇", q, "m³/h"))
            PipeMaterial.entries.filter { it.pressurePipe }.forEach { m ->
                val pick = PipeCatalog.choose(m, q, v.getValue("r"), v.getValue("v"))
                out += if (pick == null) ShkOutput("${m.title}: größer als Tabelle", "R", Double.NaN, "Pa/m")
                else ShkOutput("${pick.size.fullLabel}, v = ${GermanNumber.format(pick.velocityMs, 2)} m/s", "R", pick.rPaPerM, "Pa/m", 0)
            }
            out.filter { it.value.isFinite() }.ifEmpty { throw IllegalArgumentException("Volumenstrom zu groß für die Tabelle") }
        },
        ShkCalculator(
            "mag", "MAG-Auslegung", D, "V_N = (V_e + V_V) · (p_e + 1) / (p_e − p₀)",
            listOf(
                ShkInput("va", "Anlageninhalt", "V_A", "l", hint = "≈ 8,5 l/kW Heizkörper, 20 l/kW Fußbodenheizung"),
                ShkInput("hst", "Statische Höhe über MAG", "h_st", "m", 8.0),
                ShkInput("tmax", "Höchste Vorlauftemperatur", "ϑ_V", "°C", 70.0),
                ShkInput("psv", "Ansprechdruck Sicherheitsventil", "p_SV", "bar", 3.0),
            ),
            "Vordruck p₀ = h_st/10 + 0,2 bar (mind. 0,7 bar), Enddruck p_e = p_SV − 0,5 bar, Wasservorlage 0,5 % (mind. 3 l). " + ShkDesign.SOURCE,
        ) { v ->
            val r = ShkDesign.mag(v.getValue("va"), v.getValue("hst"), v.getValue("tmax"), v.getValue("psv"))
            listOfNotNull(
                ShkOutput("Nennvolumen mindestens", "V_N", r.nominalMinL, "l", 1),
                r.standardL?.let { ShkOutput("Gewählte Normgröße", "V_N", it.toDouble(), "l", 0) },
                ShkOutput("Vordruck", "p₀", r.preChargeBar, "bar", 2),
                ShkOutput("Fülldruck (kalt)", "p_a", r.fillBar, "bar", 2),
                ShkOutput("Enddruck", "p_e", r.finalBar, "bar", 2),
                ShkOutput("Ausdehnung", "n", r.expansionPercent, "%", 2),
                ShkOutput("Ausdehnungsvolumen", "V_e", r.expansionL, "l", 1),
                ShkOutput("Wasservorlage", "V_V", r.reserveL, "l", 1),
            )
        },
        ShkCalculator(
            "safety_valve", "Sicherheitsventil-Auswahl", D, "Φ → DN (Eintritt)",
            listOf(ShkInput("p", "Nennleistung Wärmeerzeuger", "Φ", "kW")),
            "Bis 50 kW DN 15, bis 100 kW DN 20, bis 200 kW DN 25, bis 350 kW DN 32, bis 600 kW DN 40, bis 900 kW DN 50. " +
                "Ansprechdruck nach Anlage, meist 2,5 oder 3 bar. " + ShkDesign.SOURCE,
        ) { v ->
            val dn = ShkDesign.safetyValveDn(v.getValue("p")) ?: throw IllegalArgumentException("Über 900 kW: Hersteller fragen")
            listOf(ShkOutput("Eintrittsnennweite mindestens", "DN", dn.toDouble(), "", 0))
        },
        ShkCalculator(
            "tw_peak", "Trinkwasser-Spitzendurchfluss", D, "V̇_S = a · (ΣV̇_R)^b − c",
            TapPoint.entries.map { ShkInput(it.name.lowercase(), it.title, "n", "Stk", 0.0, "V̇_R = ${GermanNumber.format(it.flowLs, 2)} l/s", allowNonPositive = true) },
            "Wohngebäude: a = 1,48, b = 0,19, c = 0,94. Nie mehr als ΣV̇_R, nie weniger als die größte Entnahmestelle. " + ShkDesign.SOURCE,
        ) { v ->
            val counts = TapPoint.entries.associateWith { t ->
                val n = v.getValue(t.name.lowercase())
                require(n >= 0 && n % 1.0 == 0.0) { "${t.title}: ganze Anzahl ≥ 0" }
                n.toInt()
            }
            val sum = counts.entries.sumOf { it.key.flowLs * it.value }
            require(sum > 0) { "Mindestens eine Entnahmestelle eingeben" }
            val vs = ShkDesign.peakFlow(counts)
            listOf(
                ShkOutput("Spitzendurchfluss", "V̇_S", vs, "l/s", 2),
                ShkOutput("Spitzendurchfluss", "V̇_S", vs * 60.0, "l/min", 1),
                ShkOutput("Summendurchfluss", "ΣV̇_R", sum, "l/s", 2),
            )
        },
        ShkCalculator(
            "insulation", "Dämmdicke nach GEG", D, "s ≥ f(dᵢ) bei λ = 0,035",
            listOf(ShkInput("di", "Innendurchmesser", "dᵢ", "mm")),
            "Bis 22 mm: 20 mm, bis 35 mm: 30 mm, bis 100 mm: gleich dᵢ, darüber 100 mm. Halbe Dicke in Durchbrüchen, " +
                "an Verteilern und zwischen Nutzern; 6 mm im Fußbodenaufbau. " + ShkDesign.SOURCE,
        ) { v ->
            val di = v.getValue("di")
            listOf(
                ShkOutput("Dämmdicke normal", "s", ShkDesign.insulationMm(di), "mm", 1),
                ShkOutput("Dämmdicke 50 %", "s", ShkDesign.insulationMm(di, InsulationCase.HALF), "mm", 1),
                ShkOutput("Im Fußbodenaufbau", "s", 6.0, "mm", 0),
            )
        },
        ShkCalculator(
            "heat_load", "Heizlast überschlägig", D, "Φ_HL = A · q",
            listOf(
                ShkInput("a", "Beheizte Fläche", "A", "m²"),
                ShkInput("q", "Spezifische Heizlast", "q", "W/m²", 80.0, "Altbau 150 · EnEV 60 · Neubau 45 · Passivhaus 15"),
            ),
            "Nur zum Überschlagen; die Raumheizlast wird nach DIN EN 12831 berechnet. " + ShkDesign.SOURCE,
        ) { v ->
            val w = v.getValue("a") * v.getValue("q")
            listOf(ShkOutput("Heizlast", "Φ_HL", w / 1000.0, "kW", 2), ShkOutput("Heizlast", "Φ_HL", w, "W", 0))
        },
        ShkCalculator(
            "radiator", "Heizkörperleistung", D, "Φ = Φ_N · (ΔT_ln / 49,8 K)ⁿ",
            listOf(
                ShkInput("pn", "Normleistung 75/65/20", "Φ_N", "W"),
                ShkInput("tv", "Vorlauftemperatur", "ϑ_V", "°C", 55.0),
                ShkInput("tr", "Rücklauftemperatur", "ϑ_R", "°C", 45.0),
                ShkInput("ti", "Raumtemperatur", "ϑ_i", "°C", 20.0),
                ShkInput("n", "Heizkörperexponent", "n", "", 1.3, "Plattenheizkörper ≈ 1,3"),
            ),
            "Umrechnung der Katalogleistung (EN 442) auf andere Temperaturen. " + ShkDesign.SOURCE,
        ) { v ->
            val dl = ShkDesign.logDelta(v.getValue("tv"), v.getValue("tr"), v.getValue("ti"))
            val w = ShkDesign.radiatorOutput(v.getValue("pn"), v.getValue("tv"), v.getValue("tr"), v.getValue("ti"), v.getValue("n"))
            listOf(
                ShkOutput("Heizleistung", "Φ", w, "W", 0),
                ShkOutput("Anteil der Normleistung", "Φ/Φ_N", w / v.getValue("pn") * 100.0, "%", 1),
                ShkOutput("Logarithmische Übertemperatur", "ΔT_ln", dl, "K", 1),
            )
        },
        ShkCalculator(
            "pump_head", "Pumpe: Förderhöhe und Volumenstrom", D, "H = (R · L · (1 + Z) + Δp_z) / (ρ · g)",
            listOf(
                ShkInput("p", "Heizleistung", "Φ", "kW"),
                ShkInput("dt", "Temperaturspreizung", "ΔT", "K", 20.0),
                ShkInput("l", "Länge ungünstigster Kreis (Vor- + Rücklauf)", "L", "m"),
                ShkInput("r", "Druckverlust je Meter", "R", "Pa/m", 100.0),
                ShkInput("z", "Zuschlag Einzelwiderstände", "Z", "%", 30.0),
                ShkInput("dpx", "Weitere Bauteile (Ventile, Kessel)", "Δp_z", "kPa", 10.0, allowNonPositive = true),
            ),
            "Betriebspunkt der Umwälzpumpe für den ungünstigsten Kreis. " + ShkDesign.SOURCE,
        ) { v ->
            require(v.getValue("dpx") >= 0) { "Weitere Bauteile: Wert ≥ 0" }
            val q = Shk.flowFromPower(v.getValue("p"), v.getValue("dt"))
            val h = ShkDesign.pumpHeadM(v.getValue("l"), v.getValue("r"), v.getValue("z"), v.getValue("dpx"))
            listOf(
                ShkOutput("Volumenstrom", "V̇", q, "m³/h"),
                ShkOutput("Förderhöhe", "H", h, "m", 2),
                ShkOutput("Druckdifferenz", "Δp", h * Shk.WATER_DENSITY * Shk.G / 1000.0, "kPa", 1),
            )
        },
    )
}
