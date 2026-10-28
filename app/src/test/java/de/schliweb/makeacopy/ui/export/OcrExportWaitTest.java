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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Tests how an export waits for running OCR jobs. */
public class OcrExportWaitTest {

  private final OcrExportWait wait = new OcrExportWait();
  private final AtomicInteger exports = new AtomicInteger();

  @Test
  public void nothingRunningGoesOnRightAway() {
    assertFalse(wait.await(Collections.emptyList(), exports::incrementAndGet));
    assertFalse(wait.isWaiting());
    assertEquals(0, exports.get());
  }

  @Test
  public void exportRunsAfterTheLastJob() {
    assertTrue(wait.await(Arrays.asList("a", "b"), exports::incrementAndGet));
    wait.onJobFinished("a");
    assertEquals(0, exports.get());
    wait.onJobFinished("other");
    assertEquals(0, exports.get());
    wait.onJobFinished("b");
    assertEquals(1, exports.get());
    assertFalse(wait.isWaiting());
    wait.onJobFinished("b");
    assertEquals(1, exports.get());
  }

  @Test
  public void cancelDropsTheExport() {
    wait.await(Collections.singletonList("a"), exports::incrementAndGet);
    wait.cancel();
    wait.onJobFinished("a");
    assertEquals(0, exports.get());
  }

  @Test
  public void refreshCatchesUpOnMissedResults() {
    wait.await(Arrays.asList("a", "b"), exports::incrementAndGet);
    wait.refresh("b"::equals);
    assertEquals(0, exports.get());
    wait.refresh(id -> false);
    assertEquals(1, exports.get());
  }
}
