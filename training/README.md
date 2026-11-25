# DocQuadNet-256 Training

This document describes the training pipeline for **DocQuadNet-256**, the document corner detection model used in the MakeACopy Android app.

Only the exported model, converted from ONNX to **NCNN** format, is shipped with **MakeACopy**. Datasets and intermediate checkpoints are **not redistributed**.

---

## 1) Model Architecture

The model is called `DocQuadNet256` (M2) and detects a document quadrilateral in a 256×256 RGB input image.

### Architecture Overview

- **Backbone**: `MobileNetV3` (`large` or `small`)
- **Feature extraction**: Collects feature maps at multiple resolutions (64/32/16, optionally 8)
- **Feature Pyramid Network (FPN)**: Lightweight top-down network with bilinear upsampling produces a shared 64×64 feature map
- **Outputs**:
  - `mask_head`: 1-channel output `mask_logits` with shape `[B, 1, 64, 64]`
  - `corner_head`: 4 heatmaps `corner_heatmaps` with shape `[B, 4, 64, 64]` (one per corner)

Both outputs are logits (no `sigmoid`/`softmax`) and are converted to probabilities only in post-processing/loss.

For deployment, the model is exported via an ONNX wrapper with two tensor outputs: `corner_heatmaps` first, then `mask_logits`.

### Backbone Size (`large` vs `small`)

| Backbone | Characteristics |
|----------|-----------------|
| `large` | More parameters, higher compute, better robustness on hard cases (perspective, blur, clutter) |
| `small` | Fewer parameters, faster inference, lighter on-device, may lose accuracy on difficult samples |

**Important**: The backbone type is fixed at training time. You cannot convert a `large` checkpoint to `small` or vice versa — the weights are incompatible. The ONNX export script auto-detects the backbone from the checkpoint config.

---

## 2) Training Strategy Overview

### High-Level Goals

1. **No leakage**: Train/val/test must be **sequence-disjoint** (no frames from the same SmartDoc sequence in different splits)
2. **Generalization > sample count**: Prefer **diversity** over redundant frames
3. **Break SmartDoc background bias**: Introduce **background diversity** (DTD background replacement) in training
4. **Keep geometry broad**: Use a small **UVDoc tail** during fine-tuning to prevent collapse into SmartDoc domain

### Training Pipeline

```
UVDoc Pretrain → V1 Fine-tune (SmartDoc + DTD + UVDoc tail) → [Optional] V2 Hard-Focus
```

---

## 3) Training Data

### UVDoc (Pretraining)

- Project: https://github.com/tanguymagne/UVDoc-Dataset
- Expected path: `training/data/UVDoc_final-dataset`

### SmartDoc 2015 (Fine-tuning)

- Paper: ICDAR 2015 SmartDoc competition
- License: CC BY 4.0
- Expected path: `training/data/smartdoc/`
- Labels: `training/labels/smartdoc_labels.jsonl`

**Important**: SmartDoc contains video frames that are near-duplicates. The pipeline uses **sequence-disjoint splitting** to prevent data leakage.

### DTD (Background Augmentation)

- Describable Textures Dataset for background replacement
- Expected path: `training/data/dtd/`

### Own Images (Optional)

- Labeled MakeACopy-specific images
- Can be augmented and mixed into training

---

## 4) Dataset Conventions

### SmartDoc "Sequence"

A **sequence** is defined as `(bg_name, model_name)` derived from filename: `backgroundXX_<model>_frame_####.jpeg`

**Rule**: All frames from a sequence must belong to exactly one split (train OR val OR test).

### CORE vs HARD Split

SmartDoc is split by document area fraction:

- **CORE**: `area_frac >= 0.15` (larger documents in frame)
- **HARD**: `area_frac < 0.15` (smaller documents, extreme perspective)

### "Trainable" Format

All datasets are converted into the strict `trainable` format:

```
<trainable_dir>/
  images/
  labels/
  split_train.txt
  split_val.txt
```

### Corner Order

Corners must always be in **clockwise order starting at top-left**: TL → TR → BR → BL.

"Top-left" is geometric (as seen in the image the model gets), not semantic: `make_trainable_from_converted.py` canonicalizes the order (sort by angle around the centroid, start at `min(x+y)`), and the rotation augmentation re-applies the same rule after rotating.

### Coordinate System

`corners_px` are **stored pixel coordinates** — the EXIF orientation of a photo is *not* applied. That is how PIL (training, evaluation) and Android's `BitmapFactory` read the pixels. `labeler.py` displays photos upright (`cv2.imread` applies EXIF), so `convert_to_docquad.py` maps its corners back to stored coordinates (`--label_coords exif`, default). Details: [EVALUATION.md](EVALUATION.md).

---

## 5) Dataset Preparation Pipeline

### Step 1: Convert UVDoc

```bash
python3 training/scripts/convert_uvdoc.py \
  --uvdoc training/data/UVDoc_final-dataset \
  --out training/labels/uvdoc_all.jsonl \
  --copy-images \
  --images-dir training/data/uvdoc_all
```

### Step 2: Convert to DocQuad Format

```bash
python3 training/scripts/convert_to_docquad.py \
  --in_images training/data/uvdoc_all \
  --in_jsonl training/labels/uvdoc_all.jsonl \
  --out_dir training/data/docquad_uvdoc_all_converted
```

### Step 3: Make Trainable

```bash
python3 training/scripts/make_trainable_from_converted.py \
  --in_images training/data/docquad_uvdoc_all_converted/images \
  --in_labels training/data/docquad_uvdoc_all_converted/labels \
  --out_dir training/data/docquad_uvdoc_all_trainable
```

### Step 4: Validate Dataset

```bash
python3 training/scripts/validate_docquad_dataset.py \
  --in_dir training/data/docquad_uvdoc_all_trainable
```

This generates `split_train.txt` and `split_val.txt` (90/10 split).

---

## 6) SmartDoc Preparation (Sequence-Disjoint)

### Step 1: Convert SmartDoc

```bash
python3 training/scripts/convert_smartdoc.py \
  --smartdoc data/smartdoc15/frames \
  --out training/labels/smartdoc_labels.jsonl \
  --copy-images \
  --images-dir training/data/smartdoc
```

### Step 2: Sequence-Disjoint Split + Subsampling

```bash
python3 training/scripts/split_smartdoc_sequences.py \
  --labels-file training/labels/smartdoc_labels.jsonl \
  --out-dir training/labels \
  --train-ratio 0.8 --val-ratio 0.1 --test-ratio 0.1 \
  --k-train 20 --k-val 5 --k-test 5 \
  --seed 42
```

**Output**:
- Subsampled: `smartdoc_{train,val,test}_sub.jsonl`
- Full: `smartdoc_{train,val,test}.jsonl`

### Step 3: CORE/HARD Split (Optional)

```bash
python3 training/scripts/split_smartdoc_by_area.py \
  --images_root training/data/smartdoc \
  --labels_jsonl training/labels/smartdoc_train_sub.jsonl \
  --out_images_root training/data/smartdoc_split \
  --out_core_labels training/labels/smartdoc_train_core.jsonl \
  --out_hard_labels training/labels/smartdoc_train_hard.jsonl \
  --area_frac_thresh 0.15 \
  --overwrite
```

### Step 4: Convert to Trainable

```bash
python3 training/scripts/make_trainable_from_converted.py \
  --in_labels_jsonl training/labels/smartdoc_train_sub.jsonl \
  --in_images training/data/smartdoc \
  --out_dir training/data/smartdoc_train_sub_trainable
```

---

## 7) Background Augmentation (DTD)

Replace SmartDoc backgrounds with DTD textures to break table bias.

**Important**: Use DTD augmentation only for training, not for val/test.

```bash
python3 training/scripts/augment_smartdoc_bg_dtd.py \
  --smartdoc-dir training/data/smartdoc \
  --labels-file training/labels/smartdoc_train_sub.jsonl \
  --dtd-dir training/data/dtd \
  --output-dir training/data/smartdoc_bg_aug \
  --global-seed 42 \
  --dilate 2 \
  --feather 2
```

Convert to trainable:

```bash
python3 training/scripts/make_trainable_from_converted.py \
  --in_labels_jsonl training/data/smartdoc_bg_aug/labels.jsonl \
  --in_images training/data/smartdoc_bg_aug/images \
  --out_dir training/data/smartdoc_bg_aug_trainable
```

---

## 8) Training

### UVDoc Pretraining

```bash
python3 training/scripts/train_docquad_heatmap.py \
  --base_dir training/data/docquad_uvdoc_all_trainable \
  --epochs 100 \
  --batch 8 \
  --lr 1e-3 \
  --out_dir training/runs/docquad_uvdoc_pretrain_small \
  --sigma 2.0 \
  --early_stop_patience 0 \
  --backbone small
```

### V1 Fine-tuning (SmartDoc + DTD + UVDoc Tail)

**Training data composition**:
- SmartDoc TRAIN (subsampled): **40%**
- SmartDoc TRAIN + DTD background: **30%**
- UVDoc tail: **30%**

**Mix datasets**:

```bash
python3 training/scripts/mix_datasets.py \
  --mode ratio \
  --a_dir training/data/smartdoc_train_sub_trainable \
  --b_dir training/data/smartdoc_bg_aug_trainable \
  --extra_dir uvdoc=training/data/docquad_uvdoc_all_trainable \
  --ratio "a=0.40 b=0.30 uvdoc=0.30" \
  --target_size 8000 \
  --seed 42 \
  --out_dir training/data/mix_v1_smartdoc_bg_uvdoctail_trainable \
  --out_report training/reports/mix_v1_report.json \
  --overwrite
```

**Train V1**:

```bash
python3 training/scripts/train_docquad_heatmap.py \
  --base_dir training/data/mix_v1_smartdoc_bg_uvdoctail_trainable \
  --epochs 100 \
  --batch 8 \
  --lr 1e-4 \
  --out_dir training/runs/docquad_v1_smartdoc_bg_uvtail_from_uvdoc_small \
  --init_ckpt training/runs/docquad_uvdoc_pretrain_small/checkpoints/best.pt \
  --sigma 2.0 \
  --early_stop_patience 5 \
  --backbone small
```

### V2 Fine-tuning (Optional, Hard-Case Focus)

Only do this if V1 improves generalization but still struggles on small docs/extreme perspective.

**Training data composition**:
- SmartDoc TRAIN HARD: **30%**
- SmartDoc TRAIN HARD + DTD BG: **20%**
- SmartDoc TRAIN CORE: **20%**
- SmartDoc TRAIN CORE + DTD BG: **20%**
- UVDoc tail: **10%**

```bash
python3 training/scripts/train_docquad_heatmap.py \
  --base_dir training/data/mix_v2_hard_focus_trainable \
  --epochs 100 \
  --batch 8 \
  --lr 3e-5 \
  --out_dir training/runs/docquad_v2_hard_focus_from_v1_small \
  --init_ckpt training/runs/docquad_v1_smartdoc_bg_uvtail_from_uvdoc_small/checkpoints/best.pt \
  --sigma 2.0 \
  --early_stop_patience 5 \
  --backbone small
```

### Rotation Fine-tuning (Tilted Documents)

Without rotation augmentation the model fails on documents tilted by more than ~20°: all four corner heatmaps stay flat and the mask is wrong as well, so no postprocessing can recover the quad. `train_docquad_heatmap.py` has an opt-in online rotation augmentation for this:

- the photo is rotated about its centre, the canvas size is kept and uncovered corners are filled by **reflection** (a constant fill adds a straight high-contrast border that looks like a document edge);
- angles that would push a corner out of the frame are retried with the opposite sign / smaller magnitude, otherwise the sample stays unrotated;
- the corner order is re-canonicalized after rotating;
- the validation split gets the same augmentation with a **fixed rotation per sample**, so `val.corner_mae_px` stays comparable between epochs and "best" reflects tilted documents too (it is *not* comparable with runs trained without the flag).

```bash
python3 training/scripts/train_docquad_heatmap.py \
  --base_dir training/data/mix_v2_with_receipts_trainable \
  --epochs 30 \
  --batch 8 \
  --lr 3e-5 \
  --out_dir training/runs/docquad_v6_rot40_from_v2_receipts \
  --init_ckpt training/runs/docquad_v2_receipts_finetune/checkpoints/best.pt \
  --aug_rotate_deg 40 \
  --aug_rotate_prob 0.5 \
  --early_stop_patience 5

# The script has no LR scheduler. Once val.corner_mae_px only oscillates, finish with a lower
# learning rate (the CLI value overrides the one stored in the checkpoint):
python3 training/scripts/train_docquad_heatmap.py \
  --base_dir training/data/mix_v2_with_receipts_trainable \
  --epochs 36 \
  --batch 8 \
  --lr 1e-5 \
  --out_dir training/runs/docquad_v6_rot40_from_v2_receipts \
  --resume training/runs/docquad_v6_rot40_from_v2_receipts/checkpoints/last.pt \
  --aug_rotate_deg 40 \
  --aug_rotate_prob 0.5
```

The trainer keeps only `best.pt` and `last.pt`; copy `last.pt` after an epoch if you want to evaluate that specific state later.

Keep `--aug_rotate_prob` well below 1 so upright documents stay in the training distribution. ±40° is a deliberate limit: around 45° it becomes ambiguous which corner is "top-left". The app covers stronger tilts with test-time rotation in the crop screen (`RotatingDocQuadDetector`).

This recipe produced the model shipped since 4.7.0 (30 epochs at 3e-5, then 6 at 1e-5; best state: epoch 35, `val.corner_mae_px` 13.4 → 9.8). Measured on the device with `CornerPipelineEvalTest` against the previous model (mean corner error relative to the image diagonal, failures counted as 100%, sets unseen by both models):

| Set | Single pass (live preview) | Crop screen result |
|-----|----------------------------|--------------------|
| Tilted hold-out (150) | 5.2% → 1.4% | 1.9% → 1.0% |
| Upright own photos (61) | 3.9% → 2.5% | 2.2% → 1.6% |
| SmartDoc val (75) | 0.45% → 0.40% | 0.55% → 0.53% |

Checkpoints on the plateau differ by only a few images on these sets, and neither `val.total_loss` nor `val.corner_mae_px` predicts their on-device ranking reliably, so measure the final candidates on the device rather than picking by the validation metric alone. What remains hard afterwards is not tilt but small documents and cluttered, low-contrast backgrounds.

### Training Parameters

| Parameter | Description |
|-----------|-------------|
| `--base_dir` | Must contain `images/`, `labels/`, and split files |
| `--sigma` | Gaussian spread for 64×64 corner heatmap targets (range: 2-4) |
| `--early_stop_patience 0` | Disables early stopping |
| `--backbone` | `large` (default) or `small` |
| `--init_ckpt` | Initialize from pretrained weights (model only, not optimizer) |
| `--resume` | Continue training from checkpoint (includes optimizer state); `--epochs` is the total, so raise it to train longer. Pass the augmentation flags again |
| `--aug_rotate_deg` | Rotation augmentation: max absolute angle in degrees (`0` = off, default) |
| `--aug_rotate_prob` | Share of samples that get rotated when `--aug_rotate_deg > 0` (default `0.5`) |

### Training Outputs

- `metrics.jsonl`: One JSON record per epoch
- `checkpoints/best.pt` and `checkpoints/last.pt`
- `vis/epoch_XX/*.png`: Visual debug images (green = label, red = prediction)

---

## 9) Evaluation

```bash
python3 training/scripts/evaluate_docquad_models.py \
  --test_dir training/data/smartdoc_val_sub_trainable \
  --model v1=training/runs/docquad_v1_smartdoc_bg_uvtail_from_uvdoc_small/checkpoints/best.onnx \
  --out training/reports/eval_v1_val.json \
  --mode product
```

### Metrics

- `corner_mae_px` (mean/median)
- IoU success rate
- Failure reasons: self-intersecting / non-convex / degenerate-area

**Rule**: Never evaluate on any dataset that shares sequences with training.

---

## 10) Export (ONNX)

```bash
python -m training.docquad_m3.export_onnx \
  --checkpoint training/runs/docquad_v1_smartdoc_bg_uvtail_from_uvdoc_small/checkpoints/best.pt \
  --out training/runs/docquad_v1_smartdoc_bg_uvtail_from_uvdoc_small/checkpoints/best.onnx
```

**Notes**:
- Run from repo root (so `training.*` module can be imported)
- Outputs: `corner_heatmaps` first, then `mask_logits` (both logits)
- Backbone type is auto-detected from checkpoint
- Use this module for app models. `scripts/export_docquad_heatmap_onnx.py` (adds a PyTorch↔ORT parity check and golden stats) exports dynamic shape handling (`Shape`/`Gather`/`Unsqueeze`) with recent PyTorch versions, which the fixed-size app model does not need; the app's NCNN build contains only the layers of the shipped models. See the layer check in [section 17](#17-ncnn-conversion).

---

## 11) FP16 Quantization

The exported FP32 ONNX model (13.3 MB, large) can be converted to FP16 to reduce size by 50% with practically no quality loss.

> **Note**: This is an offline comparison with Python `onnxruntime` only. The app's NCNN build runs in single precision only, and the NCNN conversion keeps the weights in single precision (`fp16=0`); an FP16 model is not shipped.

### Why FP16?

| | FP32 | FP16 |
|---|---|---|
| **Model size** | 13.3 MB | 6.7 MB |
| **Corner error Δ** | — | ±0.03 px |
| **Max output diff** | — | 0.24 |

The deviation is far below one pixel and has no measurable impact on document detection quality.

### Convert and Compare

```bash
python3 training/scripts/quantize_fp16_compare.py \
  --model training/runs/docquad_v1_smartdoc_bg_uvtail_from_uvdoc_small/checkpoints/best.onnx \
  --dataset training/data/my_data_trainable \
  --out /tmp/docquadnet256_fp16.onnx
```

The script:
1. Converts the FP32 model to FP16 using `onnxruntime.transformers.float16`
2. Runs inference on all images in the dataset with both models
3. Reports output deviations, mean corner error comparison, and inference times

---

## 12) Integration into MakeACopy

The app ships the model in NCNN format (see [section 17](#17-ncnn-conversion)); the ONNX export is the intermediate step.

**Preprocessing contract** — the app must feed the model exactly what training does (`DocQuadDetector`):

- letterbox to 256×256 on a **black** canvas. Mid-gray padding was tried in the app and measured worse (lower heatmap peak confidence; single-pass mean corner error 3.1% → 2.4% on upright photos when switching back to black). If the padding ever changes, change it in `DocQuadHeatmapDataset` too and retrain;
- RGB, `float32`, 0..1, NCHW;
- NCNN on the **CPU with 2 threads** (`DocQuadRunner`); `DocQuadLatencyBenchmarkTest` measures the latency on a device.

**Measure before swapping a model**: evaluate the candidate on the device with `CornerPipelineEvalTest -PcornerEvalModel=<path/name>` (the candidate's `<name>.ncnn.param` / `<name>.ncnn.bin`) against the shipped model on sets neither has seen ([EVALUATION.md](EVALUATION.md)). The test-time-rotation and best-of thresholds in the app depend on the model's confidence distribution and have to be re-checked for a new model.

```bash
scripts/convert_models_to_ncnn.sh <PADDLE_ONNX_DIR> \
  training/runs/docquad_v1_smartdoc_bg_uvtail_from_uvdoc_small/checkpoints/best.onnx
```

**Integrity check**: the script rewrites `app/src/main/assets/docquad/SHA256SUMS`; the Gradle task `verifyModelAssetSha256` verifies the model files against it at build time.

---

## 13) Available Scripts Reference

| Script | Purpose |
|--------|---------|
| `convert_uvdoc.py` | Convert UVDoc dataset to JSONL format |
| `convert_smartdoc.py` | Convert SmartDoc 2015 to JSONL format |
| `convert_to_docquad.py` | Convert JSONL + images to DocQuad format |
| `split_smartdoc_sequences.py` | Sequence-disjoint train/val/test split + subsampling |
| `split_smartdoc_by_area.py` | CORE/HARD split by document area fraction |
| `augment_smartdoc_bg_dtd.py` | DTD background replacement augmentation |
| `make_trainable_from_converted.py` | Convert to strict trainable format |
| `mix_datasets.py` | Deterministic multi-source dataset mixing |
| `validate_docquad_dataset.py` | Validate dataset and generate splits |
| `train_docquad_heatmap.py` | Model training |
| `evaluate_docquad_models.py` | Model evaluation |
| `labeler.py` | Manual corner labeling tool |
| `augmentation.py` | Data augmentation for own images |
| `analyze_cord_receipts.py` | Analyze CORD receipt aspect ratios |
| `synthesize_receipt_on_background.py` | Place receipts on DTD backgrounds with perspective |
| `quantize_fp16_compare.py` | FP16 quantization with quality comparison (offline) |
| `make_tilt_eval_set.py` | Build a tilted-document evaluation set by rotating a labelled set (see EVALUATION.md) |

---

## 14) Fine-tuning on Own Images

### Step 1: Prepare Images

```bash
mkdir -p training/data/my_data/images
# Copy your images (supported: .jpg, .jpeg, .png, .webp, .bmp)
```

### Step 2: Label Corners

```bash
python3 training/scripts/labeler.py \
  --img_dir training/data/my_data/images \
  --out training/labels/my_data.jsonl
```

**Controls**: drag points with mouse; `a` = save + next, `s` = save, `n/p` = next/prev, `r` = re-propose corners, `c` = center corners, `f` = fullscreen, `z` = fit-to-window, `q` = quit.

### Step 3: Augment (Optional)

```bash
python3 training/scripts/augmentation.py \
  --labels_in training/labels/my_data.jsonl \
  --images_in training/data/my_data/images \
  --labels_out training/labels/my_data_augmented.jsonl \
  --images_out training/data/my_data_augmented \
  --num_augmentations 5
```

### Step 4: Convert and Train

Follow the standard conversion pipeline (convert_to_docquad → make_trainable → validate), then fine-tune from a pretrained checkpoint.

---

## 15) Narrow Documents (Receipts) Training

The model may struggle with narrow documents (receipts, tickets) because training data (SmartDoc, UVDoc) contains mostly A4/Letter documents. This section describes how to add narrow document support.

### Problem Analysis

```bash
# Identify outliers in evaluation
python3 training/scripts/evaluate_docquad_models.py \
  --test_dir training/data/my_data_trainable \
  --model v1=training/runs/.../checkpoints/best.onnx \
  --out training/reports/eval_outliers.json \
  --mode product
```

Check the report for samples with low IoU - these are often narrow documents.

### Option A: Use CORD Receipt Dataset

CORD contains 932 receipt images. Extract narrow ones and synthesize training data:

```bash
# 1. Analyze CORD receipts
python3 training/scripts/analyze_cord_receipts.py \
  --cord-dir training/data/CORD \
  --min-aspect 2.0 \
  --top-n 30

# 2. Synthesize training images (place receipts on DTD backgrounds)
python3 training/scripts/synthesize_receipt_on_background.py \
  --receipts training/data/CORD/train/image training/data/CORD/test/image training/data/CORD/dev/image \
  --dtd-dir training/data/dtd \
  --output-dir training/data/cord_receipts_synthetic \
  --num-variants 10 \
  --min-aspect 2.0 \
  --seed 42

# 3. Convert to trainable format
python3 training/scripts/make_trainable_from_converted.py \
  --in_labels_jsonl training/data/cord_receipts_synthetic/labels.jsonl \
  --in_images training/data/cord_receipts_synthetic/images \
  --out_dir training/data/cord_receipts_trainable
```

### Option B: Augment Own Receipt Images

If you have labeled receipt images in `my_data_trainable`:

```bash
# 1. Extract receipt labels to JSONL
# 2. Apply geometric augmentation
python3 training/scripts/augmentation.py \
  --labels_in training/labels/receipts.jsonl \
  --images_in training/data/receipts/images \
  --labels_out training/labels/receipts_augmented.jsonl \
  --images_out training/data/receipts_augmented \
  --num_augmentations 5

# 3. Convert to trainable
python3 training/scripts/make_trainable_from_converted.py \
  --in_labels_jsonl training/labels/receipts_augmented.jsonl \
  --in_images training/data/receipts_augmented \
  --out_dir training/data/receipts_augmented_trainable
```

### V2 Training with Receipts

Mix receipt data into V2 training:

```bash
python3 training/scripts/mix_datasets.py \
  --mode ratio \
  --a_dir training/data/mix_v1_smartdoc_bg_uvdoctail_trainable \
  --extra_dir cord=training/data/cord_receipts_trainable \
  --extra_dir receipts=training/data/receipts_trainable \
  --extra_dir receipts_aug=training/data/receipts_augmented_trainable \
  --ratio "a=0.85 cord=0.10 receipts=0.025 receipts_aug=0.025" \
  --target_size 8000 \
  --seed 42 \
  --out_dir training/data/mix_v2_with_receipts_trainable \
  --out_report training/reports/mix_v2_receipts_report.json \
  --overwrite
```

Then fine-tune V1 checkpoint:

```bash
 python3 training/scripts/train_docquad_heatmap.py \
  --base_dir training/data/mix_v2_with_receipts_trainable \
  --epochs 5 \
  --batch 8 \
  --lr 3e-5 \
  --out_dir training/runs/docquad_v2_receipts_finetune_small \
  --init_ckpt training/runs/docquad_v1_smartdoc_bg_uvtail_from_uvdoc_small/checkpoints/best.pt \
  --sigma 2.0 \
  --early_stop_patience 5 \
  --backbone small

python3 training/scripts/train_docquad_heatmap.py \
  --base_dir training/data/mix_v2_with_receipts_trainable \
  --epochs 30 \
  --batch 8 \
  --lr 3e-5 \
  --out_dir training/runs/docquad_v2_receipts_finetune_small \
  --resume training/runs/docquad_v2_receipts_finetune_small/checkpoints \
  --sigma 2.0 \
  --early_stop_patience 5 \
  --backbone small

```

---

## 16) Practical Checklist (Updated)

| # | Step | Command/Action |
|---|------|----------------|
| 1 | UVDoc pretrain | `train_docquad_heatmap.py` with UVDoc trainable |
| 2 | Sequence-disjoint split | `split_smartdoc_sequences.py` |
| 3 | Convert splits to trainable | `make_trainable_from_converted.py` for each split |
| 4 | Create DTD BG-aug (TRAIN only) | `augment_smartdoc_bg_dtd.py` |
| 5 | Convert BG-aug to trainable | `make_trainable_from_converted.py` |
| 6 | Mix V1 dataset | `mix_datasets.py` |
| 7 | Train V1 | `train_docquad_heatmap.py` with init_ckpt |
| 8 | Evaluate on val/test | `evaluate_docquad_models.py` |
| 9 | (Optional) V2 hard-focus | Repeat mix + train with HARD emphasis |
| 10 | Rotation fine-tune | `train_docquad_heatmap.py --aug_rotate_deg 40 --aug_rotate_prob 0.5` |
| 11 | Export ONNX | `export_onnx` module |
| 12 | (Optional) FP16 comparison, offline only | `quantize_fp16_compare.py` |
| 13 | NCNN conversion + layer check | `scripts/convert_models_to_ncnn.sh`, compare the printed layers with `app/src/main/cpp/ncnn_config.cmake` |
| 14 | Measure on device | `CornerPipelineEvalTest -PcornerEvalModel=…` vs. the shipped model |
| 15 | Integrate into app | Converted files in `app/src/main/assets/docquad/`, update golden stats, re-check app thresholds |

---

## 17) NCNN Conversion

The app runs its models with NCNN. The ONNX export is converted with NCNN's converter pnnx (pinned version `20261020`, weights in single precision, `fp16=0`). In NCNN format each model is two files: `<name>.ncnn.param` (layer list) and `<name>.ncnn.bin` (weights).

### Prerequisites

`python3`; the script installs the pinned pnnx into a temporary virtual environment.

### Convert ONNX → NCNN

```bash
scripts/convert_models_to_ncnn.sh <PADDLE_ONNX_DIR> \
  training/runs/docquad_v1_smartdoc_bg_uvtail_from_uvdoc_small/checkpoints/best.onnx
```

The first argument is the directory with the PaddleOCR ONNX files (output of `scripts/convert_paddleocr_v5_all.sh`, default `paddle-spike/onnx`); the script always converts those models as well. With the second argument it also converts the corner model and writes:

| File | Purpose |
|------|---------|
| `app/src/main/assets/docquad/docquadnet256_trained_opset17.ncnn.param` | Layer list of the model |
| `app/src/main/assets/docquad/docquadnet256_trained_opset17.ncnn.bin` | Weights |
| `app/src/main/assets/docquad/SHA256SUMS` | Hashes of both files, checked by the Gradle task `verifyModelAssetSha256` |

To convert a candidate without touching the assets (e.g. for `-PcornerEvalModel`), run the same pnnx call directly; it writes `<name>.ncnn.param` and `<name>.ncnn.bin` next to the ONNX file:

```bash
pip install pnnx==20261020
pnnx <name>.onnx "inputshape=[1,3,256,256]" fp16=0
```

### Layer Check (Required for App Models)

The app's NCNN is built with only the layers the shipped models use. A model that needs any other layer does not load on the device. At the end, `convert_models_to_ncnn.sh` prints the layers used by the converted models; every one of them must be listed in `app/src/main/cpp/ncnn_config.cmake` (for a candidate converted by hand, read the layer names from the first column of its `.ncnn.param` file).

If additional layers appear because of shape operators in the ONNX export (`Shape`, `Gather`, `Unsqueeze`, `Concat`, `Slice`), constant-fold them with a fixed input shape and convert again — the outputs stay identical within float tolerance:

```python
import onnx
from onnxsim import simplify

model, ok = simplify(onnx.load("best.onnx"), overwrite_input_shapes={"input": [1, 3, 256, 256]})
assert ok
onnx.save(model, "best.static.onnx")
```

Any other additional layer has to be added to `ncnn_config.cmake`, and the native library has to be rebuilt (next section).

### Rebuild the Native Library

```bash
./scripts/build_ncnn_android.sh
```

This rebuilds `app/src/main/jniLibs/<ABI>/libmakeacopy_ncnn.so` with the layers listed in `ncnn_config.cmake`.

### Android Integration

The script writes the converted files into `app/src/main/assets/docquad/` under the shipped name, so no code change is needed: `DocQuadDetector.DEFAULT_MODEL_ASSET_PATH` names the model without the `.ncnn.param` / `.ncnn.bin` suffix, and `DocQuadRunner` reads both files straight from the APK assets.

---

## 18) Notes

### Few Own Images

With very few own images:
- Treat them as an optional small tail in V2 (5–15%) only if they are truly MakeACopy-domain hard cases
- Otherwise rely on SmartDoc HARD and DTD background diversification

### Known Pitfalls

- **Hamcrest versions**: Mixing versions in androidTest leads to duplicate classes
- **ABI splits**: Don't upload non-split builds alongside split builds to the same track
- **Backbone mismatch**: Cannot convert between `large` and `small` checkpoints
- **Dataset provenance**: Record for every mix which sources it contains and check them against the terms (and any personal agreements) under which you obtained the data *before* training — a fine-tune inherits the constraints of everything in its lineage, including the checkpoint it was initialized from. Evaluate only on sets that are in no training mix of the evaluated model
- **Labels on EXIF-rotated photos**: `corners_px` outside the image is the tell-tale sign of corners recorded on the upright view; `convert_to_docquad.py` handles it, `CornerPipelineEvalTest` skips and lists such labels
