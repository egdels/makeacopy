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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for the Session 4 additions to {@link DocumentSessionRepository}: incremental import
 * recovery ({@code appendPageToActive}), document lifecycle (New/Open/Close/Discard semantics via
 * {@code setActive}/{@code endActiveSession}/{@code delete}), saved-documents listing ({@code
 * listSessions}) and the empty-session cleanup policy.
 */
public class DocumentSessionLifecycleTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private File indexFile;
  private DocumentSessionRepository repo;

  @Before
  public void setUp() throws Exception {
    indexFile = new File(tmp.newFolder("documents"), "document_sessions.json");
    repo = new DocumentSessionRepository(indexFile);
  }

  private static CompletedScan scan(String id) {
    return new CompletedScan(
        id, "/tmp/" + id + "/page.jpg", 0, null, null, null, 1L, 100, 200, null, 2, "baked");
  }

  private CompletedScansRegistry registryWith(String... ids) throws Exception {
    CompletedScansRegistry registry =
        new CompletedScansRegistry(new File(tmp.newFolder("registry"), "completed_scans.json"));
    for (String id : ids) registry.insert(scan(id));
    return registry;
  }

  // ===== Incremental import (Session 4 §5-§13) =====

  @Test
  public void appendPageToActive_createsSessionAndMarksActive() {
    DocumentSession s = repo.appendPageToActive("doc-1", "A");
    assertNotNull(s);
    assertEquals("doc-1", repo.getActiveDocumentId());
    assertEquals(Arrays.asList("A"), repo.getActiveSession().pageIds());
  }

  @Test
  public void appendPageToActive_appendsInImportOrder() {
    repo.appendPageToActive("doc-1", "pageForPdf1");
    repo.appendPageToActive("doc-1", "pageForPdf2");
    repo.appendPageToActive("doc-1", "pageForPdf4");
    repo.appendPageToActive("doc-1", "pageForPdf7");
    assertEquals(
        Arrays.asList("pageForPdf1", "pageForPdf2", "pageForPdf4", "pageForPdf7"),
        repo.getActiveSession().pageIds());
  }

  @Test
  public void failedPage_isSimplyNotAppended() {
    // Importer contract: append only after successful persist; a failed page never reaches
    // appendPageToActive. 1 ok, 2 ok, 4 failed, 7 ok:
    repo.appendPageToActive("doc-1", "1");
    repo.appendPageToActive("doc-1", "2");
    repo.appendPageToActive("doc-1", "7");
    assertEquals(Arrays.asList("1", "2", "7"), repo.getActiveSession().pageIds());
  }

  @Test
  public void appendPageToActive_isIdempotent() {
    repo.appendPageToActive("doc-1", "A");
    repo.appendPageToActive("doc-1", "A");
    repo.appendPageToActive("doc-1", "B");
    repo.appendPageToActive("doc-1", "B");
    assertEquals(Arrays.asList("A", "B"), repo.getActiveSession().pageIds());
  }

  @Test
  public void finalRuntimeSync_afterIncrementalAppend_createsNoDuplicates() {
    // Incremental appends during the import ...
    repo.appendPageToActive("doc-1", "A");
    repo.appendPageToActive("doc-1", "B");
    // ... followed by the existing onComplete → ExportSessionViewModel → observer sync chain
    // which upserts the same ordered id list.
    repo.upsertActive("doc-1", Arrays.asList("A", "B"));
    assertEquals(Arrays.asList("A", "B"), repo.getActiveSession().pageIds());
  }

  @Test
  public void partialImport_survivesRestart() {
    // Pages 1..2 persisted and appended, process death before page 3.
    repo.appendPageToActive("doc-1", "A");
    repo.appendPageToActive("doc-1", "B");
    // Restart: fresh repository instance reads the same file.
    DocumentSessionRepository fresh = new DocumentSessionRepository(indexFile);
    DocumentSession active = fresh.getActiveSession();
    assertNotNull(active);
    assertEquals("doc-1", active.documentId());
    assertEquals(Arrays.asList("A", "B"), active.pageIds());
  }

  @Test
  public void cancelledImport_keepsCompletedPages() {
    // Cancel after B: completed pages stay part of the document, nothing else was appended.
    repo.appendPageToActive("doc-1", "A");
    repo.appendPageToActive("doc-1", "B");
    DocumentSessionRepository fresh = new DocumentSessionRepository(indexFile);
    assertEquals(Arrays.asList("A", "B"), fresh.getActiveSession().pageIds());
  }

  @Test
  public void emptyImport_createsNoSession() {
    // No page ever persisted → appendPageToActive is never called → no broken session exists.
    assertNull(repo.getActiveSession());
    assertTrue(repo.listSessions().isEmpty());
    assertFalse(indexFile.exists());
  }

  @Test
  public void appendPageToActive_addsToExistingDocument() {
    // "Add pages via PDF import" into an existing document keeps earlier pages and order.
    repo.upsertActive("doc-1", Arrays.asList("camA"));
    repo.appendPageToActive("doc-1", "pdfB");
    repo.appendPageToActive("doc-1", "pdfC");
    assertEquals(Arrays.asList("camA", "pdfB", "pdfC"), repo.getActiveSession().pageIds());
  }

  // ===== Document lifecycle (Session 4 §14-§18) =====

  @Test
  public void newDocument_keepsOldSessionPersisted() {
    DocumentSession a = repo.createSession(Arrays.asList("A"));
    DocumentSession b = repo.createSession(Arrays.asList("B"));
    assertEquals(b.documentId(), repo.getActiveDocumentId());
    // Old document still exists (never silently deleted).
    assertNotNull(repo.findById(a.documentId()));
    assertEquals(Arrays.asList("A"), repo.findById(a.documentId()).pageIds());
  }

  @Test
  public void openDocument_changesActivePointerWithoutCopy() {
    DocumentSession a = repo.createSession(Arrays.asList("A"));
    repo.createSession(Arrays.asList("B"));
    DocumentSession opened = repo.setActive(a.documentId());
    assertNotNull(opened);
    assertEquals(a.documentId(), opened.documentId());
    assertEquals(a.documentId(), repo.getActiveDocumentId());
    assertEquals(2, repo.listSessions().size()); // no copy created
  }

  @Test
  public void setActive_unknownId_isNoOp() {
    DocumentSession a = repo.createSession(Arrays.asList("A"));
    assertNull(repo.setActive("does-not-exist"));
    assertEquals(a.documentId(), repo.getActiveDocumentId());
  }

  @Test
  public void closeDocument_clearsPointerButKeepsSession() {
    DocumentSession a = repo.createSession(Arrays.asList("A", "B"));
    repo.endActiveSession(false);
    assertNull(repo.getActiveDocumentId());
    DocumentSession kept = new DocumentSessionRepository(indexFile).findById(a.documentId());
    assertNotNull(kept);
    assertEquals(Arrays.asList("A", "B"), kept.pageIds());
  }

  @Test
  public void closeDocument_removesEmptyDraftSession() {
    DocumentSession empty = repo.createSession(Collections.emptyList());
    repo.endActiveSession(false);
    assertNull(repo.getActiveDocumentId());
    // Empty drafts do not accumulate (§24).
    assertNull(repo.findById(empty.documentId()));
  }

  @Test
  public void discardDocument_removesSessionAndPointer() {
    DocumentSession a = repo.createSession(Arrays.asList("A"));
    repo.delete(a.documentId());
    assertNull(repo.getActiveDocumentId());
    assertNull(repo.findById(a.documentId()));
    assertTrue(repo.listSessions().isEmpty());
  }

  // ===== Saved documents list (Session 4 §19-§25) =====

  @Test
  public void listSessions_sortedByUpdatedAtDescending() throws Exception {
    DocumentSession a = repo.createSession(Arrays.asList("A"));
    Thread.sleep(5);
    DocumentSession b = repo.createSession(Arrays.asList("B"));
    Thread.sleep(5);
    // Touch A so it becomes the most recently updated document.
    repo.appendPageToActive(a.documentId(), "A2");
    List<DocumentSession> list = repo.listSessions();
    assertEquals(2, list.size());
    assertEquals(a.documentId(), list.get(0).documentId());
    assertEquals(b.documentId(), list.get(1).documentId());
  }

  @Test
  public void listSessions_excludesEmptySessions_andCountsPages() {
    repo.createSession(Collections.emptyList());
    DocumentSession b = repo.createSession(Arrays.asList("A", "B", "C"));
    List<DocumentSession> list = repo.listSessions();
    assertEquals(1, list.size());
    assertEquals(b.documentId(), list.get(0).documentId());
    assertEquals(3, list.get(0).pageIds().size());
    assertNull(list.get(0).title()); // unknown title handled by UI default label
  }

  @Test
  public void cleanupEmptySessions_removesOnlyEmptyOnes() {
    DocumentSession empty = repo.createSession(Collections.emptyList());
    DocumentSession full = repo.createSession(Arrays.asList("A"));
    repo.setActive(empty.documentId());
    int removed = repo.cleanupEmptySessions();
    assertEquals(1, removed);
    assertNull(repo.findById(empty.documentId()));
    assertNotNull(repo.findById(full.documentId()));
    // Active pointer to the removed empty session was cleared.
    assertNull(repo.getActiveDocumentId());
  }

  @Test
  public void resolvePages_ofInactiveSession_repairsMissingPages() throws Exception {
    CompletedScansRegistry registry = registryWith("A", "C");
    DocumentSession a = repo.createSession(Arrays.asList("A", "B", "C"));
    repo.createSession(Arrays.asList("X")); // another session becomes active
    List<CompletedScan> pages = repo.resolvePages(repo.findById(a.documentId()), registry);
    assertEquals(2, pages.size());
    assertEquals("A", pages.get(0).id());
    assertEquals("C", pages.get(1).id());
    // Repair is persisted for the inactive session as well.
    assertEquals(
        Arrays.asList("A", "C"),
        new DocumentSessionRepository(indexFile).findById(a.documentId()).pageIds());
  }

  @Test
  public void multipleSessions_remainIsolated() {
    repo.appendPageToActive("doc-1", "A");
    repo.appendPageToActive("doc-2", "X");
    repo.appendPageToActive("doc-2", "Y");
    assertEquals(Arrays.asList("A"), repo.findById("doc-1").pageIds());
    assertEquals(Arrays.asList("X", "Y"), repo.findById("doc-2").pageIds());
    assertEquals("doc-2", repo.getActiveDocumentId());
  }

  @Test
  public void appendPageToActive_bumpsUpdatedAt() throws Exception {
    DocumentSession before = repo.appendPageToActive("doc-1", "A");
    Thread.sleep(5);
    DocumentSession after = repo.appendPageToActive("doc-1", "B");
    assertTrue(after.updatedAt() > before.updatedAt());
  }
}
