/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.image;

import android.graphics.Bitmap;
import android.util.Log;
import android.widget.ImageView;
import lombok.experimental.UtilityClass;
import org.opencv.core.Point;

/**
 * Utility class providing methods for coordinate transformation between view space and image space
 * in scenarios involving images displayed in a view with scaling and padding considerations.
 */
@UtilityClass
public class CoordinateTransformUtils {
  private static final String TAG = "CoordinateTransformUtils";

  /**
   * Transforms an array of coordinates from view space to image space based on the dimensions of
   * the view and bitmap. This is useful for mapping positions from a scaled or padded ImageView to
   * the corresponding positions on the actual image.
   *
   * @param viewCoordinates An array of points in the coordinate space of the view.
   * @param bitmap The bitmap of the image displayed in the view.
   * @param viewWidth The width of the view displaying the image.
   * @param viewHeight The height of the view displaying the image.
   * @return An array of points transformed to the coordinate space of the bitmap, adjusted for
   *     scaling, offsets, and clamped within the bitmap's boundaries. If {@code viewCoordinates} or
   *     {@code bitmap} is null, or {@code viewWidth} or {@code viewHeight} is non-positive, the
   *     method returns the input {@code viewCoordinates}.
   */
  public static Point[] transformViewToImageCoordinates(
      Point[] viewCoordinates, Bitmap bitmap, int viewWidth, int viewHeight) {

    if (viewCoordinates == null || bitmap == null) {
      return viewCoordinates;
    }

    // Get the dimensions
    int bitmapWidth = bitmap.getWidth();
    int bitmapHeight = bitmap.getHeight();

    if (viewWidth <= 0 || viewHeight <= 0) {
      Log.e(TAG, "View has zero dimensions");
      return viewCoordinates;
    }

    // Calculate the scaling factor and offset for FIT_CENTER
    float scale;
    float offsetX = 0;
    float offsetY = 0;

    // Calculate scaling based on the aspect ratio
    float bitmapAspect = (float) bitmapWidth / bitmapHeight;
    float viewAspect = (float) viewWidth / viewHeight;

    if (bitmapAspect > viewAspect) {
      // Image is wider than view (letterboxed)
      scale = (float) viewWidth / bitmapWidth;
      offsetY = (viewHeight - (bitmapHeight * scale)) / 2;
    } else {
      // Image is taller than view (pillarboxed)
      scale = (float) viewHeight / bitmapHeight;
      offsetX = (viewWidth - (bitmapWidth * scale)) / 2;
    }

    // Transform each point
    Point[] imageCoordinates = new Point[viewCoordinates.length];
    for (int i = 0; i < viewCoordinates.length; i++) {
      // Adjust for offset (remove padding)
      double adjustedX = viewCoordinates[i].x - offsetX;
      double adjustedY = viewCoordinates[i].y - offsetY;

      // Scale back to original image coordinates
      double imageX = adjustedX / scale;
      double imageY = adjustedY / scale;

      // Clamp to image boundaries
      imageX = Math.max(0, Math.min(imageX, bitmapWidth));
      imageY = Math.max(0, Math.min(imageY, bitmapHeight));

      imageCoordinates[i] = new Point(imageX, imageY);
    }

    return imageCoordinates;
  }

  /**
   * Transforms coordinates from view space to image space using an ImageView
   *
   * @param viewCoordinates Array of points in view coordinates
   * @param bitmap The image bitmap
   * @param imageView The ImageView containing the bitmap
   * @return Array of points in image coordinates
   */
  public static Point[] transformViewToImageCoordinates(
      Point[] viewCoordinates, Bitmap bitmap, ImageView imageView) {

    if (viewCoordinates == null || bitmap == null || imageView == null) {
      return viewCoordinates;
    }

    return transformViewToImageCoordinates(
        viewCoordinates, bitmap, imageView.getWidth(), imageView.getHeight());
  }

  /**
   * Transforms coordinates from image space to view space
   *
   * @param imageCoordinates Array of points in image coordinates
   * @param bitmap The image bitmap
   * @param viewWidth The width of the view
   * @param viewHeight The height of the view
   * @return Array of points in view coordinates
   */
  public static Point[] transformImageToViewCoordinates(
      Point[] imageCoordinates, Bitmap bitmap, int viewWidth, int viewHeight) {

    if (imageCoordinates == null || bitmap == null) {
      return imageCoordinates;
    }

    // Get the dimensions
    int bitmapWidth = bitmap.getWidth();
    int bitmapHeight = bitmap.getHeight();

    if (viewWidth <= 0 || viewHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
      Log.e(TAG, "Invalid dimensions for coordinate transformation");
      return imageCoordinates;
    }

    // Calculate the scaling factor and offset for FIT_CENTER
    float scale;
    float offsetX = 0;
    float offsetY = 0;

    // Calculate scaling based on the aspect ratio
    float bitmapAspect = (float) bitmapWidth / bitmapHeight;
    float viewAspect = (float) viewWidth / viewHeight;

    if (bitmapAspect > viewAspect) {
      // Image is wider than view (letterboxed)
      scale = (float) viewWidth / bitmapWidth;
      offsetY = (viewHeight - (bitmapHeight * scale)) / 2;
    } else {
      // Image is taller than view (pillarboxed)
      scale = (float) viewHeight / bitmapHeight;
      offsetX = (viewWidth - (bitmapWidth * scale)) / 2;
    }

    // Transform each point
    Point[] viewCoordinates = new Point[imageCoordinates.length];
    for (int i = 0; i < imageCoordinates.length; i++) {
      // Scale to view coordinates
      double viewX = imageCoordinates[i].x * scale + offsetX;
      double viewY = imageCoordinates[i].y * scale + offsetY;

      viewCoordinates[i] = new Point(viewX, viewY);
    }

    return viewCoordinates;
  }

  /**
   * Remaps a point given in view coordinates of an old view size to the corresponding view
   * coordinates of a new view size, assuming the bitmap is displayed with FIT_CENTER in both cases.
   *
   * <p>This is required when a view is resized (e.g. IME/inset-driven size jumps during initial
   * layout): the displayed image rect changes non-linearly with the view size (scale + centering
   * offset), so a plain proportional scaling relative to the view would shift points off the
   * underlying image features.
   *
   * @param x X coordinate in the old view's coordinate space
   * @param y Y coordinate in the old view's coordinate space
   * @param bitmapWidth Width of the displayed bitmap
   * @param bitmapHeight Height of the displayed bitmap
   * @param oldViewWidth Width of the old view
   * @param oldViewHeight Height of the old view
   * @param newViewWidth Width of the new view
   * @param newViewHeight Height of the new view
   * @return A {@code double[2]} with the remapped {x, y} in the new view's coordinate space, or
   *     {@code null} if any dimension is non-positive.
   */
  public static double[] remapPointBetweenFitCenterViews(
      double x,
      double y,
      int bitmapWidth,
      int bitmapHeight,
      int oldViewWidth,
      int oldViewHeight,
      int newViewWidth,
      int newViewHeight) {

    if (bitmapWidth <= 0
        || bitmapHeight <= 0
        || oldViewWidth <= 0
        || oldViewHeight <= 0
        || newViewWidth <= 0
        || newViewHeight <= 0) {
      return null;
    }

    float[] oldFit = computeFitCenter(bitmapWidth, bitmapHeight, oldViewWidth, oldViewHeight);
    float[] newFit = computeFitCenter(bitmapWidth, bitmapHeight, newViewWidth, newViewHeight);

    // old view -> image space
    double imageX = (x - oldFit[1]) / oldFit[0];
    double imageY = (y - oldFit[2]) / oldFit[0];

    // image space -> new view
    double newX = imageX * newFit[0] + newFit[1];
    double newY = imageY * newFit[0] + newFit[2];

    return new double[] {newX, newY};
  }

  /**
   * Computes the FIT_CENTER transform of a bitmap inside a view.
   *
   * @return {@code float[3]} = {scale, offsetX, offsetY}
   */
  private static float[] computeFitCenter(
      int bitmapWidth, int bitmapHeight, int viewWidth, int viewHeight) {
    float scale;
    float offsetX = 0;
    float offsetY = 0;

    float bitmapAspect = (float) bitmapWidth / bitmapHeight;
    float viewAspect = (float) viewWidth / viewHeight;

    if (bitmapAspect > viewAspect) {
      // Image is wider than view (letterboxed)
      scale = (float) viewWidth / bitmapWidth;
      offsetY = (viewHeight - (bitmapHeight * scale)) / 2;
    } else {
      // Image is taller than view (pillarboxed)
      scale = (float) viewHeight / bitmapHeight;
      offsetX = (viewWidth - (bitmapWidth * scale)) / 2;
    }

    return new float[] {scale, offsetX, offsetY};
  }
}
