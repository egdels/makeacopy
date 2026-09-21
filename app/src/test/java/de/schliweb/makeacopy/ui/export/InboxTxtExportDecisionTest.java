package de.schliweb.makeacopy.ui.export;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * JVM-only tests that validate the TXT export decision logic used in ExportFragment.
 *
 * <p>When Inbox Mode is active ({@code inboxExportInProgress == true}) and OCR text inclusion is
 * enabled ({@code includeOcr == true}), the TXT file must be exported directly to the Inbox
 * directory without showing a file picker. When Inbox Mode is not active, the normal file picker
 * flow must be used.
 *
 * <p>A TXT file is only exported for a real OCR result: without recognized text (e.g. "Skip OCR")
 * nothing is exported, regardless of the "Include TXT" option.
 *
 * <p>These tests exercise the decision seam without requiring an Android device.
 */
public class InboxTxtExportDecisionTest {

  private static final String OCR_TEXT = "Hello world";

  /**
   * Determines which TXT export action should be taken.
   *
   * @return "inbox" if TXT should go directly to inbox, "picker" if file picker should open, or
   *     "none" if no TXT export is needed.
   */
  private static String decideTxtExportAction(boolean includeOcr, boolean inboxExportInProgress) {
    return decideTxtExportAction(includeOcr, inboxExportInProgress, OCR_TEXT);
  }

  private static String decideTxtExportAction(
      boolean includeOcr, boolean inboxExportInProgress, String ocrText) {
    switch (ExportTxtHelper.decideTxtExport(includeOcr, inboxExportInProgress, ocrText)) {
      case INBOX:
        return "inbox";
      case PICKER:
        return "picker";
      default:
        return "none";
    }
  }

  // --- Inbox Mode active ---

  @Test
  public void inboxMode_withOcr_exportsTxtToInbox() {
    assertEquals("inbox", decideTxtExportAction(true, true));
  }

  @Test
  public void inboxMode_withoutOcr_noTxtExport() {
    assertEquals("none", decideTxtExportAction(false, true));
  }

  // --- Normal Mode (no Inbox) ---

  @Test
  public void normalMode_withOcr_showsFilePicker() {
    assertEquals("picker", decideTxtExportAction(true, false));
  }

  @Test
  public void normalMode_withoutOcr_noTxtExport() {
    assertEquals("none", decideTxtExportAction(false, false));
  }

  // --- No real OCR result (e.g. "Skip OCR"): never export a TXT ---

  @Test
  public void includeTxt_withoutOcrResult_noTxtExport() {
    assertEquals("none", decideTxtExportAction(true, false, null));
    assertEquals("none", decideTxtExportAction(true, true, null));
    assertEquals("none", decideTxtExportAction(true, false, ""));
  }

  @Test
  public void includeTxt_withOnlyPageSeparators_noTxtExport() {
    // Multi-page session in which no page has OCR text: only the "\n\n" separators remain
    assertEquals("none", decideTxtExportAction(true, false, "\n\n\n\n"));
    assertEquals("none", decideTxtExportAction(true, true, " \n\n "));
  }

  @Test
  public void collectOcrText_singlePage_usesInMemoryText() {
    assertEquals(OCR_TEXT, ExportTxtHelper.collectOcrText(null, OCR_TEXT, null));
    assertNull(ExportTxtHelper.collectOcrText(null, null, null));
  }

  // --- TXT base name derivation (mirrors ExportFragment.stripOneExtension) ---

  private static String stripOneExtension(String name) {
    if (name == null) return null;
    int idx = name.lastIndexOf('.');
    if (idx > 0 && idx < name.length() - 1) {
      return name.substring(0, idx);
    }
    return name;
  }

  @Test
  public void txtBaseName_derivedFromPdfName() {
    assertEquals("2026-03-06_scan", stripOneExtension("2026-03-06_scan.pdf"));
  }

  @Test
  public void txtBaseName_derivedFromJpegName() {
    assertEquals("2026-03-06_scan", stripOneExtension("2026-03-06_scan.jpg"));
  }

  @Test
  public void txtBaseName_noExtension_returnsOriginal() {
    assertEquals("scan_without_ext", stripOneExtension("scan_without_ext"));
  }

  @Test
  public void txtBaseName_null_returnsNull() {
    assertNull(stripOneExtension(null));
  }

  @Test
  public void txtBaseName_multipleExtensions_stripsOnlyLast() {
    assertEquals("archive.2026-03-06", stripOneExtension("archive.2026-03-06.pdf"));
  }

  @Test
  public void txtBaseName_dotAtStart_returnsOriginal() {
    // Hidden file like ".pdf" — dot at position 0, no real base name before it
    assertEquals(".pdf", stripOneExtension(".pdf"));
  }
}
