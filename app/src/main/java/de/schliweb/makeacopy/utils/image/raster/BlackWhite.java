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
    if (width * height == 0) return;
    Enhance.flatten(argb, width, height);
    threshold(argb, width, height);
  }

  /**
   * Sauvola's threshold over a square window (clipped at the borders), in place. The window sums
   * come from column sums that slide down the rows, so the extra memory is a byte per pixel for the
   * luma and a few rows of sums, rather than two integral images of a long per pixel each (140 MB
   * for an A4 page at 300 dpi, more than the heap of many phones).
   */
  static void threshold(int[] argb, int width, int height) {
    int n = width * height;
    byte[] lum = new byte[n];
    for (int i = 0; i < n; i++) lum[i] = (byte) Gray.luma(argb[i]);

    int window = (int) Math.round(Math.min(width, height) * WINDOW_SHARE);
    window = Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, window)) | 1;
    int half = window / 2;
    // Bands of rows run in parallel; each starts its column sums afresh.
    int bands = Math.max(1, Math.min((height + BAND - 1) / BAND, 4 * PARALLELISM));
    int rowsPerBand = (height + bands - 1) / bands;
    IntStream.range(0, bands)
        .parallel()
        .forEach(
            band -> {
              int from = band * rowsPerBand;
              int until = Math.min(height, from + rowsPerBand);
              if (from < until) thresholdRows(argb, lum, width, height, half, from, until);
            });
  }

  private static final int PARALLELISM = Runtime.getRuntime().availableProcessors();

  private static void thresholdRows(
      int[] argb, byte[] lum, int width, int height, int half, int from, int until) {
    long[] colSum = new long[width];
    long[] colSq = new long[width];
    long[] sum = new long[width + 1];
    long[] sumSq = new long[width + 1];
    // Rows [top, bottom) are in the column sums.
    int top = Math.max(0, from - half);
    int bottom = top;
    for (int y = from; y < until; y++) {
      int y0 = Math.max(0, y - half);
      int y1 = Math.min(height, y + half + 1);
      while (bottom < y1) addRow(lum, width, bottom++, colSum, colSq, 1);
      while (top < y0) addRow(lum, width, top++, colSum, colSq, -1);
      for (int x = 0; x < width; x++) {
        sum[x + 1] = sum[x] + colSum[x];
        sumSq[x + 1] = sumSq[x] + colSq[x];
      }
      int rows = y1 - y0;
      for (int x = 0; x < width; x++) {
        int i = y * width + x;
        int v = lum[i] & 0xFF;
        if (v >= PAPER) {
          argb[i] = WHITE;
          continue;
        }
        int x0 = Math.max(0, x - half);
        int x1 = Math.min(width, x + half + 1);
        double count = (double) (x1 - x0) * rows;
        double s = sum[x1] - sum[x0];
        double sq = sumSq[x1] - sumSq[x0];
        double mean = s / count;
        double deviation = Math.sqrt(Math.max(0, sq / count - mean * mean));
        double threshold = mean * (1 + K * (deviation / R - 1));
        argb[i] = v > threshold ? WHITE : BLACK;
      }
    }
  }

  private static void addRow(byte[] lum, int width, int y, long[] colSum, long[] colSq, int sign) {
    int row = y * width;
    for (int x = 0; x < width; x++) {
      int v = lum[row + x] & 0xFF;
      colSum[x] += sign * v;
      colSq[x] += sign * (long) v * v;
    }
  }
}
