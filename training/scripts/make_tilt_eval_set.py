#!/usr/bin/env python3
"""Create a tilted-document evaluation set by rotating labelled photos.

Rotating the whole photo about its centre is what happens when the camera is held at an angle, so
the result is a realistic test for strongly tilted documents without needing new labels. The
canvas size is kept and the uncovered corners are filled by reflection, which avoids the artificial
straight high-contrast border that a constant fill would add (a detector can mistake that border
for a document edge). Samples whose document would leave the frame are retried with the opposite
sign / a smaller angle and skipped if nothing fits.

Input and output use the DocQuad layout (see training/EVALUATION.md):
  <dir>/images/*, <dir>/labels/*.json with "corners_px" in stored pixel coordinates.

Usage:
  python3 training/scripts/make_tilt_eval_set.py \
    --in_dir training/data/my_data_converted \
    --out_dir training/data/own_tilt_eval \
    --per_image 2
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
from pathlib import Path

import cv2
import numpy as np


def rotate_point(m: np.ndarray, x: float, y: float) -> tuple[float, float]:
    return (float(m[0, 0] * x + m[0, 1] * y + m[0, 2]), float(m[1, 0] * x + m[1, 1] * y + m[1, 2]))


def inside(pts: list[tuple[float, float]], w: int, h: int, margin: float) -> bool:
    mx, my = margin * w, margin * h
    return all(mx <= x <= w - 1 - mx and my <= y <= h - 1 - my for x, y in pts)


def candidate_angles(rng: random.Random, lo: float, hi: float, sign: float) -> list[float]:
    """Preferred angle first, then the opposite sign, then progressively smaller magnitudes."""
    mag = rng.uniform(lo, hi)
    out = [sign * mag, -sign * mag]
    step = mag
    while step - 5.0 >= lo:
        step -= 5.0
        out += [sign * step, -sign * step]
    return out


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--in_dir", required=True, action="append", help="source set (repeatable)")
    p.add_argument("--out_dir", required=True)
    p.add_argument("--per_image", type=int, default=2, help="rotated variants per source image (angle bands)")
    p.add_argument("--min_angle", type=float, default=20.0)
    p.add_argument("--max_angle", type=float, default=45.0)
    p.add_argument("--max_edge", type=int, default=2048, help="downscale sources to this edge length")
    p.add_argument("--margin", type=float, default=0.01, help="required distance of corners to the frame")
    p.add_argument("--seed", type=int, default=0)
    args = p.parse_args()

    out = Path(args.out_dir)
    if out.exists() and any(out.iterdir()):
        raise SystemExit(f"output directory is not empty: {out}")
    (out / "images").mkdir(parents=True, exist_ok=True)
    (out / "labels").mkdir(parents=True, exist_ok=True)

    band = (args.max_angle - args.min_angle) / args.per_image
    written = skipped = 0
    for in_dir in map(Path, args.in_dir):
        for label_path in sorted((in_dir / "labels").glob("*.json")):
            label = json.loads(label_path.read_text())
            # IMREAD_IGNORE_ORIENTATION: corners_px are stored-pixel coordinates.
            img = cv2.imread(str(in_dir / "images" / label["image"]), cv2.IMREAD_COLOR | cv2.IMREAD_IGNORE_ORIENTATION)
            if img is None:
                skipped += 1
                continue
            h, w = img.shape[:2]
            scale = min(1.0, args.max_edge / max(w, h))
            if scale < 1.0:
                img = cv2.resize(img, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
                h, w = img.shape[:2]
            corners = [(x * scale, y * scale) for x, y in label["corners_px"]]

            base = hashlib.sha256(f"{args.seed}:{label['image']}".encode()).digest()[0]
            for b in range(args.per_image):
                lo = args.min_angle + b * band
                seed = hashlib.sha256(f"{args.seed}:{label['image']}:{b}".encode()).hexdigest()
                rng = random.Random(seed)
                # Alternate the preferred direction so variants of one photo differ clearly.
                sign = 1.0 if (base + b) % 2 == 0 else -1.0
                for angle in candidate_angles(rng, lo, lo + band, sign):
                    if abs(angle) < args.min_angle:
                        continue
                    m = cv2.getRotationMatrix2D((w / 2.0, h / 2.0), angle, 1.0)
                    pts = [rotate_point(m, x, y) for x, y in corners]
                    if not inside(pts, w, h, args.margin):
                        continue
                    rotated = cv2.warpAffine(img, m, (w, h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT_101)
                    stem = f"{Path(label['image']).stem}_tilt{b}"
                    cv2.imwrite(str(out / "images" / f"{stem}.jpg"), rotated, [cv2.IMWRITE_JPEG_QUALITY, 92])
                    (out / "labels" / f"{stem}.json").write_text(
                        json.dumps(
                            {
                                "image": f"{stem}.jpg",
                                "width": w,
                                "height": h,
                                "corners_px": [[x, y] for x, y in pts],
                                "source_set": in_dir.name,
                                "source_image": label["image"],
                                # Positive = counter-clockwise on screen (OpenCV convention).
                                "tilt_deg": round(angle, 2),
                            },
                            indent=2,
                        )
                        + "\n"
                    )
                    written += 1
                    break
                else:
                    skipped += 1

    print(f"[tilt-eval] written={written} skipped(no fitting angle or unreadable)={skipped} -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
