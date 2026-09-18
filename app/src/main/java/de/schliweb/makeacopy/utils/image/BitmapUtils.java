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

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Size;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import lombok.experimental.UtilityClass;

/**
 * Utility class providing methods for handling and optimizing {@code Bitmap} instances,
 * specifically in ensuring they adhere to size and memory constraints for display. This class is
 * not intended to be instantiated.
 */
@UtilityClass
public final class BitmapUtils {

  // Conservative caps to avoid Canvas: trying to draw too large(...) bitmap
  // - Max edge in pixels
  // - Max total bytes for ARGB_8888 draw (~4 bytes per pixel)
  private static final int DEFAULT_MAX_EDGE = 4096; // widely safe on many devices
  private static final long DEFAULT_MAX_DRAW_BYTES = 100L * 1024L * 1024L; // ~100 MB

  /**
   * Ensures that the provided bitmap is safe to display on a Canvas by scaling it down if it
   * exceeds predefined edge or memory limits. If the bitmap is within the limits, the same instance
   * is returned. Otherwise, a downscaled copy is created and returned.
   *
   * @param src The input bitmap to process. If null, the method returns null.
   * @return A bitmap that is safe to display. Returns the original bitmap if it is within the
   *     limits, or a scaled-down copy otherwise. If the provided bitmap is null, the method returns
   *     null.
   */
  public static Bitmap ensureDisplaySafe(Bitmap src) {
    return ensureDisplaySafe(src, DEFAULT_MAX_EDGE, DEFAULT_MAX_DRAW_BYTES);
  }

  /**
   * Ensures that the provided bitmap is safe to display on a Canvas by scaling it down if it
   * exceeds predefined edge or memory limits. If the bitmap is within the limits, the same instance
   * is returned. Otherwise, a downscaled copy is created and returned.
   *
   * @param src The input bitmap to process. If null, the method returns null.
   * @param maxEdge The maximum allowed size (in pixels) for the width or height of the bitmap.
   * @param maxBytes The maximum allowed memory size (in bytes) for the bitmap.
   * @return A bitmap that is safe to display. Returns the original bitmap if it is within the
   *     limits, or a scaled-down copy otherwise. If the provided bitmap is null, the method returns
   *     null.
   */
  public static Bitmap ensureDisplaySafe(Bitmap src, int maxEdge, long maxBytes) {
    if (src == null) return null;
    int w = src.getWidth();
    int h = src.getHeight();
    if (w <= 0 || h <= 0) return src;
    long bytes = bytesForDraw(src);
    boolean overEdge = (w > maxEdge) || (h > maxEdge);
    boolean overBytes = bytes > maxBytes;
    if (!overEdge && !overBytes) return src;

    // Compute scale factor to satisfy both edge and bytes constraints
    float scaleEdge = 1f;
    if (overEdge) {
      scaleEdge = Math.min(maxEdge / (float) w, maxEdge / (float) h);
    }
    float scaleBytes = 1f;
    if (overBytes) {
      // bytes scales with area => scale factor by sqrt(maxBytes/currentBytes)
      scaleBytes = (float) Math.sqrt((double) maxBytes / Math.max(1d, (double) bytes));
    }
    float scale = Math.min(scaleEdge, scaleBytes);
    if (scale >= 1f) return src; // numeric safety

    int newW = Math.max(1, Math.round(w * scale));
    int newH = Math.max(1, Math.round(h * scale));
    if (newW == w && newH == h) return src;

    // Create a scaled bitmap. Use createScaledBitmap for quality/simplicity.
    Bitmap scaled = Bitmap.createScaledBitmap(src, newW, newH, true);
    return scaled != null ? scaled : src;
  }

  /**
   * Calculates the estimated memory usage in bytes for a bitmap when rendered with the ARGB_8888
   * configuration, which requires 4 bytes per pixel.
   *
   * @param b The input Bitmap for which the byte size is calculated. Must not be null.
   * @return The estimated memory usage in bytes for drawing the bitmap.
   */
  private static long bytesForDraw(Bitmap b) {
    // Most draw paths end up using ARGB_8888; use 4 bytes per pixel estimate.
    // Use getAllocationByteCount if available, but keep a cap for safety.
    long pixels = (long) b.getWidth() * (long) b.getHeight();
    return pixels * 4L;
  }

  /**
   * Scales the dimensions of a source rectangle to fit within a target rectangle, preserving the
   * aspect ratio.
   *
   * @param srcW The width of the source rectangle. Must be a positive integer.
   * @param srcH The height of the source rectangle. Must be a positive integer.
   * @param maxW The maximum width of the target rectangle. Must be a positive integer.
   * @param maxH The maximum height of the target rectangle. Must be a positive integer.
   * @return A {@code Size} object representing the scaled width and height that fit within the
   *     target dimensions while preserving the aspect ratio. Returns a {@code Size} of (0, 0) if
   *     either {@code srcW} or {@code srcH} is less than or equal to zero.
   */
  public static Size fitInto(int srcW, int srcH, int maxW, int maxH) {
    if (srcW <= 0 || srcH <= 0) return new Size(0, 0);
    float scale = Math.min(maxW / (float) srcW, maxH / (float) srcH);
    int w = Math.max(1, Math.round(srcW * scale));
    int h = Math.max(1, Math.round(srcH * scale));
    return new Size(w, h);
  }

  /**
   * Loads a preview bitmap for a completed scan, given the scan metadata and the requested
   * dimensions. The method attempts to fetch an in-memory bitmap if available, decode it from the
   * scan's file or thumbnail paths, and optionally rotates the bitmap based on the scan's rotation
   * metadata.
   *
   * @param scan The {@code CompletedScan} object containing metadata about the scan. This includes
   *     paths to the file, thumbnail, and rotation data. If null, the method returns null.
   * @param reqW The required width of the bitmap. Must be a positive integer.
   * @param reqH The required height of the bitmap. Must be a positive integer.
   * @return A {@code Bitmap} object that represents the preview of the completed scan. Returns null
   *     if no bitmap could be loaded or if an error occurs.
   */
  public static Bitmap loadPreviewBitmapForCompletedScan(CompletedScan scan, int reqW, int reqH) {
    if (scan == null) return null;
    Bitmap bmp = scan.inMemoryBitmap();
    boolean fromDisk = false;
    try {
      if (bmp == null) {
        String path = scan.filePath();
        if (path != null) {
          bmp = ImageDecodeUtils.decodeSampled(path, Math.max(1, reqW), Math.max(1, reqH));
          if (bmp != null) fromDisk = true;
        }
        if (bmp == null && scan.thumbPath() != null) {
          bmp =
              ImageDecodeUtils.decodeSampled(
                  scan.thumbPath(), Math.max(1, reqW), Math.max(1, reqH));
          if (bmp != null) fromDisk = true;
        }
      }
      if (bmp != null) {
        int deg = 0;
        try {
          deg = scan.rotationDeg();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
        String mode = null;
        try {
          mode = scan.orientationMode();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
        boolean shouldRotate;
        try {
          shouldRotate = RotationPolicy.shouldRotateForThumbnail(fromDisk, mode, deg);
        } catch (Throwable ignore) {
          shouldRotate = false;
        }
        if (!shouldRotate) return bmp;
        bmp = BitmapUtils.maybeRotate(bmp, deg);
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    return bmp;
  }

  /**
   * Fast-path variant of {@link #loadPreviewBitmapForCompletedScan}: decodes only the small
   * thumbnail ({@code thumbPath}) of the given scan, applying the same rotation policy. Intended as
   * an instant placeholder while the full-resolution preview is decoded and processed in the
   * background. Returns null when no thumbnail exists or decoding fails.
   *
   * @param scan The {@code CompletedScan} whose thumbnail should be decoded. May be null.
   * @param reqW The requested width for sampled decoding. Must be a positive integer.
   * @param reqH The requested height for sampled decoding. Must be a positive integer.
   * @return The decoded (and possibly rotated) thumbnail bitmap, or null.
   */
  public static Bitmap loadQuickThumbBitmapForCompletedScan(
      CompletedScan scan, int reqW, int reqH) {
    if (scan == null) return null;
    try {
      String thumbPath = scan.thumbPath();
      if (thumbPath == null) return null;
      Bitmap bmp = ImageDecodeUtils.decodeSampled(thumbPath, Math.max(1, reqW), Math.max(1, reqH));
      if (bmp == null) return null;
      int deg = 0;
      try {
        deg = scan.rotationDeg();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      String mode = null;
      try {
        mode = scan.orientationMode();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      boolean shouldRotate;
      try {
        shouldRotate = RotationPolicy.shouldRotateForThumbnail(true, mode, deg);
      } catch (Throwable ignore) {
        shouldRotate = false;
      }
      return shouldRotate ? maybeRotate(bmp, deg) : bmp;
    } catch (Throwable ignore) {
      return null;
    }
  }

  /**
   * Rotates the given bitmap by the specified degrees if necessary. - Degrees are normalized to [0,
   * 360). - Returns the original bitmap if rotation is 0 or if any error occurs. - If rotation
   * succeeds, returns the rotated instance (caller may recycle original if desired).
   */
  public static Bitmap maybeRotate(Bitmap src, int degrees) {
    if (src == null) return null;
    int deg = normalizeDegreesSafe(degrees);
    if (deg == 0) return src;
    try {
      Matrix m = new Matrix();
      m.postRotate(deg);
      Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
      return (rotated != null) ? rotated : src;
    } catch (Throwable ignore) {
      return src;
    }
  }

  /** Normalizes any degree value into the [0, 360) range. On errors returns 0. */
  public static int normalizeDegreesSafe(int degrees) {
    try {
      int d = degrees % 360;
      if (d < 0) d += 360;
      return d;
    } catch (Throwable ignore) {
      return 0;
    }
  }

  /**
   * Processes the given bitmap for preview purposes based on user preferences stored in shared
   * preferences. The processing includes optional grayscale or black-and-white conversion, and
   * ensures that the bitmap reflects specific export options if applicable.
   *
   * @param source The input bitmap to be processed. If null, the method returns null.
   * @param ctx The context used to access shared preferences and initialize OpenCV. If null, the
   *     method returns the input bitmap unchanged.
   * @return A processed bitmap reflecting the user preferences for preview. Returns the original
   *     bitmap if processing fails or no modifications are required.
   */
  public static Bitmap processForPreview(Bitmap source, Context ctx) {
    if (source == null || ctx == null) return source;
    try {
      SharedPreferences prefs = ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
      boolean toGray = prefs.getBoolean("convert_to_grayscale", false);
      boolean toBw = false;
      boolean exportAsJpeg = prefs.getBoolean("export_as_jpeg", false);
      DocumentCleanupMode cleanupMode = DocumentCleanupMode.ORIGINAL;
      try {
        cleanupMode =
            DocumentCleanupMode.valueOf(
                prefs.getString("document_cleanup_mode", DocumentCleanupMode.ORIGINAL.name()));
      } catch (Throwable ignored) {
        cleanupMode = DocumentCleanupMode.ORIGINAL;
      }

      if (exportAsJpeg) {
        toGray = false;
        toBw = prefs.getBoolean("jpeg_force_bw", false);
        toGray = prefs.getBoolean("jpeg_output_grayscale", false);
        try {
          String mode = prefs.getString("jpeg_mode", "NONE");
          if ("BW_TEXT".equalsIgnoreCase(mode)) {
            toBw = true;
            toGray = false;
          }
        } catch (Throwable ignored) {
          // Best-effort; failure is non-critical
        }
      } else {
        try {
          String mode = prefs.getString("pdf_bw_mode", null);
          if ("GRAYSCALE".equalsIgnoreCase(mode)) {
            toGray = true;
            toBw = false;
          } else if ("ROBUST".equalsIgnoreCase(mode) || "CLASSIC".equalsIgnoreCase(mode)) {
            toBw = true;
            toGray = false;
          } else {
            toBw = false;
          }
        } catch (Throwable ignored) {
          toBw = false;
        }
      }

      Bitmap safe = BitmapUtils.ensureDisplaySafe(source);
      Bitmap out = safe;

      if (cleanupMode != DocumentCleanupMode.ORIGINAL
          && !(toBw && cleanupMode == DocumentCleanupMode.CLEAN_TEXT)) {
        try {
          if (!OpenCVUtils.isInitialized()) {
            OpenCVUtils.init(ctx.getApplicationContext());
          }
        } catch (Throwable ignored) {
          // Best-effort; failure is non-critical
        }
        try {
          DocumentCleanupOptions cleanupOptions = new DocumentCleanupOptions(cleanupMode);
          cleanupOptions.preserveColor = !toGray && !toBw;
          cleanupOptions.optimizeForOcr = toBw && cleanupMode != DocumentCleanupMode.CLEAN_TEXT;
          Bitmap cleaned = DocumentCleanupProcessor.apply(ctx, safe, cleanupOptions);
          if (cleaned != null) out = cleaned;
        } catch (Throwable ignored) {
          // Best-effort; failure is non-critical
        }
      }

      if (toBw || toGray) {
        try {
          if (!OpenCVUtils.isInitialized()) {
            OpenCVUtils.init(ctx.getApplicationContext());
          }
        } catch (Throwable ignored) {
          // Best-effort; failure is non-critical
        }
        try {
          if (toBw) {
            String bwMode = exportAsJpeg ? "ROBUST" : prefs.getString("pdf_bw_mode", "ROBUST");
            boolean classicBw = "CLASSIC".equalsIgnoreCase(bwMode);
            Bitmap bw;
            if (classicBw) {
              BinarizationUtils.BwOptions opt = new BinarizationUtils.BwOptions();
              opt.mode = BinarizationUtils.BwOptions.Mode.OTSU_ONLY;
              opt.useClahe = true;
              opt.removeShadows = true;
              bw = OpenCVUtils.toBw(out, opt);
            } else {
              bw = OpenCVUtils.toBw(out);
            }
            if (bw != null) out = bw;
          } else if (toGray) {
            Bitmap gr = OpenCVUtils.toGray(out);
            if (gr != null) out = gr;
          }
        } catch (Throwable ignored) {
          // Best-effort; failure is non-critical
        }
      }
      return out;
    } catch (Throwable ignore) {
      return source;
    }
  }
}
