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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Unit tests for {@link OcrBatchController} (sequential batch OCR coordination). */
public class OcrBatchControllerTest {

  /** Records started/cancelled page ids without running anything. */
  private static class FakeStarter implements OcrBatchController.JobStarter {
    final List<String> started = new ArrayList<>();
    final List<String> cancelled = new ArrayList<>();
    Set<String> failOnStart = new HashSet<>();

    @Override
    public void startOcr(String pageId) {
      if (failOnStart.contains(pageId)) throw new RuntimeException("start failed: " + pageId);
      started.add(pageId);
    }

    @Override
    public void cancelOcr(String pageId) {
      cancelled.add(pageId);
    }
  }

  private static class RecordingListener implements OcrBatchController.Listener {
    final List<String> startedPages = new ArrayList<>();
    final List<String> finishedPages = new ArrayList<>();
    final List<Integer> startedPositions = new ArrayList<>();
    OcrBatchController.Summary summary;

    @Override
    public void onPageStarted(String pageId, int position, int total) {
      startedPages.add(pageId);
      startedPositions.add(position);
    }

    @Override
    public void onPageFinished(String pageId, boolean success, int finished, int total) {
      finishedPages.add(pageId + ":" + success);
    }

    @Override
    public void onBatchFinished(OcrBatchController.Summary s) {
      summary = s;
    }
  }

  @Test
  public void processesPagesSequentiallyInOrder() {
    FakeStarter starter = new FakeStarter();
    RecordingListener listener = new RecordingListener();
    OcrBatchController c = new OcrBatchController(starter, null, listener);

    assertTrue(c.start(Arrays.asList("a", "b", "c")));
    // Strictly sequential: only one job started until completion arrives
    assertEquals(Arrays.asList("a"), starter.started);
    c.onOcrJobFinished("a", true);
    assertEquals(Arrays.asList("a", "b"), starter.started);
    c.onOcrJobFinished("b", true);
    assertEquals(Arrays.asList("a", "b", "c"), starter.started);
    c.onOcrJobFinished("c", true);

    assertFalse(c.isRunning());
    assertEquals(3, listener.summary.total);
    assertEquals(3, listener.summary.succeeded);
    assertEquals(0, listener.summary.failed);
    assertEquals(0, listener.summary.skipped);
    assertFalse(listener.summary.cancelled);
    assertEquals(Arrays.asList(1, 2, 3), listener.startedPositions);
  }

  @Test
  public void rejectsStartWhileRunningAndEmptyInput() {
    FakeStarter starter = new FakeStarter();
    OcrBatchController c = new OcrBatchController(starter, null, null);
    assertFalse(c.start(new ArrayList<>()));
    assertFalse(c.start(null));
    assertTrue(c.start(Arrays.asList("a")));
    assertFalse(c.start(Arrays.asList("b")));
  }

  @Test
  public void removesNullAndDuplicateIds() {
    FakeStarter starter = new FakeStarter();
    RecordingListener listener = new RecordingListener();
    OcrBatchController c = new OcrBatchController(starter, null, listener);
    assertTrue(c.start(Arrays.asList("a", null, "a", "b")));
    c.onOcrJobFinished("a", true);
    c.onOcrJobFinished("b", true);
    assertEquals(Arrays.asList("a", "b"), starter.started);
    assertEquals(2, listener.summary.total);
  }

  @Test
  public void failureOnOnePageDoesNotStopBatch() {
    FakeStarter starter = new FakeStarter();
    RecordingListener listener = new RecordingListener();
    OcrBatchController c = new OcrBatchController(starter, null, listener);
    c.start(Arrays.asList("a", "b", "c"));
    c.onOcrJobFinished("a", true);
    c.onOcrJobFinished("b", false); // page b fails
    // batch continues with c
    assertEquals(Arrays.asList("a", "b", "c"), starter.started);
    c.onOcrJobFinished("c", true);
    assertEquals(2, listener.summary.succeeded);
    assertEquals(1, listener.summary.failed);
    assertEquals(Arrays.asList("b"), listener.summary.failedPageIds);
  }

  @Test
  public void retryFailedReprocessesOnlyFailedPages() {
    FakeStarter starter = new FakeStarter();
    RecordingListener listener = new RecordingListener();
    OcrBatchController c = new OcrBatchController(starter, null, listener);
    c.start(Arrays.asList("a", "b", "c"));
    c.onOcrJobFinished("a", true);
    c.onOcrJobFinished("b", false);
    c.onOcrJobFinished("c", false);
    assertEquals(Arrays.asList("b", "c"), c.getLastFailedPageIds());

    starter.started.clear();
    assertTrue(c.retryFailed());
    c.onOcrJobFinished("b", true);
    c.onOcrJobFinished("c", true);
    // Only previously failed pages were re-enqueued
    assertEquals(Arrays.asList("b", "c"), starter.started);
    assertEquals(2, listener.summary.succeeded);
    assertEquals(0, listener.summary.failed);
    assertTrue(c.getLastFailedPageIds().isEmpty());
  }

  @Test
  public void cancelStopsFurtherPagesAndCancelsCurrentJob() {
    FakeStarter starter = new FakeStarter();
    RecordingListener listener = new RecordingListener();
    OcrBatchController c = new OcrBatchController(starter, null, listener);
    c.start(Arrays.asList("a", "b", "c"));
    c.onOcrJobFinished("a", true);
    // "b" is running now
    c.cancel();
    assertEquals(Arrays.asList("b"), starter.cancelled);
    // cancelled job reports failure via broadcast → batch finishes
    c.onOcrJobFinished("b", false);
    assertFalse(c.isRunning());
    assertTrue(listener.summary.cancelled);
    // completed page kept; cancelled current page is neither success nor failure
    assertEquals(1, listener.summary.succeeded);
    assertEquals(0, listener.summary.failed);
    // "c" was never started
    assertEquals(Arrays.asList("a", "b"), starter.started);
  }

  @Test
  public void deletedPageIsSkipped() {
    FakeStarter starter = new FakeStarter();
    RecordingListener listener = new RecordingListener();
    Set<String> existing = new HashSet<>(Arrays.asList("a", "c"));
    OcrBatchController c = new OcrBatchController(starter, existing::contains, listener);
    c.start(Arrays.asList("a", "b", "c")); // "b" no longer exists
    c.onOcrJobFinished("a", true);
    c.onOcrJobFinished("c", true);
    assertEquals(Arrays.asList("a", "c"), starter.started);
    assertEquals(1, listener.summary.skipped);
    assertEquals(2, listener.summary.succeeded);
    assertEquals(3, listener.summary.total);
  }

  @Test
  public void unrelatedCompletionIsIgnored() {
    FakeStarter starter = new FakeStarter();
    RecordingListener listener = new RecordingListener();
    OcrBatchController c = new OcrBatchController(starter, null, listener);
    c.start(Arrays.asList("a", "b"));
    // Manually started single-page OCR for another page finishes → must not advance the batch
    c.onOcrJobFinished("x", true);
    c.onOcrJobFinished(null, true);
    assertEquals(Arrays.asList("a"), starter.started);
    assertTrue(c.isRunning());
    assertEquals("a", c.getCurrentPageId());
  }

  @Test
  public void synchronousStartFailureCountsAsFailedAndContinues() {
    FakeStarter starter = new FakeStarter();
    starter.failOnStart.add("b");
    RecordingListener listener = new RecordingListener();
    OcrBatchController c = new OcrBatchController(starter, null, listener);
    c.start(Arrays.asList("a", "b", "c"));
    c.onOcrJobFinished("a", true);
    // "b" failed synchronously; controller moved on to "c"
    assertEquals("c", c.getCurrentPageId());
    c.onOcrJobFinished("c", true);
    assertEquals(2, listener.summary.succeeded);
    assertEquals(1, listener.summary.failed);
    assertEquals(Arrays.asList("b"), listener.summary.failedPageIds);
  }

  @Test
  public void cancelWithoutRunningBatchIsNoOp() {
    FakeStarter starter = new FakeStarter();
    OcrBatchController c = new OcrBatchController(starter, null, null);
    c.cancel(); // must not throw
    assertFalse(c.isRunning());
    assertNull(c.getCurrentPageId());
  }

  @Test
  public void allPagesDeletedFinishesWithSkippedOnly() {
    FakeStarter starter = new FakeStarter();
    RecordingListener listener = new RecordingListener();
    OcrBatchController c = new OcrBatchController(starter, id -> false, listener);
    assertTrue(c.start(Arrays.asList("a", "b")));
    assertFalse(c.isRunning());
    assertEquals(2, listener.summary.skipped);
    assertEquals(0, listener.summary.succeeded);
    assertTrue(starter.started.isEmpty());
  }
}
