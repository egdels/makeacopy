/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.jobs;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Log;
import de.schliweb.makeacopy.data.CompletedScansRegistry;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import de.schliweb.makeacopy.utils.image.ImageDecodeUtils;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.OCRUtils;
import de.schliweb.makeacopy.utils.ocr.OcrModelManager;
import de.schliweb.makeacopy.utils.ocr.OcrPageSegmentationMode;
import de.schliweb.makeacopy.utils.ocr.OcrRecognitionPipeline;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import de.schliweb.makeacopy.utils.ocr.WordsJson;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.experimental.UtilityClass;

/**
 * Minimal background OCR job runner without external dependencies. Ensures only one OCR job per
 * page id runs at a time. After success/failure, broadcasts ACTION_OCR_UPDATED with extras.
 *
 * <p>Recognition itself (adaptive preprocessing, optional auto-rotation with best-result selection
 * and early-exit, optional layout analysis with a full-page fallback) is shared with {@code
 * OCRFragment.performOCR()} via {@link OcrRecognitionPipeline}. Language resolution, Best/Fast
 * model detection and page-segmentation mode (PSM) tuning mirror the foreground pipeline. UI- and
 * ViewModel-only steps (toasts, navigation, transform pushes) are intentionally omitted.
 */
@UtilityClass
public final class OcrBackgroundJobs {
  private static final String TAG = "OcrBackgroundJobs";

  public static final String ACTION_OCR_UPDATED = "de.schliweb.makeacopy.ACTION_OCR_UPDATED";
  public static final String EXTRA_PAGE_ID = "page_id";
  public static final String EXTRA_SUCCESS = "success";

  // SharedPreferences keys mirrored from OCRFragment so background jobs honor the same user
  // preferences as the foreground OCR. Keep names in sync with OCRFragment.
  private static final String PREFS_NAME = "export_options";
  private static final String PREF_KEY_OCR_MODE = "ocr_prep_mode"; // 0=Original,1=Quick,2=Robust
  private static final String BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT = "ocr_auto_rotate_apply_export";
  private static final String BUNDLE_LAYOUT_ANALYSIS = "layout_analysis";

  private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
  private static final Set<String> running = Collections.synchronizedSet(new HashSet<>());
  private static final Set<String> cancelled = Collections.synchronizedSet(new HashSet<>());

  /**
   * Requests cancellation of a currently running background OCR job for the given page id. The job
   * checks the cancellation flag at well-defined points (before init, between rotation attempts)
   * and aborts as soon as possible. If no job is running for {@code pageId}, this is a no-op.
   */
  public static void cancel(String pageId) {
    if (pageId == null) return;
    cancelled.add(pageId);
  }

  /**
   * Enqueues a background reprocessing task for Optical Character Recognition (OCR) on a scanned
   * page. The method will attempt to generate and store OCR results including text and recognized
   * words for the specified page.
   *
   * @param ctx The application context used for accessing system resources.
   * @param pageId The unique identifier of the scanned page to be reprocessed.
   * @param languageOpt Optional language code for OCR processing (e.g., "eng" for English). If null
   *     or empty, a default language will be used.
   * @param ocrHelperSupplier Supplier for OCRHelper instances (DI-friendly). Must not be {@code
   *     null}.
   */
  public static void enqueueReprocess(
      Context ctx,
      String pageId,
      String languageOpt,
      java.util.function.Supplier<OCRHelper> ocrHelperSupplier) {
    if (ctx == null || pageId == null) return;
    final Context app = ctx.getApplicationContext();
    synchronized (running) {
      if (running.contains(pageId)) {
        Log.d(TAG, "Job already running for pageId=" + pageId);
        return;
      }
      running.add(pageId);
    }
    cancelled.remove(pageId);
    EXEC.execute(() -> runJob(app, pageId, languageOpt, ocrHelperSupplier));
  }

  /** Runs one OCR job on the executor thread and broadcasts the outcome. */
  private static void runJob(
      Context app,
      String pageId,
      String languageOpt,
      java.util.function.Supplier<OCRHelper> ocrHelperSupplier) {
    boolean success = false;
    OCRHelper helper = null;
    try {
      CompletedScansRegistry reg = CompletedScansRegistry.get(app);
      CompletedScan s = findScan(reg, pageId);
      if (s == null) throw new RuntimeException("Entry not found in registry: " + pageId);
      Bitmap bmp = loadUprightBitmap(s);
      if (bmp == null) throw new RuntimeException("No bitmap available for OCR");

      if (cancelled.contains(pageId)) {
        throw new OcrRecognitionPipeline.CancelledException("Cancelled before OCR init");
      }

      helper = ocrHelperSupplier.get();
      configureHelper(app, helper, languageOpt);
      if (!helper.initTesseract()) throw new RuntimeException("Tesseract init failed");

      OcrRecognitionPipeline.Options prefs = readJobPrefs(app);

      // Tune Tesseract PSM based on recognition mode (Robust benefits from PSM_AUTO).
      try {
        OcrPageSegmentationMode psm =
            (prefs.prepMode() == OCRHelper.OCR_MODE_ROBUST)
                ? OcrPageSegmentationMode.AUTO
                : OcrPageSegmentationMode.SINGLE_BLOCK;
        helper.setPageSegmentationMode(psm);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }

      // Note: unlike OCRFragment we do not apply user rotation here, the bitmap is already upright
      // after the legacy-metadata rotation step.
      OcrRecognitionPipeline.Attempt best =
          OcrRecognitionPipeline.recognizeBestRotation(
                  helper, bmp, prefs, () -> cancelled.contains(pageId))
              .best();
      persistResult(app, reg, s, best != null ? best.result() : null);
      success = true;
    } catch (OcrRecognitionPipeline.CancelledException c) {
      // Cancelled jobs end without success and without persisting OCR_FAILED.
      Log.w(TAG, c.getMessage() + " for pageId=" + pageId);
    } catch (Throwable t) {
      Log.e(TAG, "Background OCR failed", t);
      // Persist OCR_FAILED so the failure survives process death and stays visible in the UI.
      markPageOcrFailed(app, pageId);
    } finally {
      // Release Tesseract on the same thread that used it.
      try {
        if (helper != null) helper.shutdown();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      running.remove(pageId);
      cancelled.remove(pageId);
      broadcastUpdated(app, pageId, success);
    }
  }

  private static CompletedScan findScan(CompletedScansRegistry reg, String pageId) {
    for (CompletedScan it : reg.listAllOrderedByDateDesc()) {
      if (it != null && pageId.equals(it.id())) return it;
    }
    return null;
  }

  /** Decodes the page image (falling back to the thumbnail) and makes sure the text is upright. */
  private static Bitmap loadUprightBitmap(CompletedScan s) {
    Bitmap bmp = null;
    if (s.filePath() != null) bmp = ImageDecodeUtils.decodeFull(s.filePath());
    if (bmp == null && s.thumbPath() != null) bmp = ImageDecodeUtils.decodeFull(s.thumbPath());
    // For rare legacy metadata entries: apply rotation before OCR so text is upright
    if (bmp != null && "metadata".equalsIgnoreCase(s.orientationMode())) {
      try {
        Bitmap rotated = OcrRecognitionPipeline.rotateBitmap(bmp, s.rotationDeg());
        if (rotated != null) bmp = rotated;
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
    return bmp;
  }

  /** Applies language and Best/Fast model settings; must run BEFORE init (mirrors OCRFragment). */
  private static void configureHelper(Context app, OCRHelper helper, String languageOpt) {
    // 1 job = 1 engine instance. No automatic reinitialization per run.
    try {
      helper.setReinitPerRun(false);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }

    // Determine effective language: use provided, else map from system locale
    String effLang = OCRUtils.resolveEffectiveLanguage(languageOpt);
    try {
      if (effLang != null && !effLang.trim().isEmpty()) {
        helper.setLanguage(effLang);
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }

    try {
      boolean useBest = OcrModelManager.isUsingBestModel(app, effLang);
      helper.setUseBestModelSettings(useBest);
      Log.d(TAG, "Best model settings enabled=" + useBest + " for lang=" + effLang);
    } catch (Throwable t) {
      Log.w(TAG, "Failed to detect/set Best model settings", t);
    }
  }

  private static OcrRecognitionPipeline.Options readJobPrefs(Context app) {
    try {
      SharedPreferences sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
      int prepMode =
          resolvePrepMode(
              sp.getInt(PREF_KEY_OCR_MODE, OCRHelper.OCR_MODE_ROBUST),
              de.schliweb.makeacopy.utils.ocr.PaddleOcrPrefs.isToggleVisible(),
              sp.getBoolean(de.schliweb.makeacopy.utils.ocr.PaddleOcrPrefs.KEY, false));
      return new OcrRecognitionPipeline.Options(
          prepMode,
          sp.getBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, false),
          FeatureFlags.isLayoutAnalysisEnabled() && sp.getBoolean(BUNDLE_LAYOUT_ANALYSIS, false));
    } catch (Throwable ignore) {
      return new OcrRecognitionPipeline.Options(OCRHelper.OCR_MODE_ROBUST, false, false);
    }
  }

  /**
   * Maps the persisted recognition mode to the mode used at runtime, matching {@code
   * OCRFragment.getSelectedOcrMode()} but without UI side-effects: preferences are not rewritten
   * here to avoid races with the UI thread.
   */
  static int resolvePrepMode(
      int storedMode, boolean paddleToggleVisible, boolean legacyPaddleEnabled) {
    // Migrate legacy Quick → Robust.
    if (storedMode == OCRHelper.OCR_MODE_QUICK) storedMode = OCRHelper.OCR_MODE_ROBUST;
    // Honor legacy PaddleOCR toggle (pref_ocr_paddle_enabled) when the new recognition-mode picker
    // has not been opened yet.
    if (paddleToggleVisible && legacyPaddleEnabled) return OCRHelper.OCR_MODE_PADDLE;
    // Guard: if PADDLE is persisted but no longer applicable on this device/build, fall back to
    // Robust at runtime (preference itself is preserved).
    if (storedMode == OCRHelper.OCR_MODE_PADDLE && !paddleToggleVisible) {
      return OCRHelper.OCR_MODE_ROBUST;
    }
    return storedMode;
  }

  /** Writes text.txt / words.json and points the registry entry at the new OCR artifact. */
  private static void persistResult(
      Context app, CompletedScansRegistry reg, CompletedScan s, OCRHelper.OcrResultWords bestResult)
      throws java.io.IOException {
    String text = (bestResult != null && bestResult.text != null) ? bestResult.text : "";
    List<RecognizedWord> words = (bestResult != null) ? bestResult.words : null;

    File dir = new File(app.getFilesDir(), "scans/" + s.id());
    if (!dir.exists()) {
      //noinspection ResultOfMethodCallIgnored
      dir.mkdirs();
    }

    // Write plain text as fallback
    File txt = new File(dir, "text.txt");
    try (FileOutputStream fos = new FileOutputStream(txt)) {
      fos.write(text.getBytes(StandardCharsets.UTF_8));
      fos.flush();
    }
    // Write words.json
    File wordsFile = new File(dir, "words.json");
    try (FileOutputStream wos = new FileOutputStream(wordsFile)) {
      String json = WordsJson.toWordsJson(words);
      wos.write(json.getBytes(StandardCharsets.UTF_8));
      wos.flush();
    }

    // Update registry to prefer words_json. Preserve multi-page metadata
    // (sourceType/pdfPageIndex) and mark the page as OCR_COMPLETE.
    CompletedScan updated =
        s.withOcr(wordsFile.getAbsolutePath(), "words_json", CompletedScan.STATUS_OCR_COMPLETE);
    try {
      reg.remove(s.id());
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    try {
      reg.insert(updated);
    } catch (Throwable e) {
      Log.w(TAG, "Failed to insert updated OCR entry", e);
    }
  }

  /** Notifies the UI (if alive). */
  private static void broadcastUpdated(Context app, String pageId, boolean success) {
    Intent intent = new Intent(ACTION_OCR_UPDATED);
    intent.putExtra(EXTRA_PAGE_ID, pageId);
    intent.putExtra(EXTRA_SUCCESS, success);
    try {
      intent.setPackage(app.getPackageName()); // keep broadcast within app
      app.sendBroadcast(intent);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * Best-effort registry update that marks a page as {@link CompletedScan#STATUS_OCR_FAILED} while
   * preserving all other persisted fields. No-op when the page no longer exists.
   */
  private static void markPageOcrFailed(Context app, String pageId) {
    try {
      CompletedScansRegistry reg = CompletedScansRegistry.get(app);
      CompletedScan s = findScan(reg, pageId);
      if (s == null) return;
      CompletedScan failed =
          s.withOcr(s.ocrTextPath(), s.ocrFormat(), CompletedScan.STATUS_OCR_FAILED);
      reg.remove(s.id());
      reg.insert(failed);
    } catch (Throwable t) {
      Log.w(TAG, "Failed to persist OCR_FAILED status for pageId=" + pageId, t);
    }
  }
}
