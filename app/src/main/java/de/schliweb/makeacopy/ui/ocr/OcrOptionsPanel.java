/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.ocr;

import static de.schliweb.makeacopy.utils.ocr.OCRHelper.OCR_MODE_ORIGINAL;
import static de.schliweb.makeacopy.utils.ocr.OCRHelper.OCR_MODE_PADDLE;
import static de.schliweb.makeacopy.utils.ocr.OCRHelper.OCR_MODE_QUICK;
import static de.schliweb.makeacopy.utils.ocr.OCRHelper.OCR_MODE_ROBUST;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.settings.SettingsCatalog;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.ocr.MultiColumnOcrPrefs;
import de.schliweb.makeacopy.utils.ocr.PaddleOcrPrefs;

/**
 * The OCR options (recognition mode, auto-rotate, post-processing, layout analysis, PaddleOCR
 * switches) as one reusable block: {@code panel_ocr_options.xml} plus the code that fills it from
 * the saved settings and writes the choices back. The OCR screen's dialog and the camera options
 * dialog both embed it. The persisted recognition mode with its migrations lives here as well.
 */
public final class OcrOptionsPanel {

  static final String PREF_KEY_OCR_MODE = "ocr_prep_mode"; // 0=Original,1=Quick,2=Robust,3=Paddle
  static final String BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT = "ocr_auto_rotate_apply_export";
  static final String BUNDLE_OCR_POST_PROCESSING = "ocr_post_processing";
  static final String BUNDLE_PADDLE_BEST_OCR = "paddle_best_ocr";
  static final String BUNDLE_LAYOUT_ANALYSIS = "layout_analysis";

  /** What the user chose when the panel was applied. */
  public record Choice(
      int mode,
      boolean autoRotateApplyExport,
      boolean postProcessing,
      boolean layoutAnalysis,
      boolean paddleBestOcr,
      boolean multiColumnOcr) {}

  private final RadioGroup modes;
  private final CheckBox autoRotate;
  private final CheckBox postProcessing;
  private final CheckBox layoutAnalysis;
  private final CheckBox paddleBestOcr;
  private final CheckBox multiColumnOcr;

  /** PaddleOCR is the only engine of the paddle flavor: no mode picker there. */
  private final boolean fixedPaddleMode = BuildConfig.FEATURE_PADDLE_OCR;

  /** PaddleOCR can be chosen as a mode (standard flavor on a supported ABI). */
  private final boolean paddleToggleVisible = PaddleOcrPrefs.isToggleVisible();

  public OcrOptionsPanel(@NonNull View root) {
    modes = root.findViewById(R.id.rg_ocr_modes);
    autoRotate = root.findViewById(R.id.checkbox_ocr_auto_rotate_apply_export_dialog);
    postProcessing = root.findViewById(R.id.checkbox_ocr_post_processing_dialog);
    layoutAnalysis = root.findViewById(R.id.checkbox_layout_analysis_dialog);
    paddleBestOcr = root.findViewById(R.id.checkbox_paddle_best_ocr_dialog);
    multiColumnOcr = root.findViewById(R.id.checkbox_multi_column_ocr_dialog);
    RadioButton rbPaddle = root.findViewById(R.id.rbtn_mode_paddle);
    if (fixedPaddleMode) modes.setVisibility(View.GONE);
    rbPaddle.setVisibility(paddleToggleVisible ? View.VISIBLE : View.GONE);
  }

  /** Shows the saved mode and OCR options and hides what does not apply. */
  public void bind(@NonNull Context ctx) {
    boolean layoutFeatureEnabled = FeatureFlags.isLayoutAnalysisEnabled();
    layoutAnalysis.setVisibility(layoutFeatureEnabled ? View.VISIBLE : View.GONE);
    paddleBestOcr.setVisibility(fixedPaddleMode ? View.VISIBLE : View.GONE);
    multiColumnOcr.setVisibility(fixedPaddleMode ? View.VISIBLE : View.GONE);

    final int initialMode =
        prepModeForPicker(selectedOcrMode(ctx), fixedPaddleMode, paddleToggleVisible);
    modes.check(radioIdForPrepMode(initialMode));

    boolean ocrAutoRotateApply = false;
    boolean ocrPostProcessing = true; // default ON
    boolean layout = false; // default OFF
    // Detection at 1920 px and multi-column reading: on by default where they exist (paddle).
    boolean paddleBest = fixedPaddleMode;
    boolean multiColumn = MultiColumnOcrPrefs.DEFAULT;
    try {
      SharedPreferences p = prefs(ctx);
      ocrAutoRotateApply = p.getBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, false);
      ocrPostProcessing = p.getBoolean(BUNDLE_OCR_POST_PROCESSING, true);
      layout = p.getBoolean(BUNDLE_LAYOUT_ANALYSIS, false);
      paddleBest = p.getBoolean(BUNDLE_PADDLE_BEST_OCR, fixedPaddleMode);
      multiColumn = p.getBoolean(MultiColumnOcrPrefs.KEY, MultiColumnOcrPrefs.DEFAULT);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    autoRotate.setChecked(ocrAutoRotateApply);
    postProcessing.setChecked(ocrPostProcessing);
    postProcessing.setVisibility(initialMode == OCR_MODE_PADDLE ? View.GONE : View.VISIBLE);
    layoutAnalysis.setChecked(layout && layoutFeatureEnabled);
    paddleBestOcr.setChecked(fixedPaddleMode && paddleBest);
    multiColumnOcr.setChecked(fixedPaddleMode && multiColumn);
    modes.setOnCheckedChangeListener(
        (group, checkedId) ->
            postProcessing.setVisibility(
                checkedId == R.id.rbtn_mode_paddle ? View.GONE : View.VISIBLE));
  }

  /** Persists the choices and returns them. */
  @NonNull
  public Choice apply(@NonNull Context ctx) {
    int selectedMode = OCR_MODE_PADDLE;
    if (!fixedPaddleMode) {
      selectedMode = prepModeForRadioId(modes.getCheckedRadioButtonId(), paddleToggleVisible);
      setSelectedOcrMode(ctx, selectedMode);
    }

    boolean postProcessingVisible = selectedMode != OCR_MODE_PADDLE;
    boolean postProcessingSelected = postProcessingVisible && postProcessing.isChecked();
    try {
      SharedPreferences.Editor editor =
          prefs(ctx)
              .edit()
              .putBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, autoRotate.isChecked())
              .putBoolean(BUNDLE_LAYOUT_ANALYSIS, layoutAnalysis.isChecked())
              .putBoolean(BUNDLE_PADDLE_BEST_OCR, fixedPaddleMode && paddleBestOcr.isChecked())
              .putBoolean(MultiColumnOcrPrefs.KEY, fixedPaddleMode && multiColumnOcr.isChecked());
      if (postProcessingVisible) {
        editor.putBoolean(BUNDLE_OCR_POST_PROCESSING, postProcessingSelected);
      }
      editor.apply();
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    return new Choice(
        selectedMode,
        autoRotate.isChecked(),
        postProcessingSelected,
        layoutAnalysis.isChecked(),
        fixedPaddleMode && paddleBestOcr.isChecked(),
        fixedPaddleMode && multiColumnOcr.isChecked());
  }

  /** Greys the whole panel out, for when OCR is skipped altogether. */
  public void setEnabled(boolean enabled) {
    for (View v :
        new View[] {
          modes, autoRotate, postProcessing, layoutAnalysis, paddleBestOcr, multiColumnOcr
        }) {
      de.schliweb.makeacopy.utils.ui.UIUtils.setEnabledWithAlpha(v, enabled);
    }
  }

  public boolean isFixedPaddleMode() {
    return fixedPaddleMode;
  }

  private static SharedPreferences prefs(Context ctx) {
    return ctx.getSharedPreferences(SettingsCatalog.PREFS_MAIN, Context.MODE_PRIVATE);
  }

  /**
   * The persisted recognition mode as it applies to this build, migrating old values on the way:
   * Quick (no longer offered) becomes Robust, the former PaddleOCR toggle becomes the Paddle mode,
   * and a saved Paddle mode falls back to Robust where PaddleOCR is not available.
   */
  public static int selectedOcrMode(@NonNull Context ctx) {
    if (BuildConfig.FEATURE_PADDLE_OCR) {
      return OCR_MODE_PADDLE;
    }
    try {
      SharedPreferences sp = prefs(ctx);
      // Migration: Quick is no longer a user-facing mode (FR#74 benchmark 2026-04-26b
      // showed Quick == Robust for binaryOutput=false). Map any persisted Quick to Robust
      // and default new installs to Robust.
      int stored = sp.getInt(PREF_KEY_OCR_MODE, OCR_MODE_ROBUST);
      if (stored == OCR_MODE_QUICK) {
        stored = OCR_MODE_ROBUST;
        sp.edit().putInt(PREF_KEY_OCR_MODE, stored).apply();
      }
      // Migration: the experimental PaddleOCR toggle (PaddleOcrPrefs.KEY) used to be a
      // separate checkbox alongside the prep-mode picker. It is now folded into the
      // recognition-mode radio group as OCR_MODE_PADDLE. Existing users that opted into
      // PaddleOCR keep their preference: map the toggle to the new mode and clear the
      // legacy key.
      try {
        if (sp.getBoolean(PaddleOcrPrefs.KEY, false)) {
          if (PaddleOcrPrefs.isToggleVisible() && stored != OCR_MODE_PADDLE) {
            stored = OCR_MODE_PADDLE;
            sp.edit().putInt(PREF_KEY_OCR_MODE, stored).remove(PaddleOcrPrefs.KEY).apply();
          } else {
            // Toggle no longer applicable (e.g. unsupported ABI) — drop legacy key.
            sp.edit().remove(PaddleOcrPrefs.KEY).apply();
          }
        }
      } catch (Throwable ignore) {
        // Best-effort migration; failure is non-critical.
      }
      // If a previously persisted PADDLE mode is no longer applicable (e.g. user installed
      // a non-paddle build), fall back to Robust without clobbering the stored value to
      // avoid data loss across re-installs of the paddle flavor.
      if (stored == OCR_MODE_PADDLE && !PaddleOcrPrefs.isToggleVisible()) {
        return OCR_MODE_ROBUST;
      }
      return stored;
    } catch (Throwable ignore) {
      return OCR_MODE_ROBUST;
    }
  }

  static void setSelectedOcrMode(@NonNull Context ctx, int mode) {
    try {
      prefs(ctx).edit().putInt(PREF_KEY_OCR_MODE, mode).apply();
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * The mode the picker shows for a saved mode. Quick is hidden in the picker (see
   * panel_ocr_options.xml: rbtn_mode_quick is gone), so a lingering Quick selection counts as
   * Robust; PaddleOCR is only valid while it can be chosen, otherwise it falls back to Robust.
   */
  @VisibleForTesting
  static int prepModeForPicker(
      int savedMode, boolean fixedPaddleMode, boolean paddleToggleVisible) {
    int mode = Math.max(0, Math.min(OCR_MODE_PADDLE, savedMode));
    if (mode == OCR_MODE_QUICK) mode = OCR_MODE_ROBUST;
    if (mode == OCR_MODE_PADDLE && !fixedPaddleMode && !paddleToggleVisible) {
      mode = OCR_MODE_ROBUST;
    }
    return mode;
  }

  private static int radioIdForPrepMode(int pickerMode) {
    if (pickerMode == OCR_MODE_ORIGINAL) return R.id.rbtn_mode_original;
    if (pickerMode == OCR_MODE_PADDLE) return R.id.rbtn_mode_paddle;
    return R.id.rbtn_mode_robust;
  }

  /** The mode for the checked radio button; anything else (incl. hidden Quick) is Robust. */
  @VisibleForTesting
  static int prepModeForRadioId(int checkedId, boolean paddleToggleVisible) {
    if (checkedId == R.id.rbtn_mode_original) return OCR_MODE_ORIGINAL;
    if (checkedId == R.id.rbtn_mode_paddle && paddleToggleVisible) return OCR_MODE_PADDLE;
    return OCR_MODE_ROBUST;
  }
}
