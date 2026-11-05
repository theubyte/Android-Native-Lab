# Parallele Arbeit am Schulranzen

Mehrere Agenten bauen gleichzeitig. Damit nichts kollidiert und die Qualität einheitlich bleibt,
gelten diese Regeln. Produktziel, Design und Phasen: [PLAN.md](PLAN.md).

## Besitz (wer darf was ändern)

| Bereich | Besitzer | Inhalt |
|---|---|---|
| `app/`, `ink/`, `design/` (außer neuen Icons über `RanzenIcons.line`), `settings.gradle.kts`, `gradle/` | Integrator (Haupt-Sitzung) | Navigation, Bibliothek, Heft, Schreibkern, Designsystem |
| `feature/rechnen/` + `core/.../core/math/`, `core/.../core/shk/` | Agent „Rechnen“ | Taschenrechner, Handschrift-Rechnen, SHK-Rechner, Einheiten, Formelsammlung |
| `feature/books/` + `core/.../core/books/` | Agent „Bücher“ | Bücher/PDF: Import, Ansicht, Annotation, Inhaltsverzeichnis, Ausschnitte |
| `feature/study/` + `core/.../core/study/` | Agent „Lernen“ | Stundenplan, Prüfungen, Karteikarten mit Wiederholung |
| `feature/backup/` + `core/.../core/backup/` | Agent „Backup“ | Vollbackup/Restore, Unraid-Sync, Import aus V15 |
| `feature/handwriting/` + `core/.../core/shapes/` | Agent „Schrift“ | Handschrift → Text, Suchindex über Handschrift, Formerkennung |

### Runde 3 (0.5, verkaufsfertig)

| Agent | Besitz | Ausnahmen |
|---|---|---|
| `lernen` | `feature/study/`, `core/.../core/study/` | |
| `handy` | `app/.../MainActivity.kt`, neues `app/.../phone/`, Scan-Code, `design/` | kleiner `readOnly`-Eingriff in `ink/NotebookCanvasView.kt` und `app/notebook/NotebookScreen.kt` |
| `heft` | `ink/`, `app/.../notebook/`, `app/.../library/`, `app/.../data/` (außer `BackupWiring.kt`) | |
| `sichern` | `feature/backup/`, `core/.../core/backup/`, `app/.../data/BackupWiring.kt`, `apps/digitaler-schulranzen/server/` | |
| `buecher` | `feature/books/`, `core/.../core/books/`, `feature/handwriting/`, `core/.../core/shapes/` | |
| `rechnen` | `feature/rechnen/`, `core/.../core/math/`, `core/.../core/shk/` | |
| `release` | `app/build.gradle.kts`, `gradle/`, ProGuard, Manifest, `docs/store/`, neues `app/.../about/`, `.github/` | |

**Handy-Regel für alle:** `rememberIsPhone()` / `isPhone(context)` aus `design/Device.kt` entscheidet.
Auf dem Handy gibt es kein Schreiben, keinen Radierer, keinen Lasso und kein Rechnen; Lesen, Scannen und Lernen
müssen dort ab 360 dp Breite einwandfrei gehen. Das Tablet bleibt unverändert. Keine Parameter quer durch die Module.

**Prüfbericht:** Jeder Agent schreibt `docs/audit/<name>.md`: geprüft, gefunden, behoben, offen.
`docs/STAND.md` ändert nur der Integrator.

Andere Dateien nur lesen. Braucht ein Agent etwas außerhalb seines Bereichs (eine neue
Bibliothek im Versionskatalog, eine Änderung im Designsystem, ein Hook im Heft), beschreibt er
das in seinem Abschlussbericht. Der Integrator setzt es um. Eigene Abhängigkeiten dürfen direkt
mit Koordinaten in die eigene `build.gradle.kts` (exakte, stabile Versionen).

## Qualität

- **Kotlin, Compose, Designsystem:** `RanzenTheme`-Farben über `Ranzen.colors`, Typografie über `Ranzen.type`, Bausteine `PaperPanel`, `TactileKey`, `SignalButton`, `QuietButton`, `RegisterTabs`, `Lamp`, Icons aus `RanzenIcons` oder eigene über `RanzenIcons.line { … }`.
  - Keine Standard-Material-Dialoge, -Karten oder -Chips und keine `material-icons`.
  - Hell und dunkel müssen beide gut aussehen.
- **Optimiert für das Tab S7 FE:** 2560 × 1600, 320 dpi, quer und hoch. Große Touch-Ziele (≥ 48 dp).
- **Alle Texte auf Deutsch**, kurz und klar.
- **Logik ohne Android:** Reine Logik (Rechnen, Wiederholungs-Algorithmus, Parser, Formate) liegt in `core` unter dem eigenen Paket und hat **JUnit-Tests** (`gradle :core:test`).
- **Speicher:** Jedes Feature-Modul hat seine eigene Ablage (eigene Room-DB oder Dateien unter `filesDir/<feature>/`), nicht die DB der App. Schreiben atomar (tmp + rename).
- **Threads:** Nichts Schweres auf dem Main-Thread (PDF-Rendering, Dateien, Netz).
- **Code-Stil:** wie im bestehenden Code: knappe KDoc-Kommentare auf Englisch, die das *Warum* erklären; deutsche UI-Texte.
- **Bauen:** `gradle :app:assembleDebug` muss grün sein, und `gradle :core:test` ebenfalls.
  - Gradle: `/c/Users/u-byt/Android/gradle-8.14.3/bin/gradle`.
  - `JAVA_HOME`: `/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`.
- **Kein Emulator.** Den nutzt nur der Integrator für Sichtprüfung.

## Ablauf

1. Jeder Agent arbeitet in **seiner eigenen Kopie** des Repos (siehe Auftrag) auf dem Branch `agent/<name>`.
2. Kleine, sinnvolle Commits mit deutscher Nachricht (Was/Warum), Autor `TheUByte <83143005+theubyte@users.noreply.github.com>`, Trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
3. **Nicht pushen.** Der Integrator holt den Branch aus der Kopie, prüft, testet im Emulator und führt zusammen.
4. **Abschlussbericht**, damit der Integrator einbauen kann:
   - was gebaut ist;
   - öffentliche Einstiegspunkte (Composables, Klassen);
   - was getestet ist und was nicht;
   - was der Integrator verdrahten muss.
