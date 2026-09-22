/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.image;

/**
 * The user-facing dewarp settings of an accepted crop (issue #91): everything the crop screen needs
 * besides the four corners and the user rotation to rebuild a curved selection exactly. Kept next
 * to the accepted corners so a Re-Edit from the Export screen restores the curved shape instead of
 * silently falling back to a straight trapezoid.
 *
 * <p>All offsets are chord-relative fractions in the convention of {@code
 * TrapezoidSelectionView#setCurveOffsetFractions}: they depend only on the corner order, so they
 * survive the user rotation like the corners themselves. The profiles are the sampled edge shapes
 * from the automatic curve estimation ({@code OpenCVUtils.estimateDewarpEdgeProfiles}) and may be
 * {@code null}; they are stored so a Re-Edit does not have to re-run the estimation, which could
 * return a different shape than the one the page was actually cropped with.
 *
 * @param topOffsetFrac signed perpendicular offset of the top curve handle (corners 0→1)
 * @param bottomOffsetFrac signed perpendicular offset of the bottom curve handle (corners 3→2)
 * @param topTangentFrac signed tangential offset of the top curve handle
 * @param bottomTangentFrac signed tangential offset of the bottom curve handle
 * @param depth perspective-depth fine adjustment in {@code [-1, 1]}, {@code 0} = neutral
 * @param topProfile chord-normalized offset profile of the top edge, or {@code null}
 * @param bottomProfile chord-normalized offset profile of the bottom edge, or {@code null}
 */
public record DewarpState(
    double topOffsetFrac,
    double bottomOffsetFrac,
    double topTangentFrac,
    double bottomTangentFrac,
    double depth,
    double[] topProfile,
    double[] bottomProfile) {

  /** Defensive copies: the arrays come from and go to mutable callers. */
  public DewarpState {
    topProfile = copy(topProfile);
    bottomProfile = copy(bottomProfile);
  }

  @Override
  public double[] topProfile() {
    return copy(topProfile);
  }

  @Override
  public double[] bottomProfile() {
    return copy(bottomProfile);
  }

  /** Whether every numeric component is finite, i.e. the state can be applied and stored. */
  public boolean isFinite() {
    return Double.isFinite(topOffsetFrac)
        && Double.isFinite(bottomOffsetFrac)
        && Double.isFinite(topTangentFrac)
        && Double.isFinite(bottomTangentFrac)
        && Double.isFinite(depth)
        && allFinite(topProfile)
        && allFinite(bottomProfile);
  }

  private static boolean allFinite(double[] values) {
    if (values == null) return true;
    for (double v : values) if (!Double.isFinite(v)) return false;
    return true;
  }

  private static double[] copy(double[] values) {
    return values == null ? null : values.clone();
  }
}
