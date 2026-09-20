package de.schliweb.makeacopy.ml.docquad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.RectF;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.ml.corners.CornerDetectorFactory;
import de.schliweb.makeacopy.ml.corners.DetectionResult;
import de.schliweb.makeacopy.ml.corners.DocQuadDetector;
import de.schliweb.makeacopy.ml.corners.Source;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import java.io.InputStream;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test für Product Guardrails: Prüft das Postprocessing-Verhalten für ein bekanntes problematisches
 * Bild aus der Evaluierung.
 *
 * <p>Das Testbild {@code sample_20260124_174305_846_original.jpg} ist ein bekannter Outlier aus der
 * Evaluierung mit hohem MAE (568px) und niedrigem IoU (0.365). Der erste Test verifiziert, dass die
 * Evidence-Based Guardrails (Rules D/E/F) dieses Bild als "suspicious" erkennen; der zweite, dass
 * der Crop-Detektor es trotzdem korrekt löst (der frühere OpenCV-Fallback ist seit 9a2fc99
 * deaktiviert, siehe dort).
 *
 * <p>Laut Evaluierungsbericht wird dieses Bild mit {@code suspicious_for_product: true} und {@code
 * suspicious_reason: "LOW_PEAK_MARGIN"} (Rule D) markiert.
 */
@RunWith(AndroidJUnit4.class)
public class DocQuadSuspiciousImageTest {

  private static final String TEST_IMAGE_ASSET =
      "instrumented_test_data/sample_20260124_174305_846_original.jpg";

  @Test
  public void suspiciousImage_triggersSuspiciousForProduct() throws Exception {
    // target context -> App-APK assets (ONNX model + test image)
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

    Assume.assumeTrue(
        "trained tests disabled (set RUN_TRAINED_TESTS=1 to enable)",
        TrainedTestConfig.trainedTestsEnabled());

    String modelAsset = TrainedTestConfig.resolveTrainedModelAsset(ctx);

    // 1) Load test image from assets
    Bitmap srcBitmap;
    try (InputStream is = ctx.getAssets().open(TEST_IMAGE_ASSET)) {
      srcBitmap = BitmapFactory.decodeStream(is);
    }
    assertNotNull("Test image could not be loaded: " + TEST_IMAGE_ASSET, srcBitmap);

    int srcW = srcBitmap.getWidth();
    int srcH = srcBitmap.getHeight();
    assertTrue("Image width must be positive", srcW > 0);
    assertTrue("Image height must be positive", srcH > 0);

    // 2) Create letterbox and render to 256x256
    DocQuadLetterbox lb =
        DocQuadLetterbox.create(srcW, srcH, DocQuadOrtRunner.IN_W, DocQuadOrtRunner.IN_H);
    Bitmap in256 = renderLetterbox256(srcBitmap, lb);

    // 3) Convert to NCHW float array
    float[] input = bitmapToNchwFloat01(in256);

    // 4) Run ONNX inference
    DocQuadOrtRunner.Outputs outputs;
    try (DocQuadOrtRunner runner = new DocQuadOrtRunner(ctx, modelAsset)) {
      outputs = runner.run(input);
    }
    assertNotNull("ONNX outputs must not be null", outputs);

    // 5) Run postprocessor with REFINE_3X3 (product mode)
    DocQuadPostprocessor.Result result =
        DocQuadPostprocessor.postprocess(outputs, lb, DocQuadPostprocessor.PeakMode.REFINE_3X3);
    assertNotNull("Postprocessor result must not be null", result);

    // 6) Log the result for diagnostic purposes
    android.util.Log.i(
        "DocQuadSuspiciousImageTest",
        "Outlier image result: chosenSource="
            + result.chosenSource()
            + ", penaltyCorners="
            + result.penaltyCorners()
            + ", penaltyMask="
            + result.penaltyMask()
            + ", suspiciousForProduct="
            + result.suspiciousForProduct()
            + ", suspiciousReason="
            + result.suspiciousReason());

    // 7) MAIN ASSERTION: This known outlier image MUST trigger suspiciousForProduct
    // The evaluation report shows this image is flagged with suspicious_reason: "LOW_PEAK_MARGIN"
    // (Rule D: Heatmap peak evidence - low margin indicates model uncertainty)
    assertTrue(
        "Expected suspiciousForProduct=true for image "
            + TEST_IMAGE_ASSET
            + " but got false. suspiciousReason="
            + result.suspiciousReason()
            + ", chosenSource="
            + result.chosenSource()
            + ", penaltyCorners="
            + result.penaltyCorners()
            + ", penaltyMask="
            + result.penaltyMask(),
        result.suspiciousForProduct());

    // 8) Verify that a valid suspiciousReason is set (one of Rules D/E/F)
    assertNotNull(
        "suspiciousReason must not be null when suspiciousForProduct is true",
        result.suspiciousReason());
    // Expected reasons from evidence-based guardrails: LOW_PEAK_MARGIN, MASK_DIFFUSE,
    // CHOSEN_MASK_INCONSISTENT
    // or from earlier rules: DISAGREE_64PX, MASK_FALLBACK_AND_PCORNER, GEOMETRY_IMPLAUSIBLE
    assertTrue(
        "suspiciousReason must be a known reason string",
        result.suspiciousReason().equals("LOW_PEAK_MARGIN")
            || result.suspiciousReason().equals("MASK_DIFFUSE")
            || result.suspiciousReason().equals("CHOSEN_MASK_INCONSISTENT")
            || result.suspiciousReason().equals("DISAGREE_64PX")
            || result.suspiciousReason().equals("MASK_FALLBACK_AND_PCORNER")
            || result.suspiciousReason().equals("GEOMETRY_IMPLAUSIBLE"));

    // Cleanup
    if (!srcBitmap.isRecycled()) srcBitmap.recycle();
    if (!in256.isRecycled()) in256.recycle();
  }

  // Ground truth of TEST_IMAGE_ASSET in stored pixel coordinates (TL,TR,BR,BL), image 3024x4032.
  private static final double[][] OUTLIER_GROUND_TRUTH = {
    {161.1, 1753.5}, {2090.7, 609.6}, {2940.7, 1801.8}, {1000.4, 3104.2}
  };

  /**
   * End-to-end check of what the product does with this known outlier (a book lying at ~30° on a
   * striped cloth) in the crop screen.
   *
   * <p>History: the suspicious guard once made {@code DocQuadDetector} fail for such images so that
   * the composite detector fell back to OpenCV. The guard has been disabled since 9a2fc99, and a
   * fallback would not have helped here anyway: OpenCV finds no document in this photo at all. The
   * intended outcome (no wrong quad for the outlier) is delivered today by the crop-screen detector:
   *
   * <ol>
   *   <li>the single model pass is still unsure about this image (low confidence), and
   *   <li>{@link CornerDetectorFactory#docQuadForCrop} rescues it with a rotated pass and returns an
   *       accurate quad.
   * </ol>
   */
  @Test
  public void knownOutlier_isRescuedByCropDetector() throws Exception {
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

    Assume.assumeTrue(
        "trained tests disabled (set RUN_TRAINED_TESTS=1 to enable)",
        TrainedTestConfig.trainedTestsEnabled());
    Assume.assumeTrue(
        "test-time rotation is disabled", BuildConfig.FEATURE_DOCQUAD_ROTATION_TTA);

    Bitmap srcBitmap;
    try (InputStream is = ctx.getAssets().open(TEST_IMAGE_ASSET)) {
      srcBitmap = BitmapFactory.decodeStream(is);
    }
    assertNotNull("Test image could not be loaded: " + TEST_IMAGE_ASSET, srcBitmap);
    int srcW = srcBitmap.getWidth();
    int srcH = srcBitmap.getHeight();

    // Same pre-scale as the crop screen (TrapezoidSelectionView.initializeCornersAsync()).
    float scale = Math.min(1f, OpenCVUtils.DETECTION_MAX_EDGE / (float) Math.max(srcW, srcH));
    Bitmap work =
        Bitmap.createScaledBitmap(
            srcBitmap, Math.round(srcW * scale), Math.round(srcH * scale), true);

    DocQuadOrtRunner runner =
        DocQuadOrtRunner.getInstance(ctx, DocQuadDetector.DEFAULT_MODEL_ASSET_PATH);
    DetectionResult single = new DocQuadDetector(runner).detect(work, ctx);
    DetectionResult crop = CornerDetectorFactory.docQuadForCrop(runner).detect(work, ctx);

    android.util.Log.i(
        "DocQuadSuspiciousImageTest",
        "single pass: success="
            + single.success
            + ", confidence="
            + single.confidence
            + " | crop detector: success="
            + crop.success
            + ", chosenSource="
            + crop.chosenSource
            + ", confidence="
            + crop.confidence);

    // 1) The single pass must not be confident about this image.
    assertNotNull("single pass must report a confidence", single.confidence);
    assertTrue(
        "Expected a low single-pass confidence for the outlier, got " + single.confidence,
        single.confidence < 0.5);

    // 2) The crop detector must rescue it with a rotated pass ...
    assertTrue("Expected the crop detector to succeed", crop.success);
    assertEquals(Source.DOCQUAD, crop.source);
    assertNotNull(crop.chosenSource);
    assertTrue(
        "Expected a rotated pass to win, got chosenSource=" + crop.chosenSource,
        crop.chosenSource.startsWith("CORNERS_ROT"));
    assertNotNull(crop.confidence);
    assertTrue(
        "Expected the rotated pass to be more confident than the single pass",
        crop.confidence > single.confidence);

    // 3) ... and the quad must be accurate: mean corner error below 3% of the image diagonal
    // (minimised over cyclic corner orderings, as in CornerPipelineEvalTest).
    double diag = Math.hypot(srcW, srcH);
    double best = Double.MAX_VALUE;
    for (int shift = 0; shift < 4; shift++) {
      double sum = 0.0;
      for (int k = 0; k < 4; k++) {
        double[] c = crop.cornersOriginalTLTRBRBL[(k + shift) % 4];
        sum +=
            Math.hypot(
                c[0] / scale - OUTLIER_GROUND_TRUTH[k][0],
                c[1] / scale - OUTLIER_GROUND_TRUTH[k][1]);
      }
      best = Math.min(best, sum / 4.0 / diag);
    }
    assertTrue(
        "Expected mean corner error < 3% of the diagonal, got "
            + String.format(java.util.Locale.US, "%.2f%%", best * 100.0),
        best < 0.03);

    if (work != srcBitmap && !work.isRecycled()) work.recycle();
    if (!srcBitmap.isRecycled()) srcBitmap.recycle();
  }

  /** Preprocess exakt wie Training: RGB, 0..1, NCHW float32. */
  private static float[] bitmapToNchwFloat01(Bitmap bmp) {
    int w = bmp.getWidth();
    int h = bmp.getHeight();
    if (w != DocQuadOrtRunner.IN_W || h != DocQuadOrtRunner.IN_H) {
      throw new IllegalArgumentException("bitmap must be 256x256");
    }
    int hw = h * w;
    float[] out = new float[3 * hw];
    int[] px = new int[hw];
    bmp.getPixels(px, 0, w, 0, 0, w, h);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int c = px[y * w + x];
        float r = ((c >> 16) & 0xFF) / 255.0f;
        float g = ((c >> 8) & 0xFF) / 255.0f;
        float b = (c & 0xFF) / 255.0f;
        int idx = y * w + x;
        out[0 + idx] = r;
        out[hw + idx] = g;
        out[2 * hw + idx] = b;
      }
    }
    return out;
  }

  private static Bitmap renderLetterbox256(Bitmap src, DocQuadLetterbox lb) {
    Bitmap out =
        Bitmap.createBitmap(DocQuadOrtRunner.IN_W, DocQuadOrtRunner.IN_H, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(out);
    canvas.drawColor(android.graphics.Color.BLACK);

    float left = (float) lb.offsetX;
    float top = (float) lb.offsetY;
    float right = (float) (lb.offsetX + (double) lb.srcW * lb.scale);
    float bottom = (float) (lb.offsetY + (double) lb.srcH * lb.scale);
    RectF dst = new RectF(left, top, right, bottom);
    canvas.drawBitmap(src, null, dst, null);
    return out;
  }
}
