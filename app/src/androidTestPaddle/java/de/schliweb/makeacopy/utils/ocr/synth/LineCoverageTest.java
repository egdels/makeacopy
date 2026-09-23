/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr.synth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LineCoverageTest {

  private static final List<String> GT =
      Arrays.asList(
          "01Lorem ipsum dolor sit amet,",
          "02consectetur adipiscing elit.",
          "07nec pellentesque eros molestie eget. In",
          "Plötzlich erblickte er den schmalen");

  @Test
  public void exactText_allPresentAndIntact() {
    String ocr = String.join("\n", GT);
    LineCoverage.Report r = LineCoverage.compare(GT, ocr);
    assertEquals(4, r.checked());
    assertEquals(0, r.missing());
    assertEquals(4, r.intact());
  }

  @Test
  public void droppedLine_isReportedMissing() {
    String ocr = GT.get(0) + "\n" + GT.get(1) + "\n" + GT.get(3);
    LineCoverage.Report r = LineCoverage.compare(GT, ocr);
    assertEquals(1, r.missing());
    assertEquals(GT.get(2), r.missingLines().get(0).reference());
  }

  @Test
  public void misreadCharactersAndMovedSpaces_stillPresentButNotIntact() {
    // The word splitter moved the boundary and the recogniser lost a character: the line is
    // present (the letters are nearly all there) but not intact.
    String ocr = "0Loremi psum dolor sit amet,\n02consectetur adipiscing elit.\n"
        + "07nec pellentesque eros molestie eget. In\nPlötzlic herblickt ee rde nschmalen";
    LineCoverage.Report r = LineCoverage.compare(GT, ocr);
    assertEquals(0, r.missing());
    assertEquals(2, r.intact());
    assertFalse(r.lines().get(0).intact());
    assertFalse(r.lines().get(3).intact());
    assertTrue(r.lines().get(3).present());
  }

  @Test
  public void columnsInterleaved_linesStillPresent() {
    // Single-flow output joins two columns per line; whitespace-insensitive matching still
    // finds each reference line inside the blob.
    String ocr = GT.get(0) + " " + GT.get(3) + "\n" + GT.get(1) + " " + GT.get(2);
    LineCoverage.Report r = LineCoverage.compare(GT, ocr);
    assertEquals(0, r.missing());
    assertEquals(0, r.intact());
  }

  @Test
  public void ocrLinesWithoutCounterpart_countAsExtra() {
    String ocr = String.join("\n", GT) + "\n---- ----\nCMYK 000 111 222\nab\n" + GT.get(0) + " " + GT.get(3);
    LineCoverage.Report r = LineCoverage.compare(GT, ocr);
    assertEquals(0, r.missing());
    // "CMYK 000 111 222" is junk; "---- ----" and "ab" have no word of three letters; the row
    // that joins two reference lines consists of known words and is not junk.
    assertEquals(1, r.extra());
  }

  @Test
  public void shortReferenceLines_areSkipped() {
    LineCoverage.Report r = LineCoverage.compare(Arrays.asList("E", "37 %", "abc"), "abc");
    assertEquals(1, r.checked()); // "37 %" normalises to "37" and is skipped like "E"
    assertEquals(0, r.missing());
  }

  @Test
  public void similarity_isOneForEqualAndZeroForDisjoint() {
    assertEquals(1.0, LineCoverage.similarity("abc", "abc"), 1e-9);
    assertEquals(0.0, LineCoverage.similarity("abc", "xyz"), 1e-9);
    assertEquals(2, LineCoverage.levenshtein("kitten", "sittin"));
  }
}
