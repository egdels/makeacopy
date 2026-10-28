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

/**
 * A utility class for managing letterbox transformations, typically used for scaling
 * and padding an image such that the longest side matches a maximum size while
 * ensuring that the width and height are aligned to a specified stride.
 *
 * This class is immutable and provides both forward and inverse mappings between
 * the source and transformed coordinate spaces.
 */
final class Letterbox {

    /**
     * Represents the stride value used for aligning dimensions to a specific granularity.
     * All scaled and padded dimensions in the {@code Letterbox} class are rounded to
     * be multiples of this stride value. This ensures uniform alignment, aiding in
     * structured image processing and dimensional consistency.
     *
     * <ul>
     * <li>The value is integral and immutable, simplifying stride-based dimension calculations.</li>
     * <li>Commonly used in scenarios where alignment to fixed grid spacing is required,
     *     such as scaling and padding operations in image transformation pipelines.</li>
     * </ul>
     */
    static final int STRIDE = 32;

    final int srcW;
    final int srcH;
    final int dstW;
    final int dstH;
    /**
     * The scaling factor used to calculate the transformation between source and destination dimensions.
     * Determines how the source dimensions are proportionally adjusted to fit within the destination dimensions
     * while preserving the aspect ratio.
     *
     * This value is computed during the initialization of the {@code Letterbox} instance based on the
     * source and destination dimensions.
     *
     * Constraints:
     * - Typically a positive value.
     * - Represents a proportional multiplier for scaling.
     */
    final double scale;
    /**
     * The horizontal padding applied to the source content to fit within the target dimensions
     * during the letterbox transformation. The padding is typically added symmetrically to both
     * sides. This value is derived based on the scaling factor and the aspect ratio difference
     * between the source and destination dimensions.
     */
    final double padX;
    /**
     * The vertical padding added to the scaled content within the target dimension
     * during the letterbox transformation. Represents the amount of empty space
     * distributed vertically (evenly split across the top and bottom) to maintain
     * aspect ratio while fitting the source dimensions into the target dimensions.
     */
    final double padY;

    private Letterbox(int srcW, int srcH, int dstW, int dstH, double scale, double padX, double padY) {
        this.srcW = srcW;
        this.srcH = srcH;
        this.dstW = dstW;
        this.dstH = dstH;
        this.scale = scale;
        this.padX = padX;
        this.padY = padY;
    }

    /**
     * Computes a {@code Letterbox} object that resizes and centers a source image
     * within a target dimension while maintaining the aspect ratio and adhering to
     * stride alignment constraints.
     *
     * @param srcW the width of the source image; must be greater than 0
     * @param srcH the height of the source image; must be greater than 0
     * @param maxSide the maximum dimension of the longer side of the output; must be greater than 0
     * @return a {@code Letterbox} object containing the dimensions and scaling details for
     *         transforming the source image to the target space
     * @throws IllegalArgumentException if {@code srcW}, {@code srcH}, or {@code maxSide} are not greater than 0
     */
    static Letterbox compute(int srcW, int srcH, int maxSide) {
        if (srcW <= 0 || srcH <= 0) {
            throw new IllegalArgumentException("srcW/srcH must be > 0");
        }
        if (maxSide <= 0) {
            throw new IllegalArgumentException("maxSide must be > 0");
        }
        // maxSide auf STRIDE-Vielfaches normalisieren (mind. STRIDE)
        int maxSideN = Math.max(STRIDE, (maxSide / STRIDE) * STRIDE);

        int longSide = Math.max(srcW, srcH);
        double scale = Math.min(1.0, (double) maxSideN / (double) longSide);

        // Skalierte Größen, dann auf STRIDE-Vielfaches aufrunden.
        int scaledW = (int) Math.round(srcW * scale);
        int scaledH = (int) Math.round(srcH * scale);
        int dstW = ceilToStride(Math.max(STRIDE, scaledW));
        int dstH = ceilToStride(Math.max(STRIDE, scaledH));

        double padX = (dstW - srcW * scale) / 2.0;
        double padY = (dstH - srcH * scale) / 2.0;
        return new Letterbox(srcW, srcH, dstW, dstH, scale, padX, padY);
    }

    private static int ceilToStride(int v) {
        return ((v + STRIDE - 1) / STRIDE) * STRIDE;
    }

    /**
     * Transforms a point from the source coordinate space to the destination coordinate space
     * using the predefined scaling factor and padding values.
     *
     * @param x the x-coordinate of the point in the source coordinate space
     * @param y the y-coordinate of the point in the source coordinate space
     * @return an array containing the transformed x and y coordinates in the destination coordinate space
     */
    double[] applyPoint(double x, double y) {
        return new double[] {x * scale + padX, y * scale + padY};
    }

    /**
     * Transforms a point from the destination coordinate space to the source coordinate space
     * by reversing the scaling and padding adjustments.
     *
     * @param x the x-coordinate of the point in the destination coordinate space
     * @param y the y-coordinate of the point in the destination coordinate space
     * @return an array containing the transformed x and y coordinates in the source coordinate space
     */
    double[] unapplyPoint(double x, double y) {
        return new double[] {(x - padX) / scale, (y - padY) / scale};
    }
}
