package de.schliweb.makeacopy.ml.corners;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for ThrottledDocQuadLiveDetector throttling logic. Note: Bitmap.createBitmap returns null
 * in JVM unit tests (returnDefaultValues=true), so throttle tests that need a non-null Bitmap use a
 * custom CornerDetector wrapper that bypasses the null-bitmap guard.
 */
public class ThrottledDocQuadLiveDetectorTest {

  private static final double[][] QUAD = {{0, 0}, {100, 0}, {100, 100}, {0, 100}};

  @Test
  public void nullBitmap_returnsFail() {
    long[] fakeTime = {0};
    ThrottledDocQuadLiveDetector det =
        new ThrottledDocQuadLiveDetector(
            null, () -> fakeTime[0], (src, ctx) -> DetectionResult.success(Source.DOCQUAD, QUAD));
    DetectionResult r = det.detect(null, null);
    assertFalse(r.success);
    assertEquals(Source.DOCQUAD, r.source);
  }

  @Test
  public void detect_success_factory() {
    DetectionResult r = DetectionResult.success(Source.DOCQUAD, QUAD);
    assertTrue(r.success);
    assertEquals(Source.DOCQUAD, r.source);
    assertSame(QUAD, r.cornersOriginalTLTRBRBL);
  }

  @Test
  public void detect_fail_factory() {
    DetectionResult r = DetectionResult.fail(Source.DOCQUAD);
    assertFalse(r.success);
    assertEquals(Source.DOCQUAD, r.source);
    assertNull(r.cornersOriginalTLTRBRBL);
  }

  @Test
  public void unsureOutline_isNotShown() {
    DetectionResult noise = DetectionResult.success(Source.DOCQUAD, QUAD).withConfidence(0.0);
    assertFalse(ThrottledDocQuadLiveDetector.unlessUnsure(noise).success);
    DetectionResult page = DetectionResult.success(Source.DOCQUAD, QUAD).withConfidence(0.9);
    assertSame(page, ThrottledDocQuadLiveDetector.unlessUnsure(page));
    DetectionResult unknown = DetectionResult.success(Source.DOCQUAD, QUAD);
    assertSame(unknown, ThrottledDocQuadLiveDetector.unlessUnsure(unknown));
  }
}
