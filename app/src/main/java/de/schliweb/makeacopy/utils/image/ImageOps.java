/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.image;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import de.schliweb.makeacopy.utils.geom.Point;
import de.schliweb.makeacopy.utils.geom.Size;
import de.schliweb.makeacopy.utils.image.raster.BlackWhite;
import de.schliweb.makeacopy.utils.image.raster.Enhance;
import de.schliweb.makeacopy.utils.image.raster.Gray;
import de.schliweb.makeacopy.utils.image.raster.Homography;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;

/**
 * Page image operations: perspective correction and dewarping of the cropped page, the edge-profile
 * estimate for curved pages, trimming of dark borders, the Enhanced cleanup, grayscale and black
 * and white, and the camera's text orientation hint. The pixel work is plain Java (package {@code
 * raster}); the perspective warp is drawn with {@link Matrix#setPolyToPoly}.
 */
@UtilityClass
public final class ImageOps {
  private static final String TAG = "ImageOps";

  /**
   * Maximum edge size (in pixels) for corner detection preprocessing.
   *
   * <p>This value MUST be used consistently across all corner detection calls (live preview in
   * CameraFragment and final detection in TrapezoidSelectionView) to ensure identical results.
   * Using different values leads to inconsistent corner positions due to varying interpolation
   * artifacts during scaling.
   *
   * <p>
   */
  public static final int DETECTION_MAX_EDGE = 720;

  /** Longest edge the dewarp edge-profile estimate works on; it only needs the page outline. */
  private static final int DEWARP_ESTIMATE_MAX_EDGE = 1600;

  public static Bitmap applyPerspectiveCorrection(Bitmap originalBitmap, Point[] corners) {
    return applyPerspectiveCorrection(originalBitmap, corners, WarpMode.AUTO_PROJECTIVE, null);
  }

  public static Bitmap applyPerspectiveCorrectionLegacyHeuristic(
      Bitmap originalBitmap, Point[] corners) {
    return applyPerspectiveCorrection(originalBitmap, corners, WarpMode.LEGACY_HEURISTIC, null);
  }

  /**
   * Warps the quad {@code corners} (TL, TR, BR, BL) of {@code originalBitmap} to an upright
   * rectangle whose size {@code mode} determines (see {@link WarpMode}).
   */
  public static Bitmap applyPerspectiveCorrection(
      Bitmap originalBitmap,
      Point[] corners,
      WarpMode mode,
      @androidx.annotation.Nullable Double targetShortOverLong) {
    if (corners == null || corners.length != 4) return originalBitmap;
    if (mode == null) mode = WarpMode.AUTO_PROJECTIVE;
    Size targetSize =
        computeDewarpTargetSize(
            corners,
            mode,
            targetShortOverLong,
            originalBitmap.getWidth(),
            originalBitmap.getHeight());
    return warpPerspectiveWithMatrix(originalBitmap, corners, targetSize);
  }

  /**
   * Dewarps a curved page: the page between the model's top and bottom curves is resampled onto a
   * rectangle, following arc length along each curve. Falls back to {@link
   * #applyPerspectiveCorrection} when the curves are effectively straight.
   */
  public static Bitmap applyDewarp(
      Bitmap originalBitmap,
      DewarpModel model,
      WarpMode mode,
      @androidx.annotation.Nullable Double targetShortOverLong) {
    if (originalBitmap == null || model == null) return originalBitmap;
    Point[] corners = model.getCorners();
    if (corners == null || corners.length != 4) return originalBitmap;
    if (mode == null) mode = WarpMode.AUTO_PROJECTIVE;

    if (model.isEffectivelyStraight(DEWARP_STRAIGHT_TOLERANCE_PX) && model.getDepth() == 0.0) {
      Log.d(TAG, "applyDewarp: curves effectively straight; using perspective pipeline");
      return applyPerspectiveCorrection(originalBitmap, corners, mode, targetShortOverLong);
    }
    try {
      int sw = originalBitmap.getWidth();
      int sh = originalBitmap.getHeight();
      int[] src = pixels(originalBitmap);
      Size targetSize = computeDewarpTargetSize(corners, mode, targetShortOverLong, sw, sh);
      int outW = Math.max(1, (int) Math.round(targetSize.width));
      int outH = Math.max(1, (int) Math.round(targetSize.height));

      double[] topArcLut = buildArcLengthLut(model, true);
      double[] bottomArcLut = buildArcLengthLut(model, false);
      // Coarse source-coordinate grid over the page (inset slightly so the edges are not sampled
      // from the background), interpolated bilinearly per output pixel.
      int g = DEWARP_GRID_SIZE;
      float[] gx = new float[g * g];
      float[] gy = new float[g * g];
      double inset = DEWARP_EDGE_INSET_FRAC;
      double span = 1.0 - 2.0 * inset;
      for (int j = 0; j < g; j++) {
        double v = inset + span * (j / (double) (g - 1));
        double w = model.blendWeight(v);
        for (int i = 0; i < g; i++) {
          double u = inset + span * (i / (double) (g - 1));
          Point pTop = model.topAt(lookupArcParam(topArcLut, u));
          Point pBottom = model.bottomAt(lookupArcParam(bottomArcLut, u));
          gx[j * g + i] = (float) ((1.0 - w) * pTop.x + w * pBottom.x);
          gy[j * g + i] = (float) ((1.0 - w) * pTop.y + w * pBottom.y);
        }
      }
      int[] out = new int[outW * outH];
      IntStream.range(0, outH)
          .parallel()
          .forEach(
              y -> {
                double fy = clamp((y + 0.5) * g / outH - 0.5, 0, g - 1);
                int y0 = (int) fy;
                int y1 = Math.min(g - 1, y0 + 1);
                double ty = fy - y0;
                for (int x = 0; x < outW; x++) {
                  double fx = clamp((x + 0.5) * g / outW - 0.5, 0, g - 1);
                  int x0 = (int) fx;
                  int x1 = Math.min(g - 1, x0 + 1);
                  double tx = fx - x0;
                  double sx = lerp2(gx, g, x0, x1, y0, y1, tx, ty);
                  double sy = lerp2(gy, g, x0, x1, y0, y1, tx, ty);
                  out[y * outW + x] = Homography.sample(src, sw, sh, sx, sy);
                }
              });
      Log.d(
          TAG,
          "applyDewarp: done, out="
              + outW
              + "x"
              + outH
              + ", maxSagitta="
              + Math.round(model.maxSagitta())
              + "px");
      return Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888);
    } catch (Throwable t) {
      Log.e(TAG, "applyDewarp failed; returning original bitmap", t);
      return originalBitmap;
    }
  }

  private static double lerp2(
      float[] grid, int g, int x0, int x1, int y0, int y1, double tx, double ty) {
    double top = grid[y0 * g + x0] + (grid[y0 * g + x1] - grid[y0 * g + x0]) * tx;
    double bottom = grid[y1 * g + x0] + (grid[y1 * g + x1] - grid[y1 * g + x0]) * tx;
    return top + (bottom - top) * ty;
  }

  /**
   * Estimates how far the top and bottom page edges bulge, for curved book pages: the quad
   * (extended above and below) is rectified to a narrow band, each column bin's paper (brighter
   * than Otsu's threshold) gives a top and bottom edge point, and a cubic is fitted through them.
   *
   * @return {top, bottom} profiles of {@code DEWARP_PROFILE_SAMPLES} offsets, each a fraction of
   *     the edge length (an entry is null when that edge is hidden), or null without an estimate
   */
  public static double[][] estimateDewarpEdgeProfiles(Bitmap bitmap, Point[] corners) {
    if (bitmap == null || bitmap.isRecycled() || corners == null || corners.length != 4) {
      return null;
    }
    for (Point p : corners) if (p == null) return null;
    try {
      double scale =
          Math.min(
              1.0,
              DEWARP_ESTIMATE_MAX_EDGE / (double) Math.max(bitmap.getWidth(), bitmap.getHeight()));
      Bitmap work = bitmap;
      Point[] quad = corners;
      if (scale < 1.0) {
        work =
            Bitmap.createScaledBitmap(
                bitmap,
                Math.max(1, (int) Math.round(bitmap.getWidth() * scale)),
                Math.max(1, (int) Math.round(bitmap.getHeight() * scale)),
                true);
        quad = new Point[4];
        for (int i = 0; i < 4; i++) quad[i] = new Point(corners[i].x * scale, corners[i].y * scale);
      }
      int sw = work.getWidth();
      int sh = work.getHeight();
      int[] src = pixels(work);
      if (work != bitmap) work.recycle();

      double wTop = distance(quad[0], quad[1]);
      double wBottom = distance(quad[3], quad[2]);
      double hLeft = distance(quad[0], quad[3]);
      double hRight = distance(quad[1], quad[2]);
      double avgW = 0.5 * (wTop + wBottom);
      double avgH = 0.5 * (hLeft + hRight);
      if (avgW < 8 || avgH < 8) return null;
      int rw = DEWARP_ESTIMATE_WIDTH;
      int rh = (int) Math.max(80, Math.min(1200, Math.round(rw * avgH / avgW)));
      int ext = (int) Math.round(rh * DEWARP_ESTIMATE_EXPAND_FRAC);
      int rhx = rh + 2 * ext;

      // Extend the quad along its left and right edges so that the page edges and some background
      // above and below them land inside the band.
      double exL = DEWARP_ESTIMATE_EXPAND_FRAC * hLeft;
      double exR = DEWARP_ESTIMATE_EXPAND_FRAC * hRight;
      double dLx = (quad[3].x - quad[0].x) / hLeft;
      double dLy = (quad[3].y - quad[0].y) / hLeft;
      double dRx = (quad[2].x - quad[1].x) / hRight;
      double dRy = (quad[2].y - quad[1].y) / hRight;
      double[] expanded = {
        quad[0].x - dLx * exL,
        quad[0].y - dLy * exL,
        quad[1].x - dRx * exR,
        quad[1].y - dRy * exR,
        quad[2].x + dRx * exR,
        quad[2].y + dRy * exR,
        quad[3].x + dLx * exL,
        quad[3].y + dLy * exL
      };
      Homography bandToImage = Homography.of(new double[] {0, 0, rw, 0, rw, rhx, 0, rhx}, expanded);
      if (bandToImage == null) return null;
      int[] band = bandToImage.warpReplicate(src, sw, sh, rw, rhx);
      Gray gray = Gray.ofArgb(band, rw, rhx).blurred();
      double thr = gray.otsu();

      int bins = DEWARP_ESTIMATE_BINS;
      int binW = rw / bins;
      int marginX = rw / 20;
      List<double[]> topPts = new ArrayList<>();
      List<double[]> bottomPts = new ArrayList<>();
      int topSaturated = 0;
      int bottomSaturated = 0;
      double[] rowMean = new double[rhx];
      for (int b = 0; b < bins; b++) {
        int x0 = b * binW;
        int x1 = Math.min(rw, x0 + binW);
        if (x1 <= marginX || x0 >= rw - marginX) continue;
        x0 = Math.max(x0, marginX);
        x1 = Math.min(x1, rw - marginX);
        int nx = x1 - x0;
        if (nx <= 0) continue;
        for (int y = 0; y < rhx; y++) {
          int sum = 0;
          for (int x = x0; x < x1; x++) sum += Math.round(gray.at(x, y));
          rowMean[y] = sum / (double) nx;
        }
        int top = -1;
        int bottom = -1;
        for (int y = 0; y < rhx; y++) {
          if (rowMean[y] >= thr) {
            if (top < 0) top = y;
            bottom = y;
          }
        }
        if (top >= 0 && bottom > top) {
          double xc = 0.5 * (x0 + x1);
          topPts.add(new double[] {xc, subpixelCrossing(rowMean, top, thr, true)});
          bottomPts.add(new double[] {xc, subpixelCrossing(rowMean, bottom, thr, false)});
          if (top <= 1) topSaturated++;
          if (bottom >= rhx - 2) bottomSaturated++;
        }
      }
      if (topPts.size() < DEWARP_ESTIMATE_MIN_BINS) {
        Log.d(
            TAG,
            "estimateDewarpEdgeProfiles: too few valid bins (" + topPts.size() + "); no estimate");
        return null;
      }
      List<double[]> topImg = transformEnvelope(topPts, bandToImage);
      List<double[]> bottomImg = transformEnvelope(bottomPts, bandToImage);

      double maxSaturated = topPts.size() * DEWARP_ESTIMATE_MAX_SATURATED_FRAC;
      double[] topProfile =
          topSaturated > maxSaturated ? null : fitEdgeProfile(topImg, quad[0], quad[1]);
      double[] bottomProfile =
          bottomSaturated > maxSaturated ? null : fitEdgeProfile(bottomImg, quad[3], quad[2]);
      if (topProfile == null && bottomProfile == null) {
        Log.d(TAG, "estimateDewarpEdgeProfiles: both edges saturated/invisible; no estimate");
        return null;
      }
      return new double[][] {topProfile, bottomProfile};
    } catch (Throwable t) {
      Log.w(TAG, "estimateDewarpEdgeProfiles failed", t);
      return null;
    }
  }

  private static List<double[]> transformEnvelope(List<double[]> pts, Homography homography) {
    List<double[]> res = new ArrayList<>(pts.size());
    for (double[] p : pts) {
      double[] q = new double[2];
      homography.map(p[0], p[1], q);
      res.add(q);
    }
    return res;
  }

  public static Bitmap trimNonPaperBorder(Bitmap src) {
    return trimNonPaperBorder(src, 0.30, 0.03);
  }

  /**
   * Cuts off rows and columns at the edges in which more than {@code darkRatioThreshold} of the
   * pixels are darker than Otsu's threshold (background left over after cropping), at most {@code
   * maxTrimFraction} of the size per side.
   */
  public static Bitmap trimNonPaperBorder(
      Bitmap src, double darkRatioThreshold, double maxTrimFraction) {
    if (src == null || src.isRecycled()) return src;
    final int w = src.getWidth();
    final int h = src.getHeight();
    if (w < 8 || h < 8) return src;
    try {
      int[] px = pixels(src);
      int[] luma = new int[px.length];
      int[] histogram = new int[256];
      for (int i = 0; i < px.length; i++) histogram[luma[i] = Gray.luma(px[i])]++;
      int thr = otsu(histogram, px.length);
      int[] rowDark = new int[h];
      int[] colDark = new int[w];
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
          if (luma[y * w + x] <= thr) {
            rowDark[y]++;
            colDark[x]++;
          }
        }
      }
      final int maxTrimX = Math.max(0, Math.min(w / 2 - 1, (int) Math.round(w * maxTrimFraction)));
      final int maxTrimY = Math.max(0, Math.min(h / 2 - 1, (int) Math.round(h * maxTrimFraction)));
      final double rowThreshold = w * darkRatioThreshold;
      final double colThreshold = h * darkRatioThreshold;
      int top = 0;
      while (top < maxTrimY && rowDark[top] > rowThreshold) top++;
      int bottom = 0;
      while (bottom < maxTrimY && rowDark[h - 1 - bottom] > rowThreshold) bottom++;
      int left = 0;
      while (left < maxTrimX && colDark[left] > colThreshold) left++;
      int right = 0;
      while (right < maxTrimX && colDark[w - 1 - right] > colThreshold) right++;
      if (top == 0 && bottom == 0 && left == 0 && right == 0) return src;
      int newW = w - left - right;
      int newH = h - top - bottom;
      if (newW < 8 || newH < 8) return src;
      Log.d(
          TAG,
          "trimNonPaperBorder: trimmed L=" + left + " R=" + right + " T=" + top + " B=" + bottom);
      return Bitmap.createBitmap(src, left, top, newW, newH);
    } catch (Throwable t) {
      Log.w(TAG, "trimNonPaperBorder failed: " + t.getMessage());
      return src;
    }
  }

  private static int otsu(int[] histogram, int total) {
    double sum = 0;
    for (int v = 0; v < 256; v++) sum += (double) v * histogram[v];
    double sumBelow = 0;
    int below = 0;
    double best = -1;
    int threshold = 0;
    for (int t = 0; t < 256; t++) {
      below += histogram[t];
      if (below == 0) continue;
      int above = total - below;
      if (above == 0) break;
      sumBelow += (double) t * histogram[t];
      double mb = sumBelow / below;
      double ma = (sum - sumBelow) / above;
      double between = (double) below * above * (mb - ma) * (mb - ma);
      if (between > best) {
        best = between;
        threshold = t;
      }
    }
    return threshold;
  }

  /** Rec. 601 luma as an opaque gray ARGB_8888 bitmap, or null on failure. */
  public static Bitmap toGray(Bitmap src) {
    if (src == null || src.isRecycled()) return null;
    try {
      int[] px = pixels(src);
      for (int i = 0; i < px.length; i++) {
        int v = Gray.luma(px[i]);
        px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
      }
      return Bitmap.createBitmap(px, src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
    } catch (Throwable t) {
      Log.d(TAG, "toGray failed: " + t.getMessage());
      return null;
    }
  }

  /** Black and white for documents (see {@link BlackWhite}), or null on failure. */
  public static Bitmap toBw(Bitmap src) {
    if (src == null || src.isRecycled()) return null;
    try {
      int[] px = pixels(src);
      BlackWhite.apply(px, src.getWidth(), src.getHeight());
      return Bitmap.createBitmap(px, src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
    } catch (Throwable t) {
      Log.d(TAG, "toBw failed: " + t.getMessage());
      return null;
    }
  }

  /**
   * The Enhanced cleanup (see {@link Enhance}): white paper without shadows, black ink, sharpened;
   * in grayscale unless {@code preserveColor}. Returns {@code src} on failure.
   */
  public static Bitmap enhance(Bitmap src, boolean preserveColor) {
    if (src == null || src.isRecycled()) return src;
    try {
      int[] px = pixels(src);
      Enhance.apply(px, src.getWidth(), src.getHeight());
      if (!preserveColor) {
        for (int i = 0; i < px.length; i++) {
          int v = Gray.luma(px[i]);
          px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
      }
      return Bitmap.createBitmap(px, src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
    } catch (Throwable t) {
      Log.w(TAG, "enhance failed", t);
      return src;
    }
  }

  /**
   * Guesses whether the text in a small upright frame runs horizontally (0) or vertically (90):
   * horizontal text lines have more vertical gradient energy than horizontal.
   */
  public static OrientationEstimate estimateTextOrientation(Bitmap uprightSmall) {
    try {
      if (uprightSmall == null || uprightSmall.getWidth() < 8 || uprightSmall.getHeight() < 8) {
        return new OrientationEstimate(0, 0.0);
      }
      int w = uprightSmall.getWidth();
      int h = uprightSmall.getHeight();
      Gray gray = Gray.ofArgb(pixels(uprightSmall), w, h);
      int maxSide = 512;
      int longSide = Math.max(w, h);
      if (longSide > maxSide) {
        double scale = (double) maxSide / longSide;
        gray =
            gray.downscaled(
                Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale)));
      }
      gray = gray.blurred();
      double sx = 0;
      double sy = 0;
      for (float v : gray.sobelX().px) sx += Math.min(255, Math.abs(v));
      for (float v : gray.sobelY().px) sy += Math.min(255, Math.abs(v));
      double total = sx + sy;
      if (total <= 1e-3) return new OrientationEstimate(0, 0.0);
      int bucket = (sy >= sx) ? 0 : 90;
      double conf = Math.min(1.0, Math.abs(sy - sx) / (total + 1e-6)) * 0.9;
      return new OrientationEstimate(bucket, conf);
    } catch (Throwable t) {
      return new OrientationEstimate(0, 0.0);
    }
  }

  /** The bitmap's ARGB pixels, row by row. */
  public static int[] pixels(Bitmap bitmap) {
    int w = bitmap.getWidth();
    int h = bitmap.getHeight();
    int[] px = new int[w * h];
    Bitmap readable = bitmap;
    if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
      readable = bitmap.copy(Bitmap.Config.ARGB_8888, false);
    }
    readable.getPixels(px, 0, w, 0, 0, w, h);
    if (readable != bitmap) readable.recycle();
    return px;
  }

  /** Sagitta (px) below which the curved model is treated as straight (perspective fallback). */
  private static final double DEWARP_STRAIGHT_TOLERANCE_PX = 1.5;

  /** Number of samples used for the arc-length reparameterization of each edge curve. */
  private static final int DEWARP_ARC_SAMPLES = 128;

  /** Grid resolution (per axis) of the coarse remap grid that is upscaled to the target size. */
  private static final int DEWARP_GRID_SIZE = 33;

  /**
   * Fraction of the ruled surface that is cropped away on each side of the dewarp result. Even with
   * well-traced edge profiles, thin slivers of the (anti-aliased) paper edge or of the dark
   * background remain directly at the model boundary; sampling only the inner {@code [inset, 1 -
   * inset]} range of the surface removes these strips at the cost of a barely visible content
   * margin. Applied to both the {@code u} (horizontal) and {@code v} (vertical) parameters.
   */
  private static final double DEWARP_EDGE_INSET_FRAC = 0.012;

  /**
   * Computes the dewarp target size using the same {@link WarpMode} strategy as {@link
   * #applyPerspectiveCorrection(Bitmap, Point[], WarpMode, Double)}.
   */
  private static Size computeDewarpTargetSize(
      Point[] corners,
      WarpMode mode,
      @androidx.annotation.Nullable Double targetShortOverLong,
      int srcWidth,
      int srcHeight) {
    switch (mode) {
      case LEGACY_HEURISTIC:
        return computeWarpTargetSize(corners);
      case FIXED_RATIO:
        if (targetShortOverLong == null
            || !Double.isFinite(targetShortOverLong)
            || targetShortOverLong <= 0.0
            || targetShortOverLong > 1.0) {
          Log.w(
              TAG,
              "applyDewarp: FIXED_RATIO requested without a valid short/long ratio;"
                  + " falling back to AUTO_PROJECTIVE");
          return computeWarpTargetSize(corners, srcWidth, srcHeight);
        }
        return computeWarpTargetSizeForFixedRatio(corners, targetShortOverLong);
      case AUTO_PROJECTIVE:
      default:
        return computeWarpTargetSize(corners, srcWidth, srcHeight);
    }
  }

  /**
   * Samples one edge curve of the model and returns its normalized cumulative arc length at {@code
   * DEWARP_ARC_SAMPLES + 1} uniformly spaced parameter values.
   *
   * @param model the dewarp model
   * @param top {@code true} for the top curve, {@code false} for the bottom curve
   * @return LUT where {@code lut[i]} is the normalized arc length at {@code t = i / SAMPLES}
   */
  private static double[] buildArcLengthLut(DewarpModel model, boolean top) {
    int n = DEWARP_ARC_SAMPLES;
    double[] lut = new double[n + 1];
    Point prev = top ? model.topAt(0) : model.bottomAt(0);
    double acc = 0;
    for (int i = 1; i <= n; i++) {
      double t = i / (double) n;
      Point p = top ? model.topAt(t) : model.bottomAt(t);
      acc += Math.hypot(p.x - prev.x, p.y - prev.y);
      lut[i] = acc;
      prev = p;
    }
    if (acc > 0) {
      for (int i = 0; i <= n; i++) lut[i] /= acc;
    } else {
      for (int i = 0; i <= n; i++) lut[i] = i / (double) n; // degenerate: uniform
    }
    return lut;
  }

  /**
   * Inverts an arc-length LUT: returns the curve parameter {@code t} at which the normalized arc
   * length equals {@code s} (both in {@code [0, 1]}), using linear interpolation.
   */
  private static double lookupArcParam(double[] lut, double s) {
    int n = lut.length - 1;
    if (s <= 0) return 0;
    if (s >= 1) return 1;
    int lo = 0, hi = n;
    while (hi - lo > 1) {
      int mid = (lo + hi) >>> 1;
      if (lut[mid] <= s) lo = mid;
      else hi = mid;
    }
    double span = lut[hi] - lut[lo];
    double frac = span > 0 ? (s - lut[lo]) / span : 0;
    return (lo + frac) / n;
  }

  /** Working width (px) of the rectified page used for the automatic curve estimation. */
  private static final int DEWARP_ESTIMATE_WIDTH = 600;

  /** Number of column bins sampled along the rectified page for the paper-edge envelope. */
  private static final int DEWARP_ESTIMATE_BINS = 32;

  /** Minimum number of valid column bins required for a reliable quadratic fit. */
  private static final int DEWARP_ESTIMATE_MIN_BINS = 10;

  /** Maximum |offset| (fraction of the chord length) returned by the automatic estimation. */
  private static final double DEWARP_ESTIMATE_MAX_OFFSET_FRAC = 0.2;

  /**
   * Vertical expansion of the selection quad (fraction of the quad height, applied on each side)
   * before rectification. The curved page edges typically bulge OUTSIDE the straight corner chords
   * (e.g. the bottom edge of a cylindrically bent page dips below the BL–BR chord), so the plain
   * quad rectification would clip them; the expanded band keeps both edges visible for tracing.
   */
  private static final double DEWARP_ESTIMATE_EXPAND_FRAC = 0.18;

  /**
   * Maximum fraction of column bins whose traced edge may sit at the expanded-band boundary
   * (saturated = page continues beyond the band, i.e. no visible edge) before the estimate for that
   * edge is considered unreliable and reset to {@code 0} (straight).
   */
  private static final double DEWARP_ESTIMATE_MAX_SATURATED_FRAC = 0.3;

  /**
   * Number of uniform samples per edge in the offset profiles returned by {@link
   * #estimateDewarpEdgeProfiles}. Matches the remap grid resolution so the traced edge shape is
   * carried into the warp without further loss.
   */
  private static final int DEWARP_PROFILE_SAMPLES = 33;

  /**
   * Convenience wrapper around {@link #estimateDewarpEdgeProfiles(Bitmap, Point[])} that reduces
   * each traced edge profile to its midpoint offset (the value used for the draggable curve
   * handles). See that method for the algorithm and the sign convention.
   *
   * @param bitmap source bitmap (typically the displayed bitmap of the crop screen)
   * @param corners selection quad in {@code bitmap} image coordinates (TL, TR, BR, BL)
   * @return {@code double[2]} with {@code {topOffsetFrac, bottomOffsetFrac}} ({@code 0} for an edge
   *     without visible curvature), or {@code null} when no reliable estimate is possible
   */
  public static double[] estimateDewarpCurveOffsets(Bitmap bitmap, Point[] corners) {
    double[][] profiles = estimateDewarpEdgeProfiles(bitmap, corners);
    if (profiles == null) return null;
    double top = profiles[0] == null ? 0 : DewarpModel.sampleProfile(profiles[0], 0.5);
    double bottom = profiles[1] == null ? 0 : DewarpModel.sampleProfile(profiles[1], 0.5);
    return new double[] {top, bottom};
  }

  /**
   * Refines an Otsu-threshold crossing to subpixel precision by linear interpolation between the
   * first row at/above the threshold and its neighbor just outside the paper.
   *
   * @param rowMean per-row mean gray values of the current column bin
   * @param idx index of the first (top) or last (bottom) row whose mean is {@code >= thr}
   * @param thr Otsu threshold value
   * @param top {@code true} for the top edge (neighbor above), {@code false} for the bottom edge
   */
  private static double subpixelCrossing(double[] rowMean, int idx, double thr, boolean top) {
    int j = top ? idx - 1 : idx + 1;
    if (j < 0 || j >= rowMean.length) return idx;
    double inside = rowMean[idx];
    double outside = rowMean[j];
    if (inside == outside) return idx;
    double f = (thr - outside) / (inside - outside);
    return j + f * (idx - j);
  }

  /**
   * Fits an edge offset profile to traced envelope points in IMAGE space and samples it into {@link
   * #DEWARP_PROFILE_SAMPLES} uniform chord-normalized offsets.
   *
   * <p>Each point is projected onto the edge chord {@code p0 → p2}: {@code u} = normalized chord
   * parameter, {@code off} = signed distance along the +90° chord normal, normalized by the chord
   * length (the model's sign convention). A corner-constrained quartic {@code off(u) = u(1-u)(c0 +
   * c1·u + c2·u²)} is fitted by least squares — it vanishes at both corners by construction, is
   * robust against bin noise and approximates typical page-edge shapes (parabolic AND sine-like
   * bends) to a fraction of a percent, which keeps the warp accurate near the corners.
   *
   * @return the sampled profile, or {@code null} when the fit is degenerate
   */
  private static double[] fitEdgeProfile(java.util.List<double[]> pts, Point p0, Point p2) {
    if (pts == null || pts.size() < 5) return null;
    double dx = p2.x - p0.x, dy = p2.y - p0.y;
    double len = Math.hypot(dx, dy);
    if (len < 1e-9) return null;
    double dirX = dx / len, dirY = dy / len;
    double nX = -dirY, nY = dirX;
    int m = pts.size();
    double[] us = new double[m];
    double[] offs = new double[m];
    for (int i = 0; i < m; i++) {
      double px = pts.get(i)[0] - p0.x, py = pts.get(i)[1] - p0.y;
      us[i] = (px * dirX + py * dirY) / len;
      offs[i] = (px * nX + py * nY) / len;
    }
    // Least squares for off(u) = c0*f1 + c1*f2 + c2*f3 with f1=u(1-u), f2=u²(1-u), f3=u³(1-u):
    // solve the 3x3 normal equations with Cramer's rule.
    double s11 = 0, s12 = 0, s13 = 0, s22 = 0, s23 = 0, s33 = 0, b1 = 0, b2 = 0, b3 = 0;
    for (int i = 0; i < m; i++) {
      double u = us[i];
      double f1 = u * (1 - u);
      double f2 = u * f1;
      double f3 = u * f2;
      s11 += f1 * f1;
      s12 += f1 * f2;
      s13 += f1 * f3;
      s22 += f2 * f2;
      s23 += f2 * f3;
      s33 += f3 * f3;
      b1 += f1 * offs[i];
      b2 += f2 * offs[i];
      b3 += f3 * offs[i];
    }
    double det =
        s11 * (s22 * s33 - s23 * s23)
            - s12 * (s12 * s33 - s23 * s13)
            + s13 * (s12 * s23 - s22 * s13);
    if (!Double.isFinite(det) || Math.abs(det) < 1e-12) return null;
    double c0 =
        (b1 * (s22 * s33 - s23 * s23) - s12 * (b2 * s33 - b3 * s23) + s13 * (b2 * s23 - b3 * s22))
            / det;
    double c1 =
        (s11 * (b2 * s33 - b3 * s23) - b1 * (s12 * s33 - s23 * s13) + s13 * (s12 * b3 - b2 * s13))
            / det;
    double c2 =
        (s11 * (s22 * b3 - b2 * s23) - s12 * (s12 * b3 - b2 * s13) + b1 * (s12 * s23 - s22 * s13))
            / det;
    if (!Double.isFinite(c0) || !Double.isFinite(c1) || !Double.isFinite(c2)) return null;
    int n = DEWARP_PROFILE_SAMPLES;
    double[] profile = new double[n];
    for (int i = 0; i < n; i++) {
      double u = i / (double) (n - 1);
      double v = u * (1 - u) * (c0 + c1 * u + c2 * u * u);
      profile[i] = clamp(v, -DEWARP_ESTIMATE_MAX_OFFSET_FRAC, DEWARP_ESTIMATE_MAX_OFFSET_FRAC);
    }
    return profile;
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  /**
   * Applies a perspective warp transformation to a given bitmap using specified corner points and
   * produces a new bitmap with the target dimensions.
   *
   * @param srcBitmap the source bitmap to be transformed.
   * @param corners an array of four {@link Point} objects representing the corner points of the
   *     region in the source bitmap to be warped. The points should be in the order: top-left,
   *     top-right, bottom-right, bottom-left.
   * @param targetSize the dimensions of the output bitmap, specified as a {@link Size} object.
   * @return a new {@link Bitmap} object containing the perspective-warped image with the specified
   *     dimensions. If the corners array is null or does not contain exactly four points, the
   *     source bitmap is returned as-is.
   */
  private static Bitmap warpPerspectiveWithMatrix(
      Bitmap srcBitmap, Point[] corners, Size targetSize) {
    if (corners == null || corners.length != 4) return srcBitmap;

    int width = Math.max(1, (int) Math.round(targetSize.width));
    int height = Math.max(1, (int) Math.round(targetSize.height));

    float[] src =
        new float[] {
          (float) corners[0].x,
          (float) corners[0].y,
          (float) corners[1].x,
          (float) corners[1].y,
          (float) corners[2].x,
          (float) corners[2].y,
          (float) corners[3].x,
          (float) corners[3].y
        };

    float[] dst = new float[] {0, 0, width, 0, width, height, 0, height};

    Matrix matrix = new Matrix();
    matrix.setPolyToPoly(src, 0, dst, 0, 4);

    Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(output);
    Paint paint = new Paint();
    paint.setAntiAlias(true);
    paint.setFilterBitmap(true);
    canvas.drawBitmap(srcBitmap, matrix, paint);
    return output;
  }

  /**
   * Returns a simple centered fallback rectangle (as RectF) with ~10% margin on each side. This
   * mirrors the margin logic of the internal Point[] variant and is suitable as a stable model
   * reference for the FramingEngine when no detection is available.
   *
   * @param width upright image width in pixels
   * @param height upright image height in pixels
   * @return RectF representing the fallback rectangle in the same coordinate space
   */
  public static RectF getFallbackRectF(int width, int height) {
    int m = Math.max(20, Math.min(width, height) / 10); // ~10% margin
    return new RectF(m, m, width - m, height - m);
  }

  /**
   * Strategy for {@link #applyPerspectiveCorrection(Bitmap, Point[], WarpMode, Double)}. See {@code
   * docs/aspect_ratio_concept_v3.8.0.md} (§5).
   */
  public enum WarpMode {
    /** Stage A: projective aspect-ratio estimate (Zhang & He, 2006), with heuristic fallback. */
    AUTO_PROJECTIVE,
    /** v3.7.1 pixel-distance heuristic, no projective correction. */
    LEGACY_HEURISTIC,
    /** Enforce a user-supplied short/long edge ratio. */
    FIXED_RATIO
  }

  /**
   * Computes the warp target size for an explicit short/long edge ratio. Orientation is derived
   * from the quad itself (which axis is longer in the image), and the longer pixel edge anchors the
   * output resolution so no upscaling occurs. See {@code docs/aspect_ratio_concept_v3.8.0.md}
   * (§5.3).
   *
   * @param corners four corners (TL, TR, BR, BL)
   * @param shortOverLong short/long edge ratio in {@code (0, 1]}
   */
  static Size computeWarpTargetSizeForFixedRatio(Point[] corners, double shortOverLong) {
    if (corners == null || corners.length != 4) {
      return new Size(1, 1);
    }
    if (!(shortOverLong > 0.0) || shortOverLong > 1.0 || !Double.isFinite(shortOverLong)) {
      return new Size(1, 1);
    }
    double wTop = distance(corners[0], corners[1]);
    double wBottom = distance(corners[2], corners[3]);
    double hLeft = distance(corners[0], corners[3]);
    double hRight = distance(corners[1], corners[2]);
    double meanW = 0.5 * (wTop + wBottom);
    double meanH = 0.5 * (hLeft + hRight);
    double longPx = Math.max(Math.max(wTop, wBottom), Math.max(hLeft, hRight));
    if (longPx < 1.0) {
      return new Size(1, 1);
    }
    boolean landscapeQuad = meanW >= meanH;
    int w;
    int h;
    if (landscapeQuad) {
      w = (int) Math.round(longPx);
      h = (int) Math.round(longPx * shortOverLong);
    } else {
      h = (int) Math.round(longPx);
      w = (int) Math.round(longPx * shortOverLong);
    }
    w = Math.max(1, w) + 1;
    h = Math.max(1, h) + 1;
    Log.d(
        TAG,
        "computeWarpTargetSizeForFixedRatio: short/long="
            + String.format(java.util.Locale.US, "%.4f", shortOverLong)
            + ", target="
            + w
            + "x"
            + h);
    return new Size(w, h);
  }

  /** Focal length of a phone's main camera as a fraction of the long image edge (~26 mm equiv.). */
  static final double PHONE_FOCAL_FRACTION = 0.75;

  /**
   * Estimates the true width/height aspect ratio of the rectangle whose perspective-projected image
   * corners are given, using the closed-form solution from Zhang & He (2006). Returns {@code null}
   * if the estimate is degenerate (near-fronto-parallel quad, division by zero, negative focal
   * length squared, etc.).
   *
   * <p>Corner order is TL, TR, BR, BL.
   *
   * @param corners four image-space corners
   * @param srcWidth width of the source image (pixels)
   * @param srcHeight height of the source image (pixels)
   * @return estimated W/H ratio, or {@code null} if not reliably computable
   */
  static Double estimateProjectiveAspectRatio(Point[] corners, int srcWidth, int srcHeight) {
    if (corners == null || corners.length != 4 || srcWidth <= 0 || srcHeight <= 0) {
      return null;
    }
    // Principal point assumed at image centre.
    double cx = srcWidth * 0.5;
    double cy = srcHeight * 0.5;

    // Centred image coordinates of the four corners (TL, TR, BR, BL).
    double[] p0 = {corners[0].x - cx, corners[0].y - cy};
    double[] p1 = {corners[1].x - cx, corners[1].y - cy};
    double[] p2 = {corners[2].x - cx, corners[2].y - cy};
    double[] p3 = {corners[3].x - cx, corners[3].y - cy};

    // Closed-form algorithm from "Whiteboard Scanning and Image Enhancement"
    // (Zhang & He, 2006), simplified for principal point at image centre and zero skew.
    //
    // Map our corner order TL(0), TR(1), BR(2), BL(3) onto the paper's
    //   m1 = TL = p0
    //   m2 = TR = p1
    //   m3 = BR = p2
    //   m4 = BL = p3
    //
    // Build lifted homogeneous points m_i = (x_i, y_i, 1).
    // Compute scalars k2, k3 so that:
    //   k2 * m2 + m1 - m3 = lambda * (point on plane), etc.
    // Following the standard derivation:
    //   k2 = det([m1 m4 m3]) / det([m2 m4 m3])
    //   k3 = det([m1 m2 m3]) / det([m4 m2 m3])
    // where det([a b c]) of homogeneous 3-vectors (with z=1) reduces to a 3x3 determinant.
    double k2num = det3(p0[0], p0[1], 1, p3[0], p3[1], 1, p2[0], p2[1], 1);
    double k2den = det3(p1[0], p1[1], 1, p3[0], p3[1], 1, p2[0], p2[1], 1);
    double k3num = det3(p0[0], p0[1], 1, p1[0], p1[1], 1, p2[0], p2[1], 1);
    double k3den = det3(p3[0], p3[1], 1, p1[0], p1[1], 1, p2[0], p2[1], 1);
    if (Math.abs(k2den) < 1e-9 || Math.abs(k3den) < 1e-9) {
      return null;
    }
    double k2 = k2num / k2den;
    double k3 = k3num / k3den;

    // n2 = k2 * m2 - m1, n3 = k3 * m4 - m1   (in homogeneous space, z components: k2*1 - 1, etc.)
    double n2x = k2 * p1[0] - p0[0];
    double n2y = k2 * p1[1] - p0[1];
    double n2z = k2 - 1.0;
    double n3x = k3 * p3[0] - p0[0];
    double n3y = k3 * p3[1] - p0[1];
    double n3z = k3 - 1.0;

    // If the quad is (almost) fronto-parallel, k2 and k3 collapse to 1 → n*z ≈ 0 →
    // f² is undefined. Detect and bail out so the caller can use the heuristic fallback.
    if (Math.abs(n2z) < 1e-6 || Math.abs(n3z) < 1e-6) {
      return null;
    }

    // Closed-form focal length squared estimate:
    //   f² = - (n2x*n3x + n2y*n3y) / (n2z * n3z)
    double fSquared = -(n2x * n3x + n2y * n3y) / (n2z * n3z);
    if (!Double.isFinite(fSquared) || fSquared <= 0.0) {
      // No real solution (common for photos taken nearly straight on): a phone's main camera,
      // about 26 mm equivalent, i.e. 0.75 of the long image edge. 1.0 was assumed before; on 70
      // such labelled photos (Sheet's corner evaluation) 0.75 puts 44 within 0.03 of A4 or Letter,
      // 1.0 puts 36.
      fSquared = PHONE_FOCAL_FRACTION * Math.max(srcWidth, srcHeight);
      fSquared *= fSquared;
    }

    // Aspect ratio squared:
    //   (W/H)² = (n2x² + n2y² + n2z² * f²) / (n3x² + n3y² + n3z² * f²)
    double num = n2x * n2x + n2y * n2y + n2z * n2z * fSquared;
    double den = n3x * n3x + n3y * n3y + n3z * n3z * fSquared;
    if (den < 1e-12) {
      return null;
    }
    double ratioSq = num / den;
    if (!Double.isFinite(ratioSq) || ratioSq <= 0.0) {
      return null;
    }
    return Math.sqrt(ratioSq);
  }

  /** 3x3 determinant of the matrix whose rows are (a1,a2,a3), (b1,b2,b3), (c1,c2,c3). */
  private static double det3(
      double a1,
      double a2,
      double a3,
      double b1,
      double b2,
      double b3,
      double c1,
      double c2,
      double c3) {
    return a1 * (b2 * c3 - b3 * c2) - a2 * (b1 * c3 - b3 * c1) + a3 * (b1 * c2 - b2 * c1);
  }

  /**
   * Calculates the Euclidean distance between two points.
   *
   * @param a the first point, represented as an object of type Point
   * @param b the second point, represented as an object of type Point
   * @return the distance between the two points as a double
   */
  private static double distance(Point a, Point b) {
    return Math.hypot(a.x - b.x, a.y - b.y);
  }

  /**
   * Result of {@link #estimateTextOrientation(Bitmap)}. bucket is 0 or 90 (degrees), representing a
   * coarse orientation class. confidence in [0..1], where higher means a clearer separation.
   *
   * @param bucketDeg 0 or 90
   * @param confidence 0..1
   */
  public record OrientationEstimate(int bucketDeg, double confidence) {
    public OrientationEstimate(int bucketDeg, double confidence) {
      this.bucketDeg = (bucketDeg == 90) ? 90 : 0;
      this.confidence = Math.max(0.0, Math.min(1.0, confidence));
    }
  }

  /**
   * Computes a tight target size (width/height) for the warp based on the lengths of the selected
   * quadrilateral edges. This preserves the aspect ratio of the selected area when mapping to a
   * rectangle.
   *
   * <p>Legacy heuristic: pixel-distance based. Used as a fallback when the projective estimate is
   * not available.
   */
  private static Size computeWarpTargetSize(Point[] corners) {
    if (corners == null || corners.length != 4) {
      return new Size(1, 1);
    }
    double wTop = distance(corners[0], corners[1]);
    double wBottom = distance(corners[2], corners[3]);
    double hLeft = distance(corners[0], corners[3]);
    double hRight = distance(corners[1], corners[2]);
    // Use inclusive pixel lengths: if corners span from 0..(W-1), distance is (W-1)
    // but target size must be W to preserve identity mapping. Hence +1.
    int w = Math.max(1, (int) Math.round(Math.max(wTop, wBottom)) + 1);
    int h = Math.max(1, (int) Math.round(Math.max(hLeft, hRight)) + 1);
    return new Size(w, h);
  }

  /**
   * Computes a tight target size (width/height) for the warp using a projective aspect-ratio
   * estimation (Zhang & He, 2006, "Whiteboard Scanning and Image Enhancement"). The image's
   * principal point is assumed to be at its centre and the focal length is estimated from the
   * geometry of the four corners (with a fallback of {@code f ≈ 0.75 · max(srcWidth, srcHeight)} if
   * the closed-form estimate is degenerate).
   *
   * <p>If the projective estimate is degenerate (e.g., near-fronto-parallel quad, parallel
   * vanishing rays, or numerically unstable), the method falls back to the pixel-distance heuristic
   * implemented by {@link #computeWarpTargetSize(Point[])}.
   *
   * <p>The returned pixel dimensions are anchored to the longest observed quad edge so that the
   * output resolution stays close to the original capture and no upscaling occurs.
   *
   * @param corners four corner points in order TL, TR, BR, BL (image pixel coordinates)
   * @param srcWidth width of the source bitmap (pixels)
   * @param srcHeight height of the source bitmap (pixels)
   * @return target {@link Size} for the warp
   */
  static Size computeWarpTargetSize(Point[] corners, int srcWidth, int srcHeight) {
    if (corners == null || corners.length != 4) {
      return new Size(1, 1);
    }
    double wTop = distance(corners[0], corners[1]);
    double wBottom = distance(corners[2], corners[3]);
    double hLeft = distance(corners[0], corners[3]);
    double hRight = distance(corners[1], corners[2]);
    double meanW = 0.5 * (wTop + wBottom);
    double meanH = 0.5 * (hLeft + hRight);
    double longPx = Math.max(Math.max(wTop, wBottom), Math.max(hLeft, hRight));
    if (longPx < 1.0) {
      return new Size(1, 1);
    }

    Double widthOverHeight = estimateProjectiveAspectRatio(corners, srcWidth, srcHeight);
    if (widthOverHeight == null
        || !Double.isFinite(widthOverHeight)
        || widthOverHeight <= 0.0
        || widthOverHeight > 100.0
        || widthOverHeight < 0.01) {
      Log.d(
          TAG, "computeWarpTargetSize: projective estimate unavailable, falling back to heuristic");
      return computeWarpTargetSize(corners);
    }

    // Decide orientation from observed quad: which axis is longer in the image?
    boolean landscapeQuad = meanW >= meanH;
    int w;
    int h;
    if (landscapeQuad) {
      // long side = width
      w = (int) Math.round(longPx);
      h = (int) Math.round(longPx / widthOverHeight);
    } else {
      // long side = height; the projective ratio is W/H from the corner ordering, which already
      // reflects orientation, so use it directly
      h = (int) Math.round(longPx);
      w = (int) Math.round(longPx * widthOverHeight);
    }
    w = Math.max(1, w) + 1;
    h = Math.max(1, h) + 1;
    Log.d(
        TAG,
        "computeWarpTargetSize: projective W/H="
            + String.format(java.util.Locale.US, "%.4f", widthOverHeight)
            + ", target="
            + w
            + "x"
            + h);
    return new Size(w, h);
  }
}
