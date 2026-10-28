/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DocumentCleanupProcessorTest {

  @Test
  public void originalOptionsAreConservativeByDefault() {
    DocumentCleanupOptions options = new DocumentCleanupOptions();

    assertEquals(DocumentCleanupMode.ORIGINAL, options.mode);
    assertTrue(options.preserveColor);
    assertEquals(0, options.targetLongEdgePx);
  }

  @Test
  public void enhancedPresetKeepsOrDropsColour() {
    assertEquals(DocumentCleanupMode.ENHANCED, DocumentCleanupOptions.enhanced(false).mode);
    assertFalse(DocumentCleanupOptions.enhanced(false).preserveColor);
    assertEquals(DocumentCleanupMode.ENHANCED, DocumentCleanupOptions.enhanced(true).mode);
    assertTrue(DocumentCleanupOptions.enhanced(true).preserveColor);
  }

  @Test
  public void explicitOptionsClampTargetSize() {
    DocumentCleanupOptions options = new DocumentCleanupOptions(DocumentCleanupMode.ENHANCED, true, -1);

    assertEquals(DocumentCleanupMode.ENHANCED, options.mode);
    assertEquals(0, options.targetLongEdgePx);
  }

  @Test
  public void savedFormerPresetsBecomeEnhanced() {
    assertEquals(DocumentCleanupMode.ENHANCED, DocumentCleanupMode.fromPreference("NATURAL"));
    assertEquals(DocumentCleanupMode.ENHANCED, DocumentCleanupMode.fromPreference("CLEAN_TEXT"));
    assertEquals(DocumentCleanupMode.ENHANCED, DocumentCleanupMode.fromPreference("ENHANCED"));
    assertEquals(DocumentCleanupMode.ORIGINAL, DocumentCleanupMode.fromPreference("ORIGINAL"));
    assertEquals(DocumentCleanupMode.ORIGINAL, DocumentCleanupMode.fromPreference(null));
    assertEquals(DocumentCleanupMode.ORIGINAL, DocumentCleanupMode.fromPreference("bogus"));
  }
}
