package de.schliweb.makeacopy.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.schliweb.makeacopy.data.library.ScanEntity;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** JVM unit tests for {@link CompletedScansCleanupPolicy} filtering logic. */
public class CompletedScansCleanupPolicyTest {

  // Helper to create a minimal CompletedScan with only id and createdAt set.
  private static CompletedScan scan(String id, long createdAt) {
    return new CompletedScan(id, null, 0, null, null, null, createdAt, 0, 0, null, 1, "baked");
  }

  // ---- MAX_AGE tests ----

  @Test
  public void byAge_emptyList_returnsEmpty() {
    List<String> ids =
        CompletedScansCleanupPolicy.idsToRemoveByAge(
            Collections.emptyList(), 30, System.currentTimeMillis());
    assertTrue(ids.isEmpty());
  }

  @Test
  public void byAge_allRecent_noneRemoved() {
    long now = System.currentTimeMillis();
    List<CompletedScan> scans =
        Arrays.asList(scan("a", now - TimeUnit.DAYS.toMillis(1)), scan("b", now));
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByAge(scans, 30, now);
    assertTrue(ids.isEmpty());
  }

  @Test
  public void byAge_oldScansRemoved() {
    long now = System.currentTimeMillis();
    CompletedScan old1 = scan("old1", now - TimeUnit.DAYS.toMillis(60));
    CompletedScan old2 = scan("old2", now - TimeUnit.DAYS.toMillis(31));
    CompletedScan recent = scan("recent", now - TimeUnit.DAYS.toMillis(5));
    // Ordered newest first
    List<CompletedScan> scans = Arrays.asList(recent, old2, old1);
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByAge(scans, 30, now);
    assertEquals(2, ids.size());
    assertTrue(ids.contains("old1"));
    assertTrue(ids.contains("old2"));
  }

  @Test
  public void byAge_exactBoundary_notRemoved() {
    long now = System.currentTimeMillis();
    // Scan created exactly 30 days ago (cutoff = now - 30d; createdAt == cutoff → not < cutoff)
    CompletedScan boundary = scan("boundary", now - TimeUnit.DAYS.toMillis(30));
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByAge(List.of(boundary), 30, now);
    assertTrue(ids.isEmpty());
  }

  // ---- MAX_COUNT tests ----

  @Test
  public void byCount_emptyList_returnsEmpty() {
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByCount(Collections.emptyList(), 5);
    assertTrue(ids.isEmpty());
  }

  @Test
  public void byCount_underLimit_noneRemoved() {
    List<CompletedScan> scans = Arrays.asList(scan("a", 3), scan("b", 2), scan("c", 1));
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByCount(scans, 5);
    assertTrue(ids.isEmpty());
  }

  @Test
  public void byCount_overLimit_oldestRemoved() {
    // Newest first: a(5), b(4), c(3), d(2), e(1)
    List<CompletedScan> scans =
        Arrays.asList(scan("a", 5), scan("b", 4), scan("c", 3), scan("d", 2), scan("e", 1));
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByCount(scans, 3);
    assertEquals(2, ids.size());
    assertEquals("d", ids.get(0));
    assertEquals("e", ids.get(1));
  }

  @Test
  public void byCount_exactLimit_noneRemoved() {
    List<CompletedScan> scans = Arrays.asList(scan("a", 2), scan("b", 1));
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByCount(scans, 2);
    assertTrue(ids.isEmpty());
  }

  // ---- MAX_STORAGE tests ----

  @Test
  public void byStorage_emptyList_returnsEmpty() {
    List<String> ids =
        CompletedScansCleanupPolicy.idsToRemoveByStorage(
            Collections.emptyList(), Collections.emptyMap(), 1000);
    assertTrue(ids.isEmpty());
  }

  @Test
  public void byStorage_underLimit_noneRemoved() {
    List<CompletedScan> scans = Arrays.asList(scan("a", 2), scan("b", 1));
    Map<String, Long> sizes = new HashMap<>();
    sizes.put("a", 100L);
    sizes.put("b", 200L);
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByStorage(scans, sizes, 500);
    assertTrue(ids.isEmpty());
  }

  @Test
  public void byStorage_overLimit_oldestRemovedFirst() {
    // Newest first: a(3), b(2), c(1)
    List<CompletedScan> scans = Arrays.asList(scan("a", 3), scan("b", 2), scan("c", 1));
    Map<String, Long> sizes = new HashMap<>();
    sizes.put("a", 300L);
    sizes.put("b", 200L);
    sizes.put("c", 100L);
    // Total = 600, limit = 400 → need to free 200 → remove c(100) then b(200) → total 300
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByStorage(scans, sizes, 400);
    // c is oldest, removed first; after removing c total=500 still >400, so b removed too
    assertEquals(2, ids.size());
    assertEquals("c", ids.get(0));
    assertEquals("b", ids.get(1));
  }

  @Test
  public void byStorage_removesJustEnough() {
    List<CompletedScan> scans = Arrays.asList(scan("a", 2), scan("b", 1));
    Map<String, Long> sizes = new HashMap<>();
    sizes.put("a", 300L);
    sizes.put("b", 300L);
    // Total = 600, limit = 350 → remove b(oldest, 300) → total 300 ≤ 350 → done
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByStorage(scans, sizes, 350);
    assertEquals(1, ids.size());
    assertEquals("b", ids.get(0));
  }

  @Test
  public void byStorage_exactLimit_noneRemoved() {
    List<CompletedScan> scans = Arrays.asList(scan("a", 2), scan("b", 1));
    Map<String, Long> sizes = new HashMap<>();
    sizes.put("a", 250L);
    sizes.put("b", 250L);
    // Total = 500, limit = 500 → exactly at limit → nothing removed
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByStorage(scans, sizes, 500);
    assertTrue(ids.isEmpty());
  }

  @Test
  public void byStorage_allRemoved_whenAllExceedLimit() {
    List<CompletedScan> scans = Arrays.asList(scan("a", 3), scan("b", 2), scan("c", 1));
    Map<String, Long> sizes = new HashMap<>();
    sizes.put("a", 500L);
    sizes.put("b", 500L);
    sizes.put("c", 500L);
    // Total = 1500, limit = 0 → all must be removed
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByStorage(scans, sizes, 0);
    assertEquals(3, ids.size());
    // Oldest first: c, b, a
    assertEquals("c", ids.get(0));
    assertEquals("b", ids.get(1));
    assertEquals("a", ids.get(2));
  }

  @Test
  public void byStorage_missingSizeEntry_treatedAsZero() {
    List<CompletedScan> scans = Arrays.asList(scan("a", 2), scan("b", 1));
    Map<String, Long> sizes = new HashMap<>();
    sizes.put("a", 600L);
    // b has no size entry → treated as 0
    // Total = 600, limit = 500 → remove b(0) → total still 600 > 500 → remove a(600) → total 0
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByStorage(scans, sizes, 500);
    assertEquals(2, ids.size());
    assertEquals("b", ids.get(0));
    assertEquals("a", ids.get(1));
  }

  // ---- MAX_AGE additional edge cases ----

  @Test
  public void byAge_allOld_allRemoved() {
    long now = System.currentTimeMillis();
    List<CompletedScan> scans =
        Arrays.asList(
            scan("a", now - TimeUnit.DAYS.toMillis(100)),
            scan("b", now - TimeUnit.DAYS.toMillis(200)));
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByAge(scans, 30, now);
    assertEquals(2, ids.size());
  }

  @Test
  public void byAge_singleScan_recent_notRemoved() {
    long now = System.currentTimeMillis();
    List<String> ids =
        CompletedScansCleanupPolicy.idsToRemoveByAge(List.of(scan("x", now)), 1, now);
    assertTrue(ids.isEmpty());
  }

  @Test
  public void byAge_zeroDaysMaxAge_removesAllExceptNow() {
    long now = System.currentTimeMillis();
    CompletedScan justNow = scan("now", now);
    CompletedScan oneSecAgo = scan("old", now - 1000);
    List<String> ids =
        CompletedScansCleanupPolicy.idsToRemoveByAge(Arrays.asList(justNow, oneSecAgo), 0, now);
    // cutoff = now - 0 = now; createdAt < now → oneSecAgo removed; justNow not (not < now)
    assertEquals(1, ids.size());
    assertEquals("old", ids.get(0));
  }

  // ---- MAX_COUNT additional edge cases ----

  @Test
  public void byCount_maxCountZero_allRemoved() {
    List<CompletedScan> scans = Arrays.asList(scan("a", 2), scan("b", 1));
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByCount(scans, 0);
    assertEquals(2, ids.size());
    assertEquals("a", ids.get(0));
    assertEquals("b", ids.get(1));
  }

  @Test
  public void byCount_maxCountOne_keepsOnlyNewest() {
    List<CompletedScan> scans = Arrays.asList(scan("a", 3), scan("b", 2), scan("c", 1));
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByCount(scans, 1);
    assertEquals(2, ids.size());
    assertEquals("b", ids.get(0));
    assertEquals("c", ids.get(1));
  }

  @Test
  public void byCount_singleScan_maxCountOne_noneRemoved() {
    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveByCount(List.of(scan("a", 1)), 1);
    assertTrue(ids.isEmpty());
  }

  // ---- idsToRemoveFromLibrary tests ----
  // Regression coverage for a bug where the library reindex after a completed-scans cleanup
  // deleted every "real" exported document, because those are indexed with a freshly generated
  // id that is never present in the CompletedScansRegistry. Only registry-sourced entries
  // (marked via sourceMetaJson as "CompletedScanEntry") may be swept up here.

  private static ScanEntity completedScanEntryEntity(String id) {
    return new ScanEntity(id, "title", 0L, 1, null, null, "{\"kind\":\"CompletedScanEntry\"}");
  }

  private static ScanEntity realExportedDocument(String id) {
    // Real exports are indexed with sourceMetaJson == null (see ScanLibraryIndexer).
    return new ScanEntity(id, "My Document", 0L, 3, null, "[\"content://export/doc.pdf\"]", null);
  }

  @Test
  public void library_realExportedDocuments_neverRemoved_evenIfNotInRegistry() {
    List<ScanEntity> allScans =
        Arrays.asList(realExportedDocument("doc-1"), realExportedDocument("doc-2"));
    // Registry only ever contains per-page ids, never a document's freshly generated id.
    Set<String> registryIds = Collections.emptySet();

    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveFromLibrary(allScans, registryIds);

    assertTrue(ids.isEmpty());
  }

  @Test
  public void library_completedScanEntry_removedWhenNoLongerInRegistry() {
    List<ScanEntity> allScans = List.of(completedScanEntryEntity("page-1"));
    Set<String> registryIds = Collections.emptySet();

    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveFromLibrary(allScans, registryIds);

    assertEquals(1, ids.size());
    assertEquals("page-1", ids.get(0));
  }

  @Test
  public void library_completedScanEntry_keptWhenStillInRegistry() {
    List<ScanEntity> allScans = List.of(completedScanEntryEntity("page-1"));
    Set<String> registryIds = new HashSet<>(List.of("page-1"));

    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveFromLibrary(allScans, registryIds);

    assertTrue(ids.isEmpty());
  }

  @Test
  public void library_mixedEntries_onlyRegistrySourcedAndStaleOnesRemoved() {
    List<ScanEntity> allScans =
        Arrays.asList(
            realExportedDocument("doc-1"), // must survive: real document, never in registry
            completedScanEntryEntity("page-1"), // stale: no longer in registry -> removed
            completedScanEntryEntity("page-2") // still current -> kept
            );
    Set<String> registryIds = new HashSet<>(List.of("page-2"));

    List<String> ids = CompletedScansCleanupPolicy.idsToRemoveFromLibrary(allScans, registryIds);

    assertEquals(1, ids.size());
    assertEquals("page-1", ids.get(0));
  }

  @Test
  public void library_emptyScanList_returnsEmpty() {
    List<String> ids =
        CompletedScansCleanupPolicy.idsToRemoveFromLibrary(
            Collections.emptyList(), Collections.emptySet());
    assertTrue(ids.isEmpty());
  }

  @Test
  public void library_nullSourceMetaJson_treatedAsRealDocument_notRemoved() {
    ScanEntity entity = new ScanEntity("doc-1", "title", 0L, 1, null, null, null);
    List<String> ids =
        CompletedScansCleanupPolicy.idsToRemoveFromLibrary(
            List.of(entity), Collections.emptySet());
    assertTrue(ids.isEmpty());
  }
}
