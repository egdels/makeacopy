package de.schliweb.makeacopy.utils.export;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests the image size limit per target DPI and the check whether a stored page JPEG can be
 * embedded unchanged.
 */
public class PdfCreatorImageSizeTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void fitToImageLimitFollowsTheDpi() {
    // An imported A4 page at 300 dpi: the long side of a FIT_TO_IMAGE page is as long as A4.
    assertArrayEquals(
        new int[] {2480, 3508},
        PdfCreator.maxImagePixels(PageFormat.FIT_TO_IMAGE, 300, 2480, 3508));
    assertArrayEquals(
        new int[] {1653, 2339},
        PdfCreator.maxImagePixels(PageFormat.FIT_TO_IMAGE, 200, 2480, 3508));
    // Landscape: the width is the long side.
    int[] landscape = PdfCreator.maxImagePixels(PageFormat.FIT_TO_IMAGE, 200, 4000, 3000);
    assertArrayEquals(new int[] {2339, 1754}, landscape);
  }

  @Test
  public void fixedFormatsKeepTheirPaperSize() {
    assertArrayEquals(
        PageFormat.A4.pixelsForDpi(200), PdfCreator.maxImagePixels(PageFormat.A4, 200, 100, 100));
  }

  @Test
  public void downscaleOnlyWhenLargerThanTheLimit() {
    assertTrue(PdfCreator.needsDownscale(2480, 3508, 1653, 2339));
    assertFalse(PdfCreator.needsDownscale(1000, 1400, 1653, 2339));
    // Within one pixel of the limit counts as already scaled.
    assertFalse(PdfCreator.needsDownscale(2481, 3509, 2480, 3508));
  }

  @Test
  public void readsTheSizeOfAPlainColorJpeg() throws Exception {
    assertArrayEquals(new int[] {2480, 3508}, PdfCreator.readPlainJpegSize(jpeg(0xC0, 3, 2480, 3508)));
    // Progressive is fine too.
    assertArrayEquals(new int[] {640, 480}, PdfCreator.readPlainJpegSize(jpeg(0xC2, 3, 640, 480)));
  }

  @Test
  public void rejectsJpegsPdfBoxWouldMislabel() throws Exception {
    // Grayscale and CMYK would be tagged DeviceRGB by JPEGFactory.createFromStream.
    assertNull(PdfCreator.readPlainJpegSize(jpeg(0xC0, 1, 640, 480)));
    assertNull(PdfCreator.readPlainJpegSize(jpeg(0xC0, 4, 640, 480)));
    // Lossless coding.
    assertNull(PdfCreator.readPlainJpegSize(jpeg(0xC3, 3, 640, 480)));
  }

  @Test
  public void rejectsNonJpegAndTruncatedFiles() throws Exception {
    assertNull(PdfCreator.readPlainJpegSize(write(new byte[] {(byte) 0x89, 'P', 'N', 'G'})));
    byte[] full = bytes(jpeg(0xC0, 3, 640, 480));
    byte[] cut = new byte[24];
    System.arraycopy(full, 0, cut, 0, cut.length);
    assertNull(PdfCreator.readPlainJpegSize(write(cut)));
    assertNull(PdfCreator.readPlainJpegSize(new File(tmp.getRoot(), "missing.jpg")));
  }

  /** SOI, a JFIF APP0 segment, a DQT stub and the frame header; enough for the header parser. */
  private File jpeg(int sof, int components, int width, int height) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(new byte[] {(byte) 0xFF, (byte) 0xD8});
    out.write(new byte[] {(byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1});
    out.write(new byte[] {0, 1, 0, 0});
    out.write(new byte[] {(byte) 0xFF, (byte) 0xDB, 0, 4, 0, 0});
    out.write(new byte[] {(byte) 0xFF, (byte) sof, 0, (byte) (8 + 3 * components), 8});
    out.write(new byte[] {(byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width});
    out.write(components);
    for (int c = 0; c < components; c++) out.write(new byte[] {(byte) (c + 1), 0x11, 0});
    out.write(new byte[] {(byte) 0xFF, (byte) 0xD9});
    return write(out.toByteArray());
  }

  private File write(byte[] data) throws Exception {
    File f = tmp.newFile();
    try (FileOutputStream os = new FileOutputStream(f)) {
      os.write(data);
    }
    return f;
  }

  private static byte[] bytes(File f) throws Exception {
    return java.nio.file.Files.readAllBytes(f.toPath());
  }
}
