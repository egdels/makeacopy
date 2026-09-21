package de.schliweb.makeacopy.ui.crop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;
import org.opencv.core.Point;

/** JVM-only tests for scaling trapezoid corners from the displayed bitmap to the full-res source. */
public class CropFragmentScalePointsTest {

  @Test
  public void scalesEachAxisIndependently() {
    Point[] display = {new Point(10, 20), new Point(100, 20), new Point(100, 200), new Point(0, 0)};
    Point[] scaled = CropFragment.scalePoints(display, 2.0, 0.5);
    assertEquals(4, scaled.length);
    assertEquals(20.0, scaled[0].x, 0.0);
    assertEquals(10.0, scaled[0].y, 0.0);
    assertEquals(200.0, scaled[2].x, 0.0);
    assertEquals(100.0, scaled[2].y, 0.0);
    assertEquals(0.0, scaled[3].x, 0.0);
  }

  @Test
  public void leavesTheInputUntouched() {
    Point[] display = {new Point(10, 20)};
    Point[] scaled = CropFragment.scalePoints(display, 3.0, 3.0);
    assertNotSame(display[0], scaled[0]);
    assertEquals(10.0, display[0].x, 0.0);
    assertEquals(20.0, display[0].y, 0.0);
  }

  @Test
  public void floatFactorsBehaveLikeTheFormerInlineMultiplication() {
    // The crop path computes its factors in float precision; widening them must not change values
    float sx = 4000 / (float) 1333;
    Point[] scaled = CropFragment.scalePoints(new Point[] {new Point(777.25, 1.0)}, sx, sx);
    assertEquals(777.25 * sx, scaled[0].x, 0.0);
  }
}
