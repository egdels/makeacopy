/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr.paddle;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.Log;
import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The PaddleRecOrtRunner class provides a recognition framework using ONNX Runtime (ORT).
 * It is designed to handle paddle OCR models with support for various configurations
 * and optimizations. This class handles model session management, caching, and inference
 * for bitmap inputs to perform text recognition.
 *
 * Key Features:
 * - Lazy initialization and caching of model sessions.
 * - Reference counting for efficient session and resource handling.
 * - Recognition on bitmap crops with output of text and confidence scores.
 */
class PaddleRecOrtRunner implements AutoCloseable {

    private static final String TAG = "PaddleRecOrt";

    /**
     * A constant representing the key used to identify the input tensor name
     * for PaddleRec ORT (ONNX Runtime) session processing.
     *
     * This is a static reference to the expected input name for the recognition model.
     */
    private static final String INPUT_NAME = "x";

    /**
     * The default height in pixels for the input tensor used in text recognition tasks.
     * <p>
     * This value is used to resize and normalize input images to ensure consistency
     * with the model's expected dimensions for optimized processing.
     */
    static final int REC_INPUT_HEIGHT = 48;

    /**
     * Represents the stride value in pixels for the width dimension used during
     * text recognition processing. This constant defines the step size for
     * sliding or cropping operations when evaluating input image regions in
     * paddle-based recognition tasks.
     *
     * <p>It is utilized internally to ensure consistent spacing between
     * processed image segments, potentially contributing to optimized model
     * performance and accurate detection.
     */
    static final int REC_WIDTH_STRIDE = 8;

    /**
     * Specifies the minimum allowed width for a recognition input during OCR processing.
     * This constant is primarily used to ensure that the input width meets the
     * minimum requirements to perform accurate text recognition.
     */
    static final int REC_MIN_WIDTH = 16;

    /**
     * Per-crop recognition logs are useful while tuning the recognizer, but they are emitted once
     * for every detected quad and can dominate real-device measurements on dense pages.
     */
    @VisibleForTesting static final boolean ENABLE_PER_CROP_RECOGNITION_LOGS = true;

    /**
     * Defines the maximum number of simultaneous live recognition sessions allowed.
     *
     * This constant imposes a hard cap on the number of active recognition sessions
     * that can run concurrently in the system. It is used to manage resource allocation
     * and ensure optimal performance by preventing excessive resource consumption.
     *
     * A value of 2 indicates that no more than two recognition sessions are allowed
     * to run simultaneously. Attempts to start additional sessions beyond this limit
     * may trigger resource management mechanisms, such as eviction or rejection.
     */
    static final int MAX_LIVE_REC_SESSIONS = 2;

    private static final Object CACHE_LOCK = new Object();
    /**
     * A static cache to store active instances of {@link PaddleRecOrtRunner}.
     *
     * <ul>
     * <li>The cache maintains a mapping between model keys (represented as {@code String})
     * and {@link CacheEntry} objects, which encapsulate a {@link PaddleRecOrtRunner} instance
     * and its reference count.</li>
     * <li>Entries in the cache are managed using a {@link LinkedHashMap}, providing predictable
     * iteration order and supporting an LRU eviction policy if necessary.</li>
     * <li>Used primarily to manage the lifecycle and reuse of recognition sessions, ensuring
     * efficient resource utilization and preventing duplicate session creation for the same model key.</li>
     * <li>This field is thread-sensitive and should only be accessed while holding the appropriate
     * synchronization lock (e.g., {@code CACHE_LOCK}) to ensure thread-safe operations.</li>
     * </ul>
     */
    private static final LinkedHashMap<String, CacheEntry> CACHE = new LinkedHashMap<>();
    private static final Executor DEFAULT_EXECUTOR = Executors.newSingleThreadExecutor();

    private final OrtEnvironment env;
    private final OrtSession session;
    private final File dictFile;
    private final String modelKey;
    private volatile String[] vocab;
    private final Object sessionLock = new Object();
    private boolean closed;

    /**
     * Represents the output of a recognition process, including the recognized text,
     * confidence score, token details, and dimensions related to the processing of the input image.
     */
    record RecOutput(
            String text,
            float confidence,
            List<CtcDecoder.RecognizedToken> tokens,
            int frameCount,
            int scaledCropWidth,
            int paddedCropWidth,
            int srcCropWidth,
            int[] argmaxIdsHead) {
        /**
         * Constructs a simplified {@code RecOutput} instance with default values for optional parameters.
         *
         * @param text The recognized text output from the recognition process.
         * @param confidence The confidence score of the recognition process, indicating the reliability of the output.
         */
        RecOutput(String text, float confidence) {
            this(text, confidence, Collections.emptyList(), 0, 0, 0, 0, null);
        }

        /**
         * Constructs a {@code RecOutput} instance with details related to the result of a recognition process.
         *
         * @param text The recognized text output from the recognition process.
         * @param confidence The confidence score of the recognition process, indicating the reliability of the recognized text.
         * @param tokens A list of {@code RecognizedToken} objects representing individual tokens identified during the recognition process.
         * @param frameCount The number of frames involved in the recognition process.
         * @param scaledCropWidth The width of the cropped image after scaling during preprocessing.
         * @param paddedCropWidth The width of the cropped image after padding is applied during preprocessing.
         * @param srcCropWidth The original width of the cropped image before scaling or padding.
         */
        RecOutput(
                String text,
                float confidence,
                List<CtcDecoder.RecognizedToken> tokens,
                int frameCount,
                int scaledCropWidth,
                int paddedCropWidth,
                int srcCropWidth) {
            this(
                    text,
                    confidence,
                    tokens,
                    frameCount,
                    scaledCropWidth,
                    paddedCropWidth,
                    srcCropWidth,
                    null);
        }
    }

    /**
     * Initializes a PaddleRecOrtRunner instance with the specified application context and model key.
     * This constructor verifies that the required assets for the given model key are extracted and
     * valid. It also creates an ONNX Runtime session to load the recognition model. If the required
     * assets or model files do not exist, an exception is thrown.
     *
     * @param context   The Android application context used for accessing assets.
     * @param modelKey  The unique key identifying the recognition model to be used. Must be non-null.
     * @throws IllegalArgumentException if the modelKey is null.
     * @throws IllegalStateException    if the recognition model or dictionary file for the modelKey
     *                                   cannot be found.
     * @throws Exception                if there is an error initializing the ONNX Runtime session
     *                                   or loading the model file.
     */
    PaddleRecOrtRunner(Context context, String modelKey) throws Exception {
        long t0 = System.nanoTime();
        if (modelKey == null) {
            throw new IllegalArgumentException("modelKey must be non-null");
        }
        this.modelKey = modelKey;
        this.env = OrtEnvironment.getEnvironment();
        // Asset-Materialisierung (idempotent + dict-SHA-Check).
        PaddleAssets.ensureRecExtracted(context, modelKey);
        File modelFile = PaddleAssets.getRecModelFile(context, modelKey);
        if (!modelFile.exists()) {
            throw new IllegalStateException(
                    "rec model not found at " + modelFile + " for modelKey=" + modelKey);
        }
        this.dictFile = PaddleAssets.getRecDictFile(context, modelKey);
        if (!this.dictFile.exists()) {
            throw new IllegalStateException(
                    "rec dict not found at " + this.dictFile + " for modelKey=" + modelKey);
        }
        this.session = createSessionWithFallback(env, modelFile.getAbsolutePath());
        Log.i(
                TAG,
                "Rec session loaded modelKey="
                        + modelKey
                        + " ("
                        + ((System.nanoTime() - t0) / 1_000_000L)
                        + " ms) from "
                        + modelFile.getAbsolutePath());
    }

    /**
     * Protected no-argument constructor for testing purposes.
     *
     * This constructor initializes a minimal instance of the PaddleRecOrtRunner
     * with default or null values for its fields.
     * It is primarily intended for usage in unit tests where a fully constructed
     * instance is not required.
     *
     * Fields initialized:
     * - {@code env}: Set to {@code null}.
     * - {@code session}: Set to {@code null}.
     * - {@code dictFile}: Set to {@code null}.
     * - {@code modelKey}: Set to {@code "test"}.
     * - {@code vocab}: Set to a single-element array containing {@code "<blank>"}.
     *
     * Use of this constructor outside testing is discouraged. For production, use
     * other constructors that initialize the runner with appropriate context and model configuration.
     *
     * Annotation:
     * - {@link VisibleForTesting}: Marks this constructor as accessible solely for testing purposes.
     */
    @VisibleForTesting
    protected PaddleRecOrtRunner() {
        this.env = null;
        this.session = null;
        this.dictFile = null;
        this.modelKey = "test";
        this.vocab = new String[] {"<blank>"};
    }

    /**
     * Creates an ONNX Runtime session for the specified model path with optimized options
     * and falls back to default options if the optimized session creation fails.
     *
     * @param env       The ONNX Runtime environment used for session creation. Must be non-null.
     * @param modelPath The file path to the model file to be loaded into the session. Must be non-null and valid.
     * @return An instance of {@link OrtSession} created with either optimized or default session options.
     * @throws Exception If both the optimized and default session creation attempts fail.
     */
    private static OrtSession createSessionWithFallback(OrtEnvironment env, String modelPath)
            throws Exception {
        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            opts.setIntraOpNumThreads(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
            return env.createSession(modelPath, opts);
        } catch (Exception e) {
            Log.w(TAG, "CPU session creation failed, retrying with default options: " + e.getMessage());
            try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
                return env.createSession(modelPath, opts);
            }
        }
    }

    private static final class CacheEntry {
        final PaddleRecOrtRunner runner;
        int refCount;

        CacheEntry(PaddleRecOrtRunner runner) {
            this.runner = runner;
            this.refCount = 1;
        }
    }

    /**
     * Retrieves an instance of the {@code PaddleRecOrtRunner} associated with the specified model key.
     * If an instance for the given model key already exists in the cache, it is returned with its
     * reference count incremented. Otherwise, a new instance is created, cached, and returned.
     *
     * @param ctx      The Android application context used for accessing resources. Must be non-null.
     * @param modelKey The unique key identifying the recognition model to be used. Must be non-null.
     * @return An instance of {@code PaddleRecOrtRunner} associated with the given model key.
     * @throws IllegalArgumentException If {@code modelKey} is null.
     * @throws Exception                If there is an error initializing the runner or loading the model.
     */
    static PaddleRecOrtRunner getInstance(Context ctx, String modelKey) throws Exception {
        if (modelKey == null) {
            throw new IllegalArgumentException("modelKey must be non-null");
        }
        synchronized (CACHE_LOCK) {
            CacheEntry e = CACHE.get(modelKey);
            if (e != null) {
                e.refCount++;
                // LRU touch: re-insertion-order
                CACHE.remove(modelKey);
                CACHE.put(modelKey, e);
                return e.runner;
            }
            PaddleRecOrtRunner runner = new PaddleRecOrtRunner(ctx.getApplicationContext(), modelKey);
            CACHE.put(modelKey, new CacheEntry(runner));
            evictLruIfNeededLocked();
            return runner;
        }
    }

    static CompletableFuture<PaddleRecOrtRunner> getInstanceAsync(Context ctx, String modelKey) {
        return getInstanceAsync(ctx, modelKey, DEFAULT_EXECUTOR);
    }

    static CompletableFuture<PaddleRecOrtRunner> getInstanceAsync(Context ctx, String modelKey,
                                                                  Executor executor) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return getInstance(ctx, modelKey);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                },
                executor);
    }

    static boolean isInstanceLoaded(String modelKey) {
        synchronized (CACHE_LOCK) {
            return CACHE.containsKey(modelKey);
        }
    }

    /**
     * Releases the instance of {@code PaddleRecOrtRunner} associated with the specified model key.
     * If the instance exists in the cache, its reference count is decremented. When the reference
     * count reaches zero or below, the instance is removed from the cache, and its associated
     * resources, such as the ONNX Runtime session, are closed.
     *
     * @param modelKey The unique key identifying the recognition model to be released.
     *                 Must be non-null. If null, the method immediately returns without action.
     */
    static void releaseInstance(String modelKey) {
        if (modelKey == null) return;
        synchronized (CACHE_LOCK) {
            CacheEntry e = CACHE.get(modelKey);
            if (e == null) return;
            e.refCount--;
            if (e.refCount <= 0) {
                CACHE.remove(modelKey);
                try {
                    e.runner.close();
                } catch (Exception ex) {
                    Log.w(TAG, "Error closing rec session for " + modelKey + ": " + ex.getMessage());
                }
            }
        }
    }

    /**
     * Releases all cached instances of {@code PaddleRecOrtRunner} and their associated resources.
     *
     * This method iterates through all entries in the cache and performs the following actions:
     * 1. Attempts to close the {@code runner} associated with each cache entry. If an exception
     *    occurs during the closure of the runner, it is caught and logged, but the operation continues
     *    for the remaining entries.
     * 2. Clears the cache after all entries have been processed.
     *
     * The method is thread-safe, as it synchronizes access to the cache using {@code CACHE_LOCK}.
     *
     * Note:
     * - This method removes all cached instances, regardless of their reference counts.
     * - It is generally intended for scenarios where all cached resources need to be freed,
     *   such as application shutdown or global cleanup.
     */
    static void releaseAll() {
        synchronized (CACHE_LOCK) {
            for (Map.Entry<String, CacheEntry> en : CACHE.entrySet()) {
                try {
                    en.getValue().runner.close();
                } catch (Exception ex) {
                    Log.w(TAG, "Error closing rec session for " + en.getKey()
                            + ": " + ex.getMessage());
                }
            }
            CACHE.clear();
        }
    }

    /**
     * Retrieves the current count of active live recognition sessions.
     *
     * This method is thread-safe and synchronizes access to the internal cache of live sessions.
     *
     * @return The number of active live recognition sessions currently present in the cache.
     */
    static int liveSessionCount() {
        synchronized (CACHE_LOCK) {
            return CACHE.size();
        }
    }

    private static void evictLruIfNeededLocked() {
        // CACHE_LOCK MUST be held by caller.
        while (CACHE.size() > MAX_LIVE_REC_SESSIONS) {
            // ältester Eintrag (LinkedHashMap insertion order).
            Map.Entry<String, CacheEntry> oldest = CACHE.entrySet().iterator().next();
            CacheEntry e = oldest.getValue();
            // Nur evicten, wenn nicht aktuell aktiv referenziert (refCount == 1 = nur Cache-Halterung
            // nach Initial-Erzeugung; wir akzeptieren auch höhere Counter und schließen trotzdem,
            // weil die Sessions ohnehin nicht außerhalb des Cache geteilt werden).
            CACHE.remove(oldest.getKey());
            try {
                e.runner.close();
            } catch (Exception ex) {
                Log.w(TAG, "LRU evict close failed for " + oldest.getKey() + ": " + ex.getMessage());
            }
            Log.i(TAG, "LRU-evicted rec modelKey=" + oldest.getKey()
                    + " (live=" + CACHE.size() + ")");
        }
    }

    // -----------------------------------------------------------------
    // Inference
    // -----------------------------------------------------------------

    /**
     * Retrieves the vocabulary associated with the current model.
     *
     * If the vocabulary has not been loaded yet, it will be loaded from
     * the dictionary file and cached for future use. This method ensures
     * thread-safe initialization of the vocabulary.
     *
     * @return An array of strings representing the vocabulary for the model.
     * @throws Exception If there is an error loading the vocabulary.
     */
    private String[] vocab() throws Exception {
        String[] v = this.vocab;
        if (v == null) {
            synchronized (this) {
                v = this.vocab;
                if (v == null) {
                    v = RecDictLoader.load(this.dictFile);
                    this.vocab = v;
                    Log.i(TAG, "Vocab loaded modelKey=" + modelKey + " size=" + v.length);
                }
            }
        }
        return v;
    }

    /**
     * Retrieves the unique key identifying the recognition model associated with this instance.
     *
     * @return A string representing the model key for this instance.
     */
    String modelKey() {
        return modelKey;
    }

    /**
     * Recognizes text from a cropped image using a pre-trained model with OCR capabilities.
     * The method processes the provided image, scales it to the required dimensions,
     * performs recognition, and returns the recognition results including text, confidence scores,
     * and token information.
     *
     * @param quadCrop the input bitmap representing the cropped image to process. It must be non-null
     *                 and not recycled; otherwise, an {@link IllegalArgumentException} will be thrown.
     * @return a {@link RecOutput} object containing the recognized text, mean confidence,
     *         tokens, frame count, and processed image dimensions.
     * @throws IllegalArgumentException if the provided bitmap is null or recycled.
     * @throws Exception if an error occurs during the recognition process.
     */
    RecOutput recognize(Bitmap quadCrop) throws Exception {
        if (quadCrop == null || quadCrop.isRecycled()) {
            throw new IllegalArgumentException("quadCrop must be non-null and not recycled");
        }
        long tStart = System.nanoTime();
        final int srcW = quadCrop.getWidth();
        final int srcH = quadCrop.getHeight();

        int targetH = REC_INPUT_HEIGHT;
        double scale = (double) targetH / Math.max(1, srcH);
        int scaledW = (int) Math.round(srcW * scale);
        if (scaledW < REC_MIN_WIDTH) scaledW = REC_MIN_WIDTH;
        int paddedW = ((scaledW + REC_WIDTH_STRIDE - 1) / REC_WIDTH_STRIDE) * REC_WIDTH_STRIDE;
        if (paddedW < REC_WIDTH_STRIDE) paddedW = REC_WIDTH_STRIDE;

        // Downscale-Qualität: Androids Bilinear-Filter tastet bei Faktoren > 2 nur
        // 2×2-Nachbarschaften ab (Aliasing) und zerstört damit dünne Striche kleiner
        // Glyphen (、。 っ) in hohen vertikalen Spalten-Crops. Daher wird vor dem
        // finalen Scale stufenweise halbiert (Mipmap-Ansatz), bis der Restfaktor ≤ 2 ist.
        Bitmap prescaled = quadCrop;
        while (prescaled.getHeight() > 2 * targetH && prescaled.getWidth() >= 2) {
            Bitmap half =
                    Bitmap.createScaledBitmap(
                            prescaled,
                            Math.max(1, prescaled.getWidth() / 2),
                            Math.max(1, prescaled.getHeight() / 2),
                            true);
            if (prescaled != quadCrop) prescaled.recycle();
            prescaled = half;
        }

        Bitmap target = Bitmap.createBitmap(paddedW, targetH, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(target);
            canvas.drawColor(Color.WHITE);
            Matrix m = new Matrix();
            m.postScale(
                    (float) scaledW / Math.max(1, prescaled.getWidth()),
                    (float) targetH / Math.max(1, prescaled.getHeight()));
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(prescaled, m, paint);
            if (prescaled != quadCrop) prescaled.recycle();

            float[] input = bitmapToNchwRgbNormalized(target, paddedW, targetH);
            long[] inputShape = new long[] {1, 3, targetH, paddedW};

            synchronized (sessionLock) {
                if (closed) {
                    throw new IllegalStateException("PaddleRecOrtRunner is closed");
                }

                try (OnnxTensor inT =
                                OnnxTensor.createTensor(env, FloatBuffer.wrap(input), inputShape);
                        OrtSession.Result results =
                                session.run(Collections.singletonMap(INPUT_NAME, inT))) {

                    float[][] logits = extractLogitsTC(results);
                    int T = logits.length;
                    int C = (T > 0) ? logits[0].length : 0;

                    String[] vocab = vocab();
                    // Vocab-Validation: dict.size()+1 == V (CTC blank inkludiert).
                    // RecDictLoader prependet bereits eine Blank-Zeile, daher gilt:
                    // vocab.length == V (dict.size()+1).
                    if (C > 0 && vocab.length != C) {
                        throw new IllegalStateException(
                                "Vocab/Model size mismatch for modelKey=" + modelKey
                                        + ": vocab=" + vocab.length + " modelV=" + C);
                    }

                    CtcDecoder.Decoded dec = CtcDecoder.decode(logits, vocab);

                    int[] argmaxHead = null;
                    if (PaddleResultBuilder.ENABLE_DEBUG_DUMPS
                            && PaddleDebugDumper.isEnabled()
                            && T > 0
                            && C > 0) {
                        int n = Math.min(T, PaddleDebugDumper.ARGMAX_HEAD_FRAMES);
                        argmaxHead = new int[n];
                        for (int t = 0; t < n; t++) {
                            float[] row = logits[t];
                            int best = 0;
                            float bestVal = row[0];
                            for (int c = 1; c < C; c++) {
                                if (row[c] > bestVal) {
                                    bestVal = row[c];
                                    best = c;
                                }
                            }
                            argmaxHead[t] = best;
                        }
                    }

                    if (ENABLE_PER_CROP_RECOGNITION_LOGS) {
                        long tEnd = System.nanoTime();
                        Log.i(
                                TAG,
                                "recognize modelKey="
                                        + modelKey
                                        + " T="
                                        + T
                                        + " C="
                                        + C
                                        + " textLen="
                                        + dec.text().length()
                                        + " meanConf="
                                        + dec.meanConfidence()
                                        + " tokens="
                                        + dec.tokens().size()
                                        + " total="
                                        + ((tEnd - tStart) / 1_000_000L)
                                        + "ms");
                    }
                    return new RecOutput(
                            dec.text(),
                            dec.meanConfidence(),
                            dec.tokens(),
                            dec.frameCount(),
                            scaledW,
                            paddedW,
                            srcW,
                            argmaxHead);
                }
            }
        } finally {
            if (!target.isRecycled()) {
                target.recycle();
            }
        }
    }

    private static float[] bitmapToNchwRgbNormalized(Bitmap bm, int w, int h) {
        int[] pixels = new int[w * h];
        bm.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] out = new float[3 * w * h];
        final int planeSize = w * h;
        for (int i = 0; i < planeSize; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            out[i] = r / 127.5f - 1.0f;
            out[planeSize + i] = g / 127.5f - 1.0f;
            out[2 * planeSize + i] = b / 127.5f - 1.0f;
        }
        return out;
    }

    private static float[][] extractLogitsTC(OrtSession.Result results) throws OrtException {
        Optional<OnnxValue> first = Optional.empty();
        for (Map.Entry<String, OnnxValue> e : results) {
            first = Optional.of(e.getValue());
            break;
        }
        if (first.isEmpty()) {
            throw new IllegalStateException("ONNX result is empty");
        }
        Object v = first.get().getValue();
        if (!(v instanceof float[][][])) {
            throw new IllegalStateException(
                    "Unexpected rec output type: "
                            + (v == null ? "null" : v.getClass().getName()));
        }
        float[][][] tensor = (float[][][]) v;
        if (tensor.length < 1) {
            throw new IllegalStateException("Unexpected rec output shape");
        }
        return tensor[0];
    }

    @Override
    public void close() {
        synchronized (sessionLock) {
            try {
                if (!closed && session != null) session.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing OrtSession: " + e.getMessage());
            }
            closed = true;
        }
        // OrtEnvironment ist global/shared; nicht schließen.
    }
}
