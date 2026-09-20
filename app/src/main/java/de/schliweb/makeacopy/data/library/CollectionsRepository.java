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
 * Interface for managing collections of scans within the application. Provides methods to create,
 * retrieve, modify, and delete collections, as well as to manage the relationships between
 * collections and scans.
 */
public interface CollectionsRepository {
  /**
   * Fixed ID of the default "Completed Scans" collection. The collection is identified by this ID,
   * never by its (translated) name, so it survives language changes.
   */
  String DEFAULT_COLLECTION_ID = "default_completed_scans";

  CollectionEntity createCollection(Context context, String name);

  List<CollectionEntity> getAllCollections(Context context);

  void assignScanToCollection(Context context, String scanId, String collectionId);

  void removeScanFromCollection(Context context, String scanId, String collectionId);

  /**
   * Deletes the collection only if it contains no scans. Returns true if deleted, false otherwise.
   */
  boolean deleteCollectionIfEmpty(Context context, String collectionId);

  /** Returns the number of scans in a collection. */
  int countItems(Context context, String collectionId);

  /** Renames a collection. Returns true if the collection was found and renamed. */
  boolean renameCollection(Context context, String collectionId, String newName);

  /** Returns all collections that contain the given scan. */
  List<CollectionEntity> getCollectionsForScan(Context context, String scanId);

  /** Returns the default "Completed Scans" collection, creating it if necessary. */
  CollectionEntity getOrCreateDefaultCompletedCollection(Context context);
}
