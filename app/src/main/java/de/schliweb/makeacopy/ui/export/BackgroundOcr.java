/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.export;

import android.content.Context;
import de.schliweb.makeacopy.jobs.OcrBackgroundJobs;
import de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel;
import de.schliweb.makeacopy.ui.ocr.OCRViewModel;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.OCRUtils;
import java.util.function.Supplier;

/**
 * OCR of a scan-flow page without the OCR step ("OCR in the background", {@link
 * ExportPrefsHelper#isOcrInBackground}): the page goes from the crop straight to the export screen
 * and is read by {@link OcrBackgroundJobs} once its image is on disk. The session keeps the page as
 * pending from the request until the result reached it, so an export waits for it.
 */
public final class BackgroundOcr {
  private static final String PREFS_NAME = "export_options";
  private static final String PREF_KEY_OCR_LANG = "ocr_language";

  private BackgroundOcr() {}

  /**
   * The OCR language of a job: the persisted choice first, then the view model's, then a default
   * derived from the system language. The persisted choice leads because the view model starts with
   * a fixed language and only the OCR screen, which this flow never opens, corrects it.
   */
  public static String language(Context ctx, OCRViewModel ocrViewModel) {
    String lang = null;
    if (ctx != null) {
      try {
        lang =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY_OCR_LANG, null);
      } catch (RuntimeException ignore) {
        // Best-effort; the fallbacks below apply
      }
    }
    if (lang == null || lang.trim().isEmpty()) {
      OCRViewModel.OcrUiState st = ocrViewModel != null ? ocrViewModel.getState().getValue() : null;
      lang = (st != null) ? st.language() : null;
    }
    if (lang == null || lang.trim().isEmpty()) {
      lang = OCRUtils.resolveEffectiveLanguage(lang);
    }
    return lang;
  }

  /**
   * Hands the persisted page to the job runner. May be called from the thread that wrote the page;
   * the page must be in the registry already.
   */
  public static void start(
      Context ctx,
      ExportSessionViewModel session,
      String pageId,
      String language,
      Supplier<OCRHelper> ocrHelperSupplier) {
    if (ctx == null || session == null || pageId == null) return;
    OcrBackgroundJobs.enqueueReprocess(
        ctx.getApplicationContext(), pageId, language, ocrHelperSupplier);
    // Queued only after the runner knows the job: "queued and not running" then means it ended
    session.markBackgroundOcrQueued(pageId);
  }
}
