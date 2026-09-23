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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Measures how many reference text lines survive an OCR run.
 *
 * <p>The comparison is deliberately tolerant of the things this test is not about: whitespace
 * and punctuation are stripped, case is ignored, and a reference line counts as present when
 * some window of the OCR text matches it with a similarity of at least {@link #PRESENT_RATIO}.
 * A line that is dropped by the detector scores far below that; a line with a few misread
 * characters scores above it.
 *
 * <p>"Intact" is the strict view: the reference line, whitespace-normalised, occurs verbatim
 * (case-sensitive) as one line of the OCR text. That catches word boundaries moved by the word
 * splitter and lines merged with a neighbour.
 *
 * <p>Pure Java, no Android dependencies, so the JVM unit test covers it.
 */
public final class LineCoverage {

  /** Similarity at or above which a reference line counts as present. */
  public static final double PRESENT_RATIO = 0.75;

  /** Reference lines shorter than this (after normalisation) are ignored: too ambiguous. */
  public static final int MIN_LINE_CHARS = 3;

  /** Result for one reference line. */
  public record LineResult(
      String reference,
      double similarity,
      boolean present,
      boolean intact,
      boolean intactInRows,
      String bestMatch) {}

  /** Aggregate over all reference lines of a page. */
  public record Report(
      List<LineResult> lines,
      int checked,
      int missing,
      int intact,
      int intactInRows,
      List<String> extraLines) {
    /** OCR lines whose words are mostly unknown to the reference: junk from rules, bars, noise. */
    public int extra() {
      return extraLines.size();
    }

    public List<LineResult> missingLines() {
      List<LineResult> out = new ArrayList<>();
      for (LineResult r : lines) if (!r.present()) out.add(r);
      return out;
    }

    public String summary() {
      return "checked=" + checked + " missing=" + missing + " intact=" + intact
          + " intactInRows=" + intactInRows + " extra=" + extraLines.size();
    }
  }

  private LineCoverage() {}

  /**
   * Compares the reference lines against the OCR text.
   *
   * @param referenceLines one reference line per entry, as drawn on the page
   * @param ocrText the OCR text with {@code \n} between lines
   */
  public static Report compare(List<String> referenceLines, String ocrText) {
    return compare(referenceLines, ocrText, ocrText);
  }

  /**
   * Same, but with separate texts for the two questions. Presence is judged on {@code
   * presenceText}, which should keep every line's words contiguous (a geometric row sort, where
   * a row just concatenates the columns); intactness on {@code intactText}, the text the user
   * gets (multi-column reading order), whose layout logic may scatter a line's words. {@code
   * intactInRows} counts the lines that are intact in {@code presenceText}: on a single-column
   * page that isolates the detector, the recogniser and the word splitter from the layout logic.
   */
  public static Report compare(List<String> referenceLines, String presenceText, String intactText) {
    String blob = normalize(presenceText);
    List<String> ocrLines = new ArrayList<>();
    for (String l : intactText.split("\n", -1)) {
      String t = collapseSpaces(l);
      if (!t.isEmpty()) ocrLines.add(t);
    }
    List<String> rowLines = new ArrayList<>();
    for (String l : presenceText.split("\n", -1)) {
      String t = collapseSpaces(l);
      if (!t.isEmpty()) rowLines.add(t);
    }
    List<LineResult> results = new ArrayList<>();
    int checked = 0, missing = 0, intact = 0, intactInRows = 0;
    for (String ref : referenceLines) {
      String n = normalize(ref);
      if (n.length() < MIN_LINE_CHARS) continue;
      checked++;
      Match m = bestWindow(n, blob);
      double sim = m.similarity();
      boolean present = sim >= PRESENT_RATIO;
      boolean isIntact = ocrLines.contains(collapseSpaces(ref));
      boolean inRows = rowLines.contains(collapseSpaces(ref));
      if (!present) missing++;
      if (isIntact) intact++;
      if (inRows) intactInRows++;
      results.add(new LineResult(ref, sim, present, isIntact, inRows, m.text()));
    }
    // Extra lines: OCR lines whose words are mostly unknown to the reference (junk from rules,
    // colour bars, noise). Judged by words, not by line similarity, so that a row which joins
    // two columns' lines does not count as junk.
    java.util.Set<String> vocabulary = new java.util.HashSet<>();
    for (String ref : referenceLines) {
      for (String w : ref.split("\\s+")) {
        String n = normalize(w);
        if (n.length() >= MIN_LINE_CHARS) vocabulary.add(n);
      }
    }
    List<String> extra = new ArrayList<>();
    for (String l : ocrLines) {
      int known = 0, total = 0;
      for (String w : l.split("\\s+")) {
        String n = normalize(w);
        if (n.length() < MIN_LINE_CHARS) continue;
        total++;
        if (vocabulary.contains(n)) known++;
      }
      if (total > 0 && known * 2 < total) extra.add(l);
    }
    return new Report(results, checked, missing, intact, intactInRows, extra);
  }

  /** Lower-cases and keeps only letters and digits, so spacing and punctuation do not count. */
  static String normalize(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    String lower = s.toLowerCase(Locale.ROOT);
    for (int i = 0; i < lower.length(); ) {
      int cp = lower.codePointAt(i);
      if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(cp);
      i += Character.charCount(cp);
    }
    return sb.toString();
  }

  static String collapseSpaces(String s) {
    return s.trim().replaceAll("\\s+", " ");
  }

  /**
   * Best similarity of {@code needle} against any window of its length in {@code hay}. Windows
   * start every quarter of the needle length; the similarity is 1 minus the Levenshtein distance
   * over the needle length, so it is 1 for an exact hit and about 0 for unrelated text.
   */
  static double bestWindowSimilarity(String needle, String hay) {
    return bestWindow(needle, hay).similarity();
  }

  /** The best-matching window of the OCR text (normalised) and its similarity. */
  record Match(double similarity, String text) {}

  static Match bestWindow(String needle, String hay) {
    int n = needle.length();
    if (n == 0) return new Match(0.0, "");
    if (hay.length() < n) return new Match(similarity(needle, hay), hay);
    int step = Math.max(1, n / 4);
    Match best = new Match(0.0, "");
    for (int i = 0; i + n <= hay.length(); i += step) {
      String w = hay.substring(i, i + n);
      double s = similarity(needle, w);
      if (s > best.similarity()) best = new Match(s, w);
      if (best.similarity() >= 0.999) break;
    }
    // Also try the tail window so a line at the very end is not missed by the stride.
    String tail = hay.substring(hay.length() - n);
    double t = similarity(needle, tail);
    if (t > best.similarity()) best = new Match(t, tail);
    return best;
  }

  static double similarity(String a, String b) {
    int d = levenshtein(a, b);
    int len = Math.max(a.length(), b.length());
    return len == 0 ? 1.0 : 1.0 - (double) d / len;
  }

  static int levenshtein(String a, String b) {
    int[] prev = new int[b.length() + 1];
    int[] cur = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) prev[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      cur[0] = i;
      char ca = a.charAt(i - 1);
      for (int j = 1; j <= b.length(); j++) {
        int cost = ca == b.charAt(j - 1) ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] t = prev;
      prev = cur;
      cur = t;
    }
    return prev[b.length()];
  }
}
