#!/usr/bin/env bash
set -euo pipefail

SRC="paddle-spike/onnx"
DST="app/src/main/assets/paddleocr/v5"

mkdir -p "$DST"

# Ziel bereinigen
find "$DST" -type f \
  \( -name "*.ort" \
  -o -name "*.onnx" \
  -o -name "*_dict.txt" \
  -o -name "*.config" \
  -o -name "SHA256SUMS" \
  -o -name "SUMMARY.txt" \) \
  -delete

# ORT-Modelle
find "$SRC" -maxdepth 1 -type f -name "*.ort" -exec cp {} "$DST"/ \;

# Dictionaries
find "$SRC" -maxdepth 1 -type f -name "*_dict.txt" -exec cp {} "$DST"/ \;

# Config + hashes
cp "$SRC/required_operators.config" \
   "$DST/paddleocr_v5.required_operators.config"

cp "$SRC/SHA256SUMS" "$DST/"
cp "$SRC/SUMMARY.txt" "$DST/"

echo ""
echo "Copied assets:"
find "$DST" -maxdepth 1 -type f | sort

echo ""
echo "Total size:"
du -sh "$DST"