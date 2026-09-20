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
import android.util.Log;
import de.schliweb.makeacopy.utils.ui.AppLanguage;
import java.util.List;
import java.util.UUID;
import javax.inject.Inject;

/**
 * Default implementation of the CollectionsRepository interface. Provides concrete methods to
 * manage collections within the application, supporting operations related to collection creation,
 * retrieval, modification, and deletion, as well as managing associations between scans and
 * collections.
 *
 * <p>This class interacts with the Room persistence library through DAO (Data Access Object)
 * interfaces to perform database operations such as insertions, deletions, and queries. It uses a
 * singleton instance of the AppDatabase to avoid redundant instances of the database.
 *
 * <p>Key Features: - Create a new collection and store it in the database. - Retrieve all
 * collections stored in the database. - Associate a scan with a collection or remove a specific
 * association. - Delete a collection only if it contains no scans. - Count the number of scans
 * within a given collection.
 *
 * <p>Error Handling: For each database operation, exceptions are caught and logged to facilitate
 * debugging, and an appropriate fallback behavior (e.g., returning null or an empty list) is
 * implemented to handle errors gracefully.
 *
 * <p>Thread Safety: Database operations are thread-safe due to the Room library's thread-safety
 * guarantees.
 */
public class DefaultCollectionsRepository implements CollectionsRepository {
  private static final String TAG = "CollectionsRepo";

  private final CollectionsDao collectionsDao;
  private final ScanCollectionJoinDao joinDao;
  private final ScansDao scansDao;

  @Inject
  public DefaultCollectionsRepository(
      CollectionsDao collectionsDao, ScanCollectionJoinDao joinDao, ScansDao scansDao) {
    this.collectionsDao = collectionsDao;
    this.joinDao = joinDao;
    this.scansDao = scansDao;
  }

  private static boolean isDefaultCollection(CollectionEntity e) {
    return e != null && DEFAULT_COLLECTION_ID.equals(e.id);
  }

  private static boolean isDefaultCollectionId(String collectionId) {
    return DEFAULT_COLLECTION_ID.equals(collectionId);
  }

  @Override
  public CollectionEntity createCollection(Context context, String name) {
    try {
      // Normalize input
      String trimmed = (name == null) ? null : name.trim();
      if (trimmed == null || trimmed.isEmpty()) return null;
      // If the requested name is the reserved default name, return the (existing or newly created)
      // default collection instead of creating a duplicate.
      try {
        String defName = defaultName(context.getApplicationContext());
        if (defName != null && defName.equalsIgnoreCase(trimmed)) {
          return getOrCreateDefaultCompletedCollection(context);
        }
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      int nextOrder = collectionsDao.getAll().size();
      CollectionEntity entity =
          new CollectionEntity(
              UUID.randomUUID().toString(), trimmed, nextOrder, System.currentTimeMillis());
      collectionsDao.insert(entity);
      return entity;
    } catch (Throwable t) {
      Log.e(TAG, "createCollection failed", t);
      return null;
    }
  }

  /**
   * Retrieves all collections from the database ordered by their sort order and creation timestamp.
   * If an error occurs during the database operation, an empty list is returned.
   *
   * @param context the Context of the application or activity, required to initialize the database.
   * @return a list of {@code CollectionEntity} objects representing all collections in the
   *     database.
   */
  @Override
  public List<CollectionEntity> getAllCollections(Context context) {
    try {
      return collectionsDao.getAll();
    } catch (Throwable t) {
      Log.e(TAG, "getAllCollections failed", t);
      return java.util.Collections.emptyList();
    }
  }

  /**
   * Assigns a scan to a collection by creating a relationship between the specified scan and
   * collection in the database. This method inserts a record into the {@code scan_collection_join}
   * table, associating the given scan and collection IDs with the current timestamp.
   *
   * @param context the application or activity context, required to initialize the database
   *     instance.
   * @param scanId the unique identifier of the scan to be added to the collection.
   * @param collectionId the unique identifier of the collection to which the scan is to be
   *     assigned.
   */
  @Override
  public void assignScanToCollection(Context context, String scanId, String collectionId) {
    try {
      // Guard: Default "Completed Scans" collection is ONLY for CompletedScanEntry items.
      // If target is the default collection, verify the scan has the marker in sourceMetaJson.
      try {
        if (isDefaultCollectionId(collectionId)) {
          try {
            if (!isCompletedScanEntry(scanId)) {
              android.util.Log.i(
                  TAG,
                  "assignScanToCollection: blocked assigning finished document to default Completed Scans collection");
              return; // silently ignore
            }
          } catch (Throwable ignore) {
            // If we cannot verify, be conservative and block assignment.
            android.util.Log.i(
                TAG,
                "assignScanToCollection: could not verify scan type, blocking assignment to default collection");
            return;
          }
        }
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      joinDao.insert(new ScanCollectionCrossRef(scanId, collectionId, System.currentTimeMillis()));
    } catch (Throwable t) {
      Log.e(TAG, "assignScanToCollection failed", t);
    }
  }

  /**
   * Removes the association between a specific scan and a collection from the database.
   *
   * <p>This method deletes the relationship in the {@code scan_collection_join} table for the
   * provided scan and collection identifiers.
   *
   * @param context the application or activity context, required to initialize the database
   *     instance.
   * @param scanId the unique identifier of the scan to be dissociated from the collection.
   * @param collectionId the unique identifier of the collection from which the scan is to be
   *     removed.
   */
  @Override
  public void removeScanFromCollection(Context context, String scanId, String collectionId) {
    try {
      joinDao.remove(scanId, collectionId);
    } catch (Throwable t) {
      Log.e(TAG, "removeScanFromCollection failed", t);
    }
  }

  /**
   * Deletes a collection from the database if it contains no items. This method checks the item
   * count for the collection and removes the collection only if the count is zero.
   *
   * @param context the application or activity context, used to initialize the database.
   * @param collectionId the unique identifier of the collection to be deleted.
   * @return {@code true} if the collection was empty and successfully deleted, {@code false}
   *     otherwise.
   */
  @Override
  public boolean deleteCollectionIfEmpty(Context context, String collectionId) {
    try {
      // Guard: default collection cannot be deleted at all
      if (isDefaultCollectionId(collectionId)) return false;
      int count = collectionsDao.countItems(collectionId);
      if (count > 0) return false;
      collectionsDao.deleteById(collectionId);
      return true;
    } catch (Throwable t) {
      Log.e(TAG, "deleteCollectionIfEmpty failed", t);
      return false;
    }
  }

  @Override
  public int countItems(Context context, String collectionId) {
    try {
      return collectionsDao.countItems(collectionId);
    } catch (Throwable t) {
      Log.e(TAG, "countItems failed", t);
      return 0;
    }
  }

  @Override
  public boolean renameCollection(Context context, String collectionId, String newName) {
    try {
      if (newName == null) return false;
      String trimmed = newName.trim();
      if (trimmed.isEmpty()) return false;
      CollectionEntity e = collectionsDao.getById(collectionId);
      if (e == null) return false;
      // Guard: default collection cannot be renamed (by ID)
      if (isDefaultCollection(e)) return false;
      // Guard: do not allow renaming another collection to the reserved default name
      try {
        String defName = defaultName(context.getApplicationContext());
        if (defName != null && defName.equalsIgnoreCase(trimmed)) {
          return false;
        }
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      e.name = trimmed;
      collectionsDao.update(e);
      return true;
    } catch (Throwable t) {
      Log.e(TAG, "renameCollection failed", t);
      return false;
    }
  }

  @Override
  @SuppressWarnings("MixedMutabilityReturnType") // all paths return mutable lists
  public java.util.List<CollectionEntity> getCollectionsForScan(Context context, String scanId) {
    try {
      if (scanId == null) return new java.util.ArrayList<>();
      java.util.List<String> ids = joinDao.getCollectionIdsForScan(scanId);
      if (ids == null || ids.isEmpty()) return new java.util.ArrayList<>();
      java.util.ArrayList<CollectionEntity> list = new java.util.ArrayList<>();
      for (String id : ids) {
        try {
          CollectionEntity ce = collectionsDao.getById(id);
          if (ce != null) list.add(ce);
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
      // Optional: sort by sortOrder then name
      try {
        list.sort(
            (a, b) -> {
              int so = Integer.compare(a.sortOrder, b.sortOrder);
              if (so != 0) return so;
              String an = (a.name == null ? "" : a.name);
              String bn = (b.name == null ? "" : b.name);
              return an.compareToIgnoreCase(bn);
            });
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      return list;
    } catch (Throwable t) {
      Log.e(TAG, "getCollectionsForScan failed", t);
      return java.util.Collections.emptyList();
    }
  }

  @Override
  public CollectionEntity getOrCreateDefaultCompletedCollection(Context context) {
    try {
      Context app = context.getApplicationContext();
      return resolveDefaultCollection(defaultName(app), () -> defaultNamesInAllLanguages(app));
    } catch (Throwable t) {
      android.util.Log.e(TAG, "getOrCreateDefaultCompletedCollection failed", t);
      return null;
    }
  }

  /**
   * Finds the default collection by its fixed ID and creates it if needed. Installs from before the
   * fixed ID identified it by its translated name, so a language change produced a second default:
   * such a collection is adopted once (and duplicates of it merged) using {@code allNames}.
   *
   * @param currentName the default collection's name in the current language
   * @param allNames its name in every language of the app; only evaluated for the one-time adoption
   */
  synchronized CollectionEntity resolveDefaultCollection(
      String currentName, java.util.function.Supplier<List<String>> allNames) {
    CollectionEntity def = collectionsDao.getById(DEFAULT_COLLECTION_ID);
    if (def == null) {
      List<String> names = allNames.get();
      CollectionEntity legacy = findLegacyDefault(currentName, names);
      if (legacy != null) {
        collectionsDao.changeId(legacy.id, DEFAULT_COLLECTION_ID);
        def = collectionsDao.getById(DEFAULT_COLLECTION_ID);
      } else {
        def =
            new CollectionEntity(
                DEFAULT_COLLECTION_ID,
                currentName,
                collectionsDao.getAll().size(),
                System.currentTimeMillis());
        try {
          collectionsDao.insert(def);
        } catch (android.database.sqlite.SQLiteConstraintException e) {
          // Another repository instance created it in the meantime
          def = collectionsDao.getById(DEFAULT_COLLECTION_ID);
        }
      }
      mergeLegacyDuplicates(names);
    }
    // Show the default collection in the current language
    if (def != null
        && !currentName.equals(def.name)
        && collectionsDao.getByName(currentName) == null) {
      def.name = currentName;
      collectionsDao.update(def);
    }
    return def;
  }

  /** The former name-based default: prefers the current language, then the fullest candidate. */
  private CollectionEntity findLegacyDefault(String currentName, List<String> names) {
    CollectionEntity current = collectionsDao.getByName(currentName);
    if (current != null) return current;
    CollectionEntity best = null;
    int bestCount = -1;
    for (CollectionEntity c : collectionsDao.getAll()) {
      if (!names.contains(c.name)) continue;
      int count = collectionsDao.countItems(c.id);
      if (count > bestCount) {
        best = c;
        bestCount = count;
      }
    }
    return best;
  }

  /**
   * Merges leftover defaults from earlier language changes into the default collection. Only
   * collections holding nothing but completed-scan entries qualify — the default accepts no other
   * items, so anything else was created by the user and is left alone.
   */
  private void mergeLegacyDuplicates(List<String> names) {
    for (CollectionEntity c : collectionsDao.getAll()) {
      if (DEFAULT_COLLECTION_ID.equals(c.id) || !names.contains(c.name)) continue;
      boolean onlyCompletedScans = true;
      for (String scanId : joinDao.getScanIdsForCollection(c.id)) {
        if (!isCompletedScanEntry(scanId)) {
          onlyCompletedScans = false;
          break;
        }
      }
      if (onlyCompletedScans) {
        Log.i(TAG, "Merging duplicate default collection '" + c.name + "'");
        collectionsDao.mergeInto(c.id, DEFAULT_COLLECTION_ID);
      }
    }
  }

  private boolean isCompletedScanEntry(String scanId) {
    ScanEntity se = scansDao.getById(scanId);
    String sm = (se != null) ? se.sourceMetaJson : null;
    return sm != null && sm.contains("\"CompletedScanEntry\"");
  }

  private static String defaultName(Context app) {
    return AppLanguage.localize(app)
        .getString(de.schliweb.makeacopy.R.string.collection_completed_scans);
  }

  private static List<String> defaultNamesInAllLanguages(Context app) {
    List<String> names = new java.util.ArrayList<>();
    for (String tag : AppLanguage.supportedTags(app)) {
      android.content.res.Configuration config =
          new android.content.res.Configuration(app.getResources().getConfiguration());
      config.setLocale(java.util.Locale.forLanguageTag(tag));
      names.add(
          app.createConfigurationContext(config)
              .getString(de.schliweb.makeacopy.R.string.collection_completed_scans));
    }
    return names;
  }
}
