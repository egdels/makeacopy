/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.image.raster;

import static org.junit.Assert.assertEquals;

import java.util.Random;
import org.junit.Test;

public class LazyGradientsTest {

  @Test
  public void equalsSobelOfTheBlurredWholeImage() {
    int w = 37;
    int h = 23;
    Random rnd = new Random(7);
    int[] argb = new int[w * h];
    for (int i = 0; i < argb.length; i++) argb[i] = 0xFF000000 | rnd.nextInt(0x1000000);
    Gray blurred = Gray.ofArgb(argb, w, h).blurred();
    Gray gx = blurred.sobelX();
    Gray gy = blurred.sobelY();
    LazyGradients lazy = new LazyGradients(argb, w, h);
    // Every pixel, borders included, and in an order that reuses cached values
    for (int y = h - 1; y >= 0; y--) {
      for (int x = 0; x < w; x++) {
        assertEquals("gx " + x + "," + y, gx.at(x, y), lazy.gx(x, y), 1e-3f);
        assertEquals("gy " + x + "," + y, gy.at(x, y), lazy.gy(x, y), 1e-3f);
      }
    }
  }
}
