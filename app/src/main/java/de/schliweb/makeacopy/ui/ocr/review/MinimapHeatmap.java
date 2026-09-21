/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.ocr.review;

import androidx.annotation.Nullable;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import java.util.List;

/** Binned confidence heatmap shown on the OCR review minimap. */
final class MinimapHeatmap {
  /** Words at or below this confidence count as "low". */
  static final float LOW_CONFIDENCE = 0.60f;

  private MinimapHeatmap() {}

  /**
   * Bins words by the center of their box into a {@code cols x rows} grid over the page.
   *
   * @return row-major array with the share (0..1) of low-confidence words per cell; all zeros when
   *     there are no words or the page size is unknown
   */
  static float[] lowConfidenceRatios(
      @Nullable List<OcrDoc.Word> words, int pageW, int pageH, int cols, int rows) {
    int n = cols * rows;
    int[] tot = new int[n];
    int[] low = new int[n];
    if (words != null && pageW > 0 && pageH > 0) {
      for (OcrDoc.Word w : words) {
        if (w == null || w.b == null || w.b.length < 4) continue;
        float cx = w.b[0] + (w.b[2] * 0.5f);
        float cy = w.b[1] + (w.b[3] * 0.5f);
        int ix = clamp((int) Math.floor((cx / pageW) * cols), cols);
        int iy = clamp((int) Math.floor((cy / pageH) * rows), rows);
        int idx = iy * cols + ix;
        tot[idx]++;
        if (w.c <= LOW_CONFIDENCE) low[idx]++;
      }
    }
    float[] vals = new float[n];
    for (int i = 0; i < n; i++) {
      vals[i] = (tot[i] > 0) ? (low[i] / (float) tot[i]) : 0f;
    }
    return vals;
  }

  private static int clamp(int index, int size) {
    return Math.max(0, Math.min(size - 1, index));
  }
}
