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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.util.Matrix;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * PDF roundtrip tests for vertical Japanese text (issue #88), running the real PDFBox pipeline on
 * the JVM with the bundled font assets:
 *
 * <ul>
 *   <li>A: Unicode roundtrip — no U+FFFD, all Kana/Kanji/punctuation survive extraction,
 *   <li>B: vertical reading order — columns are extracted right-to-left, top-to-bottom,
 *   <li>D: font coverage — every required code point is encodable by at least one embedded font.
 * </ul>
 *
 * <p>The columns are written exactly like {@code PdfCreator.addVerticalColumnsImageSpace} does
 * (same helpers: {@link PdfVerticalTextUtils} + {@link PdfTextUtils#showTextWithFallbacks}), but
 * without the Android-only bitmap/context plumbing (RectF is stubbed in JVM unit tests).
 */
public class PdfJapaneseVerticalRoundtripTest {

  private static final String COL_RIGHT = "すると、1億円を受け取った親は家に帰らなくなった。";
  private static final String COL_MIDDLE = "1億円で遊んでいるのだろう。";
  private static final String COL_LEFT = "なくなったらたかりに来ると思ったので、親に内緒で高級マンションに引っ越すことにした。";

  private File fontsDir;

  /**
   * Removes all whitespace. PDFTextStripper inserts line breaks between vertically stacked glyphs
   * (each character starts a new visual "line"); the logical code point order is unaffected and
   * search/copy in viewers normalizes this the same way.
   */
  private static String joinWhitespace(String s) {
    return s.replaceAll("\\s+", "");
  }

  @Before
  public void setUp() {
    // Gradle unit tests run with the module directory as working dir; fall back for IDE runs.
    File dir = new File("src/full/assets/fonts");
    if (!dir.isDirectory()) dir = new File("app/src/full/assets/fonts");
    assertTrue("font assets directory not found: " + dir.getAbsolutePath(), dir.isDirectory());
    fontsDir = dir;
  }

  private List<PDFont> loadFonts(PDDocument doc) throws Exception {
    // Same order as PdfTextUtils.loadFontsWithFallbacks.
    String[] names = {
      "NotoSans-Regular.ttf", "NotoSansCJKsc-Regular.ttf", "NotoSansJP-Kana.ttf"
    };
    List<PDFont> fonts = new ArrayList<>();
    for (String n : names) {
      File f = new File(fontsDir, n);
      assertTrue("missing font asset: " + f.getAbsolutePath(), f.isFile());
      fonts.add(PDType0Font.load(doc, f));
    }
    return fonts;
  }

  /**
   * Writes the three vertical example columns (right, middle, left) using the same matrix and
   * fallback logic as PdfCreator and returns the extracted text.
   */
  private String writeColumnsAndExtract() throws Exception {
    final int imgW = 1000;
    final int imgH = 1400;
    // Column boxes in image space (Android top-left origin): right → middle → left.
    float[][] boxes = {
      {820f, 100f, 40f, 1100f}, // right column: left, top, width, height
      {520f, 100f, 40f, 700f}, // middle column
      {220f, 100f, 40f, 1250f} // left column
    };
    String[] texts = {COL_RIGHT, COL_MIDDLE, COL_LEFT};

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(new PDRectangle(imgW, imgH));
      doc.addPage(page);
      List<PDFont> fonts = loadFonts(doc);

      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        for (int i = 0; i < boxes.length; i++) {
          float[] b = boxes[i];
          float fontSize = PdfVerticalTextUtils.columnFontSize(b[2]);
          float[] m =
              PdfVerticalTextUtils.columnTextMatrix(
                  b[0], b[1], b[3], /* measured */ b[3], imgW, imgH);
          cs.beginText();
          cs.setRenderingMode(RenderingMode.NEITHER);
          cs.setTextMatrix(new Matrix(m[0], m[1], m[2], m[3], m[4], m[5]));
          PdfTextUtils.showTextWithFallbacks(cs, texts[i], fontSize, fonts);
          cs.endText();
        }
      }
      doc.save(out);
    }

    try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(out.toByteArray()))) {
      PDFTextStripper stripper = new PDFTextStripper();
      // Content-stream order == logical order written by PdfCreator (no positional re-sort).
      stripper.setSortByPosition(false);
      return stripper.getText(doc);
    }
  }

  // ---------------------------------------------------------------------
  // A: Unicode roundtrip
  // ---------------------------------------------------------------------

  @Test
  public void extractedTextHasNoReplacementCharacters() throws Exception {
    String extracted = writeColumnsAndExtract();
    assertTrue("extraction must not be empty", !extracted.trim().isEmpty());
    assertTrue("no U+FFFD allowed, got: " + extracted, !extracted.contains("\uFFFD"));
  }

  @Test
  public void extractedTextContainsAllJapaneseContent() throws Exception {
    String extracted = joinWhitespace(writeColumnsAndExtract());
    String[] required = {
      "すると", "1億円", "受け取った", "親は家に帰らなくなった", "マンション", "引っ越す", "。", "、"
    };
    for (String s : required) {
      assertTrue("extracted text must contain \"" + s + "\": " + extracted, extracted.contains(s));
    }
    // No lost Hiragana/Katakana: every code point of the source must survive.
    String all = COL_RIGHT + COL_MIDDLE + COL_LEFT;
    for (int i = 0; i < all.length(); ) {
      int cp = all.codePointAt(i);
      String ch = new String(Character.toChars(cp));
      assertTrue("lost code point U+" + Integer.toHexString(cp) + " (" + ch + ")",
          extracted.contains(ch));
      i += Character.charCount(cp);
    }
  }

  // ---------------------------------------------------------------------
  // B: Vertical reading order (right → left columns)
  // ---------------------------------------------------------------------

  @Test
  public void columnsAreExtractedRightToLeft() throws Exception {
    String extracted = joinWhitespace(writeColumnsAndExtract());
    int pRight = extracted.indexOf("すると");
    int pMiddle = extracted.indexOf("遊んでいるのだろう");
    int pLeft = extracted.indexOf("なくなったらたかりに");
    assertTrue("right column missing", pRight >= 0);
    assertTrue("middle column missing", pMiddle >= 0);
    assertTrue("left column missing", pLeft >= 0);
    // Must fail if columns were emitted left-to-right.
    assertTrue("right column must come first", pRight < pMiddle);
    assertTrue("middle column must come before left column", pMiddle < pLeft);
  }

  // ---------------------------------------------------------------------
  // D: Font coverage
  // ---------------------------------------------------------------------

  @Test
  public void everyRequiredCodePointIsEncodableByAnEmbeddedFont() throws Exception {
    String probe = "漢字ひらがなカタカナっゃゅょ。、！？1億円" + COL_RIGHT + COL_MIDDLE + COL_LEFT;
    try (PDDocument doc = new PDDocument()) {
      List<PDFont> fonts = loadFonts(doc);
      StringBuilder missing = new StringBuilder();
      for (int i = 0; i < probe.length(); ) {
        int cp = probe.codePointAt(i);
        String ch = new String(Character.toChars(cp));
        boolean ok = false;
        for (PDFont font : fonts) {
          try {
            font.encode(ch);
            ok = true;
            break;
          } catch (Exception ignore) {
            // try next fallback font
          }
        }
        if (!ok) missing.append(ch).append(" (U+").append(Integer.toHexString(cp)).append(") ");
        i += Character.charCount(cp);
      }
      if (missing.length() > 0) {
        fail("code points without any embedded font glyph: " + missing);
      }
    }
  }
}
