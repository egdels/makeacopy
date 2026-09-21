/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.crop;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import de.schliweb.makeacopy.ui.BaseViewModel;
import lombok.Getter;
import lombok.Setter;

/**
 * ViewModel class for managing image cropping operations. Provides LiveData to observe the state
 * and data of the image cropping process. Extends the functionality of BaseViewModel.
 */
public class CropViewModel extends BaseViewModel {

  private final MutableLiveData<Boolean> mImageLoaded;
  private final MutableLiveData<Bitmap> mImageBitmap;
  private final MutableLiveData<Bitmap> mOriginalImageBitmap;
  private final MutableLiveData<Boolean> mImageCropped;

  // Rotation inferred from capture/EXIF (pre-crop)
  private final MutableLiveData<Integer> captureRotationDegrees = new MutableLiveData<>(0);
  // User-requested rotation after cropping (applied before OCR/export)
  private final MutableLiveData<Integer> userRotationDegrees = new MutableLiveData<>(0);
  // OCR: best rotation (additional 0/90/180/270 tried by OCR). Relative to current user rotation.
  private final MutableLiveData<Integer> bestOcrRotationDegrees = new MutableLiveData<>(0);

  public CropViewModel() {
    super("Crop Fragment");

    mImageLoaded = new MutableLiveData<>();
    mImageLoaded.setValue(false);

    mImageBitmap = new MutableLiveData<>();
    mOriginalImageBitmap = new MutableLiveData<>();

    mImageCropped = new MutableLiveData<>();
    mImageCropped.setValue(false);
  }

  public LiveData<Boolean> isImageLoaded() {
    return mImageLoaded;
  }

  public void setImageLoaded(boolean loaded) {
    mImageLoaded.setValue(loaded);
  }

  /**
   * Gets the bitmap of the image to crop
   *
   * @return LiveData containing the image bitmap
   */
  public LiveData<Bitmap> getImageBitmap() {
    return mImageBitmap;
  }

  /**
   * Sets the bitmap of the image to crop
   *
   * @param bitmap The bitmap of the image
   */
  public void setImageBitmap(Bitmap bitmap) {
    mImageBitmap.setValue(bitmap);
    setImageLoaded(bitmap != null);
  }

  /**
   * Checks if the image has been cropped
   *
   * @return LiveData containing the cropped status
   */
  public LiveData<Boolean> isImageCropped() {
    return mImageCropped;
  }

  /**
   * Sets whether the image has been cropped
   *
   * @param cropped True if the image has been cropped, false otherwise
   */
  public void setImageCropped(boolean cropped) {
    mImageCropped.setValue(cropped);
  }

  /**
   * Gets the original uncropped bitmap of the image
   *
   * @return LiveData containing the original image bitmap
   */
  public LiveData<Bitmap> getOriginalImageBitmap() {
    return mOriginalImageBitmap;
  }

  /**
   * Sets the original uncropped bitmap of the image
   *
   * @param bitmap The original bitmap of the image
   */
  public void setOriginalImageBitmap(Bitmap bitmap) {
    mOriginalImageBitmap.setValue(bitmap);
  }

  /**
   * Retrieves the rotation degrees applied to the captured image.
   *
   * @return LiveData containing the rotation degrees as an Integer.
   */
  public LiveData<Integer> getCaptureRotationDegrees() {
    return captureRotationDegrees;
  }

  /**
   * Sets the rotation degrees for the captured image. The value is normalized to be within the
   * range [0, 360).
   *
   * @param deg The rotation degrees to set, can be any integer. The value will be adjusted to fall
   *     within 0 to 359 degrees.
   */
  public void setCaptureRotationDegrees(int deg) {
    Log.d("setCaptureRotationDegrees", "deg=" + deg);
    captureRotationDegrees.setValue(((deg % 360) + 360) % 360);
  }

  /** Returns the user-requested rotation degrees (applied after crop, before OCR/export). */
  public LiveData<Integer> getUserRotationDegrees() {
    return userRotationDegrees;
  }

  /** Sets an absolute user rotation value in degrees (normalized to [0, 360)). */
  public void setUserRotationDegrees(int deg) {
    Log.d("setUserRotationDegrees", "deg=" + deg);
    userRotationDegrees.setValue(((deg % 360) + 360) % 360);
  }

  /**
   * Returns the best additional rotation (0/90/180/270) chosen by OCR trials. This is defined
   * relative to the userRotationDegrees at OCR start.
   */
  public LiveData<Integer> getBestOcrRotationDegrees() {
    return bestOcrRotationDegrees;
  }

  /** Sets the best additional rotation (0/90/180/270) chosen by OCR trials. */
  public void setBestOcrRotationDegrees(int deg) {
    int v = ((deg % 360) + 360) % 360;
    Log.d("setBestOcrRotationDegrees", "deg=" + v);
    bestOcrRotationDegrees.setValue(v);
  }

  /** Convenience: rotate 90° clockwise (right). */
  public void rotateRight() {
    Integer v = userRotationDegrees.getValue();
    int cur = (v == null ? 0 : v);
    setUserRotationDegrees(cur + 90);
  }

  /** Convenience: rotate 90° counter-clockwise (left). */
  public void rotateLeft() {
    Integer v = userRotationDegrees.getValue();
    int cur = (v == null ? 0 : v);
    setUserRotationDegrees(cur - 90);
  }

  // ---------------------------------------------------------------------------
  // Re-Edit support (FR #72)
  //
  // The following fields enable a Re-Edit roundtrip from the Export screen back
  // to CropFragment with the previously accepted trapezoid corners restored.
  //
  // Convention for lastAcceptedCornersOriginal:
  //   - 4 points, in the order returned by TrapezoidSelectionView#getCorners()
  //   - Coordinates are in the pixel space of the ROTATED full-resolution source
  //     used for cropping (i.e. originalImageBitmap rotated by lastAcceptedUserRotationDeg).
  //   - On Re-Edit, restoring userRotationDegrees to lastAcceptedUserRotationDeg yields the
  //     same source, so the stored corners can be re-applied directly (after scaling
  //     full-res → displayed bitmap and view).
  // ---------------------------------------------------------------------------

  /**
   * Last accepted trapezoid corners in coordinates of the unrotated original image. Order: [TL, TR,
   * BR, BL]. Null if no crop has been accepted yet.
   */
  private final MutableLiveData<PointF[]> lastAcceptedCornersOriginal = new MutableLiveData<>();

  /**
   * User rotation (degrees, normalized to [0,360)) that was active when the corners in {@link
   * #lastAcceptedCornersOriginal} were accepted. Stored alongside the corners so the Re-Edit can
   * reproduce the visible orientation deterministically.
   */
  private final MutableLiveData<Integer> lastAcceptedUserRotationDeg = new MutableLiveData<>(0);

  /**
   * Routing flag: when true, CropFragment was entered via the Re-Edit overlay in ExportFragment.
   * Used to decide back-navigation target and to pre-populate the trapezoid with the last accepted
   * corners.
   */
  private final MutableLiveData<Boolean> cameFromExport = new MutableLiveData<>(false);

  /** Returns the last accepted trapezoid corners (in unrotated original image coords), or null. */
  public LiveData<PointF[]> getLastAcceptedCornersOriginal() {
    return lastAcceptedCornersOriginal;
  }

  /**
   * Stores the last accepted trapezoid corners. A defensive copy is taken to decouple the caller's
   * array from the stored value.
   *
   * @param corners 4 points in unrotated original image coordinates, order [TL, TR, BR, BL], or
   *     null to clear.
   */
  public void setLastAcceptedCornersOriginal(PointF[] corners) {
    if (corners == null) {
      lastAcceptedCornersOriginal.setValue(null);
      return;
    }
    PointF[] copy = new PointF[corners.length];
    for (int i = 0; i < corners.length; i++) {
      copy[i] = corners[i] == null ? null : new PointF(corners[i].x, corners[i].y);
    }
    lastAcceptedCornersOriginal.setValue(copy);
  }

  /** Returns the user rotation (deg) that was active when the last corners were accepted. */
  public LiveData<Integer> getLastAcceptedUserRotationDeg() {
    return lastAcceptedUserRotationDeg;
  }

  /** Stores the user rotation that was active when the last corners were accepted. */
  public void setLastAcceptedUserRotationDeg(int deg) {
    lastAcceptedUserRotationDeg.setValue(((deg % 360) + 360) % 360);
  }

  /** True when CropFragment was entered via the Re-Edit overlay in ExportFragment. */
  public LiveData<Boolean> isCameFromExport() {
    return cameFromExport;
  }

  /** Sets the Re-Edit routing flag. */
  public void setCameFromExport(boolean v) {
    cameFromExport.setValue(v);
  }

  /**
   * FR #72 multi-page: index of the page in {@code ExportSessionViewModel.pages} that is being
   * re-edited. Set by ExportFragment when the user taps the Edit overlay; read by CropFragment
   * after performCrop to update the correct session page (instead of hardcoded index 0). {@code -1}
   * means "unknown / single-page workflow".
   */
  @Setter @Getter private int reEditPageIndex = -1;

  /**
   * Session 3: stable id of the {@code CompletedScan} page being re-edited. Preferred over {@link
   * #reEditPageIndex} because pages can be moved or deleted while the editor is open — the id is
   * the only stable identifier. Set by ExportFragment when the user starts editing a page; cleared
   * by CropFragment on the return path. {@code null} means "unknown / legacy index-based flow".
   */
  @Setter @Getter private String reEditPageId = null;

  /**
   * Whether the running Re-Edit works on the page's ORIGINAL image (so the accepted corners are in
   * the original's coordinate space) rather than on its already cropped page image. Decides whether
   * the page's stored crop source stays valid after the edit.
   */
  @Setter @Getter private boolean reEditFromOriginal = true;

  /**
   * FR #72 V1.3 (multi-page filmstrip identity): identity reference to the in-memory bitmap of the
   * most recently confirmed crop. Held as a {@link java.lang.ref.WeakReference} to avoid extending
   * bitmap lifetime. Used by ExportFragment to decide whether the currently previewed page is the
   * freshly captured/re-edited one (and therefore Re-Edit-eligible). Identity match only; not
   * LiveData on purpose — visibility refreshes are driven by preview/page observers.
   */
  private java.lang.ref.WeakReference<Bitmap> lastFreshPageBitmapRef =
      new java.lang.ref.WeakReference<>(null);

  /** -- GETTER -- True when the current fresh-page bitmap came from Export → Crop Re-Edit. */
  @Getter private boolean lastFreshPageBitmapFromReEdit = false;

  /** Returns the cached fresh-page bitmap (identity reference) or null when GC'ed/never set. */
  public Bitmap getLastFreshPageBitmap() {
    return lastFreshPageBitmapRef.get();
  }

  /** Sets the identity reference to the freshly produced crop bitmap (post-trim, post-rotate). */
  public void setLastFreshPageBitmap(Bitmap bmp) {
    this.lastFreshPageBitmapRef = new java.lang.ref.WeakReference<>(bmp);
    this.lastFreshPageBitmapFromReEdit = false;
  }

  /** Sets the identity reference to a freshly re-edited crop bitmap. */
  public void setLastFreshReEditPageBitmap(Bitmap bmp) {
    this.lastFreshPageBitmapRef = new java.lang.ref.WeakReference<>(bmp);
    this.lastFreshPageBitmapFromReEdit = true;
  }
}
