/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ml.docquad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit tests for the confidence measures of {@link DocQuadPostprocessor}. */
public class DocQuadConfidenceTest {

  private static float[][][][] heatmaps(float background) {
    float[][][][] hm = new float[1][4][64][64];
    for (int c = 0; c < 4; c++) {
      for (int y = 0; y < 64; y++) java.util.Arrays.fill(hm[0][c][y], background);
    }
    return hm;
  }

  /** Mask logits: +8 inside the cell rectangle [x0,x1)×[y0,y1), -8 elsewhere. */
  private static float[][][][] rectMask(int x0, int y0, int x1, int y1) {
    float[][][][] m = new float[1][1][64][64];
    for (int y = 0; y < 64; y++) {
      for (int x = 0; x < 64; x++) {
        m[0][0][y][x] = (x >= x0 && x < x1 && y >= y0 && y < y1) ? 8f : -8f;
      }
    }
    return m;
  }

  @Test
  public void minPeakProb_isLimitedByTheWeakestChannel() {
    float[][][][] hm = heatmaps(-10f);
    hm[0][0][10][10] = 6f;
    hm[0][1][10][50] = 6f;
    hm[0][2][50][50] = 6f;
    hm[0][3][50][10] = -2f; // weak BL channel

    assertEquals(1.0 / (1.0 + Math.exp(2.0)), DocQuadPostprocessor.minPeakProb(hm), 1e-9);
  }

  @Test
  public void cornerMaskIou_isOneWhenQuadAndMaskCoincide() {
    // Quad over cells [16,48)² in 64-space = [64,192]² in 256-space.
    double[][] corners256 = {{64, 64}, {192, 64}, {192, 192}, {64, 192}};
    assertEquals(
        1.0, DocQuadPostprocessor.cornerMaskIou(corners256, rectMask(16, 16, 48, 48)), 1e-9);
  }

  @Test
  public void cornerMaskIou_dropsWhenMaskIsElsewhere() {
    double[][] corners256 = {{64, 64}, {192, 64}, {192, 192}, {64, 192}};
    // Half overlap: mask shifted by half the quad width → IoU = 1/3.
    assertEquals(
        1.0 / 3.0, DocQuadPostprocessor.cornerMaskIou(corners256, rectMask(32, 16, 64, 48)), 1e-9);
    assertEquals(
        0.0, DocQuadPostprocessor.cornerMaskIou(corners256, rectMask(0, 0, 8, 8)), 1e-9);
  }

  @Test
  public void postprocess_exposesConfidenceAsProduct() {
    float[][][][] hm = heatmaps(-10f);
    hm[0][0][16][16] = 6f;
    hm[0][1][16][47] = 6f;
    hm[0][2][47][47] = 6f;
    hm[0][3][47][16] = 6f;
    DocQuadPostprocessor.Result r =
        DocQuadPostprocessor.postprocess(
            hm, rectMask(16, 16, 48, 48), null, DocQuadPostprocessor.PeakMode.ARGMAX);

    assertTrue(r.minPeakProb() > 0.99);
    assertTrue(r.cornerMaskIou() > 0.9);
    assertEquals(r.minPeakProb() * r.cornerMaskIou(), r.cornerConfidence(), 1e-12);
  }
}
