/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.export;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Unit tests for the active-page bookkeeping when filmstrip pages are reordered. */
public class ExportFragmentIndexAfterMoveTest {

  @Test
  public void movedItemFollowsItsNewPosition() {
    assertEquals(3, ExportFragment.indexAfterMove(1, 1, 3));
    assertEquals(0, ExportFragment.indexAfterMove(2, 2, 0));
  }

  @Test
  public void itemsBetweenShiftTowardsTheGap() {
    assertEquals(1, ExportFragment.indexAfterMove(2, 0, 3)); // moved forward past the active page
    assertEquals(3, ExportFragment.indexAfterMove(2, 4, 1)); // moved backward before the active
  }

  @Test
  public void itemsOutsideTheMovedRangeStay() {
    assertEquals(4, ExportFragment.indexAfterMove(4, 0, 2));
    assertEquals(0, ExportFragment.indexAfterMove(0, 2, 4));
  }

  @Test
  public void matchesAnActualListMove_forAllPositions() {
    int size = 6;
    for (int from = 0; from < size; from++) {
      for (int to = 0; to < size; to++) {
        for (int active = 0; active < size; active++) {
          List<Integer> list = new ArrayList<>();
          for (int i = 0; i < size; i++) list.add(i);
          list.add(to, list.remove(from));
          assertEquals(
              "active=" + active + " from=" + from + " to=" + to,
              list.indexOf(active),
              ExportFragment.indexAfterMove(active, from, to));
        }
      }
    }
  }
}
