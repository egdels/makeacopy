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
 * Instrumented tests that perform OCR on a Hindi test PDF and verify recognition of Devanagari
 * script, Hindi words, mixed Hindi+English text, and Devanagari numerals.
 *
 * <p>The test PDF is located at {@code test_pdfs/MakeACopy_Hindi_Test_OCR.pdf} and contains Hindi
 * text including common words, consonants, vowel marks (matras), numerals, and mixed-language
 * content.
 */
@RunWith(AndroidJUnit4.class)
public class HindiPdfOcrTest {

  private static final String PDF_ASSET_PATH =
      "instrumented_test_data/MakeACopy_Hindi_Test_OCR.pdf";

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
   * @param langCode The language code (e.g., "hin", "hin+eng")
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

  /** Verifies that the Hindi test PDF asset exists. */
  @Test
  public void testHindiPdfAssetExists() {
    try (InputStream is = context.getAssets().open(PDF_ASSET_PATH)) {
      assertNotNull("Hindi test PDF should be loadable from assets", is);
      assertTrue("Hindi test PDF should not be empty", is.available() > 0);
    } catch (IOException e) {
      fail("Hindi test PDF not found at: " + PDF_ASSET_PATH + " — " + e.getMessage());
    }
  }

  /** Verifies that the PDF can be rendered to a bitmap. */
  @Test
  public void testHindiPdfRendersSuccessfully() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      assertNotNull("Rendered bitmap should not be null", bitmap);
      assertTrue("Bitmap width should be positive", bitmap.getWidth() > 0);
      assertTrue("Bitmap height should be positive", bitmap.getHeight() > 0);
      System.out.println(
          "[DEBUG_LOG] Hindi PDF rendered: " + bitmap.getWidth() + "x" + bitmap.getHeight());
    } finally {
      bitmap.recycle();
    }
  }

  // ==================== Tesseract Initialization ====================

  /** Verifies that Tesseract initializes successfully with Hindi language. */
  @Test
  public void testTesseractInitializesWithHindi() throws Exception {
    OCRHelper helper = new OCRHelper(context);
    try {
      helper.setLanguage("hin");
      boolean initialized = helper.isLanguageAvailable(helper.getLanguage());
      assertTrue("Tesseract should initialize with 'hin' language", initialized);
    } finally {
      helper.shutdown();
    }
  }

  /** Verifies that Tesseract initializes successfully with Hindi + English. */
  @Test
  public void testTesseractInitializesWithHindiPlusEnglish() throws Exception {
    OCRHelper helper = new OCRHelper(context);
    try {
      helper.setLanguage("hin+eng");
      boolean initialized = helper.isLanguageAvailable(helper.getLanguage());
      assertTrue("Tesseract should initialize with 'hin+eng'", initialized);
    } finally {
      helper.shutdown();
    }
  }

  // ==================== OCR Recognition Tests ====================

  /** Tests that OCR produces a non-empty result for the Hindi PDF. */
  @Test
  public void testOcrProducesNonEmptyResult() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(150);
    try {
      String ocrResult = performOcr(bitmap, "hin");
      assertNotNull("OCR result should not be null", ocrResult);
      assertFalse("OCR result should not be empty", ocrResult.trim().isEmpty());
      System.out.println("[DEBUG_LOG] Hindi OCR result length: " + ocrResult.length() + " chars");
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes the title containing Hindi OCR test document keywords. */
  @Test
  public void testOcrRecognizesTitle() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(200);
    try {
      String ocrResult = performOcr(bitmap, "hin");
      assertNotNull("OCR result should not be null", ocrResult);

      System.out.println("[DEBUG_LOG] Hindi OCR full result:\n" + ocrResult);

      // Title: हिंदी ओसीआर परीक्षण दस्तावेज़
      // Check for key words from the title
      boolean found =
          ocrResult.contains("\u0939\u093f\u0902\u0926\u0940") // हिंदी
              || ocrResult.contains("\u092a\u0930\u0940\u0915\u094d\u0937\u0923") // परीक्षण
              || ocrResult.contains(
                  "\u0926\u0938\u094d\u0924\u093e\u0935\u0947\u091c\u093c"); // दस्तावेज़
      assertTrue(
          "OCR should recognize at least one word from the Hindi title "
              + "(\u0939\u093f\u0902\u0926\u0940/\u092a\u0930\u0940\u0915\u094d\u0937\u0923/\u0926\u0938\u094d\u0924\u093e\u0935\u0947\u091c\u093c)",
          found);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes the word भारत (India). */
  @Test
  public void testOcrRecognizesBharat() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(200);
    try {
      String ocrResult = performOcr(bitmap, "hin");
      assertNotNull("OCR result should not be null", ocrResult);

      // भारत appears multiple times in the document
      assertTrue(
          "OCR should recognize '\u092d\u093e\u0930\u0924' (Bharat/India)",
          ocrResult.contains("\u092d\u093e\u0930\u0924"));
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes common Hindi words from the document. */
  @Test
  public void testOcrRecognizesCommonHindiWords() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(200);
    try {
      String ocrResult = performOcr(bitmap, "hin");
      assertNotNull("OCR result should not be null", ocrResult);

      System.out.println("[DEBUG_LOG] Hindi OCR result for common words:\n" + ocrResult);

      // Check for common Hindi words that appear in the document
      int wordsFound = 0;
      String[] expectedWords = {
        "\u092d\u093e\u0930\u0924", // भारत
        "\u0939\u0948", // है
        "\u090f\u0915", // एक
        "\u0926\u0947\u0936", // देश
        "\u0928\u092e\u0938\u094d\u0924\u0947", // नमस्ते
      };

      for (String word : expectedWords) {
        if (ocrResult.contains(word)) {
          wordsFound++;
        }
      }

      assertTrue(
          "OCR should recognize at least 2 out of 5 common Hindi words, found: " + wordsFound,
          wordsFound >= 2);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes Devanagari consonants. */
  @Test
  public void testOcrRecognizesDevanagariConsonants() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(200);
    try {
      String ocrResult = performOcr(bitmap, "hin");
      assertNotNull("OCR result should not be null", ocrResult);

      // Check that at least some Devanagari consonants are recognized
      int consonantsFound = 0;
      char[] consonants = {
        '\u0915', '\u0916', '\u0917', '\u0918', // क ख ग घ
        '\u091a', '\u091b', '\u091c', // च छ ज
        '\u091f', '\u0920', '\u0921', // ट ठ ड
        '\u0924', '\u0925', '\u0926', '\u0927', '\u0928', // त थ द ध न
        '\u092a', '\u092b', '\u092c', '\u092d', '\u092e', // प फ ब भ म
        '\u092f', '\u0930', '\u0932', '\u0935', '\u0936', // य र ल व श
        '\u0937', '\u0938', '\u0939', // ष स ह
      };

      for (char c : consonants) {
        if (ocrResult.indexOf(c) >= 0) {
          consonantsFound++;
        }
      }

      System.out.println(
          "[DEBUG_LOG] Devanagari consonants found: " + consonantsFound + "/" + consonants.length);

      assertTrue(
          "OCR should recognize at least 10 Devanagari consonants, found: " + consonantsFound,
          consonantsFound >= 10);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes mixed Hindi and English text. */
  @Test
  public void testOcrRecognizesMixedHindiEnglish() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(200);
    try {
      String ocrResult = performOcr(bitmap, "hin+eng");
      assertNotNull("OCR result should not be null", ocrResult);

      System.out.println("[DEBUG_LOG] Hindi+Eng OCR result:\n" + ocrResult);

      // The document contains "Make a Copy", "Android", "OCR", "Tesseract"
      boolean hasEnglish =
          ocrResult.contains("Make")
              || ocrResult.contains("Copy")
              || ocrResult.contains("Android")
              || ocrResult.contains("OCR")
              || ocrResult.contains("Tesseract");

      // And Hindi text
      boolean hasHindi =
          ocrResult.contains("\u092d\u093e\u0930\u0924") // भारत
              || ocrResult.contains("\u0939\u0948") // है
              || ocrResult.contains("\u0939\u093f\u0902\u0926\u0940"); // हिंदी

      assertTrue("OCR should recognize English words in mixed text", hasEnglish);
      assertTrue("OCR should recognize Hindi words in mixed text", hasHindi);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR with Hindi-only mode still produces meaningful output. */
  @Test
  public void testOcrWithHindiOnlyMode() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(200);
    try {
      String ocrResult = performOcr(bitmap, "hin");
      assertNotNull("OCR result should not be null", ocrResult);

      // Even in Hindi-only mode, Devanagari text should be recognized
      boolean hasDevanagari = false;
      for (int i = 0; i < ocrResult.length(); i++) {
        char c = ocrResult.charAt(i);
        if (c >= '\u0900' && c <= '\u097F') {
          hasDevanagari = true;
          break;
        }
      }

      assertTrue("OCR in Hindi-only mode should produce Devanagari characters", hasDevanagari);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes place names like दिल्ली (Delhi) and आगरा (Agra). */
  @Test
  public void testOcrRecognizesPlaceNames() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(200);
    try {
      String ocrResult = performOcr(bitmap, "hin");
      assertNotNull("OCR result should not be null", ocrResult);

      // Check for place names in the document
      boolean found =
          ocrResult.contains("\u0926\u093f\u0932\u094d\u0932\u0940") // दिल्ली
              || ocrResult.contains("\u0906\u0917\u0930\u093e") // आगरा
              || ocrResult.contains("\u0917\u0902\u0917\u093e"); // गंगा

      assertTrue(
          "OCR should recognize at least one place name "
              + "(\u0926\u093f\u0932\u094d\u0932\u0940/\u0906\u0917\u0930\u093e/\u0917\u0902\u0917\u093e)",
          found);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests that OCR recognizes Arabic numerals from the document. */
  @Test
  public void testOcrRecognizesArabicNumerals() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(200);
    try {
      String ocrResult = performOcr(bitmap, "hin");
      assertNotNull("OCR result should not be null", ocrResult);

      // The document contains "1 2 3 4 5 6 7 8 9 0"
      int digitsFound = 0;
      for (char d = '0'; d <= '9'; d++) {
        if (ocrResult.indexOf(d) >= 0) {
          digitsFound++;
        }
      }

      assertTrue(
          "OCR should recognize at least 5 Arabic digits, found: " + digitsFound, digitsFound >= 5);
    } finally {
      bitmap.recycle();
    }
  }

  /** Tests overall OCR quality by checking minimum recognized text length. */
  @Test
  public void testOcrOverallQuality() throws Exception {
    Bitmap bitmap = renderPdfFirstPage(200);
    try {
      String ocrResult = performOcr(bitmap, "hin");
      assertNotNull("OCR result should not be null", ocrResult);

      String trimmed = ocrResult.trim();
      System.out.println("[DEBUG_LOG] Hindi OCR total chars: " + trimmed.length());

      // The document has substantial text; OCR should produce at least 100 characters
      assertTrue(
          "OCR should produce at least 100 characters of text, got: " + trimmed.length(),
          trimmed.length() >= 100);

      // Count Devanagari characters
      int devanagariCount = 0;
      for (int i = 0; i < trimmed.length(); i++) {
        char c = trimmed.charAt(i);
        if (c >= '\u0900' && c <= '\u097F') {
          devanagariCount++;
        }
      }

      System.out.println("[DEBUG_LOG] Devanagari characters: " + devanagariCount);

      // At least 20% of recognized text should be Devanagari
      double ratio = (double) devanagariCount / trimmed.length();
      assertTrue(
          "At least 20% of OCR output should be Devanagari characters, got: "
              + String.format("%.1f%%", ratio * 100),
          ratio >= 0.20);
    } finally {
      bitmap.recycle();
    }
  }
}
