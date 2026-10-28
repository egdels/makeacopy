#!/usr/bin/env python3
"""convert_uvdoc.py

Convert the UVDoc dataset into this repo's flat `labels.jsonl` format.

The UVDoc final dataset contains:
- `img/*.png` - images (~20k samples)
- `metadata_sample/*.json` - metadata with a `geom_name` reference
- `grid2d/*.mat` - 2D grids with document corner geometry (HDF5 format)

Corner extraction from `grid2d`:
- `grid2d` has shape `(2, H, W)` where `2 = (x, y)` coordinates
- corners are: `[0,0]=TL`, `[0,-1]=TR`, `[-1,-1]=BR`, `[-1,0]=BL`

Usage:
    python convert_uvdoc.py \
        --uvdoc data/UVDoc/UVDoc_final \
        --out labels/uvdoc_labels.jsonl \
        --copy-images \
        --images-dir data/uvdoc_images

    # Convert only the first N samples (for quick testing):
    python convert_uvdoc.py \
        --uvdoc data/UVDoc/UVDoc_final \
        --out labels/uvdoc_test.jsonl \
        --limit 100
"""

import os
import json
import argparse
import shutil
from typing import List, Tuple, Optional
import numpy as np

try:
    import h5py
except ImportError:
    print("Error: h5py is not installed. Please install it with: pip install h5py")
    exit(1)


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


def extract_corners_from_grid2d(grid2d_path: str) -> np.ndarray:
    """
    Extract the 4 document corners from a `grid2d` .mat file.
    
    Args:
        grid2d_path: path to the .mat file (HDF5 format)
    
    Returns:
        np.ndarray with shape (4, 2) - the 4 corners as (x, y) coordinates
    """
    with h5py.File(grid2d_path, 'r') as f:
        # grid2d has shape (2, H, W) where 2 = (x, y)
        grid = f['grid2d'][:]
    
    # Extract corners
    # grid[:, row, col] returns [x, y] for position (row, col)
    # TL = [0, 0], TR = [0, -1], BR = [-1, -1], BL = [-1, 0]
    corners = np.array([
        grid[:, 0, 0],      # Top-Left
        grid[:, 0, -1],     # Top-Right
        grid[:, -1, -1],    # Bottom-Right
        grid[:, -1, 0],     # Bottom-Left
    ], dtype=np.float32)
    
    return corners


def convert_uvdoc_to_labels(
    uvdoc_dir: str,
    output_path: str,
    copy_images: bool = False,
    images_out_dir: Optional[str] = None,
    limit: Optional[int] = None,
    filter_perspective_only: bool = False,
    filter_curved_only: bool = False,
    filter_folded_only: bool = False,
    filter_crumpled_only: bool = False,
    verbose: bool = False
) -> Tuple[int, int, int]:
    """
    Convert UVDoc dataset into `labels.jsonl` format.
    
    Args:
        uvdoc_dir: path to the UVDoc root folder
        output_path: output path for the JSONL file
        copy_images: if True, copy images into `images_out_dir`
        images_out_dir: target directory for images (when `copy_images=True`)
        limit: maximum number of samples to convert (None = all)
        filter_perspective_only: if True, only samples with Perspective=True (no Curved/Folded/Crumpled)
        filter_curved_only: if True, only samples with Curved=True (no Folded/Crumpled)
        filter_folded_only: if True, only samples with Folded=True
        filter_crumpled_only: if True, only samples with Crumpled=True
        verbose: verbose output
    
    Returns:
        (successful, skipped, failed) conversions
    """
    img_dir = os.path.join(uvdoc_dir, 'img')
    metadata_dir = os.path.join(uvdoc_dir, 'metadata_sample')
    metadata_geom_dir = os.path.join(uvdoc_dir, 'metadata_geom')
    grid2d_dir = os.path.join(uvdoc_dir, 'grid2d')
    
    # Filter flags are mutually exclusive ("only" means exactly one category)
    only_filters = [
        ("perspective", filter_perspective_only),
        ("curved", filter_curved_only),
        ("folded", filter_folded_only),
        ("crumpled", filter_crumpled_only),
    ]
    enabled = [name for name, enabled in only_filters if enabled]
    if len(enabled) > 1:
        raise ValueError(
            "Only one filter may be active: --perspective-only, --curved-only, --folded-only, --crumpled-only "
            f"(active: {', '.join(enabled)})"
        )

    # Validate directories
    for d, name in [(img_dir, 'img'), (metadata_dir, 'metadata_sample'), (grid2d_dir, 'grid2d')]:
        if not os.path.exists(d):
            raise FileNotFoundError(f"{name} directory not found: {d}")
    
    # Find all metadata files
    metadata_files = sorted([f for f in os.listdir(metadata_dir) if f.endswith('.json')])
    
    if limit:
        metadata_files = metadata_files[:limit]
    
    print(f"Processing {len(metadata_files)} samples from {uvdoc_dir}")
    
    labels = []
    success = 0
    skipped = 0
    failed = 0
    
    for i, meta_file in enumerate(metadata_files):
        sample_id = os.path.splitext(meta_file)[0]
        
        try:
            # Load metadata
            meta_path = os.path.join(metadata_dir, meta_file)
            with open(meta_path, 'r') as f:
                meta = json.load(f)
            
            geom_name = meta['geom_name']
            
            # Optional: only perspective samples (flat documents)
            if filter_perspective_only:
                geom_meta_path = os.path.join(metadata_geom_dir, f"{geom_name}.json")
                if os.path.exists(geom_meta_path):
                    with open(geom_meta_path, 'r') as f:
                        geom_meta = json.load(f)
                    sample_info = geom_meta.get('sample_info', {})
                    # Only Perspective=True and no other deformations
                    if not sample_info.get('Perspective', False):
                        skipped += 1
                        continue
                    if sample_info.get('Curved', False) or sample_info.get('Folded', False) or sample_info.get('Crumpled', False):
                        skipped += 1
                        continue
            
            # Optional: only Curved samples
            if filter_curved_only:
                geom_meta_path = os.path.join(metadata_geom_dir, f"{geom_name}.json")
                if os.path.exists(geom_meta_path):
                    with open(geom_meta_path, 'r') as f:
                        geom_meta = json.load(f)
                    sample_info = geom_meta.get('sample_info', {})
                    # Only Curved=True and no Folded/Crumpled
                    if not sample_info.get('Curved', False):
                        skipped += 1
                        continue
                    if sample_info.get('Folded', False) or sample_info.get('Crumpled', False):
                        skipped += 1
                        continue

            # Optional: only Folded samples
            if filter_folded_only:
                geom_meta_path = os.path.join(metadata_geom_dir, f"{geom_name}.json")
                if os.path.exists(geom_meta_path):
                    with open(geom_meta_path, 'r') as f:
                        geom_meta = json.load(f)
                    sample_info = geom_meta.get('sample_info', {})
                    # Only Folded=True
                    if not sample_info.get('Folded', False):
                        skipped += 1
                        continue
                else:
                    # Without metadata_geom we cannot reliably determine Folded → skip
                    skipped += 1
                    continue

            # Optional: only Crumpled samples
            if filter_crumpled_only:
                geom_meta_path = os.path.join(metadata_geom_dir, f"{geom_name}.json")
                if os.path.exists(geom_meta_path):
                    with open(geom_meta_path, 'r') as f:
                        geom_meta = json.load(f)
                    sample_info = geom_meta.get('sample_info', {})
                    # Only Crumpled=True
                    if not sample_info.get('Crumpled', False):
                        skipped += 1
                        continue
                else:
                    # Without metadata_geom we cannot reliably determine Crumpled → skip
                    skipped += 1
                    continue
            
            # Image path
            img_file = f"{sample_id}.png"
            img_path = os.path.join(img_dir, img_file)
            
            if not os.path.exists(img_path):
                if verbose:
                    print(f"  ⚠ Image not found: {img_file}")
                failed += 1
                continue
            
            # Load Grid2D and extract corners
            grid2d_path = os.path.join(grid2d_dir, f"{geom_name}.mat")
            if not os.path.exists(grid2d_path):
                if verbose:
                    print(f"  ⚠ Grid2D not found: {geom_name}.mat")
                failed += 1
                continue
            
            corners = extract_corners_from_grid2d(grid2d_path)
            
            # Sort corners (should already be correct, but for safety)
            corners_sorted = sort_corners_clockwise(corners)
            
            # Output image name
            out_img_name = f"uvdoc_{sample_id}.png"
            
            # Create label
            label = {
                "image": out_img_name,
                "corners": corners_sorted.tolist()
            }
            labels.append(label)
            
            # Optional: copy image
            if copy_images and images_out_dir:
                os.makedirs(images_out_dir, exist_ok=True)
                dst_path = os.path.join(images_out_dir, out_img_name)
                if not os.path.exists(dst_path):
                    shutil.copy2(img_path, dst_path)
            
            success += 1
            
            # Progress output
            if (i + 1) % 1000 == 0:
                print(f"  Processed: {i + 1}/{len(metadata_files)}")
            
        except Exception as e:
            if verbose:
                print(f"  ⚠ Error for {sample_id}: {e}")
            failed += 1
    
    # Write labels
    os.makedirs(os.path.dirname(os.path.abspath(output_path)) or '.', exist_ok=True)
    with open(output_path, 'w', encoding='utf-8') as f:
        for label in labels:
            f.write(json.dumps(label, ensure_ascii=False) + '\n')
    
    print(f"\n✓ Labels written: {output_path}")
    print(f"  Successful: {success}")
    print(f"  Skipped: {skipped}")
    print(f"  Failed: {failed}")
    
    return success, skipped, failed


def main():
    parser = argparse.ArgumentParser(
        description='Convert UVDoc dataset to labels.jsonl format for MakeACopy'
    )
    parser.add_argument('--uvdoc', required=True,
                       help='Path to the UVDoc_final folder')
    parser.add_argument('--out', required=True,
                       help='Output path for labels.jsonl')
    parser.add_argument('--copy-images', action='store_true',
                       help='Copy images to the target directory')
    parser.add_argument('--images-dir', default=None,
                       help='Target directory for images (when --copy-images)')
    parser.add_argument('--limit', type=int, default=None,
                       help='Maximum number of samples to convert')
    parser.add_argument('--perspective-only', action='store_true',
                       help='Only flat documents (Perspective=True, no Curved/Folded/Crumpled)')
    parser.add_argument('--curved-only', action='store_true',
                       help='Only curved documents (Curved=True, no Folded/Crumpled)')
    parser.add_argument('--folded-only', action='store_true',
                       help='Only folded documents (Folded=True)')
    parser.add_argument('--crumpled-only', action='store_true',
                       help='Only crumpled documents (Crumpled=True)')
    parser.add_argument('--verbose', '-v', action='store_true',
                       help='Verbose output')
    args = parser.parse_args()
    
    print(f"Converting UVDoc → {args.out}")
    print(f"UVDoc path: {args.uvdoc}")
    if args.perspective_only:
        print("Filter: only flat documents (Perspective)")
    if args.curved_only:
        print("Filter: only curved documents (Curved)")
    if args.folded_only:
        print("Filter: only folded documents (Folded)")
    if args.crumpled_only:
        print("Filter: only crumpled documents (Crumpled)")
    if args.limit:
        print(f"Limit: {args.limit} samples")
    print()
    
    convert_uvdoc_to_labels(
        uvdoc_dir=args.uvdoc,
        output_path=args.out,
        copy_images=args.copy_images,
        images_out_dir=args.images_dir,
        limit=args.limit,
        filter_perspective_only=args.perspective_only,
        filter_curved_only=args.curved_only,
        filter_folded_only=args.folded_only,
        filter_crumpled_only=args.crumpled_only,
        verbose=args.verbose
    )


if __name__ == '__main__':
    main()
