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

import android.graphics.Bitmap;
import android.util.Log;
import java.util.*;
import lombok.experimental.UtilityClass;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

/**
 * Utility class for image binarization operations extracted from OpenCVUtils. Contains black/white
 * conversion, adaptive thresholding (Sauvola, Wolf, NICK), despeckle, border noise removal, and
 * quality scoring for binarized images.
 *
 * <p>This class cannot be instantiated.
 */
@UtilityClass
public final class BinarizationUtils {
  private static final String TAG = "BinarizationUtils";

  /** Configuration options for black-and-white image processing. */
  public static class BwOptions {
    public enum Mode {
      AUTO_ADAPTIVE,
      OTSU_ONLY
    }

    public Mode mode = Mode.AUTO_ADAPTIVE;
    public boolean useClahe = true;
    public boolean removeShadows = true;

    /** Adaptive window (odd). 0 = auto */
    public int blockSize = 0;

    /** Offset for adaptiveThreshold (typ. 5–10). 0 = auto (noise-adaptive). */
    public int C = 0;

    /**
     * Gentle mode for scripts with fine strokes and diacritics (Arabic, Persian, Hebrew). When
     * true, skips aggressive despeckle and morphological closing operations that can destroy small
     * but important character components like dots and thin strokes.
     */
    public boolean gentleMode = false;

    /**
     * Target DPI for the output image. Used to scale despeckle aggressiveness. At lower DPI,
     * despeckle is less aggressive to preserve readability. 0 = auto (assumes 300 DPI as default).
     */
    public int targetDpi = 0;
  }

  /**
   * Robust B/W conversion with shadow handling. Emulator: adaptiveThreshold is disabled (avoid
   * SIGILL). Real devices: gentle adaptive variant (MEAN + higher C).
   */
  public static Bitmap toBw(Bitmap src, BwOptions opt) {
    if (src == null || src.isRecycled()) return null;
    if (opt == null) opt = new BwOptions();

    Mat rgba = new Mat();
    Mat gray = new Mat();
    Mat work = null; // Don't create empty Mat - will be assigned below
    Mat bw = new Mat();
    CLAHE clahe = null;

    try {
      Utils.bitmapToMat(src, rgba);
      Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

      // --- 1) Shadow correction: division-based normalization (centralized) ---
      if (opt.removeShadows && !OpenCVUtils.isSafeMode()) {
        work = new Mat();
        HighPassUtils.backgroundDivideGray(gray, work, HighPassUtils.KERNEL_FRACTION_BW, 51);
      } else {
        work = gray; // work points to gray, no separate Mat needed
      }

      // --- 2) CLAHE for local contrast enhancement (only when contrast is actually low) ---
      // Unconditional CLAHE amplifies paper grain in uniform (blank) regions, which the adaptive
      // threshold then turns into black speckles. Apply it only when the image really needs it.
      if (opt.useClahe && isLowContrast(work)) {
        clahe = Imgproc.createCLAHE();
        clahe.setClipLimit(1.5);
        clahe.setTilesGridSize(new Size(8, 8));
        clahe.apply(work, work);
      }

      // --- 3) very light smoothing against pepper noise ---
      // Keep this conservative: stronger blur removes faint strokes before thresholding.
      Imgproc.GaussianBlur(work, work, new Size(3, 3), 0);

      boolean ok = false;

      // --- 4) Adaptive threshold: choose a text-preserving local threshold ---
      // Detect low-resolution images (e.g. from autoscan) and use gentler parameters
      int longSide = Math.max(work.width(), work.height());
      boolean lowRes = longSide < 1500;

      // --- 4a) Low-res upscale: binarize at higher resolution to preserve thin strokes ---
      Mat threshInput = work;
      boolean upscaled = false;
      if (lowRes && longSide > 0 && !OpenCVUtils.isSafeMode()) {
        double scale = Math.min(2.0, 1800.0 / longSide);
        if (scale > 1.05) {
          threshInput = new Mat();
          Imgproc.resize(work, threshInput, new Size(), scale, scale, Imgproc.INTER_CUBIC);
          upscaled = true;
        }
      }

      try {
        int tLongSide = Math.max(threshInput.width(), threshInput.height());
        boolean tLowRes = tLongSide < 1500;

        if (opt.mode == BwOptions.Mode.AUTO_ADAPTIVE && !OpenCVUtils.isSafeMode()) {
          int bs;
          if (opt.blockSize > 0) {
            bs = (opt.blockSize % 2 == 1) ? opt.blockSize : opt.blockSize + 1;
          } else if (tLowRes) {
            // For low-res images use a larger relative block size to avoid
            // over-aggressive binarization that destroys text readability
            bs = Math.max(31, (Math.min(threshInput.width(), threshInput.height()) / 10) | 1);
            if (bs % 2 == 0) bs++;
          } else {
            // Larger block size captures broader context for threshold calculation
            bs = Math.max(51, (Math.min(threshInput.width(), threshInput.height()) / 30) | 1);
            if (bs % 2 == 0) bs++;
          }
          // C controls the distance to the local mean. After shadow removal the background is
          // normalized, so a noise-adaptive C keeps faint text while suppressing paper grain.
          int cVal = resolveAdaptiveC(threshInput, opt.C, tLowRes);

          Mat adaptiveMean = new Mat();
          Mat adaptiveGaussian = new Mat();
          Mat sauvola = new Mat();
          Mat otsu = new Mat();
          try {
            Imgproc.adaptiveThreshold(
                threshInput,
                adaptiveMean,
                255,
                Imgproc.ADAPTIVE_THRESH_MEAN_C,
                Imgproc.THRESH_BINARY,
                bs,
                cVal);
            Imgproc.adaptiveThreshold(
                threshInput,
                adaptiveGaussian,
                255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                bs,
                cVal);
            // Sauvola is considerably more robust against background texture on photographed
            // documents than plain mean/gaussian adaptive thresholding.
            sauvolaThreshold(threshInput, sauvola, bs, 0.30, 128.0);
            Imgproc.threshold(
                threshInput, otsu, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);

            // Use the Otsu black fraction as a dynamic target for the text coverage instead of a
            // fixed constant; a fixed target rewards noisy candidates on mostly blank pages.
            double otsuBlackFrac = estimateBlackFraction(otsu);
            double target = Math.min(0.30, Math.max(0.01, otsuBlackFrac));

            Mat[] candidates = {adaptiveGaussian, adaptiveMean, sauvola, otsu};
            Mat best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (Mat candidate : candidates) {
              double score = scoreBwQuality(candidate, target);
              if (score < bestScore) {
                bestScore = score;
                best = candidate;
              }
            }
            if (best != null) {
              best.copyTo(bw);
              ok = true;
            }
          } catch (Throwable ignore) {
            ok = false;
          } finally {
            adaptiveMean.release();
            adaptiveGaussian.release();
            sauvola.release();
            otsu.release();
          }
        }

        // --- 5) Fallback / OTSU_ONLY mode ---
        // For low-res OTSU_ONLY (s/w klassisch): use adaptive threshold instead of
        // global Otsu, because Otsu is too aggressive on low-res autoscan images
        // and destroys text readability.
        if (!ok && tLowRes && !OpenCVUtils.isSafeMode()) {
          int bs = Math.max(31, (Math.min(threshInput.width(), threshInput.height()) / 10) | 1);
          if (bs % 2 == 0) bs++;
          int cVal = resolveAdaptiveC(threshInput, opt.C, true);
          try {
            Imgproc.adaptiveThreshold(
                threshInput,
                bw,
                255,
                Imgproc.ADAPTIVE_THRESH_MEAN_C,
                Imgproc.THRESH_BINARY,
                bs,
                cVal);
            ok = true;
          } catch (Throwable ignore) {
            ok = false;
          }
        }
        if (!ok) {
          Imgproc.threshold(threshInput, bw, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        }
      } finally {
        if (upscaled) threshInput.release();
      }

      // --- 5a) Downscale back to the original size (binary-safe) ---
      if (upscaled) {
        Imgproc.resize(bw, bw, work.size(), 0, 0, Imgproc.INTER_AREA);
        Imgproc.threshold(bw, bw, 127, 255, Imgproc.THRESH_BINARY);
      }

      // --- 6) conservative cleanup ---
      // Remove only tiny isolated speckles. Avoid morphology/opening/closing here: it is fast, but
      // it also removes punctuation, diacritics and faint text on real camera captures.
      removeTinySpeckles(bw, opt.targetDpi, lowRes || opt.gentleMode);

      // --- 6a) light stroke reconstruction ---
      // Thresholding plus smoothing can leave small gaps inside thin strokes. A minimal closing
      // on the text (black) pixels reconnects them. Skipped in gentle mode to keep diacritics
      // strictly separated.
      if (!opt.gentleMode) {
        reconnectThinStrokes(bw);
      }

      Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
      Utils.matToBitmap(bw, out);
      return out;

    } catch (Throwable t) {
      Log.d(TAG, "toBw (robust) failed: " + t.getMessage());
      try {
        Mat tmpGray = new Mat(), tmpBw = new Mat();
        Utils.bitmapToMat(src, rgba);
        Imgproc.cvtColor(rgba, tmpGray, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.threshold(tmpGray, tmpBw, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(tmpBw, out);
        tmpGray.release();
        tmpBw.release();
        return out;
      } catch (Throwable t2) {
        Log.d(TAG, "toBw fallback failed: " + t2.getMessage());
        return null;
      }
    } finally {
      OpenCVUtils.release(rgba, bw);
      if (work != gray) OpenCVUtils.release(work);
      OpenCVUtils.release(gray);
      if (clahe != null) {
        try {
          clahe.collectGarbage();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
    }
  }

  /**
   * Converts a given Bitmap image to a black-and-white (grayscale) representation using default
   * options.
   *
   * @param src the source Bitmap to be converted to black-and-white
   * @return a new Bitmap representing the black-and-white version of the source image
   */
  public static Bitmap toBw(Bitmap src) {
    return toBw(src, new BwOptions());
  }

  /**
   * Returns true if the grayscale image has low global contrast and would benefit from CLAHE.
   * High-contrast documents (dark text on bright paper) are left untouched, because CLAHE would
   * only amplify paper grain in uniform regions.
   */
  static boolean isLowContrast(Mat gray8u) {
    MatOfDouble mean = new MatOfDouble();
    MatOfDouble stddev = new MatOfDouble();
    try {
      Core.meanStdDev(gray8u, mean, stddev);
      double[] sd = stddev.toArray();
      return sd.length > 0 && sd[0] < 40.0;
    } catch (Throwable t) {
      return true; // keep legacy behavior (apply CLAHE) if the estimate fails
    } finally {
      mean.release();
      stddev.release();
    }
  }

  /**
   * Resolves the C constant for adaptiveThreshold. If the caller provided an explicit value it is
   * clamped to a safe range; otherwise C is derived from the estimated image noise so that paper
   * grain stays below the threshold while faint strokes are preserved.
   */
  static int resolveAdaptiveC(Mat gray8u, int requestedC, boolean lowRes) {
    if (requestedC > 0) {
      return Math.max(3, Math.min(14, requestedC));
    }
    double noise = estimateNoiseSigma(gray8u);
    int base = lowRes ? 6 : 8;
    int cVal = (int) Math.round(base + noise);
    return Math.max(base, Math.min(12, cVal));
  }

  /**
   * Estimates the noise level of a grayscale image as the mean absolute residual against a median
   * filtered version. Text edges contribute little because they are sparse compared to paper grain,
   * which is spread over the whole page.
   */
  static double estimateNoiseSigma(Mat gray8u) {
    Mat median = new Mat();
    Mat residual = new Mat();
    try {
      Imgproc.medianBlur(gray8u, median, 3);
      Core.absdiff(gray8u, median, residual);
      Scalar meanResidual = Core.mean(residual);
      return meanResidual.val.length > 0 ? meanResidual.val[0] : 0.0;
    } catch (Throwable t) {
      return 0.0;
    } finally {
      median.release();
      residual.release();
    }
  }

  /** Returns the fraction of black (text) pixels in a binary 0/255 image. */
  static double estimateBlackFraction(Mat bw) {
    int rows = bw.rows(), cols = bw.cols();
    if (rows <= 0 || cols <= 0) return 0.0;
    int area = rows * cols;
    int white = Core.countNonZero(bw);
    return Math.min(1.0, Math.max(0.0, (area - white) / (double) area));
  }

  /**
   * Reconnects thin, broken strokes by applying a minimal morphological closing to the text (black)
   * pixels. Uses a 2x2 kernel so that letter shapes are preserved and neighboring glyphs are not
   * merged.
   */
  static void reconnectThinStrokes(Mat bw /* CV_8UC1, 0/255 */) {
    if (bw == null || bw.empty()) return;
    Mat inv = new Mat();
    Mat kernel = null;
    try {
      // Invert so text becomes white; closing then fills small gaps inside strokes.
      Core.bitwise_not(bw, inv);
      kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2));
      Imgproc.morphologyEx(inv, inv, Imgproc.MORPH_CLOSE, kernel);
      Core.bitwise_not(inv, bw);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    } finally {
      inv.release();
      if (kernel != null) kernel.release();
    }
  }

  static void removeTinySpeckles(Mat bw /* CV_8UC1, 0/255 */, int targetDpi, boolean gentle) {
    if (bw == null || bw.empty()) return;
    int minArea;
    int minHeight;
    // Prefer thresholds derived from the estimated text size: this protects dots and diacritics
    // (they scale with the font) while removing paper-grain speckles reliably.
    int medianH = estimateMedianComponentHeight(bw);
    if (medianH > 0) {
      int base = Math.max(3, (medianH * medianH) / 36);
      minArea = gentle ? Math.max(2, base / 2) : base;
      minHeight = gentle ? 1 : Math.max(1, medianH / 8);
    } else {
      int effectiveDpi = targetDpi > 0 ? targetDpi : 300;
      float dpiScale = Math.max(0.5f, Math.min(2.0f, effectiveDpi / 300f));
      minArea = gentle ? 2 : Math.max(3, Math.round(5 * dpiScale * dpiScale));
      minHeight = gentle ? 1 : Math.max(1, Math.round(2 * dpiScale));
    }
    removeSmallComponents(bw, minArea, minHeight);
  }

  /**
   * Removes small speckles from a binary image using morphological operations. The function
   * processes the input binary image to eliminate noise or small artifacts, leaving the major
   * structures intact.
   *
   * <p>The aggressiveness of despeckle is scaled based on target DPI: - At 300 DPI (reference):
   * uses 3x3 kernel and minArea=15 - At lower DPI (e.g., 72-150): uses smaller kernel (2x2) and
   * lower minArea to preserve readability - At higher DPI (e.g., 600): can use larger kernel and
   * higher minArea
   *
   * @param bw Input binary image of type Mat (CV_8UC1), with pixel values of 0 or 255. It will be
   *     modified in-place to remove speckles.
   * @param targetDpi Target DPI for the output. 0 or negative values default to 300 DPI.
   */
  static void despeckleFast(Mat bw /* CV_8UC1, 0/255 */, int targetDpi) {
    // Reference DPI for scaling calculations
    final int REFERENCE_DPI = 300;
    int effectiveDpi = targetDpi > 0 ? targetDpi : REFERENCE_DPI;

    // Scale factor relative to reference DPI
    float dpiScale = (float) effectiveDpi / REFERENCE_DPI;

    // At low DPI (< 150), skip morphological opening entirely to preserve fine details
    // At medium DPI (150-250), use 2x2 kernel
    // At high DPI (>= 250), use 3x3 kernel
    int kernelSize;
    if (effectiveDpi < 150) {
      kernelSize = 0; // Skip morphological opening
    } else if (effectiveDpi < 250) {
      kernelSize = 2;
    } else {
      kernelSize = 3;
    }

    Mat inv = new Mat();
    Mat kernel = null;
    try {
      if (kernelSize > 0) {
        // Make text and speckles white so the opening operation removes them
        Core.bitwise_not(bw, inv);
        kernel =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kernelSize, kernelSize));
        Imgproc.morphologyEx(inv, inv, Imgproc.MORPH_OPEN, kernel);
        Core.bitwise_not(inv, bw);
      }
    } finally {
      inv.release();
      if (kernel != null) kernel.release();
    }

    // Scale minArea based on DPI: at 300 DPI use 15, scale proportionally
    // At low DPI, use smaller minArea to avoid removing small but valid characters
    // Formula: minArea = 15 * (dpi/300)^2, with minimum of 4 pixels
    int minArea = Math.max(4, Math.round(15 * dpiScale * dpiScale));

    // At very low DPI (< 100), skip component removal entirely
    if (effectiveDpi >= 100) {
      removeSmallComponents(bw, minArea);
    }
  }

  /**
   * Removes connected components smaller than the specified minimum area. This helps eliminate
   * small noise artifacts that are too small to be valid characters.
   *
   * @param bw Input binary image of type Mat (CV_8UC1), with pixel values of 0 or 255. Text should
   *     be black (0) on white (255) background.
   * @param minArea Minimum area in pixels for a component to be kept.
   */
  static void removeSmallComponents(Mat bw /* CV_8UC1, 0/255 */, int minArea) {
    if (bw == null || bw.empty() || minArea <= 0) return;
    removeSmallComponents(bw, minArea, 1);
  }

  /**
   * Clears noise and small artifacts touching the image borders. This helps remove scanning
   * artifacts, edge noise, and partial characters that often appear at document edges and cause OCR
   * errors.
   *
   * @param bw Input binary image of type Mat (CV_8UC1), with pixel values of 0 or 255. It will be
   *     modified in-place to clear border-touching components.
   */
  static void clearBorderNoise(Mat bw /* CV_8UC1, 0/255 */) {
    if (bw == null || bw.empty()) return;

    int w = bw.cols();
    int h = bw.rows();

    // Define border margin (percentage of image size)
    int marginX = Math.max(8, (int) (w * 0.015)); // 1.5% of width, min 8px
    int marginY = Math.max(8, (int) (h * 0.015)); // 1.5% of height, min 8px

    // Use submat and setTo for efficient border clearing
    // Note: SubMats are views but must still be released to avoid memory leaks
    Mat top = null, bottom = null, left = null, right = null;
    try {
      // Clear top border region
      if (marginY > 0 && marginY < h) {
        top = bw.submat(0, marginY, 0, w);
        top.setTo(new Scalar(255));
      }

      // Clear bottom border region
      if (marginY > 0 && h - marginY > 0) {
        bottom = bw.submat(h - marginY, h, 0, w);
        bottom.setTo(new Scalar(255));
      }

      // Clear left border region
      if (marginX > 0 && marginX < w) {
        left = bw.submat(0, h, 0, marginX);
        left.setTo(new Scalar(255));
      }

      // Clear right border region
      if (marginX > 0 && w - marginX > 0) {
        right = bw.submat(0, h, w - marginX, w);
        right.setTo(new Scalar(255));
      }
    } catch (Throwable ignore) {
      // Fallback: pixel-by-pixel clearing if submat fails
      for (int y = 0; y < marginY && y < h; y++) {
        for (int x = 0; x < w; x++) {
          bw.put(y, x, 255);
        }
      }
      for (int y = h - marginY; y < h; y++) {
        if (y >= 0) {
          for (int x = 0; x < w; x++) {
            bw.put(y, x, 255);
          }
        }
      }
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < marginX && x < w; x++) {
          bw.put(y, x, 255);
        }
      }
      for (int y = 0; y < h; y++) {
        for (int x = w - marginX; x < w; x++) {
          if (x >= 0) {
            bw.put(y, x, 255);
          }
        }
      }
    } finally {
      // Release SubMats to avoid memory leaks
      if (top != null) top.release();
      if (bottom != null) bottom.release();
      if (left != null) left.release();
      if (right != null) right.release();
    }
  }

  /**
   * Scores a binarized image: lower is better. Penalizes excessive white coverage and many tiny
   * blobs. Note: Binary images have text=0 (black) and background=255 (white).
   * connectedComponentsWithStats treats non-zero pixels as foreground, so we must invert first.
   */
  static double scoreBwQuality(Mat bw /* CV_8UC1 0/255, text=0, bg=255 */) {
    return scoreBwQuality(bw, 0.12);
  }

  /**
   * Scores a binarized image against a dynamically estimated target black fraction (e.g. derived
   * from the Otsu result): lower is better. Heavily penalizes salt-and-pepper noise by measuring
   * the fraction and density of tiny connected components, which prevents noisy adaptive
   * thresholding results from winning against clean global thresholding on mostly blank pages.
   */
  static double scoreBwQuality(Mat bw /* CV_8UC1 0/255, text=0, bg=255 */, double targetBlackFrac) {
    Mat inv = new Mat();
    Mat labels = new Mat();
    Mat stats = new Mat();
    Mat centroids = new Mat();
    try {
      int rows = bw.rows(), cols = bw.cols();
      if (rows <= 0 || cols <= 0) return Double.POSITIVE_INFINITY;
      int area = rows * cols;
      int white = Core.countNonZero(bw);
      double whiteFrac = Math.min(1.0, Math.max(0.0, white / (double) area));

      Core.bitwise_not(bw, inv);
      int black = Core.countNonZero(inv);
      double blackFrac = Math.min(1.0, Math.max(0.0, black / (double) area));
      double tooEmptyPenalty = blackFrac < 0.01 ? 1.0 : 0.0;
      double tooDarkPenalty = blackFrac > 0.45 ? 1.0 : 0.0;
      double target = Math.min(0.30, Math.max(0.01, targetBlackFrac));
      double targetTextCoveragePenalty = Math.abs(blackFrac - target);

      // Noise penalty: tiny isolated components indicate salt-and-pepper artifacts.
      double noisePenalty = 0.0;
      int n = Imgproc.connectedComponentsWithStats(inv, labels, stats, centroids, 8, CvType.CV_32S);
      if (n > 1) {
        int tiny = 0;
        for (int i = 1; i < n; i++) {
          int ai = (int) stats.get(i, Imgproc.CC_STAT_AREA)[0];
          if (ai < 4) tiny++;
        }
        double tinyFrac = tiny / (double) (n - 1);
        double speckDensityPer10k = tiny * 10000.0 / area;
        noisePenalty = tinyFrac * 1.5 + Math.min(1.0, speckDensityPer10k * 0.05);
      }
      return targetTextCoveragePenalty
          + tooEmptyPenalty
          + tooDarkPenalty
          + noisePenalty
          + whiteFrac * 0.05;
    } catch (Throwable t) {
      return Double.POSITIVE_INFINITY;
    } finally {
      inv.release();
      labels.release();
      stats.release();
      centroids.release();
    }
  }

  /**
   * Removes connected components below given size/height thresholds (keeps punctuation by using
   * tiny limits). Note: Binary images have text=0 (black) and background=255 (white).
   * connectedComponentsWithStats treats non-zero pixels as foreground, so we must invert first.
   */
  static void removeSmallComponents(
      Mat bw /* CV_8UC1 0/255, text=0, bg=255 */, int minArea, int minHeight) {
    Mat inv = new Mat();
    Mat labels = new Mat();
    Mat stats = new Mat();
    Mat centroids = new Mat();
    try {
      // Invert so text becomes white (foreground) for connectedComponents
      Core.bitwise_not(bw, inv);
      int n = Imgproc.connectedComponentsWithStats(inv, labels, stats, centroids, 8, CvType.CV_32S);
      if (n <= 1) return;

      // Build a keep/drop lookup table, then clear all dropped labels in a single pass over the
      // pixel data. This is O(pixels) regardless of the component count, so even extremely noisy
      // pages (thousands of speckles) are cleaned reliably.
      boolean[] drop = new boolean[n];
      boolean any = false;
      for (int i = 1; i < n; i++) {
        int ai = (int) stats.get(i, Imgproc.CC_STAT_AREA)[0];
        int hi = (int) stats.get(i, Imgproc.CC_STAT_HEIGHT)[0];
        if (ai < minArea || hi < minHeight) {
          drop[i] = true;
          any = true;
        }
      }
      if (!any) return;

      int rows = bw.rows(), cols = bw.cols();
      int[] lab = new int[rows * cols];
      labels.get(0, 0, lab);
      byte[] px = new byte[rows * cols];
      bw.get(0, 0, px);
      for (int idx = 0; idx < lab.length; idx++) {
        if (drop[lab[idx]]) {
          px[idx] = (byte) 255; // set to background (white)
        }
      }
      bw.put(0, 0, px);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    } finally {
      inv.release();
      labels.release();
      stats.release();
      centroids.release();
    }
  }

  /**
   * Estimates median height of text components to guide scaling; returns -1 if not available. Note:
   * Binary images have text=0 (black) and background=255 (white). connectedComponentsWithStats
   * treats non-zero pixels as foreground, so we must invert first.
   */
  static int estimateMedianComponentHeight(Mat bw /* CV_8UC1 0/255, text=0, bg=255 */) {
    Mat inv = new Mat();
    Mat labels = new Mat();
    Mat stats = new Mat();
    Mat centroids = new Mat();
    try {
      // Invert so text becomes white (foreground) for connectedComponents
      Core.bitwise_not(bw, inv);
      int n = Imgproc.connectedComponentsWithStats(inv, labels, stats, centroids, 8, CvType.CV_32S);
      if (n <= 1) return -1;
      int rows = bw.rows(), cols = bw.cols();
      int imgArea = rows * cols;
      int minArea = Math.max(12, imgArea / 20000);
      int maxArea = Math.max(minArea + 1, imgArea / 5);
      List<Integer> heights = new ArrayList<>();
      for (int i = 1; i < n; i++) {
        int ai = (int) stats.get(i, Imgproc.CC_STAT_AREA)[0];
        int hi = (int) stats.get(i, Imgproc.CC_STAT_HEIGHT)[0];
        int wi = (int) stats.get(i, Imgproc.CC_STAT_WIDTH)[0];
        if (ai < minArea || ai > maxArea) continue;
        if (hi < 3 || hi > rows * 0.6) continue;
        if (wi < 2 || wi > cols * 0.6) continue;
        heights.add(hi);
      }
      if (heights.isEmpty()) return -1;
      Collections.sort(heights);
      return heights.get(heights.size() / 2);
    } catch (Throwable t) {
      return -1;
    } finally {
      inv.release();
      labels.release();
      stats.release();
      centroids.release();
    }
  }

  /**
   * Sauvola local adaptive thresholding.
   *
   * @param src8u grayscale CV_8U
   * @param dst output binary CV_8U (0/255)
   * @param win odd window size for local statistics
   * @param k typically in [0.2, 0.5]
   * @param R dynamic range of standard deviation (typically 128 or 255)
   */
  static void sauvolaThreshold(Mat src8u, Mat dst, int win, double k, double R) {
    if (win % 2 == 0) win++;
    int btype = CvType.CV_32F;
    Mat f = new Mat();
    Mat mean = new Mat();
    Mat sq = new Mat();
    Mat meanSq = new Mat();
    Mat var = new Mat();
    Mat stddev = new Mat();
    Mat thresh = new Mat();
    Mat mask = new Mat();
    try {
      src8u.convertTo(f, btype);
      Imgproc.boxFilter(f, mean, btype, new Size(win, win));
      Core.multiply(f, f, sq);
      Imgproc.boxFilter(sq, meanSq, btype, new Size(win, win));
      // var = E[x^2] - (E[x])^2
      Core.multiply(mean, mean, var);
      Core.subtract(meanSq, var, var);
      Core.max(var, new Scalar(0.0), var);
      Core.sqrt(var, stddev);

      // thresh = mean * (1 + k*((std/R) - 1))
      Mat stdDivR = new Mat();
      Core.divide(stddev, new Scalar(R), stdDivR);
      Mat tmp = new Mat();
      Core.subtract(stdDivR, new Scalar(1.0), tmp);
      Core.multiply(tmp, new Scalar(k), tmp);
      Core.add(tmp, new Scalar(1.0), tmp);
      Core.multiply(mean, tmp, thresh);

      // compare f > thresh -> 255 else 0
      Core.compare(f, thresh, mask, Core.CMP_GT);
      dst.create(src8u.size(), CvType.CV_8U);
      dst.setTo(new Scalar(0));
      dst.setTo(new Scalar(255), mask);
    } finally {
      f.release();
      mean.release();
      sq.release();
      meanSq.release();
      var.release();
      stddev.release();
      thresh.release();
      mask.release();
    }
  }

  /**
   * Wolf local adaptive thresholding. Similar to Sauvola but uses the global maximum standard
   * deviation as R, making it more robust for images with uneven illumination. Formula: T(x,y) =
   * mean * (1 + k * ((stddev / R) - 1)) where R = max(stddev) globally
   *
   * @param src8u grayscale CV_8U
   * @param dst output binary CV_8U (0/255)
   * @param win odd window size for local statistics
   * @param k typically in [0.2, 0.5]
   */
  static void wolfThreshold(Mat src8u, Mat dst, int win, double k) {
    if (win % 2 == 0) win++;
    int btype = CvType.CV_32F;
    Mat f = new Mat();
    Mat mean = new Mat();
    Mat sq = new Mat();
    Mat meanSq = new Mat();
    Mat var = new Mat();
    Mat stddev = new Mat();
    Mat thresh = new Mat();
    Mat mask = new Mat();
    try {
      src8u.convertTo(f, btype);
      Imgproc.boxFilter(f, mean, btype, new Size(win, win));
      Core.multiply(f, f, sq);
      Imgproc.boxFilter(sq, meanSq, btype, new Size(win, win));
      // var = E[x^2] - (E[x])^2
      Core.multiply(mean, mean, var);
      Core.subtract(meanSq, var, var);
      Core.max(var, new Scalar(0.0), var);
      Core.sqrt(var, stddev);

      // Wolf's key difference: R = max(stddev) globally instead of fixed constant
      Core.MinMaxLocResult mmr = Core.minMaxLoc(stddev);
      double wolfR = Math.max(1.0, mmr.maxVal); // avoid division by zero

      Mat stdDivR = new Mat();
      Core.divide(stddev, new Scalar(wolfR), stdDivR);
      Mat tmp = new Mat();
      Core.subtract(stdDivR, new Scalar(1.0), tmp);
      Core.multiply(tmp, new Scalar(k), tmp);
      Core.add(tmp, new Scalar(1.0), tmp);
      Core.multiply(mean, tmp, thresh);

      // compare f > thresh -> 255 else 0
      Core.compare(f, thresh, mask, Core.CMP_GT);
      dst.create(src8u.size(), CvType.CV_8U);
      dst.setTo(new Scalar(0));
      dst.setTo(new Scalar(255), mask);

      stdDivR.release();
      tmp.release();
    } finally {
      f.release();
      mean.release();
      sq.release();
      meanSq.release();
      var.release();
      stddev.release();
      thresh.release();
      mask.release();
    }
  }

  /**
   * NICK (Niblack Improved Contrast K-factor) local adaptive thresholding. An improved version of
   * Niblack that handles low contrast regions better. Formula: T(x,y) = mean + k * sqrt(stddev^2 +
   * mean^2) This avoids the issue of Niblack producing noise in uniform regions.
   *
   * @param src8u grayscale CV_8U
   * @param dst output binary CV_8U (0/255)
   * @param win odd window size for local statistics
   * @param k typically in [-0.2, -0.1] (negative values for dark text on light background)
   */
  static void nickThreshold(Mat src8u, Mat dst, int win, double k) {
    if (win % 2 == 0) win++;
    int btype = CvType.CV_32F;
    Mat f = new Mat();
    Mat mean = new Mat();
    Mat sq = new Mat();
    Mat meanSq = new Mat();
    Mat var = new Mat();
    Mat thresh = new Mat();
    Mat mask = new Mat();
    try {
      src8u.convertTo(f, btype);
      Imgproc.boxFilter(f, mean, btype, new Size(win, win));
      Core.multiply(f, f, sq);
      Imgproc.boxFilter(sq, meanSq, btype, new Size(win, win));
      // var = E[x^2] - (E[x])^2
      Core.multiply(mean, mean, var);
      Core.subtract(meanSq, var, var);
      Core.max(var, new Scalar(0.0), var);

      // NICK formula: T = mean + k * sqrt(var + mean^2)
      // This is equivalent to: T = mean + k * sqrt(stddev^2 + mean^2)
      Mat meanSquared = new Mat();
      Core.multiply(mean, mean, meanSquared);
      Mat sumVarMeanSq = new Mat();
      Core.add(var, meanSquared, sumVarMeanSq);
      Mat sqrtTerm = new Mat();
      Core.sqrt(sumVarMeanSq, sqrtTerm);

      // thresh = mean + k * sqrtTerm
      Mat kTimesRoot = new Mat();
      Core.multiply(sqrtTerm, new Scalar(k), kTimesRoot);
      Core.add(mean, kTimesRoot, thresh);

      // compare f > thresh -> 255 else 0
      Core.compare(f, thresh, mask, Core.CMP_GT);
      dst.create(src8u.size(), CvType.CV_8U);
      dst.setTo(new Scalar(0));
      dst.setTo(new Scalar(255), mask);

      meanSquared.release();
      sumVarMeanSq.release();
      sqrtTerm.release();
      kTimesRoot.release();
    } finally {
      f.release();
      mean.release();
      sq.release();
      meanSq.release();
      var.release();
      thresh.release();
      mask.release();
    }
  }
}
