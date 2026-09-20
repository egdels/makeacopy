from __future__ import annotations

import json
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from training.scripts.train_docquad_heatmap import (  # noqa: E402
    DocQuadHeatmapDataset,
    canonicalize_corners,
    pick_rotation,
    rotate_corners,
    rotate_image_reflect,
)

W, H = 200, 140
CORNERS = np.array([[60.0, 40.0], [150.0, 45.0], [145.0, 100.0], [55.0, 95.0]])


def _blob_image() -> Image.Image:
    arr = np.zeros((H, W, 3), dtype=np.uint8)
    for x, y in CORNERS.astype(int):
        arr[y - 1 : y + 2, x - 1 : x + 2] = 255
    return Image.fromarray(arr)


def test_rotate_corners_follows_the_pixels() -> None:
    img = _blob_image()
    for angle in (-35.0, 20.0, 40.0):
        rotated = np.asarray(rotate_image_reflect(img, angle)).astype(float).sum(axis=2)
        expected = rotate_corners(CORNERS, angle, W, H)
        for ex, ey in expected:
            y0, x0 = int(round(ey)), int(round(ex))
            window = rotated[y0 - 4 : y0 + 5, x0 - 4 : x0 + 5]
            ys, xs = np.nonzero(window > window.max() * 0.5)
            assert abs(xs.mean() - 4) <= 1.0 and abs(ys.mean() - 4) <= 1.0, f"angle {angle}"


def test_rotate_image_keeps_size_and_has_no_constant_border() -> None:
    noise = Image.fromarray(np.random.default_rng(0).integers(60, 200, (H, W, 3), dtype=np.uint8))
    out = rotate_image_reflect(noise, 33.0)
    assert out.size == (W, H)
    corner_patch = np.asarray(out)[:6, :6]
    assert corner_patch.std() > 5.0  # reflected content, not a flat fill


def test_canonicalize_restores_geometric_order_after_rotation() -> None:
    rect = np.array([[50.0, 40.0], [150.0, 40.0], [150.0, 100.0], [50.0, 100.0]])
    rotated = rotate_corners(rect, 40.0, W, H)
    canon = canonicalize_corners(rotated[[2, 3, 0, 1]])  # arbitrary cyclic relabelling
    sums = canon.sum(axis=1)
    assert sums.argmin() == 0  # TL first
    # clockwise on screen: positive shoelace area in image coordinates
    area = 0.5 * sum(canon[i, 0] * canon[(i + 1) % 4, 1] - canon[(i + 1) % 4, 0] * canon[i, 1] for i in range(4))
    assert area > 0


def test_pick_rotation_never_pushes_corners_out_of_frame() -> None:
    rng = random.Random(1)
    big = np.array([[8.0, 8.0], [190.0, 8.0], [190.0, 130.0], [8.0, 130.0]])  # nearly fills the frame
    for _ in range(50):
        a = pick_rotation(rng, big, W, H, 45.0)
        r = rotate_corners(big, a, W, H)
        assert (r[:, 0] >= 0).all() and (r[:, 0] <= W - 1).all() and (r[:, 1] >= 0).all() and (r[:, 1] <= H - 1).all()


def _write_sample(base: Path) -> None:
    (base / "images").mkdir(parents=True)
    (base / "labels").mkdir()
    _blob_image().save(base / "images" / "a.png")
    (base / "labels" / "a.json").write_text(
        json.dumps({"image": "a.png", "width": W, "height": H, "corners_px": CORNERS.tolist()})
    )


def test_dataset_without_flags_is_unchanged_and_rotation_moves_targets(tmp_path: Path) -> None:
    _write_sample(tmp_path)
    plain = DocQuadHeatmapDataset(tmp_path, ["a.png"], sigma=2.0)[0]
    off = DocQuadHeatmapDataset(tmp_path, ["a.png"], sigma=2.0, rotate_max_deg=0.0, rotate_prob=1.0)[0]
    assert np.array_equal(plain["x"].numpy(), off["x"].numpy())
    assert np.allclose(plain["meta"].corners_256, off["meta"].corners_256)

    ds = DocQuadHeatmapDataset(
        tmp_path, ["a.png"], sigma=2.0, rotate_max_deg=40.0, rotate_prob=1.0, deterministic_aug=True
    )
    a, b = ds[0], ds[0]
    assert np.array_equal(a["x"].numpy(), b["x"].numpy())  # deterministic per index
    assert not np.allclose(a["meta"].corners_256, plain["meta"].corners_256)
    # Heatmap peaks sit on the rotated, re-ordered corners.
    hm = a["tgt_corner"].numpy()
    for c in range(4):
        iy, ix = np.unravel_index(hm[c].argmax(), hm[c].shape)
        cx, cy = a["meta"].corners_256[c]
        assert abs((ix + 0.5) * 4 - cx) <= 4 and abs((iy + 0.5) * 4 - cy) <= 4
    # The image content moved with them: the brightest pixels are near the target corners.
    x = a["x"].numpy().sum(axis=0)
    for cx, cy in a["meta"].corners_256:
        win = x[int(cy) - 4 : int(cy) + 5, int(cx) - 4 : int(cx) + 5]
        assert win.max() > 0.5 * x.max()
