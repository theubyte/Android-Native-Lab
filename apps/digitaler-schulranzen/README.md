# Digitaler Schulranzen

**Aktueller Stand:** Android-App V15.0.7  
**Paket:** `de.millione.schulranzen`  
**Technik:** Java 17, Android SDK 35, minSdk 26, AGP 8.7.3

Digitaler Schulranzen ist eine auf Tablet und S Pen ausgerichtete Schul-App. Die zentrale Idee ist ein vollständiger digitaler Ersatz für Hefte, Schulbücher, Arbeitsblätter, Taschenrechner, Formelsammlungen, Lernmaterialien und SHK-Unterrichtswerkzeuge in einer einzigen offline-first App.

Der wichtigste technische Schwerpunkt ist das Schreiben mit dem S Pen. Schreibgefühl, geringe zusätzliche Latenz, Druckempfindlichkeit, Palm-Rejection und flüssiges Radieren haben Vorrang vor Animationen, OCR, Sync und Hintergrundfunktionen.

## Aktuell umgesetzt

- Mehrseitige Hefte mit verschiedenen Papierarten, Seitenverwaltung, Lesezeichen und PDF-Export.
- S-Pen-Schreiben mit Druckwerten, Neigung, MotionEvent-Historie und AndroidX Motion Prediction.
- Teilradierer und Strichradierer, Undo/Redo, Lasso, Lineal, Text und Marker.
- Bücher/PDFs mit Seitenrotation, Seitenzahl-Sprung, Inhaltsverzeichnis, automatischer Buchanalyse, freiem Zoom/Pan und speicherschonender Seitenübersicht.
- Schreiben, Markieren, Notizen, Lesezeichen, Favoriten und Ausschnitte direkt in Büchern.
- Buchausschnitte als Zwischenablageobjekte für Hefte, verschiebbar, skalierbar und ankerbar.
- Direkter Buch+Heft-Split-Modus mit verschiebbarem Trenner.
- Wissenschaftlicher, verschiebbarer und skalierbarer Taschenrechner.
- Handschriftliche Rechenerkennung und Berechnung eingekreister Rechenausdrücke.
- SHK-Schnellrechner, Einheiten-Assistent, Formelsammlung und Formelfavoriten.
- Prüfungsmarkierungen aus Heften und Büchern, inklusive gespeicherter Ausschnittbereiche.
- Prüfungsplanung und Karteikarten-/Wiederholungsdaten über `StudyStore`.
- Heller und dunkler UI-Modus, adaptive Werkzeugleiste, dauerhafte Undo/Redo- und Stiftsteuerung.
- Vollbackup inklusive lokal gespeicherter Bücher/PDFs, Notizen, Markierungen, Einstellungen und App-Daten.
- Unraid-Sync-Backend mit Registrierung, Login, Upload, Versionsliste und Restore.

## Dokumentation

- [`docs/PROJECT_IDEA.md`](docs/PROJECT_IDEA.md) – Produkt- und UX-Idee.
- [`docs/CURRENT_STATE.md`](docs/CURRENT_STATE.md) – tatsächlich vorhandener Funktionsstand.
- [`docs/OPEN_ITEMS.md`](docs/OPEN_ITEMS.md) – offene, unvollständige oder noch nicht validierte Punkte.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) – aktuelle technische Struktur.
- [`docs/CLAUDE_CONTEXT.md`](docs/CLAUDE_CONTEXT.md) – kompakter Projektkontext für die Weiterarbeit.
- [`CHANGELOG.md`](CHANGELOG.md) – wichtige Entwicklungsschritte bis V15.0.7.

Der Ordner `docs/reference/current-ui/` enthält Screenshots des tatsächlichen UI- und Schreibzustands aus der Entwicklung.
