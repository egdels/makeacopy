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

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.experimental.UtilityClass;

/**
 * Centralized image loader that decodes bitmaps with a path-first strategy. - Prefer
 * decodeFile(path) to avoid device-specific URI issues (e.g., Huawei/Honor API 29) - Fallback to
 * ContentResolver.openInputStream(uri) + decodeStream - Applies EXIF rotation when decoding via
 * stream; for file path, we try to rotate by reading EXIF from the file. - Provides async helper to
 * post result on main thread.
 */
@UtilityClass
public final class ImageLoader {
  private static final String TAG = "ImageLoader";

  private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  public interface Callback {
    void onLoaded(@Nullable Bitmap bitmap);

    void onError(@Nullable Throwable error);
  }

  /**
   * Decodes an image from a file path or URI into a {@link Bitmap}. The method attempts to read
   * image data, apply downsampling if necessary, and correct orientation using EXIF metadata, if
   * available.
   *
   * @param ctx the context used to access resources, such as a {@link ContentResolver}.
   * @param path the file path to the image to be decoded. This can be {@code null}.
   * @param uri the URI of the image to be decoded. This can be {@code null}.
   * @return a decoded {@link Bitmap} if successful, or {@code null} if decoding fails or both
   *     {@code path} and {@code uri} are {@code null}.
   */
  @Nullable
  public static Bitmap decode(Context ctx, @Nullable String path, @Nullable Uri uri) {
    long t0 = System.currentTimeMillis();
    String where = "none";
    try {
      // Try path first
      if (path != null && !path.isEmpty()) {
        File f = new File(path);
        if (f.exists() && f.length() > 0) {
          BitmapFactory.Options opts = new BitmapFactory.Options();
          // Basic downsampling safety: if image is huge, downsample roughly to max ~4096 px on
          // longer side
          // First decode bounds
          opts.inJustDecodeBounds = true;
          BitmapFactory.decodeFile(path, opts);
          opts.inJustDecodeBounds = false;
          opts.inSampleSize = calcInSampleSize(opts, 4096, 4096);
          Bitmap bmp = BitmapFactory.decodeFile(path, opts);
          if (bmp != null) {
            where = "path";
            // Apply EXIF rotation for file source
            try {
              int orientation = readExifRotation(path);
              if (orientation != 0) {
                Matrix m = new Matrix();
                m.postRotate(orientation);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
              }
            } catch (Exception ex) {
              Log.w(TAG, "EXIF rotation (file) failed: " + ex.getMessage());
            }
            return bmp;
          }
        }
      }

      // Fallback to URI
      if (uri != null) {
        ContentResolver resolver = ctx.getContentResolver();
        try (InputStream is = resolver.openInputStream(uri)) {
          if (is == null) return null;
          BitmapFactory.Options bounds = new BitmapFactory.Options();
          bounds.inJustDecodeBounds = true;
          // Need a mark/reset-capable stream to decode bounds then real; use two streams
          // So reopen stream to avoid mark/reset complexity
        }
        // Re-open for decode with sample size
        BitmapFactory.Options opts2 = new BitmapFactory.Options();
        opts2.inJustDecodeBounds = true;
        try (InputStream is2 = ctx.getContentResolver().openInputStream(uri)) {
          if (is2 == null) return null;
          BitmapFactory.decodeStream(is2, null, opts2);
        }
        opts2.inJustDecodeBounds = false;
        opts2.inSampleSize = calcInSampleSize(opts2, 4096, 4096);
        Bitmap bmp;
        try (InputStream is3 = ctx.getContentResolver().openInputStream(uri)) {
          if (is3 == null) return null;
          bmp = BitmapFactory.decodeStream(is3, null, opts2);
        }
        if (bmp != null) {
          where = "uri";
          // Apply EXIF rotation for URI source if available
          try (InputStream exifIs = ctx.getContentResolver().openInputStream(uri)) {
            if (exifIs != null) {
              ExifInterface exif = new ExifInterface(exifIs);
              int orientation =
                  exif.getAttributeInt(
                      ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
              int degrees = exifOrientationToDegrees(orientation);
              if (degrees != 0) {
                Matrix m = new Matrix();
                m.postRotate(degrees);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
              }
            }
          } catch (Exception ex) {
            Log.w(TAG, "EXIF rotation (uri) failed: " + ex.getMessage());
          }
          return bmp;
        }
      }
    } catch (Throwable t) {
      Log.e(TAG, "decode failed: " + t.getMessage(), t);
      return null;
    } finally {
      long dt = System.currentTimeMillis() - t0;
      Log.i(
          TAG,
          "ImageLoader.decode done in "
              + dt
              + "ms via "
              + where
              + ", path="
              + path
              + ", uri="
              + (uri != null ? uri.toString() : "null"));
    }
    return null;
  }

  /** Async version; posts result on main thread. */
  public static void decodeAsync(
      Context ctx, @Nullable String path, @Nullable Uri uri, Callback cb) {
    EXECUTOR.execute(
        () -> {
          Bitmap bmp = null;
          Throwable err = null;
          try {
            bmp = decode(ctx, path, uri);
          } catch (Throwable t) {
            err = t;
          }
          Bitmap finalBmp = bmp;
          Throwable finalErr = err;
          MAIN.post(
              () -> {
                if (finalBmp != null) cb.onLoaded(finalBmp);
                else cb.onError(finalErr);
              });
        });
  }

  private static int calcInSampleSize(BitmapFactory.Options opts, int reqW, int reqH) {
    int height = opts.outHeight;
    int width = opts.outWidth;
    int inSampleSize = 1;
    if (height > reqH || width > reqW) {
      final int halfHeight = height / 2;
      final int halfWidth = width / 2;
      while ((halfHeight / inSampleSize) >= reqH && (halfWidth / inSampleSize) >= reqW) {
        inSampleSize *= 2;
      }
    }
    return Math.max(1, inSampleSize);
  }

  private static int readExifRotation(String path) {
    try {
      ExifInterface exif = new ExifInterface(path);
      int orientation =
          exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
      return exifOrientationToDegrees(orientation);
    } catch (IOException e) {
      return 0;
    }
  }

  private static int exifOrientationToDegrees(int orientation) {
    switch (orientation) {
      case ExifInterface.ORIENTATION_ROTATE_90:
        return 90;
      case ExifInterface.ORIENTATION_ROTATE_180:
        return 180;
      case ExifInterface.ORIENTATION_ROTATE_270:
        return 270;
      default:
        return 0;
    }
  }
}
