# Schulranzen im Browser

Die Lernfunktionen des Schulranzen als Web-App, für alle ohne Android-Gerät: iPhone, iPad, PC, Mac.
Karten, Prüfungen, „Jetzt lernen“, Prüfungsreife, Probeprüfung, Fehlerheft, Fokus-Timer, Statistik.

Kein Konto, kein Server, keine Analyse, keine fremden Cookies. Alle Daten bleiben im Browser. Sie
rechnet gleich wie die Android-App: Die Logik in `js/core/` ist aus `core/.../study/` übertragen und
besteht deren Testwerte.

## Starten zum Ausprobieren

```
cd apps/schulranzen-web
python -m http.server 8000
```

Dann `http://localhost:8000/` öffnen. Die Tests laufen unter `http://localhost:8000/tests/` im
Browser, ohne Bibliothek und ohne Build. `localhost` gilt als sicher, der Service Worker läuft dort.

## Hosten

Es gibt keinen Build-Schritt: Der Ordner wird so hochgeladen, wie er ist. Jeder statische Webspace
geht, auch in einem Unterordner, denn alle Pfade sind relativ.

**Hochladen:** `index.html`, `manifest.webmanifest`, `sw.js`, `css/`, `js/`, `fonts/`, `icons/`,
`beispiele/`. Nicht nötig sind `tests/`, `docs/`, `README.md`. **Nie hochladen:** `inhalt/`.

**HTTPS ist Pflicht.** Ohne HTTPS gibt es keinen Service Worker, kein Offline und keine Installation.

Empfohlen auf dem Server:

| Einstellung | Warum |
|---|---|
| `sw.js`: `Cache-Control: no-cache` | Sonst erfahren Browser spät von neuen Versionen |
| `.webmanifest`: Typ `application/manifest+json` | Manche Server kennen die Endung nicht |
| `.woff2`: Typ `font/woff2`; `.js`: Typ `text/javascript` | Module laden nur mit einem JavaScript-Typ |
| `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff` | Lässt sich nicht aus der Seite heraus setzen |

Die Seite bringt ihre Sicherheitsregeln (Content Security Policy) selbst mit, auch auf einfachem Webspace.

**Eigene (Sub-)Domain, wenn es geht.** Browser trennen Daten nach Domain, nicht nach Ordner. Andere
Anwendungen auf derselben Domain könnten die Daten dieser App lesen. Für die Erklärhilfe mit
gemerktem Schlüssel gilt das erst recht.

Nach einem Umzug auf eine andere Adresse sind die Daten dort leer: mit „Sicherung“ mitnehmen.

### Neue Version veröffentlichen

1. `VERSION` in `sw.js` und `APP_VERSION` in `js/version.js` gleich erhöhen.
2. Neue Dateien in die Liste `FILES` in `sw.js` eintragen. Die Testseite meldet, wenn etwas fehlt.
3. `tests/` im Browser laufen lassen, dann hochladen.

Beim nächsten Besuch lädt der Browser die neue Version im Hintergrund. Die App zeigt „Neue Version
bereit“ mit „Aktualisieren“ und wechselt erst dann. Daten bleiben unberührt: Eine Änderung am
Datenbank-Aufbau braucht zusätzlich eine neue `VERSION` und eine Migration in `js/data/db.js`.

## Daten und Sicherung

Die Daten liegen in IndexedDB. Die App bittet den Browser um dauerhaften Speicher
(`navigator.storage.persist`). Sicher ist das aber nicht:

- **Safari kann Daten löschen**, wenn eine Webseite einige Wochen nicht besucht wurde. Auf dem
  Home-Bildschirm installiert ist das Risiko geringer, aber nicht null. Die App warnt dort deutlich.
- Im privaten Fenster bleibt nichts.
- „Browserdaten löschen“ löscht auch den Ranzen.

Deshalb: **Mehr › Jetzt sichern.** Die Datei enthält Karten, Bilder und Lernstand, keinen API-Schlüssel.
So kommen Daten auch auf ein zweites Gerät: „Zusammenführen“ verliert nichts, bei gleichen Karten
gewinnt der neuere Lernstand. Eine laufende Synchronisation gibt es nicht, denn es gibt keinen Server.

## Austausch

- **Stapel als Textdatei:** tab-getrennt mit den Kopfzeilen `#separator:tab`, `#html:false`,
  `#columns:Front<TAB>Back`, wie `CardCsv` der Android-App und die üblichen Kartenprogramme. Gelesen
  wird auch Semikolon und Komma. Lernstand gehört nicht zum Format.
- **Inhaltspakete:** siehe [docs/INHALTSPAKET.md](docs/INHALTSPAKET.md).
- **Eigene Pakete** gehören nach `inhalt/`. Der Ordner ist von Git ausgeschlossen: Buchinhalte
  sind urheberrechtlich geschützt, das Repo ist öffentlich.

## Erklärhilfe (Option)

Aus, bis jemand sie einrichtet. Ohne Server gibt es keinen Ort, an dem ein Schlüssel der App sicher
läge, also braucht es einen **eigenen** Claude-API-Schlüssel. Er liegt nur im Browser, standardmäßig
nur bis zum Neustart, und kommt nie in eine Sicherung. Gesendet wird nur die eine Karte, und nur
nach Klick auf „Jetzt senden“. Die App spricht dazu direkt mit `api.anthropic.com`; das ist die
einzige fremde Adresse, die die Sicherheitsregeln erlauben.

## Aufbau

```
js/core/    reine Logik ohne Speicher und ohne Oberfläche, mit Tests
js/data/    IndexedDB und alle Schreibvorgänge
js/ui/      die Bildschirme
js/app.js   Rahmen, Navigation
sw.js       Offline und Updates
tests/      Testseite im Browser
```

Ändert sich die Logik in `core/.../study/` auf Android, muss sie hier mit. Die Tests nennen ihre
Kotlin-Vorbilder.
