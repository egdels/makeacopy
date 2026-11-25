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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * A/B comparison between {@link DocQuadPostprocessor.PeakMode#REFINE_3X3} and {@link
 * DocQuadPostprocessor.PeakMode#REFINE_5X5_QUADRATIC} on the trained ONNX model with the canonical
 * golden input.
 *
 * <p>Goal: provide a sanity baseline for activating the 5×5 mode by default after eval-set
 * comparison. The test does not assert that 5×5 is "better" — that requires ground truth — only
 * that it produces a consistent, plausible refinement near the 3×3 result, with no regressions in
 * determinism, bounds, or convexity.
 *
 * <p>Skipped unless trained tests are explicitly enabled (see {@link
 * TrainedTestConfig#trainedTestsEnabled()}).
 */
@RunWith(AndroidJUnit4.class)
public class DocQuadPostprocessRefineCompareTest extends DocQuadGoldenTestBase {

  /** Max acceptable subpixel drift between 3×3 and 5×5 refinement, in 256-grid pixels. */
  private static final double MAX_DRIFT_PX_256 = 4.0;

  @Test
  public void refine5x5_isDeterministic_andCloseTo3x3_onGoldenInput() throws Exception {
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

    DocQuadPostprocessor.Result r3a =
        DocQuadPostprocessor.postprocess(out, DocQuadPostprocessor.PeakMode.REFINE_3X3);
    DocQuadPostprocessor.Result r5a =
        DocQuadPostprocessor.postprocess(out, DocQuadPostprocessor.PeakMode.REFINE_5X5_QUADRATIC);
    DocQuadPostprocessor.Result r5b =
        DocQuadPostprocessor.postprocess(out, DocQuadPostprocessor.PeakMode.REFINE_5X5_QUADRATIC);

    assertNotNull(r3a);
    assertNotNull(r5a);
    assertNotNull(r5b);
    assertNotNull(r3a.corners256());
    assertNotNull(r5a.corners256());
    assertEquals(4, r3a.corners256().length);
    assertEquals(4, r5a.corners256().length);

    // Determinism of the 5×5 refinement: identical inputs ⇒ identical outputs.
    for (int i = 0; i < 4; i++) {
      assertEquals(r5a.corners256()[i][0], r5b.corners256()[i][0], 0.0);
      assertEquals(r5a.corners256()[i][1], r5b.corners256()[i][1], 0.0);
    }

    // Bounds + finiteness for both modes.
    for (int i = 0; i < 4; i++) {
      double x3 = r3a.corners256()[i][0];
      double y3 = r3a.corners256()[i][1];
      double x5 = r5a.corners256()[i][0];
      double y5 = r5a.corners256()[i][1];
      assertTrue(Double.isFinite(x3) && Double.isFinite(y3));
      assertTrue(Double.isFinite(x5) && Double.isFinite(y5));
      assertTrue(x3 >= 0.0 && x3 <= 256.0);
      assertTrue(y3 >= 0.0 && y3 <= 256.0);
      assertTrue(x5 >= 0.0 && x5 <= 256.0);
      assertTrue(y5 >= 0.0 && y5 <= 256.0);

      double drift = Math.hypot(x5 - x3, y5 - y3);
      assertTrue(
          "Corner " + i + " drift " + drift + "px exceeds limit " + MAX_DRIFT_PX_256,
          drift <= MAX_DRIFT_PX_256);
    }

    // Both quads must remain convex with non-zero area.
    assertTrue("3×3 quad must be convex", isConvex(r3a.corners256()));
    assertTrue("5×5 quad must be convex", isConvex(r5a.corners256()));
    assertTrue("3×3 area must be > 0", Math.abs(shoelaceArea(r3a.corners256())) > 0.0);
    assertTrue("5×5 area must be > 0", Math.abs(shoelaceArea(r5a.corners256())) > 0.0);
  }

  private static boolean isConvex(double[][] pts) {
    if (pts == null || pts.length != 4) return false;
    double prevSign = 0.0;
    for (int i = 0; i < 4; i++) {
      double[] a = pts[i];
      double[] b = pts[(i + 1) % 4];
      double[] c = pts[(i + 2) % 4];
      double cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
      if (cross == 0.0) continue;
      double sign = Math.signum(cross);
      if (prevSign == 0.0) prevSign = sign;
      else if (sign != prevSign) return false;
    }
    return true;
  }

  private static double shoelaceArea(double[][] pts) {
    double s = 0.0;
    for (int i = 0; i < pts.length; i++) {
      double[] a = pts[i];
      double[] b = pts[(i + 1) % pts.length];
      s += a[0] * b[1] - b[0] * a[1];
    }
    return 0.5 * s;
  }
}
