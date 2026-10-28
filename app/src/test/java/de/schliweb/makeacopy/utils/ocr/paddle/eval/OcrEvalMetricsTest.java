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
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import org.junit.Test;

/**
 * Handgerechnete Tests für CER/WER in {@link OcrEvalRunner} (Konzept §9).
 *
 * <p>Wirkt direkt gegen die paketprivaten {@code cer}/{@code wer}-Methoden via Reflection,
 * um die Implementierung gegen Edge-Cases (leere Strings, identische Strings, reine
 * Insertions/Deletions, Unicode-Diakritika, Surrogate-Pairs) zu härten.
 */
public class OcrEvalMetricsTest {

    private static double cer(String gt, String pred) throws Exception {
        Method m = OcrEvalRunner.class.getDeclaredMethod("cer", String.class, String.class);
        m.setAccessible(true);
        return (double) m.invoke(null, gt, pred);
    }

    private static double wer(String gt, String pred) throws Exception {
        Method m = OcrEvalRunner.class.getDeclaredMethod("wer", String.class, String.class);
        m.setAccessible(true);
        return (double) m.invoke(null, gt, pred);
    }

    @Test
    public void cer_bothEmpty_isZero() throws Exception {
        assertEquals(0.0, cer("", ""), 0.0);
        assertEquals(0.0, cer(null, null), 0.0);
    }

    @Test
    public void cer_emptyGtNonEmptyPred_isOne() throws Exception {
        assertEquals(1.0, cer("", "abc"), 0.0);
    }

    @Test
    public void cer_identical_isZero() throws Exception {
        assertEquals(0.0, cer("hello world", "hello world"), 0.0);
    }

    @Test
    public void cer_pureInsertion_countsInsertedChars() throws Exception {
        // gt="hi", pred="hia" → 1 Insertion / 2 = 0.5
        assertEquals(0.5, cer("hi", "hia"), 1e-9);
    }

    @Test
    public void cer_pureDeletion_countsDeletedChars() throws Exception {
        // gt="cats", pred="cat" → 1 Deletion / 4 = 0.25
        assertEquals(0.25, cer("cats", "cat"), 1e-9);
    }

    @Test
    public void cer_pureSubstitution_countsSubstChars() throws Exception {
        // gt="abc", pred="abd" → 1 / 3
        assertEquals(1.0 / 3.0, cer("abc", "abd"), 1e-9);
    }

    @Test
    public void cer_unicodeComposedDiacritic_singleCodepoint() throws Exception {
        // "café" als NFC (mit U+00E9) hat 4 Codepoints; ein Substitutions-Fehler in 'é':
        // pred "cafe" → 1 Substitution / 4 = 0.25.
        String gt = "caf\u00e9";
        String pred = "cafe";
        assertEquals(4, gt.codePointCount(0, gt.length()));
        assertEquals(0.25, cer(gt, pred), 1e-9);
    }

    @Test
    public void cer_surrogatePair_countedAsSingleCodepoint() throws Exception {
        // U+1F600 GRINNING FACE: 2 chars, 1 codepoint.
        String gt = "a\uD83D\uDE00b"; // "a😀b"
        assertEquals(3, gt.codePointCount(0, gt.length()));
        // Identisch -> 0.
        assertEquals(0.0, cer(gt, gt), 0.0);
        // Pred ohne Emoji -> 1 Deletion in 3 → 1/3.
        assertEquals(1.0 / 3.0, cer(gt, "ab"), 1e-9);
    }

    @Test
    public void wer_bothEmpty_isZero() throws Exception {
        assertEquals(0.0, wer("", ""), 0.0);
    }

    @Test
    public void wer_identical_isZero() throws Exception {
        assertEquals(0.0, wer("foo bar baz", "foo bar baz"), 0.0);
    }

    @Test
    public void wer_oneSubstitution_outOfThree() throws Exception {
        assertEquals(1.0 / 3.0, wer("foo bar baz", "foo bar quux"), 1e-9);
    }

    @Test
    public void wer_insertionInPred_countsAsOne() throws Exception {
        // gt="a b" (2 Tokens), pred="a x b" (3 Tokens) → 1 Insertion / 2.
        assertEquals(0.5, wer("a b", "a x b"), 1e-9);
    }

    @Test
    public void wer_unicodeWhitespace_splitsCorrectly() throws Exception {
        // NBSP zwischen Tokens.
        String gt = "foo\u00A0bar";
        assertEquals(0.0, wer(gt, "foo bar"), 1e-9);
        assertEquals(0.5, wer(gt, "foo baz"), 1e-9);
    }

    @Test
    public void wer_emptyPred_yieldsOne() throws Exception {
        assertTrue(wer("alpha beta", "") >= 1.0 - 1e-9);
    }
}
