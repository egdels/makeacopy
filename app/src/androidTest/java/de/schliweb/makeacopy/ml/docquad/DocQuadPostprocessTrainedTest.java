package de.schliweb.makeacopy.ml.docquad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * M6a: Minimaler, deterministischer Postprocessing-Pfad (Argmax-Quad + Mask-Stats) für das
 * trainierte ONNX (ohne UI).
 */
@RunWith(AndroidJUnit4.class)
public class DocQuadPostprocessTrainedTest extends DocQuadGoldenTestBase {

  @Test
  public void postprocess_isDeterministic_andCornersAreInBounds() throws Exception {
    // target context -> App-APK assets (trained ONNX)
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

    Assume.assumeTrue(
        "trained tests disabled (set RUN_TRAINED_TESTS=1 to enable)",
        TrainedTestConfig.trainedTestsEnabled());

    String modelAsset = TrainedTestConfig.resolveTrainedModelAsset(ctx);

    float[] input = makeGoldenInputV1Nchw();

    DocQuadOrtRunner.Outputs out;
    try (DocQuadOrtRunner runner = new DocQuadOrtRunner(ctx, modelAsset)) {
      out = runner.run(input);
    }
    assertNotNull(out);

    DocQuadPostprocessor.Result r1 = DocQuadPostprocessor.postprocess(out);
    DocQuadPostprocessor.Result r2 = DocQuadPostprocessor.postprocess(out);

    assertNotNull(r1);
    assertNotNull(r2);
    assertNotNull(r1.corners256());
    assertEquals(4, r1.corners256().length);

    // Determinismus: exakte Gleichheit auf denselben Outputs.
    assertEquals(r1.maskProbGt05Count(), r2.maskProbGt05Count());
    assertEquals(r1.maskProbMean(), r2.maskProbMean(), 0.0);

    Set<String> distinct = new HashSet<>();
    for (int i = 0; i < 4; i++) {
      assertNotNull(r1.corners256()[i]);
      assertEquals(2, r1.corners256()[i].length);

      double x = r1.corners256()[i][0];
      double y = r1.corners256()[i][1];
      assertTrue(Double.isFinite(x));
      assertTrue(Double.isFinite(y));
      assertTrue(x >= 2.0 && x <= 254.0);
      assertTrue(y >= 2.0 && y <= 254.0);

      // deterministische Gleichheit
      assertEquals(x, r2.corners256()[i][0], 0.0);
      assertEquals(y, r2.corners256()[i][1], 0.0);

      distinct.add(x + "," + y);
    }

    // Nicht alle Punkte identisch (mindestens 3 distinct genügen hier).
    assertTrue(distinct.size() >= 3);

    // Einfache Area-Check (sollte > 0 sein, wenn Punkte nicht degeneriert sind).
    double areaAbs = Math.abs(shoelaceArea(r1.corners256()));
    assertTrue(areaAbs > 0.0);

    // Mask-Statistik: Count liegt im gültigen Range.
    assertTrue(r1.maskProbGt05Count() >= 0 && r1.maskProbGt05Count() <= 64 * 64);
  }

  private static double shoelaceArea(double[][] pts) {
    // TL, TR, BR, BL
    double s = 0.0;
    for (int i = 0; i < 4; i++) {
      int j = (i + 1) % 4;
      s += pts[i][0] * pts[j][1] - pts[j][0] * pts[i][1];
    }
    return 0.5 * s;
  }
}
