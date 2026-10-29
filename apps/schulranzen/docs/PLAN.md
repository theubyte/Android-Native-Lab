# Digitaler Schulranzen – Neuaufbau (Kotlin/Compose)

Stand: 2026-10-29. Dieser Plan beschreibt den Neuaufbau der App. Die alte App
(V15.0.7, Java) unter `apps/digitaler-schulranzen/` ist die Referenz für
Funktionsumfang, Daten und Erfahrungen. Ihr Code wird nicht übernommen.

## 1. Ziel

Die beste Schul-App für das **Samsung Galaxy Tab S7 FE (SM-T733)** mit S Pen:

- Hefte, Schulbücher, Arbeitsblätter, Taschenrechner, Formelsammlung und SHK-Werkzeuge in einer App.
- Offline zuerst. Der Unraid-Server dient nur für Konto, Backup und Wiederherstellung.
- **Schreiben wie auf Papier** hat Vorrang vor allem anderen.
- **Design „Retro-Modern Clean“** (siehe §3).
- **Zukunftsfähig:** Sauber in Module getrennt, damit später weitere Lernfunktionen hinzukommen können, ohne den Kern umzubauen, etwa Lernpfade, Probeprüfungen, Erklärhilfe oder KI-Tutor.

Reihenfolge: Erst muss die App alles können, was V15 kann, und so aussehen wie
hier geplant. Danach kommen die Lernfunktionen.

## 2. Zielgerät

| Eigenschaft | Wert | Folge für die App |
|---|---|---|
| Display | 12,4″, 2560 × 1600, **60 Hz**, LCD | Latenz kann nicht über 120 Hz versteckt werden. Front-Buffer-Rendering und Bewegungsvorhersage sind Pflicht. |
| SoC / RAM | Snapdragon 778G, 4–6 GB | PDF-Seiten kacheln und begrenzt cachen. Große Hefte lazy laden. |
| S Pen | Wacom EMR, 4096 Druckstufen, Neigung, Hover, **eine Seitentaste**, **kein Bluetooth** | Keine Air Actions. Alles über Hover und die Seitentaste. |
| Ausrichtung | Quer- und Hochformat | Werkzeugleiste quer links, hoch oben (wie V15) |

## 3. Design „Retro-Modern Clean“

Die Richtung: hochwertige Schreibwaren und klassische Bedienelemente (Braun, Papeterie,
alte Rechenschieber und Taschenrechner), modern reduziert umgesetzt.

- **Papier statt Glas:** warmes Off-White (`#F4F1EA`) als Grundfläche, eine sehr feine Papiertextur nur auf Flächen ohne Schrift, tiefes Tintenschwarz (`#1C1B19`) für Text.
- **Dunkelmodus gleichwertig:** „Schiefertafel“, warmes Anthrazit (`#1E1D1B`), Kreideweiß als Text. Kein reines Schwarz.
- **Fachfarben als Akzent, nie als Fläche:** gedeckte Retro-Töne (Petrol, Ziegelrot, Senf, Salbei, Taubenblau, Pflaume). Sie erscheinen als Kante, Registerlasche, Rückenstreifen oder Leuchtpunkt, so wie es in V15 gewünscht war.
- **Bedienelemente mit Haptik:** leicht erhabene, weich schattierte Tasten (Soft-3D), die beim Drücken einsinken. Eingesetzt sparsam, für Werkzeuge und Rechner, nicht für jede Fläche.
- **Typografie:**
  - eine kantige, freundliche Grotesk mit Retro-Anmutung für Titel;
  - eine ruhige Grotesk für Text;
  - eine Monospace für Zahlen, Rechner und Seitenzahlen.
  - Nur Schriften mit freier Lizenz (SIL OFL), mitgeliefert in der App.
- **Bewegung:** Federanimationen, kurz und weich. Der Werkzeugring entfaltet sich aus der Stiftspitze. Mappen öffnen sich wie Hefte. Auf der Schreibfläche selbst gibt es keine Animationen.
- **Bibliothek:** Hefte und Bücher als Objekte mit Rücken, Lasche und Fachfarbe statt Android-Karten. Keine Chips-Reihen: Fächer erscheinen als Registerleiste wie in einem Ordner.
- **Keine Standard-Android-Dialoge.** Stattdessen eigene Paneele, die aus der Kante gleiten.

## 4. Stift und Bedienung

### 4.1 Grundregeln

- **Stift schreibt, Finger navigieren:** Ein Finger blättert oder verschiebt, zwei Finger zoomen. Ein Finger zeichnet nur, wenn „Fingerzeichnen“ eingeschaltet ist.
- **Hand-Ablage:** Solange der Stift in der Nähe ist (Hover) oder schreibt, werden Berührungen mit der Handfläche ignoriert.
- **Seitentaste beim Schreiben gedrückt:** vorübergehend Radierer. Dieses Verhalten ist einstellbar.
- **Hover zeigt eine Vorschau:** Stift und Marker zeigen einen Punkt in Farbe und Dicke, der Radierer seinen Radius.

### 4.2 Werkzeugring (Seitentaste lang halten, ohne Berührung)

Wunsch des Nutzers: Wird die Seitentaste lang gehalten, während der Stift über dem
Display schwebt, erscheint um die Stiftspitze ein Ring mit Werkzeugen. Das Vorbild ist
die Werkzeugauswahl in modernen Notiz-Apps.

1. Der Stift schwebt, die Seitentaste wird gedrückt (`ACTION_HOVER_MOVE` mit `BUTTON_STYLUS_PRIMARY`).
2. Nach **300 ms** gehaltener Taste ohne Berührung entfaltet sich der Ring um die Spitze, mit einem kurzen Haptik-Impuls.
3. **Innerer Ring:** Stift, Marker, Radierer, Lasso, Lineal, Text.
   **Äußerer Ring:** die 6 zuletzt benutzten Farben und Dicken.
4. **Auswählen:**
   - Mit gehaltener Taste in Richtung eines Segments schweben, dann die Taste loslassen. Das ist die schnelle Variante.
   - Oder die Taste loslassen und das Segment antippen.
5. Wegbewegen, Schweben verlassen oder ein Tipp in die Mitte schließt den Ring.
6. Der Ring wird am Bildschirmrand nach innen verschoben, damit er immer ganz sichtbar ist.

**Risiko:** Samsung startet bei einem Druck auf die Seitentaste im Schweben das „Air
Command“-Menü. Ob die App die Taste beim Schweben trotzdem bekommt, muss auf dem Tablet
getestet werden. Gibt es einen Konflikt, Air Command ausschalten unter
Einstellungen › Erweiterte Funktionen › S Pen › Air Command. Die App erkennt den Fall und
zeigt diesen Hinweis.

### 4.3 Feste Werkzeugleiste

Sie bleibt als zweiter Weg, wie in V15, und ist schmal, damit sie die Schreibfläche
nicht verkleinert.

- **Immer sichtbar:** Rückgängig, Wiederholen, Stift, Marker, Radierer, aktuelle Farbe und Dicke.
- **Zweite Ebene:** Lasso, Lineal, Text, Einfügen, Rechner, Lesezeichen, Seiten.

## 5. Technik

| Bereich | Wahl |
|---|---|
| Sprache / UI | Kotlin, Jetpack Compose, Material 3 nur als Basis, darüber ein eigenes Designsystem |
| Schreiben | **Jetpack Ink 1.0** (`androidx.ink`): Pinsel, Striche, Rendering, Geometrie fürs Radieren. **Front-Buffer-Rendering** (`androidx.graphics:graphics-core`) und **Bewegungsvorhersage** (`androidx.input:input-motionprediction`). Striche sind Vektoren, ohne Raster-Cache als Wahrheit. |
| Radieren | Teilradierer über Ink-Geometrie (Kapsel gegen Strichform), Strichradierer über den Treffer des ganzen Strichs. Keine Reste bei schnellen Bewegungen (Problem in V15). |
| Daten | Room-Datenbank für Bibliothek, Fächer, Lernen und Metadaten. Je Heftseite eine Datei mit Strichen im Ink-Speicherformat (`ink-storage`), atomar geschrieben. |
| PDF | `PdfRenderer`, Seiten in Kacheln. Inhaltsverzeichnis und Textanalyse wie in V15 (PDFBox Android). |
| Handschrift-Mathe | Google ML Kit Digital Ink Recognition (wie V15). Rechenkern neu in Kotlin, getestet. |
| Server | Bestehendes Unraid-Backend (`apps/digitaler-schulranzen/server/unraid-sync`), API unverändert. |
| Module | `core` (reines Kotlin: Modelle, Mathe, SHK, testbar ohne Android), `ink` (Schreibkern), `design` (Designsystem), `app` (Bildschirme). Später je Lernfunktion ein eigenes Modul. |

`applicationId` ist **`de.millione.schulranzen.neu`**. So lassen sich alte und neue App
nebeneinander installieren. Die Daten aus V15 kommen über deren Vollbackup-ZIP
(Import-Funktion, §6, Phase 6).

## 6. Phasen

1. **Fundament:** Projekt, Module, Designsystem (Farben, Schriften, Tasten, Paneele), Navigation, Datenbank, CI-Build mit installierbarer APK.
2. **Schreibkern und Heft:**
   - Schreiben: Stift, Marker, Radierer mit Druck und Neigung, Hand-Ablage-Erkennung, Rückgängig/Wiederholen.
   - Seiten: Papierarten (liniert, kariert, punktiert, blanko), mehrere Seiten, Zoom und Verschieben.
   - Bedienung: Werkzeugring, Werkzeugleiste, automatisches Speichern.
3. **Heft komplett:** Lasso (verschieben, skalieren, kopieren), Lineal, Text, Seitenverwaltung, Lesezeichen, Seitensperre, PDF-Export, Bilder und Ausschnitte.
4. **Bibliothek:** Startseite, Fächer als Register, Hefte, Bücher, Arbeitsblätter, Suche, Papierkorb, Stundenplan.
5. **Bücher:**
   - Ansicht: PDF-Import, Ansicht, Drehen, Inhaltsverzeichnis und Analyse.
   - Bearbeiten: hineinschreiben, markieren, Notizen, Favoriten, Ausschnitte ins Heft, Buch und Heft nebeneinander.
6. **Rechnen und SHK:** schwebender Taschenrechner, eingekreiste Handschrift-Rechnung, SHK-Rechner, Einheiten, Formelsammlung.
7. **Lernen:** Prüfungen, Karteikarten mit Wiederholung, Prüfungsmarkierungen.
8. **Backup:** Vollbackup und Wiederherstellung (Unraid), **Import der V15-Daten**.
9. **Zukunft:** Lernpfade, Probeprüfungen aus eigenen Unterlagen, Erklärhilfe.

Jede Phase endet mit einer APK aus der CI und einer Liste, was auf dem Tablet getestet
werden muss. Ohne Test auf dem Tablet gilt nichts als „fertig“.

## 7. Offene Punkte

- **Werkzeugring:** Wird die Seitentaste beim Schweben mit Air Command zugestellt? Nur auf dem Tablet prüfbar.
- **V15-Daten:** Für den Import wird das Heft-Binärformat aus `NotebookView.java` gelesen. Gebraucht wird ein echtes Backup-ZIP vom Tablet zum Testen.
- **Schriften:** Die genaue Schriftwahl wird im Designsystem festgelegt (nur OFL).
