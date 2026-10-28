package de.schliweb.makeacopy.utils;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.testutil.PdfBoxTesting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import de.schliweb.makeacopy.utils.export.PdfCreator;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Integration test class for verifying the correct functionality of the PdfCreator. This class
 * ensures that PDFs created with text layers and OCR functionality behave as expected, particularly
 * in terms of text recognition and layout preservation.
 *
 * <p>The tests validate: - The successful creation of a PDF with OCR-driven text layers. - The
 * proper extraction and arrangement of text layers as cohesive phrases when processed with PDFBox.
 *
 * <p>Dependencies: - Android Instrumentation Test Framework via AndroidJUnit4. - PDFBox library for
 * processing and extracting text from PDFs. - PdfCreator for generating PDFs with searchable text
 * layers.
 *
 * <p>Test Workflow: - A dummy bitmap image is created as input for the PDF generation function. -
 * RecognizedWord objects are constructed to simulate OCR results. - PdfCreator generates a
 * searchable PDF using the provided bitmap and text layers. - PDFBox is used to extract and
 * validate the resulting text arrangement in the PDF. - Assertions verify that the text content is
 * accurately preserved and arranged.
 *
 * <p>Requirements: - The class uses Android's context to access resources during tests. - PDFBox
 * must be initialized through PDFBoxResourceLoader with an appropriate context.
 */
@RunWith(AndroidJUnit4.class)
public class PdfCreatorIntegrationTest {

  private static Context context;

  @BeforeClass
  public static void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    PdfBoxTesting.init();
  }

  /**
   * Tests the functionality of creating a searchable PDF from an image and a list of recognized
   * words, and verifies that the extracted text contains the expected words as a full phrase.
   *
   * <p>The test performs the following steps: 1. Creates a dummy bitmap image. 2. Creates a list of
   * recognized words, placing them in specific positions within the image with predefined
   * confidence values. 3. Creates a PDF file using the `PdfCreator.createSearchablePdf` method and
   * verifies that the file was successfully created. 4. Extracts text from the resulting PDF using
   * `PDFBox` to ensure the output text contains the recognized words as a coherent phrase and
   * matches the defined expectations for proximity and order.
   *
   * <p>Assertions: - Validates the existence of the created PDF file. - Confirms that the extracted
   * text contains the expected words. - Ensures that the words appear sequentially and cohesively
   * as a phrase within the extracted text ("Alpha Beta").
   *
   * @throws Exception if any operation such as PDF creation, text extraction, or file IO encounters
   *     an issue.
   */
  @Test
  public void testTextLayerExtractsAsFullPhrase() throws Exception {
    // Dummy Bitmap
    Bitmap bmp = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888);
    bmp.eraseColor(Color.WHITE);

    List<RecognizedWord> words = new ArrayList<>();
    float yTop = 200f, yBottom = 240f;
    words.add(new RecognizedWord("Alpha", new RectF(100, yTop, 200, yBottom), 99f));
    words.add(new RecognizedWord("Beta", new RectF(210, yTop, 300, yBottom), 99f));

    // Output file
    File outFile = new File(context.getCacheDir(), "ocr_test.pdf");
    if (outFile.exists()) outFile.delete();

    // Create PDF
    PdfCreator.createSearchablePdf(
        context, bmp, words, android.net.Uri.fromFile(outFile), 80, true);

    assertTrue("PDF not created", outFile.exists());

    // Extract text with PDFBox
    try (FileInputStream fis = new FileInputStream(outFile);
        PDDocument doc = PDDocument.load(fis)) {

      PDFTextStripper stripper = new PDFTextStripper();
      String text = stripper.getText(doc).trim();

      System.out.println("Extracted text: >>>" + text + "<<<");

      assertTrue("Should contain Alpha", text.contains("Alpha"));
      assertTrue("Should contain Beta", text.contains("Beta"));
      assertTrue("Words should appear together", text.matches("(?s).*Alpha\\s+Beta.*"));
      String norm = text.replaceAll("[\\s\\u00A0]+", " ").trim();
      assertTrue("Words should appear together", norm.contains("Alpha Beta"));
    }
  }

  /**
   * Tests that words on the same visual line but with slightly varying Y positions are correctly
   * ordered in the PDF text layer.
   *
   * <p>This test reproduces the issue where text selection in PDFs was shuffled because words with
   * small Y-coordinate differences were incorrectly grouped into different "lines" during PDF
   * creation.
   *
   * <p>The test creates words that simulate a typical OCR result where words on the same line may
   * have slight vertical variations (e.g., due to font baseline differences, subscripts, or OCR
   * inaccuracies).
   *
   * @throws Exception if any operation encounters an issue.
   */
  @Test
  public void testTextLayerWithVaryingYPositionsOnSameLine() throws Exception {
    // Dummy Bitmap
    Bitmap bmp = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888);
    bmp.eraseColor(Color.WHITE);

    // Simulate words on the same visual line with slight Y variations
    // Word heights are ~40px, but Y positions vary by up to 15px
    List<RecognizedWord> words = new ArrayList<>();

    // First line: "In a bid to diversify"
    // Words have slight Y variations (within typical line height tolerance)
    words.add(new RecognizedWord("In", new RectF(50, 100, 80, 140), 95f));
    words.add(new RecognizedWord("a", new RectF(90, 105, 110, 145), 95f)); // +5px Y offset
    words.add(new RecognizedWord("bid", new RectF(120, 98, 170, 138), 95f)); // -2px Y offset
    words.add(new RecognizedWord("to", new RectF(180, 103, 210, 143), 95f)); // +3px Y offset
    words.add(new RecognizedWord("diversify", new RectF(220, 100, 320, 140), 95f));

    // Second line: "its economy away"
    // Clearly on a different line (Y ~200)
    words.add(new RecognizedWord("its", new RectF(50, 200, 90, 240), 95f));
    words.add(new RecognizedWord("economy", new RectF(100, 205, 200, 245), 95f)); // +5px Y offset
    words.add(new RecognizedWord("away", new RectF(210, 198, 280, 238), 95f)); // -2px Y offset

    // Output file
    File outFile = new File(context.getCacheDir(), "ocr_varying_y_test.pdf");
    if (outFile.exists()) outFile.delete();

    // Create PDF
    PdfCreator.createSearchablePdf(
        context, bmp, words, android.net.Uri.fromFile(outFile), 80, true);

    assertTrue("PDF not created", outFile.exists());

    // Extract text with PDFBox
    try (FileInputStream fis = new FileInputStream(outFile);
        PDDocument doc = PDDocument.load(fis)) {

      PDFTextStripper stripper = new PDFTextStripper();
      String text = stripper.getText(doc).trim();

      System.out.println("[DEBUG_LOG] Extracted text: >>>" + text + "<<<");

      // Normalize whitespace for comparison
      String norm = text.replaceAll("[\\s\\u00A0]+", " ").trim();
      System.out.println("[DEBUG_LOG] Normalized text: >>>" + norm + "<<<");

      // Verify first line words appear in correct order
      assertTrue(
          "First line should contain 'In a bid to diversify' in order",
          norm.contains("In a bid to diversify"));

      // Verify second line words appear in correct order
      assertTrue(
          "Second line should contain 'its economy away' in order",
          norm.contains("its economy away"));

      // Verify the lines appear in correct order (first line before second line)
      int firstLinePos = norm.indexOf("In a bid");
      int secondLinePos = norm.indexOf("its economy");
      assertTrue("First line should appear before second line", firstLinePos < secondLinePos);
    }
  }
}
