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

import static org.junit.Assert.assertArrayEquals;

import java.util.Random;
import org.junit.Test;

public class BlackWhiteTest {

  /** Sauvola with two full integral images, as the threshold was first written. */
  private static void reference(int[] argb, int width, int height) {
    int n = width * height;
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
    int window = (int) Math.round(Math.min(width, height) / 40.0);
    window = Math.max(15, Math.min(101, window)) | 1;
    int half = window / 2;
    for (int y = 0; y < height; y++) {
      int y0 = Math.max(0, y - half);
      int y1 = Math.min(height, y + half + 1);
      for (int x = 0; x < width; x++) {
        int i = y * width + x;
        int v = lum[i];
        if (v >= 230) {
          argb[i] = 0xFFFFFFFF;
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
        double threshold = mean * (1 + 0.2 * (deviation / 128.0 - 1));
        argb[i] = v > threshold ? 0xFFFFFFFF : 0xFF000000;
      }
    }
  }

  private static void assertSameAsReference(int width, int height, long seed) {
    Random rnd = new Random(seed);
    int[] px = new int[width * height];
    for (int i = 0; i < px.length; i++) {
      int x = i % width;
      int y = i / width;
      int base = 120 + (x * 37 + y * 11) % 120;
      int v = Math.max(0, Math.min(255, base - rnd.nextInt(140)));
      px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
    }
    int[] expected = px.clone();
    reference(expected, width, height);
    int[] actual = px.clone();
    BlackWhite.threshold(actual, width, height);
    assertArrayEquals(width + "x" + height, expected, actual);
  }

  @Test
  public void slidingSumsGiveTheSameResultAsIntegralImages() {
    assertSameAsReference(600, 900, 1);
    assertSameAsReference(97, 41, 2);
    assertSameAsReference(13, 300, 3);
    assertSameAsReference(5000, 70, 4);
    assertSameAsReference(1, 1, 5);
  }
}
