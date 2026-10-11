/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import androidx.biometric.BiometricManager;
import de.schliweb.makeacopy.settings.SettingsCatalog;
import lombok.experimental.UtilityClass;

/**
 * The optional app lock (security audit F-07): with it, MainActivity covers its content until the
 * user confirms with fingerprint, face or the device screen lock. The app is locked after a cold
 * start and after it has been in the background for {@link #TIMEOUT_MS}; a shorter absence, such as
 * picking a file or sharing a document, does not ask again.
 *
 * <p>The unlock state lives in the process only, so a restarted process is always locked.
 */
@UtilityClass
public class AppLock {

  public static final String KEY = "app_lock_enabled";

  /** How long the app may be in the background before it locks again. */
  public static final long TIMEOUT_MS = 30_000L;

  /**
   * Biometrics of any class or the device credential. BIOMETRIC_STRONG | DEVICE_CREDENTIAL is not
   * supported on Android 10 (API 29), the app's minimum; BIOMETRIC_WEAK includes strong sensors.
   */
  public static final int AUTHENTICATORS =
      BiometricManager.Authenticators.BIOMETRIC_WEAK
          | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

  private static boolean unlocked;
  private static long backgroundSince = -1L;

  public static boolean isEnabled(Context ctx) {
    return ctx != null
        && ctx.getSharedPreferences(SettingsCatalog.PREFS_MAIN, Context.MODE_PRIVATE)
            .getBoolean(KEY, false);
  }

  public static void setEnabled(Context ctx, boolean enabled) {
    ctx.getSharedPreferences(SettingsCatalog.PREFS_MAIN, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY, enabled)
        .apply();
  }

  /** Whether the device has a screen lock or enrolled biometrics the lock can ask for. */
  public static boolean isAvailable(Context ctx) {
    return BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS)
        == BiometricManager.BIOMETRIC_SUCCESS;
  }

  /**
   * With the lock on, the recent-apps overview shows no preview of the app (Android 13 and later;
   * older versions need "Hide scans from screenshots and recent apps" for that).
   */
  public static void applyRecentsPreview(Activity activity) {
    if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      activity.setRecentsScreenshotEnabled(!isEnabled(activity));
    }
  }

  public static synchronized boolean needsUnlock(Context ctx) {
    return needsUnlock(isEnabled(ctx), unlocked, backgroundSince, SystemClock.elapsedRealtime());
  }

  public static synchronized void markUnlocked() {
    unlocked = true;
    backgroundSince = -1L;
  }

  /** The lock screen's activity went to the background (not for a configuration change). */
  public static synchronized void markBackground() {
    if (unlocked && backgroundSince < 0) backgroundSince = SystemClock.elapsedRealtime();
  }

  /** Back in the foreground within the timeout: the clock starts again on the next absence. */
  public static synchronized void markForeground() {
    if (unlocked && backgroundSince >= 0) {
      if (SystemClock.elapsedRealtime() - backgroundSince >= TIMEOUT_MS) {
        unlocked = false;
      }
      backgroundSince = -1L;
    }
  }

  static boolean needsUnlock(boolean enabled, boolean unlocked, long backgroundSince, long now) {
    if (!enabled) return false;
    if (!unlocked) return true;
    return backgroundSince >= 0 && now - backgroundSince >= TIMEOUT_MS;
  }
}
