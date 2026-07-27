/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.ocr.review.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDocReadingOrder;

/**
 * Renders the OCR text positioned exactly at the word bounding boxes. Uses the same transform math
 * as OcrOverlayView so that boxes and text align.
 */
public class OcrTextLayerView extends View {
  private OcrDoc doc;
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF tmp = new RectF();

  private float scale = 1f;
  private float offsetX = 0f;
  private float offsetY = 0f;
  private float userScale = 1f; // controlled by zoom bar or overlay gestures
  private float userOffsetX = 0f;
  private float userOffsetY = 0f;

  private static final float MIN_SCALE = 0.5f;
  private static final float MAX_SCALE = 4.0f;

  public OcrTextLayerView(Context ctx) {
    super(ctx);
    init();
  }

  public OcrTextLayerView(Context ctx, @Nullable AttributeSet attrs) {
    super(ctx, attrs);
    init();
  }

  public OcrTextLayerView(Context ctx, @Nullable AttributeSet attrs, int defStyle) {
    super(ctx, attrs, defStyle);
    init();
  }

  private void init() {
    setWillNotDraw(false);
    textPaint.setStyle(Paint.Style.FILL);
    // default; will be adjusted in onDraw based on UI mode
    textPaint.setColor(Color.WHITE);
    textPaint.setTextAlign(Paint.Align.LEFT);
    textPaint.setTextSize(dp(12));
  }

  public void setDoc(@Nullable OcrDoc d) {
    this.doc = d;
    requestLayout();
    invalidate();
  }

  public void setUserScale(float s) {
    this.userScale = clamp(s, MIN_SCALE, MAX_SCALE);
    requestLayout();
    invalidate();
  }

  public void setUserOffset(float ox, float oy) {
    this.userOffsetX = ox;
    this.userOffsetY = oy;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (doc == null || doc.imageSize == null || doc.imageSize.w <= 0 || doc.imageSize.h <= 0)
      return;
    computeTransform();
    if (doc.words == null) return;
    // Update text color based on current UI mode: white in dark mode, black in light mode
    boolean isNight =
        (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    textPaint.setColor(isNight ? Color.WHITE : Color.BLACK);

    for (OcrDoc.Word w : OcrDocReadingOrder.sortedWordsForReading(doc)) {
      if (w == null) continue;
      if (w.t == null || w.t.isEmpty()) continue;
      toViewRect(w, tmp);
      float h = tmp.height();
      if (h <= dp(6f)) continue;
      if (OcrDocReadingOrder.isVerticalCjkWord(w.t, tmp.width(), h)) {
        drawVerticalWord(canvas, w.t, tmp);
        continue;
      }
      float size = Math.max(dp(8f), h * 0.8f);
      textPaint.setTextSize(size);
      boolean rtl = OcrDocReadingOrder.isRtlText(w.t);
      float x = rtl ? tmp.right - dp(2f) : tmp.left + dp(2f);
      float y = tmp.top + h * 0.85f; // rough baseline inside box
      int save = canvas.save();
      canvas.clipRect(tmp);
      if (rtl) {
        canvas.drawTextRun(w.t, 0, w.t.length(), 0, w.t.length(), x, y, true, textPaint);
      } else {
        canvas.drawText(w.t, x, y, textPaint);
      }
      canvas.restoreToCount(save);
    }
  }

  /**
   * Draws a vertical (top-to-bottom) CJK word: characters are stacked inside the box, sized to fit
   * both the column width and the per-character cell height. Without this, the horizontal rendering
   * path would scale the text to the full column height and clip it away entirely.
   */
  private void drawVerticalWord(Canvas canvas, String text, RectF box) {
    int charCount = text.codePointCount(0, text.length());
    if (charCount <= 0) return;
    float cell = box.height() / charCount;
    float size = Math.max(dp(8f), Math.min(box.width() * 0.9f, cell * 0.95f));
    textPaint.setTextSize(size);
    int save = canvas.save();
    canvas.clipRect(box);
    float centerX = box.centerX();
    int index = 0;
    for (int i = 0; i < text.length(); index++) {
      int cp = text.codePointAt(i);
      int next = i + Character.charCount(cp);
      String ch = text.substring(i, next);
      float chWidth = textPaint.measureText(ch);
      float x = centerX - chWidth * 0.5f;
      float y = box.top + cell * index + cell * 0.85f;
      canvas.drawText(ch, x, y, textPaint);
      i = next;
    }
    canvas.restoreToCount(save);
  }

  private void computeTransform() {
    int imgW = doc.imageSize.w;
    int imgH = doc.imageSize.h;
    int vw = getWidth() - getPaddingLeft() - getPaddingRight();
    int vh = getHeight() - getPaddingTop() - getPaddingBottom();
    if (vw <= 0 || vh <= 0 || imgW <= 0 || imgH <= 0) {
      scale = 1f;
      offsetX = offsetY = 0f;
      return;
    }
    float sx = vw / (float) imgW;
    float sy = vh / (float) imgH;
    float base = Math.min(sx, sy);
    scale = base * clamp(userScale, MIN_SCALE, MAX_SCALE);
    float contentW = imgW * scale;
    float contentH = imgH * scale;
    float baseOffsetX = getPaddingLeft() + (vw - contentW) * 0.5f;
    float baseOffsetY = getPaddingTop() + (vh - contentH) * 0.5f;
    offsetX = baseOffsetX + userOffsetX;
    offsetY = baseOffsetY + userOffsetY;
  }

  private void toViewRect(OcrDoc.Word w, RectF out) {
    float x = w.b[0];
    float y = w.b[1];
    float rw = w.b[2];
    float rh = w.b[3];
    out.set(
        offsetX + x * scale, offsetY + y * scale,
        offsetX + (x + rw) * scale, offsetY + (y + rh) * scale);
  }

  private float clamp(float v, float min, float max) {
    return Math.max(min, Math.min(max, v));
  }

  private float dp(float v) {
    return v * getResources().getDisplayMetrics().density;
  }
}
