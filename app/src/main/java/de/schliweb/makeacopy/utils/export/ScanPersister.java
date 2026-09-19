/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;
import de.schliweb.makeacopy.data.CompletedScansRegistry;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import de.schliweb.makeacopy.utils.ocr.WordsJson;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Utility responsible for persisting a scanned page to the app's private storage and registry. It
 * writes the full JPEG (page.jpg), creates a thumbnail (thumb.jpg), and optionally persists OCR
 * outputs (text.txt, words.json). Functionality mirrors the previously inlined logic in
 * ExportFragment to reduce its complexity without changing behavior.
 */
@UtilityClass
public final class ScanPersister {
  private static final String TAG = "ScanPersister";

  /**
   * Persist the given in-memory scan to disk and registry.
   *
   * <p>Behavioural notes (kept identical to previous implementation): - JPEG quality: page 90,
   * thumbnail 75 - Thumbnail long edge ~240 px, applies rotation before scaling - Writes text.txt
   * when non-empty text provided - Writes words.json when words provided and prefers it over plain
   * text in registry - Swallows non-critical IO errors, logs registry insert failures
   *
   * @param appContext application context
   * @param inMemory completed scan that contains id, rotation, createdAt, width/height, and
   *     in-memory bitmap
   * @param ocrText optional OCR text (nullable)
   * @param ocrWords optional OCR words (nullable)
   * @return the persisted CompletedScan (with file paths, no in-memory bitmap)
   * @throws Exception for unexpected critical failures
   */
  public static CompletedScan persist(
      Context appContext, CompletedScan inMemory, String ocrText, List<RecognizedWord> ocrWords)
      throws Exception {
    if (appContext == null
        || inMemory == null
        || inMemory.id() == null
        || inMemory.inMemoryBitmap() == null) {
      throw new IllegalArgumentException("Invalid arguments for persist");
    }
    final Bitmap bmp = inMemory.inMemoryBitmap();
    final String id = inMemory.id();

    File dir = new File(appContext.getFilesDir(), "scans/" + id);
    if (!dir.exists()) {
      //noinspection ResultOfMethodCallIgnored
      dir.mkdirs();
    }
    // Bake rotation before writing page and thumb
    Bitmap baked = bmp;
    try {
      int deg = 0;
      try {
        deg = inMemory.rotationDeg();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      deg = ((deg % 360) + 360) % 360;
      if (deg != 0 && bmp != null && !bmp.isRecycled()) {
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (rotated != null) baked = rotated;
      }
    } catch (Throwable ignore) {
      /* keep original */
    }
    File page = new File(dir, "page.jpg");
    try (FileOutputStream fos = new FileOutputStream(page)) {
      baked.compress(Bitmap.CompressFormat.JPEG, 90, fos);
      fos.flush();
    }
    // Create thumbnail from baked image (no additional rotation)
    Bitmap sourceForThumb = baked;
    int w = sourceForThumb.getWidth();
    int h = sourceForThumb.getHeight();
    int longEdge = Math.max(w, h);
    int target = 240;
    float scale = longEdge > target ? (target / (float) longEdge) : 1f;
    int nw = Math.max(1, Math.round(w * scale));
    int nh = Math.max(1, Math.round(h * scale));
    Bitmap thumb = Bitmap.createScaledBitmap(sourceForThumb, nw, nh, true);
    File thumbFile = new File(dir, "thumb.jpg");
    try (FileOutputStream tfos = new FileOutputStream(thumbFile)) {
      thumb.compress(Bitmap.CompressFormat.JPEG, 75, tfos);
      tfos.flush();
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    if (sourceForThumb != bmp && sourceForThumb != null && sourceForThumb != baked) {
      try {
        sourceForThumb.recycle();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
    if (baked != bmp) {
      try {
        baked.recycle();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }

    // Persist OCR artifacts
    String ocrPath = null;
    String ocrFormat = null;
    try {
      if (ocrText != null && !ocrText.isEmpty()) {
        File txt = new File(dir, "text.txt");
        try (FileOutputStream tf = new FileOutputStream(txt)) {
          tf.write(ocrText.getBytes(StandardCharsets.UTF_8));
          tf.flush();
          ocrPath = txt.getAbsolutePath();
          ocrFormat = "plain";
        }
      }
      if (ocrWords != null && !ocrWords.isEmpty()) {
        File wordsFile = new File(dir, "words.json");
        try (FileOutputStream wos = new FileOutputStream(wordsFile)) {
          String json = WordsJson.toWordsJson(ocrWords);
          wos.write(json.getBytes(StandardCharsets.UTF_8));
          wos.flush();
          // Prefer words_json
          ocrPath = wordsFile.getAbsolutePath();
          ocrFormat = "words_json";
        }
      }
    } catch (Throwable ignore) {
      /* leave as last successful */
    }

    CompletedScan persisted =
        new CompletedScan(
            id,
            page.getAbsolutePath(),
            0, // rotation normalized after baking
            ocrPath,
            ocrFormat,
            thumbFile.getAbsolutePath(),
            inMemory.createdAt(),
            inMemory.widthPx(),
            inMemory.heightPx(),
            null,
            2,
            "baked",
            inMemory.sourceType(),
            inMemory.pdfPageIndex(),
            (ocrPath != null) ? CompletedScan.STATUS_OCR_COMPLETE : CompletedScan.STATUS_IMPORTED);
    try {
      CompletedScansRegistry reg = CompletedScansRegistry.get(appContext);
      reg.insert(persisted);
    } catch (Exception e) {
      Log.w(TAG, "Registry insert failed", e);
    }
    return persisted;
  }
}
