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
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner;

/**
 * Live-DocQuad Detector mit: - ORT/Model init genau einmal - deterministischem Time-Throttle
 *
 * <p>Wird ausschließlich bei `docquad_prod_enabled=true` instanziiert (Factory garantiert das).
 */
final class ThrottledDocQuadLiveDetector implements CornerDetector {

  private static final String TAG = "ThrottledDocQuad";
  private static final long MIN_INTERVAL_MS = 250L; // ~4 Hz
  private static final long LOG_INTERVAL_MS = 1000L;

  interface TimeSource {
    long nowMs();
  }

  interface Inference {
    DetectionResult run(Bitmap src, Context ctx) throws Exception;
  }

  private final Context appCtx;
  private final TimeSource timeSource;
  private final Inference inference;

  // Optional 1€-Smoother für Jitter-Reduktion im Live-Pfad. null = deaktiviert (Default).
  private final OneEuroCornerSmoother smoother;

  private volatile DocQuadDetector cachedDetector; // reuse across frames
  private volatile long lastRunMs = 0L;
  private volatile DetectionResult lastResult = null;
  private volatile boolean prefsLogged = false;
  private volatile long lastSmoothLogMs = 0L;

  /** Production ctor with a pre-loaded ORT runner (injected via DI). */
  ThrottledDocQuadLiveDetector(@NonNull Context appCtx, @NonNull DocQuadOrtRunner injectedRunner) {
    this(appCtx, injectedRunner, null);
  }

  /**
   * Production ctor with optional {@link OneEuroCornerSmoother}. Pass {@code null} to keep the
   * legacy unfiltered behavior (default for backward compatibility).
   */
  ThrottledDocQuadLiveDetector(
      @NonNull Context appCtx,
      @NonNull DocQuadOrtRunner injectedRunner,
      OneEuroCornerSmoother smoother) {
    this.appCtx = appCtx;
    this.timeSource = SystemClock::uptimeMillis;
    this.smoother = smoother;
    this.inference =
        new Inference() {
          @Override
          public DetectionResult run(Bitmap src, Context ctx) throws Exception {
            DocQuadDetector det = cachedDetector;
            if (det == null) {
              det = new DocQuadDetector(injectedRunner);
              cachedDetector = det;
            }
            return det.detect(src, ctx);
          }
        };
  }

  // Package-private ctor for tests.
  ThrottledDocQuadLiveDetector(
      @NonNull Context appCtx, @NonNull TimeSource timeSource, @NonNull Inference inference) {
    this(appCtx, timeSource, inference, null);
  }

  // Package-private ctor for tests with smoother injection.
  ThrottledDocQuadLiveDetector(
      @NonNull Context appCtx,
      @NonNull TimeSource timeSource,
      @NonNull Inference inference,
      OneEuroCornerSmoother smoother) {
    this.appCtx = appCtx;
    this.timeSource = timeSource;
    this.inference = inference;
    this.smoother = smoother;
  }

  @Override
  public DetectionResult detect(Bitmap src, Context ctx) {
    if (src == null) return DetectionResult.fail(Source.DOCQUAD);

    if (BuildConfig.FEATURE_FRAMING_LOGGING && !prefsLogged) {
      prefsLogged = true;
      Log.d(
          TAG,
          "live prefs: refine5x5=true"
              + " oneEuro=true"
              + " houghSnap=false"
              + " smootherAttached="
              + (smoother != null));
    }

    long now = timeSource.nowMs();
    DetectionResult cached = lastResult;
    if (cached != null && (now - lastRunMs) < MIN_INTERVAL_MS) {
      return cached;
    }

    try {
      DetectionResult raw = inference.run(src, appCtx);
      DetectionResult out = maybeSmooth(raw, src, now);
      lastRunMs = now;
      lastResult = out;
      return out;
    } catch (Throwable t) {
      lastRunMs = now;
      // Reset smoother on failure to avoid carrying stale state into the next successful run.
      if (smoother != null) smoother.reset();
      DetectionResult out = DetectionResult.fail(Source.DOCQUAD);
      lastResult = out;
      return out;
    }
  }

  private DetectionResult maybeSmooth(DetectionResult raw, Bitmap src, long nowMs) {
    if (smoother == null || raw == null || !raw.success || raw.cornersOriginalTLTRBRBL == null) {
      if (smoother != null && (raw == null || !raw.success)) smoother.reset();
      return raw;
    }
    try {
      double[][] smoothed =
          smoother.apply(raw.cornersOriginalTLTRBRBL, nowMs, src.getWidth(), src.getHeight());
      if (BuildConfig.FEATURE_FRAMING_LOGGING && (nowMs - lastSmoothLogMs) >= LOG_INTERVAL_MS) {
        lastSmoothLogMs = nowMs;
        double[][] rawC = raw.cornersOriginalTLTRBRBL;
        double maxDelta = 0.0;
        if (smoothed != null && smoothed.length == 4 && rawC.length == 4) {
          for (int i = 0; i < 4; i++) {
            double dx = smoothed[i][0] - rawC[i][0];
            double dy = smoothed[i][1] - rawC[i][1];
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d > maxDelta) maxDelta = d;
          }
        }
        Log.d(
            TAG,
            String.format(
                java.util.Locale.US,
                "smoother applied maxCornerDelta=%.2fpx src=%dx%d",
                maxDelta,
                src.getWidth(),
                src.getHeight()));
      }
      return DetectionResult.successDebug(
              raw.source, smoothed, raw.chosenSource, raw.penaltyMask, raw.penaltyCorners)
          .withConfidence(raw.confidence);
    } catch (Throwable t) {
      // Defensive: never let smoothing break detection.
      smoother.reset();
      return raw;
    }
  }
}
