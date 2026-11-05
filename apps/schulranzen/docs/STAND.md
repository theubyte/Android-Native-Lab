# Schulranzen – Stand und offene Punkte

Diese Datei ist die **eine Liste**, damit nichts verloren geht. Nach jeder Arbeitssitzung wird sie
aktualisiert: Erledigtes wandert nach „Erledigt“ (mit Version), Neues kommt unter „Offen“.
Plan und Design im Detail: [PLAN.md](PLAN.md). Regeln für parallele Agenten: [AGENTS.md](AGENTS.md).

Legende: `[x]` erledigt · `[ ]` offen · `[~]` teilweise · **(T)** nur auf dem echten Tablet prüfbar

Letzte Aktualisierung: 05.11.2026, Version **0.5.1**


---

## Offen: Runde 4 (nach 0.5.0)

### Wartet auf dich
- [ ] **Lerninhalte:** Die Bücher liegen auf dem NAS unter `/mnt/user/appdata/shk-lernwerk/public/books/`; Inhaltspakete daraus sind noch nicht gebaut.
      Daraus entstehen Inhaltspakete für Android und PWA. Buchinhalte nie ins öffentliche Repo; vor Verkauf oder öffentlichem Angebot Rechte beim Verlag klären.
- [ ] **Auf Tablet und Handy anmelden:** Sichern → Unraid-Server, Adresse `192.168.2.5:8788`, dein Konto. Danach läuft der Live-Abgleich von selbst. Empfehlung: `REGISTRATION=closed` am Server setzen.
- [ ] **PWA-Subdomain:** Name fehlt. Bis dahin nur `http://192.168.2.5:8850` (ohne Offline und Installation).
- [ ] **`shk-lernwerk` liefert die kompletten Buch-PDFs über Port 24568 aus.** Prüfen, ob das von außen erreichbar ist.
- [ ] **Erstes Update veröffentlichen:** Freigabe für `tools/release.sh` und GitHub-Release, siehe [UPDATE.md](UPDATE.md). Bis dahin meldet der Update-Knopf HTTP 404. Das Repo ist öffentlich, die APK wäre für alle ladbar.
- [ ] **Ausbilder liest gegen:** Liste in [audit/rechnen.md](audit/rechnen.md) (Trinkwasser, Abwasser, Stahl-Schellenabstände, MAG).
- [ ] **Vor Weitergabe an andere:** rechtliche Prüfung (Minderjährige, Datenschutztext ohne Anbieterangaben, Anthropic-Bedingungen). Entwürfe in `docs/store/`.

### Nur auf echter Hardware prüfbar
- [ ] **Handy:** Dokumentenscanner von ML Kit (im Emulator: „Something went wrong“, weil dort Play-Modul/Kamera fehlen), Teilen an die App aus der Galerie.
- [ ] **Tablet:** S Pen, Hand-Ablage, Zwei-Finger-Zoom, Handschrift-Modell (Als Text, Kreis-Ausrechnen, Handschriftsuche), neue Stift-Favoriten.
- [ ] **Live-Abgleich auf echten Geräten:** auf Emulatoren mit dem echten Server-Container geprüft, nicht auf Tablet und Handy, nicht mit dem Live-Konto.
- [ ] **Schwarze Blätter auf dem echten Tablet gegenprüfen:** Im Emulator erschien das Blatt leer und grau, nicht schwarz; dieselbe Ursache ist wahrscheinlich, aber am Gerät noch nicht bestätigt.
- [ ] **Update von einer Version zur nächsten:** echte Installation, Abweisung fremder Signatur, Rückkehr aus „Unbekannte Apps“.
- [ ] **Erinnerungen:** Benachrichtigung und Berechtigungsabfrage.
- [ ] **Claude mit echtem Schlüssel:** Streaming, Antwort bewerten, Fragen aus Text (nur Ablehnung mit ungültigem Schlüssel geprüft).
- [ ] **PWA auf iPhone/iPad/Safari:** gar nicht geprüft, nur Chrome.

### Bekannte Grenzen
- Prüfungsreife und Tagesplan beruhen auf plausiblen Schätzwerten, nicht auf echten Prüfungsergebnissen.
- Lernpfad und Prüfungsseite zeigen nur den ersten Stapel eines Fachs; Plan und Prognose zählen alle.
- Gelöschte Bücher und Lerndaten kommen beim Abgleich zurück. Buch-Anmerkungen ohne Konfliktkopie.
- Große Hefte laden beim Öffnen alle Striche. Seite löschen ist endgültig.
- Buchsuche: „Waerme“ findet nicht „Wärme“. Weitere Grenzen in den Prüfberichten.
- Texte vereinheitlichen (Anrede, Sicherung/Backup): Liste in [audit/release.md](audit/release.md).

---

## Offen

### 1. Vor dem Alltagseinsatz (Priorität hoch)
- [x] Fester Signaturschlüssel, damit Updates die Hefte behalten (0.3.1, nur lokal)
- [ ] Optional: Schlüssel als GitHub-Secret hinterlegen, damit auch CI-APKs passen
      (braucht dich: Secret im GitHub-Webinterface anlegen)
- [ ] **(T)** Erste Installation auf dem Tab S7 FE (Anleitung unten)
- [ ] **(T)** S Pen: Druck, Neigung, Verzögerung, Schreibgefühl
- [ ] **(T)** Hand-Ablage beim Schreiben
- [~] **(T)** Seitentaste im Schweben → Werkzeugring. **Ergebnis vom Tablet:** Air Command fängt die Taste ab.
      Samsung erlaubt Apps nicht, das abzuschalten. **Du:** Einstellungen → Erweiterte Funktionen → S Pen →
      Air Command → „Air Command mit Stifttaste öffnen“ aus (Knopf dafür im Heft unter … und einmaliger Hinweis). Danach testen.
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

### 0.5.1 – Blätter und Live-Abgleich (05.11.2026)
- **Leere/schwarze Blätter behoben:** Android verwirft die aufgezeichneten Seiten im Hintergrund, bei Speichermangel oder außerhalb des Bildes; die App zeichnete danach eine leere Seite. Daten waren nie betroffen. Sie prüft das jetzt und zeichnet neu, auch beim Buch. Im Emulator nachgestellt (Home + Speicherfreigabe) und nach der Korrektur erneut geprüft.
- **Live-Abgleich:** Solange die App offen ist alle 30 Sekunden, beim Schließen eines Hefts sofort, beim Verlassen der App noch einmal. Standardmäßig an, sobald angemeldet; „nur im WLAN“ gilt auch hier. In ein offenes Heft kommt nichts hinein, es zeigt sich beim nächsten Öffnen.
- **Server** auf API 2 aktualisiert; Sicherung unter `/mnt/user/appdata/digitaler-schulranzen-sync-backup-*`, Konto und Daten blieben.
- **PWA** als Container `schulranzen-web` auf Port 8850 (`apps/schulranzen-web/deploy/`).
- Geprüft mit zwei Emulatoren gegen einen getrennten Testserver: Heft, Prüfung und Scan kamen ohne Knopfdruck an; Tablet schreibt und Handy scannt ins selbe Heft: Strich und neue Seite blieben erhalten.

### 0.5.0 – Runde 3: geprüft, ausgebaut, Handy (05.11.2026)
Acht Agenten, Prüfberichte in [docs/audit/](audit/). Entscheidung: erstmal kein Play Store, Verteilung als APK mit Update-Funktion.
- **Lernen:** Heute-Plan über alle Prüfungen, Prüfungsreife mit Note und „was fehlt“, Fragearten (Umdrehen, Eintippen, Auswahl, Lücke, Zuordnen, Gemischt), Probeprüfung mit Timer, Nachlernrunde und Verlauf, Fragen aus Text, Fehlerheft, Fokus-Timer, Statistik, Erinnerungen, TSV/CSV, Inhaltspakete, Erklärhilfe mit Streaming (Standard: sparsames Modell). SM-2 und Merge korrigiert.
- **Handy (kürzeste Seite < 600 dp):** untere Leiste Lernen, Hefte, Bücher, Scannen, Sichern. Hefte und Bücher nur lesen. Scannen mit Prüfen, Umsortieren, Zielheft. Kein SHK.
- **Heft:** 50 Funde, vor allem Datenverlust und Speicher behoben. Seiten umsortieren/kopieren/verschieben, Hefte duplizieren/zusammenführen/als PDF teilen, Stift-Favoriten, neue Papiere.
- **Bücher:** 36 Funde. Lesemodus Handy, Bild- und Textkarten aus Buchstellen, Nachtmodus, Weiterlesen.
- **Sichern:** Zusammenführen pro Seite (Dreiwege, Konfliktkopie, Grabsteine), „Jetzt abgleichen“ mit Teilübertragung, Server API 2, Abgleich per Datei, Wiederherstellung übersteht Prozessende. http nur noch im Heimnetz.
- **Rechnen:** 108 Einträge fachlich geprüft: 45 richtig, 22 korrigiert, 31 vereinfacht und 10 nicht belegbar (beide als „Richtwert“ markiert). Rechenkern-Fehler behoben, Übungsaufgaben zu allen 36 Formeln, Rohrknicke, T-Stücke.
- **Release:** APK von rund 100 MB auf 22,3 MB (arm64) dank R8 und ABI-Trennung. Absturzbericht nur lokal, Einführung, Über-Bildschirm mit Lizenzen und Datenschutz, Update-Funktion, CI mit Lint.
- **PWA** `apps/schulranzen-web`: Lernen im Browser, offline, ohne Konto. In Chrome getestet (174 Tests), nicht auf iOS.
- **Im Release-Build auf Emulatoren geprüft** (x86_64, R8):
  - Handy 360 dp: alle Reiter, Bild aus Galerie → Prüfen → neues Heft → Lesen (Strich zeichnet nichts), Paket-Import, Lernsitzung mit allen Fragearten, Prüfung anlegen, Probeprüfung mit Auswertung, Über, Lizenzen, Update-Fehlermeldung, Absturz-Angebot, Erklärhilfe mit ungültigem Schlüssel (saubere Meldung, also läuft das SDK trotz R8).
  - Tablet: Update von 0.4.2-Daten (Hefte, Bücher blieben), Schreiben und erneut öffnen, SHK, Buch öffnen.
  - Dabei gefunden und behoben: Absturz bei „Eintippen“ (Regex, die nur auf der JVM gilt), R8-Fehler durch das SDK, rohe `{{c1::…}}` in Antworten, Zurück-Taste im Update-Hinweis.
  - Nicht durchgespielt: Sichern/Abgleich und Einführung auf dem Tablet, Zuordnen, Fokus-Timer, Statistik, Stundenplan, dunkles Thema.

### 0.4.2 – Tablet-Rückmeldung
- Taskleiste/Navigationsleiste des Tablets ausgeblendet (vom unteren Rand wischen zeigt sie kurz) ([Bild](screens/heft-vollbild.png)).
- Werkzeugleiste im Heft: wird bei wenig Höhe kompakter und scrollt notfalls – „…“ (Mehr) ist immer erreichbar.
- Buch: Bereichsleiste (Ins Heft, Merken, Prüfung, Abbrechen) lag übereinander – jetzt nebeneinander ([Bild](screens/buch-bereichsleiste.png)).
- Air Command: einmaliger Hinweis im Heft + Knopf „S-Pen-Einstellungen öffnen“ (unter … → Stift). Abschalten kann nur der Nutzer.
- Bibliothek: kein kurzes „Hefte 00“ mehr beim Start; Titel bricht bei großer Anzeigegröße nicht mehr um.
- Im Emulator geprüft (auch mit großer Anzeigegröße). Air-Command-Hinweis nur auf Samsung sichtbar → **(T)**.

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
