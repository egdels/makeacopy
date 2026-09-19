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
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.OCRUtils;
import de.schliweb.makeacopy.utils.ocr.OcrEarlyExitPolicy;
import de.schliweb.makeacopy.utils.ocr.OcrFallbackPolicy;
import de.schliweb.makeacopy.utils.ocr.OcrModelManager;
import de.schliweb.makeacopy.utils.ocr.OcrPageSegmentationMode;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import de.schliweb.makeacopy.utils.ocr.UnevenLightingPolicy;
import de.schliweb.makeacopy.utils.ocr.WordsJson;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * <p>The recognition pipeline is aligned with the foreground pipeline used by {@code
 * OCRFragment.performOCR()}: language resolution, Best/Fast model detection, page-segmentation mode
 * (PSM) tuning, adaptive preprocessing (uneven-lighting → ROBUST/forceBinary), optional
 * auto-rotation across 0/90/180/270° with deterministic best-result selection and early-exit, and
 * optional layout analysis with a full-page fallback. UI- and ViewModel-only steps (toasts,
 * navigation, transform pushes) are intentionally omitted.
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
    EXEC.execute(
        () -> {
          boolean success = false;
          OCRHelper helper = null;
          try {
            CompletedScansRegistry reg = CompletedScansRegistry.get(app);
            CompletedScan s = null;
            for (CompletedScan it : reg.listAllOrderedByDateDesc()) {
              if (it != null && pageId.equals(it.id())) {
                s = it;
                break;
              }
            }
            if (s == null) throw new RuntimeException("Entry not found in registry: " + pageId);
            Bitmap bmp = null;
            if (s.filePath() != null) bmp = ImageDecodeUtils.decodeFull(s.filePath());
            if (bmp == null && s.thumbPath() != null)
              bmp = ImageDecodeUtils.decodeFull(s.thumbPath());
            // For rare legacy metadata entries: apply rotation before OCR so text is upright
            try {
              String mode = s.orientationMode();
              int deg = s.rotationDeg();
              boolean isMetadata = mode != null && "metadata".equalsIgnoreCase(mode);
              if (bmp != null && isMetadata && ((deg % 360) != 0)) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(((deg % 360) + 360) % 360);
                Bitmap rotated =
                    Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != null) bmp = rotated;
              }
            } catch (Throwable ignore) {
              // Best-effort; failure is non-critical
            }
            if (bmp == null) throw new RuntimeException("No bitmap available for OCR");

            if (cancelled.contains(pageId)) {
              Log.w(TAG, "Cancelled before OCR init for pageId=" + pageId);
              return;
            }

            helper = ocrHelperSupplier.get();
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

            // Detect Best vs Fast model and configure helper BEFORE init (mirrors OCRFragment).
            try {
              boolean useBest = OcrModelManager.isUsingBestModel(app, effLang);
              helper.setUseBestModelSettings(useBest);
              Log.d(TAG, "Best model settings enabled=" + useBest + " for lang=" + effLang);
            } catch (Throwable t) {
              Log.w(TAG, "Failed to detect/set Best model settings", t);
            }

            if (!helper.initTesseract()) throw new RuntimeException("Tesseract init failed");

            // Read user preferences for auto-rotate / layout analysis (mirrors OCRFragment).
            int prepMode;
            boolean allowOcrAutoRotate;
            boolean useLayoutAnalysis;
            try {
              SharedPreferences sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
              int storedMode = sp.getInt(PREF_KEY_OCR_MODE, OCRHelper.OCR_MODE_ROBUST);
              // Migrate legacy Quick → Robust, matching OCRFragment.getSelectedOcrMode().
              if (storedMode == OCRHelper.OCR_MODE_QUICK) storedMode = OCRHelper.OCR_MODE_ROBUST;
              // Honor legacy PaddleOCR toggle (pref_ocr_paddle_enabled) when the new
              // recognition-mode picker has not been opened yet. Mirrors the migration in
              // OCRFragment.getSelectedOcrMode() but without UI side-effects: we do not
              // rewrite preferences here to avoid races with the UI thread.
              if (storedMode != OCRHelper.OCR_MODE_PADDLE
                  && de.schliweb.makeacopy.utils.ocr.PaddleOcrPrefs.isToggleVisible()
                  && sp.getBoolean(de.schliweb.makeacopy.utils.ocr.PaddleOcrPrefs.KEY, false)) {
                storedMode = OCRHelper.OCR_MODE_PADDLE;
              }
              // Guard: if PADDLE is persisted but no longer applicable on this device/build,
              // fall back to Robust at runtime (preference itself is preserved).
              if (storedMode == OCRHelper.OCR_MODE_PADDLE
                  && !de.schliweb.makeacopy.utils.ocr.PaddleOcrPrefs.isToggleVisible()) {
                storedMode = OCRHelper.OCR_MODE_ROBUST;
              }
              prepMode = storedMode;
              allowOcrAutoRotate = sp.getBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, false);
              useLayoutAnalysis =
                  FeatureFlags.isLayoutAnalysisEnabled()
                      && sp.getBoolean(BUNDLE_LAYOUT_ANALYSIS, false);
            } catch (Throwable ignore) {
              prepMode = OCRHelper.OCR_MODE_ROBUST;
              allowOcrAutoRotate = false;
              useLayoutAnalysis = false;
            }
            final boolean layoutAnalysisEnabled = useLayoutAnalysis;

            // Tune Tesseract PSM based on recognition mode (Robust benefits from PSM_AUTO).
            try {
              OcrPageSegmentationMode psm =
                  (prepMode == OCRHelper.OCR_MODE_ROBUST)
                      ? OcrPageSegmentationMode.AUTO
                      : OcrPageSegmentationMode.SINGLE_BLOCK;
              helper.setPageSegmentationMode(psm);
            } catch (Throwable ignore) {
              // Best-effort; failure is non-critical
            }

            // Try OCR rotations only when Auto‑Rotate is enabled. Otherwise, use current
            // orientation only. Note: unlike OCRFragment we do not apply user rotation here, the
            // bitmap is already upright after the legacy-metadata rotation step above.
            int[] extraRots = allowOcrAutoRotate ? new int[] {0, 90, 180, 270} : new int[] {0};
            OCRHelper.OcrResultWords bestResult = null;

            for (int extra : extraRots) {
              if (cancelled.contains(pageId)) {
                Log.w(
                    TAG, "Cancelled before OCR run (extraRot=" + extra + ") for pageId=" + pageId);
                return;
              }

              Bitmap rotated = (extra == 0) ? bmp : rotateBitmap(bmp, extra);

              // Adaptive preprocessing, mirroring OCRFragment:
              //   QUICK + uneven lighting  -> upgrade to ROBUST
              //   ROBUST + uneven lighting -> additionally trigger Sauvola/Retinex (forceBinary)
              int effectiveMode = prepMode;
              boolean unevenLighting = hasUnevenLighting(rotated);
              if (effectiveMode == OCRHelper.OCR_MODE_QUICK && unevenLighting) {
                effectiveMode = OCRHelper.OCR_MODE_ROBUST;
                Log.d(TAG, "Adaptive: QUICK -> ROBUST (uneven lighting, extraRot=" + extra + ")");
              }
              boolean forceBinary = (effectiveMode == OCRHelper.OCR_MODE_ROBUST) && unevenLighting;
              if (forceBinary) {
                Log.d(
                    TAG,
                    "Adaptive: ROBUST forces binary preprocessing (uneven lighting, extraRot="
                        + extra
                        + ")");
              }

              Bitmap inputForOcr;
              if (effectiveMode == OCRHelper.OCR_MODE_ORIGINAL
                  || effectiveMode == OCRHelper.OCR_MODE_PADDLE) {
                // PADDLE: skip preprocessing; the Paddle engine consumes the original bitmap.
                // On Paddle init failure, OCRHelper falls back to Tesseract on the same
                // bitmap (i.e. matches ORIGINAL behavior). See OCRHelper.selectEngine.
                inputForOcr = rotated;
              } else if (effectiveMode == OCRHelper.OCR_MODE_QUICK) {
                inputForOcr = OpenCVUtils.prepareForOCRQuick(rotated);
              } else { // OCR_MODE_ROBUST
                inputForOcr = OpenCVUtils.prepareForOCR(rotated, /*binaryOutput*/ forceBinary);
              }
              try {
                helper.setRecognitionMode(effectiveMode);
                helper.setForceBinaryRobust(forceBinary);
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }
              if (inputForOcr == null) {
                Log.w(TAG, "prepareForOCR returned null (extraRot=" + extra + "), skipping");
                continue;
              }

              OCRHelper.OcrResultWords r;
              if (layoutAnalysisEnabled) {
                OCRHelper.OcrResultWithLayout layoutResult = helper.runOcrWithLayout(inputForOcr);
                List<RecognizedWord> allWords = new ArrayList<>();
                int regionIdx = 1;
                for (OCRHelper.RegionOcrResult regionResult : layoutResult.regionResults) {
                  if (regionResult.ocrResult() != null && regionResult.ocrResult().words != null) {
                    for (RecognizedWord w : regionResult.ocrResult().words) {
                      w.setBlockId(regionIdx);
                    }
                    allWords.addAll(regionResult.ocrResult().words);
                  }
                  regionIdx++;
                }
                r =
                    new OCRHelper.OcrResultWords(
                        layoutResult.text, layoutResult.meanConfidence, allWords);

                int laWords0 = r.words != null ? r.words.size() : 0;
                int laConf0 = r.meanConfidence != null ? r.meanConfidence : 0;
                if (OcrFallbackPolicy.shouldRunFullPageFallback(laWords0, laConf0)) {
                  Log.d(
                      TAG,
                      "Layout-analysis poor (words="
                          + laWords0
                          + ", meanConf="
                          + laConf0
                          + "), running full-page fallback OCR");
                  OCRHelper.OcrResultWords fb = helper.runOcrWithRetry(inputForOcr);
                  if (fb != null) {
                    int fbWords = fb.words != null ? fb.words.size() : 0;
                    int fbConf = fb.meanConfidence != null ? fb.meanConfidence : 0;
                    boolean fbBetter =
                        fbWords > laWords0 || (fbWords >= laWords0 && fbConf > laConf0 + 1);
                    if (fbBetter) r = fb;
                  }
                }
              } else {
                r = helper.runOcrWithRetry(inputForOcr);
              }

              if (cancelled.contains(pageId)) {
                Log.w(TAG, "Cancelled after OCR run (extraRot=" + extra + ")");
                return;
              }

              // Early-exit: if the first attempt is already strong enough, skip other rotations.
              if (extra == 0 && r != null) {
                int mc0 = (r.meanConfidence != null ? r.meanConfidence : 0);
                int wc0 = (r.words != null ? r.words.size() : 0);
                int tl0 = (r.text != null ? r.text.length() : 0);
                if (OcrEarlyExitPolicy.shouldExit(mc0, wc0, tl0)) {
                  bestResult = r;
                  Log.d(
                      TAG,
                      "Early-exit at extraRot=0: meanConf="
                          + mc0
                          + ", words="
                          + wc0
                          + ", textLen="
                          + tl0);
                  break;
                }
              }

              if (r != null && isBetterResult(r, bestResult)) {
                bestResult = r;
              }
            }

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
                new CompletedScan(
                    s.id(),
                    s.filePath(),
                    s.rotationDeg(),
                    wordsFile.getAbsolutePath(),
                    "words_json",
                    s.thumbPath(),
                    s.createdAt(),
                    s.widthPx(),
                    s.heightPx(),
                    s.inMemoryBitmap(),
                    s.schemaVersion(),
                    s.orientationMode(),
                    s.sourceType(),
                    s.pdfPageIndex(),
                    CompletedScan.STATUS_OCR_COMPLETE);
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
            success = true;
          } catch (Throwable t) {
            Log.e(TAG, "Background OCR failed", t);
            // Persist OCR_FAILED so the failure survives process death and stays visible in the
            // UI. Cancelled jobs return early (no exception) and therefore never reach this path.
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
            // Notify UI (if alive)
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
        });
  }

  /**
   * Best-effort registry update that marks a page as {@link CompletedScan#STATUS_OCR_FAILED} while
   * preserving all other persisted fields. No-op when the page no longer exists.
   */
  private static void markPageOcrFailed(Context app, String pageId) {
    try {
      CompletedScansRegistry reg = CompletedScansRegistry.get(app);
      CompletedScan s = null;
      for (CompletedScan it : reg.listAllOrderedByDateDesc()) {
        if (it != null && pageId.equals(it.id())) {
          s = it;
          break;
        }
      }
      if (s == null) return;
      CompletedScan failed =
          new CompletedScan(
              s.id(),
              s.filePath(),
              s.rotationDeg(),
              s.ocrTextPath(),
              s.ocrFormat(),
              s.thumbPath(),
              s.createdAt(),
              s.widthPx(),
              s.heightPx(),
              s.inMemoryBitmap(),
              s.schemaVersion(),
              s.orientationMode(),
              s.sourceType(),
              s.pdfPageIndex(),
              CompletedScan.STATUS_OCR_FAILED);
      reg.remove(s.id());
      reg.insert(failed);
    } catch (Throwable t) {
      Log.w(TAG, "Failed to persist OCR_FAILED status for pageId=" + pageId, t);
    }
  }

  // ------------------------------------------------------------------------------------------
  // Helpers (mirrored from OCRFragment to keep both pipelines behaviorally aligned).
  // ------------------------------------------------------------------------------------------

  /**
   * Deterministic best-result selection identical to {@code OCRFragment.performOCR()}:
   *
   * <ol>
   *   <li>content presence (words/text non-empty) beats empty;
   *   <li>higher mean confidence wins (with a small epsilon);
   *   <li>tiebreaker: more words, then longer text.
   * </ol>
   */
  private static boolean isBetterResult(
      OCRHelper.OcrResultWords candidate, OCRHelper.OcrResultWords current) {
    if (current == null) return true;
    boolean hasWords = candidate.words != null && !candidate.words.isEmpty();
    boolean hasText = candidate.text != null && !candidate.text.trim().isEmpty();
    boolean hasContent = hasWords || hasText;

    boolean curHasWords = current.words != null && !current.words.isEmpty();
    boolean curHasText = current.text != null && !current.text.trim().isEmpty();
    boolean curHasContent = curHasWords || curHasText;

    if (hasContent != curHasContent) return hasContent;

    float mc = candidate.meanConfidence != null ? candidate.meanConfidence : 0f;
    float curMc = current.meanConfidence != null ? current.meanConfidence : 0f;
    if (mc > curMc + 0.01f) return true;
    if (Math.abs(mc - curMc) <= 0.01f) {
      int wc = candidate.words != null ? candidate.words.size() : 0;
      int curWc = current.words != null ? current.words.size() : 0;
      if (wc != curWc) return wc > curWc;
      int len = candidate.text != null ? candidate.text.length() : 0;
      int curLen = current.text != null ? current.text.length() : 0;
      return len > curLen;
    }
    return false;
  }

  /**
   * Rotates a bitmap clockwise by the given number of degrees (0/90/180/270). Returns the source
   * bitmap unchanged for 0°.
   */
  private static Bitmap rotateBitmap(Bitmap src, int degreesCW) {
    if (src == null) return null;
    int d = ((degreesCW % 360) + 360) % 360;
    if (d == 0) return src;
    android.graphics.Matrix m = new android.graphics.Matrix();
    m.postRotate(d);
    try {
      return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    } catch (Throwable t) {
      Log.w(TAG, "rotateBitmap failed", t);
      return src;
    }
  }

  /**
   * Heuristic for the adaptive Quick→Robust / forceBinary switch (mirrors {@code
   * OCRFragment.hasUnevenLighting}). Returns {@code true} when the input bitmap shows strongly
   * uneven illumination. Decision logic delegates to {@link UnevenLightingPolicy}.
   */
  private static boolean hasUnevenLighting(Bitmap b) {
    if (b == null || b.isRecycled()) return false;
    try {
      int w = b.getWidth();
      int h = b.getHeight();
      if (w < 4 || h < 4) return false;
      int target = 192;
      int longSide = Math.max(w, h);
      double scale = longSide > target ? (double) target / (double) longSide : 1.0;
      int dw = Math.max(4, (int) Math.round(w * scale));
      int dh = Math.max(4, (int) Math.round(h * scale));
      Bitmap small = (dw == w && dh == h) ? b : Bitmap.createScaledBitmap(b, dw, dh, true);
      try {
        int n = dw * dh;
        int[] px = new int[n];
        small.getPixels(px, 0, dw, 0, 0, dw, dh);
        return UnevenLightingPolicy.isUneven(px, dw, dh);
      } finally {
        if (small != b && !small.isRecycled()) small.recycle();
      }
    } catch (Throwable t) {
      // On any failure, be conservative and do not trigger the adaptive switch.
      return false;
    }
  }
}
