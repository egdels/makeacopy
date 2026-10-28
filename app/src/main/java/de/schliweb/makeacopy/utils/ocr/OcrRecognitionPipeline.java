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

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;
import androidx.annotation.Nullable;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Recognition pipeline shared by the foreground OCR ({@code OCRFragment.performOCR()}) and the
 * background jobs ({@code OcrBackgroundJobs}): optional auto-rotation across 0/90/180/270° with
 * deterministic best-result selection and early-exit. PaddleOCR reads the rotated bitmap as it is,
 * without preprocessing.
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
   * @param autoRotate try 0/90/180/270° instead of the current orientation only
   */
  public record Options(boolean autoRotate) {}

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
      Bitmap inputForOcr = rotated;
      OCRHelper.OcrResultWords r = helper.runOcrWithWords(inputForOcr);

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
}
