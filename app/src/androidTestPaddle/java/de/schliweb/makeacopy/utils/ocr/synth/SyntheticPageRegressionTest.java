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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.OCRPostProcessor;
import de.schliweb.makeacopy.utils.ocr.OcrRecognitionPipeline;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Runs the synthetic layout pages (see {@code assets/synth/README.md}) through the same Paddle
 * pipeline the OCR screen uses and checks that no reference line is lost.
 *
 * <p>Criteria, checked against the page's {@code .gt.txt}:
 *
 * <ul>
 *   <li>Where a {@code .reference.json} from the reference PaddleOCR pipeline exists, the app
 *       must not lose any line that the reference pipeline finds (the reference is the oracle;
 *       lines it loses itself, such as the two beside a drop cap, are not held against the app).
 *   <li>Without a reference, no line may be lost at all.
 * </ul>
 *
 * <p>Reported, not asserted: how many lines came through intact (exact text on one line), the
 * metric for the word splitter and the line grouping.
 */
@RunWith(AndroidJUnit4.class)
public class SyntheticPageRegressionTest {

  private static final String TAG = "SynthPageRegression";
  private static final String DIR = "synth";

  @Test
  public void loremColumns_noLineLost() throws Exception {
    assertNoLineLost("synth_lorem_columns");
  }

  @Test
  public void dropcapFooter_noLineLost() throws Exception {
    assertNoLineLost("synth_dropcap_footer");
  }

  @Test
  public void headlineSplit_noLineLost() throws Exception {
    assertNoLineLost("synth_headline_split");
  }

  /** Wide justified gaps: the recogniser tends to drop the spaces, the word splitter must not
   * scramble the line when it puts them back. */
  @Test
  public void wideGaps_noLineLost() throws Exception {
    assertNoLineLost("synth_wide_gaps");
  }

  /** Colour bars, rules, underlines and table lines must neither cost text lines nor become text. */
  @Test
  public void rulesBars_noLineLost_noJunk() throws Exception {
    LineCoverage.Report app = assertNoLineLost("synth_rules_bars");
    for (String l : app.extraLines()) Log.i(TAG, "synth_rules_bars EXTRA [" + l + "]");
    assertTrue("junk lines from non-text elements: " + app.extraLines(), app.extra() <= 2);
  }

  /** Faint print: the app must keep every line the reference pipeline detects. */
  @Test
  public void faintPrint_noLineLost() throws Exception {
    assertNoLineLost("synth_faint_print");
  }

  /** A centred verse column beside a justified column is still a column. */
  @Test
  public void centeredColumn_noLineLost() throws Exception {
    assertNoLineLost("synth_centered_column");
  }

  /** Sub-headings over two of three columns separate the page into bands, even with a short overhang. */
  @Test
  public void subheadings_noLineLost() throws Exception {
    assertNoLineLost("synth_subheadings");
  }

  /** Huge glyphs are a known detector limit: measured and logged, the captions must survive. */
  @Test
  public void bigGlyphs_captionsSurvive() throws Exception {
    LineCoverage.Report app = runApp("synth_big_glyphs");
    for (LineCoverage.LineResult r : app.lines()) {
      if (!r.reference().equals("37 %")) {
        assertTrue("caption line lost: " + r.reference(), r.present());
      }
    }
  }

  private LineCoverage.Report assertNoLineLost(String name) throws Exception {
    LineCoverage.Report app = runApp(name);
    LineCoverage.Report ref = runReference(name);
    Set<String> tolerated = new HashSet<>();
    if (ref != null) {
      Log.i(TAG, name + " reference pipeline " + ref.summary());
      for (LineCoverage.LineResult r : ref.missingLines()) tolerated.add(r.reference());
    }
    StringBuilder msg = new StringBuilder(name + ": lines lost by the app that the reference finds:");
    int lost = 0;
    for (LineCoverage.LineResult r : app.missingLines()) {
      if (tolerated.contains(r.reference())) continue;
      lost++;
      msg.append("\n  ")
          .append(String.format("%.2f", r.similarity()))
          .append("  ")
          .append(r.reference())
          .append("  ~  ")
          .append(r.bestMatch());
    }
    assertEquals(msg.toString(), 0, lost);
    return app;
  }

  private LineCoverage.Report runApp(String name) throws Exception {
    Context ctx = ApplicationProvider.getApplicationContext();
    AssetManager am = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
    Bitmap bm;
    try (InputStream is = am.open(DIR + "/" + name + ".png")) {
      bm = BitmapFactory.decodeStream(is);
    }
    assertNotNull("asset must decode: " + name, bm);
    List<String> gt = readLines(am, DIR + "/" + name + ".gt.txt");

    OCRHelper helper = new OCRHelper(ctx);
    helper.setReinitPerRun(false);
    helper.setLanguage("deu");
    helper.setPaddleHighQualityDetectionEnabled(true);
    assertTrue("engine init", helper.initTesseract());
    try {
      OcrRecognitionPipeline.Outcome outcome =
          OcrRecognitionPipeline.recognizeBestRotation(
              helper,
              bm,
              new OcrRecognitionPipeline.Options(OCRHelper.OCR_MODE_PADDLE, false, false),
              () -> false);
      assertNotNull("no OCR result", outcome.best());
      List<RecognizedWord> words = outcome.best().result().words;
      if (words == null) words = new ArrayList<>();
      // Presence on a purely geometric row sort of the word boxes (the same as for the
      // reference fixture), so that only the detector and the recogniser count; intactness on the
      // multi-column text the user sees, where the line grouping and the column logic count too.
      List<Box> boxes = new ArrayList<>();
      for (RecognizedWord w : words) {
        if (w.getBoundingBox() == null || w.getText() == null) continue;
        boxes.add(new Box(w.getText(), w.getBoundingBox().left, w.getBoundingBox().top,
            w.getBoundingBox().height()));
      }
      String rowText = rowsToText(boxes);
      String multiColumn = OCRPostProcessor.wordsToText(words, true);
      dumpWords(ctx, name, words);
      LineCoverage.Report report = LineCoverage.compare(gt, rowText, multiColumn);
      Log.i(TAG, name + " app " + report.summary());
      // Diagnostics: every non-intact reference line next to the closest multi-column OCR line.
      List<String> ocrLines = new ArrayList<>();
      for (String l : multiColumn.split("\n")) if (!l.trim().isEmpty()) ocrLines.add(l.trim());
      for (LineCoverage.LineResult r : report.lines()) {
        if (r.intact()) continue;
        String best = "";
        double bestSim = -1;
        for (String l : ocrLines) {
          double sim = LineCoverage.similarity(LineCoverage.normalize(r.reference()), LineCoverage.normalize(l));
          if (sim > bestSim) { bestSim = sim; best = l; }
        }
        Log.i(TAG, name + " NOT-INTACT [" + r.reference() + "]  ~  [" + best + "]");
      }
      return report;
    } finally {
      helper.close();
      bm.recycle();
    }
  }

  /** Coverage of the reference pipeline's output, or null when no fixture exists for the page. */
  private LineCoverage.Report runReference(String name) throws Exception {
    AssetManager am = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
    String json;
    try (InputStream is = am.open(DIR + "/" + name + ".reference.json")) {
      json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException missing) {
      return null;
    }
    List<String> gt = readLines(am, DIR + "/" + name + ".gt.txt");
    return LineCoverage.compare(gt, referenceText(new JSONObject(json).getJSONArray("words")));
  }

  /** A text box: one word (app) or one detected line (reference pipeline). */
  private record Box(String text, double left, double top, double height) {}

  /** Reference fixture entries as boxes; detector order is not reading order, so rows are rebuilt. */
  private static String referenceText(JSONArray words) throws Exception {
    List<Box> boxes = new ArrayList<>();
    for (int i = 0; i < words.length(); i++) {
      JSONObject w = words.getJSONObject(i);
      JSONArray poly = w.getJSONArray("polygon");
      double left = Double.MAX_VALUE, top = Double.MAX_VALUE, bottom = -Double.MAX_VALUE;
      for (int p = 0; p < poly.length(); p++) {
        JSONArray pt = poly.getJSONArray(p);
        left = Math.min(left, pt.getDouble(0));
        top = Math.min(top, pt.getDouble(1));
        bottom = Math.max(bottom, pt.getDouble(1));
      }
      boxes.add(new Box(w.getString("text"), left, top, bottom - top));
    }
    return rowsToText(boxes);
  }

  /**
   * Sorts boxes into rows by their top edge (tolerance 0.6 x median box height) and left to right
   * within a row, one row per line. A row concatenates all columns, which is fine for the
   * whitespace-insensitive presence check because every line's words stay contiguous.
   */
  private static String rowsToText(List<Box> boxes) {
    if (boxes.isEmpty()) return "";
    List<Double> heights = new ArrayList<>();
    for (Box b : boxes) heights.add(b.height());
    heights.sort(null);
    double tol = 0.6 * heights.get(heights.size() / 2);
    List<Box> sorted = new ArrayList<>(boxes);
    sorted.sort(Comparator.comparingDouble(Box::top).thenComparingDouble(Box::left));
    List<List<Box>> rows = new ArrayList<>();
    List<Box> row = new ArrayList<>();
    double rowTop = 0;
    for (Box b : sorted) {
      if (!row.isEmpty() && Math.abs(b.top() - rowTop) > tol) {
        rows.add(row);
        row = new ArrayList<>();
      }
      if (row.isEmpty()) rowTop = b.top();
      row.add(b);
    }
    rows.add(row);
    StringBuilder sb = new StringBuilder();
    for (List<Box> r : rows) {
      r.sort(Comparator.comparingDouble(Box::left));
      for (int i = 0; i < r.size(); i++) {
        if (i > 0) sb.append(' ');
        sb.append(r.get(i).text());
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  /**
   * Writes the recognised words with their boxes to {@code <external files>/synth-words/<name>.words.json}
   * so that a run can be turned into a JVM fixture for the layout policies (see
   * {@code app/src/test/resources/layout_words/}). Pull with {@code adb pull
   * /sdcard/Android/data/<app>/files/synth-words}; the connected test task uninstalls the app
   * afterwards, so install and run the test with {@code am instrument} to keep the files.
   */
  private static void dumpWords(Context ctx, String name, List<RecognizedWord> words) {
    try {
      java.io.File dir = ctx.getExternalFilesDir("synth-words");
      if (dir == null) return;
      org.json.JSONArray arr = new org.json.JSONArray();
      for (RecognizedWord w : words) {
        if (w.getBoundingBox() == null) continue;
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("text", w.getText());
        o.put("l", Math.round(w.getBoundingBox().left * 10) / 10.0);
        o.put("t", Math.round(w.getBoundingBox().top * 10) / 10.0);
        o.put("r", Math.round(w.getBoundingBox().right * 10) / 10.0);
        o.put("b", Math.round(w.getBoundingBox().bottom * 10) / 10.0);
        o.put("c", Math.round(w.getConfidence()));
        arr.put(o);
      }
      try (java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(dir, name + ".words.json"))) {
        fw.write(new JSONObject().put("page", name).put("words", arr).toString(1));
      }
    } catch (Exception e) {
      Log.w(TAG, "word dump failed: " + e);
    }
  }

  private static List<String> readLines(AssetManager am, String path) throws IOException {
    List<String> out = new ArrayList<>();
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(am.open(path), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (!line.trim().isEmpty()) out.add(line);
      }
    }
    return out;
  }
}
