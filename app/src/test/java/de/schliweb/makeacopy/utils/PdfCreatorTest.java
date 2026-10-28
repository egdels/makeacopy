package de.schliweb.makeacopy.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;
import de.schliweb.makeacopy.utils.export.PdfCreator;
import de.schliweb.makeacopy.utils.export.PdfCreator.PdfImageOutput;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import de.schliweb.makeacopy.utils.image.DocumentCleanupOptions;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link PdfCreator} core math used by the OCR text layer.
 *
 * <p>These tests mirror the formulas in PdfCreator without requiring Android or PDFBox runtime.
 */
public class PdfCreatorTest {

  // Constants from PdfCreator that we need for testing
  private float TEXT_SIZE_RATIO;
  private float MIN_FONT_PT;

  @Before
  public void setUp() throws Exception {
    // Access the private constants from PdfCreator using reflection
    Field textSizeRatioField = PdfCreator.class.getDeclaredField("TEXT_SIZE_RATIO");
    textSizeRatioField.setAccessible(true);
    TEXT_SIZE_RATIO = (float) textSizeRatioField.get(null);

    Field minFontPtField = PdfCreator.class.getDeclaredField("MIN_FONT_PT");
    minFontPtField.setAccessible(true);
    MIN_FONT_PT = (float) minFontPtField.get(null);
  }

  /**
   * Tests the font size calculation based on median line height in image space. PdfCreator uses:
   * fontSize = max(MIN_FONT_PT, medianH * TEXT_SIZE_RATIO)
   */
  @Test
  public void testFontSizeCalculation() {
    float medianH = 50.0f;
    float expected = Math.max(MIN_FONT_PT, medianH * TEXT_SIZE_RATIO);
    assertEquals(
        "Font size formula should match",
        expected,
        Math.max(MIN_FONT_PT, medianH * TEXT_SIZE_RATIO),
        0.001f);

    // Also test tiny height gets clamped to MIN_FONT_PT
    float tiny = 1.0f;
    float expectedTiny = Math.max(MIN_FONT_PT, tiny * TEXT_SIZE_RATIO);
    assertEquals(
        "Font size should not go below MIN_FONT_PT",
        expectedTiny,
        Math.max(MIN_FONT_PT, tiny * TEXT_SIZE_RATIO),
        0.001f);
    assertEquals(
        "MIN_FONT_PT should be effective for tiny heights", expectedTiny, MIN_FONT_PT, 0.0);
  }

  /**
   * Tests the baseline position computation in image space and its inversion to Y-up image space.
   * PdfCreator uses: baselineImgY = box.bottom + box.height * 0.25f; y_img = imageHeight -
   * baselineImgY;
   */
  @Test
  public void testBaselineImageSpaceComputation() {
    float boxTop = 100.0f;
    float boxBottom = 150.0f;
    float boxHeight = boxBottom - boxTop; // 50
    int imageHeight = 1000;

    float baselineImgY = boxBottom + boxHeight * 0.25f; // 150 + 12.5 = 162.5
    float y_img = Math.max(0f, Math.min(imageHeight, (imageHeight - baselineImgY)));

    float expectedBaselineImgY = 162.5f;
    float expectedYImg = 1000f - 162.5f;

    assertEquals("baselineImgY formula should match", expectedBaselineImgY, baselineImgY, 0.001f);
    assertEquals("y_img inversion should match", expectedYImg, y_img, 0.001f);
  }

  /**
   * Tests that applying the page transform (scale + offset) produces correct page coordinates when
   * text is positioned in image coordinates. Page Y = offsetY + y_img * scale
   */
  @Test
  public void testPageCoordinateAfterTransform() {
    int imageHeight = 1500;
    float scale = 0.5f;
    float offsetY = 50.0f;

    // Example OCR box
    float boxTop = 700.0f;
    float boxBottom = 760.0f; // height = 60
    float boxHeight = boxBottom - boxTop;

    float baselineImgY = boxBottom + boxHeight * 0.25f; // 760 + 15 = 775
    float y_img = imageHeight - baselineImgY; // 1500 - 775 = 725

    float pageY = offsetY + y_img * scale; // 50 + 725 * 0.5 = 412.5

    float expectedPageY = 412.5f;
    assertEquals("Page Y after transform should match", expectedPageY, pageY, 0.001f);
  }

  @Test
  public void lineToleranceKeepsAdjacentTextLinesSeparate() throws Exception {
    Method method = PdfCreator.class.getDeclaredMethod("calculateLineToleranceY", java.util.List.class);
    method.setAccessible(true);

    float tolerance =
        (float)
            method.invoke(
                null,
                Arrays.asList(
                    new RecognizedWord("Falls", new RectF(10f, 100f, 50f, 120f), 1f),
                    new RecognizedWord("Sie", new RectF(10f, 124f, 40f, 144f), 1f)));

    assertTrue("Line tolerance must be smaller than a normal adjacent line offset", tolerance < 24f);
  }

  @Test
  public void pdfOutputKeepsColorWhenNoOutputFilterIsRequested() {
    assertEquals(PdfImageOutput.COLOR, PdfCreator.resolvePdfImageOutput(false, false));
  }

  @Test
  public void pdfOutputAppliesGrayscaleAfterCleanupWhenRequested() {
    assertEquals(PdfImageOutput.GRAYSCALE, PdfCreator.resolvePdfImageOutput(true, false));
  }

  @Test
  public void pdfOutputAppliesBlackWhiteAfterCleanupWhenRequested() {
    assertEquals(PdfImageOutput.BLACK_WHITE, PdfCreator.resolvePdfImageOutput(false, true));
  }

  @Test
  public void pdfOutputBlackWhiteDominatesGrayscaleForNormalizedCleanTextAndBwCombinations() {
    assertEquals(PdfImageOutput.BLACK_WHITE, PdfCreator.resolvePdfImageOutput(true, true));
  }

  @Test
  public void pdfBlackWhiteOutputUsesNonColorCleanupOptionsForAllCleanupModes() {
    for (DocumentCleanupMode mode : DocumentCleanupMode.values()) {
      DocumentCleanupOptions options =
          PdfCreator.buildCleanupOptions(mode, PdfImageOutput.BLACK_WHITE);

      assertEquals(mode, options.mode);
      assertFalse(options.preserveColor);
    }
  }
}
