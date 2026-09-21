/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.jobs;

import static org.junit.Assert.assertEquals;

import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import org.junit.Test;

/** Unit tests for the pure decision logic of {@link OcrBackgroundJobs}. */
public class OcrBackgroundJobsTest {

  // ==================== resolvePrepMode ====================

  @Test
  public void resolvePrepMode_legacyQuick_migratesToRobust() {
    assertEquals(
        OCRHelper.OCR_MODE_ROBUST,
        OcrBackgroundJobs.resolvePrepMode(OCRHelper.OCR_MODE_QUICK, false, false));
  }

  @Test
  public void resolvePrepMode_originalAndRobust_areKept() {
    assertEquals(
        OCRHelper.OCR_MODE_ORIGINAL,
        OcrBackgroundJobs.resolvePrepMode(OCRHelper.OCR_MODE_ORIGINAL, true, false));
    assertEquals(
        OCRHelper.OCR_MODE_ROBUST,
        OcrBackgroundJobs.resolvePrepMode(OCRHelper.OCR_MODE_ROBUST, false, true));
  }

  @Test
  public void resolvePrepMode_legacyPaddleToggle_winsWhenToggleVisible() {
    assertEquals(
        OCRHelper.OCR_MODE_PADDLE,
        OcrBackgroundJobs.resolvePrepMode(OCRHelper.OCR_MODE_ROBUST, true, true));
    assertEquals(
        OCRHelper.OCR_MODE_PADDLE,
        OcrBackgroundJobs.resolvePrepMode(OCRHelper.OCR_MODE_QUICK, true, true));
  }

  @Test
  public void resolvePrepMode_persistedPaddle_fallsBackToRobustWhenNotApplicable() {
    assertEquals(
        OCRHelper.OCR_MODE_ROBUST,
        OcrBackgroundJobs.resolvePrepMode(OCRHelper.OCR_MODE_PADDLE, false, true));
    assertEquals(
        OCRHelper.OCR_MODE_PADDLE,
        OcrBackgroundJobs.resolvePrepMode(OCRHelper.OCR_MODE_PADDLE, true, false));
  }
}
