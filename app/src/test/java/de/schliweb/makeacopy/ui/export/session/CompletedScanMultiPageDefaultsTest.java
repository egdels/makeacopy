package de.schliweb.makeacopy.ui.export.session;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Verifies defaulting/normalization behavior for the additive multi-page fields of CompletedScan
 * (sourceType, pdfPageIndex, pageStatus) introduced in Session 1.
 */
public class CompletedScanMultiPageDefaultsTest {

  private static CompletedScan legacy(String id, String filePath, String ocrTextPath) {
    // Legacy 12-arg constructor (pre-multi-page signature)
    return new CompletedScan(
        id, filePath, 0, ocrTextPath, null, null, System.currentTimeMillis(), 100, 200, null, 1,
        "baked");
  }

  @Test
  public void legacyConstructor_defaultsSourceTypeToCamera() {
    CompletedScan s = legacy("id-1", "/tmp/x.jpg", null);
    assertEquals(CompletedScan.SOURCE_CAMERA, s.sourceType());
  }

  @Test
  public void legacyConstructor_defaultsPdfPageIndexToNoPdfPage() {
    CompletedScan s = legacy("id-2", "/tmp/x.jpg", null);
    assertEquals(CompletedScan.NO_PDF_PAGE, s.pdfPageIndex());
  }

  @Test
  public void legacyConstructor_withoutOcr_statusIsImported() {
    CompletedScan s = legacy("id-3", "/tmp/x.jpg", null);
    assertEquals(CompletedScan.STATUS_IMPORTED, s.pageStatus());
  }

  @Test
  public void legacyConstructor_withOcr_statusIsOcrComplete() {
    CompletedScan s = legacy("id-4", "/tmp/x.jpg", "/tmp/x.txt");
    assertEquals(CompletedScan.STATUS_OCR_COMPLETE, s.pageStatus());
  }

  @Test
  public void canonicalConstructor_keepsExplicitPdfValues() {
    CompletedScan s =
        new CompletedScan(
            "id-5",
            "/tmp/p.jpg",
            0,
            null,
            null,
            null,
            System.currentTimeMillis(),
            100,
            200,
            null,
            2,
            "baked",
            CompletedScan.SOURCE_PDF,
            16,
            CompletedScan.STATUS_IMPORTED);
    assertEquals(CompletedScan.SOURCE_PDF, s.sourceType());
    assertEquals(16, s.pdfPageIndex());
    assertEquals(CompletedScan.STATUS_IMPORTED, s.pageStatus());
  }

  @Test
  public void pdfPageIndex_normalizedToNoPdfPage_forNonPdfSources() {
    CompletedScan s =
        new CompletedScan(
            "id-6",
            "/tmp/p.jpg",
            0,
            null,
            null,
            null,
            System.currentTimeMillis(),
            100,
            200,
            null,
            2,
            "baked",
            CompletedScan.SOURCE_IMAGE,
            7, // must be ignored for non-PDF pages
            null);
    assertEquals(CompletedScan.NO_PDF_PAGE, s.pdfPageIndex());
  }

  @Test
  public void importingStatus_withFilePath_normalizedToImported() {
    CompletedScan s =
        new CompletedScan(
            "id-7",
            "/tmp/p.jpg",
            0,
            null,
            null,
            null,
            System.currentTimeMillis(),
            100,
            200,
            null,
            2,
            "baked",
            CompletedScan.SOURCE_PDF,
            3,
            CompletedScan.STATUS_IMPORTING);
    assertEquals(CompletedScan.STATUS_IMPORTED, s.pageStatus());
  }

  @Test
  public void importingStatus_withoutFilePath_isKeptTransient() {
    CompletedScan s =
        new CompletedScan(
            "id-8",
            null,
            0,
            null,
            null,
            null,
            System.currentTimeMillis(),
            100,
            200,
            null,
            2,
            "baked",
            CompletedScan.SOURCE_PDF,
            3,
            CompletedScan.STATUS_IMPORTING);
    assertEquals(CompletedScan.STATUS_IMPORTING, s.pageStatus());
  }

  @Test
  public void explicitStatus_isPreserved() {
    CompletedScan s =
        new CompletedScan(
            "id-9",
            "/tmp/p.jpg",
            0,
            null,
            null,
            null,
            System.currentTimeMillis(),
            100,
            200,
            null,
            2,
            "baked",
            CompletedScan.SOURCE_PDF,
            0,
            CompletedScan.STATUS_OCR_FAILED);
    assertEquals(CompletedScan.STATUS_OCR_FAILED, s.pageStatus());
    assertEquals(0, s.pdfPageIndex());
  }
}
