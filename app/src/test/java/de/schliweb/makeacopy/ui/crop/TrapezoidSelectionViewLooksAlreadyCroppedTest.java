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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import de.schliweb.makeacopy.utils.geom.Point;

/**
 * Validates the heuristic used by {@link TrapezoidSelectionView#looksAlreadyCropped(Point[], int,
 * int)} which detects degenerate detector output on already-cropped input images.
 */
public class TrapezoidSelectionViewLooksAlreadyCroppedTest {

  /**
   * Real-world failure observed when a pre-cropped paper photo is shared from the gallery: the
   * detector returns a quad that hugs three image edges but has one corner stuck mid-edge,
   * cutting off a large portion of the image. The heuristic must trigger so the UI falls back to
   * the full-image rectangle.
   */
  @Test
  public void degenerateQuadOnPreCroppedImage_triggersFallback() {
    int w = 1937;
    int h = 2799;
    Point[] quad =
        new Point[] {
          new Point(27, 1110), // TL stuck mid-left-edge
          new Point(1909, 288), // TR
          new Point(1240, 2478), // BR
          new Point(22, 2478) // BL
        };
    assertTrue(TrapezoidSelectionView.looksAlreadyCropped(quad, w, h));
  }

  /** A clean full-image rectangle should not be flagged as degenerate. */
  @Test
  public void cleanFullImageRect_isNotFlagged() {
    int w = 2000;
    int h = 3000;
    Point[] quad =
        new Point[] {
          new Point(0, 0), new Point(w, 0), new Point(w, h), new Point(0, h)
        };
    assertFalse(TrapezoidSelectionView.looksAlreadyCropped(quad, w, h));
  }

  /** A normal interior document detection (well inside the frame) must not trigger the fallback. */
  @Test
  public void normalDocumentDetection_isNotFlagged() {
    int w = 2000;
    int h = 3000;
    Point[] quad =
        new Point[] {
          new Point(200, 300),
          new Point(1800, 320),
          new Point(1810, 2700),
          new Point(190, 2680)
        };
    assertFalse(TrapezoidSelectionView.looksAlreadyCropped(quad, w, h));
  }

  /**
   * Real-world failure variant: on a 3504×2629 pre-cropped image the detector returned a small
   * quad fully inside the image (no edge hugging) covering only ~17% of the image area. This is
   * almost always a mis-detection on an already-cropped photo and must trigger the fallback.
   */
  @Test
  public void interiorMisdetectionOnPreCroppedImage_triggersFallback() {
    int w = 3504;
    int h = 2629;
    Point[] quad =
        new Point[] {
          new Point(1256, 583),
          new Point(1718, 616),
          new Point(2219, 1950),
          new Point(1255, 2161)
        };
    assertTrue(TrapezoidSelectionView.looksAlreadyCropped(quad, w, h));
  }

  /**
   * Real-world failure variant: on a 1701×2413 pre-cropped image three corners coincidentally sit
   * near their canonical positions (TL/TR near top, BL near bottom-left), but BR is stuck mid-
   * right at (573, 2061), cutting off a large portion of the image. The heuristic must trigger.
   */
  @Test
  public void degenerateBrCornerOnPreCroppedImage_triggersFallback() {
    int w = 1701;
    int h = 2413;
    Point[] quad =
        new Point[] {
          new Point(5, 242),
          new Point(1705, 254),
          new Point(573, 2061),
          new Point(33, 2141)
        };
    assertTrue(TrapezoidSelectionView.looksAlreadyCropped(quad, w, h));
  }

  /**
   * Real-world failure variant (PDF import, 2516×3504 pre-cropped page): the detector returned
   * a quad where TL/TR sit near their canonical corners but BR and BL are far from theirs,
   * cutting off a large portion of the image to the right and bottom. The heuristic must
   * trigger so the UI falls back to the full-image rectangle.
   */
  @Test
  public void degenerateBrBlCornersOnPreCroppedPdf_triggersFallback() {
    int w = 2516;
    int h = 3504;
    Point[] quad =
        new Point[] {
          new Point(213, 288),
          new Point(2342, 78),
          new Point(1957, 2424),
          new Point(259, 2485)
        };
    assertTrue(TrapezoidSelectionView.looksAlreadyCropped(quad, w, h));
  }

  /**
   * Real-world success case (gallery photo, 3024×4032 not pre-cropped): the detector returned a
   * well-centred quad fully inside the image. Although every corner is naturally far from the
   * canonical image corners (the document does not touch the image edges), no corner hugs an
   * edge — so the heuristic must NOT trigger and the detector result should be kept.
   */
  @Test
  public void wellCentredDocumentOnPhoto_isNotFlagged() {
    int w = 3024;
    int h = 4032;
    Point[] quad =
        new Point[] {
          new Point(714.0, 1106.0),
          new Point(2865.5, 1054.5),
          new Point(2875.0, 2609.6),
          new Point(696.1, 2554.2)
        };
    assertFalse(TrapezoidSelectionView.looksAlreadyCropped(quad, w, h));
  }

  /**
   * Real-world success case (gallery photo, 3024×4032 not pre-cropped, document in landscape
   * orientation centred in a portrait image): the detector returns a well-shaped, rectangular-ish
   * quad fully inside the image (parallel-side ratios ~0.99/0.94, bbox coverage ~28%). Although
   * Trigger B's geometric criteria (all-inside + small bbox) match, the quad's plausibility check
   * (opposing sides roughly equal length) must keep the detector result and prevent the
   * full-image fallback.
   */
  @Test
  public void wellShapedInteriorDocumentOnPhoto_isNotFlagged() {
    int w = 3024;
    int h = 4032;
    Point[] quad =
        new Point[] {
          new Point(592.5, 1108.2),
          new Point(2738.4, 1030.4),
          new Point(2741.8, 2612.4),
          new Point(582.4, 2602.3)
        };
    assertFalse(TrapezoidSelectionView.looksAlreadyCropped(quad, w, h));
  }

  /**
   * Real-world failure variant (pre-cropped JPG, 701×486): the detector returned a heavily
   * pinched, triangle-like quad where the bottom side collapsed (top≈232px vs bottom≈22.9px,
   * parRatioH≈0.10) and only three of four corners sit clearly inside the image. Neither
   * Trigger A/B/C catches this because no corner hugs an image edge and not all corners are
   * "fully inside" by the strict interior margin. The new Trigger D (degenerate trapezoid via
   * tiny opposing-side ratio + small bbox coverage) must catch it and trigger the fallback.
   */
  @Test
  public void degeneratePinchedQuadOnPreCroppedImage_triggersFallback() {
    int w = 701;
    int h = 486;
    Point[] quad =
        new Point[] {
          new Point(251.4, 131.3),
          new Point(482.1, 103.6),
          new Point(301.9, 456.6),
          new Point(283.0, 443.6)
        };
    assertTrue(TrapezoidSelectionView.looksAlreadyCropped(quad, w, h));
  }

  /** Defensive: invalid inputs must return false (no fallback triggered). */
  @Test
  public void invalidInputs_returnFalse() {
    assertFalse(TrapezoidSelectionView.looksAlreadyCropped(null, 1000, 1000));
    assertFalse(TrapezoidSelectionView.looksAlreadyCropped(new Point[3], 1000, 1000));
    assertFalse(
        TrapezoidSelectionView.looksAlreadyCropped(
            new Point[] {new Point(0, 0), new Point(1, 0), new Point(1, 1), new Point(0, 1)},
            0,
            1000));
  }
}
