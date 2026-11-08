/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.camera;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.data.DocumentSessionRepository;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.ui.export.ExportPrefsHelper;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Routes a file shared into the already running app (MainActivity is singleTask, so a share arrives
 * via onNewIntent). The file was stored as pending share in {@link CameraViewModel}; the camera
 * screen imports it when its view is created, so every route ends with a fresh camera screen.
 *
 * <ul>
 *   <li>Nothing open: the file is imported directly.
 *   <li>A document with pages is open: the user decides between adding the file as page(s) and
 *       starting a new document. A new document ends the active session without deleting it, like
 *       "Start new" in the resume prompt; its pages stay in the scan library.
 *   <li>Only a page in progress (Crop, OCR, Review), no pages yet: the user confirms that this page
 *       is discarded.
 * </ul>
 *
 * Cancel drops the pending share.
 */
public final class IncomingShareRouter {

  private static final String TAG = "IncomingShareRouter";

  /** What to do with an incoming share. */
  @VisibleForTesting
  enum Route {
    IMPORT,
    ASK_ADD_OR_NEW,
    ASK_DISCARD_PAGE
  }

  private static WeakReference<AlertDialog> openDialog = new WeakReference<>(null);

  private IncomingShareRouter() {}

  @VisibleForTesting
  static Route decide(int openPages, @Nullable Integer destinationId) {
    if (openPages > 0) return Route.ASK_ADD_OR_NEW;
    if (destinationId != null && isPageInProgress(destinationId)) return Route.ASK_DISCARD_PAGE;
    return Route.IMPORT;
  }

  private static boolean isPageInProgress(int destinationId) {
    return destinationId == R.id.navigation_crop
        || destinationId == R.id.navigation_ocr
        || destinationId == R.id.navigation_review;
  }

  /** Called on the main thread after the share was stored as pending share. */
  public static void route(@NonNull AppCompatActivity activity) {
    NavController nav = navController(activity);
    if (nav == null) return; // not set up yet: the camera screen will consume the share
    dismissOpenDialog();

    ExportSessionViewModel sessionVm =
        new ViewModelProvider(activity).get(ExportSessionViewModel.class);
    List<CompletedScan> pages = sessionVm.getPages().getValue();
    int openPages = pages == null ? 0 : pages.size();
    NavDestination current = nav.getCurrentDestination();
    Route route = decide(openPages, current != null ? current.getId() : null);

    switch (route) {
      case IMPORT:
        importIntoNewScan(activity, nav);
        break;
      case ASK_ADD_OR_NEW:
        show(
            activity,
            new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.share_into_open_title)
                .setMessage(activity.getString(R.string.share_into_open_message, openPages))
                .setPositiveButton(
                    R.string.share_add_page, (d, w) -> addToOpenDocument(activity, nav))
                .setNegativeButton(
                    R.string.share_new_document, (d, w) -> startNewDocument(activity, nav))
                .setNeutralButton(android.R.string.cancel, (d, w) -> dropShare(activity)));
        break;
      case ASK_DISCARD_PAGE:
        show(
            activity,
            new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.share_into_open_title)
                .setMessage(R.string.share_discard_page_message)
                .setPositiveButton(
                    R.string.share_open_anyway, (d, w) -> importIntoNewScan(activity, nav))
                .setNegativeButton(android.R.string.cancel, (d, w) -> dropShare(activity)));
        break;
    }
  }

  private static void show(AppCompatActivity activity, MaterialAlertDialogBuilder builder) {
    AlertDialog dialog = builder.setOnCancelListener(d -> dropShare(activity)).create();
    dialog.setOnShowListener(
        d -> DialogUtils.improveAlertDialogButtonContrastForNight(dialog, activity));
    openDialog = new WeakReference<>(dialog);
    dialog.show();
  }

  private static void dismissOpenDialog() {
    AlertDialog previous = openDialog.get();
    if (previous != null && previous.isShowing()) {
      // A newer share replaces the one the dialog was about; keep its pending Uri.
      previous.setOnCancelListener(null);
      previous.dismiss();
    }
    openDialog = new WeakReference<>(null);
  }

  /** Like "Add page" on the export screen: the imported page(s) are appended to the document. */
  private static void addToOpenDocument(AppCompatActivity activity, NavController nav) {
    ExportPrefsHelper.setPendingAddPage(activity);
    resetCaptureState(activity);
    showCamera(nav);
  }

  private static void startNewDocument(AppCompatActivity activity, NavController nav) {
    ExportSessionViewModel sessionVm =
        new ViewModelProvider(activity).get(ExportSessionViewModel.class);
    sessionVm.setInitial(null);
    sessionVm.setDocumentId(null);
    ExportPrefsHelper.clearPendingAddPage(activity);
    final Context app = activity.getApplicationContext();
    new Thread(
            () -> {
              try {
                DocumentSessionRepository.get(app).endActiveSession(false);
              } catch (Throwable t) {
                Log.w(TAG, "endActiveSession failed", t);
              }
            })
        .start();
    resetCaptureState(activity);
    showCamera(nav);
  }

  private static void importIntoNewScan(AppCompatActivity activity, NavController nav) {
    ExportPrefsHelper.clearPendingAddPage(activity);
    resetCaptureState(activity);
    showCamera(nav);
  }

  private static void dropShare(AppCompatActivity activity) {
    new ViewModelProvider(activity).get(CameraViewModel.class).clearPendingShare();
  }

  /** Drops the page in progress so its bitmaps are released and not seeded into the export. */
  private static void resetCaptureState(AppCompatActivity activity) {
    CropViewModel crop = new ViewModelProvider(activity).get(CropViewModel.class);
    crop.setImageCropped(false);
    crop.setImageBitmap(null);
    crop.setOriginalImageBitmap(null);
    CameraViewModel camera = new ViewModelProvider(activity).get(CameraViewModel.class);
    camera.setImagePath(null);
    camera.setImageUri(null);
  }

  /** A fresh camera screen consumes the pending share in onViewCreated. */
  private static void showCamera(NavController nav) {
    try {
      nav.navigate(
          R.id.navigation_camera,
          null,
          new NavOptions.Builder().setPopUpTo(R.id.navigation_camera, true).build());
    } catch (IllegalArgumentException | IllegalStateException e) {
      Log.w(TAG, "Navigation to camera failed", e);
    }
  }

  @Nullable
  private static NavController navController(AppCompatActivity activity) {
    Fragment host =
        activity.getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment_activity_main);
    return host instanceof NavHostFragment ? ((NavHostFragment) host).getNavController() : null;
  }
}
