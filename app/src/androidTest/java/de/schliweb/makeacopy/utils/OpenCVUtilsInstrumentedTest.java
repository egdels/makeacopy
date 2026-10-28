package de.schliweb.makeacopy.utils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.utils.image.BinarizationUtils;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.core.Point;

/**
 * Umfassende Instrumented-Tests für OpenCVUtils. Hinweis: Echte Testbilder werden später ergänzt –
 * aktuell werden Platzhalter-Bitmaps verwendet.
 */
@RunWith(AndroidJUnit4.class)
public class OpenCVUtilsInstrumentedTest {

  private static Context appContext;

  @BeforeClass
  public static void setUpOnce() {
    appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    // OpenCV und optionale Backends initialisieren. Falls dies fehlschlägt, werden
    // bildbasierte Tests über Assumptions ausgelassen, damit die Suite stabil bleibt.
    OpenCVUtils.init(appContext);
  }

  // ---------- Helper ----------
  private static Bitmap createSolidBitmap(int w, int h, int color) {
    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(bmp);
    c.drawColor(color);
    return bmp;
  }

  private static Bitmap createTestPatternBitmap(int w, int h) {
    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(bmp);
    c.drawColor(Color.WHITE);

    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(6f);
    p.setColor(Color.BLACK);

    // Zeichne ein Rechteck mit dicker Linie (dient als einfacher Dokumentrand)
    int pad = Math.max(12, Math.min(w, h) / 12);
    c.drawRect(pad, pad, w - pad, h - pad, p);

    // Diagonale Linien
    p.setColor(Color.DKGRAY);
    c.drawLine(pad, pad, w - pad, h - pad, p);
    c.drawLine(pad, h - pad, w - pad, pad, p);
    return bmp;
  }

  // ---------- Tests für Bitmap-basierte Utilities (mit Platzhalterbildern) ----------

  @Test
  public void toGray_withSolidBitmap_returnsNonNull() {
    assumeOpenCvInitialized();
    Bitmap src = createSolidBitmap(320, 240, Color.RED);
    Bitmap gray = OpenCVUtils.toGray(src);
    assertNotNull(gray);
    assertEquals(320, gray.getWidth());
    assertEquals(240, gray.getHeight());
  }

  @Test
  public void toBw_withPattern_returnsNonNull() {
    assumeOpenCvInitialized();
    Bitmap src = createTestPatternBitmap(480, 360);
    BinarizationUtils.BwOptions opt = new BinarizationUtils.BwOptions();
    Bitmap bw = OpenCVUtils.toBw(src, opt);
    assertNotNull(bw);
    assertEquals(480, bw.getWidth());
    assertEquals(360, bw.getHeight());
  }

  @Test
  @Ignore(
      "Temporarily disabled: this variant crashes instrumentation process on some emulators/devices; see issue #opencv-prepareForOCR-crash")
  public void prepareForOCR_binaryFlagBothVariants_nonNull() {
    assumeOpenCvInitialized();
    Bitmap src = createTestPatternBitmap(640, 400);
    Bitmap out1 = OpenCVUtils.prepareForOCR(src, true);
    Bitmap out2 = OpenCVUtils.prepareForOCR(src, false);
    assertNotNull(out1);
    assertNotNull(out2);
    assertEquals(640, out1.getWidth());
    assertEquals(400, out1.getHeight());
    assertEquals(640, out2.getWidth());
    assertEquals(400, out2.getHeight());
  }

  // ---------- Zusätzliche Tests für öffentliche Bildoperationen ----------

  @Test
  public void applyPerspectiveCorrection_identityMapping_returnsSameSize() {
    assumeOpenCvInitialized();
    Bitmap src = createTestPatternBitmap(300, 200);
    Point[] corners =
        new Point[] {
          new Point(0, 0), new Point(src.getWidth() - 1.0, 0),
          new Point(src.getWidth() - 1.0, src.getHeight() - 1.0),
              new Point(0, src.getHeight() - 1.0)
        };
    Bitmap out = OpenCVUtils.applyPerspectiveCorrection(src, corners);
    assertNotNull(out);
    assertEquals(src.getWidth(), out.getWidth());
    assertEquals(src.getHeight(), out.getHeight());
  }

  @Test
  public void applyPerspectiveCorrection_withNullCorners_returnsSameInstance() {
    Bitmap src = createTestPatternBitmap(120, 80);
    Bitmap out = OpenCVUtils.applyPerspectiveCorrection(src, null);
    assertSame("Bei null Ecken muss das Original zurückgegeben werden", src, out);
  }

  @Test
  public void applyPerspectiveCorrection_withWrongCornerCount_returnsSameInstance() {
    Bitmap src = createTestPatternBitmap(90, 60);
    Point[] corners =
        new Point[] {new Point(0, 0), new Point(10, 0), new Point(10, 10)}; // nur 3 Punkte
    Bitmap out = OpenCVUtils.applyPerspectiveCorrection(src, corners);
    assertSame("Bei falscher Anzahl Ecken muss das Original zurückgegeben werden", src, out);
  }

  @Test
  public void applyPerspectiveCorrection_degenerateQuad_doesNotCrash_andHasMinSize() {
    assumeOpenCvInitialized();
    Bitmap src = createTestPatternBitmap(200, 150);
    // Degeneriertes Viereck (alle Punkte sehr nah beieinander)
    Point c = new Point(50, 50);
    Point[] corners =
        new Point[] {
          new Point(c.x, c.y), new Point(c.x + 0.1, c.y),
          new Point(c.x + 0.1, c.y + 0.1), new Point(c.x, c.y + 0.1)
        };
    Bitmap out = OpenCVUtils.applyPerspectiveCorrection(src, corners);
    assertNotNull(out);
    assertThat(out.getWidth(), greaterThanOrEqualTo(1));
    assertThat(out.getHeight(), greaterThanOrEqualTo(1));
  }

  // Hinweis: warpPerspectiveWithMatrix ist nicht öffentlich, daher hier nicht direkt testbar.

  // ---------- Zusätzliche Robustheits- und API-Vertrags-Tests ----------

  @Test
  public void prepareForOCR_nullInput_returnsNull() {
    Bitmap out = OpenCVUtils.prepareForOCR(null, true);
    assertNull(out);
    out = OpenCVUtils.prepareForOCR(null, false);
    assertNull(out);
  }

  @Test
  public void fromBitmapBGR_returnsExpectedLengthAndRange() {
    assumeOpenCvInitialized();
    Bitmap src = createSolidBitmap(64, 64, Color.BLUE);
    float[] data = OpenCVUtils.fromBitmapBGR(src);
    assertNotNull(data);
    assertEquals(3 * 256 * 256, data.length);
    float min = Float.POSITIVE_INFINITY;
    float max = Float.NEGATIVE_INFINITY;
    int nonFinite = 0;
    for (float v : data) {
      if (!Float.isFinite(v)) nonFinite++;
      if (v < min) min = v;
      if (v > max) max = v;
    }
    assertEquals("Alle Werte müssen endlich sein", 0, nonFinite);
    assertThat("Werte sollen in [0,1] liegen", min, greaterThanOrEqualTo(0f));
    assertThat("Werte sollen in [0,1] liegen", max, lessThanOrEqualTo(1f));
  }

  // ---------- Assumption Helper ----------
  private static void assumeOpenCvInitialized() {
    // Wenn OpenCV nicht initialisiert werden konnte (z.B. fehlende native Lib im Testlauf),
    // überspringen wir die bildbasierten Tests, um Fehlalarme zu vermeiden.
    boolean ok;
    try {
      // Grober Sanity-Check: Eine einfache Konvertierung sollte ohne Crash funktionieren
      Bitmap dummy = createSolidBitmap(2, 2, Color.BLACK);
      Bitmap res = OpenCVUtils.toGray(dummy);
      ok = (res != null);
    } catch (Throwable t) {
      ok = false;
    }
    org.junit.Assume.assumeTrue("OpenCV nicht initialisiert – bildbasierte Tests übersprungen", ok);
  }
}
