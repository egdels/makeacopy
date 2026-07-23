#!/usr/bin/env python3
"""
augmentation.py - Albumentations-based augmentation for document corner detection

Features:
- Geometric augmentation with automatic keypoint synchronization
- Photometric augmentation (brightness, contrast, noise, etc.)
- Special augmentations for document scans (shadows, compression, etc.)
- Train/Val transforms with ImageNet normalization
- Batch processing of images and labels (JSONL)

Usage as module:
    from augmentation import get_train_transforms, get_val_transforms

    train_transform = get_train_transforms(img_size=256)
    val_transform = get_val_transforms(img_size=256)

    # Application
    result = train_transform(image=image, keypoints=corners)
    augmented_image = result['image']
    augmented_corners = result['keypoints']

Usage as CLI:
    python augmentation.py \\
        --labels_in labels/my_data.jsonl \\
        --images_in data/my_data \\
        --labels_out labels/my_data_augmented.jsonl \\
        --images_out data/my_data_augmented \\
        --num_augmentations 5
"""

import argparse
import json
import numpy as np
from pathlib import Path
from typing import Tuple, List, Optional, Dict, Any

try:
    import albumentations as A
    from albumentations.pytorch import ToTensorV2
    ALBUMENTATIONS_AVAILABLE = True
except ImportError:
    ALBUMENTATIONS_AVAILABLE = False
    print("Warning: albumentations not installed. Install with: pip install albumentations")


# ImageNet normalization
IMAGENET_MEAN = [0.485, 0.456, 0.406]
IMAGENET_STD = [0.229, 0.224, 0.225]


def get_train_transforms(
    img_size: int = 256,
    geometric_prob: float = 0.5,
    photometric_prob: float = 0.8,
    noise_prob: float = 0.3,
    blur_prob: float = 0.2,
    compression_prob: float = 0.3,
    shadow_prob: float = 0.2,
    normalize: bool = True
) -> A.Compose:
    """
    Creates training transforms with augmentation.

    Args:
        img_size: Target image size
        geometric_prob: Probability for geometric augmentation
        photometric_prob: Probability for photometric augmentation
        noise_prob: Probability for noise
        blur_prob: Probability for blur
        compression_prob: Probability for compression artifacts
        shadow_prob: Probability for shadow/lighting effects
        normalize: Whether to apply ImageNet normalization

    Returns:
        Albumentations Compose object
    """
    if not ALBUMENTATIONS_AVAILABLE:
        raise ImportError("albumentations is required. Install with: pip install albumentations")

    transforms = [
        # Resize to target size
        A.Resize(img_size, img_size),

        # === GEOMETRIC AUGMENTATION ===
        # IMPORTANT: Very conservative parameters to keep corners in the image!
        # Corners must still be visible after augmentation.
        A.OneOf([
            A.Perspective(
                scale=(0.01, 0.03),  # Reduced from (0.02, 0.08) - less distortion
                keep_size=True,
                fit_output=True,  # Scale image so everything remains visible
                p=1.0
            ),
            A.Affine(
                rotate=(-8, 8),      # Reduced from (-15, 15)
                shear=(-5, 5),       # Reduced from (-10, 10)
                scale=(0.95, 1.05),  # Reduced from (0.9, 1.1)
                translate_percent={"x": (-0.15, 0.15), "y": (-0.15, 0.15)},  # Simulate off-center documents
                fit_output=True,     # Scale image so everything remains visible
                p=1.0
            ),
        ], p=geometric_prob),

        # NO HorizontalFlip/VerticalFlip for documents (text would be mirrored).
        # A flip would also swap the corner semantics (TL<->TR, BL<->BR) without
        # reordering the [TL, TR, BR, BL] rows used for the heatmap channels.

        # === PHOTOMETRIC AUGMENTATION ===
        # Brightness and contrast
        A.OneOf([
            A.RandomBrightnessContrast(
                brightness_limit=0.3,
                contrast_limit=0.3,
                p=1.0
            ),
            A.HueSaturationValue(
                hue_shift_limit=20,
                sat_shift_limit=30,
                val_shift_limit=30,
                p=1.0
            ),
            A.ColorJitter(
                brightness=0.2,
                contrast=0.2,
                saturation=0.2,
                hue=0.1,
                p=1.0
            ),
        ], p=photometric_prob),

        # Gamma correction (simulates different exposures)
        A.RandomGamma(gamma_limit=(80, 120), p=0.3),

        # === NOISE ===
        A.OneOf([
            A.GaussNoise(std_range=(0.02, 0.1), p=1.0),
            A.ISONoise(
                color_shift=(0.01, 0.05),
                intensity=(0.1, 0.5),
                p=1.0
            ),
            A.MultiplicativeNoise(
                multiplier=(0.9, 1.1),
                elementwise=True,
                p=1.0
            ),
        ], p=noise_prob),

        # === BLUR ===
        A.OneOf([
            A.MotionBlur(blur_limit=7, p=1.0),
            A.GaussianBlur(blur_limit=(3, 5), p=1.0),
            A.MedianBlur(blur_limit=5, p=1.0),
        ], p=blur_prob),

        # === COMPRESSION & ARTIFACTS ===
        A.ImageCompression(
            quality_range=(50, 95),
            p=compression_prob
        ),

        # === LIGHTING EFFECTS ===
        A.OneOf([
            A.RandomShadow(
                shadow_roi=(0, 0, 1, 1),
                num_shadows_limit=(1, 3),
                shadow_dimension=5,
                p=1.0
            ),
            A.RandomToneCurve(scale=0.1, p=1.0),
        ], p=shadow_prob),

        # CLAHE for low-light simulation
        A.CLAHE(clip_limit=4.0, tile_grid_size=(8, 8), p=0.2),

        # Occasionally grayscale (for more robust features)
        A.ToGray(p=0.05),

        # FINAL RESIZE: Ensures all images are exactly img_size x img_size
        # Needed because fit_output=True in Perspective/Affine can change the size
        A.Resize(img_size, img_size),
    ]

    # Normalization
    if normalize:
        transforms.append(
            A.Normalize(mean=IMAGENET_MEAN, std=IMAGENET_STD)
        )

    # Convert to tensor
    transforms.append(ToTensorV2())

    return A.Compose(
        transforms,
        keypoint_params=A.KeypointParams(
            format='xy',
            remove_invisible=False,
            angle_in_degrees=True
        )
    )


def get_val_transforms(
    img_size: int = 256,
    normalize: bool = True
) -> A.Compose:
    """
    Creates validation transforms (only resize and normalization).

    Args:
        img_size: Target image size
        normalize: Whether to apply ImageNet normalization

    Returns:
        Albumentations Compose object
    """
    if not ALBUMENTATIONS_AVAILABLE:
        raise ImportError("albumentations is required. Install with: pip install albumentations")

    transforms = [
        A.Resize(img_size, img_size),
    ]

    if normalize:
        transforms.append(
            A.Normalize(mean=IMAGENET_MEAN, std=IMAGENET_STD)
        )

    transforms.append(ToTensorV2())

    return A.Compose(
        transforms,
        keypoint_params=A.KeypointParams(
            format='xy',
            remove_invisible=False
        )
    )


def get_inference_transforms(
    img_size: int = 256,
    normalize: bool = True
) -> A.Compose:
    """
    Creates inference transforms (identical to validation).

    Args:
        img_size: Target image size
        normalize: Whether to apply ImageNet normalization

    Returns:
        Albumentations Compose object
    """
    return get_val_transforms(img_size, normalize)


def get_strong_augmentation(
    img_size: int = 256,
    normalize: bool = True
) -> A.Compose:
    """
    Strong augmentation for difficult cases or more data variety.

    Uses more aggressive parameters than standard augmentation.
    
    NOTE: ElasticTransform and VerticalFlip were removed as they are
    unrealistic for document scans and can destroy corner semantics.
    """
    if not ALBUMENTATIONS_AVAILABLE:
        raise ImportError("albumentations is required. Install with: pip install albumentations")

    transforms = [
        A.Resize(img_size, img_size),

        # Stronger geometric augmentation (without ElasticTransform - unrealistic for documents)
        A.OneOf([
            A.Perspective(scale=(0.05, 0.12), keep_size=True, fit_output=True, p=1.0),
            A.Affine(
                rotate=(-20, 20),
                shear=(-12, 12),
                scale=(0.85, 1.15),
                translate_percent={"x": (-0.25, 0.25), "y": (-0.25, 0.25)},  # Aggressive off-center documents
                fit_output=True,
                p=1.0
            ),
        ], p=0.7),

        # NO HorizontalFlip/VerticalFlip for documents (text would be mirrored)

        # Stronger photometric augmentation
        A.OneOf([
            A.RandomBrightnessContrast(
                brightness_limit=0.4,
                contrast_limit=0.4,
                p=1.0
            ),
            A.HueSaturationValue(
                hue_shift_limit=30,
                sat_shift_limit=40,
                val_shift_limit=40,
                p=1.0
            ),
            A.RGBShift(
                r_shift_limit=30,
                g_shift_limit=30,
                b_shift_limit=30,
                p=1.0
            ),
        ], p=0.9),

        # More noise
        A.OneOf([
            A.GaussNoise(std_range=(0.05, 0.15), p=1.0),
            A.ISONoise(intensity=(0.2, 0.8), p=1.0),
            A.MultiplicativeNoise(multiplier=(0.8, 1.2), p=1.0),
        ], p=0.5),

        # More blur
        A.OneOf([
            A.MotionBlur(blur_limit=11, p=1.0),
            A.GaussianBlur(blur_limit=(3, 7), p=1.0),
            A.Defocus(radius=(3, 5), p=1.0),
        ], p=0.4),

        # Stronger compression
        A.ImageCompression(quality_range=(30, 90), p=0.5),

        # More lighting effects
        A.OneOf([
            A.RandomShadow(num_shadows_limit=(2, 5), p=1.0),
            A.RandomSunFlare(
                flare_roi=(0, 0, 1, 1),
                src_radius=100,
                p=1.0
            ),
            A.RandomFog(fog_coef_lower=0.1, fog_coef_upper=0.3, p=1.0),
        ], p=0.3),

        A.CLAHE(clip_limit=6.0, p=0.3),
        A.ToGray(p=0.1),
        A.Posterize(num_bits=4, p=0.1),
    ]

    if normalize:
        transforms.append(A.Normalize(mean=IMAGENET_MEAN, std=IMAGENET_STD))

    transforms.append(ToTensorV2())

    return A.Compose(
        transforms,
        keypoint_params=A.KeypointParams(
            format='xy',
            remove_invisible=False
        )
    )


def get_batch_augmentation_transforms(
    geometric_prob: float = 0.5,
    photometric_prob: float = 0.8,
    noise_prob: float = 0.3,
    blur_prob: float = 0.2,
    compression_prob: float = 0.3,
    shadow_prob: float = 0.2,
) -> A.Compose:
    """
    Creates augmentation transforms for batch processing.
    
    Unlike get_train_transforms():
    - No resize (original size is preserved)
    - No normalization
    - No ToTensorV2
    
    Args:
        geometric_prob: Probability for geometric augmentation
        photometric_prob: Probability for photometric augmentation
        noise_prob: Probability for noise
        blur_prob: Probability for blur
        compression_prob: Probability for compression artifacts
        shadow_prob: Probability for shadow/lighting effects

    Returns:
        Albumentations Compose object
    """
    if not ALBUMENTATIONS_AVAILABLE:
        raise ImportError("albumentations is required. Install with: pip install albumentations")

    transforms = [
        # === GEOMETRIC AUGMENTATION ===
        A.OneOf([
            A.Perspective(
                scale=(0.01, 0.03),
                keep_size=True,
                fit_output=True,
                p=1.0
            ),
            A.Affine(
                rotate=(-8, 8),
                shear=(-5, 5),
                scale=(0.95, 1.05),
                translate_percent={"x": (-0.15, 0.15), "y": (-0.15, 0.15)},  # Simulate off-center documents
                fit_output=True,
                p=1.0
            ),
        ], p=geometric_prob),

        # NO HorizontalFlip for documents (text would be mirrored)

        # === PHOTOMETRIC AUGMENTATION ===
        A.OneOf([
            A.RandomBrightnessContrast(
                brightness_limit=0.3,
                contrast_limit=0.3,
                p=1.0
            ),
            A.HueSaturationValue(
                hue_shift_limit=20,
                sat_shift_limit=30,
                val_shift_limit=30,
                p=1.0
            ),
            A.ColorJitter(
                brightness=0.2,
                contrast=0.2,
                saturation=0.2,
                hue=0.1,
                p=1.0
            ),
        ], p=photometric_prob),

        A.RandomGamma(gamma_limit=(80, 120), p=0.3),

        # === NOISE ===
        A.OneOf([
            A.GaussNoise(std_range=(0.02, 0.1), p=1.0),
            A.ISONoise(
                color_shift=(0.01, 0.05),
                intensity=(0.1, 0.5),
                p=1.0
            ),
            A.MultiplicativeNoise(
                multiplier=(0.9, 1.1),
                elementwise=True,
                p=1.0
            ),
        ], p=noise_prob),

        # === BLUR ===
        A.OneOf([
            A.MotionBlur(blur_limit=7, p=1.0),
            A.GaussianBlur(blur_limit=(3, 5), p=1.0),
            A.MedianBlur(blur_limit=5, p=1.0),
        ], p=blur_prob),

        # === COMPRESSION & ARTIFACTS ===
        A.ImageCompression(
            quality_range=(50, 95),
            p=compression_prob
        ),

        # === LIGHTING EFFECTS ===
        A.OneOf([
            A.RandomShadow(
                shadow_roi=(0, 0, 1, 1),
                num_shadows_limit=(1, 3),
                shadow_dimension=5,
                p=1.0
            ),
            A.RandomToneCurve(scale=0.1, p=1.0),
        ], p=shadow_prob),

        A.CLAHE(clip_limit=4.0, tile_grid_size=(8, 8), p=0.2),
        A.ToGray(p=0.05),
    ]

    return A.Compose(
        transforms,
        keypoint_params=A.KeypointParams(
            format='xy',
            remove_invisible=False,
            angle_in_degrees=True
        )
    )


def get_finetune_augmentation_transforms(
    geometric_prob: float = 0.3,
    photometric_prob: float = 0.7,
    noise_prob: float = 0.2,
    blur_prob: float = 0.15,
    compression_prob: float = 0.4,
    shadow_prob: float = 0.25,
) -> A.Compose:
    """
    Mild augmentation specifically for fine-tuning with few real images.
    
    Compared to get_batch_augmentation_transforms():
    - Reduced geometric probability (0.3 instead of 0.5)
    - Milder parameters for Perspective/Affine
    - NO HorizontalFlip (text would be mirrored)
    - Increased compression probability (realistic for phone photos)
    
    Args:
        geometric_prob: Probability for geometric augmentation (default: 0.3)
        photometric_prob: Probability for photometric augmentation (default: 0.7)
        noise_prob: Probability for noise (default: 0.2)
        blur_prob: Probability for blur (default: 0.15)
        compression_prob: Probability for compression artifacts (default: 0.4)
        shadow_prob: Probability for shadow/lighting effects (default: 0.25)

    Returns:
        Albumentations Compose object
    """
    if not ALBUMENTATIONS_AVAILABLE:
        raise ImportError("albumentations is required. Install with: pip install albumentations")

    transforms = [
        # === MILD GEOMETRIC AUGMENTATION ===
        A.OneOf([
            A.Perspective(
                scale=(0.005, 0.02),  # Very mild
                keep_size=True,
                fit_output=True,
                p=1.0
            ),
            A.Affine(
                rotate=(-5, 5),       # Mild
                shear=(-3, 3),        # Mild
                scale=(0.97, 1.03),   # Mild
                translate_percent={"x": (-0.10, 0.10), "y": (-0.10, 0.10)},  # Mild off-center documents
                fit_output=True,
                p=1.0
            ),
        ], p=geometric_prob),

        # NO HorizontalFlip for documents (text would be mirrored)

        # === PHOTOMETRIC AUGMENTATION ===
        A.OneOf([
            A.RandomBrightnessContrast(
                brightness_limit=0.2,
                contrast_limit=0.2,
                p=1.0
            ),
            A.HueSaturationValue(
                hue_shift_limit=10,
                sat_shift_limit=20,
                val_shift_limit=20,
                p=1.0
            ),
        ], p=photometric_prob),

        A.RandomGamma(gamma_limit=(85, 115), p=0.2),

        # === MILD NOISE ===
        A.OneOf([
            A.GaussNoise(std_range=(0.01, 0.05), p=1.0),
            A.ISONoise(
                color_shift=(0.01, 0.03),
                intensity=(0.05, 0.2),
                p=1.0
            ),
        ], p=noise_prob),

        # === MILD BLUR ===
        A.OneOf([
            A.MotionBlur(blur_limit=5, p=1.0),
            A.GaussianBlur(blur_limit=(3, 3), p=1.0),
        ], p=blur_prob),

        # === JPEG COMPRESSION (realistic for phone photos) ===
        A.ImageCompression(
            quality_range=(60, 95),
            p=compression_prob
        ),

        # === SHADOWS (realistic) ===
        A.RandomShadow(
            shadow_roi=(0, 0, 1, 1),
            num_shadows_limit=(1, 2),
            shadow_dimension=5,
            p=shadow_prob
        ),

        # CLAHE for low-light
        A.CLAHE(clip_limit=3.0, tile_grid_size=(8, 8), p=0.15),
    ]

    return A.Compose(
        transforms,
        keypoint_params=A.KeypointParams(
            format='xy',
            remove_invisible=False,
            angle_in_degrees=True
        )
    )


# =============================================================================
# Batch Processing for CLI
# =============================================================================

def load_labels(labels_path: Path) -> List[Dict[str, Any]]:
    """
    Loads labels from a JSONL file.
    
    Args:
        labels_path: Path to the JSONL file
        
    Returns:
        List of label dictionaries with 'image' and 'corners'
    """
    labels = []
    with open(labels_path, 'r', encoding='utf-8') as f:
        for line_no, line in enumerate(f, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
                if 'image' in record and 'corners' in record:
                    labels.append(record)
                else:
                    print(f"Warning: Line {line_no} missing 'image' or 'corners', skipped")
            except json.JSONDecodeError as e:
                print(f"Warning: Line {line_no} is not valid JSON: {e}")
    return labels


def save_labels(labels: List[Dict[str, Any]], labels_path: Path) -> None:
    """
    Saves labels to a JSONL file.
    
    Args:
        labels: List of label dictionaries
        labels_path: Path to the output JSONL file
    """
    labels_path.parent.mkdir(parents=True, exist_ok=True)
    with open(labels_path, 'w', encoding='utf-8') as f:
        for record in labels:
            f.write(json.dumps(record, ensure_ascii=False) + '\n')


def process_batch(
    labels_in: Path,
    images_in: Path,
    labels_out: Path,
    images_out: Path,
    num_augmentations: int = 5,
    include_original: bool = True,
    seed: Optional[int] = None
) -> Dict[str, Any]:
    """
    Processes a batch of images and labels.
    
    Args:
        labels_in: Path to the input JSONL file
        images_in: Path to the input image directory
        labels_out: Path to the output JSONL file
        images_out: Path to the output image directory
        num_augmentations: Number of augmentations per image
        include_original: Whether to also copy the original image
        seed: Random seed for reproducibility
        
    Returns:
        Report dictionary with statistics
    """
    try:
        import cv2
    except ImportError:
        raise ImportError("opencv-python is required. Install with: pip install opencv-python")
    
    if seed is not None:
        np.random.seed(seed)
    
    # Create output directory
    images_out.mkdir(parents=True, exist_ok=True)
    
    # Load labels
    labels = load_labels(labels_in)
    print(f"Loaded: {len(labels)} labels from {labels_in}")
    
    # Create transform
    transform = get_batch_augmentation_transforms()
    
    # Statistics
    report = {
        'total_input': len(labels),
        'total_output': 0,
        'processed': 0,
        'skipped': 0,
        'errors': []
    }
    
    output_labels = []
    
    for idx, record in enumerate(labels):
        image_name = record['image']
        corners = record['corners']
        
        # Image path
        image_path = images_in / image_name
        if not image_path.exists():
            print(f"Warning: Image not found: {image_path}")
            report['skipped'] += 1
            report['errors'].append(f"missing_image:{image_name}")
            continue
        
        # Load image
        image = cv2.imread(str(image_path))
        if image is None:
            print(f"Warning: Image could not be loaded: {image_path}")
            report['skipped'] += 1
            report['errors'].append(f"load_failed:{image_name}")
            continue
        
        # BGR to RGB
        image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        
        # Corners to numpy array
        corners_array = np.array(corners, dtype=np.float32)
        
        # Filename without extension
        stem = Path(image_name).stem
        suffix = Path(image_name).suffix
        
        # Copy original (if desired)
        if include_original:
            out_name = f"{stem}_orig{suffix}"
            out_path = images_out / out_name
            # RGB back to BGR for cv2.imwrite
            cv2.imwrite(str(out_path), cv2.cvtColor(image, cv2.COLOR_RGB2BGR))
            output_labels.append({
                'image': out_name,
                'corners': corners
            })
            report['total_output'] += 1
        
        # Augmentations with retry loop and canonicalization
        for aug_idx in range(num_augmentations):
            max_retries = 5
            success = False
            
            for retry in range(max_retries):
                try:
                    keypoints = corners_to_keypoints(corners_array)
                    result = transform(image=image, keypoints=keypoints)
                    
                    aug_image = result['image']
                    aug_keypoints = result['keypoints']
                    
                    # Keypoints to array
                    aug_corners_raw = np.array(aug_keypoints, dtype=np.float32)
                    
                    # CRITICAL: Enforce canonical order [TL, TR, BR, BL]
                    aug_corners_canonical = canonicalize_corners_xy(aug_corners_raw)
                    
                    # Validation
                    h, w = aug_image.shape[:2]
                    if not is_valid_quad(aug_corners_canonical, w, h, min_area_ratio=0.01):
                        if retry < max_retries - 1:
                            continue  # Retry
                        else:
                            print(f"Warning: {image_name} aug{aug_idx} invalid after {max_retries} attempts")
                            report['errors'].append(f"invalid_quad:{image_name}:{aug_idx}")
                            break
                    
                    # Corners as list for JSON
                    aug_corners = aug_corners_canonical.tolist()
                    
                    # Save
                    out_name = f"{stem}_aug{aug_idx:02d}{suffix}"
                    out_path = images_out / out_name
                    # RGB back to BGR for cv2.imwrite
                    cv2.imwrite(str(out_path), cv2.cvtColor(aug_image, cv2.COLOR_RGB2BGR))
                    
                    output_labels.append({
                        'image': out_name,
                        'corners': aug_corners
                    })
                    report['total_output'] += 1
                    success = True
                    break
                    
                except Exception as e:
                    if retry == max_retries - 1:
                        print(f"Warning: Augmentation {aug_idx} for {image_name} failed: {e}")
                        report['errors'].append(f"augmentation_failed:{image_name}:{aug_idx}:{str(e)}")
        
        report['processed'] += 1
        
        # Progress
        if (idx + 1) % 100 == 0 or idx == len(labels) - 1:
            print(f"Progress: {idx + 1}/{len(labels)} images processed")
    
    # Save labels
    save_labels(output_labels, labels_out)
    print(f"Saved: {len(output_labels)} labels to {labels_out}")
    
    return report


# =============================================================================
# Keypoint Handling Utilities
# =============================================================================

def corners_to_keypoints(corners: np.ndarray) -> List[Tuple[float, float]]:
    """
    Converts corners array to keypoints list.

    Args:
        corners: Array with shape (4, 2) or (8,) - [TL, TR, BR, BL]

    Returns:
        List of (x, y) tuples
    """
    if corners.shape == (8,):
        corners = corners.reshape(4, 2)

    return [(float(x), float(y)) for x, y in corners]


def keypoints_to_corners(keypoints: List[Tuple[float, float]]) -> np.ndarray:
    """
    Converts keypoints list to corners array.

    Args:
        keypoints: List of (x, y) tuples

    Returns:
        Array with shape (4, 2)
    """
    return np.array(keypoints, dtype=np.float32)


def canonicalize_corners_xy(corners: np.ndarray) -> np.ndarray:
    """
    Bringt 4 Ecken in kanonische Reihenfolge: [TL, TR, BR, BL] (clockwise ab TL).
    
    WICHTIG: Diese Funktion muss nach JEDER geometrischen Augmentierung aufgerufen
    werden, da Albumentations nur die Positionen transformiert, nicht die Semantik.
    
    Algorithmus:
    1. Schwerpunkt berechnen
    2. Nach Winkel sortieren (clockwise)
    3. Rotieren, sodass TL (min x+y) zuerst kommt
    
    Args:
        corners: Array mit Shape (4, 2) oder Liste von (x, y) Tupeln
        
    Returns:
        Kanonisch geordnete Ecken mit Shape (4, 2) in Reihenfolge [TL, TR, BR, BL]
    """
    import math
    
    corners = np.array(corners, dtype=np.float32)
    if corners.shape != (4, 2):
        corners = corners.reshape(4, 2)
    
    # Centroid
    cx = corners[:, 0].mean()
    cy = corners[:, 1].mean()
    
    # Calculate angles (atan2 returns -π to π)
    # In image coordinates (y grows downward):
    # - top: angle ≈ -π/2
    # - right: angle ≈ 0
    # - bottom: angle ≈ +π/2
    # - left: angle ≈ ±π
    angles = np.array([math.atan2(p[1] - cy, p[0] - cx) for p in corners])
    
    # Sort clockwise (ASCENDING by angle in image coordinates)
    # Order: top(-π/2) → right(0) → bottom(+π/2) → left(+π)
    # This gives TL → TR → BR → BL (clockwise)
    order = np.argsort(angles)
    sorted_corners = corners[order]
    
    # Find TL: minimum (x + y), on tie: min y, then min x
    def tl_key(idx):
        p = sorted_corners[idx]
        return (p[0] + p[1], p[1], p[0])
    
    tl_idx = min(range(4), key=tl_key)
    
    # Rotate so TL is at position 0
    result = np.roll(sorted_corners, -tl_idx, axis=0)
    
    return result.astype(np.float32)


def is_valid_quad(
    corners: np.ndarray,
    img_width: int,
    img_height: int,
    min_area_ratio: float = 0.01,
    margin: float = 0.0
) -> bool:
    """
    Checks if the corners form a valid convex quadrilateral.
    
    Checks:
    1. All corners within image bounds (with optional margin)
    2. Minimum area (relative to image area)
    3. Convexity (all angles in same direction)
    4. No self-intersection (non-adjacent edges don't intersect)
    
    Args:
        corners: Array with shape (4, 2) in order [TL, TR, BR, BL]
        img_width: Image width
        img_height: Image height
        min_area_ratio: Minimum area ratio to image (default: 1%)
        margin: Allowed margin outside image in pixels (default: 0)
        
    Returns:
        True if valid, False otherwise
    """
    corners = np.array(corners, dtype=np.float32)
    if corners.shape != (4, 2):
        return False
    
    # 1. Bounds check
    if np.any(corners[:, 0] < -margin) or np.any(corners[:, 0] >= img_width + margin):
        return False
    if np.any(corners[:, 1] < -margin) or np.any(corners[:, 1] >= img_height + margin):
        return False
    
    # 2. Area (Shoelace formula)
    n = 4
    area = 0.0
    for i in range(n):
        j = (i + 1) % n
        area += corners[i, 0] * corners[j, 1]
        area -= corners[j, 0] * corners[i, 1]
    area = abs(area) / 2.0
    
    min_area = img_width * img_height * min_area_ratio
    if area < min_area:
        return False
    
    # 3. Convexity (all cross products have same sign)
    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    
    signs = []
    for i in range(4):
        o = corners[i]
        a = corners[(i + 1) % 4]
        b = corners[(i + 2) % 4]
        c = cross(o, a, b)
        if abs(c) > 1e-9:
            signs.append(1 if c > 0 else -1)
    
    if not signs or not all(s == signs[0] for s in signs):
        return False
    
    # 4. Self-intersection (non-adjacent edges)
    def segments_intersect(p1, p2, p3, p4):
        d1 = cross(p3, p4, p1)
        d2 = cross(p3, p4, p2)
        d3 = cross(p1, p2, p3)
        d4 = cross(p1, p2, p4)
        
        if ((d1 > 0 and d2 < 0) or (d1 < 0 and d2 > 0)) and \
           ((d3 > 0 and d4 < 0) or (d3 < 0 and d4 > 0)):
            return True
        return False
    
    # Edge 0-1 with 2-3, edge 1-2 with 3-0
    if segments_intersect(corners[0], corners[1], corners[2], corners[3]):
        return False
    if segments_intersect(corners[1], corners[2], corners[3], corners[0]):
        return False
    
    return True


def validate_corners(
    corners: np.ndarray,
    img_size: int,
    min_area_ratio: float = 0.01
) -> bool:
    """
    Validates whether the corners form a valid quadrilateral.

    Args:
        corners: Array with shape (4, 2)
        img_size: Image size
        min_area_ratio: Minimum area ratio to image

    Returns:
        True if valid, False otherwise
    """
    # All corners in image?
    if np.any(corners < 0) or np.any(corners >= img_size):
        return False

    # Calculate area (Shoelace formula)
    n = len(corners)
    area = 0.0
    for i in range(n):
        j = (i + 1) % n
        area += corners[i, 0] * corners[j, 1]
        area -= corners[j, 0] * corners[i, 1]
    area = abs(area) / 2.0

    # Check minimum area
    min_area = img_size * img_size * min_area_ratio
    if area < min_area:
        return False

    # Check convexity (simplified)
    # TODO: Complete convexity check

    return True


# =============================================================================
# Heatmap Generation
# =============================================================================

def generate_heatmap(
    corners: np.ndarray,
    heatmap_size: int = 128,
    sigma: float = 2.0
) -> np.ndarray:
    """
    Generates Gaussian heatmaps for the corners.

    Args:
        corners: Array with shape (4, 2) - corners in image coordinates
        heatmap_size: Size of the heatmap
        sigma: Standard deviation of the Gaussian

    Returns:
        Heatmaps with shape (4, heatmap_size, heatmap_size)
    """
    heatmaps = np.zeros((4, heatmap_size, heatmap_size), dtype=np.float32)

    for i, (x, y) in enumerate(corners):
        # Scale to heatmap size
        hm_x = x * heatmap_size / 256  # Assumption: Input is 256x256
        hm_y = y * heatmap_size / 256

        # Generate Gaussian
        for hy in range(heatmap_size):
            for hx in range(heatmap_size):
                dist_sq = (hx - hm_x) ** 2 + (hy - hm_y) ** 2
                heatmaps[i, hy, hx] = np.exp(-dist_sq / (2 * sigma ** 2))

    return heatmaps


def generate_heatmap_fast(
    corners: np.ndarray,
    heatmap_size: int = 128,
    img_size: int = 256,
    sigma: float = 2.0
) -> np.ndarray:
    """
    Fast heatmap generation with vectorization.

    Args:
        corners: Array with shape (4, 2) - corners in image coordinates
        heatmap_size: Size of the heatmap
        img_size: Size of the input image
        sigma: Standard deviation of the Gaussian

    Returns:
        Heatmaps with shape (4, heatmap_size, heatmap_size)
    """
    heatmaps = np.zeros((4, heatmap_size, heatmap_size), dtype=np.float32)

    # Create grid
    y_grid, x_grid = np.mgrid[0:heatmap_size, 0:heatmap_size]

    scale = heatmap_size / img_size

    for i, (x, y) in enumerate(corners):
        # Scale to heatmap size
        hm_x = x * scale
        hm_y = y * scale

        # Calculate Gaussian (vectorized)
        dist_sq = (x_grid - hm_x) ** 2 + (y_grid - hm_y) ** 2
        heatmaps[i] = np.exp(-dist_sq / (2 * sigma ** 2))

    return heatmaps


# =============================================================================
# Dataset Utilities
# =============================================================================

class AugmentedDataset:
    """
    Wrapper for datasets with Albumentations augmentation.

    Example:
        dataset = AugmentedDataset(
            images=images,
            corners=corners,
            transform=get_train_transforms()
        )
    """

    def __init__(
        self,
        images: List[np.ndarray],
        corners: List[np.ndarray],
        transform: A.Compose,
        heatmap_size: int = 128,
        sigma: float = 2.0
    ):
        self.images = images
        self.corners = corners
        self.transform = transform
        self.heatmap_size = heatmap_size
        self.sigma = sigma

    def __len__(self) -> int:
        return len(self.images)

    def __getitem__(self, idx: int) -> Dict[str, Any]:
        image = self.images[idx]
        corners = self.corners[idx]

        # Keypoints for Albumentations
        keypoints = corners_to_keypoints(corners)

        # Apply augmentation
        result = self.transform(image=image, keypoints=keypoints)

        augmented_image = result['image']
        augmented_keypoints = result['keypoints']

        # Back to array
        augmented_corners = keypoints_to_corners(augmented_keypoints)

        # Safety net: geometric transforms only move keypoints, they do not
        # preserve the [TL, TR, BR, BL] semantics. Re-canonicalize so the
        # heatmap channels always match the corner order.
        augmented_corners = canonicalize_corners_xy(augmented_corners)

        # Generate heatmaps
        heatmaps = generate_heatmap_fast(
            augmented_corners,
            self.heatmap_size,
            img_size=augmented_image.shape[-1],  # After ToTensorV2: (C, H, W)
            sigma=self.sigma
        )

        return {
            'image': augmented_image,
            'heatmaps': heatmaps,
            'corners': augmented_corners
        }


# =============================================================================
# CLI Interface
# =============================================================================

def run_test():
    """Runs the original tests."""
    print("=" * 60)
    print("Augmentation Pipeline Test")
    print("=" * 60)

    if not ALBUMENTATIONS_AVAILABLE:
        print("ERROR: albumentations not installed!")
        print("Install with: pip install albumentations")
        return 1

    import cv2

    # Create dummy image
    img_size = 256
    image = np.random.randint(0, 255, (img_size, img_size, 3), dtype=np.uint8)

    # Dummy corners (rectangle in the center)
    corners = np.array([
        [50, 50],    # TL
        [200, 50],   # TR
        [200, 200],  # BR
        [50, 200]    # BL
    ], dtype=np.float32)

    print(f"\nOriginal corners:\n{corners}")

    # Test train transform
    print("\n--- Train Transform ---")
    train_transform = get_train_transforms(img_size=256)

    keypoints = corners_to_keypoints(corners)
    result = train_transform(image=image, keypoints=keypoints)

    print(f"Input image shape: {image.shape}")
    print(f"Output tensor shape: {result['image'].shape}")
    print(f"Output keypoints: {result['keypoints']}")

    # Test val transform
    print("\n--- Val Transform ---")
    val_transform = get_val_transforms(img_size=256)

    result = val_transform(image=image, keypoints=keypoints)

    print(f"Output tensor shape: {result['image'].shape}")
    print(f"Output keypoints: {result['keypoints']}")

    # Test heatmap generation
    print("\n--- Heatmap Generation ---")
    heatmaps = generate_heatmap_fast(corners, heatmap_size=128, img_size=256)
    print(f"Heatmap shape: {heatmaps.shape}")
    print(f"Heatmap max values: {[hm.max() for hm in heatmaps]}")

    # Test strong augmentation
    print("\n--- Strong Augmentation ---")
    strong_transform = get_strong_augmentation(img_size=256)
    result = strong_transform(image=image, keypoints=keypoints)
    print(f"Output tensor shape: {result['image'].shape}")

    print("\n" + "=" * 60)
    print("Test completed successfully!")
    print("=" * 60)
    return 0


def main():
    """CLI main function."""
    parser = argparse.ArgumentParser(
        description='Augmentation of images and labels for document corner detection',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Batch augmentation
  python augmentation.py \\
    --labels_in labels/my_data.jsonl \\
    --images_in data/my_data \\
    --labels_out labels/my_data_augmented.jsonl \\
    --images_out data/my_data_augmented \\
    --num_augmentations 5

  # Run tests only
  python augmentation.py --test
"""
    )
    
    # Batch processing arguments
    parser.add_argument(
        '--labels_in',
        type=Path,
        help='Path to input JSONL file with labels'
    )
    parser.add_argument(
        '--images_in',
        type=Path,
        help='Path to input image directory'
    )
    parser.add_argument(
        '--labels_out',
        type=Path,
        help='Path to output JSONL file for augmented labels'
    )
    parser.add_argument(
        '--images_out',
        type=Path,
        help='Path to output image directory for augmented images'
    )
    parser.add_argument(
        '--num_augmentations', '-n',
        type=int,
        default=5,
        help='Number of augmentations per image (default: 5)'
    )
    parser.add_argument(
        '--no_original',
        action='store_true',
        help='Do not copy original images to output'
    )
    parser.add_argument(
        '--seed',
        type=int,
        default=None,
        help='Random seed for reproducibility'
    )
    
    # Test mode
    parser.add_argument(
        '--test',
        action='store_true',
        help='Run the augmentation tests'
    )
    
    args = parser.parse_args()
    
    # Test mode
    if args.test:
        return run_test()
    
    # Batch processing
    if args.labels_in and args.images_in and args.labels_out and args.images_out:
        # Validation
        if not args.labels_in.exists():
            print(f"ERROR: Labels file not found: {args.labels_in}")
            return 1
        if not args.images_in.exists():
            print(f"ERROR: Image directory not found: {args.images_in}")
            return 1
        
        print("=" * 60)
        print("Batch Augmentation")
        print("=" * 60)
        print(f"Labels Input:  {args.labels_in}")
        print(f"Images Input:  {args.images_in}")
        print(f"Labels Output: {args.labels_out}")
        print(f"Images Output: {args.images_out}")
        print(f"Augmentations per image: {args.num_augmentations}")
        print(f"Copy original: {not args.no_original}")
        if args.seed is not None:
            print(f"Random Seed: {args.seed}")
        print("=" * 60)
        
        report = process_batch(
            labels_in=args.labels_in,
            images_in=args.images_in,
            labels_out=args.labels_out,
            images_out=args.images_out,
            num_augmentations=args.num_augmentations,
            include_original=not args.no_original,
            seed=args.seed
        )
        
        print("\n" + "=" * 60)
        print("Summary")
        print("=" * 60)
        print(f"Input images:    {report['total_input']}")
        print(f"Processed:       {report['processed']}")
        print(f"Skipped:         {report['skipped']}")
        print(f"Output images:   {report['total_output']}")
        if report['errors']:
            print(f"Errors:          {len(report['errors'])}")
        print("=" * 60)
        
        return 0
    
    # No valid arguments
    parser.print_help()
    return 1


if __name__ == '__main__':
    exit(main())
