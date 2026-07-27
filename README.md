# MakeACopy

MakeACopy is an open-source **offline document scanner with OCR** for Android, designed for privacy-friendly and self-hosted document workflows.

It allows you to digitize paper documents into **searchable PDFs** while
keeping the entire processing pipeline on your device. The app works
completely offline and avoids any cloud services, telemetry, or tracking.

MakeACopy is designed to be a **reliable building block for privacy-friendly
document workflows**, especially when combined with self-hosted storage
systems.

## Looking for a Microsoft Lens alternative?

With the discontinuation of Microsoft Lens, many users are looking for a reliable way to scan documents on Android.

MakeACopy offers the same core building blocks — document scanning, OCR, and searchable PDF export — while following a fundamentally different design:

- **Fully offline** – no cloud services required
- **No tracking or telemetry**
- **Open source and auditable**
- **Designed for long-term, self-hosted workflows**

Instead of integrating into a cloud ecosystem, MakeACopy focuses on local processing and interoperability with tools like SambaLite, paperless-ngx, and Syncthing.

This makes it especially suitable for users who prefer **control, privacy, and predictable behavior** over cloud-based convenience features.

## Mobile Document Pipeline

MakeACopy can be combined with
[SambaLite](https://github.com/egdels/SambaLite) to create a fully automated
mobile document workflow:

```
Paper document
      ↓
Scan with MakeACopy
      ↓
Inbox folder
      ↓
SambaLite folder sync
      ↓
NAS / archive (e.g. paperless-ngx)
```

This setup effectively turns your smartphone into a **privacy-friendly mobile
network scanner** for self-hosted document archives.

## Design Philosophy

MakeACopy treats privacy and offline operation as technical design decisions, not as moral or political statements.
The app works fully offline, is open source, and avoids tracking or cloud services because this results in a simpler, more robust, and more auditable system.
Users are encouraged to evaluate MakeACopy based on its concrete features and implementation rather than on positioning or narratives.

### App Size Note

To ensure fully offline operation, MakeACopy bundles OCR language models, fonts, and image processing components locally.
This significantly increases the app size, but avoids any cloud dependencies or on-demand downloads.

**100 MB are not a sign of excess, but of consistency.**

### Editions and Flavors

MakeACopy is available in two editions (controlled via the Gradle property `edition`):

| Edition | OCR Languages | Fonts | APK Size (arm64-v8a) |
|---------|--------------|-------|---------------------|
| **Full** | 23 languages (incl. CJK, Arabic, Hindi, Thai, Turkish) | 5 fonts (incl. CJK, Devanagari) | ~99 MB |
| **Light** | English + German | 1 font (NotoSans) | ~58 MB |

Both editions include the same ML-based document detection (ONNX model), OpenCV image processing, and full offline functionality. Both editions use an operator-stripped ONNX Runtime build that includes only the operators required for document detection. The Light edition additionally ships fewer OCR language models and fonts, and uses a minimal ONNX Runtime AAR without XNNPACK and NNAPI to further reduce APK size.

In addition, MakeACopy is built in two product flavors (`ocr` flavor dimension):

| Flavor | OCR Backend | Notes |
|--------|-------------|-------|
| **paddle** | PaddleOCR V5 | Published flavor for F-Droid and Google Play. Uses PaddleOCR only, without Tesseract runtime, Tesseract language data, or OCR dictionaries. |
| **standard** | Tesseract only | Legacy flavor for existing Tesseract language data and workflows. Available via GitHub Releases. No PaddleOCR assets bundled. |

The Full + `paddle` combination is what is published on F-Droid and Google Play. The `standard` Tesseract flavor and the Light edition are available via [GitHub Releases](https://github.com/egdels/makeacopy/releases).

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
alt="Get it on F-Droid"
height="80">](https://f-droid.org/packages/de.schliweb.makeacopy/)
[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
alt="Get it on Google Play"
height="80">](https://play.google.com/store/apps/details?id=de.schliweb.makeacopy)

[![Downloads last month](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fgithub.com%2Fkitswas%2Ffdroid-metrics-dashboard%2Fraw%2Frefs%2Fheads%2Fmain%2Fprocessed%2Fmonthly%2Fde.schliweb.makeacopy.json&query=%24.total_downloads&logo=fdroid&label=Downloads%20last%20month)](https://f-droid.org/packages/de.schliweb.makeacopy/)
[![Downloads all time](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fgithub.com%2Fkitswas%2Ffdroid-metrics-dashboard%2Fraw%2Frefs%2Fheads%2Fmain%2Fprocessed%2Ftotal%2Fde.schliweb.makeacopy.json&query=%24.total_downloads&logo=fdroid&label=Downloads%20(all%20time))](https://f-droid.org/packages/de.schliweb.makeacopy/)

Or download the latest APK from the [Releases Section](https://github.com/egdels/makeacopy/releases/latest).

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## APK Verification

All official releases of MakeACopy are signed with one of the following certificates:

- **Upload key** (used for GitHub releases, F-Droid, and sideload APKs)  
  SHA-256: AE:32:2D:3F:B7:1A:FE:21:DF:47:27:E3:7A:5C:68:03:51:1D:5A:2F:E1:FC:31:35:43:0C:EE:06:99:FA:1B:34

- **Google Play App Signing key** (used for Play Store releases)  
  SHA-256: C0:71:44:39:CB:51:62:32:A4:47:91:7A:6F:C2:28:1E:45:FA:AA:DD:37:F8:30:B1:01:1F:B4:85:68:8E:0D:64

### Verify with apksigner
```bash
apksigner verify --print-certs MakeACopy-vX.Y.Z.apk
```

## Features

- **Camera Scanning**: Capture documents using the device camera
- **Edge Detection**: Automatic document edge detection using OpenCV, enhanced with a custom-trained machine learning model (ONNX, Apache 2.0)
- **Perspective Correction**: Adjust and crop documents with manual or automatic perspective correction
- **Image Enhancement**: Apply filters (grayscale, contrast, sharpening)
- **OCR**: Offline text recognition with PaddleOCR in the published `paddle` flavor
- **OCR Review**: Interactive review and correction of OCR results with word-level editing, dictionary-based suggestions, confidence highlighting, and re-OCR for individual words
- **PDF Export**: Save as searchable PDF with recognized text
- **JPEG Export**: Export scans as high-quality JPEG images (configurable quality, color/BW)
- **Multi-page Scanning**: Combine multiple pages into one document; reorder and manage pages before export
- **Last Scans**: Quickly access and reuse your most recent scans
- **Inbox Mode**: Automatically save scans to a predefined folder — ideal for paperless-ngx, Nextcloud, or Syncthing workflows
- **Share & Save**: Export locally or share with other apps
- **Dark Mode**: Material 3 theme with day/night support
- **Privacy-Focused**: 100% offline functionality, no internet connection required

### Accessibility Mode
- MakeACopy includes an Accessibility Mode that provides spoken and haptic feedback and lets you use the hardware volume keys as the shutter.
- Guide:
  - English: [docs/accessibility_mode_guide_en.md](docs/accessibility_mode_guide_en.md)
  - Deutsch: [docs/accessibility_mode_guide_de.md](docs/accessibility_mode_guide_de.md)
  - Français: [docs/accessibility_mode_guide_fr.md](docs/accessibility_mode_guide_fr.md)

### Inbox Mode
- MakeACopy includes an Inbox Mode that automatically saves scans to a predefined folder — ideal for integration with paperless-ngx, Nextcloud, or Syncthing workflows.
- Guide:
  - English: [docs/inbox_mode_guide_en.md](docs/inbox_mode_guide_en.md)
  - Deutsch: [docs/inbox_mode_guide_de.md](docs/inbox_mode_guide_de.md)
  - Français: [docs/inbox_mode_guide_fr.md](docs/inbox_mode_guide_fr.md)

### OCR Languages

MakeACopy supports fully offline OCR. The published `paddle` flavor uses PaddleOCR; choose the closest script or language group in the OCR language selector during the OCR step.

Supported out of the box:
- English (English)
- German (Latin), French (Latin), Italian (Latin), Spanish (Latin), Portuguese (Latin), Dutch (Latin), Polish (Latin), Czech (Latin), Slovak (Latin), Hungarian (Latin), Romanian (Latin), Danish (Latin), Norwegian (Latin), Swedish (Latin), Turkish (Latin)
- Chinese (Simplified) (Chinese), Chinese (Traditional) (Chinese)
- Russian (East Slavic), Thai (Thai)
- Arabic (Arabic), Persian/Farsi (Arabic)
- Hindi (Devanagari)
- Greek (Greek)

In the `paddle` flavor, select the visible OCR language group for your document:

| Document language | Choose in the OCR language selector |
|-------------------|-------------------------------------|
| English | English |
| German, French, Italian, Spanish, Portuguese, Dutch, Polish, Czech, Slovak, Hungarian, Romanian, Danish, Norwegian, Swedish, Turkish | Latin |
| Chinese Simplified or Traditional | Chinese |
| Russian | East Slavic |
| Thai | Thai |
| Arabic and Persian/Farsi | Arabic |
| Hindi | Devanagari |
| Greek | Greek |

The F-Droid and Google Play links point to the `paddle` flavor. Use GitHub Releases if you need the `standard` Tesseract flavor.

Notes:
- All OCR runs fully offline on-device.
- For Chinese, MakeACopy includes appropriate CJK fonts for better PDF text embedding.
- For Arabic and Persian, MakeACopy includes the Noto Naskh Arabic font for proper RTL (right-to-left) text rendering in PDF exports.
- For Hindi, MakeACopy includes the Noto Sans Devanagari font for proper Devanagari text rendering in PDF exports.

## Screenshots

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png" alt="MakeACopy screenshot 1" width="110" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png" alt="MakeACopy screenshot 2" width="110" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png" alt="MakeACopy screenshot 3" width="110" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_en-US.png" alt="MakeACopy screenshot 4" width="110" />
</p>

## Installation

### F-Droid

MakeACopy is F-Droid compliant. The F-Droid listing provides the `paddle` flavor. The app builds all required native components from source during CI/local builds:

1. **OpenCV Java Classes**: The required OpenCV Java wrapper classes are directly included in the app's source tree (copied from OpenCV but now part of this project). They are no longer used from the submodule.
2. **OpenCV Native Libraries**: All OpenCV native libraries are built from source using the official OpenCV code provided via the Git submodule at `external/opencv`.
3. **ONNX Runtime Native Libraries**: For ML-assisted edge detection, ONNX Runtime is built from source (XNNPACK and NNAPI, Java bindings) using the submodule at `external/onnxruntime` via `scripts/build_onnxruntime_android.sh`. The resulting artifacts are integrated into `app/src/main/jniLibs/<ABI>/` (e.g., `libonnxruntime.so`, `libonnxruntime4j_jni.so`) and `app/libs/` (`onnxruntime-*.jar`).

This approach ensures F-Droid compatibility by not including any pre-compiled binaries in the repository and building OpenCV and ONNX Runtime native components from source.

### GitHub Releases

You can download the latest APKs from the [Releases](https://github.com/egdels/makeacopy/releases) page. Use GitHub Releases if you need the `standard` Tesseract flavor or the Light edition.

#### Automated Builds

All automated builds are handled by a single GitHub Actions workflow:

- Workflow: [.github/workflows/build-release.yml](.github/workflows/build-release.yml)
- Triggers: on push to main, pull_request to main, and tags starting with v*

The workflow contains two parallel jobs:

**Full build** (`build`):
- Sets up JDK 21 (Temurin) and installs Android NDK 28.0.13004108
- Pins CMake 3.31.6 and Python 3.11.2 for deterministic native builds
- Checks out submodules (external/opencv and external/onnxruntime)
- Builds OpenCV native libraries from source via scripts/build_opencv_android.sh
- Collects reproducibility evidence for native builds (scripts/collect_repro_evidence.sh)
- Integrates OpenCV artifacts into the app via scripts/prepare_opencv.sh
- Builds ONNX Runtime for Android (XNNPACK and NNAPI, Java bindings) via scripts/build_onnxruntime_android.sh — the operator config supports DocQuad corner detection and PaddleOCR V5
- Builds the Full edition Android app with Gradle for both flavors (`paddle` and `standard`), producing the published Paddle artifacts and the Tesseract legacy artifacts
- Verifies that no test data leaks into release APKs via `:app:verifyNoTestDataInApk`
- Renames artifacts to `MakeACopy-vX.Y.Z-<abi>-release.apk` (standard) and `MakeACopy-vX.Y.Z-<abi>-paddle-release.apk` (paddle), plus release AABs

**Light build** (`build-light`):
- Depends on the Full build job and downloads the native libraries (jniLibs and JARs) built from source
- Sets up JDK 21 only (no native build scripts needed)
- Builds the Light edition for the `standard` flavor only: `./gradlew :app:assembleStandardRelease -Pedition=light` (PaddleOCR is not part of the Light edition)
- Produces per-ABI APKs named `MakeACopy-Light-vX.Y.Z-<abi>-release.apk`

Behavior by event type:
- Push/PR to main (non-tag):
  - Builds unsigned artifacts (Full: Release AAB and per-ABI Release APKs; Light: per-ABI Release APKs)
  - Uploads all artifacts for download from the workflow run
- Tag (refs/tags/vX.Y.Z):
  - Optionally decodes a keystore from repository secrets and signs the builds
  - Verifies APK signatures using apksigner
  - Renames artifacts to `MakeACopy-vX.Y.Z-<abi>-release.apk` (standard), `MakeACopy-vX.Y.Z-<abi>-paddle-release.apk` (paddle), `MakeACopy-Light-vX.Y.Z-<abi>-release.apk`, plus release AABs
  - Generates SHA-256 checksum files for each artifact
  - Loads release notes from fastlane/metadata/android/en-US/changelogs/<versionCode>.txt
  - Creates a GitHub Release and attaches all Full `paddle`, Full `standard`, and Light APKs, release AABs, and their .sha256 files
  - Uploads artifacts to the workflow as well

How to trigger a release build:
1. Create a tag starting with v (e.g., v1.0.0)
2. Push the tag to GitHub
3. The workflow will build native libraries (OpenCV, ONNX Runtime) from source, build the app, sign (if secrets provided), verify, checksum, and publish a GitHub Release with artifacts

Notes:
- All native components are built from source to stay F-Droid compatible; no prebuilt binaries are stored in the repo.
- Both editions use an operator-stripped ONNX Runtime build. The Full edition includes XNNPACK and NNAPI support and is built via `scripts/build_onnxruntime_android.sh`. The Light edition reuses the OpenCV native libraries from the Full build and builds a minimal ONNX Runtime AAR (without XNNPACK/NNAPI) from source via `scripts/build_minimal_onnxruntime.sh`, further reducing APK size.
- The edition is controlled via the Gradle property `edition` (default: `full`). F-Droid builds use the default and require no special configuration.
- A look at [.github/workflows/build-release.yml](.github/workflows/build-release.yml) shows you how the build process works and how you can reproduce it in your own development environment.

#### Building the Light Edition

```bash
./gradlew :app:assembleRelease -Pedition=light -PenableAbiSplits=true
```

The Light edition uses `applicationIdSuffix ".light"` (`de.schliweb.makeacopy.light`) and includes only English and German OCR models, the NotoSans-Regular font, and English/German dictionaries.

## Architecture

MakeACopy follows the Single-Activity + Multi-Fragment pattern with MVVM architecture.

- **Camera Fragment**: Capture via CameraX/Camera2
- **Crop Fragment**: Perspective correction
- **OCR Fragment**: Engine-specific offline recognition via PaddleOCR in the published `paddle` flavor or Tesseract in the `standard` legacy flavor
- **Export Fragment**: PDF/text export

## Libraries Used

| Purpose | Library / Model | License |
|---------|-----------------|--------|
| Image Processing | [OpenCV](https://opencv.org) 4.13.0 | Apache 2.0 |
| ML Inference | [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT |
| Document Corner Detection | Custom-trained ONNX model | Apache 2.0 |
| OCR (`paddle` flavor) | [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) PP-OCRv5 mobile (det + 8× rec, exported to ORT) | Apache 2.0 |
| OCR (`standard` legacy flavor) | [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) 4.9.0 | Apache 2.0 |
| OCR Language Data (`standard` legacy flavor) | [tessdata](https://github.com/tesseract-ocr/tessdata) | Apache 2.0 |
| PDF | Android PdfDocument, pdfbox-android | Apache 2.0 |
| Fonts (CJK) | [Noto Sans CJK](https://github.com/notofonts/noto-cjk) | OFL 1.1 |
| Fonts (Japanese Kana) | [Noto Sans JP](https://github.com/notofonts/noto-cjk) (Kana subset) | OFL 1.1 |
| Fonts (Latin) | [Noto Sans](https://github.com/notofonts/noto-fonts) | OFL 1.1 |
| Fonts (Arabic) | [Noto Naskh Arabic](https://github.com/notofonts/arabic) | OFL 1.1 |
| Fonts (Devanagari) | [Noto Sans Devanagari](https://github.com/notofonts/devanagari) | OFL 1.1 |
| UI | Material Components | Apache 2.0 |
| OCR Dictionaries | [FrequencyWords](https://github.com/hermitdave/FrequencyWords) | CC BY-SA 4.0 |

Third-party license texts are included in the [`third_party_licenses/`](third_party_licenses/) directory.
For full attribution details, see the [`NOTICE`](NOTICE) file.

### OCR Dictionaries

MakeACopy uses word frequency dictionaries for OCR post-processing to improve text recognition accuracy.

**Source:** [FrequencyWords by Hermit Dave](https://github.com/hermitdave/FrequencyWords)  
**License:** [Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)](https://creativecommons.org/licenses/by-sa/4.0/)  
**Data Origin:** Wikipedia word frequency lists

The dictionaries are used to validate and correct OCR results by checking recognized words against known word lists. This helps reduce common OCR errors while maintaining fully offline operation.

Included dictionaries cover 23 languages: Arabic (ara), Czech (ces), Danish (dan), German (deu), English (eng), Persian (fas), French (fra), Hindi (hin), Hungarian (hun), Italian (ita), Dutch (nld), Norwegian (nor), Polish (pol), Portuguese (por), Romanian (ron), Russian (rus), Slovak (slk), Spanish (spa), Swedish (swe), Thai (tha), Turkish (tur), Chinese Simplified (chi_sim), and Chinese Traditional (chi_tra).

## Submodules

- external/opencv — OpenCV source used to build native libraries during the build; Apache 2.0.
- external/onnxruntime — ONNX Runtime source required for ML-assisted corner detection; MIT License.
  - Built from source via scripts/build_onnxruntime_android.sh (XNNPACK and NNAPI, Java bindings).
  - Artifacts integrated into app/src/main/jniLibs/<ABI>/ (libonnxruntime.so, libonnxruntime4j_jni.so) and app/libs/ (onnxruntime-*.jar).
  - See [NOTICE](NOTICE) for attributions.

### Optional OCR Language-Packs for the `standard` Tesseract flavor

The published `paddle` flavor uses PaddleOCR models and does not use Tesseract Language-Pack APKs. If you use the `standard` Tesseract flavor from GitHub Releases and want **higher accuracy OCR**, you can install optional *Language-Pack APKs*:

- **MakeACopy OCR Latin (Best)** → Includes best-quality models for  
  English (eng), German (deu), French (fra), Italian (ita), Spanish (spa), Portuguese (por), Dutch (nld), Polish (pol), Czech (ces), Slovak (slk), Hungarian (hun), Romanian (ron), Danish (dan), Norwegian (nor), Swedish (swe).

How it works:
- Language-Packs are separate, permissionless APKs.
- They only contain `.traineddata` files, no code, no network.
- MakeACopy discovers installed packs locally and lets you import the models.
- Once imported, the best models appear in the OCR language selection.

📦 [GitHub Release Page](https://github.com/egdels/makeacopy/releases/tag/langpack-latin-best-v1.1.0)

Notes:
- Packs are signed with the same key as the main app, so you can verify them with `apksigner`.
- They rarely change (only when new Tesseract models are published).

Alternatively: You can also use the original Tesseract "best" models from the official tessdata repository.
- Source: https://github.com/tesseract-ocr/tessdata_best
- Tesseract project: https://github.com/tesseract-ocr/tesseract  
  Install or download the desired `.traineddata` files and add them via the same import dialog in MakeACopy.

## Training data & models

This project includes a machine-learning model for document corner detection.
Only the **exported ONNX inference model** is distributed with the application.
No training datasets, images, labels, or intermediate checkpoints are included
or redistributed.

### Training documentation

Details about the model architecture, training pipeline, datasets used for
pretraining and fine-tuning, evaluation methodology, and reproducibility
considerations are documented separately.

See: [Training README](training/README.md)

### Datasets used during training (not redistributed)

The following publicly available datasets were used **internally** for training
and evaluation purposes only:

- **UVDoc Dataset**  
  Used for pretraining to learn generic document geometry and perspective
  across a wide range of layouts and capture conditions.

- **SmartDoc Dataset (ICDAR 2015)**  
  Used for fine-tuning and robustness training on real smartphone document
  capture scenarios.

- **Describable Textures Dataset (DTD)**  
  Used exclusively during internal training experiments for background
  diversification (background replacement) to reduce dataset bias.  
  The dataset itself and any derived images are **not included** in this
  repository or the application.

- **CORD Dataset (Consolidated Receipt Dataset)**  
  Used for fine-tuning and evaluation on receipt-specific document layouts
  and capture conditions (e.g. narrow formats, long aspect ratios, cluttered
  backgrounds).

  License: Creative Commons Attribution 4.0 International (CC BY 4.0)  
  https://creativecommons.org/licenses/by/4.0/

  The dataset and its annotations remain the property of their respective
  authors. No CORD images, labels, or derivatives are included or redistributed
  with this project or the application.

### Licensing note

The exported ONNX inference model is an independently created work and is
licensed under the **Apache License 2.0**, consistent with the rest of this
project.

Dataset licenses remain with their respective authors.
All datasets mentioned above (including UVDoc, SmartDoc, DTD, and CORD) are used
strictly for **internal training and evaluation**. No dataset images, labels,
derived images, or training checkpoints are redistributed as part of this
project or the application.

For details, see the [`NOTICE`](NOTICE) file.

## Privacy

MakeACopy respects your privacy:

- Works 100% offline
- No tracking, telemetry, or analytics
- No cloud upload
- Requires only camera and storage permissions

See our [Privacy Policy](https://egdels.github.io/makeacopy/privacy) for details.

## Related Projects

### SambaLite

[SambaLite](https://github.com/egdels/SambaLite) is a lightweight open-source
SMB client for Android that provides secure access to NAS and network shares.

When used together with MakeACopy, it enables a fully automated document
workflow:

```
Paper
   ↓
Scan with MakeACopy
   ↓
Inbox folder
   ↓
SambaLite sync
   ↓
NAS / archive
```

This combination turns an Android phone into a **mobile document scanner
for network-based archives**.

## Contributing

Contributions welcome!

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## Future Enhancements

- Add more languages to OCR detection
- Extend and refine the PaddleOCR V5 path in the published `paddle` flavor
## 🙌 Community Hall of Fame

A big thank you to the people who make *MakeACopy* better:

- Our very first supporter — created an issue, helped with a fix, and even made a donation.  
  This is the spirit of open source: collaboration, improvement, and appreciation.
- **KalleMerkt** (from the [android-hilfe.de](https://www.android-hilfe.de/) community) – provided valuable suggestions to make the user experience smoother and more intuitive.
- **tofg** – stayed persistent and helped track down a tricky SD card issue affecting some devices. Thanks to thorough testing, the fix now benefits all users.
- **Felix H.** – supported the project with a kind donation at a moment of self-doubt,  
  a reminder that *MakeACopy* truly means something to people out there.
- **teastrainer** – tested several release candidates with remarkable precision and patience,  
  helping to refine the crop and rotation workflow step by step.  
  A great example of constructive collaboration.
- **dansseg** – provided a highly precise bug report and helped validate the fix by testing multiple release candidates.  
  His report made it possible to identify and resolve a critical Android 15 crash on the Redmi Note 13 Pro 5G quickly and reliably.  
  A great example of how fast and precise open-source collaboration can be.
- **hekele1022** – tested multiple release candidates on a Sony Xperia device, providing logs and feedback that confirmed the new camera system works reliably without any device-specific hacks.  
  A key contribution that helped make *MakeACopy* fully cross-device compatible.
- **djayko** – helped test Sony builds with great patience in real-world use, confirming the stability of the new camera flow.  
  A calm, steady tester spirit that every open-source project dreams of.
- **pvagner (Peter)** – initiated and co-shaped the **Accessibility Mode** through issue-driven discussion and extensive real-world testing.  
  His detailed feedback led to substantial improvements in camera guidance, OCR rotation and export behavior, and the accessibility documentation (see issue #44).

  Working together with Peter on accessibility was genuinely enjoyable.  
  The collaboration was constructive, thoughtful, and ultimately helped improve *MakeACopy* as a whole.

### 📝 Notes from the Field

- A public review claimed that *MakeACopy* installs other apps and drains the battery.  
  This is technically impossible, as the app only requests the `CAMERA` permission.  
  This highlights how misunderstandings can sometimes appear in public reviews.

- An anonymous email criticized the application size without providing actionable feedback.  
  While not directly addressable, it serves as a reminder that open-source projects may also receive non-constructive input.

Want to join the Hall of Fame?  
Contribute code, file helpful issues, or support the project.

## ❤️ Support this project
MakeACopy is free and open source.
If you find it useful, please consider supporting development:

[![Ko-fi](https://img.shields.io/badge/Buy%20me%20a%20coffee-Ko--fi-orange)](https://ko-fi.com/egdels)
[![PayPal](https://img.shields.io/badge/Donate-PayPal-blue)](https://www.paypal.com/paypalme/egdels)

## License

```
Copyright 2025 Christian Kierdorf

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
