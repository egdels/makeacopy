package de.schliweb.makeacopy.utils.jpeg;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.utils.export.jpeg.JpegExportOptions;
import de.schliweb.makeacopy.utils.export.jpeg.JpegExporter;
import java.io.File;
import java.util.Random;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * This class contains instrumented tests for the JpegExporter functionality, which run on an
 * Android device. It validates various JPEG export options, ensuring proper behavior and
 * conformance to desired requirements such as size optimizations and output quality.
 *
 * <h2>Functional Overview</h2>
 *
 * - Tests the automatic export mode in comparison to options tailored for black-and-white text and
 * forced grayscale JPEG generation. - Measures and logs the size difference between varying export
 * configurations. - Verifies that specific configurations lead to expected reductions in file size.
 *
 * <h2>Key Operations</h2>
 *
 * - Loads external OpenCV native libraries for image processing functionality. - Creates synthetic
 * test documents with structured content such as headers, tables, and text blocks. - Performs
 * actual export operations using predefined JPEG export options. - Asserts conditions based on the
 * export outcomes, including file size comparisons.
 *
 * <h2>Structure</h2>
 *
 * - `@BeforeClass` method to initialize required OpenCV libraries. - `@Before` method to set up the
 * test environment, including a valid context. - A test method to evaluate different JPEG export
 * scenarios and compare their performance. - Multiple helper methods to create synthetic documents,
 * manage temporary files, measure file sizes, and add additional visual elements.
 */
@RunWith(AndroidJUnit4.class)
public class JpegExporterInstrumentedTest {

  private static final String TAG = "JpegExporterTest";

  @BeforeClass
  public static void loadOpenCv() {
    try {
      System.loadLibrary("opencv_java4");
    } catch (UnsatisfiedLinkError e) {
      try {
        System.loadLibrary("opencv_java3");
      } catch (UnsatisfiedLinkError ignore) {
        Log.w(TAG, "OpenCV native lib not preloaded; relying on app init.");
      }
    }
  }

  private Context ctx;

  @Before
  public void setUp() {
    ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  /**
   * Verifies the behavior of JPEG export functionality when applying different compression modes:
   * AUTO, BW_TEXT (black and white text optimization), and forced grayscale within AUTO mode.
   *
   * <p>This test: 1. Creates a synthetic document to test the export process. 2. Exports the
   * document using three configurations: AUTO, BW_TEXT, and AUTO with forced grayscale. 3. Compares
   * the exported content sizes, logging their results and verifying expected relationships. 4.
   * Confirms that the BW_TEXT export results in a smaller file size than the AUTO export. 5.
   * Ensures that forcing grayscale in the AUTO mode produces a file size smaller or equal to
   * standard AUTO mode.
   *
   * <p>Assertions are made to validate: - BW_TEXT produces at least ~5% size reduction compared to
   * AUTO.
   *
   * <p>The method also logs the size reduction percentage to give insights into optimization
   * results.
   */
  @Test
  public void export_comparison_auto_bwtext_grayscale() {
    Bitmap bitmap = makeSyntheticDocument(2480, 3508, 300); // A4-like

    Uri outAuto = createTempContentUri("auto.jpg");
    Uri outBw = createTempContentUri("bw.jpg");
    Uri outGray = createTempContentUri("gray.jpg");

    int baseQ = 85;

    JpegExportOptions optsAuto = new JpegExportOptions(baseQ, 0, JpegExportOptions.Mode.NONE);
    optsAuto.forceGrayscaleJpeg = false;
    optsAuto.maxLongEdgeGuardPx = 4096;
    optsAuto.roundResizeToMultipleOf8 = true;

    JpegExportOptions optsBw = new JpegExportOptions(baseQ, 0, JpegExportOptions.Mode.BW_TEXT);
    optsBw.forceGrayscaleJpeg = false; // exporter will gray anyway for BW_TEXT
    optsBw.maxLongEdgeGuardPx = 4096;
    optsBw.roundResizeToMultipleOf8 = true;

    JpegExportOptions optsAutoForceGray =
        new JpegExportOptions(baseQ, 0, JpegExportOptions.Mode.NONE);
    optsAutoForceGray.forceGrayscaleJpeg = true;
    optsAutoForceGray.maxLongEdgeGuardPx = 4096;
    optsAutoForceGray.roundResizeToMultipleOf8 = true;

    Assert.assertNotNull(JpegExporter.export(ctx, bitmap, optsAuto, outAuto));
    Assert.assertNotNull(JpegExporter.export(ctx, bitmap, optsBw, outBw));
    Assert.assertNotNull(JpegExporter.export(ctx, bitmap, optsAutoForceGray, outGray));

    long sizeAuto = sizeOf(outAuto);
    long sizeBw = sizeOf(outBw);
    long sizeGray = sizeOf(outGray);

    Log.i(
        TAG, "Sizes (bytes)  AUTO=" + sizeAuto + "  BW_TEXT=" + sizeBw + "  AUTO+Gray=" + sizeGray);

    Assert.assertTrue("BW_TEXT should be smaller than AUTO", sizeBw < sizeAuto);
    Assert.assertTrue(
        "AUTO (forced grayscale) should be smaller or equal to AUTO color", sizeGray <= sizeAuto);

    double reduction = (1.0 - (sizeBw / (double) sizeAuto));
    Log.i(
        TAG,
        "BW_TEXT reduction vs AUTO: "
            + Math.round(reduction * 100)
            + "% (AUTO="
            + sizeAuto
            + ", BW="
            + sizeBw
            + ")");
    Assert.assertTrue(
        "BW_TEXT should be at least ~5% smaller (was "
            + Math.round(reduction * 100)
            + "%) [AUTO="
            + sizeAuto
            + ", BW="
            + sizeBw
            + "]",
        reduction > 0.05);
  }

  // --- Helpers -------------------------------------------------------------------------------

  /**
   * Creates a temporary content URI for use in file operations. The method generates a temporary
   * file in the application's cache directory, ensuring it is isolated for the current app's
   * process.
   *
   * @param name the identifier used to name the temporary file, typically a unique or descriptive
   *     string.
   * @return the URI pointing to the newly created temporary file, which can be used for further
   *     operations.
   */
  private Uri createTempContentUri(String name) {
    // Use the target app context and its FileProvider to avoid cross-UID access issues
    File f = new File(ctx.getCacheDir(), "jpeg_exporter_test_" + name);
    if (f.exists()) // noinspection ResultOfMethodCallIgnored
    f.delete();
    try { //noinspection ResultOfMethodCallIgnored
      f.createNewFile();
    } catch (Exception ignored) {
    }
    return Uri.fromFile(f);
  }

  /**
   * Calculates the size of the content represented by the given URI in bytes. If the URI points to
   * a file in the cache directory, it calculates the size directly. Otherwise, it attempts to
   * retrieve the size using an AssetFileDescriptor or by reading the input stream.
   *
   * @param uri the URI pointing to the content whose size needs to be calculated
   * @return the size of the content in bytes, or -1 if an error occurs or the size cannot be
   *     determined
   */
  private long sizeOf(Uri uri) {
    String path = uri.getPath();
    if (path != null && path.contains("/cache/")) {
      String fileName = path.substring(path.lastIndexOf('/') + 1);
      File f = new File(ctx.getCacheDir(), fileName);
      return f.length();
    } else {
      // Try AssetFileDescriptor first
      try (android.content.res.AssetFileDescriptor afd =
          ctx.getContentResolver().openAssetFileDescriptor(uri, "r")) {
        if (afd != null && afd.getLength() >= 0) {
          return afd.getLength();
        }
      } catch (Exception ignore) {
      }
      // Fallback: read the stream and count bytes
      try (java.io.InputStream is = ctx.getContentResolver().openInputStream(uri)) {
        if (is == null) return -1L;
        byte[] buf = new byte[8192];
        long total = 0;
        int read;
        while ((read = is.read(buf)) != -1) {
          total += read;
        }
        return total;
      } catch (Exception e) {
        return -1L;
      }
    }
  }

  /**
   * Creates a synthetic document in the form of a bitmap image. The document contains a header,
   * address block, table with sample rows, and footer text. It is designed with specified
   * dimensions, resolution, and includes aesthetic details like noise overlay.
   *
   * @param width the width of the document in pixels
   * @param height the height of the document in pixels
   * @param dpi the dots per inch (DPI) resolution of the document
   * @return a generated Bitmap representing the synthetic document
   */
  private Bitmap makeSyntheticDocument(int width, int height, int dpi) {
    Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bmp);

    canvas.drawColor(Color.WHITE);

    Paint paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
    paintText.setColor(Color.BLACK);
    paintText.setStyle(Paint.Style.FILL);

    Paint paintLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    paintLine.setColor(Color.BLACK);
    paintLine.setStrokeWidth(3f);

    // Header
    paintText.setTextSize(spToPx(20f, dpi));
    canvas.drawText("ACME GmbH – Rechnung 2025-09-01 – #INV-123456", 80f, 140f, paintText);

    // Address
    paintText.setTextSize(spToPx(14f, dpi));
    float startY = 220f;
    float lineH = 48f;
    String[] lines =
        new String[] {"Max Mustermann", "Musterstraße 12", "12345 Musterstadt", "Deutschland"};
    for (int i = 0; i < lines.length; i++) {
      canvas.drawText(lines[i], 80f, startY + i * lineH, paintText);
    }

    // Table header
    float tableTop = 520f;
    float left = 80f;
    float right = width - 80f;
    canvas.drawLine(left, tableTop, right, tableTop, paintLine);
    String[] headers = new String[] {"Pos", "Beschreibung", "Menge", "Einzelpreis", "Summe"};
    float[] cols = new float[] {left, 180f, 900f, 1200f, 1500f, right};
    paintText.setTextSize(spToPx(13f, dpi));
    for (int i = 0; i < cols.length - 1; i++) {
      canvas.drawText(headers[i], cols[i] + 8f, tableTop + 40f, paintText);
      canvas.drawLine(cols[i], tableTop, cols[i], tableTop + 50f, paintLine);
    }
    canvas.drawLine(left, tableTop + 50f, right, tableTop + 50f, paintLine);

    // Table rows
    float y = tableTop + 50f;
    for (int row = 1; row <= 25; row++) {
      float rowH = 42f;
      y += rowH;
      canvas.drawLine(left, y, right, y, paintLine);
      drawRowText(canvas, paintText, cols, y - 12f, row);
    }

    // Footer
    paintText.setTextSize(spToPx(12f, dpi));
    canvas.drawText(
        "Vielen Dank für Ihren Einkauf. Zahlungsziel: 14 Tage netto.",
        80f,
        height - 160f,
        paintText);

    addSubtleNoise(bmp, 6);
    return bmp;
  }

  /**
   * Draws a row of text on a canvas, representing table content with specific formatting. The row
   * includes an index, a description, quantity, unit price, and total price.
   *
   * @param canvas the Canvas object where the text will be drawn
   * @param paint the Paint object used to style and draw the text
   * @param cols an array of floats representing the horizontal column positions
   * @param baseline the vertical baseline position for drawing the text
   * @param idx the index of the row being drawn, used to generate row-specific content
   */
  private void drawRowText(Canvas canvas, Paint paint, float[] cols, float baseline, int idx) {
    canvas.drawText(String.format("%02d", idx), cols[0] + 8f, baseline, paint);
    canvas.drawText("Artikel " + idx + " – Beispielbeschreibung", cols[1] + 8f, baseline, paint);
    canvas.drawText("1", cols[2] + 8f, baseline, paint);
    canvas.drawText("19,99 €", cols[3] + 8f, baseline, paint);
    canvas.drawText("19,99 €", cols[4] + 8f, baseline, paint);
  }

  /**
   * Converts a value in scale-independent pixels (SP) to pixels (PX) based on the provided screen
   * density in dots per inch (DPI).
   *
   * @param sp the value in scale-independent pixels (SP)
   * @param dpi the screen density in dots per inch (DPI)
   * @return the equivalent pixel (PX) value for the given SP and DPI
   */
  private float spToPx(float sp, int dpi) {
    return sp * (dpi / 160f);
  }

  /**
   * Adds subtle random noise to specific pixels of a bitmap to enhance its appearance or simulate
   * certain effects, such as a vintage texture or texture irregularity. The method modifies the
   * bitmap in place by slightly altering the RGB values of the affected pixels.
   *
   * @param bmp the Bitmap to which the noise will be applied. Must be mutable and properly
   *     initialized.
   * @param amount the maximum perturbation applied to the RGB values of each affected pixel. A
   *     higher value increases the intensity of the noise.
   */
  private void addSubtleNoise(Bitmap bmp, int amount) {
    int w = bmp.getWidth();
    int h = bmp.getHeight();
    Random rnd = new Random(1234L);
    int[] pixels = new int[w * h];
    bmp.getPixels(pixels, 0, w, 0, 0, w, h);
    for (int i = 0; i < pixels.length; i += 17) {
      int c = pixels[i];
      int r = (c >> 16) & 0xFF;
      int g = (c >> 8) & 0xFF;
      int b = c & 0xFF;
      int delta = rnd.nextInt(amount * 2 + 1) - amount;
      int rr = clamp(r + delta, 0, 255);
      int gg = clamp(g + delta, 0, 255);
      int bb = clamp(b + delta, 0, 255);
      pixels[i] = (0xFF << 24) | (rr << 16) | (gg << 8) | bb;
    }
    bmp.setPixels(pixels, 0, w, 0, 0, w, h);
  }

  /**
   * Constrains a value to lie between a minimum and a maximum bound. If the value is less than the
   * minimum, the minimum is returned. If the value is greater than the maximum, the maximum is
   * returned. Otherwise, the value itself is returned.
   *
   * @param v the value to constrain
   * @param min the minimum bound
   * @param max the maximum bound
   * @return the constrained value, which will be between the minimum and maximum (inclusive)
   */
  private int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }
}
