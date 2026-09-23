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
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** JVM-Unit-Tests für {@link DbPostProcessor}. */
public class DbPostProcessorTest {

    private static float[][] makeProb(int w, int h) {
        return new float[h][w];
    }

    private static void fillRect(float[][] prob, int x0, int y0, int w, int h, float v) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                prob[y][x] = v;
            }
        }
    }

    @Test
    public void twoSeparateRectangles_yieldExactlyTwoQuads() {
        // Probmap mit zwei klar getrennten 8x4 Rechtecken (mehr als 16 Pixel Lücke).
        // Verwende Default-Schwellwerte (db=0.3, box=0.6) -> Probabilities = 0.9
        // unclipRatio=1.0 für deterministische Bbox-Prüfung.
        DbPostProcessor pp = new DbPostProcessor(0.3, 0.6, 1.0);
        float[][] prob = makeProb(64, 32);
        fillRect(prob, 4, 4, 10, 6, 0.9f); // Rect A
        fillRect(prob, 40, 20, 12, 8, 0.9f); // Rect B

        List<Quad> quads = pp.process(prob);
        assertEquals("Exakt zwei Komponenten", 2, quads.size());

        // Beide Quads liegen innerhalb der Eingabegrenzen.
        for (Quad q : quads) {
            assertTrue(q.minX() >= 0);
            assertTrue(q.minY() >= 0);
            assertTrue(q.maxX() <= 64);
            assertTrue(q.maxY() <= 32);
        }

        // Mindestens ein Quad enthält das Zentrum von A bzw. B.
        boolean hitA = false, hitB = false;
        for (Quad q : quads) {
            if (containsPoint(q, 9.0, 7.0)) hitA = true;
            if (containsPoint(q, 46.0, 24.0)) hitB = true;
        }
        assertTrue("Quad enthält Zentrum von Rect A", hitA);
        assertTrue("Quad enthält Zentrum von Rect B", hitB);
    }

    @Test
    public void allBelowBoxThresh_yieldsEmptyList() {
        // Probabilities knapp über db_thresh, aber unter box_thresh -> Komponente wird verworfen.
        DbPostProcessor pp = new DbPostProcessor(0.3, 0.6, 1.6);
        float[][] prob = makeProb(32, 16);
        fillRect(prob, 4, 4, 10, 6, 0.4f);

        List<Quad> quads = pp.process(prob);
        assertTrue("Erwartet leere Liste, war: " + quads.size(), quads.isEmpty());
    }

    @Test
    public void emptyProbMap_returnsEmpty() {
        DbPostProcessor pp = new DbPostProcessor();
        assertTrue(pp.process(new float[16][32]).isEmpty());
    }

    @Test
    public void quadCornersInTlTrBrBlOrder() {
        DbPostProcessor pp = new DbPostProcessor(0.3, 0.6, 1.0);
        float[][] prob = makeProb(32, 16);
        fillRect(prob, 4, 4, 10, 6, 0.9f);
        List<Quad> quads = pp.process(prob);
        assertEquals(1, quads.size());
        Quad q = quads.get(0);
        // TL.x <= TR.x, BL.x <= BR.x; TL.y <= BL.y, TR.y <= BR.y
        assertTrue(q.x[0] <= q.x[1]);
        assertTrue(q.x[3] <= q.x[2]);
        assertTrue(q.y[0] <= q.y[3]);
        assertTrue(q.y[1] <= q.y[2]);
    }

    private static boolean containsPoint(Quad q, double px, double py) {
        return px >= q.minX() && px <= q.maxX() && py >= q.minY() && py <= q.maxY();
    }

    @Test
    public void flatFilledKernel_isKeptLikeInTheReference() {
        // Der DB-Kern einer langen Blocksatzzeile: 366×9 bei Letterbox 1536 (Aspect ≈ 41),
        // vollständig gefüllt. Der frühere Streifenfilter hat genau solche Komponenten als
        // Farbbalken verworfen und damit ganze Textzeilen verloren (GitHub #87); die Referenz
        // filtert nur nach Score und Mindestgröße.
        DbPostProcessor pp = new DbPostProcessor(0.3, 0.6, 1.0);
        float[][] prob = makeProb(400, 32);
        fillRect(prob, 20, 12, 366, 9, 0.9f);

        List<Quad> quads = pp.process(prob);
        assertEquals("flacher Textzeilen-Kern bleibt erhalten", 1, quads.size());
    }

    @Test
    public void veryFlatWideComponent_isKeptLikeInTheReference() {
        // 1500×12 mit homogener Probability (früher als „CMYK-Farbbalken" verworfen). Die
        // Referenz kennt keinen solchen Filter; eine seitenbreite Textzeile sieht identisch aus.
        DbPostProcessor pp = new DbPostProcessor(0.3, 0.6, 1.0);
        float[][] prob = makeProb(1600, 32);
        fillRect(prob, 50, 10, 1500, 12, 0.9f);

        List<Quad> quads = pp.process(prob);
        assertEquals("keine Aspect-/Varianz-Filterung mehr", 1, quads.size());
    }

    @Test
    public void normalTextLine_isKept() {
        DbPostProcessor pp = new DbPostProcessor(0.3, 0.6, 1.0);
        float[][] prob = makeProb(400, 64);
        fillRect(prob, 50, 16, 300, 30, 0.9f);

        List<Quad> quads = pp.process(prob);
        assertEquals("Normale Textzeile bleibt erhalten", 1, quads.size());
    }

    @Test
    public void boxHygiene_dropsComponentsBelowMinAreaOrMinSide() {
        // minArea=20, minSide=3: 4×4 (16 px) ist zu klein, 10×2 zu flach, 5×4 (20 px) bleibt.
        DbPostProcessor pp = new DbPostProcessor(0.3, 0.6, 1.0, 20, 3);
        float[][] prob = makeProb(64, 32);
        fillRect(prob, 2, 2, 4, 4, 0.9f);
        fillRect(prob, 20, 2, 10, 2, 0.9f);
        fillRect(prob, 40, 20, 5, 4, 0.9f);

        List<Quad> quads = pp.process(prob);
        assertEquals(1, quads.size());
        assertTrue(containsPoint(quads.get(0), 42.0, 22.0));
    }

    @Test
    public void connectivity_isFourNeighbourhood() {
        // Zwei Rechtecke, die sich nur diagonal an einer Ecke berühren, sind ZWEI Komponenten;
        // ein L aus zwei Rechtecken mit gemeinsamer Kante ist EINE.
        DbPostProcessor pp = new DbPostProcessor(0.3, 0.6, 1.0, 1, 1);
        float[][] diagonal = makeProb(32, 32);
        fillRect(diagonal, 2, 2, 4, 4, 0.9f);
        fillRect(diagonal, 6, 6, 4, 4, 0.9f);
        assertEquals(2, pp.process(diagonal).size());

        float[][] lShape = makeProb(32, 32);
        fillRect(lShape, 2, 2, 4, 10, 0.9f);
        fillRect(lShape, 6, 8, 8, 4, 0.9f);
        List<Quad> quads = pp.process(lShape);
        assertEquals(1, quads.size());
        // Bounding-Box des ganzen L: x 2..14, y 2..12
        assertEquals(2.0, quads.get(0).minX(), 1e-9);
        assertEquals(14.0, quads.get(0).maxX(), 1e-9);
        assertEquals(2.0, quads.get(0).minY(), 1e-9);
        assertEquals(12.0, quads.get(0).maxY(), 1e-9);
    }

    @Test
    public void unclip_growsTheBoxByAreaTimesRatioOverPerimeter() {
        // 20×10-Box, ratio 1.5: D = 200 * 0.5 / 60 = 1.666…
        DbPostProcessor pp = new DbPostProcessor(0.3, 0.6, 1.5, 1, 1);
        float[][] prob = makeProb(64, 64);
        fillRect(prob, 10, 20, 20, 10, 0.9f);

        List<Quad> quads = pp.process(prob);
        assertEquals(1, quads.size());
        double d = 200.0 * 0.5 / 60.0;
        assertEquals(10.0 - d, quads.get(0).minX(), 1e-9);
        assertEquals(30.0 + d, quads.get(0).maxX(), 1e-9);
        assertEquals(20.0 - d, quads.get(0).minY(), 1e-9);
        assertEquals(30.0 + d, quads.get(0).maxY(), 1e-9);
    }
}
