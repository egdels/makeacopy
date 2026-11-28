#!/usr/bin/env bash
# =============================================================================
# convert_paddleocr_v5_all.sh
#
# Reproduzierbare Konvertierung der upstream PaddleOCR V5 mobile Modelle
# nach ONNX für die experimentelle PaddleOCR-Integration in MakeACopy.
#
# Output:
#   paddle-spike/onnx/det.onnx
#   paddle-spike/onnx/<rec_model>.onnx
#   paddle-spike/onnx/<rec_model>_dict.txt
#   paddle-spike/onnx/SHA256SUMS
#   paddle-spike/onnx/SUMMARY.txt
# =============================================================================

set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VENV_DIR="${VENV_DIR:-$REPO_DIR/venv}"
OUT_DIR="${OUT_DIR:-$REPO_DIR/paddle-spike/onnx}"
PADDLEX_CACHE="${PADDLEX_CACHE:-$HOME/.paddlex/official_models}"
OPSET="${OPSET:-11}"

DET_NAME="PP-OCRv5_mobile_det"
LAYOUT_NAME="PP-DocLayout-S"

REC_MODELS=(
  "latin_PP-OCRv5_mobile_rec"
  "eslav_PP-OCRv5_mobile_rec"
  "cyrillic_PP-OCRv5_mobile_rec"
  "th_PP-OCRv5_mobile_rec"
  "arabic_PP-OCRv5_mobile_rec"
  "devanagari_PP-OCRv5_mobile_rec"
  "PP-OCRv5_mobile_rec"
  "korean_PP-OCRv5_mobile_rec"
  "el_PP-OCRv5_mobile_rec"
)

log() { printf '[paddleocr-v5] %s\n' "$*"; }
die() { printf '[paddleocr-v5][ERROR] %s\n' "$*" >&2; exit 1; }

[ -f "$VENV_DIR/bin/activate" ] || die "venv nicht gefunden unter $VENV_DIR"
# shellcheck disable=SC1091
source "$VENV_DIR/bin/activate"

command -v paddle2onnx >/dev/null 2>&1 || die "paddle2onnx nicht im venv."
python -c "import paddlex" 2>/dev/null || die "paddlex nicht im venv."
python -c "import yaml" 2>/dev/null || die "pyyaml nicht im venv."
python -c "import onnx" 2>/dev/null || die "onnx nicht im venv."

mkdir -p "$OUT_DIR"

log "Lade PaddleX-Modelle (Cache: $PADDLEX_CACHE)"

python - <<PY
from paddlex import create_model

models = [
    "$DET_NAME",
    "$LAYOUT_NAME",
$(printf '    "%s",\n' "${REC_MODELS[@]}")
]

for name in models:
    print(f"create_model({name!r})")
    create_model(name)
PY

convert_one() {
    local model_dir="$1"
    local save_file="$2"

    [ -d "$model_dir" ] || die "PaddleX-Verzeichnis fehlt: $model_dir"

    log "paddle2onnx: $model_dir -> $save_file"

    paddle2onnx \
        --model_dir "$model_dir" \
        --model_filename inference.json \
        --params_filename inference.pdiparams \
        --save_file "$save_file" \
        --opset_version "$OPSET" \
        --enable_onnx_checker True
}

extract_dict() {
    local model_name="$1"
    local model_dir="$2"
    local onnx_file="$3"
    local dict_file="$4"

    [ -f "$model_dir/inference.yml" ] || die "inference.yml fehlt: $model_dir/inference.yml"

    log "Extrahiere Dict: $model_name -> $dict_file"

    MODEL_NAME="$model_name" \
    MODEL_DIR="$model_dir" \
    ONNX_FILE="$onnx_file" \
    DICT_FILE="$dict_file" \
    python - <<'PY'
from pathlib import Path
import os
import yaml
import onnx

model_name = os.environ["MODEL_NAME"]
model_dir = Path(os.environ["MODEL_DIR"])
onnx_file = Path(os.environ["ONNX_FILE"])
dict_file = Path(os.environ["DICT_FILE"])

data = yaml.safe_load((model_dir / "inference.yml").read_text(encoding="utf-8"))
chars = list(data["PostProcess"]["character_dict"])

# Wichtig:
# Nicht trimmen. Ein reines Space-Token muss erhalten bleiben.
# PaddleOCR CTCLabelDecode ergänzt bei vielen Modellen ein Leerzeichen implizit.
if " " not in chars:
    chars.append(" ")

dict_file.parent.mkdir(parents=True, exist_ok=True)
dict_file.write_text("\n".join(chars) + "\n", encoding="utf-8")

m = onnx.load(str(onnx_file))
out_shape = []
for d in m.graph.output[0].type.tensor_type.shape.dim:
    if d.dim_value:
        out_shape.append(d.dim_value)
    elif d.dim_param:
        out_shape.append(d.dim_param)
    else:
        out_shape.append("?")

vocab_from_onnx = None
last = out_shape[-1] if out_shape else None
if isinstance(last, int):
    vocab_from_onnx = last

expected_vocab = len(chars) + 1

print(f"model: {model_name}")
print(f"dict entries including space: {len(chars)}")
print(f"expected CTC vocab blank=0: {expected_vocab}")
print(f"onnx output shape: {out_shape}")

if vocab_from_onnx is not None and vocab_from_onnx != expected_vocab:
    raise SystemExit(
        f"Vocab mismatch for {model_name}: "
        f"onnx last dim={vocab_from_onnx}, dict+blank={expected_vocab}"
    )

print("blank index: 0 | class i>0 maps to dict[i-1]")
PY
}

# -----------------------------------------------------------------------------
# Detection
# -----------------------------------------------------------------------------
DET_DIR="$PADDLEX_CACHE/$DET_NAME"
convert_one "$DET_DIR" "$OUT_DIR/det.onnx"

# -----------------------------------------------------------------------------
# Layout
# -----------------------------------------------------------------------------
LAYOUT_DIR="$PADDLEX_CACHE/$LAYOUT_NAME"
convert_one "$LAYOUT_DIR" "$OUT_DIR/layout.onnx"

# -----------------------------------------------------------------------------
# Recognition models
# -----------------------------------------------------------------------------
for rec_name in "${REC_MODELS[@]}"; do
    rec_dir="$PADDLEX_CACHE/$rec_name"
    onnx_out="$OUT_DIR/${rec_name}.onnx"
    dict_out="$OUT_DIR/${rec_name}_dict.txt"

    convert_one "$rec_dir" "$onnx_out"
    extract_dict "$rec_name" "$rec_dir" "$onnx_out" "$dict_out"
done

# -----------------------------------------------------------------------------
# SHA256 + Summary
# -----------------------------------------------------------------------------
if command -v shasum >/dev/null 2>&1; then
    SHA_CMD="shasum -a 256"
else
    SHA_CMD="sha256sum"
fi

log "Schreibe SHA256SUMS"
(
  cd "$OUT_DIR"
  find . -maxdepth 1 -type f \( -name "*.onnx" -o -name "*_dict.txt" \) \
    | sed 's#^\./##' \
    | sort \
    | xargs $SHA_CMD \
    | tee SHA256SUMS
)

log "Schreibe SUMMARY.txt"
{
  printf "OPSET=%s\n" "$OPSET"
  printf "OUT_DIR=%s\n" "$OUT_DIR"
  printf "\n"
  printf "%-45s %12s\n" "file" "size"
  printf "%-45s %12s\n" "----" "----"

  find "$OUT_DIR" -maxdepth 1 -type f \( -name "*.onnx" -o -name "*_dict.txt" \) \
    | sort \
    | while read -r f; do
        size=$(wc -c < "$f" | tr -d ' ')
        printf "%-45s %12s\n" "$(basename "$f")" "$size"
      done
} | tee "$OUT_DIR/SUMMARY.txt"

log "Fertig. Artefakte: $OUT_DIR"