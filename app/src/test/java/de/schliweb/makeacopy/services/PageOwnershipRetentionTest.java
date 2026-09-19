/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.services;

import static org.junit.Assert.*;

import de.schliweb.makeacopy.data.DocumentSession;
import de.schliweb.makeacopy.data.DocumentSessionRepository;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Session 5 (page ownership) unit tests: a page referenced by any persisted DocumentSession —
 * active or inactive — must never be selected for automatic cleanup. Unreferenced pages are only
 * orphan <em>candidates</em>; legacy/library pages without a session stay protected by the
 * existing age/count/storage policy and are never deleted just because no session references
 * them. Cleanup is deterministic and idempotent.
 */
public class PageOwnershipRetentionTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private DocumentSessionRepository repo;

  @Before
  public void setUp() throws Exception {
    File indexFile = new File(tmp.newFolder("documents"), "document_sessions.json");
    repo = new DocumentSessionRepository(indexFile);
  }

  private static CompletedScan scan(String id, long createdAt) {
    return new CompletedScan(
        id, "/tmp/" + id + "/page.jpg", 0, null, null, null, createdAt, 100, 200, null, 2, "baked");
  }

  // ===== Referenced pages are always retained =====

  @Test
  public void pageReferencedByActiveSession_isKept() {
    repo.createSession(Arrays.asList("A", "B"));
    List<String> candidates = Arrays.asList("A", "B", "X");
    List<String> safe =
        CompletedScansCleanupPolicy.filterOutReferenced(candidates, repo.getAllReferencedPageIds());
    assertEquals(Collections.singletonList("X"), safe);
    assertTrue(repo.isPageReferenced("A"));
    assertFalse(repo.isPageReferenced("X"));
  }

  @Test
  public void pageReferencedByInactiveSession_isKept() {
    repo.createSession(Arrays.asList("A"));
    // Creating a second session makes the first one inactive but keeps it persisted.
    repo.createSession(Arrays.asList("B"));
    Set<String> referenced = repo.getAllReferencedPageIds();
    assertTrue(referenced.contains("A"));
    assertTrue(referenced.contains("B"));
    List<String> safe =
        CompletedScansCleanupPolicy.filterOutReferenced(Arrays.asList("A", "B"), referenced);
    assertTrue(safe.isEmpty());
  }

  @Test
  public void pageReferencedByTwoSessions_removingOneReference_keepsPage() {
    DocumentSession s1 = repo.createSession(Arrays.asList("SHARED", "P1"));
    repo.createSession(Arrays.asList("SHARED", "P2"));
    assertTrue(repo.isPageReferenced("SHARED"));

    // Discard the first session: SHARED stays alive through the second one.
    repo.delete(s1.documentId());
    assertTrue(repo.isPageReferenced("SHARED"));
    assertFalse(repo.isPageReferenced("P1"));
  }

  @Test
  public void discardLastReferencingSession_makesPageOrphanCandidate() {
    DocumentSession s2 = repo.createSession(Arrays.asList("SHARED"));
    repo.delete(s2.documentId());
    assertFalse(repo.isPageReferenced("SHARED"));

    List<CompletedScan> registryPages = Arrays.asList(scan("SHARED", 1L));
    List<String> orphans =
        CompletedScansCleanupPolicy.findUnreferencedPages(
            registryPages, repo.getAllReferencedPageIds());
    assertEquals(Collections.singletonList("SHARED"), orphans);
  }

  // ===== Legacy pages without a session stay protected by the existing policy =====

  @Test
  public void legacyPageWithoutSession_isOrphanCandidateButNotBlindlyDeleted() {
    long now = System.currentTimeMillis();
    // Fresh legacy page (e.g. Scan Library / picker content) without any session reference.
    List<CompletedScan> registryPages = Arrays.asList(scan("LEGACY", now));

    List<String> orphans =
        CompletedScansCleanupPolicy.findUnreferencedPages(
            registryPages, repo.getAllReferencedPageIds());
    assertEquals(Collections.singletonList("LEGACY"), orphans);

    // The existing retention policy still decides: a fresh page is NOT selected by MAX_AGE, so
    // being unreferenced alone never triggers deletion.
    List<String> byAge = CompletedScansCleanupPolicy.idsToRemoveByAge(registryPages, 30, now);
    assertTrue(byAge.isEmpty());
    List<String> byCount = CompletedScansCleanupPolicy.idsToRemoveByCount(registryPages, 100);
    assertTrue(byCount.isEmpty());
  }

  @Test
  public void agedLegacyPage_referencedBySession_survivesAgePolicy() {
    long now = System.currentTimeMillis();
    long old = now - 365L * 24 * 60 * 60 * 1000; // 1 year old
    repo.createSession(Arrays.asList("OLD_REFERENCED"));
    List<CompletedScan> registryPages =
        Arrays.asList(scan("OLD_REFERENCED", old), scan("OLD_UNREFERENCED", old));

    List<String> byAge = CompletedScansCleanupPolicy.idsToRemoveByAge(registryPages, 30, now);
    List<String> safe =
        CompletedScansCleanupPolicy.filterOutReferenced(byAge, repo.getAllReferencedPageIds());
    assertEquals(Collections.singletonList("OLD_UNREFERENCED"), safe);
  }

  // ===== Empty / corrupt session cleanup =====

  @Test
  public void emptySession_isRemovedByCleanup() {
    repo.createSession(Collections.emptyList());
    assertEquals(1, repo.cleanupEmptySessions());
    assertTrue(repo.listSessions().isEmpty());
    assertNull(repo.getActiveSession());
  }

  @Test
  public void corruptSession_onlyMissingPageIds_isRepairedToEmptyAndRemoved() {
    // Session references only page ids that do not exist in the registry.
    DocumentSession broken = repo.createSession(Arrays.asList("MISSING1", "MISSING2"));
    de.schliweb.makeacopy.data.CompletedScansRegistry emptyRegistry =
        new de.schliweb.makeacopy.data.CompletedScansRegistry(
            new File(tmp.getRoot(), "registry.json"));
    List<CompletedScan> resolved = repo.resolvePages(broken, emptyRegistry);
    assertTrue(resolved.isEmpty());

    // Repair persisted the empty page list → empty-session cleanup removes it.
    assertEquals(1, repo.cleanupEmptySessions());
    assertTrue(repo.listSessions().isEmpty());
  }

  // ===== Idempotence =====

  @Test
  public void cleanupIsIdempotent() {
    repo.createSession(Arrays.asList("A"));
    repo.createSession(Collections.emptyList());
    assertEquals(1, repo.cleanupEmptySessions());
    assertEquals(0, repo.cleanupEmptySessions());

    Set<String> ref1 = repo.getAllReferencedPageIds();
    Set<String> ref2 = repo.getAllReferencedPageIds();
    assertEquals(ref1, ref2);

    List<String> safe1 =
        CompletedScansCleanupPolicy.filterOutReferenced(Arrays.asList("A", "X"), ref1);
    List<String> safe2 =
        CompletedScansCleanupPolicy.filterOutReferenced(safe1, ref2);
    assertEquals(safe1, safe2);
    assertEquals(Collections.singletonList("X"), safe2);
  }
}
