#!/usr/bin/env bash
set -euo pipefail

# Converts the ONNX exports of the models into the NCNN files the app ships and refreshes the
# SHA256SUMS files next to them.
#
#   scripts/convert_models_to_ncnn.sh [PADDLE_ONNX_DIR] [DOCQUAD_ONNX] [V6_DIR]
#
#   PADDLE_ONNX_DIR  directory with det.onnx and <key>_PP-OCRv5_mobile_rec.onnx
#                    (output of scripts/convert_paddleocr_v5_all.sh; default paddle-spike/onnx)
#   DOCQUAD_ONNX     ONNX export of the corner model (training/docquad_m3/export_onnx.py);
#                    left out (or ""), the corner model in the assets is not touched
#   V6_DIR           download of the Hugging Face release PaddlePaddle/PP-OCRv6_small_rec_onnx
#                    (revision 2f0724790c8b57946c89cc45d2fa79e405781f51) with inference.onnx and
#                    inference.yml; left out, PP-OCRv6 small in the assets is not touched
#
# Weights stay in single precision (fp16=0): half precision changes recognised text. The
# converter version is pinned because another version may write the same network differently.
# The OCR models take any image size, so the converter traces them with two sizes.

PNNX_VERSION="20261020"

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PADDLE_ONNX_DIR="${1:-$REPO_DIR/paddle-spike/onnx}"
DOCQUAD_ONNX="${2:-}"
V6_DIR="${3:-}"
V6_NAME="PP-OCRv6_small_rec"
V6_ONNX_SHA256="5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634"
PADDLE_ASSETS="$REPO_DIR/app/src/main/assets/paddleocr/v5"
DOCQUAD_ASSETS="$REPO_DIR/app/src/main/assets/docquad"
DOCQUAD_NAME="docquadnet256_trained_opset17"
REC_MODELS="PP-OCRv5_mobile_rec latin_PP-OCRv5_mobile_rec eslav_PP-OCRv5_mobile_rec cyrillic_PP-OCRv5_mobile_rec th_PP-OCRv5_mobile_rec arabic_PP-OCRv5_mobile_rec devanagari_PP-OCRv5_mobile_rec el_PP-OCRv5_mobile_rec"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install --quiet "pnnx==$PNNX_VERSION"
PNNX="$WORK/venv/bin/pnnx"

# convert <name> <onnx file> <target dir> <shape> [<second shape>]
convert() {
  local name="$1" onnx="$2" target="$3" shape="$4" shape2="${5:-}"
  [ -f "$onnx" ] || { echo "ERROR: missing $onnx" >&2; exit 1; }
  local dir="$WORK/$name"
  mkdir -p "$dir"
  cp "$onnx" "$dir/model.onnx"
  if [ -n "$shape2" ]; then
    (cd "$dir" && "$PNNX" model.onnx "inputshape=$shape" "inputshape2=$shape2" fp16=0 >convert.log 2>&1) || { tail -n 20 "$dir/convert.log" >&2; exit 1; }
  else
    (cd "$dir" && "$PNNX" model.onnx "inputshape=$shape" fp16=0 >convert.log 2>&1) || { tail -n 20 "$dir/convert.log" >&2; exit 1; }
  fi
  cp "$dir/model.ncnn.param" "$target/$name.ncnn.param"
  cp "$dir/model.ncnn.bin" "$target/$name.ncnn.bin"
  echo "converted $name"
}

sums() {
  (cd "$1" && shift && { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; } | LC_ALL=C sort -k2 >SHA256SUMS)
}

convert det "$PADDLE_ONNX_DIR/det.onnx" "$PADDLE_ASSETS" "[1,3,960,960]" "[1,3,640,1280]"
for name in $REC_MODELS; do
  convert "$name" "$PADDLE_ONNX_DIR/$name.onnx" "$PADDLE_ASSETS" "[1,3,48,320]" "[1,3,48,960]"
  cp "$PADDLE_ONNX_DIR/${name}_dict.txt" "$PADDLE_ASSETS/${name}_dict.txt"
done
if [ -n "$V6_DIR" ]; then
  v6_sum="$( { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$V6_DIR/inference.onnx"; else shasum -a 256 "$V6_DIR/inference.onnx"; fi; } | cut -d' ' -f1)"
  [ "$v6_sum" = "$V6_ONNX_SHA256" ] || { echo "ERROR: $V6_DIR/inference.onnx has sha256 $v6_sum, expected $V6_ONNX_SHA256" >&2; exit 1; }
  convert "$V6_NAME" "$V6_DIR/inference.onnx" "$PADDLE_ASSETS" "[1,3,48,320]" "[1,3,48,960]"
  # The dictionary is inference.yml's character list with the space PaddleOCR appends.
  "$WORK/venv/bin/pip" install --quiet pyyaml
  "$WORK/venv/bin/python" - "$V6_DIR/inference.yml" "$PADDLE_ASSETS/${V6_NAME}_dict.txt" <<'PY'
import sys, yaml
chars = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))["PostProcess"]["character_dict"]
open(sys.argv[2], "w", encoding="utf-8").write("\n".join(chars) + "\n \n")
PY
fi
sums "$PADDLE_ASSETS" *_dict.txt *.ncnn.param *.ncnn.bin

if [ -n "$DOCQUAD_ONNX" ]; then
  convert "$DOCQUAD_NAME" "$DOCQUAD_ONNX" "$DOCQUAD_ASSETS" "[1,3,256,256]"
  sums "$DOCQUAD_ASSETS" *.ncnn.param *.ncnn.bin
fi

echo "Done. Layers used by the models must be listed in app/src/main/cpp/ncnn_config.cmake:"
cat "$PADDLE_ASSETS"/*.ncnn.param "$DOCQUAD_ASSETS"/*.ncnn.param | awk 'NF>3 && $1 !~ /^[0-9]/ {print $1}' | sort -u | tr '\n' ' '
echo
