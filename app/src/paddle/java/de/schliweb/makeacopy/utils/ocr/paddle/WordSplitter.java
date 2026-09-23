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

import android.graphics.Bitmap;
import android.graphics.RectF;
import androidx.annotation.VisibleForTesting;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The WordSplitter class provides methods to distribute recognized text into segments
 * detected from projection profiles. It includes utilities for ink profile computation,
 * segment finding, and segment scaling. This class is primarily used for text segmentation
 * within scanned images or crops.
 */
final class WordSplitter {

    /**
     * The minimum ink ratio used to classify gaps in the text segmentation process.
     *
     * <ul>
     * <li>Columns with an ink level below {@code GAP_INK_RATIO * h * 255} are treated as gaps
     *     during the segmentation of text into distinct words or segments.</li>
     * <li>Used in conjunction with other thresholds to manage the identification of meaningful
     *     text regions and ignore insignificant gaps or noise.</li>
     * <li>Adjusting this value influences the sensitivity of gap detection and, consequently,
     *     the segmentation results.</li>
     * </ul>
     */
    @VisibleForTesting static final double GAP_INK_RATIO = 0.04;

    /**
     * Factor used to scale the median word-gap value while analyzing word segments
     * in the text detection process.
     *
     * This constant modifies the sensitivity of the gap detection logic, where the
     * gap between words is determined based on the comparison of ink profiles
     * and projection data. A lower factor value will result in stricter gap
     * detection (smaller gaps classified as word boundaries), while a higher value
     * will be more permissive (larger gaps may be required to separate words).
     *
     * It is primarily utilized in methods that process word segmentation and
     * refine split decisions based on projection profiling or similar heuristics.
     *
     * Value constraints:
     * - Must be a positive number to ensure logical segmentation processing.
     */
    @VisibleForTesting static final double WORD_GAP_MEDIAN_FACTOR = 1.8;

    /**
     * Defines the minimum pixel gap between words within a text segment, used to
     * distinguish and separate words during text splitting.
     *
     * This constant is primarily utilized in methods that analyze and process
     * projection profiles to identify and segment text based on pixel ink density.
     *
     * Factors like scaling and median-based gap adjustments may influence
     * this threshold during implementation-specific calculations in related methods.
     */
    @VisibleForTesting static final int MIN_WORD_GAP_PX = 3;

    /**
     * The factor used to determine the minimum ink run ratio required to classify a gap
     * between words during text segmentation. This factor is applied to the observed
     * ink distribution in column profiles of a given bitmap. A lower value increases
     * sensitivity to smaller gaps, while a higher value reduces sensitivity, potentially
     * ignoring narrower spaces.
     *
     * <ul>
     * <li>Used as part of the {@code findSegments} method to identify word boundaries
     * based on ink gaps in the projection profile.</li>
     * <li>Provides a threshold below which gaps are considered insufficient
     * to justify segmentation into distinct words.</li>
     * <li>Primarily utilized in testing and debugging to assess segmentation accuracy.</li>
     * </ul>
     */
    @VisibleForTesting static final double MIN_WORD_GAP_INK_RUN_FACTOR = 0.6;

    /**
     * Defines the minimum height ratio of gaps between words in a text segment that can
     * be considered valid for word splitting operations.
     *
     * This ratio is used as a threshold to distinguish gaps between words from
     * intra-word spacing. A gap's height, relative to the text segment's height,
     * must be at least this ratio for the gap to be identified as a separator between words.
     *
     * A lower value makes the splitting process more permissive, potentially identifying
     * smaller gaps as valid word separators, while a higher value makes the process more
     * restrictive, requiring larger gaps to separate words.
     */
    @VisibleForTesting static final double MIN_WORD_GAP_HEIGHT_RATIO = 0.18;

    /**
     * Defines the minimum relative height ratio for text segments within an ink profile.
     *
     * <ul>
     * <li>Segments smaller than this ratio, relative to the crop height, are merged with neighboring segments
     * to ensure sufficiently large segments for further processing.</li>
     * <li>This value is used in algorithms like {@code findSegments} to filter out insignificant segments and
     * improve robustness in text segmentation.</li>
     * <li>Set to {@code 0.25}, representing 25% of the crop height as the threshold for segment size.</li>
     * </ul>
     *
     * This constant is specifically marked {@code @VisibleForTesting} to allow controlled verification of its behavior
     * in test scenarios.
     */
    @VisibleForTesting static final double MIN_SEG_RATIO = 0.25;

    /**
     * Defines the target height, in pixels, for upscaling operations within the OCR
     * segmentation and processing logic. This constant is used as a reference
     * during image resizing to ensure consistent processing scale for segmentation
     * and analysis.
     *
     * <ul>
     * <li>Primarily intended for testing scenarios where deterministic scaling is
     * required.</li>
     * <li>Allows for more predictable results and segment alignment during text
     * recognition and processing steps.</li>
     * <li>Associated with the internal upscaling pipeline of the OCR algorithm.</li>
     * </ul>
     */
    @VisibleForTesting static final int UPSCALE_TARGET_HEIGHT = 48;

    /**
     * Specifies the minimum crop height in pixels required to trigger the upscaling process
     * during text segmentation. If the crop height is less than this value, no upscaling
     * will be applied.
     *
     * <ul>
     * <li>Used in conjunction with {@code UPSCALE_TARGET_HEIGHT} to determine if the input
     *    requires scaling for text segmentation accuracy.</li>
     * <li>Defined as {@code static final} to ensure immutability and consistent usage
     *    across the class.</li>
     * <li>Accessible for testing purposes, as indicated by the {@code @VisibleForTesting}
     *    annotation.</li>
     * </ul>
     */
    @VisibleForTesting static final int UPSCALE_HEIGHT_TRIGGER = 0;

    /**
     * Defines the maximum allowable scale factor for upscaling operations.
     *
     * <ul>
     * <li>Limits the degree to which an image or crop can be enlarged to prevent
     * excessive scaling and potential degradation in quality.</li>
     * <li>Used in operations such as rerendering or upscaling word segments within
     * the {@code WordSplitter} class.</li>
     * <li>A value of {@code 3.0} indicates that an element can be enlarged up to
     * three times its original size.</li>
     * <li>This constant is primarily designated for testing purposes to ensure consistent
     * behavior during scaling-related computations.</li>
     * </ul>
     */
    @VisibleForTesting static final double UPSCALE_MAX_SCALE = 3.0;

    private WordSplitter() {}

    /**
     * Splits a given word or text contained in an image crop into smaller recognized segments.
     * The method analyzes the structure of the crop, distributes the text across detected segments,
     * and returns a list of recognized words with corresponding bounding boxes.
     *
     * @param q         The quadrilateral representing the crop region.
     * @param crop      The cropped bitmap image of the word or text to analyze.
     *                  Must not be null and should have sufficient resolution.
     * @param text      The textual content represented within the crop.
     *                  Must not be null or empty after trimming.
     * @param conf100   The confidence score of the recognition in the range [0, 100].
     * @return          A list of {@link RecognizedWord} objects if successful, or null if the
     *                  provided crop, text, or detected segments are insufficient for splitting.
     */
    static List<RecognizedWord> split(Quad q, Bitmap crop, String text, float conf100) {
        return split(q, crop, text, conf100, null);
    }

    /**
     * Splits the crop into word boxes at the visible gaps and assigns the recognised characters
     * to them by their CTC frame position when {@code out} carries one per character; without
     * positions the characters are distributed proportionally to the segment widths (the old
     * behaviour, which shifts every boundary as soon as the recogniser drops a character).
     */
    static List<RecognizedWord> split(
            Quad q, Bitmap crop, String text, float conf100, PaddleRecOrtRunner.RecOutput out) {
        if (crop == null || text == null) return null;
        String trimmed = text.trim();
        if (trimmed.length() < 2) return null;
        // Wenn der Text bereits Spaces enthält, ist nichts zu tun (sehr seltener Fall).
        if (trimmed.indexOf(' ') >= 0) return null;

        int w = crop.getWidth();
        int h = crop.getHeight();
        if (w < 8 || h < 4) return null;

        // v2: Bei kleinen Crops (Body-Text ~17-20px) Profile auf hochskalierter Crop-Version
        // berechnen, weil sonst Inter-Char- und Inter-Word-Gaps pixelmäßig nicht trennbar sind.
        Bitmap analysisCrop = crop;
        double scale = 1.0;
        if (h < UPSCALE_HEIGHT_TRIGGER) {
            double s = (double) UPSCALE_TARGET_HEIGHT / h;
            if (s > UPSCALE_MAX_SCALE) s = UPSCALE_MAX_SCALE;
            int newH = (int) Math.round(h * s);
            int newW = Math.max(8, (int) Math.round(w * s));
            try {
                Bitmap scaled = Bitmap.createScaledBitmap(crop, newW, newH, true);
                if (scaled != null) {
                    analysisCrop = scaled;
                    scale = s;
                }
            } catch (Throwable ignored) {
                // Fallback auf Original-Crop, wenn createScaledBitmap fehlschlägt.
            }
        }

        int[] profile = computeInkProfile(analysisCrop);
        int analysisH = analysisCrop.getHeight();
        int[] scaledSegments = findSegments(profile, analysisH);
        if (scaledSegments == null || scaledSegments.length < 4) {
            // weniger als 2 Segmente → kein Split.
            return null;
        }
        // Segmentgrenzen zurück auf Original-Crop-Koordinaten projizieren.
        int[] segments = unscaleSegments(scaledSegments, scale, w);
        int segCount = segments.length / 2;
        if (segCount < 2) return null;

        // Gesamtbreite aller Segmente in Pixeln, als Bezugsgröße für die Char-Verteilung.
        int totalSegW = 0;
        for (int i = 0; i < segCount; i++) {
            totalSegW += (segments[2 * i + 1] - segments[2 * i] + 1);
        }
        if (totalSegW <= 0) return null;

        int n = trimmed.length();
        int[] charStarts = new int[segCount];
        int[] charEnds = new int[segCount];
        double[] charX = charPositions(text, trimmed, out, w);
        if (charX != null) {
            int[][] ranges = assignCharsByPosition(charX, segments);
            for (int i = 0; i < segCount; i++) {
                charStarts[i] = ranges[i][0];
                charEnds[i] = ranges[i][1];
            }
        } else {
            // Fallback ohne Positionsdaten: String-Anteile proportional zur Segment-Breite
            // verteilen. Verteilung über Char-Indices, damit kein Zeichen verloren geht.
            int cursor = 0;
            int seenW = 0;
            for (int i = 0; i < segCount; i++) {
                int segW = segments[2 * i + 1] - segments[2 * i] + 1;
                seenW += segW;
                int targetEnd =
                        (i == segCount - 1) ? n : (int) Math.round((double) seenW * n / totalSegW);
                if (targetEnd <= cursor) targetEnd = Math.min(n, cursor + 1);
                if (targetEnd > n) targetEnd = n;
                charStarts[i] = cursor;
                charEnds[i] = targetEnd;
                cursor = targetEnd;
            }
            // Falls Rundung Reste übrig lässt, an letztes Segment anhängen.
            if (charEnds[segCount - 1] < n) charEnds[segCount - 1] = n;
        }

        List<RecognizedWord> result = new ArrayList<>(segCount);
        for (int i = 0; i < segCount; i++) {
            String wt = trimmed.substring(charStarts[i], charEnds[i]);
            if (wt.isEmpty()) continue;
            double uStart = (double) segments[2 * i] / w;
            double uEnd = (double) (segments[2 * i + 1] + 1) / w;
            if (uEnd <= uStart) uEnd = Math.min(1.0, uStart + 1e-3);
            float[] r =
                    PaddleResultBuilder.interpolateQuadStripFloats(
                            q, clamp01(uStart), clamp01(uEnd));
            RectF bbox = new RectF(r[0], r[1], r[2], r[3]);
            result.add(new RecognizedWord(wt, bbox, conf100));
        }
        return result.size() >= 2 ? result : null;
    }

    /**
     * Horizontal centre of every char of {@code trimmed} in crop pixels, from the CTC frame per
     * char, or null when the recogniser output does not carry usable positions. Frame {@code k}
     * covers {@code [k, k+1) * paddedW / T} of the padded recognition input; dividing by the
     * scaled content width gives the fraction of the crop width.
     */
    private static double[] charPositions(
            String text, String trimmed, PaddleRecOrtRunner.RecOutput out, int cropW) {
        if (out == null || out.charFrames() == null) return null;
        int[] frames = out.charFrames();
        if (frames.length != text.length()
                || out.frameCount() <= 0
                || out.scaledCropWidth() <= 0
                || out.paddedCropWidth() <= 0) {
            return null;
        }
        int offset = text.indexOf(trimmed);
        if (offset < 0) return null;
        double[] xs = new double[trimmed.length()];
        double frameW = (double) out.paddedCropWidth() / out.frameCount();
        for (int i = 0; i < xs.length; i++) {
            double xPadded = (frames[offset + i] + 0.5) * frameW;
            xs[i] = xPadded / out.scaledCropWidth() * cropW;
        }
        return xs;
    }

    /**
     * Assigns characters to segments by position: each char goes to the segment that contains
     * its x, or to the nearest one when it lies in a gap. The assignment is kept monotonic in
     * reading order (a char never lands in an earlier segment than its predecessor), so the
     * concatenation of all ranges is the whole text again. Returns {@code [start, end)} char
     * ranges per segment; a segment that receives no character gets an empty range.
     */
    @VisibleForTesting
    static int[][] assignCharsByPosition(double[] charX, int[] segments) {
        int segCount = segments.length / 2;
        int n = charX.length;
        int[] segOf = new int[n];
        int prev = 0;
        for (int i = 0; i < n; i++) {
            double x = charX[i];
            int best = -1;
            double bestDist = Double.MAX_VALUE;
            for (int s = 0; s < segCount; s++) {
                double a = segments[2 * s];
                double b = segments[2 * s + 1];
                double dist = x < a ? a - x : (x > b ? x - b : 0.0);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = s;
                }
            }
            if (best < prev) best = prev;
            segOf[i] = best;
            prev = best;
        }
        int[][] ranges = new int[segCount][2];
        int cursor = 0;
        for (int s = 0; s < segCount; s++) {
            ranges[s][0] = cursor;
            while (cursor < n && segOf[cursor] == s) cursor++;
            ranges[s][1] = cursor;
        }
        return ranges;
    }

    /**
     * Computes the ink profile of a bitmap image crop. The ink profile is an array
     * representing the amount of ink (darkness) in each column of the image, based on
     * the pixel luminance values.
     *
     * @param crop the cropped bitmap image for which to compute the ink profile.
     *             Must not be null and should have valid dimensions.
     * @return an array of integers representing the ink profile, where each element
     *         corresponds to the sum of ink values (inverted luminance) for a column
     *         in the image.
     */
    @VisibleForTesting
    static int[] computeInkProfile(Bitmap crop) {
        int w = crop.getWidth();
        int h = crop.getHeight();
        int[] pixels = new int[w * h];
        crop.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] profile = new int[w];
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int y = 0; y < h; y++) {
                int p = pixels[y * w + x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int luma = (r * 299 + g * 587 + b * 114) / 1000;
                sum += (255 - luma);
            }
            profile[x] = sum;
        }
        return profile;
    }

    /**
     * Identifies and segments regions within an ink profile based on adaptive thresholds
     * and heuristics. The method processes an ink profile array and attempts to detect
     * contiguous ink regions (segments) while filtering out noise and small gaps.
     *
     * @param profile an array of integers representing the ink profile, where each element
     *                indicates the amount of ink (darkness) for a specific column in the
     *                crop. Must not be null and should have a valid length.
     * @param cropH   the height of the crop image, used as a reference for thresholds
     *                (e.g., gap and segment size). Must be greater than zero.
     * @return        an array of integers representing the start and end positions of
     *                detected segments. Each segment is represented by two consecutive
     *                integers (start index and end index). Returns null if fewer than two
     *                valid segments are found.
     */
    @VisibleForTesting
    static int[] findSegments(int[] profile, int cropH) {
        int w = profile.length;
        int gapThresh = (int) Math.round(GAP_INK_RATIO * cropH * 255.0);
        int minSegPx = Math.max(2, (int) Math.round(MIN_SEG_RATIO * cropH));

        // 1) Roh-Runs sammeln (alles oberhalb gapThresh).
        List<int[]> runs = new ArrayList<>();
        int s = -1;
        for (int x = 0; x < w; x++) {
            boolean ink = profile[x] > gapThresh;
            if (ink && s < 0) {
                s = x;
            } else if (!ink && s >= 0) {
                runs.add(new int[] {s, x - 1});
                s = -1;
            }
        }
        if (s >= 0) runs.add(new int[] {s, w - 1});
        if (runs.size() < 2) return null;

        // 2) Word-Gap-Schwelle adaptiv: bimodale Trennung Inter-Char vs Inter-Word.
        // Heuristik: medianGap*Faktor, aber wenn maxGap >> minGap (bimodal) Schwelle in der
        // Mitte zwischen den beiden setzen. Bei homogenen Gaps (max ≈ min) bleibt nur die
        // harte Untergrenze MIN_WORD_GAP_PX wirksam.
        int[] gaps = new int[runs.size() - 1];
        for (int i = 1; i < runs.size(); i++) {
            gaps[i - 1] = runs.get(i)[0] - runs.get(i - 1)[1] - 1;
        }
        int[] sortedGaps = gaps.clone();
        java.util.Arrays.sort(sortedGaps);
        int medianGap = sortedGaps[sortedGaps.length / 2];
        int minGap = sortedGaps[0];
        int maxGap = sortedGaps[sortedGaps.length - 1];
        int wordGapPx;
        if (maxGap >= medianGap * WORD_GAP_MEDIAN_FACTOR && maxGap > minGap + 1) {
            // Bimodal: Schwelle in der Mitte zwischen Char-Gap (median) und Word-Gap (max).
            wordGapPx = (medianGap + maxGap) / 2;
        } else {
            // Homogen: keine Inter-Char-Gaps erkennbar; alle Gaps als Word-Gaps werten,
            // sofern sie über der harten Untergrenze liegen.
            wordGapPx = MIN_WORD_GAP_PX;
        }
        int hardMin =
                Math.max(MIN_WORD_GAP_PX, (int) Math.round(MIN_WORD_GAP_HEIGHT_RATIO * cropH));
        // Zusätzliche Untergrenze relativ zur medianen Ink-Run-Breite (~Char-Breite). Bei großen
        // Schriftgraden / Headlines / Tabellen sind Inter-Char-Gaps in Pixeln oft >MIN_WORD_GAP_PX
        // und auch über der höhenbezogenen Schwelle, liegen aber nahe der Char-Breite. Wort-Gaps
        // erreichen typischerweise ≳0.6 * Char-Breite. Anwendung nur, wenn genug Runs vorliegen
        // (≥4), damit eine sinnvolle Char-Breiten-Statistik entsteht — bei 2-3 Runs entspricht
        // ein Run einem ganzen Wort und die Heuristik würde fälschlich blockieren.
        if (runs.size() >= 4) {
            int[] runWidths = new int[runs.size()];
            for (int i = 0; i < runs.size(); i++) {
                runWidths[i] = runs.get(i)[1] - runs.get(i)[0] + 1;
            }
            java.util.Arrays.sort(runWidths);
            int medianRunWidth = runWidths[runWidths.length / 2];
            int inkRunMin = (int) Math.round(MIN_WORD_GAP_INK_RUN_FACTOR * medianRunWidth);
            wordGapPx = Math.max(inkRunMin, wordGapPx);
        }
        wordGapPx = Math.max(hardMin, wordGapPx);

        // 3) Runs zusammenführen, wenn Gap < wordGapPx (= Inter-Char).
        List<int[]> merged = new ArrayList<>();
        int[] cur = runs.get(0).clone();
        for (int i = 1; i < runs.size(); i++) {
            int[] r = runs.get(i);
            int gap = r[0] - cur[1] - 1;
            if (gap < wordGapPx) {
                cur[1] = r[1];
            } else {
                merged.add(cur);
                cur = r.clone();
            }
        }
        merged.add(cur);

        // 3) Mini-Segmente (z.B. Punkt auf "i" als isolierter Run) dem nächstgelegenen Nachbarn
        //    zuschlagen.
        List<int[]> filtered = new ArrayList<>();
        for (int[] r : merged) {
            int width = r[1] - r[0] + 1;
            if (width >= minSegPx || filtered.isEmpty()) {
                filtered.add(r);
            } else {
                // Mit vorherigem Segment mergen.
                int[] prev = filtered.get(filtered.size() - 1);
                prev[1] = r[1];
            }
        }
        if (filtered.size() < 2) return null;

        int[] out = new int[filtered.size() * 2];
        for (int i = 0; i < filtered.size(); i++) {
            out[2 * i] = filtered.get(i)[0];
            out[2 * i + 1] = filtered.get(i)[1];
        }
        return out;
    }

    /**
     * Adjusts scaled segment positions based on a given scaling factor. The method reverses
     * scaling applied to segment coordinates, ensuring they fit within the bounds of the
     * original dimension while maintaining valid segment boundaries.
     *
     * @param scaledSegments an array of integers representing the start and end positions
     *                       of segments in the scaled space. Each segment is represented
     *                       by two consecutive integers (start index and end index). Can be null.
     * @param scale          the scaling factor used to adjust the segment positions back to
     *                       the original space. A value of 1.0 indicates no scaling.
     * @param origW          the width of the original space. Used to constrain segment boundaries.
     *                       Must be non-negative.
     * @return an array of integers representing the unscaled segment positions, where each
     *         segment is represented by two consecutive integers (start index and end index).
     *         Returns null if the input scaledSegments is null.
     */
    @VisibleForTesting
    static int[] unscaleSegments(int[] scaledSegments, double scale, int origW) {
        if (scaledSegments == null) return null;
        if (scale == 1.0) return scaledSegments;
        int[] out = new int[scaledSegments.length];
        int last = origW - 1;
        for (int i = 0; i < scaledSegments.length; i += 2) {
            int s0 = (int) Math.floor(scaledSegments[i] / scale);
            int s1 = (int) Math.ceil(scaledSegments[i + 1] / scale);
            if (s0 < 0) s0 = 0;
            if (s1 > last) s1 = last;
            if (s1 < s0) s1 = s0;
            out[i] = s0;
            out[i + 1] = s1;
        }
        // Überlappungen mit Vorgänger durch Anhebung von start auflösen (Rundungs-Edgecase).
        for (int i = 2; i < out.length; i += 2) {
            if (out[i] <= out[i - 1]) {
                int min = out[i - 1] + 1;
                if (min > last) min = last;
                out[i] = min;
                if (out[i + 1] < out[i]) out[i + 1] = out[i];
            }
        }
        return out;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    /**
     * A test-visible helper method that delegates the task of finding segments
     * in an ink profile to the {@code findSegments} method. This method is used
     * for testing purposes to identify contiguous regions of ink in the provided profile.
     *
     * @param profile an array of integers representing the ink profile, where each element
     *                indicates the amount of ink (darkness) for a specific column in the crop.
     *                Must not be null and should have a valid length.
     * @param cropH   the height of the crop image, used as a reference for thresholds
     *                (e.g., gap and segment size). Must be greater than zero.
     * @return        an array of integers representing the start and end positions of
     *                detected segments. Each segment is represented by two consecutive
     *                integers (start index and end index). Returns null if fewer than two
     *                valid segments are found.
     */
    @VisibleForTesting
    static int[] findSegmentsForTest(int[] profile, int cropH) {
        return findSegments(profile, cropH);
    }

    /**
     * Distributes a given length of text proportionally across specified segments.
     * The method calculates the character ranges for each segment based on their relative sizes.
     *
     * @param segments an array of integers representing the start and end positions of segments.
     *                 Each segment is defined by two consecutive integers: the start index and end index.
     *                 Must not be null and should have an even number of elements.
     * @param textLen  the total length of the text to be distributed across the segments.
     *                 Must be non-negative.
     * @return         a 2D array where each row represents a segment and contains two integers:
     *                 the start and end indices of the text distributed to that segment.
     */
    @VisibleForTesting
    static int[][] distributeCharsForTest(int[] segments, int textLen) {
        int segCount = segments.length / 2;
        int totalSegW = 0;
        for (int i = 0; i < segCount; i++) {
            totalSegW += segments[2 * i + 1] - segments[2 * i] + 1;
        }
        int[][] ranges = new int[segCount][2];
        int cursor = 0;
        int seenW = 0;
        for (int i = 0; i < segCount; i++) {
            seenW += segments[2 * i + 1] - segments[2 * i] + 1;
            int end =
                    (i == segCount - 1)
                            ? textLen
                            : (int) Math.round((double) seenW * textLen / totalSegW);
            if (end <= cursor) end = Math.min(textLen, cursor + 1);
            if (end > textLen) end = textLen;
            ranges[i][0] = cursor;
            ranges[i][1] = end;
            cursor = end;
        }
        return ranges;
    }

    @SuppressWarnings("unused")
    private static String dbg(int[] a) {
        return Arrays.toString(a);
    }
}
