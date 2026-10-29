# Digitaler Schulranzen – Unraid Sync Backend

**Bekannter Backend-Stand:** 05.10.2026  
**Standard-Port:** `8788`

Der Server ist eine kleine Flask-Anwendung für Benutzerkonto, Backup-Upload, Backup-Versionen und Wiederherstellung.

## API-Stand

- `GET /health`
- `POST /api/register`
- `POST /api/login`
- `POST /api/sync/upload`
- `GET /api/sync/versions`
- `GET /api/sync/latest`
- `GET /api/sync/version/<name>`

Benutzerkonten bestehen aus E-Mail-Adresse und sechsstelliger PIN. PINs werden mit PBKDF2-HMAC-SHA256 und individuellem Salt gespeichert. Login-Tokens sind HMAC-signiert.

Backups liegen pro Benutzer als versionierte ZIP-Dateien. Die Anzahl gespeicherter Versionen wird serverseitig begrenzt. Die aktuelle App verwendet den Server primär für vollständige Snapshots und Restore, nicht für dokumentweises Echtzeit-Synchronisieren.

Der aktuelle Stand besitzt selbst keine TLS-Terminierung. Externer HTTPS-Schutz ist daher Infrastruktur außerhalb dieses Repositories.
