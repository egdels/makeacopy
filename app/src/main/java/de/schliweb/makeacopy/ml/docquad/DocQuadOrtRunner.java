/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ml.docquad;

import ai.onnxruntime.*;
import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import de.schliweb.makeacopy.BuildConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The {@code DocQuadOrtRunner} class provides functionality for running inference on an ONNX model
 * that is expected to process document quad detection tasks. It uses ONNX Runtime for performing
 * the inference and handles input validation, output extraction, and shapes validation for specific
 * outputs.
 *
 * <p>This class is designed to work with ONNX models that require input tensors with dimensions [1,
 * 3, 256, 256] and produce specific outputs with known shapes. It is optimized for use with Android
 * applications by supporting model loading from assets or byte arrays.
 *
 * <p>For performance optimization, this class provides:
 *
 * <ul>
 *   <li>Singleton access via {@link #getInstance(Context, String)} to avoid repeated model loading
 *   <li>Asynchronous loading via {@link #getInstanceAsync(Context, String, Executor)} for
 *       non-blocking initialization
 * </ul>
 */
public final class DocQuadOrtRunner implements AutoCloseable {

  private static final String TAG = "DocQuadOrtRunner";

  public static final int IN_H = 256;
  public static final int IN_W = 256;
  public static final int OUT_H = 64;
  public static final int OUT_W = 64;

  private static volatile DocQuadOrtRunner instance;
  private static final Object LOCK = new Object();
  private static final Executor DEFAULT_EXECUTOR = Executors.newSingleThreadExecutor();

  private final OrtEnvironment env;
  private final OrtSession session;
  private final Object sessionLock = new Object();
  private boolean closed;

  DocQuadOrtRunner(Context context, String modelAssetPath) throws Exception {
    this(context, modelAssetPath, context.getCacheDir());
  }

  DocQuadOrtRunner(Context context, String modelAssetPath, File cacheDir) throws Exception {
    this.env = OrtEnvironment.getEnvironment();

    // Optimized model loading: copy to cache and use file path for mmap support.
    File modelFile = copyAssetToCache(context, modelAssetPath, cacheDir);

    this.session = createSession(env, modelFile.getAbsolutePath());
    Log.d(TAG, "Model loaded from " + modelFile.getAbsolutePath());
  }

  /**
   * Intra-op threads for the CPU execution provider. Two threads stay on the big cores of
   * big.LITTLE SoCs; more threads spill onto little cores and get slower again (Pixel 7a: 1 thread
   * ~102 ms, 2 threads ~54 ms, 4 threads ~108 ms per inference).
   */
  private static final int INTRA_OP_THREADS = 2;

  /**
   * Creates a CPU-only ORT session.
   *
   * <p>NNAPI and XNNPACK are deliberately not used: measured with {@code
   * DocQuadLatencyBenchmarkTest} on a Pixel 7a, NNAPI made inference ~12x slower (~620 ms vs. ~54
   * ms), XNNPACK brought no gain over the default CPU provider for this model, and NNAPI is known
   * to abort natively during graph partitioning on some devices (API 29). NNAPI is also deprecated
   * as of Android 15.
   */
  private static OrtSession createSession(OrtEnvironment env, String modelPath) throws Exception {
    try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
      opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
      opts.setIntraOpNumThreads(
          Math.max(1, Math.min(INTRA_OP_THREADS, Runtime.getRuntime().availableProcessors())));
      return env.createSession(modelPath, opts);
    }
  }

  /**
   * Returns a singleton instance of {@code DocQuadOrtRunner}, creating it if necessary. This method
   * is thread-safe and ensures the model is loaded only once.
   *
   * <p>Use this method when you need to reuse the same model instance across multiple inference
   * calls to avoid the overhead of repeated model loading.
   *
   * @param context the Android context for accessing assets
   * @param modelAssetPath the path to the ONNX model file in assets
   * @return the singleton instance
   * @throws Exception if model loading fails
   */
  public static DocQuadOrtRunner getInstance(Context context, String modelAssetPath)
      throws Exception {
    if (instance == null) {
      synchronized (LOCK) {
        if (instance == null) {
          instance = new DocQuadOrtRunner(context, modelAssetPath);
        }
      }
    }
    return instance;
  }

  /**
   * Asynchronously returns a singleton instance of {@code DocQuadOrtRunner}. Uses the default
   * single-thread executor for background loading.
   *
   * <p>This method is useful for non-blocking initialization, e.g., during app startup or when
   * entering a camera fragment.
   *
   * @param context the Android context for accessing assets
   * @param modelAssetPath the path to the ONNX model file in assets
   * @return a CompletableFuture that completes with the singleton instance
   */
  public static CompletableFuture<DocQuadOrtRunner> getInstanceAsync(
      Context context, String modelAssetPath) {
    return getInstanceAsync(context, modelAssetPath, DEFAULT_EXECUTOR);
  }

  /**
   * Asynchronously returns a singleton instance of {@code DocQuadOrtRunner} using the provided
   * executor.
   *
   * @param context the Android context for accessing assets
   * @param modelAssetPath the path to the ONNX model file in assets
   * @param executor the executor to use for background loading
   * @return a CompletableFuture that completes with the singleton instance
   */
  public static CompletableFuture<DocQuadOrtRunner> getInstanceAsync(
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
   * Releases the singleton instance and closes the underlying ONNX session. After calling this
   * method, the next call to {@link #getInstance(Context, String)} will create a new instance.
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

    synchronized (sessionLock) {
      if (closed) {
        throw new IllegalStateException("DocQuadOrtRunner is closed");
      }

      long[] inputShape = new long[] {1, 3, IN_H, IN_W};
      try (OnnxTensor input =
              OnnxTensor.createTensor(env, FloatBuffer.wrap(inputNchw), inputShape);
          OrtSession.Result results = session.run(Collections.singletonMap("input", input))) {

        // Always read outputs by name (robust against output order).
        float[][][][] maskLogits = getRequiredFloat4d(results, "mask_logits");
        float[][][][] cornerHeatmaps = getRequiredFloat4d(results, "corner_heatmaps");

        // Optional: fast shape assertion in debug/test builds.
        if (BuildConfig.DEBUG) {
          assertShapeMask(maskLogits);
          assertShapeCorners(cornerHeatmaps);
        }
        return new Outputs(maskLogits, cornerHeatmaps);
      }
    }
  }

  @Override
  public void close() throws Exception {
    synchronized (sessionLock) {
      if (!closed && session != null) {
        session.close();
      }
      closed = true;
    }
    // OrtEnvironment is global/shared; not closed.
  }

  /**
   * Copies an asset from the app's assets folder to the application's cache directory. The cached
   * file is versioned by the app's version code (obtained via PackageManager to preserve
   * reproducible-build compatibility). After a successful copy, stale cached copies from previous
   * versions are deleted.
   *
   * <p>Loading from a file on disk allows the ONNX Runtime to use Memory Mapping (mmap), which is
   * significantly more performant and memory-efficient than loading from a byte array or stream.
   *
   * @param context the application context used to access the assets and cache directory
   * @param assetPath the path of the asset file to be copied, relative to the assets directory
   * @return a File object pointing to the copied asset in the cache directory
   * @throws IOException if an I/O error occurs during file copy
   */
  private static File copyAssetToCache(Context context, String assetPath, File cacheDir)
      throws IOException {
    AssetManager am = context.getAssets();
    String baseName = new File(assetPath).getName();
    long versionCode;
    try {
      android.content.pm.PackageInfo pi =
          context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      versionCode = pi.getLongVersionCode();
    } catch (Exception e) {
      versionCode = -1L;
    }
    String versionedName = versionCode + "_" + baseName;
    File outFile = new File(cacheDir, versionedName);
    if (!outFile.exists()) {
      Log.i(TAG, "Copying asset " + assetPath + " to cache as " + versionedName + "...");
      try (InputStream is = am.open(assetPath);
          FileOutputStream fos = new FileOutputStream(outFile)) {
        byte[] buffer = new byte[256 * 1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
          fos.write(buffer, 0, len);
        }
      }
      // Clean up stale cached copies from previous versions.
      deleteStaleModelFiles(cacheDir, baseName, versionedName);
    }
    return outFile;
  }

  /**
   * Deletes cached model files from previous app versions.
   *
   * @param cacheDir the cache directory
   * @param baseName the base asset file name (e.g. "model.onnx")
   * @param currentName the current versioned file name to keep
   */
  private static void deleteStaleModelFiles(File cacheDir, String baseName, String currentName) {
    File[] staleFiles =
        cacheDir.listFiles(
            (dir, name) -> name.endsWith("_" + baseName) && !name.equals(currentName));
    if (staleFiles != null) {
      for (File stale : staleFiles) {
        if (stale.delete()) {
          Log.i(TAG, "Deleted stale cached model: " + stale.getName());
        } else {
          Log.w(TAG, "Failed to delete stale cached model: " + stale.getName());
        }
      }
    }
  }

  private static float[][][][] getRequiredFloat4d(OrtSession.Result results, String outputName)
      throws OrtException {
    Optional<OnnxValue> ov = results.get(outputName);
    if (ov.isEmpty()) {
      throw new IllegalStateException(buildMissingOutputMessage(outputName, results));
    }

    Object v = ov.get().getValue();
    if (!(v instanceof float[][][][])) {
      throw new IllegalStateException(
          "Output '"
              + outputName
              + "' has unexpected Java type: "
              + (v == null ? "null" : v.getClass().getName()));
    }
    return (float[][][][]) v;
  }

  private static String buildMissingOutputMessage(String missingName, OrtSession.Result results) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, OnnxValue> e : results) {
      if (sb.length() > 0) sb.append(", ");
      sb.append(e.getKey());
    }
    return "ONNX output missing: '" + missingName + "'. Available outputs: [" + sb + "]";
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
