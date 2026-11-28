#!/usr/bin/env bash
# =============================================================================
# convert_paddleocr_v5.sh
#
# Reproduzierbare Konvertierung der upstream PaddleOCR V5 mobile Modelle
# (Detection + English Recognition) nach ONNX für die experimentelle
# PaddleOCR-Integration in MakeACopy.
#
# Schritte:
#   1) PaddleX-Inferenzmodelle via `paddlex.create_model` herunterladen
#      (Cache: ~/.paddlex/official_models/).
#   2) `paddle2onnx` -> det.onnx, rec.onnx (opset 11, ONNX-Checker an).
#   3) rec_dict.txt aus inference.yml extrahieren (PaddleOCR CTC: blank=0,
#      Klasse i>0 -> rec_dict[i-1]; Leerzeichen wird implizit ergänzt,
#      sodass len(dict)+1 == V == 438).
#   4) SHA256 protokollieren und gegen erwartete Werte pruefen (optional).
#
# Voraussetzungen:
#   - Python venv unter ./venv (Repo-Root) mit installierten Paketen:
#       paddleocr paddlepaddle paddle2onnx onnx onnxruntime pyyaml
#   - macOS oder Linux Shell.
#
# Verwendung:
#   ./scripts/convert_paddleocr_v5.sh
#   VERIFY_SHA=1 ./scripts/convert_paddleocr_v5.sh   # gegen bekannte Hashes prüfen
#
# Output:
#   paddle-spike/onnx/det.onnx
#   paddle-spike/onnx/rec.onnx
#   paddle-spike/onnx/rec_dict.txt
#   paddle-spike/onnx/SHA256SUMS
# =============================================================================
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VENV_DIR="${VENV_DIR:-$REPO_DIR/venv}"
OUT_DIR="${OUT_DIR:-$REPO_DIR/paddle-spike/onnx}"
PADDLEX_CACHE="${PADDLEX_CACHE:-$HOME/.paddlex/official_models}"

DET_NAME="PP-OCRv5_mobile_det"
REC_NAME="latin_PP-OCRv5_mobile_rec"

OPSET="${OPSET:-11}"

# Bekannte Spike-Hashes (Stand 2026-05-08); informativ, nicht zwingend bitidentisch
# bei abweichenden upstream-Versionen.
EXPECTED_DET_SHA="a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d"
EXPECTED_REC_SHA="a724cb0ccf07fbe37c733fd38d07e0423adcbac035d40428f4858137025c9838"
EXPECTED_DICT_SHA="7edf5b42aa0e974c8cb8dd31799230932303479e6914b641d3e21f6225d40506"

log() { printf '[paddleocr-v5] %s\n' "$*"; }
die() { printf '[paddleocr-v5][ERROR] %s\n' "$*" >&2; exit 1; }

# -----------------------------------------------------------------------------
# venv aktivieren
# -----------------------------------------------------------------------------
[ -f "$VENV_DIR/bin/activate" ] || die "venv nicht gefunden unter $VENV_DIR — bitte 'python3 -m venv venv && source venv/bin/activate && pip install paddleocr paddlepaddle paddle2onnx onnx onnxruntime pyyaml' ausführen."
# shellcheck disable=SC1091
source "$VENV_DIR/bin/activate"

command -v paddle2onnx >/dev/null 2>&1 || die "paddle2onnx nicht im venv — 'pip install paddle2onnx' im aktiven venv ausführen."
python -c "import paddlex" 2>/dev/null || die "paddlex nicht im venv — 'pip install paddleocr paddlepaddle' im aktiven venv ausführen."
python -c "import yaml"   2>/dev/null || die "pyyaml nicht im venv — 'pip install pyyaml' im aktiven venv ausführen."

mkdir -p "$OUT_DIR"

# -----------------------------------------------------------------------------
# 1) PaddleX-Modelle herunterladen (idempotent: Cache wiederverwenden)
# -----------------------------------------------------------------------------
log "Lade PaddleX-Modelle (Cache: $PADDLEX_CACHE)"
python - <<PY
from paddlex import create_model
for name in ("$DET_NAME", "$REC_NAME"):
    print(f"create_model({name!r})")
    create_model(name)
PY

DET_DIR="$PADDLEX_CACHE/$DET_NAME"
REC_DIR="$PADDLEX_CACHE/$REC_NAME"
[ -d "$DET_DIR" ] || die "PaddleX-Verzeichnis fehlt: $DET_DIR"
[ -d "$REC_DIR" ] || die "PaddleX-Verzeichnis fehlt: $REC_DIR"

# -----------------------------------------------------------------------------
# 2) paddle2onnx-Konvertierung
# -----------------------------------------------------------------------------
convert_one() {
    local model_dir="$1" save_file="$2"
    log "paddle2onnx -> $save_file"
    paddle2onnx \
        --model_dir "$model_dir" \
        --model_filename inference.json \
        --params_filename inference.pdiparams \
        --save_file "$save_file" \
        --opset_version "$OPSET" \
        --enable_onnx_checker True
}

convert_one "$DET_DIR" "$OUT_DIR/det.onnx"
convert_one "$REC_DIR" "$OUT_DIR/rec.onnx"

# -----------------------------------------------------------------------------
# 3) rec_dict.txt extrahieren
# -----------------------------------------------------------------------------
log "Extrahiere rec_dict.txt aus $REC_DIR/inference.yml"
python - <<PY
from pathlib import Path
import yaml

src = Path("$REC_DIR/inference.yml")
out = Path("$OUT_DIR/rec_dict.txt")

data = yaml.safe_load(src.read_text(encoding="utf-8"))
chars = list(data["PostProcess"]["character_dict"])

# PaddleOCR CTCLabelDecode: Space ist für dieses Modell implizit.
if " " not in chars and len(chars) + 2 == 438:
    chars.append(" ")

if len(chars) + 1 != 438:
    raise SystemExit(f"Mismatch: chars={len(chars)}, expected vocab=438")

out.parent.mkdir(parents=True, exist_ok=True)
out.write_text("\n".join(chars) + "\n", encoding="utf-8")

print(f"written: {out}")
print(f"chars including space: {len(chars)}")
print("blank index: 0  |  class i>0 maps to rec_dict[i-1]")
PY

# -----------------------------------------------------------------------------
# 4) SHA256 protokollieren / verifizieren
# -----------------------------------------------------------------------------
if command -v shasum >/dev/null 2>&1; then
    SHA_CMD="shasum -a 256"
else
    SHA_CMD="sha256sum"
fi

( cd "$OUT_DIR" && $SHA_CMD det.onnx rec.onnx rec_dict.txt | tee SHA256SUMS )

if [ "${VERIFY_SHA:-0}" = "1" ]; then
    log "Verifiziere SHA256 gegen Spike-Referenz"
    actual_det=$($SHA_CMD "$OUT_DIR/det.onnx"      | awk '{print $1}')
    actual_rec=$($SHA_CMD "$OUT_DIR/rec.onnx"      | awk '{print $1}')
    actual_dic=$($SHA_CMD "$OUT_DIR/rec_dict.txt"  | awk '{print $1}')
    [ "$actual_det" = "$EXPECTED_DET_SHA"  ] || die "det.onnx SHA mismatch: $actual_det"
    [ "$actual_rec" = "$EXPECTED_REC_SHA"  ] || die "rec.onnx SHA mismatch: $actual_rec"
    [ "$actual_dic" = "$EXPECTED_DICT_SHA" ] || die "rec_dict.txt SHA mismatch: $actual_dic"
    log "SHA256-Verifikation erfolgreich."
fi

log "Fertig. Artefakte: $OUT_DIR"
