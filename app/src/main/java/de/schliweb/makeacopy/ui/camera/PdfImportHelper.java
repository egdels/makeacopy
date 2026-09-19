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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import java.io.IOException;

/**
 * Helper class that encapsulates PDF import logic extracted from CameraFragment. Handles rendering
 * PDF pages as bitmaps, showing page selection dialogs for multi-page PDFs, and thumbnail
 * generation.
 */
final class PdfImportHelper {

  private static final String TAG = "PdfImportHelper";

  /** Callback interface for delivering the rendered PDF page bitmap back to the caller. */
  interface PdfBitmapCallback {
    void onBitmapReady(Bitmap bitmap);
  }

  /**
   * Callback interface for the multi-page selection path. Delivers the source PDF URI and the
   * selected page indices (ascending PDF order) without rendering any full-resolution bitmaps. The
   * caller is responsible for materializing the pages (see {@link PdfMultiPageImporter}).
   */
  interface PdfPagesCallback {
    void onPagesSelected(@NonNull Uri pdfUri, @NonNull java.util.List<Integer> pageIndices);
  }

  private PdfImportHelper() {}

  /**
   * Handles PDF import - renders PDF page(s) as bitmap and delivers via callback. For single-page
   * PDFs, renders directly. For multi-page PDFs, shows a page selection dialog.
   */
  static void handlePdfImport(
      @NonNull Fragment fragment, @NonNull Uri pdfUri, @NonNull PdfBitmapCallback callback) {
    handlePdfImport(fragment, pdfUri, callback, null);
  }

  /**
   * Handles PDF import with optional multi-page selection support. Single-page PDFs are rendered
   * directly and delivered via {@code callback}. For multi-page PDFs a selection dialog is shown;
   * when {@code pagesCallback} is non-null the dialog supports multi-select (checkboxes, select
   * all, counter) and every confirmed selection — including exactly one page — is delivered via
   * {@code pagesCallback}. The legacy single-page path via {@code callback} only applies to true
   * 1-page PDFs.
   */
  static void handlePdfImport(
      @NonNull Fragment fragment,
      @NonNull Uri pdfUri,
      @NonNull PdfBitmapCallback callback,
      @androidx.annotation.Nullable PdfPagesCallback pagesCallback) {
    new Thread(
            () -> {
              ParcelFileDescriptor pfd = null;
              android.graphics.pdf.PdfRenderer renderer = null;
              try {
                Context ctx = fragment.getContext();
                if (ctx == null || !fragment.isAdded()) return;

                pfd = ctx.getContentResolver().openFileDescriptor(pdfUri, "r");
                if (pfd == null) {
                  runOnUiThread(
                      fragment,
                      () ->
                          UIUtils.showToast(
                              fragment.requireContext(),
                              R.string.error_cannot_open_pdf,
                              Toast.LENGTH_SHORT));
                  return;
                }

                renderer = new android.graphics.pdf.PdfRenderer(pfd);
                int pageCount = renderer.getPageCount();

                if (pageCount == 0) {
                  runOnUiThread(
                      fragment,
                      () ->
                          UIUtils.showToast(
                              fragment.requireContext(),
                              R.string.error_pdf_empty,
                              Toast.LENGTH_SHORT));
                  return;
                }

                if (pageCount == 1) {
                  // Single page: render directly
                  Bitmap bitmap = renderPdfPage(renderer, 0);
                  runOnUiThread(fragment, () -> callback.onBitmapReady(bitmap));
                } else {
                  // Multiple pages: show selection dialog
                  runOnUiThread(
                      fragment,
                      () ->
                          showPageSelectionDialog(
                              fragment, pdfUri, pageCount, callback, pagesCallback));
                }
              } catch (IOException | SecurityException e) {
                Log.e(TAG, "PDF import error", e);
                runOnUiThread(
                    fragment,
                    () ->
                        UIUtils.showToast(
                            fragment.requireContext(),
                            R.string.error_pdf_import_failed,
                            Toast.LENGTH_SHORT));
              } finally {
                try {
                  if (renderer != null) renderer.close();
                  if (pfd != null) pfd.close();
                } catch (IOException ignored) {
                  // Best-effort; failure is non-critical
                }
              }
            })
        .start();
  }

  /** Renders a single PDF page as a high-resolution bitmap suitable for OCR. */
  static Bitmap renderPdfPage(android.graphics.pdf.PdfRenderer renderer, int pageIndex) {
    android.graphics.pdf.PdfRenderer.Page page = renderer.openPage(pageIndex);

    // Target DPI for OCR quality (300 DPI recommended)
    final int TARGET_DPI = 300;
    final float PDF_DPI = 72f; // Standard PDF resolution
    float scale = TARGET_DPI / PDF_DPI;

    int width = (int) (page.getWidth() * scale);
    int height = (int) (page.getHeight() * scale);

    // Memory limit: max 4096x4096 pixels
    final int MAX_DIMENSION = 4096;
    if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
      float downScale = Math.min((float) MAX_DIMENSION / width, (float) MAX_DIMENSION / height);
      width = (int) (width * downScale);
      height = (int) (height * downScale);
      scale *= downScale;
    }

    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(android.graphics.Color.WHITE); // White background for transparent PDFs

    android.graphics.Matrix matrix = new android.graphics.Matrix();
    matrix.setScale(scale, scale);

    page.render(
        bitmap, null, matrix, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
    page.close();

    return bitmap;
  }

  /**
   * Shows a dialog for selecting page(s) from a multi-page PDF with thumbnail previews. When {@code
   * pagesCallback} is null the dialog behaves like the legacy single-select dialog (tap on a page
   * renders it immediately). Otherwise the dialog offers checkbox-based multi-select with "Select
   * all", a live counter on the import button and cancel. Every confirmed selection — including
   * exactly one page — is routed through {@code pagesCallback}; the legacy single-page workflow
   * only applies to PDFs with a single page (no dialog shown).
   */
  private static void showPageSelectionDialog(
      @NonNull Fragment fragment,
      @NonNull Uri pdfUri,
      int pageCount,
      @NonNull PdfBitmapCallback callback,
      @androidx.annotation.Nullable PdfPagesCallback pagesCallback) {
    if (!fragment.isAdded()) return;

    final boolean multiSelect = pagesCallback != null;

    // Inflate custom dialog layout
    View dialogView =
        LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.dialog_pdf_page_selection, null);
    RecyclerView recyclerView = dialogView.findViewById(R.id.recycler_pages);
    View progressBar = dialogView.findViewById(R.id.progress_loading);
    TextView title = dialogView.findViewById(R.id.dialog_title);
    android.widget.CheckBox selectAll = dialogView.findViewById(R.id.checkbox_select_all);

    // Set up RecyclerView with GridLayoutManager (2 columns)
    recyclerView.setLayoutManager(new GridLayoutManager(fragment.requireContext(), 2));

    MaterialAlertDialogBuilder builder =
        new MaterialAlertDialogBuilder(fragment.requireContext())
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null);
    if (multiSelect) {
      // Positive button click is wired after show() so the dialog stays open on invalid state
      builder.setPositiveButton(R.string.pdf_import_zero_pages, null);
    }
    AlertDialog dialog = builder.create();

    // Create adapter with page selection callback
    PdfPageThumbnailAdapter adapter =
        new PdfPageThumbnailAdapter(
            pageCount,
            fragment,
            multiSelect
                ? null
                : selectedPage -> {
                  dialog.dismiss();
                  renderSinglePageAsync(fragment, pdfUri, selectedPage, callback);
                });

    if (multiSelect) {
      title.setText(R.string.pdf_select_pages);
      selectAll.setVisibility(View.VISIBLE);
      selectAll.setOnCheckedChangeListener(
          (btn, checked) -> {
            if (!btn.isPressed()) return; // ignore programmatic updates
            if (checked) adapter.selectAll();
            else adapter.deselectAll();
          });
      adapter.setSelectionChangedListener(
          count -> {
            android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
              positive.setEnabled(count > 0);
              if (count == 0) {
                positive.setText(R.string.pdf_import_zero_pages);
              } else if (count == 1) {
                positive.setText(R.string.pdf_import_one_page);
              } else {
                positive.setText(fragment.getString(R.string.pdf_import_pages, count));
              }
            }
            // Keep the select-all checkbox in sync without re-triggering the listener
            selectAll.setChecked(count == pageCount);
          });
    }

    // Improve button contrast for dark mode and wire the import button (multi-select)
    dialog.setOnShowListener(
        dlg -> {
          DialogUtils.improveAlertDialogButtonContrastForNight(dialog, fragment.requireContext());
          if (multiSelect) {
            android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
              positive.setEnabled(false);
              positive.setOnClickListener(
                  v -> {
                    java.util.List<Integer> selected = adapter.getSelectedIndices();
                    if (selected.isEmpty()) return;
                    dialog.dismiss();
                    // Every confirmed selection from the multi-page dialog goes through the
                    // page-import path — also for exactly one selected page. The legacy
                    // single-page workflow remains reserved for true 1-page PDFs (handled
                    // before this dialog is shown).
                    pagesCallback.onPagesSelected(pdfUri, selected);
                  });
            }
          }
        });

    recyclerView.setAdapter(adapter);

    // Load thumbnails in background
    new Thread(
            () -> {
              ParcelFileDescriptor pfd = null;
              android.graphics.pdf.PdfRenderer renderer = null;
              try {
                Context ctx = fragment.getContext();
                if (ctx == null || !fragment.isAdded()) return;

                pfd = ctx.getContentResolver().openFileDescriptor(pdfUri, "r");
                if (pfd == null) return;

                renderer = new android.graphics.pdf.PdfRenderer(pfd);

                for (int i = 0; i < pageCount; i++) {
                  if (!fragment.isAdded()) break;
                  final int pageIndex = i;
                  Bitmap thumbnail = renderPdfPageThumbnail(renderer, pageIndex);
                  runOnUiThread(fragment, () -> adapter.setThumbnail(pageIndex, thumbnail));
                }

                // Hide progress bar and show RecyclerView
                runOnUiThread(
                    fragment,
                    () -> {
                      if (progressBar != null) progressBar.setVisibility(View.GONE);
                      recyclerView.setVisibility(View.VISIBLE);
                    });
              } catch (IOException e) {
                Log.e(TAG, "PDF thumbnail loading error", e);
              } finally {
                try {
                  if (renderer != null) renderer.close();
                  if (pfd != null) pfd.close();
                } catch (IOException ignored) {
                  // Best-effort; failure is non-critical
                }
              }
            })
        .start();

    dialog.show();
  }

  /** Renders one PDF page in full resolution on a background thread (legacy single-page path). */
  private static void renderSinglePageAsync(
      @NonNull Fragment fragment,
      @NonNull Uri pdfUri,
      int pageIndex,
      @NonNull PdfBitmapCallback callback) {
    new Thread(
            () -> {
              ParcelFileDescriptor pfd = null;
              android.graphics.pdf.PdfRenderer renderer = null;
              try {
                Context ctx = fragment.getContext();
                if (ctx == null || !fragment.isAdded()) return;

                pfd = ctx.getContentResolver().openFileDescriptor(pdfUri, "r");
                if (pfd == null) return;

                renderer = new android.graphics.pdf.PdfRenderer(pfd);
                Bitmap bitmap = renderPdfPage(renderer, pageIndex);

                runOnUiThread(fragment, () -> callback.onBitmapReady(bitmap));
              } catch (IOException e) {
                Log.e(TAG, "PDF page render error", e);
                runOnUiThread(
                    fragment,
                    () ->
                        UIUtils.showToast(
                            fragment.requireContext(),
                            R.string.error_pdf_page_render_failed,
                            Toast.LENGTH_SHORT));
              } finally {
                try {
                  if (renderer != null) renderer.close();
                  if (pfd != null) pfd.close();
                } catch (IOException ignored) {
                  // Best-effort; failure is non-critical
                }
              }
            })
        .start();
  }

  /** Renders a PDF page as a small thumbnail for preview. */
  static Bitmap renderPdfPageThumbnail(android.graphics.pdf.PdfRenderer renderer, int pageIndex) {
    android.graphics.pdf.PdfRenderer.Page page = renderer.openPage(pageIndex);

    // Thumbnail size: max 200px on longest side
    final int THUMBNAIL_SIZE = 200;
    int pageWidth = page.getWidth();
    int pageHeight = page.getHeight();

    float scale = Math.min((float) THUMBNAIL_SIZE / pageWidth, (float) THUMBNAIL_SIZE / pageHeight);
    int width = (int) (pageWidth * scale);
    int height = (int) (pageHeight * scale);

    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(android.graphics.Color.WHITE);

    android.graphics.Matrix matrix = new android.graphics.Matrix();
    matrix.setScale(scale, scale);

    page.render(
        bitmap, null, matrix, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
    page.close();

    return bitmap;
  }

  private static void runOnUiThread(@NonNull Fragment fragment, @NonNull Runnable r) {
    if (!fragment.isAdded()) return;
    fragment.requireActivity().runOnUiThread(r);
  }

  /**
   * Adapter for displaying PDF page thumbnails in a RecyclerView. Runs in one of two modes: legacy
   * single-select (non-null {@code listener}: tap delivers the page immediately) or multi-select
   * (null {@code listener}: tap toggles a checkbox; selection is reported via {@link
   * OnSelectionChangedListener}).
   */
  static class PdfPageThumbnailAdapter extends RecyclerView.Adapter<PdfPageThumbnailAdapter.VH> {
    private final int pageCount;
    private final Bitmap[] thumbnails;
    private final OnPageSelectedListener listener;
    private final Fragment fragment;
    private final boolean multiSelect;
    private final boolean[] selected;
    private OnSelectionChangedListener selectionChangedListener;

    interface OnPageSelectedListener {
      void onPageSelected(int pageIndex);
    }

    interface OnSelectionChangedListener {
      void onSelectionChanged(int selectedCount);
    }

    PdfPageThumbnailAdapter(int pageCount, Fragment fragment, OnPageSelectedListener listener) {
      this.pageCount = pageCount;
      this.thumbnails = new Bitmap[pageCount];
      this.listener = listener;
      this.fragment = fragment;
      this.multiSelect = (listener == null);
      this.selected = new boolean[pageCount];
    }

    void setSelectionChangedListener(OnSelectionChangedListener l) {
      this.selectionChangedListener = l;
    }

    void selectAll() {
      java.util.Arrays.fill(selected, true);
      notifyDataSetChanged();
      notifySelectionChanged();
    }

    void deselectAll() {
      java.util.Arrays.fill(selected, false);
      notifyDataSetChanged();
      notifySelectionChanged();
    }

    /** Returns the selected page indices in ascending PDF page order. */
    java.util.List<Integer> getSelectedIndices() {
      java.util.List<Integer> out = new java.util.ArrayList<>();
      for (int i = 0; i < selected.length; i++) {
        if (selected[i]) out.add(i);
      }
      return out;
    }

    int getSelectedCount() {
      int n = 0;
      for (boolean b : selected) if (b) n++;
      return n;
    }

    private void notifySelectionChanged() {
      if (selectionChangedListener != null) {
        selectionChangedListener.onSelectionChanged(getSelectedCount());
      }
    }

    void setThumbnail(int pageIndex, Bitmap thumbnail) {
      if (pageIndex >= 0 && pageIndex < thumbnails.length) {
        thumbnails[pageIndex] = thumbnail;
        notifyItemChanged(pageIndex);
      }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      View view =
          LayoutInflater.from(parent.getContext())
              .inflate(R.layout.item_pdf_page_thumbnail, parent, false);
      return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
      holder.pageLabel.setText(fragment.getString(R.string.pdf_page_number, position + 1));
      holder.pageNumberBadge.setText(String.valueOf(position + 1));

      if (thumbnails[position] != null) {
        holder.thumbnail.setImageBitmap(thumbnails[position]);
        holder.loadingIndicator.setVisibility(View.GONE);
      } else {
        holder.thumbnail.setImageBitmap(null);
        holder.loadingIndicator.setVisibility(View.VISIBLE);
      }

      if (multiSelect) {
        holder.checkBox.setVisibility(View.VISIBLE);
        holder.checkBox.setChecked(selected[position]);
      } else {
        holder.checkBox.setVisibility(View.GONE);
      }

      holder.itemView.setOnClickListener(
          v -> {
            if (multiSelect) {
              int pos = holder.getBindingAdapterPosition();
              if (pos == RecyclerView.NO_POSITION) return;
              selected[pos] = !selected[pos];
              holder.checkBox.setChecked(selected[pos]);
              notifySelectionChanged();
            } else if (listener != null) {
              listener.onPageSelected(position);
            }
          });
    }

    @Override
    public int getItemCount() {
      return pageCount;
    }

    static class VH extends RecyclerView.ViewHolder {
      final ImageView thumbnail;
      final TextView pageLabel;
      final TextView pageNumberBadge;
      final View loadingIndicator;
      final android.widget.CheckBox checkBox;

      VH(@NonNull View itemView) {
        super(itemView);
        thumbnail = itemView.findViewById(R.id.page_thumbnail);
        pageLabel = itemView.findViewById(R.id.page_label);
        pageNumberBadge = itemView.findViewById(R.id.page_number_badge);
        loadingIndicator = itemView.findViewById(R.id.thumbnail_loading);
        checkBox = itemView.findViewById(R.id.page_checkbox);
      }
    }
  }
}
