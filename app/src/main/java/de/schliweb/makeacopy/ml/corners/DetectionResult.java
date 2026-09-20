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

import androidx.annotation.Nullable;

/** Ergebnis der Corner-Detection in Original-Bitmap-Koordinaten (TL,TR,BR,BL). */
public final class DetectionResult {
  /** 4x2 Array: { {xTL,yTL}, {xTR,yTR}, {xBR,yBR}, {xBL,yBL} } in Original-Pixel. */
  @Nullable public final double[][] cornersOriginalTLTRBRBL;

  public final Source source;
  public final boolean success;

  // optionale Debug-Felder (können null sein)
  @Nullable public final String chosenSource;
  @Nullable public final Double penaltyMask;
  @Nullable public final Double penaltyCorners;

  /**
   * Detector confidence in [0,1], or {@code null} if the detector cannot tell. For DocQuad this is
   * the peak probability × corner/mask agreement of the model pass that produced the quad.
   */
  @Nullable public final Double confidence;

  private DetectionResult(
      boolean success,
      Source source,
      @Nullable double[][] cornersOriginalTLTRBRBL,
      @Nullable String chosenSource,
      @Nullable Double penaltyMask,
      @Nullable Double penaltyCorners,
      @Nullable Double confidence) {
    this.success = success;
    this.source = source;
    this.cornersOriginalTLTRBRBL = cornersOriginalTLTRBRBL;
    this.chosenSource = chosenSource;
    this.penaltyMask = penaltyMask;
    this.penaltyCorners = penaltyCorners;
    this.confidence = confidence;
  }

  public static DetectionResult success(Source source, double[][] cornersOriginalTLTRBRBL) {
    return new DetectionResult(true, source, cornersOriginalTLTRBRBL, null, null, null, null);
  }

  public static DetectionResult successDebug(
      Source source,
      double[][] cornersOriginalTLTRBRBL,
      @Nullable String chosenSource,
      @Nullable Double penaltyMask,
      @Nullable Double penaltyCorners) {
    return new DetectionResult(
        true, source, cornersOriginalTLTRBRBL, chosenSource, penaltyMask, penaltyCorners, null);
  }

  /** Copy of this result carrying the given detector confidence. */
  public DetectionResult withConfidence(@Nullable Double confidence) {
    return new DetectionResult(
        success,
        source,
        cornersOriginalTLTRBRBL,
        chosenSource,
        penaltyMask,
        penaltyCorners,
        confidence);
  }

  public static DetectionResult fail(Source source) {
    return new DetectionResult(false, source, null, null, null, null, null);
  }
}
