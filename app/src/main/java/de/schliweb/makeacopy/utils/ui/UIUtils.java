/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import lombok.experimental.UtilityClass;

/**
 * A utility class containing helper methods for common user interface tasks. This class provides
 * functions to adjust view margins for system insets, handle status bar height, and display Toast
 * messages safely.
 *
 * <p>This class is not intended to be instantiated.
 */
@UtilityClass
public class UIUtils {
  private static final String TAG = "UIUtils";

  /**
   * Adjusts the bottom margin of the given view to account for system insets, such as the
   * navigation bar, while also applying an additional base margin specified in dp.
   *
   * @param view The view whose bottom margin should be adjusted. If null, the method does nothing.
   * @param baseMarginDp The base margin in dp to be added to the system insets. This value is
   *     converted to pixels before being applied.
   */
  public static void adjustMarginForSystemInsets(View view, int baseMarginDp) {
    if (view == null) {
      return;
    }

    ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
    if (params == null) {
      return;
    }

    int bottomInset = 0;
    WindowInsetsCompat windowInsets = ViewCompat.getRootWindowInsets(view);
    if (windowInsets != null) {
      bottomInset = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
    }

    // Convert dp to pixels
    float density = view.getResources().getDisplayMetrics().density;
    int baseMarginPx = (int) (baseMarginDp * density);

    params.bottomMargin = baseMarginPx + bottomInset;
    view.setLayoutParams(params);
  }

  /**
   * Adjusts the top margin of the given TextView to account for the status bar's height, while also
   * including an additional base margin specified in dp. The method calculates the status bar
   * height using system insets and combines it with the provided base margin before applying the
   * resulting value to the TextView's top margin.
   *
   * @param textView The TextView whose top margin should be adjusted. If null, the method does
   *     nothing.
   * @param baseMarginDp The base margin in dp to be added to the status bar's height. This value is
   *     converted to pixels before being applied.
   */
  public static void adjustTextViewTopMarginForStatusBar(TextView textView, int baseMarginDp) {
    if (textView == null) {
      return;
    }

    ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) textView.getLayoutParams();
    if (params == null) {
      return;
    }

    int topInset = 0;
    WindowInsetsCompat windowInsets = ViewCompat.getRootWindowInsets(textView);
    if (windowInsets != null) {
      topInset = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
    }

    // Convert dp to pixels
    float density = textView.getResources().getDisplayMetrics().density;
    int baseMarginPx = (int) (baseMarginDp * density);

    params.topMargin = baseMarginPx + topInset;
    textView.setLayoutParams(params);
  }

  /**
   * Displays a toast message using the provided string and duration. If Accessibility Mode is
   * enabled, the message is announced via the device's screen reader instead of showing a toast.
   * Ensures the use of application context to prevent memory leaks or context-related issues. If
   * the context or message is null, the method does nothing.
   *
   * @param context The context from which the toast is triggered. If null, no action is taken.
   * @param message The string message to display in the toast. If null, no action is taken.
   * @param duration The duration for which the toast should be displayed. Should be either
   *     Toast.LENGTH_SHORT or Toast.LENGTH_LONG.
   */
  @SuppressWarnings("deprecation")
  public static void showToast(Context context, String message, int duration) {
    if (context == null || message == null) {
      return;
    }

    // Always use the application context to prevent memory leaks and context-related issues
    Context appContext = context.getApplicationContext();

    // If Accessibility Mode is enabled, announce via screen reader instead of showing a toast
    try {
      SharedPreferences prefs =
          appContext.getSharedPreferences("export_options", Context.MODE_PRIVATE);
      boolean a11yMode =
          prefs.getBoolean(
              de.schliweb.makeacopy.ui.camera.CameraOptionsDialogFragment.BUNDLE_ACCESSIBILITY_MODE,
              false);
      Log.d(TAG, "Accessibility Mode: " + a11yMode);
      if (a11yMode) {
        AccessibilityManager am =
            (AccessibilityManager) appContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am != null && am.isEnabled()) {
          AccessibilityEvent event =
              AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
          event.setPackageName(appContext.getPackageName());
          event.setClassName(UIUtils.class.getName());
          event.getText().add(message);
          am.sendAccessibilityEvent(event);
          Log.d(TAG, "Accessibility announcement made: " + message);
          return; // Do not show a Toast when A11y announcement is made
        }
      }
    } catch (Throwable ignore) {
      Log.e(TAG, "Error checking accessibility mode", ignore);
      // Best-effort: fall back to Toast below
    }

    Toast.makeText(appContext, message, duration).show();
  }

  /**
   * Displays a toast message using a string resource ID and a specified duration. The method
   * resolves the resource string and displays it as a toast. The application context is used
   * internally to ensure memory safety and avoid context-related issues. If the context is null or
   * the resource ID cannot be resolved, the method does nothing.
   *
   * @param context The context from which the toast is triggered. If null, no action is taken.
   * @param resId The resource ID of the string to display in the toast. If the resource ID cannot
   *     be resolved, no action is taken.
   * @param duration The duration for which the toast should be displayed. Should be either
   *     Toast.LENGTH_SHORT or Toast.LENGTH_LONG.
   */
  public static void showToast(Context context, int resId, int duration) {
    if (context == null) {
      return;
    }

    // Always use the application context to prevent memory leaks and context-related issues
    Context appContext = context.getApplicationContext();

    // Resolve string now to funnel through the same accessibility path
    String msg;
    try {
      // The application context does not follow the in-app language on Android 12 and older
      msg = AppLanguage.localize(appContext).getString(resId);
    } catch (Throwable t) {
      msg = null;
    }
    if (msg != null) {
      showToast(appContext, msg, duration);
    }
  }
}
