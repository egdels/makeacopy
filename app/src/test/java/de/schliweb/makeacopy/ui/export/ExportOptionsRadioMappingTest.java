package de.schliweb.makeacopy.ui.export;

import static org.junit.Assert.*;

import android.view.View;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.utils.export.PageFormat;
import de.schliweb.makeacopy.utils.export.PdfQualityPreset;
import de.schliweb.makeacopy.utils.export.jpeg.JpegExportOptions;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import org.junit.Test;

/** JVM-only tests for the saved option ↔ radio button mappings of the export options dialog. */
public class ExportOptionsRadioMappingTest {

  @Test
  public void cleanupMode_roundTripsForEveryMode() {
    for (DocumentCleanupMode mode : DocumentCleanupMode.values()) {
      assertEquals(
          mode,
          ExportOptionsPanel.cleanupModeFor(
              ExportOptionsPanel.cleanupRadioId(mode)));
    }
    assertEquals(DocumentCleanupMode.ORIGINAL, ExportOptionsPanel.cleanupModeFor(-1));
  }

  @Test
  public void pageFormat_roundTripsForEveryFormatWithARadioButton() {
    for (PageFormat format : PageFormat.values()) {
      int radioId = ExportOptionsPanel.pageFormatRadioId(format);
      if (radioId == View.NO_ID) continue;
      assertEquals(format, ExportOptionsPanel.pageFormatFor(radioId));
    }
    assertEquals(R.id.dialog_radio_page_a4, ExportOptionsPanel.pageFormatRadioId(PageFormat.A4));
    assertEquals(PageFormat.FIT_TO_IMAGE, ExportOptionsPanel.pageFormatFor(-1));
  }

  @Test
  public void preset_roundTripsAndDefaultsToStandard() {
    for (PdfQualityPreset preset : PdfQualityPreset.values()) {
      assertEquals(
          preset,
          ExportOptionsPanel.presetFor(ExportOptionsPanel.presetRadioId(preset)));
    }
    assertEquals(PdfQualityPreset.STANDARD, ExportOptionsPanel.presetFor(-1));
  }

  @Test
  public void jpeg_grayscaleIsModeNonePlusFlag() {
    assertEquals(
        R.id.dialog_radio_jpeg_none,
        ExportOptionsPanel.jpegRadioId(JpegExportOptions.Mode.NONE, false));
    assertEquals(
        R.id.dialog_radio_jpeg_auto,
        ExportOptionsPanel.jpegRadioId(JpegExportOptions.Mode.NONE, true));
    // BW wins over a leftover grayscale flag
    assertEquals(
        R.id.dialog_radio_jpeg_bw_text,
        ExportOptionsPanel.jpegRadioId(JpegExportOptions.Mode.BW_TEXT, true));

    assertEquals(
        JpegExportOptions.Mode.NONE,
        ExportOptionsPanel.jpegModeFor(R.id.dialog_radio_jpeg_auto));
    assertEquals(
        JpegExportOptions.Mode.BW_TEXT,
        ExportOptionsPanel.jpegModeFor(R.id.dialog_radio_jpeg_bw_text));
  }

  @Test
  public void pdfBwMode_savedClassicIsShownAsRobust() {
    assertEquals(R.id.dialog_pdf_bw_none, ExportOptionsPanel.pdfBwRadioId(null));
    assertEquals(R.id.dialog_pdf_bw_none, ExportOptionsPanel.pdfBwRadioId("unknown"));
    assertEquals(R.id.dialog_pdf_grayscale, ExportOptionsPanel.pdfBwRadioId("grayscale"));
    assertEquals(R.id.dialog_pdf_bw_robust, ExportOptionsPanel.pdfBwRadioId("ROBUST"));
    assertEquals(R.id.dialog_pdf_bw_robust, ExportOptionsPanel.pdfBwRadioId("CLASSIC"));
  }

  @Test
  public void pdfBwMode_forRadio() {
    assertNull(ExportOptionsPanel.pdfBwModeFor(R.id.dialog_pdf_bw_none));
    assertNull(ExportOptionsPanel.pdfBwModeFor(-1));
    assertEquals("GRAYSCALE", ExportOptionsPanel.pdfBwModeFor(R.id.dialog_pdf_grayscale));
    assertEquals("ROBUST", ExportOptionsPanel.pdfBwModeFor(R.id.dialog_pdf_bw_robust));
  }
}
