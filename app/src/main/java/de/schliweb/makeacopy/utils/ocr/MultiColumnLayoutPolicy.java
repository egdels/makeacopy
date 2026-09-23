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
 * <p>This class is a pure function on box coordinates and intentionally has no Android dependencies
 * so that it is fully covered by JVM unit tests (mirrors {@link VerticalTextLayoutPolicy}).
 */
public final class MultiColumnLayoutPolicy {

  /**
   * Fraction of the content width at or above which a box counts as "full-width" (headline, caption
   * spanning all columns). Full-width boxes never participate in column clustering; they separate
   * the page into vertical bands instead.
   */
  public static final float FULL_WIDTH_FRACTION = 0.6f;

  /**
   * Minimum gutter width between two columns, expressed as a factor of the median box height. The
   * median box height approximates the line height and thus the typical gutter width between
   * newspaper columns; narrower coverage valleys are treated as noise.
   */
  public static final float MIN_GAP_FACTOR = 0.5f;

  /**
   * A bin of the horizontal coverage profile counts as part of a gutter when at most this fraction
   * of the maximum coverage crosses it. Tolerates a few stray boxes (noise, descenders, boxes
   * leaking across the gutter) without closing the valley.
   */
  public static final float VALLEY_COVERAGE_FRACTION = 0.15f;

  /**
   * Absolute cap for the valley coverage tolerance. Without the cap, sparse areas (a short column
   * next to a dense one) would fall below the relative threshold entirely and swallow the gutter.
   */
  public static final int VALLEY_COVERAGE_MAX = 2;

  /**
   * Minimum column width as fraction of the band width. Valleys that would create a narrower column
   * (e.g. vertically aligned word gaps in a sparse text block) are discarded by merging the narrow
   * region with its neighbor.
   */
  public static final float MIN_COLUMN_WIDTH_FRACTION = 0.1f;

  /** Number of bins of the horizontal coverage profile used for gutter detection. */
  private static final int COVERAGE_BINS = 256;

  /** Minimum number of line boxes per column for the page to count as multi-column. */
  public static final int MIN_LINES_PER_COLUMN = 3;

  /** Minimum number of columns for the page to count as multi-column. */
  public static final int MIN_COLUMNS = 2;

  /**
   * Minimum number of line boxes per column within a single band for the band to be emitted column
   * by column. Below this the band is kept as one top-to-bottom segment. Deliberately lower than
   * {@link #MIN_LINES_PER_COLUMN}: once the page as a whole is confirmed multi-column, short bands
   * (e.g. the last lines before a figure) may still be split into columns.
   */
  public static final int MIN_LINES_PER_BAND_COLUMN = 2;

  /**
   * Maximum recursion depth for splitting a band at band-local full-width boxes (headlines that
   * span several but not all page columns).
   */
  private static final int MAX_BAND_DEPTH = 2;

  /**
   * Maximum horizontal gap between two boxes of the same row to be merged into one line fragment,
   * as factor of the median box height. Word gaps are well below one line height, the gutter
   * between columns is above it — so word-level boxes fuse back into per-column line fragments
   * while boxes on either side of a gutter stay separate.
   */
  private static final float WORD_MERGE_GAP_FACTOR = 1.0f;

  /**
   * A box counts as crossing a gutter when it reaches beyond the gutter's coverage valley into the
   * text area of both neighbouring columns, by at least this factor of the median box height on
   * each side. A line box that merely protrudes into the gutter (the detector's boxes are not
   * pixel-exact) is not a headline spanning two columns; treating it as a separator would cut the
   * page into bands of a few rows whose column analysis then has too little evidence and finds word
   * gaps instead of gutters. A sub-heading that ends inside the gutter is, by the same token, not
   * recognisable as a separator by geometry.
   */
  public static final float CROSSING_MIN_EXTENT_FACTOR = 0.25f;

  /**
   * A gutter is kept only when it has a straight edge: in at least this fraction of the rows with
   * text on that side, the boxes directly left of it end at a common x, or the boxes directly right
   * of it start at a common x (within {@link #ALIGN_TOLERANCE_FACTOR} median heights). Justified
   * and left-aligned columns are straight on both sides, a centred column is straight on its
   * neighbour's side. The word gaps of a wide justified single column also line up into coverage
   * valleys, but they are ragged on both sides, so those gutters are discarded.
   */
  public static final float ALIGNED_ROW_FRACTION = 0.6f;

  /** Tolerance for two edges to count as the same x, as factor of the median height. */
  public static final float ALIGN_TOLERANCE_FACTOR = 0.5f;

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
    if (lefts.length < MIN_COLUMNS * MIN_LINES_PER_COLUMN) return none;

    // The recognizer may emit word- or sub-line-level boxes instead of full text lines. On that
    // granularity neither the full-width classification nor the column clustering is meaningful,
    // so boxes are first fused back into per-column line fragments: boxes of the same visual row
    // whose horizontal gap is below the word-gap threshold form one fragment. Merging never
    // crosses a page-level gutter (a coverage valley of the raw boxes), so fragments stay within
    // their column even when the gutter is no wider than a word gap.
    List<Float> pageGutters = findPageGutters(lefts, tops, rights, bottoms);
    List<List<Integer>> fragments =
        mergeIntoLineFragments(lefts, tops, rights, bottoms, pageGutters);
    int m = fragments.size();
    if (m < MIN_COLUMNS * MIN_LINES_PER_COLUMN) return none;
    float[] fLefts = new float[m];
    float[] fTops = new float[m];
    float[] fRights = new float[m];
    float[] fBottoms = new float[m];
    for (int i = 0; i < m; i++) {
      float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
      for (int idx : fragments.get(i)) {
        l = Math.min(l, lefts[idx]);
        t = Math.min(t, tops[idx]);
        r = Math.max(r, rights[idx]);
        b = Math.max(b, bottoms[idx]);
      }
      fLefts[i] = l;
      fTops[i] = t;
      fRights[i] = r;
      fBottoms[i] = b;
    }

    List<int[]> fragmentSegments = segmentLineBoxes(fLefts, fTops, fRights, fBottoms, rtl);
    if (fragmentSegments.isEmpty()) return none;

    // Map fragment indices back to the original box indices (fragment members keep their
    // left-to-right order; the caller's line grouping re-orders words within a line anyway).
    List<int[]> out = new ArrayList<>(fragmentSegments.size());
    for (int[] seg : fragmentSegments) {
      List<Integer> expanded = new ArrayList<>();
      for (int fi : seg) {
        expanded.addAll(fragments.get(fi));
      }
      out.add(toArray(expanded));
    }
    return out;
  }

  /**
   * Detects the page-level gutters from the raw boxes (before any fragment merging) via the
   * horizontal coverage profile. Used only to block fragment merges across a gutter; missing a
   * gutter here is harmless because the per-band detection runs again on the merged fragments.
   */
  private static List<Float> findPageGutters(
      float[] lefts, float[] tops, float[] rights, float[] bottoms) {
    int n = lefts.length;
    float minLeft = Float.MAX_VALUE;
    float maxRight = -Float.MAX_VALUE;
    float[] heights = new float[n];
    List<Integer> all = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      minLeft = Math.min(minLeft, lefts[i]);
      maxRight = Math.max(maxRight, rights[i]);
      heights[i] = Math.max(1f, bottoms[i] - tops[i]);
      all.add(i);
    }
    Arrays.sort(heights);
    float medianHeight = heights[n / 2];
    float width = Math.max(1f, maxRight - minLeft);
    List<Float> centres = new ArrayList<>();
    for (float[] v :
        findColumnValleys(all, lefts, tops, rights, bottoms, minLeft, width, medianHeight)) {
      centres.add(0.5f * (v[0] + v[1]));
    }
    return centres;
  }

  /**
   * Fuses word- or sub-line-level boxes into per-column line fragments. Boxes are grouped into
   * visual rows by vertical center proximity; within a row, adjacent boxes are merged while their
   * horizontal gap stays below {@link #WORD_MERGE_GAP_FACTOR} times the median box height and no
   * page-level gutter lies between them. Line-level input passes through unchanged (one fragment
   * per box).
   */
  private static List<List<Integer>> mergeIntoLineFragments(
      float[] lefts, float[] tops, float[] rights, float[] bottoms, List<Float> gutters) {
    int n = lefts.length;
    float[] heights = new float[n];
    for (int i = 0; i < n; i++) heights[i] = Math.max(1f, bottoms[i] - tops[i]);
    float[] sortedHeights = heights.clone();
    Arrays.sort(sortedHeights);
    float medianHeight = sortedHeights[n / 2];
    float mergeGap = WORD_MERGE_GAP_FACTOR * medianHeight;

    // Group boxes into visual rows with the app-wide line rule (drop caps and headline words do
    // not widen the tolerance).
    List<List<Integer>> rows = new ArrayList<>();
    for (int[] line : LineGrouping.groupIntoLines(lefts, tops, rights, bottoms)) {
      List<Integer> row = new ArrayList<>(line.length);
      for (int idx : line) row.add(idx);
      rows.add(row);
    }

    // Within each row (sorted left-to-right) merge boxes across word gaps into fragments.
    List<List<Integer>> fragments = new ArrayList<>();
    for (List<Integer> r : rows) {
      r.sort(
          (a, b) -> {
            int c = Float.compare(lefts[a], lefts[b]);
            if (c != 0) return c;
            return Integer.compare(a, b);
          });
      List<Integer> fragment = new ArrayList<>();
      float fragmentRight = 0f;
      for (int idx : r) {
        boolean merge =
            fragment.isEmpty()
                || (lefts[idx] - fragmentRight <= mergeGap
                    && !crossesGutter(gutters, fragmentRight, lefts[idx]));
        if (merge) {
          fragment.add(idx);
        } else {
          fragments.add(fragment);
          fragment = new ArrayList<>();
          fragment.add(idx);
        }
        fragmentRight = Math.max(fragmentRight, rights[idx]);
      }
      if (!fragment.isEmpty()) fragments.add(fragment);
    }
    return fragments;
  }

  /** Returns {@code true} when one of the gutter split points lies within {@code [from, to]}. */
  private static boolean crossesGutter(List<Float> gutters, float from, float to) {
    for (float g : gutters) {
      if (g >= from && g <= to) return true;
    }
    return false;
  }

  /**
   * Core XY-cut on line-level boxes: splits the page into vertical bands at full-width boxes and
   * clusters each band into columns. Returns the segments in reading order, or an empty list when
   * no band forms a reliable multi-column layout.
   */
  private static List<int[]> segmentLineBoxes(
      float[] lefts, float[] tops, float[] rights, float[] bottoms, boolean rtl) {
    List<int[]> none = new ArrayList<>();
    int n = lefts.length;

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

    // Walk all boxes top-to-bottom and split them into vertical bands at full-width boxes:
    // consecutive full-width boxes form their own segment; the column boxes between two
    // full-width runs form one band. Each band is then analyzed independently (recursive
    // XY-cut): column clustering happens per band, so layouts whose columns do not extend
    // over the full page height (headline blocks, column count changing mid-page, partial
    // columns) are reconstructed correctly.
    List<Integer> all = new ArrayList<>(n);
    for (int i = 0; i < n; i++) all.add(i);
    List<int[]> segments = new ArrayList<>();
    List<Integer> fullWidthRun = new ArrayList<>();
    List<Integer> band = new ArrayList<>();
    boolean anyMultiColumnBand = false;
    // Rows, not boxes, are the unit: a full-width box takes the rest of its visual row along.
    for (List<Integer> row : rowsOf(all, lefts, tops, rights, bottoms)) {
      boolean fullWidthRow = false;
      for (int idx : row) if (fullWidth[idx]) fullWidthRow = true;
      if (fullWidthRow) {
        if (!band.isEmpty()) {
          anyMultiColumnBand |=
              emitBandSegments(band, lefts, tops, rights, bottoms, rtl, MAX_BAND_DEPTH, segments);
          band = new ArrayList<>();
        }
        fullWidthRun.addAll(row);
      } else {
        if (!fullWidthRun.isEmpty()) {
          segments.add(toArray(fullWidthRun));
          fullWidthRun = new ArrayList<>();
        }
        band.addAll(row);
      }
    }
    if (!fullWidthRun.isEmpty()) segments.add(toArray(fullWidthRun));
    if (!band.isEmpty()) {
      anyMultiColumnBand |=
          emitBandSegments(band, lefts, tops, rights, bottoms, rtl, MAX_BAND_DEPTH, segments);
    }
    // Not a multi-column page: no band produced a reliable column split — the caller falls
    // back to the regular single-flow reconstruction.
    if (!anyMultiColumnBand) return none;
    return segments;
  }

  /**
   * Analyzes one vertical band (the boxes between two full-width separators, in top-to-bottom
   * order) and appends its segments to {@code segments}.
   *
   * <p>The band's boxes are clustered into columns using a band-local gutter estimate (median box
   * height of this band only, robust against mixed font sizes elsewhere on the page). Boxes that
   * span most of the band's own width (band-local headlines or captions that do not span the whole
   * page) act as separators that split the band further into sub-bands, recursively up to {@code
   * depth} levels.
   *
   * @return {@code true} if this band (or one of its sub-bands) forms a reliable multi-column
   *     layout ({@link #MIN_COLUMNS}+ columns with {@link #MIN_LINES_PER_COLUMN}+ lines each)
   */
  private static boolean emitBandSegments(
      List<Integer> band,
      float[] lefts,
      float[] tops,
      float[] rights,
      float[] bottoms,
      boolean rtl,
      int depth,
      List<int[]> segments) {
    if (band.isEmpty()) return false;
    if (band.size() == 1) {
      segments.add(toArray(band));
      return false;
    }

    // Band-local content bounds.
    float bandLeft = Float.MAX_VALUE;
    float bandRight = -Float.MAX_VALUE;
    for (int idx : band) {
      bandLeft = Math.min(bandLeft, lefts[idx]);
      bandRight = Math.max(bandRight, rights[idx]);
    }
    float bandWidth = Math.max(1f, bandRight - bandLeft);

    // Band-local median box height as scale reference for the minimum gutter width.
    float[] heights = new float[band.size()];
    for (int i = 0; i < band.size(); i++) {
      int idx = band.get(i);
      heights[i] = Math.max(1f, bottoms[idx] - tops[idx]);
    }
    Arrays.sort(heights);
    float medianHeight = heights[heights.length / 2];

    // Find gutters via the horizontal coverage profile of this band: a gutter is an x-range
    // that (almost) no box of the band covers. Unlike interval clustering this is robust when
    // word gaps and the gutter have similar widths, because word gaps do not line up vertically
    // across the whole band while the gutter does.
    List<float[]> valleys =
        findColumnValleys(band, lefts, tops, rights, bottoms, bandLeft, bandWidth, medianHeight);
    List<Float> splitPoints = new ArrayList<>(valleys.size());
    for (float[] v : valleys) splitPoints.add(0.5f * (v[0] + v[1]));

    // Band-local separators: headlines or captions that span most of this band's width (but not
    // the whole page, otherwise the top-level pass would have caught them) or that clearly cross
    // a gutter split the band into sub-bands that are analyzed independently.
    if (depth > 0) {
      float crossMargin = CROSSING_MIN_EXTENT_FACTOR * medianHeight;
      List<Integer> localFull = new ArrayList<>();
      for (int idx : band) {
        boolean wide = rights[idx] - lefts[idx] >= FULL_WIDTH_FRACTION * bandWidth;
        boolean crossing = false;
        for (float[] v : valleys) {
          if (lefts[idx] <= v[0] - crossMargin && rights[idx] >= v[1] + crossMargin) {
            crossing = true;
            break;
          }
        }
        if (wide || crossing) {
          localFull.add(idx);
        }
      }
      if (!localFull.isEmpty() && localFull.size() < band.size()) {
        // A separator takes its whole visual row with it: a headline that the detector split
        // into several boxes has crossing and non-crossing fragments side by side, and the
        // non-crossing ones must not become one-box sub-bands between them.
        boolean any = false;
        List<Integer> fullRun = new ArrayList<>();
        List<Integer> subBand = new ArrayList<>();
        for (List<Integer> row : rowsOf(band, lefts, tops, rights, bottoms)) {
          boolean separatorRow = false;
          for (int idx : row) if (localFull.contains(idx)) separatorRow = true;
          if (separatorRow) {
            if (!subBand.isEmpty()) {
              any |=
                  emitBandSegments(subBand, lefts, tops, rights, bottoms, rtl, depth - 1, segments);
              subBand = new ArrayList<>();
            }
            fullRun.addAll(row);
          } else {
            if (!fullRun.isEmpty()) {
              segments.add(toArray(fullRun));
              fullRun = new ArrayList<>();
            }
            subBand.addAll(row);
          }
        }
        if (!fullRun.isEmpty()) segments.add(toArray(fullRun));
        if (!subBand.isEmpty()) {
          any |= emitBandSegments(subBand, lefts, tops, rights, bottoms, rtl, depth - 1, segments);
        }
        return any;
      }
    }

    int columnCount = splitPoints.size() + 1;

    int[] clusterOf = new int[lefts.length];
    Arrays.fill(clusterOf, -1);
    int[] clusterSizes = new int[columnCount];
    for (int idx : band) {
      float centerX = 0.5f * (lefts[idx] + rights[idx]);
      int column = 0;
      while (column < splitPoints.size() && centerX > splitPoints.get(column)) column++;
      clusterOf[idx] = column;
      clusterSizes[column]++;
    }
    int minSize = Integer.MAX_VALUE;
    for (int c = 0; c < columnCount; c++) minSize = Math.min(minSize, clusterSizes[c]);

    // Too few columns or columns too sparse to be trusted: keep the band as one segment in
    // top-to-bottom order instead of risking a wrong column split.
    if (columnCount < MIN_COLUMNS || minSize < MIN_LINES_PER_BAND_COLUMN) {
      segments.add(toArray(band));
      return false;
    }

    // Columns are ordered left-to-right by construction; RTL scripts read them right-to-left.
    // Boxes within a column keep the band's top-to-bottom order.
    for (int c = 0; c < columnCount; c++) {
      int column = rtl ? columnCount - 1 - c : c;
      List<Integer> inColumn = new ArrayList<>();
      for (int idx : band) {
        if (clusterOf[idx] == column) inColumn.add(idx);
      }
      if (inColumn.isEmpty()) continue;
      segments.add(toArray(inColumn));
    }
    return minSize >= MIN_LINES_PER_COLUMN;
  }

  /**
   * Detects gutters in a band via its horizontal coverage profile and returns the x-coordinates of
   * the column split points (gutter centers), ordered left to right.
   *
   * <p>The band's width is divided into bins; for each bin the number of boxes covering it is
   * counted. Interior runs of bins whose coverage is at most {@link #VALLEY_COVERAGE_FRACTION} of
   * the maximum coverage and that are at least {@code MIN_GAP_FACTOR * medianHeight} wide count as
   * gutters.
   */
  /**
   * Gutters of the band as coverage valleys {@code {start, end}} in x, left to right. The extents
   * matter for the crossing test.
   */
  private static List<float[]> findColumnValleys(
      List<Integer> band,
      float[] lefts,
      float[] tops,
      float[] rights,
      float[] bottoms,
      float bandLeft,
      float bandWidth,
      float medianHeight) {
    final int bins = COVERAGE_BINS;
    float binWidth = bandWidth / bins;
    int[] coverage = new int[bins];
    for (int idx : band) {
      int i0 = (int) ((lefts[idx] - bandLeft) / bandWidth * (bins - 1));
      int i1 = (int) ((rights[idx] - bandLeft) / bandWidth * (bins - 1));
      i0 = Math.max(0, Math.min(bins - 1, i0));
      i1 = Math.max(0, Math.min(bins - 1, i1));
      for (int i = i0; i <= i1; i++) coverage[i]++;
    }
    int maxCoverage = 0;
    for (int c : coverage) maxCoverage = Math.max(maxCoverage, c);
    int valleyThreshold =
        Math.min((int) Math.floor(VALLEY_COVERAGE_FRACTION * maxCoverage), VALLEY_COVERAGE_MAX);
    int minValleyBins = Math.max(1, Math.round(MIN_GAP_FACTOR * medianHeight / binWidth));

    List<Float> splitPoints = new ArrayList<>();
    List<float[]> extents = new ArrayList<>();
    List<Integer> valleyBins = new ArrayList<>();
    int runStart = -1;
    for (int i = 0; i <= bins; i++) {
      boolean valley = i < bins && coverage[i] <= valleyThreshold;
      if (valley) {
        if (runStart < 0) runStart = i;
      } else if (runStart >= 0) {
        // Interior runs only: runs touching the band edges are margins, not gutters.
        if (runStart > 0 && i < bins && i - runStart >= minValleyBins) {
          float center = bandLeft + (runStart + i) * 0.5f * binWidth;
          splitPoints.add(center);
          extents.add(new float[] {bandLeft + runStart * binWidth, bandLeft + i * binWidth});
          valleyBins.add(i - runStart);
        }
        runStart = -1;
      }
    }

    // Enforce a minimum column width: valleys that would create a region narrower than
    // MIN_COLUMN_WIDTH_FRACTION of the band (typically vertically aligned word gaps in a sparse
    // block) are removed by merging the narrow region with its neighbor.
    // Of the two valleys bounding a narrow region the narrower one goes: a word gap that happens
    // to line up vertically is a few bins wide, a real gutter many, and removing the wrong one
    // would keep the word gap as "gutter" and cut a column in two.
    float minColumnWidth = MIN_COLUMN_WIDTH_FRACTION * bandWidth;
    boolean changed = true;
    while (changed && !splitPoints.isEmpty()) {
      changed = false;
      float prev = bandLeft;
      for (int k = 0; k <= splitPoints.size(); k++) {
        float next = k < splitPoints.size() ? splitPoints.get(k) : bandLeft + bandWidth;
        if (next - prev < minColumnWidth) {
          int remove;
          if (k == 0) {
            remove = 0;
          } else if (k == splitPoints.size()) {
            remove = k - 1;
          } else {
            remove = valleyBins.get(k - 1) <= valleyBins.get(k) ? k - 1 : k;
          }
          splitPoints.remove(remove);
          extents.remove(remove);
          valleyBins.remove(remove);
          changed = true;
          break;
        }
        prev = next;
      }
    }
    List<Float> kept =
        dropUnalignedRegions(
            band, lefts, tops, rights, bottoms, bandLeft, bandWidth, medianHeight, splitPoints);
    List<float[]> out = new ArrayList<>(kept.size());
    for (int k = 0; k < splitPoints.size(); k++) {
      if (kept.contains(splitPoints.get(k))) out.add(extents.get(k));
    }
    return out;
  }

  /**
   * Removes gutters without a straight edge (see {@link #ALIGNED_ROW_FRACTION}); the check repeats
   * until every remaining gutter has one.
   */
  private static List<Float> dropUnalignedRegions(
      List<Integer> band,
      float[] lefts,
      float[] tops,
      float[] rights,
      float[] bottoms,
      float bandLeft,
      float bandWidth,
      float medianHeight,
      List<Float> splitPoints) {
    if (splitPoints.isEmpty()) return splitPoints;
    int m = band.size();
    float[] l = new float[m];
    float[] t = new float[m];
    float[] r = new float[m];
    float[] b = new float[m];
    for (int i = 0; i < m; i++) {
      int idx = band.get(i);
      l[i] = lefts[idx];
      t[i] = tops[idx];
      r[i] = rights[idx];
      b[i] = bottoms[idx];
    }
    List<int[]> rows = LineGrouping.groupIntoLines(l, t, r, b);
    float tolerance = ALIGN_TOLERANCE_FACTOR * medianHeight;
    List<Float> points = new ArrayList<>(splitPoints);
    boolean changed = true;
    while (changed && !points.isEmpty()) {
      changed = false;
      for (int k = 0; k < points.size(); k++) {
        float gutter = points.get(k);
        float from = k == 0 ? bandLeft : points.get(k - 1);
        float to = k + 1 < points.size() ? points.get(k + 1) : bandLeft + bandWidth;
        // Per row: right edge of the box directly left of the gutter, left edge of the box
        // directly right of it (boxes assigned to a side by their centre, limited to the
        // neighbouring regions).
        List<Float> leftEdges = new ArrayList<>();
        List<Float> rightEdges = new ArrayList<>();
        for (int[] row : rows) {
          float endLeft = Float.NaN;
          float startRight = Float.NaN;
          for (int i : row) {
            float centerX = 0.5f * (l[i] + r[i]);
            if (centerX >= from && centerX < gutter && (Float.isNaN(endLeft) || r[i] > endLeft)) {
              endLeft = r[i];
            }
            if (centerX >= gutter
                && centerX < to
                && (Float.isNaN(startRight) || l[i] < startRight)) {
              startRight = l[i];
            }
          }
          if (!Float.isNaN(endLeft)) leftEdges.add(endLeft);
          if (!Float.isNaN(startRight)) rightEdges.add(startRight);
        }
        boolean straightLeft =
            leftEdges.size() >= 2
                && modeCount(leftEdges, tolerance) >= ALIGNED_ROW_FRACTION * leftEdges.size();
        boolean straightRight =
            rightEdges.size() >= 2
                && modeCount(rightEdges, tolerance) >= ALIGNED_ROW_FRACTION * rightEdges.size();
        if (!straightLeft && !straightRight) {
          points.remove(k);
          changed = true;
          break;
        }
      }
    }
    return points;
  }

  /** Size of the largest group of values that lie within {@code tolerance} of one of them. */
  private static int modeCount(List<Float> values, float tolerance) {
    int best = 0;
    for (float a : values) {
      int count = 0;
      for (float o : values) if (Math.abs(o - a) <= tolerance) count++;
      best = Math.max(best, count);
    }
    return best;
  }

  /**
   * Visual rows of the given boxes, top-to-bottom, members left-to-right (indices into the arrays).
   */
  private static List<List<Integer>> rowsOf(
      List<Integer> subset, float[] lefts, float[] tops, float[] rights, float[] bottoms) {
    int m = subset.size();
    float[] l = new float[m];
    float[] t = new float[m];
    float[] r = new float[m];
    float[] b = new float[m];
    for (int i = 0; i < m; i++) {
      int idx = subset.get(i);
      l[i] = lefts[idx];
      t[i] = tops[idx];
      r[i] = rights[idx];
      b[i] = bottoms[idx];
    }
    List<List<Integer>> rows = new ArrayList<>();
    for (int[] line : LineGrouping.groupIntoLines(l, t, r, b)) {
      List<Integer> row = new ArrayList<>(line.length);
      for (int i : line) row.add(subset.get(i));
      rows.add(row);
    }
    return rows;
  }

  private static int[] toArray(List<Integer> list) {
    int[] out = new int[list.size()];
    for (int i = 0; i < out.length; i++) out[i] = list.get(i);
    return out;
  }
}
