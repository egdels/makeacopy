/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ml.corners;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the pure-geometry helpers of {@link EdgeSnapCornerRefiner} (TLS line fit, line
 * intersection, point-line distance) and the null/degenerate-input guards of {@code refine}.
 */
public class EdgeSnapCornerRefinerTest {

  private static final double EPS = 1e-6;

  // ---------- fitLineTLS ----------

  @Test
  public void fitLineTLS_horizontalLine() {
    double[] xs = {0, 1, 2, 3, 4};
    double[] ys = {5, 5, 5, 5, 5};
    double[] line = EdgeSnapCornerRefiner.fitLineTLS(xs, ys, 5);
    assertNotNull(line);
    // Direction must be (±1, 0)
    assertEquals(1.0, Math.abs(line[2]), EPS);
    assertEquals(0.0, line[3], EPS);
    // Line passes through y=5
    assertEquals(5.0, line[1], EPS);
  }

  @Test
  public void fitLineTLS_verticalLine() {
    double[] xs = {2, 2, 2, 2};
    double[] ys = {0, 1, 2, 3};
    double[] line = EdgeSnapCornerRefiner.fitLineTLS(xs, ys, 4);
    assertNotNull(line);
    // Direction must be (0, ±1) — plain least-squares y(x) would fail here, TLS must not.
    assertEquals(0.0, line[2], EPS);
    assertEquals(1.0, Math.abs(line[3]), EPS);
    assertEquals(2.0, line[0], EPS);
  }

  @Test
  public void fitLineTLS_diagonalLineWithNoise() {
    int n = 20;
    double[] xs = new double[n];
    double[] ys = new double[n];
    for (int i = 0; i < n; i++) {
      xs[i] = i;
      ys[i] = i + ((i % 2 == 0) ? 0.01 : -0.01); // tiny symmetric noise
    }
    double[] line = EdgeSnapCornerRefiner.fitLineTLS(xs, ys, n);
    assertNotNull(line);
    double slope = line[3] / line[2];
    assertEquals(1.0, slope, 0.01);
  }

  @Test
  public void fitLineTLS_degenerateInputs() {
    assertNull(EdgeSnapCornerRefiner.fitLineTLS(null, new double[2], 2));
    assertNull(EdgeSnapCornerRefiner.fitLineTLS(new double[2], null, 2));
    assertNull(EdgeSnapCornerRefiner.fitLineTLS(new double[] {1}, new double[] {1}, 1));
    // All points identical
    assertNull(
        EdgeSnapCornerRefiner.fitLineTLS(new double[] {3, 3, 3}, new double[] {4, 4, 4}, 3));
  }

  // ---------- intersectLines ----------

  @Test
  public void intersectLines_perpendicular() {
    double[] horizontal = {0, 5, 1, 0}; // y = 5
    double[] vertical = {2, 0, 0, 1}; // x = 2
    double[] p = EdgeSnapCornerRefiner.intersectLines(horizontal, vertical);
    assertNotNull(p);
    assertEquals(2.0, p[0], EPS);
    assertEquals(5.0, p[1], EPS);
  }

  @Test
  public void intersectLines_diagonals() {
    double[] l1 = {0, 0, 1, 1}; // y = x
    double[] l2 = {0, 4, 1, -1}; // y = 4 - x
    double[] p = EdgeSnapCornerRefiner.intersectLines(l1, l2);
    assertNotNull(p);
    assertEquals(2.0, p[0], EPS);
    assertEquals(2.0, p[1], EPS);
  }

  @Test
  public void intersectLines_parallelReturnsNull() {
    double[] l1 = {0, 0, 1, 0};
    double[] l2 = {0, 5, 1, 0};
    assertNull(EdgeSnapCornerRefiner.intersectLines(l1, l2));
  }

  @Test
  public void intersectLines_invalidInputs() {
    assertNull(EdgeSnapCornerRefiner.intersectLines(null, new double[] {0, 0, 1, 0}));
    assertNull(EdgeSnapCornerRefiner.intersectLines(new double[] {0, 0, 1, 0}, null));
    assertNull(EdgeSnapCornerRefiner.intersectLines(new double[] {0, 0, 1}, new double[] {0, 0, 1, 0}));
  }

  // ---------- pointLineDistance ----------

  @Test
  public void pointLineDistance_signedDistance() {
    double[] horizontal = {0, 0, 1, 0}; // x-axis
    assertEquals(3.0, Math.abs(EdgeSnapCornerRefiner.pointLineDistance(horizontal, 7, 3)), EPS);
    assertEquals(0.0, EdgeSnapCornerRefiner.pointLineDistance(horizontal, -4, 0), EPS);
    // Opposite sides yield opposite signs
    double d1 = EdgeSnapCornerRefiner.pointLineDistance(horizontal, 0, 2);
    double d2 = EdgeSnapCornerRefiner.pointLineDistance(horizontal, 0, -2);
    assertTrue(d1 * d2 < 0);
  }

  // ---------- refine guards (no OpenCV / Bitmap required) ----------

  @Test
  public void refine_nullBitmapReturnsInputReference() {
    double[][] quad = {{0, 0}, {10, 0}, {10, 10}, {0, 10}};
    assertSame(quad, EdgeSnapCornerRefiner.refine(null, quad));
  }

  @Test
  public void refine_invalidQuadReturnsInputReference() {
    double[][] tooFew = {{0, 0}, {10, 0}, {10, 10}};
    assertSame(tooFew, EdgeSnapCornerRefiner.refine(null, tooFew));
    double[][] nan = {{Double.NaN, 0}, {10, 0}, {10, 10}, {0, 10}};
    assertSame(nan, EdgeSnapCornerRefiner.refine(null, nan));
  }
}
