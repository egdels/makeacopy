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
 * The 3x3 Sobel derivatives of the 3x3-blurred luma of an ARGB image, computed only where they are
 * asked for: the same values as {@code Gray.ofArgb(...).blurred().sobelX()} and {@code sobelY()},
 * for a caller that needs them at a few thousand points rather than for the whole image. Luma and
 * blur are cached per pixel.
 */
public final class LazyGradients {
  public final int width;
  public final int height;
  private final int[] argb;
  private final float[] luma;
  private final float[] blur;

  public LazyGradients(int[] argb, int width, int height) {
    if (width <= 0 || height <= 0 || argb.length != width * height) {
      throw new IllegalArgumentException("bad size " + width + "x" + height);
    }
    this.width = width;
    this.height = height;
    this.argb = argb;
    this.luma = new float[argb.length];
    this.blur = new float[argb.length];
    java.util.Arrays.fill(luma, Float.NaN);
    java.util.Arrays.fill(blur, Float.NaN);
  }

  /** Horizontal derivative at {@code (x, y)}. */
  public float gx(int x, int y) {
    int l = Gray.reflect(x - 1, width);
    int r = Gray.reflect(x + 1, width);
    int u = Gray.reflect(y - 1, height);
    int d = Gray.reflect(y + 1, height);
    return (blur(r, u) - blur(l, u)) + 2f * (blur(r, y) - blur(l, y)) + (blur(r, d) - blur(l, d));
  }

  /** Vertical derivative at {@code (x, y)}. */
  public float gy(int x, int y) {
    int l = Gray.reflect(x - 1, width);
    int r = Gray.reflect(x + 1, width);
    int u = Gray.reflect(y - 1, height);
    int d = Gray.reflect(y + 1, height);
    return (blur(l, d) - blur(l, u)) + 2f * (blur(x, d) - blur(x, u)) + (blur(r, d) - blur(r, u));
  }

  private float blur(int x, int y) {
    int i = y * width + x;
    float v = blur[i];
    if (v == v) return v;
    int l = Gray.reflect(x - 1, width);
    int r = Gray.reflect(x + 1, width);
    int u = Gray.reflect(y - 1, height);
    int d = Gray.reflect(y + 1, height);
    v =
        ((luma(l, u) + 2f * luma(x, u) + luma(r, u))
                + 2f * (luma(l, y) + 2f * luma(x, y) + luma(r, y))
                + (luma(l, d) + 2f * luma(x, d) + luma(r, d)))
            / 16f;
    blur[i] = v;
    return v;
  }

  private float luma(int x, int y) {
    int i = y * width + x;
    float v = luma[i];
    if (v == v) return v;
    v = Gray.luma(argb[i]);
    luma[i] = v;
    return v;
  }
}
