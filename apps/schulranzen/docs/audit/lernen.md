# Prüfbericht Lernen (Runde 3)

Stand: 05.11.2026, Branch `agent/lernen`. Getestet mit `:core:test`, `:feature:study:testDebugUnitTest`, `:app:assembleDebug`. Kein Emulator, kein Gerät, kein echter API-Aufruf.

## Geprüft

SM-2 (`Repetition.kt`), Lernplan, Lernpfad, Probeprüfung mit Note und Schwächen, Serie, A/B-Wochen, Datumsgrenzen (Jahreswechsel, Sommerzeit, Zeitzone), Rückgängig, Leerzustände, Zusammenführen (`StudyMerge.kt`), Handy-Layouts ab 360 dp (nur durch Lesen und Rechnen der Breiten).

Für richtig befunden und nicht geändert: IHK-Notenschlüssel, Anker der A/B-Wochen, Serie, `isDue`, Obergrenze durch das Prüfungsdatum, `TopicMatcher`.

## Gefunden und behoben

| Stelle | Fehler | Behoben |
|---|---|---|
| `Repetition.review` | Wiederholtes „Nochmal“ in einer Sitzung senkte die Leichtigkeit jedes Mal, eine Karte landete dauerhaft beim Minimum | Nur noch einmal je Sitzung |
| `Repetition.grow` | Zu frühe Wiederholung (vorgezogen nach Probeprüfung, gedeckelt durch Prüfungsdatum) wuchs aus dem geplanten Abstand: aus 25 Tagen wurden 62, obwohl nur 5 gewartet | Wächst aus den wirklich gewarteten Tagen |
| `ExamPlanner.cardsToday` | Konnte negativ werden | Begrenzt auf 0 |
| `LearningPath` | Probeprüfung knapp unter 67 % galt durch Rundung als bestanden | Erst ab dem Ziel fertig |
| `lessonsOn` | Samstagsstunden erschienen in „Heute“, obwohl Samstag abgeschaltet war | Gefiltert |
| `updateCardText` | Schrieb eine alte Kopie zurück und konnte den Wiederholungsplan überschreiben | Liest vorher aus der Datenbank |
| `deckForExam` | Flow-Abfrage mitten in einer Transaktion | Einmal-Abfrage |
| Probeprüfung | „Schwache Karten lernen“ lernte alles Fällige statt der falschen Karten | Eigene Nachlernrunde mit genau diesen Karten |
| Probeprüfung | Start ohne Stapel tat stumm nichts | Klarer Hinweis |
| Probeprüfung | Zeit zählte mit `delay` und konnte im Hintergrund nachgehen; App-Ende verlor den Lauf; Abgabe konnte doppelt auslösen | Wanduhr, Zwischenspeicher mit Fortsetzen, Sperre gegen Doppelabgabe |
| Lernrunde | Der Umweg über die Erklärhilfe startete die Runde neu | Rundenstand bleibt erhalten |
| `StudyMerge` | Backups ohne `study_link`/`mock_run` (Version 1) ließen das Zusammenführen abstürzen; Spalten neuerer Backups ebenso | Tabellen und Spalten werden geprüft |
| `StudyMerge` | Einstellungen (A/B-Wochen samt Anker) kamen nicht mit, A/B-Stunden waren dann nicht zuzuordnen | Fehlende Einstellungen werden übernommen |
| `StudyMerge` | Pfadprüfung der Bilder ohne Trennzeichen (`study_x` ging durch); offene Bildschirme sahen die Änderungen nicht | Beides behoben |
| Sheets | Fehler beim Anlegen in `SuggestCardsContent`, `QuickCardContent`, `MarkForExamSheet` blieben unbemerkt, Knopf hing auf „Speichert …“ | Abgefangen, Meldung, mehrere Karten alles oder nichts |
| Heft-Links | Nach Umsortieren sprang der Link auf die falsche Seite | `pageId`, `StudyLinkTargets`, Hinweis statt falscher Seite |
| Stapelzeile | Leerer Stapel zeigte „erledigt“ | Entfernt |
| Handy | Kopfzeile, Sheet-Knöpfe, Farbwahl, Wochenraster, Kartenlayout passten nicht auf 360 dp | Eigene Handy-Darstellung, Tablet unverändert |

## Daten

`study.db` Version 3 per AutoMigration von 2: neue Tabellen `mock_answer`, `focus_session`; neue Spalten `mock_run.seconds`, `mock_run.limitSeconds`, `study_link.pageId`. Die AutoMigration prüft Room beim Bauen; ein Lauf gegen eine echte alte Datenbank steht aus. `StudyMerge` führt alle neuen Tabellen und Spalten mit.

## Offen

- Nichts davon lief auf einem Gerät: alle Bildschirme, Benachrichtigungen, Berechtigung, Datei-Import/-Export sind nur kompiliert. Sichtprüfung durch den Integrator nötig, besonders bei 360 dp.
- `StudyMerge` hat keine automatischen Tests (braucht Android). Migration 2 → 3 ebenso.
- Claude: Das Anthropic-SDK läuft auf Android hier ungetestet (Jackson, Kotlin-Reflection); `org.apache.httpcomponents` ist ausgeschlossen, Datei-Uploads des SDK würden daher scheitern (werden nicht genutzt). Streaming und Bewertung brauchen einen Test mit echtem Schlüssel.
- Rückfall nach Ablehnung (`fallbacks`): bricht eine Antwort mitten im Text ab und läuft auf einem anderen Modell weiter, können Teiltext und neue Antwort aneinanderhängen.
- Prognose und Tagesplan arbeiten mit Schätzwerten (Vergessenskurve, Sekunden je Karte, Gewichte je Prüfungsart). Sie sind nicht an echten Prüfungsergebnissen geeicht.
- `TopicMatcher` ordnet Karten nur über Wortstämme zu; Themenauswertung und Abdeckung sind entsprechend grob.
- Karten mit gleichem Stapelnamen werden nicht verhindert.
- `ExamPlanner.plan` beginnt bei fernen Prüfungen erst 29 Tage vorher; der Tagesplan verteilt die Arbeit trotzdem ab heute.
