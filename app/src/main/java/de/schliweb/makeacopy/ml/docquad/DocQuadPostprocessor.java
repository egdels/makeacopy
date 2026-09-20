/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ml.docquad;

import lombok.experimental.UtilityClass;

/**
 * Minimal, deterministic postprocessor for DocQuadNet-256.
 *
 * <p>Specification (M6a): - Corner peaks: Argmax per channel in {@code corner_heatmaps} [1,4,64,64]
 * - Peak coordinates (64-space) → 256-space: (i + 0.5) * 4.0 - Mask statistics from {@code
 * mask_logits} [1,1,64,64] via sigmoid
 */
@UtilityClass
public class DocQuadPostprocessor {

  public enum ChosenSource {
    CORNERS,
    MASK,
  }

  public enum PeakMode {
    ARGMAX,
    REFINE_3X3,
    /**
     * 5×5 weighted-centroid + parabolic 1D-fit subpixel refinement.
     *
     * <p>Provides ~0.5–1.0 px (in 64-grid) better localization than {@link #REFINE_3X3} when the
     * heatmap peak is well-formed but slightly off-center within its argmax bin. Falls back to a
     * 3×3 weighted centroid when the parabolic fit is degenerate (flat/saddle window).
     */
    REFINE_5X5_QUADRATIC,
  }

  public static Result postprocess(DocQuadOrtRunner.Outputs out) {
    if (out == null) {
      throw new IllegalArgumentException("out must not be null");
    }
    return postprocess(out.cornerHeatmaps(), out.maskLogits(), null, PeakMode.ARGMAX);
  }

  public static Result postprocess(DocQuadOrtRunner.Outputs out, PeakMode peakMode) {
    if (out == null) {
      throw new IllegalArgumentException("out must not be null");
    }
    return postprocess(out.cornerHeatmaps(), out.maskLogits(), null, peakMode);
  }

  public static Result postprocess(DocQuadOrtRunner.Outputs out, DocQuadLetterbox lb) {
    if (out == null) {
      throw new IllegalArgumentException("out must not be null");
    }
    return postprocess(out.cornerHeatmaps(), out.maskLogits(), lb, PeakMode.ARGMAX);
  }

  public static Result postprocess(
      DocQuadOrtRunner.Outputs out, DocQuadLetterbox lb, PeakMode peakMode) {
    if (out == null) {
      throw new IllegalArgumentException("out must not be null");
    }
    return postprocess(out.cornerHeatmaps(), out.maskLogits(), lb, peakMode);
  }

  public static Result postprocess(
      float[][][][] cornerHeatmaps, float[][][][] maskLogits, DocQuadLetterbox lb) {
    return postprocess(cornerHeatmaps, maskLogits, lb, PeakMode.ARGMAX);
  }

  public static Result postprocess(
      float[][][][] cornerHeatmaps,
      float[][][][] maskLogits,
      DocQuadLetterbox lb,
      PeakMode peakMode) {
    if (peakMode == null) {
      throw new IllegalArgumentException("peakMode must not be null");
    }

    double[][] corners256 = corners64ToCorners256(cornerHeatmaps, peakMode);
    MaskStats ms = computeMaskStats(maskLogits);

    QuadFromMask qm = quadFromMask256(maskLogits, corners256);

    PathChoice pc = choosePath(corners256, qm.quad256, qm.usedFallback, maskLogits);
    double[][] chosenQuad256 = pc.chosenQuad256;
    ChosenSource chosenSource = pc.chosenSource;
    double penaltyCorners = pc.penaltyCorners;
    double penaltyMask = pc.penaltyMask;

    double[][] cornersOriginal = null;
    double[][] quadOriginal = null;
    double[][] chosenOriginal = null;
    if (lb != null) {
      cornersOriginal = mapCorners256ToOriginal(corners256, lb);
      quadOriginal = mapCorners256ToOriginal(qm.quad256, lb);
      chosenOriginal = (chosenSource == ChosenSource.MASK) ? quadOriginal : cornersOriginal;
    }
    // Evidence-based product guardrails for suspicious detection
    String suspiciousReason = evaluateSuspicious(cornerHeatmaps, ms, qm, pc, peakMode);
    boolean suspiciousForProduct = suspiciousReason != null;

    return new Result(
        corners256,
        cornersOriginal,
        ms.maskProbGt05Count,
        ms.maskProbMean,
        qm.quad256,
        quadOriginal,
        qm.usedFallback,
        chosenQuad256,
        chosenOriginal,
        chosenSource,
        penaltyCorners,
        penaltyMask,
        suspiciousForProduct,
        suspiciousReason,
        minPeakProb(cornerHeatmaps),
        cornerMaskIou(corners256, maskLogits));
  }

  /**
   * Smallest peak probability ({@code sigmoid(max logit)}) over the four corner heatmaps. Confident
   * detections are close to 1; when the model has not found the document (strong tilt, low
   * contrast) at least one channel stays near 0.
   */
  public static double minPeakProb(float[][][][] cornerHeatmaps) {
    requireShapeCorners(cornerHeatmaps);
    double min = 1.0;
    for (int c = 0; c < 4; c++) {
      float best = -Float.MAX_VALUE;
      for (float[] row : cornerHeatmaps[0][c]) {
        for (float v : row) {
          if (v > best) best = v;
        }
      }
      min = Math.min(min, sigmoid(best));
    }
    return min;
  }

  /**
   * IoU on the 64×64 grid between the quad spanned by the heatmap corners and the segmentation mask
   * ({@code sigmoid(logit) > 0.5}). The two heads are predicted independently, so a high agreement
   * is strong evidence that the corners belong to a real document; it is a far better selector
   * between alternative detections than the peak probability alone.
   */
  public static double cornerMaskIou(double[][] corners256, float[][][][] maskLogits) {
    requireShapeMask(maskLogits);
    double[][] quad64 = new double[4][2];
    for (int i = 0; i < 4; i++) {
      quad64[i][0] = corners256[i][0] / 4.0;
      quad64[i][1] = corners256[i][1] / 4.0;
    }
    float[][] m = maskLogits[0][0];
    int inter = 0;
    int union = 0;
    // Scanline rasterization (even-odd rule at the cell centres): this runs for every analysed
    // frame, a point-in-polygon test per cell would cost several milliseconds.
    double[] xs = new double[4];
    for (int y = 0; y < 64; y++) {
      double cy = y + 0.5;
      int n = 0;
      for (int i = 0; i < 4; i++) {
        double[] a = quad64[i];
        double[] b = quad64[(i + 1) % 4];
        if ((a[1] > cy) != (b[1] > cy)) {
          xs[n++] = a[0] + (cy - a[1]) * (b[0] - a[0]) / (b[1] - a[1]);
        }
      }
      java.util.Arrays.sort(xs, 0, n);
      float[] row = m[y];
      int k = 0;
      boolean inside = false;
      for (int x = 0; x < 64; x++) {
        double cx = x + 0.5;
        while (k < n && xs[k] <= cx) {
          inside = !inside;
          k++;
        }
        boolean inMask = row[x] > 0.0f;
        if (inside && inMask) inter++;
        if (inside || inMask) union++;
      }
    }
    return union == 0 ? 0.0 : (double) inter / (double) union;
  }

  /**
   * Minimum number of standard deviations the peak must be above the mean for each corner heatmap.
   * Below this threshold, the heatmap is considered diffuse and the model uncertain about corner
   * placement. A well-peaked heatmap has a peak many σ above the mean.
   */
  private static final double PEAK_SIGMA_THRESHOLD = 5.0;

  /**
   * Maximum allowed mask probability mean for a non-diffuse mask. A high mean with low area
   * indicates the mask is spread across the image.
   */
  private static final double MASK_DIFFUSE_MEAN_THRESHOLD = 0.45;

  /** Minimum mask area (pixels with prob > 0.5) for a valid document detection. */
  private static final int MASK_DIFFUSE_MIN_AREA = 100;

  /** Maximum geometry penalty before the result is considered implausible. */
  private static final double GEOMETRY_IMPLAUSIBLE_THRESHOLD = 1e4;

  /**
   * Evaluates evidence-based guardrails to determine if the detection result is suspicious.
   *
   * @return a reason string if suspicious, or null if the result appears reliable
   */
  @SuppressWarnings("UnusedVariable")
  private static String evaluateSuspicious(
      float[][][][] cornerHeatmaps,
      MaskStats ms,
      QuadFromMask qm,
      PathChoice pc,
      PeakMode peakMode) {

    // Rule D: LOW_PEAK_MARGIN - check if corner heatmap peaks are ambiguous
    if (hasLowPeakMargin(cornerHeatmaps)) {
      return "LOW_PEAK_MARGIN";
    }

    // Rule E: MASK_DIFFUSE - mask probability is spread without clear document region
    if (ms.maskProbMean > MASK_DIFFUSE_MEAN_THRESHOLD
        && ms.maskProbGt05Count < MASK_DIFFUSE_MIN_AREA) {
      return "MASK_DIFFUSE";
    }

    // Rule F: CHOSEN_MASK_INCONSISTENT - mask fallback was used and corners have high penalty
    if (qm.usedFallback && pc.penaltyCorners > GEOMETRY_IMPLAUSIBLE_THRESHOLD) {
      return "MASK_FALLBACK_AND_PCORNER";
    }

    // DISAGREE_64PX - corners and mask quads disagree significantly (> 64px in 256-space)
    if (!qm.usedFallback) {
      double maxDist = maxCornerDistance(pc.chosenQuad256, qm.quad256);
      if (pc.chosenSource == ChosenSource.CORNERS && maxDist > 64.0) {
        return "DISAGREE_64PX";
      }
    }

    // GEOMETRY_IMPLAUSIBLE - chosen quad has severe geometric issues
    double chosenPenalty =
        (pc.chosenSource == ChosenSource.MASK) ? pc.penaltyMask : pc.penaltyCorners;
    if (chosenPenalty >= GEOMETRY_IMPLAUSIBLE_THRESHOLD) {
      return "GEOMETRY_IMPLAUSIBLE";
    }

    return null;
  }

  /**
   * Checks if any corner heatmap has a low peak-to-mean ratio. A low ratio indicates the model is
   * uncertain about corner placement (the heatmap is diffuse rather than sharply peaked).
   */
  private static boolean hasLowPeakMargin(float[][][][] cornerHeatmaps) {
    for (int c = 0; c < 4; c++) {
      float[][] hm = cornerHeatmaps[0][c];
      float best = -Float.MAX_VALUE;
      double sum = 0.0;
      int n = 0;
      for (int y = 0; y < 64; y++) {
        for (int x = 0; x < 64; x++) {
          float v = hm[y][x];
          sum += v;
          n++;
          if (v > best) {
            best = v;
          }
        }
      }
      double mean = sum / Math.max(n, 1);
      double sumSq = 0.0;
      for (int y = 0; y < 64; y++) {
        for (int x = 0; x < 64; x++) {
          double d = hm[y][x] - mean;
          sumSq += d * d;
        }
      }
      double std = Math.sqrt(sumSq / Math.max(n, 1));
      // If the peak is not many σ above the mean, the heatmap is diffuse
      if (std > 1e-6 && (best - mean) / std < PEAK_SIGMA_THRESHOLD) {
        return true;
      }
    }
    return false;
  }

  /**
   * Hard penalty threshold: If MASK quad has penalty >= this value, always fall back to CORNERS.
   * This prevents choosing MASK quads with severe geometric issues (OOB > 16px, self-intersecting,
   * non-convex, degenerate area) even if CORNERS has higher total penalty due to mask disagreement.
   * Value 1e5 corresponds to the penalty added when oobMax > hard (16px) in quadPenaltyGeometry.
   */
  private static final double HARD_PENALTY_THRESHOLD = 1e5;

  /**
   * Agreement threshold: Maximum allowed corner distance (in 256-space pixels) between CORNERS and
   * MASK quads. If any corner differs by more than this, MASK is considered unreliable and CORNERS
   * is preferred. This guards against distribution shift in the mask head causing poor MASK
   * predictions while CORNERS remains accurate. Value 32px in 256-space corresponds to ~12.5% of
   * the frame diagonal.
   */
  private static final double AGREEMENT_MAX_CORNER_DIST = 32.0;

  /**
   * Score margin: MASK must have penalty at least this much lower than CORNERS geometry-only
   * penalty to be chosen. This prevents MASK from winning solely due to mask disagreement penalty
   * on CORNERS when both quads are geometrically similar. Value 50.0 corresponds to ~5 grid cells
   * of disagreement (5 * 10.0).
   */
  private static final double MASK_SCORE_MARGIN = 50.0;

  static PathChoice choosePath(
      double[][] quadCorners256,
      double[][] quadFromMask256,
      boolean quadFromMaskUsedFallback,
      float[][][][] maskLogits) {
    requireShapeMask(maskLogits);

    // Compute geometry-only penalty for CORNERS (without mask disagreement)
    double pAGeom = quadPenaltyGeometry(quadCorners256);
    // Full penalty for CORNERS includes mask disagreement
    double pA = pAGeom + maskDisagreementPenaltyForCorners(quadCorners256, maskLogits);

    if (quadFromMaskUsedFallback) {
      return new PathChoice(quadCorners256, ChosenSource.CORNERS, pA, Double.POSITIVE_INFINITY);
    }

    double pB = quadPenaltyGeometry(quadFromMask256);

    // Bidirectional hard penalty fallback:
    // 1. If CORNERS has severe geometric issues AND MASK is valid → prefer MASK
    // 2. If MASK has severe geometric issues → prefer CORNERS
    // This reduces FAIL rate by using the geometrically valid quad when available.
    if (pAGeom >= HARD_PENALTY_THRESHOLD && pB < HARD_PENALTY_THRESHOLD) {
      return new PathChoice(quadFromMask256, ChosenSource.MASK, pA, pB);
    }
    if (pB >= HARD_PENALTY_THRESHOLD) {
      return new PathChoice(quadCorners256, ChosenSource.CORNERS, pA, pB);
    }

    // Guardrail 1: Agreement check - if MASK and CORNERS disagree significantly,
    // prefer CORNERS (MASK prediction is unreliable due to distribution shift)
    double maxCornerDist = maxCornerDistance(quadCorners256, quadFromMask256);
    if (maxCornerDist > AGREEMENT_MAX_CORNER_DIST) {
      return new PathChoice(quadCorners256, ChosenSource.CORNERS, pA, pB);
    }

    // Guardrail 2: Score margin - MASK must be clearly better than CORNERS geometry
    // to overcome the inherent uncertainty in mask-based quad extraction
    if (pB < pAGeom - MASK_SCORE_MARGIN) {
      return new PathChoice(quadFromMask256, ChosenSource.MASK, pA, pB);
    }

    // Default: prefer CORNERS (more reliable corner detection)
    return new PathChoice(quadCorners256, ChosenSource.CORNERS, pA, pB);
  }

  /** Compute maximum Euclidean distance between corresponding corners of two quads. */
  private static double maxCornerDistance(double[][] quad1, double[][] quad2) {
    if (quad1 == null || quad2 == null || quad1.length != 4 || quad2.length != 4) {
      return Double.MAX_VALUE;
    }
    double maxDist = 0.0;
    for (int i = 0; i < 4; i++) {
      double dx = quad1[i][0] - quad2[i][0];
      double dy = quad1[i][1] - quad2[i][1];
      double dist = Math.sqrt(dx * dx + dy * dy);
      if (dist > maxDist) {
        maxDist = dist;
      }
    }
    return maxDist;
  }

  private static double quadPenaltyGeometry(double[][] quad256) {
    if (quad256 == null || quad256.length != 4) {
      return 1e6;
    }
    for (int i = 0; i < 4; i++) {
      if (quad256[i] == null || quad256[i].length != 2) {
        return 1e6;
      }
      if (!Double.isFinite(quad256[i][0]) || !Double.isFinite(quad256[i][1])) {
        return 1e6;
      }
    }

    double penalty = 0.0;

    // Bounds penalty in 256-space (frame 0..255) with small tolerance.
    // Goal: extreme OOB outliers (e.g., -700) must never pass with penalty=0.
    final double w = 256.0;
    final double h = 256.0;
    final double tol = 2.0;
    final double hard = 16.0;
    final double kSoft = 10.0;
    final double kHard = 1000.0;

    double oobSum = DocQuadScore.oobSum(quad256, w, h, tol);
    if (oobSum > 0.0) {
      penalty += oobSum * kSoft;
    }
    double oobMax = DocQuadScore.oobMax(quad256, w, h, tol);
    if (oobMax > hard) {
      penalty += 1e5 + (oobMax - hard) * kHard;
    }

    if (DocQuadScore.selfIntersects(quad256)) {
      penalty += 1e6;
    }
    if (!DocQuadScore.isConvex(quad256)) {
      penalty += 1e6;
    }
    double areaAbs = DocQuadScore.areaAbs(quad256);
    if (!(areaAbs > 1.0)) {
      penalty += 1e6;
    }

    double edgeMin = DocQuadScore.edgeLengthMin(quad256);
    double edgeMax = DocQuadScore.edgeLengthMax(quad256);

    if (edgeMin < 8.0) {
      penalty += (8.0 - edgeMin) * 1000.0;
    }
    double r = edgeMax / Math.max(edgeMin, 1e-9);
    if (r > 25.0) {
      penalty += (r - 25.0) * 100.0;
    }
    return penalty;
  }

  private static double maskDisagreementPenaltyForCorners(
      double[][] quadCorners256, float[][][][] maskLogits) {
    // Quad256 -> Quad64
    double[][] quad64 = new double[4][2];
    for (int i = 0; i < 4; i++) {
      quad64[i][0] = quadCorners256[i][0] / 4.0;
      quad64[i][1] = quadCorners256[i][1] / 4.0;
    }

    int[] grid = new int[] {0, 8, 16, 24, 32, 40, 48, 56};
    int disagree = 0;
    float[][] m = maskLogits[0][0];

    for (int gy : grid) {
      for (int gx : grid) {
        double px = gx + 0.5;
        double py = gy + 0.5;
        boolean inQuad = pointInPolyInclusive(quad64, px, py);

        double prob = sigmoid(m[gy][gx]);
        boolean inMask = prob > 0.5;

        if (inQuad != inMask) {
          disagree++;
        }
      }
    }
    return (double) disagree * 10.0;
  }

  private static boolean pointInPolyInclusive(double[][] poly, double px, double py) {
    // 1) Edge-inclusive: point lies on an edge
    for (int i = 0; i < 4; i++) {
      int j = (i + 1) % 4;
      if (onSegment(poly[i][0], poly[i][1], poly[j][0], poly[j][1], px, py, 1e-9)) {
        return true;
      }
    }

    // 2) Ray casting (right)
    boolean inside = false;
    for (int i = 0, j = 3; i < 4; j = i++) {
      double xi = poly[i][0];
      double yi = poly[i][1];
      double xj = poly[j][0];
      double yj = poly[j][1];

      boolean intersect = ((yi > py) != (yj > py)) && (px < (xj - xi) * (py - yi) / (yj - yi) + xi);
      if (intersect) {
        inside = !inside;
      }
    }
    return inside;
  }

  private static double orient(double ax, double ay, double bx, double by, double cx, double cy) {
    return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
  }

  private static boolean onSegment(
      double ax, double ay, double bx, double by, double px, double py, double eps) {
    if (Math.abs(orient(ax, ay, bx, by, px, py)) > eps) {
      return false;
    }
    return (Math.min(ax, bx) - eps <= px && px <= Math.max(ax, bx) + eps)
        && (Math.min(ay, by) - eps <= py && py <= Math.max(ay, by) + eps);
  }

  /**
   * Minimal Mask→Quad path (M6c): - Binary mask: {@code sigmoid(logit) > 0.5} (strict), 64×64 -
   * Point cloud from pixel centers (x+0.5, y+0.5) - Oriented rectangle via PCA (v1/v2) + u/v
   * extrema - Canonicalization: centroid-angle sort (atan2), rotate-to-TL (min(x+y)) - Mapping
   * 64→256 via *4.0
   *
   * <p>Fallback (minimal, deterministic): for empty/degenerate mask, {@code fallbackCorners256} is
   * returned.
   */
  public static QuadFromMask quadFromMask256(
      float[][][][] maskLogits, double[][] fallbackCorners256) {
    requireShapeMask(maskLogits);
    if (fallbackCorners256 == null || fallbackCorners256.length != 4) {
      throw new IllegalArgumentException("fallbackCorners256 must be double[4][2]");
    }
    for (int i = 0; i < 4; i++) {
      if (fallbackCorners256[i] == null || fallbackCorners256[i].length != 2) {
        throw new IllegalArgumentException("fallbackCorners256 must be double[4][2]");
      }
    }

    float[][] m = maskLogits[0][0];

    int maskCount = 0;
    double sumX = 0.0;
    double sumY = 0.0;

    for (int y = 0; y < 64; y++) {
      float[] row = m[y];
      for (int x = 0; x < 64; x++) {
        double prob = sigmoid(row[x]);
        if (prob > 0.5) {
          maskCount++;
          sumX += (x + 0.5);
          sumY += (y + 0.5);
        }
      }
    }

    if (maskCount == 0) {
      return new QuadFromMask(fallbackCorners256, true);
    }

    double cx = sumX / (double) maskCount;
    double cy = sumY / (double) maskCount;
    if (!Double.isFinite(cx) || !Double.isFinite(cy)) {
      return new QuadFromMask(fallbackCorners256, true);
    }

    // Covariance matrix (mean over points)
    double sxx = 0.0;
    double sxy = 0.0;
    double syy = 0.0;
    for (int y = 0; y < 64; y++) {
      float[] row = m[y];
      for (int x = 0; x < 64; x++) {
        double prob = sigmoid(row[x]);
        if (prob > 0.5) {
          double dx = (x + 0.5) - cx;
          double dy = (y + 0.5) - cy;
          sxx += dx * dx;
          sxy += dx * dy;
          syy += dy * dy;
        }
      }
    }
    sxx /= maskCount;
    sxy /= maskCount;
    syy /= maskCount;

    double trace = sxx + syy;
    if (!Double.isFinite(trace) || trace < 1e-12) {
      return new QuadFromMask(fallbackCorners256, true);
    }

    // Eigenvector v1 (for lambda1) analytically
    double det = sxx * syy - sxy * sxy;
    double discArg = trace * trace / 4.0 - det;
    double disc = Math.sqrt(Math.max(0.0, discArg));
    double lambda1 = trace / 2.0 + disc;

    final double eps = 1e-12;
    double v1x;
    double v1y;
    if (Math.abs(sxy) > eps) {
      v1x = lambda1 - syy;
      v1y = sxy;
    } else {
      if (sxx >= syy) {
        v1x = 1.0;
        v1y = 0.0;
      } else {
        v1x = 0.0;
        v1y = 1.0;
      }
    }

    double n = Math.hypot(v1x, v1y);
    if (n == 0.0 || !Double.isFinite(n)) {
      return new QuadFromMask(fallbackCorners256, true);
    }
    v1x /= n;
    v1y /= n;

    // v2 orthogonal (right-handed)
    double v2x = -v1y;
    double v2y = v1x;

    // Projections (u/v) and extrema
    double uMin = Double.POSITIVE_INFINITY;
    double uMax = Double.NEGATIVE_INFINITY;
    double vMin = Double.POSITIVE_INFINITY;
    double vMax = Double.NEGATIVE_INFINITY;

    for (int y = 0; y < 64; y++) {
      float[] row = m[y];
      for (int x = 0; x < 64; x++) {
        double prob = sigmoid(row[x]);
        if (prob > 0.5) {
          double px = (x + 0.5) - cx;
          double py = (y + 0.5) - cy;
          double u = px * v1x + py * v1y;
          double v = px * v2x + py * v2y;
          if (u < uMin) uMin = u;
          if (u > uMax) uMax = u;
          if (v < vMin) vMin = v;
          if (v > vMax) vMax = v;
        }
      }
    }

    if (!(Double.isFinite(uMin)
        && Double.isFinite(uMax)
        && Double.isFinite(vMin)
        && Double.isFinite(vMax))) {
      return new QuadFromMask(fallbackCorners256, true);
    }
    if (uMax - uMin < 1e-12 || vMax - vMin < 1e-12) {
      return new QuadFromMask(fallbackCorners256, true);
    }

    // Reconstruction of 4 corners (64-space) in v1/v2 coordinates
    double[][] quad64 = new double[4][2];
    // q0 = c + umax*v1 + vmax*v2
    quad64[0][0] = cx + uMax * v1x + vMax * v2x;
    quad64[0][1] = cy + uMax * v1y + vMax * v2y;
    // q1 = c + umin*v1 + vmax*v2
    quad64[1][0] = cx + uMin * v1x + vMax * v2x;
    quad64[1][1] = cy + uMin * v1y + vMax * v2y;
    // q2 = c + umin*v1 + vmin*v2
    quad64[2][0] = cx + uMin * v1x + vMin * v2x;
    quad64[2][1] = cy + uMin * v1y + vMin * v2y;
    // q3 = c + umax*v1 + vmin*v2
    quad64[3][0] = cx + uMax * v1x + vMin * v2x;
    quad64[3][1] = cy + uMax * v1y + vMin * v2y;

    quad64 = canonicalizeQuadOrderV1(quad64);

    // 64→256 mapping
    double[][] quad256 = new double[4][2];
    for (int i = 0; i < 4; i++) {
      quad256[i][0] = quad64[i][0] * 4.0;
      quad256[i][1] = quad64[i][1] * 4.0;
    }
    return new QuadFromMask(quad256, false);
  }

  /**
   * Cyclic/geometric canonicalization to TL,TR,BR,BL as used for the training labels: sort by angle
   * around the centroid, then start at the corner with the smallest {@code x+y}.
   */
  public static double[][] canonicalizeQuadOrderV1(double[][] pts) {
    if (pts == null || pts.length != 4) {
      throw new IllegalArgumentException("pts must be double[4][2]");
    }
    double cx = 0.0;
    double cy = 0.0;
    for (int i = 0; i < 4; i++) {
      if (pts[i] == null || pts[i].length != 2) {
        throw new IllegalArgumentException("pts must be double[4][2]");
      }
      cx += pts[i][0];
      cy += pts[i][1];
    }
    cx /= 4.0;
    cy /= 4.0;

    // Sort by angle ascending (tie-break by index) – analogous to Python REORDER_METHOD_V1.
    int[] ordered = new int[] {0, 1, 2, 3};
    for (int i = 0; i < 4; i++) {
      for (int j = i + 1; j < 4; j++) {
        int a = ordered[i];
        int b = ordered[j];
        double angA = Math.atan2(pts[a][1] - cy, pts[a][0] - cx);
        double angB = Math.atan2(pts[b][1] - cy, pts[b][0] - cx);
        boolean swap = false;
        if (angB < angA) {
          swap = true;
        } else if (angB == angA && b < a) {
          swap = true;
        }
        if (swap) {
          ordered[i] = b;
          ordered[j] = a;
        }
      }
    }

    // Rotation so that TL comes first (min(x+y); tie-break by position in ordered).
    int tlPos = 0;
    double bestSum = Double.POSITIVE_INFINITY;
    for (int k = 0; k < 4; k++) {
      int idx = ordered[k];
      double s = pts[idx][0] + pts[idx][1];
      if (s < bestSum || (s == bestSum && k < tlPos)) {
        bestSum = s;
        tlPos = k;
      }
    }

    double[][] out = new double[4][2];
    for (int i = 0; i < 4; i++) {
      int src = ordered[(tlPos + i) % 4];
      out[i][0] = pts[src][0];
      out[i][1] = pts[src][1];
    }
    return out;
  }

  public static double[][] corners64ToCorners256(float[][][][] cornerHeatmaps, PeakMode peakMode) {
    if (peakMode == null) {
      throw new IllegalArgumentException("peakMode must not be null");
    }
    if (peakMode == PeakMode.ARGMAX) {
      return argmaxCorners64ToCorners256(cornerHeatmaps);
    }
    if (peakMode == PeakMode.REFINE_3X3) {
      return refineCorners64ToCorners256_3x3(cornerHeatmaps);
    }
    if (peakMode == PeakMode.REFINE_5X5_QUADRATIC) {
      return refineCorners64ToCorners256_5x5Quadratic(cornerHeatmaps);
    }
    throw new IllegalArgumentException("unsupported peakMode: " + peakMode);
  }

  /**
   * Argmax per channel (TL,TR,BR,BL) and mapping 64→256 according to FIX: x256=(ix+0.5)*4.0,
   * y256=(iy+0.5)*4.0.
   */
  public static double[][] argmaxCorners64ToCorners256(float[][][][] cornerHeatmaps) {
    requireShapeCorners(cornerHeatmaps);

    double[][] corners256 = new double[4][2];
    for (int c = 0; c < 4; c++) {
      float best = -Float.MAX_VALUE;
      int bestX = 0;
      int bestY = 0;
      float[][] hm = cornerHeatmaps[0][c];
      for (int y = 0; y < 64; y++) {
        float[] row = hm[y];
        for (int x = 0; x < 64; x++) {
          float v = row[x];
          // Strict '>' => Ties: first in scan-order remains.
          if (v > best) {
            best = v;
            bestX = x;
            bestY = y;
          }
        }
      }

      corners256[c][0] = (bestX + 0.5) * 4.0;
      corners256[c][1] = (bestY + 0.5) * 4.0;
    }
    return corners256;
  }

  /**
   * Subpixel refinement via 3×3 weighted centroid around the argmax peak.
   *
   * <p>Specification (M6b): - Window: 3×3 around (ix,iy), clipped to [0..63] at edges. - Weights: w
   * = exp(logit - maxLogitInWindow) - Centroid over pixel centers (x+0.5, y+0.5) - Mapping 64→256:
   * x256 = x64 * 4.0, y256 = y64 * 4.0
   */
  public static double[][] refineCorners64ToCorners256_3x3(float[][][][] cornerHeatmaps) {
    requireShapeCorners(cornerHeatmaps);

    double[][] corners256 = new double[4][2];
    for (int c = 0; c < 4; c++) {
      float best = -Float.MAX_VALUE;
      int bestX = 0;
      int bestY = 0;
      float[][] hm = cornerHeatmaps[0][c];

      // (1) Argmax (deterministic, strict '>')
      for (int y = 0; y < 64; y++) {
        float[] row = hm[y];
        for (int x = 0; x < 64; x++) {
          float v = row[x];
          if (v > best) {
            best = v;
            bestX = x;
            bestY = y;
          }
        }
      }

      // (2) 3×3 Refinement (clipped window, no wraps)
      int x0 = Math.max(0, bestX - 1);
      int x1 = Math.min(63, bestX + 1);
      int y0 = Math.max(0, bestY - 1);
      int y1 = Math.min(63, bestY + 1);

      double maxLogit = Double.NEGATIVE_INFINITY;
      for (int y = y0; y <= y1; y++) {
        float[] row = hm[y];
        for (int x = x0; x <= x1; x++) {
          double v = row[x];
          if (v > maxLogit) {
            maxLogit = v;
          }
        }
      }

      double sumW = 0.0;
      double sumX = 0.0;
      double sumY = 0.0;
      for (int y = y0; y <= y1; y++) {
        float[] row = hm[y];
        for (int x = x0; x <= x1; x++) {
          double logit = row[x];
          double w = Math.exp(logit - maxLogit);
          sumW += w;
          sumX += w * (x + 0.5);
          sumY += w * (y + 0.5);
        }
      }

      double x64;
      double y64;
      if (sumW == 0.0 || !Double.isFinite(sumW)) {
        // Fallback: Argmax pixel center.
        x64 = bestX + 0.5;
        y64 = bestY + 0.5;
      } else {
        x64 = sumX / sumW;
        y64 = sumY / sumW;
      }

      corners256[c][0] = x64 * 4.0;
      corners256[c][1] = y64 * 4.0;
    }
    return corners256;
  }

  /**
   * Subpixel refinement combining a 5×5 softmax-weighted centroid with a 1D parabolic fit on the
   * argmax row/column. Trainings-frei; verbessert die Lokalisierung subpixelgenau wenn der Peak gut
   * ausgeprägt aber leicht außerhalb des Bin-Zentrums liegt.
   *
   * <p>Algorithm per corner channel:
   *
   * <ol>
   *   <li>Argmax (deterministic, strict '>') yields integer peak (ix,iy) in 64-grid.
   *   <li>Parabolic subpixel offset along x: dx = 0.5 * (L - R) / (L - 2*C + R), where
   *       L=hm[iy][ix-1], C=hm[iy][ix], R=hm[iy][ix+1]. Same for y. Offsets are clamped to
   *       [-0.5,0.5]. If denominator is non-positive (saddle/flat) the offset for that axis is 0.
   *   <li>5×5 softmax-weighted centroid around (ix,iy) (clipped to [0..63]) provides a robust
   *       fall-back if the parabolic fit is degenerate in both axes.
   *   <li>Mapping 64→256: x256 = (ix + 0.5 + dx) * 4.0; analog y. The 5×5 centroid is used only
   *       when the parabolic fit is fully degenerate.
   * </ol>
   */
  public static double[][] refineCorners64ToCorners256_5x5Quadratic(float[][][][] cornerHeatmaps) {
    requireShapeCorners(cornerHeatmaps);

    double[][] corners256 = new double[4][2];
    for (int c = 0; c < 4; c++) {
      float best = -Float.MAX_VALUE;
      int bestX = 0;
      int bestY = 0;
      float[][] hm = cornerHeatmaps[0][c];

      // (1) Argmax (deterministic, strict '>')
      for (int y = 0; y < 64; y++) {
        float[] row = hm[y];
        for (int x = 0; x < 64; x++) {
          float v = row[x];
          if (v > best) {
            best = v;
            bestX = x;
            bestY = y;
          }
        }
      }

      // (2) 1D parabolic subpixel fit per axis (only when not on the edge).
      double dx = 0.0;
      boolean dxValid = false;
      if (bestX > 0 && bestX < 63) {
        double l = hm[bestY][bestX - 1];
        double cV = hm[bestY][bestX];
        double r = hm[bestY][bestX + 1];
        double denom = (l - 2.0 * cV + r);
        if (denom < -1e-12) { // strictly concave => proper maximum
          dx = 0.5 * (l - r) / denom;
          if (dx < -0.5) dx = -0.5;
          if (dx > 0.5) dx = 0.5;
          dxValid = true;
        }
      }
      double dy = 0.0;
      boolean dyValid = false;
      if (bestY > 0 && bestY < 63) {
        double t = hm[bestY - 1][bestX];
        double cV = hm[bestY][bestX];
        double b = hm[bestY + 1][bestX];
        double denom = (t - 2.0 * cV + b);
        if (denom < -1e-12) {
          dy = 0.5 * (t - b) / denom;
          if (dy < -0.5) dy = -0.5;
          if (dy > 0.5) dy = 0.5;
          dyValid = true;
        }
      }

      double x64;
      double y64;
      if (dxValid || dyValid) {
        x64 = bestX + 0.5 + dx;
        y64 = bestY + 0.5 + dy;
      } else {
        // (3) Fallback: 5×5 softmax-weighted centroid.
        int x0 = Math.max(0, bestX - 2);
        int x1 = Math.min(63, bestX + 2);
        int y0 = Math.max(0, bestY - 2);
        int y1 = Math.min(63, bestY + 2);

        double maxLogit = Double.NEGATIVE_INFINITY;
        for (int y = y0; y <= y1; y++) {
          float[] row = hm[y];
          for (int x = x0; x <= x1; x++) {
            double v = row[x];
            if (v > maxLogit) maxLogit = v;
          }
        }
        double sumW = 0.0;
        double sumX = 0.0;
        double sumY = 0.0;
        for (int y = y0; y <= y1; y++) {
          float[] row = hm[y];
          for (int x = x0; x <= x1; x++) {
            double w = Math.exp(row[x] - maxLogit);
            sumW += w;
            sumX += w * (x + 0.5);
            sumY += w * (y + 0.5);
          }
        }
        if (sumW == 0.0 || !Double.isFinite(sumW)) {
          x64 = bestX + 0.5;
          y64 = bestY + 0.5;
        } else {
          x64 = sumX / sumW;
          y64 = sumY / sumW;
        }
      }

      corners256[c][0] = x64 * 4.0;
      corners256[c][1] = y64 * 4.0;
    }
    return corners256;
  }

  public static MaskStats computeMaskStats(float[][][][] maskLogits) {
    requireShapeMask(maskLogits);
    float[][] m = maskLogits[0][0];

    int count = 0;
    double sumProb = 0.0;
    for (int y = 0; y < 64; y++) {
      float[] row = m[y];
      for (int x = 0; x < 64; x++) {
        double logit = row[x];
        double prob = sigmoid(logit);
        sumProb += prob;
        if (prob > 0.5) {
          count++;
        }
      }
    }
    double mean = sumProb / (64.0 * 64.0);
    return new MaskStats(count, mean);
  }

  public static double[][] mapCorners256ToOriginal(double[][] corners256, DocQuadLetterbox lb) {
    if (corners256 == null || corners256.length != 4) {
      throw new IllegalArgumentException("corners256 must be double[4][2]");
    }
    if (lb == null) {
      throw new IllegalArgumentException("lb must not be null");
    }

    double[][] out = new double[4][2];
    for (int i = 0; i < 4; i++) {
      if (corners256[i] == null || corners256[i].length != 2) {
        throw new IllegalArgumentException("corners256 must be double[4][2]");
      }
      double[] p = lb.inverse(corners256[i][0], corners256[i][1]);
      out[i][0] = p[0];
      out[i][1] = p[1];
    }
    return out;
  }

  private static double sigmoid(double x) {
    return 1.0 / (1.0 + Math.exp(-x));
  }

  private static void requireShapeMask(float[][][][] maskLogits) {
    if (maskLogits == null
        || maskLogits.length != 1
        || maskLogits[0] == null
        || maskLogits[0].length != 1
        || maskLogits[0][0] == null
        || maskLogits[0][0].length != 64
        || maskLogits[0][0][0] == null
        || maskLogits[0][0][0].length != 64) {
      throw new IllegalArgumentException("mask_logits must have shape [1][1][64][64]");
    }
    for (int y = 0; y < 64; y++) {
      if (maskLogits[0][0][y] == null || maskLogits[0][0][y].length != 64) {
        throw new IllegalArgumentException("mask_logits must have shape [1][1][64][64]");
      }
    }
  }

  private static void requireShapeCorners(float[][][][] cornerHeatmaps) {
    if (cornerHeatmaps == null
        || cornerHeatmaps.length != 1
        || cornerHeatmaps[0] == null
        || cornerHeatmaps[0].length != 4) {
      throw new IllegalArgumentException("corner_heatmaps must have shape [1][4][64][64]");
    }
    for (int c = 0; c < 4; c++) {
      if (cornerHeatmaps[0][c] == null
          || cornerHeatmaps[0][c].length != 64
          || cornerHeatmaps[0][c][0] == null
          || cornerHeatmaps[0][c][0].length != 64) {
        throw new IllegalArgumentException("corner_heatmaps must have shape [1][4][64][64]");
      }
      for (int y = 0; y < 64; y++) {
        if (cornerHeatmaps[0][c][y] == null || cornerHeatmaps[0][c][y].length != 64) {
          throw new IllegalArgumentException("corner_heatmaps must have shape [1][4][64][64]");
        }
      }
    }
  }

  /**
   * Postprocessing result containing detected document corners and quality penalties.
   *
   * @param penaltyCorners M6d Penalties (lower is better): - penaltyCorners: Geometry + Mask
   *     disagreement (only for corner quad) - penaltyMask: Geometry only (or +Inf if mask quad was
   *     not viable)
   * @param minPeakProb see {@link DocQuadPostprocessor#minPeakProb}
   * @param cornerMaskIou see {@link DocQuadPostprocessor#cornerMaskIou}
   */
  public record Result(
      double[][] corners256,
      double[][] cornersOriginal,
      int maskProbGt05Count,
      double maskProbMean,
      double[][] quadFromMask256,
      double[][] quadFromMaskOriginal,
      boolean quadFromMaskUsedFallback,
      double[][] chosenQuad256,
      double[][] chosenQuadOriginal,
      ChosenSource chosenSource,
      double penaltyCorners,
      double penaltyMask,
      boolean suspiciousForProduct,
      String suspiciousReason,
      double minPeakProb,
      double cornerMaskIou) {

    /**
     * Confidence of the heatmap corner quad in [0,1]: {@link #minPeakProb} × {@link
     * #cornerMaskIou}.
     */
    public double cornerConfidence() {
      return minPeakProb * cornerMaskIou;
    }
  }

  record PathChoice(
      double[][] chosenQuad256,
      ChosenSource chosenSource,
      double penaltyCorners,
      double penaltyMask) {}

  public record QuadFromMask(double[][] quad256, boolean usedFallback) {}

  public record MaskStats(int maskProbGt05Count, double maskProbMean) {}
}
