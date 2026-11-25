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

import android.content.Context;

/** Test-only access to {@link DocQuadRunner} construction for candidate models. */
public final class DocQuadTestRunners {

  private DocQuadTestRunners() {}

  /**
   * Loads a model from the assets of {@code assetCtx} (e.g. the instrumentation context, so a
   * candidate model can be evaluated without replacing the app's shipped asset).
   *
   * @param assetPath asset path without the {@code .ncnn.param} / {@code .ncnn.bin} suffix
   */
  public static DocQuadRunner fromAssets(Context assetCtx, String assetPath) throws Exception {
    return new DocQuadRunner(assetCtx, assetPath);
  }
}
