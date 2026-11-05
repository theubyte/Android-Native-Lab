# Updates ohne Store

Die App holt neue Versionen selbst von GitHub. Nichts installiert sich ohne Zustimmung.

## So läuft es in der App

1. Beim Start höchstens einmal am Tag (abschaltbar) oder per „Über → Nach Updates suchen“ liest die App
   `https://raw.githubusercontent.com/theubyte/Android-Native-Lab/main/apps/schulranzen/update.json`.
2. Ist `versionCode` höher als die installierte Version, zeigt sie Version und Änderungen.
3. Auf Wunsch lädt sie die APK für die ABI des Geräts, mit Fortschritt und Abbruch. Nach einem Netzabbruch geht es an derselben Stelle weiter.
4. Sie prüft SHA-256, Paketname, `versionCode` und das Signaturzertifikat gegen die installierte App. Bei einer Abweichung löscht sie die Datei und meldet das.
5. Sie führt einmalig zur Einstellung „Unbekannte Apps installieren“, dann installiert Android nach eigener Rückfrage. Hefte und Lerndaten bleiben erhalten, weil Paketname und Schlüssel gleich bleiben.
6. Beim nächsten Start räumt sie die geladene APK weg.

Offline oder bei Serverfehlern bleibt die automatische Prüfung still. Nur der Knopf meldet einen Fehler.

## Neue Version veröffentlichen

Voraussetzung: `signing/keystore.properties` ist lokal da, `gradle`, `python3` und `gh` sind im Pfad.

1. In `app/build.gradle.kts` `appVersion` erhöhen. Für einen zweiten Build derselben Version `appBuild` erhöhen.
2. Bauen und `update.json` schreiben. Das veröffentlicht noch nichts:
   ```
   cd apps/schulranzen
   tools/release.sh "Kurzer Änderungstext auf Deutsch"
   ```
3. Die ausgegebenen Befehle ausführen, **in dieser Reihenfolge**: erst das Release (`gh release create`, oder das Skript mit `--publish`), dann `update.json` committen und pushen. Umgekehrt bekämen Geräte eine Adresse, die es noch nicht gibt.

Tag: `schulranzen-v<version>`. Das Repo enthält mehrere Apps, darum nutzt die App nie „latest release“.

**Den Schlüssel nie verlieren.** Mit einem anderen Schlüssel weist die App das Update ab, und auch Android würde es nicht installieren.
Eine Version vor 0.5 hat die Update-Funktion noch nicht: 0.5.0 kommt einmal von Hand aufs Gerät.

## versionCode

`major·1 000 000 + minor·10 000 + patch·100 + build`, also 0.5.0 = 50000. Alle ABI-APKs einer Version tragen denselben Code; ein Gerät installiert immer nur seine eigene.

## update.json

```json
{
  "versionCode": 50100,
  "versionName": "0.5.1",
  "changes": "Änderungstext",
  "apks": {
    "arm64-v8a": {
      "url": "https://github.com/theubyte/Android-Native-Lab/releases/download/schulranzen-v0.5.1/schulranzen-0.5.1-arm64-v8a.apk",
      "sha256": "64 Hex-Zeichen",
      "size": 45000000
    }
  }
}
```

Nur HTTPS. `size` ist freiwillig. Unbekannte Felder werden ignoriert, spätere Versionen können also ergänzen.

## Mit zwei echten Versionen testen (Emulator)

Nur Debug-Builds lesen die Datei `files/update-debug-url.txt`. Steht darin eine Adresse, nimmt die App sie statt GitHub und erlaubt dafür auch http. Im Release ist der Code nicht vorhanden.

```
# Version A (niedriger Code) installieren
gradle :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell "run-as de.millione.schulranzen.neu sh -c 'echo http://10.0.2.2:8000/update.json > files/update-debug-url.txt'"

# Version B: appBuild erhöhen, bauen, in einen Ordner legen
gradle :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk /tmp/upd/b.apk
sha256sum /tmp/upd/b.apk
# /tmp/upd/update.json von Hand schreiben: Code von B, url http://10.0.2.2:8000/b.apk, sha256, ABI des Emulators
cd /tmp/upd && python3 -m http.server 8000
```

Danach prüfen: Angebot erscheint, Download mit Fortschritt, Abbruch und Fortsetzen, Führung zur Einstellung, Installation, Hefte noch da, APK im Cache weg.
Für die Abweisung eine APK mit anderem Schlüssel und passender SHA-256 anbieten, und einmal eine falsche SHA-256 eintragen.
