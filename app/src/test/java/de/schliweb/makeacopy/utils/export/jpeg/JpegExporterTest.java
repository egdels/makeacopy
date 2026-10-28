package de.schliweb.makeacopy.utils.export.jpeg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import de.schliweb.makeacopy.utils.image.DocumentCleanupOptions;
import org.junit.Test;

public class JpegExporterTest {

  @Test
  public void resolveJpegImageOutput_blackWhiteDominatesGrayscale() {
    JpegExportOptions options = new JpegExportOptions();
    options.mode = JpegExportOptions.Mode.BW_TEXT;
    options.forceGrayscaleJpeg = true;

    assertEquals(JpegExporter.JpegImageOutput.BLACK_WHITE, JpegExporter.resolveJpegImageOutput(options));
  }

  @Test
  public void resolveJpegImageOutput_grayscaleWhenForced() {
    JpegExportOptions options = new JpegExportOptions();
    options.forceGrayscaleJpeg = true;

    assertEquals(JpegExporter.JpegImageOutput.GRAYSCALE, JpegExporter.resolveJpegImageOutput(options));
  }

  @Test
  public void resolveJpegImageOutput_colorByDefault() {
    assertEquals(
        JpegExporter.JpegImageOutput.COLOR,
        JpegExporter.resolveJpegImageOutput(new JpegExportOptions()));
  }

  @Test
  public void blackWhiteOutputUsesNonColorCleanupOptionsForAllCleanupModes() {
    for (DocumentCleanupMode mode : DocumentCleanupMode.values()) {
      DocumentCleanupOptions options = JpegExporter.buildCleanupOptions(mode, false);

      assertEquals(mode, options.mode);
      assertFalse(options.preserveColor);
    }
  }
}
