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

/**
 * Soft edge-glide for corner drags: when the pointer stays outside the left/right image edge for
 * longer than the engage delay, the corner's X locks to that edge and its Y follows the raw
 * vertical finger movement. Gliding ends once the pointer is clearly back inside the image (exit
 * hysteresis). While inside, X is never force-locked to the edge.
 */
final class EdgeGlideTracker {
  private boolean gliding; // true while the pointer glides along the left/right edge
  private boolean pending; // true while waiting for the engage delay
  private long eligibleSinceMs; // timestamp when the pointer first became eligible to engage
  private boolean lockLeft = true; // which edge is locked when gliding (true=left, false=right)
  private float glideY; // accumulated Y while locked to the edge
  private float lastRawY = Float.NaN; // last rawY for delta computation

  boolean isGliding() {
    return gliding;
  }

  /** Call when a corner drag starts. */
  void start(float rawY, float cornerY) {
    gliding = false;
    pending = false;
    eligibleSinceMs = 0L;
    lastRawY = rawY;
    glideY = cornerY;
  }

  /** Call when the touch ends. */
  void reset() {
    gliding = false;
    pending = false;
    eligibleSinceMs = 0L;
    lastRawY = Float.NaN;
  }

  /**
   * Processes one move of the dragged corner.
   *
   * @param x pointer X in the same frame as {@code imgLeft}/{@code imgRight}
   * @param rawY raw screen Y of the pointer
   * @param enterEps how far outside the image the pointer must be to become eligible
   * @param exitEps how far inside the image the pointer must be to stop gliding
   * @param cornerY current Y of the dragged corner (start value when gliding engages)
   * @param target in/out: the corner's target position; overwritten while gliding
   */
  void onMove(
      float x,
      float rawY,
      float imgLeft,
      float imgRight,
      float enterEps,
      float exitEps,
      long nowMs,
      int engageDelayMs,
      float cornerY,
      float[] target) {
    if (gliding) {
      // While gliding, keep X locked to the chosen edge and accumulate Y by rawY deltas
      if (!Float.isNaN(lastRawY)) glideY += rawY - lastRawY;
      lastRawY = rawY;
      target[0] = lockLeft ? imgLeft : imgRight;
      target[1] = glideY;
      // Exit gliding when clearly back inside (beyond exit hysteresis)
      if (x >= imgLeft + exitEps && x <= imgRight - exitEps) {
        gliding = false;
        pending = false;
        eligibleSinceMs = 0L;
      }
      return;
    }

    // Not yet gliding. Consider eligibility only if really outside.
    boolean outsideLeft = x < imgLeft - enterEps;
    boolean outsideRight = x > imgRight + enterEps;
    if (!outsideLeft && !outsideRight) {
      // No longer eligible -> cancel pending
      pending = false;
      eligibleSinceMs = 0L;
      return;
    }
    // Start or continue pending timer
    if (!pending) {
      pending = true;
      eligibleSinceMs = nowMs;
      lockLeft = outsideLeft; // remember which edge we will lock to
    }
    // Engage after delay and lock immediately
    if (nowMs - eligibleSinceMs >= engageDelayMs) {
      gliding = true;
      pending = false;
      glideY = cornerY; // start from current handle y
      lastRawY = rawY;
      target[0] = lockLeft ? imgLeft : imgRight;
      target[1] = glideY;
    }
  }
}
