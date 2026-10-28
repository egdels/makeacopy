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

/** A projective map of the plane given by four point pairs, and bilinear warping with it. */
public final class Homography {
  /** Row-major 3x3 matrix, h[8] == 1. */
  private final double[] h;

  private Homography(double[] h) {
    this.h = h;
  }

  /**
   * The map that sends {@code from[i]} to {@code to[i]} for the four points (x, y pairs), or {@code
   * null} when three of them are collinear.
   */
  public static Homography of(double[] from, double[] to) {
    if (from.length != 8 || to.length != 8) throw new IllegalArgumentException("need 4 points");
    double[][] a = new double[8][9];
    for (int i = 0; i < 4; i++) {
      double x = from[2 * i];
      double y = from[2 * i + 1];
      double u = to[2 * i];
      double v = to[2 * i + 1];
      a[2 * i] = new double[] {x, y, 1, 0, 0, 0, -u * x, -u * y, u};
      a[2 * i + 1] = new double[] {0, 0, 0, x, y, 1, -v * x, -v * y, v};
    }
    for (int col = 0; col < 8; col++) {
      int pivot = col;
      for (int r = col + 1; r < 8; r++) {
        if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
      }
      if (Math.abs(a[pivot][col]) < 1e-12) return null;
      double[] t = a[col];
      a[col] = a[pivot];
      a[pivot] = t;
      for (int r = 0; r < 8; r++) {
        if (r == col) continue;
        double f = a[r][col] / a[col][col];
        if (f == 0) continue;
        for (int c = col; c < 9; c++) a[r][c] -= f * a[col][c];
      }
    }
    double[] m = new double[9];
    for (int i = 0; i < 8; i++) m[i] = a[i][8] / a[i][i];
    m[8] = 1;
    return new Homography(m);
  }

  /** Maps the point (x, y); writes the result to {@code out[0..1]}. */
  public void map(double x, double y, double[] out) {
    double w = h[6] * x + h[7] * y + h[8];
    out[0] = (h[0] * x + h[1] * y + h[2]) / w;
    out[1] = (h[3] * x + h[4] * y + h[5]) / w;
  }

  /**
   * Fills the {@code outW x outH} image so that its pixel (x, y) samples {@code src} at {@code
   * this.map(x, y)}, bilinearly, repeating the edge pixels outside the source.
   */
  public int[] warpReplicate(int[] src, int srcW, int srcH, int outW, int outH) {
    int[] out = new int[outW * outH];
    double[] p = new double[2];
    for (int y = 0; y < outH; y++) {
      for (int x = 0; x < outW; x++) {
        map(x, y, p);
        out[y * outW + x] = sample(src, srcW, srcH, p[0], p[1]);
      }
    }
    return out;
  }

  /** Bilinear ARGB sample of {@code src} at (x, y), with the edge pixels repeated outside it. */
  public static int sample(int[] src, int w, int h, double x, double y) {
    double fx = Math.max(0, Math.min(w - 1, x));
    double fy = Math.max(0, Math.min(h - 1, y));
    int x0 = (int) fx;
    int y0 = (int) fy;
    int x1 = Math.min(w - 1, x0 + 1);
    int y1 = Math.min(h - 1, y0 + 1);
    double tx = fx - x0;
    double ty = fy - y0;
    int a = src[y0 * w + x0];
    int b = src[y0 * w + x1];
    int c = src[y1 * w + x0];
    int d = src[y1 * w + x1];
    int out = 0;
    for (int shift = 0; shift <= 24; shift += 8) {
      double top = (a >> shift & 0xFF) * (1 - tx) + (b >> shift & 0xFF) * tx;
      double bottom = (c >> shift & 0xFF) * (1 - tx) + (d >> shift & 0xFF) * tx;
      out |= ((int) Math.round(top * (1 - ty) + bottom * ty) & 0xFF) << shift;
    }
    return out;
  }
}
