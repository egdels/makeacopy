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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumentierte Tests für die Paddle-OCR-Engine auf Arabisch (ara) und Persisch (fas).
 *
 * <p>Paddle-Pendant zu {@code de.schliweb.makeacopy.ocr.ArabicPersianOcrTest} (Tesseract).
 * Es werden dieselben PDF-Test-Assets unter {@code androidTest/assets/instrumented_test_data/}
 * verwendet (durch AGP für den paddle-Flavor merged) und über den
 * {@link PaddleEngineProvider} ausgeführt.
 */
@RunWith(AndroidJUnit4.class)
public class ArabicPersianPaddleOcrTest {

  private static final String ARABIC_PDF_ASSET = "instrumented_test_data/test_arabic.pdf";
  private static final String PERSIAN_PDF_ASSET = "instrumented_test_data/test_persian.pdf";

  // PDF-Varianten für Robustheits-Tests
  private static final String ARABIC_INVERTED_PDF =
      "instrumented_test_data/test_arabic_inverted.pdf";
  private static final String ARABIC_SCAN_NOISE_PDF =
      "instrumented_test_data/test_arabic_SCAN_NOISE.pdf";
  private static final String ARABIC_MIXED_LIGHT_PDF =
      "instrumented_test_data/test_arabic_MIXED_LIGHT.pdf";
  private static final String ARABIC_MIXED_LIGHTING_PDF =
      "instrumented_test_data/test_arabic_MIXED_LIGHTING.pdf";
  private static final String PERSIAN_INVERTED_PDF =
      "instrumented_test_data/test_persian_inverted.pdf";
  private static final String PERSIAN_SCAN_NOISE_PDF =
      "instrumented_test_data/test_persian_SCAN_NOISE.pdf";
  private static final String PERSIAN_MIXED_LIGHT_PDF =
      "instrumented_test_data/test_persian_MIXED_LIGHT.pdf";
  private static final String PERSIAN_MIXED_LIGHTING_PDF =
      "instrumented_test_data/test_persian_MIXED_LIGHTING.pdf";

  private static Context context;

  @BeforeClass
  public static void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  // ==================== Engine-Verfügbarkeit ====================

  /** Stellt sicher, dass die Paddle-Engine für Arabisch (ara) erstellt werden kann. */
  @Test
  public void testPaddleEngineCreate_Arabic() throws Exception {
    OcrEngine engine = PaddleEngineProvider.create(context, "ara");
    assertNotNull("Paddle engine must be creatable for 'ara'", engine);
    try {
      assertTrue("Paddle engine must be available", engine.isAvailable(context));
      assertNotNull("Engine id must not be null", engine.id());
      System.out.println("[DEBUG_LOG] Paddle engine id=" + engine.id());
    } finally {
      closeQuietly(engine);
    }
  }

  /** Stellt sicher, dass die Paddle-Engine für Persisch (fas) erstellt werden kann. */
  @Test
  public void testPaddleEngineCreate_Persian() throws Exception {
    OcrEngine engine = PaddleEngineProvider.create(context, "fas");
    assertNotNull("Paddle engine must be creatable for 'fas'", engine);
    try {
      assertTrue("Paddle engine must be available", engine.isAvailable(context));
    } finally {
      closeQuietly(engine);
    }
  }

  // ==================== Standard-PDFs ====================

  /** Paddle-OCR auf dem Standard-Arabic-PDF. */
  @Test
  public void testArabicPdf_Paddle() throws Exception {
    runPaddleOnPdf(ARABIC_PDF_ASSET, "ara", "Arabic standard");
  }

  /** Paddle-OCR auf dem Standard-Persian-PDF. */
  @Test
  public void testPersianPdf_Paddle() throws Exception {
    runPaddleOnPdf(PERSIAN_PDF_ASSET, "fas", "Persian standard");
  }

  // ==================== Arabic Varianten ====================

  @Test
  public void testArabicInvertedPdf_Paddle() throws Exception {
    runPaddleOnPdf(ARABIC_INVERTED_PDF, "ara", "Arabic inverted");
  }

  @Test
  public void testArabicScanNoisePdf_Paddle() throws Exception {
    runPaddleOnPdf(ARABIC_SCAN_NOISE_PDF, "ara", "Arabic scan noise");
  }

  @Test
  public void testArabicMixedLightPdf_Paddle() throws Exception {
    runPaddleOnPdf(ARABIC_MIXED_LIGHT_PDF, "ara", "Arabic mixed light");
  }

  @Test
  public void testArabicMixedLightingPdf_Paddle() throws Exception {
    runPaddleOnPdf(ARABIC_MIXED_LIGHTING_PDF, "ara", "Arabic mixed lighting");
  }

  // ==================== Persian Varianten ====================

  @Test
  public void testPersianInvertedPdf_Paddle() throws Exception {
    runPaddleOnPdf(PERSIAN_INVERTED_PDF, "fas", "Persian inverted");
  }

  @Test
  public void testPersianScanNoisePdf_Paddle() throws Exception {
    runPaddleOnPdf(PERSIAN_SCAN_NOISE_PDF, "fas", "Persian scan noise");
  }

  @Test
  public void testPersianMixedLightPdf_Paddle() throws Exception {
    runPaddleOnPdf(PERSIAN_MIXED_LIGHT_PDF, "fas", "Persian mixed light");
  }

  @Test
  public void testPersianMixedLightingPdf_Paddle() throws Exception {
    runPaddleOnPdf(PERSIAN_MIXED_LIGHTING_PDF, "fas", "Persian mixed lighting");
  }

  // ==================== Helpers ====================

  private static void closeQuietly(OcrEngine engine) {
    if (engine == null) return;
    try {
      engine.close();
    } catch (Throwable ignore) {
      // ignore
    }
  }

  /**
   * Führt Paddle-OCR auf der ersten Seite des angegebenen PDF-Assets aus und prüft, dass
   * Wörter mit Bounding-Box erkannt wurden und arabische/persische Zeichen
   * (Unicode 0x0600..0x06FF) im Ergebnis enthalten sind. Fehlt das Asset, wird der Test
   * übersprungen.
   */
  private void runPaddleOnPdf(String pdfAsset, String langCode, String description)
      throws Exception {
    if (!assetExists(pdfAsset)) {
      System.out.println("[DEBUG_LOG] Skipping " + description + " - PDF not found: " + pdfAsset);
      return;
    }

    System.out.println("[DEBUG_LOG] Paddle test: " + description + " (" + pdfAsset + ")");

    Bitmap page = renderPdfFirstPage(pdfAsset);
    assertNotNull(description + " - PDF page should be rendered", page);

    OcrEngine engine = PaddleEngineProvider.create(context, langCode);
    assertNotNull(description + " - Paddle engine must be creatable", engine);

    try {
      OCRHelper.OcrResultWords result = engine.run(page);
      assertNotNull(description + " - OCR result must not be null", result);
      assertNotNull(description + " - OCR text must not be null", result.text);

      String text = result.text;
      System.out.println(
          "[DEBUG_LOG] " + description + " Paddle text length: " + text.length());
      System.out.println(
          "[DEBUG_LOG] "
              + description
              + " Paddle text (first 300 chars): "
              + text.substring(0, Math.min(300, text.length())));

      assertFalse(description + " - OCR text should not be empty", text.trim().isEmpty());

      boolean hasArabicChars =
          text.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF);
      assertTrue(
          description + " - Should contain Arabic/Persian characters", hasArabicChars);

      assertNotNull(description + " - words list must not be null", result.words);
      assertFalse(description + " - words list should not be empty", result.words.isEmpty());

      long arabicCharCount =
          text.codePoints().filter(cp -> cp >= 0x0600 && cp <= 0x06FF).count();
      System.out.println(
          "[DEBUG_LOG] "
              + description
              + " - Arabic char count: "
              + arabicCharCount
              + ", words: "
              + result.words.size()
              + ", meanConfidence: "
              + result.meanConfidence);
    } finally {
      closeQuietly(engine);
      page.recycle();
    }
  }

  private boolean assetExists(String assetPath) {
    try (InputStream is = context.getAssets().open(assetPath)) {
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** Rendert die erste Seite eines PDF-Assets als Bitmap (300 DPI). */
  private Bitmap renderPdfFirstPage(String assetPath) throws IOException {
    File pdfFile = copyAssetToCache(assetPath);
    try (ParcelFileDescriptor pfd =
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = new PdfRenderer(pfd)) {
      if (renderer.getPageCount() == 0) {
        return null;
      }
      PdfRenderer.Page page = renderer.openPage(0);
      try {
        float scale = 300f / 72f;
        int width = (int) (page.getWidth() * scale);
        int height = (int) (page.getHeight() * scale);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.WHITE);
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return bitmap;
      } finally {
        page.close();
      }
    }
  }

  private File copyAssetToCache(String assetPath) throws IOException {
    File outFile = new File(context.getCacheDir(), new File(assetPath).getName());
    if (outFile.exists() && outFile.length() > 0) {
      return outFile;
    }
    try (InputStream in = context.getAssets().open(assetPath);
        FileOutputStream out = new FileOutputStream(outFile)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
    }
    return outFile;
  }
}
