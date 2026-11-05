# Digitaler Schulranzen – Unraid Sync Backend

**Bekannter Backend-Stand:** 05.11.2026 (API 2)
**Standard-Port:** `8788`

Der Server ist eine kleine Flask-Anwendung für Benutzerkonto, Backup-Upload, Backup-Versionen, Wiederherstellung und den seitenweisen Abgleich zwischen Geräten.

## Aktualisieren

Im Unraid-Terminal:

```
cd /mnt/user/appdata/digitaler-schulranzen-sync && curl -fsSLO https://raw.githubusercontent.com/theubyte/Android-Native-Lab/main/apps/digitaler-schulranzen/server/unraid-sync/app.py && docker compose up -d --build
```

Konten, Backups und Anmeldungen bleiben erhalten: `users.db` wird nur um neue Tabellen ergänzt, Backups liegen weiter unter `data/backups/`. Ältere App-Versionen (auch V15) arbeiten unverändert weiter. Neu ist nur der Ordner `data/sync/` für den Abgleich.

Ein Server ohne dieses Update bleibt für Vollbackups nutzbar; der Knopf „Jetzt abgleichen" sagt dann, dass der Server aktualisiert werden muss.

## API-Stand

Vollbackups (wie bisher, auch für V15):

- `GET /health` (neu: Feld `api`)
- `POST /api/register`
- `POST /api/login`
- `POST /api/sync/upload` (neu: nimmt nur noch ZIP oder das verschlüsselte Format an)
- `GET /api/sync/versions`
- `GET /api/sync/latest`
- `GET /api/sync/version/<name>`

Abgleich (API 2):

- `GET /api/sync/head` → `{rev, manifest}`, `rev` ist `null`, solange es keinen Stand gibt
- `POST /api/sync/blobs/missing` mit `{ids}` → `{missing}`
- `PUT /api/sync/blob/<id>` mit `X-Content-SHA256`, `GET /api/sync/blob/<id>`
- `POST /api/sync/commit` mit `If-Match: "<rev>"` oder `If-None-Match: *` und `{manifest, blobs, device}`; `412`, wenn inzwischen ein anderer Stand da ist, `409`, wenn Dateien fehlen, `428` ohne Bedingung

Ein Stand wird nur per Vergleichen-und-Tauschen ersetzt: Wer auf einem veralteten Stand aufbaut, bekommt `412`, führt neu zusammen und versucht es noch einmal. Die Dateiliste ist für den Server undurchsichtig; bei eingeschalteter Verschlüsselung sind Liste und Dateien verschlüsselt und die Namen der Dateien verraten nichts über den Inhalt.

Vollbackups und Abgleich sind getrennt: Ein Vollbackup ändert den Abgleichstand nicht, und der Abgleich füllt die Versionsliste nicht.

## Sicherheit

Benutzerkonten bestehen aus E-Mail-Adresse und sechsstelliger PIN. PINs werden mit PBKDF2-HMAC-SHA256 und individuellem Salt gespeichert. Login-Tokens sind HMAC-signiert und 30 Tage gültig.

Fehlversuche beim Login werden gebremst: nach 5 Fehlversuchen je Adresse und Konto wartet der Server 30 Sekunden, dann immer länger bis 15 Minuten; nach 50 Fehlversuchen ist das Konto eine Stunde gesperrt. Antwort `429` mit `Retry-After`. Neue Konten: höchstens 10 pro Stunde je Adresse.

Alles wird über Temp-Dateien geschrieben und erst fertig umbenannt. Ist die Platte zu voll, antwortet der Server `507`, bevor etwas geschrieben wird.

Der Server hat keine eigene TLS-Terminierung und ist für das Heimnetz gedacht. PIN und Token gehen sonst im Klartext durchs Netz. Von außen nur über VPN oder einen HTTPS-Proxy erreichbar machen, und die Backup-Verschlüsselung in der App einschalten.

## Einstellungen (`compose.yml`, alle optional)

| Variable | Standard | Bedeutung |
|---|---|---|
| `MAX_VERSIONS` | 20 | Vollbackups je Konto |
| `MAX_UPLOAD_MB` | 1024 | Größte einzelne Übertragung |
| `MAX_SYNC_HEADS` | 10 | Aufbewahrte frühere Abgleichstände mit ihren Dateien |
| `REGISTRATION` | `open` | `closed`: keine neuen Konten, sobald eines existiert. Empfohlen, sobald dein Konto angelegt ist. |
| `RESERVE_MB` | 50 | Bleibt auf der Platte immer frei |

## Tests

```
pip install Flask==3.1.2
python -m unittest test_app
```
