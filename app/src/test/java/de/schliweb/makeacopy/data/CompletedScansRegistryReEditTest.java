/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.data;

import static org.junit.Assert.*;

import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import java.io.File;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for the Session 3 re-edit invariants at the registry level: {@code insertOrReplace}
 * must keep the stable page id, replace stale metadata (old OCR paths must not survive an edit),
 * never duplicate entries and preserve source provenance.
 */
public class CompletedScansRegistryReEditTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private CompletedScansRegistry registry;

  @Before
  public void setUp() throws Exception {
    registry = new CompletedScansRegistry(new File(tmp.newFolder(), "completed_scans.json"));
  }

  @Test
  public void insertOrReplace_insertsWhenMissing() throws Exception {
    registry.insertOrReplace(
        new CompletedScan("A", "/p/page.jpg", 0, null, null, null, 1L, 10, 20, null, 2, "baked"));
    CompletedScan found = registry.findById("A");
    assertNotNull(found);
    assertEquals("/p/page.jpg", found.filePath());
  }

  @Test
  public void insertOrReplace_replacesStaleOcrMetadata_keepingIdAndProvenance() throws Exception {
    // Original PDF-sourced page with a complete OCR result.
    registry.insert(
        new CompletedScan(
            "B",
            "/p/page.jpg",
            0,
            "/p/words.json",
            "words_json",
            "/p/thumb.jpg",
            1L,
            10,
            20,
            null,
            2,
            "baked",
            CompletedScan.SOURCE_PDF,
            4,
            CompletedScan.STATUS_OCR_COMPLETE));
    // Re-edit: same id, OCR invalidated, provenance preserved.
    registry.insertOrReplace(
        new CompletedScan(
            "B",
            "/p/page.jpg",
            0,
            null,
            null,
            "/p/thumb.jpg",
            1L,
            30,
            40,
            null,
            2,
            "baked",
            CompletedScan.SOURCE_PDF,
            4,
            CompletedScan.STATUS_IMPORTED));

    CompletedScan updated = registry.findById("B");
    assertNotNull(updated);
    assertEquals("B", updated.id());
    assertNull("stale OCR path must not survive an edit", updated.ocrTextPath());
    assertEquals(CompletedScan.STATUS_IMPORTED, updated.pageStatus());
    assertEquals(CompletedScan.SOURCE_PDF, updated.sourceType());
    assertEquals(4, updated.pdfPageIndex());
    assertEquals(30, updated.widthPx());
    assertEquals(40, updated.heightPx());
  }

  @Test
  public void insertOrReplace_neverDuplicatesEntries() throws Exception {
    CompletedScan s =
        new CompletedScan("C", "/p/page.jpg", 0, null, null, null, 1L, 10, 20, null, 2, "baked");
    registry.insert(s);
    registry.insertOrReplace(s);
    registry.insertOrReplace(s);
    List<CompletedScan> all = registry.listAllOrderedByDateDesc();
    assertEquals(1, all.size());
  }
}
