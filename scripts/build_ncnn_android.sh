#!/usr/bin/env bash
set -euo pipefail

# Builds libmakeacopy_ncnn.so (reduced NCNN + JNI bridge, see app/src/main/cpp) for every ABI and
# puts it under app/src/main/jniLibs/<abi>/.
#
#   ABIS="arm64-v8a"        build only some ABIs (default: all four)
#   JOBS=<n>                parallel compile jobs (default: all cores)
#   NCNN_CMAKE=<path>       cmake binary to use (default: cmake from PATH, else the SDK's)
#                           Release builds use cmake 3.31.6; another version yields libraries that
#                           work the same but are not byte-identical.
#   VERBOSE=1               show the full compiler output
#
# The result does not depend on where the repository lies, when it is built or how many jobs run:
# sources are compiled from a fixed directory under /tmp, paths are mapped away, there is no build
# id and no timestamp, and the libraries are stripped. It does depend on the NDK and the cmake
# version, so both are pinned for releases (NDK: app/build.gradle, cmake: RELEASE_CMAKE_VERSION).

if [ "${DEBUG:-0}" = "1" ] || [ "${TRACE:-0}" = "1" ]; then
  set -x
fi
VERBOSE="${VERBOSE:-0}"
info() { >&2 echo "$@"; }

export SOURCE_DATE_EPOCH=1700000000
export TZ=UTC
export LC_ALL=C

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
NCNN_DIR_ORIG="$REPO_DIR/external/ncnn"
BRIDGE_DIR_ORIG="$REPO_DIR/app/src/main/cpp"
JNI_LIBS_BASE="$REPO_DIR/app/src/main/jniLibs"
SRC_ROOT="/tmp/makeacopy-ncnn-src"
BUILD_ROOT="/tmp/makeacopy-ncnn-build"
LIB_NAME="libmakeacopy_ncnn.so"
RELEASE_CMAKE_VERSION="3.31.6"

[ -f "$NCNN_DIR_ORIG/CMakeLists.txt" ] || {
  echo "ERROR: $NCNN_DIR_ORIG is empty. Run: git submodule update --init external/ncnn" >&2
  exit 1
}

ABIS="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"

cpu_jobs() {
  getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 1
}
JOBS="${JOBS:-$(cpu_jobs)}"

# --- SDK / NDK -------------------------------------------------------------------------------
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
  elif [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
  fi
fi

# The NDK version is part of the result: use the one app/build.gradle pins.
NDK_VERSION="$(sed -n "s/^[[:space:]]*ndkVersion[[:space:]]*=[[:space:]]*'\([^']*\)'.*/\1/p" "$REPO_DIR/app/build.gradle" | head -n 1)"
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/ndk/$NDK_VERSION" ]; then
  export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
fi
[ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ] || {
  echo "ERROR: NDK $NDK_VERSION not found. Install it or set ANDROID_NDK_HOME." >&2
  exit 1
}
FOUND_NDK_VERSION="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$ANDROID_NDK_HOME/source.properties" 2>/dev/null || true)"
if [ -n "$NDK_VERSION" ] && [ "$FOUND_NDK_VERSION" != "$NDK_VERSION" ]; then
  info "WARN: NDK is $FOUND_NDK_VERSION, app/build.gradle pins $NDK_VERSION - the libraries will differ from a release build."
fi
TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
STRIP_BIN="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -maxdepth 3 -name llvm-strip | head -n 1)"
[ -x "$STRIP_BIN" ] || { echo "ERROR: llvm-strip not found in the NDK" >&2; exit 1; }

# --- cmake -----------------------------------------------------------------------------------
CMAKE_BIN="${NCNN_CMAKE:-}"
if [ -z "$CMAKE_BIN" ]; then
  if command -v cmake >/dev/null 2>&1; then
    CMAKE_BIN="$(command -v cmake)"
  elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/cmake" ]; then
    CMAKE_BIN="$(find "$ANDROID_SDK_ROOT/cmake" -maxdepth 3 -type f -name cmake | sort -V | tail -n 1)"
  fi
fi
[ -n "$CMAKE_BIN" ] && [ -x "$CMAKE_BIN" ] || { echo "ERROR: cmake not found (set NCNN_CMAKE)" >&2; exit 1; }
FOUND_CMAKE_VERSION="$("$CMAKE_BIN" --version | sed -n '1s/^cmake version //p')"
# The Android SDK's cmake reports its version with a build suffix ("3.31.6-g38307f9").
if [ "${FOUND_CMAKE_VERSION%%-*}" != "$RELEASE_CMAKE_VERSION" ]; then
  info "WARN: cmake is $FOUND_CMAKE_VERSION, releases are built with $RELEASE_CMAKE_VERSION - the libraries will differ from a release build."
fi

# One fixed generator: the order in which objects reach the linker must not depend on whether the
# build machine happens to have ninja.
GENERATOR_ARGS=(-G "Unix Makefiles")

info "NCNN build: ABIs=[$ABIS] NDK=$FOUND_NDK_VERSION cmake=$FOUND_CMAKE_VERSION jobs=$JOBS"

# --- sources at a fixed place ----------------------------------------------------------------
rm -rf "$SRC_ROOT" "$BUILD_ROOT"
mkdir -p "$SRC_ROOT/ncnn" "$SRC_ROOT/bridge" "$BUILD_ROOT"
# Only what the build reads; no .git, so NCNN cannot pick up a version from the checkout state.
for entry in CMakeLists.txt cmake src; do
  cp -R "$NCNN_DIR_ORIG/$entry" "$SRC_ROOT/ncnn/"
done
cp "$BRIDGE_DIR_ORIG/CMakeLists.txt" "$BRIDGE_DIR_ORIG/ncnn_config.cmake" "$BRIDGE_DIR_ORIG/ncnn_jni.cpp" "$SRC_ROOT/bridge/"

PREFIX_MAP="-ffile-prefix-map=$SRC_ROOT=. -ffile-prefix-map=$BUILD_ROOT=. -ffile-prefix-map=$ANDROID_NDK_HOME=ndk"
C_FLAGS="-g0 $PREFIX_MAP"

for ABI in $ABIS; do
  info "Building $LIB_NAME for $ABI"
  ABI_BUILD_DIR="$BUILD_ROOT/$ABI"
  LOG="$BUILD_ROOT/build_$ABI.log"
  mkdir -p "$ABI_BUILD_DIR"
  run() {
    if [ "$VERBOSE" = "1" ]; then "$@"; else "$@" >>"$LOG" 2>&1 || { tail -n 40 "$LOG" >&2; echo "ERROR: build failed for $ABI, see $LOG" >&2; exit 1; }; fi
  }
  run "$CMAKE_BIN" -S "$SRC_ROOT/bridge" -B "$ABI_BUILD_DIR" "${GENERATOR_ARGS[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-29 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="$C_FLAGS" \
    -DCMAKE_CXX_FLAGS="$C_FLAGS" \
    -DNCNN_SOURCE_DIR="$SRC_ROOT/ncnn"
  run "$CMAKE_BIN" --build "$ABI_BUILD_DIR" --target makeacopy_ncnn --parallel "$JOBS"

  mkdir -p "$JNI_LIBS_BASE/$ABI"
  "$STRIP_BIN" --strip-all \
    --remove-section=.comment \
    --remove-section=.note.gnu.build-id \
    -o "$JNI_LIBS_BASE/$ABI/$LIB_NAME" "$ABI_BUILD_DIR/$LIB_NAME"
done

for ABI in $ABIS; do
  f="$JNI_LIBS_BASE/$ABI/$LIB_NAME"
  if command -v sha256sum >/dev/null 2>&1; then sum="$(sha256sum "$f" | cut -d' ' -f1)"; else sum="$(shasum -a 256 "$f" | cut -d' ' -f1)"; fi
  info "$ABI: $(wc -c <"$f" | tr -d ' ') bytes  sha256=$sum"
done
