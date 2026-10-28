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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.OcrEngine;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.junit.Test;

/**
 * Wiring-Test für das Eval-Pipeline-Skelett: prüft, dass alle Felder von {@link OcrEvalReport}
 * korrekt aus den Sample-Ergebnissen einer Stub-Engine aggregiert werden. Es wird kein echter
 * Datensatz geladen — nur synthetische Mini-Samples für die Verdrahtung.
 */
public class OcrEvalRunnerWiringTest {

    /** Stub-Engine: liefert für jeden {@code run()}-Aufruf eine vordefinierte {@link OCRHelper.OcrResultWords}. */
    private static final class StubEngine implements OcrEngine {
        private final Iterator<OCRHelper.OcrResultWords> outs;

        StubEngine(List<OCRHelper.OcrResultWords> outs) {
            this.outs = outs.iterator();
        }

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public boolean isAvailable(Context ctx) {
            return true;
        }

        @Override
        public void setLanguage(String langSpec) {}

        @Override
        public OCRHelper.OcrResultWords run(Bitmap bitmap) {
            return outs.next();
        }

        @Override
        public void close() {}
    }

    private static OCRHelper.OcrResultWords words(String text, Integer mean) {
        List<RecognizedWord> ws = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            ws.add(new RecognizedWord(text, null, mean == null ? 0f : mean.floatValue()));
        }
        return new OCRHelper.OcrResultWords(text == null ? "" : text, mean, ws);
    }

    @Test
    public void run_aggregatesAllReportFields() throws Exception {
        // Sample 1: GT="hello world", Pred="hello world" → CER=0, WER=0
        // Sample 2: GT="foo bar",     Pred="foo baz"     → CER=1/7, WER=1/2
        OcrEvalSample s1 = OcrEvalSample.of(null, "hello world");
        OcrEvalSample s2 = OcrEvalSample.of(null, "foo bar");

        StubEngine engine =
                new StubEngine(
                        Arrays.asList(
                                words("hello world", 90),
                                words("foo baz", 70)));

        OcrEvalReport report =
                new OcrEvalRunner().run(Arrays.asList(s1, s2), engine);

        assertNotNull(report);
        assertEquals(2, report.nSamples);
        // CER mittelwert: (0 + 1/7) / 2 = 1/14
        assertEquals(1.0 / 14.0, report.cer, 1e-9);
        // WER mittelwert: (0 + 1/2) / 2 = 1/4
        assertEquals(0.25, report.wer, 1e-9);
        // meanConfidence-Aggregat: (90 + 70) / 2 = 80
        assertEquals(80.0, report.meanConfidence, 1e-9);
        // Latency-Perzentile sind nicht-negativ.
        assertTrue("p50 >= 0", report.latencyMsP50 >= 0L);
        assertTrue("p95 >= 0", report.latencyMsP95 >= 0L);
        assertTrue("p95 >= p50", report.latencyMsP95 >= report.latencyMsP50);
    }

    @Test
    public void run_emptySamples_returnsZeroReport() throws Exception {
        OcrEvalReport r =
                new OcrEvalRunner().run(new ArrayList<>(), new StubEngine(new ArrayList<>()));
        assertEquals(0, r.nSamples);
        assertEquals(0.0, r.cer, 0.0);
        assertEquals(0.0, r.wer, 0.0);
        assertEquals(0L, r.latencyMsP50);
        assertEquals(0L, r.latencyMsP95);
    }
}
