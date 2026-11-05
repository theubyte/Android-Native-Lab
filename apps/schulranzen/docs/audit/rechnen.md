# Prüfbericht Rechnen (Runde 3)

Stand 05.11.2026, Branch `agent/rechnen`. Geprüft wurden `core/shk`, `core/math` und `feature/rechnen`.

**Status:** `richtig` = gegen Quelle oder Handrechnung bestätigt. `korrigiert` = war nachweislich falsch, ist behoben.
`vereinfacht` = Rechnung stimmt, beruht aber auf Annahmen, in der App als „Richtwert“ markiert.
`nicht belegbar` = keine frei zugängliche Quelle gefunden, in der App als „Richtwert“ markiert und hier für den Ausbilder offen.

**Grenzen dieser Prüfung:** Normtexte (DIN, EN) sind kostenpflichtig und lagen nicht vor. Normwerte sind daher nur über
Sekundärquellen geprüft: Lernmaterial, Fachzeitschrift, Herstellerunterlage. Das steht in jeder Zeile. „Handrechnung“ heißt: von
Hand nachgerechnet und als JUnit-Test festgehalten (`AuditTest`, `ShkTest`, `DesignTest`, `UnitsTest`). Allgemeine physikalische
Definitionen sind als solche bezeichnet und nicht einzeln online nachgeschlagen.

## Quellen

| Kürzel | Quelle |
|---|---|
| GModG | Gebäudemodernisierungsgesetz (früher GEG), Anlage 8, gesetze-im-internet.de/geg/anlage_8.html, Wortlaut gelesen |
| Reflex | Reflex, Planungsordner Membran-Druckausdehnungsgefäße 2021 (9127141), Herstellerunterlage |
| SYR | SYR, Datenblatt Membran-Sicherheitsventil 1915, Spalte Ansprechdruck je DN |
| DKI | Deutsches Kupferinstitut, Die fachgerechte Kupferrohr-Installation (i.158, 2019), Tab. 6.2 |
| Ostendorf | Ostendorf, HT-System, Montage- und Verlegeanleitung, Abschnitt 5.3 |
| MÜPRO | MÜPRO, Abstände von Befestigungspunkten (Webseite), Anhaltswerte Verbundrohr |
| SHK Profi | SHK Profi 7/2012, Tabelle 1 „Konstanten für den Spitzendurchfluss“ nach DIN 1988-300 |
| Westermann | Westermann-Lernmaterial „DIN 1988-300, Bemessung der Trinkwasserinstallation“, Tab. 3 |
| WVND | Wasserverband, Formular Ermittlung Spitzendurchfluss (DIN 1988 Teil 3, ältere Werte) |
| DAA | DAA-Technikum Essen, Auszüge Lernmaterial HLK, Tabelle 1 Mindestgefälle nach DIN 1986-100 |
| Geberit | Geberit, Druckverlusttabellen Trinkwasser (Rauheit k = 0,0015 mm) |
| Wikipedia | de.wikipedia: Eigenschaften des Wassers, Pferdestärke, Kalorie, Pound-force per square inch |
| Walraven | Walraven, Datenblatt Fixpunkte und Wärmedehnung, Ausdehnungskoeffizienten |
| Wikibooks | Tabellensammlung Chemie, spezifische Wärmekapazitäten; chemie.de, Liste der spezifischen Wärmekapazitäten |

## 1. Konstanten

| Eintrag | Wert | Quelle | Ergebnis | Status |
|---|---|---|---|---|
| Normfallbeschleunigung g | 9,80665 m/s² | Definition, in Wikipedia/Pferdestärke bestätigt | stimmt | richtig |
| c Wasser | 4,19 kJ/(kg·K) | Wikipedia: 4,178 bis 4,219 im Bereich 0 bis 100 °C | gerundeter Mittelwert | vereinfacht |
| c Wasser | 1,163 Wh/(kg·K) | 4,1868 / 3,6, Handrechnung | stimmt; 0,08 % neben 4,19 | vereinfacht |
| ρ Wasser | 1000 kg/m³ | Wikipedia: 999,975 bei 3,98 °C | gilt kalt; bei 80 °C 3 % weniger | vereinfacht |
| 1 bar in mWS | 10,197 | aus g und ρ, Handrechnung | stimmt | richtig |
| Viskosität 60 °C | 0,475·10⁻⁶ m²/s | keine online gefunden | Fachwissen | nicht belegbar |
| Dichte 60 °C für R | 983,2 kg/m³ | Gegenprobe über n aus Reflex | vorher wurde mit 1000 gerechnet, obwohl „60 °C“ dastand (F12) | korrigiert |

## 2. Formelsammlung (36)

| Formel | Quelle | Ergebnis | Status |
|---|---|---|---|
| heat_quantity Q = m·c·ΔT | Definition, Handrechnung | stimmt | richtig |
| water_energy E = V·ΔT·1,163/1000 | Handrechnung | stimmt, ohne Verluste | vereinfacht |
| heating_time t = Q/Φ | Handrechnung | stimmt, ohne Verluste | vereinfacht |
| heat_flow Φ = Q/t | Definition | stimmt | richtig |
| mixing (Richmann) | Handrechnung, auch negative Temperaturen | stimmt, gleiche Dichte angenommen | vereinfacht |
| transmission Φ = U·A·ΔT | Definition, Handrechnung | stimmt, ohne Wärmebrücken (im Text genannt) | richtig |
| expansion Δl = l₀·α·ΔT | Walraven: Stahl 0,012, Kupfer 0,017, Verbund 0,026; PE-X je Quelle 0,15 bis 0,21 | Formel stimmt, α ist Richtwert; Verbund ergänzt | vereinfacht |
| kelvin T = ϑ + 273,15 | Definition | stimmt | richtig |
| flow_from_power V̇ = Φ/(1,163·ΔT) | Handrechnung | stimmt, ρ und c fest | vereinfacht |
| power_from_flow | Handrechnung | stimmt, ρ und c fest | vereinfacht |
| mass_flow ṁ = ρ·V̇ | Definition | stimmt | vereinfacht |
| pipe_volume | Handrechnung | stimmt | richtig |
| velocity v = V̇/A | Handrechnung | stimmt | richtig |
| diameter | Handrechnung, Umkehrprobe | stimmt | richtig |
| continuity | Definition | stimmt | richtig |
| hydrostatic p = ρ·g·h | Handrechnung | stimmt, Wasser kalt | vereinfacht |
| bar_mws 1 bar ≈ 10,2 mWS | Handrechnung | stimmt | vereinfacht |
| pump_power P = ρ·g·V̇·H/η | Handrechnung | stimmt | vereinfacht |
| ohm | Definition | stimmt | richtig |
| power_dc | Definition | stimmt | richtig |
| power_1p | Definition, Handrechnung | stimmt | richtig |
| power_3p | Definition, Handrechnung | stimmt | richtig |
| current_3p | Handrechnung (21 kW = 30,31 A) | stimmt | richtig |
| work | Definition | stimmt | richtig |
| resistors | Definition | stimmt | richtig |
| circle_area | Definition | stimmt | richtig |
| density | Wikipedia (20 °C), Tabelle unten | stimmt | richtig |
| pressure | Definition | stimmt | richtig |
| efficiency | Definition | „immer kleiner 1“ missverständlich bei Brennwert auf Heizwert (F15) | korrigiert |
| percent | Definition | stimmt | richtig |
| gradient | Handrechnung | stimmt | richtig |
| pythagoras | Handrechnung | stimmt | richtig |
| mag | Reflex | Formel stimmt, Randbedingungen falsch (F1 bis F3) | korrigiert |
| radiator | deutschland-rechner.de und SBZ Monteur nennen dieselbe Formel mit 49,83 K | stimmt; n je Heizkörper 1,2 bis 1,35 | vereinfacht |
| tw_peak | SHK Profi | stimmt; Entwurf E DIN 1988-300:2026-07 liegt vor | vereinfacht |
| pump_head | Handrechnung | stimmt, R und Z sind Annahmen | vereinfacht |

## 3. Schnellrechner (29)

| Rechner | Quelle | Ergebnis | Status |
|---|---|---|---|
| flow_from_power | Handrechnung | stimmt | vereinfacht |
| power_from_flow | Handrechnung | stimmt | vereinfacht |
| pipe_volume | Handrechnung | stimmt | richtig |
| flow_velocity | Handrechnung | stimmt; v-Bereiche sind Richtwerte und so benannt | richtig |
| pipe_diameter | Handrechnung | stimmt | richtig |
| water_energy | Handrechnung | stimmt | vereinfacht |
| heating_time | Einheitenprüfung | Formeltext ohne /1000 passte nicht zu den Einheiten (F14) | korrigiert |
| heat_quantity | Wikibooks: Stahl 0,47 bis 0,48, Kupfer 0,38 bis 0,39 | stimmt, Hinweis jetzt „≈“ | richtig |
| mixing_temperature | Handrechnung | stimmt | vereinfacht |
| transmission | Handrechnung | stimmt | richtig |
| expansion | Walraven | stimmt | vereinfacht |
| pressure_to_head | Handrechnung | stimmt | vereinfacht |
| head_to_pressure | Handrechnung | stimmt | vereinfacht |
| pump_power | Handrechnung | η über 100 % wurde angenommen (F13) | korrigiert |
| ohm | Handrechnung | stimmt | richtig |
| power_1p | Handrechnung | cos φ über 1 wurde angenommen (F13) | korrigiert |
| power_3p | Handrechnung | cos φ über 1 wurde angenommen (F13) | korrigiert |
| current_3p | Handrechnung | cos φ über 1 angenommen; „zum Auswählen der Absicherung“ zu stark (F13) | korrigiert |
| efficiency | Handrechnung | siehe Formel (F15) | korrigiert |
| gradient | DAA | Hinweis „meist 1 %, Richtwert 2 %“ widersprach der Tabelle (F7) | korrigiert |
| offset | Handrechnung | stimmt | richtig |
| pipe_sizing | Handrechnung, Geberit (k Kupfer) | Materialien ohne passende Größe verschwanden still (F11) | korrigiert |
| mag | Reflex, Handrechnung | siehe F1 bis F3 | korrigiert |
| safety_valve | SYR | Werte stimmen für 2,5 bar, Herkunft war falsch angegeben (F9) | korrigiert |
| tw_peak | SHK Profi, Westermann | stimmt; nur Wohngebäude wählbar | vereinfacht |
| insulation | GModG | Fußbodenfall falsch, Außenluft fehlte (F4, F5) | korrigiert |
| heat_load | keine | q sind Erfahrungswerte | nicht belegbar |
| radiator | wie Formel, Handrechnung | stimmt | vereinfacht |
| pump_head | Handrechnung | stimmt | vereinfacht |

Gas und Abgas: Das Modul enthält keine Rechner dafür. Der Hinweis „ersetzt nicht die Fachplanung“ steht bei MAG, Sicherheitsventil,
Trinkwasser, Rohrdimensionierung, Heizlast und Pumpe.

## 4. Tabellen (14)

| Tabelle | Quelle | Ergebnis | Status |
|---|---|---|---|
| Rohre Stahl: Maße | DIN EN 10255 aus dem Fachwissen | plausibel, Schellenabstände ohne Quelle | nicht belegbar |
| Rohre Kupfer | DKI (Abstände), DIN EN 1057 aus dem Fachwissen | alle 8 Abstände stimmen | richtig |
| Rohre C-Stahl | Herstellermaße aus dem Fachwissen; Abstände nur am Edelstahl-Pressrohr verglichen | ohne direkte Quelle | nicht belegbar |
| Rohre Mehrschichtverbund | MÜPRO | Abstände waren zu groß bei 32 bis 63 mm (F8) | korrigiert |
| Rohre HT | Ostendorf | Abstände waren zu groß bei DN 40, 50, 70 (F6) | korrigiert |
| Dämmdicken | GModG, Wortlaut | siehe F4, F5, Kälte ergänzt | korrigiert |
| Schellenabstände Übersicht | DKI, MÜPRO | Kupfer belegt, Stahl und C-Stahl nicht | nicht belegbar |
| Gefälle Abwasser | DAA | unbelüftete Sammelanschlussleitung falsch mit 0,5 % (F7) | korrigiert |
| Sicherheitsventile | SYR | siehe F9, Gewinde jetzt Rp | korrigiert |
| Ausdehnung Wasser | Reflex | höchstens 0,04 Prozentpunkte Abweichung, per Test abgesichert | richtig |
| MAG-Normgrößen | Reflex: N 8 bis N 100 gelesen, Bereich bis 1000 l genannt | Größen über 100 l nicht einzeln geprüft | nicht belegbar |
| Berechnungsdurchflüsse | Westermann, WVND | alle 9 stimmen; Bezeichnungen präzisiert (F10) | korrigiert |
| Spitzendurchfluss Konstanten | SHK Profi | alle 6 Zeilen stimmen | richtig |
| Spezifische Heizlast | keine | Erfahrungswerte | nicht belegbar |

Einzeln offen in „Gefälle Abwasser“: Längen und Bögen der Einzelanschlussleitungen (4 m, 3 Bögen, 10 m) und 0,5 % belüftet. In der Tabelle steht
„im Tabellenbuch prüfen“.

## 5. Einheiten (9 Größen, 47 Einheiten)

| Größe | Quelle | Ergebnis | Status |
|---|---|---|---|
| Druck: Pa, hPa, kPa, MPa, mbar, bar | Definition | stimmt | richtig |
| Druck: mWS, mmWS | aus g und ρ = 1000 | konventionell, Wasser kalt | vereinfacht |
| Druck: psi | Wikipedia ≈ 6895 Pa | App 6894,757 Pa stimmt | richtig |
| Volumenstrom (5) | Definition, Rundlauftest | stimmt | richtig |
| Leistung: W, kW, MW | Definition | stimmt | richtig |
| Leistung: kcal/h | Wikipedia: 1 kcal = 4186,8 J | 1,163 W stimmt | richtig |
| Leistung: PS | Wikipedia: 735,49875 W | stimmt | richtig |
| Energie (7) | Definition, Wikipedia (kcal) | stimmt | richtig |
| Länge (6) | Definition | stimmt; „Zoll“ ist nicht das Rohrmaß | richtig |
| Fläche (4) | Definition | stimmt | richtig |
| Volumen (5) | Definition | stimmt | richtig |
| Geschwindigkeit (3) | Definition | stimmt | richtig |
| Temperatur (3) | Definition | Werte unter dem absoluten Nullpunkt wurden gerechnet (F16) | korrigiert |

## 6. Daten und Modelle

| Eintrag | Quelle | Ergebnis | Status |
|---|---|---|---|
| Dichte Wasser, 11 Stützstellen | Wikipedia nur 20 °C; Gegenprobe Reflex-Ausdehnung | passt, Einzelwerte nicht einzeln belegt | vereinfacht |
| Rauheit Kupfer 0,0015 mm | Geberit | stimmt | richtig |
| Rauheit Stahl, C-Stahl, Verbund | keine | Fachwissen | nicht belegbar |
| Darcy-Weisbach mit Swamee-Jain, 64/Re | Handrechnung Cu 22 × 1 bei 1 m³/h ≈ 433 Pa/m | Formel stimmt, unter Re ≈ 5000 ungenauer (angezeigt) | vereinfacht |
| Anlageninhalt l/kW im MAG-Hinweis | Reflex: 13,5 und 8,5; Fußboden 20 ohne direkte Quelle (dort 21,1 je Anlage) | Hinweis ergänzt | vereinfacht |
| Stangenlängen (Cu 5, St 6, HT 2 …) | Ostendorf für HT, sonst Fachwissen | nur für Materialmenge | nicht belegbar |
| Dämmschlauch-Handelsgrößen | keine | nur Bestellvorschlag | nicht belegbar |
| Zuschläge Materialliste | einstellbar | keine Fachaussage | vereinfacht |

## 7. Befunde (falsch und behoben)

| Nr. | Befund | Wirkung | Behebung |
|---|---|---|---|
| F1 | MAG: Mindestvordruck 0,7 bar | Reflex fordert mindestens 1 bar; Gefäß zu klein | 1,0 bar |
| F2 | MAG: Wasservorlage 3 l auch für kleine Gefäße | Reflex: bis 15 l gelten 20 % von V_N | eigener Rechenweg |
| F3 | MAG bis 110 °C erlaubt, Dichte endet bei 100 °C, Dampfdruck fehlte | Ausdehnung und p₀ still falsch | auf 100 °C begrenzt; Warnung bei p_SV < p₀ + 1,5 bar |
| F4 | Dämmung: 6 mm „im Fußbodenaufbau“ allgemein | gilt nur zwischen verschiedenen Nutzern; sonst viel zu dünn | Text und Fall korrigiert |
| F5 | Dämmung: doppelte Dicke an Außenluft fehlte, Gesetz heißt jetzt GModG, Kälte fehlte, Bestellgröße konnte unter dem Soll liegen | Fehlmengen | ergänzt |
| F6 | HT-Schellenabstand DN 40/50 0,8 m, DN 70 1,1 m | Ostendorf: 0,5 / 0,5 / 0,8 m | korrigiert |
| F7 | Unbelüftete Sammelanschlussleitung mit 0,5 %, Rechner-Hinweis widersprach | DAA: 1 % | korrigiert, Tabelle neu |
| F8 | Verbund-Schellenabstand bis 2,5 m | MÜPRO: 1,2 bis 1,5 m | korrigiert, Hersteller gehen vor |
| F9 | Sicherheitsventil-Tabelle als „DIN EN 12828“ ausgegeben | Quelle falsch: Herstellertabelle 2,5 bar nach TRD 721 | benannt |
| F10 | „Auslaufventil DN 15“ ohne Zusatz, „Urinal Spülventil“ | mit Strahlregler gilt 0,15 l/s | präzisiert |
| F11 | Rohrdimensionierung: Werkstoff ohne passende Größe fehlte kommentarlos | wirkt wie „gibt es nicht“ | Warnung je Werkstoff |
| F12 | R mit ρ = 1000, obwohl Wasser 60 °C angegeben | R rund 1,7 % zu hoch | ρ = 983,2 |
| F13 | cos φ über 1 und η über 100 % gerechnet | unmögliche Ergebnisse | Fehlermeldung |
| F14 | Aufheizzeit: Formeltext ohne /1000 | Einheiten passten nicht, Schüler rechnet falsch ab | Formel korrigiert |
| F15 | Wirkungsgrad „immer kleiner 1“ | irreführend bei Brennwert | erklärt |
| F16 | Temperatur unter −273,15 °C wurde umgerechnet | Unsinn ohne Meldung | Meldung |
| F17 | Festkomma-Ausgabe zeigte kleine Werte als 0 | z. B. 0,0004 kW als „0“ | signifikante Stellen |
| F18 | Aufmaß: negative Zeilen fehlten in der Materialliste, zählten aber in der Summe | zu viel bestellt | Abzüge wirken |
| F19 | Aufmaß-Export: unlesbare Rechnung als leere Zelle, Summe ohne Hinweis | Fehlmenge unsichtbar | „nicht lesbar“, Warnung in CSV und PDF |
| F20 | CSV: Text beginnend mit =, +, -, @ läuft in Excel als Formel | falsche Werte, Sicherheitsrisiko | entschärft |
| F21 | Schema: Längenfeld und Maßstab zeigten nach Rückgängig den alten Text | Anzeige passte nicht zum Wert | abgeglichen |
| F22 | Schema: neues Rohr startete am Finger statt am angezeigten Anschluss | Rohr hing nicht am gezeigten Port | Start aus der Vorschau |
| F23 | Temperaturfelder mit Ziffernblock | Minus je nach Tastatur nicht erreichbar | Textfeld |

### Rechenkern

| Nr. | Eingabe | Vorher | Jetzt |
|---|---|---|---|
| M1 | Handschrift `3+4=7` | **50** (= entfernte „=“ → 3+47) | 7 |
| M2 | `2x+3` | **6** (x als Malzeichen) | Fehler: Variablen |
| M3 | `12 5`, `1 2+3` | **125**, **15** | Fehler: Rechenzeichen fehlt; `1 000` bleibt |
| M4 | zwei Zeilen `12+3` / `45` | **12+345** | jede Zeile einzeln, als unsicher |
| M5 | `1e5` | **13,59** (1·e·5) | 100000 |
| M6 | `2e-1` | still eine Deutung | Fehler: mehrdeutig; `1E-5` geht |
| M7 | `1.234` | still 1,234 | Fehler: mehrdeutig; `0.125` und `1,234` gehen |
| M8 | `0,0000000000001` | **0** | 1×10⁻¹³ |
| M9 | `abs(1E-14)`, `sqrt(1E-30)` | **0** | richtiger Wert; `sin(π)` bleibt 0 |
| M10 | `0,1+0,2-0,3` | 5,5·10⁻¹⁷, nur Anzeige 0 | echte 0 |
| M11 | `0^0` | 1 | Fehler |
| M12 | `1E-200*1E-200`, `10^-400` | **0** | Fehler: zu klein |
| M13 | Zahl mit 320 Ziffern, auch im Nenner | `1/∞` = **0** | Fehler: zu groß |
| M14 | 300 × `(` oder `-` | möglicher Stack-Überlauf | Fehlermeldung |
| M15 | Handschrift: Kandidat mit den meisten Ziffern gewann | oft der falsche | Rangfolge der Erkennung |
| M16 | Reparierte Buchstaben (1/l, 0/O, s/5, b/6/8, t/+) | still | `uncertain`, Alternativen in der Ergebnisblase |
| M17 | Wort mit t, l, s, z. B. `Test 1` | wurde zur Rechnung | keine Rechnung |
| M18 | `sin 30°` | Fehler | geht, auch im RAD-Modus als Grad |

Die „Vorher“-Werte M1 bis M17 sind aus dem alten Code hergeleitet, nicht am alten Stand ausgeführt; die „Jetzt“-Werte sind Tests.

Geprüft und richtig: Punkt vor Strich, Rechtsassoziativität der Potenz, `-2^2 = -4`, `2^-1`, Prozent (`200+10% = 220`, `200*10% = 20`), `(-8)^(1/3)`, deutsches Komma, Tausenderpunkt, Division durch 0, Wurzel und Logarithmus außerhalb des Definitionsbereichs, tan(90°), Fakultät bis 170, Rückeinlesen angezeigter Zahlen.

Bekannte Grenze: Rechnung in `double`. `10^16+1-10^16` ergibt 0 statt 1; das Ergebnis zeigt 12 gültige Stellen.

## 8. Oberfläche

Nur im Code durchgesehen und gebaut, **nicht am Gerät bedient** (kein Emulator erlaubt).

| Punkt | Ergebnis |
|---|---|
| Komma, Ausdruck im Feld | über den Rechenkern, Fehler rot; Punkt-Mehrdeutigkeit wird rot |
| Leere Felder | „Werte eingeben“, kein Ergebnis |
| Negative Werte | abgewiesen außer bei Temperaturen; MAG-Höhe darf 0 sein |
| Einheitenwahl | Rundlauf aller Paare per Test; Meldung unter 0 K |
| Ausgabe-Rundung | je Ausgabe festgelegt; kleine Werte nie „0“ |
| Aufmaß, CSV, PDF | siehe F18 bis F20; Trennzeichen, BOM und Dezimalkomma geprüft |
| Materialliste | Stangen, Schellen, Dämmung per Test nachgerechnet |
| Schema speichern, Rückgängig | jede Änderung gespeichert; Knick und Rückgängig per Test; F21, F22 |
| Rechner-Verknüpfung | Eigenschaften und Live-Ergebnis mit Markierung, Warnungen und Hinweis |
| Handy | kein Rechnen dort, nichts gebaut |

## 9. Neu gebaut

- Rohrknicke nachträglich verschieben: Rohr wählen, Griffpunkt oder geraden Abschnitt ziehen (`SchemaGeometry.moveCorner`, `moveSegment`, `cornerAt`).
- T-Stück als Formteil (`SymbolType.TEE`): in der Palette, oder automatisch, wenn ein Rohr auf einem gleichen Rohr endet (`SchemaOps.addPipeWithTees`, `splitWithTee`). Keine Längenverluste; in der Materialliste mit Rohrmaß.
- Übungsaufgaben für alle 36 Formeln (`FormulaExercises`, `ExercisePanel`): Zufallswerte, Antwort prüfen, Rechenweg. Der Test rechnet jede Aufgabe mit 200 Startwerten auf zwei Wegen: Fachfunktion und Rechenkern.
- Markierung „Richtwert“ (`Reliability`, `GuideMark`), Warnungen (`ShkCalcResult.Ok.warnings`), Hinweis Fachplanung, Quelle unter jeder Tabelle.

## 10. Offen, vom Ausbilder gegenzulesen

1. Stahlrohr-Schellenabstände (DN 10 bis 50) und C-Stahl gegen Tabellenbuch; DIN 1988-200 war nicht zugänglich.
2. Einzelanschlussleitungen: 4 m, 3 Bögen, 10 m, 0,5 % belüftet gegen DIN 1986-100.
3. Trinkwasserwerte gegen die aktuelle Fassung; ein Entwurf E DIN 1988-300:2026-07 und E DIN 1986-100:2025-06 sind erschienen, die Werte daraus waren nicht einsehbar.
4. MAG-Regeln stammen aus der Reflex-Unterlage, nicht aus EN 12828 selbst.
5. Spezifische Heizlast, Rauheiten außer Kupfer, Viskosität 60 °C, Stangenlängen, Dämmschlauchgrößen.
6. Verbund-Schellenabstände: gewählt sind die vorsichtigen Anhaltswerte; Hersteller erlauben teils mehr.
7. Gesetzesname: Die amtliche Seite führt die Anlage als GModG. Ob die Berufsschule noch GEG lehrt, sollte der Ausbilder sagen; die App nennt beide Namen.
8. Am echten Tablet offen: Schema-Gesten (Knick ziehen), Ergebnisblase mit Alternativen, Tastaturen bei Minus und Komma, PDF-Ansicht.
