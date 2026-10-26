/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import android.content.Context;
import android.content.SharedPreferences;
import de.schliweb.makeacopy.BuildConfig;

/**
 * A utility class for managing the user preference that enables multi-column layout reconstruction
 * (newspapers, magazines) for the PaddleOCR text flow. When enabled, {@link
 * OCRPostProcessor#wordsToText(java.util.List, boolean)} rebuilds the extracted text column by
 * column instead of interleaving side-by-side columns line by line.
 *
 * <p>This class is not intended to be instantiated.
 */
public final class MultiColumnOcrPrefs {

  /**
   * The name of the shared preferences file used to store the option. Uses the same file as the
   * other OCR options shown in the OCR options dialog.
   */
  public static final String PREFS_NAME = "export_options";

  /**
   * The key used in the shared preferences file to store and retrieve the enablement state of the
   * multi-column layout option. The associated preference value is a boolean flag; the default is
   * {@link #DEFAULT}.
   */
  public static final String KEY = "multi_column_ocr";

  /**
   * On in the paddle flavor, the only one that offers the option: a page that is not reliably
   * multi-column is read line by line anyway, and two-column pages went from a character error rate
   * of 0.66 to 0.00 with it (Sheet's OCR evaluation, same layout policy).
   */
  public static final boolean DEFAULT = BuildConfig.FEATURE_PADDLE_OCR;

  private MultiColumnOcrPrefs() {}

  public static boolean isEnabled(Context context) {
    if (context == null) return DEFAULT;
    SharedPreferences sp =
        context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    return sp.getBoolean(KEY, DEFAULT);
  }

  public static void setEnabled(Context context, boolean enabled) {
    if (context == null) return;
    context
        .getApplicationContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY, enabled)
        .apply();
  }
}
