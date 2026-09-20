from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageOps

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from training.scripts.convert_to_docquad import Point, convert, display_to_stored  # noqa: E402

W, H = 40, 30
# Stored-pixel corners of a quad (TL,TR,BR,BL in the stored image), all distinct and asymmetric.
STORED = [(5, 4), (33, 7), (30, 25), (8, 21)]


def _stored_image_with_markers(orientation: int, path: Path) -> list[tuple[int, int]]:
    """Writes a stored image with one marker colour per corner; returns the corners as seen in the
    EXIF-upright view (what a label tool that applies EXIF orientation would record)."""
    im = Image.new("RGB", (W, H), (0, 0, 0))
    colours = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0)]
    for (x, y), c in zip(STORED, colours):
        im.putpixel((x, y), c)
    exif = Image.Exif()
    exif[274] = orientation
    im.save(path, exif=exif)

    with Image.open(path) as stored:
        upright = ImageOps.exif_transpose(stored)
    px = upright.load()
    display = []
    for c in colours:
        hits = [(x, y) for y in range(upright.height) for x in range(upright.width) if px[x, y] == c]
        assert len(hits) == 1
        display.append(hits[0])
    return display


def test_display_to_stored_matches_pil_exif_transpose(tmp_path: Path) -> None:
    for orientation in range(1, 9):
        display = _stored_image_with_markers(orientation, tmp_path / f"o{orientation}.png")
        for (xd, yd), (xs, ys) in zip(display, STORED):
            p = display_to_stored(Point(float(xd), float(yd)), orientation, W, H)
            assert (p.x, p.y) == (float(xs), float(ys)), f"orientation {orientation}"


def test_convert_writes_stored_coordinates_for_exif_rotated_photo(tmp_path: Path) -> None:
    images = tmp_path / "in"
    images.mkdir()
    display = _stored_image_with_markers(6, images / "photo.png")
    jsonl = tmp_path / "labels.jsonl"
    jsonl.write_text(json.dumps({"image": "photo.png", "corners": [list(p) for p in display]}) + "\n")

    report = convert(in_images=images, in_jsonl=jsonl, out_dir=tmp_path / "out")

    label = json.loads((tmp_path / "out" / "labels" / "photo.json").read_text())
    assert (label["width"], label["height"]) == (W, H)
    assert [tuple(p) for p in label["corners_px"]] == [(float(x), float(y)) for x, y in STORED]
    sample = report["samples"]["photo.png"]
    assert sample["exif_orientation"] == 6
    assert "bounds_error" not in sample["errors"]


def test_convert_keeps_coordinates_when_labels_are_already_stored(tmp_path: Path) -> None:
    images = tmp_path / "in"
    images.mkdir()
    _stored_image_with_markers(6, images / "photo.png")
    jsonl = tmp_path / "labels.jsonl"
    jsonl.write_text(json.dumps({"image": "photo.png", "corners": [list(p) for p in STORED]}) + "\n")

    convert(in_images=images, in_jsonl=jsonl, out_dir=tmp_path / "out", label_coords="stored")

    label = json.loads((tmp_path / "out" / "labels" / "photo.json").read_text())
    assert [tuple(p) for p in label["corners_px"]] == [(float(x), float(y)) for x, y in STORED]
