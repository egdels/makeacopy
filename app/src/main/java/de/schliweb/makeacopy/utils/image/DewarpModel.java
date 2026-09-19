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

import org.opencv.core.Point;

/**
 * Cylindrical page-distortion model for dewarping curved book pages (issue #91, Phase 1).
 *
 * <p>The model consists of the four selection corners (TL, TR, BR, BL — same convention as the
 * perspective pipeline) plus one quadratic Bezier control point per horizontal edge. The top edge
 * is the curve {@code B(t; TL, topControl, TR)}, the bottom edge is {@code B(t; BL, bottomControl,
 * BR)}. The side edges remain straight, which corresponds to a cylindrical page surface whose
 * generatrices are (approximately) parallel to the book spine.
 *
 * <p>Optionally, each horizontal edge can carry a sampled offset PROFILE (issue #91 follow-up:
 * corner accuracy). A profile is an array of normalized offsets (fraction of the chord length,
 * positive along the chord direction rotated by +90° in image coordinates), sampled uniformly in
 * the chord parameter {@code t}. When present, {@link #topAt}/{@link #bottomAt} evaluate
 * chord-point + normal·chordLen·profile(t) instead of the quadratic Bezier, which captures edge
 * shapes the single-curvature Bezier cannot (e.g. sine-like bends whose corner slope differs from a
 * parabola's). Profiles are rescaled so their midpoint matches the Bezier sagitta, so dragging the
 * on-curve midpoint handle in the UI still adjusts the overall curve strength.
 *
 * <p>All points are expected to be in the same coordinate space (typically full-resolution image
 * pixels). Instances are immutable value holders; the actual dewarping is performed by {@link
 * OpenCVUtils#applyDewarp}.
 */
public final class DewarpModel {
  /** Maximum absolute value of the {@link #depth} parameter. */
  public static final double MAX_DEPTH = 1.0;

  /** Selection corners in the order top-left, top-right, bottom-right, bottom-left. */
  private final Point[] corners;

  /** Quadratic Bezier control point of the top edge (curve from TL to TR). */
  private final Point topControl;

  /** Quadratic Bezier control point of the bottom edge (curve from BL to BR). */
  private final Point bottomControl;

  /**
   * Perspective-depth fine adjustment in {@code [-1, 1]} (issue #91, Phase 3). {@code 0} keeps the
   * linear ruled-surface blend between the two edge curves. Positive values shift the intermediate
   * generatrix sampling towards the bottom curve (compressing the output near the bottom edge),
   * negative values towards the top curve — analogous to the "depth perception" slider in
   * ScanTailor. See {@link #blendWeight(double)}.
   */
  private final double depth;

  /**
   * Optional sampled offset profile of the top edge (normalized by the chord length, uniform in
   * {@code t}); {@code null} = use the quadratic Bezier. See class docs for the sign convention.
   */
  private final double[] topProfile;

  /** Optional sampled offset profile of the bottom edge; {@code null} = quadratic Bezier. */
  private final double[] bottomProfile;

  private DewarpModel(
      Point[] corners,
      Point topControl,
      Point bottomControl,
      double depth,
      double[] topProfile,
      double[] bottomProfile) {
    this.corners = corners;
    this.topControl = topControl;
    this.bottomControl = bottomControl;
    this.depth = depth;
    this.topProfile = topProfile;
    this.bottomProfile = bottomProfile;
  }

  /**
   * Creates a model from two points that lie ON the curves at {@code t = 0.5} (the draggable curve
   * handles shown in the crop UI). For a quadratic Bezier, {@code B(0.5) = 0.25*P0 + 0.5*C +
   * 0.25*P2}, so the control point is recovered as {@code C = 2*M - 0.5*(P0 + P2)}.
   *
   * @param corners four corners (TL, TR, BR, BL); must be non-null and contain four non-null points
   * @param topMid point on the top curve at t=0.5
   * @param bottomMid point on the bottom curve at t=0.5
   * @return the model, or {@code null} when the input is invalid
   */
  public static DewarpModel fromOnCurveMidpoints(Point[] corners, Point topMid, Point bottomMid) {
    if (corners == null || corners.length != 4 || topMid == null || bottomMid == null) return null;
    for (Point p : corners) if (p == null) return null;
    Point tl = corners[0], tr = corners[1], br = corners[2], bl = corners[3];
    Point topControl =
        new Point(2.0 * topMid.x - 0.5 * (tl.x + tr.x), 2.0 * topMid.y - 0.5 * (tl.y + tr.y));
    Point bottomControl =
        new Point(2.0 * bottomMid.x - 0.5 * (bl.x + br.x), 2.0 * bottomMid.y - 0.5 * (bl.y + br.y));
    Point[] copy =
        new Point[] {
          new Point(tl.x, tl.y), new Point(tr.x, tr.y), new Point(br.x, br.y), new Point(bl.x, bl.y)
        };
    return new DewarpModel(copy, topControl, bottomControl, 0.0, null, null);
  }

  /**
   * Returns a copy of this model with the given perspective-depth parameter (issue #91, Phase 3).
   * The value is clamped to {@code ±}{@link #MAX_DEPTH}; non-finite input is treated as {@code 0}.
   *
   * @param depth depth adjustment in {@code [-1, 1]}; {@code 0} = neutral (linear blend)
   * @return a new model instance sharing this model's geometry
   */
  public DewarpModel withDepth(double depth) {
    if (!Double.isFinite(depth)) depth = 0.0;
    depth = Math.max(-MAX_DEPTH, Math.min(MAX_DEPTH, depth));
    return new DewarpModel(corners, topControl, bottomControl, depth, topProfile, bottomProfile);
  }

  /**
   * Returns a copy of this model with the given sampled edge offset profiles (issue #91 follow-up:
   * corner accuracy). Each profile is an array of chord-normalized offsets sampled uniformly in
   * {@code t} (same sign convention as the Bezier sagitta). A profile is rescaled so that its value
   * at {@code t = 0.5} matches this model's current Bezier midpoint offset — i.e. the traced edge
   * SHAPE is kept while the user's curve-handle position still controls the overall strength. A
   * profile is ignored (Bezier kept) when it is {@code null}, too short, non-finite, or its
   * midpoint value is too small to scale reliably.
   *
   * @param topProfile offset profile of the top edge, or {@code null} to keep the Bezier
   * @param bottomProfile offset profile of the bottom edge, or {@code null} to keep the Bezier
   * @return a new model instance sharing this model's corners, controls and depth
   */
  public DewarpModel withEdgeProfiles(double[] topProfile, double[] bottomProfile) {
    double[] top = prepareProfile(topProfile, chordOffsetFrac(true));
    double[] bottom = prepareProfile(bottomProfile, chordOffsetFrac(false));
    return new DewarpModel(corners, topControl, bottomControl, depth, top, bottom);
  }

  /**
   * Validates and rescales a profile so its midpoint value equals {@code targetMidFrac}; returns
   * {@code null} when the profile is unusable (then the Bezier is kept).
   */
  private static double[] prepareProfile(double[] profile, double targetMidFrac) {
    if (profile == null || profile.length < 3) return null;
    for (double d : profile) if (!Double.isFinite(d)) return null;
    double mid = sampleProfile(profile, 0.5);
    if (Math.abs(mid) < 1e-4) return null;
    double scale = targetMidFrac / mid;
    if (!Double.isFinite(scale)) return null;
    double[] scaled = new double[profile.length];
    for (int i = 0; i < profile.length; i++) scaled[i] = profile[i] * scale;
    return scaled;
  }

  /**
   * Signed chord-normalized offset of the Bezier on-curve midpoint from the straight chord midpoint
   * (positive along the chord direction rotated by +90° in image coordinates).
   */
  private double chordOffsetFrac(boolean top) {
    Point p0 = top ? corners[0] : corners[3];
    Point p2 = top ? corners[1] : corners[2];
    Point mid = quadBezier(p0, top ? topControl : bottomControl, p2, 0.5);
    double dx = p2.x - p0.x, dy = p2.y - p0.y;
    double len = Math.hypot(dx, dy);
    if (len < 1e-9) return 0;
    // Normal = chord direction rotated by +90° in image coords: (x, y) -> (-y, x).
    double nx = -dy / len, ny = dx / len;
    double mx = mid.x - 0.5 * (p0.x + p2.x), my = mid.y - 0.5 * (p0.y + p2.y);
    return (mx * nx + my * ny) / len;
  }

  /** Linearly interpolates a uniformly sampled profile at parameter {@code t} in {@code [0, 1]}. */
  public static double sampleProfile(double[] profile, double t) {
    int n = profile.length - 1;
    double pos = Math.max(0.0, Math.min(1.0, t)) * n;
    int i = (int) Math.floor(pos);
    if (i >= n) return profile[n];
    double f = pos - i;
    return profile[i] * (1.0 - f) + profile[i + 1] * f;
  }

  /** Returns the perspective-depth parameter in {@code [-1, 1]} ({@code 0} = neutral). */
  public double getDepth() {
    return depth;
  }

  /**
   * Maps the normalized output row {@code v} in {@code [0, 1]} to the blend weight between the top
   * ({@code 0}) and bottom ({@code 1}) edge curve, applying the {@link #depth} adjustment as a
   * smooth monotonic reparameterization: {@code w(v) = v + depth * v * (1 - v)}. For {@code depth
   * == 0} this is the identity (linear ruled surface); the endpoints are always fixed, so the edge
   * curves themselves are unaffected.
   *
   * @param v normalized output row in {@code [0, 1]}
   * @return blend weight in {@code [0, 1]}
   */
  public double blendWeight(double v) {
    double w = v + depth * v * (1.0 - v);
    return Math.max(0.0, Math.min(1.0, w));
  }

  /** Returns the four corners (TL, TR, BR, BL). The array must not be modified by callers. */
  public Point[] getCorners() {
    return corners;
  }

  /** Evaluates the top edge curve at parameter {@code t} in {@code [0, 1]} (TL → TR). */
  public Point topAt(double t) {
    if (topProfile != null) return profileAt(corners[0], corners[1], topProfile, t);
    return quadBezier(corners[0], topControl, corners[1], t);
  }

  /** Evaluates the bottom edge curve at parameter {@code t} in {@code [0, 1]} (BL → BR). */
  public Point bottomAt(double t) {
    if (bottomProfile != null) return profileAt(corners[3], corners[2], bottomProfile, t);
    return quadBezier(corners[3], bottomControl, corners[2], t);
  }

  /**
   * Evaluates a profiled edge: chord point at {@code t} plus profile offset along the +90° normal.
   */
  private static Point profileAt(Point p0, Point p2, double[] profile, double t) {
    double dx = p2.x - p0.x, dy = p2.y - p0.y;
    double len = Math.hypot(dx, dy);
    double off = sampleProfile(profile, t) * len;
    double nx = len < 1e-9 ? 0 : -dy / len;
    double ny = len < 1e-9 ? 0 : dx / len;
    return new Point(p0.x + t * dx + nx * off, p0.y + t * dy + ny * off);
  }

  /**
   * Returns the maximum sagitta (deviation of the on-curve midpoint from the straight chord
   * midpoint) of the two horizontal edges, in the model's coordinate units.
   */
  public double maxSagitta() {
    return Math.max(
        distance(topAt(0.5), midpoint(corners[0], corners[1])),
        distance(bottomAt(0.5), midpoint(corners[3], corners[2])));
  }

  /**
   * Returns {@code true} when both curves deviate from their straight chords by less than {@code
   * tolerancePx}; in that case a plain perspective warp is equivalent (and cheaper).
   */
  public boolean isEffectivelyStraight(double tolerancePx) {
    return maxSagitta() < tolerancePx;
  }

  private static Point quadBezier(Point p0, Point c, Point p2, double t) {
    double omt = 1.0 - t;
    double a = omt * omt, b = 2.0 * omt * t, d = t * t;
    return new Point(a * p0.x + b * c.x + d * p2.x, a * p0.y + b * c.y + d * p2.y);
  }

  private static Point midpoint(Point a, Point b) {
    return new Point(0.5 * (a.x + b.x), 0.5 * (a.y + b.y));
  }

  private static double distance(Point a, Point b) {
    return Math.hypot(a.x - b.x, a.y - b.y);
  }
}
