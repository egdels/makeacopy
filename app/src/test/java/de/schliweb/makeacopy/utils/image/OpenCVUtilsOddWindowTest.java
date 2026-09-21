/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.image;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit tests for the local-threshold window size used by {@code OpenCVUtils.prepareForOCR}. */
public class OpenCVUtilsOddWindowTest {

  @Test
  public void oddWindow_smallImage_clampsToMinimum() {
    assertEquals(31, OpenCVUtils.oddWindow(0, 24));
    assertEquals(31, OpenCVUtils.oddWindow(700, 24)); // 700 / 24 = 29
  }

  @Test
  public void oddWindow_evenQuotient_isRoundedUpToOdd() {
    assertEquals(101, OpenCVUtils.oddWindow(2400, 24)); // 2400 / 24 = 100
    assertEquals(101, OpenCVUtils.oddWindow(1000, 10)); // low-res divisor
  }

  @Test
  public void oddWindow_oddQuotient_isKept() {
    assertEquals(75, OpenCVUtils.oddWindow(2400, 32));
  }

  @Test
  public void oddWindow_isAlwaysOdd() {
    for (int minSide = 0; minSide < 5000; minSide += 37) {
      assertEquals(1, OpenCVUtils.oddWindow(minSide, 24) % 2);
      assertEquals(1, OpenCVUtils.oddWindow(minSide, 32) % 2);
      assertEquals(1, OpenCVUtils.oddWindow(minSide, 10) % 2);
    }
  }
}
