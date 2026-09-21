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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Unit tests for the pure decision logic of {@link OcrRecognitionPipeline}. */
public class OcrRecognitionPipelineTest {

  private static OCRHelper.OcrResultWords result(String text, Integer meanConf, int wordCount) {
    List<RecognizedWord> words = new ArrayList<>();
    for (int i = 0; i < wordCount; i++) {
      words.add(new RecognizedWord("w" + i, new RectF(), 90f));
    }
    return new OCRHelper.OcrResultWords(text, meanConf, words);
  }

  // ==================== isBetterResult ====================

  @Test
  public void isBetterResult_anyCandidateBeatsNoResult() {
    assertTrue(OcrRecognitionPipeline.isBetterResult(result("", 0, 0), null));
  }

  @Test
  public void isBetterResult_contentBeatsEmptyRegardlessOfConfidence() {
    assertTrue(OcrRecognitionPipeline.isBetterResult(result("abc", 10, 1), result("  ", 95, 0)));
    assertFalse(OcrRecognitionPipeline.isBetterResult(result("  ", 95, 0), result("abc", 10, 1)));
  }

  @Test
  public void isBetterResult_higherConfidenceWins() {
    assertTrue(OcrRecognitionPipeline.isBetterResult(result("abc", 80, 1), result("abcdef", 70, 5)));
    assertFalse(OcrRecognitionPipeline.isBetterResult(result("abcdef", 70, 5), result("abc", 80, 1)));
  }

  @Test
  public void isBetterResult_equalConfidence_moreWordsThenLongerTextWins() {
    assertTrue(OcrRecognitionPipeline.isBetterResult(result("abc", 80, 3), result("abcdef", 80, 2)));
    assertTrue(OcrRecognitionPipeline.isBetterResult(result("abcdef", 80, 2), result("abc", 80, 2)));
    assertFalse(OcrRecognitionPipeline.isBetterResult(result("abc", 80, 2), result("abc", 80, 2)));
  }

  @Test
  public void isBetterResult_nullConfidenceCountsAsZero() {
    assertFalse(OcrRecognitionPipeline.isBetterResult(result("abc", null, 1), result("abc", 5, 1)));
  }

  // ==================== hasContent ====================

  @Test
  public void hasContent_wordsOrNonBlankText() {
    assertTrue(OcrRecognitionPipeline.hasContent(result(null, 0, 1)));
    assertTrue(OcrRecognitionPipeline.hasContent(result("abc", 0, 0)));
    assertFalse(OcrRecognitionPipeline.hasContent(result(" \n", 90, 0)));
    assertFalse(OcrRecognitionPipeline.hasContent(null, null));
  }

  // ==================== isFallbackBetter ====================

  @Test
  public void isFallbackBetter_moreWordsWins() {
    assertTrue(OcrRecognitionPipeline.isFallbackBetter(result("t", 10, 6), result("t", 90, 5)));
  }

  @Test
  public void isFallbackBetter_sameWordsNeedsClearlyHigherConfidence() {
    assertTrue(OcrRecognitionPipeline.isFallbackBetter(result("t", 52, 5), result("t", 50, 5)));
    assertFalse(OcrRecognitionPipeline.isFallbackBetter(result("t", 51, 5), result("t", 50, 5)));
  }

  @Test
  public void isFallbackBetter_fewerWordsNeverWins() {
    assertFalse(OcrRecognitionPipeline.isFallbackBetter(result("t", 99, 4), result("t", 10, 5)));
  }
}
