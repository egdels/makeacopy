/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Puts the words of one visual line, given left to right, into reading (logical) order.
 *
 * <p>Sorting the words of a right-to-left line by descending x is only right for the Arabic words:
 * two Latin words "Hello World" next to Arabic text came out as "World Hello" (issue #101), and in
 * a left-to-right line two Arabic words came out in visual instead of reading order. This applies
 * the bidi algorithm at word level: every word is right-to-left, left-to-right or neutral (digits,
 * punctuation); a neutral word takes the direction of its strong neighbours when both agree, a
 * number following a Latin word reads with it, and everything else takes the line's base direction.
 * Runs of one direction then keep their order inside (left to right for Latin, right to left for
 * Arabic), and in a right-to-left line the runs themselves are read from right to left.
 *
 * <p>Pure Java, no Android dependencies, so the JVM tests cover it.
 */
public final class BidiWordOrder {

  private static final int RTL = 1;
  private static final int LTR = 0;
  private static final int NEUTRAL = -1;

  private BidiWordOrder() {}

  /**
   * Returns the reading order of the words of one line.
   *
   * @param leftToRight the word texts in visual order, left to right
   * @param rtlBase whether the line reads right to left
   * @return indices into {@code leftToRight} in reading order
   */
  public static int[] logicalOrder(String[] leftToRight, boolean rtlBase) {
    int n = leftToRight.length;
    int[] dir = new int[n];
    boolean[] number = new boolean[n];
    for (int i = 0; i < n; i++) {
      dir[i] = strongDirection(leftToRight[i]);
      number[i] = dir[i] == NEUTRAL && containsDigit(leftToRight[i]);
    }
    int base = rtlBase ? RTL : LTR;
    int[] resolved = new int[n];
    for (int i = 0; i < n; i++) {
      if (dir[i] != NEUTRAL) {
        resolved[i] = dir[i];
        continue;
      }
      int before = base;
      for (int j = i - 1; j >= 0; j--) {
        if (dir[j] != NEUTRAL) {
          before = dir[j];
          break;
        }
      }
      int after = base;
      for (int j = i + 1; j < n; j++) {
        if (dir[j] != NEUTRAL) {
          after = dir[j];
          break;
        }
      }
      if (before == after) resolved[i] = before;
      else if (number[i] && before == LTR) resolved[i] = LTR;
      else resolved[i] = base;
    }

    // Runs of one resolved direction, left to right.
    int[] order = new int[n];
    int[] runStart = new int[n];
    int[] runEnd = new int[n];
    int runs = 0;
    for (int i = 0; i < n; ) {
      int j = i;
      while (j + 1 < n && resolved[j + 1] == resolved[i]) j++;
      runStart[runs] = i;
      runEnd[runs] = j;
      runs++;
      i = j + 1;
    }
    int k = 0;
    for (int r = 0; r < runs; r++) {
      int run = rtlBase ? runs - 1 - r : r;
      int s = runStart[run];
      int e = runEnd[run];
      if (resolved[s] == RTL) {
        for (int i = e; i >= s; i--) order[k++] = i;
      } else {
        for (int i = s; i <= e; i++) order[k++] = i;
      }
    }
    return order;
  }

  /**
   * Reorders the words of one line in place from visual (left to right) into reading order.
   *
   * @param leftToRight the words, sorted left to right by their boxes
   * @param text the text of a word
   * @param rtlBase whether the line reads right to left
   */
  public static <T> void toLogical(List<T> leftToRight, Function<T, String> text, boolean rtlBase) {
    int n = leftToRight.size();
    if (n < 2) return;
    String[] texts = new String[n];
    for (int i = 0; i < n; i++) texts[i] = text.apply(leftToRight.get(i));
    int[] order = logicalOrder(texts, rtlBase);
    List<T> visual = new ArrayList<>(leftToRight);
    for (int i = 0; i < n; i++) leftToRight.set(i, visual.get(order[i]));
  }

  /** The direction of the first strong letter of the word, or {@link #NEUTRAL} if it has none. */
  private static int strongDirection(String s) {
    if (s == null) return NEUTRAL;
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      byte d = Character.getDirectionality(cp);
      if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
          || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return RTL;
      if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return LTR;
      i += Character.charCount(cp);
    }
    return NEUTRAL;
  }

  private static boolean containsDigit(String s) {
    if (s == null) return false;
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      if (Character.isDigit(cp)) return true;
      i += Character.charCount(cp);
    }
    return false;
  }
}
