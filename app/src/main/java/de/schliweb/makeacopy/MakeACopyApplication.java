/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy;

import android.app.Application;
import android.util.Log;
import com.google.android.material.color.DynamicColors;
import dagger.hilt.android.HiltAndroidApp;
import de.schliweb.makeacopy.data.library.CollectionsRepository;
import de.schliweb.makeacopy.services.CacheCleanupService;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.ocr.PaddleEngineProvider;
import javax.inject.Inject;

/**
 * Main Application class for MakeACopy. Handles global initialization including OpenCV and
 * background services.
 */
@HiltAndroidApp
public class MakeACopyApplication extends Application {

  @Inject CollectionsRepository collectionsRepository;

  private static final String TAG = "MakeACopyApplication";

  @Override
  public void onCreate() {
    super.onCreate();

    Log.i(TAG, "MakeACopy Application starting...");

    // In-app language must be known before anything resolves strings off the UI (Android <= 12)
    de.schliweb.makeacopy.utils.ui.AppLanguage.restoreEarly(this);

    // Material You dynamic color (Android 12+). No-op on older devices, where the
    // static fallback palette from themes.xml is used. Works fully offline.
    DynamicColors.applyToActivitiesIfAvailable(this);

    // Initialize OpenCV
    initializeOpenCV();

    initializeCacheCleanupService();

    // Ensure the default "Completed Scans" collection exists on first app start (idempotent)
    initializeDefaultCompletedScansCollection();

    Log.i(TAG, "MakeACopy Application initialized successfully");
  }

  /** Initialize OpenCV library */
  private void initializeOpenCV() {
    try {
      boolean success = OpenCVUtils.init(this);
      if (success) {
        Log.i(TAG, "OpenCV initialized successfully");
      } else {
        Log.e(TAG, "Failed to initialize OpenCV");
      }
    } catch (Exception e) {
      Log.e(TAG, "Error initializing OpenCV", e);
    }
  }

  /** Initialize and start the Cache Cleanup Service */
  private void initializeCacheCleanupService() {
    try {
      // Start the cache cleanup service with default configuration
      CacheCleanupService.startService(this);
      Log.i(TAG, "Cache Cleanup Service started");

      // Configure service for optimal performance
      // Cleanup every 2 hours, keep max 15 debug files, remove temps after 1 hour, trigger at 75%
      // memory
      CacheCleanupService.updateConfiguration(
          this,
          true, // enabled
          2, // cleanup interval hours
          15, // max debug files
          1, // max temp age hours
          75 // memory threshold percent
          );
      Log.d(TAG, "Cache Cleanup Service configured");

    } catch (Exception e) {
      Log.e(TAG, "Error initializing Cache Cleanup Service", e);
    }
  }

  /**
   * Ensures the default "Completed Scans" collection exists. Runs off the UI thread and is fully
   * idempotent.
   */
  private void initializeDefaultCompletedScansCollection() {
    try {
      if (!FeatureFlags.isScanLibraryEnable()) return;
      final android.content.Context appCtx = getApplicationContext();
      new Thread(
              () -> {
                try {
                  collectionsRepository.getOrCreateDefaultCompletedCollection(appCtx);
                } catch (Throwable t) {
                  Log.d(TAG, "initializeDefaultCompletedScansCollection: suppressed", t);
                }
              })
          .start();
    } catch (Throwable t) {
      Log.d(TAG, "initializeDefaultCompletedScansCollection: suppressed (outer)", t);
    }
  }

  /** Called when the overall system is running low on memory */
  @Override
  public void onLowMemory() {
    super.onLowMemory();

    Log.w(TAG, "System low memory detected - triggering emergency cache cleanup");

    try {
      // Force immediate cache cleanup when system is low on memory
      CacheCleanupService.forceCleanup(this);

      // Force garbage collection
      System.gc();

      Log.i(TAG, "Emergency cache cleanup completed");

    } catch (Exception e) {
      Log.e(TAG, "Error during emergency cache cleanup", e);
    }

    try {
      PaddleEngineProvider.releaseAll(this);
      Log.i(TAG, "PaddleEngineProvider.releaseAll triggered reason=low-memory");
    } catch (Throwable t) {
      Log.w(TAG, "PaddleEngineProvider.releaseAll failed reason=low-memory", t);
    }
  }

  /**
   * Called when the operating system has determined that it is a good time for a process to trim
   * unneeded memory from its process
   */
  @Override
  @SuppressWarnings("deprecation") // TRIM_MEMORY_MODERATE is deprecated since API 35 but still
  // delivered by older runtimes (minSdk = 29). We keep handling it to release native Paddle/ORT
  // resources eagerly on those devices.
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);

    Log.d(TAG, "Memory trim requested with level: " + level);

    // Trigger cache cleanup when app is in the background and memory is low (non-deprecated level)
    if (level >= TRIM_MEMORY_BACKGROUND) {
      try {
        CacheCleanupService.forceCleanup(this);
        Log.i(TAG, "Cache cleanup triggered by memory trim (level: " + level + ")");
      } catch (Exception e) {
        Log.e(TAG, "Error during memory trim cache cleanup", e);
      }
    }

    if (level >= TRIM_MEMORY_MODERATE) {
      try {
        PaddleEngineProvider.releaseAll(this);
        Log.i(
            TAG,
            "PaddleEngineProvider.releaseAll triggered level=" + level + " reason=trim-memory");
      } catch (Throwable t) {
        Log.w(
            TAG,
            "PaddleEngineProvider.releaseAll failed level=" + level + " reason=trim-memory",
            t);
      }
    }
  }
}
