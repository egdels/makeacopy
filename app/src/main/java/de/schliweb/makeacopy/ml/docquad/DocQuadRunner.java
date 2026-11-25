/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ml.docquad;

import android.content.Context;
import android.util.Log;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.ml.ncnn.NcnnNet;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Runs the document corner model (DocQuadNet-256) with NCNN.
 *
 * <p>The model takes one {@code [3, 256, 256]} tensor and produces corner heatmaps {@code [4, 64,
 * 64]} and mask logits {@code [1, 64, 64]}. It is loaded straight from the app's assets.
 *
 * <ul>
 *   <li>{@link #getInstance(Context, String)} shares one loaded model across the app
 *   <li>{@link #getInstanceAsync(Context, String, Executor)} loads it without blocking the caller
 * </ul>
 */
public final class DocQuadRunner implements AutoCloseable {

  private static final String TAG = "DocQuadRunner";

  public static final int IN_H = 256;
  public static final int IN_W = 256;
  public static final int OUT_H = 64;
  public static final int OUT_W = 64;

  /**
   * Worker threads of one run. Measured on a Pixel 7a: two threads are the fastest setting, one and
   * four are both about twice as slow.
   */
  private static final int THREADS = 2;

  /**
   * Outputs in the order the model declares them: the converter names them {@code out0}, {@code
   * out1}.
   */
  private static final String[] OUTPUT_NAMES = {"out0", "out1"};

  private static final int OUT_CORNER_HEATMAPS = 0;
  private static final int OUT_MASK_LOGITS = 1;

  /** File name ending of the model copies that versions up to 4.9 kept in the cache directory. */
  private static final String LEGACY_CACHE_SUFFIX = ".ort";

  private static volatile DocQuadRunner instance;
  private static final Object LOCK = new Object();
  private static final Executor DEFAULT_EXECUTOR = Executors.newSingleThreadExecutor();

  private final NcnnNet net;

  /**
   * Loads the model from the assets of {@code context}.
   *
   * @param modelAssetPath asset path of the model without the {@code .ncnn.param} / {@code
   *     .ncnn.bin} suffix
   */
  DocQuadRunner(Context context, String modelAssetPath) throws Exception {
    int threads = Math.max(1, Math.min(THREADS, Runtime.getRuntime().availableProcessors()));
    this.net = NcnnNet.fromAssets(context.getAssets(), modelAssetPath, threads);
    Log.d(TAG, "Model loaded from assets: " + modelAssetPath);
    deleteLegacyCacheCopies(context.getCacheDir(), new File(modelAssetPath).getName());
  }

  /**
   * Returns a singleton instance of {@code DocQuadRunner}, creating it if necessary. This method is
   * thread-safe and ensures the model is loaded only once.
   *
   * <p>Use this method when you need to reuse the same model instance across multiple inference
   * calls to avoid the overhead of repeated model loading.
   *
   * @param context the Android context for accessing assets
   * @param modelAssetPath asset path of the model without the {@code .ncnn.param} / {@code
   *     .ncnn.bin} suffix
   * @return the singleton instance
   * @throws Exception if model loading fails
   */
  public static DocQuadRunner getInstance(Context context, String modelAssetPath) throws Exception {
    if (instance == null) {
      synchronized (LOCK) {
        if (instance == null) {
          instance = new DocQuadRunner(context, modelAssetPath);
        }
      }
    }
    return instance;
  }

  /**
   * Asynchronously returns a singleton instance of {@code DocQuadRunner}. Uses the default
   * single-thread executor for background loading.
   *
   * <p>This method is useful for non-blocking initialization, e.g., during app startup or when
   * entering a camera fragment.
   *
   * @param context the Android context for accessing assets
   * @param modelAssetPath asset path of the model without the {@code .ncnn.param} / {@code
   *     .ncnn.bin} suffix
   * @return a CompletableFuture that completes with the singleton instance
   */
  public static CompletableFuture<DocQuadRunner> getInstanceAsync(
      Context context, String modelAssetPath) {
    return getInstanceAsync(context, modelAssetPath, DEFAULT_EXECUTOR);
  }

  /**
   * Asynchronously returns a singleton instance of {@code DocQuadRunner} using the provided
   * executor.
   *
   * @param context the Android context for accessing assets
   * @param modelAssetPath asset path of the model without the {@code .ncnn.param} / {@code
   *     .ncnn.bin} suffix
   * @param executor the executor to use for background loading
   * @return a CompletableFuture that completes with the singleton instance
   */
  public static CompletableFuture<DocQuadRunner> getInstanceAsync(
      Context context, String modelAssetPath, Executor executor) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return getInstance(context, modelAssetPath);
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        },
        executor);
  }

  /**
   * Checks if the singleton instance is already loaded.
   *
   * @return true if the instance is loaded and ready for inference
   */
  public static boolean isInstanceLoaded() {
    return instance != null;
  }

  /**
   * Releases the singleton instance and frees the loaded network. After calling this method, the
   * next call to {@link #getInstance(Context, String)} will create a new instance.
   *
   * <p>This method is useful for releasing resources when the model is no longer needed, e.g., when
   * the app goes to background or memory is low.
   */
  public static void releaseInstance() {
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

  public Outputs run(float[] inputNchw) throws Exception {
    if (inputNchw == null || inputNchw.length != 3 * IN_H * IN_W) {
      throw new IllegalArgumentException("inputNchw must have length " + (3 * IN_H * IN_W));
    }
    NcnnNet.Tensor[] out = net.run(inputNchw, 3, IN_H, IN_W, OUTPUT_NAMES);
    float[][][][] cornerHeatmaps = toNchw(out[OUT_CORNER_HEATMAPS], "corner_heatmaps", 4);
    float[][][][] maskLogits = toNchw(out[OUT_MASK_LOGITS], "mask_logits", 1);
    if (BuildConfig.DEBUG) {
      assertShapeMask(maskLogits);
      assertShapeCorners(cornerHeatmaps);
    }
    return new Outputs(maskLogits, cornerHeatmaps);
  }

  @Override
  public void close() {
    net.close();
  }

  /** Reshapes a flat output into {@code [1][channels][64][64]}, the layout the callers expect. */
  private static float[][][][] toNchw(NcnnNet.Tensor t, String name, int channels) {
    if (t.channels() != channels || t.height() != OUT_H || t.width() != OUT_W) {
      throw new IllegalStateException(
          name
              + " has unexpected shape ["
              + t.channels()
              + "]["
              + t.height()
              + "]["
              + t.width()
              + "] (expected ["
              + channels
              + "][64][64])");
    }
    float[][][][] out = new float[1][channels][OUT_H][OUT_W];
    float[] data = t.data();
    int pos = 0;
    for (int c = 0; c < channels; c++) {
      for (int y = 0; y < OUT_H; y++) {
        System.arraycopy(data, pos, out[0][c][y], 0, OUT_W);
        pos += OUT_W;
      }
    }
    return out;
  }

  /**
   * Versions up to 4.9 copied the model into the cache directory as {@code
   * <versionCode>_<name>.ort}. The model is now read from the assets, so those copies are dead
   * weight; remove them.
   */
  private static void deleteLegacyCacheCopies(File cacheDir, String baseName) {
    if (cacheDir == null) return;
    String ending = "_" + baseName + LEGACY_CACHE_SUFFIX;
    File[] stale = cacheDir.listFiles((dir, name) -> name.endsWith(ending));
    if (stale == null) return;
    for (File f : stale) {
      if (f.delete()) {
        Log.i(TAG, "Deleted cached model of an older version: " + f.getName());
      }
    }
  }

  private static void assertShapeMask(float[][][][] maskLogits) {
    if (maskLogits == null
        || maskLogits.length != 1
        || maskLogits[0] == null
        || maskLogits[0].length != 1
        || maskLogits[0][0] == null
        || maskLogits[0][0].length != OUT_H
        || maskLogits[0][0][0] == null
        || maskLogits[0][0][0].length != OUT_W) {
      throw new IllegalStateException("mask_logits has unexpected shape (expected [1][1][64][64])");
    }
    for (int y = 0; y < OUT_H; y++) {
      if (maskLogits[0][0][y] == null || maskLogits[0][0][y].length != OUT_W) {
        throw new IllegalStateException(
            "mask_logits has unexpected shape (expected [1][1][64][64])");
      }
    }
  }

  private static void assertShapeCorners(float[][][][] cornerHeatmaps) {
    if (cornerHeatmaps == null
        || cornerHeatmaps.length != 1
        || cornerHeatmaps[0] == null
        || cornerHeatmaps[0].length != 4) {
      throw new IllegalStateException(
          "corner_heatmaps has unexpected shape (expected [1][4][64][64])");
    }
    for (int c = 0; c < 4; c++) {
      if (cornerHeatmaps[0][c] == null
          || cornerHeatmaps[0][c].length != OUT_H
          || cornerHeatmaps[0][c][0] == null
          || cornerHeatmaps[0][c][0].length != OUT_W) {
        throw new IllegalStateException(
            "corner_heatmaps has unexpected shape (expected [1][4][64][64])");
      }
      for (int y = 0; y < OUT_H; y++) {
        if (cornerHeatmaps[0][c][y] == null || cornerHeatmaps[0][c][y].length != OUT_W) {
          throw new IllegalStateException(
              "corner_heatmaps has unexpected shape (expected [1][4][64][64])");
        }
      }
    }
  }

  /**
   * Raw model outputs containing mask logits and corner heatmaps.
   *
   * @param maskLogits [1][1][64][64]
   * @param cornerHeatmaps [1][4][64][64]
   */
  public record Outputs(float[][][][] maskLogits, float[][][][] cornerHeatmaps) {}
}
