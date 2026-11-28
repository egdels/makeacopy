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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.utils.ocr.paddle.PaddleOcrEngine;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * OCR on the sample set under {@code assets/eval/samples}: 23 languages, each as a clean scan and
 * as a phone photo, made by {@code scripts/synth_samples.py}. Logs per sample CER, WER and how many
 * ground-truth words were found (in any order), and the means; soft, nothing is asserted. With
 * {@code -e samplesDir <dir>} the images and {@code .gt.txt} files in that directory on the device
 * are read instead, e.g. CORD's receipts cut out by {@code scripts/cord_samples.py} and pushed with
 * adb (words found is the measure there: their CER hangs on whether the price column is read
 * beside or after the items).
 *
 * <p>Arguments: {@code -e paddleLanguage <code>} selects that language for every page (e.g.
 * {@code latin}, {@code deu}); without it each sample is read with its own language, as a user who
 * chose correctly. {@code -e samplesAsset <dir>} reads another asset directory ({@code eval}:
 * MakeACopy's photos and scans, {@code eval/jpn}, {@code synth}). {@code -e paddleBest false}
 * turns "Best quality" (detection at 1920 px, the app's default) off.
 *
 * <p>The time of the first sample includes loading the models; compare times only from a phone
 * and a release build.
 */
@RunWith(AndroidJUnit4.class)
public class SampleSetEvalTest {

  private static final String TAG = "SampleSetEval";

  private static final class Sample {
    final String name;
    final String language;
    final Bitmap bitmap;
    final String truth;

    Sample(String name, String language, Bitmap bitmap, String truth) {
      this.name = name;
      this.language = language;
      this.bitmap = bitmap;
      this.truth = truth;
    }
  }

  @Test
  public void samples() throws Exception {
    Context ctx = ApplicationProvider.getApplicationContext();
    List<Sample> samples = load();
    assumeTrue("no samples", !samples.isEmpty());
    OcrEngine engine = PaddleEngineProvider.create(ctx, null);
    assertNotNull(engine);
    configure(engine);
    double cerSum = 0;
    double werSum = 0;
    int found = 0;
    int words = 0;
    long ms = 0;
    for (Sample s : samples) {
      engine.setLanguage(language(s));
      long t0 = System.nanoTime();
      OCRHelper.OcrResultWords r = engine.run(s.bitmap);
      long dt = (System.nanoTime() - t0) / 1_000_000L;
      String text = normalize(r != null && r.text != null ? r.text : "");
      String truth = normalize(s.truth);
      double cer = OcrErrorRates.cer(truth, text);
      double wer = OcrErrorRates.wer(truth, text);
      int f = wordsFound(truth, text);
      int n = truth.isEmpty() ? 0 : truth.split("\\s+").length;
      cerSum += cer;
      werSum += wer;
      found += f;
      words += n;
      ms += dt;
      Log.i(
          TAG,
          String.format(
              Locale.ROOT,
              "sample=%s lang=%s cer=%.3f wer=%.3f words=%d/%d ms=%d",
              s.name,
              s.language,
              cer,
              wer,
              f,
              n,
              dt));
    }
    engine.close();
    Log.i(
        TAG,
        String.format(
            Locale.ROOT,
            "MEAN set=%s best=%s language=%s n=%d cer=%.3f wer=%.3f words=%d/%d ms=%d",
            arg("samplesDir").isEmpty() ? assetDir() : arg("samplesDir"),
            !"false".equals(arg("paddleBest")),
            arg("paddleLanguage").isEmpty() ? "own" : arg("paddleLanguage"),
            samples.size(),
            cerSum / samples.size(),
            werSum / samples.size(),
            found,
            words,
            ms));
  }

  private static void configure(OcrEngine engine) {
    if (engine instanceof PaddleOcrEngine paddle) {
      paddle.setHighQualityDetectionEnabled(!"false".equals(arg("paddleBest")));
    }
  }

  private static String language(Sample s) {
    String l = arg("paddleLanguage");
    return l.isEmpty() ? s.language : l;
  }

  private static String assetDir() {
    return arg("samplesAsset").isEmpty() ? "eval/samples" : arg("samplesAsset");
  }

  private static String arg(String key) {
    Bundle b = InstrumentationRegistry.getArguments();
    String v = b.getString(key);
    return v == null ? "" : v;
  }

  private static List<Sample> load() throws IOException {
    String dir = arg("samplesDir");
    List<Sample> out = new ArrayList<>();
    if (!dir.isEmpty()) {
      File[] files = new File(dir).listFiles();
      if (files == null) return out;
      List<String> names = new ArrayList<>();
      for (File f : files) if (isImage(f.getName())) names.add(f.getName());
      Collections.sort(names);
      for (String name : names) {
        File gt = new File(dir, base(name) + ".gt.txt");
        if (!gt.exists()) continue;
        try (InputStream in = new FileInputStream(new File(dir, name));
            InputStream t = new FileInputStream(gt)) {
          out.add(sample(name, BitmapFactory.decodeStream(in), read(t)));
        }
      }
      return out;
    }
    AssetManager am = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
    String assetDir = assetDir();
    String[] entries = am.list(assetDir);
    if (entries == null) return out;
    List<String> names = new ArrayList<>();
    for (String e : entries) if (isImage(e)) names.add(e);
    Collections.sort(names);
    for (String name : names) {
      String gt = assetDir + "/" + base(name) + ".gt.txt";
      if (!java.util.Arrays.asList(entries).contains(base(name) + ".gt.txt")) continue;
      try (InputStream in = am.open(assetDir + "/" + name);
          InputStream t = am.open(gt)) {
        out.add(sample(name, BitmapFactory.decodeStream(in), read(t)));
      }
    }
    return out;
  }

  /**
   * The sample's language is in its name ({@code language_<code>}, {@code language_<code>_scan},
   * {@code language_<code>_photo}); the Japanese pages name {@code jpn}, any other page is read as
   * English.
   */
  private static Sample sample(String file, Bitmap bitmap, String truth) {
    String name = base(file);
    String language = name.contains("jpn") ? "jpn" : "eng";
    if (name.startsWith("language_")) {
      language = name.substring("language_".length()).replaceFirst("_(scan|photo)$", "");
    }
    return new Sample(name, language, bitmap, truth);
  }

  private static boolean isImage(String name) {
    String l = name.toLowerCase(Locale.ROOT);
    return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg");
  }

  private static String base(String name) {
    return name.replaceFirst("\\.[^.]+$", "");
  }

  private static String read(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    return out.toString(StandardCharsets.UTF_8.name());
  }

  private static String normalize(String s) {
    return s.replace("\r\n", "\n").replace('\r', '\n').trim();
  }

  /** How many words of {@code truth} are in {@code text}, each as often as it is there. */
  static int wordsFound(String truth, String text) {
    Map<String, Integer> have = new HashMap<>();
    for (String w : text.split("\\s+")) if (!w.isEmpty()) have.merge(w, 1, Integer::sum);
    int found = 0;
    for (String w : truth.split("\\s+")) {
      if (w.isEmpty()) continue;
      Integer left = have.get(w);
      if (left != null && left > 0) {
        have.put(w, left - 1);
        found++;
      }
    }
    return found;
  }
}
