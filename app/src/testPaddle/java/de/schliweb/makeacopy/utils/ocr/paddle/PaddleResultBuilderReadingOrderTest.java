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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Reading-Order-Tests für {@link PaddleResultBuilder} (Schritt 10a).
 *
 * <p>Stub-Det liefert Quads in absichtlich vermischter Reihenfolge (Zeile 2 vor Zeile 1, rechte
 * Spalte vor linker). Erwartet wird, dass {@code build(...)} die Quads zeilenweise (Center-Y mit
 * Toleranz × medianer Quad-Höhe) gruppiert und innerhalb der Zeile nach Center-X sortiert. Plain-
 * Text wird mit {@code \n} zwischen Zeilen und single-space innerhalb der Zeile zusammengesetzt.
 *
 * <p>Da der JVM-Stub-Recognizer in den Tests nur eine FIFO-Liste liefert, wird die Reihenfolge
 * der Recognizer-Aufrufe (= effektive Builder-Reihenfolge) über eine ID-→-Output-Map abgebildet:
 * jedes Quad bekommt eine eindeutige ID via {@code score}, der Recognizer matcht.
 */
public class PaddleResultBuilderReadingOrderTest {

    private static Quad rectQuad(double x, double y, double w, double h, double id) {
        return new Quad(
                new double[] {x, x + w, x + w, x},
                new double[] {y, y, y + h, y + h},
                id);
    }

    /** Recognizer, der pro Quad einen RecOutput liefert, indiziert über {@code Quad.score}. */
    private static final class IdRec extends PaddleRecOrtRunner {
        private final Map<Double, PaddleRecOrtRunner.RecOutput> byId;
        private final List<Double> callOrder = new ArrayList<>();
        private final List<Bitmap> capturedCrops = new ArrayList<>();
        // Letztes Quad, das zum Crop reichte – über Cropper-Hook gesetzt.
        Quad lastQuad;

        IdRec(Map<Double, PaddleRecOrtRunner.RecOutput> byId) {
            super();
            this.byId = byId;
        }

        @Override
        PaddleRecOrtRunner.RecOutput recognize(Bitmap quadCrop) {
            // Aufrufreihenfolge gemerkt vom Cropper-Hook (siehe build()).
            double id = lastQuad.score;
            callOrder.add(id);
            capturedCrops.add(quadCrop);
            return byId.get(id);
        }
    }

    private static PaddleRecOrtRunner.RecOutput textOnly(String text) {
        // Legacy 2-arg: keine Sub-Word-Geometrie → Builder verwendet ganze Det-Box als BBox.
        return new PaddleRecOrtRunner.RecOutput(text, 0.8f);
    }

    @Test
    public void build_twoLinesShuffled_emitsLinesInReadingOrderWithNewline() throws Exception {
        // Zeile 1 (y≈10): "Hello", "World"
        // Zeile 2 (y≈60): "foo", "bar", "baz"
        Quad q1Left = rectQuad(10, 10, 80, 20, 1.0);   // "Hello"
        Quad q1Right = rectQuad(110, 10, 80, 20, 2.0); // "World"
        Quad q2A = rectQuad(10, 60, 50, 20, 3.0);      // "foo"
        Quad q2B = rectQuad(70, 60, 50, 20, 4.0);      // "bar"
        Quad q2C = rectQuad(130, 60, 50, 20, 5.0);     // "baz"

        Map<Double, PaddleRecOrtRunner.RecOutput> byId = new HashMap<>();
        byId.put(1.0, textOnly("Hello"));
        byId.put(2.0, textOnly("World"));
        byId.put(3.0, textOnly("foo"));
        byId.put(4.0, textOnly("bar"));
        byId.put(5.0, textOnly("baz"));

        // Vermischte Det-Reihenfolge: Zeile 2 (rechts→links), dann Zeile 1 (rechts, links).
        List<Quad> shuffled = Arrays.asList(q2C, q2A, q2B, q1Right, q1Left);

        IdRec rec = new IdRec(byId);
        PaddleResultBuilder.Cropper hook = (full, quad) -> {
            rec.lastQuad = quad;
            return null;
        };
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(null, shuffled, rec, hook);

        assertNotNull(result);
        assertEquals("Hello World\nfoo bar baz", result.text);

        // Aufrufreihenfolge spiegelt Reading-Order wider.
        assertEquals(Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0), rec.callOrder);
    }

    @Test
    public void build_singleQuad_emitsOnlyTextNoSeparators() throws Exception {
        Quad only = rectQuad(0, 0, 100, 20, 1.0);
        Map<Double, PaddleRecOrtRunner.RecOutput> byId = new HashMap<>();
        byId.put(1.0, textOnly("solo"));

        IdRec rec = new IdRec(byId);
        PaddleResultBuilder.Cropper hook = (full, quad) -> {
            rec.lastQuad = quad;
            return null;
        };
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(null, Arrays.asList(only), rec, hook);

        assertEquals("solo", result.text);
    }

    @Test
    public void build_identicalCenterY_tieBreakByCenterX() throws Exception {
        // Drei Quads mit *identischem* Center-Y, jeweils unterschiedliche X.
        Quad qA = rectQuad(200, 0, 50, 20, 1.0); // mitte
        Quad qB = rectQuad(0, 0, 50, 20, 2.0);   // links
        Quad qC = rectQuad(400, 0, 50, 20, 3.0); // rechts

        Map<Double, PaddleRecOrtRunner.RecOutput> byId = new HashMap<>();
        byId.put(1.0, textOnly("B"));
        byId.put(2.0, textOnly("A"));
        byId.put(3.0, textOnly("C"));

        IdRec rec = new IdRec(byId);
        PaddleResultBuilder.Cropper hook = (full, quad) -> {
            rec.lastQuad = quad;
            return null;
        };
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(null, Arrays.asList(qA, qB, qC), rec, hook);

        // Erwartet: links → mitte → rechts in einer Zeile.
        assertEquals("A B C", result.text);
    }

    @Test
    public void build_skewedQuads_groupedByCenterYWithinTolerance() throws Exception {
        // Zwei leicht schräge Quads in derselben Zeile (Center-Y differiert minimal),
        // ein klar darunter liegendes Quad als Zeile 2.
        // Quad A: TL(0,0) TR(100,4) BR(100,24) BL(0,20) → centerY ≈ 12, height ≈ 22
        Quad qA = new Quad(new double[] {0, 100, 100, 0}, new double[] {0, 4, 24, 20}, 1.0);
        // Quad B: rechts daneben, leichter Versatz: centerY ≈ 14
        Quad qB = new Quad(
                new double[] {110, 210, 210, 110},
                new double[] {2, 6, 26, 22},
                2.0);
        // Quad C: deutlich tiefer, centerY ≈ 60
        Quad qC = rectQuad(0, 50, 100, 20, 3.0);

        Map<Double, PaddleRecOrtRunner.RecOutput> byId = new HashMap<>();
        byId.put(1.0, textOnly("alpha"));
        byId.put(2.0, textOnly("beta"));
        byId.put(3.0, textOnly("gamma"));

        IdRec rec = new IdRec(byId);
        PaddleResultBuilder.Cropper hook = (full, quad) -> {
            rec.lastQuad = quad;
            return null;
        };
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(null, Arrays.asList(qC, qB, qA), rec, hook);

        assertEquals("alpha beta\ngamma", result.text);
    }

    @Test
    public void build_rtlLine_emitsTextInLogicalReadingOrder() throws Exception {
        // Drei Quads in derselben Zeile (Center-Y identisch), erkannt als arabische
        // Wörter. Paddle liefert pro Crop die Glyphen in **visueller** Reihenfolge
        // (links→rechts auf dem Bild) — bei RTL-Schrift ist das die Spiegelung der
        // logischen Codepoint-Reihenfolge. Der Builder muss daher (a) die Quads in
        // umgekehrter Reihenfolge zusammensetzen und (b) jeden Snippet per
        // Codepoints umdrehen.
        Quad qLeft = rectQuad(10, 10, 80, 20, 1.0);
        Quad qMid = rectQuad(110, 10, 80, 20, 2.0);
        Quad qRight = rectQuad(210, 10, 80, 20, 3.0);

        // Logisch korrekt:
        //   rechtes Quad → "مستند"  (logisch erstes Wort)
        //   mittleres   → "اختبار"
        //   linkes      → "العربية" (logisch drittes Wort)
        String aRightLogical = "مستند";
        String aMidLogical = "اختبار";
        String aLeftLogical = "العربية";

        // Was Paddle Rec tatsächlich emittiert: pro Crop in visueller Glyphenreihenfolge,
        // d.h. die logische Codepoint-Sequenz wird gespiegelt zurückgegeben.
        String aRightVisual = PaddleResultBuilder.reverseByCodepoints(aRightLogical);
        String aMidVisual = PaddleResultBuilder.reverseByCodepoints(aMidLogical);
        String aLeftVisual = PaddleResultBuilder.reverseByCodepoints(aLeftLogical);

        Map<Double, PaddleRecOrtRunner.RecOutput> byId = new HashMap<>();
        byId.put(1.0, textOnly(aLeftVisual));
        byId.put(2.0, textOnly(aMidVisual));
        byId.put(3.0, textOnly(aRightVisual));

        IdRec rec = new IdRec(byId);
        PaddleResultBuilder.Cropper hook = (full, quad) -> {
            rec.lastQuad = quad;
            return null;
        };
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(
                        null, Arrays.asList(qLeft, qMid, qRight), rec, hook);

        assertNotNull(result);
        // Erwartet: logische Reihenfolge "rechts → mitte → links" UND jedes Wort
        // per Codepoints zurückgedreht (also wieder logisch korrekt lesbar).
        assertEquals(aRightLogical + " " + aMidLogical + " " + aLeftLogical, result.text);

        // Word-basierte Consumers (OCRReview Layout-/Textansicht, PDF-Textlayer)
        // bekommen dieselben logischen Token-Texte. Regression: früher war nur
        // result.text korrigiert, während die RecognizedWord-Texte gespiegelt blieben.
        assertEquals(aLeftLogical, result.words.get(0).getText());
        assertEquals(aMidLogical, result.words.get(1).getText());
        assertEquals(aRightLogical, result.words.get(2).getText());

        // Recognition wird in visueller Reihenfolge aufgerufen (links→rechts);
        // die Reihenfolge-Drehung und der Codepoint-Reverse passieren erst beim
        // Aufbau des Text-Strings.
        assertEquals(Arrays.asList(1.0, 2.0, 3.0), rec.callOrder);
    }

    @Test
    public void reverseByCodepoints_isSurrogateSafe_andSelfInverse() {
        // Surrogate-Paar (U+1F600 GRINNING FACE) zwischen ASCII.
        String orig = "a\uD83D\uDE00b";
        String reversed = PaddleResultBuilder.reverseByCodepoints(orig);
        assertEquals("b\uD83D\uDE00a", reversed);
        // Self-inverse.
        assertEquals(orig, PaddleResultBuilder.reverseByCodepoints(reversed));
        // Edge cases.
        assertEquals("", PaddleResultBuilder.reverseByCodepoints(""));
        assertEquals("x", PaddleResultBuilder.reverseByCodepoints("x"));
    }

    @Test
    public void build_rtlSingleQuadWithMultipleWords_skipsWordSplitter_andReversesText() throws Exception {
        // Regression: bei einem einzelnen Det-Quad, das eine ganze RTL-Zeile mit mehreren
        // logischen Wörtern umfasst, würde WordSplitter den rec-Text linear nach Pixel-
        // Spalten in feste Substring-Bereiche schneiden — bei RTL kappt das aber Codepoints
        // mitten in Wörtern und erzeugt Buchstabensalat (zerrissene End-/Mittel-Formen).
        // Daher wird WordSplitter bei RTL-Crops übersprungen; das gesamte Quad liefert ein
        // Snippet, das per reverseByCodepoints in logische Reihenfolge gedreht wird.
        Quad qLine = rectQuad(0, 0, 600, 30, 42.0);

        // Logisch: "مستند اختبار العربية" — was Paddle visuell L→R emittiert ist die
        // Codepoint-Spiegelung (mit Spaces an den visuellen Wortgrenzen).
        String logical = "مستند اختبار العربية";
        String visualFromRec = PaddleResultBuilder.reverseByCodepoints(logical);

        Map<Double, PaddleRecOrtRunner.RecOutput> byId = new HashMap<>();
        byId.put(42.0, textOnly(visualFromRec));

        IdRec rec = new IdRec(byId);
        // Cropper liefert null — WordSplitter würde damit gar nicht erst funktionieren.
        // Der Test stellt sicher, dass der RTL-Skip greift, BEVOR WordSplitter mit null
        // aufgerufen wird; sonst gäbe es einen NPE oder ein null-Result.
        PaddleResultBuilder.Cropper hook = (full, quad) -> {
            rec.lastQuad = quad;
            return null;
        };
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(
                        null, java.util.Collections.singletonList(qLine), rec, hook);

        assertNotNull(result);
        // Logisch korrekte Reihenfolge ohne kaputt geschnittene Wörter.
        assertEquals(logical, result.text);
        assertEquals(logical, result.words.get(0).getText());
    }

    @Test
    public void isRtlText_codepointHeuristic_majorityWins() {
        assertTrue(PaddleResultBuilder.isRtlText("مستند"));
        assertTrue(PaddleResultBuilder.isRtlText("שלום"));
        assertTrue(!PaddleResultBuilder.isRtlText("Hello World"));
        // Gemischt: arabisch dominiert über LTR-Whitespace+ASCII-Komma.
        assertTrue(PaddleResultBuilder.isRtlText("مستند, foo"));
        // LTR-Mehrheit: ASCII-Buchstaben überwiegen einzelnes RTL-Zeichen.
        assertTrue(!PaddleResultBuilder.isRtlText("Hello شي"));
        assertTrue(!PaddleResultBuilder.isRtlText(""));
        assertTrue(!PaddleResultBuilder.isRtlText(null));
    }

    @Test
    public void visualToLogical_rtlBase_keepsEmbeddedNumbersAndLatin() {
        // Logisch "التاريخ: 25/12/2025" — visuell steht die Zahl links, das arabische Wort
        // rechts in gespiegelter Glyphenreihenfolge.
        String visual = "25/12/2025 :" + PaddleResultBuilder.reverseByCodepoints("التاريخ");
        assertEquals("التاريخ: 25/12/2025", PaddleResultBuilder.visualToLogical(visual, true));

        String visualLatin =
                ".MakeACopy " + PaddleResultBuilder.reverseByCodepoints("هذا مستند");
        assertEquals("هذا مستند MakeACopy.", PaddleResultBuilder.visualToLogical(visualLatin, true));

        // Arabisch-indische Ziffern werden ebenfalls links→rechts gelesen.
        String visualDigits = "٠١٢٣ :" + PaddleResultBuilder.reverseByCodepoints("أرقام");
        assertEquals("أرقام: ٠١٢٣", PaddleResultBuilder.visualToLogical(visualDigits, true));

        // Rein arabisch: identisch zur vollen Spiegelung.
        String logical = "مستند اختبار العربية";
        String visualPure = PaddleResultBuilder.reverseByCodepoints(logical);
        assertEquals(logical, PaddleResultBuilder.visualToLogical(visualPure, true));
    }

    @Test
    public void visualToLogical_ltrBase_flipsOnlyTheArabicRun() {
        // Regression: in "- رابط: https://example.com/makeacopy" überwiegt Latein, die Zeile
        // gilt als LTR; das arabische Wort kam trotzdem in visueller Reihenfolge von Paddle
        // und muss für sich gedreht werden ("طبار" → "رابط").
        String visual =
                "https://example.com/makeacopy :" + PaddleResultBuilder.reverseByCodepoints("رابط") + " -";
        assertEquals(
                "https://example.com/makeacopy :رابط -",
                PaddleResultBuilder.visualToLogical(visual, false));

        // Mehrere arabische Wörter in einem Lauf bleiben als Lauf zusammen.
        String twoWords = "Hello " + PaddleResultBuilder.reverseByCodepoints("مستند اختبار") + " World";
        assertEquals(
                "Hello مستند اختبار World", PaddleResultBuilder.visualToLogical(twoWords, false));

        // Ohne RTL-Zeichen: unverändert.
        assertEquals("Hello World", PaddleResultBuilder.visualToLogical("Hello World", false));
        assertEquals("x", PaddleResultBuilder.visualToLogical("x", false));
        assertEquals(null, PaddleResultBuilder.visualToLogical(null, true));
    }

    @Test
    public void build_arabicWordInLatinMajorityLine_isNotMirrored() throws Exception {
        // Zeile "- رابط: https://example.com/makeacopy": Quad A (links) hält die URL, Quad B
        // (rechts) das arabische Wort in visueller Glyphenreihenfolge.
        Quad qUrl = rectQuad(0, 0, 300, 20, 1.0);
        Quad qWord = rectQuad(320, 0, 60, 20, 2.0);

        Map<Double, PaddleRecOrtRunner.RecOutput> byId = new HashMap<>();
        byId.put(1.0, textOnly("https://example.com/makeacopy"));
        byId.put(2.0, textOnly(PaddleResultBuilder.reverseByCodepoints("رابط")));

        IdRec rec = new IdRec(byId);
        PaddleResultBuilder.Cropper hook = (full, quad) -> {
            rec.lastQuad = quad;
            return null;
        };
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(null, Arrays.asList(qWord, qUrl), rec, hook);

        assertEquals("https://example.com/makeacopy رابط", result.text);
        assertEquals(2, result.words.size());
        assertTrue(result.words.stream().anyMatch(w -> "رابط".equals(w.getText())));
    }

    @Test
    public void build_ltrLineUnchanged_byRtlHeuristic() throws Exception {
        // Sicherstellen, dass die RTL-Heuristik LTR-Zeilen nicht aus Versehen kippt.
        Quad qA = rectQuad(0, 0, 50, 20, 1.0);
        Quad qB = rectQuad(60, 0, 50, 20, 2.0);
        Quad qC = rectQuad(120, 0, 50, 20, 3.0);

        Map<Double, PaddleRecOrtRunner.RecOutput> byId = new HashMap<>();
        byId.put(1.0, textOnly("Hello"));
        byId.put(2.0, textOnly("nice"));
        byId.put(3.0, textOnly("World"));

        IdRec rec = new IdRec(byId);
        PaddleResultBuilder.Cropper hook = (full, quad) -> {
            rec.lastQuad = quad;
            return null;
        };
        OCRHelper.OcrResultWords result =
                PaddleResultBuilder.build(
                        null, Arrays.asList(qA, qB, qC), rec, hook);

        assertEquals("Hello nice World", result.text);
    }

    @Test
    public void groupQuadsIntoLines_directlyGroupsAndOrders() {
        // Direkter Test der Helfer-Methode.
        Quad q1Left = rectQuad(10, 10, 80, 20, 1.0);
        Quad q1Right = rectQuad(110, 10, 80, 20, 2.0);
        Quad q2A = rectQuad(10, 60, 50, 20, 3.0);
        Quad q2B = rectQuad(70, 60, 50, 20, 4.0);
        List<Quad> shuffled = Arrays.asList(q2B, q1Right, q2A, q1Left);

        List<List<Quad>> lines = PaddleResultBuilder.groupQuadsIntoLines(shuffled);
        assertEquals(2, lines.size());
        assertEquals(2, lines.get(0).size());
        assertEquals(2, lines.get(1).size());
        // Zeile 1: q1Left (id=1.0), q1Right (id=2.0)
        assertEquals(1.0, lines.get(0).get(0).score, 1e-9);
        assertEquals(2.0, lines.get(0).get(1).score, 1e-9);
        // Zeile 2: q2A (id=3.0), q2B (id=4.0)
        assertEquals(3.0, lines.get(1).get(0).score, 1e-9);
        assertEquals(4.0, lines.get(1).get(1).score, 1e-9);
    }

    @Test
    public void groupQuadsIntoLines_singleQuad_returnsSingleLine() {
        Quad only = rectQuad(0, 0, 50, 10, 1.0);
        List<List<Quad>> lines =
                PaddleResultBuilder.groupQuadsIntoLines(Arrays.asList(only));
        assertEquals(1, lines.size());
        assertEquals(1, lines.get(0).size());
        assertTrue(lines.get(0).get(0) == only);
    }
}
