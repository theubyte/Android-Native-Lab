# Prüfbericht Agent „heft“ (Runde 3)

Bereich: `ink/`, `app/.../notebook/`, `app/.../library/`, `app/.../data/` (außer `BackupWiring.kt`), neu `core/.../core/notebook/`.
Zeilennummern unter „gefunden“ beziehen sich auf den Ausgangsstand `ecff577`.

**So wurde geprüft:** Code gelesen, Ink-Bibliothek per `javap` angesehen, `:core:test` und `:app:assembleDebug` gebaut.
**Nicht auf einem Gerät gelaufen:** kein Emulator, kein Tablet. Alles an Stift, Hand-Ablage, Zoom, Share-Dialog, Drag in der Seitenübersicht und Darstellung ist nur gebaut, nicht gesehen. Die Fehler sind aus dem Code belegt, nicht beobachtet.

## Geprüft

- Speichern: Debounce, Schreiben, Reihenfolge, Hintergrund, Prozessende, Rotation (`configChanges` im Manifest: keine Neuerstellung), volle Platte
- Laden: beschädigte Dateien, Wettlauf mit laufendem Speichern und mit dem Verlassen des Hefts
- Undo über Seiten und nach Seitenänderungen, gesperrte Seiten, Papierkorb samt Dateien
- Speicher: RenderNodes, Bild-Cache, große Hintergründe, Thumbnails, wenig Speicher
- Eingabe: Hand-Ablage, Finger/Stift, Zoom, Lasso bei Zoom und über Seiten, Lineal, Formerkennung (nur Aufruf; Erkenner gehört `buecher`), Text
- PDF-Import und -Export, Bildimport
- Bibliothek: Suche, Fächer, Sortierung, Umbenennen, Löschen, Wiederherstellen, leere Zustände, Start

Ohne Befund: Koordinaten von Lasso und Lineal bei Zoom (`localOf`, `toView`), Undo-Reihenfolge beim Radieren über mehrere Schritte, `PageEdit` nach Seiten-ID (überlebt Umsortieren), Zoom-Grenzen, gesamte PDF-Elemente (Papier, Bilder, Marker, Tinte, Text über `PagePainter`), Suchfilter.

## Gefunden und behoben

### Datenverlust

| # | Fundstelle | Fehler | Behebung | Test |
|---|---|---|---|---|
| 1 | `NotebookSession.kt:476`, `InkCodec.kt:25` | Zwei Saves derselben Seite liefen parallel in dieselbe `.tmp`-Datei, ein älterer konnte einen neueren überholen. | `Library.files` (fairer Mutex, Reihenfolge wie aufgerufen), Temp-Datei je Thread | Temp: JVM |
| 2 | `NotebookSession.kt:476` | `IOException` beim Speichern (Platte voll) lief ungefangen aus `scope.launch`: App-Absturz, alles Ungespeicherte weg. | Fangen, Meldung, Seite bleibt vorgemerkt, Wiederholung nach 5–60 s | Meldung: JVM |
| 3 | `Library.kt:115-116` | Unlesbare Seite wurde still zur leeren Seite, das nächste Speichern überschrieb sie. Auch bei Speichermangel, weil `runCatching` `OutOfMemoryError` fängt. | Lesbares wird geladen, Kopie `.damaged` bleibt, Meldung; ganz unlesbar: Seite bleibt im Speicher gesperrt | Format: JVM |
| 4 | `InkCodec.kt:48-65` | Ein abgeschnittenes Ende kostete die ganze Seite; `ArrayList(n)` und `ByteArray(n)` vertrauten Längen aus der Datei (Absturz bei Müll). | `InkFileFormat.parse` liest bis zur Schadstelle, prüft Längen gegen die Dateigröße | JVM |
| 5 | `InkCodec.kt:27,42-44`, `Items.kt:81-83` | Kein `fsync` vor dem Umbenennen (nach Stromausfall leere Seite möglich); bei Fehler wurde das Original vorher gelöscht, `ItemCodec` scheiterte still. | `SafeFile`: sync, `ATOMIC_MOVE`, Original bleibt bei jedem Fehler | JVM |
| 6 | `MainActivity`-Pfad: keine Sicherung bei `ON_PAUSE` | Home oder Wischen aus den Letzten innerhalb von 700 ms: Striche nur im Speicher. | `NotebookSession.onPause()` über `LifecycleEventEffect` in `NotebookScreen` | – |
| 7 | `NotebookCanvasView.kt:882`, `NotebookSession.kt:128-133` | Strich gerade abgesetzt, sofort raus: Übergabe kam nach `listener = null`, nie gespeichert. | `canvas.settleStrokes()` vor dem Schließen und bei Pause | – |
| 8 | `NotebookSession.kt:472-473` | Save fand die Seite über `canvas.pageById`; war schon ein anderes Heft geladen, verschwand er still. | Save hält die Seite selbst | – |
| 9 | `NotebookSession.kt:132` | Altes Heft blendet aus und schließt nach dem Öffnen des neuen: `listener = null` nahm dem neuen Heft das Speichern. | Nur der eigene Listener wird entfernt; die Sichtbarkeit regelt `BindCanvas` aus `agent/handy`. | – |
| 10 | `NotebookSession.kt:114-124` | Laden nicht abbrechbar: Heft A verlassen, B geöffnet, dann zeigte die Leinwand A in der Sitzung von B. | `loadJob`, Abbruch in `close()` | – |
| 11 | `NotebookSession.kt:130` | Verlassen vor Ladeende setzte `lastPage` auf 0. | Nur wenn geladen | – |
| 12 | `Library.kt:113` | Schnell wieder öffnen las die alte Datei, solange das Speichern lief; nächstes Speichern überschrieb die letzten Striche. | Lesen wartet auf laufende Saves | – |
| 13 | `Library.kt:101-104` | `deleteForever`: erst Dateien, dann DB, im abbrechbaren Scope. Abbruch ließ ein Heft ohne Tinte zurück. | Erst DB, dann Dateien, `NonCancellable` | – |
| 14 | `NotebookCanvasView.kt:463-484` | Auswahl neben dem Blatt abgelegt: gespeichert, aber unsichtbar und mit dem Lasso nicht mehr erreichbar. | Mitte wird aufs Blatt zurückgeholt | JVM |
| 15 | `Library.kt:235-236` | Ausschnitt ins Heft: unlesbare Objekte wurden als „leer“ überschrieben; ohne `ink/`-Ordner (frische Installation) Absturz. | Neue Seite statt Überschreiben; `SafeFile` legt den Ordner an | Ordner: JVM |
| 16 | `Library.kt:96-98,93-94` | Umbenennen und Papierkorb schrieben eine alte Kopie der ganzen Zeile zurück (Seitenzahl, `lastPage`). | Einzelne Felder per Query | – |

### Gesperrte Seiten

| # | Fundstelle | Fehler | Behebung |
|---|---|---|---|
| 17 | `NotebookCanvasView.kt:226-227` | Undo und Redo änderten gesperrte Seiten. | Prüfung vor dem Schritt, Hinweis |
| 18 | `NotebookCanvasView.kt:349-354,454-461,380,578-597,621` | Lasso ist auf gesperrten Seiten erlaubt, danach gingen Löschen, Ausschneiden, Einfärben, Verschieben, Skalieren. | Kopieren bleibt erlaubt, alles andere nicht; zusätzlich Sperre in `commit()` |
| 19 | `NotebookSession.kt:409` | Gesperrte Seite ließ sich löschen. | Erst entsperren |
| 20 | `NotebookCanvasView.kt:865` | Radierer auf gesperrter Seite tat still nichts. | Hinweis wie bei den anderen Werkzeugen |

### Speicher

| # | Fundstelle | Fehler | Behebung |
|---|---|---|---|
| 21 | `NotebookCanvasView.kt:1025` | `nodes` wuchs mit jeder gesehenen Seite, und jede Display-List hält ihre Bitmaps fest, am Bild-Cache vorbei. Bei 100 Arbeitsblättern bis etwa 1,5 GB. | Nur sichtbare Seiten ±2 behalten, Rest `discardDisplayList` |
| 22 | `Library.kt:117-118` | Beim Öffnen wurden alle Bilder des Hefts dekodiert (mehr als der Cache fasst, also umsonst), danach beim Scrollen auf dem Main-Thread. | Dekodieren im Hintergrund beim ersten Zeichnen; die Seite zeichnet sich danach einmal neu |
| 23 | `Items.kt:124-125` | `OutOfMemoryError` beim Dekodieren stürzte ab. | Cache leeren, halbe Auflösung, sonst ohne Bild |
| 24 | `PageOverview.kt:118` | Thumbnail-Fehler (wenig Speicher) stürzte im `produceState` ab. | Fehler kostet nur das Bildchen |
| 25 | `Library.kt:158-160` | Sehr schmale PDF-Seite verlangte ein riesiges Bitmap. | Höhe begrenzt |

### Falsche Darstellung und Abstürze

| # | Fundstelle | Fehler | Behebung |
|---|---|---|---|
| 26 | `Items.kt:101-105` | Alle `StaticLayout` teilten ein `TextPaint`, und ein Layout zeichnet mit seinem Paint: Mehrere Texte erschienen in Farbe und Größe des zuletzt gebauten. | Eigenes Paint je Layout |
| 27 | `PagePainter.kt:16` | Ein gemeinsamer `CanvasStrokeRenderer` lief auf Main, Default (Thumbnails) und IO (PDF). Laut `javap` hat er ungeschützte `WeakHashMap`s und Scratch-Arrays. | Ein Renderer je Thread |
| 28 | `NotebookSession.kt:373`, `PdfExporter` | PDF-Export las die Live-Listen auf IO; Weiterschreiben konnte `ConcurrentModificationException` auslösen. | Momentaufnahme |
| 29 | `NotebookSession.kt:115` | Link auf ein gelöschtes Heft (Suche, Lernen): toter Bildschirm ohne Seiten. | Meldung, zurück zur Bibliothek |
| 30 | `NotebookSession.kt:121-122` | Heft ohne Seiten: `coerceIn(0, -1)` wirft bei Sprung mit Markierung. | Seite wird angelegt |
| 31 | `NotebookCanvasView.kt:221-224,264-267` | Sprung vor dem ersten Layout ging verloren: Suchtreffer oder letzte Seite öffnete auf Seite 1. | Nach dem Layout nachgeholt |
| 32 | `NotebookSession.kt:285` | Text am rechten Rand ragte über das Blatt und wurde abgeschnitten. | Rückt nach innen |
| 33 | `NotebookSession.kt:305-306` | Hohes Foto über das Blatt hinaus und abgeschnitten; Mitte stammte von einer evtl. anderen Seite. | `PagePlacement.imageBox` |
| 34 | `Library.kt:212` | PNG mit Transparenz wurde als JPEG schwarz hinterlegt. | Weiß hinterlegt |
| 35 | `Library.kt:164,212` | `compress`-Fehler und `IOException` beim Bild: leere Datei bzw. Absturz. | Prüfung, verständliche Meldung |
| 36 | `Library.kt:152-173` | PDF scheitert mittendrin: Bilder blieben als Waisen liegen. | Aufräumen |
| 37 | `NotebookSession.kt:352`, `LibraryScreen.kt:155` | PDF mit Passwort oder kaputt: technische Meldung („cannot create document“). | `UserMessages.ofPdf` |
| 38 | `Library.kt:243` | Fehlgeschlagener Export ließ eine halbe Datei am Ziel. | Erst fertig bauen, sonst Ziel-Dokument entfernen |
| 39 | `Library.kt:100-101` | Papierkorb entfernte Bilder nie (auch nach 30 Tagen), Handschrift-Index behielt Treffer auf gelöschte Hefte. | `releasePictures`: nur Bilder des Gelöschten, die weder eine andere Seite noch ein Buch nutzt; Index über `onNotebookGone` |

### Eingabe

| # | Fundstelle | Fehler | Behebung |
|---|---|---|---|
| 40 | `NotebookCanvasView.kt:556,586-588` | Hand aufgelegt nach dem Lasso: Treffer außerhalb löschte die Auswahl, innerhalb verschob sie. | Finger bei nahem Stift zählt nicht |
| 41 | `NotebookCanvasView.kt:946` | Hand hochgenommen, nachdem der Stift geschrieben hatte: `ACTION_UP` galt als Tipp und löschte die Auswahl. | Nur wenn die Geste als Tipp begann |
| 42 | `NotebookCanvasView.kt:564` | Mit „Mit dem Finger schreiben“ zeichnete die Hand auch bei schwebendem Stift. | Prüfung auf Stiftnähe |
| 43 | `NotebookCanvasView.kt:563-564` | Mit derselben Einstellung ließ sich gar nicht scrollen oder zoomen, und der Stift kam nicht durch, solange ein Finger lag. | Zweiter Finger innerhalb 400 ms übernimmt als Zoom, Stift übernimmt immer |
| 44 | `NotebookCanvasView.kt:884-902` | Zwei Striche im selben Übergabe-Bündel waren ein Undo-Schritt. | Je Strich ein Schritt |
| 45 | `InkHistory`, `NotebookCanvasView.kt:198-203` | Nach dem Löschen einer Seite verbrauchte Undo Schritte ohne Wirkung. | `history.forget` |
| 46 | Lineal, `NotebookCanvasView.kt:258-272` | Nach Drehen oder schmalerem Bereich konnte das Lineal außer Reichweite liegen. | Beim Größenwechsel eingefangen |
| 47 | `NotebookCanvasView.kt:905-950` | Text ließ sich per Finger nicht setzen (ohne Stift unbenutzbar). | Fingertipp mit Textwerkzeug |
| 48 | `NotebookSession.kt:387` | „Neue Seite“ hinter einem Arbeitsblatt wurde blanko statt in der Papierart des Hefts. | `defaultPaper` |
| 49 | `NotebookSession.kt:447` | Papierwahl auf einem Arbeitsblatt tat nichts. | Hinweis |
| 50 | `NotebookSession.kt:339-342` | Teilweise unlesbare Fotos fielen still weg. | „2 von 5 Bildern …“ |

### Härtung ohne belegten Ausfall

- `NotebookCanvasView.kt:135`: Strich-IDs aus der Laufzeit seit Neustart (Treffer auf gespeicherte IDs sehr unwahrscheinlich), jetzt Uhrzeit.
- `Library.kt:124-125`: Objektdatei wird vor der Tintendatei geschrieben, damit „Handschrift → Text“ beim Absturz dazwischen beides behält.

### Nach Zusammenführung mit `agent/handy`

- `session.open()` legt im Lesemodus keine Seite an, auch ein Heft ohne Seiten öffnet dort ohne Absturz.
- `PhoneNotebookScreen` wertet `session.missing` aus: kein endloser Ladekreis bei Ladefehlern.
- `NotebookScreen.cameraUri` ist `rememberSaveable`, und ein so zurückgekommenes Foto wartet auf das Laden des Hefts.
- Textsetzen per Finger ist im Lesemodus aus; `setPages` beendet ein laufendes Gleiten.
- Display-List-Begrenzung gilt für Tablet und Handy (ersetzt die reine `readOnly`-Fassung); Sprung vor dem Layout läuft über `pendingPage`.

## Ausbau

- **Seitenübersicht:** Blatt lang halten und ziehen (zusätzlich Vor/Zurück), Kopie, „Heft …“ verschiebt in ein anderes Heft (samt Suchindex), „Schließen“.
- **Hefte (lang drücken):** Duplizieren, Zusammenführen (Kopie hinten anhängen, Quelle 30 Tage im Papierkorb), Als PDF teilen; Teilen auch im Heft unter Mehr.
- **Stift:** Favoriten (max. 8, Tipp benutzt, lang drücken entfernt), zuletzt benutzte Farben (auch im Werkzeugring und bei Auswahl/Text), mehr Farben, eigene Farbe (Regler).
- **Papier:** neu „Eng liniert“ und „Millimeter“; „Dieses Papier für alle Seiten“.
- **Bibliothek:** Sortierung (zuletzt, Name mit deutscher Reihenfolge, neu), Fach bearbeiten und löschen (Hefte bleiben).
- **Kopieren über Hefte:** Die Lasso-Kopie bleibt beim Heftwechsel erhalten.
- **Meldungen:** `UserMessages` statt Technik oder Schweigen; Hinweis „Heft wird geöffnet …“ bei großen Heften; längere Meldungen bleiben länger stehen.

## Barrierefreiheit

- Ziele jetzt ≥ 48 dp: Werkzeugleiste (auch bei wenig Höhe, sie scrollt dann), Farben, Dicken, Listenzeilen, Seitenaktionen, Suche-leeren, Fach-Knöpfe, Einfügen.
- Namen für Vorleser: Farben, Favoriten, Dicken, Seiten-Thumbnails, Fach-Knöpfe, Heft-Cover (Aktionsnamen), Schreibfläche. Meldungen als Live-Region.
- Kontrast: `inkFaint` als Textfarbe (Platzhalter, Datum; 2,3:1 hell, 2,8:1 dunkel) in meinen Bildschirmen durch `inkMuted` (4,9:1 / 6,0:1) ersetzt.

## Leistung

- Ein neuer Stiftstrich zeichnet nicht mehr die ganze Seite neu auf: kleine zweite Display-List obendrauf (max. 32 Striche, dann einmal voll). Voll neu bei Marker (liegt unter der Tinte) und auf Seiten mit Text (liegt über der Tinte). Der Schreibpfad selbst (`penEvent`, Front-Buffer) ist unverändert.
- Kreiserkennung läuft nur noch für Striche ab 30 Einheiten (normale Schrift kostet nichts).
- Seiten werden zu viert parallel geladen, Bilder erst beim Zeigen.
- Bibliothek: nur Sortierung ergänzt, keine Datei- oder Bildarbeit beim Start.
- **Gemessen ist nichts**: weder Latenz noch Ladezeit, das braucht das Tablet.

## Tests

`core/.../core/notebook/`: `NotebookFilesTest` (Atomarität, Fehler lässt Original, Kopien, beschädigte und riesige Längen, Meldungen, Waisen-Bilder), `NotebookLogicTest` (Platzierung, Favoriten, Farben, Sortierung, Dateinamen).
Canvas, Session, Room-Queries und Bibliothek haben keine automatischen Tests: Sie brauchen Android.

## Offen

- **Große Hefte laden alle Striche beim Öffnen** (`Library.loadPagesChecked`). Parallel ist es schneller, aber Plan §2 („lazy laden“) ist nicht erfüllt. Umbau betrifft Canvas, Suche, Export; bewusst nicht in dieser Runde.
- **Lern-Links zeigen auf den Seiten-Index** (`NotebookSession.linkDraft`): Nach Umsortieren, Einfügen oder Verschieben springen sie auf die falsche Seite. Das gab es schon, Umsortieren macht es häufiger. Braucht `pageId` in `StudyLinkDraft` (Agent `lernen`).
- Hand-Ablage nutzt nur die Stiftnähe (400 ms). Eine Hand ohne Stift in der Nähe scrollt noch. `FLAG_CANCELED` und Kontaktgröße sind nicht ausgewertet: am Tablet entscheiden.
- Seite löschen ist endgültig (zweistufige Bestätigung), ohne Papierkorb für Seiten.
- Ziehen in der Seitenübersicht scrollt nicht am Rand; für weite Wege: mehrfach ziehen oder Vor/Zurück.
- Zusammengeführte Hefte: Suchtreffer auf die Quelle im Papierkorb bleiben, bis sie nach 30 Tagen gelöscht wird.
- `.damaged`-Kopien lassen sich nur per Backup oder Dateizugriff erreichen; sie werden mit dem Heft endgültig gelöscht.
- Teilen legt die PDF unter `cache/camera/teilen/`, weil `res/xml/files.xml` nur `camera/` freigibt (nicht mein Bereich).
