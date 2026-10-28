# PaddleOCR-V5 Eval-Datensatz

Dieser Mini-Datensatz wird vom instrumented Test
`de.schliweb.makeacopy.utils.ocr.PaddleVsTesseractEvalTest` (Konzept §8 + §9 + §13)
genutzt, um Paddle gegen Tesseract auf realen lateinischen Scans zu vergleichen.

## GT-Format-Konvention

- Pro Sample existiert ein Bild `<name>.jpg` (≤ 1 MB) und eine Ground-Truth-Datei
  `<name>.gt.txt`.
- `<name>.gt.txt` enthält den **gesamten Absatz**, mit `\n` zwischen Zeilen
  **genau wie auf dem Bild lesbar** (Reading-Order von oben nach unten,
  innerhalb einer Zeile von links nach rechts).
- Encoding: **UTF-8 ohne BOM**, NFC-normalisiert, lateinische Schrift.
- Vergleich erfolgt nach Zeilenenden-Normalisierung (`\r\n` → `\n`) und Trim.
  CER/WER werden zeilen-/zeichen-konkateniert berechnet (siehe
  `PaddleVsTesseractEvalTest.normalizeText`).
- GT manuell verifizieren: Bild öffnen, Text in `*.gt.txt` Zeile-für-Zeile
  abgleichen und ggf. korrigieren.

## Kategorien (≥ 12 Samples, mind. 1 pro Kategorie)

| Kategorie                    | Sample(s)                                           |
|------------------------------|-----------------------------------------------------|
| Sauberer Scan                | `clean_scan.jpg`                                    |
| Kleiner Text                 | `small_text.jpg`                                    |
| Leichte/starke Schräglage    | `skew_light.jpg`, `skew_strong.jpg`                 |
| Foto mit Schatten            | `phone_photo.jpg`, `photo_shadow_hard.jpg`          |
| Mehrspalte / Mehrzeile       | `two_column.jpg`                                    |
| Highlight / Color            | `color_highlights.jpg`, `mixed_highlight.jpg`       |
| Low-light / unebene Beleuch. | `uneven_light.jpg`, `low_light.jpg`                 |
| Rauschiger Hintergrund       | `noisy_background.jpg`                              |

Quellen:

- `clean_scan.jpg`, `small_text.jpg`, `uneven_light.jpg`, `color_highlights.jpg`,
  `noisy_background.jpg`, `phone_photo.jpg` stammen aus `docs/fr74/samples/`
  (synthetisch via `scripts/make_fr74_samples.py`, deterministisch, Seed 42).
- `skew_light`, `skew_strong`, `two_column`, `photo_shadow_hard`, `low_light`,
  `mixed_highlight` werden synthetisch via `scripts/make_eval_extra_samples.py`
  erzeugt (deterministisch, Seed 74).

## Reproduzierbarer Lauf

```bash
# arm64-v8a-Emulator starten oder Device anstecken (API 29+).
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.schliweb.makeacopy.utils.ocr.PaddleVsTesseractEvalTest
# JSON-Reports vom Device einsammeln:
./gradlew :app:pullEvalReports
```

Skippt automatisch, wenn `arm64-v8a` nicht in `Build.SUPPORTED_ABIS` ist.
JSON-Reports landen unter `Context.getExternalFilesDir("eval")/` als
`report-paddle-<timestamp>.json` und `report-tesseract-<timestamp>.json`.
Mit `pullEvalReports` werden diese nach `app/build/eval-reports/` kopiert.

Siehe auch `docs/eval/README.md` für die End-to-End-Pipeline und das
JSON-Schema.

## Hinweise zum Packaging

Die Bilder liegen unter `app/src/androidTest/assets/eval/`
(instrumented-Test-Sourceset) und werden **nicht** in das Release-APK
eingepackt; keine Anpassung der `aaptOptions.ignoreAssetsPattern` notwendig.
