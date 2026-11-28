# PaddleOCR-V5 Eval-Datensatz

Dieser Mini-Datensatz wird von den instrumented Paddle-Tests (z. B.
`de.schliweb.makeacopy.utils.ocr.PaddleOcrEngineSmokeTest`, `SkewedPagePaddleTest`)
genutzt, um PaddleOCR auf realen lateinischen Scans zu messen.

## GT-Format-Konvention

- Pro Sample existiert ein Bild `<name>.jpg` (≤ 1 MB) und eine Ground-Truth-Datei
  `<name>.gt.txt`.
- `<name>.gt.txt` enthält den **gesamten Absatz**, mit `\n` zwischen Zeilen
  **genau wie auf dem Bild lesbar** (Reading-Order von oben nach unten,
  innerhalb einer Zeile von links nach rechts).
- Encoding: **UTF-8 ohne BOM**, NFC-normalisiert, lateinische Schrift.
- Vergleich erfolgt nach Zeilenenden-Normalisierung (`\r\n` → `\n`) und Trim.
  CER/WER werden zeilen-/zeichen-konkateniert berechnet (siehe `OcrErrorRates`).
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
  -Pandroid.testInstrumentationRunnerArguments.class=de.schliweb.makeacopy.utils.ocr.PaddleOcrEngineSmokeTest
```

Siehe auch `docs/eval/README.md` für die End-to-End-Pipeline und das
JSON-Schema.

## Sprachproben (`samples/`) und `SampleSetEvalTest`

`samples/` enthält 23 Sprachen, jede als sauberen Scan (`language_<code>_scan.png`)
und als Handyfoto (`language_<code>_photo.jpg`) mit Ground Truth. Die Seiten sind
synthetisch (eigener Text, Noto-Schriften unter der SIL Open Font License) und
stammen aus dem Schwesterprojekt Sheet; `scripts/synth_samples.py` erzeugt sie
deterministisch neu.

`SampleSetEvalTest` liest einen Satz und schreibt je Probe und im Mittel CER, WER
und die gefundenen Wörter ins Log (Tag `SampleSetEval`). Der Test prüft nichts, er
misst nur:

```bash
A=-Pandroid.testInstrumentationRunnerArguments
./gradlew :app:connectedDebugAndroidTest \
  $A.class=de.schliweb.makeacopy.utils.ocr.SampleSetEvalTest   # samples/, jede Probe in ihrer Sprache
#   $A.samplesAsset=eval | synth | eval/jpn    anderer Satz aus den Assets
#   $A.paddleLanguage=latin                    diese Sprache für jede Seite gewählt
#   $A.paddleBest=false                        ohne "Best quality" (Detektor bei 1536 px)
#   $A.paddleV6=false                          ohne die PP-OCRv6-Gegenprobe (nur PP-OCRv5)
#   $A.samplesDir=<Verzeichnis auf dem Gerät>   z. B. CORD-Belege aus scripts/cord_samples.py
adb logcat -d -s SampleSetEval:I
```

Ausgangswerte von `main` (2026-11-28, `25f2d772`, Emulator Pixel_9a, Debug-Build,
NCNN, "Best quality" an). Zeiten nur zur Orientierung, verglichen wird auf einem
Handy im Release-Build:

| Satz | Wörter gefunden | CER | Zeit |
|---|---|---|---|
| `samples` (46), eigene Sprache | 1652/1894 | 0,061 | 17,0 s |
| `samples` (46), Auswahl `latin` | 1142/1894 | 0,342 | 15,3 s |
| `eval` (36) | 2004/2072 | 0,036 | 11,2 s |
| `eval` (36), ohne "Best quality" | 1987/2072 | 0,039 | 10,1 s |
| `synth` (9) | 2366/2391 | 0,455 | 8,0 s |
| `eval/jpn` (2) | 2/6 | 0,042 | 0,8 s |

Mit der PP-OCRv6-Gegenprobe und ohne das englische Modell (gleicher Emulator; das
Pixel 7a findet dieselben Wörter):

| Satz | Wörter gefunden | CER |
|---|---|---|
| `samples` (46), eigene Sprache | 1654/1894 | 0,061 |
| `eval` (36) | 2021/2072 | 0,034 |
| `eval` (36), `paddleV6=false` | 1989/2072 | 0,037 |
| `synth` (9) | 2389/2391 | 0,453 |

Zeit auf dem Pixel 7a (nicht debugfähiger Build, vorab kompiliert): `eval` 55-56 s
ohne und 59 s mit Gegenprobe, `synth` 46 s und 47 s. Auf dem Pixel ist JIT aus: nach
jeder Installation per adb `cmd package compile -m speed -f <Paket>` ausführen, sonst
läuft der Java-Teil interpretiert und die Zeiten stimmen nicht.

In `samples` fehlen die Wörter vor allem bei Koreanisch (0/68, kein Modell),
Vietnamesisch (60/140), Persisch (50/88) und Arabisch (57/80). Die hohe CER von
`synth` kommt von der Lesereihenfolge mehrspaltiger Seiten, nicht von der
Erkennung.

## Hinweise zum Packaging

Die Bilder liegen unter `app/src/androidTest/assets/eval/`
(instrumented-Test-Sourceset) und werden **nicht** in das Release-APK
eingepackt; keine Anpassung der `aaptOptions.ignoreAssetsPattern` notwendig.
