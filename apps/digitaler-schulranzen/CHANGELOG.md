# Changelog

## 15.0.7

- Radierer auf kontinuierliche Kapsel-/Segmentgeometrie umgestellt.
- Strichbreite in Radier-Trefferprüfung einbezogen.
- Ink-Cache nach Radieren konsistent neu aufgebaut.
- 2× High-Resolution-Ink-Cache und gefilterte Ausgabe für Handschrift.

## 15.0.6

- Radierer performanter gemacht.
- Radiusabhängiges Resampling und Bounding-Box-Vorprüfung.
- Direkte Löschung im Ink-Cache während Radiergesten.
- UI-Text mit Subpixel-/Anti-Alias-/Hinting-Einstellungen.
- Hardwarebeschleunigung explizit aktiviert.

## 15.0.5

- SHK-Schnellrechner ergänzt.
- Einheiten-Assistent ergänzt.
- Formelsammlung mit Favoriten ergänzt.
- Prüfungsmarkierungen für ganze Seiten und ausgewählte Bereiche ergänzt.

## 15.0.4

- Freies PDF-Pan auch bei Fit-Zoom.
- Gleichzeitig Pinch-Zoom + Pan.
- Zoom bis etwa 10× Fit.
- Automatische Formkorrektur deaktiviert.
- Eingekreiste handschriftliche Rechenbereiche eingeführt.
- Mathe-Normalisierung erweitert.

## 15.0.3

- Direkter Buch+Heft-Split-Zugang wiederhergestellt.
- Split im Landscape nebeneinander und im Portrait untereinander.

## 15.0.2

- Java-Compilerfehler wegen nicht initialisiertem `float w` behoben.

## 15.0.1

- Kotlin-Runtime-Konflikt mit alten JDK7/JDK8-Stdlib-Artefakten behoben.

## 15.0

- Schreibperformance als Hauptpriorität neu ausgerichtet.
- Motion Prediction, Hardware-Layer, Druckkurven und asynchrones Speichern.
- adaptive feste Werkzeugleiste statt überladener Bubble als Hauptbedienung.
- Light-/Dark-Paper-/Soft-3D-Richtung.
- Vollbackup inklusive importierter Bücher/PDFs.

## 14.x und davor

Die frühen Versionen haben die Grundfunktionen aufgebaut: Bibliothek, Hefte, Bücher, S-Pen, Seitenverwaltung, PDF-Import, Arbeitsblätter, Split-Screen, Taschenrechner, Buchannotation, Ausschnitt-Zwischenablage, Buchanalyse und Lernfunktionen. Die einzelnen historischen Readmes liegen unter `docs/history/`.
