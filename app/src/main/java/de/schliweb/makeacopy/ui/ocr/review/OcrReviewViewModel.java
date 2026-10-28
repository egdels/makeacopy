/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.ocr.review;

import android.graphics.Bitmap;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDocUtils;
import lombok.Getter;

/**
 * A ViewModel class for managing the state of an OCR review screen.
 *
 * <p>This ViewModel operates on an {@code OcrDoc} object representing the current state of the OCR
 * document being reviewed. It maintains an edit copy for cancel/save semantics and tracks whether
 * the document has been modified (dirty state).
 *
 * <p>Key features: - Edit copy: Review works on a copy; original is preserved for cancel - Dirty
 * tracking: Tracks if document has been modified since review started - No undo/redo: Simplified
 * state management (save/cancel semantics suffice) - No autosave: Changes are only persisted on
 * explicit save
 */
public class OcrReviewViewModel extends ViewModel {

  private final MutableLiveData<OcrDoc> doc = new MutableLiveData<>(new OcrDoc());
  private final MutableLiveData<Boolean> dirty = new MutableLiveData<>(false);

  // The original document state when review started (for cancel/dirty detection)
  private OcrDoc originalDoc;

  // Version counter incremented on each mutation for dirty tracking
  @Getter private long docVersion = 0;
  private long originalVersion = 0;

  // The session page under review when opened from the export screen; null in the scan flow
  @Getter @Nullable private String pageId;
  // That page's upright image (its OCR word space); null: use the crop of the scan flow
  @Getter @Nullable private Bitmap pageBitmap;
  // Whether the in-memory OCR state belongs to that page, so a save updates it as well
  @Getter private boolean pageOwnsOcrState;

  public LiveData<OcrDoc> getDoc() {
    return doc;
  }

  /** Returns whether the document has been modified since review started. */
  public LiveData<Boolean> isDirty() {
    return dirty;
  }

  /**
   * Sets the document for review and stores a copy as the original state. This should be called
   * when entering the review screen.
   *
   * @param d the document to be set. If null, a new instance of {@code OcrDoc} is created.
   */
  public void setDoc(OcrDoc d) {
    if (d == null) d = new OcrDoc();
    // Store deep copy as original for cancel/dirty detection
    originalDoc = OcrDocUtils.deepCopy(d);
    originalVersion = docVersion;
    doc.setValue(d);
    dirty.setValue(false);
  }

  /**
   * Reviews a session page of the export screen: a save writes the page's persisted OCR result.
   *
   * @param id the page id
   * @param bitmap the page's upright image, or {@code null} for the crop of the scan flow
   * @param ownsOcrState whether the in-memory OCR state belongs to the page
   */
  public void setPage(String id, @Nullable Bitmap bitmap, boolean ownsOcrState) {
    pageId = id;
    pageBitmap = bitmap;
    pageOwnsOcrState = ownsOcrState;
  }

  /** Back to reviewing the scan flow's in-memory OCR state. */
  public void clearPage() {
    pageId = null;
    pageBitmap = null;
    pageOwnsOcrState = false;
  }

  /** Returns the current document value. */
  public OcrDoc getDocValue() {
    return doc.getValue();
  }

  /**
   * Marks the document as modified. Should be called after any edit operation. Increments the
   * version counter for dirty tracking.
   */
  public void markModified() {
    docVersion++;
    dirty.setValue(true);
  }

  /**
   * Checks if the document has unsaved changes.
   *
   * @return true if the document has been modified since review started
   */
  public boolean hasUnsavedChanges() {
    Boolean d = dirty.getValue();
    return d != null && d;
  }

  /** Resets the document to the original state (for cancel operation). */
  public void resetToOriginal() {
    if (originalDoc != null) {
      doc.setValue(OcrDocUtils.deepCopy(originalDoc));
      docVersion = originalVersion;
      dirty.setValue(false);
    }
  }

  /**
   * Clears the dirty flag after successful save. Updates the original document to the current
   * state.
   */
  public void markSaved() {
    OcrDoc current = doc.getValue();
    if (current != null) {
      originalDoc = OcrDocUtils.deepCopy(current);
      originalVersion = docVersion;
    }
    dirty.setValue(false);
  }
}
