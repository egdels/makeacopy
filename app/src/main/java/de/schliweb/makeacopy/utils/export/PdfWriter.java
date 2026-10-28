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
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes a PDF of image pages with an invisible text layer, page by page straight to the output
 * stream: each page is one image XObject and a content stream, and all pages share the {@link
 * GlyphlessFont}. Catalog and page tree are written last, so the page count need not be known in
 * advance. Adapted from Sheet's {@code SearchablePdf}.
 */
final class PdfWriter {
  private static final int CATALOG = 1;
  private static final int PAGES = 2;
  private static final int FONT = 3;
  private static final int CID_FONT = 4;
  private static final int FONT_DESCRIPTOR = 5;
  private static final int FONT_FILE = 6;
  private static final int TO_UNICODE = 7;
  private static final int INFO = 8;
  private static final int FIRST_PAGE = 9;

  /** Resource name of the glyphless font in the content streams. */
  static final String FONT_RESOURCE = "F1";

  /** An image for a page: its pixel size and the stream dictionary entries besides the size. */
  static final class Image {
    final int width;
    final int height;
    final String dict;
    final byte[] data;

    private Image(int width, int height, String dict, byte[] data) {
      this.width = width;
      this.height = height;
      this.dict = dict;
      this.data = data;
    }

    /** A baseline or progressive JPEG with 1 (gray) or 3 (YCbCr/RGB) components, embedded as is. */
    static Image jpeg(byte[] jpeg) {
      int[] info = jpegInfo(jpeg);
      String colorSpace = info[2] == 1 ? "/DeviceGray" : "/DeviceRGB";
      return new Image(
          info[0],
          info[1],
          "/ColorSpace " + colorSpace + " /BitsPerComponent 8 /Filter /DCTDecode",
          jpeg);
    }

    /**
     * A black and white page, one bit per pixel (1 is white), rows starting on a byte boundary,
     * Flate-compressed: a tenth of a JPEG of the same page, and without its ringing around the
     * letters.
     */
    static Image bilevel(byte[] packedRows, int width, int height) throws IOException {
      if (packedRows.length != (width + 7) / 8 * height) {
        throw new IllegalArgumentException("bad size for " + width + " x " + height);
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(packedRows.length / 4);
      try (DeflaterOutputStream z =
          new DeflaterOutputStream(bytes, new Deflater(Deflater.BEST_COMPRESSION), 1 << 16)) {
        z.write(packedRows);
      }
      return new Image(
          width,
          height,
          "/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode",
          bytes.toByteArray());
    }

    /** Lossless RGB from ARGB pixels (alpha dropped), Flate-compressed. */
    static Image lossless(int[] argb, int width, int height) throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(width * height);
      try (DeflaterOutputStream z =
          new DeflaterOutputStream(bytes, new Deflater(Deflater.DEFAULT_COMPRESSION), 1 << 16)) {
        byte[] row = new byte[3 * width];
        for (int y = 0; y < height; y++) {
          for (int x = 0; x < width; x++) {
            int p = argb[y * width + x];
            row[3 * x] = (byte) (p >> 16);
            row[3 * x + 1] = (byte) (p >> 8);
            row[3 * x + 2] = (byte) p;
          }
          z.write(row);
        }
      }
      return new Image(
          width,
          height,
          "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode",
          bytes.toByteArray());
    }
  }

  private final CountingStream out;
  private final TreeMap<Integer, Long> offsets = new TreeMap<>();
  private final List<Integer> kids = new ArrayList<>();
  private int next = FIRST_PAGE;

  PdfWriter(OutputStream out, String producer) throws IOException {
    this.out = new CountingStream(out);
    raw("%PDF-1.7\n%âãÏÓ\n");
    String name = GlyphlessFont.NAME;
    obj(
        FONT,
        "<< /Type /Font /Subtype /Type0 /BaseFont /"
            + name
            + " /Encoding /Identity-H /DescendantFonts ["
            + CID_FONT
            + " 0 R] /ToUnicode "
            + TO_UNICODE
            + " 0 R >>");
    obj(
        CID_FONT,
        "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /"
            + name
            + " /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >>"
            + " /FontDescriptor "
            + FONT_DESCRIPTOR
            + " 0 R /DW "
            + GlyphlessFont.ADVANCE
            + " /CIDToGIDMap /Identity >>");
    obj(
        FONT_DESCRIPTOR,
        "<< /Type /FontDescriptor /FontName /"
            + name
            + " /Flags 5 /FontBBox [0 0 "
            + GlyphlessFont.ADVANCE
            + " 1000] /ItalicAngle 0 /Ascent 1000 /Descent -1 /CapHeight 1000 /StemV 80"
            + " /FontFile2 "
            + FONT_FILE
            + " 0 R >>");
    byte[] ttf = GlyphlessFont.bytes();
    stream(FONT_FILE, "/Length1 " + ttf.length, ttf);
    stream(TO_UNICODE, "", GlyphlessFont.TO_UNICODE.getBytes(StandardCharsets.US_ASCII));
    obj(INFO, "<< /Producer (" + producer + ") /Creator (" + producer + ") >>");
  }

  /**
   * Adds a page of {@code width x height} points showing {@code image} with the content {@code
   * content} (which draws the image as {@code /Im0} and may use the font as {@link
   * #FONT_RESOURCE}).
   */
  void addPage(double width, double height, Image image, String content) throws IOException {
    int n = next;
    next += 3;
    kids.add(n);
    String box = "[0 0 " + num(width) + " " + num(height) + "]";
    obj(
        n,
        "<< /Type /Page /Parent "
            + PAGES
            + " 0 R /MediaBox "
            + box
            + " /CropBox "
            + box
            + " /Resources << /XObject << /Im0 "
            + (n + 2)
            + " 0 R >> /Font << /"
            + FONT_RESOURCE
            + " "
            + FONT
            + " 0 R >> >> /Contents "
            + (n + 1)
            + " 0 R >>");
    stream(n + 1, "", content.getBytes(StandardCharsets.ISO_8859_1));
    stream(
        n + 2,
        "/Type /XObject /Subtype /Image /Width "
            + image.width
            + " /Height "
            + image.height
            + " "
            + image.dict,
        image.data);
  }

  int pageCount() {
    return kids.size();
  }

  /** Writes catalog, page tree and cross-reference table. */
  void finish() throws IOException {
    obj(CATALOG, "<< /Type /Catalog /Pages " + PAGES + " 0 R >>");
    StringBuilder k = new StringBuilder();
    for (int kid : kids) {
      if (k.length() > 0) k.append(' ');
      k.append(kid).append(" 0 R");
    }
    obj(PAGES, "<< /Type /Pages /Kids [" + k + "] /Count " + kids.size() + " >>");
    int size = next;
    long xref = out.count;
    StringBuilder table = new StringBuilder();
    table.append("xref\n0 ").append(size).append("\n0000000000 65535 f \n");
    for (int i = 1; i < size; i++) {
      Long offset = offsets.get(i);
      if (offset == null) throw new IOException("object " + i + " missing");
      table.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
    }
    table
        .append("trailer\n<< /Size ")
        .append(size)
        .append(" /Root ")
        .append(CATALOG)
        .append(" 0 R /Info ")
        .append(INFO)
        .append(" 0 R >>\nstartxref\n")
        .append(xref)
        .append("\n%%EOF\n");
    raw(table.toString());
    out.flush();
  }

  /** A number for the PDF: at most two decimals, no trailing zeros. */
  static String num(double v) {
    String r = String.format(Locale.ROOT, "%.2f", v);
    if (r.indexOf('.') >= 0) {
      int end = r.length();
      while (r.charAt(end - 1) == '0') end--;
      if (r.charAt(end - 1) == '.') end--;
      r = r.substring(0, end);
    }
    return r.equals("-0") || r.isEmpty() ? "0" : r;
  }

  private void raw(String s) throws IOException {
    out.write(s.getBytes(StandardCharsets.ISO_8859_1));
  }

  private void obj(int n, String body) throws IOException {
    offsets.put(n, out.count);
    raw(n + " 0 obj\n" + body + "\nendobj\n");
  }

  private void stream(int n, String dict, byte[] data) throws IOException {
    offsets.put(n, out.count);
    raw(n + " 0 obj\n<< " + dict + (dict.isEmpty() ? "" : " ") + "/Length " + data.length);
    raw(" >>\nstream\n");
    out.write(data);
    raw("\nendstream\nendobj\n");
  }

  /**
   * Width, height and component count of a JPEG from its frame header.
   *
   * @throws IllegalArgumentException if {@code jpeg} is not a JPEG with a frame header
   */
  static int[] jpegInfo(byte[] jpeg) {
    if (jpeg.length < 4 || u8(jpeg, 0) != 0xFF || u8(jpeg, 1) != 0xD8) {
      throw new IllegalArgumentException("not a JPEG");
    }
    int i = 2;
    while (i + 9 < jpeg.length) {
      if (u8(jpeg, i) != 0xFF) {
        i++;
        continue;
      }
      int marker = u8(jpeg, i + 1);
      if (marker == 0xFF || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
        i++;
        continue;
      }
      int length = (u8(jpeg, i + 2) << 8) | u8(jpeg, i + 3);
      if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
        int h = (u8(jpeg, i + 5) << 8) | u8(jpeg, i + 6);
        int w = (u8(jpeg, i + 7) << 8) | u8(jpeg, i + 8);
        int components = u8(jpeg, i + 9);
        return new int[] {w, h, components};
      }
      i += 2 + length;
    }
    throw new IllegalArgumentException("no JPEG frame header");
  }

  private static int u8(byte[] b, int i) {
    return b[i] & 0xFF;
  }

  private static final class CountingStream extends FilterOutputStream {
    long count;

    CountingStream(OutputStream out) {
      super(out);
    }

    @Override
    public void write(int b) throws IOException {
      out.write(b);
      count++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
      count += len;
    }
  }
}
