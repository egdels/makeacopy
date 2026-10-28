/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.camera;

import android.graphics.Bitmap;
import android.graphics.Rect;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.makeacopy.utils.image.ImageOps;
import de.schliweb.makeacopy.utils.image.raster.Gray;

/**
 * Computes the raw Laplacian-variance sharpness measure on the small upright analysis bitmap
 * already produced by the live corner-detection pipeline (see {@code
 * docs/focus_quality_indicator_design.md}).
 *
 * <p>The input is the existing downscaled detection bitmap (≤ {@code DETECTION_MAX_EDGE} px); no
 * extra copies of the camera frame are made. Call it only on the CameraX analyzer thread, and only
 * on frames that already pass the analysis throttle.
 */
public final class FocusQualityAnalyzer {

  /**
   * The variance of the 4-neighbour Laplacian of the luma inside {@code roi} (the whole bitmap when
   * null or too small), or -1 when the measurement fails.
   */
  public double measure(@NonNull Bitmap bitmap, @Nullable Rect roi) {
    try {
      int w = bitmap.getWidth();
      int h = bitmap.getHeight();
      Gray gray = Gray.ofArgb(ImageOps.pixels(bitmap), w, h);
      Rect r = clampRoi(roi, w, h);
      if (r == null) r = new Rect(0, 0, w, h);
      return gray.laplacianVariance(r.left, r.top, r.width(), r.height());
    } catch (Throwable t) {
      return -1.0;
    }
  }

  @Nullable
  static Rect clampRoi(@Nullable Rect roi, int width, int height) {
    if (roi == null || width <= 0 || height <= 0) return null;
    int x = Math.max(0, roi.left);
    int y = Math.max(0, roi.top);
    int w = Math.min(roi.width() - (x - roi.left), width - x);
    int h = Math.min(roi.height() - (y - roi.top), height - y);
    if (w < 16 || h < 16) return null; // too small to give a stable measurement
    if (x == 0 && y == 0 && w == width && h == height) return null; // full frame anyway
    return new Rect(x, y, x + w, y + h);
  }
}
