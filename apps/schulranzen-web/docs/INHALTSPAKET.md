# Inhaltspaket: Format 1

Ein Inhaltspaket ist eine UTF-8-JSON-Datei mit Stapeln und Karten. Die Web-App liest und schreibt
es; die Android-App soll dieselben Dateien nutzen können. Lernfortschritt steht **nie** im Paket.

Referenz-Umsetzung: `js/core/contentpack.js`, Tests in `tests/data.test.js`, Beispiel in
`beispiele/app-kennenlernen.json`.

> **Urheberrecht.** Karten aus Fach- und Tabellenbüchern gehören nicht in das öffentliche Repo und
> nicht an Dritte. Eigene Pakete liegen in `inhalt/`; der Ordner ist per `.gitignore` ausgeschlossen.

## Aufbau

```json
{
  "format": "schulranzen-inhaltspaket",
  "formatVersion": 1,
  "id": "beispiel.mein-paket",
  "titel": "Mein Paket",
  "version": "1.2.0",
  "stand": "2026-11",
  "beschreibung": "Worum es geht.",
  "autor": "Wer es gemacht hat",
  "lizenz": "Nur für den eigenen Gebrauch",
  "beispiel": false,
  "stapel": [
    {
      "id": "kapitel-1",
      "name": "Kapitel 1",
      "fach": "Mathe",
      "thema": "Bruchrechnen",
      "quelle": { "buch": "Mein Buch, 3. Auflage", "seite": "12" },
      "karten": [
        {
          "id": "k001",
          "vorne": "Frage",
          "hinten": "Antwort",
          "thema": "Kürzen",
          "quelle": { "buch": "Mein Buch, 3. Auflage", "seite": "14" },
          "geprüft": true,
          "vorneBild": "data:image/png;base64,…",
          "hintenBild": "data:image/jpeg;base64,…"
        }
      ]
    }
  ]
}
```

## Felder

### Paket

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `format` | ja | immer `schulranzen-inhaltspaket` |
| `formatVersion` | ja | Zahl, derzeit `1`. Eine andere Zahl wird abgelehnt, nichts wird übernommen. |
| `id` | ja | Dauerhafte Kennung des Pakets. Gleiche `id` heißt: neue Version desselben Pakets. Nicht ändern. |
| `titel` | ja | Name in der Vorschau |
| `version` | ja | Text oder Zahl. Verglichen wird numerisch teilweise: `1.10` ist neuer als `1.9`. |
| `stand`, `beschreibung`, `autor`, `lizenz` | nein | Text für die Vorschau |
| `beispiel` | nein | `true` kennzeichnet ein Beispielpaket ohne echten Lernstoff |
| `stapel` | ja | mindestens ein Stapel |

### Stapel

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `id` | ja | Dauerhaft und im Paket eindeutig |
| `name` | ja | Anzeigename; darf sich zwischen Versionen ändern |
| `fach` | nein | Verbindet den Stapel über das Fach mit Prüfungen |
| `thema` | nein | Vorgabe für Karten ohne eigenes Thema |
| `quelle` | nein | Vorgabe für Karten ohne eigene Quelle |
| `karten` | ja | Liste, darf leer sein |

### Karte

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `id` | empfohlen | Dauerhaft und im Stapel eindeutig. **Daran hängt der Lernfortschritt.** Fehlt sie, wird sie aus der Vorderseite abgeleitet: Dann gilt eine geänderte Vorderseite als neue Karte. Die Vorschau warnt davor. |
| `vorne`, `hinten` | ja* | Text. *Eine Seite darf stattdessen nur ein Bild haben. Zeilenumbrüche bleiben. |
| `thema` | nein | Für die Auswertung nach Thema und die Prüfungsreife |
| `quelle` | nein | `buch` (Text), `seite` (Text oder Zahl). Wird zur Antwort angezeigt. |
| `geprüft` | empfohlen | `true`: mit der Quelle verglichen. Fehlt das Feld, gilt `false`, und die Vorschau sagt das. |
| `vorneBild`, `hintenBild` | nein | data-URL mit PNG, JPEG oder WebP, je höchstens 3 MB. Andere Formate (auch SVG) werden abgelehnt. |

Auf der Rückseite legt `{{c1::Wort}}` die Lücke für den Lückentext fest. Ohne Markierung wählt die
App selbst eine Lücke: zuerst einen Wert mit Einheit, sonst einen Begriff.

Eine Antwort lässt sich maschinell prüfen, wenn sie kurz ist. Mehrere Punkte schreibt man je in eine
eigene Zeile, Alternativen mit „oder“ oder „ / “, Anmerkungen in Klammern zählen nicht zur Antwort:
`60 °C (Legionellenschutz)`. Lange Erklärungen werden automatisch zu offenen Fragen.

## Regeln beim Import

Das Prüfen kommt zuerst: Eine fehlerhafte Datei ändert nichts, die Fehlermeldung nennt Stapel und
Karte. Vor dem Übernehmen zeigt die App eine Vorschau mit allen Zahlen.

Beim Aktualisieren (gleiche Paket-`id`) wird jede Karte über `stapel.id` + `karte.id` wiedergefunden:

| Fall | Ergebnis |
|---|---|
| Karte neu | Wird angelegt, ist sofort „neu“. |
| Text geändert | Text wird ersetzt. **Lernstand bleibt.** Prüfstatus kommt aus dem Paket. |
| Text gleich | Bleibt. Ein von dir gesetztes „geprüft“ bleibt, das Paket kann es nur ergänzen. |
| Du hast die Karte selbst geändert | Dein Text bleibt, auch wenn das Paket eine neue Fassung bringt. |
| Karte fehlt in der neuen Version | Bleibt als deine eigene Karte mit Lernstand. Auf Wunsch wird sie gelöscht. |
| Stapel umbenannt | Der Name wird übernommen, Karten bleiben im Stapel. |

Lernstand heißt: `reps`, `intervalDays`, `ease`, `lapses`, Fälligkeit, letzte Wiederholung, Verlauf.

Beim Erkennen eigener Änderungen merkt sich die App die zuletzt importierte Fassung je Karte.

Ungeprüfte Karten sind beim Lernen und in der Liste markiert. In der Vorschau lassen sie sich auslassen.

## Für Android

- Das Format braucht nur JSON. Die Schlüssel `geprüft`, `vorneBild`, `hintenBild` sind wörtlich zu
  lesen, auch mit Umlaut (UTF-8). In Kotlin: `@SerialName("geprüft")`.
- Pflichten beim Lesen: dieselben Prüfungen wie `parsePack`, dieselbe Zuordnung wie `planImport`.
  Die Tests in `tests/data.test.js` sind als Testfälle gedacht und lassen sich übertragen.
- Die App muss zu jeder Karte Paket-`id`, `stapel.id/karte.id` und die zuletzt importierte Fassung von
  Vorder- und Rückseite speichern, sonst lässt sich nicht aktualisieren.
- Neue Felder kommen nur mit einer höheren `formatVersion`, wenn alte Apps sie verstehen müssten.
  Unbekannte Felder sind sonst zu überlesen.

## Bücher in Pakete bringen

Der Weg für den eigenen Gebrauch: Karten in der App oder als Textdatei selbst schreiben, dabei Buch
und Seite eintragen. Dann im Stapel „Ausgeben“ und „Inhaltspaket“ wählen und die Datei nach `inhalt/`
legen. So entsteht ein Paket mit Quelle und Prüfstatus, ohne dass etwas ins Repo gelangt.
