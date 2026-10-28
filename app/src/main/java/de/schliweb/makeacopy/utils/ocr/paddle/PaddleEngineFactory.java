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
import de.schliweb.makeacopy.utils.ocr.OcrEngine;

/**
 * Factory-Klasse für die Instanziierung und Verwaltung von Paddle-OCR-Engines.
 *
 * <p>Die Klasse bietet Methoden zur Erstellung und Verwaltung von {@link OcrEngine}-Instanzen,
 * einschließlich der Sicherstellung erforderlicher Detection-Assets. Sie stellt außerdem
 * Bequemlichkeitsfunktionen bereit, um direkt mit einer konfigurierten Sprache zu arbeiten.
 */
public final class PaddleEngineFactory {

    private PaddleEngineFactory() {}

    /**
     * Creates and returns an instance of {@link OcrEngine}. Ensures Paddle-OCR detection assets are
     * correctly extracted and available before instantiating the engine.
     *
     * @param context the Android {@link Context} object used for accessing application resources and
     *                system services required for initialization of the OCR engine.
     * @return a new instance of {@link OcrEngine}.
     * @throws Exception if the detection assets cannot be extracted or if the engine creation fails.
     */
    public static OcrEngine create(Context context) throws Exception {
        PaddleAssets.ensureDetExtracted(context);
        return new PaddleOcrEngine(context);
    }

    /**
     * Creates and returns an instance of {@link OcrEngine} configured with the specified language.
     * Ensures Paddle-OCR detection assets are correctly extracted and available before instantiating
     * the engine.
     *
     * @param context the Android {@link Context} object used for accessing application resources and
     *                system services required for initialization of the OCR engine.
     * @param langSpec the language specification string, following the standard Tesseract-style
     *                 format, to configure the {@link OcrEngine}.
     * @return a new instance of {@link OcrEngine} configured with the specified language.
     * @throws Exception if the detection assets cannot be extracted or if the engine creation or
     *                   language configuration fails.
     */
    public static OcrEngine create(Context context, String langSpec) throws Exception {
        OcrEngine e = create(context);
        e.setLanguage(langSpec);
        return e;
    }

    /**
     * Releases all resources held by Paddle detection and recognition engines.
     *
     * <p>This method is intended to clean up resources allocated by the underlying Paddle engines
     * for detection and recognition tasks. It makes a "best effort" attempt to release these
     * resources by calling the respective release methods on {@code PaddleDetOrtRunner} and
     * {@code PaddleRecOrtRunner}. Any exceptions or errors encountered during this process are
     * caught and ignored to ensure the method completes execution without failure.
     */
    public static void releaseAll() {
        try {
            PaddleDetOrtRunner.releaseInstance();
        } catch (Throwable ignored) {
            // best effort
        }
        try {
            PaddleRecOrtRunner.releaseAll();
        } catch (Throwable ignored) {
            // best effort
        }
    }
}
