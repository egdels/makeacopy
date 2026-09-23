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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * DbPostProcessor is a utility class designed to process probabilistic maps for detecting quads
 * in documents. Typically used in Optical Character Recognition (OCR) pipelines such as PP-OCRv5,
 * it aids in post-processing binarized probability maps to extract valid bounding polygons
 * based on predefined thresholds and filtering criteria.
 *
 * The primary purpose of this class is to determine bounding areas of connected components
 * by analyzing the probability map and filtering results the way the PaddleOCR reference
 * post-processing does: by box score and minimum size only. Earlier versions also rejected
 * flat, homogeneous components as "stripes" (colour bars, rules); that filter had to go because
 * the DB kernel of a long text line is exactly such a flat, filled bar (8-9 px high at the
 * detector's working resolution, more than 40 times wider than high), so whole lines of
 * justified body text vanished from the result (GitHub issue #87).
 *
 * Key features and steps in the processing workflow include:
 * - Binary thresholding to create a mask of probable areas.
 * - Identification of connected components using Breadth-First Search (BFS).
 * - Filtering of components based on predefined constraints like size and shape.
 * - Unclipping bounding boxes to account for edge cases without overly aggressive growth.
 *
 * The thresholds and filters are fully customizable through constructor parameters,
 * allowing for flexibility when applying OCR or layout reconstruction for varying document types.
 *
 * Default thresholds provided include:
 * - dbThresh: Minimum probability required to include a pixel in a binary mask.
 * - boxThresh: Minimum mean probability for each bounding box to be considered valid.
 * - minArea: Minimum pixel area for connected components to prevent noise.
 * - minSide: Minimum width or height for bounding boxes to avoid fragmentation.
 *
 * The {@code process} method serves as the entry point for extracting valid quads from the input
 * probability map. The output is a list of {@code Quad} objects, which represent the detected
 * bounding polygons in the same coordinate space as the input map.
 */
final class DbPostProcessor {

    /**
     * The default threshold value used to filter the probability map in the DB post-processing
     * pipeline. This threshold determines the minimum confidence level required for a pixel
     * to be considered part of a detected object.
     *
     * <p>The value is set to 0.3 by default and can be adjusted to refine the sensitivity of the
     * detection results. Lowering the threshold increases sensitivity, potentially including
     * more false positives, while raising it decreases sensitivity, excluding more potential
     * detections.
     */
    static final double DEFAULT_DB_THRESH = 0.3;

    /**
     * Box score of the PP-OCRv5 mobile detection model's own inference configuration ({@code
     * DBPostProcess: box_thresh 0.6}), so that the app accepts boxes the way the reference
     * pipeline does.
     */
    static final double DEFAULT_BOX_THRESH = 0.6;

    /**
     * Unclip ratio. The reference configuration says 1.5, but it grows a contour polygon whereas
     * this class grows the axis-aligned bounding box of the flood-filled component, so the
     * numbers are not interchangeable: with 1.5 the vertical Japanese book photo of {@code
     * JapaneseVerticalEvalTest} loses glyphs at the column edges (CER 0.049 instead of 0). 1.6 is
     * the value the vertical CJK path was tuned with.
     */
    static final double DEFAULT_UNCLIP_RATIO = 1.6;
    /**
     * Defines the minimum area threshold for regions to be considered during
     * post-processing in the detection pipeline.
     *
     * <p>Any region with an area smaller than this value will be ignored.
     * This constant ensures that very small regions, which are likely to
     * be noise or artifacts, do not interfere with the processing results.
     */
    static final int DEFAULT_MIN_AREA = 16;
    static final int DEFAULT_MIN_SIDE = 3;

    private final double dbThresh;
    private final double boxThresh;
    private final double unclipRatio;
    private final int minArea;
    private final int minSide;

    DbPostProcessor() {
        this(
                DEFAULT_DB_THRESH,
                DEFAULT_BOX_THRESH,
                DEFAULT_UNCLIP_RATIO,
                DEFAULT_MIN_AREA,
                DEFAULT_MIN_SIDE);
    }

    DbPostProcessor(double dbThresh, double boxThresh, double unclipRatio) {
        this(dbThresh, boxThresh, unclipRatio, DEFAULT_MIN_AREA, DEFAULT_MIN_SIDE);
    }

    DbPostProcessor(
            double dbThresh,
            double boxThresh,
            double unclipRatio,
            int minArea,
            int minSide) {
        this.dbThresh = dbThresh;
        this.boxThresh = boxThresh;
        this.unclipRatio = unclipRatio;
        this.minArea = minArea;
        this.minSide = minSide;
    }

    /**
     * Processes a 2D probability array and identifies connected components that meet specific
     * thresholds and constraints. It returns a list of quads representing bounding boxes for
     * the detected components, along with their scores.
     *
     * @param prob A 2D array of probabilities representing the prediction output. Each value
     *             indicates the confidence of a pixel being part of a region of interest.
     *             Must be non-null and contain at least one row with one column.
     * @return A list of quads, where each quad represents a detected connected component. Each
     *         quad contains four vertices (top-left, top-right, bottom-right, bottom-left),
     *         along with a score that indicates the mean probability of the component.
     */
    List<Quad> process(float[][] prob) {
        if (prob == null || prob.length == 0 || prob[0] == null || prob[0].length == 0) {
            return new ArrayList<>();
        }
        final int h = prob.length;
        final int w = prob[0].length;

        // Binäre Maske via dbThresh
        boolean[][] bin = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bin[y][x] = prob[y][x] >= dbThresh;
            }
        }

        boolean[][] visited = new boolean[h][w];
        List<Quad> result = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!bin[y][x] || visited[y][x]) continue;
                Component c = floodFill(prob, bin, visited, x, y);
                if (isPlausibleTextBox(c)) result.add(unclip(c));
            }
        }
        return result;
    }

    /** Eine zusammenhängende Komponente der binären Maske samt Statistik. */
    private static final class Component {
        int minX, maxX, minY, maxY;
        int count = 0;
        double sumProb = 0.0;

        Component(int x, int y) {
            minX = maxX = x;
            minY = maxY = y;
        }

        void add(int px, int py, float p) {
            count++;
            sumProb += p;
            if (px < minX) minX = px;
            if (px > maxX) maxX = px;
            if (py < minY) minY = py;
            if (py > maxY) maxY = py;
        }

        int boxW() {
            return maxX - minX + 1;
        }

        int boxH() {
            return maxY - minY + 1;
        }

        double meanProb() {
            return sumProb / count;
        }
    }

    /** BFS über die 4-Nachbarschaft; markiert alle Pixel der Komponente als besucht. */
    private static Component floodFill(
            float[][] prob, boolean[][] bin, boolean[][] visited, int startX, int startY) {
        final int h = bin.length;
        final int w = bin[0].length;
        Component c = new Component(startX, startY);
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {startX, startY});
        visited[startY][startX] = true;
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            int px = p[0], py = p[1];
            c.add(px, py, prob[py][px]);
            visit(bin, visited, queue, px + 1, py, w, h);
            visit(bin, visited, queue, px - 1, py, w, h);
            visit(bin, visited, queue, px, py + 1, w, h);
            visit(bin, visited, queue, px, py - 1, w, h);
        }
        return c;
    }

    private static void visit(
            boolean[][] bin, boolean[][] visited, Deque<int[]> queue, int x, int y, int w, int h) {
        if (x < 0 || x >= w || y < 0 || y >= h) return;
        if (!bin[y][x] || visited[y][x]) return;
        visited[y][x] = true;
        queue.add(new int[] {x, y});
    }

    private boolean isPlausibleTextBox(Component c) {
        if (c.count <= 0) return false;
        if (c.meanProb() < boxThresh) return false;
        // Box-Hygiene: Mindestfläche und Mindestseitenlänge.
        if (c.count < minArea) return false;
        return c.boxW() >= minSide && c.boxH() >= minSide;
    }


    /**
     * Paddle-konformes Unclip: D = area * (ratio - 1) / perimeter, isotrop. Im Gegensatz zur
     * multiplikativen Halbachsen-Skalierung wächst die Box hier nur um wenige Pixel, statt um
     * Faktor `unclipRatio` zu explodieren. Dadurch verschmelzen benachbarte Zeilen seltener zu
     * einem Riesen-Quad.
     */
    private Quad unclip(Component c) {
        double area = (double) c.boxW() * c.boxH();
        double perimeter = 2.0 * (c.boxW() + c.boxH());
        double d = perimeter > 0 ? area * Math.max(0.0, unclipRatio - 1.0) / perimeter : 0.0;

        double x0 = c.minX - d;
        double x1 = c.maxX + 1 + d;
        double y0 = c.minY - d;
        double y1 = c.maxY + 1 + d;

        double[] xs = new double[] {x0, x1, x1, x0};
        double[] ys = new double[] {y0, y0, y1, y1};
        return new Quad(xs, ys, c.meanProb());
    }
}
