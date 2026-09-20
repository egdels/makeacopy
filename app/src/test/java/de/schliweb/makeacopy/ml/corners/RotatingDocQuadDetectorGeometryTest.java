/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ml.corners;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit tests for the pure geometry of {@link RotatingDocQuadDetector}. */
public class RotatingDocQuadDetectorGeometryTest {

  private static final int W = 540;
  private static final int H = 720;

  // Tilted document in the source image, TL,TR,BR,BL.
  private static final double[][] QUAD = {{120, 90}, {430, 160}, {380, 610}, {70, 540}};

  @Test
  public void rotatedSize_matchesBoundingBox() {
    assertArrayEquals(new int[] {W, H}, RotatingDocQuadDetector.rotatedSize(W, H, 0));
    assertArrayEquals(new int[] {H, W}, RotatingDocQuadDetector.rotatedSize(W, H, 90));
    int[] s = RotatingDocQuadDetector.rotatedSize(W, H, 30);
    assertEquals(Math.ceil(W * Math.cos(Math.PI / 6) + H * 0.5), s[0], 1.0);
    assertEquals(Math.ceil(W * 0.5 + H * Math.cos(Math.PI / 6)), s[1], 1.0);
  }

  @Test
  public void mapForward_keepsEveryImagePointInsideTheRotatedFrame() {
    for (int angle : new int[] {90, -30, 30, -60, 60}) {
      int[] size = RotatingDocQuadDetector.rotatedSize(W, H, angle);
      for (double[] p : new double[][] {{0, 0}, {W, 0}, {W, H}, {0, H}}) {
        double[] q = RotatingDocQuadDetector.mapForward(p[0], p[1], angle, W, H, size);
        assertTrue("x inside for " + angle, q[0] >= -1.0 && q[0] <= size[0] + 1.0);
        assertTrue("y inside for " + angle, q[1] >= -1.0 && q[1] <= size[1] + 1.0);
      }
    }
  }

  @Test
  public void mapBack_invertsMapForward_andRestoresCornerOrder() {
    for (int angle : new int[] {90, -30, 30, -60, 60}) {
      int[] size = RotatingDocQuadDetector.rotatedSize(W, H, angle);
      double[][] rotated = new double[4][];
      for (int i = 0; i < 4; i++) {
        rotated[i] = RotatingDocQuadDetector.mapForward(QUAD[i][0], QUAD[i][1], angle, W, H, size);
      }
      // The model labels corners relative to the rotated image it sees: simulate that by
      // cyclically shifting the labels. mapBack must undo both the rotation and the relabelling.
      double[][] relabelled = {rotated[1], rotated[2], rotated[3], rotated[0]};

      double[][] back = RotatingDocQuadDetector.mapBack(relabelled, angle, W, H, size, 1.0);

      for (int i = 0; i < 4; i++) {
        assertEquals("x" + i + " @" + angle, QUAD[i][0], back[i][0], 1e-6);
        assertEquals("y" + i + " @" + angle, QUAD[i][1], back[i][1], 1e-6);
      }
      assertTrue(DocQuadDetector.isConvexTLTRBRBL(back));
    }
  }

  @Test
  public void acceptsRotated_requiresFloorAndClearMarginOverUnrotatedPass() {
    // Noise-level swap between two weak detections: keep the unrotated result.
    assertFalse(RotatingDocQuadDetector.acceptsRotated(0.08, 0.19));
    // Clear win over a weak unrotated pass.
    assertTrue(RotatingDocQuadDetector.acceptsRotated(0.14, 0.58));
    assertTrue(RotatingDocQuadDetector.acceptsRotated(0.0, 0.15));
    // Below the floor nothing is accepted, even if the unrotated pass found nothing.
    assertFalse(RotatingDocQuadDetector.acceptsRotated(-1.0, 0.09));
    // No valid unrotated quad: the floor alone decides.
    assertTrue(RotatingDocQuadDetector.acceptsRotated(-1.0, 0.12));
  }

  @Test
  public void mapBack_scalesToSourceResolution() {
    int[] size = RotatingDocQuadDetector.rotatedSize(W, H, 30);
    double[][] rotated = new double[4][];
    for (int i = 0; i < 4; i++) {
      rotated[i] = RotatingDocQuadDetector.mapForward(QUAD[i][0], QUAD[i][1], 30, W, H, size);
    }
    double[][] back = RotatingDocQuadDetector.mapBack(rotated, 30, W, H, size, 4.0);
    for (int i = 0; i < 4; i++) {
      assertEquals(QUAD[i][0] * 4.0, back[i][0], 1e-6);
      assertEquals(QUAD[i][1] * 4.0, back[i][1], 1e-6);
    }
  }
}
