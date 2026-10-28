/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import android.content.Context;
import de.schliweb.makeacopy.utils.ocr.paddle.PaddleEngineFactory;
import java.util.ArrayList;
import java.util.List;

/** The PaddleOCR language groups the app offers. */
public final class OcrModelManager {
  private static final String[] AVAILABLE_LANGUAGE_CODES =
      new String[] {"en", "latin", "eslav", "cyrillic", "arabic", "devanagari", "th", "el", "zh"};

  private OcrModelManager() {
    // no instances
  }

  /** The language groups whose recognition model the APK carries (Light: only "latin"). */
  public static String[] getAvailableLanguageCodes(Context context) {
    if (context == null) return AVAILABLE_LANGUAGE_CODES.clone();
    List<String> out = new ArrayList<>();
    for (String code : AVAILABLE_LANGUAGE_CODES) {
      if (PaddleEngineFactory.isModelBundled(context, code)) out.add(code);
    }
    return out.toArray(new String[0]);
  }
}
