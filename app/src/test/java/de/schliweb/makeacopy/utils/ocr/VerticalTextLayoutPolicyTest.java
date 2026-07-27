/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * JVM unit tests for {@link VerticalTextLayoutPolicy}. The policy is a pure function without
 * Android dependencies; the {@code RecognizedWord} adapter path is exercised on-device only
 * (RectF is not available with {@code unitTests.returnDefaultValues=true}), so these tests use
 * the primitive {@code float[]} API.
 */
public class VerticalTextLayoutPolicyTest {

  // ---------------------------------------------------------------------
  // isVerticalLayout(float[], float[])
  // ---------------------------------------------------------------------

  @Test
  public void isVerticalLayout_allTallBoxes_true() {
    // Vertical Japanese page: word boxes are tall columns.
    float[] w = {30f, 30f, 30f};
    float[] h = {400f, 380f, 420f};
    assertTrue(VerticalTextLayoutPolicy.isVerticalLayout(w, h));
  }

  @Test
  public void isVerticalLayout_horizontalDocument_false() {
    float[] w = {120f, 80f, 200f};
    float[] h = {30f, 28f, 32f};
    assertFalse(VerticalTextLayoutPolicy.isVerticalLayout(w, h));
  }

  @Test
  public void isVerticalLayout_singleTallBox_false() {
    // A single narrow hit (e.g. the letter "I" or a digit) must not flip the layout.
    assertFalse(VerticalTextLayoutPolicy.isVerticalLayout(new float[] {20f}, new float[] {60f}));
  }

  @Test
  public void isVerticalLayout_mixedMajorityTall_true() {
    // 3 of 4 tall → dominance above 0.5.
    float[] w = {30f, 30f, 30f, 200f};
    float[] h = {400f, 380f, 420f, 30f};
    assertTrue(VerticalTextLayoutPolicy.isVerticalLayout(w, h));
  }

  @Test
  public void isVerticalLayout_exactlyHalfTall_false() {
    // Dominance requires strictly more than half.
    float[] w = {30f, 200f};
    float[] h = {400f, 30f};
    assertFalse(VerticalTextLayoutPolicy.isVerticalLayout(w, h));
  }

  @Test
  public void isVerticalLayout_boundaryAspectRatioCountsAsTall() {
    // h/w == 1.5 counts as tall (>=), mirroring the PaddleOCR crop-rotation rule.
    float[] w = {20f, 20f};
    float[] h = {30f, 30f};
    assertTrue(VerticalTextLayoutPolicy.isVerticalLayout(w, h));
  }

  @Test
  public void isVerticalLayout_invalidInputs_false() {
    assertFalse(VerticalTextLayoutPolicy.isVerticalLayout(null, new float[] {1f}));
    assertFalse(VerticalTextLayoutPolicy.isVerticalLayout(new float[] {1f}, null));
    assertFalse(
        VerticalTextLayoutPolicy.isVerticalLayout(new float[] {1f}, new float[] {1f, 2f}));
    assertFalse(VerticalTextLayoutPolicy.isVerticalLayout(new float[] {}, new float[] {}));
  }

  @Test
  public void isVerticalLayout_customThresholds_respected() {
    float[] w = {30f, 30f};
    float[] h = {40f, 40f}; // ratio 1.33
    // Default ratio 1.5 → not tall.
    assertFalse(VerticalTextLayoutPolicy.isVerticalLayout(w, h));
    // Lower ratio threshold → tall.
    assertTrue(VerticalTextLayoutPolicy.isVerticalLayout(w, h, 1.2f, 2, 0.5f));
    // Higher minBoxes → classification does not apply.
    assertFalse(VerticalTextLayoutPolicy.isVerticalLayout(w, h, 1.2f, 3, 0.5f));
  }

  // ---------------------------------------------------------------------
  // groupIntoVerticalColumns
  // ---------------------------------------------------------------------

  /** Box helper: fills the four edge arrays at {@code i} with a rect at (x, y, w, h). */
  private static void box(
      float[] l, float[] t, float[] r, float[] b, int i, float x, float y, float w, float h) {
    l[i] = x;
    t[i] = y;
    r[i] = x + w;
    b[i] = y + h;
  }

  @Test
  public void groupIntoVerticalColumns_threeColumns_orderedRightToLeft() {
    // Right column (x=300) = sentence 1, middle (x=200) = sentence 2, left (x=100) = sentence 3.
    float[] l = new float[3], t = new float[3], r = new float[3], b = new float[3];
    box(l, t, r, b, 0, 100, 0, 30, 400); // left / last
    box(l, t, r, b, 1, 300, 0, 30, 400); // right / first
    box(l, t, r, b, 2, 200, 0, 30, 400); // middle / second
    List<int[]> cols = VerticalTextLayoutPolicy.groupIntoVerticalColumns(l, t, r, b);

    assertEquals(3, cols.size());
    assertArrayEquals(new int[] {1}, cols.get(0)); // right first
    assertArrayEquals(new int[] {2}, cols.get(1)); // then middle
    assertArrayEquals(new int[] {0}, cols.get(2)); // left last
  }

  @Test
  public void groupIntoVerticalColumns_segmentsWithinColumn_topToBottom() {
    float[] l = new float[2], t = new float[2], r = new float[2], b = new float[2];
    box(l, t, r, b, 0, 300, 220, 30, 180); // bottom segment
    box(l, t, r, b, 1, 300, 0, 30, 180); // top segment
    List<int[]> cols = VerticalTextLayoutPolicy.groupIntoVerticalColumns(l, t, r, b);

    assertEquals(1, cols.size());
    assertArrayEquals(new int[] {1, 0}, cols.get(0));
  }

  @Test
  public void groupIntoVerticalColumns_slightXJitterStaysInSameColumn() {
    // 5 px jitter < tolerance (0.6 × median width 30 = 18 px) → same column, top→bottom.
    float[] l = new float[2], t = new float[2], r = new float[2], b = new float[2];
    box(l, t, r, b, 0, 305, 220, 30, 180);
    box(l, t, r, b, 1, 300, 0, 30, 180);
    List<int[]> cols = VerticalTextLayoutPolicy.groupIntoVerticalColumns(l, t, r, b);

    assertEquals(1, cols.size());
    assertArrayEquals(new int[] {1, 0}, cols.get(0));
  }

  @Test
  public void groupIntoVerticalColumns_geometricTies_stableByIndex() {
    // Identical geometry → order must fall back to the original index (deterministic).
    float[] l = new float[3], t = new float[3], r = new float[3], b = new float[3];
    box(l, t, r, b, 0, 300, 0, 30, 400);
    box(l, t, r, b, 1, 300, 0, 30, 400);
    box(l, t, r, b, 2, 300, 0, 30, 400);
    List<int[]> cols = VerticalTextLayoutPolicy.groupIntoVerticalColumns(l, t, r, b);

    assertEquals(1, cols.size());
    assertArrayEquals(new int[] {0, 1, 2}, cols.get(0));
  }

  @Test
  public void groupIntoVerticalColumns_singleColumn_singleBox() {
    float[] l = new float[1], t = new float[1], r = new float[1], b = new float[1];
    box(l, t, r, b, 0, 300, 0, 30, 400);
    List<int[]> cols = VerticalTextLayoutPolicy.groupIntoVerticalColumns(l, t, r, b);

    assertEquals(1, cols.size());
    assertArrayEquals(new int[] {0}, cols.get(0));
  }

  @Test
  public void groupIntoVerticalColumns_columnsWithDifferentHeights() {
    // Shorter left column must still come after the taller right column.
    float[] l = new float[2], t = new float[2], r = new float[2], b = new float[2];
    box(l, t, r, b, 0, 100, 0, 30, 150); // left, short
    box(l, t, r, b, 1, 300, 0, 30, 400); // right, tall
    List<int[]> cols = VerticalTextLayoutPolicy.groupIntoVerticalColumns(l, t, r, b);

    assertEquals(2, cols.size());
    assertArrayEquals(new int[] {1}, cols.get(0));
    assertArrayEquals(new int[] {0}, cols.get(1));
  }

  @Test
  public void groupIntoVerticalColumns_invalidInputs_emptyResult() {
    assertTrue(
        VerticalTextLayoutPolicy.groupIntoVerticalColumns(null, null, null, null).isEmpty());
    assertTrue(
        VerticalTextLayoutPolicy.groupIntoVerticalColumns(
                new float[] {}, new float[] {}, new float[] {}, new float[] {})
            .isEmpty());
    assertTrue(
        VerticalTextLayoutPolicy.groupIntoVerticalColumns(
                new float[] {1f}, new float[] {1f, 2f}, new float[] {1f}, new float[] {1f})
            .isEmpty());
  }

  // ---------------------------------------------------------------------
  // shouldPreferZeroRotation
  // ---------------------------------------------------------------------

  @Test
  public void shouldPreferZeroRotation_zeroOr180Best_false() {
    // Guard only applies when a 90°/270° attempt "won".
    assertFalse(VerticalTextLayoutPolicy.shouldPreferZeroRotation(0, true, null));
    assertFalse(VerticalTextLayoutPolicy.shouldPreferZeroRotation(180, true, null));
  }

  @Test
  public void shouldPreferZeroRotation_zeroAttemptEmpty_false() {
    // Without content at 0° there is nothing to prefer — rotation stays legitimate.
    assertFalse(VerticalTextLayoutPolicy.shouldPreferZeroRotation(270, false, null));
    assertFalse(VerticalTextLayoutPolicy.shouldPreferZeroRotation(90, false, null));
  }

  @Test
  public void shouldPreferZeroRotation_noWords_false() {
    // Content but no word geometry → no vertical-layout evidence → keep rotation.
    assertFalse(VerticalTextLayoutPolicy.shouldPreferZeroRotation(270, true, null));
    assertFalse(
        VerticalTextLayoutPolicy.shouldPreferZeroRotation(270, true, Collections.emptyList()));
  }
}
