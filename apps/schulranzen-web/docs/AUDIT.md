# Prüfbericht Schulranzen-Web

Stand: 2026-11-05, Version 1.0.0. Alle Browser-Prüfungen liefen in **Chrome 156 (Windows, headless,
per DevTools-Protokoll gesteuert)**, die Startseite zusätzlich im Browser-Bereich der Desktop-App.
Kein Safari, kein iOS, kein Firefox, kein echtes Mobilgerät.

## Geprüft

**Rechnen wie Android.** `js/core/` ist aus dem Stand von `agent/lernen` übertragen (Logik b72f936, abgeglichen mit 73c874c: dort kamen nur `CardSuggester.plainCard` und `FocusTimer.finished` dazu, beide hier nicht übernommen, weil sie nichts an den Zahlen ändern). Die
Kotlin-Tests sind Eins zu Eins in `tests/` übernommen: `RepetitionTest`, `RepetitionAuditTest`,
`TodayPlannerTest`, `ReadinessTest`, `ErrorBookTest`, `FocusTimerTest`, `StatisticsTest`,
`AnswerCheckTest`, `QuestionMakerTest`, `CardCsvTest`, `TextQuestionsTest`, `CardSuggesterTest`,
`MockExamTest`, `LearningPathTest`. Alle bestehen. Kotlin selbst lief dabei nicht: Vergleichsmaßstab
sind die Sollwerte in den Tests, kein gemeinsamer Lauf beider Sprachen.

**Testseite** `tests/index.html`: 174 Tests, alle grün. Dazu Pakete, Sicherung, Zusammenführen, die
Offline-Liste gegen den Modulgraphen, relative Pfade, Kontraste aus der CSS-Datei, Zielgrößen.

**Durchgespielt im echten Browser**, jeweils mit Prüfung des Ergebnisses:

- Beispielpaket: Vorschau, Übernehmen; eigenes Paket per Dateiauswahl; Aktualisieren mit Erhalt des
  Lernstands; kaputte Datei wird abgelehnt.
- Lernen mit Tastatur: Leertaste, 1–4, Enter, U (Rückgängig), Esc. Alle Fragearten.
- Prüfung anlegen, Heute-Plan, Prüfungsreife, „was fehlt noch“, Lernpfad, Stoff abhaken.
- Probeprüfung: Timer, Verlassen und Fortsetzen, Selbstbewertung, Auswertung nach Thema, Nachlernrunde.
- Tab-Datei einlesen und ausgeben (Kopfzeilen wie `CardCsv.write`), Bildkarte anlegen und lernen.
- Sicherung als echter Download, Alles löschen, Wiederherstellen mit Bild, Zusammenführen.
- Fokus-Timer: läuft über Neuladen weiter, Pause nach 25 Minuten, Minuten in der Statistik.
- Offline: Kaltstart, Stapel, Schriften, Beispiel, Lizenztexte, per Netzwerksperre im Browser.
- Service Worker: Installation, Update-Hinweis, altes Paket bleibt bis zum Klick, danach Wechsel.
- Unterordner: `/schulranzen-web/` mit eigenem Scope. Chrome meldet keine Installationsfehler.
- Breite 320, 375, 768, 1440: alle Bildschirme ohne waagerechtes Scrollen, hell und dunkel angesehen.
- Fokusführung: Fokus bleibt im Paneel, Esc schließt, Fokus kehrt zurück; alle Bedienelemente benannt.
- Erklärhilfe mit **gestelltem Netz**: Aufbau der Anfrage, Einwilligung, Schlüssel nicht in der Sicherung.
- Hinweise für iOS nur durch **vorgetäuschte Browserkennung** in Chrome.

## Gefunden und behoben

| Fund | Behebung |
|---|---|
| Der Regex-Lookbehind aus Kotlin hätte in Safari vor 16.4 das ganze Modul unlesbar gemacht. | Dieselbe Prüfung von Hand; ein Test verbietet Lookbehind. |
| `\b` in JS kennt nur ASCII: Lücke „3 Stü“ statt „3“ bei „3 Stück“. | Lookahead mit `\p{L}`, eigener Test. |
| `[^]]` aus Kotlin bedeutet in JS etwas anderes. | `[^\]]`. |
| **Heute-Plan: Beim zweiten Öffnen am selben Tag kamen neue Karten nach**, weil die Quote aus den noch neuen Karten berechnet wird. Trifft auch Android. | Optionales `introducedToday`; ohne das Feld rechnet es wie Kotlin. Siehe unten. |
| Leertaste wirkungslos, solange der Fokus auf der Überschrift lag. | Tastenregel nur noch für Bedienelemente. |
| Lückenmarken `{{c1::…}}` wären in Antworten und Multiple Choice sichtbar gewesen. | Überall außer dem Lückentext bereinigt. |
| Karten eines Imports hatten dieselbe Zeit und standen in zufälliger Reihenfolge. | Zeit läuft mit, Reihenfolge wie in der Datei. |
| Der Service Worker gab bei Lizenz-Links die App statt der Datei aus. | Nur Adressen ohne Dateiendung und `.html` öffnen die App. |
| Der Knopf „Installieren“ erschien erst nach Neuzeichnen. | Bildschirm zeichnet neu, sobald der Browser es anbietet. |
| Überlauf der Prüfungsreife bei 320 px, Wortumbruch in „Nochmal“ und Tastennamen. | Layout korrigiert, nachgeprüft. |
| Textkontraste aus `design/Colors.kt` reichen im Web nicht (siehe unten). | Eigene Stufen, per Test abgesichert. |

## Abweichungen vom Android-Design

Für 4,5:1 auf dem Papierton sind drei helle Töne dunkler: `signal` als Tastenfläche `#C2441F`
(statt `#D9542F`, 3,8:1), als Text `#B03D1A`, `inkMuted` `#66625A` (statt `#6E6A62`). Lampen und Kanten
behalten das Original. Vielleicht will Android das übernehmen; beim Originalton liegt weiße Schrift
auf der Signal-Taste unter AA.

## Offen

**Nicht getestet, nur mit einem Gerät prüfbar:**

- **Alles auf iOS und in Safari:** „Zum Home-Bildschirm“, Teilen-Dialog für Sicherungen, IndexedDB
  mit Bildern, Löschverhalten, Wake Lock, Tastatur über der Eingabe. Die Syntax ist für Safari 15
  gehalten, aber das ist nur Vorsicht, kein Nachweis.
- Firefox, Android-Chrome, Touch-Bedienung am echten Gerät.
- Screenreader: Beschriftungen und Live-Regionen sind vorhanden und per Skript geprüft, aber niemand
  hat es mit VoiceOver oder NVDA angehört.
- Die Erklärhilfe gegen die echte API: Kein Schlüssel vorhanden, und ein Testlauf hätte Geld gekostet.
- Installation bis zum fertigen Symbol: Chrome meldet keine Fehler, geklickt hat niemand.
- Datenbank-Migrationen: Es gibt erst Version 1.

**Bewusst nicht gebaut:**

- Erinnerungen: Ohne Server gibt es keine Benachrichtigung bei geschlossener App.
- Laufender Abgleich zwischen Geräten: nur über die Sicherung.
- Stundenplan und alles zu Heften und Büchern: außerhalb dieses Auftrags. „Stoff“ ist eine Abhakliste
  statt Stellen im Heft (auf Android: `study_link`).
- Die Erklärhilfe erklärt nur Karten. Karten vorschlagen und freie Antworten bewerten per Claude gibt
  es auf Android, hier nicht. Offline-Vorschläge aus Text sind da.
- Eine PNG-Grafik im Android-Stil für Ladebildschirme auf iOS (`apple-touch-startup-image`) fehlt.

**Bekannte Grenzen:**

- Eine Prüfung kann hier mehrere Stapel haben (alle des Fachs oder von Hand gewählte), auf Android genau einen. Mit einem Stapel je Fach rechnen beide gleich.

- Android hat ein Paket-Format noch nicht. `docs/INHALTSPAKET.md` beschreibt, was dort nötig ist.
- Keine fachlichen Inhalte im Repo. Das Beispiel handelt nur von der Bedienung.
- Sicherungen sind nicht verschlüsselt. Wer die Datei hat, kann sie lesen; es stehen nur Lerndaten darin.
- Die Prüfungsreife ist eine Schätzung, die auf der Android-Formel beruht. Ob sie echte Noten trifft,
  hat niemand gemessen.
