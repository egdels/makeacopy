/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.crop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.opencv.core.Point;

public class TrapezoidSelectionViewImageQuadValidationTest {

  @Test
  public void validDocumentQuad_isAccepted() {
    Point[] quad =
        new Point[] {
          new Point(100, 120), new Point(900, 100), new Point(880, 920), new Point(120, 900)
        };

    assertTrue(TrapezoidSelectionView.isValidImageQuad(quad, 1000, 1000));
  }

  @Test
  public void nonFinitePoint_isRejected() {
    Point[] quad =
        new Point[] {
          new Point(100, 100),
          new Point(Double.NaN, 100),
          new Point(900, 900),
          new Point(100, 900)
        };

    assertFalse(TrapezoidSelectionView.isValidImageQuad(quad, 1000, 1000));
  }

  @Test
  public void outsideImagePoint_isRejected() {
    Point[] quad =
        new Point[] {
          new Point(100, 100), new Point(1100, 100), new Point(900, 900), new Point(100, 900)
        };

    assertFalse(TrapezoidSelectionView.isValidImageQuad(quad, 1000, 1000));
  }

  @Test
  public void slightlyOutsideQuad_isAcceptedAfterClamp() {
    // Typical DocQuad result for a frame-filling document: peaks inside the letterbox padding.
    Point[] quad =
        new Point[] {
          new Point(-12, 40), new Point(1015, 30), new Point(990, 1008), new Point(20, 980)
        };
    assertFalse(TrapezoidSelectionView.isValidImageQuad(quad, 1000, 1000));

    TrapezoidSelectionView.clampImageQuadToBounds(quad, 1000, 1000);

    assertTrue(TrapezoidSelectionView.isValidImageQuad(quad, 1000, 1000));
    assertEquals(0.0, quad[0].x, 0.0);
    assertEquals(40.0, quad[0].y, 0.0);
    assertEquals(1000.0, quad[1].x, 0.0);
    assertEquals(1000.0, quad[2].y, 0.0);
    assertEquals(20.0, quad[3].x, 0.0);
  }

  @Test
  public void clamp_keepsNonFiniteCoordinatesRejected() {
    Point[] quad =
        new Point[] {
          new Point(100, 100),
          new Point(Double.NaN, 100),
          new Point(900, 900),
          new Point(100, 900)
        };

    TrapezoidSelectionView.clampImageQuadToBounds(quad, 1000, 1000);

    assertFalse(TrapezoidSelectionView.isValidImageQuad(quad, 1000, 1000));
  }

  @Test
  public void tooSmallArea_isRejected() {
    Point[] quad =
        new Point[] {
          new Point(100, 100), new Point(150, 100), new Point(150, 150), new Point(100, 150)
        };

    assertFalse(TrapezoidSelectionView.isValidImageQuad(quad, 1000, 1000));
  }

  @Test
  public void selfIntersectingBowTie_isRejected() {
    Point[] quad =
        new Point[] {
          new Point(100, 100), new Point(900, 900), new Point(900, 100), new Point(100, 900)
        };

    assertFalse(TrapezoidSelectionView.isValidImageQuad(quad, 1000, 1000));
  }

  @Test
  public void concaveQuad_isRejected() {
    Point[] quad =
        new Point[] {
          new Point(100, 100), new Point(900, 100), new Point(500, 500), new Point(100, 900)
        };

    assertFalse(TrapezoidSelectionView.isValidImageQuad(quad, 1000, 1000));
  }

}
