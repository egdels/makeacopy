#!/usr/bin/env python3
"""Convert PP-OCRv5 ONNX models to ORT format with BASIC optimization.

Background: the project's reduced-size ONNX Runtime Mobile AAR
(`scripts/build_onnxruntime_android.sh`) accepts only the .ort format and
ships with a curated operator set. Converting models with the default
optimization level "all" introduces fused operators (e.g.
`LayerNormalization(1)`, `com.microsoft.SkipLayerNormalization`,
`FusedConv`) that may not be present in the AAR, leading to
`ORT_NOT_IMPLEMENTED` at runtime.

Using BASIC optimization avoids extended/layout fusions while still
producing a valid .ort artifact. The required_operators.config for the
AAR build is regenerated alongside.
"""
from __future__ import annotations
import argparse
import os
import sys
import onnxruntime as ort
from onnxruntime.tools import convert_onnx_models_to_ort as conv

def optimize_basic(src_dir: str) -> None:
    for name in ("det", "rec"):
        onnx_in = os.path.join(src_dir, f"{name}.onnx")
        if not os.path.exists(onnx_in):
            print(f"missing: {onnx_in}", file=sys.stderr)
            sys.exit(1)
        opt_out = os.path.join(src_dir, f"{name}.basic.onnx")
        so = ort.SessionOptions()
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
        so.optimized_model_filepath = opt_out
        ort.InferenceSession(onnx_in, sess_options=so, providers=["CPUExecutionProvider"])
        print(f"basic-optimized -> {opt_out}")

def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("dir", default="app/src/main/assets/paddleocr/v5", nargs="?")
    args = p.parse_args()
    optimize_basic(args.dir)
    # Now convert the *basic*-optimized ONNX files to ORT (also runs internal opts; but
    # extended fusions like LayerNormalization aren't reintroduced from a basic-opt graph).
    # We feed only the .basic.onnx via a staged dir to avoid touching originals.
    import tempfile, shutil
    with tempfile.TemporaryDirectory() as stage:
        for name in ("det", "rec"):
            shutil.copy(os.path.join(args.dir, f"{name}.basic.onnx"),
                        os.path.join(stage, f"{name}.onnx"))
        rc = conv.convert_onnx_models_to_ort_format(
            stage, optimization_styles=[conv.OptimizationStyle.Fixed]
        ) if hasattr(conv, "convert_onnx_models_to_ort_format") else None
        # Fallback: invoke via CLI to remain compatible across versions.
        if rc is None:
            import subprocess
            subprocess.check_call([sys.executable, "-m",
                "onnxruntime.tools.convert_onnx_models_to_ort",
                stage, "--optimization_style", "Fixed"])
        # Move .ort + config back
        for fn in os.listdir(stage):
            if fn.endswith(".ort") or fn == "required_operators.config":
                dest = os.path.join(args.dir, fn.replace(".basic", ""))
                shutil.copy(os.path.join(stage, fn), dest)
                print(f"copied {fn} -> {dest}")
    # Cleanup intermediate basic.onnx
    for name in ("det", "rec"):
        p = os.path.join(args.dir, f"{name}.basic.onnx")
        if os.path.exists(p):
            os.remove(p)
    return 0

if __name__ == "__main__":
    sys.exit(main())
