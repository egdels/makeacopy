/*
 * Copyright 2026 Christian Kierdorf
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
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.Nullable;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import lombok.Getter;
import lombok.Setter;

/**
 * Overlay view to render OCR word boxes and support editing plus navigation. - Aspect-fit base
 * transform, then applies userScale and userOffset for pinch-zoom and pan. - Hit testing with
 * tolerance. Long-press opens context menu, single tap edits. - Viewport changes (scale/offset) are
 * exposed via listener for syncing sibling layers and UI. The word text itself is drawn by {@link
 * OcrTextLayerView} underneath; this view never paints text, or it would appear twice.
 */
public class OcrOverlayView extends View {
  private OcrDoc doc;
  private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint lowConfPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF tmp = new RectF();

  private float scale = 1f; // effective scale = baseFit * userScale
  private float offsetX = 0f; // effective offset = baseCenter + userOffset
  private float offsetY = 0f;
  @Getter private float userScale = 1f; // external/gesture-controlled zoom factor (clamped)
  @Getter private float userOffsetX = 0f; // pan in view px
  @Getter private float userOffsetY = 0f;

  private static final float MIN_SCALE = 0.5f;
  private static final float MAX_SCALE = 4.0f; // keep in sync with zoom bar mapping

  private int selectedWordId = -1;
  private float lowConfThreshold = 0.60f;

  public interface OnWordTapListener {
    void onWordTapped(OcrDoc.Word w);
  }

  public interface OnWordLongPressListener {
    void onWordLongPressed(OcrDoc.Word w);
  }

  public interface OnViewportChangedListener {
    void onViewportChanged(float scale, float offsetX, float offsetY);
  }

  @Setter private OnWordTapListener onWordTapListener;
  @Setter private OnWordLongPressListener onWordLongPressListener;
  @Setter private OnViewportChangedListener onViewportChangedListener;

  private android.view.GestureDetector gestureDetector;
  private android.view.ScaleGestureDetector scaleDetector;

  private boolean isPanningOrZooming = false;

  public OcrOverlayView(Context ctx) {
    super(ctx);
    init();
  }

  public OcrOverlayView(Context ctx, @Nullable AttributeSet attrs) {
    super(ctx, attrs);
    init();
  }

  public OcrOverlayView(Context ctx, @Nullable AttributeSet attrs, int defStyle) {
    super(ctx, attrs, defStyle);
    init();
  }

  private void init() {
    boxPaint.setStyle(Paint.Style.STROKE);
    boxPaint.setStrokeWidth(dp(1.5f));
    boxPaint.setColor(Color.argb(220, 0, 150, 136));

    lowConfPaint.setStyle(Paint.Style.STROKE);
    lowConfPaint.setStrokeWidth(dp(1.5f));
    lowConfPaint.setColor(Color.argb(220, 244, 67, 54));

    selectedPaint.setStyle(Paint.Style.STROKE);
    selectedPaint.setStrokeWidth(dp(2.5f));
    selectedPaint.setColor(Color.argb(255, 255, 193, 7));

    setWillNotDraw(false);

    gestureDetector =
        new android.view.GestureDetector(
            getContext(),
            new android.view.GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDown(MotionEvent e) {
                // Required to ensure we get subsequent events
                return true;
              }

              @Override
              public boolean onSingleTapUp(MotionEvent e) {
                if (isPanningOrZooming) return true;
                handleTap(e.getX(), e.getY());
                return true;
              }

              @Override
              public void onLongPress(MotionEvent e) {
                if (isPanningOrZooming) return; // don't trigger while navigating
                handleLongPress(e.getX(), e.getY());
              }

              @Override
              public boolean onScroll(
                  MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (doc == null) return false;
                isPanningOrZooming = true;
                userOffsetX -=
                    distanceX; // distanceX is the scroll delta in view px (finger moved right ->
                // distanceX>0)
                userOffsetY -= distanceY;
                clampPan();
                notifyViewportChanged();
                invalidate();
                return true;
              }

              @Override
              public boolean onDoubleTap(MotionEvent e) {
                // Zoom into the tap location (toggle between 1.0 and 1.6)
                float target = (userScale < 1.2f) ? 1.6f : 1.0f;
                zoomTo(target, e.getX(), e.getY());
                return true;
              }
            });

    scaleDetector =
        new android.view.ScaleGestureDetector(
            getContext(),
            new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScale(android.view.ScaleGestureDetector detector) {
                isPanningOrZooming = true;
                float prevScale = userScale;
                float newScale = clamp(prevScale * detector.getScaleFactor(), MIN_SCALE, MAX_SCALE);
                // Adjust pan so the focal point remains under the fingers
                float fx = detector.getFocusX();
                float fy = detector.getFocusY();
                applyZoomAround(fx, fy, prevScale, newScale);
                userScale = newScale;
                notifyViewportChanged();
                invalidate();
                return true;
              }

              @Override
              public void onScaleEnd(android.view.ScaleGestureDetector detector) {
                isPanningOrZooming = false;
              }
            });
  }

  public void setDoc(@Nullable OcrDoc d) {
    this.doc = d;
    requestLayout();
    invalidate();
  }

  public void setSelectedWordId(int id) {
    this.selectedWordId = id;
    invalidate();
  }

  public void setLowConfThreshold(float t) {
    this.lowConfThreshold = t;
    invalidate();
  }

  public void setUserScale(float s) {
    float clamped = clamp(s, MIN_SCALE, MAX_SCALE);
    this.userScale = clamped;
    clampPan();
    notifyViewportChanged();
    requestLayout();
    invalidate();
  }

  public void setUserOffset(float ox, float oy) {
    this.userOffsetX = ox;
    this.userOffsetY = oy;
    clampPan();
    notifyViewportChanged();
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (doc == null || doc.imageSize == null || doc.imageSize.w <= 0 || doc.imageSize.h <= 0)
      return;
    computeTransform();
    if (doc.words == null) return;

    for (OcrDoc.Word w : doc.words) {
      if (w == null) continue;
      toViewRect(w, tmp);
      Paint p = (w.c <= lowConfThreshold) ? lowConfPaint : boxPaint;
      canvas.drawRect(tmp, p);
      if (w.id == selectedWordId) {
        canvas.drawRect(tmp, selectedPaint);
      }
    }
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
    float eff = base * clamp(userScale, MIN_SCALE, MAX_SCALE);
    float contentW = imgW * eff;
    float contentH = imgH * eff;
    float baseOffsetX = getPaddingLeft() + (vw - contentW) * 0.5f;
    float baseOffsetY = getPaddingTop() + (vh - contentH) * 0.5f;
    offsetX = baseOffsetX + userOffsetX;
    offsetY = baseOffsetY + userOffsetY;
    scale = eff;
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

  private boolean hit(OcrDoc.Word w, float vx, float vy) {
    toViewRect(w, tmp);
    float tol = dp(6f);
    RectF t = new RectF(tmp.left - tol, tmp.top - tol, tmp.right + tol, tmp.bottom + tol);
    return t.contains(vx, vy);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (doc == null || doc.words == null) return super.onTouchEvent(event);
    boolean handled = false;
    if (scaleDetector != null) handled |= scaleDetector.onTouchEvent(event);
    if (gestureDetector != null) handled |= gestureDetector.onTouchEvent(event);
    if (event.getActionMasked() == MotionEvent.ACTION_UP
        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      isPanningOrZooming = false;
    }
    return handled || super.onTouchEvent(event);
  }

  private void handleTap(float vx, float vy) {
    OcrDoc.Word w = findWordAt(vx, vy);
    if (w != null) {
      selectedWordId = w.id;
      invalidate();
      if (onWordTapListener != null) onWordTapListener.onWordTapped(w);
    }
  }

  private void handleLongPress(float vx, float vy) {
    OcrDoc.Word w = findWordAt(vx, vy);
    if (w != null) {
      selectedWordId = w.id;
      invalidate();
      if (onWordLongPressListener != null) onWordLongPressListener.onWordLongPressed(w);
    }
  }

  private OcrDoc.Word findWordAt(float vx, float vy) {
    if (doc == null || doc.words == null) return null;
    for (int i = doc.words.size() - 1; i >= 0; i--) {
      OcrDoc.Word w = doc.words.get(i);
      if (w == null) continue;
      if (hit(w, vx, vy)) return w;
    }
    return null;
  }

  private void clampPan() {
    if (doc == null || getWidth() == 0 || getHeight() == 0) return;
    int imgW = Math.max(1, doc.imageSize.w);
    int imgH = Math.max(1, doc.imageSize.h);
    int vw = getWidth() - getPaddingLeft() - getPaddingRight();
    int vh = getHeight() - getPaddingTop() - getPaddingBottom();
    float sx = vw / (float) imgW;
    float sy = vh / (float) imgH;
    float base = Math.min(sx, sy);
    float eff = base * clamp(userScale, MIN_SCALE, MAX_SCALE);
    float contentW = imgW * eff;
    float contentH = imgH * eff;
    // Allow a small margin beyond edges
    float margin = dp(32);
    float halfDiffX = Math.max(0f, (contentW - vw) * 0.5f);
    float halfDiffY = Math.max(0f, (contentH - vh) * 0.5f);
    // Clamp relative to centered position (userOffset is added to base centering)
    userOffsetX = clamp(userOffsetX, -halfDiffX - margin, halfDiffX + margin);
    userOffsetY = clamp(userOffsetY, -halfDiffY - margin, halfDiffY + margin);
  }

  private void zoomTo(float targetScale, float focusX, float focusY) {
    float prev = userScale;
    float clamped = clamp(targetScale, MIN_SCALE, MAX_SCALE);
    applyZoomAround(focusX, focusY, prev, clamped);
    userScale = clamped;
    clampPan();
    notifyViewportChanged();
    invalidate();
  }

  private void applyZoomAround(float fx, float fy, float prevScale, float newScale) {
    if (doc == null) return;
    // Convert view focus point into content space before and after scaling, adjust pan to keep it
    // stable
    // Compute transform for prev/new to get base offsets
    int imgW = Math.max(1, doc.imageSize.w);
    int imgH = Math.max(1, doc.imageSize.h);
    int vw = getWidth() - getPaddingLeft() - getPaddingRight();
    int vh = getHeight() - getPaddingTop() - getPaddingBottom();
    float base = Math.min(vw / (float) imgW, vh / (float) imgH);
    float prevEff = base * clamp(prevScale, MIN_SCALE, MAX_SCALE);
    float newEff = base * clamp(newScale, MIN_SCALE, MAX_SCALE);
    float prevContentW = imgW * prevEff;
    float prevContentH = imgH * prevEff;
    float newContentW = imgW * newEff;
    float newContentH = imgH * newEff;
    float prevBaseOffsetX = (vw - prevContentW) * 0.5f;
    float prevBaseOffsetY = (vh - prevContentH) * 0.5f;
    float newBaseOffsetX = (vw - newContentW) * 0.5f;
    float newBaseOffsetY = (vh - newContentH) * 0.5f;
    // Compute content coords under focus at previous scale
    float contentX = (fx - (getPaddingLeft() + prevBaseOffsetX + userOffsetX)) / prevEff;
    float contentY = (fy - (getPaddingTop() + prevBaseOffsetY + userOffsetY)) / prevEff;
    // Compute what pan is needed at new scale to keep same content under focus
    float desiredViewX = getPaddingLeft() + newBaseOffsetX + userOffsetX + contentX * newEff;
    float desiredViewY = getPaddingTop() + newBaseOffsetY + userOffsetY + contentY * newEff;
    // Adjust offsets so desiredView matches focus
    userOffsetX += (fx - desiredViewX);
    userOffsetY += (fy - desiredViewY);
    clampPan();
  }

  private void notifyViewportChanged() {
    if (onViewportChangedListener != null) {
      onViewportChangedListener.onViewportChanged(userScale, userOffsetX, userOffsetY);
    }
  }

  private float clamp(float v, float min, float max) {
    return Math.max(min, Math.min(max, v));
  }

  private float dp(float v) {
    return v * getResources().getDisplayMetrics().density;
  }
}
