/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ml.docquad;

import android.content.Context;

/** Test-only access to {@link DocQuadOrtRunner} construction for candidate models. */
public final class DocQuadTestRunners {

  private DocQuadTestRunners() {}

  /**
   * Loads a model from the assets of {@code assetCtx} (e.g. the instrumentation context, so a
   * candidate model can be evaluated without replacing the app's shipped asset). The model is
   * cached in the app's cache dir under its file name, so candidates need distinct names.
   */
  public static DocQuadOrtRunner fromAssets(Context assetCtx, Context appCtx, String assetPath)
      throws Exception {
    return new DocQuadOrtRunner(assetCtx, assetPath, appCtx.getCacheDir());
  }
}
