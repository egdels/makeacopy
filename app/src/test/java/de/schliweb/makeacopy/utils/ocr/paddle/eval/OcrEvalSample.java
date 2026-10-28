/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr.paddle.eval;

import android.graphics.Bitmap;

/**
 * Eval-Sample-POJO für die OCR-Eval-Pipeline (§9 Konzept).
 *
 * <p>Trägt entweder einen Pfad zu einem Bitmap-Asset und/oder ein bereits geladenes Bitmap, plus
 * den zugehörigen Ground-Truth-Text. In JVM-Unit-Tests wird {@link #bitmap} typischerweise via
 * Stub-Engine ignoriert; in instrumented oder nativen Eval-Läufen kommt {@link #imagePath} zum
 * Einsatz.
 *
 * <p>Diese Klasse ist Teil des Skeletts — kein vollständiger Eval-Lauf in dieser Session.
 */
public final class OcrEvalSample {
    public final String imagePath;
    public final Bitmap bitmap;
    public final String groundTruth;

    public OcrEvalSample(String imagePath, Bitmap bitmap, String groundTruth) {
        this.imagePath = imagePath;
        this.bitmap = bitmap;
        this.groundTruth = groundTruth == null ? "" : groundTruth;
    }

    public static OcrEvalSample of(Bitmap bitmap, String groundTruth) {
        return new OcrEvalSample(null, bitmap, groundTruth);
    }
}
