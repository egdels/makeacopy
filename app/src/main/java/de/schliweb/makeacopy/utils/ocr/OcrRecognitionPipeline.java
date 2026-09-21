/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;
import androidx.annotation.Nullable;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Recognition pipeline shared by the foreground OCR ({@code OCRFragment.performOCR()}) and the
 * background jobs ({@code OcrBackgroundJobs}): adaptive preprocessing (uneven-lighting →
 * ROBUST/forceBinary), optional auto-rotation across 0/90/180/270° with deterministic best-result
 * selection and early-exit, and optional layout analysis with a full-page fallback.
 *
 * <p>UI- and ViewModel-only steps (toasts, navigation, transform pushes) stay with the callers.
 */
public final class OcrRecognitionPipeline {
  private static final String TAG = "OcrRecognitionPipeline";

  private OcrRecognitionPipeline() {}

  /** Thrown when the caller's cancellation flag is set at one of the well-defined check points. */
  public static final class CancelledException extends RuntimeException {
    public CancelledException(String message) {
      super(message);
    }
  }

  /**
   * User preferences that steer a recognition run.
   *
   * @param prepMode one of the {@code OCRHelper.OCR_MODE_*} constants
   * @param autoRotate try 0/90/180/270° instead of the current orientation only
   * @param layoutAnalysis run region-based OCR with a full-page fallback
   */
  public record Options(int prepMode, boolean autoRotate, boolean layoutAnalysis) {}

  /**
   * One OCR attempt. Holds dimensions only (no bitmaps) so callers can derive the coordinate
   * transform between the rotated source and the preprocessed OCR input.
   */
  public record Attempt(
      OCRHelper.OcrResultWords result,
      int extraRotation,
      int srcWidth,
      int srcHeight,
      int inputWidth,
      int inputHeight) {}

  /**
   * Result of {@link #recognizeBestRotation}.
   *
   * @param best the best attempt, or {@code null} if every attempt was skipped
   * @param zero the attempt at the current orientation (extra rotation 0), if it produced a result
   */
  public record Outcome(@Nullable Attempt best, @Nullable Attempt zero) {}

  /**
   * Runs OCR on {@code src} and, when {@link Options#autoRotate()} is set, on its 90° rotations,
   * and picks the best attempt via {@link #isBetterResult}.
   *
   * @param cancelled checked before and after every OCR run
   * @throws CancelledException if {@code cancelled} reports {@code true}
   */
  public static Outcome recognizeBestRotation(
      OCRHelper helper, Bitmap src, Options options, BooleanSupplier cancelled) {
    int[] extraRots = options.autoRotate() ? new int[] {0, 90, 180, 270} : new int[] {0};
    Attempt best = null;
    Attempt zero = null;

    for (int extra : extraRots) {
      throwIfCancelled(cancelled, "before OCR run (extraRot=" + extra + ")");

      Bitmap rotated;
      try {
        rotated = rotateBitmap(src, extra);
      } catch (Throwable t) {
        Log.w(TAG, "rotateBitmap failed (extraRot=" + extra + "), skipping attempt", t);
        continue;
      }
      Bitmap inputForOcr = preprocess(helper, rotated, options.prepMode(), extra);
      if (inputForOcr == null) {
        Log.w(TAG, "prepareForOCR returned null (extraRot=" + extra + "), skipping attempt");
        continue;
      }

      OCRHelper.OcrResultWords r =
          options.layoutAnalysis()
              ? runOcrWithLayoutAndFallback(helper, inputForOcr)
              : helper.runOcrWithRetry(inputForOcr);

      throwIfCancelled(cancelled, "after OCR run (extraRot=" + extra + ")");
      if (r == null) continue;

      Attempt attempt =
          new Attempt(
              r,
              extra,
              rotated.getWidth(),
              rotated.getHeight(),
              inputForOcr.getWidth(),
              inputForOcr.getHeight());

      // Early-exit: if the first attempt is already strong enough, skip other rotations.
      if (extra == 0) {
        zero = attempt;
        if (isStrongEnoughForEarlyExit(r)) return new Outcome(attempt, zero);
      }

      if (best == null || isBetterResult(r, best.result())) {
        best = attempt;
      }
    }
    return new Outcome(best, zero);
  }

  private static void throwIfCancelled(BooleanSupplier cancelled, String stage) {
    if (cancelled.getAsBoolean()) throw new CancelledException("Cancelled " + stage);
  }

  /**
   * Applies the selected recognition mode (preprocessing) and keeps region-OCR (layout analysis
   * path) in sync with the page-level mode. Adaptive behavior:
   *
   * <ol>
   *   <li>QUICK + uneven lighting -> upgrade to ROBUST (Quick is a wrapper around the robust
   *       grayscale pipeline anyway, but the explicit upgrade also activates path 2).
   *   <li>ROBUST + uneven lighting -> additionally trigger the Sauvola/Retinex binary branch at the
   *       page level AND for every region in the layout-analysis path (via {@code
   *       OCRHelper.setForceBinaryRobust}). Otsu clipping on heavy shadows is the dominant failure
   *       mode for grayscale-only preprocessing on phone photos.
   * </ol>
   *
   * ORIGINAL is always honored as-is.
   *
   * @return the bitmap to feed into OCR, or {@code null} if preprocessing failed
   */
  private static Bitmap preprocess(OCRHelper helper, Bitmap rotated, int prepMode, int extra) {
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
          "Adaptive: ROBUST forces binary preprocessing (uneven lighting, extraRot=" + extra + ")");
    }

    Bitmap inputForOcr;
    if (effectiveMode == OCRHelper.OCR_MODE_ORIGINAL
        || effectiveMode == OCRHelper.OCR_MODE_PADDLE) {
      // PADDLE: skip preprocessing entirely; the Paddle engine consumes the original (rotated)
      // bitmap. If the engine cannot be initialized at runtime, OCRHelper transparently falls back
      // to Tesseract on the same un-preprocessed bitmap, matching ORIGINAL behavior.
      inputForOcr = rotated;
    } else if (effectiveMode == OCRHelper.OCR_MODE_QUICK) {
      inputForOcr = OpenCVUtils.prepareForOCRQuick(rotated);
    } else { // OCR_MODE_ROBUST
      // Default: grayscale output preserves fine details and holes in letters (e.g. 'o'),
      // avoiding over-aggressive binarization artifacts that can cause substitutions like
      // 'Oktober' → 'Okteber'. With uneven lighting we switch to the binary Sauvola/Retinex
      // branch which handles shadows much better.
      inputForOcr = OpenCVUtils.prepareForOCR(rotated, /*binaryOutput*/ forceBinary);
    }
    try {
      helper.setRecognitionMode(effectiveMode);
      helper.setForceBinaryRobust(forceBinary);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    return inputForOcr;
  }

  /**
   * Layout-aware OCR. If layout analysis produced too few words or a very low mean confidence, the
   * page was likely mis-segmented (false table detection, sparse-text PSM on the main body, …): run
   * one additional full-page OCR pass and keep the better result. This is a no-op cost on documents
   * where layout analysis works well, since {@link OcrFallbackPolicy} does not fire.
   */
  private static OCRHelper.OcrResultWords runOcrWithLayoutAndFallback(
      OCRHelper helper, Bitmap inputForOcr) {
    OCRHelper.OcrResultWithLayout layoutResult = helper.runOcrWithLayout(inputForOcr);
    // Collect all words from all regions, preserving layout structure
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
    OCRHelper.OcrResultWords r =
        new OCRHelper.OcrResultWords(layoutResult.text, layoutResult.meanConfidence, allWords);

    int laWords = wordCount(r);
    int laConf = meanConfidence(r);
    if (!OcrFallbackPolicy.shouldRunFullPageFallback(laWords, laConf)) return r;

    Log.d(
        TAG,
        "Layout-analysis poor (words="
            + laWords
            + ", meanConf="
            + laConf
            + "), running full-page fallback OCR");
    OCRHelper.OcrResultWords fb = helper.runOcrWithRetry(inputForOcr);
    if (fb == null) return r;
    boolean fbBetter = isFallbackBetter(fb, r);
    Log.d(
        TAG,
        "Fallback result: words="
            + wordCount(fb)
            + ", meanConf="
            + meanConfidence(fb)
            + ", taken="
            + fbBetter);
    return fbBetter ? fb : r;
  }

  /**
   * The full-page fallback wins with more words, or with as many words and a clearly higher mean
   * confidence. Word count is the dominant signal because the problem the fallback solves is "too
   * few words".
   */
  static boolean isFallbackBetter(
      OCRHelper.OcrResultWords fallback, OCRHelper.OcrResultWords layout) {
    int fbWords = wordCount(fallback);
    int laWords = wordCount(layout);
    return fbWords > laWords
        || (fbWords >= laWords && meanConfidence(fallback) > meanConfidence(layout) + 1);
  }

  /** Decision delegated to {@link OcrEarlyExitPolicy}, tuned against real production samples. */
  private static boolean isStrongEnoughForEarlyExit(OCRHelper.OcrResultWords r) {
    int mc = meanConfidence(r);
    int wc = wordCount(r);
    int tl = (r.text != null ? r.text.length() : 0);
    if (!OcrEarlyExitPolicy.shouldExit(mc, wc, tl)) return false;
    Log.d(TAG, "Early-exit at extraRot=0: meanConf=" + mc + ", words=" + wc + ", textLen=" + tl);
    return true;
  }

  /**
   * Deterministic best-result selection:
   *
   * <ol>
   *   <li>content presence (words/text non-empty) beats empty;
   *   <li>higher mean confidence wins (with a small epsilon);
   *   <li>tiebreaker: more words, then longer text.
   * </ol>
   */
  static boolean isBetterResult(
      OCRHelper.OcrResultWords candidate, OCRHelper.OcrResultWords current) {
    if (current == null) return true;
    boolean hasContent = hasContent(candidate);
    if (hasContent != hasContent(current)) return hasContent;

    float mc = meanConfidence(candidate);
    float curMc = meanConfidence(current);
    if (mc > curMc + 0.01f) return true;
    if (Math.abs(mc - curMc) > 0.01f) return false;

    int wc = wordCount(candidate);
    int curWc = wordCount(current);
    if (wc != curWc) return wc > curWc;
    int len = candidate.text != null ? candidate.text.length() : 0;
    int curLen = current.text != null ? current.text.length() : 0;
    return len > curLen;
  }

  /** Returns {@code true} if the result has at least one word or non-blank text. */
  public static boolean hasContent(OCRHelper.OcrResultWords r) {
    return hasContent(r.text, r.words);
  }

  /** Returns {@code true} if there is at least one word or non-blank text. */
  public static boolean hasContent(@Nullable String text, @Nullable List<RecognizedWord> words) {
    return (words != null && !words.isEmpty()) || (text != null && !text.trim().isEmpty());
  }

  private static int wordCount(OCRHelper.OcrResultWords r) {
    return r.words != null ? r.words.size() : 0;
  }

  private static int meanConfidence(OCRHelper.OcrResultWords r) {
    return r.meanConfidence != null ? r.meanConfidence : 0;
  }

  /**
   * Rotates the given bitmap clockwise. Values outside [0, 360) are normalized; for multiples of
   * 360 the source bitmap is returned unchanged.
   */
  public static Bitmap rotateBitmap(Bitmap src, int degreesCW) {
    int deg = ((degreesCW % 360) + 360) % 360;
    if (deg == 0) return src;
    Matrix m = new Matrix();
    m.postRotate(deg);
    return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
  }

  /**
   * Heuristic for the adaptive Quick→Robust / forceBinary switch: returns {@code true} when the
   * input bitmap shows strongly uneven illumination (shadow/lighting gradient typical for phone
   * photos).
   *
   * <p>The actual decision logic lives in {@link UnevenLightingPolicy} so it can be unit-tested
   * without an Android device. This method only handles bitmap downsampling and pixel extraction.
   */
  private static boolean hasUnevenLighting(Bitmap b) {
    if (b == null || b.isRecycled()) return false;
    try {
      int w = b.getWidth();
      int h = b.getHeight();
      if (w < 4 || h < 4) return false;
      // Downsample to keep this cheap regardless of input size.
      int target = 192;
      int longSide = Math.max(w, h);
      double scale = longSide > target ? (double) target / (double) longSide : 1.0;
      int dw = Math.max(4, (int) Math.round(w * scale));
      int dh = Math.max(4, (int) Math.round(h * scale));
      Bitmap small = (dw == w && dh == h) ? b : Bitmap.createScaledBitmap(b, dw, dh, true);
      try {
        int[] px = new int[dw * dh];
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
