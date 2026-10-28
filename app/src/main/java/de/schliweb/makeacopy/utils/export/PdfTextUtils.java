/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.export;

import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Utility class for PDF text layer operations extracted from PdfCreator. Contains RTL text
 * detection and reordering, font loading with fallbacks, and text rendering with font fallback
 * support.
 *
 * <p>This class cannot be instantiated.
 */
@UtilityClass
public final class PdfTextUtils {

  /**
   * Determines if a line of recognized words is predominantly RTL (Right-to-Left) script. This is
   * used for BiDi sorting in PDF text layers to ensure correct reading order for Arabic, Persian,
   * Hebrew, and other RTL scripts.
   *
   * @param line The list of recognized words in the line.
   * @return true if the line contains predominantly RTL characters, false otherwise.
   */
  public static boolean isRtlLine(List<RecognizedWord> line) {
    if (line == null || line.isEmpty()) return false;

    int rtlCount = 0;
    int ltrCount = 0;

    for (RecognizedWord w : line) {
      String text = w.getText();
      if (text == null) continue;

      for (int i = 0; i < text.length(); ) {
        int cp = text.codePointAt(i);
        byte directionality = Character.getDirectionality(cp);

        // RTL scripts: Arabic, Hebrew, etc.
        if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT
            || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
            || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
          rtlCount++;
        }
        // LTR scripts: Latin, etc.
        else if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT
            || directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
            || directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) {
          ltrCount++;
        }
        // Neutral characters (numbers, punctuation) are ignored

        i += Character.charCount(cp);
      }
    }

    // Line is RTL if it has more RTL characters than LTR characters
    return rtlCount > ltrCount;
  }

  /**
   * Checks if a single word/token contains predominantly RTL characters.
   *
   * @param text The text to check.
   * @return true if the text contains predominantly RTL characters, false otherwise.
   */
  public static boolean isRtlText(String text) {
    if (text == null || text.isEmpty()) return false;

    int rtlCount = 0;
    int ltrCount = 0;

    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      byte directionality = Character.getDirectionality(cp);

      if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT
          || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
          || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
          || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
        rtlCount++;
      } else if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT
          || directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
          || directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) {
        ltrCount++;
      }

      i += Character.charCount(cp);
    }

    return rtlCount > ltrCount;
  }

  /**
   * Checks if a list of recognized words contains any RTL (Right-to-Left) text. Used to determine
   * if gentle B/W processing should be applied to preserve fine strokes and diacritics in Arabic,
   * Persian, and Hebrew scripts.
   *
   * @param words The list of recognized words to check.
   * @return true if any word contains RTL characters, false otherwise.
   */
  public static boolean containsRtlText(List<RecognizedWord> words) {
    if (words == null || words.isEmpty()) return false;

    for (RecognizedWord w : words) {
      if (w == null) continue;
      String text = w.getText();
      if (isRtlText(text)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Lays out one logical line for the PDF content stream, where glyphs always advance to the right:
   * with an RTL base the runs are put into visual order by the Unicode bidi algorithm and every
   * right-to-left run is written back to front, while embedded Latin words and numbers keep their
   * order. The result, drawn from the line's left edge, lands each word on its own ink, and a
   * bidi-aware extractor turns it back into the logical line. Lines without an RTL base are
   * returned unchanged.
   *
   * @param logical the line in logical (reading) order
   * @param rtlBase whether the line reads right to left
   * @return the glyph sequence to write, left to right
   */
  public static String logicalToVisual(String logical, boolean rtlBase) {
    if (logical == null || logical.length() < 2 || !rtlBase) return logical;
    java.text.Bidi bidi = new java.text.Bidi(logical, java.text.Bidi.DIRECTION_RIGHT_TO_LEFT);
    int n = bidi.getRunCount();
    byte[] levels = new byte[n];
    String[] runs = new String[n];
    for (int i = 0; i < n; i++) {
      levels[i] = (byte) bidi.getRunLevel(i);
      runs[i] = logical.substring(bidi.getRunStart(i), bidi.getRunLimit(i));
    }
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) order[i] = i;
    java.text.Bidi.reorderVisually(levels, 0, order, 0, n);
    StringBuilder sb = new StringBuilder(logical.length());
    for (int i = 0; i < n; i++) {
      int run = order[i];
      sb.append((levels[run] & 1) == 1 ? reverseCodepoints(runs[run]) : runs[run]);
    }
    return sb.toString();
  }

  private static String reverseCodepoints(String s) {
    int[] cps = s.codePoints().toArray();
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = cps.length - 1; i >= 0; i--) sb.appendCodePoint(cps[i]);
    return sb.toString();
  }

  /**
   * Reorders text for correct PDF text layer representation. For RTL text that appears to be in
   * visual order (reversed), this method reverses it back to logical order so that copy/paste works
   * correctly.
   *
   * @param text The text to potentially reorder.
   * @return The text in logical order suitable for PDF text layer.
   */
  public static String reorderRtlForPdf(String text) {
    if (text == null || text.isEmpty()) return text;

    // Only process if the text contains RTL characters
    if (!isRtlText(text)) return text;

    // Use Java's Bidi class to analyze the text
    java.text.Bidi bidi = new java.text.Bidi(text, java.text.Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);

    if (bidi.isRightToLeft() || bidi.isLeftToRight()) {
      // Find first RTL character position
      int firstRtlPos = -1;
      for (int i = 0; i < text.length(); ) {
        int cp = text.codePointAt(i);
        byte dir = Character.getDirectionality(cp);
        if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
            || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
          if (firstRtlPos < 0) firstRtlPos = i;
        }
        i += Character.charCount(cp);
      }

      if (firstRtlPos >= 0) {
        // Reverse the string to convert from visual to logical order
        StringBuilder sb = new StringBuilder(text);
        return sb.reverse().toString();
      }
    }

    return text;
  }
}
