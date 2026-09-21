package de.schliweb.makeacopy.ui.export;

import static org.junit.Assert.*;

import android.view.View;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.utils.export.PageFormat;
import de.schliweb.makeacopy.utils.export.PdfCreator;
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
          ExportOptionsDialogFragment.cleanupModeFor(
              ExportOptionsDialogFragment.cleanupRadioId(mode)));
    }
    assertEquals(DocumentCleanupMode.ORIGINAL, ExportOptionsDialogFragment.cleanupModeFor(-1));
  }

  @Test
  public void pageFormat_roundTripsForEveryFormatWithARadioButton() {
    for (PageFormat format : PageFormat.values()) {
      int radioId = ExportOptionsDialogFragment.pageFormatRadioId(format);
      if (radioId == View.NO_ID) continue;
      assertEquals(format, ExportOptionsDialogFragment.pageFormatFor(radioId));
    }
    assertEquals(R.id.dialog_radio_page_a4, ExportOptionsDialogFragment.pageFormatRadioId(PageFormat.A4));
    assertEquals(PageFormat.FIT_TO_IMAGE, ExportOptionsDialogFragment.pageFormatFor(-1));
  }

  @Test
  public void textLayerMode_roundTrips() {
    for (PdfCreator.TextLayerMode mode : PdfCreator.TextLayerMode.values()) {
      int radioId = ExportOptionsDialogFragment.textLayerRadioId(mode);
      PdfCreator.TextLayerMode expected =
          mode == PdfCreator.TextLayerMode.WORD_POSITIONED
              ? PdfCreator.TextLayerMode.WORD_POSITIONED
              : PdfCreator.TextLayerMode.LINE_BASED;
      assertEquals(expected, ExportOptionsDialogFragment.textLayerModeFor(radioId));
    }
  }

  @Test
  public void preset_roundTripsAndDefaultsToStandard() {
    for (PdfQualityPreset preset : PdfQualityPreset.values()) {
      assertEquals(
          preset,
          ExportOptionsDialogFragment.presetFor(ExportOptionsDialogFragment.presetRadioId(preset)));
    }
    assertEquals(PdfQualityPreset.STANDARD, ExportOptionsDialogFragment.presetFor(-1));
  }

  @Test
  public void jpeg_grayscaleIsModeNonePlusFlag() {
    assertEquals(
        R.id.dialog_radio_jpeg_none,
        ExportOptionsDialogFragment.jpegRadioId(JpegExportOptions.Mode.NONE, false));
    assertEquals(
        R.id.dialog_radio_jpeg_auto,
        ExportOptionsDialogFragment.jpegRadioId(JpegExportOptions.Mode.NONE, true));
    // BW wins over a leftover grayscale flag
    assertEquals(
        R.id.dialog_radio_jpeg_bw_text,
        ExportOptionsDialogFragment.jpegRadioId(JpegExportOptions.Mode.BW_TEXT, true));

    assertEquals(
        JpegExportOptions.Mode.NONE,
        ExportOptionsDialogFragment.jpegModeFor(R.id.dialog_radio_jpeg_auto));
    assertEquals(
        JpegExportOptions.Mode.BW_TEXT,
        ExportOptionsDialogFragment.jpegModeFor(R.id.dialog_radio_jpeg_bw_text));
  }

  @Test
  public void pdfBwMode_savedClassicIsShownAsRobust() {
    assertEquals(R.id.dialog_pdf_bw_none, ExportOptionsDialogFragment.pdfBwRadioId(null));
    assertEquals(R.id.dialog_pdf_bw_none, ExportOptionsDialogFragment.pdfBwRadioId("unknown"));
    assertEquals(R.id.dialog_pdf_grayscale, ExportOptionsDialogFragment.pdfBwRadioId("grayscale"));
    assertEquals(R.id.dialog_pdf_bw_robust, ExportOptionsDialogFragment.pdfBwRadioId("ROBUST"));
    assertEquals(R.id.dialog_pdf_bw_robust, ExportOptionsDialogFragment.pdfBwRadioId("CLASSIC"));
  }

  @Test
  public void pdfBwMode_forRadio() {
    assertNull(ExportOptionsDialogFragment.pdfBwModeFor(R.id.dialog_pdf_bw_none));
    assertNull(ExportOptionsDialogFragment.pdfBwModeFor(-1));
    assertEquals("GRAYSCALE", ExportOptionsDialogFragment.pdfBwModeFor(R.id.dialog_pdf_grayscale));
    assertEquals("ROBUST", ExportOptionsDialogFragment.pdfBwModeFor(R.id.dialog_pdf_bw_robust));
    assertEquals("CLASSIC", ExportOptionsDialogFragment.pdfBwModeFor(R.id.dialog_pdf_bw_classic));
  }
}
