/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.data.library;

import android.content.Context;
import java.util.List;

/**
 * Repository interface for managing and retrieving scan data. This interface provides methods to
 * interact with scan entities, including indexing, querying, updating, and deleting scans. It
 * serves as an abstraction layer to access scan-related operations, typically backed by a database
 * or other persistent storage.
 *
 * <p>Methods:
 *
 * <p>- indexExportedScan(Context context, ScanIndexMeta meta): Indexes a newly exported scan into
 * the repository. This operation is used to register or update the scan's metadata and persist it
 * for future retrieval.
 *
 * <p>- getAllScans(Context context): Retrieves all scan entities available in the repository. This
 * method is useful for obtaining a list of all scans, typically ordered by their creation or
 * modification time.
 *
 * <p>- getScansForCollection(Context context, String collectionId): Retrieves a list of scans
 * associated with a specific collection. This method is intended for querying scans grouped under a
 * particular collection identifier.
 *
 * <p>- getScanById(Context context, String id): Retrieves a single scan entity using its unique
 * identifier. This method is used to load specific scan details by its ID.
 *
 * <p>- deleteScan(Context context, String id): Deletes a scan record from the repository by its
 * unique identifier. This method is used to remove a scan permanently.
 *
 * <p>- updateTitle(Context context, String id, String newTitle): Updates the title of an existing
 * scan specified by its unique identifier. This method is used to modify the human-readable title
 * associated with a scan.
 */
public interface ScansRepository {
  void indexExportedScan(Context context, ScanIndexMeta meta);

  List<ScanEntity> getAllScans(Context context);

  List<ScanEntity> getScansForCollection(Context context, String collectionId);

  ScanEntity getScanById(Context context, String id);

  void reindexOcrText(Context context, String id);

  List<ScanSearchResult> searchOcrText(Context context, String query, int limit);

  void deleteScan(Context context, String id);

  /**
   * Deletes multiple scan records (and their backing files, best-effort) by id. Equivalent to
   * calling {@link #deleteScan(Context, String)} for each id, but as a single call for callers
   * that need to delete a batch (e.g., multi-select in the Scans Library).
   */
  void deleteScans(Context context, List<String> ids);

  void updateTitle(Context context, String id, String newTitle);

  /**
   * Updates the stored export paths JSON for a scan (e.g., to repair or replace the primary URI).
   */
  void updateExportPathsJson(Context context, String id, String exportPathsJson);
}
