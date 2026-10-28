package de.schliweb.makeacopy.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import de.schliweb.makeacopy.utils.export.PdfCreator;
import de.schliweb.makeacopy.utils.export.PdfCreator.PdfImageOutput;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import de.schliweb.makeacopy.utils.image.DocumentCleanupOptions;
import org.junit.Test;

/** Unit tests for how {@link PdfCreator} picks the image output and cleanup options. */
public class PdfCreatorTest {

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
