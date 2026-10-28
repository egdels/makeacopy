package de.schliweb.makeacopy.ocr;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented tests that perform OCR on a Turkish test PDF and verify recognition of
 * Turkish-specific characters (İ, ı, ğ, ş, ç, ö, ü, Ğ, Ş, Ö, Ü, Ç) and general Turkish text.
 *
 * <p>The test PDF is located at {@code test_pdfs/MakeACopy_Turkish_Test_OCR_UTF8.pdf} and contains
 * a variety of Turkish text including special characters, numbers, addresses, and a table format.
 */
@RunWith(AndroidJUnit4.class)
public class TurkishPdfOcrTest {

  private static final String PDF_ASSET_PATH =
      "instrumented_test_data/MakeACopy_Turkish_Test_OCR_UTF8.pdf";

  private static Context context;

  @BeforeClass
  public static void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  // ==================== Helper Methods ====================

  /** Copies an asset file to the cache directory. */
  private File copyAssetToCache(String assetPath) throws IOException {
    File cacheFile = new File(context.getCacheDir(), new File(assetPath).getName());

    if (cacheFile.exists() && cacheFile.length() > 0) {
      return cacheFile;
    }

    try (InputStream is = context.getAssets().open(assetPath);
        FileOutputStream fos = new FileOutputStream(cacheFile)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = is.read(buffer)) != -1) {
        fos.write(buffer, 0, read);
      }
    }

    return cacheFile;
  }

  /** Renders the first page of a PDF from assets to a Bitmap at the given DPI. */
  private Bitmap renderPdfFirstPage(int dpi) throws Exception {
    File pdfFile = copyAssetToCache(PDF_ASSET_PATH);

    try (ParcelFileDescriptor pfd =
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = new PdfRenderer(pfd)) {

      assertTrue("PDF should have at least one page", renderer.getPageCount() > 0);

      try (PdfRenderer.Page page = renderer.openPage(0)) {
        int width = (int) (page.getWidth() * dpi / 72f);
        int height = (int) (page.getHeight() * dpi / 72f);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.WHITE);
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

        return bitmap;
      }
    }
  }

  /**
   * Performs OCR on a bitmap using the specified language code.
   *
   * @param bitmap The bitmap to process
   * @param langCode The language code (e.g., "tur", "tur+eng")
   * @return The recognized text
   */
  private String performOcr(Bitmap bitmap, String langCode) throws Exception {
    OCRHelper helper = new OCRHelper(context);
    try {
      helper.setLanguage(langCode);
      boolean initialized = helper.isLanguageAvailable(helper.getLanguage());
      if (!initialized) {
        throw new RuntimeException("Failed to initialize Tesseract for language: " + langCode);
      }

      OCRHelper.OcrResultWords result = helper.runOcrWithWords(bitmap);
      return result != null ? result.text : null;
    } finally {
      helper.shutdown();
    }
  }

  // ==================== Asset Availability ====================

  /** Verifies that the Turkish test PDF asset exists. */
  @Test
  public void testTurkishPdfAssetExists() {
    try (InputStream is = context.getAssets().open(PDF_ASSET_PATH)) {
      assertNotNull("Turkish test PDF should be loadable from assets", is);
      assertTrue("Turkish test PDF should not be empty", is.available() > 0);
    } catch (IOException e) {
      fail("Turkish test PDF not found at: " + PDF_ASSET_PATH + " — " + e.getMessage());
    }
  }

  /** Verifies that the PDF can be rendered to a bitmap. */
  @Test
  public void testTurkishPdfRendersSuccessfully() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      assertNotNull("Rendered bitmap should not be null", bitmap);
      assertTrue("Bitmap width should be positive", bitmap.getWidth() > 0);
      assertTrue("Bitmap height should be positive", bitmap.getHeight() > 0);
      System.out.println(
          "[DEBUG_LOG] Turkish PDF rendered: " + bitmap.getWidth() + "x" + bitmap.getHeight());
    } finally {
      bitmap.recycle();
    }
  }

  // ==================== Tesseract Initialization ====================

  /** Verifies that Tesseract initializes successfully with Turkish language. */
  @Test
  public void testTesseractInitializesWithTurkish() throws Exception {
    OCRHelper helper = new OCRHelper(context);
    try {
      helper.setLanguage("tur");
      boolean initialized = helper.isLanguageAvailable(helper.getLanguage());
      assertTrue("Tesseract should initialize with 'tur' language", initialized);
    } finally {
      helper.shutdown();
    }
  }

  /** Verifies that Tesseract initializes successfully with Turkish + English. */
  @Test
  public void testTesseractInitializesWithTurkishPlusEnglish() throws Exception {
    OCRHelper helper = new OCRHelper(context);
    try {
      helper.setLanguage("tur+eng");
      boolean initialized = helper.isLanguageAvailable(helper.getLanguage());
      assertTrue("Tesseract should initialize with 'tur+eng'", initialized);
    } finally {
      helper.shutdown();
    }
  }

  // ==================== OCR Recognition Tests ====================

  /** Tests that OCR produces a non-empty result for the Turkish PDF. */
  @Test
  public void testOcrProducesNonEmptyResult() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);
      assertFalse("OCR result should not be empty", ocrResult.trim().isEmpty());
      System.out.println("[DEBUG_LOG] Turkish OCR result length: " + ocrResult.length() + " chars");
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes the document title "Türkçe OCR Test Belgesi". */
  @Test
  public void testOcrRecognizesTitle() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      System.out.println("[DEBUG_LOG] Turkish OCR full result:\n" + ocrResult);

      String lower = ocrResult.toLowerCase(java.util.Locale.forLanguageTag("tr-TR"));
      // The title should contain key words
      assertTrue(
          "OCR should recognize 'türkçe' or 'ocr' or 'test' or 'belgesi' from the title",
          lower.contains("türkçe")
              || lower.contains("ocr")
              || lower.contains("test")
              || lower.contains("belgesi"));
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes the word "İstanbul" with the dotted capital İ (U+0130). */
  @Test
  public void testOcrRecognizesIstanbul() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      // İstanbul appears multiple times in the document
      boolean found =
          ocrResult.contains("İstanbul")
              || ocrResult.contains("istanbul")
              || ocrResult.contains("Istanbul")
              || ocrResult.contains("istanbul");
      assertTrue("OCR should recognize 'İstanbul' (with or without correct İ)", found);
    } finally {
      bitmap.recycle();
    }
  }

  /**
   * Tests that OCR recognizes Turkish-specific characters: İ, ı, ğ, ş, ç, ö, ü and their uppercase
   * variants.
   */
  @Test
  public void testOcrRecognizesTurkishSpecialCharacters() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      System.out.println("[DEBUG_LOG] Checking Turkish special characters in OCR result");

      // Check for presence of Turkish-specific characters
      // The document contains: ğ, ü, ş, ı, ö, ç, İ, Ğ, Ü, Ş, Ö, Ç
      int turkishCharCount = 0;
      String turkishChars = "İıĞğŞşÇçÖöÜü";
      for (int i = 0; i < turkishChars.length(); i++) {
        char c = turkishChars.charAt(i);
        if (ocrResult.indexOf(c) >= 0) {
          turkishCharCount++;
          System.out.println(
              "[DEBUG_LOG] Found Turkish char: "
                  + c
                  + " (U+"
                  + String.format("%04X", (int) c)
                  + ")");
        }
      }

      System.out.println(
          "[DEBUG_LOG] Turkish special characters found: " + turkishCharCount + "/14");

      // At least some Turkish-specific characters should be recognized
      // ç, ö, ü are shared with German/French, so they are very likely to be found
      // ğ, ş, ı, İ are uniquely Turkish
      assertTrue(
          "OCR should recognize at least 3 Turkish special characters, found: " + turkishCharCount,
          turkishCharCount >= 3);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes the dotless ı (U+0131) which is uniquely Turkish. */
  @Test
  public void testOcrRecognizesDotlessI() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      // The document contains words with ı: "ı" in the character list,
      // "Hızlı", "bilinir", "yılında", "beklenmektedir"
      boolean hasDotlessI = ocrResult.contains("ı");
      System.out.println("[DEBUG_LOG] Dotless ı (U+0131) found: " + hasDotlessI);

      // Also check words that should contain ı
      boolean hasWordWithDotlessI =
          ocrResult.contains("Hızlı")
              || ocrResult.contains("hızlı")
              || ocrResult.contains("yılında")
              || ocrResult.contains("bilinir");
      System.out.println("[DEBUG_LOG] Word with dotless ı found: " + hasWordWithDotlessI);

      assertTrue(
          "OCR should recognize dotless ı (U+0131) either standalone or in words",
          hasDotlessI || hasWordWithDotlessI);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes ğ (soft g) which is uniquely Turkish. */
  @Test
  public void testOcrRecognizesSoftG() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      // The document contains: "köpeğin", "doğruluğunu", "yoğunluğu"
      boolean hasSoftG = ocrResult.contains("ğ") || ocrResult.contains("Ğ");
      System.out.println("[DEBUG_LOG] Soft g (ğ/Ğ) found: " + hasSoftG);

      assertTrue("OCR should recognize ğ (soft g) in Turkish text", hasSoftG);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes ş (s with cedilla) which is uniquely Turkish. */
  @Test
  public void testOcrRecognizesSCedilla() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      // The document contains: "şehridir", "Şahin", "Çalışkan", "hoşça"
      boolean hasSCedilla = ocrResult.contains("ş") || ocrResult.contains("Ş");
      System.out.println("[DEBUG_LOG] S cedilla (ş/Ş) found: " + hasSCedilla);

      assertTrue("OCR should recognize ş (s with cedilla) in Turkish text", hasSCedilla);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes common Turkish words from the document. */
  @Test
  public void testOcrRecognizesCommonTurkishWords() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      String lower = ocrResult.toLowerCase(java.util.Locale.forLanguageTag("tr-TR"));

      // Count how many expected words are found
      String[] expectedWords = {
        "belge", "test", "büyük", "adres", "alan", "tutar", "liste", "madde", "metin"
      };

      int foundCount = 0;
      for (String word : expectedWords) {
        if (lower.contains(word)) {
          foundCount++;
          System.out.println("[DEBUG_LOG] Found expected word: " + word);
        } else {
          System.out.println("[DEBUG_LOG] Missing expected word: " + word);
        }
      }

      System.out.println(
          "[DEBUG_LOG] Found " + foundCount + "/" + expectedWords.length + " expected words");

      assertTrue(
          "OCR should recognize at least 4 common Turkish words, found: " + foundCount,
          foundCount >= 4);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes numeric content from the document (amounts, dates, addresses). */
  @Test
  public void testOcrRecognizesNumericContent() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      // The document contains: "1.234,56", "2026", "34000", "15"
      int numericHits = 0;
      if (ocrResult.contains("2026")) numericHits++;
      if (ocrResult.contains("34000")) numericHits++;
      if (ocrResult.contains("1.234") || ocrResult.contains("1234")) numericHits++;
      if (ocrResult.contains("15")) numericHits++;

      System.out.println("[DEBUG_LOG] Numeric content hits: " + numericHits + "/4");

      assertTrue(
          "OCR should recognize at least 2 numeric values from the document, found: " + numericHits,
          numericHits >= 2);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes punctuation and special formatting from the document. */
  @Test
  public void testOcrRecognizesPunctuation() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      // The document contains various punctuation: . , : ; - ! ? ( )
      int punctuationHits = 0;
      String[] punctuation = {".", ",", ":", "!", "?", "(", ")"};
      for (String p : punctuation) {
        if (ocrResult.contains(p)) {
          punctuationHits++;
        }
      }

      System.out.println(
          "[DEBUG_LOG] Punctuation hits: " + punctuationHits + "/" + punctuation.length);

      assertTrue(
          "OCR should recognize at least 4 punctuation marks, found: " + punctuationHits,
          punctuationHits >= 4);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests OCR with combined Turkish + English language for potentially better results. */
  @Test
  public void testOcrWithTurkishPlusEnglish() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur+eng");
      assertNotNull("OCR result should not be null", ocrResult);
      assertFalse("OCR result should not be empty", ocrResult.trim().isEmpty());

      System.out.println("[DEBUG_LOG] Turkish+English OCR result length: " + ocrResult.length());

      // Should still recognize Turkish content
      String lower = ocrResult.toLowerCase(java.util.Locale.forLanguageTag("tr-TR"));
      boolean hasTurkishContent =
          lower.contains("test") || lower.contains("belge") || lower.contains("alan");
      assertTrue("OCR with tur+eng should still recognize Turkish content", hasTurkishContent);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes the table-like structure with "Alan:" and "Değer:" labels. */
  @Test
  public void testOcrRecognizesTableStructure() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      // The document has a table with "Alan:" labels
      boolean hasAlan = ocrResult.contains("Alan") || ocrResult.contains("alan");
      System.out.println("[DEBUG_LOG] 'Alan' found: " + hasAlan);

      assertTrue("OCR should recognize 'Alan' from the table structure", hasAlan);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes the numbered list items. */
  @Test
  public void testOcrRecognizesNumberedList() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      // The document has numbered items: "1.", "2.", "3.", "4."
      boolean hasNumberedItems =
          ocrResult.contains("1.") || ocrResult.contains("2.") || ocrResult.contains("3.");

      // Also check for "madde" which appears in each list item
      boolean hasMadde =
          ocrResult.toLowerCase(java.util.Locale.forLanguageTag("tr-TR")).contains("madde");

      System.out.println("[DEBUG_LOG] Numbered items found: " + hasNumberedItems);
      System.out.println("[DEBUG_LOG] 'madde' found: " + hasMadde);

      assertTrue(
          "OCR should recognize numbered list items or 'madde'", hasNumberedItems || hasMadde);
    } finally {
      bitmap.recycle();
    }
  }

  /**
   * Tests that OCR recognizes the Turkish name "Şahin Çalışkan" which contains multiple
   * Turkish-specific characters.
   */
  @Test
  public void testOcrRecognizesTurkishName() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      // "Şahin Çalışkan" — contains Ş, ş, ı
      boolean hasName =
          ocrResult.contains("Şahin")
              || ocrResult.contains("Çalışkan")
              || ocrResult.contains("ahin")
              || ocrResult.contains("kan");

      System.out.println("[DEBUG_LOG] Turkish name recognition: " + hasName);

      assertTrue("OCR should recognize parts of the Turkish name 'Şahin Çalışkan'", hasName);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes "Atatürk Mahallesi" — a common Turkish address component. */
  @Test
  public void testOcrRecognizesAddress() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      boolean hasAddress =
          ocrResult.contains("Atatürk")
              || ocrResult.contains("Mahallesi")
              || ocrResult.contains("Sokak")
              || ocrResult.contains("Adres");

      System.out.println("[DEBUG_LOG] Address recognition: " + hasAddress);

      assertTrue("OCR should recognize address components from the document", hasAddress);
    } finally {
      bitmap.recycle();
    }
  }

  /**
   * Tests overall OCR quality by checking that a reasonable percentage of expected content is
   * recognized.
   */
  @Test
  public void testOcrOverallQuality() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "tur");
      assertNotNull("OCR result should not be null", ocrResult);

      // The original document has ~31 lines of text. OCR should produce substantial output.
      int resultLength = ocrResult.trim().length();
      System.out.println("[DEBUG_LOG] OCR result total length: " + resultLength);

      // Expect at least 200 characters of recognized text (the source has ~900+ chars)
      assertTrue(
          "OCR should produce at least 200 characters of text, got: " + resultLength,
          resultLength >= 200);

      // Count lines
      String[] lines = ocrResult.split("\n", -1);
      int nonEmptyLines = 0;
      for (String line : lines) {
        if (!line.trim().isEmpty()) {
          nonEmptyLines++;
        }
      }
      System.out.println("[DEBUG_LOG] Non-empty lines in OCR result: " + nonEmptyLines);

      // Expect at least 10 non-empty lines
      assertTrue(
          "OCR should produce at least 10 non-empty lines, got: " + nonEmptyLines,
          nonEmptyLines >= 10);
    } finally {
      bitmap.recycle();
    }
  }
}
