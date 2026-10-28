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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.tom_roush.fontbox.ttf.TTFParser;
import com.tom_roush.fontbox.ttf.TrueTypeFont;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.ByteArrayInputStream;
import org.junit.Test;

/** The glyphless text-layer font: a valid TrueType, and text in any script comes back out. */
public class GlyphlessFontTest {

  @Test
  public void fontIsAValidTrueType() throws Exception {
    TrueTypeFont font = new TTFParser(true).parse(new ByteArrayInputStream(GlyphlessFont.bytes()));
    assertEquals(2, font.getNumberOfGlyphs());
    assertEquals(1000, font.getUnitsPerEm());
    assertEquals(GlyphlessFont.ADVANCE, font.getAdvanceWidth(1));
    assertEquals(GlyphlessFont.NAME, font.getName());
    assertEquals(0xB1B0AFBAL, GlyphlessFont.checksum(GlyphlessFont.bytes()));
  }

  @Test
  public void textInEveryScriptComesBackOut() throws Exception {
    String[] lines = {
      "Grüße, Œuvre, Łódź", "مرحبا بالعالم", "नमस्ते दुनिया", "สวัสดีชาวโลก", "Привет, мир", "Γειά σου"
    };
    // As PdfCreator: right-to-left lines go in visual order, the extraction turns them back.
    StringBuilder content = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      String shown =
          PdfTextUtils.isRtlText(lines[i]) ? PdfTextUtils.logicalToVisual(lines[i], true) : lines[i];
      content.append(PdfTextLayer.textRun(shown, 20f, new float[] {1, 0, 0, 1, 50, 800 - 40 * i}));
    }
    byte[] out = PdfWriterTest.onePage(595, 842, content.toString());
    try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(out))) {
      String text = new PDFTextStripper().getText(doc);
      for (String line : lines) {
        String expected = line.replace(" ", "");
        assertTrue("missing \"" + line + "\" in: " + text, text.replace(" ", "").contains(expected));
      }
      PDType0Font loaded =
          (PDType0Font) doc.getPage(0).getResources().getFont(doc.getPage(0).getResources().getFontNames().iterator().next());
      // macOS (PDFKit) drops all text of a font whose descent is 0.
      assertTrue(loaded.getFontDescriptor().getDescent() < 0);
    }
  }

  @Test
  public void widthIsHalfAnEmPerCodeUnit() {
    assertEquals(25f, GlyphlessFont.width("abcde", 10f), 1e-6);
    assertEquals(10f, GlyphlessFont.width("😀", 10f), 1e-6); // two UTF-16 code units
  }
}
