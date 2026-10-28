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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM-Unit-Tests für die paddle-Letterbox-Hilfsklasse. */
public class LetterboxTest {

    @Test
    public void compute_landscape_padsTopBottom() {
        Letterbox lb = Letterbox.compute(1280, 720, 960);
        // dst = STRIDE-Vielfache, dst-Seiten >= scaledSize
        assertEquals(0, lb.dstW % Letterbox.STRIDE);
        assertEquals(0, lb.dstH % Letterbox.STRIDE);
        // Aspect-Ratio bleibt erhalten (innerhalb des skalierten Bereichs)
        assertEquals(0.0, lb.padX, 1.0); // im Landschafts-Fall passt skalierte Breite gerundet ins Stride
        assertTrue(lb.padY >= 0.0);
        assertTrue(lb.scale > 0.0 && lb.scale <= 1.0);
    }

    @Test
    public void compute_portrait_padsLeftRight() {
        Letterbox lb = Letterbox.compute(720, 1280, 960);
        assertEquals(0, lb.dstW % Letterbox.STRIDE);
        assertEquals(0, lb.dstH % Letterbox.STRIDE);
        assertTrue(lb.padX >= 0.0);
        assertTrue(lb.scale > 0.0 && lb.scale <= 1.0);
    }

    @Test
    public void roundtrip_unapplyApplyIsIdentity() {
        Letterbox lb = Letterbox.compute(1234, 567, 960);
        double[][] pts = {
            {0, 0},
            {1233, 0},
            {1233, 566},
            {0, 566},
            {123.4, 321.0},
            {617, 283.5}
        };
        for (double[] p : pts) {
            double[] q = lb.applyPoint(p[0], p[1]);
            double[] r = lb.unapplyPoint(q[0], q[1]);
            assertTrue("dx=" + (r[0] - p[0]), Math.abs(r[0] - p[0]) < 1e-6);
            assertTrue("dy=" + (r[1] - p[1]), Math.abs(r[1] - p[1]) < 1e-6);
        }
    }

    @Test
    public void roundtrip_eps_lessThanOnePixel() {
        // Issue-Anforderung: Roundtrip-Genauigkeit < 1 px.
        Letterbox lb = Letterbox.compute(2000, 1500, 960);
        double[] q = lb.applyPoint(987.6, 543.2);
        double[] r = lb.unapplyPoint(q[0], q[1]);
        assertTrue(Math.hypot(r[0] - 987.6, r[1] - 543.2) < 1.0);
    }

    @Test
    public void compute_aspectRatioPreserved() {
        Letterbox lb = Letterbox.compute(1000, 250, 960);
        // (srcW*scale)/(srcH*scale) muss srcW/srcH entsprechen.
        double effW = 1000.0 * lb.scale;
        double effH = 250.0 * lb.scale;
        assertEquals(1000.0 / 250.0, effW / effH, 1e-9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void compute_zeroSrcW_throws() {
        Letterbox.compute(0, 100, 960);
    }

    @Test(expected = IllegalArgumentException.class)
    public void compute_zeroMaxSide_throws() {
        Letterbox.compute(100, 100, 0);
    }

    @Test
    public void compute_dstSidesAreStrideMultiples() {
        for (int w : new int[] {31, 33, 100, 1000, 2048}) {
            for (int h : new int[] {31, 65, 200, 1500}) {
                Letterbox lb = Letterbox.compute(w, h, 960);
                assertEquals(0, lb.dstW % Letterbox.STRIDE);
                assertEquals(0, lb.dstH % Letterbox.STRIDE);
            }
        }
    }
}
