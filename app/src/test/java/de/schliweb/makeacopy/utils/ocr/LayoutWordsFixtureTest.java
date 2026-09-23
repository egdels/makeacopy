/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Runs the layout logic (column policy + line grouping) on the word boxes that the Paddle
 * pipeline produced for the synthetic pages ({@code test/resources/layout_words/}, dumped by
 * {@code SyntheticPageRegressionTest}) and counts the reference lines that come out intact. No
 * recogniser involved: this pins the layout layer on its own, on real geometry.
 *
 * <p>The floors are the counts measured when the rules were introduced; recognition errors in
 * the dumps ("1Opellentesque", "unc") keep them below the line totals.
 */
public class LayoutWordsFixtureTest {

  record Word(String text, float l, float t, float r, float b) {}

  @Test
  public void wideGaps_singleColumn_isNotSplitIntoColumns() throws Exception {
    Page p = load("synth_wide_gaps");
    assertTrue("a wide justified column must not be read as columns", p.segments().isEmpty());
    assertAtLeastIntact(p, 28);
  }

  @Test
  public void loremColumns_threeColumnsInOrder() throws Exception {
    Page p = load("synth_lorem_columns");
    assertEquals(3, p.segments().size());
    List<String> lines = p.lines();
    assertTrue(lines.get(0).startsWith("01Lorem ipsum"));
    assertTrue(indexOfLine(lines, "Er hörte") > indexOfLine(lines, "ipsum primis"));
    assertTrue(indexOfLine(lines, "One one") > indexOfLine(lines, "Ausweg."));
    assertAtLeastIntact(p, 66);
  }

  @Test
  public void headlineSplit_headlineIsOneLineBeforeTheColumns() throws Exception {
    Page p = load("synth_headline_split");
    List<String> lines = p.lines();
    assertEquals("Fähre: Welche Fahrten fallen in dieser Woche aus?", lines.get(0));
    assertAtLeastIntact(p, 45);
  }

  @Test
  public void dropcapFooter_twoColumnsAndTheInitialReadsWithItsFirstLine() throws Exception {
    Page p = load("synth_dropcap_footer");
    assertEquals(2, p.segments().size());
    List<String> lines = p.lines();
    assertTrue(lines.contains("E s war ein ruhiger Abend im Hafen, als die alte"));
    assertTrue(indexOfLine(lines, "Die neue Fähre") > indexOfLine(lines, "Aufenthaltsraum"));
    assertAtLeastIntact(p, 43);
  }

  // ---------------------------------------------------------------------------------------

  record Page(List<Word> words, List<String> gt, List<int[]> segments) {
    /** Text lines in reading order: per segment (or the whole page), lines via LineGrouping. */
    List<String> lines() {
      List<String> out = new ArrayList<>();
      if (segments.isEmpty()) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) all.add(i);
        out.addAll(linesOf(all));
      } else {
        for (int[] seg : segments) {
          List<Integer> idx = new ArrayList<>();
          for (int i : seg) idx.add(i);
          out.addAll(linesOf(idx));
        }
      }
      return out;
    }

    private List<String> linesOf(List<Integer> idx) {
      int m = idx.size();
      float[] l = new float[m], t = new float[m], r = new float[m], b = new float[m];
      for (int i = 0; i < m; i++) {
        Word w = words.get(idx.get(i));
        l[i] = w.l();
        t[i] = w.t();
        r[i] = w.r();
        b[i] = w.b();
      }
      List<String> out = new ArrayList<>();
      for (int[] line : LineGrouping.groupIntoLines(l, t, r, b)) {
        StringBuilder sb = new StringBuilder();
        for (int i : line) {
          if (sb.length() > 0) sb.append(' ');
          sb.append(words.get(idx.get(i)).text());
        }
        out.add(sb.toString());
      }
      return out;
    }

    int intact() {
      List<String> lines = lines();
      int n = 0;
      for (String g : gt) if (lines.contains(g)) n++;
      return n;
    }
  }

  private static void assertAtLeastIntact(Page p, int floor) {
    int intact = p.intact();
    assertTrue("intact lines " + intact + " < floor " + floor, intact >= floor);
  }

  private static int indexOfLine(List<String> lines, String prefix) {
    for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(prefix)) return i;
    throw new AssertionError("no line starts with: " + prefix);
  }

  private static Page load(String name) throws Exception {
    List<Word> words = new ArrayList<>();
    try (InputStream in = resource("/layout_words/" + name + ".words.json")) {
      String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      JsonArray arr = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("words");
      for (int i = 0; i < arr.size(); i++) {
        JsonObject o = arr.get(i).getAsJsonObject();
        words.add(
            new Word(
                o.get("text").getAsString(),
                o.get("l").getAsFloat(),
                o.get("t").getAsFloat(),
                o.get("r").getAsFloat(),
                o.get("b").getAsFloat()));
      }
    }
    List<String> gt = new ArrayList<>();
    try (BufferedReader br =
        new BufferedReader(
            new InputStreamReader(resource("/layout_words/" + name + ".gt.txt"), StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) if (!line.isBlank()) gt.add(line.trim());
    }
    int n = words.size();
    float[] l = new float[n], t = new float[n], r = new float[n], b = new float[n];
    for (int i = 0; i < n; i++) {
      l[i] = words.get(i).l();
      t[i] = words.get(i).t();
      r[i] = words.get(i).r();
      b[i] = words.get(i).b();
    }
    return new Page(words, gt, MultiColumnLayoutPolicy.groupIntoColumnSegments(l, t, r, b, false));
  }

  private static InputStream resource(String path) {
    InputStream in = LayoutWordsFixtureTest.class.getResourceAsStream(path);
    if (in == null) throw new AssertionError("missing test resource " + path);
    return in;
  }
}
