/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.crop;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.ml.corners.CornerDetector;
import de.schliweb.makeacopy.ml.corners.CornerDetectorFactory;
import de.schliweb.makeacopy.ml.corners.DetectionResult;
import de.schliweb.makeacopy.ml.corners.DocQuadDetector;
import de.schliweb.makeacopy.ml.corners.EdgeSnapCornerRefiner;
import de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner;
import de.schliweb.makeacopy.ml.docquad.DocQuadTestRunners;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.core.Point;

/**
 * Measurement harness for the crop corner pipeline on a real-world test set with ground-truth
 * corners. It is the on-device counterpart of {@code training/scripts/evaluate_docquad_models.py}:
 * the Python script measures the model, this test measures what the app actually does with it
 * (OpenCV candidate, best-of selection, edge-snap refinement).
 *
 * <p>The test set uses the layout from {@code training/EVALUATION.md} ({@code images/} + {@code
 * labels/*.json} with {@code corners_px} in TL,TR,BR,BL order) and is bundled into the androidTest
 * APK only on request:
 *
 * <pre>
 * ./gradlew :app:connectedStandardDebugAndroidTest \
 *   -PcornerEvalDir=training/data/docquad_real_eval \
 *   -Pandroid.testInstrumentationRunnerArguments.class=de.schliweb.makeacopy.ui.crop.CornerPipelineEvalTest
 * </pre>
 *
 * <p>Reports ({@code corner_eval_report.md/.json}) are written to AGP's additional test output
 * directory and pulled to {@code app/build/outputs/connected_android_test_additional_output/}.
 * Without a bundled test set the test is skipped. It never fails on quality: it measures.
 */
@RunWith(AndroidJUnit4.class)
public class CornerPipelineEvalTest {

  private static final String TAG = "CornerPipelineEval";
  private static final String ASSET_ROOT = "corner_eval";

  /** Max corner error (relative to the image diagonal) up to which a sample counts as "good". */
  private static final double GOOD_MAX_REL = 0.01;

  /** IoU threshold for a "match", same default as the Python evaluator. */
  private static final double MATCH_IOU = 0.90;

  private static final String[] BASE_STAGES = {"docquad", "opencv", "bestof"};

  @Test
  public void evaluateCornerPipeline() throws Exception {
    Context testCtx = InstrumentationRegistry.getInstrumentation().getContext();
    Context appCtx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    AssetManager assets = testCtx.getAssets();

    String[] labelFiles = assets.list(ASSET_ROOT + "/labels");
    Assume.assumeTrue(
        "no corner eval set bundled (pass -PcornerEvalDir=<dir with images/ and labels/>)",
        labelFiles != null && labelFiles.length > 0);
    Arrays.sort(labelFiles);

    OpenCVUtils.init(appCtx);
    // A candidate model bundled via -PcornerEvalModel replaces the shipped one for this run.
    String[] candidateModels = assets.list(ASSET_ROOT + "/model");
    String modelName = DocQuadDetector.DEFAULT_MODEL_ASSET_PATH;
    DocQuadOrtRunner runner;
    if (candidateModels != null && candidateModels.length > 0) {
      modelName = ASSET_ROOT + "/model/" + candidateModels[0];
      runner = DocQuadTestRunners.fromAssets(testCtx, appCtx, modelName);
    } else {
      runner = DocQuadOrtRunner.getInstance(appCtx, modelName);
    }
    Log.i(TAG, "model: " + modelName);
    CornerDetector docQuad = CornerDetectorFactory.docQuadForCrop(runner);
    DocQuadDetector docQuadPlain = new DocQuadDetector(runner);

    Map<String, StageStats> stats = new LinkedHashMap<>();
    // Single unrotated model pass, as used by the live preview; reference for the crop detector.
    stats.put("docquad_plain", new StageStats());
    for (String base : BASE_STAGES) {
      stats.put(base, new StageStats());
      stats.put(base + "+refine", new StageStats());
    }
    SelectionStats selection = new SelectionStats();
    JSONArray samplesJson = new JSONArray();
    JSONArray invalidLabels = new JSONArray();
    int skipped = 0;

    for (String labelFile : labelFiles) {
      if (!labelFile.endsWith(".json")) continue;
      JSONObject label =
          new JSONObject(readAssetText(assets, ASSET_ROOT + "/labels/" + labelFile));
      String imageName = label.getString("image");
      double[][] gt = parseQuad(label.getJSONArray("corners_px"));

      Bitmap bmp;
      try (InputStream is = assets.open(ASSET_ROOT + "/images/" + imageName)) {
        bmp = BitmapFactory.decodeStream(is);
      }
      if (bmp == null) {
        Log.w(TAG, "skip " + imageName + ": cannot decode");
        skipped++;
        continue;
      }
      int w = bmp.getWidth();
      int h = bmp.getHeight();
      if (label.has("width") && (label.getInt("width") != w || label.getInt("height") != h)) {
        // Labels are in stored-pixel coordinates (no EXIF rotation), like BitmapFactory decodes.
        Log.w(TAG, "skip " + imageName + ": label size does not match decoded " + w + "x" + h);
        skipped++;
        bmp.recycle();
        continue;
      }
      if (!isInsideImage(gt, w, h)) {
        // Typical cause: corners annotated on the EXIF-rotated view of the photo. Measuring
        // against such a label would silently count correct detections as gross failures.
        Log.w(TAG, "skip " + imageName + ": ground-truth corners lie outside the image");
        invalidLabels.put(imageName);
        skipped++;
        bmp.recycle();
        continue;
      }

      // Same pre-scale as TrapezoidSelectionView.initializeCornersAsync().
      float s = Math.min(1f, OpenCVUtils.DETECTION_MAX_EDGE / (float) Math.max(w, h));
      Bitmap work =
          s < 1f ? Bitmap.createScaledBitmap(bmp, Math.round(w * s), Math.round(h * s), true) : bmp;

      JSONObject sampleJson = new JSONObject();
      sampleJson.put("image", imageName);
      sampleJson.put("width", w);
      sampleJson.put("height", h);

      DetectionResult plain = docQuadPlain.detect(work, appCtx);
      Point[] plainPts =
          plain != null && plain.success
              ? TrapezoidSelectionView.pointsFromDetectionResult(plain)
              : null;
      TrapezoidSelectionView.scaleImageQuadToOriginal(plainPts, s);
      if (plain != null && plain.success && plain.confidence != null) {
        sampleJson.put("docquad_plain_confidence", plain.confidence);
      }

      long t0 = SystemClock.uptimeMillis();
      DetectionResult dr = docQuad.detect(work, appCtx);
      long docQuadMs = SystemClock.uptimeMillis() - t0;
      Point[] docPts =
          dr != null && dr.success ? TrapezoidSelectionView.pointsFromDetectionResult(dr) : null;
      TrapezoidSelectionView.scaleImageQuadToOriginal(docPts, s);
      if (dr != null && dr.success) {
        sampleJson.put("docquad_chosen_source", dr.chosenSource);
        if (dr.confidence != null) sampleJson.put("docquad_confidence", dr.confidence);
      }

      t0 = SystemClock.uptimeMillis();
      OpenCVUtils.OpenCvCornerDetection cv =
          OpenCVUtils.detectDocumentCornersWithOpenCvMetadata(appCtx, work);
      long openCvMs = SystemClock.uptimeMillis() - t0;
      Point[] cvPts = cv != null && !cv.fallbackRectangle() ? cv.corners() : null;
      boolean cvFromHough = cv != null && cv.fromHoughFallback();
      TrapezoidSelectionView.scaleImageQuadToOriginal(cvPts, s);
      if (work != bmp) work.recycle();

      // chooseBestCropCorners clamps in place and returns one of its arguments: pass copies so
      // the raw candidates stay measurable, and identify the pick by reference.
      Point[] docForChoice = copy(docPts);
      Point[] cvForChoice = copy(cvPts);
      Point[] best =
          TrapezoidSelectionView.chooseBestCropCorners(
              docForChoice,
              dr != null && dr.success ? dr.confidence : null,
              cvForChoice,
              cvFromHough,
              w,
              h);
      String picked = best == null ? "none" : (best == docForChoice ? "docquad" : "opencv");
      sampleJson.put("bestof_picked", picked);
      // Inputs of the best-of policy, so alternative policies can be simulated from the report.
      if (TrapezoidSelectionView.isValidImageQuad(docForChoice, w, h)) {
        sampleJson.put("docquad_shape_score", TrapezoidSelectionView.scoreImageQuad(docForChoice, w, h));
      }
      if (TrapezoidSelectionView.isValidImageQuad(cvForChoice, w, h)) {
        sampleJson.put("opencv_shape_score", TrapezoidSelectionView.scoreImageQuad(cvForChoice, w, h));
      }
      sampleJson.put("opencv_from_hough", cvFromHough);
      sampleJson.put("docquad_ms", docQuadMs);
      sampleJson.put("opencv_ms", openCvMs);

      Map<String, Point[]> candidates = new LinkedHashMap<>();
      candidates.put("docquad", docPts);
      candidates.put("opencv", cvPts);
      candidates.put("bestof", best);

      Map<String, CornerEvalMetrics.QuadError> errors = new LinkedHashMap<>();
      record(stats.get("docquad_plain"), sampleJson, "docquad_plain", plainPts, gt, w, h);
      for (String base : BASE_STAGES) {
        Point[] pts = candidates.get(base);
        errors.put(base, record(stats.get(base), sampleJson, base, pts, gt, w, h));

        Point[] refined = null;
        if (pts != null) {
          // The product refines only validated in-image quads.
          Point[] in = copy(pts);
          TrapezoidSelectionView.clampImageQuadToBounds(in, w, h);
          t0 = SystemClock.uptimeMillis();
          refined = EdgeSnapCornerRefiner.refine(bmp, in);
          if ("bestof".equals(base)) {
            sampleJson.put("refine_ms", SystemClock.uptimeMillis() - t0);
          }
        }
        String stage = base + "+refine";
        errors.put(stage, record(stats.get(stage), sampleJson, stage, refined, gt, w, h));
      }
      selection.add(picked, errors.get("docquad"), errors.get("opencv"));

      samplesJson.put(sampleJson);
      bmp.recycle();
    }

    String markdown =
        "model: " + modelName + "\n\n" + renderMarkdown(stats, selection, samplesJson.length(), skipped);
    if (invalidLabels.length() > 0) {
      markdown += "\n## Invalid labels (ground truth outside the image, skipped)\n\n";
      for (int i = 0; i < invalidLabels.length(); i++) {
        markdown += "- " + invalidLabels.getString(i) + "\n";
      }
    }
    for (String line : markdown.split("\n", -1)) Log.i(TAG, line);

    JSONObject report = new JSONObject();
    JSONObject meta = new JSONObject();
    meta.put("model", modelName);
    meta.put("detection_max_edge", OpenCVUtils.DETECTION_MAX_EDGE);
    meta.put("good_max_rel", GOOD_MAX_REL);
    meta.put("match_iou", MATCH_IOU);
    meta.put("samples", samplesJson.length());
    meta.put("skipped", skipped);
    meta.put("invalid_labels", invalidLabels);
    report.put("meta", meta);
    JSONObject stagesJson = new JSONObject();
    for (Map.Entry<String, StageStats> e : stats.entrySet()) {
      stagesJson.put(e.getKey(), e.getValue().toJson());
    }
    report.put("stages", stagesJson);
    report.put("selection", selection.toJson());
    report.put("samples", samplesJson);

    File outDir = resolveOutputDir(appCtx);
    writeText(new File(outDir, "corner_eval_report.json"), report.toString(2));
    writeText(new File(outDir, "corner_eval_report.md"), markdown);
    Log.i(TAG, "reports written to " + outDir.getAbsolutePath());
  }

  private static CornerEvalMetrics.QuadError record(
      StageStats stageStats,
      JSONObject sampleJson,
      String stage,
      Point[] pts,
      double[][] gt,
      int w,
      int h)
      throws Exception {
    if (pts == null || pts.length != 4) {
      stageStats.fails++;
      sampleJson.put(stage, JSONObject.NULL);
      return null;
    }
    double[][] quad = new double[4][2];
    JSONArray cornersJson = new JSONArray();
    for (int i = 0; i < 4; i++) {
      quad[i][0] = pts[i].x;
      quad[i][1] = pts[i].y;
      cornersJson.put(new JSONArray().put(round1(pts[i].x)).put(round1(pts[i].y)));
    }
    CornerEvalMetrics.QuadError e = CornerEvalMetrics.error(quad, gt, w, h);
    stageStats.add(e);
    JSONObject o = new JSONObject();
    o.put("corners_px", cornersJson);
    o.put("mean_px", round1(e.meanPx));
    o.put("max_px", round1(e.maxPx));
    o.put("mean_rel", e.meanRel);
    o.put("max_rel", e.maxRel);
    o.put("iou", e.iou);
    o.put("order_shift", e.orderShift);
    sampleJson.put(stage, o);
    return e;
  }

  /** Aggregates one pipeline stage over all samples. */
  private static final class StageStats {
    final List<Double> meanRel = new ArrayList<>();
    final List<Double> maxRel = new ArrayList<>();
    final List<Double> iou = new ArrayList<>();
    int fails;
    int good;
    int match;
    int orderShifted;

    void add(CornerEvalMetrics.QuadError e) {
      meanRel.add(e.meanRel);
      maxRel.add(e.maxRel);
      iou.add(e.iou);
      if (e.maxRel <= GOOD_MAX_REL) good++;
      if (e.iou >= MATCH_IOU) match++;
      if (e.orderShift != 0) orderShifted++;
    }

    JSONObject toJson() throws Exception {
      JSONObject o = new JSONObject();
      o.put("n", meanRel.size());
      o.put("fails", fails);
      o.put("mean_rel_mean", nanToNull(CornerEvalMetrics.mean(meanRel)));
      o.put("mean_rel_median", nanToNull(CornerEvalMetrics.percentile(meanRel, 0.5)));
      o.put("max_rel_p90", nanToNull(CornerEvalMetrics.percentile(maxRel, 0.9)));
      o.put("max_rel_worst", nanToNull(CornerEvalMetrics.percentile(maxRel, 1.0)));
      o.put("iou_mean", nanToNull(CornerEvalMetrics.mean(iou)));
      o.put("good", good);
      o.put("match", match);
      o.put("order_shifted", orderShifted);
      return o;
    }
  }

  /** How often the best-of policy picked the candidate that was actually closer to ground truth. */
  private static final class SelectionStats {
    int bothAvailable;
    int pickedBetter;
    int pickedWorse;
    final List<Double> regretRel = new ArrayList<>();

    void add(String picked, CornerEvalMetrics.QuadError doc, CornerEvalMetrics.QuadError cv) {
      if (doc == null || cv == null || "none".equals(picked)) return;
      bothAvailable++;
      double pickedErr = "docquad".equals(picked) ? doc.meanRel : cv.meanRel;
      double otherErr = "docquad".equals(picked) ? cv.meanRel : doc.meanRel;
      if (pickedErr <= otherErr) {
        pickedBetter++;
      } else {
        pickedWorse++;
        regretRel.add(pickedErr - otherErr);
      }
    }

    JSONObject toJson() throws Exception {
      JSONObject o = new JSONObject();
      o.put("both_available", bothAvailable);
      o.put("picked_better", pickedBetter);
      o.put("picked_worse", pickedWorse);
      o.put("regret_rel_mean", nanToNull(CornerEvalMetrics.mean(regretRel)));
      o.put("regret_rel_worst", nanToNull(CornerEvalMetrics.percentile(regretRel, 1.0)));
      return o;
    }
  }

  private static String renderMarkdown(
      Map<String, StageStats> stats, SelectionStats selection, int samples, int skipped) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Corner pipeline evaluation\n\n");
    sb.append(String.format(Locale.US, "samples=%d skipped=%d\n\n", samples, skipped));
    sb.append("Errors are relative to the image diagonal (1.00% of a 12 MP photo is ~50 px).\n\n");
    sb.append(
        "| stage | n | fail | mean err | median err | p90 max err | worst max err | mean IoU |"
            + " good (max<=1%) | match (IoU>=0.90) | order shifted |\n");
    sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (Map.Entry<String, StageStats> e : stats.entrySet()) {
      StageStats st = e.getValue();
      sb.append(
          String.format(
              Locale.US,
              "| %s | %d | %d | %s | %s | %s | %s | %.3f | %d | %d | %d |\n",
              e.getKey(),
              st.meanRel.size(),
              st.fails,
              pct(CornerEvalMetrics.mean(st.meanRel)),
              pct(CornerEvalMetrics.percentile(st.meanRel, 0.5)),
              pct(CornerEvalMetrics.percentile(st.maxRel, 0.9)),
              pct(CornerEvalMetrics.percentile(st.maxRel, 1.0)),
              CornerEvalMetrics.mean(st.iou),
              st.good,
              st.match,
              st.orderShifted));
    }
    sb.append("\n## Best-of selection\n\n");
    sb.append(
        String.format(
            Locale.US,
            "both candidates valid: %d, picked the better one: %d, picked the worse one: %d"
                + " (mean regret %s, worst %s)\n",
            selection.bothAvailable,
            selection.pickedBetter,
            selection.pickedWorse,
            pct(CornerEvalMetrics.mean(selection.regretRel)),
            pct(CornerEvalMetrics.percentile(selection.regretRel, 1.0))));
    return sb.toString();
  }

  private static String pct(double rel) {
    return Double.isNaN(rel) ? "-" : String.format(Locale.US, "%.2f%%", rel * 100.0);
  }

  private static Object nanToNull(double v) {
    return Double.isNaN(v) ? JSONObject.NULL : (Object) v;
  }

  private static double round1(double v) {
    return Math.round(v * 10.0) / 10.0;
  }

  private static Point[] copy(Point[] pts) {
    if (pts == null) return null;
    Point[] out = new Point[pts.length];
    for (int i = 0; i < pts.length; i++) out[i] = pts[i] == null ? null : pts[i].clone();
    return out;
  }

  /** Ground truth may touch the border, but not leave the image by more than 2% of its size. */
  private static boolean isInsideImage(double[][] quad, int w, int h) {
    double tol = 0.02 * Math.max(w, h);
    for (double[] p : quad) {
      if (p[0] < -tol || p[0] > w + tol || p[1] < -tol || p[1] > h + tol) return false;
    }
    return true;
  }

  private static double[][] parseQuad(JSONArray arr) throws Exception {
    if (arr.length() != 4) throw new IllegalArgumentException("corners_px must have 4 points");
    double[][] q = new double[4][2];
    for (int i = 0; i < 4; i++) {
      q[i][0] = arr.getJSONArray(i).getDouble(0);
      q[i][1] = arr.getJSONArray(i).getDouble(1);
    }
    return q;
  }

  private static String readAssetText(AssetManager assets, String path) throws Exception {
    try (InputStream is = assets.open(path);
        ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
      return bos.toString(StandardCharsets.UTF_8.name());
    }
  }

  /**
   * AGP passes {@code additionalTestOutputDir} and pulls its content before uninstalling the app;
   * the app-private external dir is only a fallback for manual {@code am instrument} runs.
   */
  private static File resolveOutputDir(Context appCtx) {
    String arg = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir");
    File dir =
        arg != null && !arg.isEmpty()
            ? new File(arg)
            : new File(appCtx.getExternalFilesDir(null), "corner_eval_report");
    if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "cannot create " + dir);
    return dir;
  }

  private static void writeText(File f, String text) throws Exception {
    try (FileOutputStream fos = new FileOutputStream(f)) {
      fos.write(text.getBytes(StandardCharsets.UTF_8));
    }
  }
}
