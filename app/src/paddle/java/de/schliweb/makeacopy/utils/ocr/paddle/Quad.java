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

/**
 * Represents a quadrilateral defined by four x and y coordinates,
 * along with an optional score value.
 *
 * Instances of this class are immutable after construction.
 */
final class Quad {
    final double[] x = new double[4];
    final double[] y = new double[4];
    /**
     * Represents the confidence score of the quadrilateral, typically used as a
     * measure of certainty or quality associated with the detected shape.
     *
     * <ul>
     * <li>A higher value indicates greater confidence in the quadrilateral's definition.</li>
     * <li>The value is immutable and is assigned during the creation of the {@code Quad} instance.</li>
     * <li>Can be used as a criterion for filtering or ranking multiple detected shapes when
     * analyzing results or making decisions.</li>
     * </ul>
     */
    final double score;

    /**
     * {@code {minX, minY, maxX, maxY, pixelCount}} of the raw detector component (the DB
     * kernel) this quad was unclipped from, or null when the quad did not come from the
     * detector. Lets the builder re-derive a different unclip for vertical text.
     */
    final double[] kernel;

    Quad(double[] xs, double[] ys, double score) {
        this(xs, ys, score, null);
    }

    Quad(double[] xs, double[] ys, double score, double[] kernel) {
        if (xs.length != 4 || ys.length != 4) {
            throw new IllegalArgumentException("xs/ys must have length 4");
        }
        System.arraycopy(xs, 0, this.x, 0, 4);
        System.arraycopy(ys, 0, this.y, 0, 4);
        this.score = score;
        this.kernel = kernel == null ? null : kernel.clone();
    }

    double minX() {
        return Math.min(Math.min(x[0], x[1]), Math.min(x[2], x[3]));
    }

    double minY() {
        return Math.min(Math.min(y[0], y[1]), Math.min(y[2], y[3]));
    }

    double maxX() {
        return Math.max(Math.max(x[0], x[1]), Math.max(x[2], x[3]));
    }

    double maxY() {
        return Math.max(Math.max(y[0], y[1]), Math.max(y[2], y[3]));
    }
}
