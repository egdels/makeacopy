/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.ocr.review;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.schliweb.makeacopy.ui.ocr.review.model.OcrDocReadingOrder;
import org.junit.Test;

/**
 * Tests for {@link OcrDocReadingOrder#isVerticalCjkWord(String, float, float)} which decides
 * whether the OCR review text layer renders a word as a vertical (top-to-bottom) CJK column.
 */
public class OcrDocReadingOrderVerticalTest {

  @Test
  public void verticalJapaneseColumn_isVertical() {
    assertTrue(OcrDocReadingOrder.isVerticalCjkWord("すると、1億円を受け取った", 30f, 400f));
  }

  @Test
  public void verticalKanjiKatakanaColumn_isVertical() {
    assertTrue(OcrDocReadingOrder.isVerticalCjkWord("高級マンション", 24f, 200f));
  }

  @Test
  public void wideCjkBox_isNotVertical() {
    // Horizontal CJK line: box wider than tall must keep the horizontal rendering path.
    assertFalse(OcrDocReadingOrder.isVerticalCjkWord("すると、1億円", 400f, 30f));
  }

  @Test
  public void squareCjkBox_isNotVertical() {
    assertFalse(OcrDocReadingOrder.isVerticalCjkWord("円", 30f, 30f));
  }

  @Test
  public void tallLatinBox_isNotVertical() {
    // Tall box but Latin text (e.g. rotated crop artifact) must not switch to vertical mode.
    assertFalse(OcrDocReadingOrder.isVerticalCjkWord("Hello", 30f, 400f));
  }

  @Test
  public void tallArabicBox_isNotVertical() {
    assertFalse(OcrDocReadingOrder.isVerticalCjkWord("مرحبا", 30f, 400f));
  }

  @Test
  public void mixedMostlyCjkTallBox_isVertical() {
    // Digits inside a vertical Japanese column (e.g. "1億円") must still count as vertical.
    assertTrue(OcrDocReadingOrder.isVerticalCjkWord("1億円で遊んでいる", 30f, 300f));
  }

  @Test
  public void nullOrEmptyText_isNotVertical() {
    assertFalse(OcrDocReadingOrder.isVerticalCjkWord(null, 30f, 400f));
    assertFalse(OcrDocReadingOrder.isVerticalCjkWord("", 30f, 400f));
    assertFalse(OcrDocReadingOrder.isVerticalCjkWord("   ", 30f, 400f));
  }

  @Test
  public void invalidBoxDimensions_isNotVertical() {
    assertFalse(OcrDocReadingOrder.isVerticalCjkWord("円", 0f, 400f));
    assertFalse(OcrDocReadingOrder.isVerticalCjkWord("円", -5f, 400f));
  }

  @Test
  public void aspectThresholdBoundary() {
    // Exactly 2× as tall as wide counts as vertical; just below does not.
    assertTrue(OcrDocReadingOrder.isVerticalCjkWord("円円", 30f, 60f));
    assertFalse(OcrDocReadingOrder.isVerticalCjkWord("円円", 30f, 59f));
  }
}
