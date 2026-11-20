/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.crop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** Unit tests for {@link CropGestureExclusion}. View is 1600 px high, budget 500 px per side. */
public class CropGestureExclusionTest {
  private static final int HEIGHT = 1600;
  private static final int BUDGET = 500;

  private static List<int[]> spans(float[] centers, float[] radii) {
    return CropGestureExclusion.spans(centers, radii, centers.length, HEIGHT, BUDGET);
  }

  private static int total(List<int[]> spans) {
    int sum = 0;
    for (int[] s : spans) sum += s[1] - s[0];
    return sum;
  }

  @Test
  public void handleAtTheTopIsCoveredNotOnlyTheBottomOfTheSide() {
    List<int[]> spans = spans(new float[] {300f, 1300f}, new float[] {70f, 70f});
    assertEquals(2, spans.size());
    assertArrayEquals(new int[] {230, 370}, spans.get(0));
    assertArrayEquals(new int[] {1230, 1370}, spans.get(1));
  }

  @Test
  public void spansAreClampedToTheView() {
    List<int[]> spans = spans(new float[] {10f, 1590f}, new float[] {70f, 70f});
    assertArrayEquals(new int[] {0, 80}, spans.get(0));
    assertArrayEquals(new int[] {1520, 1600}, spans.get(1));
  }

  @Test
  public void overlappingHandlesShareOneSpan() {
    List<int[]> spans = spans(new float[] {300f, 380f}, new float[] {70f, 70f});
    assertEquals(1, spans.size());
    assertArrayEquals(new int[] {230, 450}, spans.get(0));
  }

  @Test
  public void totalNeverExceedsTheBudgetAndEarlierHandlesWin() {
    float[] centers = {200f, 600f, 1000f, 1400f};
    float[] radii = {100f, 100f, 100f, 100f};
    List<int[]> spans = spans(centers, radii);
    assertTrue(total(spans) <= BUDGET);
    // The first two are whole, the third keeps its centre with what is left, the fourth is out
    assertArrayEquals(new int[] {100, 300}, spans.get(0));
    assertArrayEquals(new int[] {500, 700}, spans.get(1));
    assertArrayEquals(new int[] {950, 1050}, spans.get(2));
    assertEquals(3, spans.size());
  }

  @Test
  public void noHandlesNoSpans() {
    assertTrue(spans(new float[0], new float[0]).isEmpty());
  }
}
