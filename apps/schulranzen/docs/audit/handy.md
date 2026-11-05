# Prüfbericht Agent „handy“ (Runde 3)

Stand: Branch `agent/handy`, gegründet auf `ecff577`. `:app:assembleDebug` und `:core:test` grün.
**Auf keinem Gerät gelaufen** (kein Emulator erlaubt, kein Handy angeschlossen). Alles unten außer den
JUnit-Tests ist per Code-Lesen geprüft, nicht im Betrieb.

## Gebaut

- **Handy-Oberfläche** (`app/.../phone/`): Untere Leiste Lernen (Start), Hefte, Bücher, Scannen, Sichern.
  Kein Rechnen/SHK, keine Werkzeugleiste, kein Ring, kein Taschenrechner: `NotebookScreen` wird dort nie aufgebaut.
- **Heft lesen** (`PhoneNotebookScreen`): dieselbe `NotebookCanvasView`, `readOnly = true`. Wischen mit Nachgleiten,
  Zwei-Finger-Zoom, Doppeltipp-Zoom, Seitenübersicht (nur ansehen und springen), Lesezeichen, „Für Prüfung merken“.
- **Scannen** (`app/.../scan/`): ML Kit Document Scanner 16.0.0; Rückfall Kamera-App, Galerie, PDF.
  Prüfen (Reihenfolge, Drehen, Entfernen, Großansicht mit Zoom), optional Aufhellen/Schwarzweiß,
  Ziel wählen oder neues Heft, Ende oder hinter Seite n, Bestätigung mit Sprung ins Heft.
- **Teilen** an die App (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`; Bild, PDF), auf beiden Geräteklassen.
- **Tablet** (klein): Taste „Blätter scannen“ in der Bibliothekskopfzeile, Eintrag im Heft unter Mehr → Arbeitsblatt.
- **Reine Logik** mit Tests in `core/.../core/scan/`: `ScanPlan` (10 Tests), `ScanEnhance` (7 Tests).

## readOnly geprüft

`onTouchEvent` geht bei `readOnly` sofort in den Lesezweig; damit sind `beginPen`, Radierer, Lasso, Lineal, Text,
Auswahl-Ziehen und `fingerDraws` unerreichbar. Stift scrollt wie ein Finger. `handleHover` und
`dispatchGenericMotionEvent` (Canvas und Activity) sperren Hover, Seitentaste und Ring. `commit` und `select` sind
zusätzlich gesperrt. Tastenkürzel gibt es im Code keine (`KeyEvent`/`onKeyDown` kommt nicht vor).
Leere Seiten beim Blättern: `addPage` wird nur vom Plus-Knopf in `NotebookScreen` und von `insertWorksheets` gerufen,
Blättern und `scrollToPage` legen nichts an.

## Gefunden und behoben

| Fund | Behebung |
|---|---|
| Neuer Heftbildschirm kommt hoch, bevor der alte beim Ausblenden entsorgt ist: `close()` des alten nahm dem neuen Listener und Sichtbarkeit, wartende Seiten des alten gingen verloren | `BindCanvas` in `MainActivity`: alte Sitzung vor dem Öffnen schließen, beim Entsorgen nichts wegnehmen |
| `scrollToPage` vor dem ersten Layout ging verloren (Sprung aus Lernen beim Kaltstart landete auf Seite 1) | `pendingPage` in der Canvas |
| Beim Lesen vieler Scans hielten die Display-Listen alle Bilder fest (OOM auf Handys) | nur bei `readOnly`: Listen fern der Sicht werden freigegeben |
| Taskleiste wurde auch auf dem Handy ausgeblendet | nur noch Tablet, auch beim Auf-/Zuklappen eines Foldables |
| `singleTop`: ein Teilen hätte eine zweite Activity im Task der anderen App geöffnet (zweite Canvas, zweite Sitzung) | `singleTask` |
| Sprung zu einem gelöschten Heft zeigte auf ewig einen leeren Bildschirm | Handy: Hinweis „Heft nicht gefunden“ |

## Absichtlich so

- Share-Eingang nimmt nur `content://` fremder Anbieter: `file://` oder der eigene Provider könnten sonst private Dateien der App einlesen.
- Entwurf liegt in `noBackupFilesDir/scan-draft`, mit Reihenfolge in `draft.json`: übersteht Prozessende, kommt nicht in Sicherungen, verwaiste Bilder entstehen nicht.
- Launcher sitzen über der Navigation (`rememberScanController`), damit ein Ergebnis auch nach Prozessende hinter der Kamera ankommt.
- Lernen bleibt komponiert (nur nicht platziert), damit der Rücksprung aus einem Heft wieder bei derselben Prüfung landet.
- Handschriftsuche: auf dem Handy nur, falls das Modell dort liegt. Es wird dort nicht angeboten, darum sagt das Suchfeld nur „Hefte suchen“.

## Offen, nicht in meinem Bereich

**heft** (`NotebookSession`, `Library`). Gemeldet auf Stand `ecff577`; die Arbeitskopie von heft behebt laut Durchsicht schon den Ladewettlauf, das falsche `lastPage` und den Absturz bei leerem Heft. Beim Zusammenführen prüfen:
1. Dort legt `open()` für ein Heft ohne Seiten eine leere Seite an (`library.addPage`). Das ist auch im Lesemodus ein Schreibzugriff und widerspricht „keine leeren Seiten“. Auf dem Handy sollte das unterbleiben.
2. Dort kommt `session.missing` dazu. `PhoneNotebookScreen` prüft bisher selbst `library.notebook(id)` und dreht bei einem Ladefehler sonst endlos. Beim Zusammenführen `session.missing` mit auswerten.
3. `Library.loadPages` dekodierte alle Bilder vorab, das sah bei heft entfernt aus. Falls nicht: bei 50 Scans dauert das Öffnen Sekunden.
4. `NotebookScreen`: `cameraUri` mit `remember` statt `rememberSaveable`, geht nach Prozessende hinter der Kamera verloren.

**lernen**: `StudyHome` zeigt auf der Startseite einen Zurück-Knopf. Auf dem Handy ist Lernen der Start; bis das weg ist, führt er zu Hefte. Der Stapel von `StudyHome` ist `remember`, ein Prozessende führt zurück zur Lernen-Startseite.

**sichern**: siehe Abschlussbericht; heute gewinnt beim Zusammenführen das ganze Heft mit dem neueren `updatedAt`, Handy-Scans könnten Tablet-Seiten überschreiben.

## Nicht geprüft (braucht ein Gerät)

Echter Scanner-Ablauf, Rückfall ohne Play-Dienste, Teilen aus anderen Apps, Prozessende hinter der Kamera, Nachgleiten
und Doppeltipp, Layout bei 360 dp, dunkles Thema, Tablet-Einstiege, Drehung.
