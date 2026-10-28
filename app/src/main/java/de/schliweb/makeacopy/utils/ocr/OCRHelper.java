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
import android.graphics.Bitmap;
import de.schliweb.makeacopy.utils.layout.DocumentLayoutAnalyzer.AnalysisResult;
import de.schliweb.makeacopy.utils.layout.DocumentRegion;
import de.schliweb.makeacopy.utils.ocr.paddle.PaddleOcrEngine;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Paddle-only OCR helper implementation for the paddle flavor. */
public class OCRHelper implements AutoCloseable {
  private static final String TESSDATA_DIR = "tessdata";
  private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#([xX]?[0-9A-Fa-f]+);");

  public static final int OCR_MODE_ORIGINAL = 0;
  public static final int OCR_MODE_QUICK = 1;
  public static final int OCR_MODE_ROBUST = 2;
  public static final int OCR_MODE_PADDLE = 3;

  private final Context context;
  private String language = "eng";
  private OcrPageSegmentationMode pageSegmentationMode = OcrPageSegmentationMode.SINGLE_BLOCK;
  private boolean paddleHighQualityDetectionEnabled;

  public OCRHelper(Context context) {
    this.context = context != null ? context.getApplicationContext() : null;
  }

  public static File getTessdataDir(Context context) {
    return new File(context.getFilesDir(), TESSDATA_DIR);
  }

  public static String cleanHtmlText(String html) {
    if (html == null || html.isEmpty()) return "";
    String s = html.replaceAll("<[^>]+>", " ");
    s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    return decodeNumericEntities(s).replaceAll("\\s+", " ").trim();
  }

  public static String decodeNumericEntities(String text) {
    if (text == null || text.indexOf("&#") < 0) return text;
    Matcher matcher = NUMERIC_ENTITY_PATTERN.matcher(text);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      String token = matcher.group(1);
      try {
        int radix = token.startsWith("x") || token.startsWith("X") ? 16 : 10;
        String digits = radix == 16 ? token.substring(1) : token;
        matcher.appendReplacement(out, Matcher.quoteReplacement(new String(Character.toChars(Integer.parseInt(digits, radix)))));
      } catch (RuntimeException ex) {
        matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
      }
    }
    matcher.appendTail(out);
    return out.toString();
  }

  public boolean initTesseract() {
    return PaddleEngineProvider.create(context, language) != null;
  }

  public void shutdown() {
    close();
  }

  public boolean isTesseractInitialized() {
    return PaddleEngineProvider.create(context, language) != null;
  }

  public void setPageSegMode(int mode) {
    // Ignored by PaddleOCR; retained for API compatibility while the UI migrates.
  }

  public void setPageSegmentationMode(OcrPageSegmentationMode mode) {
    pageSegmentationMode = mode != null ? mode : OcrPageSegmentationMode.SINGLE_BLOCK;
  }

  public OcrPageSegmentationMode getPageSegmentationMode() {
    return pageSegmentationMode;
  }

  public void setRecognitionMode(int mode) {
    // Paddle flavor is Paddle-only; legacy Tesseract preprocessing modes are ignored here.
  }

  public void setForceBinaryRobust(boolean enable) {}

  public void setReinitPerRun(boolean enable) {}

  public void setUseBestModelSettings(boolean enable) {}

  public boolean isUsingBestModelSettings() {
    return false;
  }

  public void setPaddleHighQualityDetectionEnabled(boolean enable) {
    paddleHighQualityDetectionEnabled = enable;
  }

  public void setLanguage(String language) {
    if (language != null && !language.isBlank()) this.language = language;
  }

  public void applyDefaultsForLanguage(String langSpec) {}

  public void ensureLanguageDataPresent(String langSpec) {}

  public String[] getAvailableLanguages() {
    return OcrModelManager.getAvailableLanguageCodes(context);
  }

  public boolean isLanguageAvailable(String lang) {
    try (OcrEngine engine = PaddleEngineProvider.create(context, lang)) {
      return engine != null;
    } catch (Exception e) {
      return false;
    }
  }

  public boolean setVariable(String var, String value) {
    return false;
  }

  public boolean setWhitelist(String chars) {
    return true;
  }

  public String getActiveWhitelist() {
    return null;
  }

  public OcrResultWords runOcrWithWords(Bitmap bitmap) {
    try (OcrEngine engine = PaddleEngineProvider.create(context, language)) {
      if (engine == null) return new OcrResultWords("", null, new ArrayList<>());
      if (engine instanceof PaddleOcrEngine paddleEngine) {
        paddleEngine.setHighQualityDetectionEnabled(paddleHighQualityDetectionEnabled);
      }
      return engine.run(bitmap);
    } catch (Exception e) {
      return new OcrResultWords("", null, new ArrayList<>());
    }
  }

  public OcrResultWords runOcrWithRetry(Bitmap bitmap) {
    return runOcrWithWords(bitmap);
  }

  public void logAllVariables() {}

  @Override
  public void close() {
    PaddleEngineProvider.releaseAll(context);
  }

  public OcrResultWithLayout runOcrWithLayout(Bitmap bitmap) {
    OcrResultWords result = runOcrWithRetry(bitmap);
    List<RegionOcrResult> regions = new ArrayList<>();
    regions.add(new RegionOcrResult(null, result));
    return new OcrResultWithLayout(result.text, result.meanConfidence, regions, null);
  }

  public boolean hasComplexLayout(Bitmap bitmap) {
    return false;
  }

  public int getDocumentColumnCount(Bitmap bitmap) {
    return 1;
  }

  public static class OcrResult {
    public final String text;
    public final Integer meanConfidence;

    public OcrResult(String text, Integer meanConfidence) {
      this.text = text;
      this.meanConfidence = meanConfidence;
    }
  }

  public static class OcrResultWords extends OcrResult {
    public final List<RecognizedWord> words;

    public OcrResultWords(String text, Integer meanConfidence, List<RecognizedWord> words) {
      super(text, meanConfidence);
      this.words = words != null ? words : new ArrayList<>();
    }
  }

  public static class OcrResultWithLayout extends OcrResult {
    public final List<RegionOcrResult> regionResults;
    public final AnalysisResult layoutAnalysis;

    public OcrResultWithLayout(
        String text,
        Integer meanConfidence,
        List<RegionOcrResult> regionResults,
        AnalysisResult layoutAnalysis) {
      super(text, meanConfidence);
      this.regionResults = regionResults != null ? regionResults : new ArrayList<>();
      this.layoutAnalysis = layoutAnalysis;
    }
  }

  public record RegionOcrResult(DocumentRegion region, OcrResultWords ocrResult) {}
}