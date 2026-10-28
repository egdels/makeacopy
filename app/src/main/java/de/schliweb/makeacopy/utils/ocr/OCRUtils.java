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

import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * OCR language selection: the PaddleOCR language group for the user's choice or the system locale,
 * and display names of the groups.
 *
 * <p>This class is not intended to be instantiated.
 */
@UtilityClass
public class OCRUtils {

  /**
   * The language spec to use: {@code languageOpt} if set, else the PaddleOCR language group of the
   * system language.
   */
  public static String resolveEffectiveLanguage(String languageOpt) {
    if (languageOpt != null && !languageOpt.trim().isEmpty()) {
      return languageOpt;
    }
    return mapSystemLanguageToPaddleModel(Locale.getDefault());
  }

  /** The PaddleOCR language group for a system locale; Latin for anything not listed. */
  public static String mapSystemLanguageToPaddleModel(Locale locale) {
    String language = locale != null ? locale.getLanguage() : "";
    return switch (language) {
      case "en" -> "en";
      case "ru", "be", "uk" -> "eslav";
      case "bg", "mk", "mn", "sr" -> "cyrillic";
      case "ar", "fa", "ur", "ps" -> "arabic";
      case "hi", "mr", "ne", "sa" -> "devanagari";
      case "th" -> "th";
      case "el" -> "el";
      case "zh", "ja", "ko" -> "zh";
      default -> "latin";
    };
  }

  /** Display name of a PaddleOCR language group, e.g. "Latin script (Paddle)". */
  public static String codeToDisplayName(android.content.Context context, String code) {
    return codeToBaseDisplayName(code);
  }

  /** Display name of a PaddleOCR language group, e.g. "Latin script (Paddle)". */
  public static String codeToBaseDisplayName(String code) {
    return switch (code) {
      case "en" -> "English (Paddle)";
      case "latin" -> "Latin script (Paddle)";
      case "eslav" -> "East Slavic (Paddle)";
      case "cyrillic" -> "Cyrillic script (Paddle)";
      case "arabic" -> "Arabic script (Paddle)";
      case "devanagari" -> "Devanagari script (Paddle)";
      case "th" -> "Thai (Paddle)";
      case "el" -> "Greek (Paddle)";
      case "zh" -> "Chinese/Japanese/Korean (Paddle)";
      default -> code + " (Paddle)";
    };
  }
}
