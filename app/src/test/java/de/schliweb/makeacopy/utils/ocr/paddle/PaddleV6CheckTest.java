/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr.paddle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** On which pages PP-OCRv6 checks the lines the language group's v5 model is unsure of. */
public class PaddleV6CheckTest {

  @Test
  public void v6ChecksLatinAndChineseJapanesePages() {
    assertTrue(PaddleLanguageRouter.readsWithV6("latin"));
    assertTrue(PaddleLanguageRouter.readsWithV6("zh"));
    assertTrue(PaddleLanguageRouter.readsWithV6(PaddleLanguageRouter.resolveRecModel("eng")));
    assertFalse(PaddleLanguageRouter.readsWithV6("eslav"));
    assertFalse(PaddleLanguageRouter.readsWithV6("arabic"));
    assertFalse(PaddleLanguageRouter.readsWithV6(null));
  }

  @Test
  public void v6IsNoLanguageGroup() {
    assertEquals(
        "PP-OCRv6_small_rec",
        PaddleLanguageRouter.assetBaseName(PaddleLanguageRouter.V6_MODEL_KEY));
    assertFalse(PaddleLanguageRouter.MODEL_KEYS.contains(PaddleLanguageRouter.V6_MODEL_KEY));
    assertNull(PaddleLanguageRouter.resolveRecModel(PaddleLanguageRouter.V6_MODEL_KEY));
  }
}
