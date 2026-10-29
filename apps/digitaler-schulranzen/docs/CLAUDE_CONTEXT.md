# Projektkontext für Claude

## Projekt

**Name:** Digitaler Schulranzen  
**Aktueller App-Stand:** 15.0.7 (`versionCode 1507`)  
**Android-Paket:** `de.millione.schulranzen`

Der Digitale Schulranzen ist eine Android-Tablet-App für Unterricht, S-Pen-Handschrift, digitale Schulbücher, Hefte, Arbeitsblätter, Lernen und SHK-Fachwerkzeuge.

## Produktprioritäten

1. Natürliches, direktes S-Pen-Schreiben ist die wichtigste Produkteigenschaft.
2. Schreib- und Radierperformance haben Vorrang vor Animationen, OCR, Sync und Hintergrundfunktionen.
3. Die App bleibt offline-first; der Server ist Backup-/Restore-Infrastruktur.
4. Bücher und Hefte sind gleichwertige Arbeitsflächen.
5. Die Oberfläche soll sehr clean, paper-like und hochwertig wirken, mit Soft-3D-/Neumorphism-Elementen, sparsamen Glows und subtilen Liquid-Animationen.
6. Funktionen sollen schnell erreichbar bleiben; tief verschachtelte Bubble-Menüs gelten als zu umständlich.

## Wichtigste vorhandene Funktionen

- S-Pen-Handschrift mit Druck, Neigung, Motion History und Motion Prediction.
- Mehrseitige Hefte mit Papierarten, Seitenverwaltung, Lesezeichen, Lasso, Lineal, Text und PDF-Export.
- Teilradierer und Strichradierer.
- Buch-/PDF-Viewer mit freiem Zoom/Pan, Rotation, Inhaltsverzeichnis und automatischer Analyse.
- Schreiben, Markieren, Notizen, Lesezeichen, Favoriten und Ausschnitte in Büchern.
- Buchausschnitte als bewegliche/sklierbare/ankerbare Objekte in Heften.
- Buch+Heft Side-by-Side.
- wissenschaftlicher schwebender Rechner.
- Handschrift-Mathe und Rechnen durch bewusstes Einkreisen.
- SHK-Schnellrechner, Einheiten-Assistent und Formelsammlung.
- Prüfungsmarkierungen und Lern-/Karteikartendaten.
- Vollbackup inklusive PDFs/Büchern und Integritätsprüfung.
- Unraid-Server für Login, Backup-Versionen und Restore.

## Letzte technische Änderungen

### 15.0.4

- Freies PDF-Pan und Pinch/Pan gleichzeitig.
- Automatische Formkorrektur deaktiviert, weil sie zu oft falsche Kreise erzeugte.
- Eingekreiste Handschrift als mathematische Auswahl.

### 15.0.5

- SHK-Schnellrechner.
- Einheiten-Assistent.
- Formelsammlung/Favoriten.
- Prüfungsmarkierungen mit optionalem Bereich.

### 15.0.6

- Radierer deutlich beschleunigt.
- Bounding-Box-Filter und gebündelte Eraser-Bewegungen.
- direkter Ink-Cache-Eraser.
- Text-/UI-Antialiasing und Hardwarebeschleunigung explizit aktiviert.

### 15.0.7

- kontinuierliche Radiersegment-/Kapselgeometrie gegen ausgelassene Stellen.
- Berücksichtigung der Strichbreite beim Radieren.
- konsistenter Cache-Rebuild nach Radieren.
- 2× High-Resolution-Ink-Cache gegen pixelige Handschrift.
- Schreibdaten bleiben Vektor-/Punktdaten.

## Aktuell noch unbestätigt

Die praktische Wirkung von V15.0.7 auf dem Zieltablet ist noch nicht vollständig bestätigt. Direkt vor diesem Update waren zwei konkrete Probleme sichtbar:

- schneller Teilradierer ließ kleine Strichreste stehen,
- handschriftliche Linien wirkten sichtbar pixelig.

Die Änderungen in 15.0.7 adressieren genau diese beiden Punkte.

## UI-Ziel im Vergleich zum aktuellen Stand

Der Funktionsumfang ist weiter als die visuelle Ausarbeitung. Der gewünschte Look ist hochwertiger als der bisherige reale UI-Stand. Die aktuelle Bibliothek und verschiedene Nebenansichten enthalten noch klassische Android-artige Flächen, Chips oder Dialoge. Die Zielidee ist reduzierter, weicher und stärker als zusammenhängendes Paper-/Soft-3D-System gestaltet.

## Buildhistorie

Zwei bereits behobene Buildprobleme sind dokumentiert:

- gemischte Kotlin-Stdlib-Versionen verursachten Duplicate Classes; aktuelle Gradle-Konfiguration vereinheitlicht die Runtime auf Kotlin 1.8.10 und schließt alte `kotlin-stdlib-jdk7/jdk8` aus.
- ein nicht initialisierter lokaler `float w` in `MainActivity` verursachte einen Java-Compilerfehler; seit 15.0.2 behoben.

## Referenzen im Repository

- `docs/reference/current-ui/home-v14-reference-gap.jpeg`: früherer Hauptbildschirm, dessen Optik deutlich hinter der gewünschten Designsprache lag.
- `docs/reference/current-ui/notebook-toolbar-v15.jpeg`: Werkzeugleistenstand aus V15.
- `docs/reference/current-ui/handwriting-before-v15.0.7.jpeg`: Handschrift vor dem High-Resolution-Cache.
- `docs/reference/current-ui/eraser-and-ink-before-v15.0.7.jpeg`: sichtbare Radierreste und Rasterwirkung vor 15.0.7.

Weitere Details stehen in `PROJECT_IDEA.md`, `CURRENT_STATE.md` und `OPEN_ITEMS.md`.
