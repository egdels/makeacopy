/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ml.docquad;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.ml.corners.DocQuadDetector;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Latency benchmark for DocQuad inference. Breaks the product path into stages and compares ORT
 * execution-provider / thread configurations on the device under test.
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

  private interface SessionConfig {
    void apply(OrtSession.SessionOptions opts) throws Exception;
  }

  @Test
  public void benchmarkDocQuadLatency() throws Exception {
    String flag = InstrumentationRegistry.getArguments().getString("RUN_BENCHMARKS", "");
    Assume.assumeTrue(
        "benchmarks disabled (pass -e RUN_BENCHMARKS 1)",
        !(flag.isEmpty() || flag.equals("0") || flag.equalsIgnoreCase("false")));

    Context appCtx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    OpenCVUtils.init(appCtx);

    Bitmap full;
    try (InputStream is = appCtx.getAssets().open(TEST_IMAGE_ASSET)) {
      full = BitmapFactory.decodeStream(is);
    }
    float s =
        Math.min(
            1f, OpenCVUtils.DETECTION_MAX_EDGE / (float) Math.max(full.getWidth(), full.getHeight()));
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
    DocQuadOrtRunner runner =
        DocQuadOrtRunner.getInstance(appCtx, DocQuadDetector.DEFAULT_MODEL_ASSET_PATH);
    DocQuadDetector detector = new DocQuadDetector(runner);
    float[] input = new float[3 * DocQuadOrtRunner.IN_H * DocQuadOrtRunner.IN_W];
    Arrays.fill(input, 0.5f);
    DocQuadLetterbox lb =
        DocQuadLetterbox.create(
            work.getWidth(), work.getHeight(), DocQuadOrtRunner.IN_W, DocQuadOrtRunner.IN_H);
    final DocQuadOrtRunner.Outputs[] lastOutputs = new DocQuadOrtRunner.Outputs[1];

    md.append("## Product path (current session config)\n\n");
    md.append("| stage | median ms | p90 ms | min ms |\n|---|---|---|---|\n");
    row(md, "DocQuadDetector.detect (total)", measure(() -> detector.detect(work, appCtx)));
    row(md, "DocQuadOrtRunner.run (ORT + output copy)", measure(() -> lastOutputs[0] = runner.run(input)));
    row(
        md,
        "DocQuadPostprocessor.postprocess",
        measure(
            () ->
                DocQuadPostprocessor.postprocess(
                    lastOutputs[0], lb, DocQuadPostprocessor.PeakMode.REFINE_5X5_QUADRATIC)));

    // --- 2) Raw ORT sessions with different configurations --------------------------------------
    File model = copyModel(appCtx);
    int cores = Runtime.getRuntime().availableProcessors();
    Map<String, SessionConfig> configs = new LinkedHashMap<>();
    configs.put(
        "previous default: NNAPI + XNNPACK, intra=" + Math.max(1, cores / 2),
        o -> {
          o.setIntraOpNumThreads(Math.max(1, cores / 2));
          o.addNnapi();
          o.addXnnpack(Collections.emptyMap());
        });
    configs.put(
        "NNAPI only, intra=" + Math.max(1, cores / 2),
        o -> {
          o.setIntraOpNumThreads(Math.max(1, cores / 2));
          o.addNnapi();
        });
    for (int t : new int[] {1, 2, 4}) {
      configs.put("CPU only, intra=" + t, o -> o.setIntraOpNumThreads(t));
    }
    configs.put(
        "XNNPACK (own pool 2), intra=1",
        o -> {
          o.setIntraOpNumThreads(1);
          o.addConfigEntry("session.intra_op.allow_spinning", "0");
          o.addXnnpack(Collections.singletonMap("intra_op_num_threads", "2"));
        });
    configs.put(
        "XNNPACK (own pool 4), intra=1",
        o -> {
          o.setIntraOpNumThreads(1);
          o.addConfigEntry("session.intra_op.allow_spinning", "0");
          o.addXnnpack(Collections.singletonMap("intra_op_num_threads", "4"));
        });
    configs.put(
        "XNNPACK (default opts), intra=" + Math.max(1, cores / 2),
        o -> {
          o.setIntraOpNumThreads(Math.max(1, cores / 2));
          o.addXnnpack(Collections.emptyMap());
        });

    md.append("\n## ORT session.run only (same model, same input)\n\n");
    md.append("| config | create ms | median ms | p90 ms | min ms |\n|---|---|---|---|---|\n");
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    long[] shape = new long[] {1, 3, DocQuadOrtRunner.IN_H, DocQuadOrtRunner.IN_W};
    for (Map.Entry<String, SessionConfig> e : configs.entrySet()) {
      try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        e.getValue().apply(opts);
        long t0 = SystemClock.elapsedRealtimeNanos();
        try (OrtSession session = env.createSession(model.getAbsolutePath(), opts)) {
          double createMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6;
          double[] times =
              measure(
                  () -> {
                    try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
                        OrtSession.Result r = session.run(Collections.singletonMap("input", in))) {
                      r.get(0).getValue();
                      r.get(1).getValue();
                    }
                  });
          md.append(
              String.format(
                  Locale.US,
                  "| %s | %.0f | %.1f | %.1f | %.1f |\n",
                  e.getKey(),
                  createMs,
                  percentile(times, 0.5),
                  percentile(times, 0.9),
                  times[0]));
        }
      } catch (Throwable t) {
        md.append(String.format(Locale.US, "| %s | failed: %s | | | |\n", e.getKey(), t));
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

  private static File copyModel(Context ctx) throws Exception {
    File out = new File(ctx.getCacheDir(), "bench_docquad.ort");
    try (InputStream is = ctx.getAssets().open(DocQuadDetector.DEFAULT_MODEL_ASSET_PATH);
        FileOutputStream fos = new FileOutputStream(out)) {
      byte[] buf = new byte[256 * 1024];
      int n;
      while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
    }
    return out;
  }
}
