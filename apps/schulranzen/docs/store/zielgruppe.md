# Inhaltseinstufung und Zielgruppe

**Das hier ist eine Vorbereitung, keine Rechtsberatung. Weil die App auch von Minderjährigen genutzt wird, lass das vor der Veröffentlichung rechtlich prüfen.**

## Inhaltseinstufung (IARC-Fragebogen)

Pflicht, in Play Console → App-Inhalte. Die Antworten erzeugen die Altersstufen (in Deutschland USK); falsche Antworten können zur Entfernung führen ([Play-Hilfe](https://support.google.com/googleplay/android-developer/answer/9859655)).

Vorschlag nach dem heutigen Stand: Kategorie „Referenz, Nachrichten oder Bildung“. Keine Gewalt, keine sexuellen Inhalte, keine Glücksspiele, kein Austausch zwischen Nutzern, keine Standortfreigabe.

**Sonderfall Erklärhilfe:** Sie zeigt von einer KI erzeugten Text, den die App nicht vorab prüft. Das bei den Fragen zu nutzererzeugten und KI-erzeugten Inhalten ehrlich angeben und Googles aktuelle Regeln für KI-Funktionen prüfen.

## Zielgruppe: die eigentliche Entscheidung

| Wahl | Folge |
|---|---|
| **Enthält unter 13** | Die [Familien-Richtlinie](https://support.google.com/googleplay/android-developer/answer/9893335) gilt vollständig. Prüfpunkte siehe unten. |
| **Erst ab 13, aber unter 18** | Die Familien-Anforderungen gelten laut Google nicht für Apps ohne Kinder in der Zielgruppe. Datenschutzrecht und Jugendschutz gelten trotzdem. |
| **Nur ab 18** | Passt nicht: Die App soll auch von Schülern unter 18 genutzt werden. |

**Falle beim Namen:** Eine Angabe „ab 13“ schützt nicht automatisch. Wer „Schulranzen“ hört, denkt an Grundschulkinder. Google kann eine App auch nach Aufmachung und Inhalt einordnen. Schon der Name spricht also gegen eine reine Einstufung ab 13. Tatsächlich ist die App auf Azubis und ältere Schüler ausgerichtet, Store-Text und Bilder sollten das klar zeigen. **Auch das ist ein Grund, den Namen zu überdenken.**

**Empfehlung zum Prüfen:** Zielgruppe ab 13 (nur wenn die Prüfung das hergibt) oder ab 16 mit passender Beschreibung. Die Entscheidung liegt bei dir.

### Wenn Kinder unter 13 dazugehören

Das wäre mindestens zu klären:

- **ML-Kit-Nutzungsdaten** enthalten eine Kennung pro Installation. Ob das bei Kindern zulässig ist, muss geprüft werden. Das ist der größte offene Punkt.
- **Erklärhilfe:** müsste für Kinder wahrscheinlich gesperrt werden.
- Werbung: keine vorhanden, also keine Anforderungen an Werbe-Bibliotheken. Standort wird nicht erfasst.
- Datenschutzerklärung und Store-Text müssen auf Kinder eingehen.

## Datenschutzrecht

- **Art. 8 DSGVO:** Beruht die Verarbeitung bei einem Kind auf Einwilligung, gilt in Deutschland die Grenze von 16 Jahren; darunter müssen die Eltern zustimmen. Ob das hier greift, hängt von der Rechtsgrundlage ab. Das betrifft vor allem ML Kit und die Erklärhilfe.
- Texte für Minderjährige müssen verständlich sein (Art. 12 DSGVO).
- Jugendschutz in Deutschland (JuSchG, Vorsorgepflichten für Anbieter) prüfen lassen.

## Erklärhilfe mit Claude und Minderjährige

Das steht in Anthropics Regeln:

- Die [Verbraucherbedingungen](https://anthropic.com/legal/terms) verlangen mindestens 18 Jahre (oder das örtliche Mindestalter, wenn es höher ist).
- Organisationen, deren Produkte Minderjährige über die API direkt nutzen, sollen laut [Anthropic-Leitlinie](https://support.claude.com/en/articles/9307344-responsible-use-of-anthropic-s-models-guidelines-for-organizations-serving-minors) drei Dinge erfüllen: technische Schutzmaßnahmen (etwa Altersprüfung, Inhaltsfilter, Überwachung), die Einhaltung von Kinder- und Datenschutzrecht samt öffentlicher Erklärung, und den Hinweis, dass man mit einer KI spricht.

Daraus folgt für die App:

- Ein Schüler unter 18 kann sich vermutlich kein eigenes Konto mit Schlüssel holen. Die Formulierung „eigener Schlüssel“ allein löst das Problem nicht. Die **genauen API-Bedingungen** sind rechtlich zu prüfen, denn dieser Entwurf stützt sich nur auf die verlinkten Seiten.
- Die App hat heute weder Altersprüfung noch Filter. Die Erklärhilfe nennt zwar „Claude“, ein ausdrücklicher Hinweis „Du sprichst mit einer KI, Antworten können falsch sein“ fehlt.
- **Vorschlag für Agent „lernen“** (Bereich `feature/study/`): ein Hinweis in der Einrichtung: „Du sprichst mit einer KI. Nur ab 18 oder mit dem Schlüssel und dem Einverständnis deiner Eltern.“ Der genaue Wortlaut hängt an der Prüfung.
- Alternative: Die Erklärhilfe in der verkauften Fassung weglassen. Alles andere läuft ohne sie.

## Weitere Punkte in der Play Console

- Werbung: „Nein“.
- App-Zugriff: Ohne Anmeldung nutzbar. Auf den Sync-Server (eigener Server nötig) und die Erklärhilfe (eigener Schlüssel) im Hinweis an die Prüfer aufmerksam machen.
- Behörden, Gesundheit, Finanzen, Nachrichten: trifft nicht zu.
