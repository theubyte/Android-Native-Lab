package de.millione.schulranzen.core.shk

import de.millione.schulranzen.core.math.GermanNumber

/** A reference table as shown in the "Tabellen" tab. [calculatorId] links to a matching calculator. */
data class ShkTable(
    val id: String,
    val title: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    val note: String,
    val calculatorId: String? = null,
)

/** Reference tables built from the same data the calculators use, so both always agree. */
object ShkTables {
    private fun n(v: Double, d: Int = 1) = GermanNumber.format(v, d)

    private fun pipes(material: PipeMaterial) = ShkTable(
        "pipes_${material.name.lowercase()}", "Rohre: ${material.title}",
        listOf("Abmessung", "DN", "dᵢ mm", "Inhalt l/m", "Schelle alle m"),
        PipeCatalog.of(material).map { listOf(it.label, it.dn.toString(), n(it.innerMm), n(it.litresPerMeter, 3), n(it.clampSpacingM, 2)) },
        "Stangenlänge ${n(material.barLengthM, 0)} m. Schellenabstände für waagerechte Leitungen, Richtwerte – Herstellerangaben gehen vor.",
    )

    val all: List<ShkTable> by lazy {
        PipeMaterial.entries.map { pipes(it) } + listOf(
            ShkTable(
                "insulation", "Dämmdicken nach GEG",
                listOf("Innendurchmesser", "Normal", "50 %"),
                listOf(
                    listOf("bis 22 mm", "20 mm", "10 mm"),
                    listOf("über 22 bis 35 mm", "30 mm", "15 mm"),
                    listOf("über 35 bis 100 mm", "gleich dᵢ", "½ dᵢ"),
                    listOf("über 100 mm", "100 mm", "50 mm"),
                    listOf("im Fußbodenaufbau", "6 mm", "–"),
                ),
                "Bezogen auf λ = 0,035 W/(m·K). 50 % in Wand- und Deckendurchbrüchen, an Verteilern und zwischen beheizten Räumen verschiedener Nutzer. " + ShkDesign.SOURCE,
                "insulation",
            ),
            ShkTable(
                "clamps", "Schellenabstände (Übersicht)",
                listOf("DN", "Stahl", "Kupfer", "C-Stahl", "Verbund"),
                listOf(10, 12, 15, 20, 25, 32, 40, 50).map { dn ->
                    listOf("DN $dn") + listOf(PipeMaterial.STEEL, PipeMaterial.COPPER, PipeMaterial.CSTEEL, PipeMaterial.MLC).map { m ->
                        PipeCatalog.of(m).firstOrNull { it.dn == dn }?.let { "${n(it.clampSpacingM, 2)} m" } ?: "–"
                    }
                },
                "Höchstabstände waagerecht, senkrecht etwas größer möglich. " + ShkDesign.SOURCE,
            ),
            ShkTable(
                "drain_gradient", "Gefälle Abwasser",
                listOf("Leitung", "Mindestgefälle", "Hinweis"),
                listOf(
                    listOf("Einzelanschlussleitung, unbelüftet", "1 %", "max. 4 m lang, max. 3 Bögen"),
                    listOf("Einzelanschlussleitung, belüftet", "0,5 %", "max. 10 m lang"),
                    listOf("Sammelanschlussleitung", "0,5 %", "bis DN 100"),
                    listOf("Grundleitung im Gebäude", "0,5 %", "ab DN 100"),
                    listOf("Grundleitung außerhalb", "1 : DN", "z. B. DN 150 → 0,67 %"),
                    listOf("Regenwasserleitung", "0,5 %", ""),
                ),
                "Richtwerte nach DIN 1986-100. Bewährt sind 1–2 %. " + ShkDesign.SOURCE,
                "gradient",
            ),
            ShkTable(
                "safety_valves", "Sicherheitsventile Heizung",
                listOf("Nennleistung bis", "Eintritt", "Gewinde"),
                ShkDesign.SAFETY_VALVES.map { (kw, dn) -> listOf("${n(kw, 0)} kW", "DN $dn", THREADS[dn] ?: "–") },
                "Geschlossene Anlagen bis 110 °C, Ansprechdruck meist 2,5 oder 3 bar. " + ShkDesign.SOURCE,
                "safety_valve",
            ),
            ShkTable(
                "expansion", "Ausdehnung von Wasser (ab 10 °C)",
                listOf("Temperatur", "Dichte kg/m³", "Ausdehnung n"),
                listOf(30, 40, 50, 60, 70, 80, 90, 100).map { t ->
                    listOf("$t °C", n(ShkDesign.waterDensity(t.toDouble()), 2), "${n(ShkDesign.expansionPercent(t.toDouble()), 2)} %")
                },
                "Für die MAG-Auslegung: V_e = V_A · n.",
                "mag",
            ),
            ShkTable(
                "mag_sizes", "MAG-Normgrößen",
                listOf("Nennvolumen in Litern"),
                ShkDesign.MAG_SIZES.chunked(6).map { row -> listOf(row.joinToString(" · ")) },
                "Immer die nächstgrößere Größe wählen.",
                "mag",
            ),
            ShkTable(
                "tap_points", "Berechnungsdurchflüsse Trinkwasser",
                listOf("Entnahmestelle", "V̇_R l/s"),
                TapPoint.entries.map { listOf(it.title, n(it.flowLs, 2)) },
                "Mindestfließdruck meist 1 bar. " + ShkDesign.SOURCE,
                "tw_peak",
            ),
            ShkTable(
                "building_types", "Spitzendurchfluss: Konstanten",
                listOf("Gebäude", "a", "b", "c"),
                BuildingType.entries.map { listOf(it.title, n(it.a, 2), n(it.b, 2), n(it.c, 2)) },
                "V̇_S = a · (ΣV̇_R)^b − c. " + ShkDesign.SOURCE,
                "tw_peak",
            ),
            ShkTable(
                "specific_load", "Spezifische Heizlast (überschlägig)",
                listOf("Gebäude", "q"),
                ShkDesign.SPECIFIC_LOAD.map { listOf(it.first, "${n(it.second, 0)} W/m²") },
                "Nur zum Schätzen, keine Heizlastberechnung. " + ShkDesign.SOURCE,
                "heat_load",
            ),
        )
    }

    private val THREADS = mapOf(15 to "R ½", 20 to "R ¾", 25 to "R 1", 32 to "R 1¼", 40 to "R 1½", 50 to "R 2")

    fun byId(id: String): ShkTable? = all.firstOrNull { it.id == id }
}
