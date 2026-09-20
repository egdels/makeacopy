# Real-World Evaluation & Test Data Separation

This chapter describes how to evaluate DocQuadNet models in a way that produces **meaningful results for MakeACopy**.

The goal of evaluation is **not** to optimize validation loss on UVDoc, but to verify that a model behaves **better and more robustly on real camera images**.

---

## Motivation

Training and validation metrics (e.g. `val.corner_mae_px`) are useful for optimization and regression checks, but they do **not** answer the core product question:

> Does this model produce better document quads for real MakeACopy users?

To answer this, evaluation must be performed on a **separate real-world test set** that reflects actual camera usage.

---

## Core rule: Train/Test separation

Each image must belong to **exactly one** of the following groups:

- **Training origin**
- **Test origin**

This decision is final and must not be changed later.

### The decisive rule

> **If an image (or any augmentation derived from it) is used for training or fine-tuning, the original image must NOT appear in the test set.**

This applies regardless of whether:
- the test image is unaugmented
- the augmentation is mild or strong
- the image appears visually different

Sharing the same underlying scene between training and test data is considered **data leakage**.

---

## Correct usage patterns

### ✅ Allowed (correct)

- Training uses:
    - UVDoc images
    - Own images **A** with augmentation
- Test uses:
    - Own images **B** (original, unaugmented)
- No overlap between A and B

This setup measures **in-distribution generalization** and is the recommended approach for MakeACopy.

### ❌ Not allowed (incorrect)

- Training uses:
    - augment(A01), augment(A02), …
- Test uses:
    - A01, A02 (original images)

Even though the test images are unaugmented, the underlying scene has already been seen during training.

---

## What “different images” means in practice

“Different images” does **not** mean a different object class. The object remains a document.

What must differ is the **scene**, for example:

- different document pages
- different tables / floors / backgrounds
- different lighting conditions
- different viewing angles
- different partial occlusions (hands, objects)

The goal is to test **new instances of the same real-world task**.

---

## Recommended dataset sizes

You do **not** need a large test set.

Typical, effective setup:

- **Fine-tuning set**
    - 10–30 own images
    - strong or moderate augmentation
- **Real-world test set**
    - 20–50 different own images
    - no augmentation
    - never used for training

This is sufficient to reliably detect:
- regressions
- improvements from mix fine-tuning
- changes in failure rate

---

# Evaluation script: `evaluate_docquad_models.py`

Evaluation is performed using a dedicated script that compares one or more trained models on a fixed real-world test set.

This chapter assumes the script already exists at:

- `training/scripts/evaluate_docquad_models.py`

---

## Expected test set layout

Create a fixed real-world evaluation set in a dedicated folder, e.g.:

```text
training/data/docquad_real_eval/
├── images/
│   ├── img_001.jpg
│   ├── img_002.jpg
│   └── ...
└── labels/
    ├── img_001.json
    ├── img_002.json
    └── ...
```

Each label file contains the ground-truth quad:

```json
{
  "image": "img_001.jpg",
  "width": 3024,
  "height": 4032,
  "corners_px": [[x_tl,y_tl],[x_tr,y_tr],[x_br,y_br],[x_bl,y_bl]]
}
```

Corner order must be **TL, TR, BR, BL (clockwise)**.

`corners_px` are **stored pixel coordinates**: the EXIF orientation of a photo is *not* applied, because that is how PIL (training, evaluation) and Android's `BitmapFactory` read the pixels. `labeler.py` shows photos upright (`cv2.imread` applies EXIF), so `convert_to_docquad.py` maps its corners to stored coordinates (`--label_coords exif`, the default; reported as `exif_orientation_applied`). Pass `--label_coords stored` for labels that are already in stored coordinates.

---

## Running the evaluation

### Compare two models (UVDoc-only vs Mix-Fine-Tune)

```bash
python3 training/scripts/evaluate_docquad_models.py   --test_dir training/data/docquad_real_eval   --model uvdoc=training/runs/docquad_uvdoc_pretrain/checkpoints/best.onnx   --model mix=training/runs/docquad_mix_finetune/checkpoints/best.onnx   --out reports/eval_uvdoc_vs_mix.json
```

### Evaluate a single model (for regression tracking)

```bash
python3 training/scripts/evaluate_docquad_models.py   --test_dir training/data/docquad_real_eval   --model candidate=training/runs/docquad_my_data_finetune_from_uvdoc/checkpoints/best.onnx   --out reports/eval_candidate.json
```

---

## Parameters

The script is designed to be explicit and reproducible.

### Required

- `--test_dir <dir>`  
  Directory containing `images/` and `labels/`.

- `--model <name>=<onnx>` (repeatable)  
  One or more named ONNX models to evaluate. The name is used in the report.

- `--out <file.json>`  
  Output path for the JSON report.

### Execution / runtime

- `--device {cpu,cuda}` (default: `cpu`)  
  ONNX Runtime execution provider selection. Use `cpu` for maximum determinism.

- `--limit <N>` (default: disabled)  
  Evaluate only the first N samples (useful for quick checks).

- `--seed <N>` (default: `0`)  
  Seed for any randomized ordering / subsampling (if used by the script).

### Metrics / thresholds

- `--iou_thresh <float>` (default: `0.90`)  
  IoU threshold for a sample to be counted as “match” vs ground truth.

- `--max_corner_px <float>` (default: `8`)  
  Maximum mean corner distance (pixels) to still count as “good”.  
  (Assumes consistent TL/TR/BR/BL order.)

- `--min_area_frac <float>` (default: `0.05`)  
  Minimum quad area fraction relative to the image area. Below this: FAIL.

### Understanding corner MAE metrics

The evaluation reports two corner MAE (Mean Absolute Error) metrics:

- **`corner_mae_px`**: Absolute error in pixels (original image coordinates)
- **`corner_mae_rel`**: Relative error as fraction of image diagonal (0..1)

**Why both metrics?**

`corner_mae_px` alone is difficult to interpret because it depends on image resolution:
- A 50px error on a 4000×3000 image (~5000px diagonal) is excellent (~1%)
- A 50px error on a 640×480 image (~800px diagonal) is poor (~6%)

`corner_mae_rel` normalizes the error by the image diagonal, making it **resolution-independent** and comparable across datasets with different image sizes.

**Interpretation guide:**

| `corner_mae_rel` | Quality | Typical `corner_mae_px` (4000×3000) |
|------------------|---------|-------------------------------------|
| < 0.02 (2%)      | Excellent | < 100px |
| 0.02 – 0.04      | Good    | 100 – 200px |
| 0.04 – 0.06      | Acceptable | 200 – 300px |
| > 0.06 (6%)      | Poor    | > 300px |

### Failure conditions (hard FAILs)

- `--fail_on_oob` (default: enabled)  
  Count quads that extend outside the image bounds as FAIL.

- `--fail_on_geom` (default: enabled)  
  Count self-intersecting or non-convex quads as FAIL.

- `--fail_on_degenerate` (default: enabled)  
  Count degenerate quads (too small edges, near-zero area) as FAIL.

---

## Evaluation modes: RAW vs PRODUCT

The script supports two distinct evaluation modes that make it explicit whether we measure **raw model quality** or **real product behavior**.

### Mode selection

```bash
--mode raw        # default: measure raw model quality
--mode product    # measure product-level behavior (MakeACopy app-identical)
```

The selected mode is recorded in the output report under `meta.eval_mode`.

---

### RAW mode (default)

**Purpose:** Measure actual model quality. Compare training runs and checkpoints fairly.

In RAW mode:
- Uses model output only (corner heatmaps)
- Applies only minimal normalization (consistent corner ordering)
- Does **NOT** apply:
  - Self-intersection repair
  - Convexity repair
  - Mask→quad fallback
  - Confidence-based heuristics
- FAIL conditions (self-intersecting, non-convex, degenerate) are **detected and reported**, but **NOT repaired**

```bash
python3 training/scripts/evaluate_docquad_models.py \
  --test_dir training/data/docquad_real_eval \
  --model candidate=path/to/model.onnx \
  --mode raw \
  --out reports/eval_raw.json
```

---

### PRODUCT mode (MakeACopy app-identical)

**Purpose:** Measure real-world UX impact. Support release and rollout decisions.

In PRODUCT mode:
- Applies the **exact same post-processing pipeline** used in the MakeACopy Android app
- Uses **both** model outputs: `corner_heatmaps [1,4,64,64]` and `mask_logits [1,1,64,64]`
- All repairs and fallbacks match app behavior and thresholds

```bash
python3 training/scripts/evaluate_docquad_models.py \
  --test_dir training/data/docquad_real_eval \
  --model candidate=path/to/model.onnx \
  --mode product \
  --out reports/eval_product.json
```

---

### App-identical post-processing pipeline (PRODUCT mode)

The PRODUCT mode implements an **exact Python port** of the MakeACopy app's `DocQuadPostprocessor.java`:

| Step | Description | Java Source |
|------|-------------|-------------|
| 1 | Letterbox preprocessing (aspect-preserving resize to 256×256) | `DocQuadLetterbox.create()` |
| 2 | Corner extraction with 3×3 subpixel refinement | `refineCorners64ToCorners256_3x3()` |
| 3 | Mask→Quad via PCA-based oriented rectangle | `quadFromMask256()` |
| 4 | Path choice: Corners vs Mask based on penalty scores | `choosePath()` |
| 5 | Letterbox inverse transformation to original coordinates | `mapCorners256ToOriginal()` |

**Penalty functions (exact ports):**

| Function | Purpose |
|----------|---------|
| `quadPenaltyGeometry()` | OOB, self-intersection, convexity, area, edge ratios |
| `maskDisagreementPenaltyForCorners()` | 8×8 grid sampling for mask/corner agreement |
| `DocQuadScore.oobSum()` / `oobMax()` | Out-of-bounds distance calculation |
| `DocQuadScore.selfIntersects()` | Self-intersection detection |
| `DocQuadScore.isConvex()` | Convexity check |
| `canonicalizeQuadOrderV1()` | Corner ordering (TL, TR, BR, BL clockwise) |

---

### Reporting by mode

The evaluation output makes the mode explicit. For each mode, the report includes independently:

- FAIL count and reasons
- IoU statistics (mean, median, pct_ge_thresh)
- Corner MAE statistics in pixels (mean, median, pct_le_max)
- Corner MAE statistics relative to image diagonal (mean, median)
- Per-sample results (image, fail, fail_reason, iou, corner_mae_px, corner_mae_rel)

**Important:** Never merge RAW and PRODUCT results. They measure different things:
- RAW mode exposes failures that PRODUCT mode may later fix
- PRODUCT mode may show reduced FAIL rate due to post-processing
- Improved PRODUCT metrics do **not** imply better training

---

## Output report (`--out` JSON)

The report is intended to be stable for CI/regression tracking and easy diffing.

### Top-level structure

```json
{
  "meta": {
    "test_dir": "...",
    "num_samples": 42,
    "device": "cpu",
    "iou_thresh": 0.9,
    "max_corner_px": 8.0
  },
  "models": {
    "uvdoc": { "... per-model metrics ..." },
    "mix":   { "... per-model metrics ..." }
  },
  "pairwise": {
    "uvdoc_vs_mix": { "... comparison ..." }
  }
}
```

### Per-model metrics (`report.models.<name>`)

```json
{
  "num_samples": 42,
  "fail": {
    "count": 2,
    "rate": 0.047619,
    "by_reason": {
      "oob": 1,
      "non_convex": 1
    }
  },
  "iou": {
    "mean": 0.941,
    "median": 0.962,
    "pct_ge_thresh": 0.857143
  },
  "corner_mae_px": {
    "mean": 4.12,
    "median": 3.88,
    "pct_le_max": 0.904762
  },
  "corner_mae_rel": {
    "mean": 0.0008,
    "median": 0.0007
  }
}
```

### Pairwise comparison (`report.pairwise.<a>_vs_<b>`)

The script compares models on a per-sample basis using the primary objectives:
1) fewer FAILs
2) higher IoU
3) lower corner MAE

Example:

```json
{
  "wins": { "a": 18, "b": 22 },
  "ties": 2,
  "worse_cases": {
    "a": ["img_014.jpg"],
    "b": ["img_031.jpg", "img_040.jpg"]
  }
}
```

---

## Interpreting results (MakeACopy-oriented)

For MakeACopy, the most important signal is **FAIL rate**.

A model is considered **better** if:

- FAIL rate decreases (even small improvements matter)
- it is not significantly worse on any subset of hard cases
- IoU improves on real camera scenes (shadows, perspective, clutter)

A small reduction in MAE alone does **not** justify a model that increases FAILs.

---

# On-device pipeline evaluation: `CornerPipelineEvalTest`

`evaluate_docquad_models.py` measures the **model**. What the user sees in the crop screen is the result of the whole Android pipeline: DocQuad postprocessing, the OpenCV candidate, the best-of selection and the edge-snap refinement. `CornerPipelineEvalTest` (instrumented test, `app/src/androidTest/.../ui/crop/`) measures exactly that on the **same test set layout** (`images/` + `labels/*.json`).

```bash
./gradlew :app:connectedStandardDebugAndroidTest \
  -PcornerEvalDir=training/data/docquad_real_eval \
  -Pandroid.testInstrumentationRunnerArguments.class=de.schliweb.makeacopy.ui.crop.CornerPipelineEvalTest
```

- The test set is bundled into the androidTest APK **only** when `-PcornerEvalDir` is passed; without it the test is skipped.
- Labels are in stored-pixel coordinates (no EXIF rotation), identical to what the Python script reads via PIL.
- Reports are pulled to `app/build/outputs/connected_android_test_additional_output/`:
  `corner_eval_report.md` (summary table) and `corner_eval_report.json` (per-sample corners and errors).
- The test never fails on quality; it measures. Compare reports before/after a change.

> ⚠️ `connected…AndroidTest` uninstalls the app after the run. The debug build shares the application id `de.schliweb.makeacopy`, so **use an emulator or a test device**, not a phone whose scans you want to keep.

### Running it on a phone without losing app data

Install both APKs in place and start the instrumentation yourself — nothing gets uninstalled:

```bash
./gradlew :app:assembleStandardDebug :app:assembleStandardDebugAndroidTest \
  -PcornerEvalDir=training/data/<eval_set>
adb install -r -t app/build/outputs/apk/standard/debug/app-standard-arm64-v8a-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/standard/debug/app-standard-debug-androidTest.apk
adb shell am instrument -w \
  -e class de.schliweb.makeacopy.ui.crop.CornerPipelineEvalTest \
  de.schliweb.makeacopy.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/de.schliweb.makeacopy/files/corner_eval_report/ .
```

`adb install -r` only works over an existing **debug** install (same signing key); it fails harmlessly over a release install. Afterwards `adb uninstall de.schliweb.makeacopy.test` removes the test APK (and the bundled photos) without touching the app.

### Evaluating a candidate model

`-PcornerEvalModel=<file.ort>` additionally bundles a model into the test APK; the harness then evaluates it instead of the shipped asset, which stays untouched. The report header and `meta.model` name the model that was used.

```bash
./gradlew :app:assembleStandardDebugAndroidTest \
  -PcornerEvalDir=training/data/<eval_set> \
  -PcornerEvalModel=training/runs/<run>/ort/<distinct_name>.ort
```

Give candidates distinct file names: the runner caches models in the app's cache directory by name. The candidate must pass the operator check in [README.md, section 17](README.md#17-ort-format-conversion-optional-size-optimized), otherwise it does not load on the device.

Stages reported (each also as `+refine`, i.e. after `EdgeSnapCornerRefiner`):

| Stage | Meaning |
|-------|---------|
| `docquad_plain` | single unrotated `DocQuadDetector` pass, as used by the live preview (reported without `+refine`) |
| `docquad` | crop-screen DocQuad stage (`CornerDetectorFactory.docQuadForCrop`, i.e. with test-time rotation unless disabled), unclamped |
| `opencv`  | OpenCV contour/Hough candidate (fallback rectangle counts as FAIL) |
| `bestof`  | what `TrapezoidSelectionView.chooseBestCropCorners` picks — the product result (a DocQuad result with confidence ≥ `DOCQUAD_TRUSTED_CONFIDENCE` wins outright, otherwise the shape comparison decides) |

### Tilted documents: `make_tilt_eval_set.py`

DocQuadNet is weak on documents tilted by more than ~20° (small rotation augmentation in training). To measure that without new labels, rotate an existing labelled set about the image centre, which is what a tilted camera does:

```bash
python3 training/scripts/make_tilt_eval_set.py \
  --in_dir training/data/<own_unseen_set> \
  --out_dir training/data/own_tilt_eval \
  --per_image 2
```

The canvas size is kept and uncovered corners are filled by reflection (a constant fill adds a straight high-contrast border that detectors mistake for a document edge). Use only source sets the evaluated model has never seen, keep one rotated set untouched as a holdout when tuning thresholds, and do not use third-party datasets whose terms or agreements exclude this use.

Per-sample fields in `corner_eval_report.json` that make offline what-if analysis possible without another device run: `docquad_confidence` / `docquad_plain_confidence` (peak probability × corner/mask agreement of the crop-screen detector and of the single pass), `docquad_chosen_source` (`CORNERS`, `MASK` or `CORNERS_ROT<angle>` when a rotated pass won), `docquad_shape_score` / `opencv_shape_score` and `opencv_from_hough` (inputs of the best-of policy), `bestof_picked`, and timings. Decide threshold changes from these device reports: a Python replica of the preprocessing agrees with the device on confident detections but not in the low-confidence zone where such thresholds act.

Errors are relative to the image diagonal and minimised over cyclic corner orderings (`order shifted` counts samples where the outline was right but the corner labels were rotated). The **Best-of selection** section reports how often the policy picked the candidate that was actually closer to ground truth, and the regret when it did not.

---

## Summary

- Augmentation increases robustness in training
- Augmentation does **not** replace independent test images
- Original images used as augmentation bases must be excluded from test
- Real-world evaluation requires **additional images**, but only a small number
- This setup provides reliable guidance for product decisions in MakeACopy

> **Each image chooses once: training (with augmentation) or test (original only).**
