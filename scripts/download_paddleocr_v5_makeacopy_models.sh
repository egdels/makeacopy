#!/usr/bin/env bash
# download_paddleocr_v5_makeacopy_models.sh
# Downloads PP-OCRv5 mobile detection + all relevant mobile recognition models
# for MakeACopy's currently supported OCR languages.
#
# Output:
#   paddle-spike/models/
#   paddle-spike/models/manifest.json
#   paddle-spike/models/SHA256SUMS

set -euo pipefail

BASE_DIR="${1:-paddle-spike/models}"
VENV_DIR="${2:-venv}"

MODELS=(
  # Detection
  "PP-OCRv5_mobile_det"

  # Layout
  "PP-DocLayout-S"
  "PP-DocLayout-M"
  "PP-DocLayout-L"

  # Recognition
  "latin_PP-OCRv5_mobile_rec"        # eng/deu/fra/spa/ita/por/nld/pol/ces/slk/hun/ron/dan/nor/swe/tur
  "eslav_PP-OCRv5_mobile_rec"        # rus candidate 1
  "cyrillic_PP-OCRv5_mobile_rec"     # rus candidate 2 / broader Cyrillic
  "th_PP-OCRv5_mobile_rec"           # tha
  "arabic_PP-OCRv5_mobile_rec"       # ara/fas
  "devanagari_PP-OCRv5_mobile_rec"   # hin
  "PP-OCRv5_mobile_rec"              # chi_sim/chi_tra, generic multilingual
  "korean_PP-OCRv5_mobile_rec"       # korean
  "el_PP-OCRv5_mobile_rec"           # greek
)

echo "Output dir: $BASE_DIR"
echo "Venv dir:   $VENV_DIR"
echo ""

mkdir -p "$BASE_DIR"

if [ ! -d "$VENV_DIR" ]; then
  python3 -m venv "$VENV_DIR"
fi

# shellcheck disable=SC1090
source "$VENV_DIR/bin/activate"

python -m pip install --upgrade pip
python -m pip install \
  paddleocr \
  paddlepaddle \
  paddle2onnx \
  onnx \
  onnxruntime \
  pyyaml

TMP_PY="$(mktemp)"

cat > "$TMP_PY" <<'PY'
from pathlib import Path
from paddlex import create_model
import hashlib
import json
import shutil
import sys
import os

models = [
    "PP-OCRv5_mobile_det",
    "PP-DocLayout-S",
    "PP-DocLayout-M",
    "PP-DocLayout-L",
    "latin_PP-OCRv5_mobile_rec",
    "eslav_PP-OCRv5_mobile_rec",
    "cyrillic_PP-OCRv5_mobile_rec",
    "th_PP-OCRv5_mobile_rec",
    "arabic_PP-OCRv5_mobile_rec",
    "devanagari_PP-OCRv5_mobile_rec",
    "PP-OCRv5_mobile_rec",
    "korean_PP-OCRv5_mobile_rec",
    "el_PP-OCRv5_mobile_rec",
]

out = Path(os.environ["BASE_DIR"]).expanduser().resolve()
out.mkdir(parents=True, exist_ok=True)

manifest = []

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

for name in models:
    print(f"\n=== Downloading {name} ===", flush=True)
    create_model(name)

    src = Path.home() / ".paddlex" / "official_models" / name
    if not src.exists():
        print(f"ERROR: expected model dir not found: {src}", file=sys.stderr)
        sys.exit(1)

    dst = out / name
    if dst.exists():
        shutil.rmtree(dst)

    shutil.copytree(src, dst)

    files = []
    total_size = 0

    for p in sorted(dst.rglob("*")):
        if not p.is_file():
            continue

        size = p.stat().st_size
        total_size += size

        files.append({
            "path": str(p.relative_to(out)),
            "size": size,
            "sha256": sha256(p),
        })

    manifest.append({
        "model": name,
        "paddlex_cache": str(src),
        "copied_to": str(dst),
        "total_size": total_size,
        "files": files,
    })

manifest_path = out / "manifest.json"
manifest_path.write_text(
    json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
    encoding="utf-8",
)

sha_path = out / "SHA256SUMS"
with sha_path.open("w", encoding="utf-8") as f:
    for item in manifest:
        for file in item["files"]:
            f.write(f'{file["sha256"]}  {file["path"]}\n')

summary_path = out / "SUMMARY.txt"
with summary_path.open("w", encoding="utf-8") as f:
    for item in manifest:
        mib = item["total_size"] / 1024 / 1024
        f.write(f'{item["model"]}: {item["total_size"]} bytes ({mib:.2f} MiB)\n')

print("\nDone.")
print(f"Manifest: {manifest_path}")
print(f"SHA256:   {sha_path}")
print(f"Summary:  {summary_path}")
PY

BASE_DIR="$BASE_DIR" python "$TMP_PY"
rm -f "$TMP_PY"

echo ""
echo "Downloaded model directories:"
find "$BASE_DIR" -maxdepth 1 -type d -not -path "$BASE_DIR" -print | sort

echo ""
echo "Model size summary:"
cat "$BASE_DIR/SUMMARY.txt"

echo ""
echo "Total size:"
du -sh "$BASE_DIR"