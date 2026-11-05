# Datensicherheits-Formular (Entwurf)

Antworten für Play Console → App-Inhalte → Datensicherheit, Stand Version 0.5.0.
Grundlage ist der Code, nicht die Absicht. **Das ist keine Rechtsberatung, und die Verantwortung für die Angaben liegt bei dir als Entwickler.**

## Was wirklich abfließt

Google definiert „Erheben“ als jedes Übertragen vom Gerät weg, auch über Bibliotheken ([Play-Hilfe](https://support.google.com/googleplay/android-developer/answer/10787469)).

| # | Weg | Empfänger | Was | Auslöser | Verschlüsselt | Beleg im Code |
|---|---|---|---|---|---|---|
| 1 | Erklärhilfe, Kartenvorschläge | Anthropic (`api.anthropic.com`) | Frage, Kartentext, erkannter Text, der eigene API-Schlüssel als Header | Nur Knopfdruck, nur mit eigenem Schlüssel | HTTPS | `core/study/Assistant.kt`, `feature/study/StudyAssistant.kt` |
| 2 | Sync | Eigener Server des Nutzers | Komplettes Backup (Hefte, Bilder, Bücher, Lerndaten), E-Mail und 6-stellige PIN | Knopfdruck oder selbst eingerichtetes Auto-Backup | **Oft nicht:** meist `http://` im Heimnetz. Inhalt nur, wenn die Backup-Verschlüsselung an ist | `core/backup/SyncClient.kt`, `feature/backup/` |
| 3 | Modell-Download | Google | Normale Download-Anfrage, kein Inhalt | Knopfdruck, bei Rechnen auch beim ersten Ausrechnen | HTTPS | `feature/handwriting/HandwritingRecognizer.kt`, `feature/rechnen/MathInk.kt` |
| 4 | **ML-Kit-Nutzungsdaten** | Google | Gerätedaten (Hersteller, Modell, Android-Version), Paketname und App-Version, Messwerte (Laufzeit), Einstellungen der Schnittstelle, Größe von Ein- und Ausgabe, Fehlercodes, eine **Kennung pro Installation** (bei gebündelten Funktionen), bei Handschrift die eingestellte Sprache | Automatisch, sobald Text- oder Handschrifterkennung läuft | HTTPS | Angabe von Google: [ML Kit Data Disclosure](https://developers.google.com/ml-kit/android-data-disclosure). Im Manifest: `TransportBackendDiscovery`, `JobInfoSchedulerService` |

Punkt 4 stand nicht im Auftrag und ist der wichtigste Befund: Der **erkannte Text und die Bilder bleiben auf dem Gerät**, aber „nichts verlässt das Gerät“ wäre falsch. Google nennt in der Beschreibung keinen Schalter zum Abschalten.

Fließt **nicht** ab: Werbe-ID, Standort, Kontakte, Kamerabilder (außer im Backup), Absturzberichte (nur wenn der Nutzer sie selbst teilt).
Die App enthält keine Werbung und keine Analyse-Bibliothek. Der Blick in den zusammengeführten Manifest und die Abhängigkeitsliste bestätigt das.

## Antworten

**Erhebt oder teilt deine App erforderliche Nutzerdaten?** → **Ja** (wegen Punkt 4 und, wenn benutzt, 1 und 2).

**Werden alle Nutzerdaten bei der Übertragung verschlüsselt?** → **Nein.**
Punkt 2 läuft im Heimnetz meist über `http://`. „Ja“ wäre eine Falschangabe, solange die App das erlaubt. Der Eintrag zeigt dann öffentlich, dass Daten nicht verschlüsselt übertragen werden. Der einzige Weg zu „Ja“ wäre, für den Sync HTTPS zu erzwingen. Das ist deine Entscheidung und kostet die einfache Einrichtung auf Unraid.

**Können Nutzer das Löschen ihrer Daten beantragen?** → Kein Konto beim Entwickler.
Die Daten bei Punkt 2 liegen beim Nutzer selbst, bei Punkt 1 in seinem eigenen Anthropic-Konto. Für Punkt 4 gibt es von dir aus keinen Löschweg. Die Antwort rechtlich prüfen lassen.

### Datentypen

| Datentyp im Formular | Erhoben | Geteilt | Optional | Zweck | Quelle |
|---|---|---|---|---|---|
| App-Informationen und Leistung → Absturzprotokolle | Nein | Nein | | | Bericht geht nur manuell über das Teilen-Menü |
| App-Informationen und Leistung → Diagnosedaten | **Ja** | Nein | Nein (*) | Analysen | 4 |
| Gerät oder andere IDs | **Ja** | Nein | Nein (*) | Analysen | 4: Kennung pro Installation |
| App-Aktivitäten → Sonstige von Nutzern erstellte Inhalte | **Ja** | Nein (**) | Ja | App-Funktionen | 1, 2 |
| Fotos und Videos → Fotos | **Ja** | Nein (**) | Ja | App-Funktionen, nur im Backup | 2 |
| Dateien und Dokumente | **Ja** | Nein (**) | Ja | App-Funktionen, nur im Backup | 2 |
| Personenbezogene Daten → E-Mail-Adresse | **Ja** | Nein (**) | Ja | Kontoverwaltung, nur eigener Server | 2 |
| Personenbezogene Daten → Nutzer-IDs | **Ja** | Nein (**) | Ja | Kontoverwaltung, nur eigener Server | 2 |
| Standort, Kontakte, Finanzen, Gesundheit, Nachrichten, Audio, Kalender, Web-Verlauf | Nein | Nein | | | im Code nicht vorhanden |

(*) Nicht abschaltbar, außer der Nutzer verwendet die Erkennung gar nicht. Ob „optional“ passt, weil die Funktionen selbst freiwillig sind, ist eine Auslegungsfrage.

(**) Vom Nutzer selbst ausgelöste Übertragung zählt laut Google nicht als Teilen. Beim Auto-Backup hat der Nutzer das Ziel selbst eingerichtet.

## Offene Auslegungsfragen für die rechtliche Prüfung

1. **Eigener Server und eigener Anthropic-Schlüssel:** Der Wortlaut „vom Gerät übertragen“ erfasst beide, obwohl du als Entwickler nichts davon siehst. Der Entwurf gibt sie deshalb vorsichtig als erhoben an. Weniger anzugeben wäre angreifbar.
2. **ML-Kit-Daten und Google als Empfänger:** Laut Google gehen die Daten nicht an Dritte. Der Entwurf wertet das als „nicht geteilt“.
3. **Kinder:** Eine Kennung pro Installation bei Minderjährigen, siehe [zielgruppe.md](zielgruppe.md).

## Mögliche Entscheidung: ML-Kit-Nutzungsdaten unterbinden

Technisch denkbar wäre, den `cct`-Sendeweg der Google-Bibliothek `transport-runtime` im Manifest zu entfernen. Das ist **nicht eingebaut**: Es ist kein dokumentierter Weg, es ist ohne Gerät nicht prüfbar, und es könnte gegen die Nutzungsbedingungen von ML Kit verstoßen. Die saubere Alternative wäre, die Erkennung durch eine andere Lösung zu ersetzen. Das ist viel Arbeit und deine Entscheidung.

## Wann das Formular neu auszufüllen ist

Bei jeder neuen Bibliothek mit Netzzugriff, wenn der Sync HTTPS erzwingt, wenn Benachrichtigungen, Konten oder ein Fremddienst dazukommen.
