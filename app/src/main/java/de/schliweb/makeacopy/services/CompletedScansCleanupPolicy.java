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

import de.schliweb.makeacopy.data.library.ScanEntity;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Pure logic for determining which completed scans should be removed according to a given policy.
 * This class is free of Android dependencies so it can be covered by JVM unit tests.
 */
public final class CompletedScansCleanupPolicy {

  private CompletedScansCleanupPolicy() {}

  /**
   * Returns the IDs of scans that should be removed under the MAX_AGE rule.
   *
   * @param scans list ordered by date descending (newest first)
   * @param maxAgeDays maximum age in days
   * @param nowMillis current time in millis
   */
  public static List<String> idsToRemoveByAge(
      List<CompletedScan> scans, int maxAgeDays, long nowMillis) {
    long cutoff = nowMillis - TimeUnit.DAYS.toMillis(maxAgeDays);
    List<String> result = new ArrayList<>();
    for (CompletedScan s : scans) {
      if (s.createdAt() < cutoff) {
        result.add(s.id());
      }
    }
    return result;
  }

  /**
   * Returns the IDs of scans that should be removed under the MAX_COUNT rule.
   *
   * @param scans list ordered by date descending (newest first)
   * @param maxCount maximum number of scans to keep
   */
  public static List<String> idsToRemoveByCount(List<CompletedScan> scans, int maxCount) {
    List<String> result = new ArrayList<>();
    if (scans.size() > maxCount) {
      for (int i = maxCount; i < scans.size(); i++) {
        result.add(scans.get(i).id());
      }
    }
    return result;
  }

  /**
   * Returns the IDs of scans that should be removed under the MAX_STORAGE rule. Removes oldest
   * first until total size is within the limit.
   *
   * @param scans list ordered by date descending (newest first)
   * @param sizeById map from scan id to its size in bytes
   * @param maxStorageBytes maximum total storage in bytes
   */
  public static List<String> idsToRemoveByStorage(
      List<CompletedScan> scans, Map<String, Long> sizeById, long maxStorageBytes) {
    long totalSize = 0;
    for (CompletedScan s : scans) {
      totalSize += sizeById.getOrDefault(s.id(), 0L);
    }
    List<String> result = new ArrayList<>();
    if (totalSize <= maxStorageBytes) return result;
    // Delete oldest first
    List<CompletedScan> ascending = new ArrayList<>(scans);
    Collections.reverse(ascending);
    for (CompletedScan s : ascending) {
      if (totalSize <= maxStorageBytes) break;
      totalSize -= sizeById.getOrDefault(s.id(), 0L);
      result.add(s.id());
    }
    return result;
  }

  /**
   * Returns the IDs of Room scan-library entries that should be removed while re-indexing after a
   * completed-scans cleanup pass.
   *
   * <p>Only entries that were themselves sourced from the {@code CompletedScansRegistry} (single
   * page items indexed by {@code ExistingScansIndexer}, marked via {@code sourceMetaJson}
   * containing {@code "CompletedScanEntry"}) and are no longer present in the registry are
   * eligible. Real exported library documents are indexed separately at export time (see {@code
   * ScanLibraryIndexer}) with a freshly generated id that is never present in the registry, so
   * they must never be removed here just because their id isn't a registry id.
   *
   * @param allScans all current Room scan-library entries
   * @param registryIds ids currently present in the CompletedScansRegistry
   */
  public static List<String> idsToRemoveFromLibrary(
      List<ScanEntity> allScans, Set<String> registryIds) {
    List<String> result = new ArrayList<>();
    for (ScanEntity se : allScans) {
      if (se == null || se.id == null) continue;
      boolean isCompletedScanEntry =
          se.sourceMetaJson != null && se.sourceMetaJson.contains("\"CompletedScanEntry\"");
      if (isCompletedScanEntry && !registryIds.contains(se.id)) {
        result.add(se.id);
      }
    }
    return result;
  }
}
