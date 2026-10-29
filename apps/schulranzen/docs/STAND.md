# Schulranzen – Stand und offene Punkte

Diese Datei ist die **eine Liste**, damit nichts verloren geht. Nach jeder Arbeitssitzung wird sie
aktualisiert: Erledigtes wandert nach „Erledigt“ (mit Version), Neues kommt unter „Offen“.
Plan und Design im Detail: [PLAN.md](PLAN.md). Regeln für parallele Agenten: [AGENTS.md](AGENTS.md).

Legende: `[x]` erledigt · `[ ]` offen · `[~]` teilweise · **(T)** nur auf dem echten Tablet prüfbar

Letzte Aktualisierung: 29.10.2026, Version **0.4.1**


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
- [~] **(T)** Unraid-Sync mit dem echten Server: Anmelden klappt, Sichern/Laden scheiterte.
      Server-Fehler behoben (Secret-Race zwischen zwei Workern → 401 nach Login). **Du:** Server
      aktualisieren (Befehl unten) und erneut testen.

### 2. Aus der alten App (V15) noch nicht da
- [x] Buch und Heft nebeneinander (Split mit verschiebbarem Trenner) – 0.4.0
- [x] Kreis um eine Rechnung → Ergebnis erscheint daneben (abschaltbar, braucht das Handschrift-Modell) – 0.4.0 **(T)**
- [x] Prüfungsmarkierungen aus Heft (Auswahl oder Seite) und Buch; aus Lernen zurückspringen mit Markierung – 0.4.0
- [x] Lineal als echtes verschieb- und drehbares Lineal auf dem Blatt – 0.4.0 (Drehen mit zwei Fingern nur auf dem Tablet prüfbar)
- [x] Werkzeugnamen anzeigen, wenn der Stift über der Werkzeugleiste schwebt – 0.4.0 **(T)**

### 3. Lücken in den neuen Bereichen
- Heft
  - [x] Lasso über Seitengrenzen: Auswahl auf andere Seite ziehen – 0.4.0 (im Emulator nicht prüfbar)
  - [x] Formerkennung: am Strichende kurz halten → Linie/Pfeil/Kreis/Rechteck/Dreieck – 0.4.0 **(T: Schwellen am Tablet einstellen)**
  - [x] Handschrift → Text (Lasso → Lernen → Als Text) – 0.4.0 **(T)**
  - [x] Suche in der Handschrift (Bibliothekssuche, Treffer springt zur Stelle) – 0.4.0 **(T)**
  - [x] Neues Heft direkt aus PDF anlegen („Heft aus PDF“) – 0.4.0
- Bücher
  - [x] Volltextsuche im Buch – 0.4.0
  - [x] Lasso und Text im Buch – 0.4.0
  - [x] Texterkennung + Inhaltsverzeichnis für gescannte Bücher – 0.4.0
  - [x] Rückgängig für alles im Buch – 0.4.0
- Lernen
  - [x] Karteikarten mit Bildern – 0.4.0
  - [x] Bildkarte aus Lasso-Auswahl im Heft – 0.4.0 (aus dem Buch: noch über Ausschnitt → Heft)
  - [x] Prüfungsstoff als Sprung-Links – 0.4.0
- Rechnen
  - [x] SHK-Rechner: Tastatur verdeckte das Ergebnis → Ergebnis steht jetzt neben den Feldern (0.3.1)
  - [x] Handschrift-Modell vorab laden (SHK-Schnellrechner, Heft → Mehr) – 0.4.0
- Sichern
  - [x] Verschlüsselte Backups (AES-256, Passwort) – 0.4.0
  - [x] Automatisches Backup (täglich/wöchentlich, Ordner und/oder Unraid) – 0.4.0 **(T)**
  - [x] Zusammenführen: Hefte, Bilder, Bücher, SHK (0.4.0), Lerndaten (0.4.1)
  - [x] Datenbanken vor dem Wiederherstellen schließen, App startet neu – 0.4.0
- Bibliothek
  - [x] Suche in der Handschrift der Hefte – 0.4.0

### 3b. Feinschliff (in 0.4.0 gesehen)
- [x] SHK-Schema: Rohrbeschriftungen weichen Symbolen, Rohren und einander aus – 0.4.1
- [x] Lern-Sheets im Split mittig über dem ganzen Bildschirm – 0.4.1
- [ ] Rohrknicke nachträglich verschieben, T-Stücke als Formteil (Schema)
- [ ] Bildkarten: Zoom/Verschieben im Bild
- [ ] Erklärhilfe mit Streaming
- [x] Lerndaten zusammenführen (nach Bedeutung: Stapel, Prüfung, Karte …; neuer gelernte Karte gewinnt) – 0.4.1 **(T: mit zwei Geräten)**
- [x] Erstindexierung aller vorhandenen Seiten, sobald das Handschrift-Modell da ist – 0.4.1 **(T)**

### 4. SHK-Ausbau (schon in V15 offen)
- [x] Digitales Aufmaß (CSV/PDF) – 0.4.0
- [x] Automatische Materiallisten – 0.4.0
- [x] Schema-Editor mit 17 Symbolen, mit Rechnern verknüpft ([Bild](screens/shk-schema.png)) – 0.4.0
- [x] 8 Auslegungsrechner, 14 Tabellen – 0.4.0 (Werte vereinfacht, vom Ausbilder gegenlesen lassen)

### 5. Zukunft (Phase 9)
- [x] Lernpfade je Prüfung – 0.4.0
- [~] Probeprüfungen aus Karteikarten mit Note und Schwächen – 0.4.0. **Offen:** Fragen direkt aus Heft-/Buchtext
- [x] Erklärhilfe mit Claude (eigener API-Schlüssel in Lernen → Einstellungen) – 0.4.0 **(T)**
- [x] Kartenvorschläge aus Text/Handschrift (offline + mit Claude) – 0.4.0

---

## Erledigt

### 0.4.1 – Feinschliff
- SHK-Schema: Rohrbeschriftungen werden neben dem Rohr platziert, wo sie weder andere Beschriftungen noch
  Symbole oder Rohre überdecken; passt nichts, bleibt das Stück unbeschriftet ([Bild](screens/shk-schema.png)).
  Silbentrennung immer deutsch (Palette: „Wärmetau-scher“).
- Lern-Sheets (Als Text, Prüfung, Karte, Erklären, Karten vorschlagen) liegen über dem ganzen Bildschirm,
  auch neben einem Buch ([Bild](screens/pruefung-merken-split.png)).
- Lerndaten beim Zusammenführen: Stapel nach Name, Prüfung nach Fach+Datum+Art, Karte nach Stapel+Vorderseite;
  die zuletzt gelernte Kopie behält ihren Wiederholungsplan; Wiederholungen, markierte Stellen, Probeprüfungen,
  Stundenplan und Kartenbilder werden ergänzt. Nichts wird gelöscht.
- Handschriftsuche: beim ersten Mal mit Modell werden alle vorhandenen Seiten indexiert (auch aus V15 übernommene).
- Sprung aus Lernen markiert die Stelle 4 s lang ([Bild](screens/sprung-mit-markierung.png)).
- Im Emulator geprüft: Schema, Sheet im Split, Merken → Lernen → Sprung mit Markierung.
  Nicht geprüft: Zusammenführen (braucht zweites Gerät), Erstindexierung (braucht Modell-Download).

### 0.4.0
- Buch und Heft nebeneinander: im Heft *Mehr → Buch daneben öffnen*; Trenner ziehen, doppelt tippen = halbe-halbe;
  Buchausschnitte landen direkt auf der sichtbaren Heftseite. Im Emulator geprüft ([Bild](screens/heft-buch-nebeneinander.png)).
- Echtes Lineal ([Bild](screens/lineal.png)): mm/cm-Skala, Finger verschieben, zwei Finger drehen (15°-Raster),
  Stift zieht an beiden Kanten gerade Linien. Verschieben und Kantenlinien im Emulator geprüft.
- Heft aus PDF in der Bibliothek.
- Werkzeugnamen beim Schweben des Stifts über der Leiste.
- Lasso-Auswahl wandert beim Ablegen auf eine andere Seite.
- Sync-Server: gemeinsames Secret für alle Worker, Timeout 600 s.
- Agenten Runde 2 eingebaut (Bücher, Lernen, Sichern, SHK, Schrift), siehe Häkchen oben. Im Emulator geprüft:
  kompakte Buchansicht neben dem Heft ([Bild](screens/buch-kompakt-nebeneinander.png)), Bildkarte aus Lasso
  ([Bild](screens/bildkarte-aus-auswahl.png)) und Lernmodus, Lernen-Startseite, SHK-Schema mit Beispiel,
  Sichern-Bildschirm ([Bild](screens/sichern-0.4.png)). Kein Absturz.
- Nur gebaut, nicht im Emulator geprüft: Handschrift→Text, Erklärhilfe, Kartenvorschläge, Formerkennung,
  Kreis-Ausrechnen (alles braucht echten Stift bzw. ML-Kit-Modell/Netz), Prüfungs-Sprünge, Zusammenführen,
  Auto-Backup, Verschlüsselung, Buch-Volltextsuche/Texterkennung.

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

## Unraid-Server aktualisieren

Im Unraid-Terminal:

```
cd /mnt/user/appdata/digitaler-schulranzen-sync && curl -fsSLO https://raw.githubusercontent.com/theubyte/Android-Native-Lab/main/apps/digitaler-schulranzen/server/unraid-sync/app.py && curl -fsSLO https://raw.githubusercontent.com/theubyte/Android-Native-Lab/main/apps/digitaler-schulranzen/server/unraid-sync/Dockerfile && docker compose up -d --build
```
Danach in der App ab- und neu anmelden.
