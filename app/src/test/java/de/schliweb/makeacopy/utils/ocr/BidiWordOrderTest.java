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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Tests the reading order of the words of one visual line. */
public class BidiWordOrderTest {

  private static String read(boolean rtlBase, String... leftToRight) {
    List<String> words = new ArrayList<>(Arrays.asList(leftToRight));
    BidiWordOrder.toLogical(words, w -> w, rtlBase);
    return String.join(" ", words);
  }

  @Test
  public void latinWordsInAnArabicLineKeepTheirOrder() {
    // Issue #101: "Hello World" on the left, "أهلا بالعالم" on the right.
    assertEquals("أهلا بالعالم Hello World", read(true, "Hello", "World", "بالعالم", "أهلا"));
  }

  @Test
  public void arabicWordsInALatinLineAreReadRightToLeft() {
    assertEquals("Hello World أهلا بالعالم", read(false, "Hello", "World", "بالعالم", "أهلا"));
  }

  @Test
  public void pureLinesKeepTheirDirection() {
    assertEquals("مستند اختبار العربية", read(true, "العربية", "اختبار", "مستند"));
    assertEquals("one two three", read(false, "one", "two", "three"));
  }

  @Test
  public void numbersReadWithTheirNeighbours() {
    // Between Arabic words a number is part of the Arabic run.
    assertEquals("رقم 12 الصفحة", read(true, "الصفحة", "12", "رقم"));
    // After a Latin word it reads with the Latin run.
    assertEquals("الرقم No. 12345", read(true, "No.", "12345", "الرقم"));
    // A Latin line keeps a date in place.
    assertEquals("Date 25/12/2025", read(false, "Date", "25/12/2025"));
  }

  @Test
  public void punctuationBetweenLatinWordsStaysWithThem() {
    assertEquals("الاسم John - Smith", read(true, "John", "-", "Smith", "الاسم"));
  }

  @Test
  public void trivialInput() {
    assertArrayEquals(new int[0], BidiWordOrder.logicalOrder(new String[0], true));
    assertArrayEquals(new int[] {0}, BidiWordOrder.logicalOrder(new String[] {"x"}, true));
    assertArrayEquals(new int[] {1, 0}, BidiWordOrder.logicalOrder(new String[] {"ب", null}, true));
  }
}
