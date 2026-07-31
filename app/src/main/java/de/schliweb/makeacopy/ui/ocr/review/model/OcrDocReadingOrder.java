/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.ocr.review.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** Helpers for reconstructing OCR review reading order from word boxes. */
public final class OcrDocReadingOrder {
  private OcrDocReadingOrder() {}

  public static List<OcrDoc.Word> sortedWordsForReading(OcrDoc doc) {
    List<OcrDoc.Word> result = new ArrayList<>();
    if (doc == null || doc.words == null || doc.words.isEmpty()) return result;

    if (doc.lines != null && !doc.lines.isEmpty()) {
      HashMap<Integer, OcrDoc.Word> byId = new HashMap<>();
      for (OcrDoc.Word w : doc.words) {
        if (w != null) byId.put(w.id, w);
      }
      List<OcrDoc.Line> lines = new ArrayList<>(doc.lines);
      lines.sort(Comparator.comparingInt(line -> line != null && line.b != null ? line.b[1] : 0));
      for (OcrDoc.Line line : lines) {
        if (line == null || line.w == null || line.w.length == 0) continue;
        List<OcrDoc.Word> lineWords = new ArrayList<>();
        for (int wid : line.w) {
          OcrDoc.Word w = byId.get(wid);
          if (w != null) lineWords.add(w);
        }
        sortLineWordsInReadingOrder(lineWords);
        result.addAll(lineWords);
      }
      return result;
    }

    List<List<OcrDoc.Word>> lines = groupWordsIntoLines(doc.words);
    for (List<OcrDoc.Word> line : lines) {
      sortLineWordsInReadingOrder(line);
      result.addAll(line);
    }
    return result;
  }

  public static void sortLineWordsInReadingOrder(List<OcrDoc.Word> words) {
    if (words == null || words.size() < 2) return;
    boolean rtl = isRtlLine(words);
    words.sort(
        (a, b) -> {
          int c = rtl ? Integer.compare(left(b), left(a)) : Integer.compare(left(a), left(b));
          if (c != 0) return c;
          c = Integer.compare(top(a), top(b));
          if (c != 0) return c;
          return Integer.compare(a != null ? a.id : 0, b != null ? b.id : 0);
        });
  }

  private static List<List<OcrDoc.Word>> groupWordsIntoLines(List<OcrDoc.Word> words) {
    List<OcrDoc.Word> clean = new ArrayList<>();
    for (OcrDoc.Word w : words) {
      if (w != null && w.b != null && w.b.length >= 4) clean.add(w);
    }
    clean.sort(
        (a, b) -> {
          int c = Integer.compare(centerY(a), centerY(b));
          if (c != 0) return c;
          return Integer.compare(left(a), left(b));
        });

    int tolerance = Math.max(6, medianHeight(clean));
    List<List<OcrDoc.Word>> lines = new ArrayList<>();
    for (OcrDoc.Word w : clean) {
      boolean added = false;
      for (List<OcrDoc.Word> line : lines) {
        if (Math.abs(centerY(w) - averageCenterY(line)) <= tolerance) {
          line.add(w);
          added = true;
          break;
        }
      }
      if (!added) {
        List<OcrDoc.Word> line = new ArrayList<>();
        line.add(w);
        lines.add(line);
      }
    }
    return lines;
  }

  private static boolean isRtlLine(List<OcrDoc.Word> words) {
    int rtl = 0;
    int ltr = 0;
    for (OcrDoc.Word w : words) {
      String text = w != null && w.t != null ? w.t : "";
      int[] counts = countDirections(text);
      rtl += counts[0];
      ltr += counts[1];
    }
    return rtl > ltr;
  }

  /**
   * Aspect-ratio threshold above which a word box counts as a vertical column: the box must be at
   * least twice as tall as it is wide.
   */
  private static final float VERTICAL_BOX_ASPECT = 2.0f;

  /**
   * Decides whether a word must be rendered as a vertical (top-to-bottom) CJK column: the box is
   * clearly taller than wide and the text is dominated by CJK code points. Pure function so the
   * decision is covered by JVM unit tests.
   *
   * @param text the recognized text of the word (may be null)
   * @param boxWidth the width of the word box in image pixels
   * @param boxHeight the height of the word box in image pixels
   * @return true when the word should be drawn character-by-character top-to-bottom
   */
  public static boolean isVerticalCjkWord(String text, float boxWidth, float boxHeight) {
    if (text == null || text.isEmpty()) return false;
    if (boxWidth <= 0 || boxHeight < boxWidth * VERTICAL_BOX_ASPECT) return false;
    int cjk = 0;
    int total = 0;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      if (!Character.isWhitespace(cp)) {
        total++;
        if (isCjkCodePoint(cp)) cjk++;
      }
      i += Character.charCount(cp);
    }
    return total > 0 && cjk * 2 >= total;
  }

  private static boolean isCjkCodePoint(int cp) {
    Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
    return Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS.equals(block)
        || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A.equals(block)
        || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B.equals(block)
        || Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS.equals(block)
        || Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION.equals(block)
        || Character.UnicodeBlock.HIRAGANA.equals(block)
        || Character.UnicodeBlock.KATAKANA.equals(block)
        || Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS.equals(block)
        || Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS.equals(block)
        || Character.UnicodeBlock.HANGUL_SYLLABLES.equals(block)
        || Character.UnicodeBlock.HANGUL_JAMO.equals(block)
        || Character.UnicodeBlock.BOPOMOFO.equals(block);
  }

  public static boolean isRtlText(String text) {
    int[] counts = countDirections(text != null ? text : "");
    return counts[0] > counts[1];
  }

  private static int[] countDirections(String text) {
    int rtl = 0;
    int ltr = 0;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      byte dir = Character.getDirectionality(cp);
      if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
          || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
          || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
          || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
        rtl++;
      } else if (dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT
          || dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
          || dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) {
        ltr++;
      }
      i += Character.charCount(cp);
    }
    return new int[] {rtl, ltr};
  }

  private static int medianHeight(List<OcrDoc.Word> words) {
    if (words == null || words.isEmpty()) return 0;
    ArrayList<Integer> heights = new ArrayList<>(words.size());
    for (OcrDoc.Word w : words) heights.add(Math.max(0, height(w)));
    heights.sort(Integer::compareTo);
    return heights.get(heights.size() / 2);
  }

  private static int averageCenterY(List<OcrDoc.Word> words) {
    if (words == null || words.isEmpty()) return 0;
    int sum = 0;
    for (OcrDoc.Word w : words) sum += centerY(w);
    return sum / words.size();
  }

  private static int left(OcrDoc.Word w) {
    return w != null && w.b != null && w.b.length >= 1 ? w.b[0] : 0;
  }

  private static int top(OcrDoc.Word w) {
    return w != null && w.b != null && w.b.length >= 2 ? w.b[1] : 0;
  }

  private static int height(OcrDoc.Word w) {
    return w != null && w.b != null && w.b.length >= 4 ? w.b[3] : 0;
  }

  private static int centerY(OcrDoc.Word w) {
    return top(w) + height(w) / 2;
  }
}
