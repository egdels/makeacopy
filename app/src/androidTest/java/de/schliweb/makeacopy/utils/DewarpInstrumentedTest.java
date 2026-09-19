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
import de.schliweb.makeacopy.utils.image.DewarpModel;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import de.schliweb.makeacopy.utils.image.OpenCVUtils.WarpMode;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.core.Point;

/**
 * Instrumented-Tests für das Dewarping gewölbter Buchseiten (Issue #91, Phase 1): {@link
 * DewarpModel} und {@link OpenCVUtils#applyDewarp}.
 */
@RunWith(AndroidJUnit4.class)
public class DewarpInstrumentedTest {

  private static Context appContext;

  @BeforeClass
  public static void setUpOnce() {
    appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    OpenCVUtils.init(appContext);
  }

  // ---------- Helper ----------

  private static Bitmap createSolidBitmap(int w, int h, int color) {
    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(bmp);
    c.drawColor(color);
    return bmp;
  }

  /** Standard-Testmodell: Quad mit nach unten gewölbter oberer und unterer Kante. */
  private static DewarpModel createCurvedModel() {
    Point[] corners =
        new Point[] {new Point(20, 40), new Point(380, 40), new Point(380, 260), new Point(20, 260)};
    // Beide Kurven wölben sich 30 px nach unten (typisch für eine gewölbte Buchseite)
    Point topMid = new Point(200, 70);
    Point bottomMid = new Point(200, 290);
    return DewarpModel.fromOnCurveMidpoints(corners, topMid, bottomMid);
  }

  /**
   * Zeichnet eine dicke schwarze Polylinie entlang der Regelflächen-Mittelkurve (v = 0.5) des
   * Modells auf weißem Grund. Nach dem Dewarp muss diese Linie horizontal (gerade) sein.
   */
  private static Bitmap createCurvedLineBitmap(DewarpModel model, int w, int h) {
    Bitmap bmp = createSolidBitmap(w, h, Color.WHITE);
    Canvas c = new Canvas(bmp);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(5f);
    p.setColor(Color.BLACK);
    android.graphics.Path path = new android.graphics.Path();
    int n = 64;
    for (int i = 0; i <= n; i++) {
      double t = i / (double) n;
      Point top = model.topAt(t);
      Point bottom = model.bottomAt(t);
      float x = (float) (0.5 * (top.x + bottom.x));
      float y = (float) (0.5 * (top.y + bottom.y));
      if (i == 0) path.moveTo(x, y);
      else path.lineTo(x, y);
    }
    c.drawPath(path, p);
    return bmp;
  }

  private static boolean isDark(int color) {
    return Color.red(color) + Color.green(color) + Color.blue(color) < 3 * 128;
  }

  /**
   * Simulates a photographed page with a real paper/background contrast: a dark "table" background
   * fills the whole bitmap, and a white page — with straight left/right edges but top and bottom
   * edges bowed by {@code sagittaPx} at their midpoint, like a cylindrically bent book page — is
   * filled on top of it. {@code lines} curved black "text" strokes are drawn inside the page purely
   * for visual realism; the actual signal {@link OpenCVUtils#estimateDewarpCurveOffsets} traces is
   * the curved paper/background edge itself (Otsu threshold crossing), not the text.
   *
   * @return the bitmap plus the straight-chord corners (TL, TR, BR, BL) of the page, i.e. the quad
   *     a user would select by tapping the (visually slightly curved) page corners
   */
  private static Object[] createCurvedPageWithTextBitmap(
      int w, int h, int pageLeft, int pageTop, int pageRight, int pageBottom, int lines,
      float sagittaPx) {
    Bitmap bmp = createSolidBitmap(w, h, Color.DKGRAY);
    Canvas c = new Canvas(bmp);

    Point tl = new Point(pageLeft, pageTop);
    Point tr = new Point(pageRight, pageTop);
    Point br = new Point(pageRight, pageBottom);
    Point bl = new Point(pageLeft, pageBottom);

    // The white page itself: straight side edges, top/bottom edges bowed downward by sagittaPx at
    // their midpoint (same sign convention as OpenCVUtils#estimateDewarpEdgeProfiles: positive =
    // displaced in chord-normal direction, i.e. downward for a left-to-right top/bottom chord).
    Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    fill.setStyle(Paint.Style.FILL);
    fill.setColor(Color.WHITE);
    android.graphics.Path page = new android.graphics.Path();
    page.moveTo((float) tl.x, (float) tl.y);
    page.quadTo(
        0.5f * (float) (tl.x + tr.x), (float) tl.y + 2f * sagittaPx, (float) tr.x, (float) tr.y);
    page.lineTo((float) br.x, (float) br.y);
    page.quadTo(
        0.5f * (float) (bl.x + br.x), (float) br.y + 2f * sagittaPx, (float) bl.x, (float) bl.y);
    page.close();
    c.drawPath(page, fill);

    // Decorative curved "text lines" inside the page (do not drive the estimate).
    Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeWidth(8f);
    stroke.setColor(Color.BLACK);
    float x0 = pageLeft + 0.1f * (pageRight - pageLeft);
    float x1 = pageRight - 0.1f * (pageRight - pageLeft);
    for (int i = 0; i < lines; i++) {
      float y = pageTop + (pageBottom - pageTop) * (0.2f + 0.6f * i / (float) (lines - 1));
      android.graphics.Path path = new android.graphics.Path();
      path.moveTo(x0, y);
      path.quadTo(0.5f * (x0 + x1), y + 2f * sagittaPx, x1, y);
      c.drawPath(path, stroke);
    }
    return new Object[] {bmp, new Point[] {tl, tr, br, bl}};
  }

  // ---------- Tests ----------

  @Test
  public void fromOnCurveMidpoints_curveInterpolatesMidpointsAndCorners() {
    DewarpModel model = createCurvedModel();
    assertNotNull(model);
    // Kurven müssen durch die Ecken laufen …
    assertEquals(20.0, model.topAt(0).x, 1e-6);
    assertEquals(40.0, model.topAt(0).y, 1e-6);
    assertEquals(380.0, model.topAt(1).x, 1e-6);
    assertEquals(260.0, model.bottomAt(1).y, 1e-6);
    // … und bei t=0.5 exakt durch die Handle-Punkte
    assertEquals(200.0, model.topAt(0.5).x, 1e-6);
    assertEquals(70.0, model.topAt(0.5).y, 1e-6);
    assertEquals(290.0, model.bottomAt(0.5).y, 1e-6);
    // Sagitta = 30 px, also deutlich nicht "gerade"
    assertEquals(30.0, model.maxSagitta(), 1e-6);
    assertFalse(model.isEffectivelyStraight(1.5));
  }

  @Test
  public void fromOnCurveMidpoints_invalidInput_returnsNull() {
    assertNull(DewarpModel.fromOnCurveMidpoints(null, new Point(0, 0), new Point(0, 0)));
    assertNull(
        DewarpModel.fromOnCurveMidpoints(
            new Point[] {new Point(0, 0), new Point(1, 0), new Point(1, 1)},
            new Point(0, 0),
            new Point(0, 0)));
    Point[] corners =
        new Point[] {new Point(0, 0), new Point(1, 0), new Point(1, 1), new Point(0, 1)};
    assertNull(DewarpModel.fromOnCurveMidpoints(corners, null, new Point(0, 0)));
  }

  @Test
  public void straightModel_isEffectivelyStraight() {
    Point[] corners =
        new Point[] {new Point(10, 10), new Point(200, 10), new Point(200, 150), new Point(10, 150)};
    DewarpModel model =
        DewarpModel.fromOnCurveMidpoints(corners, new Point(105, 10), new Point(105, 150));
    assertNotNull(model);
    assertTrue(model.isEffectivelyStraight(1.5));
  }

  @Test
  public void applyDewarp_nullModel_returnsSameInstance() {
    Bitmap src = createSolidBitmap(100, 80, Color.WHITE);
    Bitmap out = OpenCVUtils.applyDewarp(src, null, WarpMode.AUTO_PROJECTIVE, null);
    assertSame("Bei null-Modell muss das Original zurückgegeben werden", src, out);
  }

  @Test
  public void applyDewarp_straightModel_matchesPerspectivePipeline() {
    assumeOpenCvInitialized();
    Bitmap src = createSolidBitmap(300, 200, Color.WHITE);
    Point[] corners =
        new Point[] {
          new Point(0, 0), new Point(src.getWidth() - 1.0, 0),
          new Point(src.getWidth() - 1.0, src.getHeight() - 1.0),
              new Point(0, src.getHeight() - 1.0)
        };
    Point topMid = new Point((src.getWidth() - 1.0) / 2.0, 0);
    Point bottomMid = new Point((src.getWidth() - 1.0) / 2.0, src.getHeight() - 1.0);
    DewarpModel model = DewarpModel.fromOnCurveMidpoints(corners, topMid, bottomMid);
    Bitmap dewarped = OpenCVUtils.applyDewarp(src, model, WarpMode.AUTO_PROJECTIVE, null);
    Bitmap perspective =
        OpenCVUtils.applyPerspectiveCorrection(src, corners, WarpMode.AUTO_PROJECTIVE, null);
    assertNotNull(dewarped);
    assertEquals(perspective.getWidth(), dewarped.getWidth());
    assertEquals(perspective.getHeight(), dewarped.getHeight());
  }

  @Test
  public void applyDewarp_curvedLine_becomesStraight() {
    assumeOpenCvInitialized();
    DewarpModel model = createCurvedModel();
    Bitmap src = createCurvedLineBitmap(model, 400, 300);
    Bitmap out = OpenCVUtils.applyDewarp(src, model, WarpMode.AUTO_PROJECTIVE, null);
    assertNotNull(out);
    assertNotSame("Gekrümmtes Modell darf nicht auf das Original zurückfallen", src, out);

    // Die Mittelkurve (v = 0.5) muss im Output eine horizontale Gerade bei ~H/2 sein.
    int w = out.getWidth();
    int h = out.getHeight();
    double expectedY = h / 2.0;
    int checked = 0;
    for (int i = 1; i <= 9; i++) {
      int x = (int) Math.round(w * i / 10.0);
      // Suche den dunkelsten (nächstgelegenen) Linienpixel in einem Fenster um H/2
      int found = -1;
      int window = Math.max(10, h / 10);
      for (int dy = 0; dy <= window; dy++) {
        int yUp = (int) expectedY - dy;
        int yDown = (int) expectedY + dy;
        if (yUp >= 0 && isDark(out.getPixel(x, yUp))) {
          found = yUp;
          break;
        }
        if (yDown < h && isDark(out.getPixel(x, yDown))) {
          found = yDown;
          break;
        }
      }
      assertThat("Linie an Spalte x=" + x + " nicht gefunden", found, greaterThanOrEqualTo(0));
      assertThat(
          "Linie an Spalte x=" + x + " weicht zu stark von H/2 ab (y=" + found + ")",
          Math.abs(found - expectedY),
          lessThanOrEqualTo(6.0));
      checked++;
    }
    assertEquals(9, checked);
  }

  @Test
  public void estimateDewarpCurveOffsets_curvedTextLines_returnsExpectedOffsets() {
    assumeOpenCvInitialized();
    // Real paper/background contrast: a dark background fills the whole bitmap, the white page
    // is inset from the bitmap border (leaves room for the algorithm's vertical expansion band,
    // see DEWARP_ESTIMATE_EXPAND_FRAC) and its top/bottom edges bow by `sagitta` px, like a
    // cylindrically bent book page. Text lines inside the page are purely decorative.
    int w = 600, h = 600;
    int pageLeft = 60, pageTop = 100, pageRight = 540, pageBottom = 500;
    float sagitta = 25f; // page edges bow 25 px downward at their midpoint
    Object[] fixture =
        createCurvedPageWithTextBitmap(w, h, pageLeft, pageTop, pageRight, pageBottom, 8, sagitta);
    Bitmap src = (Bitmap) fixture[0];
    Point[] corners = (Point[]) fixture[1];
    double[] offsets = OpenCVUtils.estimateDewarpCurveOffsets(src, corners);
    assertNotNull("Bei einer echten Papierkante muss eine Schätzung geliefert werden", offsets);
    assertEquals(2, offsets.length);
    // Erwarteter Offset: Sagitta / Sehnenlänge ≈ 25 / 480 ≈ 0.052; positiv = nach unten
    double expected = sagitta / (double) (pageRight - pageLeft);
    assertEquals("topOffsetFrac", expected, offsets[0], 0.02);
    assertEquals("bottomOffsetFrac", expected, offsets[1], 0.02);
    assertThat("Wölbung nach unten muss positives Vorzeichen haben", offsets[0], greaterThan(0.0));
    assertThat(offsets[1], greaterThan(0.0));
  }

  @Test
  public void withDepth_clampsAndDefaultsAreNeutral() {
    DewarpModel model = createCurvedModel();
    assertEquals(0.0, model.getDepth(), 1e-9);
    assertEquals(0.5, model.withDepth(0.5).getDepth(), 1e-9);
    // Clamping auf ±MAX_DEPTH, nicht-endliche Werte → neutral
    assertEquals(DewarpModel.MAX_DEPTH, model.withDepth(5.0).getDepth(), 1e-9);
    assertEquals(-DewarpModel.MAX_DEPTH, model.withDepth(-5.0).getDepth(), 1e-9);
    assertEquals(0.0, model.withDepth(Double.NaN).getDepth(), 1e-9);
  }

  @Test
  public void blendWeight_neutralIsIdentity_andDepthShiftsMonotonically() {
    DewarpModel model = createCurvedModel();
    // depth = 0: Identität
    for (double v = 0.0; v <= 1.0; v += 0.25) {
      assertEquals(v, model.blendWeight(v), 1e-9);
    }
    DewarpModel deep = model.withDepth(1.0);
    // Endpunkte bleiben fixiert, dazwischen Verschiebung Richtung Unterkante (w > v)
    assertEquals(0.0, deep.blendWeight(0.0), 1e-9);
    assertEquals(1.0, deep.blendWeight(1.0), 1e-9);
    assertEquals(0.75, deep.blendWeight(0.5), 1e-9); // 0.5 + 1.0*0.25
    // Monotonie
    double prev = -1;
    for (double v = 0.0; v <= 1.0001; v += 0.05) {
      double w = deep.blendWeight(Math.min(1.0, v));
      assertThat("blendWeight muss monoton sein (v=" + v + ")", w, greaterThanOrEqualTo(prev));
      prev = w;
    }
    // Negative Tiefe: Verschiebung Richtung Oberkante
    assertEquals(0.25, model.withDepth(-1.0).blendWeight(0.5), 1e-9);
  }

  @Test
  public void applyDewarp_depthChangesOutput() {
    assumeOpenCvInitialized();
    DewarpModel model = createCurvedModel();
    Bitmap src = createCurvedLineBitmap(model, 400, 300);
    Bitmap neutral = OpenCVUtils.applyDewarp(src, model, WarpMode.AUTO_PROJECTIVE, null);
    Bitmap deep = OpenCVUtils.applyDewarp(src, model.withDepth(0.8), WarpMode.AUTO_PROJECTIVE, null);
    assertNotNull(neutral);
    assertNotNull(deep);
    // Zielgröße hängt nur von den Ecken ab und muss identisch bleiben
    assertEquals(neutral.getWidth(), deep.getWidth());
    assertEquals(neutral.getHeight(), deep.getHeight());
    // Depth > 0 verschiebt die vertikale Verteilung → die Ausgabe muss sich unterscheiden
    boolean differs = false;
    outer:
    for (int y = 0; y < neutral.getHeight(); y += 7) {
      for (int x = 0; x < neutral.getWidth(); x += 7) {
        if (neutral.getPixel(x, y) != deep.getPixel(x, y)) {
          differs = true;
          break outer;
        }
      }
    }
    assertTrue("Depth ≠ 0 muss das Dewarp-Ergebnis verändern", differs);
  }

  @Test
  public void applyDewarp_straightModelWithDepth_usesRemapPath() {
    assumeOpenCvInitialized();
    Bitmap src = createSolidBitmap(300, 200, Color.WHITE);
    Point[] corners =
        new Point[] {
          new Point(0, 0), new Point(src.getWidth() - 1.0, 0),
          new Point(src.getWidth() - 1.0, src.getHeight() - 1.0),
              new Point(0, src.getHeight() - 1.0)
        };
    Point topMid = new Point((src.getWidth() - 1.0) / 2.0, 0);
    Point bottomMid = new Point((src.getWidth() - 1.0) / 2.0, src.getHeight() - 1.0);
    DewarpModel model = DewarpModel.fromOnCurveMidpoints(corners, topMid, bottomMid).withDepth(0.5);
    // Gerade Kanten, aber Depth ≠ 0: darf nicht auf die Perspektiv-Pipeline zurückfallen,
    // sondern muss den remap-Pfad nehmen (gleiche Zielgröße, kein Fehler).
    Bitmap out = OpenCVUtils.applyDewarp(src, model, WarpMode.AUTO_PROJECTIVE, null);
    assertNotNull(out);
    assertNotSame(src, out);
    Bitmap perspective =
        OpenCVUtils.applyPerspectiveCorrection(src, corners, WarpMode.AUTO_PROJECTIVE, null);
    assertEquals(perspective.getWidth(), out.getWidth());
    assertEquals(perspective.getHeight(), out.getHeight());
  }

  @Test
  public void estimateDewarpCurveOffsets_invalidOrEmptyInput_returnsNull() {
    assertNull(OpenCVUtils.estimateDewarpCurveOffsets(null, null));
    Bitmap blank = createSolidBitmap(300, 200, Color.WHITE);
    assertNull(
        "Zu wenige Ecken müssen null liefern",
        OpenCVUtils.estimateDewarpCurveOffsets(
            blank, new Point[] {new Point(0, 0), new Point(10, 0), new Point(10, 10)}));
    assumeOpenCvInitialized();
    Point[] corners =
        new Point[] {new Point(5, 5), new Point(295, 5), new Point(295, 195), new Point(5, 195)};
    // Leere (weiße) Seite: keine Textzeilen → keine Schätzung
    assertNull(OpenCVUtils.estimateDewarpCurveOffsets(blank, corners));
  }

  // ---------- Assumption Helper ----------
  private static void assumeOpenCvInitialized() {
    boolean ok;
    try {
      Bitmap dummy = createSolidBitmap(2, 2, Color.BLACK);
      Bitmap res = OpenCVUtils.toGray(dummy);
      ok = (res != null);
    } catch (Throwable t) {
      ok = false;
    }
    org.junit.Assume.assumeTrue("OpenCV nicht initialisiert – bildbasierte Tests übersprungen", ok);
  }
}
