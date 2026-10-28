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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;

/**
 * Utility class for cropping a section of a bitmap defined by a quadrilateral region.
 *
 * <p>The cropping operation takes a bitmap and a {@link Quad}, which defines
 * a rotated rectangular region using four vertices (top-left, top-right, bottom-right, bottom-left).
 * The cropped region is transformed into a rectangular output bitmap of appropriate dimensions.
 *
 * <p>If the provided quadrilateral is degenerate or cannot be transformed, this method
 * falls back to an axis-aligned cropping operation based on the bounding box of the quadrilateral.
 *
 * <p>This class is not instantiable.
 */
final class PaddleQuadCropper {

    private PaddleQuadCropper() {}

    static Bitmap crop(Bitmap full, Quad quad) {
        if (full == null || full.isRecycled()) {
            throw new IllegalArgumentException("full bitmap must be non-null and not recycled");
        }
        if (quad == null) {
            throw new IllegalArgumentException("quad must be non-null");
        }
        final double[] x = quad.x;
        final double[] y = quad.y;

        // TL=0, TR=1, BR=2, BL=3 (siehe Quad-Doku)
        double dxW = x[1] - x[0];
        double dyW = y[1] - y[0];
        double dxH = x[3] - x[0];
        double dyH = y[3] - y[0];
        int sourceW = (int) Math.round(Math.hypot(dxW, dyW));
        int sourceH = (int) Math.round(Math.hypot(dxH, dyH));
        if (sourceW < 1) sourceW = 1;
        if (sourceH < 1) sourceH = 1;

        // Affine Transformation (Source→Dest) über 3 Punkte:
        //   TL → (0,0), TR → (sourceW,0), BL → (0,sourceH)
        float[] src = new float[] {
                (float) x[0], (float) y[0],
                (float) x[1], (float) y[1],
                (float) x[3], (float) y[3]
        };
        float[] dst = new float[] {
                0f, 0f,
                (float) sourceW, 0f,
                0f, (float) sourceH
        };

        Matrix m = new Matrix();
        if (!m.setPolyToPoly(src, 0, dst, 0, 3)) {
            // Degenerate Quad: Fallback auf axis-aligned bbox.
            int x0 = (int) Math.max(0, Math.floor(quad.minX()));
            int y0 = (int) Math.max(0, Math.floor(quad.minY()));
            int x1 = (int) Math.min(full.getWidth(), Math.ceil(quad.maxX()));
            int y1 = (int) Math.min(full.getHeight(), Math.ceil(quad.maxY()));
            int bw = Math.max(1, x1 - x0);
            int bh = Math.max(1, y1 - y0);
            return Bitmap.createBitmap(full, x0, y0, bw, bh);
        }

        Bitmap out = Bitmap.createBitmap(sourceW, sourceH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(full, m, paint);
        return out;
    }
}
