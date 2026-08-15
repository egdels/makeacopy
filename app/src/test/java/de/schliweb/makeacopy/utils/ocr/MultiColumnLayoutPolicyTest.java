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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * JVM unit tests for {@link MultiColumnLayoutPolicy}. The policy is a pure function on box
 * coordinates (no Android dependencies), so the multi-column reading order is fully verifiable
 * here, mirroring {@link VerticalTextLayoutPolicyTest}.
 */
public class MultiColumnLayoutPolicyTest {

  /**
   * Builds the coordinate arrays for a synthetic page. Each row of {@code boxes} is {@code {left,
   * top, right, bottom}}.
   */
  private static float[][] columnsOf(float[][] boxes) {
    int n = boxes.length;
    float[][] out = new float[4][n];
    for (int i = 0; i < n; i++) {
      out[0][i] = boxes[i][0];
      out[1][i] = boxes[i][1];
      out[2][i] = boxes[i][2];
      out[3][i] = boxes[i][3];
    }
    return out;
  }

  private static List<int[]> group(float[][] boxes, boolean rtl) {
    float[][] c = columnsOf(boxes);
    return MultiColumnLayoutPolicy.groupIntoColumnSegments(c[0], c[1], c[2], c[3], rtl);
  }

  /** Flattens the segments into a single index order for easy comparison. */
  private static int[] flatten(List<int[]> segments) {
    int total = 0;
    for (int[] s : segments) total += s.length;
    int[] out = new int[total];
    int p = 0;
    for (int[] s : segments) for (int idx : s) out[p++] = idx;
    return out;
  }

  // ── detection guards ─────────────────────────────────────────────────────

  @Test
  public void nullArrays_returnEmpty() {
    assertTrue(
        MultiColumnLayoutPolicy.groupIntoColumnSegments(null, null, null, null, false).isEmpty());
  }

  @Test
  public void mismatchedLengths_returnEmpty() {
    assertTrue(
        MultiColumnLayoutPolicy.groupIntoColumnSegments(
                new float[2], new float[2], new float[2], new float[3], false)
            .isEmpty());
  }

  @Test
  public void singleColumn_returnsEmpty() {
    // Six stacked lines of the same column: not a multi-column layout.
    float[][] boxes = new float[6][];
    for (int i = 0; i < 6; i++) {
      boxes[i] = new float[] {100, 100 + i * 30, 400, 120 + i * 30};
    }
    assertTrue(group(boxes, false).isEmpty());
  }

  @Test
  public void tooFewLinesPerColumn_returnsEmpty() {
    // Two columns but only two lines each (< MIN_LINES_PER_COLUMN).
    float[][] boxes = {
      {100, 100, 400, 120}, {100, 130, 400, 150},
      {600, 100, 900, 120}, {600, 130, 900, 150},
      {100, 160, 400, 180}, {600, 160, 900, 180}
    };
    // 3 lines each is the minimum; drop one line from the right column to fall below it.
    float[][] tooFew = {
      boxes[0], boxes[1], boxes[4], // left column: 3 lines
      boxes[2], boxes[3] // right column: 2 lines
    };
    assertTrue(group(tooFew, false).isEmpty());
  }

  @Test
  public void indentedLines_doNotSplitColumn() {
    // A column with an indented first line (paragraph start) must stay one column.
    float[][] boxes = new float[6][];
    for (int i = 0; i < 6; i++) {
      float left = (i == 0) ? 140 : 100; // indented first line, intervals still overlap
      boxes[i] = new float[] {left, 100 + i * 30, 400, 120 + i * 30};
    }
    assertTrue(group(boxes, false).isEmpty());
  }

  // ── reading order ────────────────────────────────────────────────────────

  @Test
  public void twoColumns_ltr_leftColumnFirst() {
    // Interleaved input order (as emitted by a top-then-left sort): L0 R0 L1 R1 L2 R2.
    float[][] boxes = {
      {100, 100, 400, 120}, // 0: left col, row 0
      {600, 100, 900, 120}, // 1: right col, row 0
      {100, 130, 400, 150}, // 2: left col, row 1
      {600, 130, 900, 150}, // 3: right col, row 1
      {100, 160, 400, 180}, // 4: left col, row 2
      {600, 160, 900, 180} // 5: right col, row 2
    };
    List<int[]> segments = group(boxes, false);
    assertEquals(2, segments.size());
    assertArrayEquals(new int[] {0, 2, 4}, segments.get(0));
    assertArrayEquals(new int[] {1, 3, 5}, segments.get(1));
  }

  @Test
  public void twoColumns_rtl_rightColumnFirst() {
    float[][] boxes = {
      {100, 100, 400, 120}, // 0: left col, row 0
      {600, 100, 900, 120}, // 1: right col, row 0
      {100, 130, 400, 150}, // 2: left col, row 1
      {600, 130, 900, 150}, // 3: right col, row 1
      {100, 160, 400, 180}, // 4: left col, row 2
      {600, 160, 900, 180} // 5: right col, row 2
    };
    List<int[]> segments = group(boxes, true);
    assertEquals(2, segments.size());
    assertArrayEquals(new int[] {1, 3, 5}, segments.get(0));
    assertArrayEquals(new int[] {0, 2, 4}, segments.get(1));
  }

  @Test
  public void threeColumns_ltr_orderedLeftToRight() {
    float[][] boxes = new float[9][];
    // Column lefts: 100, 450, 800 — three rows each, interleaved by row.
    float[] colLeft = {100, 450, 800};
    int i = 0;
    for (int row = 0; row < 3; row++) {
      for (int col = 0; col < 3; col++) {
        boxes[i++] =
            new float[] {colLeft[col], 100 + row * 30, colLeft[col] + 300, 120 + row * 30};
      }
    }
    List<int[]> segments = group(boxes, false);
    assertEquals(3, segments.size());
    assertArrayEquals(new int[] {0, 3, 6}, segments.get(0)); // left column
    assertArrayEquals(new int[] {1, 4, 7}, segments.get(1)); // middle column
    assertArrayEquals(new int[] {2, 5, 8}, segments.get(2)); // right column
  }

  @Test
  public void fullWidthHeadline_emittedBeforeColumns() {
    float[][] boxes = {
      {100, 40, 900, 80}, // 0: full-width headline
      {100, 100, 400, 120}, // 1: left col, row 0
      {600, 100, 900, 120}, // 2: right col, row 0
      {100, 130, 400, 150}, // 3: left col, row 1
      {600, 130, 900, 150}, // 4: right col, row 1
      {100, 160, 400, 180}, // 5: left col, row 2
      {600, 160, 900, 180} // 6: right col, row 2
    };
    List<int[]> segments = group(boxes, false);
    assertEquals(3, segments.size());
    assertArrayEquals(new int[] {0}, segments.get(0)); // headline first
    assertArrayEquals(new int[] {1, 3, 5}, segments.get(1)); // then left column
    assertArrayEquals(new int[] {2, 4, 6}, segments.get(2)); // then right column
  }

  @Test
  public void fullWidthSeparator_splitsIntoBands() {
    float[][] boxes = {
      // Band 1: two columns, three rows each (indices 0-5, interleaved).
      {100, 100, 400, 120},
      {600, 100, 900, 120},
      {100, 130, 400, 150},
      {600, 130, 900, 150},
      {100, 160, 400, 180},
      {600, 160, 900, 180},
      // Full-width caption between the bands.
      {100, 200, 900, 240}, // 6
      // Band 2: the two columns continue (indices 7-10).
      {100, 260, 400, 280},
      {600, 260, 900, 280},
      {100, 290, 400, 310},
      {600, 290, 900, 310}
    };
    List<int[]> segments = group(boxes, false);
    // band1-left, band1-right, caption, band2-left, band2-right
    assertEquals(5, segments.size());
    assertArrayEquals(new int[] {0, 2, 4}, segments.get(0));
    assertArrayEquals(new int[] {1, 3, 5}, segments.get(1));
    assertArrayEquals(new int[] {6}, segments.get(2));
    assertArrayEquals(new int[] {7, 9}, segments.get(3));
    assertArrayEquals(new int[] {8, 10}, segments.get(4));
  }

  @Test
  public void bandsWithDifferentColumnGeometry_clusteredIndependently() {
    // Band 1 has two wide columns, band 2 (after a full-width caption) has three narrow
    // columns whose gutters fall inside band 1's column intervals. A global clustering merges
    // them; the per-band analysis must reconstruct both bands correctly.
    float[][] boxes = {
      // Band 1: two columns at 100-500 and 600-1000, three rows each (interleaved).
      {100, 100, 500, 120},
      {600, 100, 1000, 120},
      {100, 130, 500, 150},
      {600, 130, 1000, 150},
      {100, 160, 500, 180},
      {600, 160, 1000, 180},
      // Full-width caption between the bands.
      {100, 200, 1000, 240}, // 6
      // Band 2: three columns at 100-350, 420-670, 740-990, three rows each.
      {100, 260, 350, 280},
      {420, 260, 670, 280},
      {740, 260, 990, 280},
      {100, 290, 350, 310},
      {420, 290, 670, 310},
      {740, 290, 990, 310},
      {100, 320, 350, 340},
      {420, 320, 670, 340},
      {740, 320, 990, 340}
    };
    List<int[]> segments = group(boxes, false);
    // band1-left, band1-right, caption, band2-left, band2-middle, band2-right
    assertEquals(6, segments.size());
    assertArrayEquals(new int[] {0, 2, 4}, segments.get(0));
    assertArrayEquals(new int[] {1, 3, 5}, segments.get(1));
    assertArrayEquals(new int[] {6}, segments.get(2));
    assertArrayEquals(new int[] {7, 10, 13}, segments.get(3));
    assertArrayEquals(new int[] {8, 11, 14}, segments.get(4));
    assertArrayEquals(new int[] {9, 12, 15}, segments.get(5));
  }

  @Test
  public void bandLocalHeadline_splitsBandIntoSubBands() {
    // The page content is wider than the two-column body (a full-width headline at the top
    // defines the content width). A sub-headline inside the body spans both body columns but
    // less than 60% of the page width, so only the band-local pass can recognize it as a
    // separator between the upper and lower part of the columns.
    float[][] boxes = {
      {100, 40, 1200, 80}, // 0: page-wide headline
      // Upper part: two columns at 100-400 and 500-740, three rows each.
      {100, 100, 400, 120}, // 1
      {500, 100, 740, 120}, // 2
      {100, 130, 400, 150}, // 3
      {500, 130, 740, 150}, // 4
      {100, 160, 400, 180}, // 5
      {500, 160, 740, 180}, // 6
      // Sub-headline spanning both body columns (width 640 of 1100 page width = 58%).
      {100, 200, 740, 230}, // 7
      // Lower part: the two columns continue, three rows each.
      {100, 260, 400, 280}, // 8
      {500, 260, 740, 280}, // 9
      {100, 290, 400, 310}, // 10
      {500, 290, 740, 310}, // 11
      {100, 320, 400, 340}, // 12
      {500, 320, 740, 340} // 13
    };
    List<int[]> segments = group(boxes, false);
    // headline, upper-left, upper-right, sub-headline, lower-left, lower-right
    assertEquals(6, segments.size());
    assertArrayEquals(new int[] {0}, segments.get(0));
    assertArrayEquals(new int[] {1, 3, 5}, segments.get(1));
    assertArrayEquals(new int[] {2, 4, 6}, segments.get(2));
    assertArrayEquals(new int[] {7}, segments.get(3));
    assertArrayEquals(new int[] {8, 10, 12}, segments.get(4));
    assertArrayEquals(new int[] {9, 11, 13}, segments.get(5));
  }

  @Test
  public void mixedFontSizes_bandLocalGutterEstimate() {
    // A band with large-print lines elsewhere on the page must not inflate the gutter
    // estimate of the body band: the two body columns (small line height, narrow gutter)
    // must still be separated.
    float[][] boxes = {
      {100, 40, 900, 90}, // 0: full-width headline with large line height (50)
      // Body: two columns with small line height (14) and a narrow gutter (30 px).
      {100, 120, 480, 134}, // 1
      {510, 120, 890, 134}, // 2
      {100, 140, 480, 154}, // 3
      {510, 140, 890, 154}, // 4
      {100, 160, 480, 174}, // 5
      {510, 160, 890, 174} // 6
    };
    List<int[]> segments = group(boxes, false);
    assertEquals(3, segments.size());
    assertArrayEquals(new int[] {0}, segments.get(0));
    assertArrayEquals(new int[] {1, 3, 5}, segments.get(1));
    assertArrayEquals(new int[] {2, 4, 6}, segments.get(2));
  }

  @Test
  public void allBoxesAppearExactlyOnce() {
    float[][] boxes = {
      {100, 40, 900, 80},
      {100, 100, 400, 120},
      {600, 100, 900, 120},
      {100, 130, 400, 150},
      {600, 130, 900, 150},
      {100, 160, 400, 180},
      {600, 160, 900, 180}
    };
    List<int[]> segments = group(boxes, false);
    int[] flat = flatten(segments);
    boolean[] seen = new boolean[boxes.length];
    assertEquals(boxes.length, flat.length);
    for (int idx : flat) {
      assertTrue(idx >= 0 && idx < boxes.length);
      assertTrue("duplicate index " + idx, !seen[idx]);
      seen[idx] = true;
    }
  }
}
