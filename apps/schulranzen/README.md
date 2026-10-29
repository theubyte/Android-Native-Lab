# Schulranzen (Neuaufbau)

Native Android-App (Kotlin, Jetpack Compose, Jetpack Ink) für das Galaxy Tab S7 FE mit S Pen.
Nachfolger von `apps/digitaler-schulranzen` (V15.0.7, Java). Plan, Design und Phasen: [docs/PLAN.md](docs/PLAN.md). **Was erledigt ist und was noch fehlt: [docs/STAND.md](docs/STAND.md).**

## Stand 0.3.1

Fester Signaturschlüssel (nur lokal, Updates behalten die Hefte; vor 0.3.1 einmal deinstallieren), SHK-Ergebnis neben den Feldern, Buchausschnitte nie in gesperrte Seiten. Details: [docs/STAND.md](docs/STAND.md).

## Stand 0.3.0 (Phasen 1–8, parallel gebaut)

Neu in 0.3.0:
- **Arbeitsblätter als Heftseiten:** fotografieren, aus Fotos oder aus PDF (jede Seite ein Blatt), direkt beschreibbar.
- **Bibliothek:** Suche, Papierkorb (30 Tage), Bücherregal, Tagesleiste mit heutigem Stundenplan, Tasten zu Lernen, Stundenplan, SHK, Sichern.
- **Bücher** (`feature/books`): PDF-Import, scharfes Zoomen, Inhaltsverzeichnis (eingebettet oder aus dem Text), Schreiben/Markieren/Radieren, Notizen, Lesezeichen, Bereiche merken, **Ausschnitt ins Heft**.
- **Rechnen** (`feature/rechnen`): schwebender Retro-Taschenrechner im Heft, **„Ausrechnen“** für mit dem Lasso gewählte Handschrift (ML Kit), SHK-Werkzeugkasten mit 21 Schnellrechnern, Einheiten und 32 Formeln.
- **Lernen** (`feature/study`): Stundenplan als Wochenraster mit A/B-Wochen, Prüfungen mit Countdown und Lernplan, Karteikarten mit Wiederholung (SM-2, prüfungsbewusst).
- **Sichern** (`feature/backup`): Vollbackup mit Prüfsummen und Wiederherstellung, Unraid-Server (Anmelden, Hochladen, Versionen), **Übernahme aus V15** (Hefte mit allen Seiten, Arbeitsblätter, Bücher, Prüfungen, Karten).

Bekannt offen: Buch und Heft nebeneinander; im SHK-Rechner verdeckt die Tastatur beim Tippen das Ergebnis; Handschrift-Rechnen braucht beim ersten Mal Internet (Modell ~20 MB); V15-Import noch nicht mit einem echten Backup vom Tablet geprüft.

## Stand 0.2.0 (Phase 1–3)

Neu in 0.2.0 (Phase 3, Heft komplett):
- **Lasso:** Schleife ziehen, Auswahl verschieben, am Griff skalieren (Strichstärke skaliert mit),
  Ausschneiden, Kopieren, Duplizieren, Einfärben, Löschen; Einfügen auf der sichtbaren Seite.
- **Lineal:** gerade Linien, rastet bei 15°-Schritten ein, Anzeige von Länge (mm) und Winkel.
- **Text:** Seite antippen, Text schreiben (Klein/Normal/Groß, Farben), antippen zum Bearbeiten.
- **Bilder** aus der Galerie (werden in die App kopiert), mit dem Lasso verschieb- und skalierbar.
- **Seitenübersicht** mit Vorschaubildern: springen, Seite vor/zurück, Kopie, Lesezeichen, Sperren,
  Löschen; Filter „Lesezeichen“.
- **Lesezeichen** (oranges Band am Blatt) und **Seitensperre** (kein Schreiben/Radieren).
- **PDF-Export** über den Speichern-Dialog, wahlweise ohne Linien/Karos.
- Rückgängig/Wiederholen für alles davon; Datenbank-Migration 1 → 2.

## Stand 0.1.0 (Phase 1 + Anfang Phase 2)

- Designsystem „Retro-Modern Clean“: Papier- und Schiefer-Farben, Fachfarben als Akzente, Schriften Bricolage Grotesque / Inter / IBM Plex Mono (OFL, `licenses/`), eigene Linien-Icons, Tasten mit Soft-3D.
- Bibliothek: Fächer als Register, Hefte mit Rücken und Etikett, neues Heft (Titel, Fach, Papier), Heft bearbeiten / Papierkorb, neues Fach.
- Heft: endlose Spalte von A4-Seiten, Papier liniert / kariert / Punkte / blanko.
- Schreiben mit Jetpack Ink: Front-Buffer-Rendering, Bewegungsvorhersage, Druck und Neigung, Striche als Vektoren (scharf bei jedem Zoom).
- Stift schreibt, Finger scrollen und zoomen, Hand-Ablage wird ignoriert, solange der Stift in der Nähe ist.
- Stift, Marker (unter der Tinte), Teilradierer und Strichradierer ohne Reste, Rückgängig/Wiederholen.
- Seitentaste: beim Schreiben Radierer; im Schweben kurz = Radierer an/aus, lang = **Werkzeugring** um die Stiftspitze.
- Automatisches Speichern je Seite.

**Im Emulator geprüft** (2560 × 1600, 320 dpi wie das Tab S7 FE): Bibliothek, Heft anlegen, Schreiben
mit simuliertem Stift, Teilradierer, Marker, Farben/Dicken, Papierwahl, Seite hinzufügen, Scrollen,
Speichern und Wiederöffnen, Werkzeugring (über einen Debug-Zugang, da der Emulator keine
Stift-Seitentaste hat), hell und dunkel. Bilder: [docs/screens](docs/screens).

**Nicht auf dem Tablet getestet:** Schreibgefühl mit echtem S Pen (Druck, Latenz), Hand-Ablage,
Pinch-Zoom und ob die Seitentaste im Schweben ankommt (Air Command). Debug-Zugang zum Ring:
`adb shell am start -n de.millione.schulranzen.neu/de.millione.schulranzen.app.MainActivity --es ring open --ef x 1200 --ef y 700`
(weitere: `move`, `release`, `cancel`).

## Bauen

```
gradle :app:assembleDebug        # Gradle 8.14, JDK 17+ (getestet 21), Android SDK 36
```
`applicationId` `de.millione.schulranzen.neu`: läuft neben der alten App.
