package de.schliweb.makeacopy.ml.docquad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * M4 Golden-Test: NCNN auf Android liefert für das Zero-Init-Modell exakt die erwarteten Stats.
 *
 * <p>- Input ist synthetisch und entspricht exakt `training/docquad_m3/golden_samples.py` (v1). -
 * `mask_area` ist v1-definiert als `sigmoid(mask_logits) > 0.5` (strict).
 */
@RunWith(AndroidJUnit4.class)
public class DocQuadGoldenTest extends DocQuadGoldenTestBase {

  private static final String MODEL_ASSET = "docquad_m4/docquadnet256_zero_opset17";
  private static final String EXPECTED_JSON_ASSET = "docquad_m4/expected_stats_v1.json";

  @Test
  public void golden_stats_match_v1() throws Exception {
    Context testCtx = InstrumentationRegistry.getInstrumentation().getContext();

    // 1) Input exakt wie in Python Golden Sample v1
    float[] input = makeGoldenInputV1Nchw();

    // 2) Modell laufen lassen
    DocQuadRunner.Outputs out;
    try (DocQuadRunner runner = new DocQuadRunner(testCtx, MODEL_ASSET)) {
      out = runner.run(input);
    }

    assertNotNull(out);
    assertEquals(1, out.maskLogits().length);
    assertEquals(1, out.maskLogits()[0].length);
    assertEquals(DocQuadRunner.OUT_H, out.maskLogits()[0][0].length);
    assertEquals(DocQuadRunner.OUT_W, out.maskLogits()[0][0][0].length);

    assertEquals(1, out.cornerHeatmaps().length);
    assertEquals(4, out.cornerHeatmaps()[0].length);
    assertEquals(DocQuadRunner.OUT_H, out.cornerHeatmaps()[0][0].length);
    assertEquals(DocQuadRunner.OUT_W, out.cornerHeatmaps()[0][0][0].length);

    int[] cornerArgmaxIdx = computeCornerArgmaxIdx(out.cornerHeatmaps());
    int maskArea = computeMaskAreaV1(out.maskLogits());

    // 3) Erwartete Stats laden
    JsonObject expected = readJsonAsset(testCtx, EXPECTED_JSON_ASSET);
    assertEquals(MASK_AREA_DEFINITION_V1, expected.get("mask_area_definition").getAsString());

    JsonObject sample0 = expected.getAsJsonObject("samples").getAsJsonObject("sample0");
    JsonArray expCorners = sample0.getAsJsonArray("corner_argmax_idx");
    assertEquals(4, expCorners.size());
    for (int i = 0; i < 4; i++) {
      assertEquals(expCorners.get(i).getAsInt(), cornerArgmaxIdx[i]);
    }
    assertEquals(sample0.get("mask_area").getAsInt(), maskArea);
  }
}
