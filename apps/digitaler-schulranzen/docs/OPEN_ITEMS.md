# Offener Stand

Diese Punkte sind im aktuellen Projekt noch nicht vollständig abgeschlossen oder nach der letzten Änderung noch nicht ausreichend praktisch validiert.

## Schreibdarstellung V15.0.7

Die handschriftliche Darstellung wurde in V15.0.7 auf einen 2× High-Resolution-Ink-Cache umgestellt. Der letzte vor dieser Änderung gezeigte Zustand wirkte sichtbar pixelig, insbesondere bei größeren Strichen und beim Zoomen. Die optische Wirkung der 2×-Änderung auf dem Zieltablet ist noch nicht abschließend bestätigt.

Die langfristige Qualitätsidee ist eine möglichst direkte, glatte Handschrift ohne sichtbare Rasterartefakte. Der aktuelle Renderer bleibt ein Hybrid aus Vektordaten und Rastercache.

## Radierer V15.0.7

V15.0.6 radierte flüssig, ließ bei schnellen Bewegungen aber teilweise kleine Strichreste stehen. V15.0.7 verwendet deshalb kontinuierliche Segment-/Kapselgeometrie und berücksichtigt die Strichbreite. Dieser konkrete Fix ist noch nicht ausreichend auf vollgeschriebenen Seiten und bei sehr schnellen Radierbewegungen bestätigt.

## Schreibperformance

Die App hat bereits mehrere Optimierungsstufen für S-Pen-Latenz erhalten. Die zentrale offene Qualitätsfrage bleibt, ob längere und stark gefüllte Seiten dauerhaft genauso direkt reagieren wie leere Seiten. Besonders relevant sind Stift, Teilradierer, Undo/Redo, Zoom und Hintergrund-Speichern.

## Handschriftliche Mathematik

Die Erkennung basiert auf generischer ML-Kit-Handschrifterkennung und einer eigenen Normalisierungs-/Rechenengine. Das System erkennt viele einfache Rechenausdrücke, ist aber keine spezialisierte mathematische Handschrifterkennung.

Noch unvollständig bzw. fehleranfällig sind insbesondere:

- handschriftliche Brüche,
- stark überlappende Zeichen,
- komplexe Formeln mit Variablen,
- sehr große oder mehrzeilige Aufgaben,
- sichere Unterscheidung ähnlich geschriebener Ziffern und Operatoren,
- vollständige Interpretation von Einheiten innerhalb handschriftlicher Aufgaben.

Eine eingekreiste Formel ohne konkrete Zahlen kann erkannt werden, ist aber nicht automatisch numerisch lösbar.

## UI/UX

Die gewünschte visuelle Richtung ist klar definiert, der aktuelle Bildschirmstand erreicht die Referenzqualität aber noch nicht vollständig. Die bisherige Oberfläche ist funktional deutlich weiter als die visuelle Ausarbeitung.

Noch nicht vollständig erreicht sind:

- sehr hochwertiger Paper-/Soft-3D-Look auf allen Screens,
- fein abgestimmte Liquid-/Morph-Animationen,
- besonders elegante Bibliotheks-/Mappenansicht,
- dezente farbige Lichtkanten statt klassischer UI-Flächen,
- vollständige visuelle Gleichwertigkeit von Light und Dark Mode,
- konsistente Typografie und Abstände über alle Dialoge und Nebenansichten,
- vollständiger Ersatz älterer dialog- und chipartiger Android-Elemente.

## Bücher und automatische Analyse

PDF-Inhaltsverzeichnisse und automatische Analyse sind vorhanden. Die Qualität hängt stark von der PDF-Struktur und dem extrahierbaren Text ab. Gescannte Bücher ohne brauchbaren Textlayer sind für automatische Kapitelanalyse deutlich schwieriger.

## Backup/Restore

Der App-Code erstellt vollständige Backups inklusive lokalisierter PDFs und besitzt Integritätsprüfung. Der aktuelle Server ist bewusst einfach gehalten. Noch nicht Teil des aktuellen Stands sind unter anderem verschlüsselte Backups auf dem Server, Konfliktauflösung zwischen mehreren Geräten und inkrementeller Sync einzelner Dokumente.

## SHK-Ausbau

Der aktuelle SHK-Werkzeugkasten deckt Kernrechnungen und Einheiten ab. Noch nicht umgesetzt sind die weitergehenden Ideen:

- digitales Aufmaß,
- automatische Materiallisten,
- SHK-Schemaeditor,
- Symbolbibliothek für Pumpe, MAG, Speicher, Ventile, Wärmeerzeuger und Rohrnetz,
- Verknüpfung von Schemaelementen mit Berechnungen,
- weitere fachbezogene Tabellen und Auslegungshilfen.

## Lernfunktionen

Prüfungen, Karten, Markierungen und Wiederholungsdaten sind vorhanden. Eine umfassende automatische Generierung hochwertiger Lernkarten und vollständiger Probeprüfungen aus Buch- und Heftinhalten ist noch nicht als durchgängiger Workflow abgeschlossen.
