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

/**
 * A grayscale image as floats (0..255), row by row, with the few filters the app needs: 3x3
 * Gaussian blur, 3x3 Sobel, the 4-neighbour Laplacian, area downscaling and Otsu's threshold.
 * Borders are reflected without repeating the edge pixel (OpenCV's BORDER_REFLECT_101).
 */
public final class Gray {
  public final int width;
  public final int height;
  public final float[] px;

  public Gray(int width, int height, float[] px) {
    if (width <= 0 || height <= 0 || px.length != width * height) {
      throw new IllegalArgumentException("bad size " + width + "x" + height + " for " + px.length);
    }
    this.width = width;
    this.height = height;
    this.px = px;
  }

  /** Rec. 601 luma, rounded as OpenCV's RGBA2GRAY. */
  public static int luma(int argb) {
    int r = argb >> 16 & 0xFF;
    int g = argb >> 8 & 0xFF;
    int b = argb & 0xFF;
    return (r * 4899 + g * 9617 + b * 1868 + 8192) >> 14;
  }

  public static Gray ofArgb(int[] argb, int width, int height) {
    float[] out = new float[width * height];
    for (int i = 0; i < out.length; i++) out[i] = luma(argb[i]);
    return new Gray(width, height, out);
  }

  public float at(int x, int y) {
    return px[y * width + x];
  }

  /** 3x3 Gaussian (1 2 1 / 4 in each direction). */
  public Gray blurred() {
    return convolve(SMOOTH, SMOOTH, 1f / 16f);
  }

  /** Horizontal derivative, 3x3 Sobel. */
  public Gray sobelX() {
    return convolve(DERIVE, SMOOTH, 1f);
  }

  /** Vertical derivative, 3x3 Sobel. */
  public Gray sobelY() {
    return convolve(SMOOTH, DERIVE, 1f);
  }

  /**
   * Variance of the 4-neighbour Laplacian over the rectangle {@code [x0, x0+w) x [y0, y0+h)}, a
   * sharpness measure: the higher, the more fine detail.
   */
  public double laplacianVariance(int x0, int y0, int w, int h) {
    double sum = 0;
    double sumSq = 0;
    for (int y = y0; y < y0 + h; y++) {
      int up = reflect(y - 1, height) * width;
      int row = y * width;
      int down = reflect(y + 1, height) * width;
      for (int x = x0; x < x0 + w; x++) {
        double v =
            px[up + x]
                + px[down + x]
                + px[row + reflect(x - 1, width)]
                + px[row + reflect(x + 1, width)]
                - 4 * px[row + x];
        sum += v;
        sumSq += v * v;
      }
    }
    double n = (double) w * h;
    double mean = sum / n;
    return Math.max(0, sumSq / n - mean * mean);
  }

  /** Area-averaged downscale to {@code w x h} (each output pixel averages the source it covers). */
  public Gray downscaled(int w, int h) {
    if (w >= width && h >= height) return this;
    float[] tmp = new float[w * height];
    for (int y = 0; y < height; y++) areaLine(px, y * width, 1, width, tmp, y * w, 1, w);
    float[] out = new float[w * h];
    for (int x = 0; x < w; x++) areaLine(tmp, x, w, height, out, x, w, h);
    return new Gray(w, h, out);
  }

  private static void areaLine(
      float[] src,
      int srcStart,
      int srcStride,
      int n,
      float[] dst,
      int dstStart,
      int dstStride,
      int m) {
    double scale = n / (double) m;
    for (int j = 0; j < m; j++) {
      double from = j * scale;
      double until = from + scale;
      double s = 0;
      for (int i = (int) from; i < Math.min(n, (int) Math.ceil(until)); i++) {
        double cover = Math.min(until, i + 1) - Math.max(from, i);
        s += cover * src[srcStart + i * srcStride];
      }
      dst[dstStart + j * dstStride] = (float) (s / scale);
    }
  }

  /** Otsu's threshold of the values rounded to 0..255: pixels above it are the bright class. */
  public int otsu() {
    int[] histogram = new int[256];
    for (float v : px) histogram[clamp(Math.round(v))]++;
    return otsu(histogram, px.length);
  }

  static int otsu(int[] histogram, int total) {
    double sum = 0;
    for (int v = 0; v < 256; v++) sum += (double) v * histogram[v];
    double sumBelow = 0;
    int below = 0;
    double best = -1;
    int threshold = 0;
    for (int t = 0; t < 256; t++) {
      below += histogram[t];
      if (below == 0) continue;
      int above = total - below;
      if (above == 0) break;
      sumBelow += (double) t * histogram[t];
      double meanBelow = sumBelow / below;
      double meanAbove = (sum - sumBelow) / above;
      double between = (double) below * above * (meanBelow - meanAbove) * (meanBelow - meanAbove);
      if (between > best) {
        best = between;
        threshold = t;
      }
    }
    return threshold;
  }

  private static final float[] SMOOTH = {1f, 2f, 1f};
  private static final float[] DERIVE = {-1f, 0f, 1f};

  private Gray convolve(float[] kx, float[] ky, float scale) {
    float[] tmp = new float[px.length];
    for (int y = 0; y < height; y++) {
      int row = y * width;
      for (int x = 0; x < width; x++) {
        tmp[row + x] =
            kx[0] * px[row + reflect(x - 1, width)]
                + kx[1] * px[row + x]
                + kx[2] * px[row + reflect(x + 1, width)];
      }
    }
    float[] out = new float[px.length];
    for (int y = 0; y < height; y++) {
      int up = reflect(y - 1, height) * width;
      int row = y * width;
      int down = reflect(y + 1, height) * width;
      for (int x = 0; x < width; x++) {
        out[row + x] = scale * (ky[0] * tmp[up + x] + ky[1] * tmp[row + x] + ky[2] * tmp[down + x]);
      }
    }
    return new Gray(width, height, out);
  }

  static int reflect(int i, int n) {
    if (n == 1) return 0;
    if (i < 0) return -i;
    if (i >= n) return 2 * n - 2 - i;
    return i;
  }

  private static int clamp(int v) {
    return Math.max(0, Math.min(255, v));
  }
}
