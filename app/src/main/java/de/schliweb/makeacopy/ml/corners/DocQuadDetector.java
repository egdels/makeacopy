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
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.ml.docquad.DocQuadLetterbox;
import de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner;
import de.schliweb.makeacopy.ml.docquad.DocQuadPostprocessor;

/** Produktiver Adapter für DocQuadNet-256. */
public final class DocQuadDetector implements CornerDetector {

  private static final String TAG = "DocQuadDetector";

  // Rate-limited logging window in ms when FEATURE_FRAMING_LOGGING is on.
  private static final long LOG_INTERVAL_MS = 1000L;
  private long lastLogMs = 0L;

  // Release-Asset (F-Droid kompatibel, kein Download)
  public static final String DEFAULT_MODEL_ASSET_PATH = "docquad/docquadnet256_trained_opset17.ort";

  private final DocQuadOrtRunner runner;

  /** Creates a detector with the given ORT runner. */
  public DocQuadDetector(DocQuadOrtRunner runner) {
    Log.d(TAG, "DocQuadDetector created");
    this.runner = runner;
  }

  private static final DocQuadPostprocessor.PeakMode PEAK_MODE =
      DocQuadPostprocessor.PeakMode.REFINE_5X5_QUADRATIC;

  /**
   * One model pass over {@code src}: letterbox, inference, postprocessing. Coordinates in the
   * result refer to {@code src}. Returns {@code null} if inference fails.
   */
  @Nullable
  DocQuadPostprocessor.Result infer(@NonNull Bitmap src) {
    Bitmap in256 = null;
    try {
      int srcW = src.getWidth();
      int srcH = src.getHeight();
      if (srcW <= 0 || srcH <= 0) return null;
      DocQuadLetterbox lb =
          DocQuadLetterbox.create(srcW, srcH, DocQuadOrtRunner.IN_W, DocQuadOrtRunner.IN_H);
      in256 = renderLetterbox256(src, lb);
      float[] input = bitmapToNchwFloat01(in256);
      DocQuadOrtRunner.Outputs outputs = runner.run(input);
      return DocQuadPostprocessor.postprocess(outputs, lb, PEAK_MODE);
    } catch (Throwable t) {
      return null;
    } finally {
      // Live analysis can call this repeatedly; avoid accumulating Bitmap native memory.
      try {
        if (in256 != null && !in256.isRecycled()) in256.recycle();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
  }

  @Override
  public DetectionResult detect(Bitmap src, Context ctx) {
    if (src == null || ctx == null) return DetectionResult.fail(Source.DOCQUAD);
    return toDetectionResult(infer(src), src.getWidth(), src.getHeight());
  }

  /** Validates a postprocessing result and converts it into the product-facing result. */
  DetectionResult toDetectionResult(@Nullable DocQuadPostprocessor.Result r, int srcW, int srcH) {
    try {
      if (r == null || r.chosenQuadOriginal() == null || r.chosenQuadOriginal().length != 4)
        return DetectionResult.fail(Source.DOCQUAD);
      if (BuildConfig.FEATURE_FRAMING_LOGGING) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLogMs >= LOG_INTERVAL_MS) {
          lastLogMs = now;
          double[][] q = r.chosenQuadOriginal();
          Log.d(
              TAG,
              String.format(
                  java.util.Locale.US,
                  "detect peakMode=%s src=%dx%d chosen=%s TL=(%.1f,%.1f) TR=(%.1f,%.1f)"
                      + " BR=(%.1f,%.1f) BL=(%.1f,%.1f)",
                  PEAK_MODE,
                  srcW,
                  srcH,
                  String.valueOf(r.chosenSource()),
                  q[0][0],
                  q[0][1],
                  q[1][0],
                  q[1][1],
                  q[2][0],
                  q[2][1],
                  q[3][0],
                  q[3][1]));
        }
      }

      /*
           if (r.suspiciousForProduct()) {
             Log.i(TAG, "Detection flagged as suspicious: " + r.suspiciousReason());
             return DetectionResult.fail(Source.DOCQUAD);
           }
      */

      if (!isValidQuad(r.chosenQuadOriginal(), srcW, srcH))
        return DetectionResult.fail(Source.DOCQUAD);

      return DetectionResult.successDebug(
              Source.DOCQUAD,
              r.chosenQuadOriginal(),
              String.valueOf(r.chosenSource()),
              r.penaltyMask(),
              r.penaltyCorners())
          .withConfidence(r.cornerConfidence());
    } catch (Throwable t) {
      return DetectionResult.fail(Source.DOCQUAD);
    }
  }

  /** Preprocess exakt wie Training: RGB, 0..1, NCHW float32. */
  private static float[] bitmapToNchwFloat01(Bitmap bmp) {
    int w = bmp.getWidth();
    int h = bmp.getHeight();
    if (w != DocQuadOrtRunner.IN_W || h != DocQuadOrtRunner.IN_H) {
      throw new IllegalArgumentException("bitmap must be 256x256");
    }
    int hw = h * w;
    float[] out = new float[3 * hw];
    int[] px = new int[hw];
    bmp.getPixels(px, 0, w, 0, 0, w, h);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int c = px[y * w + x];
        float r = ((c >> 16) & 0xFF) / 255.0f;
        float g = ((c >> 8) & 0xFF) / 255.0f;
        float b = (c & 0xFF) / 255.0f;
        int idx = y * w + x;
        out[idx] = r;
        out[hw + idx] = g;
        out[2 * hw + idx] = b;
      }
    }
    return out;
  }

  // Black letterbox padding, exactly as in training (train_docquad_heatmap.py pastes onto a black
  // canvas). Mid-gray padding was tried and measured worse on real photos: it is a train/inference
  // mismatch that lowers the heatmap peak confidence (single pass on the device, mean corner error
  // 3.06% -> 2.35% on upright photos and 7.1% -> 4.6% on tilted ones when switching back to black).
  static final int LETTERBOX_PAD_COLOR = 0xFF000000;

  private static Bitmap renderLetterbox256(Bitmap src, DocQuadLetterbox lb) {
    Bitmap out =
        Bitmap.createBitmap(DocQuadOrtRunner.IN_W, DocQuadOrtRunner.IN_H, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(out);
    canvas.drawColor(LETTERBOX_PAD_COLOR);

    float left = (float) lb.offsetX;
    float top = (float) lb.offsetY;
    float right = (float) (lb.offsetX + (double) lb.srcW * lb.scale);
    float bottom = (float) (lb.offsetY + (double) lb.srcH * lb.scale);
    RectF dst = new RectF(left, top, right, bottom);

    // Enable bilinear filtering and dithering during the strong downscale (often >10×) to
    // reduce aliasing-induced false edges that can confuse the corner heatmaps.
    android.graphics.Paint paint = new android.graphics.Paint();
    paint.setFilterBitmap(true);
    paint.setDither(true);
    paint.setAntiAlias(true);
    canvas.drawBitmap(src, null, dst, paint);
    return out;
  }

  private static boolean isFinite(double v) {
    return !Double.isNaN(v) && !Double.isInfinite(v);
  }

  static boolean isValidQuad(double[][] c, int w, int h) {
    if (c == null || c.length != 4) return false;
    for (int i = 0; i < 4; i++) {
      if (c[i] == null || c[i].length != 2) return false;
      double x = c[i][0];
      double y = c[i][1];
      if (!isFinite(x) || !isFinite(y)) return false;
      // Plausibility: leicht außerhalb tolerieren, aber nicht komplett wild
      if (x < -w * 0.25 || x > w * 1.25) return false;
      if (y < -h * 0.25 || y > h * 1.25) return false;
    }
    // Convexity + TL/TR/BR/BL orientation check: rejects flipped/self-intersecting quads
    // such as the "cold start" frame where the model returns a degenerate ordering before
    // it has locked onto the document.
    return isConvexTLTRBRBL(c);
  }

  /**
   * Returns {@code true} iff the four corners form a strictly convex, non-self-intersecting
   * quadrilateral traversed in TL→TR→BR→BL order (i.e. clockwise in image coordinates where y grows
   * downward).
   *
   * <p>Implementation: all four signed cross products of consecutive edge vectors must share the
   * same (positive) sign and be non-zero. A positive sign in image coordinates (y grows downward)
   * corresponds to a clockwise winding, which is what TL/TR/BR/BL implies. Zero or mixed signs mean
   * the polygon is degenerate (collinear) or self-intersecting (e.g. swapped corners).
   *
   * <p>Visible for testing.
   */
  static boolean isConvexTLTRBRBL(double[][] c) {
    if (c == null || c.length != 4) return false;
    double prevSign = 0.0;
    for (int i = 0; i < 4; i++) {
      double[] a = c[i];
      double[] b = c[(i + 1) % 4];
      double[] d = c[(i + 2) % 4];
      if (a == null || b == null || d == null) return false;
      double abx = b[0] - a[0];
      double aby = b[1] - a[1];
      double bdx = d[0] - b[0];
      double bdy = d[1] - b[1];
      double cross = abx * bdy - aby * bdx;
      if (!isFinite(cross) || cross == 0.0) return false;
      double sign = Math.signum(cross);
      if (i == 0) {
        // Require clockwise winding (positive cross in image coordinates with y-down).
        if (sign < 0.0) return false;
        prevSign = sign;
      } else if (sign != prevSign) {
        return false;
      }
    }
    return true;
  }
}
