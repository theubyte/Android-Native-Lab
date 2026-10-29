# Aktueller Stand – V15.0.7

## Android-Projekt

- `applicationId`: `de.millione.schulranzen`
- `versionCode`: `1507`
- `versionName`: `15.0.7`
- `compileSdk`: `35`
- `targetSdk`: `35`
- `minSdk`: `26`
- Java: 17
- Android Gradle Plugin: 8.7.3
- Kotlin-Runtime-Abhängigkeiten werden auf 1.8.10 vereinheitlicht, weil ältere transitive JDK7/JDK8-Stdlib-Artefakte zuvor Duplicate-Class-Buildfehler ausgelöst hatten.

## Bibliothek und Organisation

`LibraryStore` verwaltet Hefte, Bücher, Arbeitsblätter, Fächer und gelöschte Elemente. Die Hauptbibliothek besitzt Typ- und Fachfilter. Elemente können umbenannt, dupliziert, Fächern zugeordnet und farblich akzentuiert werden.

## Hefte

`NotebookView` enthält eine eigene A4-Seitenengine mit mehrseitigem Binärformat und Migration älterer Dokumentversionen. Vorhanden sind:

- verschiedene Papierhintergründe,
- neue/duplizierte/verschobene/gelöschte Seiten,
- Seitenübersicht,
- Lesezeichen,
- Seitensperre,
- Textobjekte,
- Lasso,
- Lineal,
- Stift, Marker und Radierer,
- Stiftfarben und -breiten,
- PDF-Export,
- Bild-/Ausschnittobjekte auf Seiten.

Schreibdaten bleiben Punkt-/Vektordaten. Für die schnelle Darstellung wird ein gerasterter Ink-Cache verwendet.

## S Pen und Rendering

V15 hat den Schreibpfad deutlich umgebaut:

- Hardwarebeschleunigung,
- gecachter Bestandstext/Bestands-Ink,
- nur der aktive Strich wird laufend direkt gezeichnet,
- MotionEvent-Historie,
- AndroidX Motion Prediction,
- Druckinformationen,
- optionale Neigung,
- asynchron verzögertes Speichern,
- 2× High-Resolution-Ink-Cache seit V15.0.7,
- gefilterte Ausgabe des Ink-Caches beim Zoomen.

## Radierer

Vorhanden sind Teilradierer und kompletter Strichradierer. Die Entwicklung bis V15.0.7 hat den Radierpfad von einzelnen Punktabfragen zu einer kontinuierlichen Kapsel-/Segmentgeometrie geändert. Strichbreite wird bei der Trefferprüfung berücksichtigt. Der Ink-Cache wird nach dem Radieren konsistent neu aufgebaut.

## Bücher / PDF

`PdfPane`, `PdfOutlineReader` und `PdfBookAnalyzer` bilden die Buchansicht. Vorhanden sind:

- PDF-Seitenrendering,
- Seitenrotation mit gespeicherter Rotation,
- Seitenzahl-Sprung,
- eingebettetes PDF-Inhaltsverzeichnis,
- automatische Inhalts-/Kapitelanalyse als Fallback,
- frei verschiebbarer PDF-Inhalt,
- Zwei-Finger-Pan auch bei Fit-Zoom,
- gleichzeitiges Pinch-Zoom und Pan,
- Zoom bis etwa 10× Fit,
- letzte Seite wird gespeichert,
- speicherschonende Seitenübersicht in Blöcken statt Rendering des gesamten Buchs.

## Buchannotation

`BookAnnotationView` und `BookAnnotationStore` enthalten:

- Stift,
- Marker,
- Radierer,
- Notizen,
- Seitenlesezeichen,
- Favoriten/markierte Regionen,
- Auswahl von Buchausschnitten,
- Kopieren von Ausschnitten in die interne Zwischenablage,
- Export von Ausschnitten in Hefte.

## Buch + Heft

Seit V15.0.3 ist der Split-Modus wieder direkt erreichbar. Im Landscape stehen Buch und Heft nebeneinander, im Portrait untereinander. Der Trenner ist verschiebbar.

## Rechnen und Handschrifterkennung

`MathInkRecognizer` verwendet Google ML Kit Digital Ink Recognition. `MathEngine` normalisiert und berechnet erkannte Ausdrücke.

Vorhanden sind:

- automatische Rechenhinweise,
- Erkennung mehrerer ML-Kandidaten,
- Normalisierung von Multiplikation, Division, Klammern und typischen Unicode-Zeichen,
- Berechnung von `336*(55:15)` als `1232`,
- bewusste Kreis-/Oval-Geste als Auswahl einer Rechenaufgabe,
- gemeinsames Erkennen aller Stiftstriche innerhalb dieser Auswahl,
- Ergebnisblase in der Nähe der handschriftlichen Aufgabe.

Automatische Formkorrektur wurde in V15.0.4 deaktiviert, weil Dreiecke und andere Formen zu oft fälschlich als Kreise interpretiert wurden. Gerade Linien bleiben über das explizite Lineal verfügbar.

## Taschenrechner

Der wissenschaftliche Rechner ist als schwebendes Fenster umgesetzt. Er ist verschiebbar, skalierbar und responsive. Enthalten sind unter anderem Klammern, Wurzel, Potenzen, trigonometrische Funktionen, Prozent, π, e und ANS.

## SHK-Werkzeuge

`SHKTools` enthält aktuell:

- Volumenstrom aus Heizleistung und ΔT,
- Heizleistung aus Volumenstrom und ΔT,
- Rohrinhalt aus Innendurchmesser und Länge,
- Fließgeschwindigkeit aus Volumenstrom und Innendurchmesser,
- Energie zum Erwärmen einer Wassermenge,
- Druck/Förderhöhe bar ↔ mWS,
- Einheiten-Assistent für Druck, Volumenstrom, Leistung, Energie, Länge, Volumen und Temperatur,
- SHK-/Wärmelehre-/Rohrnetz-/Elektro-Formelsammlung,
- Formelfavoriten.

## Lernen und Prüfung

`StudyStore` speichert:

- Prüfungen mit Fach, Datum und Quellen,
- Karteikarten,
- Wiederholungsintervall, Ease und Fälligkeit,
- groben Lernfortschritt,
- prüfungsrelevante Seiten und Bereiche aus Heften/Büchern.

Prüfungsmarkierungen enthalten Quell-ID, Seite, Fach, Label und optional einen normalisierten Ausschnittbereich.

## Backup und Sync

`BackupManager` erstellt ein vollständiges ZIP-Snapshot mit SHA-256-Manifest. Vor dem Backup werden externe `content://`-Bücher in app-eigenen Speicher kopiert. Gesichert werden `files/` und `shared_prefs/`, darunter Hefte, Buchdateien, Annotationen, Ausschnitte, Einstellungen und Lerninformationen.

Beim Restore werden V15-Backups anhand des Manifests geprüft und zunächst in einen Staging-Bereich entpackt. Danach werden lokale Daten ersetzt und Bücher neu verknüpft.

Das aktuelle Unraid-Backend befindet sich in `server/unraid-sync/`. Es enthält:

- `/health`
- `/api/register`
- `/api/login`
- `/api/sync/upload`
- `/api/sync/versions`
- `/api/sync/latest`
- `/api/sync/version/<name>`

Authentifizierung basiert auf E-Mail + sechsstelliger PIN und signiertem Bearer-Token.
