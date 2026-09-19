/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.data;

import androidx.annotation.Nullable;

/**
 * Represents an entry for a completed scan, containing metadata about the scan and associated file
 * paths. This class is typically used to store and transfer information about a single scanned
 * document.
 */
public class CompletedScanEntry {
  public String id;
  @Nullable public String filePath;
  public int rotationDeg;
  @Nullable public String ocrTextPath;

  @Nullable
  public String
      ocrFormat; // "plain" | "hocr" | "alto" | "words_json" (optional; null implies "plain")

  @Nullable public String thumbPath;
  public long createdAt;
  public int widthPx;
  public int heightPx;
  // New fields for rotation unification
  public int schemaVersion; // defaults to 1 for legacy
  @Nullable public String orientationMode; // "baked" | "metadata"; defaults to "baked"
  // Multi-page additive fields (Session 1); all optional for legacy entries
  @Nullable public String sourceType; // "camera" | "image" | "pdf"; null implies "camera"
  public int pdfPageIndex; // only meaningful when sourceType == "pdf"; otherwise -1/0 (legacy)
  @Nullable public String pageStatus; // e.g. "IMPORTED" | "OCR_COMPLETE"; null → derived

  public CompletedScanEntry() {}

  public CompletedScanEntry(
      String id,
      @Nullable String filePath,
      int rotationDeg,
      @Nullable String ocrTextPath,
      @Nullable String ocrFormat,
      @Nullable String thumbPath,
      long createdAt,
      int widthPx,
      int heightPx,
      int schemaVersion,
      @Nullable String orientationMode) {
    this.id = id;
    this.filePath = filePath;
    this.rotationDeg = rotationDeg;
    this.ocrTextPath = ocrTextPath;
    this.ocrFormat = ocrFormat;
    this.thumbPath = thumbPath;
    this.createdAt = createdAt;
    this.widthPx = widthPx;
    this.heightPx = heightPx;
    this.schemaVersion = (schemaVersion <= 0) ? 1 : schemaVersion;
    this.orientationMode =
        (orientationMode == null || orientationMode.isEmpty()) ? "baked" : orientationMode;
  }

  public CompletedScanEntry(
      String id,
      @Nullable String filePath,
      int rotationDeg,
      @Nullable String ocrTextPath,
      @Nullable String ocrFormat,
      @Nullable String thumbPath,
      long createdAt,
      int widthPx,
      int heightPx,
      int schemaVersion,
      @Nullable String orientationMode,
      @Nullable String sourceType,
      int pdfPageIndex,
      @Nullable String pageStatus) {
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
        schemaVersion,
        orientationMode);
    this.sourceType = sourceType;
    this.pdfPageIndex = pdfPageIndex;
    this.pageStatus = pageStatus;
  }
}
