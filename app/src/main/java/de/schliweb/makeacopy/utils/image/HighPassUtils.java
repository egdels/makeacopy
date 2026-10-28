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
import android.util.Log;
import lombok.experimental.UtilityClass;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

/**
 * Shared building blocks for "high-pass" / background-division style document filtering.
 *
 * <p>The idea (see e.g. https://superuser.com/a/1856394): build a strongly blurred copy of the
 * image as an estimate of the background illumination, and divide the original by that estimate.
 * The result has an evenly lit, near-white background while preserving local contrast (grayscale
 * edges on small text), which is beneficial both for visual output and for OCR.
 *
 * <p>This utility centralizes the logic shared by {@link BinarizationUtils} and the Enhanced
 * document cleanup so that both use the exact same base behavior.
 *
 * <p>This class cannot be instantiated.
 */
@UtilityClass
public final class HighPassUtils {
  private static final String TAG = "HighPassUtils";

  /**
   * Default kernel fraction used by binarization (matches historical behavior of {@link
   * BinarizationUtils#toBw(Bitmap, BinarizationUtils.BwOptions)}): ~8% of the shorter image side.
   */
  public static final double KERNEL_FRACTION_BW = 0.08;


  /**
   * Performs background-division normalization on a single-channel 8-bit image ({@link
   * CvType#CV_8U}). The input is not modified; the result is written to {@code dst} (8-bit,
   * 1-channel). Works in floating point to avoid quantization banding.
   *
   * <p>When {@link OpenCVUtils#isSafeMode()} is active, this method falls back to a plain copy,
   * because {@code Mat.convertTo} has been observed to crash on some emulator CPUs lacking expected
   * SIMD instructions.
   *
   * @param gray input grayscale image, {@code CV_8UC1}. Must be non-null and non-empty.
   * @param dst output image (will be (re)allocated as {@code CV_8UC1}). Must be non-null. May be
   *     the same as {@code gray} for in-place operation.
   * @param kernelFraction fraction of the shorter image side to use as blur kernel size. Typical
   *     values: 0.03 (fine illumination) – 0.08 (stronger flattening).
   */
  public static void backgroundDivideGray(Mat gray, Mat dst, double kernelFraction) {
    backgroundDivideGray(gray, dst, kernelFraction, 15);
  }

  /**
   * Variant allowing callers to control the minimum kernel size. Useful to preserve historical
   * behavior of the binarization path which capped the kernel at a larger minimum to guarantee
   * strong shadow flattening even on small inputs.
   */
  public static void backgroundDivideGray(Mat gray, Mat dst, double kernelFraction, int minKernel) {
    if (gray == null || dst == null || gray.empty()) return;
    if (OpenCVUtils.isSafeMode()) {
      Log.d(TAG, "backgroundDivideGray: safe mode - plain copy");
      gray.copyTo(dst);
      return;
    }
    int k =
        Math.max(
            Math.max(3, minKernel), (int) (Math.min(gray.width(), gray.height()) * kernelFraction));
    if (k % 2 == 0) k++;
    Mat bg = new Mat();
    Mat gf = new Mat();
    Mat bgf = new Mat();
    Mat norm = new Mat();
    try {
      Imgproc.GaussianBlur(gray, bg, new Size(k, k), 0);
      gray.convertTo(gf, CvType.CV_32F);
      bg.convertTo(bgf, CvType.CV_32F);
      Core.max(bgf, new Scalar(1.0), bgf); // prevent div-by-zero
      Core.divide(gf, bgf, norm); // ~0..1
      Core.multiply(norm, new Scalar(255.0), norm); // ~0..255
      norm.convertTo(dst, CvType.CV_8U);
    } finally {
      bg.release();
      gf.release();
      bgf.release();
      norm.release();
    }
  }

  /**
   * Convenience variant returning a new {@link Mat}. Caller must release it.
   *
   * @see #backgroundDivideGray(Mat, Mat, double)
   */
  public static Mat backgroundDivideGray(Mat gray, double kernelFraction) {
    Mat out = new Mat();
    backgroundDivideGray(gray, out, kernelFraction);
    return out;
  }

  /**
   * Performs background-division normalization on the luminance channel of an LAB image while
   * preserving the a/b chroma channels. The input is not modified; the result is written to {@code
   * dst} (8-bit, 3-channel LAB).
   *
   * <p>This is the color counterpart to {@link #backgroundDivideGray(Mat, Mat, double)}: it
   * flattens uneven lighting/shadows without desaturating the image.
   *
   * <p>When {@link OpenCVUtils#isSafeMode()} is active, this method falls back to a plain copy.
   *
   * @param lab input image in LAB color space, {@code CV_8UC3}. Must be non-null and non-empty.
   * @param dst output image in LAB color space ({@code CV_8UC3}). Must be non-null. May be the same
   *     as {@code lab} for in-place operation.
   * @param kernelFraction fraction of the shorter image side to use as blur kernel size.
   */
  public static void backgroundDivideLab(Mat lab, Mat dst, double kernelFraction) {
    if (lab == null || dst == null || lab.empty()) return;
    if (OpenCVUtils.isSafeMode()) {
      Log.d(TAG, "backgroundDivideLab: safe mode - plain copy");
      lab.copyTo(dst);
      return;
    }
    java.util.List<Mat> channels = new java.util.ArrayList<>(3);
    Mat lNorm = new Mat();
    try {
      Core.split(lab, channels);
      if (channels.size() < 3) {
        lab.copyTo(dst);
        return;
      }
      backgroundDivideGray(channels.get(0), lNorm, kernelFraction);
      // release old L and replace with normalized L
      channels.get(0).release();
      channels.set(0, lNorm);
      Core.merge(channels, dst);
    } finally {
      for (Mat c : channels) {
        if (c != null && c != lNorm) c.release();
      }
      // lNorm was merged into dst as a copy; still release our local handle safely
      // (Core.merge copies pixel data out of the input Mats)
      lNorm.release();
    }
  }

  /**
   * Applies the high-pass filter to a {@link Bitmap} while preserving color. Works on the L channel
   * in LAB color space and keeps the a/b chroma channels untouched, yielding an evenly lit color
   * image suitable for visual output/export (not intended for OCR).
   *
   * @param src input bitmap (ARGB_8888 / RGBA). Must be non-null and not recycled.
   * @param applyClahe whether to apply a mild CLAHE on the L channel after normalization.
   * @return a new ARGB_8888 bitmap with the filtered content, or {@code null} on failure.
   */
  public static Bitmap applyHighPassColor(Bitmap src, boolean applyClahe) {
    if (src == null || src.isRecycled()) return null;
    Mat rgba = new Mat();
    Mat rgb = new Mat();
    Mat lab = new Mat();
    Mat labOut = new Mat();
    Mat rgbOut = new Mat();
    Mat rgbaOut = new Mat();
    Bitmap out = null;
    CLAHE clahe = null;
    java.util.List<Mat> channels = null;
    try {
      Utils.bitmapToMat(src, rgba);
      Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
      Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab);
      if (applyClahe) {
        channels = new java.util.ArrayList<>(3);
        Core.split(lab, channels);
        Mat lNorm = new Mat();
        try {
          backgroundDivideGray(channels.get(0), lNorm, KERNEL_FRACTION_BW);
          try {
            clahe = Imgproc.createCLAHE(1.5, new Size(8, 8));
            clahe.apply(lNorm, lNorm);
          } catch (Throwable ignore) {
            // CLAHE is optional; ignore failures
          }
          channels.get(0).release();
          channels.set(0, lNorm);
          Core.merge(channels, labOut);
        } finally {
          for (Mat c : channels) {
            if (c != null && c != lNorm) c.release();
          }
          lNorm.release();
        }
      } else {
        backgroundDivideLab(lab, labOut, KERNEL_FRACTION_BW);
      }
      Imgproc.cvtColor(labOut, rgbOut, Imgproc.COLOR_Lab2RGB);
      Imgproc.cvtColor(rgbOut, rgbaOut, Imgproc.COLOR_RGB2RGBA);
      out = Bitmap.createBitmap(rgbaOut.cols(), rgbaOut.rows(), Bitmap.Config.ARGB_8888);
      Utils.matToBitmap(rgbaOut, out);
      return out;
    } catch (Throwable t) {
      Log.e(TAG, "applyHighPassColor failed", t);
      if (out != null) {
        try {
          out.recycle();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
      return null;
    } finally {
      rgba.release();
      rgb.release();
      lab.release();
      labOut.release();
      rgbOut.release();
      rgbaOut.release();
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
   * Applies the high-pass filter to a {@link Bitmap} and returns a grayscale result (ARGB_8888 for
   * convenient Android consumption; each RGB channel holds the same value). Optionally applies a
   * gentle CLAHE to recover local contrast on flat documents.
   *
   * <p>Intended as the output of a dedicated "clean grayscale" filter mode.
   *
   * @param src input bitmap (ARGB_8888 / RGBA). Must be non-null and not recycled.
   * @param applyClahe whether to apply a mild CLAHE after normalization.
   * @return a new ARGB_8888 bitmap with the filtered content, or {@code null} on failure.
   */
  public static Bitmap applyHighPassGray(Bitmap src, boolean applyClahe) {
    if (src == null || src.isRecycled()) return null;
    Mat rgba = new Mat();
    Mat gray = new Mat();
    Mat work = new Mat();
    Mat rgbaOut = new Mat();
    Bitmap out = null;
    CLAHE clahe = null;
    try {
      Utils.bitmapToMat(src, rgba);
      Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
      backgroundDivideGray(gray, work, KERNEL_FRACTION_BW);
      if (applyClahe) {
        try {
          clahe = Imgproc.createCLAHE(1.5, new Size(8, 8));
          clahe.apply(work, work);
        } catch (Throwable ignore) {
          // CLAHE is optional; ignore failures
        }
      }
      Imgproc.cvtColor(work, rgbaOut, Imgproc.COLOR_GRAY2RGBA);
      out = Bitmap.createBitmap(rgbaOut.cols(), rgbaOut.rows(), Bitmap.Config.ARGB_8888);
      Utils.matToBitmap(rgbaOut, out);
      return out;
    } catch (Throwable t) {
      Log.e(TAG, "applyHighPassGray failed", t);
      if (out != null) {
        try {
          out.recycle();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
      return null;
    } finally {
      rgba.release();
      gray.release();
      work.release();
      rgbaOut.release();
      if (clahe != null) {
        try {
          clahe.collectGarbage();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
    }
  }
}
