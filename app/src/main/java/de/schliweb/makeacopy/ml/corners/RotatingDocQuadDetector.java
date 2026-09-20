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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner;
import de.schliweb.makeacopy.ml.docquad.DocQuadPostprocessor;
import java.util.Locale;

/**
 * DocQuad with test-time rotation for one-shot detection (crop screen). Not meant for the live
 * preview: a weak first pass costs up to five additional inferences.
 *
 * <p>Motivation: DocQuadNet was trained with small rotation augmentation only and fails on
 * documents tilted by more than ~20° — all four heatmaps stay flat and the mask is wrong as well,
 * so no postprocessing can recover the quad. Showing the model the same image rotated by a few
 * fixed angles brings such documents back into the orientation range it has learned.
 *
 * <p>Policy: if the unrotated pass is confident ({@link
 * DocQuadPostprocessor.Result#cornerConfidence()} ≥ {@link #ACCEPT_CONFIDENCE}), its result is
 * returned unchanged. Otherwise the rotated passes are tried and the candidate with the highest
 * confidence wins; the unrotated result is kept unless a rotated one beats it clearly (see {@link
 * #acceptsRotated}). Confidence is peak probability × corner/mask agreement: peak probability alone
 * prefers confidently wrong detections of the rotated image frame and makes results worse.
 */
public final class RotatingDocQuadDetector implements CornerDetector {

  private static final String TAG = "RotatingDocQuad";

  /** Confidence of the unrotated pass from which no further passes are run. */
  static final double ACCEPT_CONFIDENCE = 0.5;

  /** Confidence of any candidate from which the remaining angles are skipped. */
  static final double EARLY_EXIT_CONFIDENCE = 0.7;

  /**
   * Minimum confidence a rotated candidate needs to replace the unrotated result. Below this the
   * candidates only differ by noise.
   */
  static final double MIN_ROTATED_CONFIDENCE = 0.1;

  /**
   * Margin by which a rotated candidate must beat the confidence of the unrotated pass. With weak
   * detections on both sides the higher number is as often wrong as right, and replacing a usable
   * unrotated quad is worse than keeping it; a fixed floor alone could not separate the two cases.
   * Not applied when the unrotated pass produced no valid quad at all.
   */
  static final double MIN_ROTATED_MARGIN = 0.15;

  /** Additional angles in degrees, ordered by how often they rescued a failed detection. */
  private static final int[] ANGLES = {90, -30, 30, -60, 60};

  /** Rotated passes run on at most this edge length; the model input is 256 px anyway. */
  private static final int MAX_ROTATION_EDGE = 720;

  // Same neutral padding as the letterbox, so the rotated frame adds as little contrast as
  // possible.
  private static final int PAD_COLOR = DocQuadDetector.LETTERBOX_PAD_COLOR;

  private final DocQuadDetector base;

  public RotatingDocQuadDetector(@NonNull DocQuadOrtRunner runner) {
    this.base = new DocQuadDetector(runner);
  }

  @Override
  public DetectionResult detect(Bitmap src, Context ctx) {
    if (src == null || ctx == null) return DetectionResult.fail(Source.DOCQUAD);
    long t0 = SystemClock.uptimeMillis();
    int srcW = src.getWidth();
    int srcH = src.getHeight();

    DocQuadPostprocessor.Result r0 = base.infer(src);
    DetectionResult d0 = base.toDetectionResult(r0, srcW, srcH);
    double conf0 = confidence(r0, r0 != null ? r0.cornersOriginal() : null, srcW, srcH);
    if (d0.success && conf0 >= ACCEPT_CONFIDENCE) return d0;

    double[][] bestQuad = null;
    double bestConf = conf0;
    int bestAngle = 0;
    int passes = 1;
    Bitmap small = null;
    try {
      float scale = Math.min(1f, MAX_ROTATION_EDGE / (float) Math.max(srcW, srcH));
      small =
          scale < 1f
              ? Bitmap.createScaledBitmap(
                  src, Math.round(srcW * scale), Math.round(srcH * scale), true)
              : src;
      for (int angle : ANGLES) {
        Bitmap rotated = rotate(small, angle);
        DocQuadPostprocessor.Result r;
        try {
          r = base.infer(rotated);
          passes++;
        } finally {
          rotated.recycle();
        }
        if (r == null || r.cornersOriginal() == null) continue;
        double[][] quad =
            mapBack(
                r.cornersOriginal(),
                angle,
                small.getWidth(),
                small.getHeight(),
                rotatedSize(small.getWidth(), small.getHeight(), angle),
                1.0 / scale);
        double conf = confidence(r, quad, srcW, srcH);
        if (conf > bestConf) {
          bestConf = conf;
          bestQuad = quad;
          bestAngle = angle;
        }
        if (bestConf >= EARLY_EXIT_CONFIDENCE) break;
      }
    } catch (Throwable t) {
      Log.w(TAG, "rotated passes failed, keeping unrotated result: " + t);
    } finally {
      if (small != null && small != src) small.recycle();
    }

    Log.i(
        TAG,
        String.format(
            Locale.US,
            "conf0=%.2f -> angle=%d conf=%.2f, passes=%d, %d ms",
            conf0,
            bestAngle,
            bestConf,
            passes,
            SystemClock.uptimeMillis() - t0));
    if (bestQuad == null || !acceptsRotated(conf0, bestConf)) return d0;
    return DetectionResult.successDebug(
            Source.DOCQUAD, bestQuad, "CORNERS_ROT" + bestAngle, null, null)
        .withConfidence(bestConf);
  }

  /**
   * Whether a rotated candidate may replace the unrotated result. {@code unrotatedConfidence} is
   * negative when the unrotated pass produced no valid corner quad. Visible for testing.
   */
  static boolean acceptsRotated(double unrotatedConfidence, double rotatedConfidence) {
    if (rotatedConfidence < MIN_ROTATED_CONFIDENCE) return false;
    return unrotatedConfidence < 0.0
        || rotatedConfidence >= unrotatedConfidence + MIN_ROTATED_MARGIN;
  }

  /** Confidence of the heatmap corner quad, or -1 if it is not a valid quad in the source image. */
  private static double confidence(
      @Nullable DocQuadPostprocessor.Result r, @Nullable double[][] quadInSource, int w, int h) {
    if (r == null || quadInSource == null || !DocQuadDetector.isValidQuad(quadInSource, w, h)) {
      return -1.0;
    }
    return r.cornerConfidence();
  }

  private static Bitmap rotate(Bitmap src, int angleDeg) {
    int w = src.getWidth();
    int h = src.getHeight();
    int[] size = rotatedSize(w, h, angleDeg);
    Bitmap out = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(out);
    canvas.drawColor(PAD_COLOR);
    Matrix m = new Matrix();
    m.setRotate(angleDeg, w / 2f, h / 2f);
    m.postTranslate((size[0] - w) / 2f, (size[1] - h) / 2f);
    Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    canvas.drawBitmap(src, m, paint);
    return out;
  }

  /** Size {@code {w, h}} of the bounding box of a {@code w×h} image rotated by {@code angleDeg}. */
  static int[] rotatedSize(int w, int h, int angleDeg) {
    double a = Math.toRadians(angleDeg);
    double cos = Math.abs(Math.cos(a));
    double sin = Math.abs(Math.sin(a));
    return new int[] {
      (int) Math.ceil(w * cos + h * sin - 1e-9), (int) Math.ceil(w * sin + h * cos - 1e-9)
    };
  }

  /**
   * Forward mapping used by {@link #rotate}: rotation by {@code angleDeg} (clockwise on screen)
   * about the image centre, re-centred in the rotated bounding box. Visible for testing.
   */
  static double[] mapForward(double x, double y, int angleDeg, int w, int h, int[] rotatedSize) {
    double a = Math.toRadians(angleDeg);
    double dx = x - w / 2.0;
    double dy = y - h / 2.0;
    return new double[] {
      Math.cos(a) * dx - Math.sin(a) * dy + rotatedSize[0] / 2.0,
      Math.sin(a) * dx + Math.cos(a) * dy + rotatedSize[1] / 2.0
    };
  }

  /**
   * Maps a quad detected in the rotated image back into the unrotated image, scales it by {@code
   * scaleToSource} and re-establishes the geometric TL,TR,BR,BL order: the model labels corners
   * relative to the image it sees, so after a 90° pass its "TL" is a different corner of the source
   * image. Visible for testing.
   */
  static double[][] mapBack(
      double[][] quadRotated, int angleDeg, int w, int h, int[] rotatedSize, double scaleToSource) {
    double a = Math.toRadians(angleDeg);
    double[][] out = new double[4][2];
    for (int i = 0; i < 4; i++) {
      double dx = quadRotated[i][0] - rotatedSize[0] / 2.0;
      double dy = quadRotated[i][1] - rotatedSize[1] / 2.0;
      out[i][0] = (Math.cos(a) * dx + Math.sin(a) * dy + w / 2.0) * scaleToSource;
      out[i][1] = (-Math.sin(a) * dx + Math.cos(a) * dy + h / 2.0) * scaleToSource;
    }
    return DocQuadPostprocessor.canonicalizeQuadOrderV1(out);
  }
}
