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
import java.util.ArrayList;
import java.util.List;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

/**
 * Session 5: REAL process-death verification. Unlike {@link
 * DocumentSessionLifecycleInstrumentedTest} (which only re-instantiates repositories in the same
 * process), this test is split into two phases that are executed in two separate {@code am
 * instrument} invocations with an {@code adb shell am kill <package>} in between:
 *
 * <pre>
 * am instrument ... -e class ...#phaseA_persistActiveDocument
 * adb shell am kill de.schliweb.makeacopy
 * am instrument ... -e class ...#phaseB_verifyRestoredAfterProcessDeath
 * </pre>
 *
 * <p>Phase A persists a 3-page active document through the REAL app storage (default {@link
 * DocumentSessionRepository#get} and {@link ScanPersister}, real {@code filesDir}). Phase B runs
 * in a brand-new process after the kill and verifies: same documentId, same page count, same page
 * order, all page files present. Phase B also cleans up the test document and its pages.
 *
 * <p>NOTE: running both phases inside a single connected test run does NOT verify real process
 * death — the two-phase adb orchestration is required (documented in
 * docs/MULTI_PAGE_SESSION_5.md).
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ProcessDeathRecoveryInstrumentedTest {

  private static final String MARKER_TITLE = "PROCESS_DEATH_TEST_DOC";
  private static final int PAGE_COUNT = 3;

  private Context ctx() {
    return ApplicationProvider.getApplicationContext();
  }

  private static Bitmap page(int seed) {
    Bitmap b = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888);
    b.eraseColor(0xFF000000 | (seed * 0x203040));
    return b;
  }

  /** Phase A: persist a 3-page active document exactly like the import path does. */
  @Test
  public void phaseA_persistActiveDocument() throws Exception {
    Context ctx = ctx();
    DocumentSessionRepository repo = DocumentSessionRepository.get(ctx);
    List<String> pageIds = new ArrayList<>();
    for (int i = 0; i < PAGE_COUNT; i++) {
      Bitmap bmp = page(i + 1);
      CompletedScan inMemory =
          new CompletedScan(
              java.util.UUID.randomUUID().toString(),
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
              "baked");
      CompletedScan scan = ScanPersister.persist(ctx, inMemory, null, null);
      assertNotNull("page must persist", scan);
      pageIds.add(scan.id());
    }
    DocumentSession session = repo.createSession(pageIds);
    repo.save(
        new DocumentSession(
            session.documentId(),
            session.createdAt(),
            session.updatedAt(),
            MARKER_TITLE,
            pageIds));
    repo.setActive(session.documentId());
    assertEquals(session.documentId(), repo.getActiveDocumentId());
  }

  /** Phase B: runs in a fresh process (after am kill) and verifies the restored document. */
  @Test
  public void phaseB_verifyRestoredAfterProcessDeath() {
    Context ctx = ctx();
    DocumentSessionRepository repo = DocumentSessionRepository.get(ctx);
    DocumentSession active = repo.getActiveSession();
    assertNotNull("active document must survive process death", active);
    assertEquals(MARKER_TITLE, active.title());
    assertEquals(PAGE_COUNT, active.pageIds().size());

    CompletedScansRegistry registry = CompletedScansRegistry.get(ctx);
    List<CompletedScan> resolved = repo.resolvePages(active, registry);
    assertEquals("all pages must resolve after restart", PAGE_COUNT, resolved.size());
    for (int i = 0; i < PAGE_COUNT; i++) {
      CompletedScan s = resolved.get(i);
      assertEquals("page order must be preserved", active.pageIds().get(i), s.id());
      assertNotNull(s.filePath());
      assertTrue("page file must exist: " + s.filePath(), new java.io.File(s.filePath()).exists());
    }

    // Cleanup: discard the test document, then remove its (now unreferenced) pages explicitly.
    List<String> ids = new ArrayList<>(active.pageIds());
    repo.delete(active.documentId());
    for (String id : ids) {
      RegistryCleaner.removeEntryAndFiles(ctx, id);
    }
  }
}
