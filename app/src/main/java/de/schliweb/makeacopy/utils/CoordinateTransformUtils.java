package de.schliweb.makeacopy.utils;

import android.graphics.Bitmap;
import android.util.Log;
import android.widget.ImageView;
import org.opencv.core.Point;

/**
 * Utility class for transforming coordinates between view space and image space.
 * This consolidates duplicate coordinate transformation methods from CropFragment and TrapezoidSelectionView.
 */
public class CoordinateTransformUtils {
    private static final String TAG = "CoordinateTransformUtils";

    private CoordinateTransformUtils() {
        // private because utility class
    }

    /**
     * Transforms coordinates from view space to image space
     *
     * @param viewCoordinates Array of points in view coordinates
     * @param bitmap          The image bitmap
     * @param viewWidth       The width of the view
     * @param viewHeight      The height of the view
     * @return Array of points in image coordinates
     */
    public static Point[] transformViewToImageCoordinates(Point[] viewCoordinates, Bitmap bitmap, int viewWidth, int viewHeight) {

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
     * @param bitmap          The image bitmap
     * @param imageView       The ImageView containing the bitmap
     * @return Array of points in image coordinates
     */
    public static Point[] transformViewToImageCoordinates(Point[] viewCoordinates, Bitmap bitmap, ImageView imageView) {

        if (viewCoordinates == null || bitmap == null || imageView == null) {
            return viewCoordinates;
        }

        return transformViewToImageCoordinates(viewCoordinates, bitmap, imageView.getWidth(), imageView.getHeight());
    }

    /**
     * Transforms coordinates from image space to view space
     *
     * @param imageCoordinates Array of points in image coordinates
     * @param bitmap           The image bitmap
     * @param viewWidth        The width of the view
     * @param viewHeight       The height of the view
     * @return Array of points in view coordinates
     */
    public static Point[] transformImageToViewCoordinates(Point[] imageCoordinates, Bitmap bitmap, int viewWidth, int viewHeight) {

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

}