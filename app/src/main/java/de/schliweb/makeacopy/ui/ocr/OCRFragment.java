/*
 * Copyright 2025 Christian Kierdorf
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
import androidx.annotation.VisibleForTesting;
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
import de.schliweb.makeacopy.utils.image.ImageLoader;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
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
 * single-thread executor with a fresh TessBaseAPI instance per job to ensure thread-safety.
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

  // Language helper for listing/availability checks (no long-lived TessBaseAPI instance)
  private OCRHelper langHelper;

  @Inject Provider<OCRHelper> ocrHelperProvider;
  @Inject DictionaryManager dictionaryManager;

  // Concurrency: serialize OCR jobs, 1 job ↔ 1 TessBaseAPI instance
  private final ExecutorService ocrExecutor = Executors.newSingleThreadExecutor();
  private volatile Future<?> runningOcr = null;
  private final AtomicBoolean ocrCancelled = new AtomicBoolean(false);

  // SAF launcher for manual traineddata import
  private ActivityResultLauncher<Intent> openTraineddataLauncher;

  public static final String BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT = "ocr_auto_rotate_apply_export";
  public static final String BUNDLE_OCR_POST_PROCESSING = "ocr_post_processing";
  public static final String BUNDLE_PADDLE_BEST_OCR = "paddle_best_ocr";

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    ocrViewModel = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
    cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
    binding = FragmentOcrBinding.inflate(inflater, container, false);
    View root = binding.getRoot();
    binding.ocrModelsHint.setVisibility(
        getResources().getBoolean(R.bool.show_ocr_models_hint) ? View.VISIBLE : View.GONE);

    ensureBitmapWhenOpenedDirectly();
    rememberInitialImage();

    // Language helper (no initTesseract() here!)
    langHelper = ocrHelperProvider.get();

    registerTraineddataImportLauncher();
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

  /** Init SAF launcher for manual .traineddata import. */
  private void registerTraineddataImportLauncher() {
    openTraineddataLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Intent data = result.getData();
                Uri uri = data.getData();
                boolean ok = uri != null && OcrModelManager.importFromUri(requireContext(), uri);
                UIUtils.showToast(
                    requireContext(),
                    ok
                        ? getString(R.string.ocr_import_success)
                        : getString(R.string.ocr_import_failed),
                    Toast.LENGTH_SHORT);
                if (ok) {
                  refreshLanguageSpinner();
                  prepareReprocessAfterModelChange();
                }
              }
            });
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
    UIUtils.adjustMarginForSystemInsets(binding.buttonContainer, 12);
    ViewCompat.setOnApplyWindowInsetsListener(
        binding.buttonContainer,
        (v, insets) -> {
          UIUtils.adjustMarginForSystemInsets(binding.buttonContainer, 12);
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
          rv.setDoc(doc);
          Navigation.findNavController(requireView()).navigate(R.id.navigation_review);
        });
  }

  /**
   * Language spinner now only updates ViewModel language and UI. We do NOT touch any long-lived
   * TessBaseAPI here.
   */
  private static final String PREFS_NAME = "export_options";

  private static final String PREF_KEY_OCR_LANG = "ocr_language";
  private static final String PREF_KEY_OCR_MODE = "ocr_prep_mode"; // 0=Original,1=Quick,2=Robust

  // Recognition prep modes (image preprocessing before Tesseract)
  static final int OCR_MODE_ORIGINAL = 0;
  static final int OCR_MODE_QUICK = 1;
  static final int OCR_MODE_ROBUST = 2;
  // PaddleOCR: exclusive engine, no preprocessing (paddle flavor only).
  static final int OCR_MODE_PADDLE = 3;

  // Maximum number of languages that can be selected for multi-language OCR
  private static final int MAX_LANGUAGES = 2;

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

    String preferred =
        de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR
            ? mapSystemLanguageToPaddleModel(java.util.Locale.getDefault())
            : OCRUtils.mapSystemLanguageToTesseract(java.util.Locale.getDefault().getLanguage());
    if (isCodeAvailable(codes, preferred) && isLanguageAvailableSafe(preferred)) {
      return preferred;
    }

    if (de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR
        && isCodeAvailable(codes, "latin")
        && isLanguageAvailableSafe("latin")) {
      return "latin";
    }
    return null;
  }

  private static String mapSystemLanguageToPaddleModel(java.util.Locale locale) {
    String language = locale != null ? locale.getLanguage() : "";
    return switch (language) {
      case "en" -> "en";
      case "ru", "be", "uk" -> "eslav";
      case "bg", "mk", "mn", "sr" -> "cyrillic";
      case "ar", "fa", "ur", "ps" -> "arabic";
      case "hi", "mr", "ne", "sa" -> "devanagari";
      case "th" -> "th";
      case "el" -> "el";
      case "zh", "ja", "ko" -> "zh";
      default -> "latin";
    };
  }

  private static boolean isCodeAvailable(String[] codes, String code) {
    if (code == null || code.isEmpty()) return false;
    for (String available : codes) {
      if (code.equals(available)) return true;
    }
    return false;
  }

  private void showLanguageDialog(String[] codes, String[] displayNames, MaterialButton dropdown) {
    if (de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR) {
      showSingleLanguageDialog(codes, displayNames, dropdown);
    } else {
      showMultiLanguageDialog(codes, displayNames, dropdown);
    }
  }

  private void showSingleLanguageDialog(
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

  /** Shows a multi-select dialog for choosing OCR languages (max 2). */
  private void showMultiLanguageDialog(
      String[] codes, String[] displayNames, MaterialButton dropdown) {
    boolean[] checkedItems = new boolean[codes.length];
    for (int i = 0; i < codes.length; i++) {
      checkedItems[i] = selectedLanguageCodes.contains(codes[i]);
    }

    AlertDialog dlg =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_ocr_languages)
            .setMultiChoiceItems(
                displayNames,
                checkedItems,
                (dialog, which, isChecked) -> {
                  String code = codes[which];
                  if (isChecked) {
                    // Check if max languages reached
                    if (selectedLanguageCodes.size() >= MAX_LANGUAGES) {
                      // Uncheck this item and show warning
                      ((AlertDialog) dialog).getListView().setItemChecked(which, false);
                      checkedItems[which] = false;
                      UIUtils.showToast(
                          requireContext(),
                          getString(R.string.ocr_max_languages_warning),
                          Toast.LENGTH_SHORT);
                      return;
                    }
                    if (!selectedLanguageCodes.contains(code)) {
                      selectedLanguageCodes.add(code);
                    }
                  } else {
                    selectedLanguageCodes.remove(code);
                  }
                })
            .setPositiveButton(
                android.R.string.ok,
                (dialog, which) -> {
                  if (selectedLanguageCodes.isEmpty()) {
                    UIUtils.showToast(
                        requireContext(),
                        getString(R.string.ocr_no_language_selected),
                        Toast.LENGTH_SHORT);
                    // Fallback to first available language
                    if (codes.length > 0) {
                      selectedLanguageCodes.add(codes[0]);
                    }
                  }

                  applyLanguageSelection(
                      dropdown, codes, displayNames, ocrViewModel.getLanguage().getValue());
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

  /** Get available languages without keeping a long-lived TessBaseAPI. */
  private String[] getAvailableLanguages() {
    try {
      String[] flavorLanguages = OcrModelManager.getAvailableLanguageCodes(requireContext());
      if (flavorLanguages != null && flavorLanguages.length > 0) return flavorLanguages;
      if (langHelper != null) {
        String[] langs = langHelper.getAvailableLanguages();
        if (langs != null && langs.length > 0) return langs;
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    // Fallback includes Chinese (Simplified and Traditional) so users on zh locales can select them
    // when asset listing fails
    return OCRUtils.getLanguages();
  }

  private String[] mapCodesToDisplayNames(String[] codes) {
    String[] out = new String[codes.length];
    for (int i = 0; i < codes.length; i++) {
      out[i] = OCRUtils.codeToDisplayName(requireContext(), codes[i]);
    }
    return out;
  }

  /** Refresh the language spinner after importing new models. */
  private void refreshLanguageSpinner() {
    try {
      // Recreate helper to see any new files (not strictly necessary)
      langHelper = ocrHelperProvider.get();
      setupLanguageSpinner();
    } catch (Throwable t) {
      Log.w(TAG, "Failed to refresh language spinner", t);
    }
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

  private int getSelectedOcrMode() {
    if (de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR) {
      return OCR_MODE_PADDLE;
    }
    try {
      android.content.SharedPreferences sp =
          requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
      // Migration: Quick is no longer a user-facing mode (FR#74 benchmark 2026-04-26b
      // showed Quick == Robust for binaryOutput=false). Map any persisted Quick to Robust
      // and default new installs to Robust.
      int stored = sp.getInt(PREF_KEY_OCR_MODE, OCR_MODE_ROBUST);
      if (stored == OCR_MODE_QUICK) {
        stored = OCR_MODE_ROBUST;
        sp.edit().putInt(PREF_KEY_OCR_MODE, stored).apply();
      }
      // Migration: the experimental PaddleOCR toggle (PaddleOcrPrefs.KEY) used to be a
      // separate checkbox alongside the prep-mode picker. It is now folded into the
      // recognition-mode radio group as OCR_MODE_PADDLE. Existing users that opted into
      // PaddleOCR keep their preference: map the toggle to the new mode and clear the
      // legacy key.
      try {
        if (sp.getBoolean(PaddleOcrPrefs.KEY, false)) {
          if (PaddleOcrPrefs.isToggleVisible() && stored != OCR_MODE_PADDLE) {
            stored = OCR_MODE_PADDLE;
            sp.edit().putInt(PREF_KEY_OCR_MODE, stored).remove(PaddleOcrPrefs.KEY).apply();
          } else {
            // Toggle no longer applicable (e.g. unsupported ABI) — drop legacy key.
            sp.edit().remove(PaddleOcrPrefs.KEY).apply();
          }
        }
      } catch (Throwable ignore) {
        // Best-effort migration; failure is non-critical.
      }
      // If a previously persisted PADDLE mode is no longer applicable (e.g. user installed
      // a non-paddle build), fall back to Robust without clobbering the stored value to
      // avoid data loss across re-installs of the paddle flavor.
      if (stored == OCR_MODE_PADDLE && !PaddleOcrPrefs.isToggleVisible()) {
        return OCR_MODE_ROBUST;
      }
      return stored;
    } catch (Throwable ignore) {
      return OCR_MODE_ROBUST;
    }
  }

  private void setSelectedOcrMode(int mode) {
    try {
      android.content.SharedPreferences sp =
          requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
      sp.edit().putInt(PREF_KEY_OCR_MODE, mode).apply();
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  private static final String BUNDLE_LAYOUT_ANALYSIS = "layout_analysis";

  /** The input views of the OCR prep mode dialog. */
  private record PrepModeDialogViews(
      android.widget.RadioGroup modes,
      android.widget.CheckBox autoRotate,
      android.widget.CheckBox postProcessing,
      android.widget.CheckBox layoutAnalysis,
      android.widget.CheckBox paddleBestOcr,
      android.widget.CheckBox multiColumnOcr) {}

  private void showOcrPrepModeDialog() {
    android.view.LayoutInflater inflater = android.view.LayoutInflater.from(requireContext());
    android.view.View view = inflater.inflate(R.layout.dialog_ocr_prep_mode, null);
    PrepModeDialogViews views =
        new PrepModeDialogViews(
            view.findViewById(R.id.rg_ocr_modes),
            view.findViewById(R.id.checkbox_ocr_auto_rotate_apply_export_dialog),
            view.findViewById(R.id.checkbox_ocr_post_processing_dialog),
            view.findViewById(R.id.checkbox_layout_analysis_dialog),
            view.findViewById(R.id.checkbox_paddle_best_ocr_dialog),
            view.findViewById(R.id.checkbox_multi_column_ocr_dialog));
    final boolean fixedPaddleMode = de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR;
    // PaddleOCR (experimental): visible as third radio option only when feature flag
    // is enabled and ABI is arm64-v8a (see PaddleOcrPrefs.isToggleVisible).
    final boolean paddleToggleVisible = PaddleOcrPrefs.isToggleVisible();

    if (fixedPaddleMode) {
      views.modes().setVisibility(android.view.View.GONE);
    }
    android.widget.RadioButton rbPaddle = view.findViewById(R.id.rbtn_mode_paddle);
    rbPaddle.setVisibility(
        paddleToggleVisible ? android.view.View.VISIBLE : android.view.View.GONE);
    restorePrepModeDialogState(views, fixedPaddleMode, paddleToggleVisible);

    AlertDialog dlg =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                fixedPaddleMode ? R.string.ocr_models_manage : R.string.ocr_choose_prep_mode_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(
                R.string.ok,
                (d, w) -> applyPrepModeDialog(views, fixedPaddleMode, paddleToggleVisible))
            .create();
    dlg.setOnShowListener(
        d -> DialogUtils.improveAlertDialogButtonContrastForNight(dlg, requireContext()));
    dlg.show();
  }

  /** Shows the saved mode and OCR options in the dialog and hides what does not apply. */
  private void restorePrepModeDialogState(
      PrepModeDialogViews views, boolean fixedPaddleMode, boolean paddleToggleVisible) {
    // Only show layout analysis checkbox if feature flag is enabled
    boolean layoutFeatureEnabled = FeatureFlags.isLayoutAnalysisEnabled();
    views
        .layoutAnalysis()
        .setVisibility(layoutFeatureEnabled ? android.view.View.VISIBLE : android.view.View.GONE);
    views
        .paddleBestOcr()
        .setVisibility(fixedPaddleMode ? android.view.View.VISIBLE : android.view.View.GONE);
    views
        .multiColumnOcr()
        .setVisibility(fixedPaddleMode ? android.view.View.VISIBLE : android.view.View.GONE);

    final int initialMode =
        prepModeForPicker(getSelectedOcrMode(), fixedPaddleMode, paddleToggleVisible);
    views.modes().check(radioIdForPrepMode(initialMode));

    boolean ocrAutoRotateApply = false;
    boolean ocrPostProcessing = true; // default ON
    boolean layoutAnalysis = false; // default OFF
    boolean paddleBestOcr = false; // default OFF
    boolean multiColumnOcr = false; // default OFF
    try {
      android.content.SharedPreferences p =
          requireContext()
              .getSharedPreferences("export_options", android.content.Context.MODE_PRIVATE);
      ocrAutoRotateApply = p.getBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, false);
      ocrPostProcessing = p.getBoolean(BUNDLE_OCR_POST_PROCESSING, true);
      layoutAnalysis = p.getBoolean(BUNDLE_LAYOUT_ANALYSIS, false);
      paddleBestOcr = p.getBoolean(BUNDLE_PADDLE_BEST_OCR, false);
      multiColumnOcr = p.getBoolean(MultiColumnOcrPrefs.KEY, false);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    views.autoRotate().setChecked(ocrAutoRotateApply);
    views.postProcessing().setChecked(ocrPostProcessing);
    views
        .postProcessing()
        .setVisibility(
            initialMode == OCR_MODE_PADDLE ? android.view.View.GONE : android.view.View.VISIBLE);
    views.layoutAnalysis().setChecked(layoutAnalysis && layoutFeatureEnabled);
    views.paddleBestOcr().setChecked(fixedPaddleMode && paddleBestOcr);
    views.multiColumnOcr().setChecked(fixedPaddleMode && multiColumnOcr);
    views
        .modes()
        .setOnCheckedChangeListener(
            (group, checkedId) ->
                views
                    .postProcessing()
                    .setVisibility(
                        checkedId == R.id.rbtn_mode_paddle
                            ? android.view.View.GONE
                            : android.view.View.VISIBLE));
  }

  /** OK in the prep mode dialog: saves mode and options, confirms them, re-runs OCR if needed. */
  private void applyPrepModeDialog(
      PrepModeDialogViews views, boolean fixedPaddleMode, boolean paddleToggleVisible) {
    int selectedMode = OCR_MODE_PADDLE;
    if (!fixedPaddleMode) {
      selectedMode =
          prepModeForRadioId(views.modes().getCheckedRadioButtonId(), paddleToggleVisible);
      setSelectedOcrMode(selectedMode);
    }

    boolean postProcessingVisible = selectedMode != OCR_MODE_PADDLE;
    boolean postProcessingSelected = postProcessingVisible && views.postProcessing().isChecked();
    try {
      android.content.SharedPreferences p =
          requireContext()
              .getSharedPreferences("export_options", android.content.Context.MODE_PRIVATE);
      android.content.SharedPreferences.Editor editor =
          p.edit()
              .putBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, views.autoRotate().isChecked())
              .putBoolean(BUNDLE_LAYOUT_ANALYSIS, views.layoutAnalysis().isChecked())
              .putBoolean(
                  BUNDLE_PADDLE_BEST_OCR, fixedPaddleMode && views.paddleBestOcr().isChecked())
              .putBoolean(
                  MultiColumnOcrPrefs.KEY, fixedPaddleMode && views.multiColumnOcr().isChecked());
      if (postProcessingVisible) {
        editor.putBoolean(BUNDLE_OCR_POST_PROCESSING, postProcessingSelected);
      }
      editor.apply();
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }

    // PaddleOCR is now selected as a recognition mode (not a separate toggle).
    // When the user moves AWAY from PaddleOCR, release the engine so that the
    // next OCR run starts a clean Tesseract session.
    if (!fixedPaddleMode && selectedMode != OCR_MODE_PADDLE) {
      try {
        PaddleEngineProvider.releaseAll(requireContext());
      } catch (Throwable t) {
        Log.w(TAG, "PaddleEngineProvider.releaseAll failed", t);
      }
    }

    showPrepModeToast(views, selectedMode, fixedPaddleMode, postProcessingSelected);
    prepareReprocessAfterModelChange();
  }

  /** Toast with the selected mode AND the current status of the OCR options. */
  private void showPrepModeToast(
      PrepModeDialogViews views,
      int selectedMode,
      boolean fixedPaddleMode,
      boolean postProcessingSelected) {
    CharSequence[] modes =
        new CharSequence[] {
          getString(R.string.ocr_mode_original),
          getString(R.string.ocr_mode_quick),
          getString(R.string.ocr_mode_robust),
          getString(R.string.ocr_mode_paddle)
        };
    StringBuilder toastMsg =
        new StringBuilder(
            getString(
                R.string.ocr_prep_mode_set,
                modes[fixedPaddleMode ? OCR_MODE_PADDLE : selectedMode]));
    appendOptionState(
        toastMsg,
        getString(R.string.opt_ocr_auto_rotate_apply_export),
        views.autoRotate().isChecked());
    if (selectedMode != OCR_MODE_PADDLE) {
      appendOptionState(
          toastMsg, getString(R.string.opt_ocr_post_processing), postProcessingSelected);
    }
    // Only show layout analysis in toast if feature is enabled
    if (FeatureFlags.isLayoutAnalysisEnabled()) {
      appendOptionState(
          toastMsg, getString(R.string.opt_layout_analysis), views.layoutAnalysis().isChecked());
    }
    if (fixedPaddleMode) {
      appendOptionState(
          toastMsg, getString(R.string.opt_paddle_best_ocr), views.paddleBestOcr().isChecked());
      appendOptionState(
          toastMsg, getString(R.string.opt_multi_column_ocr), views.multiColumnOcr().isChecked());
    }
    UIUtils.showToast(requireContext(), toastMsg.toString(), Toast.LENGTH_SHORT);
  }

  @VisibleForTesting
  static void appendOptionState(StringBuilder sb, String label, boolean on) {
    sb.append("\n").append(label).append(": ").append(on ? "[ON]" : "[OFF]");
  }

  /**
   * The mode the picker shows for a saved mode. Quick is hidden in the picker (see
   * dialog_ocr_prep_mode.xml: rbtn_mode_quick is gone), so a lingering Quick selection counts as
   * Robust; PaddleOCR is only valid while it can be chosen, otherwise it falls back to Robust.
   */
  @VisibleForTesting
  static int prepModeForPicker(
      int savedMode, boolean fixedPaddleMode, boolean paddleToggleVisible) {
    int mode = Math.max(0, Math.min(OCR_MODE_PADDLE, savedMode));
    if (mode == OCR_MODE_QUICK) mode = OCR_MODE_ROBUST;
    if (mode == OCR_MODE_PADDLE && !fixedPaddleMode && !paddleToggleVisible) {
      mode = OCR_MODE_ROBUST;
    }
    return mode;
  }

  private static int radioIdForPrepMode(int pickerMode) {
    if (pickerMode == OCR_MODE_ORIGINAL) return R.id.rbtn_mode_original;
    if (pickerMode == OCR_MODE_PADDLE) return R.id.rbtn_mode_paddle;
    return R.id.rbtn_mode_robust;
  }

  /** The mode for the checked radio button; anything else (incl. hidden Quick) is Robust. */
  @VisibleForTesting
  static int prepModeForRadioId(int checkedId, boolean paddleToggleVisible) {
    if (checkedId == R.id.rbtn_mode_original) return OCR_MODE_ORIGINAL;
    if (checkedId == R.id.rbtn_mode_paddle && paddleToggleVisible) return OCR_MODE_PADDLE;
    return OCR_MODE_ROBUST;
  }

  /** Open a small dialog with OCR model actions. */
  private void showOcrOptionsDialog() {
    if (de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR) {
      showPaddleOcrOptionsDialog();
      return;
    }

    // Determine current language code and whether a deletable local (Best) model exists
    String curLang = null;
    try {
      curLang = ocrViewModel.getLanguage().getValue();
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    final String langCode = curLang;

    boolean hasBestTmp = false;
    if (langCode != null) {
      try {
        hasBestTmp = OcrModelManager.isUsingBestModel(requireContext(), langCode);
      } catch (Throwable ignore) {
        hasBestTmp = false;
      }
    }
    final boolean hasBest = hasBestTmp;

    CharSequence[] items =
        new CharSequence[] {
          getString(R.string.ocr_import_manual),
          getString(R.string.ocr_discover_packs),
          getString(R.string.ocr_delete_best_model),
          getString(R.string.ocr_choose_prep_mode_menu),
          getString(R.string.ocr_explain_prep_modes)
        };
    AlertDialog dlg =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ocr_models_manage)
            .setItems(
                items,
                (dialog, which) -> {
                  if (which == 0) {
                    // Manual import via SAF
                    openTraineddataLauncher.launch(OcrModelManager.createOpenTraineddataIntent());
                  } else if (which == 1) {
                    showDiscoverPacksDialog();
                  } else if (which == 2) {
                    if (langCode == null) {
                      UIUtils.showToast(
                          requireContext(),
                          getString(R.string.ocr_delete_failed),
                          Toast.LENGTH_SHORT);
                      return;
                    }
                    if (!hasBest) {
                      UIUtils.showToast(
                          requireContext(),
                          getString(R.string.ocr_nothing_to_delete),
                          Toast.LENGTH_SHORT);
                      return;
                    }
                    // Confirm deletion
                    String display = OCRUtils.codeToDisplayName(requireContext(), langCode);
                    AlertDialog confirm =
                        new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.ocr_delete_confirm_title)
                            .setMessage(getString(R.string.ocr_delete_confirm_msg, display))
                            .setPositiveButton(
                                R.string.delete,
                                (d2, w2) -> {
                                  boolean ok =
                                      OcrModelManager.deleteLocalModel(requireContext(), langCode);
                                  UIUtils.showToast(
                                      requireContext(),
                                      ok
                                          ? getString(R.string.ocr_delete_success)
                                          : getString(R.string.ocr_delete_failed),
                                      Toast.LENGTH_SHORT);
                                  if (ok) {
                                    refreshLanguageSpinner();
                                    prepareReprocessAfterModelChange();
                                  }
                                })
                            .setNegativeButton(R.string.cancel, null)
                            .create();
                    confirm.setOnShowListener(
                        dlg2 ->
                            DialogUtils.improveAlertDialogButtonContrastForNight(
                                confirm, requireContext()));
                    confirm.show();
                  } else if (which == 3) {
                    showOcrPrepModeDialog();
                  } else if (which == 4) {
                    showOcrPrepModesExplanation();
                  }
                })
            .setNegativeButton(R.string.cancel, null)
            .create();
    dlg.setOnShowListener(
        d -> DialogUtils.improveAlertDialogButtonContrastForNight(dlg, requireContext()));
    dlg.show();
  }

  private void showOcrPrepModesExplanation() {
    // Build message that also explains the OCR Auto‑Rotate option
    String explain = getString(R.string.ocr_prep_modes_message);
    String autoNote;
    try {
      autoNote = getString(R.string.ocr_prep_modes_autorotate_note);
    } catch (Throwable ignore) {
      autoNote = null;
    }
    if (autoNote != null && !autoNote.isEmpty()) {
      explain = explain + "\n\n" + autoNote;
    }
    AlertDialog info =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ocr_prep_modes_title)
            .setMessage(explain)
            .setPositiveButton(R.string.ok, null)
            .create();
    info.setOnShowListener(
        d2 -> DialogUtils.improveAlertDialogButtonContrastForNight(info, requireContext()));
    info.show();
  }

  private void showPaddleOcrOptionsDialog() {
    showOcrPrepModeDialog();
  }

  private void showDiscoverPacksDialog() {
    List<String> pkgs = OcrModelManager.discoverAddonPackages(requireContext());
    if (pkgs == null || pkgs.isEmpty()) {
      UIUtils.showToast(
          requireContext(), getString(R.string.ocr_no_packs_found), Toast.LENGTH_SHORT);
      return;
    }
    CharSequence[] items = pkgs.toArray(new CharSequence[0]);
    AlertDialog dlg =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ocr_choose_pack)
            .setItems(items, (d, idx) -> showModelsInPackDialog(pkgs.get(idx)))
            .setNegativeButton(R.string.cancel, null)
            .create();

    dlg.setOnShowListener(
        e -> DialogUtils.improveAlertDialogButtonContrastForNight(dlg, requireContext()));
    dlg.show();
  }

  private void showModelsInPackDialog(String pkg) {
    List<String> files = OcrModelManager.listTrainedDataInPackage(requireContext(), pkg);
    if (files == null || files.isEmpty()) {
      UIUtils.showToast(
          requireContext(), getString(R.string.ocr_no_models_in_pack), Toast.LENGTH_SHORT);
      return;
    }
    CharSequence[] items = files.toArray(new CharSequence[0]);
    AlertDialog dlg =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ocr_choose_model)
            .setItems(
                items,
                (d, idx) -> {
                  String filename = files.get(idx);
                  boolean ok = OcrModelManager.importFromPackage(requireContext(), pkg, filename);
                  UIUtils.showToast(
                      requireContext(),
                      ok
                          ? getString(R.string.ocr_import_success)
                          : getString(R.string.ocr_import_failed),
                      Toast.LENGTH_SHORT);
                  if (ok) {
                    refreshLanguageSpinner();
                    prepareReprocessAfterModelChange();
                  }
                })
            .setNegativeButton(R.string.cancel, null)
            .create();
    dlg.setOnShowListener(
        e -> DialogUtils.improveAlertDialogButtonContrastForNight(dlg, requireContext()));
    dlg.show();
  }

  /**
   * Executes OCR in a single-thread executor with a fresh TessBaseAPI per job. Rotation handling:
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

      // Fresh Tesseract per job
      localHelper = ocrHelperProvider.get();
      String lang = ocrViewModel.getLanguage().getValue();
      if (lang == null || lang.isEmpty()) lang = "eng";
      configureHelper(localHelper, lang);
      if (!initEngine(localHelper)) {
        postError("Engine not initialized");
        return;
      }

      // Tune Tesseract PSM based on recognition mode (Robust benefits from PSM_AUTO)
      int prepMode = getSelectedOcrMode();
      try {
        OcrPageSegmentationMode psm =
            (prepMode == OCR_MODE_ROBUST)
                ? OcrPageSegmentationMode.AUTO
                : OcrPageSegmentationMode.SINGLE_BLOCK;
        localHelper.setPageSegmentationMode(psm);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }

      // Try OCR rotations (0, 90, 180, 270) only when Auto‑Rotate is enabled, to guard against
      // wrong user rotation. Otherwise, use the current orientation only. Layout analysis requires
      // both feature flag AND user preference.
      boolean allowOcrAutoRotate = readExportPref(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, false);
      boolean useLayoutAnalysis =
          FeatureFlags.isLayoutAnalysisEnabled() && readExportPref(BUNDLE_LAYOUT_ANALYSIS, false);
      OcrRecognitionPipeline.Outcome outcome =
          OcrRecognitionPipeline.recognizeBestRotation(
              localHelper,
              src,
              new OcrRecognitionPipeline.Options(prepMode, allowOcrAutoRotate, useLayoutAnalysis),
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
      ProcessedOcr processed = postProcess(bestResult, lang, prepMode == OCR_MODE_PADDLE);
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
      // Release Tesseract in the same thread that used it
      try {
        if (localHelper != null) localHelper.shutdown();
        Log.d(TAG, LP + "Tesseract shutdown complete");
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

  /** Applies language, Best/Fast model and Paddle settings; must run BEFORE engine init. */
  private void configureHelper(OCRHelper helper, String lang) {
    // 1 job = 1 engine instance. No automatic reinitialization per run.
    try {
      helper.setReinitPerRun(false);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }

    Log.d(TAG, LP + "Language requested=" + lang);
    try {
      // Ensure language is set BEFORE init so Tesseract loads the correct traineddata
      helper.setLanguage(lang);
    } catch (Throwable t) {
      Log.e(TAG, LP + "Failed to set language " + lang, t);
    }

    // Detect if Best model is used and configure OCRHelper accordingly BEFORE init
    try {
      boolean useBest = OcrModelManager.isUsingBestModel(requireContext(), lang);
      helper.setUseBestModelSettings(useBest);
      Log.d(TAG, LP + "Best model settings enabled=" + useBest + " for lang=" + lang);
    } catch (Throwable t) {
      Log.w(TAG, LP + "Failed to detect/set Best model settings", t);
    }

    try {
      boolean paddleBestOcr =
          de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR
              && readExportPref(BUNDLE_PADDLE_BEST_OCR, false);
      helper.setPaddleHighQualityDetectionEnabled(paddleBestOcr);
      Log.d(TAG, LP + "Paddle best OCR enabled=" + paddleBestOcr);
    } catch (Throwable t) {
      Log.w(TAG, LP + "Failed to detect/set Paddle best OCR", t);
    }
  }

  private boolean initEngine(OCRHelper helper) {
    long tInit0 = System.nanoTime();
    boolean initOk = false;
    try {
      initOk = helper.initTesseract();
    } catch (Throwable t) {
      Log.e(TAG, LP + "initTesseract threw", t);
    }
    Log.d(
        TAG,
        LP
            + "Tesseract init ok="
            + initOk
            + ", took="
            + ((System.nanoTime() - tInit0) / 1_000_000L)
            + "ms");
    return initOk;
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
   * Applies post-processing to correct common OCR errors (including dictionary-based correction) if
   * the option is enabled (default: ON) and the run did not use PaddleOCR.
   *
   * <p>Never persists UI placeholder strings as OCR output: only real OCR output, or "" when OCR
   * returned nothing.
   */
  private ProcessedOcr postProcess(
      OCRHelper.OcrResultWords bestResult, String lang, boolean selectedPaddleMode) {
    String ocrText =
        (bestResult.text == null || bestResult.text.trim().isEmpty()) ? "" : bestResult.text;
    List<RecognizedWord> ocrWords =
        (bestResult.words != null) ? bestResult.words : new ArrayList<>();

    if (readExportPref(BUNDLE_OCR_POST_PROCESSING, true) && !selectedPaddleMode) {
      try {
        // Process words with dictionary - this is the single source of truth
        ocrWords = OCRPostProcessor.processWithDictionary(ocrWords, lang, dictionaryManager);
        // Derive text from processed words instead of processing text separately
        ocrText = wordsToText(ocrWords);
        // Log quality statistics
        OCRPostProcessor.OcrQualityStats stats = OCRPostProcessor.analyzeQuality(ocrWords);
        Log.d(TAG, LP + "OCR Quality: " + stats);
      } catch (Throwable t) {
        Log.w(TAG, LP + "Post-processing failed", t);
      }
    } else {
      Log.d(
          TAG,
          LP
              + (selectedPaddleMode
                  ? "OCR post-processing skipped for PaddleOCR"
                  : "OCR post-processing disabled by user preference"));
      // Even without post-processing, derive text from words for consistency
      ocrText = wordsToText(ocrWords);
    }
    return new ProcessedOcr(ocrText, ocrWords);
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
    // Signal cancel; do NOT forcibly interrupt the running job (avoid tearing down Tesseract
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
