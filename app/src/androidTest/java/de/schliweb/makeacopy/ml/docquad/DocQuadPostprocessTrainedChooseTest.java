package de.schliweb.makeacopy.ml.docquad;

import static org.junit.Assert.assertArrayEquals;
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

/** M6d: Pfadwahl A/B (Corner vs Mask) muss deterministisch sein. */
@RunWith(AndroidJUnit4.class)
public class DocQuadPostprocessTrainedChooseTest extends DocQuadGoldenTestBase {

  private static final String TAG = "DocQuadChooseTest";

  @Test
  public void choose_isDeterministic_andConsistentWithFallbackFlag() throws Exception {
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

    // M6d: Standardpfad nutzt REFINE_3X3 für Corners.
    DocQuadPostprocessor.Result r1 =
        DocQuadPostprocessor.postprocess(out, DocQuadPostprocessor.PeakMode.REFINE_3X3);
    DocQuadPostprocessor.Result r2 =
        DocQuadPostprocessor.postprocess(out, DocQuadPostprocessor.PeakMode.REFINE_3X3);

    assertNotNull(r1);
    assertNotNull(r2);

    assertNotNull(r1.chosenSource());
    assertEquals(r1.chosenSource(), r2.chosenSource());

    Log.i(
        TAG,
        "chosenSource="
            + r1.chosenSource()
            + " quadFromMaskUsedFallback="
            + r1.quadFromMaskUsedFallback());

    assertNotNull(r1.chosenQuad256());
    assertEquals(4, r1.chosenQuad256().length);

    // Determinismus: exakte Gleichheit auf denselben Outputs.
    assertQuadEquals(r1.chosenQuad256(), r2.chosenQuad256());

    // Sanity: finite + grober Range.
    for (int i = 0; i < 4; i++) {
      assertNotNull(r1.chosenQuad256()[i]);
      assertEquals(2, r1.chosenQuad256()[i].length);
      double x = r1.chosenQuad256()[i][0];
      double y = r1.chosenQuad256()[i][1];
      assertTrue(Double.isFinite(x));
      assertTrue(Double.isFinite(y));
      assertTrue(x >= 0.0 && x <= 256.0);
      assertTrue(y >= 0.0 && y <= 256.0);
    }

    if (r1.chosenSource() == DocQuadPostprocessor.ChosenSource.MASK) {
      assertTrue(
          "MASK darf nicht gewählt werden, wenn Mask-Quad Fallback ist",
          !r1.quadFromMaskUsedFallback());
      assertQuadEquals(r1.quadFromMask256(), r1.chosenQuad256());
    } else {
      assertQuadEquals(r1.corners256(), r1.chosenQuad256());
    }
  }

  private static void assertQuadEquals(double[][] a, double[][] b) {
    assertNotNull(a);
    assertNotNull(b);
    assertEquals(4, a.length);
    assertEquals(4, b.length);
    for (int i = 0; i < 4; i++) {
      assertArrayEquals(a[i], b[i], 0.0);
    }
  }
}
