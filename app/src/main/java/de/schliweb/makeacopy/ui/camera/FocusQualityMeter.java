/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.camera;

/**
 * Pure-Java normalization and segment mapping for the live focus-quality (sharpness) indicator (see
 * {@code docs/focus_quality_indicator_design.md}).
 *
 * <p>Raw Laplacian-variance values are scene-dependent (amount of text, lighting, paper texture),
 * so they must not be displayed directly. This class converts raw values into a relative score in
 * {@code [0..1]} using a <b>decaying rolling maximum</b>:
 *
 * <ul>
 *   <li>The rolling maximum tracks the sharpest value seen for the current scene; the displayed
 *       score is {@code current / rollingMax}.
 *   <li>The maximum decays exponentially over time (half-life {@link #MAX_DECAY_HALF_LIFE_MS}), so
 *       the indicator cannot become permanently stuck at a stale peak after the scene changes (e.g.
 *       moving from a text-dense page to a sparse one).
 *   <li>A floor ({@link #VARIANCE_FLOOR}) prevents division-by-near-zero noise on featureless
 *       scenes from being reported as "sharp".
 *   <li>The resulting score is smoothed with an EMA ({@link #SCORE_EMA_ALPHA}) so the UI stays
 *       stable without sudden jumps, while still reacting to focus changes within a few frames.
 * </ul>
 *
 * <p>This class is deliberately free of Android dependencies so it can be unit-tested on the JVM.
 */
public final class FocusQualityMeter {

  /** Number of UI segments the score is mapped to. */
  public static final int SEGMENT_COUNT = 5;

  /** Minimum value of the rolling maximum; guards against near-zero division noise. */
  static final double VARIANCE_FLOOR = 5.0;

  /** Half-life of the rolling maximum decay in milliseconds (~10 s per design note). */
  static final long MAX_DECAY_HALF_LIFE_MS = 10_000L;

  /** EMA smoothing factor for the normalized score (same magnitude as the corner-score EMA). */
  static final double SCORE_EMA_ALPHA = 0.35;

  /** Coarse quality bands used for accessibility announcements. */
  public enum Band {
    LOW,
    GOOD,
    EXCELLENT
  }

  private double rollingMax = VARIANCE_FLOOR;
  private long lastUpdateTs = Long.MIN_VALUE;
  private double scoreEma = -1.0; // <0 means uninitialized

  /**
   * Feeds a new raw sharpness measurement and returns the smoothed, normalized score.
   *
   * @param rawVariance raw Laplacian variance of the current frame ROI (>= 0)
   * @param nowMs monotonic-ish timestamp in milliseconds (e.g. {@code System.currentTimeMillis()});
   *     used only for decay deltas
   * @return normalized score in {@code [0..1]}
   */
  public double update(double rawVariance, long nowMs) {
    double v = Math.max(0.0, rawVariance);
    if (lastUpdateTs != Long.MIN_VALUE && nowMs > lastUpdateTs) {
      double dt = nowMs - lastUpdateTs;
      rollingMax *= Math.pow(0.5, dt / (double) MAX_DECAY_HALF_LIFE_MS);
      if (rollingMax < VARIANCE_FLOOR) rollingMax = VARIANCE_FLOOR;
    }
    lastUpdateTs = nowMs;
    if (v > rollingMax) rollingMax = v;
    double score = v / rollingMax;
    if (score < 0.0) score = 0.0;
    if (score > 1.0) score = 1.0;
    scoreEma =
        (scoreEma < 0.0) ? score : SCORE_EMA_ALPHA * score + (1.0 - SCORE_EMA_ALPHA) * scoreEma;
    return scoreEma;
  }

  /** Resets all state (e.g. when the camera is rebound or the scene changes deliberately). */
  public void reset() {
    rollingMax = VARIANCE_FLOOR;
    lastUpdateTs = Long.MIN_VALUE;
    scoreEma = -1.0;
  }

  /** Current rolling maximum; exposed for diagnostics/tests. */
  double rollingMax() {
    return rollingMax;
  }

  /**
   * Maps a normalized score in {@code [0..1]} to the number of filled segments ({@code
   * 0..SEGMENT_COUNT}). Any non-zero score lights at least one segment so the indicator never looks
   * "off" while measuring.
   */
  public static int segmentsForScore(double score) {
    if (Double.isNaN(score) || score <= 0.0) return 0;
    if (score >= 1.0) return SEGMENT_COUNT;
    int segments = (int) Math.ceil(score * SEGMENT_COUNT);
    return Math.max(1, Math.min(SEGMENT_COUNT, segments));
  }

  /** Maps a segment count to a coarse quality band for accessibility announcements. */
  public static Band bandForSegments(int segments) {
    if (segments <= 2) return Band.LOW;
    if (segments <= 4) return Band.GOOD;
    return Band.EXCELLENT;
  }
}
