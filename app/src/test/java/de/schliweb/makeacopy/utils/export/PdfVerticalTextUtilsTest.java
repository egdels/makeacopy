/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM unit tests for {@link PdfVerticalTextUtils}: vertical CJK column detection, right-to-left
 * column ordering, font size derivation from the column width and the rotated text matrix
 * (coordinate invariants for issue #88).
 */
public class PdfVerticalTextUtilsTest {

  private static final String COL_RIGHT = "すると、1億円を受け取った親は家に帰らなくなった。";
  private static final String COL_MIDDLE = "1億円で遊んでいるのだろう。";
  private static final String COL_LEFT = "なくなったらたかりに来ると思ったので、親に内緒で高級マンションに引っ越すことにした。";

  // ---------------------------------------------------------------------
  // isVerticalCjkColumn
  // ---------------------------------------------------------------------

  @Test
  public void verticalJapaneseColumn_isDetected() {
    assertTrue(PdfVerticalTextUtils.isVerticalCjkColumn(COL_RIGHT, 40f, 900f));
    assertTrue(PdfVerticalTextUtils.isVerticalCjkColumn(COL_MIDDLE, 38f, 500f));
    assertTrue(PdfVerticalTextUtils.isVerticalCjkColumn(COL_LEFT, 42f, 1200f));
  }

  @Test
  public void horizontalCjkLine_isNotDetected() {
    // Wide box: normal horizontal Japanese line must use the existing path.
    assertFalse(PdfVerticalTextUtils.isVerticalCjkColumn(COL_MIDDLE, 500f, 40f));
  }

  @Test
  public void latinTallBox_isNotDetected() {
    // Tall but Latin (e.g. rotated Latin word or a narrow column of digits + letters).
    assertFalse(PdfVerticalTextUtils.isVerticalCjkColumn("Hello World", 30f, 300f));
  }

  @Test
  public void rtlText_isNotDetected() {
    assertFalse(PdfVerticalTextUtils.isVerticalCjkColumn("مرحبا بالعالم", 30f, 300f));
  }

  @Test
  public void singleCjkCharacter_isNotDetected() {
    // A single tall glyph box must not switch to the vertical path.
    assertFalse(PdfVerticalTextUtils.isVerticalCjkColumn("親", 30f, 60f));
  }

  @Test
  public void nearSquareBox_isNotDetected() {
    // Aspect below threshold (1.8): stays horizontal.
    assertFalse(PdfVerticalTextUtils.isVerticalCjkColumn("親は", 40f, 60f));
  }

  @Test
  public void emptyOrNullText_isNotDetected() {
    assertFalse(PdfVerticalTextUtils.isVerticalCjkColumn(null, 30f, 300f));
    assertFalse(PdfVerticalTextUtils.isVerticalCjkColumn("", 30f, 300f));
    assertFalse(PdfVerticalTextUtils.isVerticalCjkColumn("   ", 30f, 300f));
  }

  @Test
  public void degenerateBox_isNotDetected() {
    assertFalse(PdfVerticalTextUtils.isVerticalCjkColumn(COL_RIGHT, 0f, 300f));
    assertFalse(PdfVerticalTextUtils.isVerticalCjkColumn(COL_RIGHT, 30f, 0f));
    assertFalse(PdfVerticalTextUtils.isVerticalCjkColumn(COL_RIGHT, -5f, 300f));
  }

  @Test
  public void mixedCjkWithDigits_isDetected() {
    // "1億円…" contains ASCII digits; majority CJK must still win.
    assertTrue(PdfVerticalTextUtils.isVerticalCjkColumn("1億円で遊んでいる", 40f, 400f));
  }

  // ---------------------------------------------------------------------
  // isCjkCodePoint
  // ---------------------------------------------------------------------

  @Test
  public void cjkCodePointClassification() {
    assertTrue(PdfVerticalTextUtils.isCjkCodePoint('漢'));
    assertTrue(PdfVerticalTextUtils.isCjkCodePoint('ひ'));
    assertTrue(PdfVerticalTextUtils.isCjkCodePoint('カ'));
    assertTrue(PdfVerticalTextUtils.isCjkCodePoint('っ'));
    assertTrue(PdfVerticalTextUtils.isCjkCodePoint('。'));
    assertTrue(PdfVerticalTextUtils.isCjkCodePoint('、'));
    assertTrue(PdfVerticalTextUtils.isCjkCodePoint('！')); // fullwidth
    assertFalse(PdfVerticalTextUtils.isCjkCodePoint('A'));
    assertFalse(PdfVerticalTextUtils.isCjkCodePoint('1'));
    assertFalse(PdfVerticalTextUtils.isCjkCodePoint('ä'));
  }

  // ---------------------------------------------------------------------
  // Column ordering (right → left)
  // ---------------------------------------------------------------------

  @Test
  public void columnsAreOrderedRightToLeft() {
    // Right column centerX=800, middle=500, left=200.
    assertTrue(PdfVerticalTextUtils.compareColumnsRightToLeft(800f, 500f) < 0);
    assertTrue(PdfVerticalTextUtils.compareColumnsRightToLeft(200f, 500f) > 0);
    assertEquals(0, PdfVerticalTextUtils.compareColumnsRightToLeft(500f, 500f));
  }

  // ---------------------------------------------------------------------
  // Font size
  // ---------------------------------------------------------------------

  @Test
  public void fontSizeDerivedFromColumnWidth_notHeight() {
    float w = 40f;
    float size = PdfVerticalTextUtils.columnFontSize(w);
    assertEquals(w * PdfVerticalTextUtils.FONT_SIZE_RATIO, size, 0.001f);
    // Never larger than the column width, never below the minimum.
    assertTrue(size <= w);
    assertTrue(size >= PdfVerticalTextUtils.MIN_FONT_SIZE);
  }

  @Test
  public void fontSizeClampedForTinyColumns() {
    assertEquals(PdfVerticalTextUtils.MIN_FONT_SIZE, PdfVerticalTextUtils.columnFontSize(1f), 0f);
    assertEquals(PdfVerticalTextUtils.MIN_FONT_SIZE, PdfVerticalTextUtils.columnFontSize(0f), 0f);
  }

  @Test
  public void fontSizeNeverExtreme() {
    // Even a pathologically wide "column" yields fontSize <= width (no runaway values).
    float size = PdfVerticalTextUtils.columnFontSize(10000f);
    assertTrue(size <= 10000f);
  }

  // ---------------------------------------------------------------------
  // Text matrix invariants
  // ---------------------------------------------------------------------

  @Test
  public void matrixOriginStaysInsideImage() {
    int imgW = 1000;
    int imgH = 1500;
    float[] m = PdfVerticalTextUtils.columnTextMatrix(800f, 100f, 900f, 850f, imgW, imgH);
    float x = m[4];
    float y = m[5];
    assertTrue("0 <= textX <= imageWidth", x >= 0f && x <= imgW);
    assertTrue("0 <= textY <= imageHeight", y >= 0f && y <= imgH);
    // Anchor at column top-left (image y flipped to PDF y-up).
    assertEquals(800f, x, 0.001f);
    assertEquals(imgH - 100f, y, 0.001f);
  }

  @Test
  public void matrixIsMinus90DegreeRotation() {
    float[] m = PdfVerticalTextUtils.columnTextMatrix(800f, 100f, 900f, 900f, 1000, 1500);
    // [a b c d] = [0 -s 1 0]: advance points down the page, glyph-up points right.
    assertEquals(0f, m[0], 0.001f);
    assertTrue("advance must point downwards", m[1] < 0f);
    assertEquals(1f, m[2], 0.001f);
    assertEquals(0f, m[3], 0.001f);
  }

  @Test
  public void advanceScaleIsClamped() {
    // Tiny measured width must not explode the matrix.
    float[] m = PdfVerticalTextUtils.columnTextMatrix(100f, 100f, 900f, 0.01f, 1000, 1500);
    assertTrue(Math.abs(m[1]) <= PdfVerticalTextUtils.MAX_ADVANCE_SCALE);
    // Very long measured width must not collapse below the minimum.
    m = PdfVerticalTextUtils.columnTextMatrix(100f, 100f, 90f, 100000f, 1000, 1500);
    assertTrue(Math.abs(m[1]) >= PdfVerticalTextUtils.MIN_ADVANCE_SCALE);
  }

  @Test
  public void matrixClampsOutOfBoundsOrigins() {
    float[] m = PdfVerticalTextUtils.columnTextMatrix(-50f, -20f, 900f, 850f, 1000, 1500);
    assertTrue(m[4] >= 0f);
    assertTrue(m[5] <= 1500f);
    m = PdfVerticalTextUtils.columnTextMatrix(2000f, 3000f, 900f, 850f, 1000, 1500);
    assertTrue(m[4] <= 1000f);
    assertTrue(m[5] >= 0f);
  }
}
