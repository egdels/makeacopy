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
import android.view.WindowManager;
import de.schliweb.makeacopy.settings.SettingsCatalog;
import lombok.experimental.UtilityClass;

/**
 * The "Protect screen content" setting (security audit F-04): with it, the main window is marked
 * secure, so screenshots, screen recordings and the recent-apps preview show no scan. All screens
 * with document content live in MainActivity, so its window is the one to mark.
 */
@UtilityClass
public class ScreenProtection {

  public static final String KEY = "protect_screen_content";

  public static boolean isEnabled(Context ctx) {
    return ctx != null
        && ctx.getSharedPreferences(SettingsCatalog.PREFS_MAIN, Context.MODE_PRIVATE)
            .getBoolean(KEY, false);
  }

  /** Marks {@code activity}'s window secure or not, as the setting says. */
  public static void apply(Activity activity) {
    if (activity == null || activity.getWindow() == null) return;
    if (isEnabled(activity)) {
      activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    } else {
      activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
  }
}
