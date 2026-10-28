/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.crop;

import androidx.annotation.Nullable;

/**
 * Aspect ratio choices for the crop step (stage B, see {@code
 * docs/aspect_ratio_concept_v3.8.0.md}).
 *
 * <p>Deliberately separated from {@code de.schliweb.makeacopy.utils.export.PageFormat} (PDF page
 * size with letterboxing). This enum models the geometry that is enforced at warp time, before any
 * export step.
 *
 * <p>{@link #AUTO} keeps stage A (projective estimate inside {@code
 * ImageOps.computeWarpTargetSize}) and is the default. {@link #ORIGINAL} forces the legacy
 * pixel-distance heuristic. The fixed entries map to a concrete short/long ratio. {@link #CUSTOM}
 * pulls its actual ratio from {@link CropPrefsHelper}.
 */
public enum CropAspectRatio {
  /** Stage A: projective aspect-ratio estimate (Zhang & He, 2006). Default. */
  AUTO,
  /** Force the legacy pixel-distance heuristic (no projective correction). */
  ORIGINAL,
  A3,
  /** DIN A series, 1 : sqrt(2). */
  A4,
  A5,
  /** US Letter, 8.5 : 11. */
  US_LETTER,
  /** US Legal, 8.5 : 14. */
  LEGAL,
  /** User-defined ratio; the actual numbers are stored via {@link CropPrefsHelper}. */
  CUSTOM;

  private static final double DIN_A = 1.0 / Math.sqrt(2.0); // ≈ 0.7071067811865476
  private static final double LETTER = 8.5 / 11.0; // ≈ 0.7727272727272727
  private static final double LEGAL_R = 8.5 / 14.0; // ≈ 0.6071428571428571

  /**
   * Returns the short/long edge ratio in {@code (0, 1]} for fixed entries. For {@link #AUTO},
   * {@link #ORIGINAL} and {@link #CUSTOM} the method returns {@code null}; callers must resolve
   * those via {@link CropPrefsHelper}.
   */
  @Nullable
  public Double shortOverLong() {
    switch (this) {
      case A3:
      case A4:
      case A5:
        return DIN_A;
      case US_LETTER:
        return LETTER;
      case LEGAL:
        return LEGAL_R;
      case AUTO:
      case ORIGINAL:
      case CUSTOM:
      default:
        return null;
    }
  }

  /**
   * Parses a stored name into a {@link CropAspectRatio}. Returns {@code def} when the input is
   * {@code null} or not a known constant.
   */
  public static CropAspectRatio fromName(@Nullable String name, CropAspectRatio def) {
    if (name == null) return def;
    try {
      return CropAspectRatio.valueOf(name);
    } catch (IllegalArgumentException e) {
      return def;
    }
  }
}
