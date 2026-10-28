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

/**
 * Aggregierter Eval-Report (§9 Konzept).
 *
 * <p>Felder:
 * <ul>
 *   <li>{@link #cer} — Character Error Rate, gemittelt über alle Samples (0..1).
 *   <li>{@link #wer} — Word Error Rate, gemittelt über alle Samples (0..1).
 *   <li>{@link #meanConfidence} — durchschnittliche {@code OcrResultWords.meanConfidence} (0..100).
 *   <li>{@link #latencyMsP50} / {@link #latencyMsP95} — Perzentile der Pro-Sample-Latenz in ms.
 *   <li>{@link #nSamples} — Anzahl ausgewerteter Samples.
 * </ul>
 */
public final class OcrEvalReport {
    public final double cer;
    public final double wer;
    public final double meanConfidence;
    public final long latencyMsP50;
    public final long latencyMsP95;
    public final int nSamples;

    public OcrEvalReport(
            double cer,
            double wer,
            double meanConfidence,
            long latencyMsP50,
            long latencyMsP95,
            int nSamples) {
        this.cer = cer;
        this.wer = wer;
        this.meanConfidence = meanConfidence;
        this.latencyMsP50 = latencyMsP50;
        this.latencyMsP95 = latencyMsP95;
        this.nSamples = nSamples;
    }
}
