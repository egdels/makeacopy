/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.image.raster;

import java.util.stream.IntStream;

/**
 * Black and white for documents: {@link Enhance#flatten} evens out paper and shadows, then a
 * Sauvola threshold over a window of about a text line decides each pixel. After flattening the
 * paper is close to white everywhere, so the local threshold only has to separate ink from paper.
 */
public final class BlackWhite {
  /** Rows per parallel task. */
  private static final int BAND = 64;

  /** Sauvola's sensitivity: higher keeps less of faint strokes. */
  private static final double K = 0.2;

  /** Dynamic range of the standard deviation for 8-bit images. */
  private static final double R = 128.0;

  /** Window side as a share of the shorter image side, and its bounds in pixels. */
  private static final double WINDOW_SHARE = 1.0 / 40.0;

  private static final int MIN_WINDOW = 15;
  private static final int MAX_WINDOW = 101;

  /** Pixels at least this bright are paper whatever the neighbourhood says. */
  private static final int PAPER = 230;

  private static final int WHITE = 0xFFFFFFFF;
  private static final int BLACK = 0xFF000000;

  private BlackWhite() {}

  /** Turns {@code argb} into opaque black and white, in place. */
  public static void apply(int[] argb, int width, int height) {
    int n = width * height;
    if (n == 0) return;
    Enhance.flatten(argb, width, height);
    int[] lum = new int[n];
    for (int i = 0; i < n; i++) lum[i] = Gray.luma(argb[i]);

    long[] sum = new long[(width + 1) * (height + 1)];
    long[] sumSq = new long[(width + 1) * (height + 1)];
    int stride = width + 1;
    for (int y = 0; y < height; y++) {
      long row = 0;
      long rowSq = 0;
      for (int x = 0; x < width; x++) {
        int v = lum[y * width + x];
        row += v;
        rowSq += (long) v * v;
        sum[(y + 1) * stride + x + 1] = sum[y * stride + x + 1] + row;
        sumSq[(y + 1) * stride + x + 1] = sumSq[y * stride + x + 1] + rowSq;
      }
    }

    int window = (int) Math.round(Math.min(width, height) * WINDOW_SHARE);
    window = Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, window)) | 1;
    int half = window / 2;
    // Rows are independent once the integral images exist.
    IntStream.range(0, (height + BAND - 1) / BAND)
        .parallel()
        .forEach(
            band -> {
              int from = band * BAND;
              int until = Math.min(height, from + BAND);
              for (int y = from; y < until; y++) {
                int y0 = Math.max(0, y - half);
                int y1 = Math.min(height, y + half + 1);
                for (int x = 0; x < width; x++) {
                  int i = y * width + x;
                  int v = lum[i];
                  if (v >= PAPER) {
                    argb[i] = WHITE;
                    continue;
                  }
                  int x0 = Math.max(0, x - half);
                  int x1 = Math.min(width, x + half + 1);
                  double count = (double) (x1 - x0) * (y1 - y0);
                  double s =
                      sum[y1 * stride + x1]
                          - sum[y0 * stride + x1]
                          - sum[y1 * stride + x0]
                          + sum[y0 * stride + x0];
                  double sq =
                      sumSq[y1 * stride + x1]
                          - sumSq[y0 * stride + x1]
                          - sumSq[y1 * stride + x0]
                          + sumSq[y0 * stride + x0];
                  double mean = s / count;
                  double deviation = Math.sqrt(Math.max(0, sq / count - mean * mean));
                  double threshold = mean * (1 + K * (deviation / R - 1));
                  argb[i] = v > threshold ? WHITE : BLACK;
                }
              }
            });
  }
}
