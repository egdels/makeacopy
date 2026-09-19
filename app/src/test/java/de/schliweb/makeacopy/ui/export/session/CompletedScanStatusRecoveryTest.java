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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for the batch-OCR status recovery normalization in {@link CompletedScan}: transient
 * OCR_PROCESSING states must never survive a reload (process death), and OCR_PENDING must remain
 * re-startable.
 */
public class CompletedScanStatusRecoveryTest {

  private static CompletedScan scan(String ocrTextPath, String pageStatus) {
    return new CompletedScan(
        "id-1",
        "/data/scans/id-1/page.jpg",
        0,
        ocrTextPath,
        ocrTextPath != null ? "words_json" : null,
        null,
        123L,
        100,
        200,
        null,
        2,
        "baked",
        CompletedScan.SOURCE_PDF,
        3,
        pageStatus);
  }

  @Test
  public void ocrProcessingWithArtifactsNormalizesToComplete() {
    CompletedScan s = scan("/data/scans/id-1/words.json", CompletedScan.STATUS_OCR_PROCESSING);
    assertEquals(CompletedScan.STATUS_OCR_COMPLETE, s.pageStatus());
  }

  @Test
  public void ocrProcessingWithoutArtifactsNormalizesToPending() {
    CompletedScan s = scan(null, CompletedScan.STATUS_OCR_PROCESSING);
    assertEquals(CompletedScan.STATUS_OCR_PENDING, s.pageStatus());
  }

  @Test
  public void ocrPendingIsPreserved() {
    CompletedScan s = scan(null, CompletedScan.STATUS_OCR_PENDING);
    assertEquals(CompletedScan.STATUS_OCR_PENDING, s.pageStatus());
  }

  @Test
  public void ocrFailedIsPreserved() {
    CompletedScan s = scan(null, CompletedScan.STATUS_OCR_FAILED);
    assertEquals(CompletedScan.STATUS_OCR_FAILED, s.pageStatus());
  }

  @Test
  public void ocrCompleteIsPreserved() {
    CompletedScan s = scan("/data/scans/id-1/words.json", CompletedScan.STATUS_OCR_COMPLETE);
    assertEquals(CompletedScan.STATUS_OCR_COMPLETE, s.pageStatus());
  }

  @Test
  public void legacyImportingWithFileStillNormalizesToImported() {
    CompletedScan s = scan(null, CompletedScan.STATUS_IMPORTING);
    assertEquals(CompletedScan.STATUS_IMPORTED, s.pageStatus());
  }
}
