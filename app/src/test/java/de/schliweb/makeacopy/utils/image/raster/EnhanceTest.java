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
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import org.junit.Test;

/** The Enhanced cleanup: paper (also in shadow) to white, ink to black, pictures and bars kept. */
public class EnhanceTest {

  private static int rgb(int r, int g, int b) {
    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }

  private static int gray(int v) {
    return rgb(v, v, v);
  }

  private static int level(int p) {
    return p & 0xFF;
  }

  private static int luma(int p) {
    return (77 * (p >> 16 & 0xFF) + 150 * (p >> 8 & 0xFF) + 29 * (p & 0xFF)) >> 8;
  }

  /** Two-pixel ink stripes every 16 columns on paper whose level depends on x. */
  private static int[] page(int w, int h, IntUnaryOperator paper, int ink) {
    int[] px = new int[w * h];
    for (int i = 0; i < px.length; i++) {
      int x = i % w;
      px[i] = (x % 16 == 7 || x % 16 == 8) ? gray(ink) : gray(paper.applyAsInt(x));
    }
    return px;
  }

  @Test
  public void paperBecomesWhiteAndInkBlack() {
    int w = 64;
    int[] px = page(w, 64, x -> 200, 60);
    Enhance.apply(px, w, 64);
    assertEquals(255, level(px[30 * w + 2]));
    assertTrue("ink " + level(px[30 * w + 23]), level(px[30 * w + 23]) < 20);
  }

  @Test
  public void lightFallingOffIsEvenedOut() {
    int w = 320;
    int[] px = page(w, 160, x -> 150 + 80 * x / w, 40);
    Enhance.apply(px, w, 160);
    for (int x : new int[] {3, 100, 202, 315}) {
      assertTrue("paper at " + x + ": " + level(px[80 * w + x]), level(px[80 * w + x]) >= 242);
    }
    assertTrue("ink on the dark side " + level(px[80 * w + 7]), level(px[80 * w + 7]) < 40);
  }

  @Test
  public void aColourCastIsTakenOut() {
    int w = 64;
    int[] px = new int[w * 64];
    for (int i = 0; i < px.length; i++) {
      int x = i % w;
      px[i] = (x % 16 == 7 || x % 16 == 8) ? rgb(30, 30, 40) : rgb(225, 230, 250);
    }
    Enhance.apply(px, w, 64);
    int p = px[30 * w + 3];
    assertTrue(
        String.format("paper %06X", p & 0xFFFFFF),
        (p >> 16 & 0xFF) >= 250 && (p >> 8 & 0xFF) >= 250 && (p & 0xFF) >= 250);
  }

  @Test
  public void aPictureOnThePageIsNotWashedOut() {
    int w = 320;
    Random rnd = new Random(1);
    int[] px = new int[w * w];
    for (int i = 0; i < px.length; i++) {
      int x = i % w;
      int y = i / w;
      px[i] =
          (x >= 96 && x < 224 && y >= 96 && y < 224) ? gray(30 + rnd.nextInt(80)) : gray(215);
    }
    int[] before = new int[px.length];
    for (int i = 0; i < px.length; i++) before[i] = luma(px[i]);
    Enhance.apply(px, w, w);
    double sumAfter = 0;
    double sumBefore = 0;
    Set<Integer> levels = new HashSet<>();
    int n = 0;
    for (int y = 110; y < 210; y++) {
      for (int x = 110; x < 210; x++) {
        int i = y * w + x;
        sumAfter += luma(px[i]);
        sumBefore += before[i];
        levels.add(luma(px[i]));
        n++;
      }
    }
    assertTrue("picture " + sumAfter / n, sumAfter / n < 100);
    assertTrue(levels.size() > 40);
    assertTrue(sumBefore / n < 100);
    assertEquals(255, level(px[20 * w + 20]));
  }

  @Test
  public void aShadowOverTextBecomesWhitePaper() {
    int w = 320;
    int[] px = new int[w * w];
    for (int i = 0; i < px.length; i++) {
      int x = i % w;
      double shade = x < 128 ? 0.5 : 1.0;
      px[i] = gray((int) (((x % 16 == 7 || x % 16 == 8) ? 40 : 220) * shade));
    }
    Enhance.apply(px, w, w);
    for (int x : new int[] {20, 60, 100, 202, 300}) {
      assertTrue("paper at " + x + ": " + level(px[160 * w + x]), level(px[160 * w + x]) >= 240);
    }
    assertTrue("ink in the shadow " + level(px[160 * w + 23]), level(px[160 * w + 23]) < 40);
  }

  @Test
  public void aBlackBarStaysBlack() {
    int w = 320;
    int[] px = new int[w * w];
    for (int i = 0; i < px.length; i++) {
      int y = i / w;
      px[i] = (y >= 140 && y < 180) ? gray(25) : gray(215);
    }
    Enhance.apply(px, w, w);
    assertTrue("bar " + level(px[160 * w + 160]), level(px[160 * w + 160]) < 20);
    assertEquals(255, level(px[40 * w + 160]));
  }

  @Test
  public void aSoftStrokeGetsCrisp() {
    int w = 64;
    int[] profile = {230, 190, 140, 110, 140, 190, 230};
    int[] px = new int[w * 64];
    for (int i = 0; i < px.length; i++) {
      int x = i % w;
      px[i] = gray(x >= 29 && x <= 35 ? profile[x - 29] : 230);
    }
    Enhance.apply(px, w, 64);
    int row = 30 * w;
    assertTrue("core " + level(px[row + 32]), level(px[row + 32]) < 50);
    assertTrue(
        "flank " + level(px[row + 34]),
        level(px[row + 34]) - level(px[row + 32]) > 3 * (190 - 110) * 255 / 230 / 2);
    assertEquals(255, level(px[row + 10]));
  }

  @Test
  public void coloursKeepTheirHue() {
    int w = 20;
    int red = rgb(180, 40, 40);
    int[] px = new int[w * 20];
    for (int i = 0; i < px.length; i++) px[i] = i < w * 10 ? red : gray(230);
    Enhance.apply(px, w, 20);
    int p = px[2 * w + 5];
    int r = p >> 16 & 0xFF;
    int g = p >> 8 & 0xFF;
    int b = p & 0xFF;
    assertEquals(g, b);
    assertTrue("red " + r + " " + g + " " + b, r > g + 100);
    assertEquals(px[2 * w + 5], px[3 * w + 15]);
  }

  @Test
  public void emptyAndUniformImagesAreFine() {
    Enhance.apply(new int[0], 0, 0);
    int[] px = new int[16];
    for (int i = 0; i < px.length; i++) px[i] = gray(128);
    Enhance.apply(px, 4, 4);
    Set<Integer> values = new HashSet<>();
    for (int v : px) values.add(v);
    assertEquals(1, values.size());
  }
}
