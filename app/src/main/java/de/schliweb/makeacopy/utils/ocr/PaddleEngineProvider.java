/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import android.content.Context;
import android.util.Log;
import de.schliweb.makeacopy.utils.ocr.paddle.PaddleEngineFactory;

/**
 * Provides functionality to create and manage Paddle OCR engine instances.
 *
 * This class serves as a wrapper for the creation and release of OCR engine
 * instances using the Paddle OCR backend through the {@link PaddleEngineFactory}.
 * It manages language configuration for the OCR engine and ensures that
 * resources are released when no longer needed.
 *
 * This is a utility class and should not be instantiated.
 */
public final class PaddleEngineProvider {

    private static final String TAG = "PaddleEngineProvider";

    private PaddleEngineProvider() {}

    static OcrEngine create(Context context, String language) {
        if (context == null) {
            return null;
        }
        Context appCtx = context.getApplicationContext();
        try {
            OcrEngine engine = PaddleEngineFactory.create(appCtx);
            if (language != null) {
                try {
                    engine.setLanguage(language);
                } catch (Throwable t) {
                    Log.w(TAG, "engine.setLanguage failed (continuing)", t);
                }
            }
            return engine;
        } catch (Throwable t) {
            Log.w(TAG, "PaddleEngineFactory.create failed", t);
            return null;
        }
    }

    public static void releaseAll(Context context) {
        try {
            PaddleEngineFactory.releaseAll();
        } catch (Throwable t) {
            Log.w(TAG, "releaseAll failed", t);
        }
    }
}
