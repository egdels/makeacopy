/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import org.junit.Test;

/** Language of a background OCR job, and which pages count as interrupted. */
public class BackgroundOcrLanguageTest {

  @Test
  public void persistedChoiceWins() {
    assertEquals("eslav", BackgroundOcr.pickLanguage("eslav", "latin", lang -> true));
  }

  @Test
  public void withoutPersistedChoice_systemLanguageDecides() {
    assertEquals("latin", BackgroundOcr.pickLanguage(null, "latin", lang -> true));
    assertEquals("arabic", BackgroundOcr.pickLanguage(" ", "arabic", lang -> true));
  }

  @Test
  public void systemLanguageWithoutBundledModel_fallsBackToLatin() {
    assertEquals("latin", BackgroundOcr.pickLanguage(null, "arabic", "latin"::equals));
  }

  @Test
  public void onlyAPageStillWaitingWithoutTextCountsAsInterrupted() {
    assertTrue(BackgroundOcr.isInterrupted(page(null, CompletedScan.STATUS_OCR_PENDING)));
    // OCR_PROCESSING is normalised to OCR_PENDING when the entry is loaded
    assertTrue(BackgroundOcr.isInterrupted(page(null, CompletedScan.STATUS_OCR_PROCESSING)));
    assertFalse(BackgroundOcr.isInterrupted(page(null, CompletedScan.STATUS_IMPORTED)));
    assertFalse(BackgroundOcr.isInterrupted(page(null, CompletedScan.STATUS_OCR_FAILED)));
    assertFalse(BackgroundOcr.isInterrupted(page("/x/words.json", null)));
    assertFalse(BackgroundOcr.isInterrupted(null));
  }

  private static CompletedScan page(String ocrPath, String status) {
    return new CompletedScan(
        "p1",
        "/x/page.jpg",
        0,
        ocrPath,
        ocrPath != null ? "words_json" : null,
        null,
        0L,
        10,
        10,
        null,
        2,
        "baked",
        CompletedScan.SOURCE_CAMERA,
        CompletedScan.NO_PDF_PAGE,
        status);
  }
}
