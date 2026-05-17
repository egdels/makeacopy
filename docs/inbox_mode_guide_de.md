# MakeACopy – Inbox‑Modus

Dieser Leitfaden erklärt Schritt für Schritt, wie du den Inbox‑Modus in MakeACopy benutzt. Der Modus ist für Nutzer gedacht, die viele Dokumente nacheinander scannen und diese automatisch in einem vordefinierten Ordner speichern möchten – ohne jedes Mal einen Speicherort auszuwählen.

---

Inhalt
- Was macht der Inbox‑Modus?
- Inbox‑Modus einschalten
- Scan‑Workflow mit Inbox‑Modus
- Dateinamen und Kollisionsbehandlung
- OCR‑Text einfügen (automatischer TXT‑Export)
- Mehrseitige Dokumente
- Automatisch neuen Scan starten
- Fallback‑Verhalten
- Integrationsbeispiele
- Datenschutz und Offline‑Betrieb
- Häufige Fragen (FAQ)

---

Was macht der Inbox‑Modus?
- Ermöglicht die Konfiguration eines **Standard‑Export‑Verzeichnisses** (die „Inbox").
- Speichert Scans automatisch in diesem Verzeichnis – kein Datei‑Picker nötig.
- Generiert Dateinamen automatisch mit kollisionssicherer Benennung.
- Wenn „OCR‑Text einfügen" aktiviert ist, wird auch eine separate TXT‑Datei automatisch exportiert.
- Unterstützt mehrseitige PDFs wie der normale Export.
- Startet optional sofort einen neuen Scan nach dem Export.
- Funktioniert vollständig offline – keine Datenverbindung, kein Upload.

---

Inbox‑Modus einschalten
1) Tippe im Export‑Bildschirm auf das **Zahnrad‑Symbol** (Export‑Optionen).
2) Aktiviere die Checkbox **„Standard‑Export‑Verzeichnis aktivieren"**.
3) Tippe auf **„Ordner auswählen"** und wähle dein Inbox‑Verzeichnis über den System‑Ordner‑Picker.
4) Konfiguriere optional das **Dateinamenformat** (z. B. `YYYY-MM-DD_scan`).
5) Aktiviere optional **„Automatisch neuen Scan nach Export starten"**.
6) Bestätige mit **„Bestätigen"**.

Ab jetzt:
- Beim Export wird der Datei‑Picker übersprungen und das Dokument direkt in deinem Inbox‑Ordner gespeichert.
- Du siehst eine kurze Bestätigung: **„✓ In Inbox gespeichert"**.

---

Scan‑Workflow mit Inbox‑Modus

### Ohne Inbox‑Modus

    Scannen → Zuschneiden → OCR → Export‑Ordner wählen → Speichern

### Mit Inbox‑Modus

    Scannen → Zuschneiden → OCR → Automatisch gespeichert → Neuer Scan

Der Export‑Dialog wird komplett übersprungen. Nach dem Speichern kannst du sofort den nächsten Scan starten.

---

Dateinamen und Kollisionsbehandlung

Der Inbox‑Modus generiert Dateinamen automatisch basierend auf der konfigurierten Vorlage:

| Vorlage | Beispiel |
|---|---|
| `YYYY-MM-DD_scan` (Standard) | `2026-03-06_scan.pdf` |
| `YYYY-MM-DD_HHmmss_scan` | `2026-03-06_094500_scan.pdf` |
| `YYYY-MM-DD` | `2026-03-06.pdf` |

Wenn eine Datei mit demselben Namen bereits existiert, wird ein Suffix angehängt:

    2026-03-06_scan.pdf
    2026-03-06_scan_2.pdf
    2026-03-06_scan_3.pdf

---

OCR‑Text einfügen (automatischer TXT‑Export)

Wenn **„OCR‑Text einfügen"** in den Export‑Optionen aktiviert ist, exportiert der Inbox‑Modus auch eine separate TXT‑Datei mit dem erkannten Text – automatisch, ohne Datei‑Picker.

Die TXT‑Datei verwendet denselben Basisnamen wie die PDF‑ oder JPEG‑Datei:

    2026-03-06_scan.pdf
    2026-03-06_scan.txt

Das ist nützlich für Workflows, in denen der OCR‑Rohtext neben dem Dokument benötigt wird.

Hinweis: Wenn der Inbox‑Modus **nicht** aktiv ist, verwendet der TXT‑Export weiterhin den normalen Datei‑Picker.

---

Mehrseitige Dokumente

Der Inbox‑Modus unterstützt mehrseitige PDFs vollständig. Der Workflow ist:

1. Scanne die erste Seite.
2. Füge weitere Seiten über „Seite hinzufügen" hinzu.
3. Ordne Seiten um oder entferne sie nach Bedarf.
4. Tippe auf „Exportieren" – alle Seiten werden in einer einzigen PDF zusammengefasst und im Inbox‑Ordner gespeichert.

Der Inbox‑Modus ändert nur, **wohin** die Datei gespeichert wird, nicht **wie** sie erstellt wird.

---

Automatisch neuen Scan starten

Wenn **„Automatisch neuen Scan nach Export starten"** aktiviert ist:

- Nach einem erfolgreichen Inbox‑Export navigiert die App automatisch zurück zum Kamera‑Bildschirm.
- Das erzeugt eine nahtlose Schleife: Scannen → Speichern → Scannen → Speichern → …

Das ist besonders nützlich beim Scannen eines Dokumentenstapels.

---

Fallback‑Verhalten

Wenn der Inbox‑Ordner nicht mehr erreichbar ist (z. B. Berechtigung widerrufen, Ordner gelöscht):

- Die App zeigt eine Meldung mit einer Erklärung.
- Der Inbox‑Modus wird automatisch deaktiviert.
- Der normale Datei‑Picker wird angezeigt, damit du dein Dokument trotzdem speichern kannst.

Du kannst den Inbox‑Modus jederzeit wieder aktivieren, indem du in den Export‑Optionen einen neuen Ordner auswählst.

---

Integrationsbeispiele

### SambaLite

    Scannen → Inbox‑Ordner → SambaLite überträgt auf SMB‑Freigabe → Netzwerkarchiv

Richte den Inbox‑Ordner auf ein Verzeichnis, das von [SambaLite](https://egdels.github.io/SambaLite/) überwacht wird. Gescannte Dokumente werden automatisch auf deine SMB/CIFS‑Netzwerkfreigabe übertragen.

### paperless‑ngx

    Scannen → Inbox‑Ordner → paperless‑ngx importiert → OCR → Durchsuchbares Archiv

Setze den Inbox‑Ordner auf das Verzeichnis, das von paperless‑ngx überwacht wird. Jeder Scan wird automatisch erkannt und verarbeitet.

### Nextcloud

    Scannen → Synchronisierter Ordner → Nextcloud‑Sync → Dokumentenarchiv

Richte den Inbox‑Ordner auf ein Nextcloud‑synchronisiertes Verzeichnis auf deinem Gerät.

### Syncthing

    Scannen → Syncthing‑Ordner → Synchronisiert zum Desktop → Archiv

Verwende einen Syncthing‑freigegebenen Ordner als Inbox‑Verzeichnis.

---

Datenschutz und Offline‑Betrieb
- MakeACopy verarbeitet alle Bilder und OCR lokal auf deinem Gerät.
- Der Inbox‑Modus lädt nichts hoch. Keine Internetverbindung nötig.
- Der Inbox‑Ordner ist ein lokales Verzeichnis auf deinem Gerät (oder ein synchronisierter Ordner, der von einer anderen App verwaltet wird).

---

Häufige Fragen (FAQ)

F: Ist der Inbox‑Ordner derselbe wie der normale Export‑Ordner?
A: Nein. Der Inbox‑Ordner und der normale Export‑Ordner werden unabhängig voneinander gespeichert. Eine Änderung des einen beeinflusst den anderen nicht.

F: Kann ich denselben Ordner für Inbox und normalen Export verwenden?
A: Ja. Beide können auf dasselbe Verzeichnis zeigen, ohne Konflikte zu verursachen, werden aber separat konfiguriert.

F: Was passiert, wenn ich den Inbox‑Modus deaktiviere?
A: Beim Export wird wieder der normale Datei‑Picker angezeigt. Deine Inbox‑Ordner‑Einstellung bleibt erhalten und kann später wieder aktiviert werden.

F: Funktioniert der Inbox‑Modus mit JPEG‑Export?
A: Ja. Der Inbox‑Modus funktioniert sowohl für PDF‑ als auch für JPEG‑Exporte.

F: Kann ich das Dateinamenformat ändern?
A: Ja. In den Export‑Optionen kannst du zwischen drei Vorlagen wählen: Datum + Scan, Datum + Uhrzeit + Scan oder nur Datum.

F: Ich habe ein Dokument exportiert, aber die TXT‑Datei fehlt.
A: Die TXT‑Datei wird nur erstellt, wenn „OCR‑Text einfügen" in den Export‑Optionen aktiviert ist. Prüfe, ob diese Option aktiv ist.

F: Die App sagt, mein Inbox‑Ordner ist nicht mehr erreichbar.
A: Das kann passieren, wenn der Ordner gelöscht oder die Berechtigung widerrufen wurde (z. B. nach einem System‑Update). Wähle den Ordner in den Export‑Optionen erneut aus, um den Zugriff wiederherzustellen.

---

Technischer Hinweis (für Mitwirkende)

- Der Inbox‑Modus wird über das Feature‑Flag `FEATURE_INBOX_MODE` in `BuildConfig` gesteuert.
- Einstellungen werden in `SharedPreferences` unter den Schlüsseln `inbox_enabled`, `inbox_uri`, `inbox_filename_template` und `inbox_auto_new_scan` gespeichert.
- Die Export‑Logik befindet sich in `ExportFragment.java`; die Datei‑Erstellungs‑Utility ist `InboxExporter.java`.

---

Kontakt
Wenn etwas unklar ist oder du Verbesserungsvorschläge hast, freuen wir uns über Feedback in den App‑Store‑Bewertungen oder im Projekt‑Repository.
