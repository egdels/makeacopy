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

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * The "Enhanced" document cleanup on ARGB pixels: lifts the paper to white per block (which also
 * removes shadows and colour casts), stretches the darkest ink to black, and sharpens the luma by
 * unsharp masking. Ported from Sheet's {@code core.image.Enhance}.
 */
public final class Enhance {
  private static final int BLOCKS = 40;
  private static final int MIN_BLOCK = 16;

  /** Share of the brightest pixels of a block that count as its paper. */
  private static final double PAPER_SHARE = 0.25;

  /** A block is paper when its paper luma reaches this share of the typical block. */
  private static final double PAPER_MIN = 0.7;

  /** A uniform (flat) block counts as paper in shadow down to this share. */
  private static final double SHADOW_MIN = 0.3;

  private static final float FLAT = 6f;

  /** Paper is lifted slightly above white so that it clips to 255. */
  private static final float PAPER_LIFT = 1.05f;

  private static final double MAX_GAIN = 3.0;
  private static final double BLACK_SHARE = 0.005;
  private static final int MAX_BLACK = 64;

  /** How much mid tones are darkened towards the ink. */
  private static final double INK = 0.6;

  private static final int[] KERNEL = {1, 6, 15, 20, 15, 6, 1};
  private static final int KERNEL_SUM = 64;
  private static final double AMOUNT = 0.8;
  private static final int THRESHOLD = 3;

  private static final int BAND = 64;

  private Enhance() {}

  /** Whitens the paper, blackens the ink and sharpens {@code argb} in place. */
  public static void apply(int[] argb, int width, int height) {
    check(argb, width, height);
    if (width * height == 0) return;
    whitenPaper(argb, width, height);
    blackenInk(argb, width, height);
    sharpen(argb, width, height);
  }

  /** The paper and ink steps of {@link #apply} without sharpening, as black and white starts. */
  public static void flatten(int[] argb, int width, int height) {
    check(argb, width, height);
    if (width * height == 0) return;
    whitenPaper(argb, width, height);
    blackenInk(argb, width, height);
  }

  private static void check(int[] argb, int width, int height) {
    if (width < 0 || height < 0 || argb.length < width * height) {
      throw new IllegalArgumentException(argb.length + " pixels for " + width + " x " + height);
    }
  }

  private interface Rows {
    void run(int from, int until);
  }

  private static void inBands(int count, Rows rows) {
    int bands = (count + BAND - 1) / BAND;
    IntStream.range(0, bands)
        .parallel()
        .forEach(b -> rows.run(b * BAND, Math.min(count, (b + 1) * BAND)));
  }

  static int luma(int r, int g, int b) {
    return (77 * r + 150 * g + 29 * b) >> 8;
  }

  static int luma(int p) {
    return luma(p >> 16 & 0xFF, p >> 8 & 0xFF, p & 0xFF);
  }

  private static void whitenPaper(int[] argb, int width, int height) {
    int size = Math.max(MIN_BLOCK, (Math.max(width, height) + BLOCKS - 1) / BLOCKS);
    int bw = (width + size - 1) / size;
    int bh = (height + size - 1) / size;
    float[][] paper = new float[3][bw * bh];
    float[] lumas = new float[bw * bh];
    boolean[] flat = new boolean[bw * bh];
    IntStream.range(0, bh)
        .parallel()
        .forEach(
            by -> {
              int[] histogram = new int[256];
              for (int bx = 0; bx < bw; bx++) {
                paperOfBlock(argb, width, height, size, bx, by, bw, histogram, paper, lumas, flat);
              }
            });
    float[] sorted = lumas.clone();
    Arrays.sort(sorted);
    float typical = sorted[sorted.length / 2];
    boolean[] valid = new boolean[bw * bh];
    for (int i = 0; i < valid.length; i++) {
      valid[i] = lumas[i] >= typical * PAPER_MIN || (flat[i] && lumas[i] >= typical * SHADOW_MIN);
    }
    fillFromNeighbours(paper, valid, bw, bh, typical);
    for (int pass = 0; pass < 2; pass++) for (float[] c : paper) boxBlur(c, bw, bh);

    float[][] gains = new float[3][bw * bh];
    for (int c = 0; c < 3; c++) {
      for (int i = 0; i < bw * bh; i++) {
        float g = PAPER_LIFT * 255f / Math.max(1f, paper[c][i]);
        gains[c][i] = Math.max(1f, Math.min((float) MAX_GAIN, g));
      }
    }
    int[] left = new int[width];
    int[] right = new int[width];
    float[] towards = new float[width];
    for (int x = 0; x < width; x++) {
      float fx = clamp((x + 0.5f) / size - 0.5f, 0f, bw - 1);
      left[x] = (int) fx;
      right[x] = Math.min(bw - 1, left[x] + 1);
      towards[x] = fx - left[x];
    }
    inBands(
        height,
        (from, until) -> {
          float[] rowR = new float[bw];
          float[] rowG = new float[bw];
          float[] rowB = new float[bw];
          for (int y = from; y < until; y++) {
            float fy = clamp((y + 0.5f) / size - 0.5f, 0f, bh - 1);
            int top = (int) fy * bw;
            int bottom = Math.min(bh - 1, (int) fy + 1) * bw;
            float ty = fy - (int) fy;
            gainRow(gains[0], top, bottom, ty, rowR);
            gainRow(gains[1], top, bottom, ty, rowG);
            gainRow(gains[2], top, bottom, ty, rowB);
            whitenRow(argb, y * width, width, left, right, towards, rowR, rowG, rowB);
          }
        });
  }

  private static void paperOfBlock(
      int[] argb,
      int width,
      int height,
      int size,
      int bx,
      int by,
      int bw,
      int[] histogram,
      float[][] paper,
      float[] lumas,
      boolean[] flat) {
    int x0 = bx * size;
    int y0 = by * size;
    int x1 = Math.min(width, x0 + size);
    int y1 = Math.min(height, y0 + size);
    Arrays.fill(histogram, 0);
    for (int y = y0; y < y1; y++) {
      int i = y * width + x0;
      for (int x = x0; x < x1; x++) histogram[luma(argb[i++])]++;
    }
    int count = (x1 - x0) * (y1 - y0);
    int median = 0;
    int below = histogram[0];
    while (median < 255 && below < (count + 1) / 2) below += histogram[++median];
    int cut = 255;
    int above = histogram[255];
    while (cut > 0 && above < count * PAPER_SHARE) above += histogram[--cut];
    long r = 0;
    long g = 0;
    long b = 0;
    int k = 0;
    for (int y = y0; y < y1; y++) {
      int i = y * width + x0;
      for (int x = x0; x < x1; x++) {
        int p = argb[i++];
        if (luma(p) < cut) continue;
        r += p >> 16 & 0xFF;
        g += p >> 8 & 0xFF;
        b += p & 0xFF;
        k++;
      }
    }
    int i = by * bw + bx;
    paper[0][i] = (float) r / k;
    paper[1][i] = (float) g / k;
    paper[2][i] = (float) b / k;
    lumas[i] = luma(Math.round(paper[0][i]), Math.round(paper[1][i]), Math.round(paper[2][i]));
    flat[i] = lumas[i] - median <= FLAT;
  }

  private static void gainRow(float[] gains, int top, int bottom, float ty, float[] out) {
    for (int bx = 0; bx < out.length; bx++) {
      out[bx] = gains[top + bx] + (gains[bottom + bx] - gains[top + bx]) * ty;
    }
  }

  private static void whitenRow(
      int[] argb,
      int start,
      int width,
      int[] left,
      int[] right,
      float[] towards,
      float[] rowR,
      float[] rowG,
      float[] rowB) {
    int i = start;
    for (int x = 0; x < width; x++) {
      int a = left[x];
      int b = right[x];
      float t = towards[x];
      int p = argb[i];
      int r = Math.min(255, (int) ((p >> 16 & 0xFF) * (rowR[a] + (rowR[b] - rowR[a]) * t) + 0.5f));
      int g = Math.min(255, (int) ((p >> 8 & 0xFF) * (rowG[a] + (rowG[b] - rowG[a]) * t) + 0.5f));
      int bl = Math.min(255, (int) ((p & 0xFF) * (rowB[a] + (rowB[b] - rowB[a]) * t) + 0.5f));
      argb[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | bl;
      i++;
    }
  }

  private static void fillFromNeighbours(
      float[][] paper, boolean[] valid, int bw, int bh, float typical) {
    boolean any = false;
    for (boolean v : valid) any |= v;
    if (!any) {
      for (float[] c : paper) Arrays.fill(c, typical);
      return;
    }
    while (!allTrue(valid)) {
      boolean[] next = valid.clone();
      for (int i = 0; i < valid.length; i++) {
        if (valid[i]) continue;
        int x = i % bw;
        int y = i / bw;
        float[] sum = new float[3];
        int k = 0;
        for (int dy = -1; dy <= 1; dy++) {
          for (int dx = -1; dx <= 1; dx++) {
            int nx = x + dx;
            int ny = y + dy;
            if (nx < 0 || nx >= bw || ny < 0 || ny >= bh || !valid[ny * bw + nx]) continue;
            for (int c = 0; c < 3; c++) sum[c] += paper[c][ny * bw + nx];
            k++;
          }
        }
        if (k == 0) continue;
        for (int c = 0; c < 3; c++) paper[c][i] = sum[c] / k;
        next[i] = true;
      }
      System.arraycopy(next, 0, valid, 0, valid.length);
    }
  }

  private static boolean allTrue(boolean[] a) {
    for (boolean v : a) if (!v) return false;
    return true;
  }

  private static void boxBlur(float[] m, int w, int h) {
    float[] src = m.clone();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        float s = 0f;
        int k = 0;
        for (int dy = -1; dy <= 1; dy++) {
          for (int dx = -1; dx <= 1; dx++) {
            int nx = x + dx;
            int ny = y + dy;
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
            s += src[ny * w + nx];
            k++;
          }
        }
        m[y * w + x] = s / k;
      }
    }
  }

  private static void blackenInk(int[] argb, int width, int height) {
    int n = width * height;
    int[] histogram = new int[256];
    inBands(
        height,
        (from, until) -> {
          int[] local = new int[256];
          for (int y = from; y < until; y++) {
            for (int i = y * width; i < (y + 1) * width; i++) local[luma(argb[i])]++;
          }
          synchronized (histogram) {
            for (int v = 0; v < 256; v++) histogram[v] += local[v];
          }
        });
    int black = 0;
    int count = 0;
    while (black < 255 && count + histogram[black] <= n * BLACK_SHARE) count += histogram[black++];
    final int ink = Math.min(black, MAX_BLACK);
    int[] map = new int[256];
    for (int v = 0; v < 256; v++) {
      double t = Math.max(0, v - ink) / (255.0 - ink);
      map[v] = clamp((int) Math.round(255.0 * (t - INK * t * (1 - t) * (1 - t))), 0, 255);
    }
    inBands(
        height,
        (from, until) -> {
          for (int i = from * width; i < until * width; i++) {
            int p = argb[i];
            argb[i] =
                (p & 0xFF000000)
                    | (map[p >> 16 & 0xFF] << 16)
                    | (map[p >> 8 & 0xFF] << 8)
                    | map[p & 0xFF];
          }
        });
  }

  private static void sharpen(int[] argb, int width, int height) {
    int n = width * height;
    byte[] lum = new byte[n];
    byte[] tmp = new byte[n];
    inBands(
        height,
        (from, until) -> {
          for (int y = from; y < until; y++) {
            int row = y * width;
            for (int i = row; i < row + width; i++) lum[i] = (byte) luma(argb[i]);
            for (int x = 0; x < width; x++) tmp[row + x] = (byte) blur(lum, row, x, 1, width);
          }
        });
    inBands(
        height,
        (from, until) -> {
          for (int y = from; y < until; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
              int i = row + x;
              int diff = (lum[i] & 0xFF) - blur(tmp, x, y, width, height);
              if (diff < THRESHOLD && diff > -THRESHOLD) continue;
              int d = (int) Math.round(AMOUNT * diff);
              int p = argb[i];
              int red = clamp((p >> 16 & 0xFF) + d, 0, 255);
              int green = clamp((p >> 8 & 0xFF) + d, 0, 255);
              int blue = clamp((p & 0xFF) + d, 0, 255);
              argb[i] = (p & 0xFF000000) | (red << 16) | (green << 8) | blue;
            }
          }
        });
  }

  /** 7-tap binomial blur of {@code v} at position {@code at} along a line with {@code stride}. */
  private static int blur(byte[] v, int start, int at, int stride, int count) {
    int c = start + at * stride;
    int s;
    if (at >= 3 && at < count - 3) {
      s =
          (v[c - 3 * stride] & 0xFF)
              + (v[c + 3 * stride] & 0xFF)
              + 6 * ((v[c - 2 * stride] & 0xFF) + (v[c + 2 * stride] & 0xFF))
              + 15 * ((v[c - stride] & 0xFF) + (v[c + stride] & 0xFF))
              + 20 * (v[c] & 0xFF);
    } else {
      s = 0;
      for (int k = -3; k <= 3; k++) {
        s += KERNEL[k + 3] * (v[start + clamp(at + k, 0, count - 1) * stride] & 0xFF);
      }
    }
    return (s + KERNEL_SUM / 2) / KERNEL_SUM;
  }

  private static float clamp(float v, float lo, float hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
