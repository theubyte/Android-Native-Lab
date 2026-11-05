# Store-Eintrag (Entwurf)

Stand: Version 0.5.0. Alle Aussagen sind am Code geprüft. Nichts davon ist veröffentlicht.

**Vor dem Einstellen klären:** Der Name „Schulranzen“ ist nicht geprüft (siehe [checkliste.md](checkliste.md)).
Der Paketname `de.millione.schulranzen.neu` ist nach dem ersten Hochladen für immer fest.

## App-Titel (höchstens 30 Zeichen)

`Schulranzen` (11 Zeichen)

Wenn der Name nicht frei ist, braucht es einen neuen Namen. Der wäre dann auch in `AndroidManifest.xml` (`android:label`) zu ändern.

## Kurzbeschreibung (höchstens 80 Zeichen)

`Hefte, Bücher, Rechnen und Lernen an einem Ort. Für Schule und Ausbildung.` (74 Zeichen)

## Langbeschreibung (höchstens 4000 Zeichen)

```
Schulranzen ersetzt Hefte, Ordner und den Stapel Kopien. Schreib mit dem Stift auf dem Tablet, lies deine Bücher als PDF und lern für die nächste Prüfung, alles in einer App. Deine Sachen bleiben auf deinem Gerät.

SCHREIBEN (TABLET MIT STIFT)
• Hefte nach Fächern sortiert, mit Linien, Karos oder leerem Blatt
• Stift mit Druck und Neigung, Marker, Radierer, Lasso, Lineal
• Handschrift in Text umwandeln und in der Handschrift suchen
• Arbeitsblätter fotografieren oder als PDF einfügen
• Hefte als PDF ausgeben

BÜCHER
• PDF-Bücher einlesen, darin schreiben und markieren
• Volltextsuche, Lesezeichen, Inhaltsverzeichnis
• Texterkennung für gescannte Bücher, auf dem Gerät
• Buch und Heft nebeneinander, Ausschnitte direkt ins Heft

LERNEN
• Stundenplan mit A- und B-Wochen
• Prüfungen mit Countdown und Lernplan
• Karteikarten mit Wiederholung im richtigen Abstand, auch mit Bildern
• Probeprüfungen aus deinen Karten

RECHNEN UND SHK
• Taschenrechner, Einheiten und Formelsammlung
• Eine Rechnung im Heft einkreisen, das Ergebnis steht daneben
• Für die Ausbildung zum Anlagenmechaniker SHK: Rechner, Auslegungstabellen, Aufmaß, Materialliste und Schema-Editor

AUF DEM HANDY
Auf dem Handy liest du, scannst und lernst. Geschrieben wird auf dem Tablet.

DEINE DATEN
• Keine Werbung, kein Konto, kein Tracking durch die App
• Sichern als Datei, auf Wunsch verschlüsselt (AES-256) und automatisch
• Sicherung auf den eigenen Server im Heimnetz möglich
• Erklärhilfe mit Claude nur mit deinem eigenen API-Schlüssel von Anthropic. Ohne Schlüssel geht kein Text dorthin

GUT ZU WISSEN
• Die Handschrifterkennung lädt einmalig ein Sprachmodell von Google (etwa 20 MB)
• Die Texterkennung und die Handschrifterkennung stammen von Google ML Kit. Der Inhalt bleibt auf dem Gerät. ML Kit sendet aber technische Nutzungsdaten an Google, siehe Datenschutzerklärung
• Die SHK-Rechner und Tabellen sind Lernhilfen mit vereinfachten Werten. Für echte Anlagen gelten die aktuellen Normen und die Herstellerangaben
```

Prüfpunkte zum Text:

- „kein Tracking durch die App“ stimmt nur für die App selbst. Der ML-Kit-Absatz steht deshalb bewusst daneben.
  Wer das nicht so stehen lassen will, streicht „durch die App“ nicht einfach weg, sondern nimmt den ganzen Punkt heraus.
- „Hefte als PDF ausgeben“, „Karteikarten mit Bildern“ und die übrigen Funktionen sind laut `docs/STAND.md` gebaut. Auf dem Tablet ist vieles noch nicht geprüft (dort mit **(T)** markiert). Vor dem Einstellen abgleichen.
- Der Handy-Absatz setzt die Arbeit des Agenten „handy“ voraus.
- Keine Superlative, keine Preise und keine Aufrufe zum Bewerten: Das verlangt Google für Metadaten.
- Marken: „Claude“, „Anthropic“ und „Google“ werden nur beschreibend genannt. Rechtlich prüfen lassen.

## Kategorie und Kontakt

- Kategorie: **Bildung**
- Kontakt-E-Mail: `[PLATZHALTER: E-Mail für den Store-Eintrag]`
- Datenschutz-URL: `[PLATZHALTER: öffentliche URL der Datenschutzerklärung]`
- Website: `[PLATZHALTER: optional]`

## Grafiken

| Datei | Vorgabe | Stand |
|---|---|---|
| App-Symbol | 512 × 512 PNG | [grafik/icon-512.png](grafik/icon-512.png), erzeugt aus dem Vektor-Symbol |
| Vorstellungsgrafik | 1024 × 500 | fehlt, braucht eine Gestaltungsentscheidung |

## Screenshot-Liste

Aufnehmen erst nach dem Zusammenführen von Runde 3, mit Beispieldaten ohne echte Namen und Schulen. Je Gerätegröße mindestens 2, für Tablets besser 4 bis 8.
Der Stift-Teil braucht das echte Tablet.

**Tablet, 10 Zoll, quer (Tab S7 FE, 2560 × 1600):**

1. Bibliothek mit Fächer-Reitern, Heften und Tagesleiste, hell
2. Offenes Heft mit Handschrift, Skizze und Werkzeugleiste, hell
3. Lasso-Auswahl mit „Ausrechnen“ oder eingekreister Rechnung
4. Buch und Heft nebeneinander
5. Lernen: Prüfung mit Countdown und Lernplan
6. Karteikarte im Lernmodus
7. SHK-Schema-Editor mit Beispiel
8. Bibliothek oder Heft, dunkel

**Tablet, 7 Zoll:** Motive 1, 4 und 5.

**Handy, hoch (mindestens 1080 × 1920):**

1. Bibliothek auf dem Handy
2. Buch lesen
3. Dokument scannen
4. Karteikarte lernen
5. Stundenplan

Vorhandene Bilder in `docs/screens/` zeigen Entwicklungsstände und sind nicht als Store-Bilder gedacht.
