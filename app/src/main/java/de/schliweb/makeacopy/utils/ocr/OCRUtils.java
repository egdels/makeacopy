/*
 * Copyright 2025 Christian Kierdorf
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
 * A utility class providing methods for optimal OCR language selection and handling of supported
 * language configurations. This class is designed to work with Tesseract OCR language codes and
 * assists in determining effective language settings based on user preferences or the system's
 * locale.
 *
 * <p>This class is not intended to be instantiated.
 */
@UtilityClass
public class OCRUtils {

  private static final String TAG = "OCRUtils";

  /**
   * Resolves the effective language based on the provided language option and the system's default
   * locale. If a specific language option is given, it will return that language. Otherwise, it
   * resolves the appropriate language code based on the system's default language and region. The
   * returned language code is compatible with Tesseract OCR.
   *
   * @param languageOpt A string representing the optional language code. If null or empty, the
   *     method uses the system's default language to determine the effective language.
   * @return A string representing the effective language code. If no valid language can be
   *     determined, the default value "eng" (for English) is returned.
   */
  public static String resolveEffectiveLanguage(String languageOpt) {
    if (languageOpt != null && !languageOpt.trim().isEmpty()) {
      return languageOpt;
    }
    try {
      Locale loc = Locale.getDefault();
      String sys = loc.getLanguage();
      if ("zh".equalsIgnoreCase(sys)) {
        String country = loc.getCountry();
        if ("TW".equalsIgnoreCase(country)
            || "HK".equalsIgnoreCase(country)
            || "MO".equalsIgnoreCase(country)) {
          return "chi_tra";
        } else {
          return "chi_sim";
        }
      } else if ("de".equalsIgnoreCase(sys)) {
        return "deu";
      } else if ("fr".equalsIgnoreCase(sys)) {
        return "fra";
      } else if ("it".equalsIgnoreCase(sys)) {
        return "ita";
      } else if ("es".equalsIgnoreCase(sys)) {
        return "spa";
      } else if ("pt".equalsIgnoreCase(sys)) {
        return "por";
      } else if ("nl".equalsIgnoreCase(sys)) {
        return "nld";
      } else if ("pl".equalsIgnoreCase(sys)) {
        return "pol";
      } else if ("cs".equalsIgnoreCase(sys)) {
        return "ces";
      } else if ("ru".equalsIgnoreCase(sys)) {
        return "rus";
      } else if ("th".equalsIgnoreCase(sys)) {
        return "tha";
      } else if ("sk".equalsIgnoreCase(sys)) {
        return "slk";
      } else if ("hu".equalsIgnoreCase(sys)) {
        return "hun";
      } else if ("ro".equalsIgnoreCase(sys)) {
        return "ron";
      } else if ("da".equalsIgnoreCase(sys)) {
        return "dan";
      } else if ("sv".equalsIgnoreCase(sys)) {
        return "swe";
      } else if ("no".equalsIgnoreCase(sys)
          || "nb".equalsIgnoreCase(sys)
          || "nn".equalsIgnoreCase(sys)) {
        return "nor";
      } else if ("fa".equalsIgnoreCase(sys)) {
        return "fas";
      } else if ("ar".equalsIgnoreCase(sys)) {
        return "ara";
      } else if ("hi".equalsIgnoreCase(sys)) {
        return "hin";
      } else if ("tr".equalsIgnoreCase(sys)) {
        return "tur";
      } else {
        return "eng";
      }
    } catch (Throwable ignore) {
      return "eng";
    }
  }

  /**
   * Maps a system language code to the corresponding Tesseract OCR language code. If the provided
   * language is not recognized, defaults to "eng" (English). Special handling is applied for
   * Chinese to differentiate between simplified and traditional scripts based on the system region.
   *
   * @param systemLanguage A string representing the system language code (e.g., "en", "de", "zh").
   * @return A string representing the corresponding Tesseract OCR language code. Defaults to "eng"
   *     if the input language is not recognized.
   */
  public static String mapSystemLanguageToTesseract(String systemLanguage) {
    return switch (systemLanguage) {
      case "en" -> "eng";
      case "de" -> "deu";
      case "fr" -> "fra";
      case "it" -> "ita";
      case "es" -> "spa";
      case "pt" -> "por";
      case "nl" -> "nld";
      case "pl" -> "pol";
      case "cs" -> "ces";
      case "ru" -> "rus";
      case "th" -> "tha";
      case "sk" -> "slk";
      case "hu" -> "hun";
      case "ro" -> "ron";
      case "da" -> "dan";
      case "sv" -> "swe";
      case "no", "nb", "nn" -> "nor";
      case "fa" -> "fas";
      case "ar" -> "ara";
      case "hi" -> "hin";
      case "tr" -> "tur";
      case "zh" -> {
        // Map Chinese to Simplified or Traditional based on region, default to Simplified
        try {
          Locale loc = Locale.getDefault();
          String country = loc.getCountry();
          if ("TW".equalsIgnoreCase(country)
              || "HK".equalsIgnoreCase(country)
              || "MO".equalsIgnoreCase(country)) {
            yield "chi_tra";
          }
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
        yield "chi_sim";
      }
      default -> "eng";
    };
  }

  /**
   * Retrieves a list of supported language codes.
   *
   * @return An array of strings representing the language codes supported for OCR. The codes
   *     include "eng" (English), "deu" (German), "fra" (French), "ita" (Italian), "spa" (Spanish),
   *     "por" (Portuguese), "nld" (Dutch), "pol" (Polish), "ces" (Czech), "slk" (Slovak), "hun"
   *     (Hungarian), "ron" (Romanian), "dan" (Danish), "nor" (Norwegian), "swe" (Swedish), "rus"
   *     (Russian), "tha" (Thai), "fas" (Persian/Farsi), "ara" (Arabic), "hin" (Hindi), "tur"
   *     (Turkish), "chi_sim" (Simplified Chinese), and "chi_tra" (Traditional Chinese).
   */
  public static String[] getLanguages() {
    return new String[] {
      "eng", "deu", "fra", "ita", "spa", "por", "nld", "pol", "ces", "slk", "hun", "ron", "dan",
      "nor", "swe", "rus", "tha", "fas", "ara", "hin", "tur", "chi_sim", "chi_tra"
    };
  }

  /**
   * Maps an OCR language code to a human-readable, localized display name including the model
   * variant label (Fast/Best) resolved via the flavor-specific {@link OcrModelManager}. For the
   * PaddleOCR flavor the Paddle model names are returned instead.
   *
   * @param context context used to resolve the installed model variant
   * @param code OCR language code (e.g. "deu", "chi_sim" or a Paddle model code like "latin")
   * @return display name such as "German (Best)" or "Latin script (Paddle)"
   */
  public static String codeToDisplayName(android.content.Context context, String code) {
    if (de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR) {
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
    // Map common Tesseract 3-letter codes to 2-letter BCP-47 where possible, for localization
    String two;
    switch (code) {
      case "eng":
        two = "en";
        break;
      case "deu":
        two = "de";
        break;
      case "fra":
        two = "fr";
        break;
      case "ita":
        two = "it";
        break;
      case "spa":
        two = "es";
        break;
      case "por":
        two = "pt";
        break;
      case "nld":
        two = "nl";
        break;
      case "pol":
        two = "pl";
        break;
      case "ces":
        two = "cs";
        break;
      case "slk":
        two = "sk";
        break;
      case "hun":
        two = "hu";
        break;
      case "ron":
        two = "ro";
        break;
      case "dan":
        two = "da";
        break;
      case "nor":
        two = "no";
        break;
      case "swe":
        two = "sv";
        break;
      case "rus":
        two = "ru";
        break;
      case "tha":
        two = "th";
        break;
      case "fas":
        two = "fa";
        break;
      case "ara":
        two = "ar";
        break;
      case "hin":
        two = "hi";
        break;
      case "tur":
        two = "tr";
        break;
      case "chi_sim":
        return appendVariantLabel(context, "Chinese (Simplified)", code);
      case "chi_tra":
        return appendVariantLabel(context, "Chinese (Traditional)", code);
      default:
        // Fallback: try first two letters
        if (code != null && code.length() >= 2) {
          two = code.substring(0, 2);
        } else {
          two = "en";
        }
    }
    String baseName;
    try {
      Locale loc = Locale.forLanguageTag(two);
      baseName = loc.getDisplayLanguage(Locale.getDefault());
    } catch (Throwable ignore) {
      baseName = code;
    }
    return appendVariantLabel(context, baseName, code);
  }

  /**
   * Appends the model variant label (Fast/Best) to the given base display name, resolved via the
   * flavor-specific {@link OcrModelManager}.
   */
  private static String appendVariantLabel(
      android.content.Context context, String baseName, String code) {
    String variant = OcrModelManager.isUsingBestModel(context, code) ? "Best" : "Fast";
    return baseName + " (" + variant + ")";
  }
}
