package de.schliweb.makeacopy.ml.docquad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** M6c: Mask→Quad Sanity-Test (trained ONNX, deterministisch, ohne Magic-Values). */
@RunWith(AndroidJUnit4.class)
public class DocQuadPostprocessTrainedMaskQuadTest extends DocQuadGoldenTestBase {

  private static final String TAG = "DocQuadMaskQuad";

  @Test
  public void maskQuad_isDeterministic_andInReasonableBounds() throws Exception {
    // target context -> App-APK assets (trained ONNX)
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

    Assume.assumeTrue(
        "trained tests disabled (set RUN_TRAINED_TESTS=1 to enable)",
        TrainedTestConfig.trainedTestsEnabled());

    String modelAsset = TrainedTestConfig.resolveTrainedModelAsset(ctx);

    float[] input = makeGoldenInputV1Nchw();

    DocQuadRunner.Outputs out;
    try (DocQuadRunner runner = new DocQuadRunner(ctx, modelAsset)) {
      out = runner.run(input);
    }
    assertNotNull(out);

    DocQuadPostprocessor.Result r1 =
        DocQuadPostprocessor.postprocess(out, DocQuadPostprocessor.PeakMode.REFINE_3X3);
    DocQuadPostprocessor.Result r2 =
        DocQuadPostprocessor.postprocess(out, DocQuadPostprocessor.PeakMode.REFINE_3X3);

    assertNotNull(r1);
    assertNotNull(r2);
    assertNotNull(r1.quadFromMask256());
    assertEquals(4, r1.quadFromMask256().length);

    // Logcat wird von Gradle/UTP eingesammelt; System.out ist nicht zuverlässig sichtbar.
    Log.i(TAG, "quadFromMaskUsedFallback=" + r1.quadFromMaskUsedFallback());

    assertEquals(r1.quadFromMaskUsedFallback(), r2.quadFromMaskUsedFallback());

    for (int i = 0; i < 4; i++) {
      assertNotNull(r1.quadFromMask256()[i]);
      assertEquals(2, r1.quadFromMask256()[i].length);

      double x = r1.quadFromMask256()[i][0];
      double y = r1.quadFromMask256()[i][1];
      assertTrue(Double.isFinite(x));
      assertTrue(Double.isFinite(y));

      // PCA-Quad kann minimal über 254 hinausgehen, sollte aber nicht wild sein.
      assertTrue(x >= 0.0 && x <= 256.0);
      assertTrue(y >= 0.0 && y <= 256.0);

      // Determinismus: exakte Gleichheit.
      assertEquals(x, r2.quadFromMask256()[i][0], 0.0);
      assertEquals(y, r2.quadFromMask256()[i][1], 0.0);
    }

    double areaAbs = Math.abs(shoelaceArea(r1.quadFromMask256()));
    assertTrue(areaAbs > 0.0);

    if (r1.quadFromMaskUsedFallback()) {
      // Minimaler Fallback: quad == corners256 (exact)
      assertNotNull(r1.corners256());
      for (int i = 0; i < 4; i++) {
        assertEquals(r1.corners256()[i][0], r1.quadFromMask256()[i][0], 0.0);
        assertEquals(r1.corners256()[i][1], r1.quadFromMask256()[i][1], 0.0);
      }
    }
  }

  private static double shoelaceArea(double[][] pts) {
    double s = 0.0;
    for (int i = 0; i < 4; i++) {
      int j = (i + 1) % 4;
      s += pts[i][0] * pts[j][1] - pts[j][0] * pts[i][1];
    }
    return 0.5 * s;
  }
}
