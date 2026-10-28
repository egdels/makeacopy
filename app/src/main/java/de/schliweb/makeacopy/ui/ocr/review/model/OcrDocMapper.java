/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.ocr.review.model;

import android.graphics.RectF;
import de.schliweb.makeacopy.ui.ocr.OCRViewModel;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.util.List;

/**
 * Maps the current OCR UI state into the editable OCR JSON schema (OcrDoc, schema=1). Boxes are
 * expected to be in pixels of the OCR destination image (scaled bitmap).
 */
public final class OcrDocMapper {
  private OcrDocMapper() {}

  public static OcrDoc fromState(OCRViewModel.OcrUiState state) {
    if (state == null) return new OcrDoc();
    OCRViewModel.OcrTransform tx = state.transform();
    // Use effective words (reviewed if available, otherwise original OCR)
    return fromWords(
        state.getEffectiveWords(), tx != null ? tx.dstW() : 0, tx != null ? tx.dstH() : 0);
  }

  /**
   * Maps persisted OCR words of a page to a Review document.
   *
   * @param words the words, in the pixel coordinates of the page image
   * @param imageW the page image width
   * @param imageH the page image height
   */
  public static OcrDoc fromWords(List<RecognizedWord> words, int imageW, int imageH) {
    OcrDoc doc = new OcrDoc();
    doc.imageSize.w = imageW;
    doc.imageSize.h = imageH;
    if (words == null) return doc;
    int id = 1;
    for (RecognizedWord rw : words) {
      if (rw == null) continue;
      RectF b = rw.getBoundingBox();
      if (b == null) continue;
      OcrDoc.Word w = new OcrDoc.Word();
      w.id = id++;
      w.t = rw.getText() != null ? rw.getText() : "";
      // convert to [x,y,w,h] ints; clamp to non-negative
      int x = Math.max(0, Math.round(b.left));
      int y = Math.max(0, Math.round(b.top));
      int width = Math.max(0, Math.round(b.right - b.left));
      int height = Math.max(0, Math.round(b.bottom - b.top));
      w.b[0] = x;
      w.b[1] = y;
      w.b[2] = width;
      w.b[3] = height;
      float c = rw.getConfidence();
      if (c > 1.0f) c = c / 100f; // normalize tess 0..100 → 0..1
      if (c < 0f) c = 0f;
      if (c > 1f) c = 1f;
      w.c = c;
      w.e = false; // not edited initially
      w.l = rw.getLineId(); // layout line index (0 if no layout)
      w.k = rw.getBlockId(); // layout block/region index (0 if no layout)
      w.lang = rw.getLang(); // preserve language from reviewed words
      doc.words.add(w);
    }
    return doc;
  }
}
