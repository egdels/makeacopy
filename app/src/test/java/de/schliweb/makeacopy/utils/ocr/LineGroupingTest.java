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

import java.util.List;
import org.junit.Test;

public class LineGroupingTest {

  /** Boxes as {left, top, right, bottom}. */
  private static List<int[]> group(float[][] boxes) {
    int n = boxes.length;
    float[] l = new float[n], t = new float[n], r = new float[n], b = new float[n];
    for (int i = 0; i < n; i++) {
      l[i] = boxes[i][0];
      t[i] = boxes[i][1];
      r[i] = boxes[i][2];
      b[i] = boxes[i][3];
    }
    return LineGrouping.groupIntoLines(l, t, r, b);
  }

  @Test
  public void bodyText_twoLinesOfThreeWords() {
    float[][] boxes = {
      {0, 0, 50, 30}, {60, 1, 110, 31}, {120, 0, 170, 30},
      {0, 50, 50, 80}, {60, 51, 110, 81}, {120, 49, 170, 79},
    };
    List<int[]> lines = group(boxes);
    assertEquals(2, lines.size());
    assertArrayEquals(new int[] {0, 1, 2}, lines.get(0));
    assertArrayEquals(new int[] {3, 4, 5}, lines.get(1));
  }

  @Test
  public void dropCap_joinsTheLineItsTopStartsAndDoesNotSwallowTheNextLine() {
    // Two-line initial "E" (height 100) beside body lines of height 30 with a pitch of 50: the
    // old per-word tolerance (0.6 x 100 = 60 > pitch) pulled the second line into the first.
    float[][] boxes = {
      {0, 0, 70, 100}, // E
      {80, 0, 300, 30}, {310, 0, 400, 30}, // line 1
      {80, 50, 300, 80}, {310, 50, 400, 80}, // line 2
      {0, 100, 300, 130}, // line 3 (full width again)
    };
    List<int[]> lines = group(boxes);
    assertEquals(3, lines.size());
    assertArrayEquals(new int[] {0, 1, 2}, lines.get(0));
    assertArrayEquals(new int[] {3, 4}, lines.get(1));
    assertArrayEquals(new int[] {5}, lines.get(2));
  }

  @Test
  public void mixedSizesOnOneRow_stayTogether() {
    // A headline word (height 45) next to body words (height 30) on the same baseline row.
    float[][] boxes = {
      {0, 0, 100, 45}, {110, 8, 200, 38}, {210, 7, 300, 37},
      {0, 70, 100, 100},
    };
    List<int[]> lines = group(boxes);
    assertEquals(2, lines.size());
    assertArrayEquals(new int[] {0, 1, 2}, lines.get(0));
  }

  @Test
  public void membersAreOrderedLeftToRightRegardlessOfInputOrder() {
    float[][] boxes = {{200, 0, 250, 30}, {0, 0, 50, 30}, {100, 0, 150, 30}};
    List<int[]> lines = group(boxes);
    assertEquals(1, lines.size());
    assertArrayEquals(new int[] {1, 2, 0}, lines.get(0));
  }

  @Test
  public void empty_returnsNoLines() {
    assertEquals(0, group(new float[0][]).size());
  }
}
