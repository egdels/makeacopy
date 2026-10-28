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
import de.schliweb.makeacopy.utils.ocr.paddle.PaddleEngineFactory;
import de.schliweb.makeacopy.utils.ocr.paddle.PaddleOcrEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs PaddleOCR for one language group; one instance per OCR job. */
public class OCRHelper implements AutoCloseable {
  private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#([xX]?[0-9A-Fa-f]+);");

  private final Context context;
  private String language = "latin";
  private boolean paddleHighQualityDetectionEnabled;

  public OCRHelper(Context context) {
    this.context = context != null ? context.getApplicationContext() : null;
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
        matcher.appendReplacement(
            out,
            Matcher.quoteReplacement(
                new String(Character.toChars(Integer.parseInt(digits, radix)))));
      } catch (RuntimeException ex) {
        matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
      }
    }
    matcher.appendTail(out);
    return out.toString();
  }

  public void shutdown() {
    close();
  }

  public void setPaddleHighQualityDetectionEnabled(boolean enable) {
    paddleHighQualityDetectionEnabled = enable;
  }

  public void setLanguage(String language) {
    if (language != null && !language.isBlank()) this.language = language;
  }

  public String getLanguage() {
    return language;
  }

  public String[] getAvailableLanguages() {
    return OcrModelManager.getAvailableLanguageCodes(context);
  }

  public boolean isLanguageAvailable(String lang) {
    return context != null && PaddleEngineFactory.isLanguageBundled(context, lang);
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

  @Override
  public void close() {
    PaddleEngineProvider.releaseAll(context);
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
}
