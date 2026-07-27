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

import lombok.experimental.UtilityClass;

/**
 * Pure-math helpers for rendering vertical CJK text columns into the searchable PDF text layer.
 *
 * <p>PaddleOCR emits vertical Japanese/CJK columns as one {@code RecognizedWord} per column with a
 * tall, narrow bounding box in (unrotated) page/image coordinates and the column text in logical
 * top-to-bottom order (see {@code PaddleResultBuilder}). This class provides the geometry used by
 * {@code PdfCreator} to write such columns as rotated text runs so that:
 *
 * <ul>
 *   <li>the content stream keeps the logical Unicode order (copy/search/extraction work),
 *   <li>columns are emitted right-to-left (Japanese vertical reading order),
 *   <li>glyph positions overlay the visible column inside the page.
 * </ul>
 *
 * <p>All methods intentionally operate on primitive floats/strings only so they can be verified in
 * plain JVM unit tests (android.graphics.RectF is stubbed there).
 */
@UtilityClass
public class PdfVerticalTextUtils {

  /** Minimum height/width aspect ratio for a word box to be considered a vertical column. */
  static final float MIN_VERTICAL_ASPECT = 1.8f;

  /** Minimum fraction of CJK code points required to treat a tall box as vertical CJK text. */
  static final float MIN_CJK_RATIO = 0.5f;

  /** Lower bound for the derived font size (image-space units, like PdfCreator.MIN_FONT_PT). */
  static final float MIN_FONT_SIZE = 2f;

  /** Ratio of column width used as font size (mirrors PdfCreator.TEXT_SIZE_RATIO). */
  static final float FONT_SIZE_RATIO = 0.70f;

  /** Clamp bounds for the advance scale that stretches the run to the column height. */
  static final float MIN_ADVANCE_SCALE = 0.50f;

  static final float MAX_ADVANCE_SCALE = 2.00f;

  /**
   * Returns whether the given code point belongs to a script/range typically used in vertical CJK
   * writing (Han, Kana, CJK punctuation, fullwidth/halfwidth forms).
   *
   * @param cp the Unicode code point
   * @return true if the code point is CJK-related
   */
  public static boolean isCjkCodePoint(int cp) {
    return (cp >= 0x3000 && cp <= 0x303F) // CJK symbols and punctuation
        || (cp >= 0x3040 && cp <= 0x309F) // Hiragana
        || (cp >= 0x30A0 && cp <= 0x30FF) // Katakana
        || (cp >= 0x31F0 && cp <= 0x31FF) // Katakana phonetic extensions
        || (cp >= 0x3400 && cp <= 0x4DBF) // CJK extension A
        || (cp >= 0x4E00 && cp <= 0x9FFF) // CJK unified ideographs
        || (cp >= 0xF900 && cp <= 0xFAFF) // CJK compatibility ideographs
        || (cp >= 0xFF00 && cp <= 0xFFEF) // Fullwidth/halfwidth forms
        || (cp >= 0x20000 && cp <= 0x2FA1F); // CJK extensions B+ and compat supplement
  }

  /**
   * Detects whether a recognized word should be rendered as a vertical CJK column.
   *
   * <p>The decision is purely geometric plus script-based: the box must be clearly taller than wide
   * (typical for a rotated recognition crop of a vertical column), contain at least two code
   * points, and consist mostly of CJK code points. Horizontal CJK, Latin, RTL and single characters
   * never match, so the existing horizontal paths are unaffected.
   *
   * @param text the recognized text of the word (logical order, top-to-bottom)
   * @param width the bounding-box width in image pixels
   * @param height the bounding-box height in image pixels
   * @return true if the word is a vertical CJK column
   */
  public static boolean isVerticalCjkColumn(String text, float width, float height) {
    if (text == null) return false;
    String t = text.trim();
    if (t.isEmpty()) return false;
    if (!(width > 0f) || !(height > 0f)) return false;
    if (height < MIN_VERTICAL_ASPECT * width) return false;

    int total = 0;
    int cjk = 0;
    for (int i = 0; i < t.length(); ) {
      int cp = t.codePointAt(i);
      if (!Character.isWhitespace(cp)) {
        total++;
        if (isCjkCodePoint(cp)) cjk++;
      }
      i += Character.charCount(cp);
    }
    if (total < 2) return false;
    return cjk >= total * MIN_CJK_RATIO;
  }

  /**
   * Computes the font size for a vertical column from the column width (the narrow, plausible
   * dimension). The column height must never be used: it is the run length, not the glyph size.
   *
   * @param columnWidth the column width in image pixels
   * @return the clamped font size in image-space units, always in [MIN_FONT_SIZE, columnWidth]
   *     (with a minimum of MIN_FONT_SIZE for degenerate boxes)
   */
  public static float columnFontSize(float columnWidth) {
    float size = columnWidth * FONT_SIZE_RATIO;
    float upper = Math.max(MIN_FONT_SIZE, columnWidth);
    return Math.max(MIN_FONT_SIZE, Math.min(size, upper));
  }

  /**
   * Computes the PDF text matrix for one vertical column as {@code [a, b, c, d, e, f]}.
   *
   * <p>The matrix rotates the text run by -90° so the writing direction points down the page while
   * the content stream keeps the logical character order. The advance is scaled so that the
   * measured run length matches the visible column height (clamped to avoid extreme matrices from
   * tiny measured widths).
   *
   * @param columnLeft left edge of the column box in image pixels (Android top-left origin)
   * @param columnTop top edge of the column box in image pixels (Android top-left origin)
   * @param columnHeight column box height in image pixels
   * @param measuredTextWidth the horizontal advance of the run at the chosen font size
   * @param imageWidth image width in pixels (for clamping)
   * @param imageHeight image height in pixels (for the Y-up conversion and clamping)
   * @return the text matrix entries {a, b, c, d, e, f}
   */
  public static float[] columnTextMatrix(
      float columnLeft,
      float columnTop,
      float columnHeight,
      float measuredTextWidth,
      int imageWidth,
      int imageHeight) {
    float advanceScale = 1f;
    if (measuredTextWidth > 1e-3f && columnHeight > 0f) {
      advanceScale = columnHeight / measuredTextWidth;
      advanceScale = Math.max(MIN_ADVANCE_SCALE, Math.min(MAX_ADVANCE_SCALE, advanceScale));
    }

    // Anchor at the top-left corner of the column; glyph bodies extend along text-space +y,
    // which the rotation maps to +x (to the right), i.e. into the column box.
    float x = clamp(columnLeft, 0f, imageWidth);
    float y = clamp(imageHeight - columnTop, 0f, imageHeight);

    // Rotation by -90°: advance (text-space +x) maps to (0, -advanceScale) = down the page,
    // glyph up direction (text-space +y) maps to (1, 0) = right.
    return new float[] {0f, -advanceScale, 1f, 0f, x, y};
  }

  /**
   * Comparator value ordering vertical columns right-to-left by their horizontal center (Japanese
   * vertical reading order: the rightmost column is read first).
   *
   * @param centerXa horizontal center of column A
   * @param centerXb horizontal center of column B
   * @return negative if A comes first (A is further right), positive if B comes first
   */
  public static int compareColumnsRightToLeft(float centerXa, float centerXb) {
    return Float.compare(centerXb, centerXa);
  }

  private static float clamp(float v, float min, float max) {
    if (v < min) return min;
    if (v > max) return max;
    return v;
  }
}
