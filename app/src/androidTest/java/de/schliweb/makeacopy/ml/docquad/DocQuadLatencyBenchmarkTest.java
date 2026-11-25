/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ml.docquad;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.ml.corners.DocQuadDetector;
import de.schliweb.makeacopy.ml.ncnn.NcnnNet;
import de.schliweb.makeacopy.utils.image.ImageOps;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Latency benchmark for DocQuad inference. Breaks the product path into stages and compares NCNN
 * thread counts on the device under test.
 *
 * <p>Disabled by default; run with {@code -e RUN_BENCHMARKS 1}. It measures, it never fails on
 * timing. The report goes to logcat (tag {@code DocQuadBench}) and to {@code
 * docquad_latency_report.md} in the additional test output dir (or the app's external files dir).
 */
@RunWith(AndroidJUnit4.class)
public class DocQuadLatencyBenchmarkTest {

  private static final String TAG = "DocQuadBench";
  private static final String TEST_IMAGE_ASSET = "instrumented_test_data/20251007_183138.jpg";
  private static final int WARMUP = 5;
  private static final int RUNS = 30;

  private interface Step {
    void run() throws Exception;
  }

  @Test
  public void benchmarkDocQuadLatency() throws Exception {
    String flag = InstrumentationRegistry.getArguments().getString("RUN_BENCHMARKS", "");
    Assume.assumeTrue(
        "benchmarks disabled (pass -e RUN_BENCHMARKS 1)",
        !(flag.isEmpty() || flag.equals("0") || flag.equalsIgnoreCase("false")));

    Context appCtx = InstrumentationRegistry.getInstrumentation().getTargetContext();

    Bitmap full;
    try (InputStream is = appCtx.getAssets().open(TEST_IMAGE_ASSET)) {
      full = BitmapFactory.decodeStream(is);
    }
    float s =
        Math.min(
            1f, ImageOps.DETECTION_MAX_EDGE / (float) Math.max(full.getWidth(), full.getHeight()));
    Bitmap work =
        Bitmap.createScaledBitmap(
            full, Math.round(full.getWidth() * s), Math.round(full.getHeight() * s), true);

    StringBuilder md = new StringBuilder();
    md.append("# DocQuad latency benchmark\n\n");
    md.append(
        String.format(
            Locale.US,
            "device=%s %s, api=%d, cores=%d, debuggable build, warmup=%d, runs=%d\n\n",
            android.os.Build.MANUFACTURER,
            android.os.Build.MODEL,
            android.os.Build.VERSION.SDK_INT,
            Runtime.getRuntime().availableProcessors(),
            WARMUP,
            RUNS));

    // --- 1) Product path, stage by stage -------------------------------------------------------
    DocQuadRunner runner =
        DocQuadRunner.getInstance(appCtx, DocQuadDetector.DEFAULT_MODEL_ASSET_PATH);
    DocQuadDetector detector = new DocQuadDetector(runner);
    float[] input = new float[3 * DocQuadRunner.IN_H * DocQuadRunner.IN_W];
    Arrays.fill(input, 0.5f);
    DocQuadLetterbox lb =
        DocQuadLetterbox.create(
            work.getWidth(), work.getHeight(), DocQuadRunner.IN_W, DocQuadRunner.IN_H);
    final DocQuadRunner.Outputs[] lastOutputs = new DocQuadRunner.Outputs[1];

    md.append("## Product path (shipped configuration)\n\n");
    md.append("| stage | median ms | p90 ms | min ms |\n|---|---|---|---|\n");
    row(md, "DocQuadDetector.detect (total)", measure(() -> detector.detect(work, appCtx)));
    row(md, "DocQuadRunner.run (NCNN + output copy)", measure(() -> lastOutputs[0] = runner.run(input)));
    row(
        md,
        "DocQuadPostprocessor.postprocess",
        measure(
            () ->
                DocQuadPostprocessor.postprocess(
                    lastOutputs[0], lb, DocQuadPostprocessor.PeakMode.REFINE_5X5_QUADRATIC)));

    // --- 2) The bare network with different thread counts -------------------------------------
    int cores = Runtime.getRuntime().availableProcessors();
    md.append("\n## NCNN run only (same model, same input)\n\n");
    md.append("| threads | load ms | median ms | p90 ms | min ms |\n|---|---|---|---|---|\n");
    String[] outputs = {"out0", "out1"};
    for (int threads : new int[] {1, 2, 4, cores}) {
      if (threads > cores) continue;
      long t0 = SystemClock.elapsedRealtimeNanos();
      try (NcnnNet net =
          NcnnNet.fromAssets(
              appCtx.getAssets(), DocQuadDetector.DEFAULT_MODEL_ASSET_PATH, threads)) {
        double loadMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6;
        double[] times =
            measure(() -> net.run(input, 3, DocQuadRunner.IN_H, DocQuadRunner.IN_W, outputs));
        md.append(
            String.format(
                Locale.US,
                "| %d | %.0f | %.1f | %.1f | %.1f |\n",
                threads,
                loadMs,
                percentile(times, 0.5),
                percentile(times, 0.9),
                times[0]));
      } catch (Throwable t) {
        md.append(String.format(Locale.US, "| %d | failed: %s | | | |\n", threads, t));
      }
    }

    String report = md.toString();
    for (String line : report.split("\n", -1)) Log.i(TAG, line);
    String arg = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir");
    File dir =
        arg != null && !arg.isEmpty()
            ? new File(arg)
            : new File(appCtx.getExternalFilesDir(null), "docquad_latency_report");
    if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "cannot create " + dir);
    try (FileOutputStream fos = new FileOutputStream(new File(dir, "docquad_latency_report.md"))) {
      fos.write(report.getBytes(StandardCharsets.UTF_8));
    }
    work.recycle();
    full.recycle();
  }

  private static void row(StringBuilder md, String name, double[] sortedMs) {
    md.append(
        String.format(
            Locale.US,
            "| %s | %.1f | %.1f | %.1f |\n",
            name,
            percentile(sortedMs, 0.5),
            percentile(sortedMs, 0.9),
            sortedMs[0]));
  }

  /** Returns sorted run times in ms after warm-up. */
  private static double[] measure(Step step) throws Exception {
    for (int i = 0; i < WARMUP; i++) step.run();
    double[] ms = new double[RUNS];
    for (int i = 0; i < RUNS; i++) {
      long t0 = SystemClock.elapsedRealtimeNanos();
      step.run();
      ms[i] = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6;
    }
    Arrays.sort(ms);
    return ms;
  }

  private static double percentile(double[] sorted, double p) {
    int idx = (int) Math.ceil(p * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
  }


}
