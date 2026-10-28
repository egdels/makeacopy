/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.ocr;

import android.app.Activity;
import android.content.Intent;
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
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import dagger.hilt.android.AndroidEntryPoint;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentOcrBinding;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.ui.options.OptionsDialogFragment;
import de.schliweb.makeacopy.utils.image.ImageLoader;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.infra.IncomingUriPolicy;
import de.schliweb.makeacopy.utils.ocr.*;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import de.schliweb.makeacopy.utils.ui.HapticsUtils;
import de.schliweb.makeacopy.utils.ui.TransitionUtils;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import javax.inject.Inject;
import javax.inject.Provider;

/**
 * OCRFragment handles the Optical Character Recognition (OCR) functionality within the application.
 * This fragment manages UI and orchestration; the actual OCR work is done on a dedicated
 * single-thread executor with a fresh PaddleOCR helper per job.
 *
 * <p>Flow: Crop -> (optional) User Rotation -> OCR -> Export
 */
@AndroidEntryPoint
public class OCRFragment extends Fragment {

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    TransitionUtils.applySharedAxisX(this);
  }

  private static final String TAG = "OCRFragment";
  // Early-exit thresholds are now centralized in OcrEarlyExitPolicy. The previous
  // "meanConf >= 55" gate was too lenient and would early-exit on tiny garbage
  // results (e.g. 12 words / meanConf 55 / textLen 52), preventing recovery via
  // further rotation attempts and the layout-analysis full-page fallback. The new
  // policy also requires a minimum word count and a minimum text length.

  private FragmentOcrBinding binding;

  // Tracks the previous OCR processing state to emit a haptic tick on completion
  private boolean wasOcrProcessing;
  private OCRViewModel ocrViewModel;
  private CropViewModel cropViewModel;

  // Track last observed image to decide when to reset OCR state
  private Bitmap lastObservedBitmap;

  // Language helper for listing/availability checks
  private OCRHelper langHelper;

  @Inject Provider<OCRHelper> ocrHelperProvider;

  // Concurrency: serialize OCR jobs, 1 job ↔ 1 OCRHelper
  private final ExecutorService ocrExecutor = Executors.newSingleThreadExecutor();
  private volatile Future<?> runningOcr = null;
  private final AtomicBoolean ocrCancelled = new AtomicBoolean(false);

  public static final String BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT =
      OcrOptionsPanel.BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT;
  public static final String BUNDLE_PADDLE_BEST_OCR = OcrOptionsPanel.BUNDLE_PADDLE_BEST_OCR;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    ocrViewModel = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
    cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
    binding = FragmentOcrBinding.inflate(inflater, container, false);
    View root = binding.getRoot();

    ensureBitmapWhenOpenedDirectly();
    rememberInitialImage();

    // Language helper for listing/availability checks
    langHelper = ocrHelperProvider.get();

    observeViewModels();
    setupInsets(root);
    setupBackNavigation();
    setupActionButtons();

    // Language selection
    setupLanguageSpinner();

    return root;
  }

  /** If OCR was opened directly (skipping Crop), ensure we have a bitmap in CropViewModel. */
  private void ensureBitmapWhenOpenedDirectly() {
    try {
      if (cropViewModel.getImageBitmap().getValue() == null) {
        de.schliweb.makeacopy.ui.camera.CameraViewModel camVm =
            new ViewModelProvider(requireActivity())
                .get(de.schliweb.makeacopy.ui.camera.CameraViewModel.class);
        String path = camVm.getImagePath() != null ? camVm.getImagePath().getValue() : null;
        android.net.Uri uri = camVm.getImageUri() != null ? camVm.getImageUri().getValue() : null;
        android.graphics.Bitmap bmp = ImageLoader.decode(requireContext(), path, uri);
        if (bmp != null) {
          cropViewModel.setImageBitmap(bmp);
        }
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * On first entry, if we have an image and no OCR results yet, remember this image. Do NOT reset
   * if we already have OCR results (e.g., returning from Review screen).
   */
  private void rememberInitialImage() {
    try {
      Bitmap cur = cropViewModel.getImageBitmap().getValue();
      if (cur != null) {
        lastObservedBitmap = cur;
        // Only reset if no OCR has been performed yet for this image
        OCRViewModel.OcrUiState currentState = ocrViewModel.getState().getValue();
        boolean hasOcrResults = currentState != null && currentState.imageProcessed();
        if (!hasOcrResults) {
          ocrViewModel.resetForNewImage();
        }
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  private void observeViewModels() {
    ocrViewModel.getState().observe(getViewLifecycleOwner(), this::renderState);

    // Error events
    ocrViewModel
        .getErrorEvents()
        .observe(
            getViewLifecycleOwner(),
            ev -> {
              if (ev == null) return;
              String msg = ev.getContentIfNotHandled();
              if (msg != null)
                UIUtils.showToast(requireContext(), "OCR failed: " + msg, Toast.LENGTH_LONG);
            });

    // When image changes in Crop VM, reset OCR state if it's a different image than last time
    cropViewModel
        .getImageBitmap()
        .observe(
            getViewLifecycleOwner(),
            bitmap -> {
              if (bitmap != null) {
                if (bitmap != lastObservedBitmap) {
                  lastObservedBitmap = bitmap;
                  ocrViewModel.resetForNewImage();
                }
              }
            });
  }

  private void renderState(OCRViewModel.OcrUiState state) {
    boolean finished = state.imageProcessed() && !state.processing();
    // Haptic confirmation when OCR processing finishes
    if (wasOcrProcessing && finished) {
      HapticsUtils.vibrateOneShot(getContext(), 30L);
    }
    wasOcrProcessing = state.processing();
    binding.buttonProcess.setEnabled(finished);
    binding.buttonProcess.setText(R.string.next);

    binding.textOcr.setText(
        state.processing()
            ? getString(R.string.processing_image)
            : (state.imageProcessed()
                ? getString(R.string.ocr_processing_complete_tap_the_button_to_proceed_to_export)
                : getString(R.string.no_image_processed_crop_an_image_first)));

    // Use effective text (reviewed if available, otherwise original OCR)
    String effectiveText = state.getEffectiveText();
    binding.ocrResultText.setText(
        (effectiveText == null || effectiveText.isEmpty())
            ? getString(R.string.ocr_results_will_appear_here)
            : effectiveText);

    // Enable review button only when OCR finished and we have words (and feature enabled)
    if (!FeatureFlags.isOcrReviewEnabled()) {
      // When feature is disabled, hide the review button completely
      binding.buttonOcrReview.setVisibility(View.GONE);
    } else {
      boolean hasWords = state.words() != null && !state.words().isEmpty();
      setEnabledWithAlpha(binding.buttonOcrReview, finished && hasWords);
      binding.buttonOcrReview.setVisibility(View.VISIBLE);
    }

    // Enable share button only when OCR finished and there is text to share
    boolean hasText = effectiveText != null && !effectiveText.trim().isEmpty();
    setEnabledWithAlpha(binding.buttonOcrShare, finished && hasText);

    // Disable settings (OCR options) button while processing is running
    setEnabledWithAlpha(binding.buttonOcrOptions, !state.processing());

    // Proceed to Export
    binding.buttonProcess.setOnClickListener(v -> navigateToExport());
  }

  private static void setEnabledWithAlpha(View button, boolean enabled) {
    button.setEnabled(enabled);
    button.setAlpha(enabled ? 1f : 0.4f);
  }

  private void setupInsets(View root) {
    // Insets (status bar)
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
          ViewGroup.MarginLayoutParams textParams =
              (ViewGroup.MarginLayoutParams) binding.textOcr.getLayoutParams();
          textParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density) + topInset;
          binding.textOcr.setLayoutParams(textParams);
          return insets;
        });

    // Bottom inset for button container
    UIUtils.applyBottomBarInsets(binding.buttonContainer);
    ViewCompat.setOnApplyWindowInsetsListener(
        binding.buttonContainer,
        (v, insets) -> {
          UIUtils.applyBottomBarInsets(binding.buttonContainer);
          return insets;
        });
  }

  /** The Back button and system back (gesture/hardware) both return to Crop the same way. */
  private void setupBackNavigation() {
    binding.buttonBack.setOnClickListener(v -> navigateBackToCrop());
    OnBackPressedCallback backCallback =
        new OnBackPressedCallback(true) {
          @Override
          public void handleOnBackPressed() {
            navigateBackToCrop();
          }
        };
    requireActivity()
        .getOnBackPressedDispatcher()
        .addCallback(getViewLifecycleOwner(), backCallback);
  }

  /** Navigates to Crop reliably: try to pop back stack, otherwise navigate explicitly. */
  private void navigateBackToCrop() {
    try {
      // Prevent immediate auto-forward from Crop by resetting cropped state and restoring
      // original
      try {
        cropViewModel.setImageCropped(false);
        cropViewModel.setUserRotationDegrees(0);

        Bitmap orig = cropViewModel.getOriginalImageBitmap().getValue();
        if (orig != null) cropViewModel.setImageBitmap(orig);
      } catch (Throwable ignoreSet) {
        // Best-effort; failure is non-critical
      }
      androidx.navigation.NavController nav = Navigation.findNavController(requireView());
      boolean popped = nav.popBackStack();
      if (!popped) {
        nav.navigate(R.id.navigation_crop);
      }
    } catch (Throwable ignore) {
      try {
        Navigation.findNavController(requireView()).navigate(R.id.navigation_crop);
      } catch (Throwable ignored2) {
        // Best-effort; failure is non-critical
      }
    }
  }

  private void setupActionButtons() {
    // OCR options (settings) icon above the button bar
    binding.buttonOcrOptions.setOnClickListener(v -> showOcrOptionsDialog());
    // Share recognized text directly with other apps
    binding.buttonOcrShare.setOnClickListener(v -> shareOcrText());
    // OCR Review icon (optional, feature-flagged)
    if (!FeatureFlags.isOcrReviewEnabled()) {
      binding.buttonOcrReview.setVisibility(View.GONE);
      return;
    }
    binding.buttonOcrReview.setVisibility(View.VISIBLE);
    binding.buttonOcrReview.setOnClickListener(
        v -> {
          // Build OcrDoc from current OCR state and pass to Review VM
          de.schliweb.makeacopy.ui.ocr.review.OcrReviewViewModel rv =
              new ViewModelProvider(requireActivity())
                  .get(de.schliweb.makeacopy.ui.ocr.review.OcrReviewViewModel.class);
          OCRViewModel.OcrUiState s = ocrViewModel.getState().getValue();
          de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc doc =
              de.schliweb.makeacopy.ui.ocr.review.model.OcrDocMapper.fromState(s);
          rv.clearPage();
          rv.setDoc(doc);
          Navigation.findNavController(requireView()).navigate(R.id.navigation_review);
        });
  }

  private static final String PREFS_NAME = "export_options";

  private static final String PREF_KEY_OCR_LANG = "ocr_language";

  // Track currently selected language codes for multi-select
  private final List<String> selectedLanguageCodes = new ArrayList<>();

  private void setupLanguageSpinner() {
    MaterialButton dropdown = binding.languageSpinner;
    String[] codes = getAvailableLanguages();
    String[] displayNames = mapCodesToDisplayNames(codes);

    // Determine preferred language: saved preference (if available and installed) else system
    // default
    android.content.SharedPreferences sp =
        requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    String savedLangSpec = null;
    try {
      savedLangSpec = sp.getString(PREF_KEY_OCR_LANG, null);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }

    // Parse saved language spec (may contain multiple languages separated by +)
    selectedLanguageCodes.clear();
    if (savedLangSpec != null && !savedLangSpec.isEmpty()) {
      for (String lang : savedLangSpec.split("\\+", -1)) {
        String trimmed = lang.trim();
        if (!trimmed.isEmpty() && isLanguageAvailableSafe(trimmed)) {
          selectedLanguageCodes.add(trimmed);
        }
      }
    }

    // Fallback to system language if no valid saved selection
    if (selectedLanguageCodes.isEmpty()) {
      String defaultLang = resolveDefaultLanguageForDevice(codes);
      if (defaultLang != null) {
        selectedLanguageCodes.add(defaultLang);
      } else if (codes.length > 0) {
        selectedLanguageCodes.add(codes[0]);
      }
    }

    // Update dropdown display text
    updateLanguageDropdownText(dropdown, codes, displayNames);

    // Set initial language in ViewModel
    String langSpec = buildLangSpec();
    ocrViewModel.setLanguage(langSpec);

    // Initial auto-run logic (only if not already processed)
    Bitmap bitmap = cropViewModel.getImageBitmap().getValue();
    de.schliweb.makeacopy.ui.ocr.OCRViewModel.OcrUiState st0 = ocrViewModel.getState().getValue();
    boolean alreadyProcessed0 = (st0 != null && st0.imageProcessed());
    if (bitmap != null && !alreadyProcessed0) {
      performOCR();
    }

    // Set click listener to show language selection dialog
    dropdown.setOnClickListener(v -> showLanguageDialog(codes, displayNames, dropdown));
  }

  private String resolveDefaultLanguageForDevice(String[] codes) {
    if (codes == null || codes.length == 0) return null;

    String preferred = OCRUtils.mapSystemLanguageToPaddleModel(java.util.Locale.getDefault());
    if (isCodeAvailable(codes, preferred) && isLanguageAvailableSafe(preferred)) {
      return preferred;
    }

    if (isCodeAvailable(codes, "latin") && isLanguageAvailableSafe("latin")) {
      return "latin";
    }
    return null;
  }

  private static boolean isCodeAvailable(String[] codes, String code) {
    if (code == null || code.isEmpty()) return false;
    for (String available : codes) {
      if (code.equals(available)) return true;
    }
    return false;
  }

  private void showLanguageDialog(
      String[] codes, String[] displayNames, MaterialButton dropdown) {
    int checkedItem = -1;
    if (!selectedLanguageCodes.isEmpty()) {
      String selected = selectedLanguageCodes.get(0);
      for (int i = 0; i < codes.length; i++) {
        if (codes[i].equals(selected)) {
          checkedItem = i;
          break;
        }
      }
    }

    AlertDialog dlg =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_ocr_languages)
            .setSingleChoiceItems(
                displayNames,
                checkedItem,
                (dialog, which) -> {
                  String prevLang = ocrViewModel.getLanguage().getValue();
                  selectedLanguageCodes.clear();
                  selectedLanguageCodes.add(codes[which]);
                  applyLanguageSelection(dropdown, codes, displayNames, prevLang);
                  dialog.dismiss();
                })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
    dlg.setOnShowListener(
        d -> DialogUtils.improveAlertDialogButtonContrastForNight(dlg, requireContext()));
    dlg.show();
  }

  private void applyLanguageSelection(
      MaterialButton dropdown, String[] codes, String[] displayNames, String prevLang) {
    String newLangSpec = buildLangSpec();
    updateLanguageDropdownText(dropdown, codes, displayNames);
    ocrViewModel.setLanguage(newLangSpec);
    try {
      android.content.SharedPreferences sp =
          requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
      sp.edit().putString(PREF_KEY_OCR_LANG, newLangSpec).apply();
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }

    de.schliweb.makeacopy.ui.ocr.OCRViewModel.OcrUiState st = ocrViewModel.getState().getValue();
    boolean processed = (st != null && st.imageProcessed());
    boolean changed = !Objects.equals(prevLang, newLangSpec);
    if (processed && changed) {
      binding.buttonProcess.setText(R.string.btn_process);
      binding.buttonProcess.setOnClickListener(v -> performOCR());
    } else if (processed) {
      binding.buttonProcess.setText(R.string.next);
      binding.buttonProcess.setOnClickListener(v -> navigateToExport());
    }
  }

  /** Shares the current OCR text (reviewed text if available) with other apps via ACTION_SEND. */
  private void shareOcrText() {
    try {
      OCRViewModel.OcrUiState state = ocrViewModel.getState().getValue();
      String text = state != null ? state.getEffectiveText() : null;
      if (text == null || text.trim().isEmpty()) {
        UIUtils.showToast(
            requireContext(), getString(R.string.ocr_results_will_appear_here), Toast.LENGTH_SHORT);
        return;
      }
      Intent sendIntent = new Intent(Intent.ACTION_SEND);
      sendIntent.setType("text/plain");
      sendIntent.putExtra(Intent.EXTRA_TEXT, text);
      startActivity(Intent.createChooser(sendIntent, getString(R.string.btn_share_text)));
    } catch (Throwable t) {
      UIUtils.showToast(requireContext(), getString(R.string.share_failed), Toast.LENGTH_SHORT);
    }
  }

  /** Navigates to Export without retaining intermediate scan workflow fragments. */
  private void navigateToExport() {
    NavOptions navOptions =
        new NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setPopUpTo(R.id.navigation_camera, false)
            .build();
    Navigation.findNavController(requireView()).navigate(R.id.navigation_export, null, navOptions);
  }

  /** Builds the language specification string from selected languages (e.g., "deu+eng"). */
  private String buildLangSpec() {
    if (selectedLanguageCodes.isEmpty()) {
      return "eng"; // Fallback
    }
    return String.join("+", selectedLanguageCodes);
  }

  /** Updates the dropdown text to show selected languages. */
  private void updateLanguageDropdownText(
      MaterialButton dropdown, String[] codes, String[] displayNames) {
    if (selectedLanguageCodes.isEmpty()) {
      dropdown.setText(getString(R.string.label_language));
      return;
    }

    StringBuilder displayText = new StringBuilder();
    for (int i = 0; i < selectedLanguageCodes.size(); i++) {
      String code = selectedLanguageCodes.get(i);
      // Find display name for this code
      int idx =
          IntStream.range(0, codes.length)
              .filter(j -> codes[j].equals(code))
              .findFirst()
              .orElse(-1);
      if (idx >= 0) {
        if (displayText.length() > 0) displayText.append(" + ");
        displayText.append(displayNames[idx]);
      }
    }
    dropdown.setText(displayText.toString());
  }

  private boolean isLanguageAvailableSafe(String lang) {
    try {
      return langHelper != null && langHelper.isLanguageAvailable(lang);
    } catch (Throwable t) {
      return false;
    }
  }

  /** The language groups whose PaddleOCR model is bundled. */
  private String[] getAvailableLanguages() {
    return OcrModelManager.getAvailableLanguageCodes(requireContext());
  }

  private String[] mapCodesToDisplayNames(String[] codes) {
    String[] out = new String[codes.length];
    for (int i = 0; i < codes.length; i++) {
      out[i] = OCRUtils.codeToDisplayName(requireContext(), codes[i]);
    }
    return out;
  }

  /**
   * After models are added or removed, allow the user to restart OCR easily. This switches the
   * primary action to "Process" and wires it to performOCR().
   */
  private void prepareReprocessAfterModelChange() {
    try {
      Bitmap bmp = cropViewModel != null ? cropViewModel.getImageBitmap().getValue() : null;
      boolean hasImage = bmp != null;
      binding.buttonProcess.setText(R.string.btn_process);
      binding.buttonProcess.setEnabled(hasImage);
      binding.buttonProcess.setOnClickListener(v -> performOCR());
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * Opens the shared options dialog at the OCR group. When it is confirmed the primary action
   * becomes "Process" so that the user can re-run OCR with the new settings.
   */
  private void showOcrPrepModeDialog() {
    getParentFragmentManager()
        .setFragmentResultListener(
            OptionsDialogFragment.REQUEST_KEY,
            getViewLifecycleOwner(),
            (requestKey, bundle) -> {
              prepareReprocessAfterModelChange();
              getParentFragmentManager()
                  .clearFragmentResultListener(OptionsDialogFragment.REQUEST_KEY);
            });
    OptionsDialogFragment.show(getParentFragmentManager(), OptionsDialogFragment.Section.OCR);
  }

  /** Opens the OCR options. */
  private void showOcrOptionsDialog() {
    showOcrPrepModeDialog();
  }


  /**
   * Executes OCR in a single-thread executor with a fresh OCRHelper per job. Rotation handling:
   * capture-rotation compensation, then user rotation (after crop, before OCR). No write-back to
   * CropViewModel from OCR thread.
   */
  private void performOCR() {
    if (ocrExecutor.isShutdown()) {
      UIUtils.showToast(
          requireContext(), "Screen is closing, cannot start OCR", Toast.LENGTH_SHORT);
      Log.w(TAG, "performOCR: Executor is already shut down; aborting");
      return;
    }

    Bitmap imageBitmap = cropViewModel.getImageBitmap().getValue();
    Boolean croppedFlagAtStart = cropViewModel.isImageCropped().getValue();
    Integer userRotAtStart = cropViewModel.getUserRotationDegrees().getValue();
    // captureRotationDegrees value intentionally read to trigger LiveData observation
    Log.d(
        TAG,
        "performOCR: start on thread="
            + Thread.currentThread().getName()
            + ", imageBitmap="
            + (imageBitmap == null
                ? "null"
                : (imageBitmap.getWidth() + "x" + imageBitmap.getHeight()))
            + ", isImageCropped="
            + croppedFlagAtStart
            + ", userDeg="
            + userRotAtStart);
    if (imageBitmap == null) {
      UIUtils.showToast(requireContext(), "No image to process", Toast.LENGTH_SHORT);
      Log.w(TAG, "performOCR: No image present in CropViewModel");
      return;
    }

    // Prevent parallel runs
    if (runningOcr != null && !runningOcr.isDone()) {
      UIUtils.showToast(requireContext(), "OCR already running", Toast.LENGTH_SHORT);
      Log.w(TAG, "performOCR: A previous OCR task is still running; ignoring new request");
      return;
    }

    ocrViewModel.startProcessing();
    ocrCancelled.set(false);

    try {
      runningOcr = ocrExecutor.submit(() -> runOcrJob(imageBitmap));
    } catch (java.util.concurrent.RejectedExecutionException ex) {
      UIUtils.showToast(requireContext(), "OCR service is shutting down", Toast.LENGTH_SHORT);
      Log.w(TAG, "performOCR: RejectedExecutionException (executor shutting down)", ex);
      ocrViewModel.finishError("Executor shutdown");
    }
  }

  private static final String LP = "[OCR_LOG] ";

  /** Background part of {@link #performOCR()}; runs on the OCR executor thread. */
  private void runOcrJob(Bitmap imageBitmap) {
    long t0 = System.nanoTime();
    OCRHelper localHelper = null;
    try {
      Log.d(TAG, LP + "BG thread=" + Thread.currentThread().getName());
      // Prepare bitmap (orientation corrections)
      Log.d(TAG, LP + "Preparing image for OCR - orientation handling");

      // Note: We ignore capture/EXIF rotation here because the app is locked to portrait
      // (AndroidManifest: android:screenOrientation="portrait") and handles configChanges for
      // orientation/screenSize/screenLayout. In this setup, getCaptureRotationDegrees() is
      // effectively always 0 and evaluating it adds no value. If orientation handling changes in
      // the future, restore compensation here if needed.

      // Apply user-requested rotation (after crop, before OCR)
      Integer ur = cropViewModel.getUserRotationDegrees().getValue();
      Bitmap src = OcrRecognitionPipeline.rotateBitmap(imageBitmap, ur != null ? ur : 0);

      if (ocrCancelled.get()) {
        throw new OcrRecognitionPipeline.CancelledException("Cancelled before OCR init");
      }

      // Fresh engine per job
      localHelper = ocrHelperProvider.get();
      String lang = ocrViewModel.getLanguage().getValue();
      if (lang == null || lang.isEmpty()) lang = "latin";
      configureHelper(localHelper, lang);

      // Try OCR rotations (0, 90, 180, 270) only when Auto‑Rotate is enabled, to guard against
      // wrong user rotation. Otherwise, use the current orientation only.
      boolean allowOcrAutoRotate = readExportPref(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, false);
      OcrRecognitionPipeline.Outcome outcome =
          OcrRecognitionPipeline.recognizeBestRotation(
              localHelper,
              src,
              new OcrRecognitionPipeline.Options(allowOcrAutoRotate),
              ocrCancelled::get);

      OcrRecognitionPipeline.Attempt best = preferZeroRotationForVerticalText(outcome);
      if (best == null) {
        postError("OCR failed (no result)");
        return;
      }
      OCRHelper.OcrResultWords bestResult = best.result();
      Log.d(
          TAG,
          LP
              + "Best rotation extra="
              + best.extraRotation()
              + "°, meanConf="
              + bestResult.meanConfidence
              + ", hasContent="
              + OcrRecognitionPipeline.hasContent(bestResult)
              + ", words="
              + (bestResult.words != null ? bestResult.words.size() : 0)
              + ", textLen="
              + (bestResult.text == null ? 0 : bestResult.text.length()));

      // Push transform of best attempt to VM on UI thread
      OCRViewModel.OcrTransform tx = toTransform(best);
      // Only persist the computed rotation (relative extra rotation, for optional export
      // alignment) if the feature is enabled; otherwise reset to 0
      final int bestRotToStore = allowOcrAutoRotate ? best.extraRotation() : 0;
      runOnUiThreadSafe(
          () -> {
            ocrViewModel.setTransform(tx);
            try {
              if (cropViewModel != null) cropViewModel.setBestOcrRotationDegrees(bestRotToStore);
            } catch (Throwable ignore) {
              // Best-effort; failure is non-critical
            }
          });

      long durMs = (System.nanoTime() - t0) / 1_000_000L;
      ProcessedOcr processed = postProcess(bestResult);
      runOnUiThreadSafe(
          () -> {
            ocrViewModel.setWords(processed.words());
            ocrViewModel.finishSuccess(
                processed.text(), processed.words(), durMs, bestResult.meanConfidence, tx);
            try {
              showResultFeedback(processed, bestResult.meanConfidence, best.extraRotation());
            } catch (Throwable ignore) {
              // Best-effort; failure is non-critical
            }
          });

    } catch (OcrRecognitionPipeline.CancelledException c) {
      Log.w(TAG, LP + c.getMessage());
      postError("Cancelled");
    } catch (Throwable e) {
      Log.e(TAG, "performOCR: Unexpected error", e);
      postError(e.getMessage() != null ? e.getMessage() : e.toString());
    } finally {
      // Release the engine in the same thread that used it
      try {
        if (localHelper != null) localHelper.shutdown();
      } catch (Throwable ignored) {
        // Best-effort; failure is non-critical
      }
    }
  }

  /** Reads a boolean from the export-options preferences; returns {@code def} on any failure. */
  private boolean readExportPref(String key, boolean def) {
    try {
      return requireContext()
          .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
          .getBoolean(key, def);
    } catch (Throwable ignore) {
      return def;
    }
  }

  /** Applies language and Paddle settings. */
  private void configureHelper(OCRHelper helper, String lang) {
    Log.d(TAG, LP + "Language requested=" + lang);
    helper.setLanguage(lang);
    boolean paddleBestOcr = readExportPref(BUNDLE_PADDLE_BEST_OCR, true);
    helper.setPaddleHighQualityDetectionEnabled(paddleBestOcr);
    Log.d(TAG, LP + "Paddle best OCR enabled=" + paddleBestOcr);
  }


  /**
   * Vertical-layout guard: for genuinely vertical documents (CJK top-to-bottom columns) the
   * 90°/270° attempts can "win" by confidence because vertical columns then look like horizontal
   * lines to the detector — but the page is correctly oriented. When the 0° attempt already
   * produced content whose boxes are predominantly tall, prefer the 0° result and do not rotate.
   *
   * @return the attempt to use, or {@code null} if there is none
   */
  private static OcrRecognitionPipeline.Attempt preferZeroRotationForVerticalText(
      OcrRecognitionPipeline.Outcome outcome) {
    OcrRecognitionPipeline.Attempt best = outcome.best();
    OcrRecognitionPipeline.Attempt zero = outcome.zero();
    if (best == null || zero == null) return best;
    if (!de.schliweb.makeacopy.utils.ocr.VerticalTextLayoutPolicy.shouldPreferZeroRotation(
        best.extraRotation(),
        OcrRecognitionPipeline.hasContent(zero.result()),
        zero.result().words)) {
      return best;
    }
    Log.i(
        TAG,
        LP
            + "Vertical text layout detected at 0°; overriding auto-rotate (was extra="
            + best.extraRotation()
            + "°)");
    return zero;
  }

  /** Coordinate transform between the rotated source and the preprocessed OCR input. */
  private static OCRViewModel.OcrTransform toTransform(OcrRecognitionPipeline.Attempt a) {
    OCRViewModel.OcrTransform tx =
        new OCRViewModel.OcrTransform(
            a.srcWidth(),
            a.srcHeight(),
            a.inputWidth(),
            a.inputHeight(),
            a.inputWidth() / (float) a.srcWidth(),
            a.inputHeight() / (float) a.srcHeight(),
            0,
            0);
    Log.d(
        TAG,
        LP
            + "Transform: src="
            + tx.srcW()
            + "x"
            + tx.srcH()
            + ", dst="
            + tx.dstW()
            + "x"
            + tx.dstH()
            + ", sx="
            + tx.scaleX()
            + ", sy="
            + tx.scaleY());
    return tx;
  }

  /** Final OCR output as persisted to the ViewModel. */
  private record ProcessedOcr(String text, List<RecognizedWord> words) {}

  /**
   * Derives the text from the recognized words (reading order, multi-column option).
   *
   * <p>Never persists UI placeholder strings as OCR output: only real OCR output, or "" when OCR
   * returned nothing.
   */
  private ProcessedOcr postProcess(OCRHelper.OcrResultWords bestResult) {
    List<RecognizedWord> ocrWords =
        (bestResult.words != null) ? bestResult.words : new ArrayList<>();
    return new ProcessedOcr(wordsToText(ocrWords), ocrWords);
  }


  private String wordsToText(List<RecognizedWord> words) {
    String text =
        OCRPostProcessor.wordsToText(words, MultiColumnOcrPrefs.isEnabled(requireContext()));
    return (text == null || text.trim().isEmpty()) ? "" : text;
  }

  /**
   * UI thread: tells the user what OCR found. With Auto‑Rotate enabled this also applies the
   * detected rotation to the current scan. Shows at most one toast per OCR run.
   */
  private void showResultFeedback(ProcessedOcr processed, Integer meanConf, int bestRot) {
    // UX guard: never show a score or rotation for an empty OCR result. Content is determined
    // from the final values persisted to the ViewModel.
    if (!OcrRecognitionPipeline.hasContent(processed.text(), processed.words())) {
      UIUtils.showToast(
          requireContext(), getString(R.string.ocr_no_text_detected), Toast.LENGTH_SHORT);
      return;
    }
    int score = meanConf != null ? meanConf : -1;
    if (readExportPref(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, false)) {
      applyDetectedRotation(bestRot);
      // If we know the score, show rotation + score combined; otherwise, show rotation only.
      UIUtils.showToast(
          requireContext(),
          score >= 0
              ? getString(R.string.ocr_found_rotation_with_score, bestRot, score)
              : getString(R.string.ocr_found_rotation, bestRot),
          Toast.LENGTH_SHORT);
    } else if (score >= 0) {
      // Auto‑Rotate not applied, but still useful to show the OCR score.
      UIUtils.showToast(requireContext(), getString(R.string.ocr_score, score), Toast.LENGTH_SHORT);
    }
  }

  /**
   * Applies the detected rotation to the current scan for export, respecting the unified rotation
   * model (apply in-memory; persist will bake).
   */
  private void applyDetectedRotation(int bestRot) {
    if (cropViewModel == null) return;
    try {
      Integer cur = cropViewModel.getUserRotationDegrees().getValue();
      int curDeg = (cur == null) ? 0 : cur;
      cropViewModel.setUserRotationDegrees(((curDeg + bestRot) % 360 + 360) % 360);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    // We have applied the OCR suggestion; clear the helper to avoid re-applying later.
    try {
      cropViewModel.setBestOcrRotationDegrees(0);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  private void postError(String msg) {
    runOnUiThreadSafe(
        () -> {
          ocrViewModel.finishError(msg);
        });
  }

  private void runOnUiThreadSafe(Runnable r) {
    if (!isAdded()) return;
    try {
      requireActivity()
          .runOnUiThread(
              () -> {
                if (!isAdded() || binding == null) return;
                r.run();
              });
    } catch (Throwable ignored) {
      // Best-effort; failure is non-critical
    }
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    // Signal cancel; do NOT forcibly interrupt the running job (avoid tearing down the engine
    // mid-call)
    ocrCancelled.set(true);

    binding = null;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    // Fragment is going away for good: now it's safe to shut down the executor
    ocrExecutor.shutdown();
  }
}
