/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.image;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Tests for {@link CoordinateTransformUtils#remapPointBetweenFitCenterViews(double, double, int,
 * int, int, int, int, int)}.
 *
 * <p>Regression scenario: after capturing a scan, the crop view is first laid out at 1080x1601
 * (IME/insets not yet settled), corner detection runs and maps corners into view coordinates of
 * that size, and afterwards the view shrinks to 1080x1546. A plain proportional scaling relative to
 * the view height ignores the changed letterbox offset and shifts the corners off the document
 * edges; the FIT_CENTER-aware remap must keep them on the same image points.
 */
public class CoordinateTransformUtilsRemapTest {

  private static final double EPS = 0.01;

  private static final int BMP_W = 3024;
  private static final int BMP_H = 4032;

  /** Maps an image-space point to view coordinates using FIT_CENTER (letterbox case). */
  private static double[] imageToView(double ix, double iy, int viewW, int viewH) {
    float scale = (float) viewW / BMP_W;
    float offsetY = (viewH - (BMP_H * scale)) / 2f;
    return new double[] {ix * scale, iy * scale + offsetY};
  }

  @Test
  public void remap_keepsPointOnSameImageFeature_whenViewHeightShrinks() {
    // Detected document corner in image coordinates (from the real log)
    double imageX = 628.0516469689675;
    double imageY = 609.9188407554702;

    int oldW = 1080, oldH = 1601;
    int newW = 1080, newH = 1546;

    double[] oldView = imageToView(imageX, imageY, oldW, oldH);
    double[] expectedNewView = imageToView(imageX, imageY, newW, newH);

    double[] remapped =
        CoordinateTransformUtils.remapPointBetweenFitCenterViews(
            oldView[0], oldView[1], BMP_W, BMP_H, oldW, oldH, newW, newH);

    assertNotNull(remapped);
    assertArrayEquals(expectedNewView, remapped, EPS);
  }

  @Test
  public void remap_isIdentity_whenViewSizeUnchanged() {
    double[] remapped =
        CoordinateTransformUtils.remapPointBetweenFitCenterViews(
            224.3, 298.33, BMP_W, BMP_H, 1080, 1601, 1080, 1601);

    assertNotNull(remapped);
    assertArrayEquals(new double[] {224.3, 298.33}, remapped, EPS);
  }

  @Test
  public void remap_handlesPillarboxToLetterboxTransition() {
    // Wide image inside a portrait view (letterboxed) then a landscape view (pillarboxed)
    int bmpW = 4032, bmpH = 3024;
    double imageX = 1000.0, imageY = 500.0;

    int oldW = 1080, oldH = 1920; // letterboxed: scale=1080/4032
    int newW = 1920, newH = 1080; // pillarboxed: scale=1080/3024

    float oldScale = (float) oldW / bmpW;
    float oldOffsetY = (oldH - (bmpH * oldScale)) / 2f;
    double[] oldView = new double[] {imageX * oldScale, imageY * oldScale + oldOffsetY};

    float newScale = (float) newH / bmpH;
    float newOffsetX = (newW - (bmpW * newScale)) / 2f;
    double[] expected = new double[] {imageX * newScale + newOffsetX, imageY * newScale};

    double[] remapped =
        CoordinateTransformUtils.remapPointBetweenFitCenterViews(
            oldView[0], oldView[1], bmpW, bmpH, oldW, oldH, newW, newH);

    assertNotNull(remapped);
    assertArrayEquals(expected, remapped, EPS);
  }

  @Test
  public void remap_returnsNull_onInvalidDimensions() {
    assertNull(
        CoordinateTransformUtils.remapPointBetweenFitCenterViews(
            0, 0, 0, BMP_H, 1080, 1601, 1080, 1546));
    assertNull(
        CoordinateTransformUtils.remapPointBetweenFitCenterViews(
            0, 0, BMP_W, BMP_H, 0, 1601, 1080, 1546));
    assertNull(
        CoordinateTransformUtils.remapPointBetweenFitCenterViews(
            0, 0, BMP_W, BMP_H, 1080, 1601, 1080, 0));
  }
}
