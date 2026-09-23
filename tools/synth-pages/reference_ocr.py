"""Runs the reference PaddleOCR pipeline (via the paddle-worker engine) over the synthetic
pages and writes one JSON per page: {"words": [{"text", "confidence", "polygon"}]}.

Usage (from the makeacopy monorepo, with its venv):
  cd paddle-worker && WORKER_DET_MODEL=PP-OCRv5_mobile_det WORKER_REC_MODEL=latin_PP-OCRv5_mobile_rec \
     ../venv/bin/python <this file> <png...> --out <dir>
"""
import argparse, json, os, sys, time
sys.path.insert(0, os.getcwd())
from app import engine  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("images", nargs="+")
ap.add_argument("--out", required=True)
a = ap.parse_args()
os.makedirs(a.out, exist_ok=True)
for img in a.images:
    t0 = time.time()
    res = engine.run_ocr(img)
    name = os.path.splitext(os.path.basename(img))[0]
    with open(os.path.join(a.out, name + ".reference.json"), "w", encoding="utf-8") as fh:
        json.dump({"source": os.path.basename(img), "language": res["language"], "words": res["words"],
                   "model": res.get("model", {}), }, fh, ensure_ascii=False, indent=1)
    print(name, "lines", len(res["words"]), f"{time.time()-t0:.1f}s")
