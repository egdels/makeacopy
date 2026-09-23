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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM-Unit-Tests für {@link CtcDecoder}. */
public class CtcDecoderTest {

    /** Vokabular: Index 0 = Blank, 1 = "a", 2 = "b". */
    private static final String[] VOCAB = {"<blank>", "a", "b"};

    /** Erzeugt einen einzelnen one-hot-Frame mit hohem Logit am Index {@code argmax}. */
    private static float[] frame(int argmax) {
        float[] row = new float[VOCAB.length];
        for (int c = 0; c < row.length; c++) {
            row[c] = (c == argmax) ? 10f : 0f;
        }
        return row;
    }

    @Test
    public void decode_simpleSequence_aaBlankB_yieldsAB() {
        float[][] logits = new float[][] {frame(1), frame(1), frame(0), frame(2)};
        CtcDecoder.Decoded d = CtcDecoder.decode(logits, VOCAB);
        assertEquals("ab", d.text());
        assertArrayEquals(new int[] {1, 2}, d.tokenIndices());
        // Mittlere Konfidenz wird nur über akzeptierte Frames gemittelt; bei one-hot
        // logits ~ 1.0.
        assertTrue("meanConf=" + d.meanConfidence(), d.meanConfidence() > 0.99f);
    }

    @Test
    public void decode_recordsTheFrameOfEveryEmittedChar() {
        // Frames: a a _ b  -> "ab"; the a is emitted at frame 0 (frame 1 is a repeat), b at 3.
        float[][] logits = {
            {0.1f, 0.9f, 0.0f}, {0.1f, 0.9f, 0.0f}, {0.9f, 0.05f, 0.05f}, {0.1f, 0.0f, 0.9f}
        };
        CtcDecoder.Decoded d = CtcDecoder.decode(logits, VOCAB);
        assertEquals("ab", d.text());
        assertArrayEquals(new int[] {0, 3}, d.charFrames());
    }

    @Test
    public void decode_multiCharVocabEntry_repeatsItsFrame() {
        String[] vocab = {"", "ab", "c"};
        float[][] logits = {{0.1f, 0.9f, 0.0f}, {0.9f, 0.0f, 0.1f}, {0.1f, 0.0f, 0.9f}};
        CtcDecoder.Decoded d = CtcDecoder.decode(logits, vocab);
        assertEquals("abc", d.text());
        assertArrayEquals(new int[] {0, 0, 2}, d.charFrames());
    }

    @Test
    public void decode_allBlanks_yieldsEmptyAndZeroConfidence() {
        float[][] logits = new float[][] {frame(0), frame(0), frame(0)};
        CtcDecoder.Decoded d = CtcDecoder.decode(logits, VOCAB);
        assertEquals("", d.text());
        assertEquals(0, d.tokenIndices().length);
        assertEquals(0f, d.meanConfidence(), 0f);
    }

    @Test
    public void decode_meanConfidence_onlyOverAcceptedFrames() {
        // Frame 0: hohes "a" (sehr sicher) -> akzeptiert.
        // Frame 1: Blank mit niedrigem Vertrauen -> NICHT in den Mittelwert einbezogen.
        // Frame 2: weiches "b". Werte als Logits, rowSum != 1 und max > 1, damit die
        // Auto-Detektion (probability vs. logits) eindeutig logits-Pfad nimmt.
        float[] f0 = new float[] {-10f, 10f, -10f}; // a, sehr sicher
        float[] f1 = new float[] {0.5f, 0.4f, 0.4f}; // blank, knapp (Logit-Pfad: rowSum != 1)
        float[] f2 = new float[] {0f, 0f, 2.0f}; // b, max>1 -> klar logits
        float[][] logits = new float[][] {f0, f1, f2};

        CtcDecoder.Decoded d = CtcDecoder.decode(logits, VOCAB);
        assertEquals("ab", d.text());
        // Mittel über Frame 0 und Frame 2 (Blank-Frame ausgeschlossen).
        // Konfidenz Frame 0 ≈ 1.0, Frame 2 = softmax([0,0,2])[2].
        float expected = (1.0f + softmaxMax(f2)) / 2.0f;
        assertEquals(expected, d.meanConfidence(), 1e-3f);
    }

    @Test
    public void decode_repeatsCollapsedAcrossBlank() {
        // "a, blank, a" -> "aa" (CTC-Standard).
        float[][] logits = new float[][] {frame(1), frame(0), frame(1)};
        CtcDecoder.Decoded d = CtcDecoder.decode(logits, VOCAB);
        assertEquals("aa", d.text());
    }

    @Test
    public void decode_emptyLogits_returnsEmpty() {
        CtcDecoder.Decoded d = CtcDecoder.decode(new float[0][0], VOCAB);
        assertEquals("", d.text());
        assertEquals(0f, d.meanConfidence(), 0f);
    }

    private static float softmaxMax(float[] row) {
        float max = row[0];
        for (float v : row) if (v > max) max = v;
        double sum = 0;
        for (float v : row) sum += Math.exp(v - max);
        return (float) (1.0 / sum);
    }

    // ---------------------------------------------------------------------
    // Sub-Word-Tokens / Frame-Ranges (§3 Konzept).
    // ---------------------------------------------------------------------

    /** Vokabular mit Whitespace-Eintrag bei Index 3. */
    private static final String[] VOCAB_WS = {"<blank>", "a", "b", " ", "c"};

    private static float[] frameWs(int argmax) {
        float[] row = new float[VOCAB_WS.length];
        for (int c = 0; c < row.length; c++) {
            row[c] = (c == argmax) ? 10f : 0f;
        }
        return row;
    }

    @Test
    public void decode_tokensSplitOnWhitespace_andCarryFrameRanges() {
        // Frames: 0=a, 1=b, 2=blank, 3=' ', 4=c, 5=blank, 6=' ', 7=a
        // Erwartet: drei Worte "ab" [0..1], "c" [4..4], "a" [7..7]; T=8.
        float[][] logits = new float[][] {
                frameWs(1), frameWs(2), frameWs(0), frameWs(3),
                frameWs(4), frameWs(0), frameWs(3), frameWs(1)
        };
        CtcDecoder.Decoded d = CtcDecoder.decode(logits, VOCAB_WS);
        assertEquals("ab c a", d.text());
        assertEquals(8, d.frameCount());
        assertEquals(3, d.tokens().size());

        CtcDecoder.RecognizedToken t0 = d.tokens().get(0);
        assertEquals("ab", t0.text());
        assertEquals(0, t0.frameStart());
        assertEquals(1, t0.frameEnd());
        assertTrue("conf=" + t0.confidence(), t0.confidence() > 0.99f);

        CtcDecoder.RecognizedToken t1 = d.tokens().get(1);
        assertEquals("c", t1.text());
        assertEquals(4, t1.frameStart());
        assertEquals(4, t1.frameEnd());

        CtcDecoder.RecognizedToken t2 = d.tokens().get(2);
        assertEquals("a", t2.text());
        assertEquals(7, t2.frameStart());
        assertEquals(7, t2.frameEnd());
    }

    @Test
    public void decode_singleWordToken_frameRangeSpansAllAcceptedFrames() {
        float[][] logits = new float[][] {frameWs(1), frameWs(2), frameWs(0), frameWs(4)};
        CtcDecoder.Decoded d = CtcDecoder.decode(logits, VOCAB_WS);
        assertEquals("abc", d.text());
        assertEquals(1, d.tokens().size());
        CtcDecoder.RecognizedToken t = d.tokens().get(0);
        assertEquals("abc", t.text());
        assertEquals(0, t.frameStart());
        assertEquals(3, t.frameEnd());
    }

    @Test
    public void decode_leadingAndTrailingWhitespace_doesNotCreateEmptyTokens() {
        // Frames: ' ', a, ' '
        float[][] logits = new float[][] {frameWs(3), frameWs(1), frameWs(3)};
        CtcDecoder.Decoded d = CtcDecoder.decode(logits, VOCAB_WS);
        assertEquals(" a ", d.text());
        assertEquals(1, d.tokens().size());
        assertEquals("a", d.tokens().get(0).text());
        assertEquals(1, d.tokens().get(0).frameStart());
        assertEquals(1, d.tokens().get(0).frameEnd());
    }

    @Test
    public void decode_emptyLogits_emptyTokensAndZeroFrameCount() {
        CtcDecoder.Decoded d = CtcDecoder.decode(new float[0][0], VOCAB_WS);
        assertEquals(0, d.tokens().size());
        assertEquals(0, d.frameCount());
    }

    /**
     * Reproduziert die Issue-Sequenz {@code [1,1,0,2,2,437,3]}: Wiederholungen werden gemerged,
     * Blank (0) entfernt, das Space-Token an Klasse 437 muss als echtes " " im Text erscheinen
     * (nicht als leerer String — dann wäre wer kaputt). Das schützt die {@link RecDictLoader}-
     * Konvention „letztes Token = ' ', kein trim".
     */
    @Test
    public void decode_spaceClassAtEndOfVocab_preservesSpaceInOutput() {
        // Vocab mit Space am letzten Index (analog en_PP-OCRv5: V=438, space_class_idx=437).
        // Hier kompakter: V=4, Indizes [blank, "a", "b", " "].
        String[] v = {"", "a", "b", " "};
        // Sequenz [1,1,0,2,2,3,1] → erwartet "ab a"
        float[][] logits = new float[][] {
                oneHot(1, v.length), oneHot(1, v.length),
                oneHot(0, v.length), oneHot(2, v.length),
                oneHot(2, v.length), oneHot(3, v.length),
                oneHot(1, v.length)
        };
        CtcDecoder.Decoded d = CtcDecoder.decode(logits, v);
        assertEquals("ab a", d.text());
        // Space darf nicht via internen .trim() entfernt werden, auch nicht zwischen Worten.
        assertTrue(d.text().contains(" "));
        // Sub-Word-Splitting an Whitespace ergibt zwei Worte.
        assertEquals(2, d.tokens().size());
        assertEquals("ab", d.tokens().get(0).text());
        assertEquals("a", d.tokens().get(1).text());
    }

    private static float[] oneHot(int argmax, int n) {
        float[] r = new float[n];
        for (int c = 0; c < n; c++) r[c] = (c == argmax) ? 10f : 0f;
        return r;
    }
}
