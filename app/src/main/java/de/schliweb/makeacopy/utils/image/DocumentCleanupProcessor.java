/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.image;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import lombok.experimental.UtilityClass;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** Shared, deterministic OpenCV-based document cleanup pipeline. */
@UtilityClass
public final class DocumentCleanupProcessor {
  private static final String TAG = "DocumentCleanupProcessor";

  public static Bitmap apply(Context context, Bitmap input, DocumentCleanupOptions options) {
    if (input == null) return null;
    DocumentCleanupOptions safeOptions =
        options == null ? DocumentCleanupOptions.original() : options;
    if (safeOptions.mode == null || safeOptions.mode == DocumentCleanupMode.ORIGINAL) return input;

    try {
      if (context != null && !OpenCVUtils.isInitialized()) {
        OpenCVUtils.init(context.getApplicationContext());
      }
    } catch (Throwable t) {
      Log.w(TAG, "OpenCV init failed before cleanup", t);
    }

    Bitmap working = maybeDownscale(input, safeOptions.targetLongEdgePx);
    return safeOptions.preserveColor
        ? HighPassUtils.applyHighPassColor(working, true)
        : HighPassUtils.applyHighPassGray(working, true);
  }

  public static void applyInPlace(Mat bgr, DocumentCleanupOptions options) {
    if (bgr == null || bgr.empty()) return;
    DocumentCleanupOptions safeOptions =
        options == null ? DocumentCleanupOptions.original() : options;
    if (safeOptions.mode == null || safeOptions.mode == DocumentCleanupMode.ORIGINAL) return;

    if (safeOptions.preserveColor) {
      Mat lab = new Mat();
      try {
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab);
        HighPassUtils.backgroundDivideLab(lab, lab, HighPassUtils.KERNEL_FRACTION_BW);
        Imgproc.cvtColor(lab, bgr, Imgproc.COLOR_Lab2BGR);
        sharpen(bgr, 1.25);
      } finally {
        lab.release();
      }
    }
  }

  private static Bitmap maybeDownscale(Bitmap input, int targetLongEdgePx) {
    if (targetLongEdgePx <= 0 || input == null) return input;
    int longEdge = Math.max(input.getWidth(), input.getHeight());
    if (longEdge <= targetLongEdgePx) return input;
    double scale = targetLongEdgePx / (double) longEdge;
    int w = Math.max(1, (int) Math.round(input.getWidth() * scale));
    int h = Math.max(1, (int) Math.round(input.getHeight() * scale));
    return Bitmap.createScaledBitmap(input, w, h, true);
  }

  private static void sharpen(Mat mat, double amount) {
    Mat blurred = new Mat();
    try {
      Imgproc.GaussianBlur(mat, blurred, new Size(0, 0), 1.0);
      Core.addWeighted(mat, amount, blurred, 1.0 - amount, 0, mat);
    } finally {
      blurred.release();
    }
  }
}
