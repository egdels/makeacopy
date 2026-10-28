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
 * Camera frames (NV21) to small upright ARGB images in one pass: each output pixel averages the
 * luma of the source block it covers and takes the chroma at the block centre (BT.601, video
 * range), and the rotation is applied while writing.
 */
public final class Nv21 {
  private Nv21() {}

  /** Size of the upright output for a {@code width x height} frame, as {width, height}. */
  public static int[] uprightSize(int width, int height, int rotationDegrees, int maxSize) {
    double scale = Math.min(1.0, maxSize / (double) Math.max(width, height));
    int w = Math.max(1, (int) Math.round(width * scale));
    int h = Math.max(1, (int) Math.round(height * scale));
    return quarterTurn(rotationDegrees) ? new int[] {h, w} : new int[] {w, h};
  }

  /**
   * Converts the frame, scaled down to fit {@code maxSize} and rotated clockwise by {@code
   * rotationDegrees} (0, 90, 180 or 270), into ARGB pixels of {@link #uprightSize}.
   */
  public static int[] toUprightArgb(
      byte[] nv21, int width, int height, int rotationDegrees, int maxSize) {
    double scale = Math.min(1.0, maxSize / (double) Math.max(width, height));
    int tw = Math.max(1, (int) Math.round(width * scale));
    int th = Math.max(1, (int) Math.round(height * scale));
    int rot = ((rotationDegrees % 360) + 360) % 360;
    int outW = quarterTurn(rot) ? th : tw;
    int outH = quarterTurn(rot) ? tw : th;
    int[] out = new int[tw * th];
    int chroma = width * height;
    int[] xs = bounds(width, tw);
    int[] ys = bounds(height, th);
    for (int oy = 0; oy < th; oy++) {
      int y0 = ys[oy];
      int y1 = Math.max(y0 + 1, ys[oy + 1]);
      for (int ox = 0; ox < tw; ox++) {
        int x0 = xs[ox];
        int x1 = Math.max(x0 + 1, xs[ox + 1]);
        int sum = 0;
        for (int y = y0; y < y1; y++) {
          int row = y * width;
          for (int x = x0; x < x1; x++) sum += nv21[row + x] & 0xFF;
        }
        int luma = sum / ((x1 - x0) * (y1 - y0));
        int cx = ((x0 + x1) / 2) & ~1;
        int cy = (y0 + y1) / 2 / 2;
        int uv = chroma + cy * width + cx;
        int v = (nv21[uv] & 0xFF) - 128;
        int u = (nv21[uv + 1] & 0xFF) - 128;
        int c = 1192 * Math.max(0, luma - 16);
        int r = clamp((c + 1634 * v) >> 10);
        int g = clamp((c - 833 * v - 400 * u) >> 10);
        int b = clamp((c + 2066 * u) >> 10);
        int px = 0xFF000000 | (r << 16) | (g << 8) | b;
        int dx;
        int dy;
        switch (rot) {
          case 90 -> {
            dx = th - 1 - oy;
            dy = ox;
          }
          case 180 -> {
            dx = tw - 1 - ox;
            dy = th - 1 - oy;
          }
          case 270 -> {
            dx = oy;
            dy = tw - 1 - ox;
          }
          default -> {
            dx = ox;
            dy = oy;
          }
        }
        out[dy * outW + dx] = px;
      }
    }
    return out;
  }

  private static boolean quarterTurn(int rotationDegrees) {
    int rot = ((rotationDegrees % 360) + 360) % 360;
    return rot == 90 || rot == 270;
  }

  /** Source start of each of the {@code m} output cells along {@code n}, plus the end. */
  private static int[] bounds(int n, int m) {
    int[] b = new int[m + 1];
    for (int i = 0; i <= m; i++) b[i] = (int) ((long) i * n / m);
    return b;
  }

  private static int clamp(int v) {
    return Math.max(0, Math.min(255, v));
  }
}
