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

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.util.Log;
import java.util.*;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

/**
 * Utility class for performing various operations with OpenCV.
 *
 * <p>This class cannot be instantiated.
 */
@UtilityClass
public final class OpenCVUtils {
  private static final String TAG = "OpenCVUtils";

  @Getter private static boolean isInitialized = false;

  private static boolean USE_SAFE_MODE = true;

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

  public static boolean isSafeMode() {
    return USE_SAFE_MODE;
  }

  /**
   * Initializes OpenCV by loading the native library. This method should be called before using any
   * OpenCV functionality.
   *
   * @param context The application context.
   * @return true if OpenCV was initialized successfully, false otherwise.
   */
  public static boolean init(Context context) {
    if (isInitialized) return true;

    try {
      System.loadLibrary("opencv_java4");
      Log.i(TAG, "OpenCV loaded manually via System.loadLibrary");
      configureRuntime();
      configureSafeMode();
      isInitialized = true;
    } catch (Throwable t) {
      Log.e(TAG, "OpenCV init error", t);
    }

    return isInitialized;
  }

  private static void configureRuntime() {
    try {
      org.opencv.core.Core.setNumThreads(1);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical.
    }
  }

  /**
   * Configures the safe mode based on the device's specifications and characteristics.
   *
   * <p>This method evaluates the device manufacturer, model, device name, and Android SDK version
   * to determine whether the device is classified as high-end or an emulator. Using this
   * evaluation, it configures the `USE_SAFE_MODE` flag accordingly.
   *
   * <p>Conditions for classifying a device as high-end include: - SDK version 29 or higher. - The
   * manufacturer does not contain "mediatek" or "spreadtrum". - The device name does not contain
   * "generic". - The model does not contain "emulator" or "x86"/"x86_64". - The manufacturer is
   * associated with reputable brands like Google, Samsung, or Xiaomi.
   *
   * <p>Conditions for identifying a device as an emulator include: - The device name contains
   * "emu", "x86", or "x86_64". - The model contains "sdk", "emulator", or "virtual". - The
   * manufacturer contains "genymotion".
   *
   * <p>Based on the classification, `USE_SAFE_MODE` is enabled if the device is not high-end or is
   * identified as an emulator.
   *
   * <p>The method logs the safe mode configuration for debugging purposes.
   */
  private static void configureSafeMode() {
    String manufacturer = Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
    String model = Build.MODEL.toLowerCase(java.util.Locale.ROOT);
    String device = Build.DEVICE.toLowerCase(java.util.Locale.ROOT);
    String fingerprint = Build.FINGERPRINT.toLowerCase(java.util.Locale.ROOT);
    String hardware = Build.HARDWARE.toLowerCase(java.util.Locale.ROOT);
    String product = Build.PRODUCT.toLowerCase(java.util.Locale.ROOT);
    int sdk = Build.VERSION.SDK_INT;

    boolean isHighEnd =
        sdk >= 29
            && !manufacturer.contains("mediatek")
            && !manufacturer.contains("spreadtrum")
            && !device.contains("generic")
            && !model.contains("emulator")
            && !device.contains("x86")
            && !device.contains("x86_64")
            && (manufacturer.contains("google")
                || manufacturer.contains("samsung")
                || manufacturer.contains("xiaomi"));
    // Improved emulator detection: check fingerprint, hardware, and product for emulator patterns
    // This catches arm64 emulators that don't have x86 in their device name
    boolean isEmulator =
        device.contains("emu")
            || model.contains("sdk")
            || model.contains("emulator")
            || model.contains("virtual")
            || manufacturer.contains("genymotion")
            || model.contains("generator")
            || fingerprint.contains("generic")
            || fingerprint.contains("sdk")
            || fingerprint.contains("emulator")
            || hardware.contains("goldfish")
            || hardware.contains("ranchu")
            || product.contains("sdk")
            || product.contains("emulator")
            || product.contains("google_sdk");

    USE_SAFE_MODE = !isHighEnd || isEmulator;

    Log.i(TAG, "Safe mode = " + USE_SAFE_MODE);
    try {
      if (USE_SAFE_MODE) {
        // Disable aggressive SIMD/parallel optimizations that may use unsupported instructions on
        // some CPUs
        org.opencv.core.Core.setUseOptimized(false);
        org.opencv.core.Core.setNumThreads(1);
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * Applies a perspective transformation to the given input matrix (image) using the specified
   * source points and maps it to a target size, ensuring the resulting perspective transformation
   * fits within the target dimensions. The function ensures safe handling of invalid inputs and
   * cleans up intermediate resources.
   *
   * @param input The input image represented as a {@code Mat} object. Must not be null or empty.
   * @param srcPoints An array of four {@code Point} objects specifying the source quadrilateral to
   *     be transformed. Must not be null and must contain exactly four points.
   * @param targetSize The target size for the output image, represented as a {@code Size} object.
   *     Specifies the dimensions (width and height) of the transformed image.
   * @param borderMode OpenCV border mode for pixels sampled from outside {@code input} (e.g. {@link
   *     Core#BORDER_CONSTANT} to fill with black, {@link Core#BORDER_REPLICATE} to extend the edge
   *     pixels). Only matters when {@code srcPoints} extends beyond {@code input}'s bounds.
   * @return A new {@code Mat} object containing the transformed (warped) image. If an error occurs
   *     or invalid input is provided, the original input image is returned.
   */
  private static Mat warpPerspectiveSafe(
      Mat input, Point[] srcPoints, Size targetSize, int borderMode) {
    if (input == null || input.empty() || srcPoints == null || srcPoints.length != 4) {
      Log.e(TAG, "Invalid input or source points");
      return input;
    }

    Mat srcMat = new Mat(4, 1, CvType.CV_32FC2);
    Mat dstMat = new Mat(4, 1, CvType.CV_32FC2);
    Mat transform = new Mat();
    Mat output = new Mat();
    try {
      Point[] dstPoints =
          new Point[] {
            new Point(0, 0),
            new Point(targetSize.width - 1, 0),
            new Point(targetSize.width - 1, targetSize.height - 1),
            new Point(0, targetSize.height - 1)
          };

      for (int i = 0; i < 4; i++) {
        srcMat.put(i, 0, srcPoints[i].x, srcPoints[i].y);
        dstMat.put(i, 0, dstPoints[i].x, dstPoints[i].y);
      }

      transform = Imgproc.getPerspectiveTransform(srcMat, dstMat);
      Imgproc.warpPerspective(
          input, output, transform, targetSize, Imgproc.INTER_LINEAR, borderMode);
      return output;
    } catch (Throwable t) {
      Log.e(TAG, "warpPerspective failed", t);
      release(output);
      return input;
    } finally {
      release(srcMat, dstMat, transform);
    }
  }

  /**
   * Applies a perspective correction to the given bitmap based on the specified corner points. This
   * method attempts to correct the image's perspective distortion by warping it to a target size
   * while maintaining the aspect ratio of the selected area defined by the corners. The
   * implementation uses OpenCV's warpPerspective if available and falls back to Android's
   * Matrix-based transformation if in safe mode.
   *
   * @param originalBitmap The input bitmap to which the perspective correction will be applied.
   *     This cannot be null.
   * @param corners An array of four points that represent the corners of the area to be corrected.
   *     These points must be in the order: top-left, top-right, bottom-right, bottom-left. The
   *     array must have exactly four points; otherwise, the original bitmap will be returned.
   * @return A new bitmap with the perspective correction applied. If an error occurs or the
   *     parameters are invalid, the original bitmap is returned unmodified.
   */
  public static Bitmap applyPerspectiveCorrection(Bitmap originalBitmap, Point[] corners) {
    return applyPerspectiveCorrection(originalBitmap, corners, WarpMode.AUTO_PROJECTIVE, null);
  }

  /**
   * Forces the legacy pixel-distance heuristic for the warp target size, ignoring the projective
   * estimate from stage A (v3.7.2). This is the implementation of the "Keep original (no aspect
   * correction)" path in the crop screen aspect-ratio selector. See {@code
   * docs/aspect_ratio_concept_v3.8.0.md} (§5.4).
   */
  public static Bitmap applyPerspectiveCorrectionLegacyHeuristic(
      Bitmap originalBitmap, Point[] corners) {
    return applyPerspectiveCorrection(originalBitmap, corners, WarpMode.LEGACY_HEURISTIC, null);
  }

  /**
   * Applies a perspective correction with an explicit warp mode and optional fixed target ratio.
   *
   * <p>This is the stage B (v3.8.0) overload of the warp pipeline. The {@code mode} parameter
   * selects how the target rectangle's size is determined:
   *
   * <ul>
   *   <li>{@link WarpMode#AUTO_PROJECTIVE}: stage A behaviour — projective estimate (Zhang & He,
   *       2006) with heuristic fallback. {@code targetShortOverLong} is ignored.
   *   <li>{@link WarpMode#LEGACY_HEURISTIC}: forces the v3.7.1 pixel-distance heuristic. {@code
   *       targetShortOverLong} is ignored.
   *   <li>{@link WarpMode#FIXED_RATIO}: enforces the supplied {@code targetShortOverLong} ratio
   *       (short/long edge in {@code (0, 1]}). Orientation (portrait vs. landscape) is derived from
   *       the quad itself; the longer pixel edge anchors the output resolution so no upscaling
   *       occurs.
   * </ul>
   *
   * <p>If {@code mode} is {@link WarpMode#FIXED_RATIO} but {@code targetShortOverLong} is missing
   * or out of range, the method falls back to {@link WarpMode#AUTO_PROJECTIVE}.
   *
   * @param originalBitmap source bitmap
   * @param corners four corners of the selection (TL, TR, BR, BL)
   * @param mode warp target-size strategy; see above
   * @param targetShortOverLong short/long edge ratio in {@code (0, 1]}; only used when {@code mode
   *     == FIXED_RATIO}
   * @return the cropped bitmap, or {@code originalBitmap} when corners are invalid
   */
  public static Bitmap applyPerspectiveCorrection(
      Bitmap originalBitmap,
      Point[] corners,
      WarpMode mode,
      @androidx.annotation.Nullable Double targetShortOverLong) {
    if (corners == null || corners.length != 4) return originalBitmap;
    if (mode == null) mode = WarpMode.AUTO_PROJECTIVE;
    Bitmap bitmapForOpenCv = ensureBitmapToMatCompatible(originalBitmap);
    Mat mat = new Mat();
    try {
      Utils.bitmapToMat(bitmapForOpenCv, mat);
      // Compute a tight target size based on the selection to preserve aspect ratio of the cropped
      // area. The strategy depends on the requested WarpMode:
      //   - AUTO_PROJECTIVE: projective aspect-ratio estimation (Zhang & He, 2006) with
      //     heuristic fallback when degenerate. See docs/aspect_ratio_concept_v3.7.2.md.
      //   - LEGACY_HEURISTIC: pixel-distance heuristic only.
      //   - FIXED_RATIO: enforce the user-supplied short/long ratio; see
      //     docs/aspect_ratio_concept_v3.8.0.md (§5).
      Size targetSize;
      switch (mode) {
        case LEGACY_HEURISTIC:
          targetSize = computeWarpTargetSize(corners);
          break;
        case FIXED_RATIO:
          if (targetShortOverLong == null
              || !Double.isFinite(targetShortOverLong)
              || targetShortOverLong <= 0.0
              || targetShortOverLong > 1.0) {
            Log.w(
                TAG,
                "applyPerspectiveCorrection: FIXED_RATIO requested without a valid"
                    + " short/long ratio; falling back to AUTO_PROJECTIVE");
            targetSize =
                computeWarpTargetSize(
                    corners, originalBitmap.getWidth(), originalBitmap.getHeight());
          } else {
            targetSize = computeWarpTargetSizeForFixedRatio(corners, targetShortOverLong);
          }
          break;
        case AUTO_PROJECTIVE:
        default:
          targetSize =
              computeWarpTargetSize(corners, originalBitmap.getWidth(), originalBitmap.getHeight());
          break;
      }
      if (!isSafeMode()) {
        Log.d(TAG, "Using OpenCV warpPerspective");
        Mat warped = warpPerspectiveSafe(mat, corners, targetSize, Core.BORDER_CONSTANT);
        try {
          Bitmap output =
              Bitmap.createBitmap(
                  (int) targetSize.width, (int) targetSize.height, Bitmap.Config.ARGB_8888);
          Utils.matToBitmap(warped, output);
          return output;
        } finally {
          release(warped);
        }
      } else {
        Log.d(TAG, "Using Android Matrix warp fallback");
        return warpPerspectiveWithMatrix(originalBitmap, corners, targetSize);
      }
    } finally {
      release(mat);
    }
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
   * Applies a dewarp (crop + flattening of a cylindrically curved page) to the given bitmap based
   * on a {@link DewarpModel} (issue #91, Phase 1).
   *
   * <p>The model's top and bottom edges are quadratic Bezier curves; the side edges are straight.
   * The method builds a ruled-surface mapping between the two arc-length-parameterized curves,
   * renders it into coarse {@code map_x}/{@code map_y} grids, upscales them to the target size and
   * resamples the source via {@link Imgproc#remap}. The coarse grid keeps the memory overhead
   * negligible while remaining exact for the smooth cylindrical mapping.
   *
   * <p>The target size is determined exactly like in {@link #applyPerspectiveCorrection(Bitmap,
   * Point[], WarpMode, Double)}, using the model's four corners. When the curves are effectively
   * straight, the method delegates to the plain perspective pipeline (identical behaviour, cheaper
   * and battle-tested).
   *
   * @param originalBitmap source bitmap
   * @param model dewarp model in source-bitmap pixel coordinates
   * @param mode warp target-size strategy (see {@link #applyPerspectiveCorrection(Bitmap, Point[],
   *     WarpMode, Double)})
   * @param targetShortOverLong short/long edge ratio in {@code (0, 1]}; only used when {@code mode
   *     == FIXED_RATIO}
   * @return the dewarped bitmap, or {@code originalBitmap} when the input is invalid or an error
   *     occurs
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

    // Straight curves (and neutral depth): identical to a perspective warp; use the existing
    // (cheaper) pipeline. A non-zero depth changes the vertical distribution and therefore must
    // go through the remap path even when the curves are straight.
    if (model.isEffectivelyStraight(DEWARP_STRAIGHT_TOLERANCE_PX) && model.getDepth() == 0.0) {
      Log.d(TAG, "applyDewarp: curves effectively straight; using perspective pipeline");
      return applyPerspectiveCorrection(originalBitmap, corners, mode, targetShortOverLong);
    }

    Bitmap bitmapForOpenCv = ensureBitmapToMatCompatible(originalBitmap);
    Mat src = new Mat();
    Mat gridX = new Mat();
    Mat gridY = new Mat();
    Mat mapX = new Mat();
    Mat mapY = new Mat();
    Mat dst = new Mat();
    try {
      Utils.bitmapToMat(bitmapForOpenCv, src);

      Size targetSize =
          computeDewarpTargetSize(
              corners,
              mode,
              targetShortOverLong,
              originalBitmap.getWidth(),
              originalBitmap.getHeight());
      int outW = Math.max(1, (int) Math.round(targetSize.width));
      int outH = Math.max(1, (int) Math.round(targetSize.height));

      // Arc-length reparameterization of both curves so that equal horizontal steps in the output
      // correspond to equal path lengths on the (cylindrical) page surface.
      double[] topArcLut = buildArcLengthLut(model, true);
      double[] bottomArcLut = buildArcLengthLut(model, false);

      // Coarse mapping grid: grid(u, v) = (1 - w) * top(u) + w * bottom(u), a ruled surface
      // between the two curves (straight generatrices = straight side edges). The blend weight
      // w = model.blendWeight(v) applies the perspective-depth fine adjustment (issue #91,
      // Phase 3); for depth == 0 it is simply w = v (linear blend).
      int g = DEWARP_GRID_SIZE;
      float[] gx = new float[g * g];
      float[] gy = new float[g * g];
      // Sample only the inner part of the surface (small symmetric inset) so that residual
      // paper-edge/background slivers directly at the model boundary do not end up in the result.
      double inset = DEWARP_EDGE_INSET_FRAC;
      double span = 1.0 - 2.0 * inset;
      for (int j = 0; j < g; j++) {
        double v = inset + span * (j / (double) (g - 1));
        double w = model.blendWeight(v);
        for (int i = 0; i < g; i++) {
          double u = inset + span * (i / (double) (g - 1));
          double tTop = lookupArcParam(topArcLut, u);
          double tBottom = lookupArcParam(bottomArcLut, u);
          Point pTop = model.topAt(tTop);
          Point pBottom = model.bottomAt(tBottom);
          gx[j * g + i] = (float) ((1.0 - w) * pTop.x + w * pBottom.x);
          gy[j * g + i] = (float) ((1.0 - w) * pTop.y + w * pBottom.y);
        }
      }
      gridX.create(g, g, CvType.CV_32FC1);
      gridY.create(g, g, CvType.CV_32FC1);
      gridX.put(0, 0, gx);
      gridY.put(0, 0, gy);

      // Upscale the smooth grid to full map resolution (memory-friendly; see concept §Speicher).
      Imgproc.resize(gridX, mapX, new Size(outW, outH), 0, 0, Imgproc.INTER_LINEAR);
      Imgproc.resize(gridY, mapY, new Size(outW, outH), 0, 0, Imgproc.INTER_LINEAR);

      Imgproc.remap(src, dst, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);

      Bitmap output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
      Utils.matToBitmap(dst, output);
      Log.d(
          TAG,
          "applyDewarp: done, out="
              + outW
              + "x"
              + outH
              + ", maxSagitta="
              + Math.round(model.maxSagitta())
              + "px");
      return output;
    } catch (Throwable t) {
      Log.e(TAG, "applyDewarp failed; returning original bitmap", t);
      return originalBitmap;
    } finally {
      release(src, gridX, gridY, mapX, mapY, dst);
    }
  }

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
   * Estimates the shape of the curved page edges for the dewarp model by tracing the paper edges
   * (issue #91, Phase 2 — automatic curve suggestion; follow-up — corner accuracy).
   *
   * <p>The selection quad is expanded vertically by {@link #DEWARP_ESTIMATE_EXPAND_FRAC} along its
   * side edges and rectified with a plain perspective warp to a moderate working resolution, so
   * that the curved top/bottom page edges are visible even where they bulge outside the corner
   * chords. For a set of column bins, the paper/background transition is located with subpixel
   * precision (mean-gray Otsu-threshold crossing), the traced envelope points are mapped back to
   * image space through the inverse rectification homography (eliminating the perspective bias of
   * the band) and a corner-constrained quartic offset curve is fitted per edge (see {@link
   * #fitEdgeProfile}). The result is sampled into a uniform offset PROFILE of {@link
   * #DEWARP_PROFILE_SAMPLES} chord-normalized offsets (see {@link DewarpModel#withEdgeProfiles});
   * unlike a single quadratic Bezier, the profile also captures sine-like edge shapes whose corner
   * slope differs from a parabola's, which removes the residual distortion near the corners.
   *
   * <p>When an edge is not visible inside the expanded band (the selection lies well inside the
   * page, so no dark background is reached), its estimate saturates at the band boundary; such
   * edges are reported as {@code null} (straight/Bezier) instead of guessing.
   *
   * <p>Sign convention: a POSITIVE offset means the on-curve midpoint is displaced from the
   * straight chord midpoint in the direction of the chord direction rotated by +90° in image
   * coordinates (x right, y down) — i.e. "downwards" for a left-to-right top edge (TL→TR) and for
   * the bottom edge (BL→BR).
   *
   * <p>This method may take a few hundred milliseconds and must be called off the UI thread.
   *
   * @param bitmap source bitmap (typically the displayed bitmap of the crop screen)
   * @param corners selection quad in {@code bitmap} image coordinates (TL, TR, BR, BL)
   * @return {@code double[2][]} with {@code {topProfile, bottomProfile}} (an element is {@code
   *     null} when that edge has no reliably visible curvature), or {@code null} when no estimate
   *     is possible at all
   */
  public static double[][] estimateDewarpEdgeProfiles(Bitmap bitmap, Point[] corners) {
    if (bitmap == null || bitmap.isRecycled() || corners == null || corners.length != 4) {
      return null;
    }
    for (Point p : corners) if (p == null) return null;

    Bitmap bitmapForOpenCv = ensureBitmapToMatCompatible(bitmap);
    Mat src = new Mat();
    Mat rectified = new Mat();
    Mat gray = new Mat();
    Mat paper = new Mat();
    try {
      Utils.bitmapToMat(bitmapForOpenCv, src);

      double wTop = Math.hypot(corners[1].x - corners[0].x, corners[1].y - corners[0].y);
      double wBottom = Math.hypot(corners[2].x - corners[3].x, corners[2].y - corners[3].y);
      double hLeft = Math.hypot(corners[3].x - corners[0].x, corners[3].y - corners[0].y);
      double hRight = Math.hypot(corners[2].x - corners[1].x, corners[2].y - corners[1].y);
      double avgW = 0.5 * (wTop + wBottom);
      double avgH = 0.5 * (hLeft + hRight);
      if (avgW < 8 || avgH < 8) return null;
      int rw = DEWARP_ESTIMATE_WIDTH;
      int rh = (int) Math.max(80, Math.min(1200, Math.round(rw * avgH / avgW)));
      int ext = (int) Math.round(rh * DEWARP_ESTIMATE_EXPAND_FRAC);
      int rhx = rh + 2 * ext;

      // Expand the quad along its (straight) side edges so both curved page edges stay visible;
      // the homography maps the straight chords straight, the curved page edges stay curved.
      Point[] expanded = new Point[4];
      double exL = DEWARP_ESTIMATE_EXPAND_FRAC * hLeft;
      double exR = DEWARP_ESTIMATE_EXPAND_FRAC * hRight;
      double dLx = (corners[3].x - corners[0].x) / hLeft;
      double dLy = (corners[3].y - corners[0].y) / hLeft;
      double dRx = (corners[2].x - corners[1].x) / hRight;
      double dRy = (corners[2].y - corners[1].y) / hRight;
      expanded[0] = new Point(corners[0].x - dLx * exL, corners[0].y - dLy * exL);
      expanded[1] = new Point(corners[1].x - dRx * exR, corners[1].y - dRy * exR);
      expanded[2] = new Point(corners[2].x + dRx * exR, corners[2].y + dRy * exR);
      expanded[3] = new Point(corners[3].x + dLx * exL, corners[3].y + dLy * exL);
      // The expanded band deliberately reaches beyond the selection quad (and often beyond the
      // source bitmap itself, see class javadoc on DEWARP_ESTIMATE_EXPAND_FRAC). Replicate the
      // edge pixels there instead of the default black fill, so a page that fills (most of) the
      // photo doesn't create a false paper/background edge at the out-of-bounds band boundary.
      rectified = warpPerspectiveSafe(src, expanded, new Size(rw, rhx), Core.BORDER_REPLICATE);
      if (rectified == null || rectified.empty() || rectified == src) return null;

      // Grayscale + blur; the Otsu threshold VALUE separates paper (bright) from background.
      Imgproc.cvtColor(rectified, gray, Imgproc.COLOR_RGBA2GRAY);
      Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);
      double thr =
          Imgproc.threshold(gray, paper, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);

      byte[] grayPx = new byte[rw * rhx];
      gray.get(0, 0, grayPx);

      // Trace the paper edges per column bin with SUBPIXEL precision: average the gray values
      // over the bin's columns and locate the first/last crossing of the Otsu threshold. This is
      // markedly less biased than counting binary majority rows (the binary mask flips a row or
      // two early/late depending on the local mix of edge and background pixels).
      int bins = DEWARP_ESTIMATE_BINS;
      int binW = rw / bins;
      int marginX = rw / 20;
      java.util.List<double[]> topPts = new java.util.ArrayList<>();
      java.util.List<double[]> bottomPts = new java.util.ArrayList<>();
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
          int rowOff = y * rw;
          for (int x = x0; x < x1; x++) sum += grayPx[rowOff + x] & 0xFF;
          rowMean[y] = sum / (double) nx;
        }
        int top = -1, bottom = -1;
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

      // Map the traced envelope points back to IMAGE space through the inverse rectification
      // homography. Measuring the offsets in image space (relative to the actual corner chords)
      // avoids the perspective bias of the rectified band, where vertical distances are scaled
      // differently near the top and bottom of the photographed page.
      org.opencv.core.MatOfPoint2f bandCorners =
          new org.opencv.core.MatOfPoint2f(
              new Point(0, 0), new Point(rw, 0), new Point(rw, rhx), new Point(0, rhx));
      org.opencv.core.MatOfPoint2f quadCorners = new org.opencv.core.MatOfPoint2f(expanded);
      Mat inverseH = Imgproc.getPerspectiveTransform(bandCorners, quadCorners);
      java.util.List<double[]> topImg = transformEnvelope(topPts, inverseH);
      java.util.List<double[]> bottomImg = transformEnvelope(bottomPts, inverseH);
      release(bandCorners, quadCorners, inverseH);

      // An edge whose envelope saturates at the expanded-band boundary is not visible (selection
      // inside the page); report it as null (straight) rather than tracing the band boundary.
      double maxSaturated = topPts.size() * DEWARP_ESTIMATE_MAX_SATURATED_FRAC;
      double[] topProfile =
          topSaturated > maxSaturated ? null : fitEdgeProfile(topImg, corners[0], corners[1]);
      double[] bottomProfile =
          bottomSaturated > maxSaturated ? null : fitEdgeProfile(bottomImg, corners[3], corners[2]);
      if (topProfile == null && bottomProfile == null) {
        Log.d(TAG, "estimateDewarpEdgeProfiles: both edges saturated/invisible; no estimate");
        return null;
      }
      Log.d(
          TAG,
          "estimateDewarpEdgeProfiles: topMid="
              + (topProfile == null ? "null" : DewarpModel.sampleProfile(topProfile, 0.5))
              + ", bottomMid="
              + (bottomProfile == null ? "null" : DewarpModel.sampleProfile(bottomProfile, 0.5))
              + " (bins="
              + topPts.size()
              + ", saturated="
              + topSaturated
              + "/"
              + bottomSaturated
              + ")");
      return new double[][] {topProfile, bottomProfile};
    } catch (Throwable t) {
      Log.w(TAG, "estimateDewarpEdgeProfiles failed", t);
      return null;
    } finally {
      release(src, rectified, gray, paper);
    }
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

  /** Transforms a list of {@code {x, y}} points with the given 3x3 perspective homography. */
  private static java.util.List<double[]> transformEnvelope(
      java.util.List<double[]> pts, Mat homography) {
    Point[] in = new Point[pts.size()];
    for (int i = 0; i < pts.size(); i++) in[i] = new Point(pts.get(i)[0], pts.get(i)[1]);
    org.opencv.core.MatOfPoint2f srcM = new org.opencv.core.MatOfPoint2f(in);
    org.opencv.core.MatOfPoint2f dstM = new org.opencv.core.MatOfPoint2f();
    org.opencv.core.Core.perspectiveTransform(srcM, dstM, homography);
    Point[] out = dstM.toArray();
    release(srcM, dstM);
    java.util.List<double[]> res = new java.util.ArrayList<>(out.length);
    for (Point p : out) res.add(new double[] {p.x, p.y});
    return res;
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

  private static Bitmap ensureBitmapToMatCompatible(Bitmap bitmap) {
    if (bitmap == null) return null;
    Bitmap.Config config = bitmap.getConfig();
    if (config == Bitmap.Config.ARGB_8888 || config == Bitmap.Config.RGB_565) {
      return bitmap;
    }
    Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, false);
    if (converted != null) {
      Log.d(TAG, "Converted " + config + " bitmap to ARGB_8888 for OpenCV");
      return converted;
    }
    Bitmap fallback =
        Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(fallback);
    canvas.drawBitmap(bitmap, 0f, 0f, null);
    Log.d(TAG, "Redrew " + config + " bitmap as ARGB_8888 for OpenCV");
    return fallback;
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
   * Trims residual non-paper border (e.g. dark background remnants left after a slightly loose
   * perspective crop) from the four sides of the given bitmap.
   *
   * <p>Approach: convert to grayscale, apply Otsu thresholding to obtain a foreground mask, and
   * compute the dark-pixel ratio for each row and column. As long as the outermost row/column has a
   * dark-pixel ratio above {@code darkRatioThreshold}, it is considered border and trimmed. To
   * avoid cutting into the document, no more than {@code maxTrimFraction} of each side is removed.
   *
   * <p>Why it works: a clean scan of a document has only sparse dark pixels per row/column
   * (text/graphics), typically &lt; 30%. A row that is mostly black background sits well above that
   * threshold and is safely identified as non-paper.
   *
   * @param src input bitmap (typically the warpPerspective output). May be null.
   * @return a new trimmed {@link Bitmap}, or {@code src} unchanged if no trimming is needed or the
   *     operation cannot be performed.
   */
  public static Bitmap trimNonPaperBorder(Bitmap src) {
    return trimNonPaperBorder(src, 0.30, 0.03);
  }

  /**
   * See {@link #trimNonPaperBorder(Bitmap)} for a description of the algorithm.
   *
   * @param src input bitmap.
   * @param darkRatioThreshold a row/column is considered border if its dark-pixel ratio exceeds
   *     this value (range 0..1, typical 0.3).
   * @param maxTrimFraction maximum fraction of each side that may be removed (range 0..0.5, typical
   *     0.03 = 3%).
   * @return trimmed bitmap, or {@code src} unchanged if nothing was trimmed.
   */
  public static Bitmap trimNonPaperBorder(
      Bitmap src, double darkRatioThreshold, double maxTrimFraction) {
    if (src == null || src.isRecycled()) return src;
    final int w = src.getWidth();
    final int h = src.getHeight();
    if (w < 8 || h < 8) return src;

    Mat rgba = new Mat();
    Mat gray = new Mat();
    Mat bw = new Mat();
    try {
      Utils.bitmapToMat(src, rgba);
      Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
      // Otsu threshold; INV so that "dark" pixels (border / text) become 255.
      Imgproc.threshold(gray, bw, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);

      final int maxTrimX = Math.max(0, Math.min(w / 2 - 1, (int) Math.round(w * maxTrimFraction)));
      final int maxTrimY = Math.max(0, Math.min(h / 2 - 1, (int) Math.round(h * maxTrimFraction)));

      // Row sums (dark-pixel count per row). Mat is 8U with values 0/255.
      Mat rowSum = new Mat();
      Mat colSum = new Mat();
      try {
        Core.reduce(bw, rowSum, 1, Core.REDUCE_SUM, CvType.CV_32S); // h x 1
        Core.reduce(bw, colSum, 0, Core.REDUCE_SUM, CvType.CV_32S); // 1 x w

        int[] rowBuf = new int[h];
        int[] colBuf = new int[w];
        rowSum.get(0, 0, rowBuf);
        colSum.get(0, 0, colBuf);

        final double rowThreshold = w * 255.0 * darkRatioThreshold;
        final double colThreshold = h * 255.0 * darkRatioThreshold;

        int top = 0;
        while (top < maxTrimY && rowBuf[top] > rowThreshold) top++;
        int bottom = 0;
        while (bottom < maxTrimY && rowBuf[h - 1 - bottom] > rowThreshold) bottom++;
        int left = 0;
        while (left < maxTrimX && colBuf[left] > colThreshold) left++;
        int right = 0;
        while (right < maxTrimX && colBuf[w - 1 - right] > colThreshold) right++;

        if (top == 0 && bottom == 0 && left == 0 && right == 0) {
          return src;
        }
        int newW = w - left - right;
        int newH = h - top - bottom;
        if (newW < 8 || newH < 8) {
          // Sanity guard: refuse to trim if it would destroy the image
          return src;
        }
        Log.d(
            TAG,
            "trimNonPaperBorder: trimmed L="
                + left
                + " R="
                + right
                + " T="
                + top
                + " B="
                + bottom
                + " ("
                + w
                + "x"
                + h
                + " → "
                + newW
                + "x"
                + newH
                + ")");
        return Bitmap.createBitmap(src, left, top, newW, newH);
      } finally {
        release(rowSum, colSum);
      }
    } catch (Throwable t) {
      Log.w(TAG, "trimNonPaperBorder failed: " + t.getMessage());
      return src;
    } finally {
      release(rgba, gray, bw);
    }
  }

  // ---------- ONNX utilities ----------

  public static float[] fromBitmapBGR(Bitmap bitmap) {
    if (bitmap == null) throw new IllegalArgumentException("bitmap is null");
    Mat rgba = new Mat();
    Mat bgr = new Mat();
    try {
      Utils.bitmapToMat(bitmap, rgba);
      Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);

      Mat resized = new Mat();
      Imgproc.resize(bgr, resized, new Size(256, 256), 0, 0, Imgproc.INTER_AREA);

      Mat floatImage = new Mat();
      resized.convertTo(floatImage, CvType.CV_32FC3, 1.0 / 255.0);

      List<Mat> ch = new ArrayList<>(3);
      Core.split(floatImage, ch); // B, G, R

      int H = 256, W = 256, C = 3, HW = H * W;
      float[] nchw = new float[C * HW];
      for (int c = 0; c < C; c++) {
        float[] buf = new float[HW];
        ch.get(c).get(0, 0, buf);
        System.arraycopy(buf, 0, nchw, c * HW, HW);
      }

      for (Mat cMat : ch) {
        if (cMat != null) cMat.release();
      }
      floatImage.release();
      resized.release();
      return nchw;

    } finally {
      bgr.release();
      rgba.release();
    }
  }

  /**
   * Converts a given color {@link Bitmap} image to a grayscale {@link Bitmap}.
   *
   * @param src the source {@link Bitmap} to be converted; must be non-null and not recycled
   * @return a new {@link Bitmap} object in grayscale, or null if the conversion fails
   */
  public static Bitmap toGray(Bitmap src) {
    if (src == null || src.isRecycled()) return null;
    Mat rgba = new Mat();
    Mat gray = new Mat();
    try {
      Utils.bitmapToMat(src, rgba);
      Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
      Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
      Utils.matToBitmap(gray, out);
      return out;
    } catch (Throwable t) {
      Log.d(TAG, "toGray failed: " + t.getMessage());
      return null;
    } finally {
      release(rgba, gray);
    }
  }

  /**
   * Configuration options for black-and-white image processing.
   *
   * @deprecated Use {@link BinarizationUtils.BwOptions} directly. This alias is kept for backward
   *     compatibility.
   */
  @Deprecated
  public static class BwOptions extends BinarizationUtils.BwOptions {}

  /**
   * Robust B/W conversion with shadow handling. Delegates to {@link BinarizationUtils#toBw(Bitmap,
   * BinarizationUtils.BwOptions)}.
   */
  public static Bitmap toBw(Bitmap src, BinarizationUtils.BwOptions opt) {
    return BinarizationUtils.toBw(src, opt);
  }

  /** Delegates to {@link BinarizationUtils#toBw(Bitmap)}. */
  public static Bitmap toBw(Bitmap src) {
    return BinarizationUtils.toBw(src);
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
   * Releases the provided OpenCV Mat objects to free up memory. This method is a no-op for null
   * inputs and handles exceptions if any occur during the release process.
   *
   * @param mats An array of Mat objects to be released. Null values within the array are safely
   *     ignored.
   */
  static void release(Mat... mats) {
    if (mats == null) return;
    for (Mat m : mats) {
      if (m != null) {
        try {
          m.release();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
    }
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


  // --- Heuristics delegated to BinarizationUtils ---

  // ===== New helpers for Robust pipeline =====

  // --- Lightweight text orientation estimation (preview-time) ---

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
   * Estimate whether text runs mostly horizontally (0° bucket) or vertically (90° bucket).
   * Heuristic: Compare summed absolute Scharr/Sobel gradient magnitudes in X vs. Y. Horizontal text
   * lines produce stronger horizontal edge responses → |Gy| > |Gx|.
   *
   * @param uprightSmall an upright bitmap (already rotated by CameraX rotationDegrees), ideally <=
   *     720 px long side
   * @return OrientationEstimate with bucket 0 or 90 and a confidence 0..1
   */
  public static OrientationEstimate estimateTextOrientation(Bitmap uprightSmall) {
    try {
      if (uprightSmall == null || uprightSmall.getWidth() < 8 || uprightSmall.getHeight() < 8) {
        return new OrientationEstimate(0, 0.0);
      }
      // Convert to grayscale Mat
      Mat rgba = new Mat();
      Utils.bitmapToMat(uprightSmall, rgba);
      Mat gray = new Mat();
      Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
      rgba.release();

      // Downscale to speed up and reduce noise
      int maxSide = 512;
      int w = gray.cols(), h = gray.rows();
      int longSide = Math.max(w, h);
      if (longSide > maxSide) {
        double scale = (double) maxSide / longSide;
        Imgproc.resize(
            gray,
            gray,
            new Size(
                Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))),
            0,
            0,
            Imgproc.INTER_AREA);
      }

      // Light denoise and normalization
      Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);

      // Use Scharr if available, fallback to Sobel
      Mat gx = new Mat();
      Mat gy = new Mat();
      try {
        Imgproc.Scharr(gray, gx, CvType.CV_16S, 1, 0);
        Imgproc.Scharr(gray, gy, CvType.CV_16S, 0, 1);
      } catch (Throwable t) {
        Imgproc.Sobel(gray, gx, CvType.CV_16S, 1, 0, 3);
        Imgproc.Sobel(gray, gy, CvType.CV_16S, 0, 1, 3);
      }
      Mat agx = new Mat();
      Mat agy = new Mat();
      Core.convertScaleAbs(gx, agx);
      Core.convertScaleAbs(gy, agy);
      gx.release();
      gy.release();

      Scalar sumX = Core.sumElems(agx);
      Scalar sumY = Core.sumElems(agy);
      double sx = sumX.val[0];
      double sy = sumY.val[0];
      agx.release();
      agy.release();
      gray.release();

      double total = sx + sy;
      if (total <= 1e-3) {
        // Blank or too uniform
        return new OrientationEstimate(0, 0.0);
      }

      // If vertical gradient energy (Gy) dominates, we assume horizontal lines → 0° bucket
      int bucket = (sy >= sx) ? 0 : 90;
      double diff = Math.abs(sy - sx);
      double conf = Math.min(1.0, Math.max(0.0, diff / (total + 1e-6)));
      // Slightly compress confidence to be conservative
      conf = Math.max(0.0, Math.min(1.0, conf * 0.9));
      return new OrientationEstimate(bucket, conf);
    } catch (Throwable t) {
      return new OrientationEstimate(0, 0.0);
    }
  }
}
