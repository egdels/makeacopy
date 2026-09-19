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

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent grouping of scanned pages into one logical document (Session 3).
 *
 * <p>A {@code DocumentSession} only stores the stable {@code documentId}, timestamps, an optional
 * title and the ordered list of {@link de.schliweb.makeacopy.ui.export.session.CompletedScan} page
 * ids. It deliberately does NOT persist OCR progress, export status, editor state or any UI
 * selection — {@code CompletedScan} remains the page model, this record is purely the durable
 * grouping/order/recovery infrastructure.
 *
 * @param documentId stable unique identifier (UUID string)
 * @param createdAt creation timestamp (epoch millis)
 * @param updatedAt last modification timestamp (epoch millis)
 * @param title optional user-facing title (may be null)
 * @param pageIds ordered list of CompletedScan ids; the persisted order is authoritative
 */
public record DocumentSession(
    String documentId,
    long createdAt,
    long updatedAt,
    @Nullable String title,
    List<String> pageIds) {

  public DocumentSession {
    if (pageIds == null) pageIds = Collections.emptyList();
    // Defensive copy so callers cannot mutate the persisted state behind our back.
    pageIds = Collections.unmodifiableList(new ArrayList<>(pageIds));
  }

  /** Returns a copy of this session with the given ordered page ids and a fresh updatedAt. */
  public DocumentSession withPageIds(List<String> newPageIds) {
    return new DocumentSession(
        documentId, createdAt, System.currentTimeMillis(), title, newPageIds);
  }
}
