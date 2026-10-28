#!/usr/bin/env python3
"""convert_smartdoc.py

Convert the SmartDoc 2015 Challenge 1 dataset into `labels.jsonl` format.

The SmartDoc 2015 dataset contains:
- `background01-05/*/frame_*.jpeg` - video frames
- `metadata.csv.gz` - ground truth with corner coordinates

This script converts the coordinates into the `labels.jsonl` format used for training.

Usage:
    python convert_smartdoc.py \
        --smartdoc data/smartdoc15/frames \
        --out labels/smartdoc_labels.jsonl

    # With image copying:
    python convert_smartdoc.py \
        --smartdoc data/smartdoc15/frames \
        --out labels/smartdoc_labels.jsonl \
        --copy-images \
        --images-dir data/smartdoc

    # Only certain backgrounds:
    python convert_smartdoc.py \
        --smartdoc data/smartdoc15/frames \
        --out labels/smartdoc_labels.jsonl \
        --backgrounds background01 background02

    # Use every n-th frame (to reduce the dataset):
    python convert_smartdoc.py \
        --smartdoc data/smartdoc15/frames \
        --out labels/smartdoc_labels.jsonl \
        --every-nth 5
"""

import os
import json
import argparse
import gzip
import csv
import shutil
from typing import List, Tuple, Optional
import numpy as np


def sort_corners_clockwise(pts: np.ndarray) -> np.ndarray:
    """
    Sort corners in clockwise order starting at top-left:
      0=TL, 1=TR, 2=BR, 3=BL
    
    This matches the expected order in MakeACopy/ImageOps.java.
    """
    pts = np.asarray(pts, dtype=np.float32).reshape(4, 2)
    
    # Step 1: find top-left (smallest x+y sum)
    s = pts[:, 0] + pts[:, 1]
    tl_idx = int(np.argmin(s))
    
    # Step 2: find bottom-right (largest x+y sum)
    br_idx = int(np.argmax(s))
    
    # Step 3: remaining two points are TR and BL
    # TR has larger x, BL has larger y
    remaining = [i for i in range(4) if i not in [tl_idx, br_idx]]
    
    if pts[remaining[0], 0] > pts[remaining[1], 0]:
        tr_idx, bl_idx = remaining[0], remaining[1]
    else:
        tr_idx, bl_idx = remaining[1], remaining[0]
    
    # Order: TL(0), TR(1), BR(2), BL(3) clockwise
    return pts[[tl_idx, tr_idx, br_idx, bl_idx]]


def convert_smartdoc_to_labels(
    smartdoc_dir: str,
    output_path: str,
    copy_images: bool = False,
    images_out_dir: Optional[str] = None,
    backgrounds: Optional[List[str]] = None,
    every_nth: int = 1
) -> Tuple[int, int]:
    """
    Convert SmartDoc 2015 dataset into `labels.jsonl` format.
    
    Args:
        smartdoc_dir: path to the SmartDoc frames folder
        output_path: output path for labels.jsonl
        copy_images: if True, copy images into `images_out_dir`
        images_out_dir: target directory for images (when `copy_images=True`)
        backgrounds: list of backgrounds to use (None = all)
        every_nth: use only every n-th frame (1 = all)
    
    Returns:
        (successful, skipped) conversions
    """
    metadata_path = os.path.join(smartdoc_dir, 'metadata.csv.gz')
    
    if not os.path.exists(metadata_path):
        raise FileNotFoundError(f"Metadata file not found: {metadata_path}")
    
    # Load metadata
    print(f"Loading metadata from {metadata_path}...")
    with gzip.open(metadata_path, 'rt', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    
    print(f"Found: {len(rows)} entries in metadata.csv")
    
    # Filter by background
    if backgrounds:
        rows = [r for r in rows if r['bg_name'] in backgrounds]
        print(f"After background filter: {len(rows)} entries")
    
    # Filter by every_nth
    if every_nth > 1:
        rows = [r for i, r in enumerate(rows) if i % every_nth == 0]
        print(f"After every-nth filter ({every_nth}): {len(rows)} entries")
    
    labels = []
    success = 0
    skipped = 0
    
    for row in rows:
        image_path = os.path.join(smartdoc_dir, row['image_path'])
        
        if not os.path.exists(image_path):
            print(f"  ⚠ Image not found: {row['image_path']}")
            skipped += 1
            continue
        
        try:
            # Extract corners from CSV (already in pixel coordinates)
            # Format: tl, bl, br, tr -> we need tl, tr, br, bl
            tl = [float(row['tl_x']), float(row['tl_y'])]
            bl = [float(row['bl_x']), float(row['bl_y'])]
            br = [float(row['br_x']), float(row['br_y'])]
            tr = [float(row['tr_x']), float(row['tr_y'])]
            
            # Corners as array
            corners = np.array([tl, bl, br, tr], dtype=np.float32)
            
            # Sort clockwise (TL, TR, BR, BL)
            corners_sorted = sort_corners_clockwise(corners)
            
            # Unique image name (bg_model_frame)
            bg = row['bg_name']
            model = row['model_name']
            frame_idx = row['frame_index']
            rel_image_name = f"{bg}_{model}_frame_{frame_idx:0>4}.jpeg"
            
            # Create label
            label = {
                "image": rel_image_name,
                "corners": corners_sorted.tolist()
            }
            labels.append(label)
            
            # Optional: Copy image
            if copy_images and images_out_dir:
                os.makedirs(images_out_dir, exist_ok=True)
                dst_path = os.path.join(images_out_dir, rel_image_name)
                if not os.path.exists(dst_path):
                    shutil.copy2(image_path, dst_path)
            
            success += 1
            
            if success % 1000 == 0:
                print(f"  Processed: {success}/{len(rows)}")
            
        except Exception as e:
            print(f"  ⚠ Error at {row['image_path']}: {e}")
            skipped += 1
    
    # Save labels
    os.makedirs(os.path.dirname(os.path.abspath(output_path)) or '.', exist_ok=True)
    with open(output_path, 'w', encoding='utf-8') as f:
        for label in labels:
            f.write(json.dumps(label, ensure_ascii=False) + '\n')
    
    print(f"\n✓ Labels saved: {output_path}")
    print(f"  Successful: {success}")
    print(f"  Skipped: {skipped}")
    
    return success, skipped


def main():
    parser = argparse.ArgumentParser(
        description='Converts SmartDoc 2015 Dataset to labels.jsonl format'
    )
    parser.add_argument('--smartdoc', required=True,
                       help='Path to SmartDoc frames folder')
    parser.add_argument('--out', required=True,
                       help='Output path for labels.jsonl')
    parser.add_argument('--copy-images', action='store_true',
                       help='Copy images to target folder')
    parser.add_argument('--images-dir', default=None,
                       help='Target folder for images (when --copy-images)')
    parser.add_argument('--backgrounds', nargs='+', default=None,
                       help='Use only specific backgrounds (e.g. background01 background02)')
    parser.add_argument('--every-nth', type=int, default=1,
                       help='Use only every n-th frame (default: 1 = all)')
    args = parser.parse_args()
    
    print(f"Converting SmartDoc 2015 → {args.out}")
    print(f"SmartDoc path: {args.smartdoc}")
    if args.backgrounds:
        print(f"Backgrounds: {', '.join(args.backgrounds)}")
    if args.every_nth > 1:
        print(f"Every {args.every_nth}. frame")
    print()
    
    convert_smartdoc_to_labels(
        smartdoc_dir=args.smartdoc,
        output_path=args.out,
        copy_images=args.copy_images,
        images_out_dir=args.images_dir,
        backgrounds=args.backgrounds,
        every_nth=args.every_nth
    )


if __name__ == '__main__':
    main()
