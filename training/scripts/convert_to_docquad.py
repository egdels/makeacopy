#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from PIL import Image


@dataclass(frozen=True)
class Point:
    x: float
    y: float


def _is_number(x: Any) -> bool:
    # `bool` is a subclass of `int`; exclude it explicitly.
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def _add_unique_error(sample: dict[str, Any], code: str) -> None:
    errs: list[str] = sample.setdefault("errors", [])
    if code not in errs:
        errs.append(code)


def _polygon_area_abs(pts: list[Point]) -> float:
    # Shoelace formula (absolute area)
    s = 0.0
    n = len(pts)
    for i in range(n):
        j = (i + 1) % n
        s += pts[i].x * pts[j].y - pts[j].x * pts[i].y
    return abs(0.5 * s)


def _orient(a: Point, b: Point, c: Point) -> float:
    # Cross product (b-a) x (c-a)
    return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)


def _on_segment(a: Point, b: Point, p: Point, eps: float = 1e-9) -> bool:
    if abs(_orient(a, b, p)) > eps:
        return False
    return (
        min(a.x, b.x) - eps <= p.x <= max(a.x, b.x) + eps
        and min(a.y, b.y) - eps <= p.y <= max(a.y, b.y) + eps
    )


def _segments_intersect(a1: Point, a2: Point, b1: Point, b2: Point, eps: float = 1e-9) -> bool:
    # Standard segment intersection including collinearity; endpoints count as intersection.
    o1 = _orient(a1, a2, b1)
    o2 = _orient(a1, a2, b2)
    o3 = _orient(b1, b2, a1)
    o4 = _orient(b1, b2, a2)

    def _sign(v: float) -> int:
        if v > eps:
            return 1
        if v < -eps:
            return -1
        return 0

    s1, s2, s3, s4 = _sign(o1), _sign(o2), _sign(o3), _sign(o4)

    if s1 == 0 and _on_segment(a1, a2, b1, eps=eps):
        return True
    if s2 == 0 and _on_segment(a1, a2, b2, eps=eps):
        return True
    if s3 == 0 and _on_segment(b1, b2, a1, eps=eps):
        return True
    if s4 == 0 and _on_segment(b1, b2, a2, eps=eps):
        return True

    return (s1 * s2 < 0) and (s3 * s4 < 0)


def _has_self_intersection_quad(pts: list[Point]) -> bool:
    # Quad edges: (0-1),(1-2),(2-3),(3-0). Only check the non-adjacent pairs.
    # Adjacent edges share endpoints and are not considered self-intersections.
    a0, a1, a2, a3 = pts
    # (0-1) with (2-3)
    if _segments_intersect(a0, a1, a2, a3):
        return True
    # (1-2) with (3-0)
    if _segments_intersect(a1, a2, a3, a0):
        return True
    return False


def _is_convex_quad(pts: list[Point], eps: float = 1e-9) -> tuple[bool, bool]:
    """(is_convex, is_degenerate).

    Degenerate: at least one angle/edge cross product is ~0.
    """
    signs: list[int] = []
    degenerate = False
    n = len(pts)
    for i in range(n):
        a = pts[i]
        b = pts[(i + 1) % n]
        c = pts[(i + 2) % n]
        cross = _orient(a, b, c)
        if abs(cross) <= eps:
            degenerate = True
            continue
        signs.append(1 if cross > 0 else -1)
    if not signs:
        return False, True
    is_convex = all(s == signs[0] for s in signs)
    return is_convex, degenerate


def _canonical_order_indices(pts: list[Point]) -> list[int]:
    """Kanonische Reihenfolge per Spezifikation:

    - Centroid berechnen
    - nach Winkel (clockwise) sortieren
    - rotieren, sodass TL zuerst ist (min(x+y), dann min(y), dann min(x))
    """
    cx = sum(p.x for p in pts) / 4.0
    cy = sum(p.y for p in pts) / 4.0

    def angle_idx(i: int) -> tuple[float, int]:
        p = pts[i]
        ang = math.atan2(p.y - cy, p.x - cx)
        return ang, i

    # Clockwise: sort by angle descending.
    ordered = sorted(range(4), key=lambda i: (angle_idx(i)[0], i), reverse=True)

    # TL = min(x+y), tie-break by y, x, index
    def tl_key(i: int) -> tuple[float, float, float, int]:
        p = pts[i]
        return (p.x + p.y, p.y, p.x, i)

    tl_idx = min(ordered, key=tl_key)
    k = ordered.index(tl_idx)
    return ordered[k:] + ordered[:k]


def _parse_corners(record: dict[str, Any], sample: dict[str, Any]) -> list[Point] | None:
    if "corners" not in record:
        _add_unique_error(sample, "missing_key_corners")
        return None
    corners = record["corners"]
    if not isinstance(corners, list):
        _add_unique_error(sample, "corners_not_list")
        return None
    if len(corners) != 4:
        _add_unique_error(sample, "corners_not_4")
        return None

    pts: list[Point] = []
    for idx, p in enumerate(corners):
        if not (isinstance(p, (list, tuple)) and len(p) == 2):
            _add_unique_error(sample, f"corner_{idx}_not_pair")
            return None
        x, y = p[0], p[1]
        if not (_is_number(x) and _is_number(y)):
            _add_unique_error(sample, f"corner_{idx}_non_numeric")
            return None
        pts.append(Point(float(x), float(y)))
    return pts


def _safe_read_image_size(path: Path) -> tuple[int, int] | None:
    try:
        with Image.open(path) as im:
            w, h = im.size
        if not (isinstance(w, int) and isinstance(h, int) and w > 0 and h > 0):
            return None
        return int(w), int(h)
    except Exception:
        return None


_EXIF_ORIENTATION_TAG = 274


def _read_exif_orientation(path: Path) -> int:
    """EXIF orientation (1..8); 1 if absent or unreadable."""
    try:
        with Image.open(path) as im:
            o = im.getexif().get(_EXIF_ORIENTATION_TAG, 1)
        return int(o) if isinstance(o, int) and 1 <= o <= 8 else 1
    except Exception:
        return 1


def display_to_stored(p: Point, orientation: int, width: int, height: int) -> Point:
    """Map a point from the EXIF-upright view to stored pixel coordinates.

    Label tools that load photos with EXIF orientation applied (``cv2.imread`` does, and so does
    ``training/scripts/labeler.py``) produce corners in the upright view. Everything downstream
    (PIL in training/evaluation, ``BitmapFactory`` in the app) reads the pixels as stored, so
    ``corners_px`` must be in stored coordinates. ``width``/``height`` are the stored dimensions.
    """
    x, y = p.x, p.y
    w1, h1 = float(width - 1), float(height - 1)
    if orientation == 2:  # mirrored horizontally
        return Point(w1 - x, y)
    if orientation == 3:  # rotated 180
        return Point(w1 - x, h1 - y)
    if orientation == 4:  # mirrored vertically
        return Point(x, h1 - y)
    if orientation == 5:  # transposed
        return Point(y, x)
    if orientation == 6:  # displayed rotated 90 CW
        return Point(y, h1 - x)
    if orientation == 7:  # transversed
        return Point(w1 - y, h1 - x)
    if orientation == 8:  # displayed rotated 90 CCW
        return Point(w1 - y, x)
    return p


def _ensure_empty_output_dir(out_dir: Path) -> None:
    if out_dir.exists():
        # "Do not overwrite": if the directory already contains content, abort.
        if any(out_dir.iterdir()):
            raise SystemExit(
                f"Output directory already exists and is not empty: {out_dir} (delete/rename it or choose a different --out_dir)"
            )
    else:
        out_dir.mkdir(parents=True, exist_ok=False)


def _write_json(path: Path, obj: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    data = json.dumps(obj, ensure_ascii=False, indent=2)
    tmp.write_text(data + "\n", encoding="utf-8")
    tmp.replace(path)


def convert(
    *,
    in_images: Path,
    in_jsonl: Path,
    out_dir: Path,
    label_coords: str = "exif",
) -> dict[str, Any]:
    if label_coords not in ("exif", "stored"):
        raise ValueError(f"label_coords must be 'exif' or 'stored', got {label_coords!r}")
    _ensure_empty_output_dir(out_dir)
    out_images = out_dir / "images"
    out_labels = out_dir / "labels"
    out_images.mkdir(parents=True, exist_ok=True)
    out_labels.mkdir(parents=True, exist_ok=True)

    report: dict[str, Any] = {
        "total_records": 0,
        "converted": 0,
        "skipped": 0,
        "errors_by_type": {},
        "samples": {},
    }

    seen_images: set[str] = set()

    def bump(code: str) -> None:
        m: dict[str, int] = report["errors_by_type"]
        m[code] = int(m.get(code, 0)) + 1

    with in_jsonl.open("r", encoding="utf-8") as f:
        for line_no, raw in enumerate(f, start=1):
            line = raw.strip()
            if not line:
                continue

            report["total_records"] += 1
            placeholder_name = f"__line_{line_no}__"
            sample: dict[str, Any] = {
                "status": "skipped",
                "errors": [],
                "width": None,
                "height": None,
            }

            try:
                rec = json.loads(line)
            except Exception:
                _add_unique_error(sample, "invalid_json")
                report["samples"][placeholder_name] = sample
                report["skipped"] += 1
                bump("invalid_json")
                continue

            if not isinstance(rec, dict):
                _add_unique_error(sample, "record_not_object")
                report["samples"][placeholder_name] = sample
                report["skipped"] += 1
                bump("record_not_object")
                continue

            image = rec.get("image", None)
            if not isinstance(image, str) or not image:
                _add_unique_error(sample, "missing_or_invalid_image")
                report["samples"][placeholder_name] = sample
                report["skipped"] += 1
                bump("missing_or_invalid_image")
                continue

            # Always store the report entry under the image key.
            report["samples"].setdefault(image, sample)
            sample = report["samples"][image]

            if image in seen_images:
                _add_unique_error(sample, "duplicate_record_for_image")
                report["skipped"] += 1
                bump("duplicate_record_for_image")
                continue
            seen_images.add(image)

            pts = _parse_corners(rec, sample)
            if pts is None:
                report["skipped"] += 1
                for e in sample["errors"]:
                    bump(e)
                continue

            src_img = in_images / image
            if not src_img.is_file():
                _add_unique_error(sample, "missing_image_file")
                report["skipped"] += 1
                bump("missing_image_file")
                continue

            size = _safe_read_image_size(src_img)
            if size is None:
                _add_unique_error(sample, "image_open_failed")
                report["skipped"] += 1
                bump("image_open_failed")
                continue
            width, height = size
            sample["width"] = int(width)
            sample["height"] = int(height)

            # Corners labelled on the EXIF-upright view -> stored pixel coordinates.
            if label_coords == "exif":
                orientation = _read_exif_orientation(src_img)
                if orientation != 1:
                    pts = [display_to_stored(p, orientation, width, height) for p in pts]
                    sample["exif_orientation"] = orientation
                    _add_unique_error(sample, "exif_orientation_applied")

            # Additional checks (report-only)
            # Bounds
            oob = False
            for p in pts:
                if not (0.0 <= p.x <= float(width - 1) and 0.0 <= p.y <= float(height - 1)):
                    oob = True
                    break
            if oob:
                _add_unique_error(sample, "bounds_error")

            # Geometry
            area = _polygon_area_abs(pts)
            if not (area > 0.0):
                _add_unique_error(sample, "degenerate_area")
            else:
                area_frac = area / float(width * height)
                if area_frac < 0.05:
                    _add_unique_error(sample, "area_fraction_lt_0.05")

            if _has_self_intersection_quad(pts):
                _add_unique_error(sample, "self_intersection")

            is_convex, is_deg = _is_convex_quad(pts)
            if is_deg:
                _add_unique_error(sample, "degenerate_convexity")
            if not is_convex:
                _add_unique_error(sample, "not_convex")

            # Corner order check (report-only)
            canonical = _canonical_order_indices(pts)
            if canonical != [0, 1, 2, 3]:
                _add_unique_error(sample, "order_error")

            # Write output (keep corners unchanged)
            dst_img = out_images / image
            dst_label = out_labels / f"{Path(image).stem}.json"

            if dst_img.exists() or dst_label.exists():
                # Output collision: do not overwrite.
                _add_unique_error(sample, "output_collision")
                report["skipped"] += 1
                bump("output_collision")
                continue

            try:
                shutil.copy2(src_img, dst_img)
            except Exception:
                _add_unique_error(sample, "image_copy_failed")
                report["skipped"] += 1
                bump("image_copy_failed")
                continue

            label_obj = {
                "image": image,
                "width": int(width),
                "height": int(height),
                "corners_px": [[p.x, p.y] for p in pts],
            }
            try:
                _write_json(dst_label, label_obj)
            except Exception:
                _add_unique_error(sample, "label_write_failed")
                report["skipped"] += 1
                bump("label_write_failed")
                # The image may already have been copied; we do not delete it automatically.
                continue

            # Status OK
            sample["status"] = "ok"
            report["converted"] += 1

            # Increment errors_by_type (warnings/errors) for ok samples
            for e in sample["errors"]:
                bump(e)

    # For samples that never ended up in seen_images (e.g. invalid_json placeholders),
    # errors have already been counted.

    report_path = out_dir / "report.json"
    _write_json(report_path, report)
    return report


def _top_errors(errors_by_type: dict[str, Any], n: int = 5) -> list[tuple[str, int]]:
    items: list[tuple[str, int]] = []
    for k, v in errors_by_type.items():
        try:
            items.append((str(k), int(v)))
        except Exception:
            continue
    items.sort(key=lambda kv: (-kv[1], kv[0]))
    return items[:n]


def _find_first_ok_label(out_dir: Path) -> Path | None:
    labels = out_dir / "labels"
    if not labels.is_dir():
        return None
    for p in sorted(labels.glob("*.json")):
        return p
    return None


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Konvertiert UVDoc JSONL + Bilder in DocQuadNet-256 Minimalformat (per-image JSON + report.json)."
    )
    parser.add_argument(
        "--in_images",
        default="training/data/uvdoc_all",
        help="Eingabe-Bilderverzeichnis (Default: training/data/uvdoc_all)",
    )
    parser.add_argument(
        "--in_jsonl",
        default="training/labels/uvdoc_all.jsonl",
        help="Eingabe-Labels als JSONL (Default: training/labels/uvdoc_all.jsonl)",
    )
    parser.add_argument(
        "--out_dir",
        default="training/data/docquad_uvdoc_all_converted",
        help="Ausgabe-Verzeichnis (wird neu angelegt; Default: training/data/docquad_uvdoc_all_converted)",
    )
    parser.add_argument(
        "--label_coords",
        choices=("exif", "stored"),
        default="exif",
        help=(
            "Koordinatensystem der Eingabe-Labels bei Fotos mit EXIF-Orientierung: 'exif' = auf der "
            "aufrechten Ansicht gelabelt (labeler.py / cv2.imread; Default), 'stored' = bereits in "
            "gespeicherten Pixelkoordinaten. Die Ausgabe ist immer in gespeicherten Pixelkoordinaten."
        ),
    )
    args = parser.parse_args(list(argv) if argv is not None else None)

    in_images = Path(args.in_images)
    in_jsonl = Path(args.in_jsonl)
    out_dir = Path(args.out_dir)

    if not in_images.is_dir():
        raise SystemExit(f"in_images ist kein Verzeichnis: {in_images}")
    if not in_jsonl.is_file():
        raise SystemExit(f"in_jsonl ist keine Datei: {in_jsonl}")

    report = convert(
        in_images=in_images, in_jsonl=in_jsonl, out_dir=out_dir, label_coords=args.label_coords
    )

    print(f"[docquad] out_dir: {out_dir}")
    print(f"[docquad] total_records: {report['total_records']}")
    print(f"[docquad] converted: {report['converted']}")
    print(f"[docquad] skipped: {report['skipped']}")

    top = _top_errors(report.get("errors_by_type", {}), n=8)
    if top:
        print("[docquad] top_errors:")
        for k, v in top:
            print(f"  - {k}: {v}")

    ex = _find_first_ok_label(out_dir)
    if ex is not None:
        try:
            txt = ex.read_text(encoding="utf-8")
        except Exception:
            txt = None
        print(f"[docquad] example_label: {ex}")
        if txt is not None:
            print(txt)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
