/*
 * Copyright 2026 Christian Kierdorf
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
 * Policy that detects a multi-column text layout (newspapers, magazines) from the geometry of
 * recognized line boxes and reconstructs the reading order column by column.
 *
 * <p>Background: PaddleOCR emits one {@code RecognizedWord} per detected text line. The default
 * text builder groups boxes into visual lines purely by vertical proximity, which interleaves the
 * lines of side-by-side columns and destroys the text flow. This policy clusters the boxes into
 * columns using their horizontal extent, treats page-wide boxes (headlines, captions) as full-width
 * separators, and returns segments in reading order: full-width band, then the columns of the next
 * band left-to-right (or right-to-left for RTL scripts), each column top-to-bottom.
 *
 * <p>This class is a pure function on box coordinates and intentionally has no Android
 * dependencies so that it is fully covered by JVM unit tests (mirrors {@link
 * VerticalTextLayoutPolicy}).
 */
public final class MultiColumnLayoutPolicy {

  /**
   * Fraction of the content width at or above which a box counts as "full-width" (headline,
   * caption spanning all columns). Full-width boxes never participate in column clustering; they
   * separate the page into vertical bands instead.
   */
  public static final float FULL_WIDTH_FRACTION = 0.6f;

  /**
   * Minimum horizontal gap between two column clusters, expressed as a factor of the median box
   * height. Boxes whose horizontal intervals overlap or are closer than this gap are merged into
   * the same column. The median box height approximates the line height and thus the typical
   * gutter width between newspaper columns.
   */
  public static final float MIN_GAP_FACTOR = 0.5f;

  /** Minimum number of line boxes per column for the page to count as multi-column. */
  public static final int MIN_LINES_PER_COLUMN = 3;

  /** Minimum number of columns for the page to count as multi-column. */
  public static final int MIN_COLUMNS = 2;

  private MultiColumnLayoutPolicy() {
    // utility
  }

  /**
   * Groups line boxes of a multi-column page into reading-order segments. Each segment is a group
   * of box indices that forms one continuous piece of the text flow (a full-width band or a single
   * column of a band); indices within a segment are ordered top-to-bottom.
   *
   * <p>Returns an empty list when the boxes do not form a multi-column layout (fewer than {@link
   * #MIN_COLUMNS} columns, or a column with fewer than {@link #MIN_LINES_PER_COLUMN} lines) — the
   * caller must then fall back to the regular single-flow text reconstruction.
   *
   * @param lefts left edges of the boxes
   * @param tops top edges of the boxes (same length)
   * @param rights right edges of the boxes (same length)
   * @param bottoms bottom edges of the boxes (same length)
   * @param rtl {@code true} to read columns right-to-left (RTL scripts), {@code false} for
   *     left-to-right
   * @return segments in reading order, or an empty list if the layout is not multi-column
   */
  public static List<int[]> groupIntoColumnSegments(
      float[] lefts, float[] tops, float[] rights, float[] bottoms, boolean rtl) {
    List<int[]> none = new ArrayList<>();
    if (lefts == null
        || tops == null
        || rights == null
        || bottoms == null
        || lefts.length != tops.length
        || lefts.length != rights.length
        || lefts.length != bottoms.length) {
      return none;
    }
    int n = lefts.length;
    if (n < MIN_COLUMNS * MIN_LINES_PER_COLUMN) return none;

    // Content bounds and full-width classification.
    float minLeft = Float.MAX_VALUE;
    float maxRight = -Float.MAX_VALUE;
    for (int i = 0; i < n; i++) {
      minLeft = Math.min(minLeft, lefts[i]);
      maxRight = Math.max(maxRight, rights[i]);
    }
    float contentWidth = Math.max(1f, maxRight - minLeft);
    boolean[] fullWidth = new boolean[n];
    List<Integer> columnBoxes = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      if (rights[i] - lefts[i] >= FULL_WIDTH_FRACTION * contentWidth) {
        fullWidth[i] = true;
      } else {
        columnBoxes.add(i);
      }
    }
    if (columnBoxes.size() < MIN_COLUMNS * MIN_LINES_PER_COLUMN) return none;

    // Median box height of the column candidates as scale reference for the gutter width.
    float[] heights = new float[columnBoxes.size()];
    for (int i = 0; i < columnBoxes.size(); i++) {
      int idx = columnBoxes.get(i);
      heights[i] = Math.max(1f, bottoms[idx] - tops[idx]);
    }
    float[] sortedHeights = heights.clone();
    Arrays.sort(sortedHeights);
    float minGap = MIN_GAP_FACTOR * sortedHeights[sortedHeights.length / 2];

    // Cluster the candidates into columns by merging overlapping/near horizontal intervals.
    // clusterOf[i] holds the column id of box i (or -1 for full-width boxes).
    int[] clusterOf = new int[n];
    Arrays.fill(clusterOf, -1);
    List<float[]> clusterRanges = clusterIntervals(lefts, rights, columnBoxes, minGap, clusterOf);
    int columnCount = clusterRanges.size();
    if (columnCount < MIN_COLUMNS) return none;
    int[] clusterSizes = new int[columnCount];
    for (int idx : columnBoxes) clusterSizes[clusterOf[idx]]++;
    for (int size : clusterSizes) {
      if (size < MIN_LINES_PER_COLUMN) return none;
    }

    // Column order in reading direction: by cluster center X, ascending (LTR) or descending (RTL).
    Integer[] columnOrder = new Integer[columnCount];
    for (int c = 0; c < columnCount; c++) columnOrder[c] = c;
    Arrays.sort(
        columnOrder,
        (a, b) -> {
          float cxa = 0.5f * (clusterRanges.get(a)[0] + clusterRanges.get(a)[1]);
          float cxb = 0.5f * (clusterRanges.get(b)[0] + clusterRanges.get(b)[1]);
          int c = rtl ? Float.compare(cxb, cxa) : Float.compare(cxa, cxb);
          if (c != 0) return c;
          return Integer.compare(a, b);
        });

    // Walk all boxes top-to-bottom and split them into vertical bands at full-width boxes:
    // consecutive full-width boxes form their own segment; the column boxes between two
    // full-width runs form one band that is emitted column by column.
    Integer[] byY = new Integer[n];
    for (int i = 0; i < n; i++) byY[i] = i;
    Arrays.sort(
        byY,
        (a, b) -> {
          int c = Float.compare(tops[a] + bottoms[a], tops[b] + bottoms[b]);
          if (c != 0) return c;
          return Integer.compare(a, b);
        });

    List<int[]> segments = new ArrayList<>();
    List<Integer> fullWidthRun = new ArrayList<>();
    List<Integer> band = new ArrayList<>();
    for (int idx : byY) {
      if (fullWidth[idx]) {
        if (!band.isEmpty()) {
          emitBand(band, clusterOf, columnOrder, tops, bottoms, segments);
          band = new ArrayList<>();
        }
        fullWidthRun.add(idx);
      } else {
        if (!fullWidthRun.isEmpty()) {
          segments.add(toArray(fullWidthRun));
          fullWidthRun = new ArrayList<>();
        }
        band.add(idx);
      }
    }
    if (!fullWidthRun.isEmpty()) segments.add(toArray(fullWidthRun));
    if (!band.isEmpty()) emitBand(band, clusterOf, columnOrder, tops, bottoms, segments);
    return segments;
  }

  /**
   * Merges the horizontal intervals of the given boxes into column clusters. Intervals that
   * overlap or whose gap is smaller than {@code minGap} join the same cluster. Fills {@code
   * clusterOf} with the cluster id per box and returns the {@code [left, right]} range of each
   * cluster.
   */
  private static List<float[]> clusterIntervals(
      float[] lefts, float[] rights, List<Integer> boxes, float minGap, int[] clusterOf) {
    List<Integer> byLeft = new ArrayList<>(boxes);
    byLeft.sort(
        (a, b) -> {
          int c = Float.compare(lefts[a], lefts[b]);
          if (c != 0) return c;
          return Integer.compare(a, b);
        });
    List<float[]> ranges = new ArrayList<>();
    float curRight = 0f;
    int cluster = -1;
    for (int idx : byLeft) {
      if (cluster < 0 || lefts[idx] - curRight >= minGap) {
        cluster++;
        curRight = rights[idx];
        ranges.add(new float[] {lefts[idx], curRight});
      } else {
        curRight = Math.max(curRight, rights[idx]);
        ranges.get(cluster)[1] = curRight;
      }
      clusterOf[idx] = cluster;
    }
    return ranges;
  }

  /**
   * Emits one vertical band as segments: one segment per column (in reading direction), boxes
   * within a column ordered top-to-bottom. Columns without boxes in this band are skipped.
   */
  private static void emitBand(
      List<Integer> band,
      int[] clusterOf,
      Integer[] columnOrder,
      float[] tops,
      float[] bottoms,
      List<int[]> segments) {
    for (int column : columnOrder) {
      List<Integer> inColumn = new ArrayList<>();
      for (int idx : band) {
        if (clusterOf[idx] == column) inColumn.add(idx);
      }
      if (inColumn.isEmpty()) continue;
      inColumn.sort(
          (a, b) -> {
            int c = Float.compare(tops[a] + bottoms[a], tops[b] + bottoms[b]);
            if (c != 0) return c;
            return Integer.compare(a, b);
          });
      segments.add(toArray(inColumn));
    }
  }

  private static int[] toArray(List<Integer> list) {
    int[] out = new int[list.size()];
    for (int i = 0; i < out.length; i++) out[i] = list.get(i);
    return out;
  }
}
