# Hilfe

MakeACopy macht aus Papier durchsuchbare PDFs, vollständig auf deinem
Handy. Ein Scan durchläuft vier Bildschirme: Kamera, Zuschneiden, OCR und
Exportieren. Alles, was sich einstellen lässt, steht unter **Optionen**,
der Schieberegler-Taste auf dem Kamerabildschirm.

## Ein Foto aufnehmen

- Halte das Handy so über die Seite, dass alle vier Ecken zu sehen sind.
  Tippe in die Vorschau, um scharf zu stellen.
- Tippe auf **Scannen**, um das Foto aufzunehmen. Die Taschenlampe hilft
  bei schwachem Licht, die Zoom-Taste geht näher an kleine Seiten heran.
- **Laden** nimmt stattdessen ein Bild oder PDF, das schon auf deinem
  Handy liegt. Du kannst Bilder und PDFs auch aus anderen Apps an
  MakeACopy teilen oder mit MakeACopy öffnen.

## Zuschneiden

- MakeACopy findet die Ränder der Seite selbst. Ziehe Ecken oder Kanten
  dorthin, wo sie danebenliegen; mit der Einrast-Taste rasten die Ecken
  im rechten Winkel ein.
- Die Dreh-Tasten drehen die Seite in 90°-Schritten, die Taste für das
  Seitenverhältnis legt ein Seitenformat fest.
- Für Buchseiten, die sich zum Falz hin wölben, schalte gekrümmte Kanten
  ein und ziehe die Mittelpunkte der oberen und unteren Kante auf den
  gewölbten Rand der Seite.
- Tippe auf **Zuschneiden**, um weiterzumachen.

## Text erkennen

- Der OCR-Bildschirm erkennt den Text sofort. Wähle vorher die
  **Sprache** des Dokuments, wenn eine andere angezeigt wird. Die
  Sprachen sind nach Schrift gruppiert: Deutsch, Französisch, Spanisch
  und die meisten anderen europäischen Sprachen stehen unter *Latin
  script*.
- **Überprüfen** zeigt die erkannten Wörter auf der Seite: Tippe auf ein
  Wort, um es zu korrigieren. **Text teilen** gibt den Text an eine
  andere App weiter.
- Tippe auf **Weiter**, um zum Export zu gelangen. Änderst du danach die
  Sprache, erscheint **Verarbeiten**, um die Seite neu zu erkennen.

## Exportieren

- Wähle **PDF** oder **JPEG**. Ein PDF enthält den erkannten Text, so
  dass du darin suchen und Text kopieren kannst.
- **Speichern** legt die Datei in einem Ordner deiner Wahl ab, **Teilen**
  gibt sie an eine andere App weiter.
- Für ein Dokument mit mehreren Seiten scannst du jede Seite einzeln:
  Jeder fertige Scan wird aufbewahrt, und **Seite hinzufügen** fügt
  fertige Scans zum aktuellen Dokument hinzu. Ziehe die Seiten im
  Streifen, um ihre Reihenfolge zu ändern.
- **Zuschnitt anpassen** führt zurück zum Zuschnitt der aktuellen Seite,
  **Neuer Scan** beginnt das nächste Dokument.

## Bibliothek und Sammlungen

- Jeder fertige Scan bleibt in der Bibliothek, erreichbar über die
  Bibliotheks-Taste auf dem Kamera- und dem Export-Bildschirm. Öffne dort
  einen Scan, um seine Details zu sehen, ihn erneut zu teilen oder einer
  Sammlung hinzuzufügen.
- **Sammlungen** fassen Scans zusammen, zum Beispiel nach Thema oder
  Jahr.
- Wie lange Scans aufbewahrt werden, legst du unter Optionen → Bibliothek
  fest. Standardmäßig bleiben alle erhalten.

## Optionen

- **Scannen**: den OCR-Schritt, das Zuschneiden oder die Kantenerkennung
  überspringen, oder den erkannten Seitenumriss live in der
  Kameravorschau zeigen.
- **Kamera**: Belichtungskorrektur, manueller Fokus und ob die App bei
  wenig Licht die Taschenlampe anbietet.
- **OCR**: der Erkennungsmodus und die Nachbearbeitung, die typische
  Erkennungsfehler korrigiert.
- **Exportieren**: Seitengröße, Qualität, Farbe, die Textebene im PDF,
  eine eigene TXT-Datei mit dem Text und der Inbox-Modus.
- **App**: Barrierefreiheitsmodus, Schutz der Scans vor Screenshots und
  in der App-Übersicht, App-Sprache, diese Hilfe und die
  Datenschutzerklärung.

## Inbox-Modus

Der Inbox-Modus speichert jeden Export ohne Nachfrage in einen festen
Ordner, zum Beispiel einen, den Syncthing, Nextcloud oder SambaLite
synchronisiert oder aus dem paperless-ngx importiert. Schalte ihn unter
Optionen → Exportieren mit **Standard-Exportverzeichnis aktivieren** ein
und wähle den Ordner mit **Ordner auswählen**. Die Dateinamen entstehen
aus dem Datum; eine vorhandene Datei wird nie überschrieben.

## Barrierefreiheitsmodus

Der Barrierefreiheitsmodus führt mit gesprochenen Hinweisen und
Vibration durch die Aufnahme, so dass sich eine Seite scannen lässt,
ohne den Bildschirm zu sehen. Die Hinweise spricht dein Screenreader,
etwa TalkBack; schalte ihn also ebenfalls ein. Die Lautstärketasten
dienen dann als Auslöser. Schalte den Modus unter Optionen → App ein.

## Tipps für gute Scans

- Lege die Seite auf eine dunkle, einfarbige Fläche, damit sich ihre
  Ränder abheben.
- Licht von der Seite oder von oben vermeidet den Schatten des Handys auf
  der Seite.
- Halte das Handy parallel zur Seite; den Rest richtet das Zuschneiden
  gerade.
- Geh bei kleiner Schrift lieber näher heran, statt zu zoomen.

## Wenn etwas nicht klappt

Verhält sich die App seltsam, sammelt **Protokolle teilen** unter
Optionen → App das Protokoll der App, das du zusammen mit einer
Beschreibung des Problems schicken kannst. Probleme meldest du unter
https://github.com/egdels/makeacopy/issues.

## Datenschutz

MakeACopy hat keine Internet-Berechtigung: Deine Dokumente verlassen das
Handy nur, wenn du sie selbst speicherst oder teilst. Einzelheiten stehen
in der Datenschutzerklärung, Optionen → App → Datenschutzerklärung.
