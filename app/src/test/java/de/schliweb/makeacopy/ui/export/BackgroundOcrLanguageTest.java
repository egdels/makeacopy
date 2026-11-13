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

import org.junit.Test;

/** Language of a background OCR job when the OCR screen was never opened. */
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
}
