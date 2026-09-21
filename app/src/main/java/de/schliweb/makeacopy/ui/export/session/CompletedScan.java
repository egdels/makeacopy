/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.export.session;

import android.graphics.Bitmap;
import androidx.annotation.Nullable;

/**
 * Represents a completed scan, encapsulating all necessary data about the scan result and its
 * metadata.
 *
 * <p>Instances of this class provide information such as the scan's unique identifier, file paths
 * for the scanned document and associated data, orientation details, dimensions, and creation
 * timestamp. An optional in-memory bitmap reference can be included for use in UI operations, such
 * as rendering thumbnails.
 *
 * <p>Fields: - id: A unique identifier for the scan (e.g., UUID string). - filePath: An optional
 * file path to the scanned document. - rotationDeg: The rotation angle of the scan in degrees
 * (valid values are 0, 90, 180, 270). - ocrTextPath: An optional file path to the OCR (Optical
 * Character Recognition) text data for the scan. - thumbPath: An optional file path to a thumbnail
 * version of the scan. - createdAt: The timestamp (in milliseconds) when the scan was created. -
 * widthPx: The width of the scanned image in pixels. - heightPx: The height of the scanned image in
 * pixels. - inMemoryBitmap: An optional in-memory bitmap used for UI thumbnails or other runtime
 * purposes.
 *
 * @param id could be UUID string
 * @param filePath optional until registry is added
 * @param rotationDeg 0, 90, 180, 270
 * @param ocrTextPath not used in v1 increment
 * @param thumbPath not used in v1 increment
 * @param inMemoryBitmap Convenience: keep a reference to the in-memory bitmap for v1 (UI
 *     thumbnails)
 */
public record CompletedScan(
    String id,
    @Nullable String filePath,
    int rotationDeg,
    @Nullable String ocrTextPath,
    @Nullable String ocrFormat,
    @Nullable String thumbPath,
    long createdAt,
    int widthPx,
    int heightPx,
    @Nullable Bitmap inMemoryBitmap,
    int schemaVersion,
    @Nullable String orientationMode,
    @Nullable String sourceType,
    int pdfPageIndex,
    @Nullable String pageStatus) {

  /** Source type for pages captured with the camera (default for legacy entries). */
  public static final String SOURCE_CAMERA = "camera";

  /** Source type for pages imported from an image file. */
  public static final String SOURCE_IMAGE = "image";

  /** Source type for pages imported from a PDF document. */
  public static final String SOURCE_PDF = "pdf";

  /** Transient status while a page is being materialized (never expected to persist). */
  public static final String STATUS_IMPORTING = "IMPORTING";

  /** Page is fully materialized (page.jpg exists) but has no OCR result yet. */
  public static final String STATUS_IMPORTED = "IMPORTED";

  /** Page is queued for OCR in a batch run but processing has not started yet. */
  public static final String STATUS_OCR_PENDING = "OCR_PENDING";

  /**
   * Transient status while OCR is running for the page. Like {@link #STATUS_IMPORTING} this is
   * never expected to persist; loaded entries are normalized in the compact constructor so that a
   * process death can never leave permanent OCR_PROCESSING "zombies".
   */
  public static final String STATUS_OCR_PROCESSING = "OCR_PROCESSING";

  /** Page has an OCR result. */
  public static final String STATUS_OCR_COMPLETE = "OCR_COMPLETE";

  /** OCR was attempted for the page but failed. */
  public static final String STATUS_OCR_FAILED = "OCR_FAILED";

  /** Sentinel value of {@link #pdfPageIndex()} for pages that do not originate from a PDF. */
  public static final int NO_PDF_PAGE = -1;

  /**
   * Constructs a CompletedScan object representing a completed scan, encapsulating various metadata
   * and associated information about the scan.
   *
   * @param id A unique identifier for the scan, typically a UUID string.
   * @param filePath An optional file path pointing to the scanned document. May be null if not
   *     available.
   * @param rotationDeg The rotation angle of the scanned document, specified in degrees. Valid
   *     values are 0, 90, 180, or 270.
   * @param ocrTextPath An optional file path pointing to the OCR payload for the scan. May be null
   *     if not available.
   * @param ocrFormat Optional string describing the OCR payload format (e.g., "plain", "hocr",
   *     "alto", "words_json"). May be null to imply "plain" for backward compatibility.
   * @param thumbPath An optional file path pointing to a thumbnail version of the scan. May be null
   *     if not available.
   * @param createdAt The timestamp when the scan was created, specified in milliseconds since the
   *     Unix epoch.
   * @param widthPx The width of the scanned image, measured in pixels.
   * @param heightPx The height of the scanned image, measured in pixels.
   * @param inMemoryBitmap An optional in-memory bitmap representation of the scanned document,
   *     typically used for UI thumbnails. May be null if not available.
   */
  public CompletedScan {
    // Normalize defaults for backward compatibility when callers pass 0/ null
    if (orientationMode == null || orientationMode.isEmpty()) {
      orientationMode = "baked"; // safe default for legacy entries
    }
    if (schemaVersion <= 0) {
      schemaVersion = 1; // legacy entries
    }
    // Multi-page additive fields (Session 1): safe defaults for legacy entries
    if (sourceType == null || sourceType.isEmpty()) {
      sourceType = SOURCE_CAMERA; // legacy entries were camera captures or treated as such
    }
    if (!SOURCE_PDF.equals(sourceType) || pdfPageIndex < 0) {
      pdfPageIndex = NO_PDF_PAGE; // only meaningful for PDF-sourced pages
    }
    if (pageStatus == null || pageStatus.isEmpty()) {
      // Derive status for legacy entries: OCR present -> OCR_COMPLETE, otherwise IMPORTED
      pageStatus = (ocrTextPath != null) ? STATUS_OCR_COMPLETE : STATUS_IMPORTED;
    } else if (STATUS_IMPORTING.equals(pageStatus) && filePath != null) {
      // Recovery normalization: a fully materialized page must not stay in IMPORTING
      pageStatus = STATUS_IMPORTED;
    } else if (STATUS_OCR_PROCESSING.equals(pageStatus)) {
      // Recovery normalization after process death: OCR_PROCESSING must not survive a reload.
      // If a complete OCR artifact exists the page is effectively done; otherwise it becomes
      // re-startable via OCR_PENDING.
      pageStatus = (ocrTextPath != null) ? STATUS_OCR_COMPLETE : STATUS_OCR_PENDING;
    }
  }

  /**
   * Backward-compatible constructor matching the pre-multi-page signature. Delegates to the
   * canonical constructor with safe defaults for {@code sourceType}, {@code pdfPageIndex} and
   * {@code pageStatus} (normalized in the compact constructor).
   */
  public CompletedScan(
      String id,
      @Nullable String filePath,
      int rotationDeg,
      @Nullable String ocrTextPath,
      @Nullable String ocrFormat,
      @Nullable String thumbPath,
      long createdAt,
      int widthPx,
      int heightPx,
      @Nullable Bitmap inMemoryBitmap,
      int schemaVersion,
      @Nullable String orientationMode) {
    this(
        id,
        filePath,
        rotationDeg,
        ocrTextPath,
        ocrFormat,
        thumbPath,
        createdAt,
        widthPx,
        heightPx,
        inMemoryBitmap,
        schemaVersion,
        orientationMode,
        null,
        NO_PDF_PAGE,
        null);
  }

  /**
   * Returns a copy with the given OCR artifact and page status; all other fields are preserved
   * (including the multi-page metadata {@code sourceType}/{@code pdfPageIndex}).
   */
  public CompletedScan withOcr(
      @Nullable String ocrTextPath, @Nullable String ocrFormat, @Nullable String pageStatus) {
    return new CompletedScan(
        id,
        filePath,
        rotationDeg,
        ocrTextPath,
        ocrFormat,
        thumbPath,
        createdAt,
        widthPx,
        heightPx,
        inMemoryBitmap,
        schemaVersion,
        orientationMode,
        sourceType,
        pdfPageIndex,
        pageStatus);
  }
}
