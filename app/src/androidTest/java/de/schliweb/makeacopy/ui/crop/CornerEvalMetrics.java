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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-Java metrics for the corner pipeline evaluation (no Android/OpenCV dependency, so the math
 * can be checked on a plain JVM).
 *
 * <p>All quads are {@code double[4][2]}; ground truth is TL,TR,BR,BL as in {@code
 * training/EVALUATION.md}.
 */
final class CornerEvalMetrics {

  private CornerEvalMetrics() {}

  /** Per-sample error of one predicted quad against ground truth. */
  static final class QuadError {
    /** Mean / max corner distance in original pixels. */
    final double meanPx;

    final double maxPx;

    /** Same, relative to the image diagonal (resolution independent). */
    final double meanRel;

    final double maxRel;
    final double iou;

    /**
     * Cyclic shift of the prediction that matched ground truth best. Non-zero means the quad
     * outline is right but the detector labelled the corners in a rotated order.
     */
    final int orderShift;

    QuadError(
        double meanPx, double maxPx, double meanRel, double maxRel, double iou, int orderShift) {
      this.meanPx = meanPx;
      this.maxPx = maxPx;
      this.meanRel = meanRel;
      this.maxRel = maxRel;
      this.iou = iou;
      this.orderShift = orderShift;
    }
  }

  /**
   * Corner error minimised over the four cyclic orderings of {@code pred}; crop geometry does not
   * depend on which corner is called "TL".
   */
  static QuadError error(double[][] pred, double[][] gt, int imgW, int imgH) {
    double diag = Math.hypot(imgW, imgH);
    double bestMean = Double.POSITIVE_INFINITY;
    double bestMax = Double.POSITIVE_INFINITY;
    int bestShift = 0;
    for (int shift = 0; shift < 4; shift++) {
      double sum = 0.0;
      double max = 0.0;
      for (int i = 0; i < 4; i++) {
        double[] p = pred[(i + shift) % 4];
        double d = Math.hypot(p[0] - gt[i][0], p[1] - gt[i][1]);
        sum += d;
        if (d > max) max = d;
      }
      double mean = sum / 4.0;
      if (mean < bestMean) {
        bestMean = mean;
        bestMax = max;
        bestShift = shift;
      }
    }
    return new QuadError(
        bestMean, bestMax, bestMean / diag, bestMax / diag, iou(pred, gt), bestShift);
  }

  /** IoU of two convex quads; 0 if either is degenerate or {@code clip} is not convex. */
  static double iou(double[][] a, double[][] b) {
    double areaA = Math.abs(signedArea(toList(a)));
    double areaB = Math.abs(signedArea(toList(b)));
    if (!(areaA > 0.0) || !(areaB > 0.0)) return 0.0;
    double inter = Math.abs(signedArea(clipConvex(toList(a), toList(b))));
    double union = areaA + areaB - inter;
    return union > 0.0 ? Math.max(0.0, Math.min(1.0, inter / union)) : 0.0;
  }

  /** Sutherland–Hodgman: clips {@code subject} against the convex polygon {@code clip}. */
  private static List<double[]> clipConvex(List<double[]> subject, List<double[]> clip) {
    double orientation = Math.signum(signedArea(clip));
    List<double[]> out = subject;
    for (int i = 0; i < clip.size() && !out.isEmpty(); i++) {
      double[] c0 = clip.get(i);
      double[] c1 = clip.get((i + 1) % clip.size());
      List<double[]> in = out;
      out = new ArrayList<>();
      for (int j = 0; j < in.size(); j++) {
        double[] p = in.get(j);
        double[] q = in.get((j + 1) % in.size());
        double sp = orientation * side(c0, c1, p);
        double sq = orientation * side(c0, c1, q);
        if (sp >= 0) out.add(p);
        if ((sp >= 0) != (sq >= 0)) {
          double t = sp / (sp - sq);
          out.add(new double[] {p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])});
        }
      }
    }
    return out;
  }

  private static double side(double[] a, double[] b, double[] p) {
    return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
  }

  private static double signedArea(List<double[]> poly) {
    double s = 0.0;
    for (int i = 0; i < poly.size(); i++) {
      double[] p = poly.get(i);
      double[] q = poly.get((i + 1) % poly.size());
      s += p[0] * q[1] - q[0] * p[1];
    }
    return 0.5 * s;
  }

  private static List<double[]> toList(double[][] quad) {
    return new ArrayList<>(Arrays.asList(quad));
  }

  static double mean(List<Double> values) {
    if (values.isEmpty()) return Double.NaN;
    double s = 0.0;
    for (double v : values) s += v;
    return s / values.size();
  }

  /** Nearest-rank percentile, {@code p} in [0,1]. */
  static double percentile(List<Double> values, double p) {
    if (values.isEmpty()) return Double.NaN;
    double[] sorted = new double[values.size()];
    for (int i = 0; i < sorted.length; i++) sorted[i] = values.get(i);
    Arrays.sort(sorted);
    int idx = (int) Math.ceil(p * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
  }
}
