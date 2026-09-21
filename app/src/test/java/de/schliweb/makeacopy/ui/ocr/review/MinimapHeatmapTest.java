/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.ocr.review;

import static org.junit.Assert.assertEquals;

import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Unit tests for {@link MinimapHeatmap}. */
public class MinimapHeatmapTest {

  private static OcrDoc.Word word(int x, int y, int w, int h, float conf) {
    OcrDoc.Word word = new OcrDoc.Word();
    word.b = new int[] {x, y, w, h};
    word.c = conf;
    return word;
  }

  @Test
  public void noWordsOrUnknownPageSize_yieldsAllZeros() {
    assertEquals(4, MinimapHeatmap.lowConfidenceRatios(null, 100, 100, 2, 2).length);
    float[] vals =
        MinimapHeatmap.lowConfidenceRatios(List.of(word(10, 10, 10, 10, 0.1f)), 0, 100, 2, 2);
    for (float v : vals) assertEquals(0f, v, 0f);
  }

  @Test
  public void wordsAreBinnedByBoxCenter_rowMajor() {
    // 2x2 grid over a 100x100 page: centers (25,25) -> cell 0, (75,25) -> cell 1, (25,75) -> cell 2
    List<OcrDoc.Word> words =
        Arrays.asList(
            word(20, 20, 10, 10, 0.2f), word(70, 20, 10, 10, 0.9f), word(20, 70, 10, 10, 0.6f));
    float[] vals = MinimapHeatmap.lowConfidenceRatios(words, 100, 100, 2, 2);
    assertEquals(1f, vals[0], 0f);
    assertEquals(0f, vals[1], 0f);
    assertEquals(1f, vals[2], 0f); // 0.60 still counts as low
    assertEquals(0f, vals[3], 0f);
  }

  @Test
  public void cellValueIsShareOfLowConfidenceWords() {
    List<OcrDoc.Word> words =
        Arrays.asList(
            word(0, 0, 10, 10, 0.1f),
            word(5, 5, 10, 10, 0.95f),
            word(8, 8, 10, 10, 0.95f),
            word(9, 9, 10, 10, 0.95f));
    assertEquals(0.25f, MinimapHeatmap.lowConfidenceRatios(words, 100, 100, 2, 2)[0], 1e-6f);
  }

  @Test
  public void outOfPageAndMalformedWords_areClampedOrSkipped() {
    List<OcrDoc.Word> words = new ArrayList<>();
    words.add(word(-50, -50, 10, 10, 0.1f)); // clamps into first cell
    words.add(word(500, 500, 10, 10, 0.1f)); // clamps into last cell
    words.add(null);
    OcrDoc.Word noBox = new OcrDoc.Word();
    noBox.b = null;
    words.add(noBox);
    float[] vals = MinimapHeatmap.lowConfidenceRatios(words, 100, 100, 2, 2);
    assertEquals(1f, vals[0], 0f);
    assertEquals(1f, vals[3], 0f);
  }
}
