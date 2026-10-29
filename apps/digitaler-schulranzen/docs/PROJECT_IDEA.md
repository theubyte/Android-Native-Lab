# Projektidee

## Grundidee

Der Digitale Schulranzen ersetzt möglichst viele physische und digitale Schulwerkzeuge durch eine einzige Tablet-App. Die App ist besonders für S-Pen-Nutzung im Unterricht ausgelegt und soll sich eher wie Papier und echte Schulmaterialien als wie eine klassische Business-App anfühlen.

## Schreibgefühl

Das Schreiben ist der Kern der App. Das gewünschte Gefühl ist direkt, natürlich und papiernah. Die App soll möglichst keine wahrnehmbare zusätzliche Stiftlatenz erzeugen. Druck und Neigung beeinflussen den Strich. Fingerbedienung, Zoom, Pan und Seitennavigation sollen die S-Pen-Eingabe nicht stören.

Während aktiver Stifteingabe haben Schreib-Rendering und Eingabepriorität Vorrang. Rechenhilfe, Analyse, Backup, Sync, Vorschaubilder und Animationen sind Nebenprozesse.

## Visuelle Richtung

Die gewünschte Designsprache ist eine Mischung aus:

- sehr sauberem, hellem Paper-Look,
- weichen Soft-3D-/Neumorphism-Flächen,
- sparsamen farbigen Licht- und Kantenakzenten,
- einem gleichwertigen dunklen Modus,
- sehr reduzierter Typografie,
- subtilen Ambient-Bewegungen auf der Hauptseite,
- flüssigen, organischen Liquid-/Morph-Animationen bei Interaktionen,
- möglichst wenig klassischen Android-Karten, Chips und Popup-Menüs.

Fachfarben sollen nicht ganze Mappen oder Karten einfärben. Farbe erscheint eher als feine leuchtende Kante, Segment, Linie oder Glow. Die Hauptseite soll lebendig wirken, während die Schreibfläche selbst ruhig und ablenkungsfrei bleibt.

## Werkzeugbedienung

Die frühere radiale Bubble wurde mit zunehmender Funktionsmenge zu verschachtelt. Der aktuelle Ansatz ist eine feste adaptive Werkzeugleiste:

- Landscape: links.
- Portrait: oben.
- Undo und Redo dauerhaft sichtbar.
- Stift, Marker, Radierer, Farbe und Dicke schnell erreichbar.
- Seltenere Funktionen in einer kurzen sekundären Ebene.
- S-Pen-Hover kann Werkzeugnamen anzeigen.

Die Werkzeugbedienung soll mit möglichst wenigen Schritten funktionieren und darf die Schreibfläche nicht unnötig verkleinern.

## Bücher und Hefte

Bücher sind aktive Arbeitsmaterialien, nicht nur PDF-Viewer. Schreiben, Markieren, Notizen, Lesezeichen, Favoriten, Ausschnitte, Inhaltsverzeichnis, Analyse und freies Navigieren gehören zum Buchmodus.

Buchausschnitte können als Objekte in Hefte übernommen werden. Diese Objekte bleiben mit der Heftseite verbunden, sind beweglich, skalierbar und können verankert werden.

Der Buch+Heft-Modus bildet die typische Unterrichtssituation ab: Quelle auf einer Seite, eigenes Heft auf der anderen.

## Automatische Rechenhilfe

Handschriftliche Rechenaufgaben sollen während des Schreibens erkannt werden. Eine bewusst eingekreiste Aufgabe dient als explizite Auswahl. Der komplette Inhalt des markierten Bereichs wird gemeinsam erkannt und an die Rechenengine übergeben.

Klammern, Multiplikation, Division mit `:` oder `/`, Potenzen und typische handschriftliche Varianten gehören zur Idee. Das Ergebnis erscheint als kleine Hilfsblase in der Nähe der Rechnung, ohne den Schreibfluss zu übernehmen.

## SHK-Schwerpunkt

Neben allgemeinen Schulwerkzeugen ist die App auf SHK-Unterricht ausgelegt. Dazu gehören:

- Heizleistung und Volumenstrom,
- Temperaturdifferenzen,
- Rohrinhalt,
- Fließgeschwindigkeit,
- Druck und Förderhöhe,
- Energie zum Erwärmen von Wasser,
- Einheitenumrechnung,
- Wärmelehre-, Rohrnetz-, Elektro- und SHK-Grundformeln,
- prüfungsrelevante Markierungen direkt aus Schulunterlagen.

Langfristig gehören auch Aufmaß, Materiallisten und SHK-Schema-/Symbolwerkzeuge zur Produktidee.

## Datensicherheit

Die App ist offline-first. Unterrichtsmaterial und Handschrift liegen lokal vor. Der Server dient für Anmeldung, Vollbackup, Versionshistorie und Wiederherstellung. Ein Backup soll die komplette App-Situation wiederherstellen, einschließlich importierter Bücher und PDFs.
