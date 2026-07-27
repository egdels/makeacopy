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

import java.util.List;

/**
 * Policy that detects a vertical text layout (CJK top-to-bottom writing, columns read
 * right-to-left) from the geometry of recognized words and decides whether the auto-rotate
 * heuristic must be suppressed.
 *
 * <p>Background: the auto-rotate loop tries the page at 0/90/180/270 degrees and keeps the attempt
 * with the highest mean confidence. For genuinely vertical documents (e.g. Japanese books) the
 * 90/270 degree attempts can "win" because vertical columns then look like horizontal lines to the
 * detector — but the page itself is correctly oriented, so applying that rotation to the scan is
 * wrong and the recognition quality degrades. When the 0-degree attempt already produced content
 * whose boxes are predominantly tall (portrait-shaped), the layout is vertical and the 0-degree
 * result must be preferred.
 *
 * <p>This class is a pure function and intentionally has no Android dependencies so that it is
 * fully covered by JVM unit tests.
 */
public final class VerticalTextLayoutPolicy {

  /**
   * Aspect ratio (height/width) at or above which a word bounding box counts as "tall". Matches the
   * crop-rotation threshold used by the PaddleOCR reference implementation (h/w &gt;= 1.5).
   */
  public static final float DEFAULT_TALL_BOX_ASPECT_RATIO = 1.5f;

  /** Minimum number of boxes required before a page can be classified as vertical layout. */
  public static final int DEFAULT_MIN_BOXES = 2;

  /** Fraction of tall boxes above which the page counts as vertical layout. */
  public static final float DEFAULT_DOMINANCE_FRACTION = 0.5f;

  private VerticalTextLayoutPolicy() {
    // utility
  }

  /**
   * Returns {@code true} if the given box dimensions indicate a vertical text layout with the
   * default thresholds.
   *
   * @param widths widths of the word bounding boxes
   * @param heights heights of the word bounding boxes (same length as {@code widths})
   * @return {@code true} iff tall boxes dominate
   */
  public static boolean isVerticalLayout(float[] widths, float[] heights) {
    return isVerticalLayout(
        widths,
        heights,
        DEFAULT_TALL_BOX_ASPECT_RATIO,
        DEFAULT_MIN_BOXES,
        DEFAULT_DOMINANCE_FRACTION);
  }

  /**
   * Variant with explicit thresholds, primarily for testing and tuning.
   *
   * @param widths widths of the word bounding boxes
   * @param heights heights of the word bounding boxes (same length as {@code widths})
   * @param tallAspectRatio minimum height/width ratio for a box to count as tall
   * @param minBoxes minimum number of boxes for the classification to apply
   * @param dominanceFraction fraction of tall boxes above which the layout is vertical
   * @return {@code true} iff tall boxes dominate
   */
  public static boolean isVerticalLayout(
      float[] widths,
      float[] heights,
      float tallAspectRatio,
      int minBoxes,
      float dominanceFraction) {
    if (widths == null || heights == null || widths.length != heights.length) return false;
    if (widths.length < minBoxes) return false;
    int tall = 0;
    for (int i = 0; i < widths.length; i++) {
      float w = Math.max(1f, widths[i]);
      float h = Math.max(1f, heights[i]);
      if (h / w >= tallAspectRatio) tall++;
    }
    return tall > widths.length * dominanceFraction;
  }

  /**
   * Convenience adapter for {@link RecognizedWord} lists. Extracts box dimensions and delegates to
   * {@link #isVerticalLayout(float[], float[])}.
   *
   * @param words recognized words with bounding boxes (may be {@code null})
   * @return {@code true} iff tall boxes dominate
   */
  public static boolean isVerticalLayout(List<RecognizedWord> words) {
    if (words == null || words.isEmpty()) return false;
    float[] widths = new float[words.size()];
    float[] heights = new float[words.size()];
    int n = 0;
    for (RecognizedWord w : words) {
      if (w == null || w.getBoundingBox() == null) continue;
      widths[n] = w.getBoundingBox().right - w.getBoundingBox().left;
      heights[n] = w.getBoundingBox().bottom - w.getBoundingBox().top;
      n++;
    }
    if (n != words.size()) {
      widths = java.util.Arrays.copyOf(widths, n);
      heights = java.util.Arrays.copyOf(heights, n);
    }
    return isVerticalLayout(widths, heights);
  }

  /**
   * Tolerance factor for grouping boxes into vertical columns: two boxes belong to the same column
   * when their center-X distance is at most {@code factor × median box width}. Matches the
   * line-grouping tolerance used by the Paddle result builder.
   */
  public static final float COLUMN_TOLERANCE_FACTOR = 0.6f;

  /**
   * Groups word boxes of a vertical (CJK top-to-bottom) page into columns and returns them in
   * reading order: columns sorted right-to-left by their horizontal position, segments within a
   * column sorted top-to-bottom. Ties are broken deterministically (top ascending, then left
   * descending, then original index ascending) so equal geometries always yield a stable order.
   *
   * <p>The grouping operates on the original page coordinates of the boxes only; it is a pure
   * function without Android dependencies so that it is fully covered by JVM unit tests.
   *
   * @param lefts left edges of the boxes
   * @param tops top edges of the boxes (same length)
   * @param rights right edges of the boxes (same length)
   * @param bottoms bottom edges of the boxes (same length)
   * @return list of columns in reading order (right→left); each column contains the indices of its
   *     boxes ordered top→bottom. Empty list for invalid input.
   */
  public static List<int[]> groupIntoVerticalColumns(
      float[] lefts, float[] tops, float[] rights, float[] bottoms) {
    List<int[]> columns = new java.util.ArrayList<>();
    if (lefts == null
        || tops == null
        || rights == null
        || bottoms == null
        || lefts.length == 0
        || lefts.length != tops.length
        || lefts.length != rights.length
        || lefts.length != bottoms.length) {
      return columns;
    }
    int n = lefts.length;
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) order[i] = i;
    // Right→left by center-X; deterministic tie-breaks: top ascending, left descending, index.
    java.util.Arrays.sort(
        order,
        (a, b) -> {
          float cxa = 0.5f * (lefts[a] + rights[a]);
          float cxb = 0.5f * (lefts[b] + rights[b]);
          int c = Float.compare(cxb, cxa);
          if (c != 0) return c;
          c = Float.compare(tops[a], tops[b]);
          if (c != 0) return c;
          c = Float.compare(lefts[b], lefts[a]);
          if (c != 0) return c;
          return Integer.compare(a, b);
        });

    // Median box width as scale reference for the column tolerance.
    float[] widths = new float[n];
    for (int i = 0; i < n; i++) widths[i] = Math.max(1f, rights[i] - lefts[i]);
    float[] sortedWidths = widths.clone();
    java.util.Arrays.sort(sortedWidths);
    float tol = COLUMN_TOLERANCE_FACTOR * sortedWidths[n / 2];

    List<Integer> current = new java.util.ArrayList<>();
    float currentRefX = Float.NaN;
    for (int idx : order) {
      float cx = 0.5f * (lefts[idx] + rights[idx]);
      if (current.isEmpty()) {
        current.add(idx);
        currentRefX = cx;
      } else if (Math.abs(cx - currentRefX) <= tol) {
        current.add(idx);
        // Running mean as reference, robust against slight drift within a column.
        currentRefX = (currentRefX * (current.size() - 1) + cx) / current.size();
      } else {
        columns.add(finishColumn(current, tops, bottoms));
        current = new java.util.ArrayList<>();
        current.add(idx);
        currentRefX = cx;
      }
    }
    if (!current.isEmpty()) {
      columns.add(finishColumn(current, tops, bottoms));
    }
    return columns;
  }

  /** Sorts a column's indices top→bottom (deterministic tie-break on index) and materializes. */
  private static int[] finishColumn(List<Integer> column, float[] tops, float[] bottoms) {
    column.sort(
        (a, b) -> {
          float cya = 0.5f * (tops[a] + bottoms[a]);
          float cyb = 0.5f * (tops[b] + bottoms[b]);
          int c = Float.compare(cya, cyb);
          if (c != 0) return c;
          return Integer.compare(a, b);
        });
    int[] out = new int[column.size()];
    for (int i = 0; i < out.length; i++) out[i] = column.get(i);
    return out;
  }

  /**
   * Decides whether the auto-rotate result must be overridden in favor of the 0-degree attempt.
   *
   * @param bestRotationDegrees rotation of the currently best OCR attempt (0/90/180/270)
   * @param zeroAttemptHasContent whether the 0-degree attempt produced words or text
   * @param zeroAttemptWords words of the 0-degree attempt (used for layout detection)
   * @return {@code true} iff a 90/270-degree rotation "won" although the 0-degree attempt shows a
   *     vertical text layout — i.e. the page is correctly oriented and must not be rotated
   */
  public static boolean shouldPreferZeroRotation(
      int bestRotationDegrees,
      boolean zeroAttemptHasContent,
      List<RecognizedWord> zeroAttemptWords) {
    if (bestRotationDegrees != 90 && bestRotationDegrees != 270) return false;
    if (!zeroAttemptHasContent) return false;
    return isVerticalLayout(zeroAttemptWords);
  }
}
