# Prüfbericht Agent „release“ (Runde 3)

Stand: Branch `agent/release`, mit `agent/handy` zusammengeführt. Ziel nach Planänderung: **direkt installierbare, selbst signierte APK**, kein Play Store.
`docs/store/` bleibt als angefangener Entwurf liegen und wird nicht weitergeführt (die Verweise dort auf `checkliste.md` und `grafik/` zeigen ins Leere).

## Größe

Gemessen an `:app:assembleRelease`, ohne Einbau von `about/` in `MainActivity` (R8 entfernt den noch unbenutzten Code, danach kommen einige Hundert KB dazu). Stand mit Handy-Zweig und Scanner.

| APK | Größe | Für |
|---|---|---|
| vorher, eine für alle (0.4.2, mein Nachbau ohne R8) | **99,5 MB** (gemeldet waren 106 MB) | |
| `app-arm64-v8a-release.apk` | **17,6 MB** | Tab S7 FE, aktuelle Handys |
| `app-armeabi-v7a-release.apk` | **16,1 MB** | ältere 32-bit-Geräte |
| `app-x86_64-release.apk` | 18,2 MB | Emulator, Chromebooks |
| `app-universal-release.apk` | 32,5 MB | Rückfall für die Hand |
| `app-debug.apk` (Debug, eine für alle, ohne R8) | 50,5 MB | Entwicklung |

Anteile der arm64-APK: Code 5,0 MB (vorher 14,5), Bibliotheken 7,9 MB (entpackt 19,4), Assets 3,3 MB, Ressourcen 1,2 MB. Ohne komprimierte Bibliotheken wären es etwa 29 MB.
`assembleRelease` schreibt je ABI eine Datei; die alte `app-release.apk` gibt es dann nicht mehr. Baut ein Aufruf Debug und Release zusammen, teilt er auch Debug auf: darum getrennt bauen.

Was die Größe bringt: je ABI eine APK, R8 und Ressourcen-Schrumpfung, Bibliotheken in der APK komprimiert (`useLegacyPackaging`), 32-bit-x86 raus, Post-Quanten-Tabellen von Bouncy Castle raus (4 MB), nur deutsche Bibliothekstexte.

Preis der komprimierten Bibliotheken: Android entpackt sie bei der Installation. Auf dem Gerät belegt die App dadurch etwa 8 MB mehr als ohne Kompression; dafür ist jeder Download etwa 11 MB kleiner. Bei Bedarf eine Zeile in `app/build.gradle.kts`.

## versionCode

`major·1 000 000 + minor·10 000 + patch·100 + build`, aus `appVersion` und `appBuild` oben in `app/build.gradle.kts`. 0.5.0 = 50000, höher als die bisherige 7, also installierbar über 0.4.2.
Alle ABI-APKs einer Version tragen denselben Code. Das ist hier richtig: Ein Gerät installiert nur seine eigene, und der Updater wählt nach `Build.SUPPORTED_ABIS`.

## 16-KB-Seitengröße

Geprüft mit eigenem ELF-Leser an den Bibliotheken: alle `LOAD`-Segmente der 64-bit-Bibliotheken (arm64-v8a, x86_64) stehen auf 16 KB, die RELRO-Bedingung nach der Android-Anleitung ist erfüllt.
32-bit-Bibliotheken (`libdigitalink.so`, `libmlkit_google_ocr_pipeline.so` auf armeabi-v7a mit 4 KB) sind davon nicht betroffen, weil es 16-KB-Geräte nur mit 64 bit gibt.
Weil die Bibliotheken nun komprimiert sind, entfällt die Zip-Ausrichtung als Fehlerquelle. Auf einem 16-KB-Gerät gelaufen ist nichts.

## R8

Statisch geprüft an `mapping.txt` und den DEX-Dateien des gebauten APK:

- `RanzenDatabase_Impl`, `StudyDatabase_Impl`, `WorkDatabase_Impl`, `AutoBackupWorker`, beide Initializer: Name unverändert.
- ML Kit (294 Klassen, davon 33 Dokumentenscanner) und Ink (424 Klassen) behalten ihre Namen. Die 31 `$$serializer`-Klassen sind vorhanden.
- Keep-Regeln großzügig für JNI und Reflexion: ML Kit, Ink, graphics, pdfbox, Bouncy-Castle-Provider, Dynamite-Beschreibungen des Scanners. Das kostet etwas Größe und ist gewollt.
- Der Scanner läuft über die Play-Dienste. Ohne sie greift der Rückfall des Agenten „handy“ (Kamera-App, Galerie, PDF).
- Das Teilen an die App (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`) ist im zusammengeführten Manifest vorhanden, `singleTask` ebenso. Es hängt nicht an R8-Namen, weil `MainActivity` im Manifest steht.

**Das belegt nicht, dass die App läuft.** Keep-Regeln lassen sich statisch nur auf Vorhandensein prüfen. Laufende Prüfung am Release-APK (Integrator), genau die Wege mit JNI oder Reflexion:

1. Heft schreiben und neu öffnen (Ink, Room)
2. Handschrift-Modell laden, Lasso → Als Text, Kreis-Ausrechnen (Digital Ink)
3. Gescanntes Buch: Texterkennung (Text Recognition)
4. PDF-Buch öffnen, Inhaltsverzeichnis, Volltextsuche; **verschlüsseltes** PDF (pdfbox, Bouncy Castle)
5. Dokumentenscanner auf dem Handy (Play-Dienste)
6. Automatisches Backup auslösen (WorkManager), Sicherung und Wiederherstellung (kotlinx.serialization)
7. Lernen: Stapel, Karte, Wiederholung (Room, zweite Datenbank)

Die Stacktraces im Release sind umbenannt. `app/build/outputs/mapping/release/mapping.txt` je Version aufheben, sonst sind Absturzberichte kaum lesbar.

## Debug-Hooks

Der adb-Werkzeugring in `MainActivity.onNewIntent` steht hinter `!BuildConfig.DEBUG`. **Geprüft im DEX des Release-APK:** `onNewIntent` enthält nur noch `super`, `setIntent` und das Teilen, der Text „ring“ kommt dort nicht vor. R8 hat den Block entfernt.
Das Teilen (`receiveShare`) läuft davor und bewusst auch im Release. Es nimmt nur `content://` fremder Anbieter (`ScanPlan.acceptsShare`), keine `file://`-Adressen und nicht den eigenen Provider.
Der neue Debug-Eingang `files/update-debug-url.txt` (eigener Test-Server) steht ebenfalls hinter `BuildConfig.DEBUG`. StrictMode nur in Debug, und nur als Log.

## Baseline-Profil

Ein gemessenes Profil braucht ein Gerät (Macrobenchmark), das ist ohne Emulator nicht möglich. Eingebaut: von Hand geschriebene Regeln in `app/src/main/baseline-prof.txt`. AGP führt sie mit den Profilen von Compose und den anderen Bibliotheken zusammen. Im APK liegt `assets/dexopt/baseline.prof` (5,9 KB).
Der Nutzen ist nicht gemessen.

## Manifest und Berechtigungen

Zusammengeführter Manifest, geprüft:

| Berechtigung | Quelle | Nötig für |
|---|---|---|
| `INTERNET` | backup, handwriting, study | Sync, Modell, Erklärhilfe, Updates. Android kann das nicht je Ziel einschränken |
| `ACCESS_NETWORK_STATE` | handwriting, rechnen, WorkManager | WLAN-Prüfung vor Downloads, Netzbedingung beim Auto-Backup |
| `REQUEST_INSTALL_PACKAGES` | **neu, app** | Updates. Android fragt dennoch bei jeder Installation |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` | WorkManager | Auto-Backup, auch nach einem Neustart |
| `FOREGROUND_SERVICE` | WorkManager | Eigener Code startet keinen. Könnte mit `tools:node="remove"` weg, aber ML Kit bringt selbst WorkManager mit, und ohne Gerät ist das nicht prüfbar. Darum belassen |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx.core | Eigene App, interne Absicherung |

**Kamera:** Keine Berechtigung nötig und keine deklariert. Das Heft nutzt die Kamera-App (`TakePicture`), der Scanner die Berechtigung der Play-Dienste (laut Google). Wer `CAMERA` einträgt, bricht übrigens `TakePicture`, solange sie nicht erteilt ist.
**Benachrichtigungen:** Kein Code in Haupt- und Handy-Stand zeigt welche, also keine `POST_NOTIFICATIONS`. Baut „lernen“ Erinnerungen, muss die Berechtigung in den Manifest (mir melden) und zur Laufzeit erfragt werden. Vor dem Zusammenführen prüfen.
**Exportiert:** `MainActivity` (Launcher und Teilen), `SystemJobService` (`BIND_JOB_SERVICE`), `DiagnosticsReceiver` und `ProfileInstallReceiver` (`DUMP`). Alles andere ist nicht exportiert.
**FileProvider:** nur `cache/camera`, `cache/reports`, `cache/updates`, nicht exportiert.
**Backup:** `allowBackup=false`, dazu `dataExtractionRules`, weil `allowBackup` ab Android 12 die Übertragung auf ein neues Gerät nicht stoppt. Beides schließt alles aus. Die Folge steht in der Hilfe: Gerätewechsel nur über „Sichern“.
**Symbol:** Das alte Monochrom-Symbol war die farbige Grafik, im Themed-Modus also ein Klotz. Jetzt eigene Form mit ausgesparter Beschriftung. Die Grafik ist auf die sichere Zone verkleinert. **Nicht gesehen**, auch nicht im Emulator.
**Splash:** Eingebaut über `values-v31` ohne Bibliothek, dunkel über `values-night`. Fensterfarbe ist jetzt die Schreibtischfarbe statt eines helleren Papiertons. Der Dunkel-Schalter der App wirkt erst nach dem Splash (der folgt dem System).

### Cleartext

Android kann in der Netzwerkkonfiguration nur Hostnamen freigeben, keine IP-Bereiche. Für `http://192.168.x.y:8788` muss Klartext also grundsätzlich erlaubt bleiben. Gesperrt (nur HTTPS) sind `anthropic.com`, `google.com`, `googleapis.com`, `gstatic.com`, `googleusercontent.com`, `github.com`, `githubusercontent.com`. Der Updater verlangt zusätzlich im eigenen Code HTTPS.
`usesCleartextTraffic="true"` aus `feature/backup` wird im App-Manifest entfernt, weil die Konfiguration gilt.

**Offen für „sichern“:** Heute lässt sich `http://irgendein-server.de` eintragen, und E-Mail, PIN und Backup gingen unverschlüsselt durchs Internet. Schmal wird das erst im Sync-Client: `http://` nur für private Ziele. Vorschlag für `core/.../backup/SyncClient.kt`, in `open()` vor `openConnection`:

```kotlin
/** Plain http only inside the home network or a private VPN. */
fun isPrivateHost(host: String): Boolean {
    val h = host.lowercase().trim('[', ']')
    if (h == "localhost" || '.' !in h && ':' !in h) return true
    if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".fritz.box")) return true
    if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true
    val p = h.split('.').mapNotNull { it.toIntOrNull() }
    if (p.size != 4) return false
    return p[0] == 10 || p[0] == 127 || p[0] == 192 && p[1] == 168 || p[0] == 172 && p[1] in 16..31 ||
        p[0] == 169 && p[1] == 254 || p[0] == 100 && p[1] in 64..127 // the last one: Tailscale and similar
}
```

**Vormerken:** Ab `targetSdk 37` (Android 17) braucht direkter Zugriff aufs Heimnetz die Laufzeit-Berechtigung `ACCESS_LOCAL_NETWORK`. Bei `targetSdk 36` bleibt es, wie es ist.

## Absturz-Abfang

`about/StartupInitializer` läuft über androidx.startup vor der App und braucht keine Änderung in `MainActivity`. Abstürze landen als Textdatei in `noBackupFilesDir/crash` (also nie in einer Sicherung), die letzten 5 bleiben. Beim nächsten Start: „Bericht teilen“, „Ansehen“, „Nicht teilen“. Kein Fremddienst, kein automatisches Senden. Das Teilen läuft über das System-Menü ohne vorgegebenen Empfänger.
Abstürze in nativem Code (Ink, ML Kit) und Hänger erreichen den Handler nicht. Ab Android 11 liest die App sie beim nächsten Start aus `getHistoricalProcessExitReasons`. Auf Android 8 bis 10 fehlen sie. E-Mail-Adressen, API-Schlüssel und Bearer-Token werden aus dem Bericht entfernt; Dateinamen in Fehlermeldungen könnten stehen bleiben, darum „Ansehen“.

## Update-Funktion

Vorher gab es keine. Beschreibung und Ablauf: [UPDATE.md](../UPDATE.md). Logik in `core/.../core/update/` mit 21 Tests (Parser, Vergleich, ABI, Fehlerfälle, Download und Fortsetzen gegen einen lokalen Server), Oberfläche in `app/.../about/`.
Bewusste Entscheidungen:

- `ACTION_VIEW` über den FileProvider statt `PackageInstaller`: weniger Code, Android führt selbst durch die Installation.
- Kein Vordergrund-Dienst: Ein Download läuft, solange der Prozess lebt. Endet er, setzt das nächste Angebot die Teildatei fort.
- `update.json` wird nicht signiert. Schutz bieten HTTPS und die Prüfung gegen das Zertifikat der installierten App: Ohne den Schlüssel lässt sich nichts installieren. Auch eine alte, echt signierte Version lässt sich nicht als neu ausgeben, weil der `versionCode` in der APK zum Angebot passen muss und höher als der installierte sein muss.
- `REQUEST_INSTALL_PACKAGES` wäre bei einem späteren Play-Eintrag nicht zulässig. Dann müsste die Funktion in eine eigene Variante.

**Nur mit zwei echten Versionen auf einem Gerät prüfbar** (Anleitung in UPDATE.md):

- Signaturvergleich über `getPackageArchiveInfo` mit echten APKs
- Führung zur Einstellung „Unbekannte Apps“ und die Rückkehr, auch wenn Android dabei den Prozess beendet
- die Installation selbst, Daten danach noch da, Aufräumen beim nächsten Start
- Fortsetzen nach echtem Netzabbruch und die Weiterleitung von GitHub zum Download-Host
- der echte Abruf von `raw.githubusercontent.com`: `update.json` ist noch nicht veröffentlicht, die App bekommt bis dahin HTTP 404 und bleibt beim automatischen Start still. **Vor dem ersten Release ist die Prüfung per Knopf also ein sichtbarer Fehler**

## Lint

`:app:lintRelease` läuft mit `abortOnError` über alle Module (`checkDependencies`) und ist in CI Pflicht. Ergebnis: 1 Fehler, 96 Warnungen, 14 Hinweise.

Der eine Fehler steht begründet in `app/lint-baseline.xml`: `UnusedBoxWithConstraintsScope` in `feature/study/.../Cards.kt:428` (Besitzer „lernen“). Kein Fehlverhalten, nur eine unnötige Unterkomposition. Die Baseline enthält **nur** Fehler, Warnungen bleiben im Bericht sichtbar.

Warnungen, die man sich ansehen sollte:

| Warnung | Wo | Einordnung |
|---|---|---|
| `InsecureBaseConfiguration` | `network_security_config.xml` | Gewollt, siehe Cleartext |
| `TrustAllX509TrustManager` | `bcpkix-jdk15to18-1.72.jar` | Von Bouncy Castle über pdfbox, nicht aus dem eigenen Code |
| `InlinedApi` `CONFIRM`-Haptik | `BookCanvasView.kt:892,1033`, `NotebookCanvasView.kt:797` | Unter API 30 kein Haptik-Effekt, kein Absturz. Besitzer: buecher, heft |
| `ExifInterface` | `ScanStore.kt:255`, `StudyImages.kt:61` | Die Plattform-Klasse hat auf alten Geräten bekannte Fehler. Besitzer: handy, lernen |
| `ApplySharedPref` | `BackupSettings.kt:113`, `StudyAssistant.kt:39` | Läuft dort auf dem IO-Thread, unkritisch |
| `DrawAllocation` | `NotebookCanvasView.kt:1169,1259`, `BookCanvasView.kt:1298` | Prüfen bei Ruckeln |
| `UseKtx` (55), `UseTomlInstead` (9) | überall | Stil |


## Texte

Geprüft: alle 1568 UI-Zeichenketten aus dem Kotlin-Code, mit automatischer Rechtschreibprüfung (de) und per Durchsicht aller Treffer. Alle unbekannten Wörter sind Fachbegriffe oder Code. **Echte Tippfehler habe ich keine gefunden.** Keine Stelle siezt (die beiden „Sie“-Treffer beziehen sich auf eine Stelle und eine Version). Keine Änderungen außerhalb meines Bereichs.

Einheitlichkeit, zum Entscheiden durch die Besitzer:

| Punkt | Wo | Vorschlag |
|---|---|---|
| **Anrede gemischt.** Die App spricht meist unpersönlich („Bitte den Ordner neu wählen“), an wenigen Stellen per du | du: `AssistantScreen.kt:139` „Deine Frage“, `Assistant.kt:161` „Trag ihn … ein“, `StudyHome.kt:370` „Leg einen Stapel an“, `ShkScreen.kt:504` „Tippe im Formelblatt“ (dazu der Handy-Stand). Unpersönlich: etwa 25 „Bitte …“-Sätze, vor allem `SyncClient.kt`, `BackupManager.kt`, `BackupScreen.kt`, `BackupCrypto.kt`, `Clip.kt:94`, `TimetableScreen.kt:506` | Auf du umstellen, z. B. „Bitte wähl den Ordner neu.“ Das ist ein eigener kleiner Durchgang je Besitzer |
| Imperativ uneinheitlich | „Tippe“ (`ShkScreen.kt:504`) gegen „Trag“, „Leg“ | Kurzform ohne -e |
| **„Tablet“ in Meldungen, die auch das Handy zeigt** | `SyncClient.kt:392` „ist das Tablet im selben WLAN?“, `BookSearch.kt:178` „liest sie auf dem Tablet“. Tablet-only und unkritisch: `MathInk.kt:204`, `InkModelPanel.kt:67` | „Gerät“ |
| Schriftmodell gegen Handschrift-Modell | `HandwritingRecognizer.kt:36,38,41,43,177,186,208` gegen `MathInk`, `InkModelPanel`, Bibliothek | Ein Wort: „Handschrift-Modell“ |
| Sicherung gegen Backup | `BackupManager.kt:344`, `BackupScreen.kt:741,825`, `FullBackup.kt:120` gegen sonst überall „Backup“ | Überschrift bleibt „Sichern“, Substantiv „Backup“ |
| S Pen | `AirCommand.kt:51,56` | Passt: „S Pen“, zusammengesetzt „S-Pen-Einstellungen“ |
| Kleiner Wortlaut | `Assistant.kt:161` „wird ein API-Schlüssel gebraucht“ | „braucht einen API-Schlüssel“ |

Eigene Texte (`about/`) sind per du. Hilfe, Einführung und Datenschutz sind sachlich an den Code angelehnt, aber nicht im Betrieb gegengelesen.

## Datenfluss (für den Hinweis in der App)

Ergebnis der Code-Prüfung, steht in „Über → Datenschutz“: Update-Prüfung bei GitHub, Modell-Download von Google, Erklärhilfe an Anthropic (nur mit Schlüssel), eigener Server, und ein Punkt, der nicht im Auftrag stand:
**ML Kit sendet laut Google technische Nutzungsdaten samt einer Kennung pro Installation an Google** und bietet dafür keinen beschriebenen Schalter ([Quelle](https://developers.google.com/ml-kit/android-data-disclosure)). Der Inhalt bleibt auf dem Gerät. „Nichts verlässt das Gerät“ darf die App also nicht sagen.
Der Text nennt keinen Anbieter, weil keine erfundenen Angaben hineinsollen. Sobald die App an andere geht, gehört ein vollständiger Datenschutztext mit Namen und Kontakt dazu, und das sollte rechtlich geprüft werden, weil auch Minderjährige sie nutzen. Das gilt auch ohne Store. Anthropic verlangt bei Produkten für Minderjährige eigene Schutzmaßnahmen, und seine Verbraucherbedingungen setzen 18 Jahre an ([Leitlinie](https://support.claude.com/en/articles/9307344-responsible-use-of-anthropic-s-models-guidelines-for-organizations-serving-minors)). Die Erklärhilfe nennt heute Claude, sagt dort aber nicht ausdrücklich „KI“ und prüft kein Alter. Der Hinweis steht bisher nur unter „Über“; in die Erklärhilfe gehört er durch „lernen“.

## Offen

- Die Erklärhilfe nutzt das Modell `claude-haiku-5-5` (`Assistant.kt`). Nicht gegen die API geprüft.
- `applicationId` endet auf `.neu`. Ohne Store ist das egal; ein Wechsel würde alle Installationen trennen.
- Der Sync-Server hat keine Funktion zum Löschen eines Kontos (`app.py`). Das betrifft nur eigene Server.
- Native Abstürze auf Android 8 bis 10 werden nicht erfasst.
