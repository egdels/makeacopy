/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import dagger.hilt.android.AndroidEntryPoint;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.data.library.ScansRepository;
import de.schliweb.makeacopy.databinding.FragmentExportBinding;
import de.schliweb.makeacopy.ui.camera.CameraViewModel;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.ui.ocr.OCRViewModel;
import de.schliweb.makeacopy.utils.export.*;
import de.schliweb.makeacopy.utils.export.jpeg.JpegExportOptions;
import de.schliweb.makeacopy.utils.export.jpeg.JpegExporter;
import de.schliweb.makeacopy.utils.image.*;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.infra.FileUtils;
import de.schliweb.makeacopy.utils.infra.SessionIds;
import de.schliweb.makeacopy.utils.ocr.*;
import de.schliweb.makeacopy.utils.ui.A11yUtils;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import de.schliweb.makeacopy.utils.ui.HapticsUtils;
import de.schliweb.makeacopy.utils.ui.TransitionUtils;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import de.schliweb.makeacopy.utils.ui.ViewSizeUtils;
import java.io.File;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;

/**
 * ExportFragment is a UI component that extends Fragment and facilitates exporting scanned or
 * captured documents in various formats such as PDF or TXT. It integrates with ViewModel classes to
 * manage and interact with export logic, OCR text, and camera functionalities.
 *
 * <p>Fields: - TAG: A string tag used for logging. - binding: Represents view binding used for
 * interacting with the fragment's layout. - exportViewModel: Manages the state and logic related to
 * exporting documents. - cropViewModel: Handles the cropping functionality of the document. -
 * ocrViewModel: Manages Optical Character Recognition (OCR) results and data. - cameraViewModel:
 * Manages camera operations and captures. - createDocumentLauncher: ActivityResultLauncher for
 * creating a document. - createTxtDocumentLauncher: ActivityResultLauncher for creating TXT files.
 * - lastExportedDocumentUri: The URI of the last exported document for reference. -
 * lastExportedPdfName: The name of the last exported PDF document.
 *
 * <p>Methods: - onCreateView: Inflates the fragment's layout, initializes ViewModels, sets up event
 * listeners, and manages shared preferences. - checkDocumentReady: Validates if the document is
 * ready for export. - performExport: Executes the export operation based on the specified
 * configurations. - selectFileLocation: Opens the file chooser to select the export file location.
 * - launchTxtFileCreation: Launches the dialog for creating a TXT file. - exportOcrTextToTxt:
 * Exports OCR text data to a specified TXT file. - shareDocument: Facilitates sharing the exported
 * document. - onDestroyView: Handles cleanup tasks when the fragment's view is destroyed. -
 * getOcrTextFromState: Retrieves the OCR text content from the application state. -
 * getOcrWordsFromState: Retrieves a list of recognized OCR words from the application state.
 */
@AndroidEntryPoint
public class ExportFragment extends Fragment {

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Enter/return only: "New"/"Add page" navigate to the camera screen (SurfaceView preview),
    // which must not be animated by view transitions.
    TransitionUtils.applySharedAxisXEnterReturnOnly(this);
  }

  @Inject ScansRepository scansRepository;
  @Inject javax.inject.Provider<OCRHelper> ocrHelperProvider;
  private static final String TAG = "ExportFragment";
  // Main thread handler for safe UI updates
  private final android.os.Handler mainHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());
  // Track last pages count to avoid repetitive announcements
  private int lastPagesCount = -1;
  // When Include OCR Text is selected, delay the library assignment snackbar
  // until the TXT file has been successfully saved.
  private boolean deferAssignUntilTxt = false;
  private FragmentExportBinding binding;
  private ExportViewModel exportViewModel;
  private CropViewModel cropViewModel;
  private OCRViewModel ocrViewModel;
  private CameraViewModel cameraViewModel;
  // Multipage session (v1 increment)
  private de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel exportSessionViewModel;
  private int activeSessionPageIndex = -1;
  private Bitmap lastFreshMultipagePreviewBitmap;
  private String lastFreshMultipagePageId;
  private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();

  // Session 3: serializes all DocumentSession persistence I/O (sync/restore/discard) so writes
  // stay ordered and off the main thread.
  private final ExecutorService documentSessionExecutor = Executors.newSingleThreadExecutor();
  private int previewRenderGeneration = 0;
  // Multipage: guards against stale results when the user quickly selects several pages while
  // page bitmaps are decoded off the main thread.
  private int previewPageLoadGeneration = 0;

  /**
   * Loads the preview bitmap for the given session page off the main thread and delivers the result
   * back on the UI thread. Decoding multi-page bitmaps from disk (plus rotation) is expensive and
   * previously blocked the main thread, causing long delays until the preview appeared. A
   * generation counter ensures only the most recent request wins when the user switches pages
   * quickly.
   *
   * @param page the session page whose preview should be loaded; no-op when null
   * @param onLoaded callback invoked on the main thread with the decoded bitmap (never null)
   */
  private void loadPageIntoPreviewAsync(
      de.schliweb.makeacopy.ui.export.session.CompletedScan page,
      java.util.function.Consumer<Bitmap> onLoaded) {
    if (page == null) return;
    int[] sz =
        ViewSizeUtils.sizeOrDefault(binding != null ? binding.documentPreview : null, 2048, 2048);
    final int reqW = sz[0];
    final int reqH = sz[1];
    final int generation = ++previewPageLoadGeneration;
    // Fast path: show the small thumbnail immediately as a placeholder so the preview reacts
    // instantly to a filmstrip selection; the fully decoded and processed bitmap replaces it
    // as soon as it is ready. Skipped when the page bitmap is already in memory.
    if (page.inMemoryBitmap() == null && page.thumbPath() != null) {
      previewExecutor.execute(
          () -> {
            Bitmap quick = BitmapUtils.loadQuickThumbBitmapForCompletedScan(page, 512, 512);
            if (quick == null) return;
            mainHandler.post(
                () -> {
                  if (!isAdded() || binding == null || generation != previewPageLoadGeneration)
                    return;
                  binding.documentPreview.setImageBitmap(quick);
                  binding.documentPreview.setVisibility(View.VISIBLE);
                });
          });
    }
    previewExecutor.execute(
        () -> {
          Bitmap bmp = BitmapUtils.loadPreviewBitmapForCompletedScan(page, reqW, reqH);
          mainHandler.post(
              () -> {
                if (!isAdded()
                    || binding == null
                    || generation != previewPageLoadGeneration
                    || bmp == null) return;
                onLoaded.accept(bmp);
              });
        });
  }

  /**
   * A BroadcastReceiver to handle updates from OCR processing jobs. This receiver listens for
   * broadcasts containing information about the OCR processing status and updates the session data
   * accordingly.
   *
   * <p>The receiver performs the following tasks: - Extracts the associated page ID and success
   * status from the received Intent. - If the processing was successful, updates the session data
   * using the OCR result. - If the processing failed, displays a user notification indicating the
   * failure.
   *
   * <p>It expects the following extras in the received Intent: - {@link
   * de.schliweb.makeacopy.jobs.OcrBackgroundJobs#EXTRA_PAGE_ID}: A String representing the ID of
   * the page that was processed. This is used to associate the OCR result with the correct session.
   * - {@link de.schliweb.makeacopy.jobs.OcrBackgroundJobs#EXTRA_SUCCESS}: A boolean indicating
   * whether the OCR processing was successful.
   *
   * <p>In case of any exceptions during the session update, a warning message is logged.
   */
  private final android.content.BroadcastReceiver ocrUpdateReceiver =
      new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
          if (intent == null) return;
          String id =
              intent.getStringExtra(de.schliweb.makeacopy.jobs.OcrBackgroundJobs.EXTRA_PAGE_ID);
          boolean success =
              intent.getBooleanExtra(
                  de.schliweb.makeacopy.jobs.OcrBackgroundJobs.EXTRA_SUCCESS, false);
          if (id == null) return;
          boolean batchActive = ocrBatchController != null && ocrBatchController.isRunning();
          if (success) {
            try {
              SessionOcrUpdater.applyOcrResultToSession(
                  requireContext(), exportSessionViewModel, id);
            } catch (Exception e) {
              Log.w(TAG, "Failed to update session after OCR job", e);
            }
          } else if (!batchActive) {
            UIUtils.showToast(
                requireContext(), getString(R.string.ocr_processing_failed), Toast.LENGTH_SHORT);
          }
          // Drive the batch (if any): the controller starts the next page after each completion.
          if (ocrBatchController != null) {
            ocrBatchController.onOcrJobFinished(id, success);
          }
        }
      };

  // Document-wide OCR batch coordination (Session 2). The controller reuses the existing
  // per-page OCR pipeline; completion is driven by the ACTION_OCR_UPDATED broadcast above.
  private de.schliweb.makeacopy.jobs.OcrBatchController ocrBatchController;
  private androidx.appcompat.app.AlertDialog ocrBatchProgressDialog;
  private com.google.android.material.progressindicator.LinearProgressIndicator ocrBatchProgressBar;
  private android.widget.TextView ocrBatchProgressLabel;

  private de.schliweb.makeacopy.ui.export.session.ExportPagesAdapter pagesAdapter;
  private ActivityResultLauncher<String> createDocumentLauncher;
  private ActivityResultLauncher<String> createTxtDocumentLauncher;
  private ActivityResultLauncher<String> createJpegDocumentLauncher;
  private ActivityResultLauncher<String> createZipDocumentLauncher;

  private static class CreateDocumentWithInitialUri extends ActivityResultContracts.CreateDocument {
    public CreateDocumentWithInitialUri(@NonNull String mimeType) {
      super(mimeType);
    }

    @NonNull
    @Override
    public android.content.Intent createIntent(@NonNull Context context, @NonNull String input) {
      android.content.Intent intent = super.createIntent(context, input);
      // Restore last export folder location if available.
      // EXTRA_INITIAL_URI is only a hint — OEM pickers may ignore it.
      String lastExportUri = ExportPrefsHelper.getLastExportUri(context);
      if (lastExportUri != null) {
        try {
          Uri initialUri = Uri.parse(lastExportUri);
          intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        } catch (Exception e) {
          Log.w("ExportFragment", "Ignoring invalid last export URI", e);
        }
      }
      return intent;
    }
  }

  // URI of the last exported document for sharing
  private Uri lastExportedDocumentUri;
  private String lastExportedPdfName;
  // Tracks whether the current export was triggered via Inbox Mode (for auto-new-scan)
  private boolean inboxExportInProgress = false;
  // Library assignment helper: remember last indexed scan id (only when feature flag is on)
  private boolean ocrReceiverRegistered = false;

  /**
   * Posts a runnable to the main thread only if the Fragment is still added and the view binding
   * exists. If called on the main thread, runs immediately; otherwise posts to main.
   */
  private void postToUiSafe(@NonNull Runnable action) {
    if (!isAdded() || binding == null) return;
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      action.run();
    } else {
      mainHandler.post(
          () -> {
            if (!isAdded() || binding == null) return;
            action.run();
          });
    }
  }

  /**
   * Renders the preview image based on user-selected options such as grayscale, black-and-white, or
   * JPEG BW mode. This method fetches user preferences for export options, processes the provided
   * bitmap accordingly, and updates the preview image in the user interface.
   *
   * @param source The source bitmap image to be processed and displayed in the preview.
   */
  private void renderPreview(Bitmap source) {
    if (binding == null || source == null) return;
    Context ctx = getContext();
    if (ctx == null) return;
    Context appContext = ctx.getApplicationContext();
    final int generation = ++previewRenderGeneration;
    previewExecutor.execute(
        () -> {
          Bitmap out = BitmapUtils.processForPreview(source, appContext);
          mainHandler.post(
              () -> {
                if (!isAdded() || binding == null || generation != previewRenderGeneration) return;
                binding.documentPreview.setImageBitmap(out);
                binding.documentPreview.setVisibility(View.VISIBLE);
                updateEditCropOverlayVisibility();
                updatePreviewOcrBadge();
              });
        });
  }

  /**
   * Updates the OCR badge overlay shown on top of the document preview, mirroring the per-page OCR
   * indicator used in the filmstrip ({@link
   * de.schliweb.makeacopy.ui.export.session.ExportPagesAdapter}).
   *
   * <p>Behavior:
   *
   * <ul>
   *   <li>Hidden when the preview is not visible or no active page can be determined.
   *   <li>Shows {@code [OCR]} on a green background when the active page has an existing OCR text
   *       file on disk.
   *   <li>Shows {@code [⚠]} on an orange background when OCR is missing; tapping the badge then
   *       opens the same OCR options as the filmstrip badge via {@link #showOcrBatchOptions(int)}
   *       (inline OCR for single-page sessions, current/selected/all pages otherwise).
   * </ul>
   */
  private void updatePreviewOcrBadge() {
    if (binding == null || binding.previewOcrBadge == null) return;
    android.widget.TextView badge = binding.previewOcrBadge;
    try {
      // Hide while no preview image is shown.
      if (binding.documentPreview == null
          || binding.documentPreview.getVisibility() != View.VISIBLE) {
        badge.setVisibility(View.GONE);
        badge.setOnClickListener(null);
        return;
      }
      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
          (exportSessionViewModel != null) ? exportSessionViewModel.getPages().getValue() : null;
      // Resolve the active page like the edit entry points do (multi-page pages restored from
      // disk have a freshly decoded preview bitmap, so the in-memory identity lookup alone
      // would fail): filmstrip selection first, then bitmap identity, then single-page fallback.
      int idx = activeSessionPageIndex;
      int n = (pages == null) ? 0 : pages.size();
      if (idx < 0 || idx >= n) idx = findActivePageIndex();
      if ((idx < 0 || idx >= n) && n == 1) idx = 0;
      // Single-page hot workflow without session entry: no badge to show.
      if (idx < 0 || pages == null || idx >= pages.size()) {
        badge.setVisibility(View.GONE);
        badge.setOnClickListener(null);
        return;
      }
      de.schliweb.makeacopy.ui.export.session.CompletedScan s = pages.get(idx);
      if (s == null) {
        badge.setVisibility(View.GONE);
        badge.setOnClickListener(null);
        return;
      }
      String ocrPath = s.ocrTextPath();
      boolean hasOcr = false;
      if (ocrPath != null) {
        try {
          File f = new File(ocrPath);
          hasOcr = f.exists() && f.isFile();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
      if (hasOcr) {
        badge.setText("[OCR]");
        badge.setBackgroundColor(0x8032CD32); // semi green
        badge.setOnClickListener(null);
      } else {
        badge.setText("[\u26A0]");
        badge.setBackgroundColor(0x80FFA500); // semi orange
        final int pos = idx;
        badge.setOnClickListener(v -> showOcrBatchOptions(pos));
      }
      badge.setVisibility(View.VISIBLE);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
      badge.setVisibility(View.GONE);
      badge.setOnClickListener(null);
    }
  }

  /**
   * Toggles the visibility of the Re-Edit overlay (FR #72) on the document preview.
   *
   * <p>The overlay is shown when:
   *
   * <ul>
   *   <li>the document preview is currently visible,
   *   <li>the {@link CropViewModel} holds the previously accepted trapezoid corners, and
   *   <li>the original image source (path or URI) for the active page is still reachable via {@link
   *       CameraViewModel}.
   * </ul>
   *
   * In V1 this effectively limits Re-Edit to the freshly captured / imported single-page workflow.
   * Multi-page Re-Edit per page is tracked as a follow-up issue.
   */
  private void updateEditCropOverlayVisibility() {
    if (binding == null || binding.buttonEditCrop == null) return;
    boolean show = false;
    try {
      if (cropViewModel != null
          && cameraViewModel != null
          && binding.documentPreview.getVisibility() == View.VISIBLE) {
        boolean hasCorners = cropViewModel.getLastAcceptedCornersOriginal().getValue() != null;
        String path =
            cameraViewModel.getImagePath() != null
                ? cameraViewModel.getImagePath().getValue()
                : null;
        Uri u =
            cameraViewModel.getImageUri() != null ? cameraViewModel.getImageUri().getValue() : null;
        boolean hasOriginal = (path != null && !path.isEmpty()) || u != null;
        show = hasCorners && hasOriginal && isActivePageReEditable();
        // Session 3: any persisted page (page.jpg on disk) is editable via the persisted-page
        // edit path, regardless of whether the original capture is still reachable.
        if (!show) {
          de.schliweb.makeacopy.ui.export.session.CompletedScan active = getActivePageForEdit();
          show = active != null && active.filePath() != null;
        }
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    binding.buttonEditCrop.setVisibility(show ? View.VISIBLE : View.GONE);
  }

  /**
   * FR #72 V1: Re-Edit is only possible for the page whose original capture is still tracked by
   * {@link CameraViewModel} — that is effectively the most recently captured/imported page (its
   * {@code inMemoryBitmap} is the very last entry in the session pages list). For any other page we
   * have neither the original source path nor the persisted corners, so editing it would re- crop
   * the wrong source. We therefore only enable Re-Edit when the currently previewed bitmap matches
   * the last entry in the session, or when there is only one page (no filmstrip).
   *
   * <p>FR #72 multi-page (sibling helper {@link #findActivePageIndex()}): the index lookup below
   * locates the active page even when it is not the last one, used by the Re-Edit confirm path to
   * update the correct session slot.
   */

  /**
   * FR #72 multi-page: returns the index in {@code ExportSessionViewModel.pages} whose {@code
   * inMemoryBitmap()} matches the currently previewed bitmap. Returns {@code -1} when unknown (no
   * session, no preview, or no match — e.g. single-page hot workflow with no session entry yet, in
   * which case the legacy "page 0" fallback is appropriate).
   */
  private int findActivePageIndex() {
    try {
      if (exportSessionViewModel == null || exportViewModel == null) return -1;
      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
          exportSessionViewModel.getPages().getValue();
      if (pages == null || pages.isEmpty()) return -1;
      Bitmap curPreview = exportViewModel.getDocumentBitmap().getValue();
      if (curPreview == null) return -1;
      for (int i = 0; i < pages.size(); i++) {
        de.schliweb.makeacopy.ui.export.session.CompletedScan s = pages.get(i);
        if (s != null && s.inMemoryBitmap() == curPreview) return i;
      }
      return -1;
    } catch (Throwable ignore) {
      return -1;
    }
  }

  private boolean isActivePageReEditable() {
    // FR #72 V1.3: in the single-page workflow, updateEditCropOverlayVisibility() already requires
    // persisted corners and a reachable original source, so bitmap identity must not decide
    // editability. Preview/rotation/filter paths may legitimately create a new Bitmap instance. In
    // multi-page workflows, keep the strict identity check so selecting another filmstrip page
    // hides
    // the Edit overlay.
    try {
      if (exportViewModel == null || cropViewModel == null) return false;
      Bitmap cur = exportViewModel.getDocumentBitmap().getValue();
      if (cur == null) return false;
      int n = 0;
      if (exportSessionViewModel != null) {
        List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
            exportSessionViewModel.getPages().getValue();
        n = (pages == null) ? 0 : pages.size();
      }
      if (n <= 1) return true;
      if (activeSessionPageIndex < 0 || activeSessionPageIndex >= n) return false;
      if (lastFreshMultipagePageId == null) return false;
      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
          exportSessionViewModel.getPages().getValue();
      if (pages == null || activeSessionPageIndex >= pages.size()) return false;
      de.schliweb.makeacopy.ui.export.session.CompletedScan activePage =
          pages.get(activeSessionPageIndex);
      if (activePage == null || !lastFreshMultipagePageId.equals(activePage.id())) return false;
      Bitmap fresh = cropViewModel.getLastFreshPageBitmap();
      if (fresh != null) return cur == fresh;
      return false;
    } catch (Throwable ignore) {
      return false;
    }
  }

  private void renderPreviewFromCurrent() {
    if (exportViewModel == null) return;
    Bitmap cur = exportViewModel.getDocumentBitmap().getValue();
    if (cur != null) renderPreview(cur);
  }

  /**
   * Creates and initializes the view hierarchy associated with this fragment. This method handles
   * view inflation, view model setup, event listeners, and initializes shared preferences for
   * maintaining user selections.
   *
   * @param inflater The LayoutInflater object that can be used to inflate any views in the
   *     fragment.
   * @param container If non-null, this is the parent view that the fragment's UI should be attached
   *     to. The fragment should not add the view itself, but this can be used to generate the
   *     LayoutParams of the view.
   * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous
   *     saved state as given here.
   * @return The root view of the fragment's layout that has been created and initialized.
   */
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentExportBinding.inflate(inflater, container, false);
    View root = binding.getRoot();

    Context context = requireContext();
    ExportPrefsHelper.getPrefs(context);

    boolean includeOcr = ExportPrefsHelper.isIncludeOcr(context);
    boolean convertToGrayscale = ExportPrefsHelper.isGrayscaleFromPdfMode(context);
    boolean exportAsJpeg = ExportPrefsHelper.isExportAsJpeg(context);

    // Initialize JPEG mode checkboxes from saved preference (default AUTO)
    // jpeg_mode preference is read later when building export options

    // ViewModel
    exportViewModel = new ViewModelProvider(this).get(ExportViewModel.class);
    exportViewModel.setIncludeOcr(includeOcr);
    exportViewModel.setConvertToGrayscale(convertToGrayscale);
    exportViewModel.setExportFormat(exportAsJpeg ? "JPEG" : "PDF");

    // Inline export format selector (PDF | JPEG): mirrors the persisted preference and
    // updates it immediately so the Save button uses the visible selection.
    binding.exportFormatToggle.check(exportAsJpeg ? R.id.format_jpeg : R.id.format_pdf);
    binding.exportFormatToggle.addOnButtonCheckedListener(
        (group, checkedId, isChecked) -> {
          if (!isChecked) return;
          boolean jpegSelected = checkedId == R.id.format_jpeg;
          Context c = getContext();
          if (c == null || ExportPrefsHelper.isExportAsJpeg(c) == jpegSelected) return;
          ExportPrefsHelper.setExportAsJpeg(c, jpegSelected);
          exportViewModel.setExportFormat(jpegSelected ? "JPEG" : "PDF");
          renderPreviewFromCurrent();
        });

    // Include OCR option is now managed solely via ExportOptionsDialogFragment.
    // Keep the inline checkbox hidden and do not alter its visibility here.

    // Observe exporting state and progress to update progress bar (delegated)
    ExportUiBindings.bindExportProgress(binding, getViewLifecycleOwner(), exportViewModel);

    // Back button: navigate to OCR (if not skipping OCR) or Crop (if skipping OCR)
    View backBtn = root.findViewById(R.id.button_back);
    if (backBtn != null) {
      backBtn.setOnClickListener(
          v -> {
            // Delegate to the same back handling as system Back to ensure identical behavior
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
          });
    }

    cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
    ocrViewModel = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
    cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);

    // Ensure we have a bitmap if arriving here directly (skipping Crop/OCR)
    if (cropViewModel.getImageBitmap().getValue() == null) {
      Context ctxInit = getContext();
      if (ctxInit != null) {
        String path =
            cameraViewModel.getImagePath() != null
                ? cameraViewModel.getImagePath().getValue()
                : null;
        Uri u =
            cameraViewModel.getImageUri() != null ? cameraViewModel.getImageUri().getValue() : null;
        Bitmap bmp = ImageLoader.decode(ctxInit, path, u);
        if (bmp != null) {
          cropViewModel.setImageBitmap(bmp);
        }
      }
    }

    // FR #72 — Edit-Overlay: re-enter CropFragment to adjust the trapezoid for the
    // currently displayed preview page. The overlay is only shown when the original
    // image source for the active page is reachable (single-page hot workflow in V1):
    // a known image path / URI in CameraViewModel and previously persisted corners
    // in CropViewModel.
    if (binding.buttonEditCrop != null) {
      binding.buttonEditCrop.setOnClickListener(
          v -> {
            android.graphics.PointF[] lastCorners =
                cropViewModel.getLastAcceptedCornersOriginal().getValue();
            String path =
                cameraViewModel.getImagePath() != null
                    ? cameraViewModel.getImagePath().getValue()
                    : null;
            Uri origUri =
                cameraViewModel.getImageUri() != null
                    ? cameraViewModel.getImageUri().getValue()
                    : null;
            boolean hasOriginal = (path != null && !path.isEmpty()) || origUri != null;
            if (!hasOriginal || lastCorners == null || !isActivePageReEditable()) {
              // Session 3: fall back to editing the persisted page image (page.jpg) so ANY
              // persisted page of the document can be re-edited, not only the fresh one.
              de.schliweb.makeacopy.ui.export.session.CompletedScan active = getActivePageForEdit();
              if (active != null && active.filePath() != null) {
                startPersistedPageEdit(v, active);
                return;
              }
              UIUtils.showToast(
                  requireContext(),
                  getString(R.string.edit_crop_original_unavailable),
                  Toast.LENGTH_SHORT);
              return;
            }
            // Mark Re-Edit entry so CropFragment can:
            //   - reload the original from disk (the in-memory original was nulled here),
            //   - pre-populate the trapezoid with lastAcceptedCornersOriginal,
            //   - on confirm/back, pop directly back to Export instead of advancing to OCR.
            cropViewModel.setCameFromExport(true);
            cropViewModel.setImageCropped(false);
            // FR #72 multi-page: remember which session page is being re-edited so the
            // confirm path can update the correct page instead of hardcoded index 0.
            cropViewModel.setReEditPageIndex(findActivePageIndex());
            // Session 3: also record the stable page id — the id survives page moves/deletes
            // while the editor is open, unlike the index.
            {
              de.schliweb.makeacopy.ui.export.session.CompletedScan active = getActivePageForEdit();
              cropViewModel.setReEditPageId(active != null ? active.id() : null);
            }
            try {
              // FR #72 — use forward navigate (not popBackStack) so the existing Export
              // entry is preserved on the back stack. On confirm/back the Re-Edit flow can
              // then popBackStack(navigation_export, false) and the very same Export
              // instance (with its observers) receives the updated cropped bitmap and
              // re-renders the preview correctly.
              Navigation.findNavController(v).navigate(R.id.navigation_crop);
            } catch (Throwable t) {
              Log.w(TAG, "Re-Edit navigation failed", t);
              cropViewModel.setCameFromExport(false);
              cropViewModel.setReEditPageIndex(-1);
              cropViewModel.setReEditPageId(null);
            }
          });
      // Visibility is recomputed whenever the preview is rendered; default hidden.
      updateEditCropOverlayVisibility();
    }

    // Multipage session setup (v1 increment) - use Activity scope so it survives navigation
    exportSessionViewModel =
        new ViewModelProvider(requireActivity())
            .get(de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel.class);
    pagesAdapter =
        new de.schliweb.makeacopy.ui.export.session.ExportPagesAdapter(
            new de.schliweb.makeacopy.ui.export.session.ExportPagesAdapter.Callbacks() {
              @Override
              public void onRemoveClicked(int position) {
                if (!isAdded()) return;
                // Confirm removal to avoid accidental fat-finger deletions (analog to back
                // navigation)
                androidx.appcompat.app.AlertDialog dialog =
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                            requireContext())
                        .setTitle(getString(R.string.confirm_remove_page_title))
                        .setMessage(getString(R.string.confirm_remove_page_message))
                        .setPositiveButton(
                            R.string.confirm,
                            (dialogInterface, which) -> {
                              if (exportSessionViewModel == null) return;
                              List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
                                  exportSessionViewModel.getPages().getValue();
                              int n = (cur == null) ? 0 : cur.size();
                              if (position < 0 || position >= n) return;
                              exportSessionViewModel.removeAt(position);
                              // A11y: announce removal
                              View v = getView();
                              if (isAdded() && v != null) {
                                A11yUtils.announce(v, getString(R.string.page_removed));
                              }
                            })
                        .setNegativeButton(
                            R.string.cancel, (dialogInterface, which) -> dialogInterface.dismiss())
                        .create();
                dialog.setOnShowListener(
                    dlg ->
                        DialogUtils.improveAlertDialogButtonContrastForNight(
                            dialog, requireContext()));
                dialog.show();
              }

              @Override
              public void onRemoveConfirmed(int position) {
                if (!isAdded() || exportSessionViewModel == null) return;
                List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
                    exportSessionViewModel.getPages().getValue();
                int n = (cur == null) ? 0 : cur.size();
                if (position < 0 || position >= n) return;
                exportSessionViewModel.removeAt(position);
                View v = getView();
                if (isAdded() && v != null) {
                  A11yUtils.announce(v, getString(R.string.page_removed));
                }
              }

              @Override
              public void onPageClicked(int position) {
                List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
                    exportSessionViewModel.getPages().getValue();
                if (cur == null || position < 0 || position >= cur.size()) return;
                de.schliweb.makeacopy.ui.export.session.CompletedScan sel = cur.get(position);
                if (sel == null) return;
                activeSessionPageIndex = position;
                loadPageIntoPreviewAsync(
                    sel,
                    bmp -> {
                      exportViewModel.setDocumentBitmap(bmp);
                      exportViewModel.setDocumentReady(true);
                    });
              }

              @Override
              public void onReorder(int fromPosition, int toPosition) {
                if (activeSessionPageIndex == fromPosition) {
                  activeSessionPageIndex = toPosition;
                } else if (fromPosition < activeSessionPageIndex
                    && activeSessionPageIndex <= toPosition) {
                  activeSessionPageIndex--;
                } else if (toPosition <= activeSessionPageIndex
                    && activeSessionPageIndex < fromPosition) {
                  activeSessionPageIndex++;
                }
                exportSessionViewModel.move(fromPosition, toPosition);
                // A11y: announce new position (1-based)
                View rootV = getView();
                if (isAdded() && rootV != null) {
                  A11yUtils.announce(
                      rootV, getString(R.string.page_moved_to_position, toPosition + 1));
                }
              }

              @Override
              public void onOcrRequested(int position) {
                showOcrBatchOptions(position);
              }
            });
    androidx.recyclerview.widget.LinearLayoutManager lm =
        new androidx.recyclerview.widget.LinearLayoutManager(
            requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false);
    binding.pagesRecycler.setLayoutManager(lm);
    binding.pagesRecycler.setAdapter(pagesAdapter);

    // Enable drag & drop reordering via ItemTouchHelper (horizontal)
    androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback cb =
        new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.LEFT
                | androidx.recyclerview.widget.ItemTouchHelper.RIGHT,
            0) {
          @Override
          public boolean onMove(
              @NonNull androidx.recyclerview.widget.RecyclerView recyclerView,
              @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder,
              @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder target) {
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            return pagesAdapter.onItemMove(from, to);
          }

          @Override
          public void onSwiped(
              @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder,
              int direction) {
            // no-op (we don't support swipe to dismiss here)
          }

          @Override
          public boolean isLongPressDragEnabled() {
            // Long-press on the item starts drag
            return true;
          }
        };
    new androidx.recyclerview.widget.ItemTouchHelper(cb)
        .attachToRecyclerView(binding.pagesRecycler);
    // Observe pages to update UI
    exportSessionViewModel
        .getPages()
        .observe(
            getViewLifecycleOwner(),
            pages -> {
              pagesAdapter.submitList(pages);
              int n = (pages == null) ? 0 : pages.size();
              // Show filmstrip only when there are actually more than one page
              binding.pagesContainer.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
              // Show "Clear all" only when more than one page exists
              // Trash icon is always visible; only enabled when there are multiple pages
              binding.buttonClearPages.setVisibility(View.VISIBLE);
              binding.buttonClearPages.setEnabled(n > 1);
              // If current preview points to a removed page, auto-select a remaining one
              Bitmap curPreview = exportViewModel.getDocumentBitmap().getValue();
              boolean found = false;
              if (curPreview != null && pages != null) {
                for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : pages) {
                  if (s != null && s.inMemoryBitmap() == curPreview) {
                    found = true;
                    break;
                  }
                }
                if (!found
                    && lastFreshMultipagePageId != null
                    && curPreview == lastFreshMultipagePreviewBitmap) {
                  for (int i = 0; i < pages.size(); i++) {
                    de.schliweb.makeacopy.ui.export.session.CompletedScan s = pages.get(i);
                    if (s != null && lastFreshMultipagePageId.equals(s.id())) {
                      activeSessionPageIndex = i;
                      found = true;
                      break;
                    }
                  }
                }
              }
              if (!found && pages != null && !pages.isEmpty()) {
                de.schliweb.makeacopy.ui.export.session.CompletedScan first = pages.get(0);
                if (first != null) {
                  final int pageCount = n;
                  loadPageIntoPreviewAsync(
                      first,
                      bmp -> {
                        activeSessionPageIndex = 0;
                        exportViewModel.setDocumentBitmap(bmp);
                        if (pageCount <= 1) {
                          try {
                            cropViewModel.setLastFreshPageBitmap(bmp);
                          } catch (Throwable ignore) {
                            // Best-effort; failure is non-critical
                          }
                        }
                        exportViewModel.setDocumentReady(true);
                      });
                }
              }
              // Accessibility: Announce updated page count when it changes
              if (isAdded() && n != lastPagesCount) {
                lastPagesCount = n;
                View rootView = getView();
                if (rootView != null) {
                  String msg = getString(R.string.pages_count_announcement, n);
                  A11yUtils.announce(rootView, msg);
                }
              }
              // Do not toggle Include OCR checkbox visibility here; it remains hidden and
              // controlled by the dialog.
              // Refresh OCR badge overlay on the preview (mirrors filmstrip badge state).
              updatePreviewOcrBadge();
              // Session 3: keep the persistent DocumentSession snapshot in sync with the runtime
              // page list (ordered page ids) on every add/addAll/remove/move/update.
              syncDocumentSessionAsync(pages);
            });
    // Initialize or update pages based on current state and pending add-page flag
    Bitmap initBmp = cropViewModel.getImageBitmap().getValue();
    List<de.schliweb.makeacopy.ui.export.session.CompletedScan> currentPages =
        exportSessionViewModel.getPages().getValue();
    int curSize = (currentPages == null) ? 0 : currentPages.size();

    boolean pendingAdd = ExportPrefsHelper.isPendingAddPage(context);
    if (curSize == 0) {
      // First time opening Export in this session: seed with current cropped bitmap if available
      if (initBmp != null) {
        int userDeg = 0;
        Integer vDeg = cropViewModel.getUserRotationDegrees().getValue();
        if (vDeg != null) userDeg = ((vDeg % 360) + 360) % 360;
        de.schliweb.makeacopy.ui.export.session.CompletedScan initial =
            new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                java.util.UUID.randomUUID().toString(),
                null,
                userDeg,
                null,
                null,
                null,
                System.currentTimeMillis(),
                initBmp.getWidth(),
                initBmp.getHeight(),
                initBmp,
                1,
                "metadata");
        // Align the session id used by Review autosave to this export session id
        if (FeatureFlags.isOcrReviewEnabled() && isAdded()) {
          Context c = getContext();
          if (c != null) {
            SessionIds.setCurrentScanId(c.getApplicationContext(), initial.id());
          }
        }
        activeSessionPageIndex = 0;
        exportSessionViewModel.setInitial(initial);
        // FR #72 V1.3: mark this bitmap as the "fresh" page (re-editable). Older pages
        // selected later via the filmstrip will have a different bitmap identity and will
        // therefore not show the Edit overlay.
        try {
          cropViewModel.setLastFreshPageBitmap(initBmp);
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
        // Persist initial page so it appears in the registry as well
        persistCompletedScanAsync(initial);
      } else {
        exportSessionViewModel.setInitial(null);
        // Session 3: no in-memory state (e.g. fresh process) — try to restore the active
        // persisted DocumentSession so the document composition survives process death.
        restoreDocumentSessionAsync();
      }
    } else if (pendingAdd) {
      // User initiated adding another page and returned here after new capture/crop
      if (initBmp != null) {
        // Avoid adding duplicates if the same bitmap reference is already present
        boolean alreadyPresent = false;
        for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : currentPages) {
          if (s != null && s.inMemoryBitmap() == initBmp) {
            alreadyPresent = true;
            break;
          }
        }
        if (!alreadyPresent) {
          int userDeg = 0;
          Integer v2 = cropViewModel.getUserRotationDegrees().getValue();
          if (v2 != null) userDeg = ((v2 % 360) + 360) % 360;
          de.schliweb.makeacopy.ui.export.session.CompletedScan added =
              new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                  java.util.UUID.randomUUID().toString(),
                  null,
                  userDeg,
                  null,
                  null,
                  null,
                  System.currentTimeMillis(),
                  initBmp.getWidth(),
                  initBmp.getHeight(),
                  initBmp,
                  1,
                  "metadata");
          // Keep SessionIds aligned to the last added page (so Review autosave per-page stays
          // consistent)
          if (FeatureFlags.isOcrReviewEnabled() && isAdded()) {
            Context c2 = getContext();
            if (c2 != null) {
              SessionIds.setCurrentScanId(c2.getApplicationContext(), added.id());
            }
          }
          activeSessionPageIndex = curSize;
          exportSessionViewModel.add(added);
          // FR #72 V1.3: the newly added page is the fresh one (its original capture is still
          // tracked by CameraViewModel). Mark it for the Edit-overlay identity check.
          try {
            cropViewModel.setLastFreshPageBitmap(initBmp);
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
          // Persist this newly added page into the CompletedScans registry (Insert-Hook)
          persistCompletedScanAsync(added);
        }
      }
      // Clear the flag regardless to prevent re-adding on future opens
      ExportPrefsHelper.clearPendingAddPage(context);
    }
    binding.buttonAddPage.setOnClickListener(
        v -> {
          // Directly open the Completed Scans picker (no dialog)
          ArrayList<String> already = new ArrayList<>();
          List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
              exportSessionViewModel.getPages().getValue();
          if (cur != null) {
            for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : cur) {
              if (s != null && s.id() != null) already.add(s.id());
            }
          }
          Bundle args = new Bundle();
          args.putStringArrayList(
              de.schliweb.makeacopy.ui.export.picker.CompletedScansPickerFragment
                  .ARG_ALREADY_SELECTED_IDS,
              already);
          try {
            Navigation.findNavController(requireView())
                .navigate(R.id.navigation_completed_scans_picker, args);
          } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Best-effort; failure is non-critical
          }
        });
    binding.buttonClearPages.setOnClickListener(
        v -> {
          androidx.appcompat.app.AlertDialog dialog =
              new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                  .setTitle(getString(R.string.confirm_clear_pages_title))
                  .setMessage(getString(R.string.confirm_clear_pages_message))
                  .setPositiveButton(
                      R.string.confirm,
                      (dialogInterface, which) -> {
                        // Reset to initial single page
                        Bitmap bmp = exportViewModel.getDocumentBitmap().getValue();
                        de.schliweb.makeacopy.ui.export.session.CompletedScan one = null;
                        if (bmp != null) {
                          one =
                              new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                                  UUID.randomUUID().toString(),
                                  null,
                                  0,
                                  null,
                                  null,
                                  null,
                                  System.currentTimeMillis(),
                                  bmp.getWidth(),
                                  bmp.getHeight(),
                                  bmp,
                                  1,
                                  "metadata");
                        }
                        exportSessionViewModel.setInitial(one);
                      })
                  .setNegativeButton(
                      R.string.cancel, (dialogInterface, which) -> dialogInterface.dismiss())
                  .create();
          dialog.setOnShowListener(
              dlg ->
                  DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext()));
          dialog.show();
        });
    binding.buttonLibraryActions.setOnClickListener(v -> showDocumentActionsMenu());

    createDocumentLauncher =
        registerForActivityResult(
            new CreateDocumentWithInitialUri("application/pdf"),
            uri -> {
              Log.d(TAG, "createDocumentLauncher: Document creation result received");
              if (uri != null) {
                Uri safeUri = FileUtils.ensureExtension(requireContext(), uri, ".pdf");
                Uri folderHint =
                    de.schliweb.makeacopy.utils.infra.DocumentUriUtils.deriveParentDocumentUri(
                        safeUri);
                ExportPrefsHelper.setLastExportUri(
                    requireContext(),
                    folderHint != null ? folderHint.toString() : safeUri.toString());
                String displayName = FileUtils.getDisplayNameFromUri(requireContext(), safeUri);
                exportViewModel.setSelectedFileLocation(safeUri);
                exportViewModel.setSelectedFileLocationName(displayName);
                lastExportedPdfName = displayName;
                performExport();
              } else {
                Log.d(TAG, "createDocumentLauncher: User cancelled document creation");
              }
            });

    createTxtDocumentLauncher =
        registerForActivityResult(
            new CreateDocumentWithInitialUri("text/plain"),
            uri -> {
              if (uri != null) {
                Uri safeUri = FileUtils.ensureExtension(requireContext(), uri, ".txt");
                Uri folderHint =
                    de.schliweb.makeacopy.utils.infra.DocumentUriUtils.deriveParentDocumentUri(
                        safeUri);
                ExportPrefsHelper.setLastExportUri(
                    requireContext(),
                    folderHint != null ? folderHint.toString() : safeUri.toString());
                String displayName = FileUtils.getDisplayNameFromUri(requireContext(), safeUri);
                Log.d(TAG, "createTxtDocumentLauncher: Display name from URI: " + displayName);
                exportOcrTextToTxt(safeUri);
              } else {
                Log.d(TAG, "createTxtDocumentLauncher: User cancelled TXT document creation");
              }
            });

    createJpegDocumentLauncher =
        registerForActivityResult(
            new CreateDocumentWithInitialUri("image/jpeg"),
            uri -> {
              Log.d(TAG, "createJpegDocumentLauncher: JPEG creation result received");
              if (uri != null) {
                Uri safeUri = FileUtils.ensureExtension(requireContext(), uri, ".jpg");
                Uri folderHint =
                    de.schliweb.makeacopy.utils.infra.DocumentUriUtils.deriveParentDocumentUri(
                        safeUri);
                ExportPrefsHelper.setLastExportUri(
                    requireContext(),
                    folderHint != null ? folderHint.toString() : safeUri.toString());
                String displayName = FileUtils.getDisplayNameFromUri(requireContext(), safeUri);
                exportViewModel.setSelectedFileLocation(safeUri);
                exportViewModel.setSelectedFileLocationName(displayName);
                performJpegExport();
              } else {
                Log.d(TAG, "createJpegDocumentLauncher: User cancelled JPEG document creation");
              }
            });
    createZipDocumentLauncher =
        registerForActivityResult(
            new CreateDocumentWithInitialUri("application/zip"),
            uri -> {
              Log.d(TAG, "createZipDocumentLauncher: ZIP creation result received");
              if (uri != null) {
                Uri safeUri = FileUtils.ensureExtension(requireContext(), uri, ".zip");
                Uri folderHint =
                    de.schliweb.makeacopy.utils.infra.DocumentUriUtils.deriveParentDocumentUri(
                        safeUri);
                ExportPrefsHelper.setLastExportUri(
                    requireContext(),
                    folderHint != null ? folderHint.toString() : safeUri.toString());
                String displayName = FileUtils.getDisplayNameFromUri(requireContext(), safeUri);
                exportViewModel.setSelectedFileLocation(safeUri);
                exportViewModel.setSelectedFileLocationName(displayName);
                performJpegZipExport();
              } else {
                Log.d(TAG, "createZipDocumentLauncher: User cancelled ZIP document creation");
              }
            });

    // Listen for results from CompletedScansPickerFragment
    getParentFragmentManager()
        .setFragmentResultListener(
            de.schliweb.makeacopy.ui.export.picker.CompletedScansPickerFragment.RESULT_KEY,
            getViewLifecycleOwner(),
            (requestKey, bundle) -> {
              java.util.ArrayList<String> ids =
                  bundle.getStringArrayList(
                      de.schliweb.makeacopy.ui.export.picker.CompletedScansPickerFragment
                          .RESULT_IDS);
              if (ids == null || ids.isEmpty()) return;
              Context ctx2 = getContext();
              if (ctx2 == null) return;
              // Resolve from registry
              java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> all =
                  de.schliweb.makeacopy.data.CompletedScansRegistry.get(ctx2)
                      .listAllOrderedByDateDesc();
              java.util.Map<String, de.schliweb.makeacopy.ui.export.session.CompletedScan> byId =
                  new java.util.HashMap<>();
              for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : all) {
                if (s != null && s.id() != null) byId.put(s.id(), s);
              }
              java.util.ArrayList<de.schliweb.makeacopy.ui.export.session.CompletedScan> picked =
                  new java.util.ArrayList<>();
              for (String id : ids) {
                de.schliweb.makeacopy.ui.export.session.CompletedScan s = byId.get(id);
                if (s != null) picked.add(s);
              }
              if (!picked.isEmpty()) {
                // Sort by creation timestamp ascending to maintain chronological order when adding
                // multiple pages
                picked.sort(
                    java.util.Comparator.comparingLong(
                        de.schliweb.makeacopy.ui.export.session.CompletedScan::createdAt));
                exportSessionViewModel.addAll(picked);
                UIUtils.showToast(
                    requireContext(),
                    getString(R.string.added_pages_from_registry, picked.size()),
                    Toast.LENGTH_SHORT);
              }
            });

    // Back-Handling
    requireActivity()
        .getOnBackPressedDispatcher()
        .addCallback(
            getViewLifecycleOwner(),
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                // If multipage session is active (>1 pages), ask for confirmation to delete all
                // pages
                List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
                    exportSessionViewModel != null
                        ? exportSessionViewModel.getPages().getValue()
                        : null;
                int n = (pages == null) ? 0 : pages.size();
                if (n > 1) {
                  androidx.appcompat.app.AlertDialog dialog =
                      new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                              requireContext())
                          .setTitle(getString(R.string.confirm_clear_multipage_title))
                          .setMessage(getString(R.string.confirm_clear_multipage_message))
                          .setPositiveButton(
                              R.string.confirm,
                              (dialogInterface, which) -> {
                                // Clear all pages in the session before leaving
                                // Session 3: the user explicitly discards the document — also
                                // drop the persisted DocumentSession (pages themselves follow the
                                // existing registry cleanup policy).
                                discardActiveDocumentSessionAsync();
                                if (exportSessionViewModel != null)
                                  exportSessionViewModel.setInitial(null);
                                // Reset camera/crop state and navigate back to camera
                                cameraViewModel.setImageUri(null);
                                cropViewModel.setImageCropped(false);
                                cropViewModel.setImageBitmap(null);
                                cropViewModel.setOriginalImageBitmap(null);
                                cropViewModel.setImageLoaded(false);
                                // Also clear pending add flag to avoid unintended re-adding on next
                                // open
                                ExportPrefsHelper.clearPendingAddPage(requireContext());
                                View fragmentView = getView();
                                if (fragmentView == null) return;
                                NavOptions navOptions =
                                    new NavOptions.Builder()
                                        .setPopUpTo(R.id.navigation_camera, true)
                                        .build();
                                Navigation.findNavController(fragmentView)
                                    .navigate(R.id.navigation_camera, null, navOptions);
                              })
                          .setNegativeButton(
                              R.string.cancel,
                              (dialogInterface, which) -> {
                                dialogInterface.dismiss(); // stay on Export
                              })
                          .create();
                  dialog.setOnShowListener(
                      dlg ->
                          DialogUtils.improveAlertDialogButtonContrastForNight(
                              dialog, requireContext()));
                  dialog.show();
                  return;
                }
                // Default behavior (single/zero page): clear session, reset and navigate back
                // Session 3: leaving Export back to Camera discards the current document draft
                // (same semantics as before Session 3, now also for the persisted snapshot).
                discardActiveDocumentSessionAsync();
                if (exportSessionViewModel != null) exportSessionViewModel.setInitial(null);
                cameraViewModel.setImageUri(null);
                cropViewModel.setImageCropped(false);
                cropViewModel.setImageBitmap(null);
                cropViewModel.setOriginalImageBitmap(null);
                cropViewModel.setImageLoaded(false);
                NavOptions navOptions =
                    new NavOptions.Builder().setPopUpTo(R.id.navigation_camera, true).build();
                Navigation.findNavController(requireView())
                    .navigate(R.id.navigation_camera, null, navOptions);
              }
            });

    exportViewModel.getText().observe(getViewLifecycleOwner(), binding.textExport::setText);

    // No inline option listeners: options are managed exclusively via ExportOptionsDialogFragment.

    binding.buttonExport.setOnClickListener(
        v -> {
          // Use last saved options directly to save a click
          Context ctx = requireContext();
          boolean includeOcrSel = ExportPrefsHelper.isIncludeOcr(ctx);
          boolean exportAsJpegSel = ExportPrefsHelper.isExportAsJpeg(ctx);
          boolean graySel = ExportPrefsHelper.isGrayscaleFromPdfMode(ctx);

          // Update ViewModel to reflect the options used for this export
          exportViewModel.setIncludeOcr(includeOcrSel);
          exportViewModel.setConvertToGrayscale(graySel);
          exportViewModel.setExportFormat(exportAsJpegSel ? "JPEG" : "PDF");

          // Inbox Mode: skip file picker and export directly to inbox directory
          if (FeatureFlags.isInboxModeEnabled() && ExportPrefsHelper.isInboxEnabled(ctx)) {
            String inboxUriStr = ExportPrefsHelper.getInboxUri(ctx);
            if (inboxUriStr == null) {
              UIUtils.showToast(
                  ctx, getString(R.string.inbox_no_folder_selected), Toast.LENGTH_LONG);
              return;
            }
            if (inboxUriStr != null) {
              Uri inboxTreeUri = Uri.parse(inboxUriStr);
              if (InboxExporter.hasValidPermission(ctx, inboxTreeUri)) {
                String template = ExportPrefsHelper.getInboxFilenameTemplate(ctx);
                String baseName = InboxExporter.buildInboxBaseName(template);
                String mimeType = exportAsJpegSel ? "image/jpeg" : "application/pdf";
                String extension = exportAsJpegSel ? ".jpg" : ".pdf";
                Uri fileUri =
                    InboxExporter.createFileInInbox(
                        ctx, inboxTreeUri, mimeType, baseName, extension);
                if (fileUri != null) {
                  fileUri = FileUtils.ensureExtension(ctx, fileUri, extension);
                  String displayName =
                      de.schliweb.makeacopy.utils.infra.FileUtils.getDisplayNameFromUri(
                          ctx, fileUri);
                  exportViewModel.setSelectedFileLocation(fileUri);
                  exportViewModel.setSelectedFileLocationName(displayName);
                  lastExportedPdfName = displayName;
                  inboxExportInProgress = true;
                  if (exportAsJpegSel) {
                    performJpegExport();
                  } else {
                    performExport();
                  }
                  return;
                }
              }
              // Permission lost or file creation failed → clear inbox and fall through to picker
              UIUtils.showToast(ctx, getString(R.string.inbox_permission_lost), Toast.LENGTH_LONG);
              ExportPrefsHelper.clearInbox(ctx);
            }
          }

          // Proceed to file location selection based on format
          if (exportAsJpegSel) {
            selectJpegFileLocation();
          } else {
            selectFileLocation();
          }
        });

    // Options button opens the export options dialog without starting export
    binding.buttonOptions.setOnClickListener(
        v -> {
          getParentFragmentManager()
              .setFragmentResultListener(
                  ExportOptionsDialogFragment.REQUEST_KEY,
                  getViewLifecycleOwner(),
                  (requestKey, bundle) -> {
                    // Update ViewModel with new choices for immediate feedback and re-render
                    // preview
                    boolean includeOcrSel =
                        bundle.getBoolean(ExportOptionsDialogFragment.BUNDLE_INCLUDE_OCR, false);
                    boolean exportAsJpegSel =
                        bundle.getBoolean(ExportOptionsDialogFragment.BUNDLE_EXPORT_AS_JPEG, false);
                    String pdfMode = bundle.getString("pdf_bw_mode", null);
                    exportViewModel.setIncludeOcr(includeOcrSel);
                    // Derive grayscale flag for ViewModel from pdf_bw_mode (GRAYSCALE selected)
                    boolean graySel = "GRAYSCALE".equalsIgnoreCase(pdfMode);
                    exportViewModel.setConvertToGrayscale(graySel);
                    exportViewModel.setExportFormat(exportAsJpegSel ? "JPEG" : "PDF");
                    // Re-render preview to reflect grayscale/BW selections immediately
                    renderPreviewFromCurrent();
                    // No export kickoff here
                    getParentFragmentManager()
                        .clearFragmentResultListener(ExportOptionsDialogFragment.REQUEST_KEY);
                  });
          ExportOptionsDialogFragment.show(getParentFragmentManager());
        });
    binding.buttonAddScan.setOnClickListener(
        v -> {
          Context ctx3 = getContext();
          if (ctx3 != null) {
            ExportPrefsHelper.setPendingAddPage(ctx3);
          }
          cameraViewModel.setImageUri(null);
          cropViewModel.setImageCropped(false);
          cropViewModel.setImageBitmap(null);
          cropViewModel.setOriginalImageBitmap(null);
          cropViewModel.setImageLoaded(false);
          NavOptions navOptions =
              new NavOptions.Builder().setPopUpTo(R.id.navigation_camera, true).build();
          Navigation.findNavController(requireView())
              .navigate(R.id.navigation_camera, null, navOptions);
        });
    binding.buttonShareSmall.setOnClickListener(v -> shareDocument());

    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          UIUtils.adjustTextViewTopMarginForStatusBar(binding.textExport, 8);
          UIUtils.adjustMarginForSystemInsets(binding.exportOptionsGroup, 8);
          return insets;
        });

    exportViewModel
        .isDocumentReady()
        .observe(
            getViewLifecycleOwner(),
            ready -> {
              binding.buttonExport.setEnabled(ready);
              setShareButtonsEnabled(false); // erst nach Export aktiv
              binding.textExport.setText(
                  ready
                      ? R.string.document_ready_for_export
                      : R.string.no_document_ready_process_ocr_first);
            });

    exportViewModel
        .getDocumentBitmap()
        .observe(
            getViewLifecycleOwner(),
            bitmap -> {
              if (bitmap != null) {
                markSinglePageBitmapFreshForReEdit(bitmap);
                renderPreview(bitmap);
              } else {
                binding.documentPreview.setVisibility(View.INVISIBLE);
                updatePreviewOcrBadge();
              }
            });

    // No inline PDF preset UI setup: presets are chosen in the dialog and stored in
    // SharedPreferences.

    checkDocumentReady();

    return root;
  }

  private void markSinglePageBitmapFreshForReEdit(Bitmap bitmap) {
    if (bitmap == null || cropViewModel == null || exportSessionViewModel == null) return;
    try {
      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
          exportSessionViewModel.getPages().getValue();
      int n = (pages == null) ? 0 : pages.size();
      if (n <= 1) {
        cropViewModel.setLastFreshPageBitmap(bitmap);
        lastFreshMultipagePreviewBitmap = null;
        lastFreshMultipagePageId = null;
        return;
      }

      // FR #72 V1.3: in multi-page mode only the last freshly scanned/imported page is
      // re-editable. Rotation and preview/filter rendering can replace that page's preview bitmap
      // with derived Bitmap instances, so keep the fresh marker moving along that exact preview
      // chain. Do not mark arbitrary filmstrip selections.
      Bitmap fresh = cropViewModel.getLastFreshPageBitmap();
      if (activeSessionPageIndex < 0 || activeSessionPageIndex >= n) return;
      de.schliweb.makeacopy.ui.export.session.CompletedScan activePage =
          pages.get(activeSessionPageIndex);
      Bitmap activePageBitmap = activePage != null ? activePage.inMemoryBitmap() : null;
      boolean activePageIsFresh =
          activePage != null
              && lastFreshMultipagePageId != null
              && lastFreshMultipagePageId.equals(activePage.id());
      if (activePageIsFresh
          && ((fresh != null && fresh == activePageBitmap)
              || (lastFreshMultipagePreviewBitmap != null
                  && fresh == lastFreshMultipagePreviewBitmap))) {
        cropViewModel.setLastFreshPageBitmap(bitmap);
        lastFreshMultipagePreviewBitmap = bitmap;
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * Checks and determines whether the document is ready for export. This method evaluates the
   * current state of the cropped bitmap and OCR text, updates the corresponding fields in the
   * exportViewModel, and sets the document ready status accordingly.
   *
   * <p>- Retrieves the cropped bitmap from the cropViewModel. If it exists, it sets the document
   * bitmap in the exportViewModel. - Extracts the OCR text from the current state. If available, it
   * updates the exportViewModel with the extracted OCR text. - Updates the document ready status in
   * the exportViewModel. The document is considered ready if the document bitmap is not null.
   */
  private void checkDocumentReady() {
    // Only consider the document ready if the image has been cropped (perspective-corrected)
    Boolean isCropped = cropViewModel.isImageCropped().getValue();
    Bitmap maybeBitmap = cropViewModel.getImageBitmap().getValue();

    if (Boolean.TRUE.equals(isCropped) && maybeBitmap != null) {
      Bitmap bmp = maybeBitmap;
      Integer v = cropViewModel.getUserRotationDegrees().getValue();
      int userDeg = v == null ? 0 : ((v % 360) + 360) % 360;

      // Re-Edit produces a fresh preview bitmap before returning to Export. Session/page observers
      // can briefly replace ExportViewModel's current bitmap with another instance, so the stable
      // signal is the explicit fresh Re-Edit marker plus its stored bitmap reference.
      Bitmap freshReEditBitmap = cropViewModel.getLastFreshPageBitmap();
      boolean keepFreshReEditBitmap =
          cropViewModel.isLastFreshPageBitmapFromReEdit() && freshReEditBitmap != null;

      if (keepFreshReEditBitmap) {
        bmp = freshReEditBitmap;
        Log.d(
            TAG,
            "[EXPORT_LOG] checkDocumentReady: keeping fresh Re-Edit bitmap as-is, user rotation="
                + userDeg
                + "°");
      } else if (userDeg != 0) {
        Log.d(
            TAG,
            "[EXPORT_LOG] checkDocumentReady: applying user rotation="
                + userDeg
                + "° to cropped bitmap before preview");
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(userDeg);
        Bitmap rotated =
            android.graphics.Bitmap.createBitmap(
                bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (rotated != null) bmp = rotated;
      } else {
        Log.d(
            TAG,
            "[EXPORT_LOG] checkDocumentReady: user rotation is 0°, using cropped bitmap as-is");
      }

      if (!keepFreshReEditBitmap) {
        try {
          cropViewModel.setLastFreshPageBitmap(bmp);
          List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
              exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
          int n = (pages == null) ? 0 : pages.size();
          if (n > 1 && activeSessionPageIndex == n - 1) {
            lastFreshMultipagePreviewBitmap = bmp;
            de.schliweb.makeacopy.ui.export.session.CompletedScan activePage =
                pages != null
                        && activeSessionPageIndex >= 0
                        && activeSessionPageIndex < pages.size()
                    ? pages.get(activeSessionPageIndex)
                    : null;
            lastFreshMultipagePageId = activePage != null ? activePage.id() : null;
          } else {
            lastFreshMultipagePreviewBitmap = null;
            lastFreshMultipagePageId = null;
          }
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }

      exportViewModel.setDocumentBitmap(bmp);
      exportViewModel.setDocumentReady(true);
    } else {
      // Prevent exporting the original, un-cropped image
      exportViewModel.setDocumentBitmap(null);
      exportViewModel.setDocumentReady(false);
    }

    // OCR text (if present) can still be shown/prepared independently
    String ocrText = getOcrTextFromState();
    if (ocrText != null) exportViewModel.setOcrText(ocrText);
  }

  /**
   * Initiates the export process for the current document. This method handles the generation of a
   * PDF with optional OCR content, allows customization options such as grayscale conversion, and
   * manages user-selected file locations.
   *
   * <p>If the required document bitmap is not available, it shows a warning message to the user and
   * cancels the export process. The method also supports the generation of plain text files
   * containing OCR data if enabled.
   *
   * <p>Upon successful export, the resulting file URI and metadata (such as display name) are
   * updated and made available for further actions like sharing. In case of export failure, an
   * error message is displayed, and necessary fields are cleared.
   *
   * <p>The export process runs on a background thread to avoid blocking the UI thread, and state
   * updates such as export status and generated file paths are synchronized with the UI thread.
   *
   * <p>Steps performed in this method include: - Fetching the current document and ensuring it is
   * ready for export. - Checking and applying user preferences for grayscale conversion and OCR
   * inclusion. - Creating a searchable PDF (or notifying the user of failure if the process fails).
   * - Optionally triggering subsequent TXT creation if OCR data is included in the export. -
   * Updating UI elements based on the success or failure of the export.
   *
   * <p>Error handling ensures that unexpected failures are logged, and user feedback is provided
   * through toast messages and UI updates.
   */
  private void performExport() {
    Log.d(TAG, "performExport: Starting export process");

    // Multipage handling: if >1 pages, compose PDF
    final List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
        exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
    final boolean isMulti = pages != null && pages.size() > 1;

    final Bitmap documentBitmap = exportViewModel.getDocumentBitmap().getValue();
    if (!isMulti && documentBitmap == null) {
      UIUtils.showToast(
          requireContext(), getString(R.string.no_document_to_export), Toast.LENGTH_SHORT);
      return;
    }

    final boolean includeOcr = Boolean.TRUE.equals(exportViewModel.isIncludeOcr().getValue());
    final boolean convertToGrayscale =
        Boolean.TRUE.equals(exportViewModel.isConvertToGrayscale().getValue());
    final Uri selectedLocation = exportViewModel.getSelectedFileLocation().getValue();

    // PDF text layer words sourcing: prefer edited OCR JSON when OCR Review feature is enabled
    List<RecognizedWord> wordsTmp;
    if (FeatureFlags.isOcrReviewEnabled()) {
      wordsTmp = null;
      // Try to find a scan id to resolve autosave path
      String candidateId = null;
      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pgs =
          exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
      if (pgs != null && !pgs.isEmpty() && pgs.get(0) != null) {
        candidateId = pgs.get(0).id();
      }
      Context ctxForIds = getContext();
      if ((candidateId == null || candidateId.trim().isEmpty()) && ctxForIds != null) {
        // Fall back to session id used by Review autosave
        candidateId = SessionIds.getOrCreateCurrentScanId(ctxForIds.getApplicationContext());
      }
      if (candidateId != null && !candidateId.trim().isEmpty() && ctxForIds != null) {
        File dir = new File(ctxForIds.getFilesDir(), "scans/" + candidateId);
        File ocrFile = new File(dir, "page.ocr.json");
        List<RecognizedWord> fromJson = OcrJsonWords.parseFile(ocrFile);
        if (fromJson != null && !fromJson.isEmpty()) {
          wordsTmp = fromJson;
        }
      }
      if (wordsTmp == null) {
        // fallback: current in-memory OCR words
        wordsTmp = getOcrWordsFromState();
      }
    } else {
      wordsTmp = getOcrWordsFromState();
    }
    if (wordsTmp != null && wordsTmp.isEmpty()) {
      wordsTmp = null;
    }
    final String recognizedText = getOcrTextFromState();
    final List<RecognizedWord> recognizedWords =
        ensurePdfTextLayerWords(
            wordsTmp, recognizedText, exportViewModel.getDocumentBitmap().getValue());

    final Context appContext = requireContext().getApplicationContext();
    exportViewModel.setTxtExportUri(null);
    // Disable Share at the start of export; it will be re-enabled only on success
    setShareButtonsEnabled(false);
    lastExportedDocumentUri = null;
    lastExportedPdfName = null;
    exportViewModel.setExporting(true);
    // A11y: announce export start (guarded)
    if (isAdded()) {
      View rv = getView();
      if (rv != null) {
        A11yUtils.announce(rv, getString(R.string.export_started));
      }
    }

    new Thread(
            () -> {
              try {
                // Resolve export settings from SharedPreferences via helper
                List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pgsForPreset =
                    exportSessionViewModel != null
                        ? exportSessionViewModel.getPages().getValue()
                        : null;
                int pageCount = (pgsForPreset == null) ? 0 : pgsForPreset.size();
                PdfQualityPreset preset = ExportPrefsHelper.resolvePreset(appContext, pageCount);
                boolean[] grayBw =
                    ExportPrefsHelper.resolveGrayAndBwFlags(
                        appContext, preset.forceGrayscale, convertToGrayscale);
                final boolean convertGrayEffective = grayBw[0];
                final boolean convertBwEffective = grayBw[1];
                final int jpegQuality = preset.jpegQuality;
                final PdfCreator.BwMode bwMode = ExportPrefsHelper.resolveBwMode(appContext);
                final de.schliweb.makeacopy.utils.image.DocumentCleanupMode cleanupMode =
                    ExportPrefsHelper.resolveCleanupMode(appContext);
                final PageFormat pageFormat = ExportPrefsHelper.resolvePageFormat(appContext);
                final PdfCreator.TextLayerMode textLayerMode =
                    ExportPrefsHelper.resolveTextLayerMode(appContext);

                Uri exportUri;
                if (isMulti) {
                  Log.d(TAG, "performExport: Creating PDF for multipage session (streaming)");
                  // Streaming export: pages are loaded lazily one at a time via PageSource so
                  // peak memory depends on a single page, not on the document page count.
                  final Bitmap current = documentBitmap;
                  final List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pageSnapshot =
                      new ArrayList<>(pages);
                  final int totalPages = pageSnapshot.size();
                  postToUiSafe(
                      () -> {
                        exportViewModel.setExportProgressMax(totalPages);
                        exportViewModel.setExportProgress(0);
                      });
                  final PdfCreator.PageSource pageSource =
                      new PdfCreator.PageSource() {
                        // Tracks whether the bitmap handed out for the current page is owned by
                        // this source (decoded/rotated here) or shared (session in-memory bitmap).
                        // Only owned bitmaps are recycled in releasePage(). Access is safe because
                        // PdfCreator consumes pages strictly sequentially.
                        private boolean currentPageOwned = false;

                        @Override
                        public int getPageCount() {
                          return totalPages;
                        }

                        @Override
                        public Bitmap loadBitmap(int index) {
                          de.schliweb.makeacopy.ui.export.session.CompletedScan s =
                              pageSnapshot.get(index);
                          currentPageOwned = false;
                          if (s == null) return null;
                          Bitmap pageBmp = s.inMemoryBitmap();
                          boolean loadedFromFile = false;
                          if (pageBmp == null) {
                            String p = s.filePath();
                            if (p != null) {
                              // Decode full-res without implicit EXIF rotation; baked files are
                              // visually upright
                              pageBmp = ImageDecodeUtils.decodeFull(p);
                              loadedFromFile = (pageBmp != null);
                              currentPageOwned = loadedFromFile;
                            }
                          }
                          if (pageBmp == null) return null;
                          int deg = s.rotationDeg();
                          String mode = s.orientationMode();
                          boolean shouldRotate =
                              RotationPolicy.shouldRotateForExport(loadedFromFile, mode, deg);
                          if (shouldRotate) {
                            android.graphics.Matrix m = new android.graphics.Matrix();
                            m.postRotate(((deg % 360) + 360) % 360);
                            Bitmap rotated =
                                android.graphics.Bitmap.createBitmap(
                                    pageBmp,
                                    0,
                                    0,
                                    pageBmp.getWidth(),
                                    pageBmp.getHeight(),
                                    m,
                                    true);
                            if (rotated != pageBmp) {
                              if (loadedFromFile && !pageBmp.isRecycled()) {
                                pageBmp.recycle();
                              }
                              pageBmp = rotated;
                              currentPageOwned = true;
                            }
                          }
                          return pageBmp;
                        }

                        @Override
                        public List<RecognizedWord> loadWords(int index) {
                          de.schliweb.makeacopy.ui.export.session.CompletedScan s =
                              pageSnapshot.get(index);
                          if (s == null) return null;
                          List<RecognizedWord> pageWords = loadWordsForSessionPage(s);
                          if (pageWords == null
                              && s.inMemoryBitmap() == current
                              && recognizedWords != null
                              && !recognizedWords.isEmpty()) {
                            pageWords = recognizedWords;
                          }
                          return pageWords;
                        }

                        @Override
                        public void releasePage(int index, Bitmap bitmap) {
                          // Ownership contract: only recycle bitmaps this source created; the
                          // session's in-memory bitmaps stay alive for the UI.
                          if (currentPageOwned && bitmap != null && !bitmap.isRecycled()) {
                            bitmap.recycle();
                          }
                          currentPageOwned = false;
                        }
                      };
                  exportUri =
                      PdfCreator.createSearchablePdf(
                          appContext,
                          pageSource,
                          selectedLocation,
                          jpegQuality,
                          convertGrayEffective,
                          convertBwEffective,
                          preset.targetDpi,
                          (pageIndex, total) ->
                              postToUiSafe(
                                  () ->
                                      exportViewModel.setExportProgress(
                                          Math.max(0, Math.min(pageIndex, total)))),
                          bwMode,
                          pageFormat,
                          cleanupMode,
                          textLayerMode,
                          MultiColumnOcrPrefs.isEnabled(appContext));

                } else {
                  Log.d(TAG, "performExport: Creating PDF for single page session");
                  // Single-page: documentBitmap is already oriented for preview; avoid
                  // double-rotating here
                  final Bitmap toExport = documentBitmap;
                  exportUri =
                      PdfCreator.createSearchablePdf(
                          appContext,
                          toExport,
                          recognizedWords,
                          selectedLocation,
                          jpegQuality,
                          convertGrayEffective,
                          convertBwEffective,
                          preset.targetDpi,
                          bwMode,
                          pageFormat,
                          cleanupMode,
                          textLayerMode,
                          MultiColumnOcrPrefs.isEnabled(appContext));
                }

                final Uri finalUri = exportUri;
                postToUiSafe(
                    () -> {
                      if (finalUri != null) {
                        lastExportedDocumentUri = finalUri;
                        // Persist SAF permission so the file stays readable after app restarts
                        persistUriPermission(finalUri);
                        String displayName =
                            FileUtils.getDisplayNameFromUri(
                                requireContext(), lastExportedDocumentUri);
                        lastExportedPdfName = displayName;
                        setShareButtonsEnabled(true);

                        String msg =
                            getString(
                                isMulti
                                    ? R.string.document_multipage_exported
                                    : R.string.document_exported,
                                lastExportedPdfName);
                        // Confirm the successful export with a short haptic tick
                        HapticsUtils.vibrateOneShot(appContext, 30L);
                        UIUtils.showToast(appContext, msg, Toast.LENGTH_LONG);
                        View rv = getView();
                        if (isAdded() && rv != null) {
                          A11yUtils.announce(rv, msg);
                        }

                        // Begin: index exported PDF into scan library (feature-guarded via service
                        // locator)
                        int pageCountForIndex = isMulti ? ((pages == null) ? 0 : pages.size()) : 1;
                        indexScanLibraryAsync(displayName, pageCountForIndex, finalUri);
                        // End: index
                        if (includeOcr) {
                          if (inboxExportInProgress) {
                            // Inbox Mode: export TXT directly to inbox without file picker
                            exportTxtToInbox();
                          } else {
                            // Normal mode: show file picker for TXT
                            deferAssignUntilTxt = true;
                            launchTxtFileCreation();
                          }
                        }
                      } else {
                        lastExportedDocumentUri = null;
                        exportViewModel.setTxtExportUri(null);
                        setShareButtonsEnabled(false);
                        String fail = getString(R.string.failed_to_export_document);
                        UIUtils.showToast(appContext, fail, Toast.LENGTH_SHORT);
                        View rv = getView();
                        if (isAdded() && rv != null) {
                          A11yUtils.announce(rv, fail);
                        }
                      }
                    });
              } catch (Exception e) {
                Log.e(TAG, "Error during export", e);
                postToUiSafe(
                    () -> {
                      lastExportedDocumentUri = null;
                      exportViewModel.setTxtExportUri(null);
                      setShareButtonsEnabled(false);
                      String err =
                          getString(R.string.error_during_export_with_reason, e.getMessage());
                      UIUtils.showToast(appContext, err, Toast.LENGTH_SHORT);
                      View rv = getView();
                      if (isAdded() && rv != null) {
                        A11yUtils.announce(rv, err);
                      }
                    });
              } finally {
                postToUiSafe(
                    () -> {
                      exportViewModel.setExporting(false);
                      exportViewModel.setExportProgress(0);
                      exportViewModel.setExportProgressMax(0);
                      maybeAutoNewScan();
                    });
              }
            })
        .start();
  }

  /**
   * Loads the OCR words for a single session page from disk. Prefers the edited OCR JSON sidecar
   * (when the OCR Review feature is enabled), then falls back to the registry-backed words_json
   * payload. Returns {@code null} when no usable OCR words exist (image-only page).
   */
  private List<RecognizedWord> loadWordsForSessionPage(
      de.schliweb.makeacopy.ui.export.session.CompletedScan s) {
    if (s == null) return null;
    List<RecognizedWord> pageWords = null;
    if (FeatureFlags.isOcrReviewEnabled()) {
      // 1) Try our editable OCR JSON sidecar under filesDir/scans/<id>/page.ocr.json
      if (s.id() != null) {
        Context c = getContext();
        if (c != null) {
          File dir = new File(c.getFilesDir(), "scans/" + s.id());
          File ocrFile = new File(dir, "page.ocr.json");
          List<RecognizedWord> fromJson = OcrJsonWords.parseFile(ocrFile);
          if (fromJson != null && !fromJson.isEmpty()) pageWords = fromJson;
        }
      }
    }
    // 2) If not found (or feature disabled), try registry-backed words_json
    if (pageWords == null) {
      String fmt = s.ocrFormat();
      String path = s.ocrTextPath();
      if ("words_json".equalsIgnoreCase(fmt) && path != null) {
        File f = new File(path);
        if (f.exists() && f.isFile()) {
          try {
            pageWords = WordsJson.parseFile(f);
          } catch (Exception e) {
            // Broken OCR JSON on a single page must not abort the export (image-only fallback)
            Log.w(TAG, "Failed to parse OCR words for page " + s.id(), e);
            pageWords = null;
          }
          if (pageWords != null && pageWords.isEmpty()) pageWords = null;
        }
      }
    }
    return pageWords;
  }

  /**
   * Handles the selection of the file location and initiates the document creation process.
   *
   * <p>This method generates a default file name with the current timestamp in the format
   * "yyyyMMdd_HHmmss" combined with a "DOC_" prefix and a ".pdf" suffix. The generated file name is
   * passed to the document creation launcher, which prompts the user to select a location for
   * saving the file. The selected location can subsequently be used for exporting or saving the
   * generated PDF document.
   */
  private void selectFileLocation() {
    String defaultFileName = buildDefaultBaseName() + ".pdf";
    createDocumentLauncher.launch(defaultFileName);
  }

  /**
   * If the current export was triggered via Inbox Mode and auto-new-scan is enabled, navigates back
   * to the camera fragment to start a new scan immediately.
   */
  private void maybeAutoNewScan() {
    if (!inboxExportInProgress) return;
    inboxExportInProgress = false;
    Context ctx = getContext();
    if (ctx == null) return;
    if (!ExportPrefsHelper.isInboxAutoNewScan(ctx)) return;
    if (!isAdded()) return;
    UIUtils.showToast(ctx, getString(R.string.inbox_saved), Toast.LENGTH_SHORT);
    cameraViewModel.setImageUri(null);
    cropViewModel.setImageCropped(false);
    cropViewModel.setImageBitmap(null);
    cropViewModel.setOriginalImageBitmap(null);
    cropViewModel.setImageLoaded(false);
    if (exportSessionViewModel != null) exportSessionViewModel.setInitial(null);
    Navigation.findNavController(requireView()).navigate(R.id.navigation_camera);
  }

  // Centralized default base-name derivation used for PDF/JPEG/ZIP/TXT
  private String buildDefaultBaseName() {
    String timeStamp =
        java.time.LocalDateTime.now(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    return "DOC_" + timeStamp;
  }

  // Strip a single trailing extension (case-insensitive), e.g. file.pdf -> file; file.name.zip ->
  // file.name
  private String stripOneExtension(String name) {
    if (name == null) return null;
    int idx = name.lastIndexOf('.');
    if (idx > 0 && idx < name.length() - 1) {
      return name.substring(0, idx);
    }
    return name;
  }

  // Persist read/write access for a SAF Uri so it remains accessible after app restarts
  private void persistUriPermission(@NonNull Uri uri) {
    try {
      // Persist both read and write in case user wants to overwrite later; providers may ignore
      // write
      requireContext()
          .getContentResolver()
          .takePersistableUriPermission(
              uri,
              android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                  | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    } catch (SecurityException | IllegalArgumentException ignore) {
      // Some providers don't support persisting or the Uri is already persisted; ignore errors
    }
  }

  /** Launches SAF CreateDocument for JPEG export with default filename. */
  private void selectJpegFileLocation() {
    java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
        exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
    int n = (pages == null) ? 0 : pages.size();
    String base = buildDefaultBaseName();
    if (n > 1) {
      String defaultZipName = base + ".zip";
      createZipDocumentLauncher.launch(defaultZipName);
    } else {
      String defaultFileName = base + ".jpg";
      createJpegDocumentLauncher.launch(defaultFileName);
    }
  }

  /**
   * Performs JPEG export using the already perspective-corrected bitmap and JpegExporter. MVP: uses
   * default options (quality=85, original size, no enhancement).
   */
  private void performJpegExport() {
    performJpegExport(ExportPrefsHelper.resolveJpegMode(requireContext()));
  }

  /** Performs JPEG export using a chosen enhancement mode. */
  private void performJpegExport(JpegExportOptions.Mode chosenMode) {
    // If multiple pages, this call path shouldn't be used; ZIP path handles it
    java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pagesCheck =
        exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
    if (pagesCheck != null && pagesCheck.size() > 1) {
      // Should have gone through ZIP flow
      UIUtils.showToast(
          requireContext(), getString(R.string.multipage_not_implemented), Toast.LENGTH_SHORT);
      return;
    }
    Log.d(TAG, "performJpegExport: Starting JPEG export process with mode=" + chosenMode);
    // v1 increment: if multiple pages are present, multi-image ZIP export is not implemented
    java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
        exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
    if (pages != null && pages.size() > 1) {
      UIUtils.showToast(
          requireContext(), getString(R.string.multipage_not_implemented), Toast.LENGTH_SHORT);
      return;
    }
    final Bitmap documentBitmap = exportViewModel.getDocumentBitmap().getValue();
    if (documentBitmap == null) {
      UIUtils.showToast(
          requireContext(), getString(R.string.no_document_to_export), Toast.LENGTH_SHORT);
      return;
    }
    final Uri selectedLocation = exportViewModel.getSelectedFileLocation().getValue();
    if (selectedLocation == null) {
      UIUtils.showToast(
          requireContext(), getString(R.string.no_target_selected), Toast.LENGTH_SHORT);
      return;
    }
    final Context appContext = requireContext().getApplicationContext();
    final boolean includeOcr = Boolean.TRUE.equals(exportViewModel.isIncludeOcr().getValue());

    // Ensure OpenCV is initialized before using JpegExporter (which uses OpenCV APIs)
    try {
      if (!OpenCVUtils.isInitialized()) {
        OpenCVUtils.init(appContext);
      }
    } catch (Exception e) {
      Log.w(TAG, "performJpegExport: OpenCV init failed or not available", e);
    }

    // Reset any previously generated TXT URI to avoid sharing stale OCR text
    exportViewModel.setTxtExportUri(null);
    // Disable Share at the start of export; it will be re-enabled only on success
    setShareButtonsEnabled(false);
    lastExportedDocumentUri = null;
    lastExportedPdfName = null;
    exportViewModel.setExporting(true);
    // A11y: announce export start (JPEG)
    if (isAdded()) {
      View rv = getView();
      if (rv != null) {
        A11yUtils.announce(rv, getString(R.string.export_started));
      }
    }
    new Thread(
            () -> {
              try {
                JpegExportOptions options =
                    new JpegExportOptions(); // defaults (quality=85, no resize)
                options.mode = (chosenMode != null) ? chosenMode : JpegExportOptions.Mode.NONE;
                options.forceGrayscaleJpeg = ExportPrefsHelper.isJpegOutputGrayscale(appContext);
                options.cleanupMode = ExportPrefsHelper.resolveCleanupMode(appContext);

                // For single-image JPEG, the preview bitmap is already oriented (rotated) for
                // display.
                // Align behavior with PDF export: do not apply rotation again to avoid
                // double-rotation.
                Uri exportUri =
                    JpegExporter.export(appContext, documentBitmap, options, selectedLocation);
                final Uri exportUriFinal = exportUri;
                postToUiSafe(
                    () -> {
                      if (exportUriFinal != null) {
                        lastExportedDocumentUri = exportUriFinal;
                        // Persist SAF permission so the file stays readable after app restarts
                        persistUriPermission(exportUriFinal);
                        String displayName =
                            FileUtils.getDisplayNameFromUri(
                                requireContext(), lastExportedDocumentUri);
                        lastExportedPdfName = displayName;
                        setShareButtonsEnabled(true);
                        UIUtils.showToast(
                            appContext,
                            getString(R.string.image_exported, displayName),
                            Toast.LENGTH_LONG);

                        // Begin: index exported scan in background (feature-guarded via service
                        // locator)
                        indexScanLibraryAsync(displayName, 1, exportUriFinal);
                        // End: index
                        if (includeOcr) {
                          if (inboxExportInProgress) {
                            // Inbox Mode: export TXT directly to inbox without file picker
                            exportTxtToInbox();
                          } else {
                            // Normal mode: show file picker for TXT
                            deferAssignUntilTxt = true;
                            launchTxtFileCreation();
                          }
                        }
                      } else {
                        lastExportedDocumentUri = null;
                        setShareButtonsEnabled(false);
                        UIUtils.showToast(
                            appContext,
                            getString(R.string.failed_to_export_image),
                            Toast.LENGTH_SHORT);
                      }
                    });
              } catch (Exception e) {
                Log.e(TAG, "Error during JPEG export", e);
                postToUiSafe(
                    () -> {
                      lastExportedDocumentUri = null;
                      setShareButtonsEnabled(false);
                      UIUtils.showToast(
                          appContext,
                          getString(R.string.error_during_jpeg_export_with_reason, e.getMessage()),
                          Toast.LENGTH_SHORT);
                    });
              } finally {
                postToUiSafe(
                    () -> {
                      exportViewModel.setExporting(false);
                      exportViewModel.setExportProgress(0);
                      exportViewModel.setExportProgressMax(0);
                      maybeAutoNewScan();
                    });
              }
            })
        .start();
  }

  /** Performs a multi-image ZIP export for JPEG when there are multiple pages. */
  private void performJpegZipExport() {
    JpegExportOptions.Mode mode = ExportPrefsHelper.resolveJpegMode(requireContext());

    final Uri selectedLocation = exportViewModel.getSelectedFileLocation().getValue();
    if (selectedLocation == null) {
      UIUtils.showToast(
          requireContext(), getString(R.string.no_target_selected), Toast.LENGTH_SHORT);
      return;
    }
    final List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
        exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
    if (pages == null || pages.size() <= 1) {
      UIUtils.showToast(
          requireContext(), getString(R.string.multipage_not_implemented), Toast.LENGTH_SHORT);
      return;
    }
    final Context appContext = requireContext().getApplicationContext();
    exportViewModel.setExporting(true);
    // A11y: announce export start (ZIP)
    if (isAdded()) {
      View rv = getView();
      if (rv != null) {
        A11yUtils.announce(rv, getString(R.string.export_started));
      }
    }
    exportViewModel.setTxtExportUri(null);
    // Disable Share at the start of export; it will be re-enabled only on success
    setShareButtonsEnabled(false);
    lastExportedDocumentUri = null;
    lastExportedPdfName = null;

    final JpegExportOptions.Mode finalMode = mode;
    new Thread(
            () -> {
              java.util.zip.ZipOutputStream zos = null;
              // Initialize progress for ZIP multi-image export
              final int totalPages = (pages == null) ? 0 : pages.size();
              postToUiSafe(
                  () -> {
                    exportViewModel.setExportProgressMax(totalPages);
                    exportViewModel.setExportProgress(0);
                  });
              try {
                // Ensure OpenCV is initialized
                try {
                  if (!OpenCVUtils.isInitialized()) OpenCVUtils.init(appContext);
                } catch (Exception e) {
                  Log.w(TAG, "performJpegZipExport: OpenCV init failed or not available", e);
                }

                JpegExportOptions options = new JpegExportOptions();
                options.mode = finalMode;
                options.forceGrayscaleJpeg = ExportPrefsHelper.isJpegOutputGrayscale(appContext);
                options.cleanupMode = ExportPrefsHelper.resolveCleanupMode(appContext);

                OutputStream os =
                    requireContext().getContentResolver().openOutputStream(selectedLocation, "w");
                if (os == null) throw new RuntimeException("Failed to open ZIP output stream");
                zos = new java.util.zip.ZipOutputStream(os);

                int idx = 1;
                for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : pages) {
                  if (s == null) continue;
                  String name = String.format(Locale.getDefault(), "page_%03d.jpg", idx);
                  java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(name);
                  zos.putNextEntry(entry);
                  Bitmap pageBmp = s.inMemoryBitmap();
                  boolean loadedFromFile = false;
                  if (pageBmp == null) {
                    String p = s.filePath();
                    if (p != null) {
                      try {
                        // Decode full-res without implicit EXIF rotation; baked files are visually
                        // upright
                        pageBmp = ImageDecodeUtils.decodeFull(p);
                        loadedFromFile = (pageBmp != null);
                      } catch (Exception e) {
                        Log.w(TAG, "ZIP export: decodeFull failed for " + p, e);
                      }
                    }
                  }
                  if (pageBmp == null) {
                    // Nothing to write for this page
                    zos.closeEntry();
                    idx++;
                    continue;
                  }
                  int deg = s.rotationDeg();
                  String orientationMode = s.orientationMode();
                  boolean shouldRotate =
                      RotationPolicy.shouldRotateForExport(loadedFromFile, orientationMode, deg);
                  if (shouldRotate) {
                    try {
                      android.graphics.Matrix m = new android.graphics.Matrix();
                      m.postRotate(((deg % 360) + 360) % 360);
                      Bitmap rotated =
                          android.graphics.Bitmap.createBitmap(
                              pageBmp, 0, 0, pageBmp.getWidth(), pageBmp.getHeight(), m, true);
                      if (rotated != pageBmp) pageBmp = rotated;
                    } catch (Exception e) {
                      Log.w(TAG, "ZIP export: rotation failed, keeping original", e);
                    }
                  }
                  boolean ok = JpegExporter.exportToStream(appContext, pageBmp, options, zos);
                  zos.closeEntry();
                  if (!ok) throw new RuntimeException("Failed to encode " + name);
                  // Recycle if this bitmap was not the session's in-memory reference
                  if (s.inMemoryBitmap() != pageBmp && pageBmp != null && !pageBmp.isRecycled()) {
                    pageBmp.recycle();
                  }
                  // Update progress after each page
                  final int done = idx;
                  postToUiSafe(() -> exportViewModel.setExportProgress(done));
                  idx++;
                }
                zos.finish();
                zos.flush();

                Uri exportUri = selectedLocation;
                postToUiSafe(
                    () -> {
                      if (exportUri != null) {
                        lastExportedDocumentUri = exportUri;
                        // Persist SAF permission so the file stays readable after app restarts
                        persistUriPermission(exportUri);
                        String displayName =
                            FileUtils.getDisplayNameFromUri(requireContext(), exportUri);
                        lastExportedPdfName = displayName;
                        setShareButtonsEnabled(true);
                        UIUtils.showToast(
                            appContext,
                            getString(R.string.zip_exported, displayName),
                            Toast.LENGTH_LONG);

                        // Begin: index exported multi-page scan in background (feature-guarded via
                        // service locator)
                        indexScanLibraryAsync(displayName, totalPages, exportUri);
                        // End: index
                        if (Boolean.TRUE.equals(exportViewModel.isIncludeOcr().getValue())) {
                          // Defer showing the assignment snackbar until TXT has been saved
                          deferAssignUntilTxt = true;
                          launchTxtFileCreation();
                        }
                      } else {
                        lastExportedDocumentUri = null;
                        setShareButtonsEnabled(false);
                        UIUtils.showToast(
                            appContext,
                            getString(R.string.failed_to_export_zip),
                            Toast.LENGTH_SHORT);
                      }
                    });
              } catch (Exception e) {
                Log.e(TAG, "Error during ZIP export", e);
                postToUiSafe(
                    () -> {
                      lastExportedDocumentUri = null;
                      setShareButtonsEnabled(false);
                      UIUtils.showToast(
                          appContext,
                          getString(R.string.error_during_zip_export_with_reason, e.getMessage()),
                          Toast.LENGTH_SHORT);
                    });
              } finally {
                if (zos != null) {
                  try {
                    zos.close();
                  } catch (Exception ignore) {
                    // Best-effort; failure is non-critical
                  }
                }
                postToUiSafe(
                    () -> {
                      exportViewModel.setExporting(false);
                      exportViewModel.setExportProgress(0);
                      exportViewModel.setExportProgressMax(0);
                    });
              }
            })
        .start();
  }

  /**
   * Launches the process to create a TXT file with a generated name based on the last exported PDF
   * name or a default timestamp.
   *
   * <p>- If the last exported PDF name (`lastExportedPdfName`) is `null`, a default file name is
   * generated using the current timestamp in the format "yyyyMMdd_HHmmss" with a "DOC_" prefix. -
   * If a valid PDF name exists, it is used as the base name, after removing the ".pdf" extension. -
   * In case of an exception during name processing, a fallback file name based on the timestamp is
   * used. - A ".txt" suffix is appended to the final base name, and the resulting file name is
   * provided to the `createTxtDocumentLauncher` to trigger the TXT file creation process.
   */
  private void launchTxtFileCreation() {
    String pdfName = lastExportedPdfName;
    if (pdfName == null) {
      pdfName = buildDefaultBaseName();
    } else {
      // Strip one extension (handles .pdf, .jpg, .jpeg, .zip, etc.)
      pdfName = stripOneExtension(pdfName);
    }
    String txtFileName = pdfName + ".txt";
    createTxtDocumentLauncher.launch(txtFileName);
  }

  /**
   * Exports the OCR text as a TXT file directly into the Inbox directory, bypassing the file
   * picker. Uses the same base name as the last exported PDF/JPEG with a {@code .txt} extension.
   */
  private void exportTxtToInbox() {
    Context ctx = getContext();
    if (ctx == null) return;
    String inboxUriStr = ExportPrefsHelper.getInboxUri(ctx);
    if (inboxUriStr == null) {
      Log.w(TAG, "exportTxtToInbox: no inbox URI configured");
      return;
    }
    Uri inboxTreeUri = Uri.parse(inboxUriStr);
    String baseName = lastExportedPdfName;
    if (baseName == null) {
      baseName = buildDefaultBaseName();
    } else {
      baseName = stripOneExtension(baseName);
    }
    Uri txtUri = InboxExporter.createFileInInbox(ctx, inboxTreeUri, "text/plain", baseName, ".txt");
    if (txtUri != null) {
      txtUri = FileUtils.ensureExtension(ctx, txtUri, ".txt");
      exportOcrTextToTxt(txtUri);
    } else {
      Log.w(TAG, "exportTxtToInbox: failed to create TXT file in inbox");
    }
  }

  /**
   * Exports the OCR text to a specified TXT file in the given URI. This method retrieves the OCR
   * text from the current state and writes it to the provided TXT file URI. If the OCR text is not
   * available or the output stream cannot be opened, it logs an error or displays a message to the
   * user. On successful export, the URI of the exported TXT file is saved, and a confirmation
   * message is shown to the user.
   *
   * @param txtUri The URI of the TXT file where the OCR text will be exported. If null, the method
   *     will exit without performing any actions.
   */
  private void exportOcrTextToTxt(Uri txtUri) {
    ExportTxtHelper.exportOcrTextToTxt(
        requireContext(),
        exportViewModel,
        exportSessionViewModel,
        txtUri,
        getOcrTextFromState(),
        exportViewModel.getDocumentBitmap().getValue(),
        () -> deferAssignUntilTxt = false);
  }

  // ===== Session 3: persistent DocumentSession wiring =====

  /**
   * Returns the currently active/previewed page, falling back to the single page when the session
   * has exactly one entry. Used by the edit entry points.
   */
  private de.schliweb.makeacopy.ui.export.session.CompletedScan getActivePageForEdit() {
    if (exportSessionViewModel == null) return null;
    List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
        exportSessionViewModel.getPages().getValue();
    if (pages == null || pages.isEmpty()) return null;
    int idx = activeSessionPageIndex;
    if (idx < 0 || idx >= pages.size()) idx = findActivePageIndex();
    if ((idx < 0 || idx >= pages.size()) && pages.size() == 1) idx = 0;
    if (idx < 0 || idx >= pages.size()) return null;
    return pages.get(idx);
  }

  /**
   * Session 3: opens the existing single-page crop editor for a persisted page. The persisted
   * page.jpg is decoded as the editing source (the authoritative working copy — no re-render from
   * an original PDF), previous trapezoid state is cleared and the stable page id is recorded so the
   * editor return path updates the SAME page (no new page id).
   */
  private void startPersistedPageEdit(
      View v, de.schliweb.makeacopy.ui.export.session.CompletedScan page) {
    if (page == null || page.filePath() == null) return;
    Bitmap src = null;
    try {
      src = android.graphics.BitmapFactory.decodeFile(page.filePath());
    } catch (Throwable t) {
      Log.w(TAG, "startPersistedPageEdit: decode failed", t);
    }
    if (src == null) {
      UIUtils.showToast(
          requireContext(), getString(R.string.edit_crop_original_unavailable), Toast.LENGTH_SHORT);
      return;
    }
    // The persisted page is already baked (rotation 0) — reset editor state so CropFragment
    // starts from the stored image with default corner detection.
    cropViewModel.setUserRotationDegrees(0);
    cropViewModel.setLastAcceptedUserRotationDeg(0);
    cropViewModel.setLastAcceptedCornersOriginal(null);
    cropViewModel.setOriginalImageBitmap(src);
    cropViewModel.setImageCropped(false);
    cropViewModel.setImageBitmap(src);
    cropViewModel.setCameFromExport(true);
    cropViewModel.setReEditPageIndex(findActivePageIndex());
    cropViewModel.setReEditPageId(page.id());
    try {
      Navigation.findNavController(v).navigate(R.id.navigation_crop);
    } catch (Throwable t) {
      Log.w(TAG, "Persisted page edit navigation failed", t);
      cropViewModel.setCameFromExport(false);
      cropViewModel.setReEditPageIndex(-1);
      cropViewModel.setReEditPageId(null);
    }
  }

  /**
   * Session 3: persists the ordered page ids of the runtime session into the active
   * DocumentSession. The document id is generated eagerly on the main thread (race-free), so all
   * page sources (camera seed, PDF import, add page) funnel through this single sync point. Empty
   * lists are ignored here — explicit discards go through {@link
   * #discardActiveDocumentSessionAsync()}.
   */
  private void syncDocumentSessionAsync(
      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages) {
    if (exportSessionViewModel == null) return;
    Context c = getContext();
    if (c == null) return;
    final Context app = c.getApplicationContext();
    final ArrayList<String> ids = new ArrayList<>();
    if (pages != null) {
      for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : pages) {
        if (s != null && s.id() != null) ids.add(s.id());
      }
    }
    if (ids.isEmpty()) return;
    if (exportSessionViewModel.getDocumentId() == null) {
      exportSessionViewModel.setDocumentId(java.util.UUID.randomUUID().toString());
    }
    final String docId = exportSessionViewModel.getDocumentId();
    documentSessionExecutor.execute(
        () -> {
          try {
            de.schliweb.makeacopy.data.DocumentSessionRepository.get(app).upsertActive(docId, ids);
          } catch (Throwable t) {
            Log.w(TAG, "DocumentSession sync failed", t);
          }
        });
  }

  /**
   * Session 3: restores the active persisted DocumentSession into the runtime session (after
   * process death / app restart). Missing pages are skipped and the session is repaired by the
   * repository; the persisted page order is preserved. No in-memory bitmaps are required — the
   * preview loads lazily from the persisted files.
   */
  private void restoreDocumentSessionAsync() {
    Context c = getContext();
    if (c == null) return;
    final Context app = c.getApplicationContext();
    documentSessionExecutor.execute(
        () -> {
          try {
            de.schliweb.makeacopy.data.DocumentSessionRepository repo =
                de.schliweb.makeacopy.data.DocumentSessionRepository.get(app);
            de.schliweb.makeacopy.data.DocumentSession session = repo.getActiveSession();
            if (session == null) return;
            final List<de.schliweb.makeacopy.ui.export.session.CompletedScan> resolved =
                repo.resolveActivePages(de.schliweb.makeacopy.data.CompletedScansRegistry.get(app));
            if (resolved.isEmpty()) return;
            final String docId = session.documentId();
            postToUiSafe(
                () -> {
                  if (exportSessionViewModel == null) return;
                  List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
                      exportSessionViewModel.getPages().getValue();
                  if (cur != null && !cur.isEmpty()) return; // runtime session took over meanwhile
                  exportSessionViewModel.setDocumentId(docId);
                  activeSessionPageIndex = 0;
                  exportSessionViewModel.addAll(resolved);
                  Log.i(
                      TAG,
                      "Restored DocumentSession " + docId + " with " + resolved.size() + " pages");
                });
          } catch (Throwable t) {
            Log.w(TAG, "DocumentSession restore failed", t);
          }
        });
  }

  /**
   * Session 3: explicit discard of the current document draft (user navigated back / cleared the
   * multipage session). Deletes the persisted DocumentSession; the underlying CompletedScans follow
   * the existing registry cleanup policy and are NOT deleted here.
   */
  private void discardActiveDocumentSessionAsync() {
    Context c = getContext();
    if (c == null) return;
    final Context app = c.getApplicationContext();
    final String docId =
        exportSessionViewModel != null ? exportSessionViewModel.getDocumentId() : null;
    if (exportSessionViewModel != null) exportSessionViewModel.setDocumentId(null);
    documentSessionExecutor.execute(
        () -> {
          try {
            de.schliweb.makeacopy.data.DocumentSessionRepository repo =
                de.schliweb.makeacopy.data.DocumentSessionRepository.get(app);
            if (docId != null) {
              repo.delete(docId);
            } else {
              repo.endActiveSession(true);
            }
          } catch (Throwable t) {
            Log.w(TAG, "DocumentSession discard failed", t);
          }
        });
  }

  /**
   * Session 4 document lifecycle menu (behind the library-actions icon): open a saved document,
   * start a new document, close the current document (persisted, not deleted) or open the scan
   * library. While an OCR batch is running all document-switching actions are blocked so no OCR job
   * can ever update UI state of a different document.
   */
  private void showDocumentActionsMenu() {
    String[] options =
        new String[] {
          getString(R.string.doc_action_saved_documents),
          getString(R.string.doc_action_new_document),
          getString(R.string.doc_action_close_document),
          getString(R.string.doc_action_scan_library)
        };
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.doc_actions_title)
        .setItems(
            options,
            (dlg, which) -> {
              if (which == 3) {
                try {
                  Navigation.findNavController(requireView())
                      .navigate(R.id.navigation_scans_library);
                } catch (IllegalArgumentException | IllegalStateException ex) {
                  Log.w(TAG, "Navigation to library failed", ex);
                }
                return;
              }
              // Document switching (Open/New/Close) is blocked during a running OCR batch.
              if (ocrBatchController != null && ocrBatchController.isRunning()) {
                UIUtils.showToast(
                    requireContext(),
                    getString(R.string.ocr_batch_already_running),
                    Toast.LENGTH_SHORT);
                return;
              }
              if (which == 0) {
                try {
                  Navigation.findNavController(requireView())
                      .navigate(R.id.navigation_saved_documents);
                } catch (IllegalArgumentException | IllegalStateException ex) {
                  Log.w(TAG, "Navigation to saved documents failed", ex);
                }
              } else {
                // New (1) and Close (2): both keep the current session persisted (Close ≠
                // Delete), clear the active pointer and the runtime session, then return to the
                // camera. "New" starts a fresh document lazily with the next page.
                closeDocumentAndReturnToCamera();
              }
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  /**
   * Session 4 "Close document" / "New document": the persisted DocumentSession is kept (empty
   * drafts are cleaned up by the repository), the active pointer is cleared, the runtime session is
   * emptied and the user returns to the camera start state.
   */
  private void closeDocumentAndReturnToCamera() {
    Context c = getContext();
    if (c == null) return;
    final Context app = c.getApplicationContext();
    if (exportSessionViewModel != null) {
      exportSessionViewModel.setDocumentId(null);
      exportSessionViewModel.setInitial(null);
    }
    if (exportViewModel != null) {
      exportViewModel.setDocumentBitmap(null);
      exportViewModel.setDocumentReady(false);
    }
    documentSessionExecutor.execute(
        () -> {
          try {
            de.schliweb.makeacopy.data.DocumentSessionRepository.get(app).endActiveSession(false);
          } catch (Throwable t) {
            Log.w(TAG, "DocumentSession close failed", t);
          }
        });
    UIUtils.showToast(requireContext(), getString(R.string.doc_closed_toast), Toast.LENGTH_SHORT);
    try {
      Navigation.findNavController(requireView()).navigate(R.id.navigation_camera);
    } catch (IllegalArgumentException | IllegalStateException ex) {
      Log.w(TAG, "Navigation to camera failed", ex);
    }
  }

  // Insert-Hook implementation: persist a newly added CompletedScan to app storage and registry
  private void persistCompletedScanAsync(de.schliweb.makeacopy.ui.export.session.CompletedScan s) {
    if (s == null || s.id() == null || s.inMemoryBitmap() == null) return;
    final android.content.Context appContext = requireContext().getApplicationContext();
    final String id = s.id();
    // Respect user preference: Skip OCR (export only)
    boolean skipOcrPref = ExportPrefsHelper.isSkipOcr(requireContext());
    // Capture current in-memory OCR text/words at call time unless Skip OCR is enabled
    final String ocrTextAtCall = skipOcrPref ? null : getOcrTextFromState();
    final java.util.List<RecognizedWord> ocrWordsAtCall =
        skipOcrPref ? null : getOcrWordsFromState();
    new Thread(
            () -> {
              try {
                // Persist scan (page.jpg, thumb.jpg, and optional OCR artifacts) and insert into
                // registry
                de.schliweb.makeacopy.ui.export.session.CompletedScan persisted =
                    ScanPersister.persist(appContext, s, ocrTextAtCall, ocrWordsAtCall);

                // Update current session item so the filmstrip badge reflects OCR immediately
                final String finalOcrPath = persisted.ocrTextPath();
                final String finalOcrFormat = persisted.ocrFormat();
                postToUiSafe(
                    () -> {
                      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
                          exportSessionViewModel.getPages().getValue();
                      if (cur == null) return;
                      for (int i = 0; i < cur.size(); i++) {
                        de.schliweb.makeacopy.ui.export.session.CompletedScan it = cur.get(i);
                        if (it != null && id.equals(it.id())) {
                          de.schliweb.makeacopy.ui.export.session.CompletedScan updated =
                              new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                                  it.id(),
                                  persisted.filePath(),
                                  it.rotationDeg(),
                                  finalOcrPath,
                                  finalOcrFormat,
                                  it.thumbPath() != null ? it.thumbPath() : persisted.thumbPath(),
                                  it.createdAt(),
                                  it.widthPx(),
                                  it.heightPx(),
                                  it.inMemoryBitmap(),
                                  persisted.schemaVersion(),
                                  persisted.orientationMode());
                          exportSessionViewModel.updateAt(i, updated);
                          break;
                        }
                      }
                    });
              } catch (Exception e) {
                Log.w(TAG, "Persist scan failed", e);
              }
            })
        .start();
  }

  /**
   * Shares the last exported document along with an optional corresponding text (TXT) file if
   * available.
   *
   * <p>This method prepares an Intent to share the exported document, ensuring compatibility with
   * PDF and TXT file formats. If the last exported document URI is null, it displays a message
   * notifying the user to export a document before attempting to share.
   *
   * <p>Key functionality: - Validates the presence of a document to share. Displays a notification
   * if no document is available. - Retrieves the file name and optionally locates a corresponding
   * TXT file for sharing. - Configures the sharing intent based on the presence or absence of a TXT
   * file: - If a TXT file exists, prepares a multiple-file sharing intent including both the PDF
   * and TXT files. - If no TXT file exists, prepares a single-file sharing intent for the PDF file
   * only. - Handles file URIs consistently, using a file provider if necessary to ensure secure
   * content access. - Sets intent details such as the title, subject, shared text, and necessary
   * permissions. - Initiates a system UI to allow users to choose a sharing destination from
   * available apps.
   *
   * <p>Includes robust error handling to catch and log exceptions, displaying appropriate user
   * feedback when sharing fails.
   */
  private void runInlineOcrForPage(int position) {
    List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
        exportSessionViewModel.getPages().getValue();
    if (cur == null || position < 0 || position >= cur.size()) return;
    de.schliweb.makeacopy.ui.export.session.CompletedScan s = cur.get(position);
    if (s == null) return;
    // Enqueue background OCR job for this page id. UI will be updated when the job broadcasts
    // completion.
    UIUtils.showToast(
        requireContext(), getString(R.string.ocr_processing_started), Toast.LENGTH_SHORT);
    de.schliweb.makeacopy.jobs.OcrBackgroundJobs.enqueueReprocess(
        requireContext().getApplicationContext(),
        s.id(),
        resolveOcrLanguage(),
        () -> ocrHelperProvider.get());
  }

  /** SharedPreferences file shared with the OCR screen's language selection. */
  private static final String OCR_PREFS_NAME = "export_options";

  /** Preference key for the persisted OCR language spec (kept in sync with OCRFragment). */
  private static final String PREF_KEY_OCR_LANG = "ocr_language";

  /** Maximum number of languages selectable for multi-language OCR (Tesseract flavor). */
  private static final int MAX_OCR_LANGUAGES = 2;

  /**
   * Resolves the OCR language used for inline/batch OCR: ViewModel state first, then the persisted
   * preference from the OCR screen, finally a sensible system-based default.
   */
  private String resolveOcrLanguage() {
    de.schliweb.makeacopy.ui.ocr.OCRViewModel.OcrUiState st = ocrViewModel.getState().getValue();
    String lang = (st != null && st.language() != null) ? st.language() : null;
    if (lang == null || lang.trim().isEmpty()) {
      try {
        lang =
            requireContext()
                .getSharedPreferences(OCR_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY_OCR_LANG, null);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
    if (lang == null || lang.trim().isEmpty()) {
      lang = OCRUtils.resolveEffectiveLanguage(lang);
    }
    return lang;
  }

  /**
   * Persists the given OCR language spec (preference + ViewModel) so that subsequent inline/batch
   * OCR runs use it, then re-opens the OCR batch options for the given page.
   */
  private void applyOcrLanguage(String langSpec, int position) {
    if (langSpec == null || langSpec.trim().isEmpty()) return;
    ocrViewModel.setLanguage(langSpec);
    try {
      requireContext()
          .getSharedPreferences(OCR_PREFS_NAME, Context.MODE_PRIVATE)
          .edit()
          .putString(PREF_KEY_OCR_LANG, langSpec)
          .apply();
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    showOcrBatchOptions(position);
  }

  /**
   * Lets the user change the OCR language directly from the export screen (multi-page sessions have
   * no other way to reach the OCR screen's language selection). Mirrors OCRFragment: a
   * single-choice dialog for the PaddleOCR flavor, a multi-choice dialog (max. {@link
   * #MAX_OCR_LANGUAGES}) for the Tesseract flavor.
   */
  private void showOcrLanguagePicker(int position) {
    String[] resolvedCodes = null;
    try {
      resolvedCodes = OcrModelManager.getAvailableLanguageCodes(requireContext());
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    final String[] codes =
        (resolvedCodes != null && resolvedCodes.length > 0)
            ? resolvedCodes
            : OCRUtils.getLanguages();
    final String[] displayNames = new String[codes.length];
    for (int i = 0; i < codes.length; i++) {
      displayNames[i] = OCRUtils.codeToDisplayName(requireContext(), codes[i]);
    }
    final List<String> selected = new ArrayList<>();
    String current = resolveOcrLanguage();
    if (current != null) {
      for (String part : current.split("\\+", -1)) {
        String trimmed = part.trim();
        if (!trimmed.isEmpty() && !selected.contains(trimmed)) selected.add(trimmed);
      }
    }
    if (de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR) {
      int checkedItem = -1;
      if (!selected.isEmpty()) {
        for (int i = 0; i < codes.length; i++) {
          if (codes[i].equals(selected.get(0))) {
            checkedItem = i;
            break;
          }
        }
      }
      new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.select_ocr_languages)
          .setSingleChoiceItems(
              displayNames,
              checkedItem,
              (dlg, which) -> {
                dlg.dismiss();
                applyOcrLanguage(codes[which], position);
              })
          .setNegativeButton(android.R.string.cancel, null)
          .show();
    } else {
      final boolean[] checked = new boolean[codes.length];
      for (int i = 0; i < codes.length; i++) {
        checked[i] = selected.contains(codes[i]);
      }
      new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.select_ocr_languages)
          .setMultiChoiceItems(
              displayNames,
              checked,
              (dlg, which, isChecked) -> {
                if (isChecked) {
                  int count = 0;
                  for (boolean b : checked) if (b) count++;
                  if (count > MAX_OCR_LANGUAGES) {
                    ((androidx.appcompat.app.AlertDialog) dlg)
                        .getListView()
                        .setItemChecked(which, false);
                    checked[which] = false;
                    UIUtils.showToast(
                        requireContext(),
                        getString(R.string.ocr_max_languages_warning),
                        Toast.LENGTH_SHORT);
                  }
                }
              })
          .setPositiveButton(
              android.R.string.ok,
              (dlg, w) -> {
                List<String> chosen = new ArrayList<>();
                for (int i = 0; i < codes.length; i++) {
                  if (checked[i]) chosen.add(codes[i]);
                }
                if (chosen.isEmpty()) {
                  UIUtils.showToast(
                      requireContext(),
                      getString(R.string.ocr_no_language_selected),
                      Toast.LENGTH_SHORT);
                  return;
                }
                applyOcrLanguage(String.join("+", chosen), position);
              })
          .setNegativeButton(android.R.string.cancel, null)
          .show();
    }
  }

  /**
   * Entry point for the per-page OCR badge. For single-page sessions this behaves like before
   * (inline OCR for the tapped page). For multi-page sessions the user can choose between OCR for
   * the current page, a page selection, all pages ("OCR all" skips pages that already have a
   * complete OCR result), or changing the OCR language used for these runs.
   */
  private void showOcrBatchOptions(int position) {
    List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
        exportSessionViewModel.getPages().getValue();
    if (cur == null || cur.size() <= 1) {
      runInlineOcrForPage(position);
      return;
    }
    if (ocrBatchController != null && ocrBatchController.isRunning()) {
      UIUtils.showToast(
          requireContext(), getString(R.string.ocr_batch_already_running), Toast.LENGTH_SHORT);
      return;
    }
    String[] options =
        new String[] {
          getString(R.string.ocr_batch_option_current),
          getString(R.string.ocr_batch_option_selected),
          getString(R.string.ocr_batch_option_all),
          getString(R.string.ocr_batch_option_language, resolveOcrLanguage())
        };
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.ocr_batch_title)
        .setItems(
            options,
            (dlg, which) -> {
              if (which == 0) {
                runInlineOcrForPage(position);
              } else if (which == 1) {
                showOcrPagePicker(position);
              } else if (which == 2) {
                startOcrAll();
              } else {
                showOcrLanguagePicker(position);
              }
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  /** Multi-choice page picker for "OCR selected pages" (reuses the picker UX pattern). */
  private void showOcrPagePicker(int preselectedPosition) {
    List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
        exportSessionViewModel.getPages().getValue();
    if (cur == null || cur.isEmpty()) return;
    final int n = cur.size();
    final String[] labels = new String[n];
    final boolean[] checked = new boolean[n];
    for (int i = 0; i < n; i++) {
      labels[i] = getString(R.string.page_n_of_m, i + 1, n);
      checked[i] = (i == preselectedPosition);
    }
    final List<de.schliweb.makeacopy.ui.export.session.CompletedScan> snapshot =
        new ArrayList<>(cur);
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.ocr_batch_select_pages)
        .setMultiChoiceItems(labels, checked, (dlg, which, isChecked) -> checked[which] = isChecked)
        .setPositiveButton(
            R.string.ocr_batch_title,
            (dlg, w) -> {
              List<String> ids = new ArrayList<>();
              for (int i = 0; i < n; i++) {
                de.schliweb.makeacopy.ui.export.session.CompletedScan s = snapshot.get(i);
                if (checked[i] && s != null && s.id() != null) ids.add(s.id());
              }
              if (!ids.isEmpty()) startOcrBatch(ids);
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  /**
   * Starts "OCR all": processes every session page that does not yet have a complete OCR result.
   * Pages with {@code OCR_COMPLETE} status are skipped by design (re-OCR remains available via the
   * explicit per-page/selected actions).
   */
  private void startOcrAll() {
    List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
        exportSessionViewModel.getPages().getValue();
    if (cur == null || cur.isEmpty()) return;
    List<String> ids = new ArrayList<>();
    for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : cur) {
      if (s == null || s.id() == null) continue;
      if (de.schliweb.makeacopy.ui.export.session.CompletedScan.STATUS_OCR_COMPLETE.equals(
          s.pageStatus())) {
        continue; // already has OCR → skip by default
      }
      ids.add(s.id());
    }
    if (ids.isEmpty()) {
      UIUtils.showToast(
          requireContext(), getString(R.string.ocr_batch_nothing_to_do), Toast.LENGTH_SHORT);
      return;
    }
    startOcrBatch(ids);
  }

  /** Starts a sequential OCR batch over the given stable page ids. */
  private void startOcrBatch(List<String> pageIds) {
    if (pageIds == null || pageIds.isEmpty()) return;
    if (ocrBatchController != null && ocrBatchController.isRunning()) {
      UIUtils.showToast(
          requireContext(), getString(R.string.ocr_batch_already_running), Toast.LENGTH_SHORT);
      return;
    }
    final Context appContext = requireContext().getApplicationContext();
    final String lang = resolveOcrLanguage();

    ocrBatchController =
        new de.schliweb.makeacopy.jobs.OcrBatchController(
            new de.schliweb.makeacopy.jobs.OcrBatchController.JobStarter() {
              @Override
              public void startOcr(String pageId) {
                de.schliweb.makeacopy.jobs.OcrBackgroundJobs.enqueueReprocess(
                    appContext, pageId, lang, () -> ocrHelperProvider.get());
              }

              @Override
              public void cancelOcr(String pageId) {
                de.schliweb.makeacopy.jobs.OcrBackgroundJobs.cancel(pageId);
              }
            },
            pageId -> {
              // Deleted pages are skipped: the batch works with stable ids, not positions.
              List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
                  exportSessionViewModel.getPages().getValue();
              if (pages == null) return false;
              for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : pages) {
                if (s != null && pageId.equals(s.id())) return true;
              }
              return false;
            },
            new de.schliweb.makeacopy.jobs.OcrBatchController.Listener() {
              @Override
              public void onPageStarted(String pageId, int pos, int total) {
                postToUiSafe(() -> updateOcrBatchProgress(pos, total));
              }

              @Override
              public void onPageFinished(String pageId, boolean success, int finished, int total) {
                if (!success) {
                  postToUiSafe(() -> markSessionPageOcrFailed(pageId));
                }
              }

              @Override
              public void onBatchFinished(
                  de.schliweb.makeacopy.jobs.OcrBatchController.Summary summary) {
                postToUiSafe(() -> onOcrBatchFinished(summary));
              }
            });
    showOcrBatchProgressDialog(pageIds.size());
    ocrBatchController.start(pageIds);
  }

  /** Reflects a persisted OCR failure in the in-memory session entry (badge/status). */
  private void markSessionPageOcrFailed(String pageId) {
    if (exportSessionViewModel == null || pageId == null) return;
    List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
        exportSessionViewModel.getPages().getValue();
    if (cur == null) return;
    for (int i = 0; i < cur.size(); i++) {
      de.schliweb.makeacopy.ui.export.session.CompletedScan it = cur.get(i);
      if (it != null && pageId.equals(it.id())) {
        exportSessionViewModel.updateAt(
            i,
            new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                it.id(),
                it.filePath(),
                it.rotationDeg(),
                it.ocrTextPath(),
                it.ocrFormat(),
                it.thumbPath(),
                it.createdAt(),
                it.widthPx(),
                it.heightPx(),
                it.inMemoryBitmap(),
                it.schemaVersion(),
                it.orientationMode(),
                it.sourceType(),
                it.pdfPageIndex(),
                de.schliweb.makeacopy.ui.export.session.CompletedScan.STATUS_OCR_FAILED));
        break;
      }
    }
  }

  private void showOcrBatchProgressDialog(int total) {
    dismissOcrBatchProgressDialog();
    View v =
        LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_pdf_import_progress, null, false);
    android.widget.TextView title = v.findViewById(R.id.import_progress_title);
    title.setText(R.string.ocr_batch_running_title);
    ocrBatchProgressBar = v.findViewById(R.id.import_progress_bar);
    ocrBatchProgressBar.setMax(total);
    ocrBatchProgressBar.setProgress(0);
    ocrBatchProgressLabel = v.findViewById(R.id.import_progress_label);
    ocrBatchProgressLabel.setText(getString(R.string.page_n_of_m, 1, total));
    ocrBatchProgressDialog =
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(v)
            .setCancelable(false)
            .setNegativeButton(
                android.R.string.cancel,
                (dlg, w) -> {
                  if (ocrBatchController != null) ocrBatchController.cancel();
                })
            .create();
    ocrBatchProgressDialog.setOnShowListener(
        dlg ->
            DialogUtils.improveAlertDialogButtonContrastForNight(
                ocrBatchProgressDialog, requireContext()));
    ocrBatchProgressDialog.show();
  }

  private void updateOcrBatchProgress(int pos, int total) {
    if (ocrBatchProgressBar != null) {
      ocrBatchProgressBar.setMax(total);
      ocrBatchProgressBar.setProgress(Math.max(0, pos - 1));
    }
    if (ocrBatchProgressLabel != null) {
      ocrBatchProgressLabel.setText(getString(R.string.page_n_of_m, pos, total));
    }
  }

  private void dismissOcrBatchProgressDialog() {
    if (ocrBatchProgressDialog != null) {
      try {
        ocrBatchProgressDialog.dismiss();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      ocrBatchProgressDialog = null;
    }
    ocrBatchProgressBar = null;
    ocrBatchProgressLabel = null;
  }

  /** Shows the batch summary and offers "Retry failed" when applicable. */
  private void onOcrBatchFinished(de.schliweb.makeacopy.jobs.OcrBatchController.Summary summary) {
    dismissOcrBatchProgressDialog();
    if (!isAdded()) return;
    if (summary.cancelled) {
      UIUtils.showToast(
          requireContext(), getString(R.string.ocr_batch_cancelled), Toast.LENGTH_SHORT);
      return;
    }
    String msg = getString(R.string.ocr_batch_summary, summary.succeeded, summary.failed);
    if (summary.skipped > 0) {
      msg += "\n" + getString(R.string.ocr_batch_summary_skipped, summary.skipped);
    }
    com.google.android.material.dialog.MaterialAlertDialogBuilder b =
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ocr_batch_finished_title)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null);
    if (summary.failed > 0) {
      b.setNeutralButton(
          R.string.ocr_batch_retry_failed,
          (dlg, w) -> {
            if (ocrBatchController != null) {
              List<String> failedIds = ocrBatchController.getLastFailedPageIds();
              if (!failedIds.isEmpty()) {
                showOcrBatchProgressDialog(failedIds.size());
                ocrBatchController.retryFailed();
              }
            }
          });
    }
    androidx.appcompat.app.AlertDialog dialog = b.create();
    dialog.setOnShowListener(
        dlg -> DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext()));
    dialog.show();
  }

  /**
   * Enables or disables the share buttons within the UI.
   *
   * @param enabled a boolean indicating whether the share buttons should be enabled (true) or
   *     disabled (false)
   */
  private void setShareButtonsEnabled(boolean enabled) {
    if (binding != null) {
      if (binding.buttonShareSmall != null) binding.buttonShareSmall.setEnabled(enabled);
    }
  }

  /**
   * Shares the last exported document using an appropriate sharing intent.
   *
   * <p>This method checks if there is a document available to share. If no document is found, a
   * message is displayed to the user indicating that they need to export a document first.
   *
   * <p>If a document exists, the method attempts to retrieve the file name and uses a helper class
   * to initiate the sharing process. It handles any exceptions that may occur during the sharing
   * operation by logging the error and showing a corresponding error message to the user.
   *
   * <p>Preconditions: - The method assumes that the `lastExportedDocumentUri` refers to the URI of
   * the last successfully exported document. - The `exportViewModel` is expected to provide the URI
   * for exporting the document in TXT format. - Helper utilities such as FileUtils and
   * ShareIntentHelper should be functional and imported.
   *
   * <p>Postconditions: - Either the sharing intent is successfully triggered, or the user is
   * notified of any errors or missing documents.
   *
   * <p>Error Handling: - Displays a toast message to the user if no document is available to share.
   * - Logs and displays a toast message for any exceptions encountered during the sharing process.
   */
  private void shareDocument() {
    if (lastExportedDocumentUri == null) {
      UIUtils.showToast(
          requireContext(),
          getString(R.string.no_document_to_share_export_first),
          Toast.LENGTH_SHORT);
      return;
    }
    try {
      String fileName = FileUtils.getDisplayNameFromUri(requireContext(), lastExportedDocumentUri);
      Uri txtUri = exportViewModel.getTxtExportUri().getValue();
      ShareIntentHelper.shareDocument(this, lastExportedDocumentUri, txtUri, fileName);
    } catch (android.content.ActivityNotFoundException e) {
      Log.w(TAG, "No activity found to handle share intent", e);
      UIUtils.showToast(
          requireContext(),
          getString(
              R.string.error_sharing_document_with_reason,
              getString(R.string.no_app_found_to_open_file)),
          Toast.LENGTH_SHORT);
    } catch (SecurityException e) {
      Log.e(TAG, "SecurityException during share", e);
      UIUtils.showToast(
          requireContext(),
          getString(R.string.error_sharing_document_with_reason, e.getMessage()),
          Toast.LENGTH_SHORT);
    } catch (IllegalArgumentException | IllegalStateException e) {
      Log.e(TAG, "Share failed due to illegal state/arg", e);
      UIUtils.showToast(
          requireContext(),
          getString(R.string.error_sharing_document_with_reason, e.getMessage()),
          Toast.LENGTH_SHORT);
    }
  }

  @Override
  public void onStart() {
    super.onStart();
    if (!ocrReceiverRegistered) {
      android.content.Context app = requireContext().getApplicationContext();
      android.content.IntentFilter filter =
          new android.content.IntentFilter(
              de.schliweb.makeacopy.jobs.OcrBackgroundJobs.ACTION_OCR_UPDATED);
      int flags = androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED;
      try {
        androidx.core.content.ContextCompat.registerReceiver(app, ocrUpdateReceiver, filter, flags);
        ocrReceiverRegistered = true;
      } catch (IllegalArgumentException | SecurityException e) {
        Log.w(TAG, "Failed to register OCR update receiver", e);
      }
    }
  }

  @Override
  public void onStop() {
    if (ocrReceiverRegistered) {
      android.content.Context app = requireContext().getApplicationContext();
      try {
        app.unregisterReceiver(ocrUpdateReceiver);
      } catch (IllegalArgumentException | SecurityException e) {
        Log.w(TAG, "Failed to unregister OCR update receiver", e);
      }
      ocrReceiverRegistered = false;
    }
    super.onStop();
  }

  @Override
  public void onDestroyView() {
    previewRenderGeneration++;
    previewPageLoadGeneration++;
    super.onDestroyView();
    binding = null;
  }

  @Override
  public void onDestroy() {
    previewExecutor.shutdownNow();
    super.onDestroy();
  }

  /**
   * Retrieves the effective OCR text from the current state managed by the `ocrViewModel`.
   *
   * <p>This method returns the reviewed text if available, otherwise the original OCR text. This
   * ensures that any edits made in the Review screen are used for export.
   *
   * @return The effective OCR text (reviewed if available, otherwise original), or null if the
   *     state is unavailable.
   */
  private String getOcrTextFromState() {
    OCRViewModel.OcrUiState s = ocrViewModel.getState().getValue();
    return (s != null) ? s.getEffectiveText() : null;
  }

  /**
   * Retrieves the effective list of recognized words from the current OCR state.
   *
   * <p>This method returns the reviewed words if available, otherwise the original OCR words. This
   * ensures that any edits made in the Review screen are used for export.
   *
   * @return The effective list of recognized words (reviewed if available, otherwise original), or
   *     null if the state is unavailable.
   */
  private List<RecognizedWord> getOcrWordsFromState() {
    OCRViewModel.OcrUiState s = ocrViewModel.getState().getValue();
    return (s != null) ? s.getEffectiveWords() : null;
  }

  @VisibleForTesting
  static List<RecognizedWord> ensurePdfTextLayerWords(
      List<RecognizedWord> words, String text, Bitmap bitmap) {
    if (words != null && !words.isEmpty()) return words;
    if (bitmap == null || text == null || text.trim().isEmpty()) return words;

    List<String> lines = new ArrayList<>();
    for (String rawLine : text.replace('\r', '\n').split("\\n+", -1)) {
      String line = rawLine != null ? rawLine.trim() : "";
      if (!line.isEmpty()) lines.add(line);
    }
    if (lines.isEmpty()) return words;

    int width = Math.max(1, bitmap.getWidth());
    int height = Math.max(1, bitmap.getHeight());
    float marginX = width * 0.08f;
    float top = height * 0.10f;
    float usableHeight = height * 0.80f;
    float lineHeight = Math.max(24f, usableHeight / Math.max(lines.size(), 1));
    float boxHeight = Math.max(16f, lineHeight * 0.70f);
    List<RecognizedWord> fallback = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      float lineTop = Math.min(height - 1f, top + i * lineHeight);
      float lineBottom = Math.min(height, lineTop + boxHeight);
      fallback.add(
          new RecognizedWord(
              lines.get(i),
              new android.graphics.RectF(marginX, lineTop, width - marginX, lineBottom),
              0f));
    }
    Log.w(TAG, "Using OCR text fallback for PDF text layer, lines=" + fallback.size());
    return fallback;
  }

  private void indexScanLibraryAsync(String title, int pageCount, Uri exportUri) {
    ScanLibraryIndexer.indexAsync(
        requireContext().getApplicationContext(),
        scansRepository,
        title,
        pageCount,
        exportUri,
        getOcrTextFromState(),
        buildDefaultBaseName());
  }
}
