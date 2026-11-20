/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.crop;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical spans along one screen side that are kept free of the system back gesture.
 *
 * <p>The system decides on the first touch whether a swipe from the side is "back", and it honours
 * at most 200dp of exclusion per side, counted from the bottom of the screen upwards. A strip over
 * the whole side therefore protects only its lowest 200dp. The budget is spent on the handles that
 * sit in the side's gesture zone instead, in the order given (most important first).
 */
final class CropGestureExclusion {
  private CropGestureExclusion() {}

  /**
   * @param centers vertical handle centres in view pixels, most important first
   * @param radii touch radius of each handle in view pixels
   * @param count number of valid entries in {@code centers} / {@code radii}
   * @param viewHeight height of the view in pixels
   * @param budget total height in pixels the system honours on one side
   * @return disjoint {@code {top, bottom}} spans, sorted top to bottom, at most {@code budget} high
   *     in total
   */
  static List<int[]> spans(float[] centers, float[] radii, int count, int viewHeight, int budget) {
    List<int[]> spans = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      int top = Math.max(0, (int) Math.floor(centers[i] - radii[i]));
      int bottom = Math.min(viewHeight, (int) Math.ceil(centers[i] + radii[i]));
      if (bottom <= top) continue;
      int left = budget - height(spans);
      int added = addedHeight(spans, top, bottom);
      if (added > left) {
        // Not enough budget for the whole handle: keep its centre covered
        int cut = added - left;
        top += cut / 2;
        bottom -= cut - cut / 2;
        if (bottom <= top || addedHeight(spans, top, bottom) > left) continue;
      }
      merge(spans, top, bottom);
    }
    return spans;
  }

  private static int height(List<int[]> spans) {
    int sum = 0;
    for (int[] s : spans) sum += s[1] - s[0];
    return sum;
  }

  /** Height that {@code [top, bottom]} adds to the spans, i.e. without what they already cover. */
  private static int addedHeight(List<int[]> spans, int top, int bottom) {
    int covered = 0;
    for (int[] s : spans) {
      covered += Math.max(0, Math.min(bottom, s[1]) - Math.max(top, s[0]));
    }
    return bottom - top - covered;
  }

  private static void merge(List<int[]> spans, int top, int bottom) {
    List<int[]> out = new ArrayList<>();
    for (int[] s : spans) {
      if (s[1] < top || s[0] > bottom) {
        out.add(s);
      } else {
        top = Math.min(top, s[0]);
        bottom = Math.max(bottom, s[1]);
      }
    }
    int at = 0;
    while (at < out.size() && out.get(at)[0] < top) at++;
    out.add(at, new int[] {top, bottom});
    spans.clear();
    spans.addAll(out);
  }
}
