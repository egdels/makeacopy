/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.export.session;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ViewModel for managing the export session, specifically handling a collection of completed scans
 * and providing methods to interact with and manipulate this collection.
 */
public class ExportSessionViewModel extends ViewModel {
  /**
   * A LiveData object holding a list of completed scans. This list is used to manage the collection
   * of scanned items within the export session.
   *
   * <p>The value is initialized with an empty list to ensure a non-null reference. This variable is
   * mutable, allowing updates to the live data, such as adding, removing, or reordering scans.
   */
  private final MutableLiveData<List<CompletedScan>> pages =
      new MutableLiveData<>(new ArrayList<>());

  /**
   * Session 3: id of the persistent {@link de.schliweb.makeacopy.data.DocumentSession} this runtime
   * page list belongs to. Runtime-only — the ViewModel remains the runtime state, the
   * DocumentSessionRepository owns the persistent snapshot. {@code null} until the first page is
   * added (a new document) or a persisted session is restored.
   */
  private String documentId;

  /** Returns the persistent document id backing this runtime session, or null. */
  public String getDocumentId() {
    return documentId;
  }

  /** Binds this runtime session to a persistent document id (or null to unbind). */
  public void setDocumentId(String documentId) {
    this.documentId = documentId;
  }

  /**
   * Retrieves a LiveData object containing a list of CompletedScan objects. This LiveData monitors
   * and provides reactive updates to the collection of completed scans within the export session.
   *
   * @return a LiveData instance providing access to the current list of CompletedScan objects.
   */
  public LiveData<List<CompletedScan>> getPages() {
    return pages;
  }

  /**
   * Sets the initial state of the pages LiveData with a list containing a single CompletedScan
   * object provided as a parameter. If the provided scan is null, an empty list is set instead.
   *
   * @param scan a CompletedScan object to be set as the initial entry in the pages list. May be
   *     null, in which case an empty list will be used.
   */
  public void setInitial(CompletedScan scan) {
    List<CompletedScan> cur = new ArrayList<>();
    if (scan != null) cur.add(scan);
    pages.setValue(cur);
  }

  /**
   * Adds a list of {@code CompletedScan} objects to the existing collection while avoiding
   * duplicates. Only scans with unique identifiers not already present in the current list are
   * added.
   *
   * @param scans a list of {@code CompletedScan} objects to be added. If the list is null or empty,
   *     no operation is performed.
   */
  public void addAll(List<CompletedScan> scans) {
    if (scans == null || scans.isEmpty()) return;
    List<CompletedScan> cur = new ArrayList<>(safe());
    for (CompletedScan s : scans) {
      if (!contains(cur, s.id())) cur.add(s);
    }
    pages.setValue(cur);
  }

  /**
   * Adds a {@code CompletedScan} object to the current collection, if it is not already present
   * based on its unique identifier. The collection is then updated and reactive updates are posted
   * via the associated LiveData.
   *
   * @param scan a {@code CompletedScan} object to be added. If null, the method does nothing.
   */
  public void add(CompletedScan scan) {
    if (scan == null) return;
    List<CompletedScan> cur = new ArrayList<>(safe());
    if (!contains(cur, scan.id())) {
      cur.add(scan);
      pages.setValue(cur);
    }
  }

  /**
   * Removes a {@code CompletedScan} object from the specified position in the current collection if
   * the provided index is valid. The method updates the associated LiveData with the modified list.
   *
   * @param index the zero-based position of the {@code CompletedScan} object to be removed. If the
   *     index is out of range (negative or greater than or equal to the size of the list), no
   *     operation is performed.
   */
  public void removeAt(int index) {
    List<CompletedScan> cur = new ArrayList<>(safe());
    if (index >= 0 && index < cur.size()) {
      cur.remove(index);
      pages.setValue(cur);
    }
  }

  /**
   * Moves a {@code CompletedScan} object from one position to another within the current collection
   * managed by this instance. The operation updates the associated LiveData with the modified list.
   * If either the source or destination index is invalid or both indices are identical, no changes
   * are made.
   *
   * @param from the zero-based index of the {@code CompletedScan} object to be moved. Must be
   *     within the bounds of the list.
   * @param to the zero-based index where the {@code CompletedScan} object should be moved to. Must
   *     be within the bounds of the list and not equal to {@code from}.
   */
  public void move(int from, int to) {
    List<CompletedScan> cur = new ArrayList<>(safe());
    if (from < 0 || from >= cur.size() || to < 0 || to >= cur.size()) return;
    if (from == to) return;
    CompletedScan s = cur.remove(from);
    cur.add(to, s);
    pages.setValue(cur);
  }

  /**
   * Replaces the item at the given index with the provided scan and posts the updated list. If the
   * index is out of bounds or scan is null, no-op.
   */
  public void updateAt(int index, CompletedScan scan) {
    if (scan == null) return;
    List<CompletedScan> cur = new ArrayList<>(safe());
    if (index >= 0 && index < cur.size()) {
      cur.set(index, scan);
      pages.setValue(cur);
    }
  }

  /**
   * Checks whether a {@code CompletedScan} object with the specified unique identifier exists
   * within the internal list of scans.
   *
   * <p>This method uses a helper method to determine the presence of an object in a list of scans
   * by matching the provided identifier against the unique identifier of each scan.
   *
   * @param id the unique identifier of the {@code CompletedScan} object being searched for. If
   *     {@code null}, the method will return {@code false}.
   * @return {@code true} if a {@code CompletedScan} with the specified identifier is found in the
   *     list, otherwise {@code false}.
   */
  public boolean contains(String id) {
    return contains(safe(), id);
  }

  /**
   * Checks if a list of {@code CompletedScan} objects contains a scan with a specific unique
   * identifier.
   *
   * @param list a list of {@code CompletedScan} objects to be searched. The list must not be null.
   * @param id the unique identifier of the {@code CompletedScan} object being searched for. If
   *     {@code null}, the method returns {@code false}.
   * @return {@code true} if a {@code CompletedScan} object with the specified identifier exists in
   *     the list, otherwise {@code false}.
   */
  private static boolean contains(List<CompletedScan> list, String id) {
    if (id == null) return false;
    for (CompletedScan s : list) if (id.equals(s.id())) return true;
    return false;
  }

  /**
   * Safely retrieves the current list of completed scans. If the internal list is null, returns an
   * empty list to ensure a non-null result.
   *
   * @return a list of {@code CompletedScan} objects if available; otherwise, an empty list.
   */
  private List<CompletedScan> safe() {
    List<CompletedScan> cur = pages.getValue();
    if (cur == null) return Collections.emptyList();
    return cur;
  }
}
