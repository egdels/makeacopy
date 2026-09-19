/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import de.schliweb.makeacopy.utils.export.ScanPersister;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Materializes selected pages of a PDF as persisted MakeACopy pages (Session 1 of the multi-page
 * workflow).
 *
 * <p>Memory strategy (eager, sequential): pages are rendered strictly one at a time in ascending
 * PDF page order; each rendered bitmap is immediately persisted via {@link ScanPersister} (page.jpg
 * + thumb.jpg + registry entry) and recycled before the next page is rendered. At no point is more
 * than one full-resolution bitmap held in memory, so peak RAM matches today's single-page PDF
 * import regardless of the number of pages.
 *
 * <p>Cancel policy: pages that were already fully persisted when the user cancels are kept and
 * delivered to the caller (consistent state: files + registry entry exist); the currently rendering
 * page is not persisted and no further pages are started.
 *
 * <p>Error policy: a failure on a single page is counted and skipped; the import continues with the
 * next page. Document-level failures (cannot open / SecurityException / IO error) abort the import
 * and deliver the pages persisted so far.
 */
final class PdfMultiPageImporter {

  private static final String TAG = "PdfMultiPageImporter";

  /** Rough per-page storage estimate (page.jpg + thumb.jpg), intentionally conservative. */
  private static final long ESTIMATED_BYTES_PER_PAGE = 2L * 1024 * 1024;

  /** Minimum free space headroom that must remain after the import. */
  private static final long STORAGE_HEADROOM_BYTES = 50L * 1024 * 1024;

  /** Result callback; {@link #onComplete} is invoked exactly once on the UI thread. */
  interface Listener {
    /**
     * Delivers the outcome of the import.
     *
     * @param imported successfully persisted pages in ascending PDF page order (possibly empty)
     * @param failedCount number of pages that failed to render/persist
     * @param cancelled true when the user cancelled the import
     */
    void onComplete(@NonNull List<CompletedScan> imported, int failedCount, boolean cancelled);
  }

  private PdfMultiPageImporter() {}

  /**
   * Starts the sequential import of the given PDF pages. Shows a progress dialog with cancel
   * support; all rendering and file I/O happens on a background thread. User feedback (storage
   * errors, summary toasts) is handled here; the listener only integrates the result.
   */
  static void start(
      @NonNull Fragment fragment,
      @NonNull Uri pdfUri,
      @NonNull List<Integer> pageIndices,
      @NonNull Listener listener) {
    if (!fragment.isAdded() || pageIndices.isEmpty()) return;
    final Context appContext = fragment.requireContext().getApplicationContext();

    // Storage guard: refuse to start when the estimated space is clearly not available.
    if (!hasEnoughStorage(appContext, pageIndices.size())) {
      UIUtils.showToast(
          fragment.requireContext(), R.string.error_insufficient_storage, Toast.LENGTH_LONG);
      return;
    }

    final List<Integer> indices = new ArrayList<>(pageIndices);
    Collections.sort(indices); // strictly ascending PDF page order
    final int total = indices.size();

    // Progress dialog with cancel
    View dialogView =
        LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.dialog_pdf_import_progress, null);
    LinearProgressIndicator progressBar = dialogView.findViewById(R.id.import_progress_bar);
    TextView progressLabel = dialogView.findViewById(R.id.import_progress_label);
    progressBar.setMax(total);
    progressBar.setProgress(0);
    progressLabel.setText(fragment.getString(R.string.pdf_importing_progress, 0, total));

    final AtomicBoolean cancelled = new AtomicBoolean(false);
    AlertDialog dialog =
        new MaterialAlertDialogBuilder(fragment.requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled.set(true))
            .create();
    dialog.setOnShowListener(
        dlg ->
            DialogUtils.improveAlertDialogButtonContrastForNight(
                dialog, fragment.requireContext()));
    dialog.show();

    final AtomicBoolean completed = new AtomicBoolean(false);
    new Thread(
            () -> {
              List<CompletedScan> imported = new ArrayList<>();
              int failed = 0;
              ParcelFileDescriptor pfd = null;
              android.graphics.pdf.PdfRenderer renderer = null;
              try {
                pfd = appContext.getContentResolver().openFileDescriptor(pdfUri, "r");
                if (pfd == null) {
                  runOnUiThread(
                      fragment,
                      () ->
                          UIUtils.showToast(
                              appContext, R.string.error_cannot_open_pdf, Toast.LENGTH_SHORT));
                } else {
                  renderer = new android.graphics.pdf.PdfRenderer(pfd);
                  for (int i = 0; i < total; i++) {
                    if (cancelled.get()) break;
                    int pageIndex = indices.get(i);
                    final int done = i + 1;
                    Bitmap bitmap = null;
                    try {
                      // Render exactly one full-resolution page at a time (PdfRenderer is not
                      // thread-safe; rendering stays strictly sequential).
                      bitmap = PdfImportHelper.renderPdfPage(renderer, pageIndex);
                      CompletedScan inMemory =
                          new CompletedScan(
                              UUID.randomUUID().toString(),
                              null,
                              0, // PDF pages are rendered upright
                              null,
                              null,
                              null,
                              System.currentTimeMillis(),
                              bitmap.getWidth(),
                              bitmap.getHeight(),
                              bitmap,
                              2,
                              "baked",
                              CompletedScan.SOURCE_PDF,
                              pageIndex,
                              CompletedScan.STATUS_IMPORTING);
                      // Persist immediately: page.jpg + thumb.jpg + registry entry. The registry
                      // entry is only written after the files exist (no half-imported entries).
                      CompletedScan persisted =
                          ScanPersister.persist(appContext, inMemory, null, null);
                      imported.add(persisted);
                    } catch (Throwable pageError) {
                      Log.w(TAG, "Failed to import PDF page " + pageIndex, pageError);
                      failed++;
                    } finally {
                      if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle(); // free the page before rendering the next one
                      }
                    }
                    runOnUiThread(
                        fragment,
                        () -> {
                          if (dialog.isShowing()) {
                            progressBar.setProgress(done);
                            progressLabel.setText(
                                fragment.getString(R.string.pdf_importing_progress, done, total));
                          }
                        });
                  }
                }
              } catch (IOException | SecurityException e) {
                Log.e(TAG, "PDF multi-page import error", e);
                runOnUiThread(
                    fragment,
                    () ->
                        UIUtils.showToast(
                            appContext, R.string.error_pdf_import_failed, Toast.LENGTH_SHORT));
              } finally {
                try {
                  if (renderer != null) renderer.close();
                  if (pfd != null) pfd.close();
                } catch (IOException ignored) {
                  // Best-effort; failure is non-critical
                }
              }

              final List<CompletedScan> resultPages = imported;
              final int failedCount = failed;
              runOnUiThread(
                  fragment,
                  () -> {
                    if (!completed.compareAndSet(false, true)) return; // deliver exactly once
                    try {
                      if (dialog.isShowing()) dialog.dismiss();
                    } catch (Throwable ignore) {
                      // Best-effort; failure is non-critical
                    }
                    if (cancelled.get()) {
                      UIUtils.showToast(
                          appContext, R.string.pdf_import_cancelled, Toast.LENGTH_SHORT);
                    } else if (failedCount > 0 && !resultPages.isEmpty()) {
                      UIUtils.showToast(
                          appContext,
                          appContext.getString(
                              R.string.pdf_import_summary_failed, resultPages.size(), failedCount),
                          Toast.LENGTH_LONG);
                    } else if (resultPages.isEmpty()) {
                      UIUtils.showToast(
                          appContext, R.string.pdf_import_nothing_imported, Toast.LENGTH_LONG);
                    }
                    listener.onComplete(resultPages, failedCount, cancelled.get());
                  });
            })
        .start();
  }

  /** Conservative free-space check on the app's private files dir. */
  private static boolean hasEnoughStorage(Context appContext, int pageCount) {
    try {
      StatFs stat = new StatFs(appContext.getFilesDir().getAbsolutePath());
      long available = stat.getAvailableBytes();
      long required = pageCount * ESTIMATED_BYTES_PER_PAGE + STORAGE_HEADROOM_BYTES;
      return available >= required;
    } catch (Throwable t) {
      Log.w(TAG, "Storage check failed; proceeding optimistically", t);
      return true; // do not block the import on a failed estimate
    }
  }

  private static void runOnUiThread(@NonNull Fragment fragment, @NonNull Runnable r) {
    try {
      if (!fragment.isAdded()) return;
      fragment.requireActivity().runOnUiThread(r);
    } catch (Throwable ignore) {
      // Fragment detached mid-import; UI update is skipped, persistence already happened.
    }
  }
}
