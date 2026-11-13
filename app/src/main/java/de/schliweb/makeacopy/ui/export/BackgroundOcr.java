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
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.OCRUtils;
import de.schliweb.makeacopy.utils.ocr.paddle.PaddleEngineFactory;
import java.util.Locale;
import java.util.function.Predicate;
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
   * The OCR language of a job: the persisted choice first, then the default the OCR screen would
   * pick for the system language. The view model is not asked: it starts with a fixed language and
   * only the OCR screen, which this flow never opens, corrects it.
   */
  public static String language(Context ctx) {
    String persisted = null;
    if (ctx != null) {
      try {
        persisted =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY_OCR_LANG, null);
      } catch (RuntimeException ignore) {
        // Best-effort; the default below applies
      }
    }
    return pickLanguage(
        persisted,
        OCRUtils.mapSystemLanguageToPaddleModel(Locale.getDefault()),
        lang -> ctx == null || PaddleEngineFactory.isLanguageBundled(ctx, lang));
  }

  /**
   * {@code persisted} if set, else {@code systemDefault} if its model is bundled, else Latin (the
   * model every edition ships).
   */
  static String pickLanguage(String persisted, String systemDefault, Predicate<String> bundled) {
    if (persisted != null && !persisted.trim().isEmpty()) return persisted;
    if (systemDefault != null && bundled.test(systemDefault)) return systemDefault;
    return "latin";
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
