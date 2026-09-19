package de.schliweb.makeacopy.data;

import static org.junit.Assert.*;

import org.junit.Test;

/** Verifies the additive multi-page fields of CompletedScanEntry (Session 1). */
public class CompletedScanEntryMultiPageTest {

  @Test
  public void legacyConstructor_leavesMultiPageFieldsUnset() {
    CompletedScanEntry e =
        new CompletedScanEntry(
            "id-1",
            "/tmp/x.jpg",
            0,
            null,
            null,
            null,
            System.currentTimeMillis(),
            100,
            200,
            1,
            "baked");
    assertNull(e.sourceType);
    assertEquals(0, e.pdfPageIndex);
    assertNull(e.pageStatus);
  }

  @Test
  public void extendedConstructor_setsMultiPageFields() {
    CompletedScanEntry e =
        new CompletedScanEntry(
            "id-2",
            "/tmp/y.jpg",
            0,
            null,
            null,
            null,
            System.currentTimeMillis(),
            100,
            200,
            2,
            "baked",
            "pdf",
            16,
            "IMPORTED");
    assertEquals("pdf", e.sourceType);
    assertEquals(16, e.pdfPageIndex);
    assertEquals("IMPORTED", e.pageStatus);
  }
}
