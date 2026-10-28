/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.export.jpeg;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import de.schliweb.makeacopy.utils.image.DocumentCleanupOptions;
import de.schliweb.makeacopy.utils.image.DocumentCleanupProcessor;
import de.schliweb.makeacopy.utils.image.ImageOps;
import de.schliweb.makeacopy.utils.infra.LogSafe;
import java.io.IOException;
import java.io.OutputStream;
import lombok.experimental.UtilityClass;

/**
 * JPEG exporter with optional downscale and document enhancement modes.
 *
 * <p>Modes: - NONE: optional downscale only. - BW_TEXT: black/white output. Document cleanup is
 * configured separately via {@link JpegExportOptions#cleanupMode}.
 *
 * <p>Notes: - Uses an optional long-edge guard (from options) to avoid OOM on huge images. - Can
 * round resize targets to multiples of 8 (helps JPEG block alignment). - Fast path: for "NONE +
 * resize" without grayscale forcing, uses Bitmap.createScaledBitmap.
 */
@UtilityClass
public final class JpegExporter {

  private static final String TAG = "JpegExporter";
  private static final int JPEG_BLOCK_SIZE = 8;

  /**
   * Exports the given (already perspective-corrected) bitmap to the provided targetUri as JPEG.
   *
   * @param context Application or Activity context
   * @param bitmap Perspective-corrected bitmap to save (ARGB_8888 recommended)
   * @param options Export options (if null a default instance is used)
   * @param targetUri Target Uri (e.g., from ACTION_CREATE_DOCUMENT with MIME image/jpeg)
   * @return targetUri if success, otherwise null
   */
  public static Uri export(
      Context context, Bitmap bitmap, JpegExportOptions options, Uri targetUri) {
    if (context == null || bitmap == null || targetUri == null) {
      Log.e(TAG, "export: invalid arguments (context/bitmap/uri)");
      return null;
    }
    if (options == null) options = new JpegExportOptions();

    final boolean outputModeNone = options.mode == JpegExportOptions.Mode.NONE;
    final JpegImageOutput imageOutput = resolveJpegImageOutput(options);
    final boolean cleanupNone =
        options.cleanupMode == null || options.cleanupMode == DocumentCleanupMode.ORIGINAL;
    final int srcW = bitmap.getWidth();
    final int srcH = bitmap.getHeight();
    final int curLong = Math.max(srcW, srcH);

    final int targetLong = computeTargetLongEdge(curLong, options);
    final boolean needsResize = targetLong > 0 && targetLong < curLong;

    // Shortcut 1: no resize + no cleanup/output conversion → compress original bitmap
    if (!needsResize && outputModeNone && cleanupNone && !options.forceGrayscaleJpeg) {
      return compressToUri(context, bitmap, options.quality, targetUri);
    }

    // Shortcut 2: ONLY resize (mode NONE)
    if (needsResize && outputModeNone && cleanupNone && !options.forceGrayscaleJpeg) {
      final double scale = targetLong / (double) curLong;
      int newW = (int) Math.round(srcW * scale);
      int newH = (int) Math.round(srcH * scale);
      newW = roundToJpegBlockSize(newW, options.roundResizeToMultipleOf8);
      newH = roundToJpegBlockSize(newH, options.roundResizeToMultipleOf8);
      newW = Math.max(8, newW);
      newH = Math.max(8, newH);
      Bitmap scaled;
      try {
        scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
        return compressToUri(context, scaled, options.quality, targetUri);
      } catch (OutOfMemoryError oom) {
        Log.e(TAG, "export: OOM during Bitmap scaling", oom);
        return null;
      } catch (Exception e) {
        Log.e(TAG, "export: error during Bitmap scaling", e);
        return null;
      }
    }

    try {
      Bitmap outBitmap = render(context, bitmap, options, imageOutput, needsResize, targetLong);
      return compressToUri(context, outBitmap, outputQuality(options), targetUri);
    } catch (OutOfMemoryError oom) {
      Log.e(TAG, "export: OutOfMemoryError during processing", oom);
      return null;
    } catch (Exception e) {
      Log.e(TAG, "export: error during processing", e);
      return null;
    }
  }

  // === IO ===

  private static Uri compressToUri(Context context, Bitmap bmp, int quality, Uri targetUri) {
    ContentResolver resolver = context.getContentResolver();
    try (OutputStream os = resolver.openOutputStream(targetUri, "w")) {
      if (os == null) {
        Log.e(TAG, "compressToUri: failed to open OutputStream for Uri: " + LogSafe.uri(targetUri));
        return null;
      }
      boolean ok = bmp.compress(Bitmap.CompressFormat.JPEG, clampQuality(quality), os);
      if (!ok) {
        Log.e(TAG, "compressToUri: Bitmap.compress returned false");
        return null;
      }
      os.flush(); // close() flushes too; explicit for clarity
      return targetUri;
    } catch (IOException e) {
      Log.e(TAG, "compressToUri: IO error while writing JPEG", e);
      return null;
    } catch (SecurityException se) {
      Log.e(TAG, "compressToUri: security error while writing JPEG", se);
      return null;
    }
  }

  /**
   * Processes and writes a JPEG to the provided OutputStream using the same pipeline as export().
   * Returns true on success.
   */
  public static boolean exportToStream(
      Context context, Bitmap bitmap, JpegExportOptions options, OutputStream out) {
    if (context == null || bitmap == null || out == null) {
      Log.e(TAG, "exportToStream: invalid arguments");
      return false;
    }
    if (options == null) options = new JpegExportOptions();

    final boolean outputModeNone = options.mode == JpegExportOptions.Mode.NONE;
    final JpegImageOutput imageOutput = resolveJpegImageOutput(options);
    final boolean cleanupNone =
        options.cleanupMode == null || options.cleanupMode == DocumentCleanupMode.ORIGINAL;
    final int srcW = bitmap.getWidth();
    final int srcH = bitmap.getHeight();
    final int curLong = Math.max(srcW, srcH);
    final int targetLong = computeTargetLongEdge(curLong, options);
    final boolean needsResize = targetLong > 0 && targetLong < curLong;

    // Shortcut 1
    if (!needsResize && outputModeNone && cleanupNone && !options.forceGrayscaleJpeg) {
      return bitmap.compress(Bitmap.CompressFormat.JPEG, clampQuality(options.quality), out);
    }
    // Shortcut 2: ONLY resize
    if (needsResize && outputModeNone && cleanupNone && !options.forceGrayscaleJpeg) {
      final double scale = targetLong / (double) curLong;
      int newW = (int) Math.round(srcW * scale);
      int newH = (int) Math.round(srcH * scale);
      newW = roundToJpegBlockSize(newW, options.roundResizeToMultipleOf8);
      newH = roundToJpegBlockSize(newH, options.roundResizeToMultipleOf8);
      newW = Math.max(8, newW);
      newH = Math.max(8, newH);
      Bitmap scaled;
      try {
        scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
        return scaled.compress(Bitmap.CompressFormat.JPEG, clampQuality(options.quality), out);
      } catch (Throwable t) {
        Log.e(TAG, "exportToStream: error during scaling", t);
        return false;
      }
    }

    try {
      Bitmap outBitmap = render(context, bitmap, options, imageOutput, needsResize, targetLong);
      return outBitmap.compress(
          Bitmap.CompressFormat.JPEG, clampQuality(outputQuality(options)), out);
    } catch (Throwable t) {
      Log.e(TAG, "exportToStream: error during processing", t);
      return false;
    }
  }

  // === utils ===

  /** Downscales (if needed), cleans up and converts {@code bitmap} for the JPEG. */
  private static Bitmap render(
      Context context,
      Bitmap bitmap,
      JpegExportOptions options,
      JpegImageOutput imageOutput,
      boolean needsResize,
      int targetLong) {
    Bitmap current = bitmap;
    if (needsResize) {
      int srcW = bitmap.getWidth();
      int srcH = bitmap.getHeight();
      final double scale = targetLong / (double) Math.max(srcW, srcH);
      int newW = (int) Math.round(srcW * scale);
      int newH = (int) Math.round(srcH * scale);
      newW = Math.max(8, roundToJpegBlockSize(newW, options.roundResizeToMultipleOf8));
      newH = Math.max(8, roundToJpegBlockSize(newH, options.roundResizeToMultipleOf8));
      current = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
    }
    Bitmap cleaned =
        DocumentCleanupProcessor.apply(
            context,
            current,
            buildCleanupOptions(options.cleanupMode, imageOutput == JpegImageOutput.COLOR));
    if (cleaned != null) current = cleaned;
    Bitmap converted = null;
    if (imageOutput == JpegImageOutput.BLACK_WHITE) {
      converted = ImageOps.toBw(current);
    } else if (imageOutput == JpegImageOutput.GRAYSCALE) {
      converted = ImageOps.toGray(current);
    }
    return converted != null ? converted : current;
  }

  /** Black and white text compresses extremely well; it gets a lower quality for a smaller file. */
  private static int outputQuality(JpegExportOptions options) {
    return options.mode == JpegExportOptions.Mode.BW_TEXT
        ? clampQuality(options.quality - 30)
        : options.quality;
  }

  private static int roundToJpegBlockSize(int value, boolean enabled) {
    if (!enabled) return value;
    return (value / JPEG_BLOCK_SIZE) * JPEG_BLOCK_SIZE;
  }

  static DocumentCleanupOptions buildCleanupOptions(
      DocumentCleanupMode cleanupMode, boolean preserveColor) {
    DocumentCleanupOptions cleanupOptions =
        cleanupMode != null
            ? new DocumentCleanupOptions(cleanupMode)
            : DocumentCleanupOptions.original();
    cleanupOptions.preserveColor = preserveColor;
    return cleanupOptions;
  }

  static JpegImageOutput resolveJpegImageOutput(JpegExportOptions options) {
    if (options != null && options.mode == JpegExportOptions.Mode.BW_TEXT) {
      return JpegImageOutput.BLACK_WHITE;
    }
    if (options != null && options.forceGrayscaleJpeg) {
      return JpegImageOutput.GRAYSCALE;
    }
    return JpegImageOutput.COLOR;
  }

  enum JpegImageOutput {
    COLOR,
    GRAYSCALE,
    BLACK_WHITE
  }

  private static int clampQuality(int q) {
    return Math.max(0, Math.min(100, q));
  }

  private static int computeTargetLongEdge(int curLong, JpegExportOptions opts) {
    // Base target: desired long edge or current
    int target = (opts.longEdgePx > 0) ? opts.longEdgePx : curLong;
    // Apply guard if set (>0)
    if (opts.maxLongEdgeGuardPx > 0) {
      target = Math.min(target, opts.maxLongEdgeGuardPx);
    }
    return target;
  }
}
