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

import androidx.room.*;
import java.util.List;

/**
 * Data Access Object (DAO) for managing operations on the "collections" table within the Room
 * database. Provides methods for inserting, updating, retrieving, and deleting records, as well as
 * performing specific queries related to collections and their relationships.
 *
 * <p>Methods included:
 *
 * <p>- insert(CollectionEntity collection): Adds a new collection to the database. If a conflict
 * occurs (e.g., duplicate ID), the operation is aborted.
 *
 * <p>- update(CollectionEntity collection): Updates an existing collection's details in the
 * database.
 *
 * <p>- getAll(): Retrieves all collections from the database, sorted by their sort order
 * (ascending) and creation timestamp (ascending).
 *
 * <p>- getById(String id): Fetches a collection by its unique ID. Returns a single collection or
 * null if no match is found.
 *
 * <p>- deleteById(String id): Deletes the collection with the specified ID from the database.
 *
 * <p>- countItems(String collectionId): Counts the number of related items (e.g., scans) linked to
 * a specific collection ID via the scan-collection join table.
 */
@Dao
public interface CollectionsDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  void insert(CollectionEntity collection);

  @Update
  void update(CollectionEntity collection);

  @Query("SELECT * FROM collections ORDER BY sortOrder ASC, createdAt ASC")
  List<CollectionEntity> getAll();

  @Query("SELECT * FROM collections WHERE id = :id LIMIT 1")
  CollectionEntity getById(String id);

  @Query("SELECT * FROM collections WHERE name = :name LIMIT 1")
  CollectionEntity getByName(String name);

  @Query("DELETE FROM collections WHERE id = :id")
  void deleteById(String id);

  @Query("SELECT COUNT(*) FROM scan_collection_join WHERE collectionId = :collectionId")
  int countItems(String collectionId);

  @Query("UPDATE collections SET id = :newId WHERE id = :oldId")
  void updateId(String oldId, String newId);

  /** Moves the items of one collection to another; items the target already has stay behind. */
  @Query(
      "UPDATE OR IGNORE scan_collection_join SET collectionId = :newId WHERE collectionId = :oldId")
  void moveItems(String oldId, String newId);

  @Query("DELETE FROM scan_collection_join WHERE collectionId = :collectionId")
  void removeAllItems(String collectionId);

  /** Gives a collection a new ID and keeps its items attached. */
  @Transaction
  default void changeId(String oldId, String newId) {
    updateId(oldId, newId);
    moveItems(oldId, newId);
  }

  /** Moves all items of {@code sourceId} into {@code targetId} and deletes the source. */
  @Transaction
  default void mergeInto(String sourceId, String targetId) {
    moveItems(sourceId, targetId);
    removeAllItems(sourceId); // items the target already had
    deleteById(sourceId);
  }
}
