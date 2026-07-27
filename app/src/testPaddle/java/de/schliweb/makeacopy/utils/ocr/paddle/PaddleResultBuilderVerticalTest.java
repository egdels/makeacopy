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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * JVM-Unit-Tests für die Vertikaltext-Logik in {@link PaddleResultBuilder}: Hochkant-Erkennung
 * pro Quad, Vertikallayout-Erkennung pro Seite, Spalten-Gruppierung (Leserichtung rechts→links,
 * innerhalb der Spalte top-to-bottom) sowie die Kandidatenauswahl der Crop-Rotation.
 * Bitmap-abhängige Pfade (Crop-Rotation selbst) werden hier bewusst nicht ausgeführt
 * ({@code unitTests.returnDefaultValues=true}).
 */
public class PaddleResultBuilderVerticalTest {

    private static Quad rect(double x, double y, double w, double h) {
        return new Quad(
                new double[] {x, x + w, x + w, x},
                new double[] {y, y, y + h, y + h},
                0.95);
    }

    private static Quad rectQuad(double x, double y, double w, double h, double id) {
        return new Quad(
                new double[] {x, x + w, x + w, x},
                new double[] {y, y, y + h, y + h},
                id);
    }

    /** Recognizer, der pro Quad einen RecOutput liefert, indiziert über {@code Quad.score}. */
    private static final class IdRec extends PaddleRecOrtRunner {
        private final Map<Double, PaddleRecOrtRunner.RecOutput> byId;
        final List<Double> callOrder = new ArrayList<>();
        // Letztes Quad, das zum Crop reichte – über Cropper-Hook gesetzt.
        Quad lastQuad;

        IdRec(Map<Double, PaddleRecOrtRunner.RecOutput> byId) {
            super();
            this.byId = byId;
        }

        @Override
        PaddleRecOrtRunner.RecOutput recognize(Bitmap quadCrop) {
            double id = lastQuad.score;
            callOrder.add(id);
            return byId.get(id);
        }
    }

    private static PaddleRecOrtRunner.RecOutput textOnly(String text) {
        return new PaddleRecOrtRunner.RecOutput(text, 0.8f);
    }

    /** Führt build(...) seriell (full=null) mit einem ID-basierten Fake-Recognizer aus. */
    private static OCRHelper.OcrResultWords buildWithTexts(
            List<Quad> quads, IdRec rec)
            throws Exception {
        PaddleResultBuilder.Cropper hook =
                (full, quad) -> {
                    rec.lastQuad = quad;
                    return null;
                };
        return PaddleResultBuilder.build(null, quads, rec, hook);
    }

    // ---------------------------------------------------------------------
    // isTallQuad
    // ---------------------------------------------------------------------

    @Test
    public void isTallQuad_verticalColumn_isTall() {
        // Typische vertikale CJK-Spalte: 30 px breit, 400 px hoch.
        assertTrue(PaddleResultBuilder.isTallQuad(rect(0, 0, 30, 400)));
    }

    @Test
    public void isTallQuad_horizontalLine_isNotTall() {
        assertFalse(PaddleResultBuilder.isTallQuad(rect(0, 0, 400, 30)));
    }

    @Test
    public void isTallQuad_thresholdExactlyOnRatio_isTall() {
        // h/w == 1.5 → per Definition (>=) hochkant.
        assertTrue(PaddleResultBuilder.isTallQuad(rect(0, 0, 20, 30)));
    }

    @Test
    public void isTallQuad_justBelowRatio_isNotTall() {
        assertFalse(PaddleResultBuilder.isTallQuad(rect(0, 0, 20, 29)));
    }

    // ---------------------------------------------------------------------
    // isVerticalLayout
    // ---------------------------------------------------------------------

    @Test
    public void isVerticalLayout_allColumns_true() {
        List<Quad> qs =
                Arrays.asList(
                        rect(300, 0, 30, 400),
                        rect(200, 0, 30, 380),
                        rect(100, 0, 30, 420));
        assertTrue(PaddleResultBuilder.isVerticalLayout(qs));
    }

    @Test
    public void isVerticalLayout_horizontalDocument_false() {
        List<Quad> qs =
                Arrays.asList(
                        rect(0, 0, 400, 30),
                        rect(0, 50, 380, 30),
                        rect(0, 100, 420, 30));
        assertFalse(PaddleResultBuilder.isVerticalLayout(qs));
    }

    @Test
    public void isVerticalLayout_singleTallQuad_false() {
        // Ein einzelner schmaler Treffer (z. B. eine Ziffer) darf das Layout nicht kippen.
        assertFalse(
                PaddleResultBuilder.isVerticalLayout(
                        Collections.singletonList(rect(0, 0, 30, 400))));
    }

    @Test
    public void isVerticalLayout_mixedMajorityHorizontal_false() {
        // 1 von 3 hochkant → keine Dominanz.
        List<Quad> qs =
                Arrays.asList(
                        rect(0, 0, 400, 30),
                        rect(0, 50, 380, 30),
                        rect(500, 0, 30, 400));
        assertFalse(PaddleResultBuilder.isVerticalLayout(qs));
    }

    @Test
    public void isVerticalLayout_nullOrEmpty_false() {
        assertFalse(PaddleResultBuilder.isVerticalLayout(null));
        assertFalse(PaddleResultBuilder.isVerticalLayout(Collections.emptyList()));
    }

    // ---------------------------------------------------------------------
    // groupQuadsIntoColumns
    // ---------------------------------------------------------------------

    @Test
    public void groupQuadsIntoColumns_columnsOrderedRightToLeft() {
        // Drei Spalten: rechts (x=300), Mitte (x=200), links (x=100). Die Leserichtung
        // vertikaler CJK-Dokumente ist rechts→links.
        Quad right = rect(300, 0, 30, 400);
        Quad middle = rect(200, 0, 30, 400);
        Quad left = rect(100, 0, 30, 400);
        // Absichtlich unsortierte Eingabe.
        List<List<Quad>> cols =
                PaddleResultBuilder.groupQuadsIntoColumns(Arrays.asList(left, right, middle));

        assertEquals(3, cols.size());
        assertSame(right, cols.get(0).get(0));
        assertSame(middle, cols.get(1).get(0));
        assertSame(left, cols.get(2).get(0));
    }

    @Test
    public void groupQuadsIntoColumns_withinColumnOrderedTopToBottom() {
        // Eine Spalte, die vom Detektor in zwei Segmente zerlegt wurde.
        Quad top = rect(300, 0, 30, 180);
        Quad bottom = rect(300, 220, 30, 180);
        List<List<Quad>> cols =
                PaddleResultBuilder.groupQuadsIntoColumns(Arrays.asList(bottom, top));

        assertEquals(1, cols.size());
        assertEquals(2, cols.get(0).size());
        assertSame(top, cols.get(0).get(0));
        assertSame(bottom, cols.get(0).get(1));
    }

    @Test
    public void groupQuadsIntoColumns_slightXJitterStaysInSameColumn() {
        // Segmente derselben Spalte mit leichtem horizontalem Versatz (< Toleranz
        // = LINE_TOLERANCE_FACTOR × mediane Breite) müssen zusammen bleiben.
        Quad a = rect(300, 0, 30, 180);
        Quad b = rect(305, 220, 30, 180); // 5 px Jitter, Toleranz = 0.6*30 = 18 px
        List<List<Quad>> cols = PaddleResultBuilder.groupQuadsIntoColumns(Arrays.asList(b, a));

        assertEquals(1, cols.size());
        assertSame(a, cols.get(0).get(0));
        assertSame(b, cols.get(0).get(1));
    }

    @Test
    public void groupQuadsIntoColumns_farApartQuadsSplitIntoColumns() {
        Quad a = rect(300, 0, 30, 400);
        Quad b = rect(100, 0, 30, 400); // weit links → eigene Spalte
        List<List<Quad>> cols = PaddleResultBuilder.groupQuadsIntoColumns(Arrays.asList(a, b));

        assertEquals(2, cols.size());
        assertSame(a, cols.get(0).get(0));
        assertSame(b, cols.get(1).get(0));
    }

    // ---------------------------------------------------------------------
    // build(...) — Regressionstests für die vertikale Leserichtung des Endtexts
    // ---------------------------------------------------------------------

    @Test
    public void build_threeVerticalColumns_finalTextReadsRightToLeft() throws Exception {
        // Räumliche Anordnung: rechte Spalte (x=300) = Satz 1, mittlere (x=200) = Satz 2,
        // linke (x=100) = Satz 3. Traditionelles japanisches Vertikallayout liest die
        // Spalten rechts→links; der Test schlägt fehl, wenn links→rechts ausgegeben wird.
        Quad right = rectQuad(300, 0, 30, 400, 1.0);
        Quad middle = rectQuad(200, 0, 30, 400, 2.0);
        Quad left = rectQuad(100, 0, 30, 400, 3.0);

        Map<Double, PaddleRecOrtRunner.RecOutput> byId = new HashMap<>();
        byId.put(1.0, textOnly("Satz 1"));
        byId.put(2.0, textOnly("Satz 2"));
        byId.put(3.0, textOnly("Satz 3"));

        // Absichtlich unsortierte Det-Reihenfolge (links, rechts, mitte).
        IdRec rec = new IdRec(byId);
        OCRHelper.OcrResultWords result =
                buildWithTexts(Arrays.asList(left, right, middle), rec);

        assertEquals("Satz 1\nSatz 2\nSatz 3", result.text);
        // Recognition-Reihenfolge == Spaltenreihenfolge (rechts→links) — keine spätere
        // Umkehrung beim Aufbau des Gesamttexts (keine doppelte Umkehrung möglich).
        assertEquals(Arrays.asList(1.0, 2.0, 3.0), rec.callOrder);
    }

    @Test
    public void build_segmentsWithinColumn_topToBottomWithoutSpaces() throws Exception {
        // Eine Spalte, zwei Segmente: oben vor unten, direkt konkateniert (kein Space).
        Quad rightTop = rectQuad(300, 0, 30, 180, 1.0);
        Quad rightBottom = rectQuad(300, 220, 30, 180, 2.0);
        Quad leftFull = rectQuad(100, 0, 30, 400, 3.0);

        Map<Double, PaddleRecOrtRunner.RecOutput> byId = new HashMap<>();
        byId.put(1.0, textOnly("すると、"));
        byId.put(2.0, textOnly("1億円を"));
        byId.put(3.0, textOnly("受け取った"));

        IdRec rec = new IdRec(byId);
        OCRHelper.OcrResultWords result =
                buildWithTexts(Arrays.asList(rightBottom, leftFull, rightTop), rec);

        assertEquals("すると、1億円を\n受け取った", result.text);
    }

    @Test
    public void build_singleVerticalColumnSplitInSegments_topToBottom() throws Exception {
        Quad top = rectQuad(300, 0, 30, 180, 1.0);
        Quad bottom = rectQuad(300, 220, 30, 180, 2.0);

        Map<Double, PaddleRecOrtRunner.RecOutput> byId = new HashMap<>();
        byId.put(1.0, textOnly("oben"));
        byId.put(2.0, textOnly("unten"));

        IdRec rec = new IdRec(byId);
        OCRHelper.OcrResultWords result =
                buildWithTexts(Arrays.asList(bottom, top), rec);

        assertEquals("obenunten", result.text);
    }

    @Test
    public void build_nearEqualXWithinColumn_staysOneColumnTopToBottom() throws Exception {
        // Leichter X-Jitter (< Toleranz) darf keine zweite Spalte erzeugen und die
        // Reihenfolge oben→unten nicht ändern.
        Quad a = rectQuad(300, 0, 30, 180, 1.0);
        Quad b = rectQuad(305, 220, 30, 180, 2.0);

        Map<Double, PaddleRecOrtRunner.RecOutput> byId = new HashMap<>();
        byId.put(1.0, textOnly("A"));
        byId.put(2.0, textOnly("B"));

        IdRec rec = new IdRec(byId);
        OCRHelper.OcrResultWords result = buildWithTexts(Arrays.asList(b, a), rec);

        assertEquals("AB", result.text);
    }

    @Test
    public void build_horizontalLines_readingOrderUnchanged() throws Exception {
        // Horizontale Zeilen (breite Quads) dürfen durch die Vertikal-Korrektur nicht
        // umgekehrt werden: links→rechts mit Spaces, Zeilen mit Newline.
        Quad line1Left = rectQuad(10, 10, 80, 20, 1.0);
        Quad line1Right = rectQuad(110, 10, 80, 20, 2.0);
        Quad line2 = rectQuad(10, 60, 180, 20, 3.0);

        Map<Double, PaddleRecOrtRunner.RecOutput> byId = new HashMap<>();
        byId.put(1.0, textOnly("Hello"));
        byId.put(2.0, textOnly("World"));
        byId.put(3.0, textOnly("again"));

        IdRec rec = new IdRec(byId);
        OCRHelper.OcrResultWords result =
                buildWithTexts(Arrays.asList(line2, line1Right, line1Left), rec);

        assertEquals("Hello World\nagain", result.text);
    }

    // ---------------------------------------------------------------------
    // chooseRotationCandidate (0 = unrotiert, 1 = 90° CCW, 2 = 90° CW)
    // ---------------------------------------------------------------------

    @Test
    public void chooseRotationCandidate_rotationWithHigherConfidenceWins() {
        // Vertikale Spalte: unrotiert liefert wenig/mit niedriger Konfidenz, CCW gewinnt.
        int chosen =
                PaddleResultBuilder.chooseRotationCandidate(
                        new String[] {"す", "すると、1億円を", "を円億1、とるす"},
                        new float[] {0.35f, 0.93f, 0.55f});
        assertEquals(1, chosen);
    }

    @Test
    public void chooseRotationCandidate_nonEmptyBeatsEmpty() {
        // Nur die CW-Rotation liefert Text → sie gewinnt trotz niedriger Konfidenz.
        int chosen =
                PaddleResultBuilder.chooseRotationCandidate(
                        new String[] {"", null, "テキスト"},
                        new float[] {0.9f, 0.95f, 0.4f});
        assertEquals(2, chosen);
    }

    @Test
    public void chooseRotationCandidate_tieKeepsUnrotated() {
        // Regressionsschutz: Bei Gleichstand bleibt der unrotierte Crop erhalten
        // (z. B. schmale Einzelzeichen in horizontalen Dokumenten).
        int chosen =
                PaddleResultBuilder.chooseRotationCandidate(
                        new String[] {"1", "一", "一"},
                        new float[] {0.9f, 0.9f, 0.9f});
        assertEquals(0, chosen);
    }

    @Test
    public void chooseRotationCandidate_tieOnConfidenceLongerTextWins() {
        int chosen =
                PaddleResultBuilder.chooseRotationCandidate(
                        new String[] {"す", "すると、1億円を", "すると"},
                        new float[] {0.9f, 0.9f, 0.9f});
        assertEquals(1, chosen);
    }

    @Test
    public void chooseRotationCandidate_allEmpty_keepsUnrotated() {
        int chosen =
                PaddleResultBuilder.chooseRotationCandidate(
                        new String[] {"", "", ""}, new float[] {0f, 0f, 0f});
        assertEquals(0, chosen);
    }
}
