package de.schliweb.makeacopy.ml.docquad;

import android.content.Context;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Base class for DocQuad golden tests containing common helper methods. Provides utilities for
 * creating test inputs, computing statistics, and reading test assets.
 */
public abstract class DocQuadGoldenTestBase {

  protected static final String MASK_AREA_DEFINITION_V1 = "mask_prob_gt_0.5";

  /**
   * Corresponds exactly to `training/docquad_m3/golden_samples.py` → `_make_input_sample_v1()`.
   *
   * <p>Shape: [1,3,256,256] NCHW, float32, value range 0..1.
   */
  protected static float[] makeGoldenInputV1Nchw() {
    int h = DocQuadRunner.IN_H;
    int w = DocQuadRunner.IN_W;
    float[] out = new float[1 * 3 * h * w];

    // Channel 0 (R): horizontal gradient x/255
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        out[idx(0, y, x)] = (float) x / 255.0f;
      }
    }
    // Channel 1 (G): vertical gradient y/255
    for (int y = 0; y < h; y++) {
      float v = (float) y / 255.0f;
      for (int x = 0; x < w; x++) {
        out[idx(1, y, x)] = v;
      }
    }
    // Channel 2 (B): constant 0.25
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        out[idx(2, y, x)] = 0.25f;
      }
    }

    // R-channel block: [64:192, 64:192] = 1.0
    for (int y = 64; y < 192; y++) {
      for (int x = 64; x < 192; x++) {
        out[idx(0, y, x)] = 1.0f;
      }
    }
    return out;
  }

  /**
   * Computes the NCHW index for a given channel, y, and x coordinate. NCHW: ((c * H) + y) * W + x
   */
  protected static int idx(int c, int y, int x) {
    return ((c * DocQuadRunner.IN_H) + y) * DocQuadRunner.IN_W + x;
  }

  /**
   * `mask_area` Definition v1 (FIX): - `mask_prob = sigmoid(mask_logits)` - `mask_bin = mask_prob >
   * 0.5` (strict) - `mask_area = sum(mask_bin)`
   */
  protected static int computeMaskAreaV1(float[][][][] maskLogits) {
    float[][] m = maskLogits[0][0];
    int area = 0;
    for (int y = 0; y < DocQuadRunner.OUT_H; y++) {
      for (int x = 0; x < DocQuadRunner.OUT_W; x++) {
        double logit = (double) m[y][x];
        double prob = 1.0 / (1.0 + Math.exp(-logit));
        if (prob > 0.5) {
          area++;
        }
      }
    }
    return area;
  }

  /**
   * Reads a JSON asset file from the given context.
   *
   * @param ctx the context to read from
   * @param assetPath path to the asset file
   * @return JsonObject parsed from the asset
   * @throws Exception if reading or parsing fails
   */
  protected static JsonObject readJsonAsset(Context ctx, String assetPath) throws Exception {
    try (InputStream is = ctx.getAssets().open(assetPath);
        ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8 * 1024];
      int n;
      while ((n = is.read(buf)) >= 0) {
        if (n > 0) baos.write(buf, 0, n);
      }
      String s = new String(baos.toByteArray(), StandardCharsets.UTF_8);
      return JsonParser.parseString(s).getAsJsonObject();
    }
  }

  /**
   * Computes the argmax index for each corner heatmap.
   *
   * @param cornerHeatmaps the corner heatmaps [batch, 4, height, width]
   * @return array of 4 argmax indices (flattened position)
   */
  protected static int[] computeCornerArgmaxIdx(float[][][][] cornerHeatmaps) {
    int[] out = new int[4];
    for (int c = 0; c < 4; c++) {
      float best = -Float.MAX_VALUE;
      int bestIdx = 0;
      float[][] hm = cornerHeatmaps[0][c];
      for (int y = 0; y < DocQuadRunner.OUT_H; y++) {
        for (int x = 0; x < DocQuadRunner.OUT_W; x++) {
          float v = hm[y][x];
          if (v > best) {
            best = v;
            bestIdx = y * DocQuadRunner.OUT_W + x;
          }
        }
      }
      out[c] = bestIdx;
    }
    return out;
  }
}
