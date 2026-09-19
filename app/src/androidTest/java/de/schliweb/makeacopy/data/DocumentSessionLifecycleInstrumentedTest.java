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

import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import de.schliweb.makeacopy.utils.export.ScanPersister;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Session 4 lifecycle E2E on real device storage (no UI): pages are persisted through the real
 * {@link ScanPersister} (page.jpg + thumb.jpg + registry entry) and attached incrementally to a
 * {@link DocumentSessionRepository} exactly like the multi-page PDF import does. The
 * import-interruption ("process death") is simulated at the repository layer by discarding all
 * in-memory state and re-reading the persisted files with fresh instances — a real {@code am
 * kill} mid-import is not exercised here (documented as a manual adb test in
 * docs/MULTI_PAGE_SESSION_4.md).
 */
@RunWith(AndroidJUnit4.class)
public class DocumentSessionLifecycleInstrumentedTest {

  private final List<String> createdScanIds = new ArrayList<>();
  private File indexFile;

  private Context ctx() {
    return ApplicationProvider.getApplicationContext();
  }

  @After
  public void tearDown() {
    // Remove persisted test pages (files + registry entries) and the test session index.
    for (String id : createdScanIds) {
      try {
        RegistryCleaner.removeEntryAndFiles(ctx(), id);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
    createdScanIds.clear();
    if (indexFile != null) {
      //noinspection ResultOfMethodCallIgnored
      indexFile.delete();
    }
  }

  /** Persists a real page (small bitmap) exactly like the PDF importer does. */
  private CompletedScan persistPage(int pdfPageIndex) throws Exception {
    Bitmap bmp = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888);
    bmp.eraseColor(0xFF000000 | (pdfPageIndex * 37) << 8);
    CompletedScan inMemory =
        new CompletedScan(
            UUID.randomUUID().toString(),
            null,
            0,
            null,
            null,
            null,
            System.currentTimeMillis(),
            bmp.getWidth(),
            bmp.getHeight(),
            bmp,
            2,
            "baked",
            CompletedScan.SOURCE_PDF,
            pdfPageIndex,
            CompletedScan.STATUS_IMPORTING);
    CompletedScan persisted = ScanPersister.persist(ctx(), inMemory, null, null);
    createdScanIds.add(persisted.id());
    bmp.recycle();
    return persisted;
  }

  @Test
  public void partialImport_recovery_close_open_discard_endToEnd() throws Exception {
    indexFile =
        new File(
            new File(ctx().getFilesDir(), "documents"),
            "test_sessions_" + System.currentTimeMillis() + ".json");
    DocumentSessionRepository repo = new DocumentSessionRepository(indexFile);
    CompletedScansRegistry registry = CompletedScansRegistry.get(ctx());

    // --- Incremental import: stable documentId before the first page ---
    String docId = UUID.randomUUID().toString();

    // Pages A and B are persisted and attached immediately (render → persist → append).
    CompletedScan a = persistPage(0);
    repo.appendPageToActive(docId, a.id());
    CompletedScan b = persistPage(1);
    repo.appendPageToActive(docId, b.id());

    // --- Simulated process death during page 3: drop all in-memory state ---
    DocumentSessionRepository restarted = new DocumentSessionRepository(indexFile);
    DocumentSession active = restarted.getActiveSession();
    assertNotNull("active document survives restart", active);
    assertEquals(docId, active.documentId());
    List<CompletedScan> resolved = restarted.resolveActivePages(registry);
    assertEquals("both fully persisted pages are recovered", 2, resolved.size());
    assertEquals(a.id(), resolved.get(0).id());
    assertEquals(b.id(), resolved.get(1).id());
    for (CompletedScan s : resolved) {
      assertNotNull(s.filePath());
      assertTrue("page file exists on disk", new File(s.filePath()).exists());
    }

    // --- Resume: import continues with pages C and D (order preserved) ---
    CompletedScan c = persistPage(2);
    restarted.appendPageToActive(docId, c.id());
    CompletedScan d = persistPage(3);
    restarted.appendPageToActive(docId, d.id());

    // --- Close document: session persists, active pointer cleared ---
    restarted.endActiveSession(false);
    assertNull(restarted.getActiveDocumentId());
    assertNotNull(restarted.findById(docId));

    // --- Open document later (fresh instance again): same id, same order ---
    DocumentSessionRepository reopened = new DocumentSessionRepository(indexFile);
    assertEquals(1, reopened.listSessions().size());
    DocumentSession openedSession = reopened.setActive(docId);
    assertNotNull(openedSession);
    List<CompletedScan> openedPages = reopened.resolvePages(openedSession, registry);
    assertEquals(4, openedPages.size());
    assertEquals(a.id(), openedPages.get(0).id());
    assertEquals(b.id(), openedPages.get(1).id());
    assertEquals(c.id(), openedPages.get(2).id());
    assertEquals(d.id(), openedPages.get(3).id());

    // --- Discard: session removed, pages themselves are kept (registry cleanup owns them) ---
    reopened.delete(docId);
    assertNull(reopened.getActiveDocumentId());
    assertNull(reopened.findById(docId));
    assertTrue(reopened.listSessions().isEmpty());
    assertNotNull("pages survive discard", registry.findById(a.id()));
  }
}
