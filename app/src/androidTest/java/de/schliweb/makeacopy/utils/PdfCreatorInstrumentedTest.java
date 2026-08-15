package de.schliweb.makeacopy.utils;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.PDFTextStripperByArea;
import de.schliweb.makeacopy.utils.export.PageFormat;
import de.schliweb.makeacopy.utils.export.PdfCreator;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * PdfCreatorInstrumentedTest is a test class for verifying PDF creation and text layer
 * functionality using the PdfCreator utility in an Android application.
 *
 * <p>The tests ensure that generated PDFs are created properly, contain correct text properties,
 * and align appropriately with OCR (Optical Character Recognition) data. It uses Android-specific
 * testing libraries and is designed to be executed in an instrumented test environment.
 *
 * <p>This test class includes the following main tests: - Verifying that all test PDFs produce a
 * searchable text layer using predefined test files. - Testing PDF geometry and embedded font
 * properties in the created PDFs. - Ensuring correct alignment and overlay of OCR text on the
 * generated PDFs.
 *
 * <p>The setup method performs one-time initialization, such as setting up the application context
 * and initializing PDFBox resources.
 */
@RunWith(AndroidJUnit4.class)
public class PdfCreatorInstrumentedTest {

  private static Context context;

  @BeforeClass
  public static void setupOnce() {
    context = ApplicationProvider.getApplicationContext();
    PDFBoxResourceLoader.init(context);
  }

  /**
   * Tests that a set of sample PDF files can be processed to produce output PDFs containing
   * searchable text layers.
   *
   * <p>The test performs the following steps for each PDF file: 1. Renders the first page of the
   * PDF to a bitmap image. 2. Verifies the bitmap is successfully created and not null. 3.
   * Simulates text recognition by creating dummy OCR results. 4. Generates a PDF with a searchable
   * text layer using the rendered bitmap and dummy OCR data. 5. Validates the output PDF file is
   * successfully created and non-empty. 6. Extracts the text from the output PDF and verifies the
   * presence of specific tokens.
   *
   * <p>Assertions are used to ensure: - The PDF page is rendered successfully. - The output file is
   * created and has a size greater than a minimum threshold. - The extracted text contains expected
   * keywords ("Alpha" and "Beta").
   *
   * @throws Exception If an error occurs during any step of the process, such as rendering, PDF
   *     generation, or text extraction.
   */
  @Test
  public void test_All_TestPdfs_Produce_Searchable_TextLayer() throws Exception {
    String base = "instrumented_test_data/";
    String[] testFiles = {
      "simple_line.pdf",
      "vertical_scan.pdf",
      "multi_line.pdf",
      "multi_column.pdf",
      "special_chars.pdf",
      "stress_test.pdf",
      "bilingual.pdf",
      "narrow_spacing.pdf",
      "two_column_rotated.pdf"
    };

    for (String name : testFiles) {
      String assetPath = base + name;

      Bitmap pageBmp = PdfTestUtils.renderPdfAssetToBitmap(context, assetPath, 0);
      assertNotNull("Render failed for: " + name, pageBmp);

      try {
        List<RecognizedWord> words = makeDummyWords(pageBmp.getWidth(), pageBmp.getHeight());

        File outDir = new File(context.getExternalFilesDir(null), "test_out");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        File outFile = new File(outDir, "out_" + name.replace(".pdf", "") + ".pdf");
        Uri outUri = Uri.fromFile(outFile);

        Uri result =
            PdfCreator.createSearchablePdf(
                context, pageBmp, words, outUri, 90 /*jpegQuality*/, true /*toGray*/);
        assertNotNull("PdfCreator returned null for: " + name, result);
        assertTrue(
            "Output file not created for: " + name, outFile.exists() && outFile.length() > 0);
        assertTrue("Output PDF seems empty: " + name, outFile.length() > 1024);

        try (InputStream in = context.getContentResolver().openInputStream(result);
            PDDocument doc = PDDocument.load(in)) {
          String txt = new PDFTextStripper().getText(doc);
          assertNotNull("Extracted text null for: " + name, txt);
          assertTrue("Missing token 'Alpha' in: " + name, txt.contains("Alpha"));
          assertTrue("Missing token 'Beta' in: " + name, txt.contains("Beta"));
        }
      } finally {
        if (pageBmp != null && !pageBmp.isRecycled()) pageBmp.recycle();
      }
    }
  }

  /**
   * Creates a list of dummy recognized words with predefined attributes, including their text,
   * bounding boxes, and confidence levels. Used for simulating OCR results.
   *
   * @param w The width of the target area or image.
   * @param h The height of the target area or image.
   * @return A list of RecognizedWord objects representing dummy OCR results.
   */
  private static List<RecognizedWord> makeDummyWords(int w, int h) {
    float x1 = w * 0.12f, x2 = w * 0.22f;
    float yTop = h * 0.20f;
    float yBottom = yTop + Math.max(24f, h * 0.02f);

    List<RecognizedWord> list = new ArrayList<>();
    list.add(new RecognizedWord("Alpha", new RectF(x1, yTop, x1 + w * 0.08f, yBottom), 0.99f));
    list.add(new RecognizedWord("Beta", new RectF(x2, yTop, x2 + w * 0.06f, yBottom), 0.99f));
    return list;
  }

  /**
   * Tests the generation of a searchable PDF with correct geometry and embedded fonts.
   *
   * <p>This method performs the following steps: 1. Creates a blank bitmap with specified width and
   * height. 2. Simulates recognized text by initializing a list of dummy OCR results with specific
   * attributes such as text, bounding box, and confidence. 3. Generates a searchable PDF using the
   * bitmap and OCR data, and writes the output to a file. 4. Validates the PDF generation process
   * by ensuring that: - The output file is created successfully and is non-empty. - The PDF
   * contains the expected number of pages. - MediaBox and CropBox dimensions of the PDF page are
   * consistent. - At least one embedded font is included in the generated PDF.
   *
   * <p>Assertions are made to ensure the correctness of each validation step.
   *
   * @throws Exception if any step in PDF creation, validation, or resource handling fails.
   */
  @Test
  public void testPdfGeometryAndFonts() throws Exception {
    Context ctx = ApplicationProvider.getApplicationContext();

    int imgW = 800, imgH = 1200;
    Bitmap bmp = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888);
    bmp.eraseColor(Color.WHITE);

    try {
      List<RecognizedWord> words = new ArrayList<>();
      words.add(new RecognizedWord("Hello", new RectF(100, 200, 200, 240), 0.99f));

      File out = new File(ctx.getCacheDir(), "test_geom_fonts.pdf");
      Uri uri = Uri.fromFile(out);

      Uri res = PdfCreator.createSearchablePdf(ctx, bmp, words, uri, 90, false);
      assertNotNull("PDF creation failed", res);
      assertTrue(out.exists() && out.length() > 0);

      try (PDDocument doc = PDDocument.load(out)) {
        assertEquals(1, doc.getNumberOfPages());
        PDPage page = doc.getPage(0);

        PDRectangle media = page.getMediaBox();
        PDRectangle crop = page.getCropBox();
        assertEquals("Rotate must be 0", 0, page.getRotation());

        assertEquals(media.getWidth(), crop.getWidth(), 0.01);
        assertEquals(media.getHeight(), crop.getHeight(), 0.01);
        assertEquals(media.getLowerLeftX(), crop.getLowerLeftX(), 0.01);
        assertEquals(media.getLowerLeftY(), crop.getLowerLeftY(), 0.01);

        boolean hasEmbedded = false;
        for (COSName fontName : page.getResources().getFontNames()) {
          try {
            PDFont f = page.getResources().getFont(fontName);
            if (f != null && f.isEmbedded()) {
              hasEmbedded = true;
              break;
            }
          } catch (Exception ignore) {
          }
        }
        assertTrue("Expected at least one embedded font", hasEmbedded);
      }
    } finally {
      if (bmp != null && !bmp.isRecycled()) bmp.recycle();
    }
  }

  @Test
  public void searchablePdfExtractsLineWithSingleSpaces() throws Exception {
    Context ctx = ApplicationProvider.getApplicationContext();

    int imgW = 2200, imgH = 400;
    Bitmap bmp = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888);
    bmp.eraseColor(Color.WHITE);

    try {
      List<RecognizedWord> words = new ArrayList<>();
      words.add(new RecognizedWord("In", new RectF(100, 100, 150, 145), 0.99f));
      words.add(new RecognizedWord("a", new RectF(260, 100, 285, 145), 0.99f));
      words.add(new RecognizedWord("bid", new RectF(390, 100, 455, 145), 0.99f));
      words.add(new RecognizedWord("to", new RectF(620, 100, 660, 145), 0.99f));
      words.add(new RecognizedWord("diversify", new RectF(830, 100, 1010, 145), 0.99f));

      File out = new File(ctx.getCacheDir(), "test_single_spaces_text_layer.pdf");
      Uri uri = Uri.fromFile(out);

      Uri res = PdfCreator.createSearchablePdf(ctx, bmp, words, uri, 90, false);
      assertNotNull("PDF creation failed", res);

      try (PDDocument doc = PDDocument.load(out)) {
        String txt = new PDFTextStripper().getText(doc).trim();
        assertEquals("In a bid to diversify", txt);
      }
    } finally {
      bmp.recycle();
    }
  }

  /**
   * Tests the alignment and overlay correctness of OCR text regions within a generated searchable
   * PDF.
   *
   * <p>This method validates the following functionalities: 1. Creation of a blank bitmap image to
   * simulate an OCR processing environment. 2. Definition of text regions with associated bounding
   * boxes and confidence scores, representing OCR results. 3. Generation of a searchable PDF with
   * the bitmap and OCR data, verifying that the process succeeds. 4. Extraction of specific OCR
   * regions from the PDF using PDFTextStripperByArea in both top-left and bottom-left coordinate
   * systems. 5. Validation that the extracted text matches the expected OCR input ("Hello" and
   * "World"), ensuring correct alignment and positioning.
   *
   * <p>Assertions: - Ensures the PDF is successfully created with the expected searchable content.
   * - Verifies that the text regions for the specified coordinates contain the correct OCR text,
   * accounting for alignment discrepancies.
   *
   * <p>The test includes padding adjustments to region boundaries to account for minor OCR
   * misalignments.
   *
   * @throws Exception if an error occurs during bitmap manipulation, PDF generation, region
   *     extraction, or validation.
   */
  @Test
  public void testOcrTextOverlayAlignment() throws Exception {
    Context ctx = ApplicationProvider.getApplicationContext();

    int imgW = 800, imgH = 1200;
    Bitmap bmp = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888);
    bmp.eraseColor(Color.WHITE);

    try {
      RectF helloBox = new RectF(100, 200, 220, 240);
      RectF worldBox = new RectF(250, 200, 370, 240);

      List<RecognizedWord> words = new ArrayList<>();
      words.add(new RecognizedWord("Hello", helloBox, 0.99f));
      words.add(new RecognizedWord("World", worldBox, 0.99f));

      File out = new File(ctx.getCacheDir(), "test_ocr_align.pdf");
      Uri uri = Uri.fromFile(out);

      // Exact per-word overlay alignment is only guaranteed by WORD_POSITIONED mode.
      // The default LINE_BASED mode writes each line as a single horizontally scaled
      // text run, so inner word boundaries are only approximately aligned.
      Uri res =
          PdfCreator.createSearchablePdf(
              ctx,
              bmp,
              words,
              uri,
              90,
              false,
              false,
              300,
              null,
              PageFormat.A4,
              DocumentCleanupMode.ORIGINAL,
              PdfCreator.TextLayerMode.WORD_POSITIONED);
      assertNotNull("PDF creation failed", res);

      try (PDDocument doc = PDDocument.load(out)) {
        PDPage page = doc.getPage(0);
        PDRectangle pageSize = page.getMediaBox();
        float pageH = pageSize.getHeight();

        float scale = Math.min(pageSize.getWidth() / imgW, pageSize.getHeight() / imgH);
        float drawW = imgW * scale;
        float drawH = imgH * scale;
        float offX = (pageSize.getWidth() - drawW) / 2f;
        float offY = (pageSize.getHeight() - drawH) / 2f;

        RectF helloTL = imageRectToAreaRectTL(helloBox, imgH, scale, offX, offY, pageH);
        RectF worldTL = imageRectToAreaRectTL(worldBox, imgH, scale, offX, offY, pageH);

        RectF helloBL = imageRectToAreaRectBL(helloBox, imgH, scale, offX, offY);
        RectF worldBL = imageRectToAreaRectBL(worldBox, imgH, scale, offX, offY);

        helloTL = expand(helloTL, 8f);
        worldTL = expand(worldTL, 8f);
        helloBL = expand(helloBL, 8f);
        worldBL = expand(worldBL, 8f);

        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
        stripper.setSortByPosition(true);

        stripper.addRegion("helloTL", helloTL);
        stripper.addRegion("worldTL", worldTL);
        stripper.addRegion("helloBL", helloBL);
        stripper.addRegion("worldBL", worldBL);

        stripper.extractRegions(page);

        String tHelloTL = stripper.getTextForRegion("helloTL");
        String tWorldTL = stripper.getTextForRegion("worldTL");
        String tHelloBL = stripper.getTextForRegion("helloBL");
        String tWorldBL = stripper.getTextForRegion("worldBL");

        boolean helloOk =
            (tHelloTL != null && tHelloTL.contains("Hello"))
                || (tHelloBL != null && tHelloBL.contains("Hello"));
        boolean worldOk =
            (tWorldTL != null && tWorldTL.contains("World"))
                || (tWorldBL != null && tWorldBL.contains("World"));

        assertTrue("Region 'Hello' not found in either coord system", helloOk);
        assertTrue("Region 'World' not found in either coord system", worldOk);
      }
    } finally {
      if (bmp != null && !bmp.isRecycled()) bmp.recycle();
    }
  }

  /**
   * Converts an image rectangle (defined in the image space) into a rectangle in the document's
   * page coordinate system (using top-left as the origin). The method applies scaling, offsets, and
   * coordinate transformation to achieve the conversion.
   *
   * @param rImg The rectangle in image coordinates to be transformed.
   * @param imageHeight The height of the image from which the rectangle originates.
   * @param scale The scaling factor to apply during the transformation.
   * @param offX The x-axis offset to apply to the rectangle's position in the page coordinate
   *     system.
   * @param offY The y-axis offset to apply to the rectangle's position in the page coordinate
   *     system.
   * @param pageHeight The height of the document page, used to convert between bottom-left and
   *     top-left coordinate systems.
   * @return A RectF object representing the transformed rectangle in the document's page coordinate
   *     system with a top-left origin.
   */
  private static RectF imageRectToAreaRectTL(
      RectF rImg, int imageHeight, float scale, float offX, float offY, float pageHeight) {
    float xLeft = offX + rImg.left * scale;
    float yTopBL = offY + (imageHeight - rImg.top) * scale; // in BL
    float yBotBL = offY + (imageHeight - rImg.bottom) * scale; // in BL
    float width = rImg.width() * scale;
    float height = Math.abs(yTopBL - yBotBL);
    float yTopTL = pageHeight - yTopBL; // BL → TL
    return new RectF(xLeft, yTopTL, xLeft + width, yTopTL + height);
  }

  /**
   * Transforms a rectangle defined in an image's coordinate space into a rectangle in a bottom-left
   * origin coordinate system. The method applies scaling, offsets, and coordinate transformation
   * during the conversion.
   *
   * @param rImg The input rectangle defined in the image coordinate space.
   * @param imageHeight The height of the image from which the rectangle originates.
   * @param scale The scaling factor to apply to the rectangle's dimensions and position.
   * @param offX The x-axis offset to apply to the rectangle's transformed position.
   * @param offY The y-axis offset to apply to the rectangle's transformed position.
   * @return A RectF object representing the transformed rectangle in the bottom-left coordinate
   *     system.
   */
  private static RectF imageRectToAreaRectBL(
      RectF rImg, int imageHeight, float scale, float offX, float offY) {
    float xLeft = offX + rImg.left * scale;
    float yBL = offY + (imageHeight - rImg.bottom) * scale; // lower-left y
    float width = rImg.width() * scale;
    float height = rImg.height() * scale;
    return new RectF(xLeft, yBL, xLeft + width, yBL + height);
  }

  private static RectF expand(RectF r, float pad) {
    return new RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad);
  }
}
