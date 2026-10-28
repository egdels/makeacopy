package de.schliweb.makeacopy.utils;

import static org.junit.Assert.assertEquals;

import de.schliweb.makeacopy.utils.image.ImageOps.OrientationEstimate;
import org.junit.Test;

public class OrientationEstimateTest {

  @Test
  public void bucket0_storedAsIs() {
    OrientationEstimate e = new OrientationEstimate(0, 0.8);
    assertEquals(0, e.bucketDeg());
    assertEquals(0.8, e.confidence(), 1e-9);
  }

  @Test
  public void bucket90_storedAsIs() {
    OrientationEstimate e = new OrientationEstimate(90, 0.95);
    assertEquals(90, e.bucketDeg());
    assertEquals(0.95, e.confidence(), 1e-9);
  }

  @Test
  public void nonStandardBucket_clampedTo0() {
    // Any value other than 90 becomes 0
    OrientationEstimate e = new OrientationEstimate(45, 0.5);
    assertEquals(0, e.bucketDeg());
  }

  @Test
  public void confidence_clampedToZeroOne() {
    OrientationEstimate high = new OrientationEstimate(0, 2.0);
    assertEquals(1.0, high.confidence(), 1e-9);

    OrientationEstimate low = new OrientationEstimate(0, -0.5);
    assertEquals(0.0, low.confidence(), 1e-9);
  }

  @Test
  public void confidence_boundaryValues() {
    assertEquals(0.0, new OrientationEstimate(0, 0.0).confidence(), 1e-9);
    assertEquals(1.0, new OrientationEstimate(90, 1.0).confidence(), 1e-9);
  }
}
