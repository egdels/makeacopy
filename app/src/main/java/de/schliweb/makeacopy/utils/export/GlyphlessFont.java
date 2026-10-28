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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * A font without visible glyphs for the invisible OCR text layer, as Tesseract and OCRmyPDF use: a
 * TrueType with two empty glyphs, every advance {@link #ADVANCE} of 1000 units, embedded as a Type0
 * font with Identity-H encoding. The codes are the text's UTF-16 code units, and a ToUnicode map
 * turns them back, so every script works with this one font of a few hundred bytes instead of a
 * Noto font per script (12.6 MB in the full edition). The text is drawn in render mode 3
 * (invisible) anyway, so no glyph is ever painted.
 *
 * <p>{@link PdfWriter} writes the font objects and shows the codes as hex strings; widths are
 * {@link #width}. The FontDescriptor's descent is -1, as Tesseract's: with 0, macOS (PDFKit,
 * Preview) cannot work out the font's height and finds no text at all.
 *
 * <p>Characters outside the Basic Multilingual Plane (emoji, mathematical alphanumerics) take two
 * codes, one per surrogate, and come out of a text extraction as U+FFFD, as with Tesseract's font;
 * none of the Noto fonts used before had them either.
 */
public final class GlyphlessFont {

  public static final String NAME = "GlyphLessFont";

  /** Advance of every code, in 1/1000 of the font size. */
  public static final int ADVANCE = 500;

  private static final int UNITS_PER_EM = 1000;

  static final String TO_UNICODE =
      "/CIDInit /ProcSet findresource begin\n"
          + "12 dict begin\n"
          + "begincmap\n"
          + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
          + "/CMapName /Adobe-Identity-UCS def\n"
          + "/CMapType 2 def\n"
          + "1 begincodespacerange\n"
          + "<0000> <FFFF>\n"
          + "endcodespacerange\n"
          + "1 beginbfrange\n"
          + "<0000> <FFFF> <0000>\n"
          + "endbfrange\n"
          + "endcmap\n"
          + "CMapName currentdict /CMap defineresource pop\n"
          + "end\n"
          + "end\n";

  private static byte[] fontBytes;

  private GlyphlessFont() {}

  /** Width of {@code text} at {@code fontSize}: {@link #ADVANCE} per UTF-16 code unit. */
  public static float width(String text, float fontSize) {
    return text.length() * ADVANCE / 1000f * fontSize;
  }

  static synchronized byte[] bytes() {
    if (fontBytes == null) fontBytes = build();
    return fontBytes;
  }

  private static byte[] build() {
    Map<String, byte[]> tables = new TreeMap<>();
    tables.put("cmap", cmap());
    tables.put("glyf", new byte[0]);
    tables.put("head", head());
    tables.put("hhea", hhea());
    tables.put(
        "hmtx",
        table(
            d -> {
              for (int i = 0; i < 2; i++) {
                d.writeShort(ADVANCE);
                d.writeShort(0);
              }
            }));
    tables.put(
        "loca",
        table(
            d -> {
              for (int i = 0; i < 3; i++) d.writeShort(0);
            }));
    tables.put("maxp", maxp());
    tables.put("name", name());
    tables.put("post", post());

    int n = tables.size();
    int pow = 1;
    int log = 0;
    while (pow * 2 <= n) {
      pow *= 2;
      log++;
    }
    ByteArrayOutputStream font = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(font);
    try {
      out.writeInt(0x00010000);
      out.writeShort(n);
      out.writeShort(pow * 16);
      out.writeShort(log);
      out.writeShort(n * 16 - pow * 16);
      int offset = 12 + 16 * n;
      for (Map.Entry<String, byte[]> e : tables.entrySet()) {
        out.writeBytes(e.getKey());
        out.writeInt((int) checksum(e.getValue()));
        out.writeInt(offset);
        out.writeInt(e.getValue().length);
        offset += padded(e.getValue().length);
      }
      for (byte[] data : tables.values()) {
        out.write(data);
        for (int i = data.length; i < padded(data.length); i++) out.write(0);
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    byte[] result = font.toByteArray();
    // head.checkSumAdjustment makes the whole font sum to 0xB1B0AFBA.
    long adjustment = (0xB1B0AFBAL - checksum(result)) & 0xFFFFFFFFL;
    int at = 12 + 16 * n;
    for (Map.Entry<String, byte[]> e : tables.entrySet()) {
      if (e.getKey().equals("head")) break;
      at += padded(e.getValue().length);
    }
    for (int i = 0; i < 4; i++) result[at + 8 + i] = (byte) (adjustment >> (24 - 8 * i));
    return result;
  }

  private static int padded(int size) {
    return (size + 3) / 4 * 4;
  }

  static long checksum(byte[] data) {
    long sum = 0;
    for (int i = 0; i < data.length; i += 4) {
      long word = 0;
      for (int k = 0; k < 4; k++)
        word = (word << 8) | (i + k < data.length ? data[i + k] & 0xFF : 0);
      sum = (sum + word) & 0xFFFFFFFFL;
    }
    return sum;
  }

  private interface Writer {
    void write(DataOutputStream d) throws IOException;
  }

  private static byte[] table(Writer w) {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    try {
      w.write(new DataOutputStream(b));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return b.toByteArray();
  }

  private static byte[] head() {
    return table(
        d -> {
          d.writeInt(0x00010000); // version
          d.writeInt(0x00010000); // fontRevision
          d.writeInt(0); // checkSumAdjustment, set at the end
          d.writeInt(0x5F0F3CF5); // magic
          d.writeShort(0x000B); // flags: baseline at 0, integer scaling
          d.writeShort(UNITS_PER_EM);
          d.writeLong(0); // created
          d.writeLong(0); // modified
          for (int i = 0; i < 4; i++) d.writeShort(0); // bounding box of all glyphs: empty
          d.writeShort(0); // macStyle
          d.writeShort(3); // lowestRecPPEM
          d.writeShort(2); // fontDirectionHint
          d.writeShort(0); // indexToLocFormat: short
          d.writeShort(0); // glyphDataFormat
        });
  }

  private static byte[] hhea() {
    return table(
        d -> {
          d.writeInt(0x00010000);
          d.writeShort(UNITS_PER_EM); // ascender
          d.writeShort(0); // descender
          d.writeShort(0); // lineGap
          d.writeShort(ADVANCE); // advanceWidthMax
          d.writeShort(0); // minLeftSideBearing
          d.writeShort(0); // minRightSideBearing
          d.writeShort(0); // xMaxExtent
          d.writeShort(1); // caretSlopeRise
          d.writeShort(0); // caretSlopeRun
          d.writeShort(0); // caretOffset
          for (int i = 0; i < 4; i++) d.writeShort(0);
          d.writeShort(0); // metricDataFormat
          d.writeShort(2); // numberOfHMetrics
        });
  }

  private static byte[] maxp() {
    return table(
        d -> {
          d.writeInt(0x00010000);
          d.writeShort(2); // numGlyphs
          d.writeShort(0); // maxPoints
          d.writeShort(0); // maxContours
          d.writeShort(0); // maxCompositePoints
          d.writeShort(0); // maxCompositeContours
          d.writeShort(2); // maxZones
          for (int i = 0; i < 8; i++) d.writeShort(0);
        });
  }

  /** One format 4 subtable (Windows, Unicode BMP) mapping nothing: only the final segment. */
  private static byte[] cmap() {
    return table(
        d -> {
          d.writeShort(0);
          d.writeShort(1);
          d.writeShort(3);
          d.writeShort(1);
          d.writeInt(12);
          d.writeShort(4); // format
          d.writeShort(24); // length
          d.writeShort(0); // language
          d.writeShort(2); // segCountX2
          d.writeShort(2); // searchRange
          d.writeShort(0); // entrySelector
          d.writeShort(0); // rangeShift
          d.writeShort(0xFFFF); // endCode
          d.writeShort(0); // reservedPad
          d.writeShort(0xFFFF); // startCode
          d.writeShort(1); // idDelta
          d.writeShort(0); // idRangeOffset
        });
  }

  /** Family (1), full name (4) and PostScript name (6), Windows Unicode. */
  private static byte[] name() {
    return table(
        d -> {
          byte[] value = NAME.getBytes(StandardCharsets.UTF_16BE);
          int[] ids = {1, 4, 6};
          d.writeShort(0);
          d.writeShort(ids.length);
          d.writeShort(6 + 12 * ids.length);
          for (int id : ids) {
            d.writeShort(3);
            d.writeShort(1);
            d.writeShort(0x409);
            d.writeShort(id);
            d.writeShort(value.length);
            d.writeShort(0); // all records share one string
          }
          d.write(value);
        });
  }

  private static byte[] post() {
    return table(
        d -> {
          d.writeInt(0x00030000); // version 3: no glyph names
          d.writeInt(0); // italicAngle
          d.writeShort(-100); // underlinePosition
          d.writeShort(50); // underlineThickness
          d.writeInt(0); // isFixedPitch
          for (int i = 0; i < 4; i++) d.writeInt(0);
        });
  }
}
