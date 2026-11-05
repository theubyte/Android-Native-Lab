# Prüfbericht „sichern“

Stand 05.11.2026, Branch `agent/sichern`, enthält `agent/heft` und `agent/handy`.
Zeilenangaben bei „gefunden“ beziehen sich auf den Stand vor der Arbeit (`ecff577`).

## Geprüft

Backup/Restore-Rundlauf, Abbruch mittendrin, zu wenig Speicher, Zip-Slip, Verschlüsselung (Parameter, Authentifizierung, falsches Passwort), V15-Import, Auto-Backup-Rotation, Tokens, Server `app.py` (Authentifizierung, Pfade, Upload-Grenzen, Rate-Limit, atomares Schreiben), BackupScreen ab 360 dp.

Für gut befunden und unverändert:

- AES-256-GCM in Blöcken mit Zähler und Letzter-Block-Flag, PBKDF2 600 000 Runden, Kopf als AAD. Umordnen, Abschneiden und Anhängen fallen auf. Falsches Passwort und Beschädigung werden unterschieden. Iterationszahl im Kopf kann nicht unbemerkt gesenkt werden.
- Zip-Slip und Zip-Bomben: `ZipPaths`, Manifest-Abgleich, Abbruch über angekündigter Größe.
- Rotation: nur Dateien mit Auto-Präfix werden gelöscht, Zeitstempel im Namen.
- Tokens und PIN nur im Keystore-Tresor, nicht im Backup, keine Weiterleitungen, kein Token in URLs.

## Gefunden und behoben

| # | Stelle | Fehler | Behoben |
|---|---|---|---|
| 1 | `app/.../data/BackupWiring.kt:44` | Hefte nur als Ganzes nach `updatedAt` zusammengeführt: Scan auf dem Handy plus Schreiben auf dem Tablet verliert eine Seite der Änderungen, schlimmster Fehler | `NotebookMerge` + `NotebookSyncParticipant`, seitenweise mit Dreiwegevergleich |
| 2 | `BackupWiring.kt:83` | Lesezeichen, Sperre, Papier ohne Zeitstempel gingen verloren | Feldweise gegen die Basis |
| 3 | `BackupWiring.kt:90` (`copyIn`) | Löscht das Ziel, wenn das Umbenennen scheitert; setzt zudem die Dateizeit nicht | `atomicCopy` über `SafeFile`, auch in `FolderMergeParticipant` |
| 4 | Gelöschte Seiten und Hefte | Kamen beim Zusammenführen zurück | Grabsteine in `files/sync/tombstones.json`, entstehen aus dem Vergleich mit dem zuletzt Bekannten |
| 5 | `FullBackup.kt:213` (`swap`) | Prozess stirbt beim Tausch: Daten liegen in `restore-old`, und die nächste Wiederherstellung **löscht sie** | Journal, `FullBackup.recover` bei jedem App-Start (`AutoBackupInitializer`), auch vor jedem neuen Tausch |
| 6 | `BackupManager.kt:258` | Nach gescheitertem Tausch bleiben die Datenbanken geschlossen, App läuft weiter | Neustart wird verlangt, Meldung „fehlgeschlagen, Daten unverändert“ |
| 7 | `FullBackup.kt:214` | Backup konnte fremdes Konto einschleusen, wenn die geräteeigene Prefs-Datei hier fehlt | Solche Pfade werden aus dem Stage entfernt |
| 8 | `FullBackup.extractVerified`, `unlock` | Zu wenig Speicher erst mitten im Vorgang | `Space.require` vorab |
| 9 | `BackupManager.kt:381` | Rohe englische Systemtexte in der Oberfläche | `BackupErrors` |
| 10 | `BackupManager.kt` | Abgestürzte Läufe ließen entschlüsselte Kopien im Cache | `sweepOld` |
| 11 | `AutoBackup.kt:137` | Alte Backups wurden gelöscht, ohne die Größe der neuen Datei zu prüfen | Längenprüfung vor Umbenennen und Rotation |
| 12 | `V15Backup.kt:76` | `bytes()` ohne Größengrenze, Speicherüberlauf durch manipulierte Datei | Grenze wie bei `text()` |
| 13 | `BackupScreen.kt:264` | Zurück während des Laufs bricht Import oder Vorgang durch Verlassen ab | Während des Laufs gesperrt, dafür gibt es „Abbrechen“ |
| 14 | `app.py` `/api/login` | Kein Rate-Limit: sechsstellige PIN durchprobierbar | Gestaffelte Wartezeit, Konto-Sperre, `429` |
| 15 | `app.py` `/api/login` | Laufzeit verrät, ob ein Konto existiert | Hash auch bei unbekannter Adresse |
| 16 | `app.py` `upload` | Direktes Schreiben: abgebrochener Schreibvorgang wird neueste Version | Temp-Datei, fsync, Umbenennen; Inhaltsprüfung auf ZIP oder SRBK |
| 17 | `app.py` | Volle Platte: `500` und angefangene Datei | `507` vorab und als Fehlerbehandlung |
| 18 | `app.py` `register` | E-Mail ohne Längen- und Typprüfung, Verbindung blieb bei Konflikt offen, Unicode-Ziffern in PIN, unbegrenzte Registrierung | Geprüft, `closing`, Limit, optional `REGISTRATION=closed` |
| 19 | `app.py` `latest` | Fehler, wenn der Ordner noch nicht existiert | Wird angelegt |
| 20 | `BackupScreen.kt` | 32 dp Rand, feste Breiten (120, 190 dp), Knopfzeilen laufen aus dem Bild | Schmales Layout unter 600 dp |
| 21 | `SyncAccount.kt:104` | Hochladen konnte jeden neuesten Stand stillschweigend ablösen | Eigener Abgleichkanal mit Compare-and-Swap, Vollbackups berühren ihn nicht |

## Gebaut

Zusammenführen seitenweise, Ein-Knopf-Abgleich mit Teilübertragung auf Dateiebene, Abgleich über Datei auf demselben Weg, automatischer Abgleich, Server API 2. Die Teilübertragung wurde gebaut, weil ein Scan sonst bei jedem Abgleich alle Buch-PDFs bewegt hätte: Inhaltsadressierte Dateien auf dem Server, Dateiliste mit Compare-and-Swap, Wiederaufnahme nach Abbruch auf Dateiebene.

## Getestet

- `:core:test` grün, darunter `NotebookMergeTest` und `SyncEngineTest`. `SyncEngineTest` spielt ganze Geräte mit echtem Merge-Code und echten Dateien durch, nur die Datenbank ist eine JSON-Datei: Handy scannt/Tablet schreibt, Konflikt, Löschen, Verschieben, Hefte zusammenführen, Lesezeichen, Netzabbruch an jeder Stelle, schnelleres zweites Gerät, Verschlüsselung, drei Geräte, Dateitausch.
- `SyncEngineTest.realServerFlow` und `SyncClientTest.realServer` liefen gegen den echten Flask-Server (lokal unter Windows, Flask-Entwicklungsserver, ohne Docker und gunicorn).
- `test_app.py`: Altes API, Abwärtskompatibilität einer bestehenden `users.db`, Authentifizierung, Pfade, Grenzen, Rate-Limit, Compare-and-Swap, Aufräumen.
- `:app:assembleDebug` grün.

## Nicht getestet

- **Nichts davon lief auf einem Gerät oder Emulator.** Das gilt für `RoomNotebookStore`, `withTransaction`, `SyncWorker`, `AutoSync`, `ShareFileProvider`, das Teilen und `recoverInterrupted` im echten App-Start.
- **BackupScreen bei 360 dp ist nur gebaut, nicht angesehen.** Hell und dunkel ebenso.
- Echter Unraid-Server mit Docker und zwei gunicorn-Workern. Der Compare-and-Swap liegt in SQLite und sollte zwischen Workern halten; nachgestellt wurde das nicht.
- Echtes Volllaufen des Speichers; geprüft ist nur die Vorabprüfung und die Übersetzung der Meldung. Das Backup prüft vorab keinen Platz (komprimierte Größe unbekannt), löscht aber bei Fehler die angefangene Datei.
- Abbruch mittendrin bei `createLocked` und `exportTo` nur durchgelesen, kein Test.

## Offen

- **Bücher, Lernen, SHK:** Löschen wandert nicht mit, Gelöschtes kommt beim Abgleich zurück (Richtung ohne Datenverlust). Buch-Anmerkungen laufen weiter „neuere Datei gewinnt“, ohne Konfliktkopie. Auf dem Handy wird nicht geschrieben, daher selten, aber nicht ausgeschlossen. Bücher brauchen Grabsteine auf Buchebene, das ist eine Sache für `buecher` und `sichern` gemeinsam.
- **Erster Abgleich eines Geräts lädt alle Bücher.** Eine Einstellung „Bücher auf diesem Gerät nicht laden“ fehlt, die Dateiliste müsste dafür fremde Einträge weitertragen.
- **Frühere Abgleichstände:** Der Server hebt 10 auf, die App bietet noch kein Zurückgehen an. Vollbackups bleiben der Weg zurück.
- **Rollback per „Ersetzen“:** Wer ein altes Backup einspielt und danach abgleicht, bekommt den neueren Stand vom Server zurück. Kein Datenverlust, aber der Rückschritt hält nicht. Hinweistext fehlt.
- **Massenlöschung:** Über 60 % und mindestens 30 Seiten auf einmal werden nicht übertragen und kommen zurück. Absicht, kann aber beim echten Aufräumen überraschen; die Warnung erscheint.
- **Falsche Geräteuhr:** Ein Konflikt entscheidet nach Dateizeit; bei falscher Uhr liegt die neuere Fassung als Kopie hinten, verloren geht nichts.
- **V15-Import** zweimal ausgeführt legt Hefte doppelt an (`V15Sink`, Besitz `heft`).
- **Kein TLS** auf dem Server, im Heimnetz gewollt, siehe README.
