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
import android.widget.TextView;
import androidx.annotation.NonNull;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.settings.SettingsCatalog;
import de.schliweb.makeacopy.ui.export.BackgroundOcr;
import de.schliweb.makeacopy.utils.ocr.MultiColumnOcrPrefs;
import de.schliweb.makeacopy.utils.ocr.OCRUtils;
import de.schliweb.makeacopy.utils.ocr.OcrModelManager;
import de.schliweb.makeacopy.utils.ui.DialogUtils;

/**
 * The OCR options (language, auto-rotate, PaddleOCR detection at 1920 px, multi-column reading
 * order) as one reusable block: {@code panel_ocr_options.xml} plus the code that fills it from the
 * saved settings and writes the choices back. The OCR screen's dialog and the camera options dialog
 * both embed it.
 */
public final class OcrOptionsPanel {

  static final String BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT = "ocr_auto_rotate_apply_export";
  static final String BUNDLE_PADDLE_BEST_OCR = "paddle_best_ocr";
  static final String PREF_KEY_OCR_LANG = "ocr_language";

  /**
   * What the user chose when the panel was applied; {@code language} is {@code null} unless the
   * user picked one here.
   */
  public record Choice(
      boolean autoRotateApplyExport,
      boolean paddleBestOcr,
      boolean multiColumnOcr,
      String language) {}

  private final CheckBox autoRotate;
  private final CheckBox paddleBestOcr;
  private final CheckBox multiColumnOcr;
  private final TextView languageButton;

  /** The language picked in this dialog, not saved before {@link #apply}; null = unchanged. */
  private String pickedLanguage;

  public OcrOptionsPanel(@NonNull View root) {
    autoRotate = root.findViewById(R.id.checkbox_ocr_auto_rotate_apply_export_dialog);
    paddleBestOcr = root.findViewById(R.id.checkbox_paddle_best_ocr_dialog);
    multiColumnOcr = root.findViewById(R.id.checkbox_multi_column_ocr_dialog);
    languageButton = root.findViewById(R.id.button_ocr_language_dialog);
  }

  /**
   * Shows or hides the language choice. The OCR screen hides it: its own language button re-reads
   * the page at once, which a choice made here would not.
   */
  public void setLanguageChoiceVisible(boolean visible) {
    languageButton.setVisibility(visible ? View.VISIBLE : View.GONE);
  }

  /** The language picked here and not saved yet, for restoring the dialog; may be null. */
  public String pickedLanguage() {
    return pickedLanguage;
  }

  /** Restores a language picked before the dialog was rebuilt. */
  public void restorePickedLanguage(@NonNull Context ctx, String language) {
    if (language == null || language.trim().isEmpty()) return;
    pickedLanguage = language;
    showLanguage(ctx, language);
  }

  private void showLanguage(Context ctx, String code) {
    languageButton.setText(
        ctx.getString(R.string.ocr_batch_option_language, OCRUtils.codeToDisplayName(ctx, code)));
  }

  private void pickLanguage(Context ctx) {
    final String[] codes = OcrModelManager.getAvailableLanguageCodes(ctx);
    if (codes.length == 0) return;
    final String[] names = new String[codes.length];
    String current = pickedLanguage != null ? pickedLanguage : BackgroundOcr.language(ctx);
    int checked = -1;
    for (int i = 0; i < codes.length; i++) {
      names[i] = OCRUtils.codeToDisplayName(ctx, codes[i]);
      if (codes[i].equals(current)) checked = i;
    }
    androidx.appcompat.app.AlertDialog dlg =
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.select_ocr_languages)
            .setSingleChoiceItems(
                names,
                checked,
                (dialog, which) -> {
                  pickedLanguage = codes[which];
                  showLanguage(ctx, pickedLanguage);
                  dialog.dismiss();
                })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
    dlg.setOnShowListener(d -> DialogUtils.improveAlertDialogButtonContrastForNight(dlg, ctx));
    dlg.show();
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
    // The saved language, or the one a job would use without a saved choice
    showLanguage(ctx, BackgroundOcr.language(ctx));
    languageButton.setOnClickListener(v -> pickLanguage(ctx));
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
      if (pickedLanguage != null) {
        prefs(ctx).edit().putString(PREF_KEY_OCR_LANG, pickedLanguage).apply();
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    return new Choice(
        autoRotate.isChecked(),
        paddleBestOcr.isChecked(),
        multiColumnOcr.isChecked(),
        pickedLanguage);
  }

  /** Greys the whole panel out, for when OCR is skipped altogether. */
  public void setEnabled(boolean enabled) {
    for (View v : new View[] {languageButton, autoRotate, paddleBestOcr, multiColumnOcr}) {
      de.schliweb.makeacopy.utils.ui.UIUtils.setEnabledWithAlpha(v, enabled);
    }
  }

  private static SharedPreferences prefs(Context ctx) {
    return ctx.getSharedPreferences(SettingsCatalog.PREFS_MAIN, Context.MODE_PRIVATE);
  }
}
