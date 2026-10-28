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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WordSplitterTest {

    /** Hilfsmethode: Profil mit Tinte ‚H' (= cropH*255) in Bereichen, dazwischen 0. */
    private int[] mkProfile(int width, int cropH, int[]... ranges) {
        int[] p = new int[width];
        int hi = cropH * 255;
        for (int[] r : ranges) {
            for (int x = r[0]; x <= r[1]; x++) p[x] = hi;
        }
        return p;
    }

    @Test
    public void findSegments_returnsTwoSegmentsWithClearGap() {
        int cropH = 20;
        // 20px Wort, 10px Gap, 20px Wort. minGap = 0.35*20 = 7, also 10px > 7 → Split.
        int[] profile = mkProfile(50, cropH, new int[] {0, 19}, new int[] {30, 49});
        int[] segs = WordSplitter.findSegmentsForTest(profile, cropH);
        assertNotNull(segs);
        assertEquals(4, segs.length);
        assertEquals(0, segs[0]);
        assertEquals(19, segs[1]);
        assertEquals(30, segs[2]);
        assertEquals(49, segs[3]);
    }

    @Test
    public void findSegments_gapBelowMinWordGapPxIsMerged() {
        int cropH = 20;
        // Gap nur 2px (< MIN_WORD_GAP_PX=3) → Runs gemergt → < 2 Segmente → null.
        int[] profile = mkProfile(50, cropH, new int[] {0, 19}, new int[] {22, 49});
        assertNull(WordSplitter.findSegmentsForTest(profile, cropH));
    }

    @Test
    public void findSegments_returnsNullWhenSingleRun() {
        int cropH = 20;
        int[] profile = mkProfile(40, cropH, new int[] {0, 39});
        assertNull(WordSplitter.findSegmentsForTest(profile, cropH));
    }

    @Test
    public void findSegments_returnsNullWhenAllZero() {
        int cropH = 20;
        int[] profile = new int[40];
        assertNull(WordSplitter.findSegmentsForTest(profile, cropH));
    }

    @Test
    public void distributeChars_proportionalAndTotalCovered() {
        // Zwei Segmente, Breiten 10 und 30 → 1:3 Char-Verhältnis bei textLen=12.
        int[] segs = new int[] {0, 9, 50, 79};
        int[][] r = WordSplitter.distributeCharsForTest(segs, 12);
        assertEquals(2, r.length);
        // Erstes Segment ~3 Chars, zweites bis Ende.
        assertEquals(0, r[0][0]);
        assertEquals(3, r[0][1]);
        assertEquals(3, r[1][0]);
        assertEquals(12, r[1][1]);
    }

    @Test
    public void distributeChars_minOneCharPerSegment() {
        // Sehr kleines Segment darf nicht 0 Chars bekommen.
        int[] segs = new int[] {0, 0, 10, 99};
        int[][] r = WordSplitter.distributeCharsForTest(segs, 5);
        assertEquals(2, r.length);
        assertTrue("first range non-empty", r[0][1] > r[0][0]);
        assertEquals(5, r[1][1]);
    }

    // --- WordSplitter v2: Upscale-Pfad / Segmentgrenzen-Rückprojektion ---

    @Test
    public void assignCharsByPosition_placesEachCharInTheSegmentThatContainsIt() {
        // Two words: chars at x 5..25 belong to [0,30], chars at 60..80 to [50,90].
        int[] segs = {0, 30, 50, 90};
        double[] xs = {5, 12, 19, 25, 60, 70, 80};
        int[][] r = WordSplitter.assignCharsByPosition(xs, segs);
        assertArrayEquals(new int[] {0, 4}, r[0]);
        assertArrayEquals(new int[] {4, 7}, r[1]);
    }

    @Test
    public void assignCharsByPosition_doesNotShiftTheBoundaryWhenACharIsMissing() {
        // "01Lorem ipsum" read as "0Loremipsum": one char lost in the first word. Proportional
        // distribution would move the boundary ("0Loremi psum"); by position the first word gets
        // exactly the chars drawn left of the gap.
        int[] segs = {0, 180, 250, 370};
        double[] xs = {10, 50, 80, 110, 140, 170, 260, 290, 320, 350, 365};
        int[][] r = WordSplitter.assignCharsByPosition(xs, segs);
        assertArrayEquals(new int[] {0, 6}, r[0]);
        assertArrayEquals(new int[] {6, 11}, r[1]);
    }

    @Test
    public void assignCharsByPosition_charInTheGapGoesToTheNearerSegment() {
        int[] segs = {0, 30, 50, 90};
        double[] xs = {10, 33, 48, 70};
        int[][] r = WordSplitter.assignCharsByPosition(xs, segs);
        assertArrayEquals(new int[] {0, 2}, r[0]);
        assertArrayEquals(new int[] {2, 4}, r[1]);
    }

    @Test
    public void assignCharsByPosition_staysMonotonicAndCoversEveryChar() {
        // A char whose position points back to an earlier segment stays with its predecessor's
        // segment; no char is dropped and empty segments get empty ranges.
        int[] segs = {0, 30, 40, 60, 70, 100};
        double[] xs = {10, 80, 20, 90};
        int[][] r = WordSplitter.assignCharsByPosition(xs, segs);
        assertArrayEquals(new int[] {0, 1}, r[0]);
        assertArrayEquals(new int[] {1, 1}, r[1]);
        assertArrayEquals(new int[] {1, 4}, r[2]);
    }

    @Test
    public void unscaleSegments_identityWhenScaleOne() {
        int[] scaled = new int[] {0, 19, 30, 49};
        int[] back = WordSplitter.unscaleSegments(scaled, 1.0, 50);
        assertArrayEquals(scaled, back);
    }

    @Test
    public void unscaleSegments_returnsNullForNull() {
        assertNull(WordSplitter.unscaleSegments(null, 2.0, 50));
    }

    @Test
    public void unscaleSegments_correctlyDownscalesAndClamps() {
        // scaled crop 100px breit aus origW=50 (scale=2.0). Segmente {[0,19],[60,99]}
        // → original {[0,10],[30,49]} via floor/ceil.
        int[] scaled = new int[] {0, 19, 60, 99};
        int[] back = WordSplitter.unscaleSegments(scaled, 2.0, 50);
        assertArrayEquals(new int[] {0, 10, 30, 49}, back);
    }

    @Test
    public void unscaleSegments_resolvesOverlapByLifting() {
        // Beide skalierten Segmente liegen so nah, dass nach floor/ceil seg2.start <= seg1.end.
        // unscale muss seg2.start auf seg1.end+1 anheben.
        // scale=2.0, origW=20 → last=19.
        // [0,5] → [0,3]; [6,11] → floor(3),ceil(5.5)=[3,6] → Überlappung, hebe auf [4,6].
        int[] scaled = new int[] {0, 5, 6, 11};
        int[] back = WordSplitter.unscaleSegments(scaled, 2.0, 20);
        assertEquals(0, back[0]);
        assertEquals(3, back[1]);
        assertTrue("seg2 start lifted above seg1 end", back[2] > back[1]);
        assertTrue("seg2 end >= start", back[3] >= back[2]);
    }

    @Test
    public void findSegments_smallCropProfileNeedsHigherResolution() {
        // Realistischer Body-Text-Fall: cropH=18, sehr kleine Inter-Word-Gaps (2px), die nach
        // Upscale auf 48px → scale~2.67 → ~5.3px werden und damit oberhalb der Mindestschwelle
        // (max(MIN_WORD_GAP_PX=3, 0.18*48=8.64) → 9px) liegen müssten. Hier prüfen wir indirekt:
        // bei skaliertem cropH=48 muss derselbe Profile-Inhalt einen Split liefern.
        int cropH = 48;
        // 60px Wort, 12px Gap, 60px Wort (entspricht Upscale-Faktor 2.67 von 18px crop mit 4-5px gap).
        int[] profile =
                mkProfile(132, cropH, new int[] {0, 59}, new int[] {72, 131});
        int[] segs = WordSplitter.findSegmentsForTest(profile, cropH);
        assertNotNull("after upscale gap should be detectable", segs);
        assertEquals(4, segs.length);
    }

    @Test
    public void findSegments_smallCropAtNativeResolutionRejectsTinyGap() {
        // Gegenprobe ohne Upscale: cropH=18, derselbe 2px-Gap → Mindest-Gap = max(3, 0.18*18≈3.2)=4
        // → 2px-Gap unterhalb → Runs gemergt → kein Split.
        int cropH = 18;
        int[] profile = mkProfile(50, cropH, new int[] {0, 22}, new int[] {25, 49});
        assertNull("native-resolution gap too narrow", WordSplitter.findSegmentsForTest(profile, cropH));
    }

    @Test
    public void findSegments_homogeneousTextAtUpscaledHeightStillRejects() {
        // Single run im skalierten Crop → kein Split (Fallback-Verhalten bleibt erhalten).
        int cropH = 48;
        int[] profile = mkProfile(120, cropH, new int[] {0, 119});
        assertNull(WordSplitter.findSegmentsForTest(profile, cropH));
    }

    @Test
    public void findSegments_largeFontInterCharGapsNotSplit() {
        // Realistischer Headline-Fall „B ra u m a n n": großer Schriftgrad mit Inter-Char-Gaps,
        // die in Pixeln über MIN_WORD_GAP_PX und über MIN_WORD_GAP_HEIGHT_RATIO*cropH liegen,
        // aber relativ zur medianen Char-Breite klein bleiben (<0.6 * Char-Breite).
        // cropH=80 (große Schrift), 7 Glyphen mit Breite ~30px, Gaps ~8px (~0.27 * Char-Breite).
        // Höhenbasierte Schwelle: max(3, 0.18*80)=15 → 8 < 15, wird also bereits durch hardMin
        // gefiltert. Daher konstruieren wir Gaps so, dass sie *über* hardMin liegen, aber unter
        // der neuen Char-Breiten-Schwelle.
        // cropH=40 → hardMin=max(3, 0.18*40)=8. Char-Breite=30, Gap=10 → über hardMin, aber
        // 10 < 0.6*30 = 18. Mit neuer Logik darf nicht gesplittet werden.
        int cropH = 40;
        int charW = 30;
        int gap = 10;
        int n = 7;
        int width = n * charW + (n - 1) * gap;
        int[][] ranges = new int[n][];
        int x = 0;
        for (int i = 0; i < n; i++) {
            ranges[i] = new int[] {x, x + charW - 1};
            x += charW + gap;
        }
        int[] profile = mkProfile(width, cropH, ranges);
        int[] segs = WordSplitter.findSegmentsForTest(profile, cropH);
        // Mit neuer Logik (inkRunMin=18) werden alle Inter-Char-Gaps verschluckt → 1 Segment → null.
        assertNull("inter-char gaps in large font must not split", segs);
    }

    @Test
    public void findSegments_threeWordsWithTwoGaps() {
        int cropH = 20;
        int[] profile =
                mkProfile(
                        100,
                        cropH,
                        new int[] {0, 19},
                        new int[] {30, 49},
                        new int[] {60, 89});
        int[] segs = WordSplitter.findSegmentsForTest(profile, cropH);
        assertNotNull(segs);
        assertArrayEquals(new int[] {0, 19, 30, 49, 60, 89}, segs);
    }
}
