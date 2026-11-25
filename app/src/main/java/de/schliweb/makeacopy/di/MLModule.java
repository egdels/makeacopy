/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.di;

import android.content.Context;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import de.schliweb.makeacopy.ml.corners.DocQuadDetector;
import de.schliweb.makeacopy.ml.docquad.DocQuadRunner;
import javax.inject.Singleton;

/** Hilt module that provides ML-related dependencies as singletons. */
@Module
@InstallIn(SingletonComponent.class)
public class MLModule {

  @Provides
  @Singleton
  DocQuadRunner provideDocQuadRunner(@ApplicationContext Context context) {
    try {
      return DocQuadRunner.getInstance(context, DocQuadDetector.DEFAULT_MODEL_ASSET_PATH);
    } catch (Exception e) {
      throw new RuntimeException("DocQuad model load failed", e);
    }
  }
}
