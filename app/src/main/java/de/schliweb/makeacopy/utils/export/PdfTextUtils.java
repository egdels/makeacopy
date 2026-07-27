/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.export;

import android.content.Context;
import android.util.Log;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Utility class for PDF text layer operations extracted from PdfCreator. Contains RTL text
 * detection and reordering, font loading with fallbacks, and text rendering with font fallback
 * support.
 *
 * <p>This class cannot be instantiated.
 */
@UtilityClass
public final class PdfTextUtils {
  private static final String TAG = "PdfTextUtils";

  /**
   * Determines if a line of recognized words is predominantly RTL (Right-to-Left) script. This is
   * used for BiDi sorting in PDF text layers to ensure correct reading order for Arabic, Persian,
   * Hebrew, and other RTL scripts.
   *
   * @param line The list of recognized words in the line.
   * @return true if the line contains predominantly RTL characters, false otherwise.
   */
  public static boolean isRtlLine(List<RecognizedWord> line) {
    if (line == null || line.isEmpty()) return false;

    int rtlCount = 0;
    int ltrCount = 0;

    for (RecognizedWord w : line) {
      String text = w.getText();
      if (text == null) continue;

      for (int i = 0; i < text.length(); ) {
        int cp = text.codePointAt(i);
        byte directionality = Character.getDirectionality(cp);

        // RTL scripts: Arabic, Hebrew, etc.
        if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT
            || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
            || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
          rtlCount++;
        }
        // LTR scripts: Latin, etc.
        else if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT
            || directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
            || directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) {
          ltrCount++;
        }
        // Neutral characters (numbers, punctuation) are ignored

        i += Character.charCount(cp);
      }
    }

    // Line is RTL if it has more RTL characters than LTR characters
    return rtlCount > ltrCount;
  }

  /**
   * Checks if a single word/token contains predominantly RTL characters.
   *
   * @param text The text to check.
   * @return true if the text contains predominantly RTL characters, false otherwise.
   */
  public static boolean isRtlText(String text) {
    if (text == null || text.isEmpty()) return false;

    int rtlCount = 0;
    int ltrCount = 0;

    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      byte directionality = Character.getDirectionality(cp);

      if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT
          || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
          || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
          || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
        rtlCount++;
      } else if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT
          || directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
          || directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) {
        ltrCount++;
      }

      i += Character.charCount(cp);
    }

    return rtlCount > ltrCount;
  }

  /**
   * Checks if a list of recognized words contains any RTL (Right-to-Left) text. Used to determine
   * if gentle B/W processing should be applied to preserve fine strokes and diacritics in Arabic,
   * Persian, and Hebrew scripts.
   *
   * @param words The list of recognized words to check.
   * @return true if any word contains RTL characters, false otherwise.
   */
  public static boolean containsRtlText(List<RecognizedWord> words) {
    if (words == null || words.isEmpty()) return false;

    for (RecognizedWord w : words) {
      if (w == null) continue;
      String text = w.getText();
      if (isRtlText(text)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reorders text for correct PDF text layer representation. For RTL text that appears to be in
   * visual order (reversed), this method reverses it back to logical order so that copy/paste works
   * correctly.
   *
   * @param text The text to potentially reorder.
   * @return The text in logical order suitable for PDF text layer.
   */
  public static String reorderRtlForPdf(String text) {
    if (text == null || text.isEmpty()) return text;

    // Only process if the text contains RTL characters
    if (!isRtlText(text)) return text;

    // Use Java's Bidi class to analyze the text
    java.text.Bidi bidi = new java.text.Bidi(text, java.text.Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);

    if (bidi.isRightToLeft() || bidi.isLeftToRight()) {
      // Find first RTL character position
      int firstRtlPos = -1;
      for (int i = 0; i < text.length(); ) {
        int cp = text.codePointAt(i);
        byte dir = Character.getDirectionality(cp);
        if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
            || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
          if (firstRtlPos < 0) firstRtlPos = i;
        }
        i += Character.charCount(cp);
      }

      if (firstRtlPos >= 0) {
        // Reverse the string to convert from visual to logical order
        StringBuilder sb = new StringBuilder(text);
        return sb.reverse().toString();
      }
    }

    return text;
  }

  /**
   * Loads a list of PDF fonts with fallback mechanisms based on provided font files.
   *
   * @param document the PDF document instance where fonts will be loaded into
   * @param context the Android context used for asset access and caching
   * @return a list of PDFont objects, including the successfully loaded fonts and any fallbacks
   */
  public static List<PDFont> loadFontsWithFallbacks(PDDocument document, Context context) {
    List<PDFont> fonts = new ArrayList<>();
    String[] candidates =
        new String[] {
          "fonts/NotoSans-Regular.ttf",
          "fonts/NotoSansThai-Regular.ttf",
          "fonts/NotoSansCJKsc-Regular.ttf",
          // Kana/Japanese punctuation subset of Noto Sans JP (OFL). The bundled CJKsc subset
          // only covers Han + CJK punctuation/fullwidth ranges; Hiragana/Katakana would
          // otherwise degrade to U+FFFD in the searchable text layer (issue #88).
          "fonts/NotoSansJP-Kana.ttf",
          "fonts/NotoNaskhArabic-Regular.ttf",
          "fonts/NotoSansDevanagari-Regular.ttf"
        };
    for (String path : candidates) {
      try {
        File f = copyAssetToCache(context, path);
        Log.d(TAG, "Loading font (file-based): " + path + " (" + f.length() + " bytes)");
        fonts.add(PDType0Font.load(document, f));
      } catch (Exception exception) {
        Log.w(TAG, "Font not found: " + path);
        Log.w(TAG, "Exception: " + exception.getMessage());
      }
    }
    if (fonts.isEmpty()) {
      try {
        File f = copyAssetToCache(context, "fonts/NotoSans-Regular.ttf");
        fonts.add(PDType0Font.load(document, f));
      } catch (Exception e) {
        Log.w(TAG, "No embedded font found, falling back to Helvetica (not embedded)");
        fonts.add(PDType1Font.HELVETICA);
      }
    }
    return fonts;
  }

  /**
   * Copies a file from the assets directory to the cache directory.
   *
   * @param ctx the context used to access the assets and cache directories
   * @param assetPath the relative path of the asset to be copied
   * @return the file object pointing to the copied file in the cache directory
   * @throws java.io.IOException if an I/O error occurs during copying
   */
  static File copyAssetToCache(Context ctx, String assetPath) throws java.io.IOException {
    File out = new File(ctx.getCacheDir(), new File(assetPath).getName());
    if (out.exists() && out.length() > 0) return out;
    try (InputStream in = ctx.getAssets().open(assetPath);
        FileOutputStream os = new FileOutputStream(out)) {
      byte[] buf = new byte[16 * 1024];
      int r;
      while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
    }
    return out;
  }

  /**
   * Writes text to a PDF page content stream with support for font fallbacks.
   *
   * @param cs the {@link PDPageContentStream} used to draw the text
   * @param token the text string to be displayed
   * @param fontSize the font size for rendering the text
   * @param fonts a {@link List} of {@link PDFont} objects to try for displaying the text
   * @throws Exception if there is an error during text drawing or font operations
   */
  public static void showTextWithFallbacks(
      PDPageContentStream cs, String token, float fontSize, List<PDFont> fonts) throws Exception {
    if (token == null || token.isEmpty()) return;
    if (fonts == null || fonts.isEmpty()) throw new IllegalStateException("No fonts available");

    final int len = token.length();
    int i = 0;
    while (i < len) {
      // 1) Find a suitable font for this code point
      int cp = token.codePointAt(i);
      String ch = new String(Character.toChars(cp));

      int fontIdx = -1;
      for (int f = 0; f < fonts.size(); f++) {
        try {
          fonts.get(f).encode(ch); // probe-encode
          fontIdx = f;
          break;
        } catch (Exception ignore) {
          // Best-effort; failure is non-critical
        }
      }
      if (fontIdx < 0) {
        // Fallback: replace with U+FFFD using the first font
        cs.setFont(fonts.get(0), fontSize);
        try {
          cs.showText("\uFFFD");
        } catch (Exception ignore) {
          // Best-effort; failure is non-critical
        }
        i += Character.charCount(cp);
        continue;
      }

      // 2) Collect consecutive characters that use the same font
      PDFont chosenFont = fonts.get(fontIdx);
      StringBuilder run = new StringBuilder();
      run.append(ch);
      i += Character.charCount(cp);

      while (i < len) {
        int nextCp = token.codePointAt(i);
        String nextCh = new String(Character.toChars(nextCp));
        boolean ok = false;
        try {
          chosenFont.encode(nextCh);
          ok = true;
        } catch (Exception ignore) {
          // Best-effort; failure is non-critical
        }
        if (!ok) break;
        run.append(nextCh);
        i += Character.charCount(nextCp);
      }

      // 3) Emit the run
      cs.setFont(chosenFont, fontSize);
      try {
        cs.showText(run.toString());
      } catch (Exception e) {
        // Fallback: emit char-by-char, replacing failures with U+FFFD
        for (int j = 0; j < run.length(); ) {
          int c = run.codePointAt(j);
          String s = new String(Character.toChars(c));
          try {
            cs.showText(s);
          } catch (Exception ignore) {
            try {
              cs.showText("\uFFFD");
            } catch (Exception ignore2) {
              // Best-effort; failure is non-critical
            }
          }
          j += Character.charCount(c);
        }
      }
    }
  }
}
