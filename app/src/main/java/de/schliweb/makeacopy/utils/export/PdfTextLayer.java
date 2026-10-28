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

import android.graphics.RectF;
import de.schliweb.makeacopy.utils.ocr.BidiWordOrder;
import de.schliweb.makeacopy.utils.ocr.MultiColumnLayoutPolicy;
import de.schliweb.makeacopy.utils.ocr.OCRPostProcessor;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The invisible text layer of a page as PDF content operators, in image pixel space with the origin
 * at the bottom left (the caller maps it onto the image with one {@code cm}).
 *
 * <p>Each OCR line is one text run in the {@link GlyphlessFont}, scaled horizontally to the line's
 * box: positioning every word on its own makes some extractors read the gaps as extra spaces or
 * line breaks. Lines come from {@link OCRPostProcessor#groupLines}, the grouping the text export
 * uses as well. Right-to-left lines are written in visual order (the PDF shows glyphs left to
 * right; extractors restore the logical order), vertical CJK columns as rotated runs from right to
 * left, and with {@code multiColumn} the columns of a page one after the other.
 */
final class PdfTextLayer {
  /** Font size relative to the OCR box height. */
  private static final float TEXT_SIZE_RATIO = 0.70f;

  private static final float MIN_FONT_SIZE = 2f;
  private static final float MIN_LINE_TEXT_SCALE_X = 0.50f;
  private static final float MAX_LINE_TEXT_SCALE_X = 2.00f;

  private PdfTextLayer() {}

  /** The operators for {@code words} on an image of {@code imageWidth x imageHeight} pixels. */
  static String build(
      List<RecognizedWord> words, int imageWidth, int imageHeight, boolean multiColumn) {
    StringBuilder out = new StringBuilder();
    if (words == null || words.isEmpty()) return "";
    List<RecognizedWord> clean = clean(words, imageWidth, imageHeight);
    if (clean.isEmpty()) return "";

    // PaddleOCR reports a vertical Japanese column as one word with a tall, narrow box and the
    // text top to bottom (issue #88); the horizontal line grouping would merge all columns.
    List<RecognizedWord> verticalColumns = new ArrayList<>();
    for (int i = clean.size() - 1; i >= 0; i--) {
      RecognizedWord w = clean.get(i);
      RectF b = w.getBoundingBox();
      if (PdfVerticalTextUtils.isVerticalCjkColumn(w.getText(), b.width(), b.height())) {
        verticalColumns.add(w);
        clean.remove(i);
      }
    }
    if (!verticalColumns.isEmpty()) {
      verticalColumns.sort(
          (a, b) ->
              PdfVerticalTextUtils.compareColumnsRightToLeft(
                  a.getBoundingBox().centerX(), b.getBoundingBox().centerX()));
      for (RecognizedWord w : verticalColumns) appendColumn(out, w, imageWidth, imageHeight);
    }
    if (clean.isEmpty()) return out.toString();

    // Multi-column pages (issue #87): segment by segment, so that a side-by-side column's lines
    // are not merged into one line; the glyph positions stay the same.
    if (multiColumn) {
      List<int[]> segments = columnSegments(clean);
      if (!segments.isEmpty()) {
        for (int[] segment : segments) {
          List<RecognizedWord> segmentWords = new ArrayList<>(segment.length);
          for (int i : segment) segmentWords.add(clean.get(i));
          appendLines(out, segmentWords, imageWidth, imageHeight);
        }
        return out.toString();
      }
    }
    appendLines(out, clean, imageWidth, imageHeight);
    return out.toString();
  }

  /** The words with finite, ordered boxes clipped to the image, without empty ones. */
  private static List<RecognizedWord> clean(
      List<RecognizedWord> words, int imageWidth, int imageHeight) {
    List<RecognizedWord> clean = new ArrayList<>(words.size());
    for (RecognizedWord w : words) {
      if (w == null || w.getBoundingBox() == null) continue;
      RectF b = new RectF(w.getBoundingBox());
      if (!Float.isFinite(b.left)) b.left = 0f;
      if (!Float.isFinite(b.top)) b.top = 0f;
      if (!Float.isFinite(b.right)) b.right = 0f;
      if (!Float.isFinite(b.bottom)) b.bottom = 0f;
      b.sort();
      b.left = clamp(b.left, 0f, imageWidth);
      b.right = clamp(b.right, 0f, imageWidth);
      b.top = clamp(b.top, 0f, imageHeight);
      b.bottom = clamp(b.bottom, 0f, imageHeight);
      if (b.width() <= 0.5f || b.height() <= 0.5f) continue;
      clean.add(new RecognizedWord(w.getText(), b, w.getConfidence()));
    }
    return clean;
  }

  private static List<int[]> columnSegments(List<RecognizedWord> clean) {
    int n = clean.size();
    float[] lefts = new float[n];
    float[] tops = new float[n];
    float[] rights = new float[n];
    float[] bottoms = new float[n];
    for (int i = 0; i < n; i++) {
      RectF b = clean.get(i).getBoundingBox();
      lefts[i] = b.left;
      tops[i] = b.top;
      rights[i] = b.right;
      bottoms[i] = b.bottom;
    }
    return MultiColumnLayoutPolicy.groupIntoColumnSegments(
        lefts, tops, rights, bottoms, PdfTextUtils.containsRtlText(clean));
  }

  private static void appendLines(
      StringBuilder out, List<RecognizedWord> words, int imageWidth, int imageHeight) {
    for (List<RecognizedWord> line : OCRPostProcessor.groupLines(words)) {
      if (line.isEmpty()) continue;
      boolean rtl = PdfTextUtils.isRtlLine(line);
      // Lines come left to right; an RTL line is put into logical order (rightmost Arabic word
      // first, embedded Latin words keep their own order, issue #101) and laid out as a whole
      // below, so that its first word lands on the right where the ink is. An LTR line stays in
      // visual order and only its RTL words are turned into glyph order.
      if (rtl) BidiWordOrder.toLogical(line, RecognizedWord::getText, true);

      StringBuilder lineText = new StringBuilder();
      for (RecognizedWord w : line) {
        String token = safeText(w.getText()).trim();
        if (token.isEmpty()) continue;
        if (lineText.length() > 0) lineText.append(' ');
        lineText.append(rtl ? token : PdfTextUtils.reorderRtlForPdf(token));
      }
      if (lineText.length() == 0) continue;
      String token =
          Normalizer.normalize(
              PdfTextUtils.logicalToVisual(lineText.toString(), rtl), Normalizer.Form.NFC);

      RectF box = lineBox(line);
      float fontSize = Math.max(MIN_FONT_SIZE, medianHeight(line) * TEXT_SIZE_RATIO);
      float measured = GlyphlessFont.width(token, fontSize);
      float scaleX =
          measured > 0f
              ? clamp(
                  Math.max(1f, box.width()) / measured,
                  MIN_LINE_TEXT_SCALE_X,
                  MAX_LINE_TEXT_SCALE_X)
              : 1f;
      float x = clamp(box.left, 0f, imageWidth);
      float y = clamp(imageHeight - baselineInBox(box.bottom, box.height()), 0f, imageHeight);
      appendRun(out, token, fontSize, new float[] {scaleX, 0f, 0f, 1f, x, y});
    }
  }

  private static void appendColumn(
      StringBuilder out, RecognizedWord w, int imageWidth, int imageHeight) {
    String raw = safeText(w.getText()).trim();
    if (raw.isEmpty()) return;
    String token = Normalizer.normalize(raw, Normalizer.Form.NFC);
    RectF b = w.getBoundingBox();
    float fontSize = PdfVerticalTextUtils.columnFontSize(b.width());
    float measured = GlyphlessFont.width(token, fontSize);
    float[] m =
        PdfVerticalTextUtils.columnTextMatrix(
            b.left, b.top, b.height(), measured, imageWidth, imageHeight);
    appendRun(out, token, fontSize, m);
  }

  /** One invisible text run: render mode 3, the font at {@code size}, text matrix {@code m}. */
  static String textRun(String text, float size, float[] m) {
    StringBuilder out = new StringBuilder();
    appendRun(out, text, size, m);
    return out.toString();
  }

  private static void appendRun(StringBuilder out, String text, float size, float[] m) {
    out.append("BT 3 Tr /")
        .append(PdfWriter.FONT_RESOURCE)
        .append(' ')
        .append(PdfWriter.num(size))
        .append(" Tf");
    for (float v : m) out.append(' ').append(matrixNum(v));
    out.append(" Tm <");
    for (int i = 0; i < text.length(); i++) {
      out.append(String.format(Locale.ROOT, "%04X", (int) text.charAt(i)));
    }
    out.append("> Tj ET\n");
  }

  /** Matrix entries need more precision than positions: the scale is a ratio. */
  private static String matrixNum(float v) {
    String r = String.format(Locale.ROOT, "%.4f", v);
    int end = r.length();
    while (r.charAt(end - 1) == '0') end--;
    if (r.charAt(end - 1) == '.') end--;
    r = r.substring(0, end);
    return r.equals("-0") ? "0" : r;
  }

  /** Bounding box of the line's words that have text. */
  private static RectF lineBox(List<RecognizedWord> line) {
    RectF box = null;
    for (RecognizedWord w : line) {
      if (safeText(w.getText()).trim().isEmpty()) continue;
      if (box == null) box = new RectF(w.getBoundingBox());
      else box.union(w.getBoundingBox());
    }
    return box != null ? box : new RectF(0f, 0f, 1f, 1f);
  }

  private static float medianHeight(List<RecognizedWord> line) {
    float[] h = new float[line.size()];
    for (int i = 0; i < h.length; i++) h[i] = line.get(i).getBoundingBox().height();
    java.util.Arrays.sort(h);
    int n = h.length;
    return n % 2 == 1 ? h[n / 2] : 0.5f * (h[n / 2 - 1] + h[n / 2]);
  }

  /**
   * Baseline for a text run inside an OCR box (image Y grows downward): a fifth of the box height
   * above its bottom, leaving room for descenders. A baseline below the box makes the selectable
   * text layer hang under the visible scan text.
   */
  static float baselineInBox(float boxBottom, float boxHeight) {
    return boxBottom - boxHeight * 0.20f;
  }

  /** The text with control characters (other than line breaks and tabs) as spaces. */
  private static String safeText(String t) {
    if (t == null) return "";
    return t.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ");
  }

  private static float clamp(float v, float min, float max) {
    return Math.max(min, Math.min(max, v));
  }
}
