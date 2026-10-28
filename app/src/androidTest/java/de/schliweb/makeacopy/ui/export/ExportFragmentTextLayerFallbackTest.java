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
import static org.junit.Assert.assertSame;

import android.graphics.Bitmap;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ExportFragmentTextLayerFallbackTest {

  @Test
  public void ensurePdfTextLayerWords_keepsExistingWords() {
    Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
    List<RecognizedWord> words = new ArrayList<>();
    words.add(new RecognizedWord("مرحبا", new android.graphics.RectF(10, 10, 90, 30), 99f));

    List<RecognizedWord> result = ExportFragment.ensurePdfTextLayerWords(words, "مرحبا", bitmap);

    assertSame(words, result);
  }

  @Test
  public void ensurePdfTextLayerWords_buildsFallbackWordsFromArabicText() {
    Bitmap bitmap = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888);

    List<RecognizedWord> result =
        ExportFragment.ensurePdfTextLayerWords(null, "العربية\nمثال للتعرف على النصوص", bitmap);

    assertFalse(result.isEmpty());
    assertEquals(2, result.size());
    assertEquals("العربية", result.get(0).getText());
    assertEquals("مثال للتعرف على النصوص", result.get(1).getText());
  }
}