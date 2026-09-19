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

import android.content.Context;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JSON-backed persistence for {@link DocumentSession}s (Session 3).
 *
 * <p>Follows the {@link CompletedScansRegistry} pattern: a single index file under {@code
 * filesDir/documents/document_sessions.json} holding a small schema version, the id of the single
 * active session (v1 supports exactly one active document) and the list of sessions. All writes are
 * atomic (temp file + fsync + rename) so a process death can never leave a half-written file
 * behind; corrupted or missing files degrade to an empty store.
 *
 * <p>This class only owns grouping/order persistence. It contains no OCR or export business logic.
 */
public final class DocumentSessionRepository {
  private static final String TAG = "DocumentSessionRepo";

  private static volatile DocumentSessionRepository instance;

  private final File indexFile;
  private final Gson gson;

  /** Returns the process-wide singleton bound to {@code filesDir/documents}. */
  public static DocumentSessionRepository get(Context ctx) {
    DocumentSessionRepository local = instance;
    if (local != null) return local;
    synchronized (DocumentSessionRepository.class) {
      if (instance == null) {
        File base = new File(ctx.getFilesDir(), "documents");
        if (!base.exists()) {
          //noinspection ResultOfMethodCallIgnored
          base.mkdirs();
        }
        instance = new DocumentSessionRepository(new File(base, "document_sessions.json"));
      }
      return instance;
    }
  }

  /** Visible for tests: create a repository backed by an arbitrary file. */
  public DocumentSessionRepository(File indexFile) {
    this.indexFile = indexFile;
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
  }

  // ===== Session lifecycle =====

  /**
   * Creates a new session with the given ordered page ids (may be empty), marks it as the active
   * session and persists atomically. Any previously active session stays persisted but is no longer
   * active ("Start new document" semantics — old data is never silently deleted).
   */
  public synchronized DocumentSession createSession(@Nullable List<String> pageIds) {
    StoreFile sf = safeLoad();
    long now = System.currentTimeMillis();
    DocumentSession session =
        new DocumentSession(UUID.randomUUID().toString(), now, now, null, pageIds);
    sf.sessions.add(toEntry(session));
    sf.activeDocumentId = session.documentId();
    persist(sf);
    return session;
  }

  /** Returns the active session or {@code null} when none exists / the id dangles. */
  @Nullable
  public synchronized DocumentSession getActiveSession() {
    StoreFile sf = safeLoad();
    if (sf.activeDocumentId == null) return null;
    SessionEntry e = findEntry(sf, sf.activeDocumentId);
    if (e == null) {
      // activeDocumentId points to a missing session → fall back to "no active draft" cleanly.
      Log.w(TAG, "Active documentId dangles, clearing: " + sf.activeDocumentId);
      sf.activeDocumentId = null;
      persist(sf);
      return null;
    }
    return toRuntime(e);
  }

  /** Loads a session by id (active or not). */
  @Nullable
  public synchronized DocumentSession findById(String documentId) {
    if (documentId == null) return null;
    SessionEntry e = findEntry(safeLoad(), documentId);
    return (e == null) ? null : toRuntime(e);
  }

  /**
   * Upserts the session with the given id, replaces its ordered page ids and marks it as the active
   * session — all in one atomic write. This is the main sync entry point used by the runtime {@code
   * ExportSessionViewModel} observer: the caller owns the (eagerly generated) document id, so rapid
   * successive syncs are race-free and source-agnostic (camera page, PDF import, add page, remove,
   * move all funnel through here).
   */
  public synchronized DocumentSession upsertActive(String documentId, List<String> pageIds) {
    if (documentId == null) return null;
    StoreFile sf = safeLoad();
    SessionEntry e = findEntry(sf, documentId);
    long now = System.currentTimeMillis();
    if (e == null) {
      e = new SessionEntry();
      e.documentId = documentId;
      e.createdAt = now;
      sf.sessions.add(e);
    }
    e.pageIds = (pageIds == null) ? new ArrayList<>() : new ArrayList<>(pageIds);
    e.updatedAt = now;
    sf.activeDocumentId = documentId;
    persist(sf);
    return toRuntime(e);
  }

  /**
   * Session 4 (incremental import recovery): appends a single fully persisted page id to the
   * session with the given id and marks that session active — all in one atomic write. The entry is
   * created when it does not exist yet (stable documentId is generated by the caller before the
   * import starts). Appending is idempotent: an id that is already part of the session is never
   * duplicated. Callers MUST only invoke this after the page (CompletedScan) has been fully
   * persisted, so a session can never reference a page that does not exist.
   */
  public synchronized DocumentSession appendPageToActive(String documentId, String pageId) {
    if (documentId == null || pageId == null) return null;
    StoreFile sf = safeLoad();
    SessionEntry e = findEntry(sf, documentId);
    long now = System.currentTimeMillis();
    if (e == null) {
      e = new SessionEntry();
      e.documentId = documentId;
      e.createdAt = now;
      sf.sessions.add(e);
    }
    if (!e.pageIds.contains(pageId)) {
      e.pageIds.add(pageId);
      e.updatedAt = now;
    }
    sf.activeDocumentId = documentId;
    persist(sf);
    return toRuntime(e);
  }

  /**
   * Session 4: lists all persisted sessions ordered by {@code updatedAt} descending (most recently
   * touched documents first). Sessions with an empty page id list are excluded — they carry no user
   * data and are subject to {@link #cleanupEmptySessions()}.
   */
  public synchronized List<DocumentSession> listSessions() {
    StoreFile sf = safeLoad();
    List<DocumentSession> out = new ArrayList<>();
    for (SessionEntry e : sf.sessions) {
      if (e == null || e.documentId == null) continue;
      if (e.pageIds == null || e.pageIds.isEmpty()) continue;
      out.add(toRuntime(e));
    }
    out.sort((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()));
    return out;
  }

  /** Session 4: returns the id of the active session, or {@code null}. */
  @Nullable
  public synchronized String getActiveDocumentId() {
    return safeLoad().activeDocumentId;
  }

  /**
   * Session 4 ("Open document"): marks the existing session with the given id as active. The
   * session keeps its stable documentId — no copy is created. Returns the session, or {@code null}
   * when it does not exist (the active pointer is then left unchanged).
   */
  @Nullable
  public synchronized DocumentSession setActive(String documentId) {
    if (documentId == null) return null;
    StoreFile sf = safeLoad();
    SessionEntry e = findEntry(sf, documentId);
    if (e == null) return null;
    sf.activeDocumentId = documentId;
    persist(sf);
    return toRuntime(e);
  }

  /**
   * Session 4 cleanup policy: removes sessions that never received a page (empty page id list).
   * Valid sessions with pages are always kept; the active session is only removed when it is empty
   * itself (its pointer is cleared then). Returns the number of removed sessions.
   */
  public synchronized int cleanupEmptySessions() {
    StoreFile sf = safeLoad();
    List<SessionEntry> next = new ArrayList<>();
    int removed = 0;
    for (SessionEntry e : sf.sessions) {
      if (e == null || e.documentId == null) continue;
      if (e.pageIds == null || e.pageIds.isEmpty()) {
        removed++;
        if (e.documentId.equals(sf.activeDocumentId)) sf.activeDocumentId = null;
        continue;
      }
      next.add(e);
    }
    if (removed > 0) {
      sf.sessions = next;
      persist(sf);
    }
    return removed;
  }

  /** Persists (inserts or replaces) the given session without changing the active id. */
  public synchronized void save(DocumentSession session) {
    if (session == null || session.documentId() == null) return;
    StoreFile sf = safeLoad();
    SessionEntry e = findEntry(sf, session.documentId());
    if (e == null) {
      sf.sessions.add(toEntry(session));
    } else {
      e.createdAt = session.createdAt();
      e.updatedAt = session.updatedAt();
      e.title = session.title();
      e.pageIds = new ArrayList<>(session.pageIds());
    }
    persist(sf);
  }

  /** Deletes the session with the given id; clears the active pointer when it referenced it. */
  public synchronized void delete(String documentId) {
    if (documentId == null) return;
    StoreFile sf = safeLoad();
    List<SessionEntry> next = new ArrayList<>();
    for (SessionEntry e : sf.sessions) {
      if (e == null || documentId.equals(e.documentId)) continue;
      next.add(e);
    }
    sf.sessions = next;
    if (documentId.equals(sf.activeDocumentId)) sf.activeDocumentId = null;
    persist(sf);
  }

  /**
   * Ends the active session. The session data itself is kept persistent unless {@code
   * deleteSession} is true (explicit user discard).
   */
  public synchronized void endActiveSession(boolean deleteSession) {
    StoreFile sf = safeLoad();
    String active = sf.activeDocumentId;
    if (active == null) return;
    sf.activeDocumentId = null;
    // Session 4 cleanup rule: a session that never received a page carries no user data and is
    // removed on Close/New so empty drafts do not accumulate indefinitely (§24).
    SessionEntry activeEntry = findEntry(sf, active);
    if (activeEntry != null && (activeEntry.pageIds == null || activeEntry.pageIds.isEmpty())) {
      deleteSession = true;
    }
    if (deleteSession) {
      List<SessionEntry> next = new ArrayList<>();
      for (SessionEntry e : sf.sessions) {
        if (e == null || active.equals(e.documentId)) continue;
        next.add(e);
      }
      sf.sessions = next;
    }
    persist(sf);
  }

  // ===== Restore =====

  /**
   * Resolves the active session's ordered page ids against the given registry.
   *
   * <p>Consistency rules (Session 3 §13): missing page ids are skipped, the session is repaired
   * (persisted without the dangling ids) and a warning is logged. No dummy pages are ever created;
   * a single missing page never invalidates the whole document.
   *
   * @return ordered list of resolved pages; empty when there is no active session or none of its
   *     pages can be resolved
   */
  public synchronized List<CompletedScan> resolveActivePages(CompletedScansRegistry registry) {
    return resolvePages(getActiveSession(), registry);
  }

  /**
   * Session 4: resolves the ordered page ids of the given session against the registry using the
   * same repair rules as {@link #resolveActivePages(CompletedScansRegistry)} (missing ids are
   * skipped and the session is repaired). Used by "Open document" for inactive sessions.
   */
  public synchronized List<CompletedScan> resolvePages(
      @Nullable DocumentSession session, CompletedScansRegistry registry) {
    List<CompletedScan> out = new ArrayList<>();
    if (session == null || registry == null) return out;
    java.util.Map<String, CompletedScan> byId = new java.util.HashMap<>();
    for (CompletedScan s : registry.listAllOrderedByDateDesc()) {
      if (s != null && s.id() != null) byId.put(s.id(), s);
    }
    List<String> repaired = new ArrayList<>();
    boolean missing = false;
    for (String id : session.pageIds()) {
      CompletedScan s = (id == null) ? null : byId.get(id);
      if (s == null) {
        missing = true;
        Log.w(TAG, "Restore: skipping missing pageId " + id);
        continue;
      }
      repaired.add(id);
      out.add(s);
    }
    if (missing) {
      // Repair the persisted session so subsequent restores are consistent.
      save(session.withPageIds(repaired));
    }
    return out;
  }

  // ===== Internal persistence =====

  private static SessionEntry findEntry(StoreFile sf, String documentId) {
    for (SessionEntry e : sf.sessions) {
      if (e != null && documentId.equals(e.documentId)) return e;
    }
    return null;
  }

  private static DocumentSession toRuntime(SessionEntry e) {
    return new DocumentSession(e.documentId, e.createdAt, e.updatedAt, e.title, e.pageIds);
  }

  private static SessionEntry toEntry(DocumentSession s) {
    SessionEntry e = new SessionEntry();
    e.documentId = s.documentId();
    e.createdAt = s.createdAt();
    e.updatedAt = s.updatedAt();
    e.title = s.title();
    e.pageIds = new ArrayList<>(s.pageIds());
    return e;
  }

  private StoreFile safeLoad() {
    try {
      return load();
    } catch (Exception e) {
      Log.w(TAG, "safeLoad: treating as empty due to error: " + e.getMessage());
      return StoreFile.empty();
    }
  }

  private StoreFile load() throws IOException {
    if (!indexFile.exists()) return StoreFile.empty();
    try (FileInputStream fis = new FileInputStream(indexFile)) {
      byte[] buffer = new byte[8192];
      java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
      int read;
      while ((read = fis.read(buffer)) != -1) baos.write(buffer, 0, read);
      String json = new String(baos.toByteArray(), StandardCharsets.UTF_8);
      StoreFile sf = gson.fromJson(json, StoreFile.class);
      if (sf == null) return StoreFile.empty();
      if (sf.schemaVersion <= 0) sf.schemaVersion = 1;
      if (sf.sessions == null) sf.sessions = new ArrayList<>();
      // Normalize entries with missing optional fields robustly.
      List<SessionEntry> valid = new ArrayList<>();
      for (SessionEntry e : sf.sessions) {
        if (e == null || e.documentId == null) continue;
        if (e.pageIds == null) e.pageIds = new ArrayList<>();
        valid.add(e);
      }
      sf.sessions = valid;
      return sf;
    }
  }

  private void persist(StoreFile sf) {
    try {
      writeAtomically(sf);
    } catch (IOException e) {
      Log.w(TAG, "persist failed: " + e.getMessage());
    }
  }

  private void writeAtomically(StoreFile sf) throws IOException {
    File dir = indexFile.getParentFile();
    if (dir != null && !dir.exists()) {
      //noinspection ResultOfMethodCallIgnored
      dir.mkdirs();
    }
    String json = gson.toJson(sf);
    File tmp = new File(indexFile.getParentFile(), indexFile.getName() + ".tmp");
    try (FileOutputStream fos = new FileOutputStream(tmp)) {
      fos.write(json.getBytes(StandardCharsets.UTF_8));
      fos.flush();
      try {
        fos.getFD().sync();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
    if (indexFile.exists() && !indexFile.delete()) {
      Log.w(TAG, "writeAtomically: failed to delete old index, attempting overwrite via rename");
    }
    boolean renamed = tmp.renameTo(indexFile);
    if (!renamed) {
      try (FileChannel in = new FileInputStream(tmp).getChannel();
          FileChannel out = new FileOutputStream(indexFile).getChannel()) {
        long size = in.size();
        long pos = 0;
        while (pos < size) {
          pos += out.transferFrom(in, pos, size - pos);
        }
        out.force(true);
      }
      //noinspection ResultOfMethodCallIgnored
      tmp.delete();
    }
  }

  // Internal JSON layout (schemaVersion 1). Unknown fields are ignored by Gson by design.
  static class StoreFile {
    int schemaVersion = 1;
    @Nullable String activeDocumentId;
    List<SessionEntry> sessions = new ArrayList<>();

    static StoreFile empty() {
      StoreFile sf = new StoreFile();
      sf.schemaVersion = 1;
      sf.sessions = new ArrayList<>();
      return sf;
    }
  }

  static class SessionEntry {
    String documentId;
    long createdAt;
    long updatedAt;
    @Nullable String title;
    List<String> pageIds = new ArrayList<>();
  }
}
