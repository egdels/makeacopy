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

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.sqlite.db.SimpleSQLiteQuery;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import org.json.JSONArray;

/**
 * DefaultScansRepository is a concrete implementation of the ScansRepository interface. It provides
 * access to the application's scan-related data and operations by interacting with the local Room
 * database via DAOs. This repository layer abstracts database interactions for clients needing to
 * manage or retrieve scans.
 *
 * <p>Features: - Idempotent creation or updating of scan records in the database. - Retrieval of
 * all scans or scans filtered by collection IDs. - Deletion of scan records, including their
 * associations with collections. - Updating scan titles in the database.
 *
 * <p>Error Handling: - Catches throwable exceptions and logs errors to avoid application crashes
 * during database operations. Defaults are returned where appropriate (e.g., empty lists or nulls).
 */
public class DefaultScansRepository implements ScansRepository {
  private static final String TAG = "ScansRepo";
  private static final SimpleSQLiteQuery OCR_FTS_TABLE_EXISTS_QUERY =
      new SimpleSQLiteQuery(
          "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'scan_page_text_fts')");

  private final ScansDao scansDao;
  private final ScanCollectionJoinDao joinDao;
  private final ScanPageTextDao scanPageTextDao;
  private final OcrSearchIndexer ocrSearchIndexer;
  private Integer ocrFtsType; // null=unknown, 0=none, 4=FTS4, 5=FTS5

  @Inject
  public DefaultScansRepository(
      ScansDao scansDao, ScanCollectionJoinDao joinDao, ScanPageTextDao scanPageTextDao) {
    this(scansDao, joinDao, scanPageTextDao, new OcrSearchIndexer(scanPageTextDao));
  }

  public DefaultScansRepository(
      ScansDao scansDao,
      ScanCollectionJoinDao joinDao,
      ScanPageTextDao scanPageTextDao,
      OcrSearchIndexer ocrSearchIndexer) {
    this.scansDao = scansDao;
    this.joinDao = joinDao;
    this.scanPageTextDao = scanPageTextDao;
    this.ocrSearchIndexer = ocrSearchIndexer;
  }

  /**
   * Indexes or updates the metadata of an exported scan in the database. This method ensures that
   * the scan represented by the given metadata is persisted in the database. If the scan already
   * exists, its basic fields are updated with any new information provided in the metadata. If the
   * scan does not exist, it is inserted as a new entry.
   *
   * @param context the application context used to access the database
   * @param meta the metadata of the scan to be indexed or updated, represented as a {@code
   *     ScanIndexMeta}
   */
  @Override
  public void indexExportedScan(Context context, ScanIndexMeta meta) {
    try {
      // Idempotent-ish: check if exists
      ScanEntity existing = scansDao.getById(meta.id());
      if (existing == null) {
        ScanEntity entity =
            new ScanEntity(
                meta.id(),
                meta.title(),
                meta.createdAt(),
                meta.pageCount(),
                meta.coverPath(),
                meta.exportPathsJson(),
                meta.sourceMetaJson());
        scansDao.insert(entity);
        ocrSearchIndexer.indexScan(context, entity);
      } else {
        // Update basic fields
        existing.title = meta.title() != null ? meta.title() : existing.title;
        existing.createdAt = existing.createdAt == 0 ? meta.createdAt() : existing.createdAt;
        existing.pageCount = Math.max(existing.pageCount, meta.pageCount());
        if (meta.coverPath() != null) existing.coverPath = meta.coverPath();
        if (meta.exportPathsJson() != null) existing.exportPathsJson = meta.exportPathsJson();
        if (meta.sourceMetaJson() != null) existing.sourceMetaJson = meta.sourceMetaJson();
        scansDao.update(existing);
        ocrSearchIndexer.indexScan(context, existing);
      }
    } catch (Throwable t) {
      Log.e(TAG, "indexExportedScan failed", t);
    }
  }

  /**
   * Retrieves all saved scans from the database in descending order of their creation timestamp.
   * This method returns an empty list if the query fails or if no scans exist in the database.
   *
   * @param context the application context used to access the database.
   * @return a list of {@code ScanEntity} objects representing all saved scans from the database.
   *     Returns an empty list if the query fails or no scans are found.
   */
  @Override
  public List<ScanEntity> getAllScans(Context context) {
    try {
      return scansDao.getAll();
    } catch (Throwable t) {
      Log.e(TAG, "getAllScans failed", t);
      return java.util.Collections.emptyList();
    }
  }

  /**
   * Retrieves all scans associated with a specific collection from the database. If the query fails
   * or no scans are found for the given collection, an empty list is returned.
   *
   * @param context the application context used to access the database
   * @param collectionId the unique identifier of the collection for which scans are being retrieved
   * @return a list of {@code ScanEntity} objects associated with the specified collection. Returns
   *     an empty list if the query fails or no scans are found for the given collection.
   */
  @Override
  public List<ScanEntity> getScansForCollection(Context context, String collectionId) {
    try {
      return scansDao.getAllByCollection(collectionId);
    } catch (Throwable t) {
      Log.e(TAG, "getScansForCollection failed", t);
      return java.util.Collections.emptyList();
    }
  }

  /**
   * Retrieves a specific scan from the database using its unique identifier. If the query fails or
   * the scan is not found, this method returns null.
   *
   * @param context the application context used to access the database
   * @param id the unique identifier of the scan to be retrieved
   * @return the {@code ScanEntity} associated with the given identifier, or null if the query fails
   *     or the scan cannot be found
   */
  @Override
  public ScanEntity getScanById(Context context, String id) {
    try {
      return scansDao.getById(id);
    } catch (Throwable t) {
      Log.e(TAG, "getScanById failed", t);
      return null;
    }
  }

  @Override
  public void reindexOcrText(Context context, String id) {
    try {
      ScanEntity entity = scansDao.getById(id);
      if (entity != null) ocrSearchIndexer.indexScan(context, entity);
    } catch (Throwable t) {
      Log.e(TAG, "reindexOcrText failed", t);
    }
  }

  @Override
  public List<ScanSearchResult> searchOcrText(Context context, String query, int limit) {
    try {
      String ftsQuery = ScanSearchQueries.sanitizeFtsQuery(query);
      if (ftsQuery.isEmpty()) return java.util.Collections.emptyList();
      if (getOcrFtsType() == 0) return searchOcrTextFallback(query, limit);
      return scanPageTextDao.searchOcr(
          ScanSearchQueries.ocrSearch(ftsQuery, Math.max(1, limit), getOcrFtsType() == 5));
    } catch (Throwable t) {
      if (AppDatabase.isMissingFtsModule(t) || AppDatabase.isMissingScanPageTextFts(t)) {
        ocrFtsType = 0;
        return searchOcrTextFallback(query, limit);
      }
      Log.e(TAG, "searchOcrText failed", t);
      return java.util.Collections.emptyList();
    }
  }

  private List<ScanSearchResult> searchOcrTextFallback(String query, int limit) {
    try {
      String likeQuery = ScanSearchQueries.sanitizeLikeQuery(query);
      if ("%%".equals(likeQuery)) return java.util.Collections.emptyList();
      return scanPageTextDao.searchOcrFallback(
          ScanSearchQueries.ocrFallbackSearch(query, Math.max(1, limit)));
    } catch (Throwable t) {
      Log.e(TAG, "searchOcrTextFallback failed", t);
      return java.util.Collections.emptyList();
    }
  }

  private int getOcrFtsType() {
    if (ocrFtsType != null) return ocrFtsType;
    try {
      if (scanPageTextDao.hasOcrFtsTable(OCR_FTS_TABLE_EXISTS_QUERY) == 0) {
        ocrFtsType = 0;
      } else {
        // Table exists, check if it is FTS5
        try {
          // We use a query that will fail if FTS5 is not available or if the table is FTS4
          // FTS5-specific functions like bm25() will throw an exception during compilation of the
          // query if not available.
          // Note: using a RawQuery here to avoid Room's pre-compilation check.
          scanPageTextDao.hasOcrFtsTable(
              new SimpleSQLiteQuery(
                  "SELECT 1 FROM scan_page_text_fts WHERE scan_page_text_fts MATCH 'test' AND bm25(scan_page_text_fts) IS NOT NULL LIMIT 0"));
          ocrFtsType = 5;
        } catch (Throwable t) {
          Log.i(TAG, "FTS5 functions unavailable, treating as FTS4: " + t.getMessage());
          ocrFtsType = 4;
        }
      }
    } catch (Throwable t) {
      if (AppDatabase.isMissingFtsModule(t) || AppDatabase.isMissingScanPageTextFts(t)) {
        ocrFtsType = 0;
        return 0;
      }
      Log.e(TAG, "Checking OCR FTS availability failed", t);
      ocrFtsType = 0;
    }
    return ocrFtsType;
  }

  /**
   * Deletes a scan with the specified unique identifier from the database, including associated
   * data and physical artifacts. This method performs cleanup on related entries, files, and joins
   * associated with the scan to ensure data integrity and avoid orphaned references or artifacts.
   *
   * @param context the application context used to access the underlying database and perform
   *     cleanup operations
   * @param id the unique identifier of the scan to be deleted from the database
   */
  @Override
  public void deleteScan(Context context, String id) {
    try {
      ScanEntity entity = null;
      try {
        entity = scansDao.getById(id);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }

      // Best-effort: If this scan originated from the app's registry (incrementally indexed),
      // remove its physical artifacts under files/scans/<id> and drop the registry entry.
      try {
        de.schliweb.makeacopy.data.RegistryCleaner.removeEntryAndFiles(
            context.getApplicationContext(), id);
      } catch (Throwable ignoreCleanup) {
        // keep delete robust; physical cleanup is best-effort
      }

      // Best-effort: Delete exported files referenced by this scan (file://, content://, or
      // absolute paths).
      if (entity != null) {
        try {
          bestEffortDeleteBackedFiles(context.getApplicationContext(), entity);
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }

      // Remove any joins first to respect FK constraints if added later
      joinDao.removeAllForScan(id);
      // scanPageText entries are removed automatically via ForeignKey CASCADE on scanId
      scansDao.deleteById(id);
    } catch (Throwable t) {
      Log.e(TAG, "deleteScan failed", t);
    }
  }

  @Override
  public void deleteScans(Context context, List<String> ids) {
    if (ids == null || ids.isEmpty()) return;
    // Copy defensively: callers may pass a live, mutable selection set/list.
    for (String id : new java.util.ArrayList<>(ids)) {
      if (id == null) continue;
      deleteScan(context, id);
    }
  }

  private void bestEffortDeleteBackedFiles(Context ctx, ScanEntity e) {
    if (ctx == null || e == null) return;
    Set<String> seen = new HashSet<>();

    // delete all export paths
    try {
      if (e.exportPathsJson != null && !e.exportPathsJson.isEmpty()) {
        JSONArray arr = new JSONArray(e.exportPathsJson);
        for (int i = 0; i < arr.length(); i++) {
          String s = arr.optString(i, null);
          deleteOnePathOrUri(ctx, s, seen);
        }
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }

    // delete cover path if present (may be a file path or uri) and not already deleted
    deleteOnePathOrUri(ctx, e.coverPath, seen);
  }

  private void deleteOnePathOrUri(Context ctx, String pathOrUri, Set<String> seen) {
    if (pathOrUri == null || pathOrUri.isEmpty()) return;
    if (seen != null) {
      if (seen.contains(pathOrUri)) return;
      seen.add(pathOrUri);
    }
    try {
      if (pathOrUri.startsWith("content://")) {
        Uri u = Uri.parse(pathOrUri);
        ContentResolver cr = ctx.getContentResolver();
        try {
          cr.delete(u, null, null);
        } catch (SecurityException se) {
          // No permission to delete; ignore to keep operation robust
        }
      } else if (pathOrUri.startsWith("file://")) {
        Uri u = Uri.parse(pathOrUri);
        String path = u.getPath();
        if (path != null) {
          File f = new File(path);
          if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
          }
        }
      } else {
        // treat as plain filesystem path
        File f = new File(pathOrUri);
        if (f.exists()) {
          //noinspection ResultOfMethodCallIgnored
          f.delete();
        }
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  @Override
  public void updateTitle(Context context, String id, String newTitle) {
    try {
      ScanEntity e = scansDao.getById(id);
      if (e != null) {
        e.title = newTitle;
        scansDao.update(e);
      }
    } catch (Throwable t) {
      Log.e(TAG, "updateTitle failed", t);
    }
  }

  @Override
  public void updateExportPathsJson(Context context, String id, String exportPathsJson) {
    try {
      ScanEntity e = scansDao.getById(id);
      if (e != null) {
        e.exportPathsJson = exportPathsJson;
        scansDao.update(e);
      }
    } catch (Throwable t) {
      Log.e(TAG, "updateExportPathsJson failed", t);
    }
  }
}
