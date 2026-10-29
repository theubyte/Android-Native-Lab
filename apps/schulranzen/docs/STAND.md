# Schulranzen – Stand und offene Punkte

Diese Datei ist die **eine Liste**, damit nichts verloren geht. Nach jeder Arbeitssitzung wird sie
aktualisiert: Erledigtes wandert nach „Erledigt“ (mit Version), Neues kommt unter „Offen“.
Plan und Design im Detail: [PLAN.md](PLAN.md). Regeln für parallele Agenten: [AGENTS.md](AGENTS.md).

Legende: `[x]` erledigt · `[ ]` offen · `[~]` teilweise · **(T)** nur auf dem echten Tablet prüfbar

Letzte Aktualisierung: 29.10.2026, Version **0.3.1**

---

## Offen

### 1. Vor dem Alltagseinsatz (Priorität hoch)
- [x] Fester Signaturschlüssel, damit Updates die Hefte behalten (0.3.1, nur lokal)
- [ ] Optional: Schlüssel als GitHub-Secret hinterlegen, damit auch CI-APKs passen
      (braucht dich: Secret im GitHub-Webinterface anlegen)
- [ ] **(T)** Erste Installation auf dem Tab S7 FE (Anleitung unten)
- [ ] **(T)** S Pen: Druck, Neigung, Verzögerung, Schreibgefühl
- [ ] **(T)** Hand-Ablage beim Schreiben
- [ ] **(T)** Seitentaste im Schweben → Werkzeugring (oder fängt Air Command sie ab?)
- [ ] **(T)** Zwei-Finger-Zoom in Heft und Buch
- [ ] **(T)** Hochformat
- [ ] **(T)** V15-Übernahme mit dem echten Backup der alten App
- [ ] **(T)** Unraid-Sync mit dem echten Server

### 2. Aus der alten App (V15) noch nicht da
- [ ] Buch und Heft nebeneinander (Split mit verschiebbarem Trenner)
- [ ] Rechnungen automatisch erkennen beim Schreiben / Kreis-Geste zum Ausrechnen
      (jetzt: Lasso → „Ausrechnen“)
- [ ] Prüfungsmarkierungen: Heft-/Buchstelle als „prüfungsrelevant“ markieren, aus Lernen dorthin springen
- [ ] Lineal als echtes verschieb- und drehbares Lineal auf dem Blatt (jetzt: Gerade-Linie-Werkzeug)
- [ ] Werkzeugnamen anzeigen, wenn der Stift über der Werkzeugleiste schwebt

### 3. Lücken in den neuen Bereichen
- Heft
  - [ ] Lasso über Seitengrenzen
  - [ ] Formen (Kreis, Pfeil, Rechteck) mit Formerkennung
  - [ ] Handschrift → Text
  - [ ] Suche in der Handschrift
  - [ ] Neues Heft direkt aus PDF/Arbeitsblatt anlegen
- Bücher
  - [ ] Volltextsuche im Buch
  - [ ] Lasso und Text im Buch
  - [ ] Inhaltsverzeichnis für gescannte Bücher (Texterkennung)
  - [ ] Rückgängig für Notizen, Bereiche, Drehen
- Lernen
  - [ ] Karteikarten mit Bildern
  - [ ] Karte direkt aus Heft-/Buchausschnitt anlegen
  - [ ] Quellen einer Prüfung als Sprung-Links statt Freitext
- Rechnen
  - [x] SHK-Rechner: Tastatur verdeckte das Ergebnis → Ergebnis steht jetzt neben den Feldern (0.3.1)
  - [ ] Handschrift-Rechnen braucht beim ersten Mal Internet (ML-Kit-Modell ~20 MB) – Hinweis/Vorab-Download
- Sichern
  - [ ] Backups auf dem Server verschlüsseln
  - [ ] Automatisches Backup (z. B. täglich im WLAN)
  - [ ] Zusammenführen statt Ersetzen bei mehreren Geräten
  - [ ] Datenbanken vor dem Wiederherstellen sauber schließen (`beforeRestore`-Hook verdrahten)
- Bibliothek
  - [ ] Suche auch im Inhalt (Text, später Handschrift)

### 4. SHK-Ausbau (schon in V15 offen)
- [ ] Digitales Aufmaß
- [ ] Automatische Materiallisten
- [ ] Schema-Editor mit Symbolbibliothek (Pumpe, MAG, Speicher, Ventile, Wärmeerzeuger, Rohrnetz), mit Berechnungen verknüpft
- [ ] Weitere Tabellen und Auslegungshilfen

### 5. Zukunft (Phase 9)
- [ ] Lernpfade
- [ ] Probeprüfungen aus eigenen Heften und Büchern
- [ ] Erklärhilfe / Lernassistent
- [ ] Automatisch erzeugte Karteikarten

---

## Erledigt

### 0.3.1
- Fester Signaturschlüssel: neue Versionen installieren sich über die alte, Hefte bleiben erhalten.
  Der Schlüssel liegt **nur lokal** (Repo ist öffentlich): `apps/schulranzen/signing/` (git-ignoriert)
  plus Sicherung in `A:\LumaCutndroid-signing\schulranzen\`. **Nicht löschen** – ohne ihn
  lassen sich keine Updates mehr über die installierte App spielen.
  GitHub-Builds (CI) signieren weiter mit dem Zufalls-Testschlüssel → **zum Installieren die APK
  aus dem Chat bzw. vom lokalen Build nehmen, nicht das CI-Artefakt.**
- SHK-Rechner: Ergebnis rechts neben den Eingabefeldern, bleibt beim Tippen sichtbar.
- Buchausschnitt ins Heft: Ist die Zielseite gesperrt, kommt der Ausschnitt auf eine neue Seite
  dahinter. Sehr hohe Ausschnitte werden auf 80 % der Seitenhöhe begrenzt.
  (Gebaut, im Emulator noch nicht durchgespielt.)
- Im Emulator geprüft: APK mit festem Schlüssel signiert (CN=Schulranzen), SHK-Ergebnis bei
  offener Tastatur sichtbar ([Bild](screens/shk-tastatur-0.3.1.png)), Beschriftungen trennen sauber.

### 0.3.0 – Bücher, Rechnen, Lernen, Sichern (4 Agenten parallel)
- Arbeitsblätter als Heftseiten (Kamera, Fotos, PDF)
- Bibliothek: Suche, Papierkorb (30 Tage), Bücherregal, Tagesleiste
- Bücher: PDF-Import, Zoom, Inhaltsverzeichnis, Schreiben/Markieren, Notizen, Lesezeichen, Ausschnitt ins Heft
- Rechnen: schwebender Taschenrechner, „Ausrechnen“ per Lasso (ML Kit), 21 SHK-Rechner, Einheiten, 32 Formeln
- Lernen: Stundenplan mit A/B-Wochen, Prüfungen mit Countdown und Lernplan, Karteikarten (SM-2)
- Sichern: Vollbackup + Wiederherstellung, Unraid-Server, Übernahme aus V15

### 0.2.0 – Heft komplett
- Lasso (verschieben, skalieren, ausschneiden, kopieren, einfärben), Lineal mit 15°-Raster,
  Text, Bilder, Seitenübersicht, Lesezeichen, Seitensperre, PDF-Export, Rückgängig für alles

### 0.1.0 – Grundlage
- Design „Retro-Modern Clean“, Schriften, Icons, Bibliothek mit Fächern
- Schreiben mit Jetpack Ink (Druck, Neigung, Vorhersage), Radierer, Marker, Papierarten
- Seitentaste: Radierer, Werkzeugring um die Stiftspitze, automatisches Speichern

---

## Installation auf dem Tablet

1. Die APK aus dem Chat nehmen (lokal gebaut, fester Schlüssel). Das GitHub-Artefakt hat einen
   anderen Schlüssel und taugt nur zum Ausprobieren.
2. APK aufs Tablet kopieren und antippen. Einmalig „Installation aus dieser Quelle erlauben“.
3. **Einmalig:** Eine Version vor 0.3.1 muss vorher deinstalliert werden (anderer Schlüssel).
   Ab 0.3.1 installiert sich jede neue Version einfach darüber, Hefte bleiben.
4. Die alte App (V15) bleibt daneben installiert (andere Paket-ID). Übernahme: *Sichern → Aus V15 übernehmen*.
