/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.ocr.review.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link OcrTextEdit}. The page has two lines of 40 px high words, every letter 10
 * px wide, words 10 px apart: "The quick brownfox" at y=0 and "jumps over" at y=100.
 */
public class OcrTextEditTest {

  private static OcrDoc page(String... lines) {
    OcrDoc doc = new OcrDoc();
    int id = 1;
    for (int l = 0; l < lines.length; l++) {
      int x = 0;
      for (String text : lines[l].split(" ")) {
        OcrDoc.Word w = new OcrDoc.Word();
        w.id = id++;
        w.t = text;
        w.b = new int[] {x, l * 100, text.length() * 10, 40};
        w.c = 0.9f;
        w.l = l;
        doc.words.add(w);
        x += text.length() * 10 + 10;
      }
    }
    return doc;
  }

  private static OcrDoc page() {
    return page("The quick brownfox", "jumps over");
  }

  /** Applies the text with every word as its own piece, in document order. */
  private static boolean edit(OcrDoc doc, String text) {
    List<List<OcrDoc.Word>> pieces = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    for (OcrDoc.Word w : doc.words) {
      pieces.add(Collections.singletonList(w));
      texts.add(w.t);
    }
    return OcrTextEdit.apply(doc, pieces, texts, OcrTextEdit.Tokens.of(text, false), false);
  }

  private static String text(OcrDoc doc) {
    StringBuilder sb = new StringBuilder();
    for (OcrDoc.Word w : doc.words) sb.append(sb.length() > 0 ? " " : "").append(w.t);
    return sb.toString();
  }

  private static OcrDoc.Word word(OcrDoc doc, String text) {
    for (OcrDoc.Word w : doc.words) if (text.equals(w.t)) return w;
    throw new AssertionError("no word " + text + " in: " + text(doc));
  }

  @Test
  public void unchangedTextChangesNothing() {
    OcrDoc doc = page();
    assertFalse(edit(doc, "The quick  brownfox\n\njumps over "));
    assertEquals("The quick brownfox jumps over", text(doc));
    for (OcrDoc.Word w : doc.words) assertFalse(w.e);
  }

  @Test
  public void changedWordKeepsItsBoxAndTheOthersStayUntouched() {
    OcrDoc doc = page();
    assertTrue(edit(doc, "The quack brownfox\njumps over"));
    assertEquals("The quack brownfox jumps over", text(doc));
    assertArrayEquals(new int[] {40, 0, 50, 40}, word(doc, "quack").b);
    assertTrue(word(doc, "quack").e);
    assertFalse(word(doc, "over").e);
  }

  @Test
  public void wordTypedApartSharesItsBox() {
    OcrDoc doc = page();
    assertTrue(edit(doc, "The quick brown fox\njumps over"));
    assertEquals("The quick brown fox jumps over", text(doc));
    // brownfox was at x=100, 80 wide: 5 of 8 letters, then 3 of 8
    assertArrayEquals(new int[] {100, 0, 50, 40}, word(doc, "brown").b);
    assertArrayEquals(new int[] {150, 0, 30, 40}, word(doc, "fox").b);
    assertEquals(0, word(doc, "fox").l);
    // The words behind keep their text and box: nothing shifts, nothing is lost
    assertArrayEquals(new int[] {0, 100, 50, 40}, word(doc, "jumps").b);
    assertArrayEquals(new int[] {60, 100, 40, 40}, word(doc, "over").b);
    assertFalse(word(doc, "over").e);
  }

  @Test
  public void joinedWordsBecomeOneOverBothBoxes() {
    OcrDoc doc = page();
    assertTrue(edit(doc, "Thequick brownfox\njumps over"));
    assertEquals("Thequick brownfox jumps over", text(doc));
    assertArrayEquals(new int[] {0, 0, 90, 40}, word(doc, "Thequick").b);
  }

  @Test
  public void deletedWordIsRemovedAndTheRestStays() {
    OcrDoc doc = page();
    assertTrue(edit(doc, "The brownfox\njumps over"));
    assertEquals("The brownfox jumps over", text(doc));
    assertArrayEquals(new int[] {100, 0, 80, 40}, word(doc, "brownfox").b);
    assertFalse(word(doc, "brownfox").e);
  }

  @Test
  public void newWordTakesAShareOfTheWordBeforeIt() {
    OcrDoc doc = page();
    assertTrue(edit(doc, "The quick new brownfox\njumps over"));
    assertEquals("The quick new brownfox jumps over", text(doc));
    // quick was at x=40, 50 wide: 5 of 8 letters stay, 3 of 8 go to the new word
    assertArrayEquals(new int[] {40, 0, 31, 40}, word(doc, "quick").b);
    assertArrayEquals(new int[] {71, 0, 19, 40}, word(doc, "new").b);
    assertFalse(word(doc, "brownfox").e);
  }

  @Test
  public void newWordAtTheStartOfALineTakesItsShareFromThatLine() {
    OcrDoc doc = page();
    assertTrue(edit(doc, "The quick brownfox\nnew jumps over"));
    assertEquals("The quick brownfox new jumps over", text(doc));
    assertEquals(100, word(doc, "new").b[1]);
    assertEquals(0, word(doc, "new").b[0]);
    assertEquals(1, word(doc, "new").l);
    assertFalse(word(doc, "brownfox").e);
  }

  @Test
  public void newWordsAtTheVeryStartAndEnd() {
    OcrDoc doc = page();
    assertTrue(edit(doc, "A The quick brownfox\njumps over Z"));
    assertEquals("A The quick brownfox jumps over Z", text(doc));
    assertEquals(0, word(doc, "A").b[1]);
    assertEquals(100, word(doc, "Z").b[1]);
  }

  @Test
  public void newWordsOnBothSidesOfOneWord() {
    OcrDoc doc = page();
    assertTrue(edit(doc, "The quick brownfox\nA jumps B over"));
    assertEquals("The quick brownfox A jumps B over", text(doc));
    for (String t : new String[] {"A", "jumps", "B"}) assertEquals(100, word(doc, t).b[1]);
    assertFalse(word(doc, "over").e);
    assertFalse(word(doc, "brownfox").e);
  }

  @Test
  public void everythingDeletedLeavesNoWords() {
    OcrDoc doc = page();
    assertTrue(edit(doc, "  "));
    assertTrue(doc.words.isEmpty());
  }

  @Test
  public void wordsJoinedAcrossLinesKeepOnlyTheirOwnLinesBox() {
    OcrDoc doc = page();
    assertTrue(edit(doc, "The quick brownfoxjumps over"));
    assertEquals("The quick brownfoxjumps over", text(doc));
    assertEquals(40, word(doc, "brownfoxjumps").b[3]);
  }

  @Test
  public void rightToLeftWordTypedApartStartsAtTheRight() {
    OcrDoc doc = page("سلامدنیا");
    assertTrue(edit(doc, "سلام دنیا"));
    assertArrayEquals(new int[] {40, 0, 40, 40}, word(doc, "سلام").b);
    assertArrayEquals(new int[] {0, 0, 40, 40}, word(doc, "دنیا").b);
  }

  @Test
  public void newWordsGetFreshIdsAndJoinTheirLine() {
    OcrDoc doc = page();
    OcrDoc.Line line = new OcrDoc.Line();
    line.id = 1;
    line.w = new int[] {1, 2, 3};
    doc.lines.add(line);
    assertTrue(edit(doc, "The quick brown fox\nover"));
    assertEquals(6, word(doc, "fox").id);
    assertArrayEquals(new int[] {1, 2, 3, 6}, line.w);
  }

  @Test
  public void columnOfAVerticalPageIsOnePiece() {
    // Two columns, right one first; the right column has two segments
    OcrDoc doc = new OcrDoc();
    int[][] boxes = {{100, 0, 40, 200}, {100, 210, 40, 100}, {0, 0, 40, 300}};
    String[] texts = {"日本", "語", "文字"};
    for (int i = 0; i < 3; i++) {
      OcrDoc.Word w = new OcrDoc.Word();
      w.id = i + 1;
      w.t = texts[i];
      w.b = boxes[i];
      doc.words.add(w);
    }
    List<List<OcrDoc.Word>> pieces = new ArrayList<>();
    pieces.add(new ArrayList<>(doc.words.subList(0, 2)));
    pieces.add(new ArrayList<>(doc.words.subList(2, 3)));
    List<String> pieceTexts = new ArrayList<>();
    pieceTexts.add("日本語");
    pieceTexts.add("文字");

    assertTrue(
        OcrTextEdit.apply(
            doc,
            pieces,
            pieceTexts,
            OcrTextEdit.Tokens.of("日本人\n文字", true),
            true));
    assertEquals(2, doc.words.size());
    assertEquals("日本人", doc.words.get(0).t);
    assertArrayEquals(new int[] {100, 0, 40, 310}, doc.words.get(0).b);
    assertFalse(doc.words.get(1).e);
  }
}
