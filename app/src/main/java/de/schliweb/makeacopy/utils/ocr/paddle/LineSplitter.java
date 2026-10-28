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
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The {@code LineSplitter} class provides methods for analyzing and splitting quadrilaterals
 * (Quads) into sub-quadrilaterals based on their geometric properties and image content. It is
 * used to identify and segment tall Quads that represent areas such as lines of text, ensuring
 * accurate processing in image analysis tasks.
 *
 * This class operates in the context of splitting image elements (e.g., lines of text in a document)
 * and is designed to work with a combination of geometric reasoning and projection profile analysis.
 * The class is final and cannot be extended.
 */
final class LineSplitter {

    /**
     * Factor used to determine whether a quad's height is significantly tall compared
     * to the median height of other quads. This value acts as a multiplier for
     * the median height to identify candidates for splitting into sub-quads.
     *
     * <p>Quads with a height exceeding this factor multiplied by the median height
     * are considered "tall" and may be subjected to splitting during processing.
     *
     * <p>Primarily intended to assist in the segmentation of unusually large quads
     * into smaller sub-quads while maintaining the original order top-to-bottom.
     */
    @VisibleForTesting static final double SPLIT_HEIGHT_FACTOR = 1.8;

    /**
     * The minimum threshold value used to identify "valleys" in projection profiles
     * for determining potential split points. A valley is a region with sufficiently
     * low projection values relative to its surroundings, which may indicate a natural
     * separation between text lines.
     *
     * <p>Primarily utilized in text-line splitting algorithms, this constant influences
     * the sensitivity of the valley detection process. A lower threshold value may
     * result in more aggressive splitting, identifying smaller or shallower valleys,
     * while a higher value becomes more conservative, requiring deeper valleys to trigger splits.
     *
     * <p>Visible only for testing purposes to validate and fine-tune the line-splitting
     * behavior in scenarios governed by this parameter.
     */
    @VisibleForTesting static final double VALLEY_THRESHOLD = 0.3;

    /**
     * Represents the minimum allowable height for a line in pixels.
     *
     * This constant is used to ensure that sub-lines or split sections of text
     * meet a certain height threshold, preventing invalid or negligible line
     * segments from being processed.
     *
     * The value is set to 4 pixels, which serves as a lower bound for
     * valid line heights during operations such as line splitting or
     * projection profiling.
     */
    @VisibleForTesting static final int MIN_LINE_HEIGHT_PX = 4;

    /**
     * The minimum height, in pixels, for a valley in the horizontal projection profile
     * that is considered significant during quad splitting.
     *
     * This threshold is used to distinguish meaningful valleys from noise when analyzing
     * text regions in an image, particularly in the process of splitting text into
     * sub-regions or lines.
     *
     * A smaller value makes the algorithm more sensitive to minor variations in the
     * projection profile, potentially leading to finer-grained splits. Conversely,
     * a larger value may result in coarser splits or no splitting in cases of minimal
     * variation.
     */
    @VisibleForTesting static final int MIN_VALLEY_HEIGHT_PX = 2;

    @VisibleForTesting static final double WIDE_QUAD_MIN_ASPECT = 8.0;
    @VisibleForTesting static final int WIDE_QUAD_TARGET_SCALED_WIDTH = 768;

    private LineSplitter() {}

    /**
     * Functional interface defining a contract for cropping a given image to a specified region.
     *
     * The cropping operation is defined by selecting a portion of the input image specified by
     * the given {@code Quad}, which represents a rotated rectangle in image coordinates. The
     * resulting bitmap represents the cropped area, maintaining the original pixel data within
     * the bounds of the specified {@code Quad}.
     */
    @FunctionalInterface
    interface Cropper {
        Bitmap crop(Bitmap full, Quad quad);
    }

    /**
     * Splits tall quads from a list of {@link Quad} objects into smaller sub-quads based on their
     * height in relation to a calculated median height. Quads that exceed a certain height threshold
     * determined by {@code SPLIT_HEIGHT_FACTOR} are processed, while others are left unchanged.
     *
     * @param full the original bitmap image from which the quads are derived. This bitmap is used
     *             to create crops for further processing of tall quads.
     * @param quads the list of {@link Quad} objects to process. Each quad represents a region in
     *              the bitmap that may be split if its height exceeds the calculated threshold.
     * @return a list of {@link Quad} objects, which may include the original quads or newly
     *         split sub-quads if splitting was applicable. Returns the input list unchanged
     *         if no splitting was necessary.
     */
    static List<Quad> splitTallQuads(Bitmap full, List<Quad> quads) {
        return splitTallQuads(full, quads, PaddleQuadCropper::crop);
    }

    @VisibleForTesting
    static List<Quad> splitWideQuads(List<Quad> quads) {
        if (quads == null || quads.isEmpty()) return quads;

        List<Quad> out = null;
        for (int i = 0; i < quads.size(); i++) {
            Quad q = quads.get(i);
            List<Quad> subs = splitOneWideQuad(q);
            if (subs == null || subs.size() < 2) {
                if (out != null) out.add(q);
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(quads.size() + subs.size());
                for (int j = 0; j < i; j++) out.add(quads.get(j));
            }
            out.addAll(subs);
        }
        return out != null ? out : quads;
    }

    /**
     * Splits tall quads from a list of {@link Quad} objects into smaller sub-quads based on their
     * height in relation to a calculated median height. Quads that exceed a certain height threshold
     * determined by {@code SPLIT_HEIGHT_FACTOR} are processed, while others are left unchanged.
     *
     * @param full the original bitmap image from which the quads are derived. This bitmap is used
     *             to create crops for further processing of tall quads.
     * @param quads the list of {@link Quad} objects to process. Each quad represents a region in
     *              the bitmap that may be split if its height exceeds the calculated threshold.
     * @param cropper the {@link Cropper} interface implementation used to crop a portion of the
     *                provided bitmap for detailed processing of tall quads.
     * @return a list of {@link Quad} objects, which may include the original quads or newly
     *         split sub-quads if splitting was applicable. Returns the input list unchanged
     *         if no splitting was necessary.
     */
    @VisibleForTesting
    static List<Quad> splitTallQuads(Bitmap full, List<Quad> quads, Cropper cropper) {
        if (quads == null || quads.isEmpty()) return quads;

        double medianHeight = medianHeight(quads);
        if (medianHeight <= 0) return quads;
        double splitThresholdPx = SPLIT_HEIGHT_FACTOR * medianHeight;

        List<Quad> out = null; // lazy: nur kopieren, wenn tatsächlich gesplittet wird
        for (int i = 0; i < quads.size(); i++) {
            Quad q = quads.get(i);
            double h = q.maxY() - q.minY();
            if (h <= splitThresholdPx) {
                if (out != null) out.add(q);
                continue;
            }
            Bitmap crop = null;
            List<Quad> subs;
            try {
                crop = cropper.crop(full, q);
                subs = splitOneQuad(q, crop, medianHeight);
            } catch (RuntimeException e) {
                subs = null;
            } finally {
                if (crop != null && crop != full && !crop.isRecycled()) {
                    crop.recycle();
                }
            }
            if (subs == null || subs.size() < 2) {
                if (out != null) out.add(q);
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(quads.size() + subs.size());
                for (int j = 0; j < i; j++) out.add(quads.get(j));
            }
            out.addAll(subs);
        }
        return out != null ? out : quads;
    }

    /**
     * Calculates the median height of a list of {@link Quad} objects. The height of each
     * quad is determined as the difference between its maximum and minimum y-coordinates.
     * Only non-negative heights are included in the calculation.
     *
     * @param quads the list of {@link Quad} objects for which the median height is to be calculated.
     *              Each quad's height is derived from the difference between its maximum
     *              and minimum y-coordinates.
     * @return the median height of the quads as a {@code double}. If the list is empty,
     *         the behavior of this method is undefined.
     */
    @VisibleForTesting
    static double medianHeight(List<Quad> quads) {
        double[] hs = new double[quads.size()];
        for (int i = 0; i < quads.size(); i++) {
            hs[i] = Math.max(0.0, quads.get(i).maxY() - quads.get(i).minY());
        }
        Arrays.sort(hs);
        return hs[hs.length / 2];
    }

    private static List<Quad> splitOneWideQuad(Quad q) {
        double w = Math.max(1.0, q.maxX() - q.minX());
        double h = Math.max(1.0, q.maxY() - q.minY());
        double aspect = w / h;
        double scaledW = w * PaddleRecOrtRunner.REC_INPUT_HEIGHT / h;
        if (aspect < WIDE_QUAD_MIN_ASPECT || scaledW <= WIDE_QUAD_TARGET_SCALED_WIDTH) {
            return null;
        }

        int parts = (int) Math.ceil(scaledW / WIDE_QUAD_TARGET_SCALED_WIDTH);
        if (parts < 2) return null;
        parts = Math.min(parts, 8);

        List<Quad> out = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            double u0 = (double) i / parts;
            double u1 = (double) (i + 1) / parts;
            out.add(verticalStrip(q, u0, u1));
        }
        return out;
    }

    private static Quad verticalStrip(Quad q, double u0, double u1) {
        double topX0 = lerp(q.x[0], q.x[1], u0);
        double topY0 = lerp(q.y[0], q.y[1], u0);
        double topX1 = lerp(q.x[0], q.x[1], u1);
        double topY1 = lerp(q.y[0], q.y[1], u1);
        double bottomX1 = lerp(q.x[3], q.x[2], u1);
        double bottomY1 = lerp(q.y[3], q.y[2], u1);
        double bottomX0 = lerp(q.x[3], q.x[2], u0);
        double bottomY0 = lerp(q.y[3], q.y[2], u0);
        return new Quad(
                new double[] {topX0, topX1, bottomX1, bottomX0},
                new double[] {topY0, topY1, bottomY1, bottomY0},
                q.score);
    }

    private static double lerp(double a, double b, double u) {
        return a + (b - a) * u;
    }

    /**
     * Splits a single {@link Quad} into smaller sub-quads based on text bands detected
     * in the associated cropped image. This method leverages horizontal projection
     * profiling to identify text bands, merges small bands with neighbors if necessary,
     * and validates the resulting structure against the specified median height.
     *
     * @param q the input {@link Quad} representing a region in the image. This quad
     *          serves as the basis for splitting into sub-quads.
     * @param crop a {@link Bitmap} object representing the cropped image. It contains
     *             visual data pertaining to the input quad and is used for text
     *             detection and segmentation.
     * @param medianHeight the pre-calculated median height used to validate the
     *                     plausibility of the resulting sub-quads. Sub-quads with
     *                     implausible dimensions are discarded.
     * @return a list of sub-quads resulting from the splitting process, or {@code null}
     *         if no valid splits were identified.
     */
    @VisibleForTesting
    static List<Quad> splitOneQuad(Quad q, Bitmap crop, double medianHeight) {
        if (crop == null || crop.isRecycled()) return null;
        int cw = crop.getWidth();
        int ch = crop.getHeight();
        if (cw <= 0 || ch < 2 * MIN_LINE_HEIGHT_PX) return null;

        // Horizontales Projection Profile: pro Zeile Summe von „Tinte" (1 - luma/255).
        double[] profile = new double[ch];
        int[] row = new int[cw];
        double maxVal = 0.0;
        double minVal = Double.POSITIVE_INFINITY;
        for (int y = 0; y < ch; y++) {
            crop.getPixels(row, 0, cw, 0, y, cw, 1);
            double sum = 0.0;
            for (int x = 0; x < cw; x++) {
                int p = row[x];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                int luma = (r * 299 + g * 587 + b * 114) / 1000;
                sum += (255 - luma);
            }
            profile[y] = sum;
            if (sum > maxVal) maxVal = sum;
            if (sum < minVal) minVal = sum;
        }
        if (maxVal <= 0) return null;
        double range = maxVal - minVal;
        if (range <= 0) return null;
        double cutoff = minVal + VALLEY_THRESHOLD * range;

        // In Bänder oberhalb der Schwelle segmentieren (Textbänder); Valleys = darunter.
        List<int[]> bands = new ArrayList<>();
        int bandStart = -1;
        for (int y = 0; y < ch; y++) {
            boolean ink = profile[y] > cutoff;
            if (ink && bandStart < 0) bandStart = y;
            else if (!ink && bandStart >= 0) {
                bands.add(new int[] {bandStart, y - 1});
                bandStart = -1;
            }
        }
        if (bandStart >= 0) bands.add(new int[] {bandStart, ch - 1});

        // Mindesthöhe der Bänder erzwingen; zu kleine Bänder mit Nachbarn mergen.
        List<int[]> merged = new ArrayList<>();
        for (int[] b : bands) {
            if (b[1] - b[0] + 1 < MIN_LINE_HEIGHT_PX) {
                if (!merged.isEmpty()
                        && b[0] - merged.get(merged.size() - 1)[1] <= MIN_VALLEY_HEIGHT_PX) {
                    merged.get(merged.size() - 1)[1] = b[1];
                } else {
                    // verwerfen (zu klein und kein direkter Vorgänger zum Mergen)
                }
            } else {
                merged.add(new int[] {b[0], b[1]});
            }
        }
        if (merged.size() < 2) return null;

        // Außerdem: gefundene Bandhöhen müssen plausibel zur medianHeight sein. Wenn ein
        // einziges Band fast den ganzen Crop füllt → kein echter Split.
        int dominantH = 0;
        for (int[] b : merged) dominantH = Math.max(dominantH, b[1] - b[0] + 1);
        if (dominantH > 0.9 * ch) return null;

        // Sub-Quads im Bildraum berechnen. AABB-Annahme: Top-Edge y0 = q.y[0], Bottom-Edge y1
        // = q.y[3]. Skalierung: cropY ∈ [0, ch] → bildY ∈ [y0, y1] linear interpoliert.
        double y0 = q.y[0];
        double y1 = q.y[3];
        double x0 = q.x[0];
        double x1 = q.x[1];
        double scale = (y1 - y0) / ch;

        List<Quad> result = new ArrayList<>(merged.size());
        for (int[] b : merged) {
            // Etwas Padding (1 px im Crop-Raum) um Glyphen-Cutoff zu vermeiden.
            int top = Math.max(0, b[0] - 1);
            int bot = Math.min(ch - 1, b[1] + 1);
            double sy0 = y0 + top * scale;
            double sy1 = y0 + (bot + 1) * scale;
            double[] xs = new double[] {x0, x1, x1, x0};
            double[] ys = new double[] {sy0, sy0, sy1, sy1};
            result.add(new Quad(xs, ys, q.score));
        }
        return result;
    }
}
