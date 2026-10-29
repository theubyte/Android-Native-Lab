package de.millione.schulranzen.core.shk

import java.text.Normalizer

enum class FormulaCategory(val title: String) {
    HEAT("Wärmelehre"),
    PIPES("Rohrnetz"),
    ELECTRIC("Elektro"),
    BASICS("SHK-Grundformeln"),
}

data class FormulaVariable(val symbol: String, val meaning: String, val unit: String)

/**
 * One entry of the formula collection.
 * @property calculatorId quick calculator that solves this formula, if any.
 */
data class Formula(
    val id: String,
    val category: FormulaCategory,
    val title: String,
    val formula: String,
    val variables: List<FormulaVariable>,
    val explanation: String,
    val calculatorId: String? = null,
    val keywords: List<String> = emptyList(),
)

private fun v(symbol: String, meaning: String, unit: String) = FormulaVariable(symbol, meaning, unit)

object Formulas {
    private val H = FormulaCategory.HEAT
    private val R = FormulaCategory.PIPES
    private val E = FormulaCategory.ELECTRIC
    private val B = FormulaCategory.BASICS

    val all: List<Formula> = listOf(
        // Wärmelehre
        Formula(
            "heat_quantity", H, "Wärmemenge", "Q = m · c · ΔT",
            listOf(v("Q", "Wärmemenge", "kJ"), v("m", "Masse", "kg"), v("c", "spezifische Wärmekapazität", "kJ/(kg·K)"), v("ΔT", "Temperaturänderung", "K")),
            "Wie viel Wärme ein Stoff aufnimmt oder abgibt, wenn sich seine Temperatur ändert. Für Wasser ist c ≈ 4,19 kJ/(kg·K).",
            "heat_quantity", listOf("wärme", "energie", "erwärmen", "kapazität"),
        ),
        Formula(
            "water_energy", H, "Energie zum Wassererwärmen", "E = V · ΔT · 1,163 / 1000",
            listOf(v("E", "Energie", "kWh"), v("V", "Wassermenge", "l"), v("ΔT", "Temperaturänderung", "K"), v("1,163", "c von Wasser", "Wh/(kg·K)")),
            "Die Faustformel für Warmwasser: 1 Liter Wasser um 1 K erwärmen braucht 1,163 Wh.",
            "water_energy", listOf("warmwasser", "speicher", "boiler", "kwh"),
        ),
        Formula(
            "heating_time", H, "Aufheizzeit", "t = Q / Φ",
            listOf(v("t", "Zeit", "h"), v("Q", "benötigte Wärmemenge", "kWh"), v("Φ", "Heizleistung", "kW")),
            "Zeit, bis ein Speicher aufgeheizt ist. Verluste verlängern die Zeit.",
            "heating_time", listOf("speicher", "dauer", "warmwasser"),
        ),
        Formula(
            "heat_flow", H, "Wärmeleistung (Wärmestrom)", "Φ = Q / t",
            listOf(v("Φ", "Wärmestrom, Leistung", "kW"), v("Q", "Wärmemenge", "kJ oder kWh"), v("t", "Zeit", "s oder h")),
            "Leistung ist Energie pro Zeit. kJ/s = kW, kWh/h = kW.",
            null, listOf("leistung", "zeit"),
        ),
        Formula(
            "mixing", H, "Mischtemperatur", "ϑm = (m₁·ϑ₁ + m₂·ϑ₂) / (m₁ + m₂)",
            listOf(v("ϑm", "Mischtemperatur", "°C"), v("m₁, m₂", "Mengen", "kg oder l"), v("ϑ₁, ϑ₂", "Temperaturen", "°C")),
            "Richmannsche Mischungsregel für gleiche Flüssigkeiten, z. B. Mischwasser an der Armatur.",
            "mixing_temperature", listOf("mischen", "richmann", "mischwasser", "thermostat"),
        ),
        Formula(
            "transmission", H, "Transmissionswärmeverlust", "Φ = U · A · ΔT",
            listOf(v("Φ", "Wärmeverlust", "W"), v("U", "Wärmedurchgangskoeffizient", "W/(m²·K)"), v("A", "Bauteilfläche", "m²"), v("ΔT", "Temperaturdifferenz innen/außen", "K")),
            "Grundlage der Heizlastberechnung: Wärme, die durch Wand, Fenster oder Dach verloren geht.",
            "transmission", listOf("heizlast", "u-wert", "wand", "fenster"),
        ),
        Formula(
            "expansion", H, "Längenausdehnung", "Δl = l₀ · α · ΔT",
            listOf(v("Δl", "Längenänderung", "mm"), v("l₀", "Ausgangslänge", "m"), v("α", "Längenausdehnungskoeffizient", "mm/(m·K)"), v("ΔT", "Temperaturänderung", "K")),
            "Rohre dehnen sich beim Erwärmen. Kupfer 0,0166, Stahl 0,012, PE-X etwa 0,2 mm/(m·K).",
            "expansion", listOf("dehnung", "ausdehnung", "kompensator", "rohr"),
        ),
        Formula(
            "kelvin", H, "Celsius und Kelvin", "T = ϑ + 273,15",
            listOf(v("T", "absolute Temperatur", "K"), v("ϑ", "Celsius-Temperatur", "°C")),
            "Temperaturdifferenzen sind in K und °C gleich groß: 60 °C − 10 °C = 50 K.",
            null, listOf("temperatur", "umrechnen"),
        ),
        // Rohrnetz
        Formula(
            "flow_from_power", R, "Volumenstrom aus Heizleistung", "V̇ = Φ / (1,163 · ΔT)",
            listOf(v("V̇", "Volumenstrom", "m³/h"), v("Φ", "Heizleistung", "kW"), v("ΔT", "Temperaturspreizung", "K")),
            "Wie viel Heizwasser umlaufen muss, um eine Leistung zu transportieren. Grundlage für die Pumpenauslegung.",
            "flow_from_power", listOf("pumpe", "hydraulik", "spreizung", "abgleich"),
        ),
        Formula(
            "power_from_flow", R, "Heizleistung aus Volumenstrom", "Φ = 1,163 · V̇ · ΔT",
            listOf(v("Φ", "Heizleistung", "kW"), v("V̇", "Volumenstrom", "m³/h"), v("ΔT", "Temperaturspreizung", "K")),
            "Umstellung der Volumenstrom-Formel: welche Leistung ein Kreis bei gegebenem Volumenstrom überträgt.",
            "power_from_flow", listOf("hydraulik", "leistung", "spreizung"),
        ),
        Formula(
            "mass_flow", R, "Massenstrom", "ṁ = ρ · V̇",
            listOf(v("ṁ", "Massenstrom", "kg/h"), v("ρ", "Dichte", "kg/m³"), v("V̇", "Volumenstrom", "m³/h")),
            "Für Wasser gilt näherungsweise 1 m³/h ≈ 1000 kg/h.",
            null, listOf("masse", "dichte"),
        ),
        Formula(
            "pipe_volume", R, "Rohrinhalt", "V = π · dᵢ² / 4 · L",
            listOf(v("V", "Volumen", "m³"), v("dᵢ", "Innendurchmesser", "m"), v("L", "Rohrlänge", "m")),
            "Wasserinhalt einer Leitung, z. B. für Füllmenge, Spülen oder Warmwasser-Ausstoßzeiten.",
            "pipe_volume", listOf("inhalt", "füllmenge", "liter", "volumen"),
        ),
        Formula(
            "velocity", R, "Fließgeschwindigkeit", "v = V̇ / A",
            listOf(v("v", "Fließgeschwindigkeit", "m/s"), v("V̇", "Volumenstrom", "m³/s"), v("A", "Querschnitt = π · dᵢ² / 4", "m²")),
            "Zu schnell bedeutet Geräusche und hohen Druckverlust, zu langsam große Rohre. Heizung etwa 0,3–1 m/s.",
            "flow_velocity", listOf("geschwindigkeit", "rohr", "dimensionierung"),
        ),
        Formula(
            "diameter", R, "Rohrdurchmesser bestimmen", "dᵢ = √(4 · V̇ / (π · v))",
            listOf(v("dᵢ", "Innendurchmesser", "m"), v("V̇", "Volumenstrom", "m³/s"), v("v", "gewählte Fließgeschwindigkeit", "m/s")),
            "Die Fließgeschwindigkeits-Formel nach dᵢ umgestellt: mit ihr wird die Rohrdimension gewählt.",
            "pipe_diameter", listOf("dimension", "rohrweite", "auslegung"),
        ),
        Formula(
            "continuity", R, "Kontinuitätsgleichung", "A₁ · v₁ = A₂ · v₂",
            listOf(v("A₁, A₂", "Querschnitte", "m²"), v("v₁, v₂", "Fließgeschwindigkeiten", "m/s")),
            "Was in ein Rohr hineinfließt, fließt auch heraus. Bei kleinerem Querschnitt wird das Wasser schneller.",
            null, listOf("reduzierung", "querschnitt"),
        ),
        Formula(
            "hydrostatic", R, "Hydrostatischer Druck", "p = ρ · g · h",
            listOf(v("p", "Druck", "Pa"), v("ρ", "Dichte (Wasser 1000)", "kg/m³"), v("g", "Erdbeschleunigung 9,81", "m/s²"), v("h", "Höhe der Wassersäule", "m")),
            "Je 10 m Höhe steigt der Druck um etwa 1 bar. Wichtig für Anlagendruck und Ausdehnungsgefäß.",
            "head_to_pressure", listOf("druck", "höhe", "mws", "anlagendruck", "mag"),
        ),
        Formula(
            "bar_mws", R, "Druck und Förderhöhe", "1 bar ≈ 10,2 mWS",
            listOf(v("p", "Druck", "bar"), v("H", "Förderhöhe", "mWS = m")),
            "Pumpen werden mit Förderhöhe in Metern angegeben, Manometer zeigen bar.",
            "pressure_to_head", listOf("pumpe", "förderhöhe", "manometer"),
        ),
        Formula(
            "pump_power", R, "Pumpenleistung", "P = ρ · g · V̇ · H / η",
            listOf(v("P", "Wellenleistung", "W"), v("V̇", "Volumenstrom", "m³/s"), v("H", "Förderhöhe", "m"), v("η", "Pumpenwirkungsgrad", "–")),
            "Die hydraulische Leistung ρ·g·V̇·H geteilt durch den Wirkungsgrad.",
            "pump_power", listOf("pumpe", "umwälzpumpe"),
        ),
        // Elektro
        Formula(
            "ohm", E, "Ohmsches Gesetz", "U = R · I",
            listOf(v("U", "Spannung", "V"), v("R", "Widerstand", "Ω"), v("I", "Stromstärke", "A")),
            "Merkhilfe URI: U = R · I, I = U / R, R = U / I.",
            "ohm", listOf("spannung", "widerstand", "strom", "uri"),
        ),
        Formula(
            "power_dc", E, "Elektrische Leistung", "P = U · I",
            listOf(v("P", "Leistung", "W"), v("U", "Spannung", "V"), v("I", "Stromstärke", "A")),
            "Gilt für Gleichstrom und rein ohmsche Last (Heizstab).",
            null, listOf("leistung", "watt"),
        ),
        Formula(
            "power_1p", E, "Wirkleistung Wechselstrom", "P = U · I · cos φ",
            listOf(v("P", "Wirkleistung", "W"), v("U", "Spannung (230 V)", "V"), v("I", "Stromstärke", "A"), v("cos φ", "Leistungsfaktor", "–")),
            "Bei Motoren (Pumpen) ist cos φ kleiner als 1.",
            "power_1p", listOf("wechselstrom", "230"),
        ),
        Formula(
            "power_3p", E, "Wirkleistung Drehstrom", "P = √3 · U · I · cos φ",
            listOf(v("P", "Wirkleistung", "W"), v("U", "Außenleiterspannung (400 V)", "V"), v("I", "Stromstärke je Leiter", "A"), v("cos φ", "Leistungsfaktor", "–")),
            "Für Durchlauferhitzer, Wärmepumpen und Heizstäbe am Drehstromnetz.",
            "power_3p", listOf("drehstrom", "400", "durchlauferhitzer"),
        ),
        Formula(
            "current_3p", E, "Stromstärke Drehstrom", "I = P / (√3 · U · cos φ)",
            listOf(v("I", "Stromstärke", "A"), v("P", "Wirkleistung", "W"), v("U", "Außenleiterspannung", "V"), v("cos φ", "Leistungsfaktor", "–")),
            "Damit wird die Absicherung eines Drehstromgeräts überschlagen.",
            "current_3p", listOf("sicherung", "absicherung", "drehstrom"),
        ),
        Formula(
            "work", E, "Elektrische Arbeit", "W = P · t",
            listOf(v("W", "Arbeit, Energie", "kWh"), v("P", "Leistung", "kW"), v("t", "Zeit", "h")),
            "Energiekosten = W · Preis je kWh.",
            null, listOf("energie", "kosten", "kwh"),
        ),
        Formula(
            "resistors", E, "Widerstände schalten", "Reihe: R = R₁ + R₂   ·   Parallel: 1/R = 1/R₁ + 1/R₂",
            listOf(v("R", "Gesamtwiderstand", "Ω"), v("R₁, R₂", "Einzelwiderstände", "Ω")),
            "In Reihe addieren sich die Widerstände, parallel wird der Gesamtwiderstand kleiner als der kleinste.",
            null, listOf("reihenschaltung", "parallelschaltung"),
        ),
        // Grundformeln
        Formula(
            "circle_area", B, "Kreisfläche", "A = π · d² / 4",
            listOf(v("A", "Fläche", "m²"), v("d", "Durchmesser", "m")),
            "Rohrquerschnitt; gleichwertig A = π · r².",
            null, listOf("kreis", "querschnitt", "fläche"),
        ),
        Formula(
            "density", B, "Dichte", "ρ = m / V",
            listOf(v("ρ", "Dichte", "kg/m³"), v("m", "Masse", "kg"), v("V", "Volumen", "m³")),
            "Wasser hat bei 4 °C etwa 1000 kg/m³, bei 80 °C etwa 972 kg/m³.",
            null, listOf("masse", "volumen"),
        ),
        Formula(
            "pressure", B, "Druck", "p = F / A",
            listOf(v("p", "Druck", "Pa = N/m²"), v("F", "Kraft", "N"), v("A", "Fläche", "m²")),
            "1 bar = 100 000 Pa = 10 N/cm².",
            null, listOf("kraft", "fläche", "bar"),
        ),
        Formula(
            "efficiency", B, "Wirkungsgrad", "η = P_ab / P_zu",
            listOf(v("η", "Wirkungsgrad", "– oder %"), v("P_ab", "abgegebene Leistung", "kW"), v("P_zu", "zugeführte Leistung", "kW")),
            "Immer kleiner als 1 (100 %). Mehrere Stufen: η = η₁ · η₂ · …",
            "efficiency", listOf("nutzungsgrad", "verluste"),
        ),
        Formula(
            "percent", B, "Prozentrechnung", "W = G · p / 100",
            listOf(v("W", "Prozentwert", "–"), v("G", "Grundwert", "–"), v("p", "Prozentsatz", "%")),
            "Aufschläge: Neuer Wert = G · (1 + p/100).",
            null, listOf("prozent", "rabatt", "aufschlag"),
        ),
        Formula(
            "gradient", B, "Gefälle", "I = Δh / L · 100 %",
            listOf(v("I", "Gefälle", "%"), v("Δh", "Höhenunterschied", "m"), v("L", "Länge", "m")),
            "1 % entspricht 1 cm Höhenunterschied je Meter Leitung.",
            "gradient", listOf("abwasser", "neigung", "steigung"),
        ),
        Formula(
            "pythagoras", B, "Satz des Pythagoras", "c² = a² + b²",
            listOf(v("c", "schräge Seite (Etagenlänge)", "mm"), v("a, b", "Katheten (Versatz, Baulänge)", "mm")),
            "Für Etagen und Versätze im Rohrleitungsbau.",
            "offset", listOf("etage", "versatz", "dreieck"),
        ),
    )

    fun byId(id: String): Formula? = all.firstOrNull { it.id == id }

    fun byCategory(category: FormulaCategory): List<Formula> = all.filter { it.category == category }

    /**
     * Search over title, formula, variables, explanation and keywords. Every
     * word of [query] must match somewhere; case, umlauts and accents are ignored
     * so "warme" finds "Wärme". Title hits rank first.
     */
    fun search(query: String, within: List<Formula> = all): List<Formula> {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return within
        return within.mapNotNull { f ->
            val title = fold(f.title)
            val rest = fold(
                buildString {
                    append(f.formula).append(' ').append(f.explanation).append(' ').append(f.category.title)
                    f.keywords.forEach { append(' ').append(it) }
                    f.variables.forEach { append(' ').append(it.symbol).append(' ').append(it.meaning).append(' ').append(it.unit) }
                },
            )
            if (words.all { title.contains(it) || rest.contains(it) }) {
                val score = words.count { title.contains(it) } * 10 + if (title.startsWith(words.first())) 5 else 0
                f to score
            } else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    internal fun fold(s: String): String {
        val lower = s.lowercase().replace("ß", "ss").replace("ä", "a").replace("ö", "o").replace("ü", "u")
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("\\s+"), " ").trim()
    }
}
