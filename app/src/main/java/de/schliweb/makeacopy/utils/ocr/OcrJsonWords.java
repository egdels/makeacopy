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

import android.graphics.RectF;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.ui.ocr.review.store.OcrJsonStore;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Utility to read editable OCR JSON (schema 1) and convert it to RecognizedWord list consumable by
 * PdfCreator.
 */
public final class OcrJsonWords {
  private OcrJsonWords() {}

  /**
   * Parses the given file as schema-1 ocr.json and converts words to RecognizedWord list. Returns
   * null if file not found or parsing fails, or empty list if no words present.
   */
  public static List<RecognizedWord> parseFile(File file) {
    if (file == null || !file.exists()) return null;
    try {
      OcrDoc doc = OcrJsonStore.load(file);
      if (doc == null || doc.words == null) return new ArrayList<>();
      List<RecognizedWord> out = new ArrayList<>(doc.words.size());
      final float MIN_W = 2f;
      final float MIN_H = 2f;
      for (OcrDoc.Word w : doc.words) {
        if (w == null) continue;
        String text = (w.t != null) ? w.t.trim() : "";
        // Decode any numeric HTML entities (e.g., &#39; for apostrophe)
        text = OCRHelper.decodeNumericEntities(text);
        if (text.isEmpty()) continue; // skip empty/whitespace-only words
        float left = w.b != null && w.b.length >= 4 ? w.b[0] : 0f;
        float top = w.b != null && w.b.length >= 4 ? w.b[1] : 0f;
        float width = w.b != null && w.b.length >= 4 ? w.b[2] : 0f;
        float height = w.b != null && w.b.length >= 4 ? w.b[3] : 0f;
        // tiny-box clamp rule: skip boxes that are too small to render reliably
        if (width < MIN_W || height < MIN_H) continue;
        float right = left + width;
        float bottom = top + height;
        RectF box = new RectF(left, top, right, bottom);
        float conf = w.c; // already 0..1 per schema; PdfCreator logic is robust
        RecognizedWord rw = new RecognizedWord(text, box, conf);
        rw.setBlockId(w.k);
        rw.setLineId(w.l);
        rw.setLang(w.lang);
        out.add(rw);
      }
      // Sort by layout structure if available, otherwise by position (Y→X)
      final boolean hasLayoutInfo = out.stream().anyMatch(rw -> rw.getBlockId() > 0);
      Collections.sort(
          out,
          new Comparator<RecognizedWord>() {
            @Override
            public int compare(RecognizedWord a, RecognizedWord b) {
              if (hasLayoutInfo) {
                int cb = Integer.compare(a.getBlockId(), b.getBlockId());
                if (cb != 0) return cb;
                int cl = Integer.compare(a.getLineId(), b.getLineId());
                if (cl != 0) return cl;
              }
              RectF ra = a.getBoundingBox();
              RectF rb = b.getBoundingBox();
              int cy = Float.compare(ra.top, rb.top);
              if (cy != 0) return cy;
              return Float.compare(ra.left, rb.left);
            }
          });
      return out;
    } catch (Throwable t) {
      return null;
    }
  }
}
