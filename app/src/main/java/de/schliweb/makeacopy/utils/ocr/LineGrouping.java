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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Groups text boxes (words or line fragments) into visual lines by vertical position.
 *
 * <p>One rule for the whole app: two boxes share a line when their vertical anchors lie within
 * {@link #LINE_TOLERANCE_FACTOR} times the line height of each other. The line height is the
 * height of the boxes involved, capped at {@link #TALL_BOX_FACTOR} times the page's median box
 * height, so that a single tall box cannot widen the tolerance beyond the line pitch. A box that
 * is taller than that cap (a drop cap, a headline word next to body text) is anchored at the line
 * its top edge starts, not at its geometric centre, because that is the line it belongs to for
 * reading: a two-line drop cap "E" reads with "s war ein ...", not with the line below.
 *
 * <p>Pure Java, no Android dependencies, so the JVM tests cover it and the layout policies can
 * share it.
 */
public final class LineGrouping {

  /** Vertical tolerance for two boxes to share a line, as a factor of the line height. */
  public static final float LINE_TOLERANCE_FACTOR = 0.6f;

  /**
   * Boxes taller than this factor times the median box height are "tall": they neither widen the
   * line tolerance nor are they placed by their centre.
   */
  public static final float TALL_BOX_FACTOR = 1.5f;

  private LineGrouping() {}

  /**
   * Groups the boxes into lines.
   *
   * @return the lines top-to-bottom, each an array of box indices ordered left-to-right by their
   *     left edge (callers that need right-to-left order re-sort the members)
   */
  public static List<int[]> groupIntoLines(
      float[] lefts, float[] tops, float[] rights, float[] bottoms) {
    int n = lefts.length;
    List<int[]> lines = new ArrayList<>();
    if (n == 0) return lines;
    float[] heights = new float[n];
    for (int i = 0; i < n; i++) heights[i] = Math.max(1f, bottoms[i] - tops[i]);
    float[] sorted = heights.clone();
    Arrays.sort(sorted);
    float median = sorted[n / 2];
    float tallLimit = TALL_BOX_FACTOR * median;

    float[] anchors = new float[n];
    float[] lineHeights = new float[n];
    for (int i = 0; i < n; i++) {
      if (heights[i] > tallLimit) {
        anchors[i] = tops[i] + 0.5f * median;
        lineHeights[i] = median;
      } else {
        anchors[i] = 0.5f * (tops[i] + bottoms[i]);
        lineHeights[i] = heights[i];
      }
    }

    // Strict, transitive pre-order: TimSort rejects pair-dependent comparators on dense boxes.
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) order[i] = i;
    Arrays.sort(
        order,
        (a, b) -> {
          int c = Float.compare(anchors[a], anchors[b]);
          if (c != 0) return c;
          c = Float.compare(lefts[a], lefts[b]);
          if (c != 0) return c;
          return Integer.compare(a, b);
        });

    List<Integer> current = new ArrayList<>();
    float refAnchor = 0f;
    float refHeight = 0f;
    for (int idx : order) {
      if (current.isEmpty()) {
        current.add(idx);
        refAnchor = anchors[idx];
        refHeight = lineHeights[idx];
        continue;
      }
      float tolerance = LINE_TOLERANCE_FACTOR * Math.max(refHeight, lineHeights[idx]);
      if (Math.abs(anchors[idx] - refAnchor) <= tolerance) {
        current.add(idx);
        refHeight = Math.max(refHeight, lineHeights[idx]);
      } else {
        lines.add(sortedByLeft(current, lefts));
        current = new ArrayList<>();
        current.add(idx);
        refAnchor = anchors[idx];
        refHeight = lineHeights[idx];
      }
    }
    if (!current.isEmpty()) lines.add(sortedByLeft(current, lefts));
    return lines;
  }

  private static int[] sortedByLeft(List<Integer> members, float[] lefts) {
    members.sort(
        (a, b) -> {
          int c = Float.compare(lefts[a], lefts[b]);
          return c != 0 ? c : Integer.compare(a, b);
        });
    int[] out = new int[members.size()];
    for (int i = 0; i < out.length; i++) out[i] = members.get(i);
    return out;
  }
}
