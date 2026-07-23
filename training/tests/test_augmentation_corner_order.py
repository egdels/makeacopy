from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

SCRIPTS_DIR = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

albumentations = pytest.importorskip("albumentations")
pytest.importorskip("albumentations.pytorch")

import augmentation  # noqa: E402


def _collect_transform_names(transform) -> list[str]:
    names: list[str] = []
    stack = [transform]
    while stack:
        t = stack.pop()
        names.append(type(t).__name__)
        for child in getattr(t, "transforms", []) or []:
            stack.append(child)
    return names


def test_train_transforms_contain_no_flip():
    """Flips would swap corner semantics (TL<->TR etc.) without reordering
    the [TL, TR, BR, BL] rows used for the heatmap channels."""
    transform = augmentation.get_train_transforms()
    names = _collect_transform_names(transform)
    assert not any("Flip" in n for n in names), f"Flip transform found: {names}"


def test_canonicalize_corners_restores_order_after_horizontal_flip():
    img_size = 256
    corners = np.array(
        [[50, 50], [200, 60], [210, 200], [40, 190]], dtype=np.float32
    )  # TL, TR, BR, BL
    flipped = corners.copy()
    flipped[:, 0] = (img_size - 1) - flipped[:, 0]  # rows now TR, TL, BL, BR

    canonical = augmentation.canonicalize_corners_xy(flipped)

    expected = flipped[[1, 0, 3, 2]]  # back to TL, TR, BR, BL
    np.testing.assert_allclose(canonical, expected, atol=1e-4)


def test_augmented_dataset_heatmap_channels_match_corner_semantics():
    """Heatmap channel i must peak near corner i in [TL, TR, BR, BL] order."""
    rng = np.random.default_rng(42)
    img_size = 256
    heatmap_size = 128
    image = rng.integers(0, 255, (img_size, img_size, 3), dtype=np.uint8)
    corners = np.array(
        [[40, 40], [215, 45], [220, 210], [35, 205]], dtype=np.float32
    )

    dataset = augmentation.AugmentedDataset(
        images=[image],
        corners=[corners],
        transform=augmentation.get_train_transforms(img_size=img_size),
        heatmap_size=heatmap_size,
    )

    for _ in range(20):
        sample = dataset[0]
        heatmaps = sample["heatmaps"]
        out_corners = sample["corners"]
        assert heatmaps.shape == (4, heatmap_size, heatmap_size)

        scale = heatmap_size / img_size
        for i in range(4):
            peak = np.unravel_index(np.argmax(heatmaps[i]), heatmaps[i].shape)
            expected_xy = out_corners[i] * scale
            assert abs(peak[1] - expected_xy[0]) <= 2
            assert abs(peak[0] - expected_xy[1]) <= 2

        # Corners must be in canonical [TL, TR, BR, BL] order.
        np.testing.assert_allclose(
            out_corners,
            augmentation.canonicalize_corners_xy(out_corners),
            atol=1e-4,
        )
