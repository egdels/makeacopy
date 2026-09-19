/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.jobs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Coordinates a sequential OCR batch over a list of stable page ids on top of the existing per-page
 * OCR pipeline ({@link OcrBackgroundJobs}). The controller does not run OCR itself; it only decides
 * which page to start next, aggregates progress and errors, and coordinates cancel and retry.
 *
 * <p>Scheduling follows a strict "one at a time" model (variant B): the next page is only enqueued
 * after the completion callback for the previous page arrived. This keeps cancel reliable (no
 * hidden queue inside the executor), makes retry/skip trivial and guarantees that at most one
 * batch-initiated OCR job exists at any time.
 *
 * <p>The batch works with stable page ids, not adapter positions: pages deleted while the batch is
 * running are detected via the {@code pageExists} predicate and skipped; reordering the session
 * does not affect the batch order, which is fixed when the batch starts.
 *
 * <p>Threading: all public methods are synchronized; callbacks are invoked while not holding the
 * lock is NOT guaranteed, so listeners must not call back into the controller synchronously except
 * via {@link #cancel()}. Completion is driven externally by calling {@link
 * #onOcrJobFinished(String, boolean)} (e.g. from the {@code ACTION_OCR_UPDATED} broadcast).
 */
public class OcrBatchController {

  /** Abstraction over the actual per-page OCR job execution (DI/test-friendly). */
  public interface JobStarter {
    /** Starts (enqueues) the existing background OCR job for the given page id. */
    void startOcr(String pageId);

    /** Requests cancellation of a running OCR job for the given page id (best-effort). */
    void cancelOcr(String pageId);
  }

  /** Progress/completion callbacks for UI. */
  public interface Listener {
    /** A page has been handed to the OCR pipeline. {@code position} is 1-based within the batch. */
    void onPageStarted(String pageId, int position, int total);

    /** A page finished (success or failure). {@code finished} counts processed pages so far. */
    void onPageFinished(String pageId, boolean success, int finished, int total);

    /** The batch is done (all pages processed, or cancelled). */
    void onBatchFinished(Summary summary);
  }

  /** Immutable result summary of a batch run. */
  public static final class Summary {
    public final int total;
    public final int succeeded;
    public final int failed;
    public final int skipped;
    public final boolean cancelled;
    public final List<String> failedPageIds;

    Summary(
        int total,
        int succeeded,
        int failed,
        int skipped,
        boolean cancelled,
        List<String> failedPageIds) {
      this.total = total;
      this.succeeded = succeeded;
      this.failed = failed;
      this.skipped = skipped;
      this.cancelled = cancelled;
      this.failedPageIds = Collections.unmodifiableList(new ArrayList<>(failedPageIds));
    }
  }

  private final JobStarter jobStarter;
  private final Predicate<String> pageExists;
  private final Listener listener;

  private final List<String> queue = new ArrayList<>();
  private final List<String> failedPageIds = new ArrayList<>();
  private int nextIndex;
  private String currentPageId;
  private boolean running;
  private boolean cancelRequested;
  private int succeeded;
  private int failed;
  private int skipped;
  private List<String> lastFailedPageIds = new ArrayList<>();

  /**
   * @param jobStarter starts/cancels the underlying per-page OCR jobs; must not be null
   * @param pageExists returns whether the page id is still part of the document; pages for which
   *     this returns {@code false} at their turn are skipped. May be null (no skipping).
   * @param listener progress callbacks; may be null
   */
  public OcrBatchController(
      JobStarter jobStarter, Predicate<String> pageExists, Listener listener) {
    if (jobStarter == null) throw new IllegalArgumentException("jobStarter must not be null");
    this.jobStarter = jobStarter;
    this.pageExists = pageExists;
    this.listener = listener;
  }

  /**
   * Starts a batch over the given page ids (order preserved; null/duplicate ids removed). Returns
   * {@code false} when a batch is already running or the effective list is empty.
   */
  public synchronized boolean start(List<String> pageIds) {
    if (running) return false;
    queue.clear();
    if (pageIds != null) {
      for (String id : pageIds) {
        if (id != null && !queue.contains(id)) queue.add(id);
      }
    }
    if (queue.isEmpty()) return false;
    nextIndex = 0;
    currentPageId = null;
    cancelRequested = false;
    succeeded = 0;
    failed = 0;
    skipped = 0;
    failedPageIds.clear();
    running = true;
    startNext();
    return true;
  }

  /**
   * Re-enqueues only the pages that failed in the previous run. Successful pages are never
   * re-processed. Uses the same batch mechanics as {@link #start(List)}.
   */
  public synchronized boolean retryFailed() {
    if (running) return false;
    return start(new ArrayList<>(lastFailedPageIds));
  }

  /**
   * Cancels the batch: no further page is started, not-yet-started pages are dropped, and a
   * best-effort cancellation is requested for the currently running job. Results of already
   * completed pages remain persisted. The batch finishes (with {@code cancelled=true}) once the
   * current job reports completion, or immediately when no job is running.
   */
  public synchronized void cancel() {
    if (!running || cancelRequested) return;
    cancelRequested = true;
    if (currentPageId != null) {
      try {
        jobStarter.cancelOcr(currentPageId);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      // finish() happens when the cancelled job reports back via onOcrJobFinished
    } else {
      finish();
    }
  }

  /**
   * Must be called when the underlying OCR job for {@code pageId} finished (success or failure).
   * Completions for pages that are not the batch's current page (e.g. manually started single-page
   * OCR) are ignored.
   */
  public synchronized void onOcrJobFinished(String pageId, boolean success) {
    if (!running || pageId == null || !pageId.equals(currentPageId)) return;
    currentPageId = null;
    if (success) {
      succeeded++;
    } else if (!cancelRequested) {
      // A cancelled current page is neither a success nor a genuine failure; it stays
      // re-startable (its status is normalized back to a pending/imported state).
      failed++;
      failedPageIds.add(pageId);
    }
    int finished = succeeded + failed + skipped;
    if (listener != null) {
      try {
        listener.onPageFinished(pageId, success, finished, queue.size());
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
    if (cancelRequested) {
      finish();
    } else {
      startNext();
    }
  }

  /** Whether a batch is currently active. */
  public synchronized boolean isRunning() {
    return running;
  }

  /** The page id currently being processed, or null. */
  public synchronized String getCurrentPageId() {
    return currentPageId;
  }

  /** Failed page ids of the last finished batch (for "Retry failed"). */
  public synchronized List<String> getLastFailedPageIds() {
    return new ArrayList<>(lastFailedPageIds);
  }

  private void startNext() {
    while (nextIndex < queue.size()) {
      if (cancelRequested) {
        finish();
        return;
      }
      String id = queue.get(nextIndex);
      int position = nextIndex + 1;
      nextIndex++;
      if (pageExists != null && !pageExists.test(id)) {
        // Page was deleted while the batch was running → skip.
        skipped++;
        continue;
      }
      currentPageId = id;
      if (listener != null) {
        try {
          listener.onPageStarted(id, position, queue.size());
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
      try {
        jobStarter.startOcr(id);
      } catch (Throwable t) {
        // Starting the job failed synchronously → count as failed page and continue.
        currentPageId = null;
        failed++;
        failedPageIds.add(id);
        if (listener != null) {
          try {
            listener.onPageFinished(id, false, succeeded + failed + skipped, queue.size());
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
        }
        continue;
      }
      return;
    }
    finish();
  }

  private void finish() {
    if (!running) return;
    running = false;
    currentPageId = null;
    lastFailedPageIds = new ArrayList<>(failedPageIds);
    Summary summary =
        new Summary(queue.size(), succeeded, failed, skipped, cancelRequested, failedPageIds);
    if (listener != null) {
      try {
        listener.onBatchFinished(summary);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
  }
}
