/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.export;

/**
 * Supported page formats for PDF export.
 *
 * <p>{@link #FIT_TO_IMAGE} adapts the PDF page size to the actual image aspect ratio, avoiding
 * letterboxing and data loss. The fixed formats ({@link #A4}, {@link #US_LETTER}, {@link #LEGAL})
 * scale the image to fit the chosen paper size.
 */
public enum PageFormat {

  /** Page size matches the image aspect ratio — no letterboxing, no data loss. */
  FIT_TO_IMAGE,

  /** ISO A4: 210 × 297 mm (8.27 × 11.69 in). */
  A4,

  /** US Letter: 8.5 × 11 in (215.9 × 279.4 mm). */
  US_LETTER,

  /** US Legal: 8.5 × 14 in (215.9 × 355.6 mm). */
  LEGAL;

  /**
   * Returns the page size for this format. For {@link #FIT_TO_IMAGE} the caller must provide the
   * prepared bitmap dimensions; the page is sized so that the longer edge equals the A4 long edge
   * (842 pt) and the shorter edge is proportional to the image aspect ratio.
   *
   * @param imageWidth width of the prepared bitmap (only used for {@link #FIT_TO_IMAGE})
   * @param imageHeight height of the prepared bitmap (only used for {@link #FIT_TO_IMAGE})
   * @return {width, height} of the page in PDF points (1/72 in)
   */
  public float[] toPageSize(int imageWidth, int imageHeight) {
    switch (this) {
      case US_LETTER:
        return new float[] {612f, 792f}; // 8.5 × 11 in @ 72 DPI
      case LEGAL:
        return new float[] {612f, 1008f}; // 8.5 × 14 in @ 72 DPI
      case FIT_TO_IMAGE:
        {
          float maxDim = A4_HEIGHT_PT;
          float imgAspect = (float) imageWidth / imageHeight;
          float w, h;
          if (imgAspect >= 1f) {
            w = maxDim;
            h = maxDim / imgAspect;
          } else {
            h = maxDim;
            w = maxDim * imgAspect;
          }
          return new float[] {w, h};
        }
      case A4:
      default:
        return new float[] {A4_WIDTH_PT, A4_HEIGHT_PT};
    }
  }

  /** ISO A4 in points (1/72 in): 210 × 297 mm. */
  private static final float A4_WIDTH_PT = 210f / 25.4f * 72f;

  private static final float A4_HEIGHT_PT = 297f / 25.4f * 72f;

  /**
   * Returns the maximum pixel dimensions for this format at the given DPI. For {@link
   * #FIT_TO_IMAGE} returns {@code null} — the caller should not constrain the image to a fixed
   * paper size.
   *
   * @param dpi target resolution in dots per inch
   * @return an {@code int[2]} array {width, height} in pixels, or {@code null} for {@link
   *     #FIT_TO_IMAGE}
   */
  public int[] pixelsForDpi(int dpi) {
    float wInch, hInch;
    switch (this) {
      case FIT_TO_IMAGE:
        return null; // no fixed-format constraint
      case US_LETTER:
        wInch = 8.5f;
        hInch = 11.0f;
        break;
      case LEGAL:
        wInch = 8.5f;
        hInch = 14.0f;
        break;
      case A4:
      default:
        wInch = 8.27f;
        hInch = 11.69f;
        break;
    }
    return new int[] {Math.max(1, Math.round(wInch * dpi)), Math.max(1, Math.round(hInch * dpi))};
  }

  /**
   * Parses a page format from its enum name, returning the given default if parsing fails.
   *
   * @param name the enum name (may be null)
   * @param defaultValue fallback value
   * @return the parsed {@link PageFormat} or {@code defaultValue}
   */
  public static PageFormat fromName(String name, PageFormat defaultValue) {
    if (name == null) return defaultValue;
    try {
      return valueOf(name);
    } catch (IllegalArgumentException e) {
      return defaultValue;
    }
  }
}
