#!/usr/bin/env python3
# Copyright 2026 Christian Kierdorf
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
"""Real phone photos for SampleSetEvalTest (-e samplesDir, ported from Sheet): the receipts of a CORD split
(github.com/clovaai/cord, CC BY 4.0), each cut out along its labelled
outline (roi; the whole photo where it has none) as the crop screen would,
with its text as ground truth: the
words of a row (row_id) left to right, rows top to bottom.

    scripts/cord_samples.py <CORD>/test <out dir>

Needs pillow. CORD stays on the developer's disk, not in the repository:

    adb push <out dir> /sdcard/Android/data/de.schliweb.makeacopy/files/cord
    adb shell am instrument -w -e samplesDir /sdcard/Android/data/de.schliweb.makeacopy/files/cord \
      -e class de.schliweb.makeacopy.utils.ocr.SampleSetEvalTest \
      de.schliweb.makeacopy.test/androidx.test.runner.AndroidJUnitRunner
"""

import json
import math
import sys
from pathlib import Path

from PIL import Image


def main() -> None:
    split, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for js in sorted((split / "json").glob("*.json")):
        photo = split / "image" / f"{js.stem}.png"
        if not photo.is_file():
            continue
        label = json.loads(js.read_text(encoding="utf-8"))
        img = Image.open(photo).convert("RGB")
        meta = label["meta"]["image_size"]
        sx, sy = img.width / meta["width"], img.height / meta["height"]
        roi = label["roi"]
        if roi:
            corners = [(roi[f"x{i}"] * sx, roi[f"y{i}"] * sy) for i in range(1, 5)]  # TL, TR, BR, BL
        else:  # a few have no outline: the receipt fills the photo
            corners = [(0, 0), (img.width, 0), (img.width, img.height), (0, img.height)]
        width = round(max(math.dist(corners[0], corners[1]), math.dist(corners[3], corners[2])))
        height = round(max(math.dist(corners[0], corners[3]), math.dist(corners[1], corners[2])))
        tl, tr, br, bl = corners
        page = img.transform((width, height), Image.QUAD, (*tl, *bl, *br, *tr), resample=Image.BICUBIC, fillcolor=(255, 255, 255))
        rows: dict[int, list[tuple[float, float, str]]] = {}
        for line in label["valid_line"]:
            for word in line["words"]:
                q = word["quad"]
                rows.setdefault(word["row_id"], []).append(((q["x1"] + q["x4"]) / 2, (q["y1"] + q["y4"]) / 2, word["text"]))
        ordered = sorted(rows.values(), key=lambda ws: sum(w[1] for w in ws) / len(ws))
        truth = "\n".join(" ".join(w[2] for w in sorted(ws)) for ws in ordered)
        page.save(out / f"cord_{js.stem}.png")
        (out / f"cord_{js.stem}.gt.txt").write_text(truth + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
