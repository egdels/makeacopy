/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import de.schliweb.makeacopy.data.CompletedScansRegistry;
import de.schliweb.makeacopy.data.RegistryCleaner;
import de.schliweb.makeacopy.data.library.AppDatabase;
import de.schliweb.makeacopy.data.library.DefaultCollectionsRepository;
import de.schliweb.makeacopy.data.library.DefaultScansRepository;
import de.schliweb.makeacopy.data.library.ExistingScansIndexer;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CacheCleanupService is a background service that performs periodic and immediate cache cleanup
 * operations to maintain optimal performance and storage usage. The service can be configured
 * dynamically and operates both regularly based on a schedule and in response to specific memory
 * pressure conditions.
 *
 * <p>The service supports cleaning up various types of temporary files, including debug images, old
 * camera images, and other temporary files. Configuration properties, such as cleanup intervals and
 * maximum resource thresholds, are customizable through SharedPreferences and Intent-based updates.
 *
 * <p>The service is compatible with Android 8.0+ and includes memory-safe static utility methods
 * for direct cleanup operations when service-based cleanup is not feasible.
 *
 * <p>Key Features: - Scheduled cleanup tasks with adjustable intervals - Immediate cleanup
 * triggered via intents - Memory threshold monitoring for adaptive cleanup - Comprehensive caching
 * and temporary file removal - Static utility methods for use in memory-sensitive contexts -
 * Configuration persistence and runtime updates
 *
 * <p>Predefined Constants: - TAG: Logs tag for debugging purposes - PREFS_NAME: Name of the
 * SharedPreferences storage - PREF_LAST_CLEANUP: Key for storing the timestamp of the last cleanup
 * - PREF_CLEANUP_ENABLED: Key to enable/disable cleanup - PREF_CLEANUP_INTERVAL_HOURS: Key for
 * cleanup interval in hours - PREF_MAX_DEBUG_FILES: Key for maximum debug files to retain -
 * PREF_MAX_TEMP_AGE_HOURS: Key for maximum permissible age of temporary files -
 * PREF_MEMORY_THRESHOLD_PERCENT: Key for memory threshold percentage to trigger cleanup -
 * DEFAULT_CLEANUP_INTERVAL_HOURS: Default interval for cleanup in hours - DEFAULT_MAX_DEBUG_FILES:
 * Default maximum debug files - DEFAULT_MAX_TEMP_AGE_HOURS: Default maximum temporary file age in
 * hours - DEFAULT_MEMORY_THRESHOLD_PERCENT: Default memory usage threshold percentage
 *
 * <p>Execution Context: - The service operates as a background task utilizing scheduled executors.
 * - File system operations and memory monitoring occur in separate threads to ensure minimal impact
 * on application performance.
 *
 * <p>Threading Model: - Scheduled tasks and immediate cleanup operations execute in the context of
 * a ScheduledExecutorService to avoid blocking the main thread. - Direct cleanup methods offer
 * static alternatives for scenarios where background services are restricted.
 *
 * <p>Methods Overview: - onCreate, onStartCommand, onDestroy: Lifecycle management of the service -
 * onBind: Binder implementation for service-client communication - loadConfiguration: Loads
 * configuration parameters from SharedPreferences - updateConfiguration: Dynamically updates
 * service configuration based on Intent extras - startScheduledCleanup, stopScheduledCleanup,
 * restartScheduledCleanup: Manage the lifecycle of scheduled periodic cleanup tasks -
 * performScheduledCleanup: Checks if cleanup is necessary and triggers comprehensive cleanup -
 * performImmediateCleanup: Executes immediate cleanup in response to requests -
 * performComprehensiveCleanup: Orchestrates cleanup across multiple cache types -
 * cleanupDebugImages, cleanupOldCameraImages, cleanupTempFiles: Specific cleanup tasks -
 * isMemoryUsageHigh: Checks if memory usage exceeds the defined threshold -
 * cleanupDirectoryRecursively: Deletes files older than a predefined age within a directory -
 * logMemoryUsage: Logs current memory statistics for diagnostic purposes - Static utility methods:
 * Provide alternatives for direct cleanup scenarios, including cleanupDebugImagesDirect,
 * cleanupOldCameraImagesDirect, cleanupTempFilesDirect, cleanupDirectoryRecursivelyDirect,
 * performCacheCleanupDirect, and logMemoryUsageStatic
 *
 * <p>Note: - Ensure the service is properly configured with adequate SharedPreferences entries or
 * intent extras to avoid unexpected behavior. - Static utility methods should only be used when the
 * associated cleanup operations need to occur without instantiating the service or when the app is
 * in a background state.
 */
@SuppressWarnings("FutureReturnValueIgnored") // cleanup tasks are fire-and-forget
public class CacheCleanupService extends Service {

  private static final String TAG = "CacheCleanupService";

  // Configuration constants
  private static final String PREFS_NAME = "cache_cleanup_prefs";
  private static final String PREF_LAST_CLEANUP = "last_cleanup_time";
  private static final String PREF_CLEANUP_ENABLED = "cleanup_enabled";
  private static final String PREF_CLEANUP_INTERVAL_HOURS = "cleanup_interval_hours";
  private static final String PREF_MAX_DEBUG_FILES = "max_debug_files";
  private static final String PREF_MAX_TEMP_AGE_HOURS = "max_temp_age_hours";
  private static final String PREF_MEMORY_THRESHOLD_PERCENT = "memory_threshold_percent";

  // Default values
  private static final long DEFAULT_CLEANUP_INTERVAL_HOURS = 2; // Every 2 hours
  private static final int DEFAULT_MAX_DEBUG_FILES = 20;
  private static final int DEFAULT_MAX_TEMP_AGE_HOURS = 2;
  private static final int DEFAULT_MEMORY_THRESHOLD_PERCENT = 75;

  private ScheduledExecutorService scheduledExecutor;
  private SharedPreferences preferences;

  // Configuration
  private boolean cleanupEnabled = true;
  private long cleanupIntervalHours = DEFAULT_CLEANUP_INTERVAL_HOURS;
  private int maxDebugFiles = DEFAULT_MAX_DEBUG_FILES;
  private int maxTempAgeHours = DEFAULT_MAX_TEMP_AGE_HOURS;
  private int memoryThresholdPercent = DEFAULT_MEMORY_THRESHOLD_PERCENT;

  @Override
  public void onCreate() {
    super.onCreate();

    Log.i(TAG, "CacheCleanupService created");

    preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

    loadConfiguration();

    if (cleanupEnabled) {
      startScheduledCleanup();
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.i(TAG, "CacheCleanupService started");

    if (intent != null) {
      String action = intent.getAction();
      if ("FORCE_CLEANUP".equals(action)) {
        performImmediateCleanup();
      } else if ("UPDATE_CONFIG".equals(action)) {
        updateConfiguration(intent);
      }
    }

    return START_STICKY; // Restart if killed
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    Log.i(TAG, "CacheCleanupService destroyed");

    if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
      scheduledExecutor.shutdown();
      try {
        if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduledExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduledExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null; // No binding needed
  }

  /** Loads configuration from SharedPreferences */
  private void loadConfiguration() {
    cleanupEnabled = preferences.getBoolean(PREF_CLEANUP_ENABLED, true);
    cleanupIntervalHours =
        preferences.getLong(PREF_CLEANUP_INTERVAL_HOURS, DEFAULT_CLEANUP_INTERVAL_HOURS);
    maxDebugFiles = preferences.getInt(PREF_MAX_DEBUG_FILES, DEFAULT_MAX_DEBUG_FILES);
    maxTempAgeHours = preferences.getInt(PREF_MAX_TEMP_AGE_HOURS, DEFAULT_MAX_TEMP_AGE_HOURS);
    memoryThresholdPercent =
        preferences.getInt(PREF_MEMORY_THRESHOLD_PERCENT, DEFAULT_MEMORY_THRESHOLD_PERCENT);

    Log.d(
        TAG,
        String.format(
            "Configuration loaded: enabled=%b, interval=%dh, maxDebugFiles=%d, maxTempAge=%dh, memoryThreshold=%d%%",
            cleanupEnabled,
            cleanupIntervalHours,
            maxDebugFiles,
            maxTempAgeHours,
            memoryThresholdPercent));
  }

  /**
   * Updates the service configuration based on the provided intent. The configuration is persisted
   * in SharedPreferences and adjustments are made to the scheduled cleanup task accordingly.
   *
   * @param intent The intent containing updated configuration values. This intent may include the
   *     following optional extras: - PREF_CLEANUP_ENABLED: Boolean flag indicating whether cleanup
   *     is enabled. - PREF_CLEANUP_INTERVAL_HOURS: Long value specifying the interval in hours
   *     between cleanup tasks. - PREF_MAX_DEBUG_FILES: Integer specifying the maximum number of
   *     debug files to retain. - PREF_MAX_TEMP_AGE_HOURS: Integer specifying the maximum age in
   *     hours of temporary files to retain. - PREF_MEMORY_THRESHOLD_PERCENT: Integer specifying the
   *     memory usage threshold percentage to trigger cleanup.
   */
  private void updateConfiguration(Intent intent) {
    SharedPreferences.Editor editor = preferences.edit();

    if (intent.hasExtra(PREF_CLEANUP_ENABLED)) {
      cleanupEnabled = intent.getBooleanExtra(PREF_CLEANUP_ENABLED, true);
      editor.putBoolean(PREF_CLEANUP_ENABLED, cleanupEnabled);
    }

    if (intent.hasExtra(PREF_CLEANUP_INTERVAL_HOURS)) {
      cleanupIntervalHours =
          intent.getLongExtra(PREF_CLEANUP_INTERVAL_HOURS, DEFAULT_CLEANUP_INTERVAL_HOURS);
      editor.putLong(PREF_CLEANUP_INTERVAL_HOURS, cleanupIntervalHours);
    }

    if (intent.hasExtra(PREF_MAX_DEBUG_FILES)) {
      maxDebugFiles = intent.getIntExtra(PREF_MAX_DEBUG_FILES, DEFAULT_MAX_DEBUG_FILES);
      editor.putInt(PREF_MAX_DEBUG_FILES, maxDebugFiles);
    }

    if (intent.hasExtra(PREF_MAX_TEMP_AGE_HOURS)) {
      maxTempAgeHours = intent.getIntExtra(PREF_MAX_TEMP_AGE_HOURS, DEFAULT_MAX_TEMP_AGE_HOURS);
      editor.putInt(PREF_MAX_TEMP_AGE_HOURS, maxTempAgeHours);
    }

    if (intent.hasExtra(PREF_MEMORY_THRESHOLD_PERCENT)) {
      memoryThresholdPercent =
          intent.getIntExtra(PREF_MEMORY_THRESHOLD_PERCENT, DEFAULT_MEMORY_THRESHOLD_PERCENT);
      editor.putInt(PREF_MEMORY_THRESHOLD_PERCENT, memoryThresholdPercent);
    }

    editor.apply();

    Log.i(TAG, "Configuration updated");

    // Restart scheduled cleanup with new configuration
    if (cleanupEnabled) {
      restartScheduledCleanup();
    } else {
      stopScheduledCleanup();
    }
  }

  /** Starts the scheduled cleanup task */
  private void startScheduledCleanup() {
    if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
      scheduledExecutor.shutdown();
    }

    scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

    long intervalMs = cleanupIntervalHours * 60L * 60L * 1000L;

    scheduledExecutor.scheduleAtFixedRate(
        this::performScheduledCleanup,
        intervalMs, // Initial delay
        intervalMs, // Period
        TimeUnit.MILLISECONDS);

    Log.i(TAG, "Scheduled cleanup started with interval: " + cleanupIntervalHours + " hours");
  }

  /** Stops the scheduled cleanup task */
  private void stopScheduledCleanup() {
    if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
      scheduledExecutor.shutdown();
      Log.i(TAG, "Scheduled cleanup stopped");
    }
  }

  /** Restarts the scheduled cleanup with updated configuration */
  private void restartScheduledCleanup() {
    stopScheduledCleanup();
    startScheduledCleanup();
  }

  /**
   * Performs scheduled cleanup. The executor already fires at the configured interval, so this
   * method always runs the comprehensive cleanup without an additional time-gate. Memory pressure
   * is logged for diagnostics but does not prevent the cleanup from running.
   */
  private void performScheduledCleanup() {
    Log.d(TAG, "Performing scheduled cleanup");

    try {
      boolean highMemory = isMemoryUsageHigh();
      Log.i(TAG, String.format("Scheduled cleanup running (highMemory=%b)", highMemory));

      performComprehensiveCleanup();

      // Update last cleanup time
      preferences.edit().putLong(PREF_LAST_CLEANUP, System.currentTimeMillis()).apply();

    } catch (Exception e) {
      Log.e(TAG, "Error during scheduled cleanup", e);
    }
  }

  /** Performs immediate cleanup (called via intent) */
  private void performImmediateCleanup() {
    Log.i(TAG, "Performing immediate cleanup");

    // Run cleanup on background thread
    if (scheduledExecutor == null || scheduledExecutor.isShutdown()) {
      // Create temporary executor if scheduled one is not available
      Executors.newSingleThreadExecutor()
          .submit(
              () -> {
                performComprehensiveCleanup();
                preferences.edit().putLong(PREF_LAST_CLEANUP, System.currentTimeMillis()).apply();
              });
    } else {
      scheduledExecutor.submit(
          () -> {
            performComprehensiveCleanup();
            preferences.edit().putLong(PREF_LAST_CLEANUP, System.currentTimeMillis()).apply();
          });
    }
  }

  /** Checks if memory usage is above threshold */
  private boolean isMemoryUsageHigh() {
    try {
      Runtime runtime = Runtime.getRuntime();
      long usedMemory = runtime.totalMemory() - runtime.freeMemory();
      long maxMemory = runtime.maxMemory();
      double memoryUsagePercent = (double) usedMemory / maxMemory * 100;

      Log.d(
          TAG,
          String.format(
              "Current memory usage: %.1f%% (threshold: %d%%)",
              memoryUsagePercent, memoryThresholdPercent));

      return memoryUsagePercent > memoryThresholdPercent;

    } catch (Exception e) {
      Log.e(TAG, "Error checking memory usage", e);
      return false;
    }
  }

  /** Performs comprehensive cleanup of all cache types */
  private void performComprehensiveCleanup() {
    Log.i(TAG, "Starting comprehensive cache cleanup");

    long startTime = System.currentTimeMillis();

    try {
      // Log memory before cleanup
      logMemoryUsage("before cleanup");

      // Cleanup different cache types
      int debugFilesCleanup = cleanupDebugImages();
      int cameraFilesCleanup = cleanupOldCameraImages();
      int tempFilesCleanup = cleanupTempFiles();
      int completedScansCleanup = cleanupCompletedScans();

      // Force garbage collection
      System.gc();

      // Log memory after cleanup
      logMemoryUsage("after cleanup");

      long duration = System.currentTimeMillis() - startTime;

      Log.i(
          TAG,
          String.format(
              "Cache cleanup completed in %dms. Files removed: debug=%d, camera=%d, temp=%d, completedScans=%d",
              duration,
              debugFilesCleanup,
              cameraFilesCleanup,
              tempFilesCleanup,
              completedScansCleanup));

    } catch (Exception e) {
      Log.e(TAG, "Error during comprehensive cleanup", e);
    }
  }

  /**
   * Cleans up completed scans according to the configured policy.
   *
   * @return number of scan entries removed
   */
  private int cleanupCompletedScans() {
    try {
      String policy = preferences.getString("completed_scans_cleanup_policy", "NONE");
      if ("NONE".equals(policy)) return 0;

      CompletedScansRegistry registry = CompletedScansRegistry.get(this);
      long now = System.currentTimeMillis();
      int totalRemoved = 0;

      if ("MAX_AGE".equals(policy) || "COMBINED".equals(policy)) {
        int maxAgeDays = preferences.getInt("completed_scans_max_age_days", 30);
        List<String> ids =
            CompletedScansCleanupPolicy.idsToRemoveByAge(
                registry.listAllOrderedByDateDesc(), maxAgeDays, now);
        for (String id : ids) {
          RegistryCleaner.removeEntryAndFiles(this, id);
          totalRemoved++;
        }
      }

      if ("MAX_COUNT".equals(policy) || "COMBINED".equals(policy)) {
        int maxCount = preferences.getInt("completed_scans_max_count", 100);
        List<String> ids =
            CompletedScansCleanupPolicy.idsToRemoveByCount(
                registry.listAllOrderedByDateDesc(), maxCount);
        for (String id : ids) {
          RegistryCleaner.removeEntryAndFiles(this, id);
          totalRemoved++;
        }
      }

      if ("MAX_STORAGE".equals(policy) || "COMBINED".equals(policy)) {
        int maxMb = preferences.getInt("completed_scans_max_storage_mb", 500);
        long maxBytes = maxMb * 1024L * 1024L;
        List<CompletedScan> scans = registry.listAllOrderedByDateDesc();
        java.util.HashMap<String, Long> sizeById = new java.util.HashMap<>();
        for (CompletedScan s : scans) {
          sizeById.put(s.id(), calculateScanEntrySize(s));
        }
        List<String> ids =
            CompletedScansCleanupPolicy.idsToRemoveByStorage(scans, sizeById, maxBytes);
        for (String id : ids) {
          RegistryCleaner.removeEntryAndFiles(this, id);
          totalRemoved++;
        }
      }

      if (totalRemoved > 0) {
        Log.i(TAG, "Cleaned up " + totalRemoved + " completed scans (policy=" + policy + ")");
        // Re-index to keep Room database in sync after deletions
        reindexAfterCleanup();
      }
      return totalRemoved;

    } catch (Exception e) {
      Log.e(TAG, "Error cleaning up completed scans", e);
      return 0;
    }
  }

  /**
   * Re-indexes the scan library database after cleanup to keep Room in sync. Removes Room entries
   * whose IDs are no longer in the CompletedScansRegistry, then runs incremental indexing to repair
   * any remaining metadata.
   */
  private void reindexAfterCleanup() {
    try {
      AppDatabase db = AppDatabase.getInstance(this);
      DefaultScansRepository scansRepo =
          new DefaultScansRepository(
              db.scansDao(), db.scanCollectionJoinDao(), db.scanPageTextDao());
      DefaultCollectionsRepository collectionsRepo =
          new DefaultCollectionsRepository(
              db.collectionsDao(), db.scanCollectionJoinDao(), db.scansDao());

      // Collect IDs still present in the registry
      java.util.Set<String> registryIds = new java.util.HashSet<>();
      for (CompletedScan s : CompletedScansRegistry.get(this).listAllOrderedByDateDesc()) {
        if (s != null && s.id() != null) registryIds.add(s.id());
      }

      // Remove Room entries that are no longer in the registry (see
      // CompletedScansCleanupPolicy#idsToRemoveFromLibrary for why this must be scoped to
      // registry-sourced entries only).
      int removed = 0;
      List<de.schliweb.makeacopy.data.library.ScanEntity> allScans = scansRepo.getAllScans(this);
      if (allScans != null) {
        for (String id : CompletedScansCleanupPolicy.idsToRemoveFromLibrary(allScans, registryIds)) {
          scansRepo.deleteScan(this, id);
          removed++;
        }
      }

      // Run incremental indexing to repair metadata for remaining entries
      int indexed = ExistingScansIndexer.runIncremental(this, scansRepo, collectionsRepo);
      Log.i(
          TAG,
          "Re-indexed scan library after cleanup: removed="
              + removed
              + " stale Room entries, indexed="
              + indexed
              + " items");
    } catch (Exception e) {
      Log.e(TAG, "Error re-indexing scan library after cleanup", e);
    }
  }

  private long calculateScanEntrySize(CompletedScan s) {
    long size = 0;
    File dir = new File(getFilesDir(), "scans/" + s.id());
    if (dir.isDirectory()) {
      File[] files = dir.listFiles();
      if (files != null) {
        for (File f : files) {
          size += f.length();
        }
      }
    }
    if (s.filePath() != null) {
      File f = new File(s.filePath());
      if (f.exists() && !f.getAbsolutePath().startsWith(dir.getAbsolutePath())) {
        size += f.length();
      }
    }
    return size;
  }

  /** Cleans up debug images */
  private int cleanupDebugImages() {
    try {
      File externalDir = getExternalFilesDir(null);
      if (externalDir == null || !externalDir.exists()) return 0;

      File[] debugFiles =
          externalDir.listFiles((file, name) -> name.startsWith("debug_") && name.endsWith(".png"));
      if (debugFiles == null) return 0;

      // Sort by last modified date (oldest first)
      Arrays.sort(debugFiles, Comparator.comparingLong(File::lastModified));

      // Keep only the most recent debug files
      int filesToDelete = Math.max(0, debugFiles.length - maxDebugFiles);
      int deletedCount = 0;

      for (int i = 0; i < filesToDelete; i++) {
        if (debugFiles[i].delete()) {
          deletedCount++;
        }
      }

      if (deletedCount > 0) {
        Log.d(TAG, "Cleaned up " + deletedCount + " debug images");
      }

      return deletedCount;

    } catch (Exception e) {
      Log.e(TAG, "Error cleaning up debug images", e);
      return 0;
    }
  }

  /** Cleans up old camera images */
  private int cleanupOldCameraImages() {
    try {
      File picturesDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MakeACopy");
      if (!picturesDir.exists()) return 0;

      File[] imageFiles =
          picturesDir.listFiles(
              (file, name) -> name.startsWith("MakeACopy_") && name.endsWith(".jpg"));
      if (imageFiles == null) return 0;

      long maxAgeMs = maxTempAgeHours * 60L * 60L * 1000L;
      long cutoffTime = System.currentTimeMillis() - maxAgeMs;
      int deletedCount = 0;

      for (File imageFile : imageFiles) {
        if (imageFile.lastModified() < cutoffTime && imageFile.delete()) {
          deletedCount++;
        }
      }

      if (deletedCount > 0) {
        Log.d(TAG, "Cleaned up " + deletedCount + " old camera images");
      }

      return deletedCount;

    } catch (Exception e) {
      Log.e(TAG, "Error cleaning up old camera images", e);
      return 0;
    }
  }

  /** Cleans up other temporary files */
  private int cleanupTempFiles() {
    try {
      int total = 0;
      // Cleanup internal app cache directory
      File cacheDir = getCacheDir();
      total += cleanupDirectoryRecursively(cacheDir);
      // Also cleanup external cache directory if available
      File extCacheDir = getExternalCacheDir();
      if (extCacheDir != null) {
        total += cleanupDirectoryRecursively(extCacheDir);
      }
      return total;

    } catch (Exception e) {
      Log.e(TAG, "Error cleaning up temp files", e);
      return 0;
    }
  }

  /**
   * Recursively cleans up the files and directories within the specified directory. Temporary files
   * older than the maximum allowed age are deleted. Empty directories are also removed during the
   * cleanup process.
   *
   * @param directory The root directory to start the cleanup process. This must be a valid
   *     directory; if it is null, does not exist, or is not a directory, the method will return
   *     immediately without performing any cleanup.
   * @return The total number of files and directories that were successfully deleted during the
   *     cleanup process. Returns 0 if no deletions occur or if the input directory is invalid.
   */
  private int cleanupDirectoryRecursively(File directory) {
    if (directory == null || !directory.exists() || !directory.isDirectory()) {
      return 0;
    }

    int deletedCount = 0;
    long maxAgeMs = maxTempAgeHours * 60L * 60L * 1000L;
    long cutoffTime = System.currentTimeMillis() - maxAgeMs;

    File[] files = directory.listFiles();
    if (files == null) return 0;

    for (File file : files) {
      if (file.isDirectory()) {
        deletedCount += cleanupDirectoryRecursively(file);
        // Remove empty directories
        if (file.list() != null && file.list().length == 0) {
          if (file.delete()) deletedCount++;
        }
      } else {
        if (file.lastModified() < cutoffTime && file.delete()) {
          deletedCount++;
        }
      }
    }

    return deletedCount;
  }

  /**
   * Logs the current memory usage details, including the percentage of used memory relative to the
   * maximum available memory and the absolute values in MB for both used and maximum memory. This
   * method helps in tracking memory usage during various operations in the service.
   *
   * @param context A string that provides additional context for the log (e.g., "before cleanup",
   *     "after cleanup") to help identify the stage or operation being monitored.
   */
  private void logMemoryUsage(String context) {
    try {
      Runtime runtime = Runtime.getRuntime();
      long usedMemory = runtime.totalMemory() - runtime.freeMemory();
      long maxMemory = runtime.maxMemory();
      double memoryUsagePercent = (double) usedMemory / maxMemory * 100;

      Log.d(
          TAG,
          String.format(
              "Memory usage %s: %.1f%% (%d MB / %d MB)",
              context, memoryUsagePercent, usedMemory / (1024 * 1024), maxMemory / (1024 * 1024)));

    } catch (Exception e) {
      Log.e(TAG, "Error logging memory usage", e);
    }
  }

  /**
   * Starts the CacheCleanupService using the provided context. This method initializes the service
   * responsible for performing cache cleanup operations based on its pre-configured settings.
   *
   * @param context The context from which the service is started. Typically, this will be an
   *     instance of an Activity or Application derived class, required to properly bind and start
   *     the service.
   */
  public static void startService(Context context) {
    try {
      Intent intent = new Intent(context, CacheCleanupService.class);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // We could use startForegroundService here, but that requires a notification.
        // For a cleanup service, standard startService is usually enough if app is in foreground.
        // If app is in background, it will throw BackgroundServiceStartNotAllowedException
        // (caught).
        context.startService(intent);
      } else {
        context.startService(intent);
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to start service (likely background restrictions): " + e.getMessage());
      // Fallback to direct cleanup if needed, or just ignore as it's non-critical
    }
  }

  /**
   * Forces an immediate cleanup operation using either a service-based approach or a direct cleanup
   * fallback in case of service start failure. This method initiates comprehensive cache management
   * to free up memory or storage resources.
   *
   * @param context The context from which the cleanup is initiated. Typically, this will be an
   *     instance of an Activity, Service, or Application required to properly invoke the service or
   *     perform fallback cleanup.
   */
  public static void forceCleanup(Context context) {
    try {
      // Try to start service first (works when app is in foreground)
      Intent intent = new Intent(context, CacheCleanupService.class);
      intent.setAction("FORCE_CLEANUP");
      context.startService(intent);
      Log.i(TAG, "Service-based cleanup initiated");

    } catch (Exception e) {
      // Fallback to direct cleanup if service start fails (background restrictions)
      Log.w(TAG, "Service start failed, using direct cleanup: " + e.getMessage());
      performDirectCleanup(context);
    }
  }

  /**
   * Updates the configuration of the CacheCleanupService. The method sends the updated parameters
   * to the service via an intent, which adjusts its behavior based on the provided values.
   *
   * @param context The context from which the service is called. Typically, this will be an
   *     Activity or Application instance.
   * @param enabled A boolean flag indicating whether the cleanup service is enabled or disabled.
   * @param intervalHours The interval, in hours, between consecutive cleanup operations.
   * @param maxDebugFiles The maximum number of debug files that should be retained by the service.
   * @param maxTempAgeHours The maximum age, in hours, that temporary files should be retained
   *     before being cleaned up.
   * @param memoryThresholdPercent The memory usage threshold percentage at which the service will
   *     initiate cleanup operations.
   */
  public static void updateConfiguration(
      Context context,
      boolean enabled,
      long intervalHours,
      int maxDebugFiles,
      int maxTempAgeHours,
      int memoryThresholdPercent) {
    try {
      Intent intent = new Intent(context, CacheCleanupService.class);
      intent.setAction("UPDATE_CONFIG");
      intent.putExtra(PREF_CLEANUP_ENABLED, enabled);
      intent.putExtra(PREF_CLEANUP_INTERVAL_HOURS, intervalHours);
      intent.putExtra(PREF_MAX_DEBUG_FILES, maxDebugFiles);
      intent.putExtra(PREF_MAX_TEMP_AGE_HOURS, maxTempAgeHours);
      intent.putExtra(PREF_MEMORY_THRESHOLD_PERCENT, memoryThresholdPercent);
      context.startService(intent);
    } catch (Exception e) {
      Log.w(TAG, "Service configuration update failed: " + e.getMessage());
    }
  }

  /**
   * Performs a direct cache cleanup operation without starting a service. This method handles
   * cleaning up various cache files and updates preferences to reflect the last cleanup time. It is
   * designed to be safe for background execution and can be used as a fallback when service-based
   * cleanup fails.
   *
   * @param context The context in which the cleanup will be performed. Typically, this is an
   *     instance of an Activity, Service, or Application. It is used to access application-specific
   *     resources, such as SharedPreferences for updating cleanup metadata.
   */
  public static void performDirectCleanup(Context context) {
    Log.i(TAG, "Direct cache cleanup requested (background-safe)");

    try {
      // Perform cleanup directly without starting service
      performCacheCleanupDirect(context);

      // Update preferences to reflect cleanup time
      SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
      prefs.edit().putLong(PREF_LAST_CLEANUP, System.currentTimeMillis()).apply();

      Log.i(TAG, "Direct cache cleanup completed successfully");

    } catch (Exception e) {
      Log.e(TAG, "Error during direct cache cleanup", e);
    }
  }

  /**
   * Performs a direct cache cleanup operation. This method cleans up debug files, old camera
   * images, and temporary files while logging memory usage before and after the cleanup. Garbage
   * collection is also explicitly triggered during the process. The operation is designed to be
   * executed in a controlled manner to efficiently manage resources and log cleanup performance
   * metrics.
   *
   * @param context The context in which the cleanup is performed. This is typically an instance of
   *     an Activity, Service, or Application, used to access application-specific resources
   *     required during the cleanup process.
   */
  private static void performCacheCleanupDirect(Context context) {
    long startTime = System.currentTimeMillis();

    try {
      // Log memory before cleanup
      logMemoryUsageStatic("before direct cleanup");

      // Cleanup different cache types directly
      int debugFilesCleanup = cleanupDebugImagesDirect(context);
      int cameraFilesCleanup = cleanupOldCameraImagesDirect(context);
      int tempFilesCleanup = cleanupTempFilesDirect(context);

      // Force garbage collection
      System.gc();

      // Log memory after cleanup
      logMemoryUsageStatic("after direct cleanup");

      long duration = System.currentTimeMillis() - startTime;

      Log.i(
          TAG,
          String.format(
              "Direct cache cleanup completed in %dms. Files removed: debug=%d, camera=%d, temp=%d",
              duration, debugFilesCleanup, cameraFilesCleanup, tempFilesCleanup));

    } catch (Exception e) {
      Log.e(TAG, "Error during direct cache cleanup", e);
    }
  }

  /**
   * Cleans up debug images directly from the application's external files directory. This method
   * identifies and deletes debug image files prefixed with "debug_" and suffixed with ".png". It
   * ensures that only a predefined maximum number of recent debug files are retained, deleting the
   * older ones to free up storage space.
   *
   * @param context The application context used to access the external files directory. This must
   *     not be null, as it is required to retrieve the directory.
   * @return The number of debug image files successfully deleted during the cleanup process.
   *     Returns 0 if no files are deleted or if an error occurs.
   */
  private static int cleanupDebugImagesDirect(Context context) {
    try {
      File externalDir = context.getExternalFilesDir(null);
      if (externalDir == null || !externalDir.exists()) return 0;

      File[] debugFiles =
          externalDir.listFiles((file, name) -> name.startsWith("debug_") && name.endsWith(".png"));
      if (debugFiles == null) return 0;

      // Use default max files value
      int maxDebugFiles = DEFAULT_MAX_DEBUG_FILES;

      // Sort by last modified date (oldest first)
      Arrays.sort(debugFiles, Comparator.comparingLong(File::lastModified));

      // Keep only the most recent debug files
      int filesToDelete = Math.max(0, debugFiles.length - maxDebugFiles);
      int deletedCount = 0;

      for (int i = 0; i < filesToDelete; i++) {
        if (debugFiles[i].delete()) {
          deletedCount++;
        }
      }

      if (deletedCount > 0) {
        Log.d(TAG, "Direct cleanup: Cleaned up " + deletedCount + " debug images");
      }

      return deletedCount;

    } catch (Exception e) {
      Log.e(TAG, "Error in direct debug images cleanup", e);
      return 0;
    }
  }

  /**
   * Cleans up old camera image files directly from the application's external files directory. This
   * method identifies image files with a specific naming pattern, compares their last modified
   * timestamps to a defined cutoff time, and deletes files that exceed the maximum allowed age.
   *
   * @param context The application context used to access the external files directory. This must
   *     not be null, as it is required to retrieve the directory for performing the cleanup.
   * @return The number of camera image files successfully deleted during the cleanup process.
   *     Returns 0 if no files are deleted or if an error occurs.
   */
  private static int cleanupOldCameraImagesDirect(Context context) {
    try {
      File picturesDir =
          new File(
              context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "MakeACopy");
      if (!picturesDir.exists()) return 0;

      File[] imageFiles =
          picturesDir.listFiles(
              (file, name) -> name.startsWith("MakeACopy_") && name.endsWith(".jpg"));
      if (imageFiles == null) return 0;

      // Use default max age value
      long maxAgeMs = DEFAULT_MAX_TEMP_AGE_HOURS * 60 * 60 * 1000L;
      long cutoffTime = System.currentTimeMillis() - maxAgeMs;
      int deletedCount = 0;

      for (File imageFile : imageFiles) {
        if (imageFile.lastModified() < cutoffTime && imageFile.delete()) {
          deletedCount++;
        }
      }

      if (deletedCount > 0) {
        Log.d(TAG, "Direct cleanup: Cleaned up " + deletedCount + " old camera images");
      }

      return deletedCount;

    } catch (Exception e) {
      Log.e(TAG, "Error in direct camera images cleanup", e);
      return 0;
    }
  }

  /**
   * Cleans up temporary files directly from the application's cache directory. This method attempts
   * to delete files and directories within the app's cache directory and returns the total count of
   * items successfully deleted. In case of any error during the cleanup process, the method logs
   * the exception and returns 0.
   *
   * @param context The application context used to access the cache directory. This must not be
   *     null, as it is necessary to perform the cleanup operation.
   * @return The total number of files and directories successfully deleted. Returns 0 if no items
   *     are deleted or if an error occurs.
   */
  private static int cleanupTempFilesDirect(Context context) {
    try {
      int total = 0;
      // Cleanup internal app cache directory
      File cacheDir = context.getCacheDir();
      total += cleanupDirectoryRecursivelyDirect(cacheDir);
      // Also cleanup external cache directory if available
      File extCacheDir = context.getExternalCacheDir();
      if (extCacheDir != null) {
        total += cleanupDirectoryRecursivelyDirect(extCacheDir);
      }
      return total;

    } catch (Exception e) {
      Log.e(TAG, "Error in direct temp files cleanup", e);
      return 0;
    }
  }

  /**
   * Recursively cleans up a directory by deleting files and subdirectories that match age or empty
   * directory criteria. Files older than the defined maximum temporary file age will be deleted.
   * Empty directories are also removed.
   *
   * @param directory the directory to be cleaned up. If the directory is null, does not exist, or
   *     is not a valid directory, the method returns immediately without performing any actions.
   * @return the number of files and directories that were successfully deleted during the cleanup
   *     process.
   */
  private static int cleanupDirectoryRecursivelyDirect(File directory) {
    if (directory == null || !directory.exists() || !directory.isDirectory()) {
      return 0;
    }

    int deletedCount = 0;
    long maxAgeMs = DEFAULT_MAX_TEMP_AGE_HOURS * 60 * 60 * 1000L;
    long cutoffTime = System.currentTimeMillis() - maxAgeMs;

    File[] files = directory.listFiles();
    if (files == null) return 0;

    for (File file : files) {
      if (file.isDirectory()) {
        deletedCount += cleanupDirectoryRecursivelyDirect(file);
        // Remove empty directories
        if (file.list() != null && file.list().length == 0) {
          if (file.delete()) deletedCount++;
        }
      } else {
        if (file.lastModified() < cutoffTime && file.delete()) {
          deletedCount++;
        }
      }
    }

    return deletedCount;
  }

  /**
   * Logs the memory usage including the used memory, maximum memory, and usage percentage.
   *
   * @param context A descriptive string to provide context for the memory usage log.
   */
  private static void logMemoryUsageStatic(String context) {
    try {
      Runtime runtime = Runtime.getRuntime();
      long usedMemory = runtime.totalMemory() - runtime.freeMemory();
      long maxMemory = runtime.maxMemory();
      double memoryUsagePercent = (double) usedMemory / maxMemory * 100;

      Log.d(
          TAG,
          String.format(
              "Memory usage %s: %.1f%% (%d MB / %d MB)",
              context, memoryUsagePercent, usedMemory / (1024 * 1024), maxMemory / (1024 * 1024)));

    } catch (Exception e) {
      Log.e(TAG, "Error logging memory usage", e);
    }
  }
}
