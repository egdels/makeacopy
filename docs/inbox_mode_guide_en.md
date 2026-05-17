# MakeACopy – Inbox Mode

This guide explains step by step how to use Inbox Mode in MakeACopy. The mode is designed for users who scan many documents in sequence and want them automatically saved to a predefined folder — without selecting a save location each time.

---

Contents
- What does Inbox Mode do?
- Turn on Inbox Mode
- Scanning workflow with Inbox Mode
- File naming and collision handling
- Include OCR text (automatic TXT export)
- Multi-page documents
- Auto-start new scan
- Fallback behavior
- Integration examples
- Privacy and offline operation
- Frequently Asked Questions (FAQ)

---

What does Inbox Mode do?
- Lets you configure a **default export directory** (the "Inbox").
- Automatically saves scans to this directory — no file picker required.
- Generates filenames automatically with collision-safe naming.
- If "Include OCR text" is enabled, a separate TXT file is also exported automatically.
- Supports multi-page PDFs just like the normal export.
- Optionally starts a new scan immediately after export.
- Works fully offline — no data connection, no upload.

---

Turn on Inbox Mode
1) In the export screen, tap the **gear icon** (Export Options).
2) Enable the checkbox **"Enable default export directory"**.
3) Tap **"Select folder"** and choose your Inbox directory using the system folder picker.
4) Optionally configure the **filename format** (e.g., `YYYY-MM-DD_scan`).
5) Optionally enable **"Start new scan automatically after export"**.
6) Confirm with **"Confirm"**.

From now on:
- When you export a scan, the file picker is skipped and the document is saved directly to your Inbox folder.
- You will see a brief confirmation: **"✓ Saved to Inbox"**.

---

Scanning workflow with Inbox Mode

### Without Inbox Mode

    Scan → Crop → OCR → Select export folder → Save

### With Inbox Mode

    Scan → Crop → OCR → Saved automatically → New Scan

The export dialog is completely skipped. After saving, you can immediately start the next scan.

---

File naming and collision handling

Inbox Mode generates filenames automatically based on the configured template:

| Template | Example |
|---|---|
| `YYYY-MM-DD_scan` (default) | `2026-03-06_scan.pdf` |
| `YYYY-MM-DD_HHmmss_scan` | `2026-03-06_094500_scan.pdf` |
| `YYYY-MM-DD` | `2026-03-06.pdf` |

If a file with the same name already exists, a suffix is appended:

    2026-03-06_scan.pdf
    2026-03-06_scan_2.pdf
    2026-03-06_scan_3.pdf

---

Include OCR text (automatic TXT export)

If **"Include OCR text"** is enabled in the export options, Inbox Mode also exports a separate TXT file containing the recognized text — automatically, without a file picker.

The TXT file uses the same base name as the PDF or JPEG:

    2026-03-06_scan.pdf
    2026-03-06_scan.txt

This is useful for workflows where you want the raw OCR text alongside the document.

Note: If Inbox Mode is **not** active, the TXT export still uses the normal file picker.

---

Multi-page documents

Inbox Mode fully supports multi-page PDFs. The workflow is:

1. Scan the first page.
2. Add more pages using "Add Page".
3. Reorder or remove pages as needed.
4. Tap "Export" — all pages are combined into a single PDF and saved to the Inbox folder.

Inbox Mode only changes **where** the file is saved, not **how** it is created.

---

Auto-start new scan

When **"Start new scan automatically after export"** is enabled:

- After a successful Inbox export, the app navigates back to the camera screen automatically.
- This creates a seamless loop: Scan → Save → Scan → Save → …

This is especially useful when scanning a stack of documents.

---

Fallback behavior

If the Inbox folder becomes inaccessible (e.g., permission revoked, folder deleted):

- The app shows a message explaining the issue.
- Inbox Mode is automatically disabled.
- The normal file picker is shown so you can still save your document.

You can re-enable Inbox Mode at any time by selecting a new folder in the export options.

---

Integration examples

### SambaLite

    Scan → Inbox Folder → SambaLite transfers to SMB share → Network archive

Point the Inbox folder to a directory monitored by [SambaLite](https://egdels.github.io/SambaLite/). Scanned documents are automatically transferred to your SMB/CIFS network share.

### paperless-ngx

    Scan → Inbox Folder → paperless-ngx imports → OCR → Searchable archive

Set the Inbox folder to the directory monitored by paperless-ngx. Each scan is automatically picked up and processed.

### Nextcloud

    Scan → Synced Folder → Nextcloud sync → Document archive

Point the Inbox folder to a Nextcloud-synced directory on your device.

### Syncthing

    Scan → Syncthing Folder → Synced to desktop → Archive

Use a Syncthing-shared folder as the Inbox directory.

---

Privacy and offline operation
- MakeACopy processes all images and OCR locally on your device.
- Inbox Mode does not upload anything. No internet connection required.
- The Inbox folder is a local directory on your device (or a synced folder managed by another app).

---

Frequently Asked Questions (FAQ)

Q: Is the Inbox folder the same as the normal export folder?
A: No. The Inbox folder and the normal export folder are stored independently. Changing one does not affect the other.

Q: Can I use the same folder for both Inbox and normal export?
A: Yes. Both can point to the same directory without conflicts, but they are configured separately.

Q: What happens if I disable Inbox Mode?
A: The normal file picker is shown again when exporting. Your Inbox folder setting is preserved and can be re-enabled later.

Q: Does Inbox Mode work with JPEG export?
A: Yes. Inbox Mode works for both PDF and JPEG exports.

Q: Can I change the filename format?
A: Yes. In the export options, you can choose between three templates: date + scan, date + time + scan, or date only.

Q: I exported a document but the TXT file is missing.
A: The TXT file is only created when "Include OCR text" is enabled in the export options. Check that this option is active.

Q: The app says my Inbox folder is no longer accessible.
A: This can happen if the folder was deleted or the permission was revoked (e.g., after a system update). Re-select the folder in the export options to restore access.

---

Technical note (for contributors)

- Inbox Mode is controlled by the feature flag `FEATURE_INBOX_MODE` in `BuildConfig`.
- Preferences are stored in `SharedPreferences` under the keys `inbox_enabled`, `inbox_uri`, `inbox_filename_template`, and `inbox_auto_new_scan`.
- The export logic is in `ExportFragment.java`; the file creation utility is `InboxExporter.java`.

---

Contact
If anything is unclear or you have suggestions, we welcome feedback in app store reviews or in the project repository.
