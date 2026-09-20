/*
 * Copyright 2025 Christian Kierdorf
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
import static org.junit.Assert.assertSame;
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

  // A correct but strongly tilted DocQuad result loses the pure shape comparison against a
  // "nicer" axis-aligned OpenCV quad.
  private static final Point[] TILTED_DOCQUAD = {
    new Point(420, 120), new Point(900, 480), new Point(560, 900), new Point(90, 520)
  };
  private static final Point[] NICE_OPENCV = {
    new Point(100, 120), new Point(900, 100), new Point(880, 920), new Point(120, 900)
  };

  @Test
  public void chooseBest_withoutConfidence_followsShapeScore() {
    Point[] doc = TILTED_DOCQUAD.clone();
    Point[] cv = NICE_OPENCV.clone();
    assertSame(cv, TrapezoidSelectionView.chooseBestCropCorners(doc, null, cv, false, 1000, 1000));
    assertSame(cv, TrapezoidSelectionView.chooseBestCropCorners(doc, 0.1, cv, false, 1000, 1000));
  }

  @Test
  public void chooseBest_confidentDocQuad_winsOverNicerOpenCvQuad() {
    Point[] doc = TILTED_DOCQUAD.clone();
    Point[] cv = NICE_OPENCV.clone();
    assertSame(
        doc,
        TrapezoidSelectionView.chooseBestCropCorners(
            doc, TrapezoidSelectionView.DOCQUAD_TRUSTED_CONFIDENCE, cv, false, 1000, 1000));
  }

  @Test
  public void chooseBest_confidentButInvalidDocQuad_fallsBackToOpenCv() {
    Point[] bowTie = {
      new Point(100, 100), new Point(900, 900), new Point(900, 100), new Point(100, 900)
    };
    Point[] cv = NICE_OPENCV.clone();
    assertSame(cv, TrapezoidSelectionView.chooseBestCropCorners(bowTie, 0.9, cv, false, 1000, 1000));
  }

  @Test
  public void scoreImageQuad_prefersPlausibleDocumentOverTinyValidQuad() {
    Point[] plausible =
        new Point[] {
          new Point(100, 120), new Point(900, 100), new Point(880, 920), new Point(120, 900)
        };
    Point[] tinyButValid =
        new Point[] {
          new Point(100, 100), new Point(260, 100), new Point(260, 260), new Point(100, 260)
        };

    assertTrue(
        TrapezoidSelectionView.scoreImageQuad(plausible, 1000, 1000)
            > TrapezoidSelectionView.scoreImageQuad(tinyButValid, 1000, 1000));
  }

  @Test
  public void scoreImageQuad_prefersBalancedQuadOverStronglySkewedQuad() {
    Point[] balanced =
        new Point[] {
          new Point(100, 120), new Point(900, 100), new Point(880, 920), new Point(120, 900)
        };
    Point[] skewed =
        new Point[] {
          new Point(80, 100), new Point(940, 120), new Point(650, 900), new Point(260, 880)
        };

    assertTrue(
        TrapezoidSelectionView.scoreImageQuad(balanced, 1000, 1000)
            > TrapezoidSelectionView.scoreImageQuad(skewed, 1000, 1000));
  }
}