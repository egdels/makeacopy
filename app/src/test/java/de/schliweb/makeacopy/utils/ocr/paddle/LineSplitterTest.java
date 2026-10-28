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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * JVM-Unit-Tests für {@link LineSplitter}. Da {@code android.graphics.Bitmap} im JVM nicht
 * verfügbar ist (Default-Stubs via {@code unitTests.returnDefaultValues=true}), werden hier nur
 * die deterministischen Steuerpfade abgedeckt: Median-Berechnung, „kein Kandidat" → Identität,
 * sowie der Fallback bei fehlgeschlagenem Crop ({@code null}).
 */
public class LineSplitterTest {

    private static Quad rect(double x, double y, double w, double h) {
        return new Quad(
                new double[] {x, x + w, x + w, x},
                new double[] {y, y, y + h, y + h},
                0.95);
    }

    @Test
    public void medianHeight_oddCount_returnsMiddleValue() {
        List<Quad> qs =
                Arrays.asList(rect(0, 0, 10, 4), rect(0, 0, 10, 8), rect(0, 0, 10, 12));
        assertEquals(8.0, LineSplitter.medianHeight(qs), 1e-9);
    }

    @Test
    public void splitTallQuads_emptyOrNull_returnsSameRef() {
        List<Quad> empty = Collections.emptyList();
        assertSame(empty, LineSplitter.splitTallQuads(null, empty, (f, q) -> null));
        assertSame(null, LineSplitter.splitTallQuads(null, null, (f, q) -> null));
    }

    @Test
    public void splitTallQuads_noTallQuad_returnsSameListIdentity() {
        // Alle Quads ähnlich groß → kein Splitkandidat → Identität.
        List<Quad> qs =
                Arrays.asList(
                        rect(0, 0, 50, 12),
                        rect(0, 20, 50, 13),
                        rect(0, 40, 50, 11));
        List<Quad> out = LineSplitter.splitTallQuads(null, qs, (f, q) -> null);
        assertSame(qs, out);
    }

    @Test
    public void splitWideQuads_noWideQuad_returnsSameListIdentity() {
        List<Quad> qs =
                Arrays.asList(
                        rect(0, 0, 100, 20),
                        rect(0, 30, 120, 20),
                        rect(0, 60, 80, 20));
        assertSame(qs, LineSplitter.splitWideQuads(qs));
    }

    @Test
    public void splitWideQuads_veryWideQuad_splitsIntoOrderedStrips() {
        Quad wide = rect(0, 0, 800, 20);
        List<Quad> out = LineSplitter.splitWideQuads(Collections.singletonList(wide));

        assertEquals(3, out.size());
        assertEquals(0.0, out.get(0).minX(), 1e-9);
        assertEquals(800.0 / 3.0, out.get(0).maxX(), 1e-9);
        assertEquals(800.0 / 3.0, out.get(1).minX(), 1e-9);
        assertEquals(1600.0 / 3.0, out.get(1).maxX(), 1e-9);
        assertEquals(1600.0 / 3.0, out.get(2).minX(), 1e-9);
        assertEquals(800.0, out.get(2).maxX(), 1e-9);
    }

    @Test
    public void splitTallQuads_tallQuadButNullCrop_keepsOriginal() {
        // Ein deutlich höherer Quad → Splitkandidat. Cropper liefert null → Fallback auf
        // Original-Quad. Liste muss unverändert in Größe und Inhalt bleiben.
        Quad small1 = rect(0, 0, 50, 10);
        Quad small2 = rect(0, 20, 50, 10);
        Quad tall = rect(0, 40, 50, 80); // 8x median → Splitkandidat
        List<Quad> qs = Arrays.asList(small1, small2, tall);
        List<Quad> out = LineSplitter.splitTallQuads(null, qs, (f, q) -> null);
        assertEquals(3, out.size());
        assertSame(small1, out.get(0));
        assertSame(small2, out.get(1));
        assertSame(tall, out.get(2));
    }

    @Test
    public void splitOneQuad_nullOrTinyCrop_returnsNull() {
        Quad q = rect(0, 0, 50, 80);
        assertTrue(LineSplitter.splitOneQuad(q, null, 10.0) == null);
    }

    @Test
    public void splitTallQuads_throwingCropper_keepsOriginal() {
        Quad small = rect(0, 0, 50, 10);
        Quad tall = rect(0, 20, 50, 80);
        List<Quad> qs = Arrays.asList(small, tall);
        LineSplitter.Cropper bad =
                (f, qq) -> {
                    throw new RuntimeException("crop failed");
                };
        List<Quad> out = LineSplitter.splitTallQuads(null, qs, bad);
        assertEquals(2, out.size());
        assertSame(small, out.get(0));
        assertSame(tall, out.get(1));
    }
}
