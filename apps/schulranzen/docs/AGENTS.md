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
