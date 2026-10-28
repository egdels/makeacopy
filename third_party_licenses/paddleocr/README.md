# PaddleOCR PP-OCRv5 mobile (Multi-Model) — Third-Party Notices

This directory documents the upstream source, license, and provenance of the
PaddleOCR **PP-OCRv5 mobile** detection and recognition models that are
shipped — only in the `paddle` build flavor — as **ORT** assets under
`app/src/main/assets/paddleocr/v5/`.

This file replaces the previous single-recognition-model documentation
(`det.onnx` / `rec.onnx` / `rec_dict.txt` / fixed `V=438`). The runtime is
now multi-model with language-group routing; see
[`docs/paddleocr_v5_integration_concept.md`](../../docs/paddleocr_v5_integration_concept.md)
for the architectural truth.

## Models / Source

All models are obtained from the upstream PaddleOCR / PaddleX project (PP-OCRv5
mobile family).

Detection (1 model):

- `PP-OCRv5_mobile_det`

Recognition (9 language-group models, lazily loaded per OCR run):

- `en_PP-OCRv5_mobile_rec`        — English
- `latin_PP-OCRv5_mobile_rec`     — Latin-script languages (deu, fra, spa, ita, por, nld, …)
- `eslav_PP-OCRv5_mobile_rec`     — East-Slavic (rus, ukr, bel)
- `cyrillic_PP-OCRv5_mobile_rec`  — broader Cyrillic
- `arabic_PP-OCRv5_mobile_rec`    — Arabic / Persian
- `devanagari_PP-OCRv5_mobile_rec`— Devanagari (hin, mar, …)
- `th_PP-OCRv5_mobile_rec`        — Thai
- `el_PP-OCRv5_mobile_rec`        — Greek
- `PP-OCRv5_mobile_rec`           — generic multilingual / Chinese fallback

Each recognition model is paired with its own dictionary file
`<modelKey>_dict.txt`, extracted from the corresponding upstream
`inference.yml`. The CTC convention used by PaddleOCR applies: blank=0,
class `i>0` -> `dict[i-1]`; an implicit space token is appended by the
loader so that `len(dict)+1 == V` (vocab dimension of the rec model).

## Pipeline (provenance)

The shipped `*.ort` files are produced from upstream Paddle inference weights
through the following local pipeline:

1. **Download** Paddle inference weights via
   [`scripts/spike_paddle_rec_model.sh`](../../scripts/spike_paddle_rec_model.sh)
   (uses `paddlex.create_model` under the hood, plus the detection model).
2. **Convert** Paddle → ONNX (`opset=11`) with `paddle2onnx`, see
   [`scripts/convert_paddleocr_v5_all.sh`](../../scripts/convert_paddleocr_v5_all.sh).
3. **Reduce / convert** ONNX → ORT (basic optimization, with the operator set
   pinned in `paddleocr_v5.required_operators.config`), see
   [`scripts/convert_paddleocr_v5_to_ort_basic_all.py`](../../scripts/convert_paddleocr_v5_to_ort_basic_all.py)
   and
   [`scripts/generate_paddleocr_v5_ops_config_all.sh`](../../scripts/generate_paddleocr_v5_ops_config_all.sh).
4. **Stage** the resulting `*.ort`, `*_dict.txt`,
   `paddleocr_v5.required_operators.config`, `SHA256SUMS` and `SUMMARY.txt`
   into the asset directory via
   [`scripts/copy_paddleocr_v5_all_to_assets.sh`](../../scripts/copy_paddleocr_v5_all_to_assets.sh).

The intermediate `*.onnx` files are spike / conversion artifacts only and
are **never** shipped as release assets. Two independent gates enforce this:

- **Build-time:** the Gradle task `failOnPaddleOnnxAssets` fails the build
  if any `*.onnx` is found under `app/src/main/assets/paddleocr/v5/`.
- **Runtime:** `PaddleAssets.rejectOnnxAssetsOrThrow` refuses to start the
  Paddle engine if a `.onnx` ever appears in the extracted asset cache.

## Shipped artifacts

What is actually packaged into the `paddle` flavor APK under
`assets/paddleocr/v5/`:

- `PP-OCRv5_mobile_det.ort` (≈ 4.7 MB) — detection
- `<lang>_PP-OCRv5_mobile_rec.ort` × 8 + `PP-OCRv5_mobile_rec.ort`
  (≈ 7.6–7.9 MB each, generic multilingual ≈ 16 MB) — recognition
- `<lang>_PP-OCRv5_mobile_rec_dict.txt` × 8 + `PP-OCRv5_mobile_rec_dict.txt`
  — per-model character dictionaries
- `paddleocr_v5.required_operators.config` — pinned operator set used by the
  reduced ONNX Runtime build (includes `com.microsoft;1;FusedConv,FusedMatMul,QuickGelu,SkipLayerNormalization`)
- `SHA256SUMS` — asset-internal pin file (consumed by `verifyPaddleAssetSha256`
  at build time and by `PaddleAssets` at runtime; pins the 9 dict files only)
- `SUMMARY.txt` — human-readable provenance summary

The `standard` (F-Droid) flavor contains **none** of these files; Paddle
assets are only added via the `paddle`-flavor `srcDirs` in `app/build.gradle`.
The `paddle` flavor is additionally ABI-gated to `arm64-v8a`.

## Upstream projects and licenses

The redistributed weights and dictionaries are © the PaddleOCR / PaddlePaddle
authors and contributors and are redistributed here under the terms of the
Apache License, Version 2.0. ONNX / ORT conversion does not change the
upstream license.

- **PaddleOCR** — https://github.com/PaddlePaddle/PaddleOCR
  - License: Apache License 2.0 — see [`LICENSE-Apache-2.0.txt`](LICENSE-Apache-2.0.txt)
    (verbatim copy of the Apache 2.0 license).
  - Source of the inference weights and dictionaries.
- **PaddleX** — https://github.com/PaddlePaddle/PaddleX
  - License: Apache License 2.0.
  - Used as the download / packaging entry point for the PP-OCRv5 mobile
    weights via `paddlex.create_model`.
- **Paddle2ONNX** — https://github.com/PaddlePaddle/Paddle2ONNX
  - License: Apache License 2.0.
  - Used at build/spike time to convert Paddle inference graphs to ONNX
    (`opset=11`). Not redistributed in the APK.
- **ONNX Runtime** — https://github.com/microsoft/onnxruntime
  - License: MIT.
  - Used at build/spike time to convert ONNX → ORT (basic optimization) and
    at runtime as the inference engine. The Android runtime artifact is built
    as a *reduced* ORT build pinned to the operator set in
    `paddleocr_v5.required_operators.config`. The `com.microsoft` fused ops
    (`FusedConv`, `FusedMatMul`, `QuickGelu`, `SkipLayerNormalization`) are
    intentional members of that operator set; do not strip them.

The full text of the Apache 2.0 license is included verbatim in this folder
as `LICENSE-Apache-2.0.txt`. The MIT license of ONNX Runtime is reproduced in
the upstream project; ONNX Runtime binaries are not redistributed from this
folder (they are pulled as a Maven/AAR dependency by Gradle).

## Trademarks

Apache License 2.0 §6 explicitly does *not* grant trademark rights. "PaddleOCR",
"PaddlePaddle", "PaddleX", and related marks and logos are trademarks of their
respective owners (Baidu, Inc. and the PaddlePaddle authors).

MakeACopy is **not** affiliated with, endorsed by, or sponsored by Baidu, Inc.
or the PaddlePaddle / PaddleOCR project. The PaddleOCR PP-OCRv5 mobile models
are used here solely as a third-party component under the terms of the Apache
License 2.0; their inclusion does not imply any trademark license.

Likewise, "ONNX" and "ONNX Runtime" are trademarks of their respective owners
(the LF AI & Data Foundation / Microsoft Corporation); ONNX Runtime is used
here under the terms of the MIT license without any trademark grant.

## SHA-256 pins

Cryptographic pins for the redistributed payload live in
[`SHA256SUMS`](SHA256SUMS) in this folder. Only the **dictionary** files are
pinned here — these are stable, byte-reproducible text artifacts and are the
primary correctness anchor between the upstream `inference.yml` and the
runtime CTC decoder.

The reason `*.ort` files are *not* pinned:

- They are produced by a local `onnxruntime` basic-optimization conversion
  whose output is not guaranteed to be bit-for-bit reproducible across host
  toolchains. Pinning their SHA-256 would create false build failures on
  legitimate re-conversions.
- The Gradle task `verifyPaddleAssetSha256` therefore checks `*.ort` only for
  presence and non-empty size, while verifying every `*_dict.txt` against the
  asset-internal `SHA256SUMS`.

The `*_dict.txt` hashes in this file MUST stay identical to the corresponding
entries in `app/src/main/assets/paddleocr/v5/SHA256SUMS` (the latter is the
file actually consumed by the build).
