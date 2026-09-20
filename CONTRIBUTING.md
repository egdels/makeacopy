# Contributing to MakeACopy

This guide explains how to set up a development environment for MakeACopy on **Linux** and **macOS**.

> **Version alignment:** All local tool versions (JDK, NDK, CMake, Python) must match the versions defined in the GitHub Actions workflow (`.github/workflows/build-release.yml`) to ensure reproducible builds and F-Droid compatibility. The versions listed below are derived from that workflow.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| **JDK** | 17 (Temurin recommended) | Required for Gradle and Android builds |
| **Android SDK** | API 36 | `compileSdk = 36`, `minSdk = 29` |
| **Android NDK** | 28.0.13004108 | Only needed if building native libs from source (Option B) |
| **CMake** | 3.31.6 | Only needed if building native libs from source (Option B) |
| **Python** | 3.11.x | Only needed if building native libs from source (Option B) |
| **Git** | any recent | With submodule support |

## 1. Clone the Repository

```bash
git clone --recurse-submodules https://github.com/egdels/makeacopy.git
cd makeacopy
```

If you already cloned without `--recurse-submodules`:

```bash
git submodule update --init --recursive
```

The project uses two Git submodules:
- `external/opencv` — OpenCV 4.13.0
- `external/onnxruntime` — ONNX Runtime v1.24.1

## 2. Install JDK 21

### macOS

```bash
brew install openjdk@21
```

Or install [Eclipse Temurin](https://adoptium.net/) manually.

### Linux (Debian/Ubuntu)

```bash
sudo apt-get install -y openjdk-21-jdk
```

### Linux (Fedora)

```bash
sudo dnf install -y java-21-openjdk-devel
```

Verify:

```bash
java -version   # should show 17.x
```

## 3. Install Android SDK and NDK

### Option A: Android Studio (recommended)

1. Install [Android Studio](https://developer.android.com/studio).
2. Open **SDK Manager** → **SDK Platforms** → install **Android API 36**.
3. Under **SDK Tools**, install:
   - **NDK (Side by side)** version **28.0.13004108**
   - **CMake** (see section below for exact version)
4. Set the environment variable:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"          # Linux
export ANDROID_HOME="$HOME/Library/Android/sdk"   # macOS
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"
```

### Option B: Command-line only

```bash
# Install NDK via sdkmanager
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "ndk;28.0.13004108" \
  "platforms;android-36" \
  "build-tools;36.0.0"
```

## 4. Install CMake 3.31.6

The project requires CMake **3.31.6** to match F-Droid reproducible builds.

### macOS

```bash
brew install cmake
# If brew installs a different version, download 3.31.6 manually:
# https://github.com/Kitware/CMake/releases/tag/v3.31.6
```

### Linux

```bash
# Download and install CMake 3.31.6
wget https://github.com/Kitware/CMake/releases/download/v3.31.6/cmake-3.31.6-linux-x86_64.tar.gz
tar xzf cmake-3.31.6-linux-x86_64.tar.gz
export PATH="$PWD/cmake-3.31.6-linux-x86_64/bin:$PATH"
```

Verify:

```bash
cmake --version   # should show 3.31.6
```

## 5. Install Python 3.11

Python is needed by CMake scripts during native library builds.

### macOS

```bash
brew install python@3.11
```

### Linux (Debian/Ubuntu)

```bash
sudo apt-get install -y python3.11
```

Verify:

```bash
python3 --version   # should show 3.11.x
```

## 6. Obtain Native Libraries

The app requires native libraries (OpenCV and ONNX Runtime). You have two options:

### Option A: Extract from a Release APK (recommended for contributors not modifying native code)

Recommended for contributors working exclusively on Java/Kotlin code, UI, OCR flow, business logic, or tests — without modifying native code. You can obtain the prebuilt libraries from CI artifacts or extract them from an official release APK.

#### From CI artifacts

Every CI build on `main` uploads a **`native-libs`** artifact containing `jniLibs/` and `libs/`. Download it from the [Actions tab](https://github.com/egdels/makeacopy/actions/workflows/build-release.yml), extract it, and place the contents into your project:

```bash
# After downloading and unzipping native-libs.zip:
cp -r app/src/main/jniLibs/ <project>/app/src/main/jniLibs/
cp -r app/libs/              <project>/app/libs/
```

#### From a release APK

You can also extract the native libraries from any official release APK:

```bash
# 1. Download a release APK (e.g. arm64-v8a)
wget https://github.com/egdels/makeacopy/releases/latest/download/MakeACopy-v3.1.0-arm64-v8a-release.apk

# 2. Extract native libraries
unzip -o MakeACopy-v3.1.0-arm64-v8a-release.apk 'lib/*' -d /tmp/apk_extract

# 3. Copy to project (APK uses lib/<abi>/, project uses jniLibs/<abi>/)
mkdir -p app/src/main/jniLibs/arm64-v8a
cp /tmp/apk_extract/lib/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/
```

For the ONNX Runtime JAR, download it from the same CI artifact or from [Maven Central](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-android) (version 1.24.1) and place it in `app/libs/`.

> **Note:** Each APK contains only one ABI. For development on an emulator (x86_64) or a physical device (arm64-v8a), you only need the matching ABI. Extract from the corresponding APK.

> **Important:** The `app/src/main/jniLibs/` directory is listed in `.gitignore` and must **not** be committed to the repository. The same applies to `app/libs/` (ONNX Runtime JAR). These directories contain build artifacts that are either built from source (Option B) or obtained from CI/release APKs (Option A). Committing them would violate the project's policy of not storing prebuilt binaries in the repository.

### Option B: Build from source (required for native changes and F-Droid)

If you need to modify native code or reproduce F-Droid builds, compile the native libraries from source.

#### Set environment variables

```bash
export ANDROID_HOME="$HOME/Android/Sdk"                    # adjust for your system
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ORT_CMAKE="$(which cmake)"
export OPENCV_CMAKE="$(which cmake)"
export PY3_BIN="$(which python3)"
export BUILD_GENERATOR="Unix Makefiles"
```

#### Build OpenCV

```bash
chmod +x scripts/build_opencv_android.sh
VERBOSE=1 ./scripts/build_opencv_android.sh
```

#### Prepare OpenCV for the app

```bash
chmod +x scripts/prepare_opencv.sh
./scripts/prepare_opencv.sh
```

This copies the compiled `.so` files into `app/src/main/jniLibs/`.

#### Build ONNX Runtime

```bash
chmod +x scripts/build_onnxruntime_android.sh
./scripts/build_onnxruntime_android.sh
```

The script builds ONNX Runtime with the **CPU execution provider only** and strips it down to the operators of the bundled models. Notes for local builds:

- The host `python3` needs the `flatbuffers` package (`python3 -m pip install flatbuffers`); without it the operator reduction fails with `cannot import name 'parse_config'`.
- If the script picks an older CMake from the Android SDK, point it to the required one: `ORT_CMAKE=/path/to/cmake-3.31.6/bin/cmake`.
- `JOBS=<n>` limits the parallel compile jobs (default: all cores).
- `ORT_USE_XNNPACK=1` and/or `ORT_USE_NNAPI=1` build the optional execution providers in again. The app does not use them (NNAPI was ~12× slower for the corner model on a Pixel 7a, XNNPACK brought no gain); this is only useful to repeat that measurement with `DocQuadLatencyBenchmarkTest` on other hardware.

> **Tip:** To build only for a specific ABI (faster for development):
> ```bash
> ABIS="arm64-v8a" ./scripts/build_opencv_android.sh
> ABIS="arm64-v8a" ./scripts/prepare_opencv.sh
> ABIS="arm64-v8a" ./scripts/build_onnxruntime_android.sh
> ```

## 7. Build the App

```bash
chmod +x gradlew

# Debug APK (with ABI splits)
./gradlew :app:assembleDebug

# Debug APK (single universal APK, faster)
./gradlew :app:assembleDebug -PenableAbiSplits=false

# Release APK
./gradlew :app:assembleRelease
```

## 8. Run Tests

### JVM Unit Tests

```bash
# All unit tests
./gradlew :app:testDebugUnitTest

# Single test class
./gradlew :app:testDebugUnitTest --tests "de.schliweb.makeacopy.ExampleSanityTest"
```

### Instrumented Tests (requires emulator or device)

```bash
# All instrumented tests
./gradlew :app:connectedDebugAndroidTest

# Single test class
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments=class=de.schliweb.makeacopy.SomeInstrumentedTest
```

> The emulator or device must run **API 29+** (matching `minSdk`).

## IDE Setup

### Android Studio / IntelliJ IDEA

1. Open the project root directory.
2. Let Gradle sync complete.
3. Ensure **annotation processing** is enabled (required for Lombok and Room):
   - **Settings → Build → Compiler → Annotation Processors → Enable annotation processing**
4. The project uses **Java 21** — make sure your IDE JDK is set accordingly.

### Lombok

The project uses Lombok (`compileOnly` + `annotationProcessor`). Install the Lombok plugin in your IDE for proper code completion and navigation.

## Project Structure

```
makeacopy/
├── app/                          # Android application module
│   ├── src/main/java/            # Main source code
│   ├── src/test/java/            # JVM unit tests
│   ├── src/androidTest/java/     # Instrumented tests
│   └── src/main/jniLibs/         # Native libraries (generated)
├── external/
│   ├── opencv/                   # OpenCV submodule
│   ├── onnxruntime/              # ONNX Runtime submodule
│   └── opencv_pinned_jni/        # Pinned JNI headers for reproducibility
├── scripts/                      # Build scripts for native libraries
├── fastlane/                     # Fastlane metadata and changelogs
├── docs/                         # Documentation
├── langpack-latin-best/          # OCR language pack module
├── training/                     # ML model training resources
└── server/                       # Server module
```

## Feature Flags

The app uses `BuildConfig` booleans to gate features:

| Flag | Default | Description |
|------|---------|-------------|
| `FEATURE_SCAN_LIBRARY` | `true` | Scan library functionality |
| `FEATURE_REVIEW_OCR` | `true` | OCR review and correction |
| `FEATURE_FRAMING_LOGGING` | `false` | Debug logging for framing engine |
| `FEATURE_A11Y_GUIDANCE` | `true` | Accessibility guidance mode |
| `FEATURE_LAYOUT_ANALYSIS` | `false` | Layout analysis feature |
| `FEATURE_INBOX_MODE` | `true` | Inbox Mode – automatic export to a default directory |

When adding new features, prefer **default-off** for risky or experimental changes.

## 9. Code Quality

The project uses three code-quality plugins. Run them regularly before pushing changes.

### Spotless (Code Formatting)

Spotless enforces [Google Java Format](https://github.com/google/google-java-format) on all Java sources.

```bash
# Check formatting (fails if code is not formatted)
./gradlew :app:spotlessCheck

# Auto-format all Java sources
./gradlew :app:spotlessApply
```

> **Tip:** Run `spotlessApply` before every commit to avoid CI failures.

### JaCoCo (Test Coverage)

JaCoCo generates a coverage report from the debug unit tests.

```bash
./gradlew :app:jacocoTestReport
```

Reports are generated at:
- **HTML:** `app/build/reports/jacoco/jacocoTestReport/html/index.html`
- **XML:** `app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`

### Error Prone (Static Analysis)

Error Prone runs automatically during every Java compilation — no separate command needed. All findings are reported as **warnings** in the compiler output.

```bash
./gradlew :app:compileStandardDebugJavaWithJavac
```

> **Note:** Error Prone only analyses Java sources (not Kotlin). OpenCV sources under `org.opencv` are excluded from analysis.

### Android Lint (Static Analysis)

Android Lint checks for accessibility issues, unused resources, performance problems, and more.

```bash
./gradlew :app:lintStandardDebug :app:lintPaddleDebug
```

The HTML reports are generated at:
- `app/build/reports/lint-results-standardDebug.html`
- `app/build/reports/lint-results-paddleDebug.html`

> **Note:** Intentional suppressions are documented in `app/lint.xml` with explanations for each ignored check.

### Run All Quality Checks Together

```bash
./gradlew :app:spotlessCheck :app:lintStandardDebug :app:lintPaddleDebug :app:jacocoTestReport
```

This checks formatting (Spotless), runs Android Lint, compiles the code (Error Prone runs automatically), executes unit tests, and generates the coverage report (JaCoCo).

## Tips

- **ABI splits**: Enabled by default. Use `-PenableAbiSplits=false` for a single universal APK during development.
- **Hamcrest**: The project pins a single Hamcrest version for `androidTest`. When adding test dependencies, exclude `hamcrest-core` and `hamcrest-library` to avoid duplicate classes.
- **Test PDFs**: Place test assets under `app/src/debug/assets` or test directories, not in `main` — test PDFs are excluded from release builds.
- **Native builds take time**: The first OpenCV and ONNX Runtime builds can take 30–60 minutes. Subsequent builds are faster if the build directories are cached.

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
