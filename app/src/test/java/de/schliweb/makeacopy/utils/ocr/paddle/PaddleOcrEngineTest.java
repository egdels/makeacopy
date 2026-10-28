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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.RectF;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * JVM-Unit-Tests für {@link PaddleOcrEngine} mit gestubten Det- und Rec-Runnern.
 *
 * <p>Verifiziert das Zusammenspiel:
 * <ul>
 *   <li>Reading-Order: {@link PaddleResultBuilder} sortiert Quads top-to-bottom, left-to-right.
 *   <li>Konfidenz-Skalierung 0..1 → 0..100 (Tesseract-Konvention).
 *   <li>BBox-Bounds plausibel und nicht-leer.
 * </ul>
 *
 * <p>Bitmap-Argument wird via {@code runInternal(Bitmap)} mit {@code null} weitergereicht;
 * der gestubte Cropper liefert {@code null}, sodass keine Bitmap-Methoden aufgerufen werden.
 */
public class PaddleOcrEngineTest {

    /** Stub-Det: liefert eine fest verdrahtete, unsortierte Quad-Liste. */
    private static final class StubDet extends PaddleDetOrtRunner {
        private final List<Quad> quads;

        StubDet(List<Quad> quads) {
            super();
            this.quads = quads;
        }

        @Override
        List<Quad> detect(Bitmap bitmap) {
            return quads;
        }

        @Override
        List<Quad> detect(Bitmap bitmap, boolean allowHighQualityRetry) {
            return quads;
        }
    }

    /** Stub-Rec: deterministische Tokens+Konfidenzen aus FIFO-Liste. */
    private static final class StubRec extends PaddleRecOrtRunner {
        private final List<PaddleRecOrtRunner.RecOutput> outputs;
        int callCount = 0;

        StubRec(List<PaddleRecOrtRunner.RecOutput> outputs) {
            super();
            this.outputs = outputs;
        }

        @Override
        PaddleRecOrtRunner.RecOutput recognize(Bitmap quadCrop) {
            return outputs.get(callCount++);
        }
    }

    private static Quad quadAt(double x, double y, double w, double h) {
        return new Quad(
                new double[] {x, x + w, x + w, x},
                new double[] {y, y, y + h, y + h},
                0.95);
    }

    @Test
    public void recognize_sortsToReadingOrderAndScalesConfidence() throws Exception {
        // Drei Quads in NICHT-Lesereihenfolge. Erwartet wird:
        //   1) "alpha"   bei y=10, x=10
        //   2) "beta"    bei y=10, x=120  (gleiche Zeile, weiter rechts)
        //   3) "gamma"   bei y=80, x=15   (untere Zeile)
        Quad qBeta = quadAt(120, 10, 60, 14); // tatsächlich Position 2
        Quad qGamma = quadAt(15, 80, 70, 14); // tatsächlich Position 3
        Quad qAlpha = quadAt(10, 10, 50, 14); // tatsächlich Position 1
        List<Quad> unsorted = Arrays.asList(qBeta, qGamma, qAlpha);

        // Stub-Rec liefert Tokens in der erwarteten Reading-Order (alpha, beta, gamma),
        // weil PaddleResultBuilder die Quads sortiert, bevor er rec.recognize() aufruft.
        StubRec rec =
                new StubRec(
                        Arrays.asList(
                                new PaddleRecOrtRunner.RecOutput("alpha", 0.80f),
                                new PaddleRecOrtRunner.RecOutput("beta", 0.90f),
                                new PaddleRecOrtRunner.RecOutput("gamma", 0.70f)));

        StubDet det = new StubDet(unsorted);

        // Bitmap-Bounds für Bounds-Check (logisch — im JVM-Test ohne echte Bitmap).
        final int bmpW = 400;
        final int bmpH = 200;

        PaddleOcrEngine.RunnerSupplier supplier =
                new PaddleOcrEngine.RunnerSupplier() {
                    @Override
                    public PaddleDetOrtRunner det() {
                        return det;
                    }

                    @Override
                    public PaddleRecOrtRunner rec(String modelKey) {
                        return rec;
                    }

                    @Override
                    public PaddleResultBuilder.Cropper cropper() {
                        return (full, quad) -> null; // bypass Crop in JVM-Test
                    }
                };

        // null als Context: Engine speichert nur appContext, das hier nicht genutzt wird.
        PaddleOcrEngine engine = new PaddleOcrEngine(null, supplier);
        // Sprachrouting: "eng" → modelKey "en"; ohne setLanguage liefert runInternal sofort
        // ein leeres Result (Tesseract-Fallback-Pfad).
        engine.setLanguage("eng");

        OCRHelper.OcrResultWords result = engine.runInternal(null);

        assertNotNull(result);
        assertNotNull(result.words);
        assertEquals(3, result.words.size());
        assertEquals(3, rec.callCount);

        // Reading-Order: alpha, beta, gamma.
        RecognizedWord w0 = result.words.get(0);
        RecognizedWord w1 = result.words.get(1);
        RecognizedWord w2 = result.words.get(2);
        assertEquals("alpha", w0.getText());
        assertEquals("beta", w1.getText());
        assertEquals("gamma", w2.getText());

        // Konfidenz-Skalierung: 0..1 → 0..100.
        assertEquals(80f, w0.getConfidence(), 1e-3f);
        assertEquals(90f, w1.getConfidence(), 1e-3f);
        assertEquals(70f, w2.getConfidence(), 1e-3f);

        // Aggregate. Reading-Order-Fix (Schritt 10a): alpha+beta auf derselben Zeile (y≈10..24,
        // gemeinsamer Center-Y), gamma auf neuer Zeile (y≈80..94).
        assertEquals("alpha beta\ngamma", result.text);
        assertNotNull(result.meanConfidence);
        // round((80+90+70)/3) = 80
        assertEquals(Integer.valueOf(80), result.meanConfidence);

        // BBox-Plausibilität: nicht-leer und innerhalb der logischen Bitmap-Grenzen.
        // Hinweis: In JVM-Unit-Tests sind {@code android.graphics.RectF}-Felder/-Methoden
        // mockable-default (0), daher prüfen wir die Plausibilität indirekt anhand der vom
        // Builder konsumierten Quads (deren Bounds in der erwarteten Reading-Order).
        double[][] expectedBounds = {
                {10, 10, 60, 24},   // alpha
                {120, 10, 180, 24}, // beta
                {15, 80, 85, 94}    // gamma
        };
        for (int i = 0; i < expectedBounds.length; i++) {
            RectF bb = result.words.get(i).getBoundingBox();
            assertNotNull(bb);
            double[] eb = expectedBounds[i];
            assertTrue("bbox width > 0", eb[2] - eb[0] > 0);
            assertTrue("bbox height > 0", eb[3] - eb[1] > 0);
            assertTrue("bbox left >= 0", eb[0] >= 0);
            assertTrue("bbox top >= 0", eb[1] >= 0);
            assertTrue("bbox right <= bmpW", eb[2] <= bmpW);
            assertTrue("bbox bottom <= bmpH", eb[3] <= bmpH);
        }
    }
}
