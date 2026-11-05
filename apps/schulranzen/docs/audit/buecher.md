# Prüfbericht Bücher, Handschrift, Formen (Runde 3)

Agent `buecher`, Stand 05.11.2026. Zeilennummern beziehen sich auf den Ausgangsstand `ecff577`.
Pfade: `books/` = `feature/books/.../books/`, `hw/` = `feature/handwriting/.../handwriting/`, `core/` = `core/.../core/`.

## Geprüft

- PDF: Import (riesig, kaputt, Passwort, kein Speicher), Öffnen, Rendering, Bitmap-Caches, Abbruch beim Blättern, Schließen.
- Texterkennung und Indexer: Abbruch, Wiederaufnahme, Fortschritt, leere Seiten, Fehlerfälle, Löschen während der Arbeit.
- Suche: Umlaute, ß, Silbentrennung, Treffer-Position, Schnipsel, Indexformat.
- Inhaltsverzeichnis: eingebettet (auch kaputte Bäume), gedruckt, Überschriften.
- Annotationen: Matrizen bei allen vier Drehungen, Zoom, Text aufrecht, Lasso, Bereich, Rückgängig.
- Löschen eines Buchs mit allen Dateien; Speichern (Reihenfolge, Fehler).
- Handschrift-Index: Rennbedingungen, Modell fehlt, offline, Abbruch.
- Formerkennung: Schwellen mit 40 simulierten Stiften je Fall.

## Gefunden und behoben

### Bücher

| # | Stelle | Fehler | Folge | Behoben |
|---|---|---|---|---|
| 1 | `books/BookCanvasView.kt:205-208, 303` | Die Liste der Seiten mit Tinte kam im Hintergrund, die erste Zeichnung konnte früher sein und die Seite als leer eintragen | Tinte der ersten Seite unsichtbar; nächstes Speichern **überschrieb die alte Tinte** | Liste wird vor `setDocument` gelesen |
| 2 | `books/BookCanvasView.kt:1304 + 1368` | Markierung wurde zweimal in Ansichtskoordinaten umgerechnet | Suchtreffer und Sprünge aus Lernen falsch markiert | zweite Umrechnung entfernt |
| 3 | `books/BookCanvasView.kt:335` | Beim Drehen des Geräts und Ziehen des Split-Trenners änderte sich der Zoom, die Verschiebung nicht | Sprung an eine ganz andere Stelle im Buch | gleiche Stelle bleibt im Blick |
| 4 | `books/BookCanvasView.kt:377` | Aktuelle Seite = Linie bei einem Drittel der Höhe | Bei kurzen Seiten (Folien auf dem Handy) zählte die nächste Seite: Sprung meldete S. 11 statt 10, jedes Öffnen rückte eine Seite weiter, Lesezeichen auf falscher Seite | `ColumnLayout.dominant`, Sprungziel bleibt aktuell bis zum Scrollen |
| 5 | `books/BookCanvasView.kt:433` | Render-Warteschlange ohne Abbruch | Nach schnellem Wischen wartete die sichtbare Seite hinter allen überflogenen | überflogene Seiten werden übersprungen |
| 6 | `books/BookCanvasView.kt:430` | Bitmap-Höhe ohne Grenze | Streifen-PDF: Hunderte MB für eine Seite | `RenderMath.baseSize` (höchstens 4,6 MP) |
| 7 | `books/BookCanvasView.kt:435-447` | Fehlgeschlagene Seite: nur die Nummer, stilles Wiederholen bei jedem Bild | Nutzer sieht nie, was los ist | nach zwei Versuchen „Seite N nicht lesbar“ |
| 8 | `books/BookCanvasView.kt:811` | Seitentaste und Radierer-Ende radieren auch im Lesen | auf dem Handy wäre Radieren möglich | im Lesemodus abgeschaltet |
| 9 | `books/BookCanvasView.kt:225-236` | Seite drehen ließ einen markierten Bereich in alter Form stehen | Ausschnitt von der falschen Stelle | Bereich wird aufgehoben |
| 10 | `books/BookSession.kt:137-139` | Das `isActive`-Prüfen wird bei Abbruch nie erreicht, das Ergebnis von `withContext` geht verloren | PDF und Thread blieben offen, wenn der Bildschirm beim Öffnen schloss | Dokument wird außerhalb des Ergebnisses gehalten und geschlossen |
| 11 | `books/BookSession.kt:154-157` | Eine Meldung für alles; Fehler beim Inhaltsverzeichnis stoppten still auch Suche und Index | keine klare Ursache, keine Suche | eigene Texte (Passwort, Datei fehlt, Speicher, beschädigt); Rest läuft weiter |
| 12 | `books/BookSession.kt:261, 297` | Speichern parallel und Fehler verschluckt | älterer Stand konnte neueren überschreiben, voller Speicher blieb unbemerkt | eine Warteschlange, Meldung bei Fehler |
| 13 | `books/BookSession.kt:358` | „Für die Prüfung gemerkt“, bevor überhaupt gewählt war, auch bei Abbrechen | falsche Rückmeldung | entfernt, siehe Verdrahtung |
| 14 | `books/BookShelf.kt:234` | Jede `SecurityException` galt als Passwort, auch entzogene Dateirechte | falsche Meldung | Kopieren hat eigene Fehler |
| 15 | `books/BookShelf.kt:231-238` | Speicher voll beim Kopieren: „keine lesbare PDF-Datei“ | falsche Meldung | „Nicht genug Speicherplatz“ |
| 16 | `books/BookShelf.kt:258, 268` | Eine kaputte Seite oder ein kaputtes Deckblatt verwarf das ganze Buch | lesbares Buch nicht importierbar | Seite bekommt die Größe der Vorseite, Buch ohne Deckblatt geht |
| 17 | `books/BookShelf.kt:136`, `books/BookIndexer.kt:224, 285-288` | Löschen wartete nicht auf den Indexer, dessen `finally` schrieb danach `outline.json` | Ordner eines gelöschten Buchs kam zurück | `forget` wartet, spätes Speichern prüft, ob es das Buch noch gibt; Leseposition wird mit gelöscht |
| 18 | `books/BookShelf.kt` Ladepfad | Beim Beenden mitten im Import blieb `<id>.pdf.tmp` für immer | bis zu Hunderte MB verloren | wird beim Start entfernt |
| 19 | `books/BookIndexer.kt:215` | Fehlgeschlagene Seite wurde wie „gelesen, leer“ gespeichert | nie wieder versucht | bleibt offen für den nächsten Lauf, Meldung mit Anzahl |
| 20 | `books/BookIndexer.kt:111-112, 274-277` | Fehler komplett still | „Nichts gefunden“ bei unlesbarem Text | `BookIndexer.problems`, Anzeige in Suche und Inhalt |
| 21 | `books/BookOutlines.kt:69` | Lesezeichen, die aufeinander zeigen, ohne Titel: die Grenze griff nie | Endlosschleife beim Öffnen eines kaputten PDFs | Knoten- und Tiefengrenze |
| 22 | `books/BookInkStore.kt:39` | Leere Textdatei je Seite | Seite galt für immer als „hat Tinte“ | Datei wird gelöscht |
| 22a | `books/BookInkStore.kt:31-34` (Fund von `heft`) | Unlesbare Datei galt als leer, das nächste Speichern schrieb darüber | **Datenverlust** | Lesbares wird geladen, `.damaged`-Kopie bleibt, Meldung; ganz unlesbare Seite ist gesperrt und wird nicht gespeichert. Nutzt `InkCodec.readChecked` und `SafeFile.keepCopy` aus `agent/heft` |
| 23 | `core/books/TextSearch.kt:123` | Zerlegte Umlaute (u + Trema) blieben Zeichen im Text | „Übung“ fand diese PDFs nicht | Zeichen werden übergangen |
| 24 | `core/books/TextSearch.kt:108` | Nur `-` galt als Trennstrich; weicher und Unicode-Trennstrich nicht; Kompositum am Zeilenende bekam ein Leerzeichen | „Widerstand“, „Heizungs-Wasser“ nicht gefunden | alle Formen behandelt |
| 25 | `core/books/TextIndex.kt:128` | Leere Scan-Seiten galten immer als offen | Hinweis verschwand nie, jeder Lauf las sie neu | Erkannt und leer zählt als erledigt |
| 26 | `core/books/TextIndex.kt:204` | Positionen über 8191 Einheiten abgeschnitten | Treffer auf langen Seiten an falscher Stelle | Format 2 mit Schrittweite je Seite, Format 1 bleibt lesbar |
| 27 | `core/books/BookMath.kt:20` | Seitenhöhe 0 ergab eine unsichtbare Seite | Seite fehlt in der Spalte | A4-Verhältnis |

### Handschrift

| # | Stelle | Fehler | Folge | Behoben |
|---|---|---|---|---|
| 28 | `hw/HandwritingRecognizer.kt:105` | `catch (Exception)` schluckte den Abbruch; übrige Zeilen liefen ins Leere | **halbe Seite wurde als erledigt eingetragen**, gleiche Signatur verhinderte die Wiederholung | Abbruch geht durch; Index speichert nur vollständige Seiten |
| 29 | `hw/HandwritingRecognizer.kt:205` | Abbruch des Aufrufers beim Laden galt als Fehler | „Schriftmodell konnte nicht geladen werden“, obwohl nur der Bildschirm wechselte | Zustand zurück auf „fehlt“, Abbruch geht weiter |
| 30 | `hw/HandwritingRecognizer.kt:183, 200` | „Nur WLAN“ wartet ohne Ende, wenn das WLAN weg ist, und hält die Sperre | „Wird gelesen …“ hing für immer | Grenze 10 Minuten; wer nur fragt, wartet nicht |
| 31 | `hw/HandwritingIndex.kt:87-88` | Abgebrochener Lauf entfernte beim Ende den Eintrag des nächsten | zwei Läufe je Seite, ältester konnte zuletzt schreiben | entfernt nur noch sich selbst; Läufe nacheinander |
| 32 | `hw/HandwritingIndex.kt:95, 114` | Lauf für eine geleerte Seite brach sich selbst ab | Datei konnte bleiben: gelöschte Schrift nach Neustart wieder in der Suche | eigener Weg ohne Selbstabbruch |
| 33 | `hw/HandwritingIndex.kt:106-107` | Erst Speicher, dann Datei | Suche sah Stand, den ein Neustart nicht kennt | erst Datei |
| 34 | `core/shapes/TextSearch.kt:20` | Zerlegtes „ü“ wurde zu „u“, nicht „ue“ | „Muell“ fand es nicht | erst zusammensetzen |

### Formerkennung

| # | Stelle | Fehler | Folge | Behoben |
|---|---|---|---|---|
| 35 | `core/shapes/ShapeRecognizer.kt:496` | Letzter winziger Schritt zum Anfang zeigt irgendwohin und konnte eine ganze Umdrehung dazugeben oder wegnehmen | Messung: **3 von 12 sauberen Quadraten** (300 × 300) und Rechtecken wurden abgelehnt | Umdrehungen über Punkte mit Abstand; 40 Stifte je Form im Test |

Die Schwellen selbst sind stimmig: Bei 40 simulierten Stiften kommen kleinste Linie, Quadrat und Kreis durch, geschriebene „1“ und „o“, „Z“, Häkchen, Welle und Acht bleiben Schrift. Am echten Stift ist das nicht geprüft.

## Geprüft, kein Fehler

- Drehmatrizen und `PageRotation` stimmen überein, Notizen, Tinte, Bereiche und Text bleiben bei Drehung und Zoom an der Stelle (Rechnung nachvollzogen, nicht am Gerät gesehen).
- `CanvasStrokeRenderer` im Ausschnitt: die zusätzliche Matrix ist laut API nur für die Qualität, nicht doppelt angewendet.
- Rückgängig: Reihenfolge beim Radieren, Marks und Tinte im selben Verlauf, Speichern nach Undo.
- Details beim Zoomen: alte Aufträge werden über die Generation verworfen.
- Wiederaufnahme von Text und Erkennung: fertige Seiten werden übersprungen.
- Handschrift-Suche und Snippet-Bereiche: Treffer liegen immer innerhalb des Textes.

## Offen

- **Am Gerät nicht geprüft** (Regel: kein Emulator): alles Sichtbare, auch die Handy-Oberfläche bei 360 dp, Nachtmodus, Zielgrößen, TalkBack.
- Kaputte PDFs, die im nativen PDF-Teil von Android abstürzen, lassen sich aus Kotlin nicht abfangen.
- Das Bild einer Seite im Nachtmodus ist eine Umkehrung: Fotos sehen fremd aus. Ausschnitte und Karten behalten ihre echten Farben.
- Gemischte Bücher (teils Text, teils Scan) bekommen kein Angebot zur Texterkennung, nur rein gescannte. Die Karte aus einem Bereich liest dort trotzdem.
- Gedrehter Text auf ungedrehter Seite: pdfbox liefert Positionen in Schreibrichtung, der Treffer kann danebenliegen. Nur am echten PDF prüfbar.
- „Waerme“ findet in der Buchsuche nicht „Wärme“ (umgekehrt auch nicht). In der Handschriftsuche geht das.
- `shelf.json` kaputt: Bücher fehlen in der Liste, die Dateien bleiben. Es gibt keine Reparatur. Schreiben ist atomar, der Fall ist unwahrscheinlich.
- Tinte gelesener Seiten bleibt im Speicher, bis das Buch schließt.
- Stirbt die App mitten im Löschen, bleiben Dateien ohne Eintrag zurück. Nicht automatisch entfernt: bei einer beschädigten Liste wäre das Datenverlust.
- `QuietButton` (`design/Components.kt:217`) ist etwa 40 dp hoch, Besitzer `handy`.
