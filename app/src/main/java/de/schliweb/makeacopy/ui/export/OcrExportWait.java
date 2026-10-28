/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.export;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Holds an export back until the OCR jobs still running for pages of the document have finished.
 *
 * <p>"OCR for this page" in the export screen runs in the background with only a toast; an export
 * started in those seconds wrote the page without its text layer, or with the text of the previous
 * run. The export screen now hands the export to this class, which runs it once the last of those
 * jobs has delivered its result. Pure Java, no Android dependencies, so the JVM tests cover it; the
 * caller keeps it on the main thread.
 */
final class OcrExportWait {

  private final Set<String> waiting = new HashSet<>();
  private Runnable continuation;

  /**
   * Starts waiting for the given jobs.
   *
   * @param runningPageIds pages whose OCR is still running
   * @param then runs once all of them have finished, not at all if there is nothing to wait for
   * @return {@code true} if the caller has to wait, {@code false} if it can go on right away
   */
  boolean await(Collection<String> runningPageIds, Runnable then) {
    waiting.clear();
    if (runningPageIds != null) {
      for (String id : runningPageIds) if (id != null) waiting.add(id);
    }
    if (waiting.isEmpty()) {
      continuation = null;
      return false;
    }
    continuation = then;
    return true;
  }

  /** Whether an export is waiting. */
  boolean isWaiting() {
    return continuation != null;
  }

  /** A job delivered its result (success or not); runs the export after the last one. */
  void onJobFinished(String pageId) {
    if (continuation == null || pageId == null) return;
    waiting.remove(pageId);
    runIfDone();
  }

  /**
   * Drops the jobs that are no longer running, for results that arrived while nobody listened (the
   * screen was stopped).
   */
  void refresh(Predicate<String> stillRunning) {
    if (continuation == null) return;
    waiting.removeIf(id -> !stillRunning.test(id));
    runIfDone();
  }

  /** Gives up waiting; the export does not run. */
  void cancel() {
    waiting.clear();
    continuation = null;
  }

  private void runIfDone() {
    if (!waiting.isEmpty()) return;
    Runnable then = continuation;
    continuation = null;
    if (then != null) then.run();
  }
}
