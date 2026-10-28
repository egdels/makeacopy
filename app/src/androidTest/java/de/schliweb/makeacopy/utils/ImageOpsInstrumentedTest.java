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
import de.schliweb.makeacopy.utils.image.ImageOps;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import de.schliweb.makeacopy.utils.geom.Point;

/**
 * Umfassende Instrumented-Tests für ImageOps. Hinweis: Echte Testbilder werden später ergänzt –
 * aktuell werden Platzhalter-Bitmaps verwendet.
 */
@RunWith(AndroidJUnit4.class)
public class ImageOpsInstrumentedTest {

  private static Context appContext;

  @BeforeClass
  public static void setUpOnce() {
    appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
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
    Bitmap src = createSolidBitmap(320, 240, Color.RED);
    Bitmap gray = ImageOps.toGray(src);
    assertNotNull(gray);
    assertEquals(320, gray.getWidth());
    assertEquals(240, gray.getHeight());
  }

  @Test
  public void toBw_withPattern_returnsNonNull() {
    Bitmap src = createTestPatternBitmap(480, 360);
    Bitmap bw = ImageOps.toBw(src);
    assertNotNull(bw);
    assertEquals(480, bw.getWidth());
    assertEquals(360, bw.getHeight());
  }

  // ---------- Zusätzliche Tests für öffentliche Bildoperationen ----------

  @Test
  public void applyPerspectiveCorrection_identityMapping_returnsSameSize() {
    Bitmap src = createTestPatternBitmap(300, 200);
    Point[] corners =
        new Point[] {
          new Point(0, 0), new Point(src.getWidth() - 1.0, 0),
          new Point(src.getWidth() - 1.0, src.getHeight() - 1.0),
              new Point(0, src.getHeight() - 1.0)
        };
    Bitmap out = ImageOps.applyPerspectiveCorrection(src, corners);
    assertNotNull(out);
    assertEquals(src.getWidth(), out.getWidth());
    assertEquals(src.getHeight(), out.getHeight());
  }

  @Test
  public void applyPerspectiveCorrection_withNullCorners_returnsSameInstance() {
    Bitmap src = createTestPatternBitmap(120, 80);
    Bitmap out = ImageOps.applyPerspectiveCorrection(src, null);
    assertSame("Bei null Ecken muss das Original zurückgegeben werden", src, out);
  }

  @Test
  public void applyPerspectiveCorrection_withWrongCornerCount_returnsSameInstance() {
    Bitmap src = createTestPatternBitmap(90, 60);
    Point[] corners =
        new Point[] {new Point(0, 0), new Point(10, 0), new Point(10, 10)}; // nur 3 Punkte
    Bitmap out = ImageOps.applyPerspectiveCorrection(src, corners);
    assertSame("Bei falscher Anzahl Ecken muss das Original zurückgegeben werden", src, out);
  }

  @Test
  public void applyPerspectiveCorrection_degenerateQuad_doesNotCrash_andHasMinSize() {
    Bitmap src = createTestPatternBitmap(200, 150);
    // Degeneriertes Viereck (alle Punkte sehr nah beieinander)
    Point c = new Point(50, 50);
    Point[] corners =
        new Point[] {
          new Point(c.x, c.y), new Point(c.x + 0.1, c.y),
          new Point(c.x + 0.1, c.y + 0.1), new Point(c.x, c.y + 0.1)
        };
    Bitmap out = ImageOps.applyPerspectiveCorrection(src, corners);
    assertNotNull(out);
    assertThat(out.getWidth(), greaterThanOrEqualTo(1));
    assertThat(out.getHeight(), greaterThanOrEqualTo(1));
  }

  // Hinweis: warpPerspectiveWithMatrix ist nicht öffentlich, daher hier nicht direkt testbar.

  // ---------- Zusätzliche Robustheits- und API-Vertrags-Tests ----------
}
