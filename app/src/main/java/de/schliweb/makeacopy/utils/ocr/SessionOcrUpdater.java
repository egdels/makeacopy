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

import android.content.Context;
import android.util.Log;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.data.CompletedScansRegistry;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import java.util.List;

/**
 * This class provides utility methods to update an export session with the results of an OCR
 * (Optical Character Recognition) operation. The updates primarily involve replacing specific data
 * of a page in the session with processed OCR data.
 *
 * <p>This class is non-instantiable as it serves as a static utility class.
 */
public final class SessionOcrUpdater {
  private static final String TAG = "SessionOcrUpdater";

  private SessionOcrUpdater() {}

  /**
   * Applies the OCR result to the given export session by updating the corresponding page entry
   * with the processed data and notifying the user.
   *
   * @param context the context required for accessing resources and application state, must not be
   *     null
   * @param sessionVM the export session view model containing the session data to be updated, must
   *     not be null
   * @param pageId the unique identifier of the page on which the OCR result should be applied, must
   *     not be null
   */
  public static void applyOcrResultToSession(
      Context context, ExportSessionViewModel sessionVM, String pageId) {
    if (context == null || sessionVM == null || pageId == null) return;
    try {
      Context app = context.getApplicationContext();
      CompletedScansRegistry reg = CompletedScansRegistry.get(app);
      CompletedScan persisted = null;
      for (CompletedScan e : reg.listAllOrderedByDateDesc()) {
        if (e != null && pageId.equals(e.id())) {
          persisted = e;
          break;
        }
      }
      if (persisted == null) return;

      List<CompletedScan> cur = sessionVM.getPages().getValue();
      if (cur == null) return;
      for (int i = 0; i < cur.size(); i++) {
        CompletedScan it = cur.get(i);
        if (it != null && pageId.equals(it.id())) {
          CompletedScan updated =
              new CompletedScan(
                  it.id(),
                  persisted.filePath(),
                  it.rotationDeg(),
                  persisted.ocrTextPath(),
                  persisted.ocrFormat(),
                  (it.thumbPath() != null ? it.thumbPath() : persisted.thumbPath()),
                  it.createdAt(),
                  it.widthPx(),
                  it.heightPx(),
                  it.inMemoryBitmap(),
                  persisted.schemaVersion(),
                  persisted.orientationMode(),
                  persisted.sourceType(),
                  persisted.pdfPageIndex(),
                  persisted.pageStatus());
          sessionVM.updateAt(i, updated);
          break;
        }
      }
      try {
        UIUtils.showToast(
            context,
            context.getString(R.string.ocr_processing_finished),
            android.widget.Toast.LENGTH_SHORT);
      } catch (Throwable ignored) {
        // Best-effort; failure is non-critical
      }
    } catch (Throwable t) {
      Log.w(TAG, "Failed to update session after OCR job", t);
    }
  }
}
