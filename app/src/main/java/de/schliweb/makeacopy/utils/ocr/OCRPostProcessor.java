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

import java.util.*;
import java.util.regex.Pattern;

/**
 * Turns PaddleOCR words into text: reading order (horizontal, multi-column, vertical CJK columns),
 * right-to-left lines, paragraph breaks and HTML entity decoding.
 */
public class OCRPostProcessor {

  private OCRPostProcessor() {
    // utility class
  }

  /**
   * Converts a list of recognized words to a single text string. Words are sorted by their vertical
   * position (top to bottom) and then horizontal position, grouped into lines based on vertical
   * proximity, and joined with spaces within lines and newlines between lines. Paragraphs are
   * detected based on larger vertical gaps between lines. HTML entities in the text are decoded.
   *
   * <p>For RTL (right-to-left) scripts like Arabic and Persian, words within a line are sorted from
   * right to left to preserve correct reading order.
   *
   * @param words The list of recognized words to convert
   * @return The extracted text with proper line breaks and paragraphs, or empty string if words is
   *     null or empty
   */
  public static String wordsToText(List<RecognizedWord> words) {
    return wordsToText(words, false);
  }

  /**
   * Variant of {@link #wordsToText(List)} with optional multi-column layout reconstruction
   * (newspapers, magazines). When {@code multiColumn} is {@code true} and the box geometry forms a
   * multi-column layout (see {@link MultiColumnLayoutPolicy}), the text is rebuilt segment by
   * segment in column reading order: full-width headlines first, then each column completely
   * top-to-bottom, columns left-to-right (or right-to-left for predominantly RTL content).
   * Otherwise the regular single-flow reconstruction is used.
   *
   * @param words The list of recognized words to convert
   * @param multiColumn whether multi-column layout reconstruction is enabled (user option)
   * @return The extracted text with proper line breaks and paragraphs, or empty string if words is
   *     null or empty
   */
  public static String wordsToText(List<RecognizedWord> words, boolean multiColumn) {
    if (words == null || words.isEmpty()) {
      return "";
    }

    // Vertical CJK layout (top-to-bottom columns, read right→left): the horizontal line
    // grouping below would merge all columns into one "line" sorted left→right, reversing
    // the column reading order. Detect the layout from the box geometry and reconstruct the
    // text column-wise instead. Horizontal (LTR/RTL) documents are unaffected.
    if (VerticalTextLayoutPolicy.isVerticalLayout(words)) {
      return verticalWordsToText(words);
    }

    if (multiColumn) {
      String columnText = multiColumnWordsToText(words);
      if (columnText != null) {
        return columnText;
      }
    }

    return horizontalWordsToText(words);
  }

  /**
   * Reconstructs the text of a horizontal (LTR/RTL) page: words are grouped into visual lines by
   * vertical proximity, ordered by the line's reading direction and joined with paragraph detection
   * based on vertical gaps.
   *
   * @param words the recognized words (non-null, non-empty)
   * @return the reconstructed text
   */
  /**
   * The words grouped into lines, top to bottom, by the one line-grouping rule of the whole app
   * (see {@link LineGrouping}): a drop cap or a headline word next to body text neither widens the
   * tolerance beyond the line pitch nor lands in the wrong line. The text and the PDF text layer
   * both use it, so they read the page in the same order.
   */
  public static List<List<RecognizedWord>> groupLines(List<RecognizedWord> words) {
    int n = words.size();
    float[] lefts = new float[n];
    float[] tops = new float[n];
    float[] rights = new float[n];
    float[] bottoms = new float[n];
    for (int i = 0; i < n; i++) {
      android.graphics.RectF r = words.get(i).getBoundingBox();
      lefts[i] = r.left;
      tops[i] = r.top;
      rights[i] = r.right;
      bottoms[i] = r.bottom;
    }
    List<List<RecognizedWord>> lines = new ArrayList<>();
    for (int[] line : LineGrouping.groupIntoLines(lefts, tops, rights, bottoms)) {
      List<RecognizedWord> members = new ArrayList<>(line.length);
      for (int idx : line) members.add(words.get(idx));
      lines.add(members);
    }
    return lines;
  }

  private static String horizontalWordsToText(List<RecognizedWord> words) {
    List<List<RecognizedWord>> lines = groupLines(words);

    // Calculate average line height for paragraph detection
    float totalLineHeight = 0;
    int lineCount = 0;
    for (List<RecognizedWord> line : lines) {
      for (RecognizedWord w : line) {
        totalLineHeight += w.height();
        lineCount++;
      }
    }
    float avgWordHeight = lineCount > 0 ? totalLineHeight / lineCount : 20f;
    // Paragraph threshold: gap larger than 1.5x average word height indicates new paragraph
    float paragraphThreshold = avgWordHeight * 1.5f;

    // Sort words within each line by X position and build text
    StringBuilder result = new StringBuilder();
    float lastLineBottomY = Float.MIN_VALUE;

    for (int i = 0; i < lines.size(); i++) {
      List<RecognizedWord> line = lines.get(i);

      // Determine if this line is predominantly RTL based on Unicode directionality
      boolean isRtlLine = isLineRtl(line);

      // Sort words left to right, then into reading order: an RTL line reads its Arabic words
      // right to left but embedded Latin words in their own order, an LTR line the other way
      // round (issue #101)
      line.sort((a, b) -> Float.compare(a.getBoundingBox().left, b.getBoundingBox().left));
      BidiWordOrder.toLogical(line, RecognizedWord::getText, isRtlLine);

      // Calculate line's top Y for paragraph detection
      float lineTopY = Float.MAX_VALUE;
      for (RecognizedWord w : line) {
        lineTopY = Math.min(lineTopY, w.getBoundingBox().top);
      }

      // Check for paragraph break (large vertical gap)
      if (i > 0 && lastLineBottomY != Float.MIN_VALUE) {
        float gap = lineTopY - lastLineBottomY;
        if (gap > paragraphThreshold) {
          // Insert extra newline for paragraph break
          result.append("\n");
        }
      }

      for (int j = 0; j < line.size(); j++) {
        if (j > 0) {
          result.append(" ");
        }
        // Decode HTML entities in the word text. RecognizedWord.text is expected to be stored in
        // logical Unicode order; RTL handling here is limited to line ordering by geometry.
        result.append(decodeHtmlEntities(line.get(j).getText()));
      }

      if (i < lines.size() - 1) {
        result.append("\n");
      }

      // Update last line bottom Y
      float lineBottomY = Float.MIN_VALUE;
      for (RecognizedWord w : line) {
        lineBottomY = Math.max(lineBottomY, w.getBoundingBox().bottom);
      }
      lastLineBottomY = lineBottomY;
    }

    return result.toString();
  }

  /**
   * Reconstructs the text of a multi-column page (newspapers, magazines): the column grouping is
   * delegated to {@link MultiColumnLayoutPolicy#groupIntoColumnSegments}, then every segment (a
   * full-width band or one column of a band) is rebuilt with the regular line/paragraph logic and
   * the segments are joined with blank lines. Columns are read right-to-left when the page content
   * is predominantly RTL.
   *
   * @param words the recognized words (non-null, non-empty)
   * @return the reconstructed text, or {@code null} if the boxes do not form a multi-column layout
   *     (caller falls back to the single-flow reconstruction)
   */
  private static String multiColumnWordsToText(List<RecognizedWord> words) {
    int n = words.size();
    float[] lefts = new float[n];
    float[] tops = new float[n];
    float[] rights = new float[n];
    float[] bottoms = new float[n];
    for (int i = 0; i < n; i++) {
      android.graphics.RectF r = words.get(i).getBoundingBox();
      lefts[i] = r.left;
      tops[i] = r.top;
      rights[i] = r.right;
      bottoms[i] = r.bottom;
    }
    boolean rtl = isLineRtl(words);
    List<int[]> segments =
        MultiColumnLayoutPolicy.groupIntoColumnSegments(lefts, tops, rights, bottoms, rtl);
    if (segments.size() < 2) {
      return null;
    }
    StringBuilder result = new StringBuilder();
    for (int s = 0; s < segments.size(); s++) {
      List<RecognizedWord> segmentWords = new ArrayList<>(segments.get(s).length);
      for (int idx : segments.get(s)) {
        segmentWords.add(words.get(idx));
      }
      String segmentText = horizontalWordsToText(segmentWords);
      if (segmentText.isEmpty()) continue;
      if (result.length() > 0) {
        result.append("\n\n");
      }
      result.append(segmentText);
    }
    return result.toString();
  }

  /**
   * Reconstructs the text of a vertical (CJK top-to-bottom) page: columns are ordered
   * right-to-left, segments within a column top-to-bottom. Segments of the same column are
   * concatenated without artificial spaces; complete columns are separated by a newline. The column
   * grouping is delegated to {@link VerticalTextLayoutPolicy#groupIntoVerticalColumns} so the
   * ordering is deterministic and covered by JVM unit tests.
   *
   * @param words the recognized words (non-null, non-empty)
   * @return the reconstructed text in vertical reading order
   */
  private static String verticalWordsToText(List<RecognizedWord> words) {
    int n = words.size();
    float[] lefts = new float[n];
    float[] tops = new float[n];
    float[] rights = new float[n];
    float[] bottoms = new float[n];
    for (int i = 0; i < n; i++) {
      android.graphics.RectF r = words.get(i).getBoundingBox();
      lefts[i] = r.left;
      tops[i] = r.top;
      rights[i] = r.right;
      bottoms[i] = r.bottom;
    }
    List<int[]> columns =
        VerticalTextLayoutPolicy.groupIntoVerticalColumns(lefts, tops, rights, bottoms);
    StringBuilder result = new StringBuilder();
    for (int c = 0; c < columns.size(); c++) {
      if (c > 0) {
        result.append('\n');
      }
      for (int idx : columns.get(c)) {
        String text = words.get(idx).getText();
        if (text == null) continue;
        result.append(decodeHtmlEntities(text));
      }
    }
    return result.toString();
  }

  /**
   * Determines if a line of words is predominantly RTL (right-to-left) based on Unicode
   * directionality analysis of the text content.
   *
   * <p>This method counts RTL and LTR characters in all words of the line and returns true if RTL
   * characters are the majority.
   *
   * @param line The list of words in the line
   * @return true if the line is predominantly RTL, false otherwise
   */
  private static boolean isLineRtl(List<RecognizedWord> line) {
    if (line == null || line.isEmpty()) {
      return false;
    }

    int rtlCount = 0;
    int ltrCount = 0;

    for (RecognizedWord word : line) {
      String text = word.getText();
      if (text == null) continue;

      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        byte directionality = Character.getDirectionality(c);

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
        // Neutral characters (spaces, punctuation, digits) are not counted
      }
    }

    // Line is RTL if RTL characters are the majority
    return rtlCount > ltrCount;
  }

  /**
   * Decodes common HTML entities in a string. Handles numeric entities (&#39;, &#34;, etc.) and
   * named entities (&amp;, &lt;, &gt;, &quot;, &apos;).
   *
   * @param text The text containing HTML entities
   * @return The decoded text with entities replaced by their character equivalents
   */
  private static String decodeHtmlEntities(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }

    String result = text;

    // Decode numeric entities (decimal): &#39; -> '
    java.util.regex.Matcher decimalMatcher = Pattern.compile("&#(\\d+);").matcher(result);
    StringBuffer sb = new StringBuffer();
    while (decimalMatcher.find()) {
      try {
        int codePoint = Integer.parseInt(decimalMatcher.group(1));
        decimalMatcher.appendReplacement(
            sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) codePoint)));
      } catch (NumberFormatException e) {
        // Keep original if parsing fails
      }
    }
    decimalMatcher.appendTail(sb);
    result = sb.toString();

    // Decode numeric entities (hexadecimal): &#x27; -> '
    java.util.regex.Matcher hexMatcher = Pattern.compile("&#[xX]([0-9a-fA-F]+);").matcher(result);
    sb = new StringBuffer();
    while (hexMatcher.find()) {
      try {
        int codePoint = Integer.parseInt(hexMatcher.group(1), 16);
        hexMatcher.appendReplacement(
            sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) codePoint)));
      } catch (NumberFormatException e) {
        // Keep original if parsing fails
      }
    }
    hexMatcher.appendTail(sb);
    result = sb.toString();

    // Decode common named entities
    result = result.replace("&amp;", "&");
    result = result.replace("&lt;", "<");
    result = result.replace("&gt;", ">");
    result = result.replace("&quot;", "\"");
    result = result.replace("&apos;", "'");
    result = result.replace("&nbsp;", " ");

    return result;
  }
}
