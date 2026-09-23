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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** JVM-Unit-Tests für {@link PaddleResultBuilder}. */
public class PaddleResultBuilderTest {

    /** Stub-Recognizer: liefert Texte/Konfidenzen aus einer FIFO-Liste. */
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

    /** Cropper, der einfach {@code null} zurückgibt — kein Bitmap-API benötigt. */
    private static final PaddleResultBuilder.Cropper PASSTHROUGH = (full, quad) -> null;

    private static Quad quadAt(double x, double y, double w, double h) {
        return new Quad(
                new double[] {x, x + w, x + w, x},
                new double[] {y, y, y + h, y + h},
                0.95);
    }

    @Test
    public void build_lowConfidenceLine_isReadAgainWithTheFallbackAndTheBetterReadingWins()
            throws Exception {
        Quad q = quadAt(10, 20, 200, 12);
        StubRec rec =
                new StubRec(
                        Collections.singletonList(
                                new PaddleRecOrtRunner.RecOutput("0orempsu", 0.30f)));
        StubRec fallback =
                new StubRec(
                        Collections.singletonList(
                                new PaddleRecOrtRunner.RecOutput("01Lorem ipsum", 0.97f)));
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(
                        null, Collections.singletonList(q), rec, PASSTHROUGH, () -> fallback);
        assertEquals("01Lorem ipsum", result.text);
        assertEquals(1, rec.callCount);
        assertEquals(1, fallback.callCount);
    }

    @Test
    public void build_confidentLine_neverAsksTheFallback() throws Exception {
        Quad q = quadAt(10, 20, 200, 12);
        StubRec rec =
                new StubRec(
                        Collections.singletonList(
                                new PaddleRecOrtRunner.RecOutput("hello", 0.90f)));
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(
                        null,
                        Collections.singletonList(q),
                        rec,
                        PASSTHROUGH,
                        () -> {
                            throw new AssertionError("fallback must not be created");
                        });
        assertEquals("hello", result.text);
    }

    @Test
    public void build_fallbackLessConfident_keepsTheFirstReading() throws Exception {
        Quad q = quadAt(10, 20, 200, 12);
        StubRec rec =
                new StubRec(
                        Collections.singletonList(new PaddleRecOrtRunner.RecOutput("abc", 0.50f)));
        StubRec fallback =
                new StubRec(
                        Collections.singletonList(new PaddleRecOrtRunner.RecOutput("xyz", 0.40f)));
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(
                        null, Collections.singletonList(q), rec, PASSTHROUGH, () -> fallback);
        assertEquals("abc", result.text);
        assertEquals(1, fallback.callCount);
    }

    @Test
    public void build_twoQuads_yieldsWordsInOrderWithMeanConfidence() throws Exception {
        Quad q1 = quadAt(10, 20, 50, 12); // y=20, "first"
        Quad q2 = quadAt(15, 60, 80, 14); // y=60, "second"
        List<Quad> quads = Arrays.asList(q1, q2);

        StubRec rec =
                new StubRec(
                        Arrays.asList(
                                new PaddleRecOrtRunner.RecOutput("first", 0.80f),
                                new PaddleRecOrtRunner.RecOutput("second", 0.90f)));

        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(null, quads, rec, PASSTHROUGH);

        assertNotNull(result);
        assertEquals(2, result.words.size());
        assertEquals(2, rec.callCount);

        // Reading-Order: zuerst q1, dann q2.
        RecognizedWord w1 = result.words.get(0);
        RecognizedWord w2 = result.words.get(1);
        assertEquals("first", w1.getText());
        assertEquals("second", w2.getText());

        // Wort-Konfidenzen: 0..1 -> 0..100 skaliert.
        assertEquals(80f, w1.getConfidence(), 1e-3f);
        assertEquals(90f, w2.getConfidence(), 1e-3f);

        // Aggregat-Text getrennt durch Newline.
        assertEquals("first\nsecond", result.text);

        // Aggregat-meanConfidence = round((80+90)/2) = 85.
        assertNotNull(result.meanConfidence);
        assertEquals(Integer.valueOf(85), result.meanConfidence);
    }

    @Test
    public void build_emptyQuads_returnsEmptyResult() throws Exception {
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(null, Collections.emptyList(), new StubRec(new ArrayList<>()), PASSTHROUGH);
        assertEquals("", result.text);
        assertNull(result.meanConfidence);
        assertTrue(result.words.isEmpty());
    }

    @Test
    public void build_singleQuad_meanConfidenceEqualsWordConfidence() throws Exception {
        Quad q = quadAt(0, 0, 10, 10);
        StubRec rec =
                new StubRec(
                        Collections.singletonList(
                                new PaddleRecOrtRunner.RecOutput("hello", 0.42f)));
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(null, Collections.singletonList(q), rec, PASSTHROUGH);
        assertEquals("hello", result.text);
        assertEquals(1, result.words.size());
        // 0.42 * 100 = 42 (gerundet).
        assertEquals(Integer.valueOf(42), result.meanConfidence);
    }

    @Test
    public void build_clampsConfidenceTo0to100() throws Exception {
        Quad q = quadAt(0, 0, 10, 10);
        StubRec rec =
                new StubRec(
                        Collections.singletonList(
                                new PaddleRecOrtRunner.RecOutput("x", 1.5f))); // außerhalb 0..1
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(null, Collections.singletonList(q), rec, PASSTHROUGH);
        assertEquals(100f, result.words.get(0).getConfidence(), 1e-3f);
        assertEquals(Integer.valueOf(100), result.meanConfidence);
    }
}
