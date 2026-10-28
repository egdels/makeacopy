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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

/** The PDF writer's output, read back with PDFBox. */
public class PdfWriterTest {

  /** A one-page PDF of {@code width x height} points with a 1x1 image and {@code content}. */
  static byte[] onePage(double width, double height, String content) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PdfWriter writer = new PdfWriter(out, "Test");
    writer.addPage(width, height, PdfWriter.Image.lossless(new int[] {0xFFFFFFFF}, 1, 1), content);
    writer.finish();
    return out.toByteArray();
  }

  /**
   * The headers of a JPEG of {@code w x h} pixels with {@code components} components: enough for
   * the writer and for PDFBox, which does not decode images while extracting text.
   */
  private static byte[] jpeg(int w, int h, int components) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0xFF);
    out.write(0xD8);
    // An APP0 segment first, so that the frame header has to be found
    out.write(0xFF);
    out.write(0xE0);
    out.write(0);
    out.write(4);
    out.write(0);
    out.write(0);
    int length = 8 + 3 * components;
    out.write(0xFF);
    out.write(0xC0);
    out.write(length >> 8);
    out.write(length);
    out.write(8);
    out.write(h >> 8);
    out.write(h);
    out.write(w >> 8);
    out.write(w);
    out.write(components);
    for (int c = 1; c <= components; c++) {
      out.write(c);
      out.write(0x11);
      out.write(0);
    }
    out.write(0xFF);
    out.write(0xD9);
    return out.toByteArray();
  }

  @Test
  public void pagesImagesAndTextComeBackOut() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PdfWriter writer = new PdfWriter(out, "MakeACopy");
    byte[] color = jpeg(40, 30, 3);
    writer.addPage(
        595.28,
        841.89,
        PdfWriter.Image.jpeg(color),
        "q 40 0 0 30 10 10 cm /Im0 Do Q\n"
            + PdfTextLayer.textRun("Hello page one", 12f, new float[] {1, 0, 0, 1, 50, 700}));
    writer.addPage(
        300,
        200,
        PdfWriter.Image.lossless(new int[] {0xFF102030, 0xFF405060}, 2, 1),
        "q 2 0 0 1 0 0 cm /Im0 Do Q\n"
            + PdfTextLayer.textRun("Second", 12f, new float[] {1, 0, 0, 1, 20, 100}));
    writer.finish();
    assertEquals(2, writer.pageCount());

    try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(out.toByteArray()))) {
      assertEquals(2, doc.getNumberOfPages());
      PDPage first = doc.getPage(0);
      assertEquals(595.28f, first.getMediaBox().getWidth(), 0.01f);
      assertEquals(841.89f, first.getMediaBox().getHeight(), 0.01f);
      PDImageXObject img = (PDImageXObject) first.getResources().getXObject(COSName.getPDFName("Im0"));
      assertEquals(40, img.getWidth());
      assertEquals(30, img.getHeight());
      assertEquals("DCTDecode", img.getCOSObject().getCOSName(COSName.FILTER).getName());
      PDImageXObject lossless =
          (PDImageXObject) doc.getPage(1).getResources().getXObject(COSName.getPDFName("Im0"));
      assertEquals(2, lossless.getWidth());
      assertEquals("FlateDecode", lossless.getCOSObject().getCOSName(COSName.FILTER).getName());
      String text = new PDFTextStripper().getText(doc);
      assertTrue(text, text.contains("Hello page one"));
      assertTrue(text, text.contains("Second"));
      assertEquals("MakeACopy", doc.getDocumentInformation().getProducer());
    }
  }

  @Test
  public void jpegInfoReadsSizeAndComponents() throws Exception {
    assertArrayEquals(new int[] {40, 30, 3}, PdfWriter.jpegInfo(jpeg(40, 30, 3)));
    assertArrayEquals(
        new int[] {16, 8, 1}, PdfWriter.jpegInfo(jpeg(16, 8, 1)));
  }

  @Test(expected = IllegalArgumentException.class)
  public void jpegInfoRejectsOtherData() {
    PdfWriter.jpegInfo(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
  }

  @Test
  public void grayJpegUsesDeviceGray() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PdfWriter writer = new PdfWriter(out, "Test");
    writer.addPage(
        100, 100, PdfWriter.Image.jpeg(jpeg(8, 8, 1)), "");
    writer.finish();
    try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(out.toByteArray()))) {
      PDImageXObject img =
          (PDImageXObject) doc.getPage(0).getResources().getXObject(COSName.getPDFName("Im0"));
      assertEquals("DeviceGray", img.getColorSpace().getName());
    }
  }

  @Test
  public void numbersAreShortAndLocaleFree() {
    assertEquals("595.28", PdfWriter.num(595.2756));
    assertEquals("12", PdfWriter.num(12.0));
    assertEquals("0.5", PdfWriter.num(0.5));
    assertEquals("0", PdfWriter.num(-0.001));
  }
}
