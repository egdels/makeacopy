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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.googlecode.tesseract.android.TessBaseAPI;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.layout.DocumentLayoutAnalyzer;
import de.schliweb.makeacopy.utils.layout.DocumentRegion;
import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;

/**
 * OCRHelper is a utility class used for Optical Character Recognition (OCR) by leveraging Tesseract
 * OCR technologies. It provides methods to initialize, configure, and perform OCR operations on
 * images.
 */
public class OCRHelper {
  private static final String TAG = "OCRHelper";
  private static final String TESSDATA_DIR = "tessdata";
  private static final String DEFAULT_LANGUAGE = "eng";
  private static final String TRAINEDDATA_EXT = ".traineddata";
  private static final String DEFAULT_DPI = "300";
  private static final String BEST_MODEL_DPI = "400";

  /**
   * A regular expression pattern used to match and extract specific HTML span elements related to
   * OCR results. These span elements represent words extracted by OCR engines such as Tesseract and
   * are associated with word-level metadata like bounding box coordinates and recognition
   * confidence.
   *
   * <p>The pattern matches `<span>` elements with the following characteristics: - A class
   * attribute that contains `ocrx_word` or `ocr_word`. - A title attribute that includes bounding
   * box (`bbox`) information and confidence (`x_wconf`) values.
   *
   * <p>Capturing groups are used to extract: 1. Metadata information from the title attribute
   * (e.g., bounding box data). 2. The text content enclosed within the span element.
   *
   * <p>This pattern is case-insensitive and supports matching across multiple lines.
   */
  private static final Pattern SPAN_PATTERN =
      Pattern.compile(
          "<span[^>]*class=[\"'][^\"']*ocrx?_word[^\"']*[\"'][^>]*title=[\"']([^\"']+)[\"'][^>]*>(.*?)</span>",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /**
   * A regular expression pattern used to match bounding box data in text. This pattern identifies
   * strings in the format "bbox x1 y1 x2 y2", where `x1`, `y1`, `x2`, and `y2` are integers
   * representing the coordinates of a bounding box.
   *
   * <p>The pattern is case-insensitive and designed to capture four integer groups corresponding to
   * the bounding box's corners.
   */
  private static final Pattern BBOX_PATTERN =
      Pattern.compile("bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

  /**
   * A precompiled {@link Pattern} used to match and extract confidence (x_wconf) values from text,
   * typically in the context of processing OCR-related data or structured output formats such as
   * HOCR.
   *
   * <p>The pattern is constructed with the following properties: - It matches strings containing
   * "x_wconf" followed by one or more digits. - The matching is case insensitive due to the use of
   * {@link Pattern#CASE_INSENSITIVE}.
   *
   * <p>Capturing groups: - Captures the numeric component immediately following "x_wconf".
   *
   * <p>This pattern is likely used to parse and extract confidence levels associated with
   * OCR-recognized words or regions.
   */
  private static final Pattern XWCONF_PATTERN =
      Pattern.compile("x_wconf\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

  private final Context context;
  private final String dataPath;
  private TessBaseAPI tessBaseAPI;
  private String language = DEFAULT_LANGUAGE;
  private boolean isInitialized = false;
  // Use a fixed Page Segmentation Mode by default to stabilize OCR results
  @Getter private int pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK;
  // Option to reinitialize Tesseract engine per OCR run to avoid internal state carry-over
  private boolean reinitPerRun = true;
  // Flag to indicate if Best model optimizations should be applied
  private boolean useBestModelSettings = false;

  /** Recognition modes mirroring the user-facing setting in OCRFragment. */
  public static final int OCR_MODE_ORIGINAL = 0;

  public static final int OCR_MODE_QUICK = 1;
  public static final int OCR_MODE_ROBUST = 2;

  /**
   * PaddleOCR (paddle flavor only): exclusive engine selection. Skips all preprocessing — the
   * original (rotated) bitmap is fed directly into the Paddle pipeline. When the paddle engine
   * cannot be initialized at runtime, OCRHelper transparently falls back to Tesseract on the same
   * original bitmap (i.e. behaves like {@link #OCR_MODE_ORIGINAL}).
   */
  public static final int OCR_MODE_PADDLE = 3;

  /**
   * Selected recognition mode for region-OCR (layout analysis path). Defaults to ROBUST to preserve
   * historical behavior; callers should set it from the user preference so that the region pipeline
   * matches the page-level pipeline used in OCRFragment.
   */
  @Getter private int recognitionMode = OCR_MODE_ROBUST;

  /**
   * When true and recognitionMode == ROBUST, region-OCR forces useBinary=true for all region types
   * (including TABLE/COLUMN/HEADER which would otherwise prefer grayscale). Used by the adaptive
   * uneven-lighting heuristic in OCRFragment to trigger Sauvola/Retinex on photo-style inputs with
   * strong shadows. Default false preserves prior behavior.
   */
  @Getter private boolean forceBinaryRobust = false;

  private String dpi = DEFAULT_DPI;

  /**
   * Constructs an instance of the OCRHelper class.
   *
   * @param context The application context used to initialize the OCR framework. It is recommended
   *     to pass a valid context to ensure the proper functioning of the OCRHelper.
   */
  public OCRHelper(Context context) {
    this.context = context.getApplicationContext();
    // Use no-backup directory to align with OcrModelManager imports
    this.dataPath = ContextCompat.getNoBackupFilesDir(this.context).getAbsolutePath();
  }

  /**
   * Retrieves the directory path for the tessdata folder within the application's private file
   * storage.
   *
   * @param context the Context of the application, used to access file directories
   * @return a File object representing the tessdata directory
   */
  public static File getTessdataDir(Context context) {
    return new File(ContextCompat.getNoBackupFilesDir(context), TESSDATA_DIR);
  }

  /**
   * Cleans an HTML text string by removing HTML tags, resolving HTML entities (both named and
   * numeric), trimming whitespace, and normalizing multiple consecutive spaces into a single space.
   *
   * @param html the HTML text string to be cleaned; can be null
   * @return the cleaned plain text string; returns an empty string if the input is null
   */
  private static String cleanHtmlText(String html) {
    if (html == null) return "";
    // Tags entfernen
    String t = html.replaceAll("<[^>]+>", "");
    // Grundlegende Entities auflösen
    t =
        t.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'");
    // Numerische HTML-Entities auflösen (z.B. &#39; für Apostroph, &#x27; für hex)
    t = decodeNumericEntities(t);
    // trim & normalisieren
    t = t.trim();
    // Mehrfach-Leerzeichen → eins
    t = t.replaceAll("\\s{2,}", " ");
    return t;
  }

  /**
   * Decodes numeric HTML entities (both decimal like &#39; and hexadecimal like &#x27;) into their
   * corresponding Unicode characters.
   *
   * @param text the text containing numeric HTML entities
   * @return the text with numeric entities replaced by their Unicode characters
   */
  public static String decodeNumericEntities(String text) {
    if (text == null || text.isEmpty()) return text;
    // Decimal entities: &#123;
    java.util.regex.Matcher decMatcher = Pattern.compile("&#(\\d+);").matcher(text);
    StringBuffer sb = new StringBuffer();
    while (decMatcher.find()) {
      try {
        int codePoint = Integer.parseInt(decMatcher.group(1));
        String replacement = new String(Character.toChars(codePoint));
        decMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
      } catch (Throwable ignore) {
        // Keep original if parsing fails
      }
    }
    decMatcher.appendTail(sb);
    text = sb.toString();

    // Hexadecimal entities: &#x1F; or &#X1F;
    java.util.regex.Matcher hexMatcher = Pattern.compile("&#[xX]([0-9a-fA-F]+);").matcher(text);
    sb = new StringBuffer();
    while (hexMatcher.find()) {
      try {
        int codePoint = Integer.parseInt(hexMatcher.group(1), 16);
        String replacement = new String(Character.toChars(codePoint));
        hexMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
      } catch (Throwable ignore) {
        // Keep original if parsing fails
      }
    }
    hexMatcher.appendTail(sb);
    return sb.toString();
  }

  /**
   * Initializes the Tesseract OCR engine for the specified language and prepares it for processing.
   * This method ensures that the required language data is available, sets default configurations,
   * and initializes the underlying Tesseract API.
   *
   * @return true if the Tesseract engine is successfully initialized; false otherwise
   */
  public boolean initTesseract() {
    if (isInitialized) return true;
    try {
      ensureLanguageDataPresent(language);
      tessBaseAPI = new TessBaseAPI();
      int oem = useBestModelSettings ? TessBaseAPI.OEM_LSTM_ONLY : TessBaseAPI.OEM_DEFAULT;
      boolean ok = tessBaseAPI.init(dataPath, language, oem);
      if (!ok) {
        Log.e(TAG, "Tesseract initialization failed");
        return false;
      }
      tessBaseAPI.setPageSegMode(pageSegMode);
      isInitialized = true;
      // Select DPI based on model type - Best models benefit from higher DPI
      setVariable("user_defined_dpi", dpi);
      // Disable automatic inversion detection (can cause issues with clean documents)
      setVariable("tessedit_do_invert", "0");
      // More aggressive noise reduction during text ordering
      setVariable("textord_heavy_nr", "1");
      // Allow more children per outline for complex glyphs (improves detail recognition)
      setVariable("edges_max_children_per_outline", "40");
      // Reduce minimum line size to detect smaller text lines
      setVariable("textord_min_linesize", "2.5");
      // Verbesserte Wortsegmentierung
      setVariable("textord_force_make_prop_words", "1");
      // Bessere Erkennung von Sonderzeichen
      setVariable("tessedit_char_blacklist", "");
      // Verbesserte Baseline-Erkennung für schiefe Texte
      setVariable("textord_straight_baselines", "1");
      // Verbesserte Zeichensegmentierung
      setVariable("segment_penalty_dict_nonword", "0.5");
      setVariable("segment_penalty_garbage", "1.5");
      // Bessere Erkennung von Ligaturen und Sonderzeichen
      setVariable("tessedit_enable_dict_correction", "1");
      // Verbesserte Zeilenerkennung
      setVariable("textord_tabfind_vertical_text", "0"); // Für westliche Sprachen
      setVariable("textord_tabfind_force_vertical_text", "0");
      // Bessere Worttrennung
      setVariable("tessedit_word_for_word", "0");
      setVariable("tessedit_enable_bigram_correction", "1");

      // --- Best model specific optimizations ---
      if (useBestModelSettings) {
        // Lower penalties for Best models - they have better language models
        setVariable("language_model_penalty_non_dict_word", "0.05");
        setVariable("language_model_penalty_non_freq_dict_word", "0.05");
        // Enable more thorough character segmentation for Best models
        setVariable("chop_enable", "1");
        // Increase certainty threshold for better accuracy
        setVariable("classify_min_certainty", "-2.5");
      } else {
        // Fast model defaults
        // Reduce penalty for non-dictionary words (improves recognition of names, abbreviations)
        setVariable("language_model_penalty_non_dict_word", "0.1");
        // Reduce penalty for non-frequent dictionary words
        setVariable("language_model_penalty_non_freq_dict_word", "0.1");
      }
      applyDefaultsForLanguage(language);
      Log.i(
          TAG, "Tesseract initialized: lang=" + language + ", psm=" + pageSegMode + ", dpi=" + dpi);
      return true;
    } catch (Exception e) {
      Log.e(TAG, "Error initializing Tesseract", e);
      return false;
    }
  }

  /**
   * Releases resources associated with the Tesseract OCR engine and resets the state of the
   * OCRHelper.
   *
   * <p>This method ensures that the Tesseract OCR engine is properly shut down and memory is
   * released. If the engine has been initialized, its resources are recycled and the associated
   * instance is set to null. The state of OCRHelper is also updated to indicate that it is no
   * longer initialized.
   */
  public void shutdown() {
    if (tessBaseAPI != null) {
      try {
        tessBaseAPI.recycle();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      tessBaseAPI = null;
    }
    isInitialized = false;
  }

  /**
   * Checks whether the Tesseract OCR engine has been successfully initialized.
   *
   * @return true if the Tesseract engine is initialized and ready for processing; false otherwise
   */
  public boolean isTesseractInitialized() {
    return isInitialized;
  }

  /**
   * Sets the page segmentation mode for the OCR engine. The page segmentation mode determines how
   * the OCR engine divides the input image into text blocks.
   *
   * @param mode the page segmentation mode to set. It is an integer value representing the desired
   *     page segmentation mode. Refer to the OCR engine documentation for valid mode values.
   */
  public void setPageSegMode(int mode) {
    this.pageSegMode = mode;
    if (!isInitialized) {
      Log.w(TAG, "setPageSegMode: Engine not initialized yet; will apply on init. psm=" + mode);
      return;
    }
    try {
      tessBaseAPI.setPageSegMode(mode);
    } catch (Throwable t) {
      Log.e(TAG, "Failed to set PSM on engine", t);
    }
    Log.i(TAG, "setPageSegMode: applied psm=" + mode);
  }

  /** Sets the page segmentation mode using the project-internal, engine-neutral enum. */
  public void setPageSegmentationMode(OcrPageSegmentationMode mode) {
    setPageSegMode(toTesseractPageSegMode(mode));
  }

  private int toTesseractPageSegMode(OcrPageSegmentationMode mode) {
    if (mode == null) return TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK;
    return switch (mode) {
      case AUTO -> TessBaseAPI.PageSegMode.PSM_AUTO;
      case SINGLE_WORD -> TessBaseAPI.PageSegMode.PSM_SINGLE_WORD;
      case SINGLE_BLOCK -> TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK;
    };
  }

  /**
   * Sets the recognition mode for the OCR processing.
   *
   * @param mode The recognition mode to be set. Valid values are: OCR_MODE_ORIGINAL,
   *     OCR_MODE_QUICK, OCR_MODE_ROBUST, and OCR_MODE_PADDLE. If an invalid value is provided, the
   *     mode defaults to OCR_MODE_ROBUST.
   */
  public void setRecognitionMode(int mode) {
    if (mode != OCR_MODE_ORIGINAL
        && mode != OCR_MODE_QUICK
        && mode != OCR_MODE_ROBUST
        && mode != OCR_MODE_PADDLE) {
      mode = OCR_MODE_ROBUST;
    }
    this.recognitionMode = mode;
    Log.i(TAG, "setRecognitionMode: " + mode);
  }

  /**
   * Enables or disables the force binary robust mode.
   *
   * @param enable A boolean value indicating whether to enable (true) or disable (false) the force
   *     binary robust mode.
   */
  public void setForceBinaryRobust(boolean enable) {
    this.forceBinaryRobust = enable;
    Log.i(TAG, "setForceBinaryRobust: " + enable);
  }

  /**
   * Sets the reinitialization behavior for each run.
   *
   * @param enable A boolean value indicating whether reinitialization should be enabled (true) or
   *     disabled (false).
   */
  public void setReinitPerRun(boolean enable) {
    this.reinitPerRun = enable;
    Log.i(TAG, "setReinitPerRun: " + enable);
  }

  /**
   * Configures whether the application should use the best model settings.
   *
   * @param enable A boolean indicating whether to enable the best model settings. If true, the best
   *     model settings will be applied; otherwise, default settings will be used.
   */
  public void setUseBestModelSettings(boolean enable) {
    this.useBestModelSettings = enable;
    Log.i(TAG, "setUseBestModelSettings: " + enable);
    this.dpi = enable ? BEST_MODEL_DPI : DEFAULT_DPI;
  }

  /**
   * Returns whether Best model optimizations are enabled.
   *
   * @return true if Best model settings are active
   */
  public boolean isUsingBestModelSettings() {
    return useBestModelSettings;
  }

  public void setPaddleHighQualityDetectionEnabled(boolean enable) {
    // Standard flavor does not include PaddleOCR; keep API parity with the paddle flavor.
  }

  /**
   * Sets the language for the OCR engine. If the specified language is null or empty, a default
   * language value is used. If the given language differs from the currently set language and the
   * OCR engine has been initialized, it reinitializes the engine with the new language.
   *
   * @param language The language code to be set for the OCR engine. This should correspond to the
   *     available language files. If null or empty, a predefined default language will be used.
   * @throws IOException If there is an error ensuring the required language data files are present.
   */
  public void setLanguage(String language) throws IOException {
    if (language == null || language.isEmpty()) language = DEFAULT_LANGUAGE;
    if (language.equals(this.language) && isInitialized) return;

    this.language = language;
    ensureLanguageDataPresent(language);

    if (isInitialized) {
      shutdown();
      initTesseract();
    }
    applyDefaultsForLanguage(language);
  }

  /**
   * Applies default configuration settings for the specified language or language specification
   * within the OCR engine. This method ensures that the OCR engine is configured with optimal
   * settings such as page segmentation mode, DPI, interword space preservation, and character
   * whitelists based on the given language specification.
   *
   * @param langSpec The language specification provided as a string (e.g., "eng", "deu+eng"). This
   *     is used to configure the character whitelist and other language-specific settings for the
   *     OCR engine. If null or empty, default settings are applied.
   */
  public void applyDefaultsForLanguage(String langSpec) {
    if (!isInitialized) return;

    String ls = (langSpec == null) ? "" : langSpec.toLowerCase(java.util.Locale.ROOT);
    boolean isCjkOrThai = ls.contains("chi_") || ls.contains("tha");
    boolean isRtlArabic = ls.contains("ara") || ls.contains("fas");

    // For Chinese, Thai, Arabic, and Persian, prefer AUTO segmentation; otherwise keep configured
    // PSM
    int psm =
        (isCjkOrThai || isRtlArabic)
            ? com.googlecode.tesseract.android.TessBaseAPI.PageSegMode.PSM_AUTO
            : pageSegMode;
    this.pageSegMode = psm; // Keep instance variable in sync for getPageSegMode()
    tessBaseAPI.setPageSegMode(psm);

    // In CJK and Thai, interword spaces are not meaningful; let Tesseract decide spacing
    setVariable("preserve_interword_spaces", isCjkOrThai ? "0" : "1");

    // Für Sprachen mit vielen Akzenten (FR, ES, PT)
    if (ls.contains("fra") || ls.contains("spa") || ls.contains("por")) {
      setVariable("tessedit_enable_dict_correction", "1");
      setVariable("language_model_penalty_punc", "0.1");
    }

    // Für Deutsch (Komposita)
    if (ls.contains("deu")) {
      setVariable("language_model_penalty_non_dict_word", "0.08");
      setVariable("segment_penalty_dict_case_ok", "0.5");
    }

    // Für Kyrillisch (RU)
    if (ls.contains("rus")) {
      setVariable("tessedit_enable_dict_correction", "1");
    }

    // Für Devanagari-Schriften (Hindi, Marathi, Nepali, Sanskrit): kleine diakritische
    // Zeichen (Matras, Anusvara, Virama) dürfen nicht von der aggressiven Rauschunterdrückung
    // entfernt werden, sonst gehen z.B. bei "हिंदी" die Vokalzeichen verloren.
    boolean isDevanagari =
        ls.contains("hin") || ls.contains("mar") || ls.contains("nep") || ls.contains("san");
    if (isDevanagari) {
      setVariable("textord_heavy_nr", "0");
    }

    // Whitelist handling: Best models work better without whitelist restrictions
    // RTL scripts (Arabic/Persian) should never use Latin whitelist
    if (useBestModelSettings || isRtlArabic) {
      // Clear whitelist for Best models and RTL scripts to allow full character recognition
      setWhitelist("");
      if (isRtlArabic) {
        Log.i(TAG, "RTL script (ara/fas): whitelist disabled for Arabic script recognition");
      } else {
        Log.i(TAG, "Best model: whitelist disabled for full character recognition");
      }
    } else if (!isCjkOrThai) {
      // Do NOT enforce Latin whitelist for Chinese/Thai; otherwise compose whitelist from spec
      setWhitelist(OCRWhitelist.getWhitelistForLangSpec(langSpec));
    }

    String scriptInfo = isCjkOrThai ? " (CJK/TH)" : (isRtlArabic ? " (RTL)" : "");
    Log.i(
        TAG,
        "applyDefaultsForLanguage: langSpec="
            + langSpec
            + scriptInfo
            + ", psm="
            + ((isCjkOrThai || isRtlArabic) ? "AUTO" : String.valueOf(pageSegMode))
            + ", dpi="
            + dpi
            + ", bestModel="
            + useBestModelSettings);
  }

  /**
   * Ensures that the required language data files are available for the specified language
   * specification. This method processes the language specification, which may consist of one or
   * more language codes separated by a "+" symbol, and ensures that data files for each language
   * are present.
   *
   * @param langSpec The language specification string (e.g., "eng", "deu+eng"). Each language part
   *     should correspond to a valid language code for which the required data files will be
   *     checked or copied.
   * @throws IOException If an I/O error occurs while ensuring the language data files are present.
   */
  private void ensureLanguageDataPresent(String langSpec) throws IOException {
    for (String part : langSpec.split("\\+", -1)) {
      String lang = part.trim();
      if (!lang.isEmpty()) copyLanguageDataFileSingle(lang);
    }
  }

  /**
   * Copies a single language data file for the Tesseract OCR engine to the appropriate directory.
   * If the file already exists and is non-empty, the method does nothing. Otherwise, it attempts to
   * extract the file from the application's assets and saves it to the target location.
   *
   * @param lang The language code specifying the language data file to copy (e.g., "eng" for
   *     English).
   * @throws IOException If an error occurs while creating the directory or copying the file.
   */
  private void copyLanguageDataFileSingle(String lang) throws IOException {
    File dir = new File(dataPath + "/" + TESSDATA_DIR);
    if (!dir.exists() && !dir.mkdirs())
      throw new IOException("Failed to create tessdata dir: " + dir);

    String filename = lang + TRAINEDDATA_EXT;
    File target = new File(dir, filename);
    if (target.exists() && target.length() > 0) return;

    try (InputStream in = context.getAssets().open(TESSDATA_DIR + "/" + filename)) {
      File tmp = File.createTempFile(lang + ".", ".tmp", dir);
      try (OutputStream out = new FileOutputStream(tmp)) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
      }
      if (!tmp.renameTo(target)) {
        try (InputStream rin = new FileInputStream(tmp);
            OutputStream rout = new FileOutputStream(target)) {
          byte[] buf = new byte[8192];
          int n;
          while ((n = rin.read(buf)) != -1) rout.write(buf, 0, n);
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
      }
    }
  }

  /**
   * Retrieves the list of available languages for OCR processing based on the language data files
   * present in the application's assets or local directory.
   *
   * @return An array of strings, where each string corresponds to the code of an available
   *     language. Returns an empty array if no languages are found or if an error occurs while
   *     accessing the data.
   */
  public String[] getAvailableLanguages() {
    try {
      LinkedHashSet<String> langs = new LinkedHashSet<>();

      String[] assetFiles = context.getAssets().list(TESSDATA_DIR);
      if (assetFiles != null) {
        for (String f : assetFiles)
          if (f.endsWith(TRAINEDDATA_EXT))
            langs.add(f.substring(0, f.length() - TRAINEDDATA_EXT.length()));
      }

      File localDir = new File(dataPath + "/" + TESSDATA_DIR);
      File[] local = localDir.listFiles((d, name) -> name.endsWith(TRAINEDDATA_EXT));
      if (local != null) {
        for (File f : local) {
          String n = f.getName();
          langs.add(n.substring(0, n.length() - TRAINEDDATA_EXT.length()));
        }
      }
      return langs.toArray(new String[0]);
    } catch (IOException e) {
      Log.e(TAG, "Error listing languages", e);
      return new String[0];
    }
  }

  /**
   * Checks whether the specified language is available for the OCR engine. The method verifies the
   * presence of the corresponding trained data file used by the OCR engine.
   *
   * @param lang The language code to check (e.g., "eng" for English). This should be a valid
   *     language code corresponding to the expected trained data file.
   * @return true if the trained data file for the specified language is available in the assets or
   *     local directory; false otherwise.
   */
  public boolean isLanguageAvailable(String lang) {
    String filename = lang + TRAINEDDATA_EXT;
    try {
      String[] assetFiles = context.getAssets().list(TESSDATA_DIR);
      if (assetFiles != null) {
        for (String a : assetFiles) if (a.equals(filename)) return true;
      }
    } catch (IOException e) {
      Log.e(TAG, "Error checking assets", e);
    }
    File f = new File(dataPath + "/" + TESSDATA_DIR + "/" + filename);
    return f.exists() && f.length() > 0;
  }

  private boolean setVariable(String var, String value) {
    if (!isInitialized) {
      Log.e(TAG, "Tesseract not initialized");
      return false;
    }
    return tessBaseAPI.setVariable(var, value);
  }

  /**
   * Updates the whitelist of characters for text recognition.
   *
   * @param chars the string containing characters to be included in the whitelist
   * @return true if the whitelist was successfully updated, false otherwise
   */
  private boolean setWhitelist(String chars) {
    return setVariable("tessedit_char_whitelist", chars);
  }

  /**
   * Returns the active whitelist string for the current language configuration. Returns an empty
   * string when no whitelist filtering should be applied (e.g., Best models, RTL scripts,
   * CJK/Thai).
   */
  private String getActiveWhitelist() {
    String ls = (language == null) ? "" : language.toLowerCase(java.util.Locale.ROOT);
    boolean isCjkOrThai = ls.contains("chi_") || ls.contains("tha");
    boolean isRtlArabic = ls.contains("ara") || ls.contains("fas");
    if (useBestModelSettings || isRtlArabic || isCjkOrThai) {
      return "";
    }
    return OCRWhitelist.getWhitelistForLangSpec(language);
  }

  /**
   * Performs Optical Character Recognition (OCR) on the given bitmap and retrieves the recognized
   * text along with additional details such as recognition confidence and word-level information.
   * This method uses Tesseract OCR and processes the input image to extract text.
   *
   * @param bitmap The input image as a {@link Bitmap} object. The image should be clear and
   *     appropriately oriented for optimal OCR results.
   * @return An {@link OcrResultWords} object containing the recognized text, confidence value, and
   *     a list of recognized words with their respective details. If OCR fails or Tesseract is not
   *     initialized, the result will contain empty text and default values.
   */
  private OcrResultWords runOcrWithWords(Bitmap bitmap) {
    if (bitmap == null) {
      Log.e(TAG, "runOcrWithWords: bitmap is null");
      return new OcrResultWords("", null, new ArrayList<>());
    }
    try {
      // Optionally reinitialize engine to avoid non-deterministic internal state
      if (!isInitialized) {
        initTesseract();
      } else if (reinitPerRun) {
        Log.i(TAG, "runOcrWithWords: reinitializing engine per run");
        shutdown();
        initTesseract();
      }
      if (!isInitialized) {
        Log.e(TAG, "Tesseract not initialized after (re)init");
        return new OcrResultWords("", null, new ArrayList<>());
      }

      Bitmap src =
          bitmap.getConfig() == Bitmap.Config.ARGB_8888
              ? bitmap
              : bitmap.copy(Bitmap.Config.ARGB_8888, false);
      Log.i(
          TAG,
          "runOcrWithWords: start OCR lang="
              + language
              + ", psm="
              + pageSegMode
              + ", dpi="
              + dpi
              + ", img="
              + src.getWidth()
              + "x"
              + src.getHeight());

      tessBaseAPI.setImage(src);
      String text = tessBaseAPI.getUTF8Text();
      String hocr = null;
      try {
        hocr = tessBaseAPI.getHOCRText(0); // Seite 0
      } catch (Throwable t) {
        Log.w(TAG, "getHOCRText not available", t);
      }
      Integer conf = getMeanConfidenceSafe();
      tessBaseAPI.clear();

      // Post-process: filter text by whitelist (Tesseract LSTM engine may ignore whitelist)
      String whitelist = getActiveWhitelist();
      text = OCRWhitelist.filterByWhitelist(text, whitelist);

      List<RecognizedWord> words = parseHocrWords(hocr, conf, whitelist);
      Log.i(
          TAG,
          "runOcrWithWords: done textLen="
              + (text != null ? text.length() : 0)
              + ", words="
              + (words != null ? words.size() : 0)
              + ", meanConf="
              + conf);
      return new OcrResultWords(text, conf, words);
    } catch (Exception e) {
      Log.e(TAG, "Error performing OCR with HOCR", e);
      return new OcrResultWords("", null, new ArrayList<>());
    }
  }

  private static boolean isEmptyResult(OcrResultWords r) {
    if (r == null) return true;
    boolean hasWords = r.words != null && !r.words.isEmpty();
    if (hasWords) return false;
    String t = r.text;
    return t == null || t.trim().isEmpty();
  }

  /**
   * Selects and initializes the appropriate OCR (Optical Character Recognition) engine based on the
   * current configuration and system capabilities. This method evaluates feature flags, toggle
   * states, and initializes the Paddle OCR engine if enabled and supported. Diagnostics data is
   * recorded for tracking initialization status and fallback scenarios.
   *
   * @return An instance of {@code OcrEngine} if the Paddle OCR engine is successfully initialized;
   *     {@code null} if the engine is not enabled, not supported, or initialization fails.
   */
  private OcrEngine selectEngine() {
    final String lang = language;
    final String[] abis = android.os.Build.SUPPORTED_ABIS;
    final String abiInfo = (abis != null && abis.length > 0) ? abis[0] : "unknown";
    final boolean featureFlag = BuildConfig.FEATURE_PADDLE_OCR;
    final boolean toggleEnabled = (recognitionMode == OCR_MODE_PADDLE);

    if (!featureFlag) {
      OcrBackendDiagnostics.record(
          "tesseract",
          lang,
          abiInfo,
          false,
          toggleEnabled,
          OcrBackendDiagnostics.Reason.DISABLED_BY_FLAG);
      return null;
    }
    if (!toggleEnabled) {
      OcrBackendDiagnostics.record(
          "tesseract", lang, abiInfo, true, false, OcrBackendDiagnostics.Reason.TOGGLE_OFF);
      return null;
    }
    try {
      OcrEngine engine = PaddleEngineProvider.create(context, lang);
      if (engine == null) {
        OcrBackendDiagnostics.record(
            "tesseract",
            lang,
            abiInfo,
            true,
            true,
            OcrBackendDiagnostics.Reason.PADDLE_INIT_FAILED);
        return null;
      }
      OcrBackendDiagnostics.record(
          "paddle", lang, abiInfo, true, true, OcrBackendDiagnostics.Reason.PADDLE_OK);
      return engine;
    } catch (Throwable t) {
      Log.w(TAG, "PaddleEngineProvider.create failed → fallback to Tesseract", t);
      OcrBackendDiagnostics.record(
          "tesseract", lang, abiInfo, true, true, OcrBackendDiagnostics.Reason.PADDLE_INIT_FAILED);
      return null;
    }
  }

  /**
   * Releases resources used by the object and performs cleanup. This method ensures that all
   * dependencies and initialized components are properly deallocated to prevent memory leaks.
   *
   * <p>The method performs the following steps: 1. Safely calls `recycle` on the `tessBaseAPI`
   * instance (if not null), catching any exceptions to avoid disrupting the cleanup process. 2.
   * Sets the `tessBaseAPI` reference to null and updates the `isInitialized` flag to indicate that
   * the instance is no longer active. 3. Attempts to release all resources associated with the
   * `PaddleEngineProvider`, logging any errors that occur during the release process.
   *
   * <p>Any exceptions occurring during the cleanup process are logged, but they do not prevent the
   * method from proceeding with further cleanup tasks.
   */
  public void close() {
    try {
      if (tessBaseAPI != null) {
        try {
          tessBaseAPI.recycle();
        } catch (Throwable t) {
          Log.w(TAG, "tessBaseAPI.recycle failed", t);
        }
        tessBaseAPI = null;
        isInitialized = false;
      }
    } finally {
      try {
        PaddleEngineProvider.releaseAll(context);
      } catch (Throwable t) {
        Log.w(TAG, "PaddleEngineProvider.releaseAll failed", t);
      }
    }
  }

  public OcrResultWords runOcrWithRetry(Bitmap bitmap) {
    OcrEngine e = selectEngine();
    if (e != null) {
      try {
        return e.run(bitmap);
      } catch (Throwable t) {
        Log.w(TAG, "Paddle failed → fallback to Tesseract", t);
      }
    }
    logAllVariables();
    OcrResultWords result = runOcrWithWords(bitmap);

    boolean baseEmpty = isEmptyResult(result);
    boolean lowConf = result.meanConfidence != null && result.meanConfidence < 50;

    // Deterministic fallback retry when either confidence is low OR result is empty.
    // This covers the observed edge-case: high meanConfidence but empty text/words.
    if (lowConf || baseEmpty) {
      int originalPsm = pageSegMode;

      int retryPsm;
      if (baseEmpty && originalPsm == TessBaseAPI.PageSegMode.PSM_AUTO) {
        retryPsm = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK;
      } else {
        retryPsm = TessBaseAPI.PageSegMode.PSM_AUTO;
      }

      Log.i(
          TAG,
          "runOcrWithRetry: triggering retry, lowConf="
              + lowConf
              + ", baseEmpty="
              + baseEmpty
              + ", baseMc="
              + result.meanConfidence
              + ", psm="
              + originalPsm
              + " -> "
              + retryPsm);
      setPageSegMode(retryPsm);

      OcrResultWords retryResult = runOcrWithWords(bitmap);
      setPageSegMode(originalPsm);

      boolean retryEmpty = isEmptyResult(retryResult);

      // Hard guard: empty result must never replace a non-empty one
      if (!baseEmpty && retryEmpty) {
        return result;
      }
      if (baseEmpty && !retryEmpty) {
        return retryResult;
      }

      Log.i(
          TAG,
          "runOcrWithRetry: baseEmpty="
              + baseEmpty
              + ", retryEmpty="
              + retryEmpty
              + ", baseMc="
              + result.meanConfidence
              + ", retryMc="
              + retryResult.meanConfidence);

      // Same emptiness class -> compare by confidence, then by content size
      float baseMc = (result.meanConfidence != null) ? result.meanConfidence : 0f;
      float retryMc = (retryResult.meanConfidence != null) ? retryResult.meanConfidence : 0f;
      if (retryMc > baseMc + 10f) {
        return retryResult;
      }
      if (Math.abs(retryMc - baseMc) <= 10f) {
        int baseWords = (result.words != null) ? result.words.size() : 0;
        int retryWords = (retryResult.words != null) ? retryResult.words.size() : 0;
        if (retryWords > baseWords) {
          return retryResult;
        }
        int baseLen = (result.text != null) ? result.text.length() : 0;
        int retryLen = (retryResult.text != null) ? retryResult.text.length() : 0;
        if (retryLen > baseLen) {
          return retryResult;
        }
      }
    }
    return result;
  }

  public void logAllVariables() {
    if (!isInitialized) return;
    String[] vars = {
      "user_defined_dpi",
      "tessedit_do_invert",
      "textord_heavy_nr",
      "language_model_penalty_non_dict_word",
      "tessedit_char_whitelist"
    };
    for (String var : vars) {
      String val = tessBaseAPI.getVariable(var);
      Log.d(TAG, "Tesseract var: " + var + " = " + val);
    }
  }

  /**
   * Parses the given hOCR (HTML for OCR) content to extract recognized words along with their
   * bounding box coordinates and confidence levels. Each recognized word is represented as an
   * instance of the RecognizedWord class.
   *
   * @param hocr the hOCR content to be parsed, represented as a String. It contains structured OCR
   *     data with bounding box coordinates and confidence values.
   * @param defaultConf the default confidence value to use if a specific confidence value is not
   *     available in the hOCR data.
   * @return a list of recognized words extracted from the hOCR content. Each recognized word
   *     includes the text content, bounding box, and confidence level.
   */
  private List<RecognizedWord> parseHocrWords(String hocr, Integer defaultConf, String whitelist) {
    List<RecognizedWord> out = new ArrayList<>();
    if (hocr == null || hocr.isEmpty()) return out;

    Matcher m = SPAN_PATTERN.matcher(hocr);
    while (m.find()) {
      String title = m.group(1);
      String htmlText = m.group(2);

      if (title == null) continue;
      Matcher bboxM = BBOX_PATTERN.matcher(title);
      if (!bboxM.find()) continue;

      try {
        float left = Float.parseFloat(bboxM.group(1));
        float top = Float.parseFloat(bboxM.group(2));
        float right = Float.parseFloat(bboxM.group(3));
        float bottom = Float.parseFloat(bboxM.group(4));

        RectF box = new RectF(left, top, right, bottom);

        float conf = (defaultConf != null) ? defaultConf : 0f;
        Matcher confM = XWCONF_PATTERN.matcher(title);
        if (confM.find()) {
          try {
            conf = Float.parseFloat(confM.group(1));
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
        }

        String text = cleanHtmlText(htmlText);
        text = OCRWhitelist.filterByWhitelist(text, whitelist);
        if (text.isEmpty()) continue;

        out.add(new RecognizedWord(text, box, conf));
      } catch (Throwable ignore) {
        // schluckt fehlerhafte Einträge
      }
    }
    return out;
  }

  /**
   * Retrieves the mean confidence value of the OCR engine if it has been successfully initialized.
   * The mean confidence represents the average confidence level of the recognized text. If the OCR
   * engine is not initialized or an error occurs during retrieval, this method returns null.
   *
   * @return The mean confidence value as an Integer if available, or null if the OCR engine is not
   *     initialized or an error occurs during the operation.
   */
  public Integer getMeanConfidenceSafe() {
    if (!isInitialized) return null;
    try {
      return tessBaseAPI.meanConfidence(); // in tess-two oft so benannt
    } catch (Throwable t) {
      return null;
    }
  }

  /**
   * Represents the result of an OCR (Optical Character Recognition) process. This class
   * encapsulates the extracted text along with the mean confidence score of the OCR engine.
   */
  public static class OcrResult {
    public final String text;
    public final Integer meanConfidence;

    public OcrResult(String text, Integer meanConfidence) {
      this.text = text != null ? text : "";
      this.meanConfidence = meanConfidence;
    }
  }

  /**
   * Represents the result of an OCR (Optical Character Recognition) process with detailed
   * recognition information at the word level. This class extends {@code OcrResult} by including a
   * list of recognized words, each with additional details.
   *
   * <p>The {@code words} property contains a collection of {@code RecognizedWord} objects, allowing
   * for more granular inspection of the OCR output, such as bounding boxes, confidence scores, and
   * text content for each recognized word.
   */
  public static class OcrResultWords extends OcrResult {
    public final List<RecognizedWord> words;

    public OcrResultWords(String text, Integer meanConfidence, List<RecognizedWord> words) {
      super(text, meanConfidence);
      this.words = (words != null) ? words : new ArrayList<>();
    }
  }

  /**
   * Represents the result of an OCR process with layout analysis. Contains OCR results organized by
   * document regions for improved reading order and structure preservation.
   */
  public static class OcrResultWithLayout extends OcrResult {
    public final List<RegionOcrResult> regionResults;
    public final DocumentLayoutAnalyzer.AnalysisResult layoutAnalysis;

    public OcrResultWithLayout(
        String text,
        Integer meanConfidence,
        List<RegionOcrResult> regionResults,
        DocumentLayoutAnalyzer.AnalysisResult layoutAnalysis) {
      super(text, meanConfidence);
      this.regionResults = (regionResults != null) ? regionResults : new ArrayList<>();
      this.layoutAnalysis = layoutAnalysis;
    }
  }

  /** Represents the OCR result for a single document region. */
  public record RegionOcrResult(DocumentRegion region, OcrResultWords ocrResult) {}

  /**
   * Performs OCR with layout analysis for complex documents. Analyzes the document structure
   * (columns, tables, headers, footers) and processes each region with optimal settings for
   * improved accuracy.
   *
   * <p>Note: This method requires the FEATURE_LAYOUT_ANALYSIS feature flag to be enabled. If
   * disabled, it falls back to standard OCR without layout analysis.
   *
   * @param bitmap the input document image
   * @return OCR result with layout information and region-specific results
   */
  public OcrResultWithLayout runOcrWithLayout(Bitmap bitmap) {
    if (bitmap == null || bitmap.isRecycled()) {
      Log.w(TAG, "runOcrWithLayout: null or recycled bitmap");
      return new OcrResultWithLayout("", null, new ArrayList<>(), null);
    }

    // Check feature flag - fall back to standard OCR if layout analysis is disabled
    if (!FeatureFlags.isLayoutAnalysisEnabled()) {
      Log.d(
          TAG,
          "runOcrWithLayout: FEATURE_LAYOUT_ANALYSIS is disabled, falling back to standard OCR");
      OcrResultWords standardResult = runOcrWithWords(bitmap);
      return new OcrResultWithLayout(
          standardResult != null ? standardResult.text : "",
          standardResult != null ? standardResult.meanConfidence : null,
          new ArrayList<>(),
          null);
    }

    // Perform layout analysis
    DocumentLayoutAnalyzer analyzer = new DocumentLayoutAnalyzer();
    analyzer.setLanguage(language);
    DocumentLayoutAnalyzer.AnalysisResult layoutAnalysis = analyzer.analyzeWithMetadata(bitmap);

    List<DocumentRegion> regions = layoutAnalysis.regions();
    List<RegionOcrResult> regionResults = new ArrayList<>();
    StringBuilder fullText = new StringBuilder();
    int totalConfidence = 0;
    int confidenceCount = 0;

    // Save current PSM to restore later
    int originalPsm = pageSegMode;

    try {
      // Process each region with optimal PSM
      for (DocumentRegion region : regions) {
        // Skip non-text regions
        if (region.getType() == DocumentRegion.Type.FIGURE) {
          continue;
        }

        // Extract region bitmap
        Bitmap regionBitmap = extractRegionBitmap(bitmap, region.getBounds());
        if (regionBitmap == null) {
          continue;
        }

        // Skip small regions – OpenCV native ops (warpAffine, convertTo, GaussianBlur,
        // fastNlMeansDenoising) can trigger SIGILL on small images on some devices/emulators.
        if (regionBitmap.getWidth() < 64 || regionBitmap.getHeight() < 64) {
          if (regionBitmap != bitmap && !regionBitmap.isRecycled()) {
            regionBitmap.recycle();
          }
          continue;
        }

        // Apply preprocessing to improve OCR accuracy for this region
        Bitmap preprocessedRegion = null;
        try {
          // Use grayscale preprocessing for better detail preservation
          // Tables and columns benefit from non-binary output to preserve fine details
          // Binary output can introduce artifacts on clean PDF text
          boolean useBinary =
              region.getType() != DocumentRegion.Type.TABLE
                  && region.getType() != DocumentRegion.Type.COLUMN
                  && region.getType() != DocumentRegion.Type.HEADER;
          // Adaptive override: when uneven lighting was detected at page level, force the
          // Sauvola/Retinex/binary path for every region (incl. TABLE/COLUMN/HEADER) — Otsu
          // clipping on shadows hurts those region types just as much as plain text.
          if (forceBinaryRobust) {
            useBinary = true;
          }
          // Respect the user-selected recognition mode so region-OCR matches the page-level
          // pipeline. ORIGINAL skips preprocessing (the input bitmap is used as-is); QUICK uses
          // the fast Otsu-based pipeline; ROBUST uses the full background-division/Sauvola path.
          switch (recognitionMode) {
            case OCR_MODE_ORIGINAL:
              preprocessedRegion = regionBitmap;
              break;
            case OCR_MODE_QUICK:
              preprocessedRegion = OpenCVUtils.prepareForOCRQuick(regionBitmap);
              break;
            case OCR_MODE_ROBUST:
            default:
              preprocessedRegion = OpenCVUtils.prepareForOCR(regionBitmap, useBinary);
              break;
          }
          if (preprocessedRegion == null) {
            preprocessedRegion = regionBitmap;
          }
        } catch (Throwable e) {
          Log.w(TAG, "runOcrWithLayout: preprocessing failed for region, using original", e);
          preprocessedRegion = regionBitmap;
        }

        try {
          // Set optimal PSM for this region type
          int optimalPsm = region.getOptimalPsm();
          setPageSegMode(optimalPsm);

          // Run OCR on preprocessed region
          OcrResultWords result = runOcrWithWords(preprocessedRegion);

          if (result != null && !result.text.isEmpty()) {
            // Adjust word bounding boxes to absolute coordinates
            List<RecognizedWord> adjustedWords =
                adjustWordCoordinates(result.words, region.getBounds());

            OcrResultWords adjustedResult =
                new OcrResultWords(result.text, result.meanConfidence, adjustedWords);

            regionResults.add(new RegionOcrResult(region, adjustedResult));

            // Append to full text with proper spacing
            if (fullText.length() > 0) {
              fullText.append("\n\n");
            }
            fullText.append(result.text);

            // Accumulate confidence
            if (result.meanConfidence != null) {
              totalConfidence += result.meanConfidence;
              confidenceCount++;
            }
          }
        } finally {
          // Recycle preprocessed bitmap if it's different from regionBitmap
          if (preprocessedRegion != null
              && preprocessedRegion != regionBitmap
              && !preprocessedRegion.isRecycled()) {
            preprocessedRegion.recycle();
          }
          // Recycle region bitmap if it's different from source
          if (regionBitmap != bitmap && !regionBitmap.isRecycled()) {
            regionBitmap.recycle();
          }
        }
      }
    } finally {
      // Restore original PSM
      setPageSegMode(originalPsm);
    }

    Integer avgConfidence = confidenceCount > 0 ? totalConfidence / confidenceCount : null;

    Log.d(
        TAG,
        "runOcrWithLayout: processed "
            + regionResults.size()
            + " regions, "
            + "columns="
            + layoutAnalysis.columnCount()
            + ", hasTable="
            + layoutAnalysis.hasTable());

    return new OcrResultWithLayout(
        fullText.toString(), avgConfidence, regionResults, layoutAnalysis);
  }

  /**
   * Extracts a sub-bitmap for a specific region.
   *
   * @param source the source bitmap
   * @param bounds the region bounds
   * @return extracted bitmap or null if extraction fails
   */
  private Bitmap extractRegionBitmap(Bitmap source, Rect bounds) {
    if (source == null || bounds == null) {
      return null;
    }

    try {
      // Clamp bounds to image dimensions
      int left = Math.max(0, bounds.left);
      int top = Math.max(0, bounds.top);
      int right = Math.min(source.getWidth(), bounds.right);
      int bottom = Math.min(source.getHeight(), bounds.bottom);

      int width = right - left;
      int height = bottom - top;

      if (width <= 0 || height <= 0) {
        return null;
      }

      return Bitmap.createBitmap(source, left, top, width, height);
    } catch (Exception e) {
      Log.e(TAG, "extractRegionBitmap: error", e);
      return null;
    }
  }

  /**
   * Adjusts word bounding box coordinates from region-relative to absolute image coordinates.
   *
   * @param words list of recognized words with region-relative coordinates
   * @param regionBounds the region's bounds in absolute coordinates
   * @return list of words with adjusted coordinates
   */
  private List<RecognizedWord> adjustWordCoordinates(
      List<RecognizedWord> words, Rect regionBounds) {
    if (words == null || regionBounds == null) {
      return words;
    }

    List<RecognizedWord> adjusted = new ArrayList<>();
    for (RecognizedWord word : words) {
      RectF originalBox = word.getBoundingBox();
      RectF adjustedBox =
          new RectF(
              originalBox.left + regionBounds.left,
              originalBox.top + regionBounds.top,
              originalBox.right + regionBounds.left,
              originalBox.bottom + regionBounds.top);
      adjusted.add(new RecognizedWord(word.getText(), adjustedBox, word.getConfidence()));
    }
    return adjusted;
  }

  /**
   * Checks if the document has a complex layout that would benefit from layout analysis.
   *
   * <p>Note: This method requires the FEATURE_LAYOUT_ANALYSIS feature flag to be enabled. If
   * disabled, it always returns false.
   *
   * @param bitmap the input document image
   * @return true if the document has multiple columns or tables, false if feature is disabled
   */
  public boolean hasComplexLayout(Bitmap bitmap) {
    if (!FeatureFlags.isLayoutAnalysisEnabled()) {
      Log.d(TAG, "hasComplexLayout: FEATURE_LAYOUT_ANALYSIS is disabled");
      return false;
    }

    if (bitmap == null || bitmap.isRecycled()) {
      return false;
    }

    DocumentLayoutAnalyzer analyzer = new DocumentLayoutAnalyzer();
    return analyzer.hasComplexLayout(bitmap);
  }

  /**
   * Gets the estimated number of columns in the document.
   *
   * <p>Note: This method requires the FEATURE_LAYOUT_ANALYSIS feature flag to be enabled. If
   * disabled, it always returns 1 (single column).
   *
   * @param bitmap the input document image
   * @return number of columns (1 for single column documents or if feature is disabled)
   */
  public int getDocumentColumnCount(Bitmap bitmap) {
    if (!FeatureFlags.isLayoutAnalysisEnabled()) {
      Log.d(TAG, "getDocumentColumnCount: FEATURE_LAYOUT_ANALYSIS is disabled");
      return 1;
    }

    if (bitmap == null || bitmap.isRecycled()) {
      return 1;
    }

    DocumentLayoutAnalyzer analyzer = new DocumentLayoutAnalyzer();
    return analyzer.getColumnCount(bitmap);
  }
}
