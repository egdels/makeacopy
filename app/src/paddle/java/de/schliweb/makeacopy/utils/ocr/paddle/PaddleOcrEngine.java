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

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import androidx.annotation.VisibleForTesting;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.OcrEngine;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of the {@link OcrEngine} interface using the Paddle OCR backend.
 *
 * This class provides OCR functionality by utilizing Paddle OCR's detection and recognition
 * models. The main purpose of this class is to perform text detection and recognition on
 * input bitmaps.
 *
 * This implementation is designed to be used internally by the infrastructure via the
 * {@link OCRHelper} class. Instances of this engine should not be directly created or managed
 * by external callers.
 *
 * Key features of this implementation:
 * - Detection and recognition are performed using async runners.
 * - Supports language-based model selection.
 * - Returns an empty result if no recognition model is found.
 *
 * Thread Safety:
 * - This class is thread-safe.
 * - An internal mechanism ensures proper handling of detection and recognition runners.
 */
public final class PaddleOcrEngine implements OcrEngine {

    private static final String TAG = "PaddleOcrEngine";

    /**
     * Interface defining the contract for supplying runners used in OCR operations, including detection, recognition,
     * and optional cropping functionality. This interface is typically implemented to create custom or test-specific
     * runner suppliers.
     */
    interface RunnerSupplier {
        PaddleDetOrtRunner det() throws Exception;

        /**
         * Provides a PaddleRecOrtRunner instance for the specified recognition model.
         *
         * @param modelKey the key identifying the recognition model to be used.
         * @return an instance of PaddleRecOrtRunner for the specified model.
         * @throws Exception if there is an error during the runner initialization or model loading process.
         */
        PaddleRecOrtRunner rec(String modelKey) throws Exception;

        /**
         * Provides a default cropper implementation for processing OCR results.
         * The cropper is responsible for handling the cropping logic defined by the PaddleQuadCropper.
         *
         * @return a lambda reference to the cropping method defined in PaddleQuadCropper.
         */
        default PaddleResultBuilder.Cropper cropper() {
            return PaddleQuadCropper::crop;
        }
    }

    private final RunnerSupplier supplier;
    private volatile String language;
    private volatile boolean highQualityDetectionEnabled;

    PaddleOcrEngine(Context context) {
        this(context, defaultSupplier(context));
    }

    @VisibleForTesting
    PaddleOcrEngine(Context context, RunnerSupplier supplier) {
        this.supplier = supplier;
    }

    private static RunnerSupplier defaultSupplier(Context context) {
        final Context appCtx = context.getApplicationContext();
        return new RunnerSupplier() {
            @Override
            public PaddleDetOrtRunner det() throws Exception {
                return PaddleDetOrtRunner.getInstanceAsync(appCtx).get(60, TimeUnit.SECONDS);
            }

            @Override
            public PaddleRecOrtRunner rec(String modelKey) throws Exception {
                return PaddleRecOrtRunner.getInstanceAsync(appCtx, modelKey)
                        .get(60, TimeUnit.SECONDS);
            }
        };
    }

    @Override
    public String id() {
        return "paddle";
    }

    @Override
    public boolean isAvailable(Context ctx) {
        try {
            // Verfügbar, wenn Detection vorhanden und mindestens ein Recognition-Modell.
            if (!PaddleAssets.getDetModelFile(ctx).exists()
                    && !assetExists(ctx, PaddleAssets.DET_NAME)) {
                return false;
            }
            return PaddleAssets.anyRecModelPresent(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "isAvailable check failed", t);
            return false;
        }
    }

    private static boolean assetExists(Context ctx, String name) {
        try {
            String[] arr = ctx.getAssets().list(PaddleAssets.ASSET_DIR);
            if (arr == null) return false;
            for (String a : arr) if (name.equals(a)) return true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void setLanguage(String langSpec) {
        this.language = langSpec;
        // Tatsächliche Modell-Auflösung erfolgt pro run() in PaddleLanguageRouter.
    }

    public void setHighQualityDetectionEnabled(boolean enabled) {
        highQualityDetectionEnabled = enabled;
    }

    @Override
    public OCRHelper.OcrResultWords run(Bitmap bitmap) throws Exception {
        if (bitmap == null || bitmap.isRecycled()) {
            return new OCRHelper.OcrResultWords("", null, new java.util.ArrayList<>());
        }
        return runInternal(bitmap);
    }

    /**
     * Executes the internal OCR processing workflow by utilizing a detection and recognition model
     * to process the given image and extract recognized words along with their coordinates and confidence scores.
     *
     * @param bitmap the image to be processed for optical character recognition
     * @return an instance of {@link OCRHelper.OcrResultWords} containing the recognized words,
     *         their spatial information, and confidence scores
     * @throws Exception if an error occurs during the OCR processing workflow
     */
    @VisibleForTesting
    OCRHelper.OcrResultWords runInternal(Bitmap bitmap) throws Exception {
        long tStart = System.nanoTime();
        String modelKey = PaddleLanguageRouter.resolveRecModel(language);
        if (modelKey == null) {
            Log.i(TAG, "no rec model for lang=" + language + " → empty result");
            return new OCRHelper.OcrResultWords("", null, new java.util.ArrayList<>());
        }
        PaddleDetOrtRunner det = supplier.det();
        PaddleRecOrtRunner rec = supplier.rec(modelKey);
        long tInit = System.nanoTime();

        if (PaddleResultBuilder.ENABLE_DEBUG_DUMPS) {
            PaddleDebugDumper.registerSample(
                    bitmap, "img_" + Integer.toHexString(System.identityHashCode(bitmap)));
        }

        List<Quad> quads = det.detect(bitmap, highQualityDetectionEnabled);
        // Second pass for low-confidence lines with the base model, whose Latin recognition is
        // stronger than the script-specific mobile models' on digits, capitals and wide gaps.
        // Loaded lazily, only when a line actually falls below the threshold.
        java.util.concurrent.Callable<PaddleRecOrtRunner> fallbackRec =
                PaddleLanguageRouter.hasBaseModelFallback(modelKey)
                        ? () -> supplier.rec(PaddleLanguageRouter.BASE_MODEL_KEY)
                        : null;
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(bitmap, quads, rec, supplier.cropper(), fallbackRec);
        long tEnd = System.nanoTime();

        Log.i(
                TAG,
                "recognize modelKey="
                        + modelKey
                        + " live="
                        + PaddleRecOrtRunner.liveSessionCount()
                        + " initMs="
                        + ((tInit - tStart) / 1_000_000L)
                        + " totalMs="
                        + ((tEnd - tStart) / 1_000_000L)
                        + " quads="
                        + (quads == null ? 0 : quads.size())
                        + " words="
                        + (result.words == null ? 0 : result.words.size())
                        + " meanConfidence="
                        + result.meanConfidence);
        return result;
    }

    @Override
    public void close() {
        release();
    }

    /**
     * Releases resources and instances associated with the OCR engine.
     *
     * This method ensures the proper deallocation of resources used by both
     * the detection and recognition models. It attempts to release the
     * PaddleDetOrtRunner and PaddleRecOrtRunner instances. If any error
     * occurs while releasing resources, a warning message is logged.
     *
     * Potential uses include calling this method when the engine is no longer
     * needed to free up system resources.
     */
    void release() {
        try {
            PaddleDetOrtRunner.releaseInstance();
        } catch (Throwable t) {
            Log.w(TAG, "Det releaseInstance failed", t);
        }
        try {
            PaddleRecOrtRunner.releaseAll();
        } catch (Throwable t) {
            Log.w(TAG, "Rec releaseAll failed", t);
        }
    }
}
