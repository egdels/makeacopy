package de.schliweb.makeacopy.ml.corners;

import static org.junit.Assert.*;

import org.junit.Test;

public class DetectionResultTest {

  @Test
  public void success_setsFieldsCorrectly() {
    double[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
    DetectionResult r = DetectionResult.success(Source.DOCQUAD, corners);
    assertTrue(r.success);
    assertEquals(Source.DOCQUAD, r.source);
    assertSame(corners, r.cornersOriginalTLTRBRBL);
    assertNull(r.chosenSource);
    assertNull(r.penaltyMask);
    assertNull(r.penaltyCorners);
  }

  @Test
  public void fail_setsFieldsCorrectly() {
    DetectionResult r = DetectionResult.fail(Source.DOCQUAD);
    assertFalse(r.success);
    assertEquals(Source.DOCQUAD, r.source);
    assertNull(r.cornersOriginalTLTRBRBL);
  }

  @Test
  public void successDebug_setsAllFields() {
    double[][] corners = {{10, 20}, {30, 20}, {30, 40}, {10, 40}};
    DetectionResult r = DetectionResult.successDebug(Source.DOCQUAD, corners, "mask", 0.5, 0.3);
    assertTrue(r.success);
    assertEquals(Source.DOCQUAD, r.source);
    assertSame(corners, r.cornersOriginalTLTRBRBL);
    assertEquals("mask", r.chosenSource);
    assertEquals(0.5, r.penaltyMask, 1e-9);
    assertEquals(0.3, r.penaltyCorners, 1e-9);
  }

  @Test
  public void successDebug_nullOptionalFields() {
    double[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
    DetectionResult r = DetectionResult.successDebug(Source.DOCQUAD, corners, null, null, null);
    assertTrue(r.success);
    assertNull(r.chosenSource);
    assertNull(r.penaltyMask);
    assertNull(r.penaltyCorners);
  }

  @Test
  public void sourceEnum_allValues() {
    Source[] values = Source.values();
    assertEquals(1, values.length);
    assertEquals(Source.DOCQUAD, Source.valueOf("DOCQUAD"));
  }
}
