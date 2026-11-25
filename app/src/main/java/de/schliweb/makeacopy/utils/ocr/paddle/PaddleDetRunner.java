/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr.paddle;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import androidx.annotation.VisibleForTesting;
import de.schliweb.makeacopy.ml.ncnn.NcnnNet;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Class representing a PaddleOCR detection runner using NCNN. Provides detection capabilities on
 * image bitmaps to extract sorted quadrilateral coordinates in image space.
 */
class PaddleDetRunner implements AutoCloseable {

  private static final String TAG = "PaddleDet";

  /**
   * Represents the name of the input tensor used in the PaddleDetRunner for inference. This
   * constant is utilized to identify the corresponding input in the neural network model. The value
   * of this variable must match the input tensor name defined in the model configuration.
   */

  /**
   * The default maximum side length for resizing an image during detection processing. This value
   * determines the maximum dimension (in pixels) to which the input image is scaled while
   * maintaining its aspect ratio. It is used to ensure that the model processes inputs within a
   * consistent and optimal size range, balancing performance and accuracy.
   */
  static final int DEFAULT_MAX_SIDE = 1536;

  /**
   * Longer-side limit used when the user has enabled "Best Paddle OCR quality". Earlier versions
   * only retried at this size when the first pass on a large source produced at most 19 boxes,
   * which never happens on dense pages, so the option had no effect exactly where the extra
   * resolution matters (small body text on newspaper and magazine pages).
   */
  static final int HIGH_QUALITY_MAX_SIDE = 1920;

  /**
   * Precomputed mean values for the BGR (Blue, Green, Red) color channels used in image
   * normalization. These values are scaled versions of standard normalization means for BGR
   * channels, multiplied by 255 to match the range of pixel intensity values.
   *
   * <ul>
   *   <li>Blue channel mean: 0.406 * 255
   *   <li>Green channel mean: 0.456 * 255
   *   <li>Red channel mean: 0.485 * 255
   * </ul>
   *
   * This constant is primarily used for preprocessing image data when converting from raw pixel
   * intensities to normalized values as part of the model input pipeline.
   */
  private static final float[] MEAN_BGR = {0.406f * 255f, 0.456f * 255f, 0.485f * 255f};

  /**
   * The standard deviations for BGR color channels used for image normalization. Each channel's
   * value is scaled by 255 to match the range of typical image pixel intensities.
   *
   * <ul>
   *   <li>Blue channel: 0.225 * 255
   *   <li>Green channel: 0.224 * 255
   *   <li>Red channel: 0.229 * 255
   * </ul>
   *
   * This constant is primarily utilized for preprocessing images before passing them to a machine
   * learning model to ensure consistent input distribution.
   */
  private static final float[] STD_BGR = {0.225f * 255f, 0.224f * 255f, 0.229f * 255f};

  private static volatile PaddleDetRunner instance;
  private static final Object LOCK = new Object();
  private static final Executor DEFAULT_EXECUTOR = Executors.newSingleThreadExecutor();

  private final NcnnNet net;
  private final DbPostProcessor postProcessor;
  private final Object sessionLock = new Object();
  private boolean closed;

  /** For tests that only exercise the code around the model: no network is loaded. */
  @VisibleForTesting
  protected PaddleDetRunner() {
    this.net = null;
    this.postProcessor = null;
  }

  /**
   * Worker threads of one detection. Two, not half the cores: on a Pixel 7a (2 + 2 + 4 cores) four
   * threads waited for the small cores and a page took longer.
   */
  private static final int THREADS = 2;

  PaddleDetRunner(Context context) throws Exception {
    long t0 = System.nanoTime();
    this.postProcessor = new DbPostProcessor();
    PaddleAssets.ensureDetAvailable(context);
    String base = PaddleAssets.detModelAssetBase();
    this.net = NcnnNet.fromAssets(context.getAssets(), base, THREADS);
    Log.i(
        TAG,
        "Det model loaded ("
            + ((System.nanoTime() - t0) / 1_000_000L)
            + " ms) from assets "
            + base);
  }

  static PaddleDetRunner getInstance(Context ctx) throws Exception {
    if (instance == null) {
      synchronized (LOCK) {
        if (instance == null) {
          instance = new PaddleDetRunner(ctx.getApplicationContext());
        }
      }
    }
    return instance;
  }

  static CompletableFuture<PaddleDetRunner> getInstanceAsync(Context ctx) {
    return getInstanceAsync(ctx, DEFAULT_EXECUTOR);
  }

  static CompletableFuture<PaddleDetRunner> getInstanceAsync(Context ctx, Executor executor) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return getInstance(ctx);
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        },
        executor);
  }

  static boolean isInstanceLoaded() {
    return instance != null;
  }

  static void releaseInstance() {
    synchronized (LOCK) {
      if (instance != null) {
        try {
          instance.close();
        } catch (Exception e) {
          Log.w(TAG, "Error closing instance: " + e.getMessage());
        }
        instance = null;
      }
    }
  }

  /**
   * Detects rotated quadrilateral regions, such as text areas, within a bitmap image. This method
   * applies preprocessing steps to the input image and runs the NCNN network to perform inference.
   * The detected regions are returned as a list of quadrilaterals.
   *
   * @param bitmap The input image to process. It must be non-null and not recycled.
   * @return A list of {@code Quad} objects representing the detected regions in the image. Each
   *     {@code Quad} contains the coordinates of the quadrilateral vertices and an optional score.
   */
  List<Quad> detect(Bitmap bitmap) {
    return detect(bitmap, DEFAULT_MAX_SIDE);
  }

  /**
   * Detects text regions at the default resolution, or at {@link #HIGH_QUALITY_MAX_SIDE} when
   * {@code highQuality} is set.
   */
  List<Quad> detect(Bitmap bitmap, boolean highQuality) {
    return detect(bitmap, highQuality ? HIGH_QUALITY_MAX_SIDE : DEFAULT_MAX_SIDE);
  }

  /**
   * Detects rotated quadrilateral regions, such as text areas, within a bitmap image. This method
   * preprocesses the input image (e.g., resizing and normalization), runs the NCNN model for
   * inference, and performs post-processing to determine and adjust the detected quadrilateral
   * regions.
   *
   * @param bitmap The input image as a {@code Bitmap}. It must be non-null and not recycled.
   * @param maxSide The maximum allowable dimension (in pixels) for the longer side of the input
   *     image. This value is used to scale the image while preserving aspect ratio.
   * @return A list of {@code Quad} objects representing the detected quadrilateral regions. Each
   *     {@code Quad} contains vertex coordinates (in clockwise order from top-left) and an optional
   *     confidence score.
   * @throws IllegalArgumentException If the provided {@code bitmap} is null or has been recycled.
   */
  List<Quad> detect(Bitmap bitmap, int maxSide) {
    if (bitmap == null || bitmap.isRecycled()) {
      throw new IllegalArgumentException("bitmap must be non-null and not recycled");
    }
    return detectOnce(bitmap, maxSide);
  }

  private List<Quad> detectOnce(Bitmap bitmap, int maxSide) {
    long tStart = System.nanoTime();
    final int srcW = bitmap.getWidth();
    final int srcH = bitmap.getHeight();
    Letterbox lb = Letterbox.compute(srcW, srcH, maxSide);
    Log.i(TAG, "detect input=" + srcW + "x" + srcH + " -> letterbox=" + lb.dstW + "x" + lb.dstH);

    // 1) Letterbox in Bitmap zeichnen (RGB-Padding 0).
    Bitmap target = Bitmap.createBitmap(lb.dstW, lb.dstH, Bitmap.Config.ARGB_8888);
    try {
      Canvas canvas = new Canvas(target);
      canvas.drawColor(Color.BLACK);
      android.graphics.Matrix m = new android.graphics.Matrix();
      m.postScale((float) lb.scale, (float) lb.scale);
      m.postTranslate((float) lb.padX, (float) lb.padY);
      Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
      canvas.drawBitmap(bitmap, m, paint);

      // 2) Eingabe-Tensor erzeugen: NCHW float32, BGR-Mean/Std-Norm.
      float[] input = bitmapToNchwBgrNormalized(target, lb.dstW, lb.dstH);

      synchronized (sessionLock) {
        if (closed) {
          throw new IllegalStateException("PaddleDetRunner is closed");
        }

        {
          // 3) Probability map [1,H,W].
          float[][] prob = toProbMap(net.run(input, 3, lb.dstH, lb.dstW));

          long tPost = System.nanoTime();
          List<Quad> quadsLb = postProcessor.process(prob);
          long tPostEnd = System.nanoTime();
          // logPostprocessSensitivity(prob, quadsLb.size());

          // 4) De-letterbox.
          List<Quad> quads = new java.util.ArrayList<>(quadsLb.size());
          for (Quad q : quadsLb) {
            double[] xs = new double[4];
            double[] ys = new double[4];
            for (int i = 0; i < 4; i++) {
              double[] p = lb.unapplyPoint(q.x[i], q.y[i]);
              // Auf Bildgrenzen klemmen.
              xs[i] = Math.max(0.0, Math.min(srcW - 1.0, p[0]));
              ys[i] = Math.max(0.0, Math.min(srcH - 1.0, p[1]));
            }
            // The kernel bounds travel along in image coordinates (the builder
            // re-derives the unclip for vertical text from them); the pixel count
            // stays in map units and is informational only.
            double[] kernel = null;
            if (q.kernel != null) {
              double[] tl = lb.unapplyPoint(q.kernel[0], q.kernel[1]);
              double[] br = lb.unapplyPoint(q.kernel[2], q.kernel[3]);
              kernel = new double[] {tl[0], tl[1], br[0], br[1], q.kernel[4]};
            }
            quads.add(new Quad(xs, ys, q.score, kernel));
          }

          // 5) Sortierung: zuerst y, dann x (TL→BR-Lesefluss).
          quads.sort(Comparator.<Quad>comparingDouble(Quad::minY).thenComparingDouble(Quad::minX));

          long tEnd = System.nanoTime();
          Log.i(
              TAG,
              "detect quads="
                  + quads.size()
                  + " postproc="
                  + ((tPostEnd - tPost) / 1_000_000L)
                  + "ms total="
                  + ((tEnd - tStart) / 1_000_000L)
                  + "ms");
          return quads;
        }
      }
    } finally {
      if (!target.isRecycled()) {
        target.recycle();
      }
    }
  }

  private static float[] bitmapToNchwBgrNormalized(Bitmap bm, int w, int h) {
    int[] pixels = new int[w * h];
    bm.getPixels(pixels, 0, w, 0, 0, w, h);
    float[] out = new float[3 * w * h];
    // Channel-Layout: NCHW mit BGR-Reihenfolge (Channel 0 = B, 1 = G, 2 = R).
    final int planeSize = w * h;
    for (int i = 0; i < planeSize; i++) {
      int p = pixels[i];
      int r = (p >> 16) & 0xFF;
      int g = (p >> 8) & 0xFF;
      int b = p & 0xFF;
      out[i] = (b - MEAN_BGR[0]) / STD_BGR[0];
      out[planeSize + i] = (g - MEAN_BGR[1]) / STD_BGR[1];
      out[2 * planeSize + i] = (r - MEAN_BGR[2]) / STD_BGR[2];
    }
    return out;
  }

  /** Splits the flat single-channel output into rows; the post-processor tolerates any size. */
  private static float[][] toProbMap(NcnnNet.Tensor t) {
    if (t.channels() != 1 || t.height() < 1 || t.width() < 1) {
      throw new IllegalStateException(
          "Unexpected det output shape ["
              + t.channels()
              + "]["
              + t.height()
              + "]["
              + t.width()
              + "]");
    }
    float[][] prob = new float[t.height()][t.width()];
    for (int y = 0; y < t.height(); y++) {
      System.arraycopy(t.data(), y * t.width(), prob[y], 0, t.width());
    }
    return prob;
  }

  @Override
  public void close() {
    synchronized (sessionLock) {
      if (!closed && net != null) net.close();
      closed = true;
    }
  }
}
