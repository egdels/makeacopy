#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import random
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image, ImageDraw

# If the script is executed directly via `python training/scripts/...py`,
# `sys.path[0]` will be the scripts directory. To make `import training.*`
# work reliably, we deterministically add the repo root to `sys.path`.
_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from training.docquad_m1.letterbox import LetterboxTransform
from training.docquad_m1.targets import generate_corner_heatmaps_64, generate_mask_target_64
from training.docquad_m2.model import DocQuadNet256


IN_SIZE = 256

# Spec: sigma is constant and must be in [2,4].
# Default is 2.0; it can be overridden via CLI to deterministically pick
# one of the allowed values (no scheduling).
DEFAULT_SIGMA = 2.0


def _set_determinism(seed: int = 0) -> None:
    os.environ.setdefault("PYTHONHASHSEED", str(int(seed)))
    random.seed(int(seed))
    np.random.seed(int(seed))
    torch.manual_seed(int(seed))
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(int(seed))

    try:
        torch.use_deterministic_algorithms(True)
    except Exception:
        # Not every Torch configuration supports this on all backends.
        pass

    torch.backends.cudnn.benchmark = False
    torch.backends.cudnn.deterministic = True


def _auto_device() -> torch.device:
    if torch.cuda.is_available():
        return torch.device("cuda")
    if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def _read_split_list(path: Path) -> list[str]:
    names: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if not s:
            continue
        names.append(s)
    return names


def _load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _extract_corners_px(label: dict[str, Any]) -> np.ndarray:
    corners = label.get("corners_px", None)
    if corners is None:
        raise ValueError("missing_field:corners_px")
    if not isinstance(corners, list) or len(corners) != 4:
        raise ValueError("corners_not_4")
    out = np.empty((4, 2), dtype=np.float64)
    for i, p in enumerate(corners):
        if not isinstance(p, list) or len(p) != 2:
            raise ValueError("corner_not_pair")
        x, y = p[0], p[1]
        if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
            raise ValueError("corner_not_numeric")
        out[i, 0] = float(x)
        out[i, 1] = float(y)
    return out


# Rotation augmentation works on a reduced copy: the model input is 256 px, so 2x oversampling is
# plenty and keeps the per-sample cost low.
_AUG_MAX_EDGE = 512


def canonicalize_corners(corners: np.ndarray) -> np.ndarray:
    """TL,TR,BR,BL as in `make_trainable_from_converted._canonicalize_indices_v1`: sort by angle
    around the centroid, start at min(x+y). Needed after rotating, because the geometric "top-left"
    corner changes with the rotation angle."""
    c = corners.mean(axis=0)
    ang = np.arctan2(corners[:, 1] - c[1], corners[:, 0] - c[0])
    order = sorted(range(4), key=lambda i: (float(ang[i]), i))
    sums = [float(corners[i, 0] + corners[i, 1]) for i in order]
    tl = min(range(4), key=lambda k: (sums[k], k))
    return corners[order[tl:] + order[:tl]]


def rotate_corners(corners: np.ndarray, angle_deg: float, w: int, h: int) -> np.ndarray:
    """Corners after rotating a w×h image by `angle_deg` about its centre, same convention as
    `PIL.Image.rotate` (positive = counter-clockwise on screen, y pointing down)."""
    a = np.deg2rad(angle_deg)
    # PIL maps the pixel grid about ((w-1)/2, (h-1)/2) in pixel-index coordinates.
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
    dx, dy = corners[:, 0] - cx, corners[:, 1] - cy
    return np.stack([cx + dx * np.cos(a) + dy * np.sin(a), cy - dx * np.sin(a) + dy * np.cos(a)], axis=1)


def rotate_image_reflect(img: Image.Image, angle_deg: float) -> Image.Image:
    """Rotates about the centre, keeps the canvas size and fills uncovered corners by reflection.
    A constant fill would add a straight high-contrast border that looks like a document edge."""
    w, h = img.size
    pad = int(np.ceil((np.hypot(w, h) - min(w, h)) / 2.0)) + 2
    pad = min(pad, w - 1, h - 1)  # np.pad(reflect) cannot pad more than the image size - 1
    arr = np.pad(np.asarray(img), ((pad, pad), (pad, pad), (0, 0)), mode="reflect")
    rotated = Image.fromarray(arr).rotate(angle_deg, resample=Image.BILINEAR, expand=False)
    return rotated.crop((pad, pad, pad + w, pad + h))


def pick_rotation(
    rng: random.Random, corners: np.ndarray, w: int, h: int, max_deg: float, margin: float = 0.01
) -> float:
    """Random angle in [-max_deg, max_deg] that keeps all corners inside the frame; tries the
    opposite sign and smaller magnitudes, returns 0.0 if nothing fits."""
    mag = rng.uniform(0.0, max_deg)
    sign = rng.choice((-1.0, 1.0))
    for m in (mag, mag * 0.66, mag * 0.33):
        for a in (sign * m, -sign * m):
            r = rotate_corners(corners, a, w, h)
            if (
                (r[:, 0] >= margin * w).all()
                and (r[:, 0] <= (w - 1) - margin * w).all()
                and (r[:, 1] >= margin * h).all()
                and (r[:, 1] <= (h - 1) - margin * h).all()
            ):
                return float(a)
    return 0.0


def _pil_to_chw_float01(img_rgb: Image.Image) -> torch.Tensor:
    if img_rgb.mode != "RGB":
        img_rgb = img_rgb.convert("RGB")
    w, h = img_rgb.size
    raw = img_rgb.tobytes()  # RGB interleaved
    try:
        t = torch.frombuffer(memoryview(raw), dtype=torch.uint8)
    except Exception:
        # Fallback (slower, but robust)
        t = torch.tensor(list(raw), dtype=torch.uint8)
    t = t.reshape(h, w, 3).permute(2, 0, 1).contiguous()
    return t.to(dtype=torch.float32) / 255.0


@dataclass(frozen=True)
class SampleMeta:
    name: str
    src_w: int
    src_h: int
    corners_src: np.ndarray  # (4,2) float64
    corners_256: np.ndarray  # (4,2) float64
    letterbox: LetterboxTransform
    # For deterministic mapping when using PIL letterboxing (paste offsets / resized size):
    resized_w: int
    resized_h: int
    paste_x: int
    paste_y: int


class DocQuadHeatmapDataset(torch.utils.data.Dataset):
    """Dataset for DocQuadNet-256 heatmap+mask training.

    Preprocessing (FIX):
    - RGB -> float32, 0..1
    - Letterbox -> 256×256
    - Corners are mapped into 256-space using the same letterbox transform

    Note: PIL `paste()` uses integer offsets, while the corner transform uses
    `LetterboxTransform` (float). This can introduce small sub-pixel differences,
    but it is deterministic.
    """

    def __init__(
        self,
        base_dir: Path,
        names: list[str],
        *,
        sigma: float,
        rotate_max_deg: float = 0.0,
        rotate_prob: float = 0.0,
        deterministic_aug: bool = False,
    ):
        """`rotate_max_deg`/`rotate_prob` enable rotation augmentation (off by default).
        `deterministic_aug` derives the augmentation from the sample index, so a validation set
        sees the same rotations every epoch and its metric stays comparable."""
        self.base_dir = base_dir
        self.images_dir = base_dir / "images"
        self.labels_dir = base_dir / "labels"
        self.names = list(names)
        self.sigma = float(sigma)
        self.rotate_max_deg = float(rotate_max_deg)
        self.rotate_prob = float(rotate_prob)
        self.deterministic_aug = bool(deterministic_aug)
        self._rng = random.Random(0)

    def __len__(self) -> int:
        return len(self.names)

    def __getitem__(self, idx: int) -> dict[str, Any]:
        name = self.names[int(idx)]
        img_path = self.images_dir / name
        label_path = self.labels_dir / (Path(name).stem + ".json")

        if not img_path.exists():
            raise FileNotFoundError(f"missing image: {img_path}")
        if not label_path.exists():
            raise FileNotFoundError(f"missing label: {label_path}")

        label = _load_json(label_path)
        if label.get("image", None) != name:
            raise ValueError(f"label image field mismatch: file={name} json.image={label.get('image', None)}")

        w = int(label.get("width"))
        h = int(label.get("height"))
        corners_src = _extract_corners_px(label)

        with Image.open(img_path) as im0:
            im0 = im0.convert("RGB")
            if im0.size != (w, h):
                raise ValueError(f"image size mismatch for {name}: label=({w},{h}) file={im0.size}")

            if self.rotate_max_deg > 0.0 and self.rotate_prob > 0.0:
                rng = random.Random(1_000_003 * int(idx) + 17) if self.deterministic_aug else self._rng
                if rng.random() < self.rotate_prob:
                    s = min(1.0, _AUG_MAX_EDGE / float(max(w, h)))
                    small = im0.resize((max(2, round(w * s)), max(2, round(h * s))), resample=Image.BILINEAR) if s < 1.0 else im0
                    sw, sh = small.size
                    small_corners = corners_src * np.array([sw / float(w), sh / float(h)])
                    angle = pick_rotation(rng, small_corners, sw, sh, self.rotate_max_deg)
                    if angle != 0.0:
                        im0 = rotate_image_reflect(small, angle)
                        rotated = rotate_corners(small_corners, angle, sw, sh)
                        # Back to the label's coordinate frame (w×h) so metrics keep their scale.
                        corners_src = canonicalize_corners(rotated * np.array([w / float(sw), h / float(sh)]))

            letterbox = LetterboxTransform.create(w, h, IN_SIZE, IN_SIZE)
            resized_w = int(round(w * letterbox.scale))
            resized_h = int(round(h * letterbox.scale))
            resized = im0.resize((resized_w, resized_h), resample=Image.BILINEAR)
            canvas = Image.new("RGB", (IN_SIZE, IN_SIZE), (0, 0, 0))
            paste_x = int(round(letterbox.offset_x))
            paste_y = int(round(letterbox.offset_y))
            canvas.paste(resized, (paste_x, paste_y))

        x = _pil_to_chw_float01(canvas)
        if x.shape != (3, IN_SIZE, IN_SIZE):
            raise RuntimeError(f"internal error: unexpected input tensor shape {tuple(x.shape)}")

        corners_256 = letterbox.forward(corners_src).astype(np.float64)  # (4,2)
        corners_256 = np.clip(corners_256, 0.0, 255.0)

        hm = generate_corner_heatmaps_64(corners_256.astype(np.float32), sigma=self.sigma)  # (4,64,64)
        mask = generate_mask_target_64(corners_256.astype(np.float32))  # (64,64) uint8

        tgt_corner = torch.from_numpy(hm).to(dtype=torch.float32)
        tgt_mask = torch.from_numpy(mask.astype(np.float32)).unsqueeze(0)  # (1,64,64)

        if tgt_corner.shape != (4, 64, 64):
            raise RuntimeError(f"internal error: unexpected target corner shape {tuple(tgt_corner.shape)}")
        if tgt_mask.shape != (1, 64, 64):
            raise RuntimeError(f"internal error: unexpected target mask shape {tuple(tgt_mask.shape)}")

        meta = SampleMeta(
            name=name,
            src_w=w,
            src_h=h,
            corners_src=corners_src,
            corners_256=corners_256,
            letterbox=letterbox,
            resized_w=resized_w,
            resized_h=resized_h,
            paste_x=paste_x,
            paste_y=paste_y,
        )

        return {
            "x": x,
            "tgt_corner": tgt_corner,
            "tgt_mask": tgt_mask,
            "meta": meta,
        }


def _collate(batch: list[dict[str, Any]]) -> dict[str, Any]:
    x = torch.stack([b["x"] for b in batch], dim=0)
    tgt_corner = torch.stack([b["tgt_corner"] for b in batch], dim=0)
    tgt_mask = torch.stack([b["tgt_mask"] for b in batch], dim=0)
    meta = [b["meta"] for b in batch]
    return {
        "x": x,
        "tgt_corner": tgt_corner,
        "tgt_mask": tgt_mask,
        "meta": meta,
    }


def _decode_corners_argmax_64_to_256(pred_corner_logits: torch.Tensor) -> torch.Tensor:
    """Argmax-Decode (Pixelzentren) 64->256.

    Spezifikation:
    - Heatmap-Grid ist auf Pixelzentren (i+0.5, j+0.5)
    - Downsample-Faktor 4
    """

    if pred_corner_logits.ndim != 4 or pred_corner_logits.shape[1] != 4 or pred_corner_logits.shape[-2:] != (64, 64):
        raise ValueError("pred_corner_logits must have shape [B,4,64,64]")

    b = int(pred_corner_logits.shape[0])
    flat = pred_corner_logits.reshape(b, 4, 64 * 64)
    idx = torch.argmax(flat, dim=-1)  # [B,4]
    py = (idx // 64).to(dtype=torch.float32)
    px = (idx % 64).to(dtype=torch.float32)
    x64 = px + 0.5
    y64 = py + 0.5
    x256 = x64 * 4.0
    y256 = y64 * 4.0
    return torch.stack([x256, y256], dim=-1)  # [B,4,2]


def _corner_mae_px_and_rel(pred_corner_logits: torch.Tensor, meta: list[SampleMeta]) -> tuple[float, float]:
    """MAE in Pixeln im Originalbild und relativ zur Bilddiagonale.
    
    Returns:
        (mae_px, mae_rel): mae_px ist der absolute Fehler in Pixeln,
                          mae_rel ist der Fehler relativ zur Bilddiagonale (0..1, z.B. 0.02 = 2%).
    """

    pred_256 = _decode_corners_argmax_64_to_256(pred_corner_logits).detach().cpu().numpy()  # [B,4,2]

    abs_sum = 0.0
    rel_sum = 0.0
    count = 0
    n_images = 0
    for i, m in enumerate(meta):
        pred_src = m.letterbox.inverse(pred_256[i]).astype(np.float64)
        gt_src = m.corners_src.astype(np.float64)
        d = np.abs(pred_src - gt_src).reshape(-1)
        abs_sum += float(d.sum())
        # Image diagonal for relative metric: mean(d) / diag per image
        diag = np.sqrt(float(m.src_w) ** 2 + float(m.src_h) ** 2)
        rel_sum += float(d.mean()) / diag if diag > 0 else 0.0
        count += int(d.size)
        n_images += 1
    mae_px = abs_sum / float(max(1, count))
    mae_rel = rel_sum / float(max(1, n_images))
    return mae_px, mae_rel


def _draw_quad(draw: ImageDraw.ImageDraw, pts: list[tuple[float, float]], color: tuple[int, int, int], width: int = 3) -> None:
    if len(pts) != 4:
        return
    poly = pts + [pts[0]]
    draw.line(poly, fill=color, width=width)
    r = 4
    for (x, y) in pts:
        draw.ellipse((x - r, y - r, x + r, y + r), outline=color, width=2)


def _overlay_mask_on_original(
    *,
    img_rgb: Image.Image,
    meta: SampleMeta,
    mask_prob_64: np.ndarray,
    alpha: float = 0.35,
) -> Image.Image:
    """Optional mask overlay on the original image.

    Procedure (deterministic, without additional dependencies):
    - mask_prob (64) -> (256) via bilinear
    - crop to the resized ROI in the 256 canvas (paste_x/y + resized_w/h)
    - resize back to (src_w, src_h)
    - alpha blend (blue)
    """

    if img_rgb.mode != "RGB":
        img_rgb = img_rgb.convert("RGB")

    m = np.asarray(mask_prob_64, dtype=np.float32)
    if m.shape != (64, 64):
        return img_rgb

    m_img = Image.fromarray(np.clip(m * 255.0, 0.0, 255.0).astype(np.uint8), mode="L")
    m_256 = m_img.resize((256, 256), resample=Image.BILINEAR)

    x0 = int(meta.paste_x)
    y0 = int(meta.paste_y)
    x1 = int(meta.paste_x + meta.resized_w)
    y1 = int(meta.paste_y + meta.resized_h)
    x0 = max(0, min(256, x0))
    y0 = max(0, min(256, y0))
    x1 = max(0, min(256, x1))
    y1 = max(0, min(256, y1))
    if x1 <= x0 or y1 <= y0:
        return img_rgb

    m_crop = m_256.crop((x0, y0, x1, y1))
    m_src = m_crop.resize((meta.src_w, meta.src_h), resample=Image.BILINEAR)

    # Blue overlay; alpha is proportional to the mask
    base = img_rgb.convert("RGBA")
    overlay = Image.new("RGBA", base.size, (0, 0, 255, 0))
    # Alpha channel: mask * alpha
    a = np.asarray(m_src, dtype=np.uint8)
    a = np.clip(a.astype(np.float32) * float(alpha), 0.0, 255.0).astype(np.uint8)
    overlay.putalpha(Image.fromarray(a, mode="L"))
    out = Image.alpha_composite(base, overlay).convert("RGB")
    return out


@dataclass(frozen=True)
class EpochAgg:
    corner_loss: float
    mask_loss: float
    total_loss: float
    corner_mae_px: float
    corner_mae_rel: float  # relativer Fehler bezogen auf Bilddiagonale (0..1)


def _run_epoch(
    *,
    model: DocQuadNet256,
    loader: torch.utils.data.DataLoader,
    device: torch.device,
    opt: torch.optim.Optimizer | None,
    lambda_mask: float,
) -> EpochAgg:
    is_train = opt is not None
    if is_train:
        model.train()
    else:
        model.eval()

    sum_corner = 0.0
    sum_mask = 0.0
    sum_total = 0.0
    sum_mae_px = 0.0
    sum_mae_rel = 0.0
    n_batches = 0

    for batch in loader:
        x = batch["x"].to(device)
        tgt_corner = batch["tgt_corner"].to(device)
        tgt_mask = batch["tgt_mask"].to(device)
        meta = batch["meta"]

        if is_train:
            opt.zero_grad(set_to_none=True)

        with torch.set_grad_enabled(is_train):
            out = model(x)

            # Test/debug requirement: forward output shapes must match exactly.
            if out.corner_heatmaps.shape != (x.shape[0], 4, 64, 64):
                raise RuntimeError(f"unexpected corner_heatmaps shape: {tuple(out.corner_heatmaps.shape)}")
            if out.mask_logits.shape != (x.shape[0], 1, 64, 64):
                raise RuntimeError(f"unexpected mask_logits shape: {tuple(out.mask_logits.shape)}")

            corner_loss = F.binary_cross_entropy_with_logits(out.corner_heatmaps, tgt_corner)
            mask_loss = F.binary_cross_entropy_with_logits(out.mask_logits, tgt_mask)
            total_loss = corner_loss + float(lambda_mask) * mask_loss
            if is_train:
                total_loss.backward()
                opt.step()

        sum_corner += float(corner_loss.detach().cpu().item())
        sum_mask += float(mask_loss.detach().cpu().item())
        sum_total += float(total_loss.detach().cpu().item())
        mae_px, mae_rel = _corner_mae_px_and_rel(out.corner_heatmaps, meta)
        sum_mae_px += mae_px
        sum_mae_rel += mae_rel
        n_batches += 1

    denom = float(max(1, n_batches))
    return EpochAgg(
        corner_loss=sum_corner / denom,
        mask_loss=sum_mask / denom,
        total_loss=sum_total / denom,
        corner_mae_px=sum_mae_px / denom,
        corner_mae_rel=sum_mae_rel / denom,
    )


def _render_fixed_val_samples(
    *,
    epoch: int,
    out_dir: Path,
    model: DocQuadNet256,
    device: torch.device,
    base_dir: Path,
    fixed_names: list[str],
    sigma: float,
    overlay_mask: bool,
) -> None:
    vis_dir = out_dir / "vis" / f"epoch_{epoch:02d}"
    vis_dir.mkdir(parents=True, exist_ok=True)

    ds = DocQuadHeatmapDataset(base_dir, fixed_names, sigma=sigma)
    model.eval()

    with torch.no_grad():
        for name in fixed_names:
            sample = ds[fixed_names.index(name)]
            x = sample["x"].unsqueeze(0).to(device)
            meta: SampleMeta = sample["meta"]

            out = model(x)
            pred_256 = _decode_corners_argmax_64_to_256(out.corner_heatmaps).detach().cpu().numpy()[0]
            pred_src = meta.letterbox.inverse(pred_256).astype(np.float64)

            img_path = base_dir / "images" / name
            with Image.open(img_path) as im0:
                im0 = im0.convert("RGB")
                d = ImageDraw.Draw(im0)
                _draw_quad(d, [(float(x), float(y)) for x, y in meta.corners_src], (0, 255, 0), width=3)
                _draw_quad(d, [(float(x), float(y)) for x, y in pred_src], (255, 0, 0), width=3)

                if overlay_mask:
                    mask_prob = torch.sigmoid(out.mask_logits).detach().cpu().numpy()[0, 0]
                    im0 = _overlay_mask_on_original(img_rgb=im0, meta=meta, mask_prob_64=mask_prob)

                im0.save(vis_dir / f"{Path(name).stem}.png")


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="DocQuadNet-256 Training (Heatmap + Mask)")
    p.add_argument("--base_dir", type=Path, default=Path("training/data/docquad_uvdoc_all_trainable"))
    p.add_argument("--train_split", type=Path, default=None)
    p.add_argument("--val_split", type=Path, default=None)
    p.add_argument("--epochs", type=int, default=10)
    p.add_argument("--batch", type=int, default=8)
    p.add_argument("--lr", type=float, default=1e-3)
    p.add_argument("--device", type=str, default="auto", choices=["auto", "cpu", "cuda", "mps"])
    p.add_argument("--out_dir", type=Path, default=Path("training/runs/docquad_heatmap"))
    p.add_argument(
        "--resume",
        type=str,
        default=None,
        help="Path to a checkpoint .pt or to a checkpoints/ directory (loads model_state and optionally optimizer state)",
    )
    p.add_argument(
        "--init_ckpt",
        type=Path,
        default=None,
        help="Loads only model_state from a .pt checkpoint and starts a fresh run (no optimizer/epoch/best state).",
    )
    p.add_argument(
        "--early_stop_patience",
        type=int,
        default=0,
        help="Early stopping patience in epochs (0 = disabled). Metric: val.corner_mae_px (lower is better).",
    )
    p.add_argument(
        "--early_stop_min_delta",
        type=float,
        default=0.0,
        help="Minimum required drop in val.corner_mae_px to count as an improvement.",
    )
    p.add_argument(
        "--aug_rotate_deg",
        type=float,
        default=0.0,
        help="Rotation augmentation: max absolute angle in degrees (0 = off). Rotates the photo about its "
        "centre with reflected borders and re-canonicalizes the corner order.",
    )
    p.add_argument(
        "--aug_rotate_prob",
        type=float,
        default=0.5,
        help="Share of samples that get rotated when --aug_rotate_deg > 0 (the rest stays upright).",
    )
    p.add_argument("--sigma", type=float, default=DEFAULT_SIGMA)
    p.add_argument("--overlay_mask", action="store_true")
    p.add_argument(
        "--backbone",
        type=str,
        default="large",
        choices=["large", "small"],
        help="MobileNetV3 backbone variant: 'large' (more accurate) or 'small' (faster/lighter). Default: large",
    )

    args = p.parse_args(argv)

    if args.resume is not None and args.init_ckpt is not None:
        raise ValueError("--resume and --init_ckpt are mutually exclusive")

    if not (2.0 <= float(args.sigma) <= 4.0):
        raise ValueError("sigma must be in [2,4] and constant (no scheduling)")

    base_dir: Path = args.base_dir
    train_split = args.train_split or (base_dir / "split_train.txt")
    val_split = args.val_split or (base_dir / "split_val.txt")

    if not base_dir.exists():
        raise FileNotFoundError(f"base_dir not found: {base_dir}")
    if not train_split.exists():
        raise FileNotFoundError(f"train_split not found: {train_split}")
    if not val_split.exists():
        raise FileNotFoundError(f"val_split not found: {val_split}")

    _set_determinism(seed=0)

    if args.device == "auto":
        device = _auto_device()
    else:
        device = torch.device(args.device)

    init_ckpt_path: Path | None = None
    if args.init_ckpt is not None:
        ip = Path(args.init_ckpt)
        if ip.is_dir():
            raise ValueError(f"init_ckpt must be a .pt file, not a directory: {ip}")
        if not ip.is_file():
            raise FileNotFoundError(f"init_ckpt checkpoint not found: {ip}")
        if ip.suffix.lower() != ".pt":
            raise ValueError(f"init_ckpt must be a .pt file: {ip}")
        init_ckpt_path = ip

    resume_ckpt_path: Path | None = None
    resume_out_dir: Path | None = None
    if args.resume is not None:
        rp = Path(str(args.resume))
        if rp.is_dir():
            # Erwartet: `.../checkpoints/` Verzeichnis.
            cand_last = rp / "last.pt"
            cand_best = rp / "best.pt"
            if cand_last.is_file():
                resume_ckpt_path = cand_last
            elif cand_best.is_file():
                resume_ckpt_path = cand_best
            else:
                raise FileNotFoundError(f"resume directory does not contain last.pt or best.pt: {rp}")
            resume_out_dir = rp.parent
        else:
            if not rp.is_file():
                raise FileNotFoundError(f"resume checkpoint not found: {rp}")
            resume_ckpt_path = rp
            resume_out_dir = rp.parent.parent if rp.parent.name == "checkpoints" else rp.parent

    out_dir_default = Path("training/runs/docquad_heatmap")
    out_dir: Path = args.out_dir
    if resume_out_dir is not None:
        if out_dir == out_dir_default and out_dir.resolve() != resume_out_dir.resolve():
            out_dir = resume_out_dir
        elif out_dir.resolve() != resume_out_dir.resolve():
            raise ValueError(
                f"out_dir ({out_dir}) must match resume run directory ({resume_out_dir}); "
                "omit --out_dir to use the resume directory"
            )
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "checkpoints").mkdir(parents=True, exist_ok=True)
    (out_dir / "vis").mkdir(parents=True, exist_ok=True)

    train_names = _read_split_list(train_split)
    val_names = _read_split_list(val_split)
    if not train_names or not val_names:
        raise ValueError("split files must contain at least one sample name")

    sigma = float(args.sigma)
    ds_train = DocQuadHeatmapDataset(
        base_dir, train_names, sigma=sigma, rotate_max_deg=args.aug_rotate_deg, rotate_prob=args.aug_rotate_prob
    )
    # Validation gets the same augmentation, fixed per sample, so "best" reflects tilted documents too.
    ds_val = DocQuadHeatmapDataset(
        base_dir,
        val_names,
        sigma=sigma,
        rotate_max_deg=args.aug_rotate_deg,
        rotate_prob=args.aug_rotate_prob,
        deterministic_aug=True,
    )

    gen = torch.Generator()
    gen.manual_seed(0)

    train_loader = torch.utils.data.DataLoader(
        ds_train,
        batch_size=int(args.batch),
        shuffle=True,
        num_workers=0,
        collate_fn=_collate,
        generator=gen,
        drop_last=False,
    )
    val_loader = torch.utils.data.DataLoader(
        ds_val,
        batch_size=int(args.batch),
        shuffle=False,
        num_workers=0,
        collate_fn=_collate,
        drop_last=False,
    )

    # 8 fixed val samples for visualization: deterministically the first 8 from split_val.
    fixed_names = val_names[: min(8, len(val_names))]

    backbone = str(args.backbone)
    model = DocQuadNet256(backbone=backbone, fpn_channels=96).to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=float(args.lr), weight_decay=1e-4)

    lambda_mask = 1.0
    best_val_corner_mae_px: float | None = None
    epochs_without_improve = 0
    start_epoch = 1

    if int(args.early_stop_patience) < 0:
        raise ValueError("early_stop_patience must be >= 0")
    if float(args.early_stop_min_delta) < 0.0:
        raise ValueError("early_stop_min_delta must be >= 0.0")

    if init_ckpt_path is not None:
        obj = torch.load(str(init_ckpt_path), map_location="cpu")
        if not isinstance(obj, dict) or "model_state" not in obj:
            raise ValueError(
                f"unsupported init checkpoint format at: {init_ckpt_path} (expected dict with key 'model_state')"
            )
        model.load_state_dict(obj["model_state"], strict=True)
        start_epoch = 1
        best_val_corner_mae_px = None
        epochs_without_improve = 0
        print(
            f"init: loaded model_state from {init_ckpt_path}; start_epoch=1; optimizer re-initialized; out_dir={out_dir}",
            flush=True,
        )

    if resume_ckpt_path is not None:
        obj = torch.load(str(resume_ckpt_path), map_location="cpu")
        if not isinstance(obj, dict) or "model_state" not in obj:
            raise ValueError(
                f"unsupported resume checkpoint format at: {resume_ckpt_path} (expected dict with key 'model_state')"
            )

        model.load_state_dict(obj["model_state"], strict=True)
        start_epoch = int(obj.get("epoch", 0)) + 1
        if start_epoch < 1:
            start_epoch = 1

        # Optimizer state: only accept explicitly known keys.
        # No "guessing" based on similar names.
        supported_opt_keys = ["opt_state", "optimizer_state"]
        used_opt_key: str | None = None
        opt_state = None

        if "opt_state" in obj and obj.get("opt_state") is not None:
            used_opt_key = "opt_state"
            opt_state = obj.get("opt_state")
        elif "optimizer_state" in obj and obj.get("optimizer_state") is not None:
            used_opt_key = "optimizer_state"
            opt_state = obj.get("optimizer_state")

        ignored_opt_like = [
            k
            for k in obj.keys()
            if ("opt" in str(k).lower() or "optim" in str(k).lower()) and k not in supported_opt_keys
        ]
        if ignored_opt_like:
            print(
                "resume: ignoring unknown optimizer-related keys: "
                f"{sorted(ignored_opt_like)} (supported: {supported_opt_keys})",
                flush=True,
            )

        if opt_state is not None:
            opt.load_state_dict(opt_state)
            print(
                f"resume: loaded optimizer state (key={used_opt_key}) from {resume_ckpt_path}",
                flush=True,
            )
        else:
            present = [k for k in supported_opt_keys if k in obj]
            print(
                "resume: checkpoint has no optimizer state; optimizer re-initialized: "
                f"{resume_ckpt_path} (supported keys: {supported_opt_keys}, present: {present})",
                flush=True,
            )

        # Training policy: on resume, the LR provided via CLI should take precedence,
        # even if the optimizer state contains a different LR.
        # (Momentum/Adam moments are preserved.)
        if args.lr is not None:
            lr_target = float(args.lr)
            lr_before = [float(g.get("lr", lr_target)) for g in opt.param_groups]
            for g in opt.param_groups:
                g["lr"] = lr_target
            if any(abs(v - lr_target) > 0.0 for v in lr_before):
                print(
                    f"resume: enforced lr={lr_target} (CLI override; was={lr_before})",
                    flush=True,
                )

        # If possible, derive the best val.corner_mae_px seen so far from the existing log.
        log_path_existing = out_dir / "metrics.jsonl"
        if log_path_existing.is_file():
            try:
                best_seen = None
                for line in log_path_existing.read_text(encoding="utf-8").splitlines():
                    if not line.strip():
                        continue
                    r = json.loads(line)
                    v = r.get("val", {})
                    cm = v.get("corner_mae_px", None)
                    if cm is None:
                        continue
                    cm_f = float(cm)
                    if best_seen is None or cm_f < best_seen:
                        best_seen = cm_f
                best_val_corner_mae_px = best_seen
            except Exception:
                # The log is optional; if in doubt, restart best-tracking.
                best_val_corner_mae_px = None

        if "best_val_corner_mae_px" in obj:
            try:
                best_val_corner_mae_px = float(obj["best_val_corner_mae_px"])
            except Exception:
                pass

        print(
            f"resume: start_epoch={start_epoch} out_dir={out_dir} checkpoint={resume_ckpt_path}",
            flush=True,
        )

    log_path = out_dir / "metrics.jsonl"
    log_mode = "a" if (resume_ckpt_path is not None and log_path.exists()) else "w"
    if log_mode == "a":
        print(f"metrics: append -> {log_path}", flush=True)
    else:
        if log_path.exists():
            # Non-resume runs overwrite by default. Log this explicitly to avoid silent data loss.
            print(f"metrics: overwrite -> {log_path}", flush=True)
        else:
            print(f"metrics: create -> {log_path}", flush=True)
    with log_path.open(log_mode, encoding="utf-8") as f:
        if start_epoch > int(args.epochs):
            print(
                f"nothing to do: start_epoch ({start_epoch}) > epochs ({int(args.epochs)}); already finished?",
                flush=True,
            )
        for epoch in range(start_epoch, int(args.epochs) + 1):
            train_agg = _run_epoch(model=model, loader=train_loader, device=device, opt=opt, lambda_mask=lambda_mask)
            val_agg = _run_epoch(model=model, loader=val_loader, device=device, opt=None, lambda_mask=lambda_mask)

            rec = {
                "epoch": epoch,
                "sigma": sigma,
                "lambda_mask": lambda_mask,
                "optimizer": {
                    "name": "AdamW",
                    "lr": float(opt.param_groups[0].get("lr", float(args.lr))),
                    "weight_decay": float(opt.param_groups[0].get("weight_decay", 1e-4)),
                },
                "device": str(device),
                "train": {
                    "corner_loss": train_agg.corner_loss,
                    "mask_loss": train_agg.mask_loss,
                    "total_loss": train_agg.total_loss,
                    "corner_mae_px": train_agg.corner_mae_px,
                    "corner_mae_rel": train_agg.corner_mae_rel,
                },
                "val": {
                    "corner_loss": val_agg.corner_loss,
                    "mask_loss": val_agg.mask_loss,
                    "total_loss": val_agg.total_loss,
                    "corner_mae_px": val_agg.corner_mae_px,
                    "corner_mae_rel": val_agg.corner_mae_rel,
                },
            }
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            f.flush()

            print(
                f"epoch {epoch:02d} | "
                f"lr={float(opt.param_groups[0].get('lr', float(args.lr))):.6g} | "
                f"train total={train_agg.total_loss:.6f} corner={train_agg.corner_loss:.6f} mask={train_agg.mask_loss:.6f} mae_px={train_agg.corner_mae_px:.3f} mae_rel={train_agg.corner_mae_rel:.4f} | "
                f"val total={val_agg.total_loss:.6f} corner={val_agg.corner_loss:.6f} mask={val_agg.mask_loss:.6f} mae_px={val_agg.corner_mae_px:.3f} mae_rel={val_agg.corner_mae_rel:.4f}",
                flush=True,
            )

            # Best / early stopping is based solely on val.corner_mae_px (minimum).
            cur_mae = float(val_agg.corner_mae_px)
            improved = False
            if best_val_corner_mae_px is None:
                improved = True
            else:
                # Only count an improvement if the metric drops by at least min_delta.
                if cur_mae < float(best_val_corner_mae_px) and (float(best_val_corner_mae_px) - cur_mae) >= float(
                    args.early_stop_min_delta
                ):
                    improved = True

            if improved:
                best_val_corner_mae_px = cur_mae
                epochs_without_improve = 0
                best_path = out_dir / "checkpoints" / "best.pt"
                torch.save(
                    {
                        "epoch": epoch,
                        "model_state": model.state_dict(),
                        "opt_state": opt.state_dict(),
                        "best_val_corner_mae_px": best_val_corner_mae_px,
                        "config": {"sigma": sigma, "lambda_mask": lambda_mask, "fpn_channels": 96, "backbone": backbone},
                    },
                    best_path,
                )
            else:
                epochs_without_improve += 1

            # Checkpoints
            last_path = out_dir / "checkpoints" / "last.pt"
            torch.save(
                {
                    "epoch": epoch,
                    "model_state": model.state_dict(),
                    "opt_state": opt.state_dict(),
                    "best_val_corner_mae_px": best_val_corner_mae_px,
                    "config": {"sigma": sigma, "lambda_mask": lambda_mask, "fpn_channels": 96, "backbone": backbone},
                },
                last_path,
            )

            # Visual debug
            _render_fixed_val_samples(
                epoch=epoch,
                out_dir=out_dir,
                model=model,
                device=device,
                base_dir=base_dir,
                fixed_names=fixed_names,
                sigma=sigma,
                overlay_mask=bool(args.overlay_mask),
            )

            if int(args.early_stop_patience) > 0 and epochs_without_improve >= int(args.early_stop_patience):
                print(
                    f"early_stop: stop at epoch {epoch} (best val.corner_mae_px={best_val_corner_mae_px}, "
                    f"min_delta={float(args.early_stop_min_delta)}, patience={int(args.early_stop_patience)})",
                    flush=True,
                )
                break

    print(f"wrote: {log_path}")
    print(f"wrote: {out_dir / 'checkpoints'}")
    print(f"wrote: {out_dir / 'vis'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
