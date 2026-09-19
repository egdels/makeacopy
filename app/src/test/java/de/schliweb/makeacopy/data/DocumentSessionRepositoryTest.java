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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link DocumentSessionRepository} (Session 3): creation, serialization round
 * trips, ordered page ids, add/remove/move/update semantics, restore with missing pages (session
 * repair), dangling activeDocumentId, corrupted JSON, schema version and unknown optional fields.
 */
public class DocumentSessionRepositoryTest {

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

  // ===== Session creation / serialization =====

  @Test
  public void createSession_persistsAndBecomesActive() {
    DocumentSession s = repo.createSession(Arrays.asList("A", "B", "C"));
    assertNotNull(s.documentId());
    assertTrue(indexFile.exists());

    DocumentSessionRepository fresh = new DocumentSessionRepository(indexFile);
    DocumentSession active = fresh.getActiveSession();
    assertNotNull(active);
    assertEquals(s.documentId(), active.documentId());
    assertEquals(Arrays.asList("A", "B", "C"), active.pageIds());
  }

  @Test
  public void emptySession_isValid() {
    DocumentSession s = repo.createSession(null);
    DocumentSession active = new DocumentSessionRepository(indexFile).getActiveSession();
    assertNotNull(active);
    assertEquals(s.documentId(), active.documentId());
    assertTrue(active.pageIds().isEmpty());
  }

  @Test
  public void pageOrder_survivesRepositoryReload() {
    DocumentSession s = repo.createSession(Arrays.asList("A", "B", "C"));
    // move A to index 2 -> B, C, A
    repo.upsertActive(s.documentId(), Arrays.asList("B", "C", "A"));
    DocumentSession restored = new DocumentSessionRepository(indexFile).getActiveSession();
    assertNotNull(restored);
    assertEquals(Arrays.asList("B", "C", "A"), restored.pageIds());
  }

  @Test
  public void addRemove_arePersisted() {
    DocumentSession s = repo.createSession(Arrays.asList("A"));
    repo.upsertActive(s.documentId(), Arrays.asList("A", "B")); // add
    repo.upsertActive(s.documentId(), Arrays.asList("B")); // remove A
    DocumentSession restored = new DocumentSessionRepository(indexFile).getActiveSession();
    assertEquals(Arrays.asList("B"), restored.pageIds());
  }

  @Test
  public void update_doesNotChangeDocumentId() {
    DocumentSession s = repo.createSession(Arrays.asList("A", "B"));
    DocumentSession updated = repo.upsertActive(s.documentId(), Arrays.asList("A", "B"));
    assertEquals(s.documentId(), updated.documentId());
  }

  @Test
  public void upsertActive_createsSessionWhenMissing() {
    DocumentSession s = repo.upsertActive("doc-1", Arrays.asList("A"));
    assertEquals("doc-1", s.documentId());
    DocumentSession active = new DocumentSessionRepository(indexFile).getActiveSession();
    assertEquals("doc-1", active.documentId());
    assertEquals(Arrays.asList("A"), active.pageIds());
  }

  @Test
  public void startNewDocument_keepsOldSessionPersisted() {
    DocumentSession first = repo.createSession(Arrays.asList("A"));
    DocumentSession second = repo.createSession(Arrays.asList("X"));
    DocumentSessionRepository fresh = new DocumentSessionRepository(indexFile);
    assertEquals(second.documentId(), fresh.getActiveSession().documentId());
    // Old session data is not silently deleted.
    assertNotNull(fresh.findById(first.documentId()));
  }

  @Test
  public void endActiveSession_keepsDataUnlessDeleted() {
    DocumentSession s = repo.createSession(Arrays.asList("A"));
    repo.endActiveSession(false);
    DocumentSessionRepository fresh = new DocumentSessionRepository(indexFile);
    assertNull(fresh.getActiveSession());
    assertNotNull(fresh.findById(s.documentId()));
  }

  @Test
  public void endActiveSession_deletesOnExplicitDiscard() {
    DocumentSession s = repo.createSession(Arrays.asList("A"));
    repo.endActiveSession(true);
    DocumentSessionRepository fresh = new DocumentSessionRepository(indexFile);
    assertNull(fresh.getActiveSession());
    assertNull(fresh.findById(s.documentId()));
  }

  @Test
  public void delete_clearsActivePointer() {
    DocumentSession s = repo.createSession(Arrays.asList("A"));
    repo.delete(s.documentId());
    assertNull(repo.getActiveSession());
    assertNull(repo.findById(s.documentId()));
  }

  // ===== Restore / consistency =====

  @Test
  public void resolveActivePages_returnsOrderedPages() throws Exception {
    CompletedScansRegistry registry =
        new CompletedScansRegistry(new File(tmp.newFolder("registry"), "completed_scans.json"));
    registry.insert(scan("A"));
    registry.insert(scan("B"));
    registry.insert(scan("C"));
    repo.createSession(Arrays.asList("A", "B", "C"));

    // Fresh instances simulate an app restart (no shared in-memory state).
    DocumentSessionRepository freshRepo = new DocumentSessionRepository(indexFile);
    List<CompletedScan> pages = freshRepo.resolveActivePages(registry);
    assertEquals(3, pages.size());
    assertEquals("A", pages.get(0).id());
    assertEquals("B", pages.get(1).id());
    assertEquals("C", pages.get(2).id());
  }

  @Test
  public void resolveActivePages_skipsMissingAndRepairsSession() throws Exception {
    CompletedScansRegistry registry =
        new CompletedScansRegistry(new File(tmp.newFolder("registry"), "completed_scans.json"));
    registry.insert(scan("A"));
    registry.insert(scan("C"));
    DocumentSession s = repo.createSession(Arrays.asList("A", "B", "C"));

    List<CompletedScan> pages = repo.resolveActivePages(registry);
    assertEquals(2, pages.size());
    assertEquals("A", pages.get(0).id());
    assertEquals("C", pages.get(1).id());

    // Session was repaired persistently: B is gone after reload.
    DocumentSession repaired =
        new DocumentSessionRepository(indexFile).findById(s.documentId());
    assertEquals(Arrays.asList("A", "C"), repaired.pageIds());
  }

  @Test
  public void danglingActiveDocumentId_fallsBackToNoActiveSession() throws Exception {
    String json =
        "{\"schemaVersion\":1,\"activeDocumentId\":\"missing-doc\",\"sessions\":[]}";
    Files.write(indexFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
    DocumentSessionRepository fresh = new DocumentSessionRepository(indexFile);
    assertNull(fresh.getActiveSession());
    // The dangling pointer was cleared persistently.
    String persisted = new String(Files.readAllBytes(indexFile.toPath()), StandardCharsets.UTF_8);
    assertFalse(persisted.contains("missing-doc"));
  }

  // ===== Robust deserialization =====

  @Test
  public void corruptedJson_isTreatedAsEmptyStore() throws Exception {
    Files.write(indexFile.toPath(), "{not valid json!!".getBytes(StandardCharsets.UTF_8));
    DocumentSessionRepository fresh = new DocumentSessionRepository(indexFile);
    assertNull(fresh.getActiveSession());
    // Repository stays usable.
    DocumentSession s = fresh.createSession(Arrays.asList("A"));
    assertEquals(s.documentId(), fresh.getActiveSession().documentId());
  }

  @Test
  public void unknownOptionalFields_areIgnored() throws Exception {
    String json =
        "{\"schemaVersion\":1,\"activeDocumentId\":\"doc-1\",\"futureField\":42,"
            + "\"sessions\":[{\"documentId\":\"doc-1\",\"createdAt\":1,\"updatedAt\":2,"
            + "\"pageIds\":[\"A\",\"B\"],\"someUnknown\":{\"x\":1}}]}";
    Files.write(indexFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
    DocumentSession active = new DocumentSessionRepository(indexFile).getActiveSession();
    assertNotNull(active);
    assertEquals(Arrays.asList("A", "B"), active.pageIds());
  }

  @Test
  public void missingOptionalFields_getRobustDefaults() throws Exception {
    String json =
        "{\"activeDocumentId\":\"doc-1\","
            + "\"sessions\":[{\"documentId\":\"doc-1\"}]}"; // no schemaVersion/pageIds/title
    Files.write(indexFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
    DocumentSession active = new DocumentSessionRepository(indexFile).getActiveSession();
    assertNotNull(active);
    assertTrue(active.pageIds().isEmpty());
    assertNull(active.title());
  }

  @Test
  public void schemaVersion_isWrittenToDisk() throws Exception {
    repo.createSession(Arrays.asList("A"));
    String persisted = new String(Files.readAllBytes(indexFile.toPath()), StandardCharsets.UTF_8);
    assertTrue(persisted.contains("\"schemaVersion\":1"));
  }

  @Test
  public void atomicWrite_leavesNoTempFileBehind() {
    repo.createSession(Arrays.asList("A"));
    File tmpFile = new File(indexFile.getParentFile(), indexFile.getName() + ".tmp");
    assertFalse(tmpFile.exists());
    assertTrue(indexFile.exists());
  }

  @Test
  public void documentSession_pageIdsAreImmutable() {
    DocumentSession s = new DocumentSession("d", 1, 2, null, Arrays.asList("A"));
    try {
      s.pageIds().add("B");
      fail("pageIds must be unmodifiable");
    } catch (UnsupportedOperationException expected) {
      // expected
    }
  }
}
