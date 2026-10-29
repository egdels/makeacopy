# PaddleOCR PP-OCRv5 mobile (Multi-Model) — Third-Party Notices

This directory documents the upstream source, license, and provenance of the
PaddleOCR **PP-OCRv5 mobile** detection and recognition models that are
shipped in every build of the app as **NCNN** assets under
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

The shipped `*.ncnn.param` / `*.ncnn.bin` files are produced from upstream Paddle inference weights
through the following local pipeline:

1. **Download** Paddle inference weights via
   [`scripts/spike_paddle_rec_model.sh`](../../scripts/spike_paddle_rec_model.sh)
   (uses `paddlex.create_model` under the hood, plus the detection model).
2. **Convert** Paddle → ONNX (`opset=11`) with `paddle2onnx`, see
   [`scripts/convert_paddleocr_v5_all.sh`](../../scripts/convert_paddleocr_v5_all.sh).
3. **Convert** ONNX → NCNN with NCNN's converter pnnx (pinned version
   `20261020`, weights in single precision, `fp16=0`), see
   [`scripts/convert_models_to_ncnn.sh`](../../scripts/convert_models_to_ncnn.sh).
   The script writes the `*.ncnn.param` / `*.ncnn.bin` files and the
   `*_dict.txt` files into the asset directory and rewrites its `SHA256SUMS`.

The intermediate `*.onnx` files are spike / conversion artifacts only and
are **never** shipped as release assets.

## Shipped artifacts

What is actually packaged into the APK under
`assets/paddleocr/v5/`:

- `det.ncnn.param` + `det.ncnn.bin` (≈ 4.7 MB) — detection
- `<lang>_PP-OCRv5_mobile_rec.ncnn.param` + `.ncnn.bin` × 8 and
  `PP-OCRv5_mobile_rec.ncnn.param` + `.ncnn.bin`
  (≈ 7.7–8.0 MB each, generic multilingual ≈ 16 MB) — recognition; per model
  the `.param` file is the layer list, the `.bin` file holds the weights
- `<lang>_PP-OCRv5_mobile_rec_dict.txt` × 8 + `PP-OCRv5_mobile_rec_dict.txt`
  — per-model character dictionaries
- `SHA256SUMS` — asset-internal pin file (consumed by `verifyModelAssetSha256`
  at build time, pinning every model and dictionary file, and by `PaddleAssets`
  at runtime for the dictionaries it copies to the cache)
- `SUMMARY.txt` — human-readable provenance summary

There are no build flavors any more: every APK (Google Play and F-Droid, all
ABIs) contains these files, and OCR runs on PaddleOCR only.

## Upstream projects and licenses

The redistributed weights and dictionaries are © the PaddleOCR / PaddlePaddle
authors and contributors and are redistributed here under the terms of the
Apache License, Version 2.0. ONNX / NCNN conversion does not change the
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
- **NCNN** — https://github.com/Tencent/ncnn
  - License: BSD 3-Clause — see [`../ncnn_LICENSE.txt`](../ncnn_LICENSE.txt).
  - Its converter pnnx is used at build/spike time to convert ONNX → NCNN;
    NCNN itself is the inference engine at runtime. The Android library is
    built from source from the submodule `external/ncnn` as a *reduced* build
    (CPU only, single precision, only the layers the bundled models use; see
    `app/src/main/cpp/ncnn_config.cmake`).

The full text of the Apache 2.0 license is included verbatim in this folder
as `LICENSE-Apache-2.0.txt`. The BSD 3-Clause license of NCNN is included as
`third_party_licenses/ncnn_LICENSE.txt`.

## Trademarks

Apache License 2.0 §6 explicitly does *not* grant trademark rights. "PaddleOCR",
"PaddlePaddle", "PaddleX", and related marks and logos are trademarks of their
respective owners (Baidu, Inc. and the PaddlePaddle authors).

MakeACopy is **not** affiliated with, endorsed by, or sponsored by Baidu, Inc.
or the PaddlePaddle / PaddleOCR project. The PaddleOCR PP-OCRv5 mobile models
are used here solely as a third-party component under the terms of the Apache
License 2.0; their inclusion does not imply any trademark license.

Likewise, "ONNX" is a trademark of its respective owner (the LF AI & Data
Foundation); the ONNX format is used here only as an intermediate conversion
format, without any trademark grant.

## SHA-256 pins

Cryptographic pins for the redistributed payload live in
[`SHA256SUMS`](SHA256SUMS) in this folder. Only the **dictionary** files are
pinned in this folder's file — these are stable, byte-reproducible text
artifacts and are the primary correctness anchor between the upstream
`inference.yml` and the runtime CTC decoder.

The model files (`*.ncnn.param`, `*.ncnn.bin`) are pinned in the
asset-internal `app/src/main/assets/paddleocr/v5/SHA256SUMS`, which
`scripts/convert_models_to_ncnn.sh` rewrites on every conversion. The Gradle
task `verifyModelAssetSha256` verifies every model and dictionary file against
that file (and likewise the corner model against
`app/src/main/assets/docquad/SHA256SUMS`).

The `*_dict.txt` hashes in this file MUST stay identical to the corresponding
entries in `app/src/main/assets/paddleocr/v5/SHA256SUMS` (the latter is the
file actually consumed by the build).
