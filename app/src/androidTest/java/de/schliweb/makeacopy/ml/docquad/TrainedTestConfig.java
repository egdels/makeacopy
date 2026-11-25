package de.schliweb.makeacopy.ml.docquad;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.io.InputStream;

/**
 * Helper für opt-in Tests, die von einem trainierten ONNX-Modell abhängen.
 *
 * <p>Analog zu den Python-Tests sollen trainierte Artefakte den Default-Testlauf nicht flaky
 * machen. Aktivierung erfolgt daher explizit über Instrumentation-Args:
 *
 * <pre>
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.RUN_TRAINED_TESTS=1
 * </pre>
 */
final class TrainedTestConfig {

  private TrainedTestConfig() {}

  static boolean trainedTestsEnabled() {
    String v = InstrumentationRegistry.getArguments().getString("RUN_TRAINED_TESTS", "");
    v = v == null ? "" : v.trim();
    return !(v.isEmpty() || v.equals("0") || v.equalsIgnoreCase("false"));
  }

  /**
   * Liefert den Asset-Namen des trainierten Modells (ohne {@code .ncnn.param} / {@code .ncnn.bin}).
   *
   * <p>Kanonischer Name ist ohne Versionssuffix.
   */
  static String resolveTrainedModelAsset(Context ctx) {
    // Das trainierte Modell ist ein Runtime-Asset (App-APK), nicht ein Test-Asset.
    // Deshalb muss hier i.d.R. der *targetContext* (nicht der instrumentation context)
    // verwendet werden.
    String base = "docquad/docquadnet256_trained_opset17";
    requireExistingAsset(ctx, base + ".ncnn.param");
    requireExistingAsset(ctx, base + ".ncnn.bin");
    return base;
  }

  /**
   * Liefert den Asset-Namen für die Expected-Stats des trained Snapshots.
   *
   * <p>Kanonischer Name ist ohne Versionssuffix.
   */
  static String resolveTrainedExpectedStatsAsset(Context ctx) {
    return requireExistingAsset(ctx, "docquad_m4/expected_stats_trained.json");
  }

  private static String requireExistingAsset(Context ctx, String assetName) {
    if (!assetExists(ctx, assetName)) {
      throw new AssertionError("Required androidTest asset missing: " + assetName);
    }
    return assetName;
  }

  private static boolean assetExists(Context ctx, String assetName) {
    try (InputStream ignored = ctx.getAssets().open(assetName)) {
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
