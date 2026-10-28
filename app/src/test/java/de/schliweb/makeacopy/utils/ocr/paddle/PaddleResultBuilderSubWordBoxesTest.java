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
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Sub-Word-BBox-Tests für {@link PaddleResultBuilder}.
 *
 * <p>Stub-Recognizer liefert deterministische Token-Sequenz inklusive zwei Whitespace-Lücken;
 * erwartet werden drei Wörter mit aufeinanderfolgenden, nicht-überlappenden BBoxes innerhalb
 * eines horizontalen Quads.
 *
 * <p><b>Hinweis:</b> {@code RectF} ist im JVM-Unit-Test ein Android-Stub mit Default-Werten 0
 * (vgl. {@code unitTests.returnDefaultValues = true} in {@code app/build.gradle}). Geometrische
 * Vergleiche laufen daher gegen {@link PaddleResultBuilder#interpolateQuadStripFloats}; die
 * Builder-Verifikation prüft Anzahl, Texte, Konfidenzen und das Aggregat.
 */
public class PaddleResultBuilderSubWordBoxesTest {

    private static final PaddleResultBuilder.Cropper PASSTHROUGH = (full, q) -> null;

    private static Quad horizontalQuad(double x, double y, double w, double h) {
        return new Quad(
                new double[] {x, x + w, x + w, x},
                new double[] {y, y, y + h, y + h},
                0.95);
    }

    /** Stub-Recognizer mit FIFO-Outputs (inkl. Sub-Word-Geometrie). */
    private static final class StubRec extends PaddleRecOrtRunner {
        private final List<PaddleRecOrtRunner.RecOutput> outs;
        int callCount = 0;

        StubRec(List<PaddleRecOrtRunner.RecOutput> outs) {
            super();
            this.outs = outs;
        }

        @Override
        PaddleRecOrtRunner.RecOutput recognize(Bitmap quadCrop) {
            return outs.get(callCount++);
        }
    }

    /** Berechnet die Frame-Range eines Tokens als {@code [uStart, uEnd]} entsprechend Builder-Logik. */
    private static double[] frameRangeToU(int frameStart, int frameEnd, int T, int paddedW, int scaledW) {
        double xStart = ((double) frameStart) * paddedW / T;
        double xEnd = ((double) (frameEnd + 1)) * paddedW / T;
        double uStart = Math.max(0.0, Math.min(1.0, xStart / scaledW));
        double uEnd = Math.max(0.0, Math.min(1.0, xEnd / scaledW));
        return new double[] {uStart, uEnd};
    }

    @Test
    public void interpolateQuadStripFloats_horizontalQuad_linearXMapping() {
        Quad q = horizontalQuad(100, 50, 800, 40);
        // u in [0.25, 0.75] -> x in [300, 700], y unverändert [50, 90].
        float[] r = PaddleResultBuilder.interpolateQuadStripFloats(q, 0.25, 0.75);
        assertEquals(300f, r[0], 1e-3f);
        assertEquals(50f, r[1], 1e-3f);
        assertEquals(700f, r[2], 1e-3f);
        assertEquals(90f, r[3], 1e-3f);
    }

    @Test
    public void interpolateQuadStripFloats_skewedQuad_axisAlignedHull() {
        // Schräges Quad: TL(0,0), TR(100,10), BR(100,30), BL(0,20).
        Quad q = new Quad(
                new double[] {0, 100, 100, 0},
                new double[] {0, 10, 30, 20},
                0.9);
        // u in [0.0, 0.5]: top0=(0,0), top1=(50,5), bot0=(0,20), bot1=(50,25)
        // hull: minX=0, maxX=50, minY=0, maxY=25.
        float[] r = PaddleResultBuilder.interpolateQuadStripFloats(q, 0.0, 0.5);
        assertEquals(0f, r[0], 1e-3f);
        assertEquals(0f, r[1], 1e-3f);
        assertEquals(50f, r[2], 1e-3f);
        assertEquals(25f, r[3], 1e-3f);
    }

    @Test
    public void build_threeTokensInHorizontalQuad_yieldsThreeNonOverlappingSubWords()
            throws Exception {
        Quad q = horizontalQuad(100, 50, 800, 40);
        final int T = 20, paddedW = 200, scaledW = 200;

        List<CtcDecoder.RecognizedToken> tokens = Arrays.asList(
                new CtcDecoder.RecognizedToken("foo", 0.90f, 0, 4),
                new CtcDecoder.RecognizedToken("bar", 0.80f, 7, 11),
                new CtcDecoder.RecognizedToken("baz", 0.70f, 14, 19));
        PaddleRecOrtRunner.RecOutput out =
                new PaddleRecOrtRunner.RecOutput(
                        "foo bar baz", 0.80f, tokens, T, scaledW, paddedW, scaledW);

        StubRec rec = new StubRec(Collections.singletonList(out));
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(null, Collections.singletonList(q), rec, PASSTHROUGH);

        assertNotNull(result);
        assertEquals("foo bar baz", result.text);
        assertEquals(3, result.words.size());

        RecognizedWord w0 = result.words.get(0);
        RecognizedWord w1 = result.words.get(1);
        RecognizedWord w2 = result.words.get(2);
        assertEquals("foo", w0.getText());
        assertEquals("bar", w1.getText());
        assertEquals("baz", w2.getText());

        // Konfidenzen 0..1 → 0..100.
        assertEquals(90f, w0.getConfidence(), 1e-3f);
        assertEquals(80f, w1.getConfidence(), 1e-3f);
        assertEquals(70f, w2.getConfidence(), 1e-3f);
        // Aggregat-meanConfidence = round((90+80+70)/3) = 80.
        assertEquals(Integer.valueOf(80), result.meanConfidence);

        // Geometrie: Frame-Range → u → BBox via interpolateQuadStripFloats.
        double[] u0 = frameRangeToU(0, 4, T, paddedW, scaledW);
        double[] u1 = frameRangeToU(7, 11, T, paddedW, scaledW);
        double[] u2 = frameRangeToU(14, 19, T, paddedW, scaledW);
        float[] r0 = PaddleResultBuilder.interpolateQuadStripFloats(q, u0[0], u0[1]);
        float[] r1 = PaddleResultBuilder.interpolateQuadStripFloats(q, u1[0], u1[1]);
        float[] r2 = PaddleResultBuilder.interpolateQuadStripFloats(q, u2[0], u2[1]);
        // Erwartung über die Quad-Mathematik: 100..300, 380..580, 660..900.
        assertEquals(100f, r0[0], 1e-3f);
        assertEquals(300f, r0[2], 1e-3f);
        assertEquals(380f, r1[0], 1e-3f);
        assertEquals(580f, r1[2], 1e-3f);
        assertEquals(660f, r2[0], 1e-3f);
        assertEquals(900f, r2[2], 1e-3f);
        // Aufeinanderfolgend, nicht-überlappend.
        assertTrue("r0.right <= r1.left", r0[2] <= r1[0]);
        assertTrue("r1.right <= r2.left", r1[2] <= r2[0]);
    }

    @Test
    public void build_singleTokenWithGeometry_fallsBackToFullQuadBBox_textAndConf() throws Exception {
        Quad q = horizontalQuad(0, 0, 100, 20);
        List<CtcDecoder.RecognizedToken> tokens = Collections.singletonList(
                new CtcDecoder.RecognizedToken("hello", 0.5f, 2, 8));
        PaddleRecOrtRunner.RecOutput out =
                new PaddleRecOrtRunner.RecOutput("hello", 0.5f, tokens, 12, 100, 100, 100);

        StubRec rec = new StubRec(Collections.singletonList(out));
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(null, Collections.singletonList(q), rec, PASSTHROUGH);

        assertEquals(1, result.words.size());
        RecognizedWord w = result.words.get(0);
        assertEquals("hello", w.getText());
        // Bei 1 Token verwenden wir Token-Konfidenz, nicht Aggregat.
        assertEquals(50f, w.getConfidence(), 1e-3f);
    }

    @Test
    public void build_paddingBeyondScaledWidth_isClippedToContent_geometryOnly() {
        Quad q = horizontalQuad(0, 0, 200, 10);
        final int T = 20, paddedW = 320, scaledW = 200;
        // Frame 12..15: padded 192..256, clipped auf scaledW=200.
        double[] u = frameRangeToU(12, 15, T, paddedW, scaledW);
        // uStart = 192/200 = 0.96; uEnd = clamp(256/200) = 1.0.
        assertEquals(0.96, u[0], 1e-6);
        assertEquals(1.0, u[1], 1e-6);
        float[] r = PaddleResultBuilder.interpolateQuadStripFloats(q, u[0], u[1]);
        assertEquals(192f, r[0], 1e-3f);
        assertEquals(200f, r[2], 1e-3f);
    }

    @Test
    public void build_emptyTokensWithText_fallsBackToFullText() throws Exception {
        Quad q = horizontalQuad(0, 0, 50, 10);
        // Keine Tokens, aber Text: legacy 2-arg Konstruktor.
        PaddleRecOrtRunner.RecOutput out = new PaddleRecOrtRunner.RecOutput("legacy", 0.6f);
        StubRec rec = new StubRec(Collections.singletonList(out));
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(null, Collections.singletonList(q), rec, PASSTHROUGH);

        assertEquals(1, result.words.size());
        assertEquals("legacy", result.words.get(0).getText());
        assertEquals(60f, result.words.get(0).getConfidence(), 1e-3f);
    }
}
