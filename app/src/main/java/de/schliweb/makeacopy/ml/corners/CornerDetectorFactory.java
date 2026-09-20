/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ml.corners;

import android.content.Context;
import androidx.annotation.NonNull;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner;

/**
 * A factory class for creating instances of {@link CornerDetector}. This factory provides
 * specialized configurations of corner detectors for different use cases, such as cropping and live
 * processing. The detectors utilize a combination of DocQuad-based detection and an OpenCV-based
 * fallback for flexibility and robustness. Smoothing mechanisms can also be applied for live
 * detection scenarios.
 *
 * <p>This class is not intended to be instantiated.
 */
public final class CornerDetectorFactory {

  private CornerDetectorFactory() {}

  /**
   * Creates a {@link CornerDetector} optimized for cropping operations. This detector combines a
   * DocQuad-based detection mechanism with an OpenCV fallback to ensure reliability in diverse
   * scenarios.
   *
   * @param ctx the application context used for initializing the detector.
   * @param runner the pre-loaded {@link DocQuadOrtRunner} instance for DocQuad-based detection.
   * @return a {@link CornerDetector} instance suitable for cropping operations.
   */
  @NonNull
  public static CornerDetector forCrop(@NonNull Context ctx, @NonNull DocQuadOrtRunner runner) {
    if (!BuildConfig.FEATURE_DOCQUAD_CORNERS) {
      return new OpenCvCornerDetector();
    }
    return new CompositeCornerDetector(docQuadForCrop(runner), new OpenCvCornerDetector());
  }

  /**
   * The DocQuad stage for one-shot detection on a captured image: with test-time rotation (see
   * {@link RotatingDocQuadDetector}) unless disabled by feature flag. Never use it for the live
   * preview, where a weak frame would cost several inferences.
   */
  @NonNull
  public static CornerDetector docQuadForCrop(@NonNull DocQuadOrtRunner runner) {
    return BuildConfig.FEATURE_DOCQUAD_ROTATION_TTA
        ? new RotatingDocQuadDetector(runner)
        : new DocQuadDetector(runner);
  }

  /**
   * Creates a {@link CornerDetector} optimized for live corner detection during real-time
   * processing. This detector uses a combination of throttled DocQuad detection and a fallback to
   * OpenCV-based corner detection. It also applies smoothing using a default One Euro Corner
   * Smoother for stability.
   *
   * @param ctx the application context used for initializing the detector.
   * @param runner the pre-loaded {@link DocQuadOrtRunner} instance for DocQuad-based detection.
   * @return a {@link CornerDetector} instance suitable for live processing.
   */
  @NonNull
  public static CornerDetector forLive(@NonNull Context ctx, @NonNull DocQuadOrtRunner runner) {
    OneEuroCornerSmoother smoother = OneEuroCornerSmoother.withDefaults();
    if (!BuildConfig.FEATURE_DOCQUAD_CORNERS) {
      return new OpenCvCornerDetector();
    }
    return new CompositeCornerDetector(
        new ThrottledDocQuadLiveDetector(ctx.getApplicationContext(), runner, smoother),
        new OpenCvCornerDetector());
  }
}
