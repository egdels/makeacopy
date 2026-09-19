package de.schliweb.makeacopy.data;

import static org.junit.Assert.*;

import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies serialization/deserialization of the additive multi-page fields (sourceType,
 * pdfPageIndex, pageStatus) in the CompletedScans registry, including backward compatibility with
 * legacy registry JSON that does not contain these fields.
 */
public class CompletedScansRegistryMultiPageTest {

  private File tempDir;
  private File indexFile;
  private CompletedScansRegistry registry;

  @Before
  public void setUp() throws Exception {
    tempDir = java.nio.file.Files.createTempDirectory("mac_registry_mp_test").toFile();
    indexFile = new File(tempDir, "completed_scans.json");
    registry = new CompletedScansRegistry(indexFile);
  }

  @After
  public void tearDown() {
    if (tempDir != null && tempDir.exists()) {
      deleteRec(tempDir);
    }
  }

  private void deleteRec(File f) {
    if (f.isDirectory()) {
      File[] files = f.listFiles();
      if (files != null) for (File c : files) deleteRec(c);
    }
    //noinspection ResultOfMethodCallIgnored
    f.delete();
  }

  @Test
  public void roundtrip_pdfPage_preservesMultiPageFields() throws Exception {
    CompletedScan pdfPage =
        new CompletedScan(
            "pdf-1",
            "/tmp/pdf-1/page.jpg",
            0,
            null,
            null,
            "/tmp/pdf-1/thumb.jpg",
            System.currentTimeMillis(),
            100,
            200,
            null,
            2,
            "baked",
            CompletedScan.SOURCE_PDF,
            16,
            CompletedScan.STATUS_IMPORTED);
    registry.insert(pdfPage);

    List<CompletedScan> all = registry.listAllOrderedByDateDesc();
    assertEquals(1, all.size());
    CompletedScan loaded = all.get(0);
    assertEquals(CompletedScan.SOURCE_PDF, loaded.sourceType());
    assertEquals(16, loaded.pdfPageIndex());
    assertEquals(CompletedScan.STATUS_IMPORTED, loaded.pageStatus());
  }

  @Test
  public void legacyJson_withoutNewFields_loadsWithSafeDefaults() throws Exception {
    // Simulate a pre-multi-page registry file (no sourceType/pdfPageIndex/pageStatus)
    String legacyJson =
        "{\"version\":1,\"items\":[{"
            + "\"id\":\"legacy-1\",\"filePath\":\"/tmp/legacy-1/page.jpg\",\"rotationDeg\":0,"
            + "\"createdAt\":123456789,\"widthPx\":100,\"heightPx\":200,"
            + "\"schemaVersion\":1,\"orientationMode\":\"baked\"},{"
            + "\"id\":\"legacy-2\",\"filePath\":\"/tmp/legacy-2/page.jpg\",\"rotationDeg\":0,"
            + "\"ocrTextPath\":\"/tmp/legacy-2/text.txt\",\"ocrFormat\":\"plain\","
            + "\"createdAt\":123456790,\"widthPx\":100,\"heightPx\":200,"
            + "\"schemaVersion\":1,\"orientationMode\":\"baked\"}]}";
    try (FileOutputStream fos = new FileOutputStream(indexFile)) {
      fos.write(legacyJson.getBytes(StandardCharsets.UTF_8));
    }

    List<CompletedScan> all = registry.listAllOrderedByDateDesc();
    assertEquals(2, all.size());
    // ordered by createdAt desc: legacy-2 first
    CompletedScan withOcr = all.get(0);
    CompletedScan withoutOcr = all.get(1);

    assertEquals("legacy-2", withOcr.id());
    assertEquals(CompletedScan.SOURCE_CAMERA, withOcr.sourceType());
    assertEquals(CompletedScan.NO_PDF_PAGE, withOcr.pdfPageIndex());
    assertEquals(CompletedScan.STATUS_OCR_COMPLETE, withOcr.pageStatus());

    assertEquals("legacy-1", withoutOcr.id());
    assertEquals(CompletedScan.SOURCE_CAMERA, withoutOcr.sourceType());
    assertEquals(CompletedScan.NO_PDF_PAGE, withoutOcr.pdfPageIndex());
    assertEquals(CompletedScan.STATUS_IMPORTED, withoutOcr.pageStatus());
  }

  @Test
  public void roundtrip_legacyConstructedScan_staysLoadable() throws Exception {
    // Insert via the legacy 12-arg constructor (as existing call sites do)
    CompletedScan legacy =
        new CompletedScan(
            "legacy-3",
            "/tmp/legacy-3/page.jpg",
            0,
            null,
            null,
            null,
            System.currentTimeMillis(),
            100,
            200,
            null,
            1,
            "baked");
    registry.insert(legacy);

    List<CompletedScan> all = registry.listAllOrderedByDateDesc();
    assertEquals(1, all.size());
    CompletedScan loaded = all.get(0);
    assertEquals("legacy-3", loaded.id());
    assertEquals(CompletedScan.SOURCE_CAMERA, loaded.sourceType());
    assertEquals(CompletedScan.NO_PDF_PAGE, loaded.pdfPageIndex());
    assertEquals(CompletedScan.STATUS_IMPORTED, loaded.pageStatus());
  }
}
