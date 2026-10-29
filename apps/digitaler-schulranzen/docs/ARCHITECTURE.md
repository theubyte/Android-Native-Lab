# Technische Architektur

## App-Struktur

Die App ist eine klassische Android-Anwendung in Java. Der Großteil der Benutzeroberfläche wird programmatisch in `MainActivity` und spezialisierten Custom Views aufgebaut.

### Zentrale Klassen

| Klasse | Rolle |
|---|---|
| `MainActivity` | Hauptnavigation, Bibliothek, Toolbars, Dialoge, Buch-/Heft-Workflows, Split-Mode, Rechner, SHK-UI, Lernfunktionen |
| `NotebookView` | Heftseiten, S-Pen-Ink, Radierer, Lasso, Seitenzustand, Bildobjekte, PDF-Export |
| `PdfPane` | PDF-Rendering, Seitenwechsel, Matrix-Zoom/Pan, Rotation, Übersichtsinteraktion |
| `BookAnnotationView` | Schreiben, Markieren, Radieren und Interaktion über Buchseiten |
| `BookAnnotationStore` | Persistenz von Buchstrichen, Notizen, Favoriten und Bereichen |
| `PdfOutlineReader` | Lesen eingebetteter PDF-Outlines |
| `PdfBookAnalyzer` | Textbasierte automatische Kapitel-/Inhaltsanalyse |
| `MathInkRecognizer` | ML-Kit-Digital-Ink-Erkennung |
| `MathEngine` | Normalisierung und Auswertung mathematischer Ausdrücke |
| `SHKTools` | SHK-Rechnungen, Einheiten und Formelsammlung |
| `StudyStore` | Prüfungen, Karteikarten, Wiederholungsdaten und Prüfungsmarkierungen |
| `LibraryStore` | Bücher, Hefte, Arbeitsblätter, Fächer und Bibliotheksmetadaten |
| `BackupManager` | Vollbackup, SHA-256-Manifest, Server-Upload und Restore |
| `AppFileResolver` | app-eigene Dateipfade und lokalisierte Buchdateien |
| `DocumentProcessor` | Aufbereitung importierter Dokument-/Bildinhalte |

## Persistenz

Die App verwendet eine Mischung aus:

- eigenen Binärdateien für Heftinhalte,
- JSON-/Dateiformaten für Buchannotation und Metadaten,
- `SharedPreferences` für Bibliothek, Einstellungen, Lerninformationen und Sitzungsdaten,
- app-eigenem Dateispeicher für importierte Bücher, Arbeitsblätter, Ausschnitte und Backups.

Ältere Heftformate besitzen Migrationspfade bis in die aktuelle Datenstruktur.

## Renderingpfad Schreiben

Die dauerhaften Schreibdaten bestehen aus Strichen mit Punktfolgen. Punkte enthalten Positions- und Stiftinformationen. Für Bildschirmperformance existiert ein Ink-Cache. Der aktuelle Strich wird separat gezeichnet; abgeschlossene Striche werden in den Cache übernommen.

V15.0.7 verwendet einen 2× Cache und gefilterte Skalierung. Der Datenbestand selbst bleibt nicht auf diese Rasterauflösung reduziert.

## PDF

Android `PdfRenderer` übernimmt die visuelle Seitendarstellung. PDFBox Android wird für Outline und Textanalyse eingesetzt. Die Buchansicht verwendet eine Matrix für Zoom, Pan und Rotation.

## Handschrifterkennung

Google ML Kit Digital Ink Recognition liefert mehrere Kandidaten. Die App erzeugt zusätzliche normalisierte Varianten und bewertet Kandidaten anhand mathematischer Plausibilität. Die eigentliche numerische Auswertung erfolgt lokal in `MathEngine`.

## Abhängigkeiten

- `androidx.input:input-motionprediction:1.0.0`
- `com.google.mlkit:digital-ink-recognition:19.0.0`
- `com.tom-roush:pdfbox-android:2.0.27.0`
- Kotlin BOM `1.8.10` zur konsistenten transitive Runtime-Auflösung

## Server

Der aktuelle Server ist eine kleine Flask-Anwendung mit SQLite-Benutzerdatenbank. Benutzer besitzen E-Mail, Salt und PBKDF2-PIN-Hash. Bearer-Tokens sind HMAC-signiert. Backups werden pro Benutzer als ZIP-Versionen abgelegt.

Der Servercode liegt unter `server/unraid-sync/` und entspricht dem bekannten Unraid-Sync-Stand vom 05.10.2026.
