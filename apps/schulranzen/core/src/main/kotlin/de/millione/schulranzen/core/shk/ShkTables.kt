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
    /** Where the values come from, shown under the table. Never name a source that was not checked. */
    val source: String = "",
    /** Values are guide values and shown as "Richtwert". */
    val guide: Boolean = true,
)

/** Reference tables built from the same data the calculators use, so both always agree. */
object ShkTables {
    private fun n(v: Double, d: Int = 1) = GermanNumber.format(v, d)

    private fun pipes(material: PipeMaterial) = ShkTable(
        "pipes_${material.name.lowercase()}", "Rohre: ${material.title}",
        listOf("Abmessung", "DN", "dᵢ mm", "Inhalt l/m", "Schelle alle m"),
        PipeCatalog.of(material).map { listOf(it.label, it.dn.toString(), n(it.innerMm), n(it.litresPerMeter, 3), n(it.clampSpacingM, 2)) },
        "Stangenlänge ${n(material.barLengthM, 0)} m. Schellenabstände für waagerechte Leitungen, Herstellerangaben gehen vor.",
        source = PIPE_SOURCE.getValue(material),
    )

    private val PIPE_SOURCE = mapOf(
        PipeMaterial.STEEL to "Abmessungen DIN EN 10255 (mittel). Schellenabstände aus dem Fachwissen, frei zugängliche Quelle nicht gefunden: im Tabellenbuch prüfen.",
        PipeMaterial.COPPER to "Abmessungen DIN EN 1057. Schellenabstände: Deutsches Kupferinstitut, Die fachgerechte Kupferrohr-Installation, Tab. 6.2.",
        PipeMaterial.CSTEEL to "Abmessungen und Schellenabstände nach Herstellerangaben üblicher Pressrohre: im Datenblatt prüfen.",
        PipeMaterial.MLC to "Übliche Handelsmaße. Schellenabstände: Anhaltswerte MÜPRO; Hersteller erlauben teils mehr.",
        PipeMaterial.HT to "Empfohlene Abstände waagerecht: Ostendorf, HT-System, Montageanleitung. Senkrecht 1,2–2 m.",
    )

    val all: List<ShkTable> by lazy {
        PipeMaterial.entries.map { pipes(it) } + listOf(
            ShkTable(
                "insulation", "Dämmdicken nach GModG (früher GEG)",
                listOf("Innendurchmesser", "Normal", "50 %", "An Außenluft"),
                listOf(
                    listOf("bis 22 mm", "20 mm", "10 mm", "40 mm"),
                    listOf("über 22 bis 35 mm", "30 mm", "15 mm", "60 mm"),
                    listOf("über 35 bis 100 mm", "gleich dᵢ", "½ dᵢ", "2 · dᵢ"),
                    listOf("über 100 mm", "100 mm", "50 mm", "200 mm"),
                    listOf("Fußbodenaufbau zwischen Nutzern", "6 mm", "–", "–"),
                    listOf("Kälte/Kaltwasser RLT und Klima, bis 22 mm", "9 mm", "–", "–"),
                    listOf("Kälte/Kaltwasser RLT und Klima, über 22 mm", "19 mm", "–", "–"),
                ),
                "Bezogen auf λ = 0,035 W/(m·K) bei 40 °C (Kälte: bei 10 °C). 50 % in Wand- und Deckendurchbrüchen, in Kreuzungen, an Verbindungen, " +
                    "zentralen Verteilern und zwischen beheizten Räumen verschiedener Nutzer. Ausnahmen: beheizte Räume eines Nutzers mit frei liegender Absperrung; " +
                    "Warmwasser-Stichleitungen bis 3 l. Kaltes Trinkwasser ist hier nicht geregelt (DIN 1988-200).",
                "insulation",
                source = "Gebäudemodernisierungsgesetz (GModG, früher GEG), Anlage 8, gesetze-im-internet.de.",
                guide = false,
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
                source = "Kupfer: Deutsches Kupferinstitut, Tab. 6.2. Verbund: Anhaltswerte MÜPRO. Stahl und C-Stahl aus dem Fachwissen: im Tabellenbuch prüfen.",
            ),
            ShkTable(
                "drain_gradient", "Gefälle Abwasser",
                listOf("Leitung", "Mindestgefälle", "Hinweis"),
                listOf(
                    listOf("Einzelanschlussleitung, unbelüftet", "1 %", "bis 4 m, bis 3 Bögen; im Tabellenbuch prüfen"),
                    listOf("Einzelanschlussleitung, belüftet", "0,5 %", "bis 10 m; im Tabellenbuch prüfen"),
                    listOf("Sammelanschlussleitung, unbelüftet", "1 %", "1 : 100"),
                    listOf("Schmutzwasser-Sammelleitung im Gebäude", "0,5 %", "1 : 200, h/d = 0,5"),
                    listOf("Regenwasserleitung im Gebäude", "0,5 %", "1 : 200, h/d = 0,7"),
                    listOf("Schmutz-, Misch- und Regenwasser außerhalb", "1 : DN", "z. B. DN 150 → 0,67 %, h/d = 0,7"),
                ),
                "Steiler als 1 : 20 (5 cm/m) vermeiden. Mischwasser ist im Gebäude nicht zulässig. " + ShkDesign.SOURCE,
                "gradient",
                source = "Nach DIN 1986-100, aus dem Lernmaterial des DAA-Technikums Essen. Längen und Bögen der Einzelanschlussleitungen ohne frei zugängliche Quelle.",
            ),
            ShkTable(
                "safety_valves", "Sicherheitsventile Heizung",
                listOf("Nennleistung bis (2,5 bar)", "Eintritt", "Gewinde"),
                ShkDesign.SAFETY_VALVES.map { (kw, dn) -> listOf("${n(kw, 0)} kW", "DN $dn", THREADS[dn] ?: "–") },
                "Bei 3 bar darf etwas mehr angeschlossen werden. Maßgeblich ist die Tabelle des eingebauten Ventils. " + ShkDesign.SOURCE,
                "safety_valve",
                source = "SYR Membran-Sicherheitsventil 1915, Datenblatt; Zuordnung nach TRD 721.",
            ),
            ShkTable(
                "expansion", "Ausdehnung von Wasser (ab 10 °C)",
                listOf("Temperatur", "Dichte kg/m³", "Ausdehnung n"),
                listOf(30, 40, 50, 60, 70, 80, 90, 100).map { t ->
                    listOf("$t °C", n(ShkDesign.waterDensity(t.toDouble()), 2), "${n(ShkDesign.expansionPercent(t.toDouble()), 2)} %")
                },
                "Für die MAG-Auslegung: V_e = V_A · n. Berechnet aus der Dichte; Herstellertabellen weichen um bis zu 0,04 Prozentpunkte ab.",
                "mag",
                source = "Dichte: übliche Stoffwerte. Vergleich: Reflex-Planungsunterlage (70 °C: 2,24 %).",
            ),
            ShkTable(
                "mag_sizes", "MAG-Normgrößen",
                listOf("Nennvolumen in Litern"),
                ShkDesign.MAG_SIZES.chunked(6).map { row -> listOf(row.joinToString(" · ")) },
                "Immer die nächstgrößere Größe wählen. Lieferbare Größen je Hersteller.",
                "mag",
                source = "Baureihe Reflex N/NG, ohne Gewähr für einzelne Größen.",
            ),
            ShkTable(
                "tap_points", "Berechnungsdurchflüsse Trinkwasser",
                listOf("Entnahmestelle", "V̇_R l/s"),
                TapPoint.entries.map { listOf(it.title, n(it.flowLs, 2)) },
                "Mindestfließdruck: Mischarmaturen 1 bar, Auslaufventile, Spülkasten und Maschinen 0,5 bar. " + ShkDesign.PLANNER_NOTE + " " + ShkDesign.SOURCE,
                "tw_peak",
                source = "Referenzwerte DIN 1988-300:2012 (Auswahl), aus Westermann-Lernmaterial. Ein Entwurf der Norm von 2026 liegt vor.",
            ),
            ShkTable(
                "building_types", "Spitzendurchfluss: Konstanten",
                listOf("Gebäude", "a", "b", "c"),
                BuildingType.entries.map { listOf(it.title, n(it.a, 2), n(it.b, 2), n(it.c, 2)) },
                "V̇_S = a · (ΣV̇_R)^b − c. Betreutes Wohnen und Seniorenheime wie Wohngebäude. " + ShkDesign.PLANNER_NOTE,
                "tw_peak",
                source = "DIN 1988-300:2012, wiedergegeben in SHK Profi 7/2012. Ein Entwurf der Norm von 2026 liegt vor.",
                guide = false,
            ),
            ShkTable(
                "specific_load", "Spezifische Heizlast (überschlägig)",
                listOf("Gebäude", "q"),
                ShkDesign.SPECIFIC_LOAD.map { listOf(it.first, "${n(it.second, 0)} W/m²") },
                "Nur zum Schätzen, keine Heizlastberechnung (DIN EN 12831). " + ShkDesign.PLANNER_NOTE,
                "heat_load",
                source = "Grobe Erfahrungswerte ohne Normbezug: nicht belegbar.",
            ),
        )
    }

    private val THREADS = mapOf(15 to "Rp ½", 20 to "Rp ¾", 25 to "Rp 1", 32 to "Rp 1¼", 40 to "Rp 1½", 50 to "Rp 2")

    fun byId(id: String): ShkTable? = all.firstOrNull { it.id == id }
}
