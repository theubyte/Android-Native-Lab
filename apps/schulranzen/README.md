# Schulranzen (Neuaufbau)

Native Android-App (Kotlin, Jetpack Compose, Jetpack Ink) für das Galaxy Tab S7 FE mit S Pen.
Nachfolger von `apps/digitaler-schulranzen` (V15.0.7, Java). Plan, Design und Phasen: [docs/PLAN.md](docs/PLAN.md).

## Stand 0.1.0 (Phase 1 + Anfang Phase 2)

- Designsystem „Retro-Modern Clean“: Papier- und Schiefer-Farben, Fachfarben als Akzente, Schriften Bricolage Grotesque / Inter / IBM Plex Mono (OFL, `licenses/`), eigene Linien-Icons, Tasten mit Soft-3D.
- Bibliothek: Fächer als Register, Hefte mit Rücken und Etikett, neues Heft (Titel, Fach, Papier), Heft bearbeiten / Papierkorb, neues Fach.
- Heft: endlose Spalte von A4-Seiten, Papier liniert / kariert / Punkte / blanko.
- Schreiben mit Jetpack Ink: Front-Buffer-Rendering, Bewegungsvorhersage, Druck und Neigung, Striche als Vektoren (scharf bei jedem Zoom).
- Stift schreibt, Finger scrollen und zoomen, Hand-Ablage wird ignoriert, solange der Stift in der Nähe ist.
- Stift, Marker (unter der Tinte), Teilradierer und Strichradierer ohne Reste, Rückgängig/Wiederholen.
- Seitentaste: beim Schreiben Radierer; im Schweben kurz = Radierer an/aus, lang = **Werkzeugring** um die Stiftspitze.
- Automatisches Speichern je Seite.

**Nicht auf dem Tablet getestet.** Gebaut und Geometrie-Tests grün; Schreibgefühl, Werkzeugring
(Air Command!) und Optik müssen auf dem Tab S7 FE geprüft werden.

## Bauen

```
gradle :app:assembleDebug        # Gradle 8.14, JDK 17+ (getestet 21), Android SDK 36
```
`applicationId` `de.millione.schulranzen.neu`: läuft neben der alten App.
