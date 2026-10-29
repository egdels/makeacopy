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

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.CheckBox;
import androidx.annotation.NonNull;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.settings.SettingsCatalog;
import de.schliweb.makeacopy.utils.ocr.MultiColumnOcrPrefs;

/**
 * The OCR options (auto-rotate, PaddleOCR detection at 1920 px, multi-column reading order) as one
 * reusable block: {@code panel_ocr_options.xml} plus the code that fills it from the saved settings
 * and writes the choices back. The OCR screen's dialog and the camera options dialog both embed it.
 */
public final class OcrOptionsPanel {

  static final String BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT = "ocr_auto_rotate_apply_export";
  static final String BUNDLE_PADDLE_BEST_OCR = "paddle_best_ocr";

  /** What the user chose when the panel was applied. */
  public record Choice(
      boolean autoRotateApplyExport, boolean paddleBestOcr, boolean multiColumnOcr) {}

  private final CheckBox autoRotate;
  private final CheckBox paddleBestOcr;
  private final CheckBox multiColumnOcr;

  public OcrOptionsPanel(@NonNull View root) {
    autoRotate = root.findViewById(R.id.checkbox_ocr_auto_rotate_apply_export_dialog);
    paddleBestOcr = root.findViewById(R.id.checkbox_paddle_best_ocr_dialog);
    multiColumnOcr = root.findViewById(R.id.checkbox_multi_column_ocr_dialog);
  }

  /** Shows the saved OCR options. */
  public void bind(@NonNull Context ctx) {
    boolean ocrAutoRotateApply = false;
    // Detection at 1920 px: on by default.
    boolean paddleBest = true;
    boolean multiColumn = MultiColumnOcrPrefs.DEFAULT;
    try {
      SharedPreferences p = prefs(ctx);
      ocrAutoRotateApply = p.getBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, false);
      paddleBest = p.getBoolean(BUNDLE_PADDLE_BEST_OCR, true);
      multiColumn = p.getBoolean(MultiColumnOcrPrefs.KEY, MultiColumnOcrPrefs.DEFAULT);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    autoRotate.setChecked(ocrAutoRotateApply);
    paddleBestOcr.setChecked(paddleBest);
    multiColumnOcr.setChecked(multiColumn);
  }

  /** Persists the choices and returns them. */
  @NonNull
  public Choice apply(@NonNull Context ctx) {
    try {
      prefs(ctx)
          .edit()
          .putBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, autoRotate.isChecked())
          .putBoolean(BUNDLE_PADDLE_BEST_OCR, paddleBestOcr.isChecked())
          .putBoolean(MultiColumnOcrPrefs.KEY, multiColumnOcr.isChecked())
          .apply();
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    return new Choice(
        autoRotate.isChecked(), paddleBestOcr.isChecked(), multiColumnOcr.isChecked());
  }

  /** Greys the whole panel out, for when OCR is skipped altogether. */
  public void setEnabled(boolean enabled) {
    for (View v : new View[] {autoRotate, paddleBestOcr, multiColumnOcr}) {
      de.schliweb.makeacopy.utils.ui.UIUtils.setEnabledWithAlpha(v, enabled);
    }
  }

  private static SharedPreferences prefs(Context ctx) {
    return ctx.getSharedPreferences(SettingsCatalog.PREFS_MAIN, Context.MODE_PRIVATE);
  }
}
