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
import android.graphics.Matrix;
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
import de.schliweb.makeacopy.utils.image.ImageLoader;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
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

    // If OCR was opened directly (skipping Crop), ensure we have a bitmap in CropViewModel
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

    // On first entry, if we have an image and no OCR results yet, remember this image
    // Do NOT reset if we already have OCR results (e.g., returning from Review screen)
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

    // Language helper (no initTesseract() here!)
    langHelper = ocrHelperProvider.get();

    // Init SAF launcher for manual .traineddata import
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

    // State observer
    ocrViewModel
        .getState()
        .observe(
            getViewLifecycleOwner(),
            state -> {
              boolean canProceed = state.imageProcessed() && !state.processing();
              // Haptic confirmation when OCR processing finishes
              if (wasOcrProcessing && !state.processing() && state.imageProcessed()) {
                HapticsUtils.vibrateOneShot(getContext(), 30L);
              }
              wasOcrProcessing = state.processing();
              binding.buttonProcess.setEnabled(canProceed);
              binding.buttonProcess.setText(R.string.next);

              binding.textOcr.setText(
                  state.processing()
                      ? getString(R.string.processing_image)
                      : (state.imageProcessed()
                          ? getString(
                              R.string.ocr_processing_complete_tap_the_button_to_proceed_to_export)
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
                boolean enableReview = state.imageProcessed() && !state.processing() && hasWords;
                binding.buttonOcrReview.setEnabled(enableReview);
                binding.buttonOcrReview.setAlpha(enableReview ? 1f : 0.4f);
                binding.buttonOcrReview.setVisibility(View.VISIBLE);
              }

              // Enable share button only when OCR finished and there is text to share
              boolean hasText = effectiveText != null && !effectiveText.trim().isEmpty();
              boolean enableShare = state.imageProcessed() && !state.processing() && hasText;
              binding.buttonOcrShare.setEnabled(enableShare);
              binding.buttonOcrShare.setAlpha(enableShare ? 1f : 0.4f);

              // Disable settings (OCR options) button while processing is running
              boolean processing = state.processing();
              binding.buttonOcrOptions.setEnabled(!processing);
              binding.buttonOcrOptions.setAlpha(processing ? 0.4f : 1f);

              // Proceed to Export
              binding.buttonProcess.setOnClickListener(v -> navigateToExport());
            });

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

    // Back navigates to Crop reliably: try to pop back stack, otherwise navigate explicitly
    binding.buttonBack.setOnClickListener(
        v -> {
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
        });

    // Also handle system back (gesture/hardware) the same way
    OnBackPressedCallback backCallback =
        new OnBackPressedCallback(true) {
          @Override
          public void handleOnBackPressed() {
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
        };
    requireActivity()
        .getOnBackPressedDispatcher()
        .addCallback(getViewLifecycleOwner(), backCallback);

    // OCR options (settings) icon above the button bar
    binding.buttonOcrOptions.setOnClickListener(v -> showOcrOptionsDialog());
    // Share recognized text directly with other apps
    binding.buttonOcrShare.setOnClickListener(v -> shareOcrText());
    // OCR Review icon (optional, feature-flagged)
    if (!FeatureFlags.isOcrReviewEnabled()) {
      binding.buttonOcrReview.setVisibility(View.GONE);
    } else {
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

    // Language selection
    setupLanguageSpinner();

    return root;
  }

  /**
   * Language spinner now only updates ViewModel language and UI. We do NOT touch any long-lived
   * TessBaseAPI here.
   */
  private static final String PREFS_NAME = "export_options";

  private static final String PREF_KEY_OCR_LANG = "ocr_language";
  private static final String PREF_KEY_OCR_MODE = "ocr_prep_mode"; // 0=Original,1=Quick,2=Robust

  // Recognition prep modes (image preprocessing before Tesseract)
  private static final int OCR_MODE_ORIGINAL = 0;
  private static final int OCR_MODE_QUICK = 1;
  private static final int OCR_MODE_ROBUST = 2;
  // PaddleOCR: exclusive engine, no preprocessing (paddle flavor only).
  private static final int OCR_MODE_PADDLE = 3;

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
      out[i] = codeToDisplayName(codes[i]);
    }
    return out;
  }

  private String codeToDisplayName(String code) {
    if (de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR) {
      return switch (code) {
        case "en" -> "English (Paddle)";
        case "latin" -> "Latin script (Paddle)";
        case "eslav" -> "East Slavic (Paddle)";
        case "cyrillic" -> "Cyrillic script (Paddle)";
        case "arabic" -> "Arabic script (Paddle)";
        case "devanagari" -> "Devanagari script (Paddle)";
        case "th" -> "Thai (Paddle)";
        case "el" -> "Greek (Paddle)";
        case "zh" -> "Chinese/Japanese/Korean (Paddle)";
        default -> code + " (Paddle)";
      };
    }
    // Map common Tesseract 3-letter codes to 2-letter BCP-47 where possible, for localization
    String two;
    switch (code) {
      case "eng":
        two = "en";
        break;
      case "deu":
        two = "de";
        break;
      case "fra":
        two = "fr";
        break;
      case "ita":
        two = "it";
        break;
      case "spa":
        two = "es";
        break;
      case "por":
        two = "pt";
        break;
      case "nld":
        two = "nl";
        break;
      case "pol":
        two = "pl";
        break;
      case "ces":
        two = "cs";
        break;
      case "slk":
        two = "sk";
        break;
      case "hun":
        two = "hu";
        break;
      case "ron":
        two = "ro";
        break;
      case "dan":
        two = "da";
        break;
      case "nor":
        two = "no";
        break;
      case "swe":
        two = "sv";
        break;
      case "rus":
        two = "ru";
        break;
      case "tha":
        two = "th";
        break;
      case "fas":
        two = "fa";
        break;
      case "ara":
        two = "ar";
        break;
      case "hin":
        two = "hi";
        break;
      case "tur":
        two = "tr";
        break;
      case "chi_sim":
        return appendVariantLabel("Chinese (Simplified)", code);
      case "chi_tra":
        return appendVariantLabel("Chinese (Traditional)", code);
      default:
        // Fallback: try first two letters
        if (code != null && code.length() >= 2) {
          two = code.substring(0, 2);
        } else {
          two = "en";
        }
    }
    String baseName;
    try {
      java.util.Locale loc = java.util.Locale.forLanguageTag(two);
      baseName = loc.getDisplayLanguage(java.util.Locale.getDefault());
    } catch (Throwable ignore) {
      baseName = code;
    }
    return appendVariantLabel(baseName, code);
  }

  private String appendVariantLabel(String baseName, String code) {
    String variant = determineModelVariant(code);
    return baseName + " (" + variant + ")";
  }

  /**
   * Determine whether the given language code uses the flavor's built-in Fast model or a higher
   * quality model managed by the flavor-specific OCR model manager.
   */
  private String determineModelVariant(String code) {
    return OcrModelManager.isUsingBestModel(requireContext(), code) ? "Best" : "Fast";
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

  /**
   * Heuristic for the adaptive Quick→Robust switch: returns true when the input bitmap shows
   * strongly uneven illumination (shadow/lighting gradient typical for phone photos), where global
   * Otsu in Quick mode would likely clip and lose text.
   *
   * <p>The actual decision logic lives in {@link
   * de.schliweb.makeacopy.utils.ocr.UnevenLightingPolicy} so it can be unit-tested without an
   * Android device. This method only handles bitmap downsampling and pixel extraction.
   */
  private static boolean hasUnevenLighting(android.graphics.Bitmap b) {
    if (b == null || b.isRecycled()) return false;
    try {
      int w = b.getWidth();
      int h = b.getHeight();
      if (w < 4 || h < 4) return false;
      // Downsample to keep this cheap regardless of input size.
      int target = 192;
      int longSide = Math.max(w, h);
      double scale = longSide > target ? (double) target / (double) longSide : 1.0;
      int dw = Math.max(4, (int) Math.round(w * scale));
      int dh = Math.max(4, (int) Math.round(h * scale));
      android.graphics.Bitmap small =
          (dw == w && dh == h) ? b : android.graphics.Bitmap.createScaledBitmap(b, dw, dh, true);
      try {
        int n = dw * dh;
        int[] px = new int[n];
        small.getPixels(px, 0, dw, 0, 0, dw, dh);
        return de.schliweb.makeacopy.utils.ocr.UnevenLightingPolicy.isUneven(px, dw, dh);
      } finally {
        if (small != b && !small.isRecycled()) small.recycle();
      }
    } catch (Throwable t) {
      // On any failure, be conservative and do not trigger the adaptive switch.
      return false;
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

  private void showOcrPrepModeDialog() {
    android.view.LayoutInflater inflater = android.view.LayoutInflater.from(requireContext());
    android.view.View view = inflater.inflate(R.layout.dialog_ocr_prep_mode, null);

    android.widget.RadioGroup rg = view.findViewById(R.id.rg_ocr_modes);
    android.widget.RadioButton rbOriginal = view.findViewById(R.id.rbtn_mode_original);
    android.widget.RadioButton rbRobust = view.findViewById(R.id.rbtn_mode_robust);
    android.widget.CheckBox cbOcrAuto =
        view.findViewById(R.id.checkbox_ocr_auto_rotate_apply_export_dialog);
    android.widget.CheckBox cbOcrPostProc =
        view.findViewById(R.id.checkbox_ocr_post_processing_dialog);
    android.widget.CheckBox cbLayoutAnalysis =
        view.findViewById(R.id.checkbox_layout_analysis_dialog);
    android.widget.CheckBox cbPaddleBestOcr =
        view.findViewById(R.id.checkbox_paddle_best_ocr_dialog);
    android.widget.RadioButton rbPaddle = view.findViewById(R.id.rbtn_mode_paddle);
    final boolean fixedPaddleMode = de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR;

    if (fixedPaddleMode) {
      rg.setVisibility(android.view.View.GONE);
    }

    // Only show layout analysis checkbox if feature flag is enabled
    boolean layoutFeatureEnabled = FeatureFlags.isLayoutAnalysisEnabled();
    cbLayoutAnalysis.setVisibility(
        layoutFeatureEnabled ? android.view.View.VISIBLE : android.view.View.GONE);
    cbPaddleBestOcr.setVisibility(
        fixedPaddleMode ? android.view.View.VISIBLE : android.view.View.GONE);

    // PaddleOCR (experimental): visible as third radio option only when feature flag
    // is enabled and ABI is arm64-v8a (see PaddleOcrPrefs.isToggleVisible).
    final boolean paddleToggleVisible = PaddleOcrPrefs.isToggleVisible();
    rbPaddle.setVisibility(
        paddleToggleVisible ? android.view.View.VISIBLE : android.view.View.GONE);

    int mode = Math.max(0, Math.min(OCR_MODE_PADDLE, getSelectedOcrMode()));
    // Quick mode is hidden in the picker (see dialog_ocr_prep_mode.xml: rbtn_mode_quick
    // is gone). Treat any lingering Quick selection as Robust for the UI state.
    if (mode == OCR_MODE_QUICK) mode = OCR_MODE_ROBUST;
    // PaddleOCR is only valid when the toggle is visible; otherwise fall back to Robust.
    if (mode == OCR_MODE_PADDLE && !fixedPaddleMode && !paddleToggleVisible) {
      mode = OCR_MODE_ROBUST;
    }
    if (mode == OCR_MODE_ORIGINAL) rbOriginal.setChecked(true);
    else if (mode == OCR_MODE_PADDLE) rbPaddle.setChecked(true);
    else rbRobust.setChecked(true);
    final int initialMode = mode;

    boolean ocrAutoRotateApply = false;
    boolean ocrPostProcessing = true; // default ON
    boolean layoutAnalysis = false; // default OFF
    boolean paddleBestOcr = false; // default OFF
    try {
      android.content.SharedPreferences p =
          requireContext()
              .getSharedPreferences("export_options", android.content.Context.MODE_PRIVATE);
      ocrAutoRotateApply = p.getBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, false);
      ocrPostProcessing = p.getBoolean(BUNDLE_OCR_POST_PROCESSING, true);
      layoutAnalysis = p.getBoolean(BUNDLE_LAYOUT_ANALYSIS, false);
      paddleBestOcr = p.getBoolean(BUNDLE_PADDLE_BEST_OCR, false);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    cbOcrAuto.setChecked(ocrAutoRotateApply);
    cbOcrPostProc.setChecked(ocrPostProcessing);
    cbOcrPostProc.setVisibility(
        initialMode == OCR_MODE_PADDLE ? android.view.View.GONE : android.view.View.VISIBLE);
    cbLayoutAnalysis.setChecked(layoutAnalysis && layoutFeatureEnabled);
    cbPaddleBestOcr.setChecked(fixedPaddleMode && paddleBestOcr);
    rg.setOnCheckedChangeListener(
        (group, checkedId) ->
            cbOcrPostProc.setVisibility(
                checkedId == R.id.rbtn_mode_paddle
                    ? android.view.View.GONE
                    : android.view.View.VISIBLE));

    AlertDialog dlg =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                fixedPaddleMode ? R.string.ocr_models_manage : R.string.ocr_choose_prep_mode_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(
                R.string.ok,
                (d, w) -> {
                  int selectedMode = OCR_MODE_PADDLE;
                  if (!fixedPaddleMode) {
                    selectedMode = OCR_MODE_ROBUST; // default Robust
                    int checkedId = rg.getCheckedRadioButtonId();
                    if (checkedId == R.id.rbtn_mode_original) selectedMode = OCR_MODE_ORIGINAL;
                    else if (checkedId == R.id.rbtn_mode_quick) selectedMode = OCR_MODE_ROBUST;
                    else if (checkedId == R.id.rbtn_mode_robust) selectedMode = OCR_MODE_ROBUST;
                    else if (checkedId == R.id.rbtn_mode_paddle && paddleToggleVisible)
                      selectedMode = OCR_MODE_PADDLE;

                    setSelectedOcrMode(selectedMode);
                  }

                  boolean postProcessingVisible = selectedMode != OCR_MODE_PADDLE;
                  boolean postProcessingSelected =
                      postProcessingVisible && cbOcrPostProc.isChecked();
                  try {
                    android.content.SharedPreferences p =
                        requireContext()
                            .getSharedPreferences(
                                "export_options", android.content.Context.MODE_PRIVATE);
                    android.content.SharedPreferences.Editor editor =
                        p.edit()
                            .putBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, cbOcrAuto.isChecked())
                            .putBoolean(BUNDLE_LAYOUT_ANALYSIS, cbLayoutAnalysis.isChecked())
                            .putBoolean(
                                BUNDLE_PADDLE_BEST_OCR,
                                fixedPaddleMode && cbPaddleBestOcr.isChecked());
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

                  CharSequence[] modes =
                      new CharSequence[] {
                        getString(R.string.ocr_mode_original),
                        getString(R.string.ocr_mode_quick),
                        getString(R.string.ocr_mode_robust),
                        getString(R.string.ocr_mode_paddle)
                      };
                  // Show toast including selected mode AND current status of OCR options.
                  String modeMsg =
                      fixedPaddleMode
                          ? getString(R.string.ocr_prep_mode_set, modes[OCR_MODE_PADDLE])
                          : getString(R.string.ocr_prep_mode_set, modes[selectedMode]);
                  String autoLabel = getString(R.string.opt_ocr_auto_rotate_apply_export);
                  String autoState = cbOcrAuto.isChecked() ? "[ON]" : "[OFF]";
                  String postProcLabel = getString(R.string.opt_ocr_post_processing);
                  String postProcState = postProcessingSelected ? "[ON]" : "[OFF]";
                  StringBuilder toastMsg =
                      new StringBuilder(modeMsg)
                          .append("\n")
                          .append(autoLabel)
                          .append(": ")
                          .append(autoState);
                  if (postProcessingVisible) {
                    toastMsg.append("\n").append(postProcLabel).append(": ").append(postProcState);
                  }
                  // Only show layout analysis in toast if feature is enabled
                  if (FeatureFlags.isLayoutAnalysisEnabled()) {
                    String layoutLabel = getString(R.string.opt_layout_analysis);
                    String layoutState = cbLayoutAnalysis.isChecked() ? "[ON]" : "[OFF]";
                    toastMsg.append("\n").append(layoutLabel).append(": ").append(layoutState);
                  }
                  if (fixedPaddleMode) {
                    String paddleBestLabel = getString(R.string.opt_paddle_best_ocr);
                    String paddleBestState = cbPaddleBestOcr.isChecked() ? "[ON]" : "[OFF]";
                    toastMsg
                        .append("\n")
                        .append(paddleBestLabel)
                        .append(": ")
                        .append(paddleBestState);
                  }
                  UIUtils.showToast(requireContext(), toastMsg.toString(), Toast.LENGTH_SHORT);
                  prepareReprocessAfterModelChange();
                })
            .create();
    dlg.setOnShowListener(
        d -> DialogUtils.improveAlertDialogButtonContrastForNight(dlg, requireContext()));
    dlg.show();
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
                    String display = codeToDisplayName(langCode);
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
      runningOcr =
          ocrExecutor.submit(
              () -> {
                final String LP = "[OCR_LOG] ";
                long t0 = System.nanoTime();
                OCRHelper localHelper = null;
                try {
                  Log.d(TAG, LP + "BG thread=" + Thread.currentThread().getName());
                  // Prepare bitmap (orientation corrections)
                  Log.d(TAG, LP + "Preparing image for OCR - orientation handling");
                  Bitmap src = imageBitmap;

                  // Note: We ignore capture/EXIF rotation here because the app is locked to
                  // portrait
                  // (AndroidManifest: android:screenOrientation="portrait") and handles
                  // configChanges for orientation/screenSize/screenLayout. In this setup,
                  // getCaptureRotationDegrees() is effectively always 0 and evaluating it adds no
                  // value.
                  // If orientation handling changes in the future, restore compensation here if
                  // needed.
                  Bitmap srcNoCaptureRotation = src; // alias for clarity
                  src = srcNoCaptureRotation;

                  // Apply user-requested rotation (after crop, before OCR)
                  int userDeg = 0;
                  Integer ur = cropViewModel.getUserRotationDegrees().getValue();
                  if (ur != null) userDeg = ((ur % 360) + 360) % 360;
                  if (userDeg != 0) {
                    src = rotateBitmap(src, userDeg);
                  }

                  // We will try OCR in 90° steps (0, 90, 180, 270) to guard against wrong user
                  // rotation
                  // Start with current src (already including userDeg). For each attempt, rotate
                  // extra and pick best by meanConfidence, then by text length.

                  if (ocrCancelled.get()) {
                    Log.w(TAG, LP + "Cancelled before OCR init");
                    postError("Cancelled");
                    return;
                  }

                  // Fresh Tesseract per job
                  localHelper = ocrHelperProvider.get();
                  // 1 job = 1 engine instance. No automatic reinitialization per run.
                  try {
                    localHelper.setReinitPerRun(false);
                  } catch (Throwable ignore) {
                    // Best-effort; failure is non-critical
                  }

                  String lang = ocrViewModel.getLanguage().getValue();
                  if (lang == null || lang.isEmpty()) lang = "eng";
                  Log.d(TAG, LP + "Language requested=" + lang);

                  try {
                    // Ensure language is set BEFORE init so Tesseract loads the correct traineddata
                    localHelper.setLanguage(lang);
                  } catch (Throwable t) {
                    Log.e(TAG, LP + "Failed to set language " + lang, t);
                  }

                  // Detect if Best model is used and configure OCRHelper accordingly BEFORE init
                  try {
                    boolean useBest = OcrModelManager.isUsingBestModel(requireContext(), lang);
                    localHelper.setUseBestModelSettings(useBest);
                    Log.d(TAG, LP + "Best model settings enabled=" + useBest + " for lang=" + lang);
                  } catch (Throwable t) {
                    Log.w(TAG, LP + "Failed to detect/set Best model settings", t);
                  }

                  try {
                    android.content.SharedPreferences p =
                        requireContext()
                            .getSharedPreferences(
                                "export_options", android.content.Context.MODE_PRIVATE);
                    boolean paddleBestOcr =
                        de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR
                            && p.getBoolean(BUNDLE_PADDLE_BEST_OCR, false);
                    localHelper.setPaddleHighQualityDetectionEnabled(paddleBestOcr);
                    Log.d(TAG, LP + "Paddle best OCR enabled=" + paddleBestOcr);
                  } catch (Throwable t) {
                    Log.w(TAG, LP + "Failed to detect/set Paddle best OCR", t);
                  }

                  long tInit0 = System.nanoTime();
                  boolean initOk = false;
                  try {
                    initOk = localHelper.initTesseract();
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
                  if (!initOk) {
                    postError("Engine not initialized");
                    return;
                  }

                  // Tune Tesseract PSM based on recognition mode (Robust benefits from PSM_AUTO)
                  try {
                    int prepMode = getSelectedOcrMode();
                    OcrPageSegmentationMode psm =
                        (prepMode == OCR_MODE_ROBUST)
                            ? OcrPageSegmentationMode.AUTO
                            : OcrPageSegmentationMode.SINGLE_BLOCK;
                    localHelper.setPageSegmentationMode(psm);
                  } catch (Throwable ignore) {
                    // Best-effort; failure is non-critical
                  }

                  // Try OCR rotations only when Auto‑Rotate is enabled. Otherwise, use current
                  // orientation only.
                  boolean allowOcrAutoRotate = false;
                  boolean useLayoutAnalysis = false;
                  try {
                    android.content.SharedPreferences p =
                        requireContext()
                            .getSharedPreferences(
                                "export_options", android.content.Context.MODE_PRIVATE);
                    allowOcrAutoRotate = p.getBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, false);
                    // Layout analysis requires both feature flag AND user preference
                    useLayoutAnalysis =
                        FeatureFlags.isLayoutAnalysisEnabled()
                            && p.getBoolean(BUNDLE_LAYOUT_ANALYSIS, false);
                  } catch (Throwable ignore) {
                    // Best-effort; failure is non-critical
                  }
                  final boolean layoutAnalysisEnabled = useLayoutAnalysis;

                  // When disabled, restrict to a single attempt at the current orientation
                  // (extra=0)
                  int[] extraRots =
                      allowOcrAutoRotate ? new int[] {0, 90, 180, 270} : new int[] {0};
                  OCRHelper.OcrResultWords bestResult = null;
                  OCRViewModel.OcrTransform bestTx = null;
                  int bestRot = 0;
                  // Result of the 0° attempt, kept for the vertical-layout guard below.
                  OCRHelper.OcrResultWords zeroResult = null;
                  OCRViewModel.OcrTransform zeroTx = null;

                  for (int extra : extraRots) {
                    if (ocrCancelled.get()) {
                      Log.w(TAG, LP + "Cancelled before OCR run (extraRot=" + extra + ")");
                      postError("Cancelled");
                      return;
                    }

                    Bitmap rotated = (extra == 0) ? src : rotateBitmap(src, extra);

                    // Apply selected recognition mode (preprocessing).
                    // Adaptive behavior:
                    //   1) QUICK + uneven lighting  -> upgrade to ROBUST (Quick is now a wrapper
                    //      around the robust grayscale pipeline anyway, but the explicit upgrade
                    //      keeps the intent clear and also activates path (2)).
                    //   2) ROBUST + uneven lighting -> additionally trigger the Sauvola/Retinex
                    //      binary branch (binaryOutput=true) at the page level AND for every
                    //      region in the layout-analysis path (via OCRHelper.setForceBinaryRobust).
                    //      Otsu clipping on heavy shadows is the dominant failure mode for
                    //      grayscale-only preprocessing on phone photos; the binary branch with
                    //      Sauvola/Retinex is the correct response.
                    // ORIGINAL is always honored as-is.
                    int mode = getSelectedOcrMode();
                    int effectiveMode = mode;
                    boolean unevenLighting = hasUnevenLighting(rotated);
                    if (mode == OCR_MODE_QUICK && unevenLighting) {
                      effectiveMode = OCR_MODE_ROBUST;
                      Log.d(
                          TAG,
                          LP
                              + "Adaptive: QUICK -> ROBUST (uneven lighting detected, extraRot="
                              + extra
                              + ")");
                    }
                    boolean forceBinary = (effectiveMode == OCR_MODE_ROBUST) && unevenLighting;
                    if (forceBinary) {
                      Log.d(
                          TAG,
                          LP
                              + "Adaptive: ROBUST forces binary preprocessing (uneven lighting,"
                              + " extraRot="
                              + extra
                              + ")");
                    }

                    Bitmap inputForOcr;
                    if (effectiveMode == OCR_MODE_ORIGINAL || effectiveMode == OCR_MODE_PADDLE) {
                      // PADDLE: skip preprocessing entirely; the Paddle engine consumes the
                      // original (rotated) bitmap. If the engine cannot be initialized at
                      // runtime, OCRHelper transparently falls back to Tesseract on the same
                      // un-preprocessed bitmap, matching ORIGINAL behavior.
                      inputForOcr = rotated;
                    } else if (effectiveMode == OCR_MODE_QUICK) {
                      inputForOcr = OpenCVUtils.prepareForOCRQuick(rotated);
                    } else { // OCR_MODE_ROBUST
                      // Default: grayscale output preserves fine details and holes in letters
                      // (e.g. 'o'), avoiding over-aggressive binarization artifacts that can
                      // cause substitutions like 'Oktober' → 'Okteber'. When the adaptive
                      // heuristic detects uneven lighting we switch to the binary Sauvola/Retinex
                      // branch which handles shadows much better.
                      inputForOcr =
                          OpenCVUtils.prepareForOCR(rotated, /*binaryOutput*/ forceBinary);
                    }
                    // Keep region-OCR (layout analysis path) in sync with the page-level mode and
                    // the adaptive binary trigger.
                    try {
                      localHelper.setRecognitionMode(effectiveMode);
                      localHelper.setForceBinaryRobust(forceBinary);
                    } catch (Throwable ignore) {
                      // Best-effort; failure is non-critical
                    }
                    if (inputForOcr == null) {
                      Log.w(
                          TAG,
                          "prepareForOCR returned null (extraRot=" + extra + "), skipping attempt");
                      continue;
                    }

                    OCRViewModel.OcrTransform tx =
                        new OCRViewModel.OcrTransform(
                            rotated.getWidth(),
                            rotated.getHeight(),
                            inputForOcr.getWidth(),
                            inputForOcr.getHeight(),
                            inputForOcr.getWidth() / (float) rotated.getWidth(),
                            inputForOcr.getHeight() / (float) rotated.getHeight(),
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

                    // Run OCR (use layout analysis if enabled)
                    OCRHelper.OcrResultWords r;
                    if (layoutAnalysisEnabled) {
                      OCRHelper.OcrResultWithLayout layoutResult =
                          localHelper.runOcrWithLayout(inputForOcr);
                      // Collect all words from all regions, preserving layout structure
                      List<RecognizedWord> allWords = new ArrayList<>();
                      int regionIdx = 1;
                      for (OCRHelper.RegionOcrResult regionResult : layoutResult.regionResults) {
                        if (regionResult.ocrResult() != null
                            && regionResult.ocrResult().words != null) {
                          for (RecognizedWord w : regionResult.ocrResult().words) {
                            w.setBlockId(regionIdx);
                          }
                          allWords.addAll(regionResult.ocrResult().words);
                        }
                        regionIdx++;
                      }
                      r =
                          new OCRHelper.OcrResultWords(
                              layoutResult.text, layoutResult.meanConfidence, allWords);

                      // Full-page fallback: if layout analysis produced too few words or a
                      // very low mean confidence, the page was likely mis-segmented (false
                      // table detection, sparse-text PSM on the main body, …). Run one
                      // additional full-page OCR pass with PSM=AUTO and keep whichever
                      // result has more recognized words. This is a no-op cost on
                      // documents where layout analysis works well, since the trigger
                      // (OcrFallbackPolicy) does not fire there.
                      int laWords0 = r.words != null ? r.words.size() : 0;
                      int laConf0 = r.meanConfidence != null ? r.meanConfidence : 0;
                      if (de.schliweb.makeacopy.utils.ocr.OcrFallbackPolicy
                          .shouldRunFullPageFallback(laWords0, laConf0)) {
                        Log.d(
                            TAG,
                            LP
                                + "Layout-analysis poor (words="
                                + laWords0
                                + ", meanConf="
                                + laConf0
                                + "), running full-page fallback OCR");
                        OCRHelper.OcrResultWords fb = localHelper.runOcrWithRetry(inputForOcr);
                        if (fb != null) {
                          int fbWords = fb.words != null ? fb.words.size() : 0;
                          int fbConf = fb.meanConfidence != null ? fb.meanConfidence : 0;
                          // Prefer the fallback if it found more words OR a clearly higher
                          // mean confidence. Word count is the dominant signal because the
                          // problem the fallback solves is "too few words".
                          boolean fbBetter =
                              fbWords > laWords0 || (fbWords >= laWords0 && fbConf > laConf0 + 1);
                          Log.d(
                              TAG,
                              LP
                                  + "Fallback result: words="
                                  + fbWords
                                  + ", meanConf="
                                  + fbConf
                                  + ", taken="
                                  + fbBetter);
                          if (fbBetter) {
                            r = fb;
                          }
                        }
                      }
                    } else {
                      r = localHelper.runOcrWithRetry(inputForOcr);
                    }

                    if (ocrCancelled.get()) {
                      Log.w(TAG, LP + "Cancelled after OCR run (extraRot=" + extra + ")");
                      postError("Cancelled");
                      return;
                    }

                    // Keep the 0° attempt for the vertical-layout guard after the loop.
                    if (extra == 0) {
                      zeroResult = r;
                      zeroTx = tx;
                    }

                    // Early-exit: if the first attempt (extra=0) is already strong enough, skip
                    // other rotations. Decision delegated to OcrEarlyExitPolicy so the gate is
                    // unit-testable and tuned against real production samples.
                    if (extra == 0) {
                      int mc0 = (r.meanConfidence != null ? r.meanConfidence : 0);
                      int wc0 = (r.words != null ? r.words.size() : 0);
                      int tl0 = (r.text != null ? r.text.length() : 0);
                      if (de.schliweb.makeacopy.utils.ocr.OcrEarlyExitPolicy.shouldExit(
                          mc0, wc0, tl0)) {
                        bestResult = r;
                        bestTx = tx;
                        bestRot = 0;
                        Log.d(
                            TAG,
                            LP
                                + "Early-exit: meanConf="
                                + mc0
                                + " (>= "
                                + de.schliweb.makeacopy.utils.ocr.OcrEarlyExitPolicy
                                    .DEFAULT_MIN_MEAN_CONF
                                + "), words="
                                + wc0
                                + " (>= "
                                + de.schliweb.makeacopy.utils.ocr.OcrEarlyExitPolicy
                                    .DEFAULT_MIN_WORDS
                                + "), textLen="
                                + tl0
                                + " (>= "
                                + de.schliweb.makeacopy.utils.ocr.OcrEarlyExitPolicy
                                    .DEFAULT_MIN_TEXT_LEN
                                + "), skipping further rotations");
                        break;
                      }
                    }

                    // Choose best deterministically:
                    // 1) content presence (words/text)
                    // 2) mean confidence
                    // 3) content size (words count, then text length)
                    boolean take;
                    if (bestResult == null) {
                      take = true;
                    } else {
                      boolean hasWords = r.words != null && !r.words.isEmpty();
                      boolean hasText = r.text != null && !r.text.trim().isEmpty();
                      boolean hasContent = hasWords || hasText;

                      boolean bestHasWords =
                          bestResult.words != null && !bestResult.words.isEmpty();
                      boolean bestHasText =
                          bestResult.text != null && !bestResult.text.trim().isEmpty();
                      boolean bestHasContent = bestHasWords || bestHasText;

                      if (hasContent != bestHasContent) {
                        take = hasContent; // non-empty beats empty
                      } else {
                        float mc = (r.meanConfidence != null ? r.meanConfidence : 0f);
                        float bestMc =
                            (bestResult.meanConfidence != null ? bestResult.meanConfidence : 0f);
                        if (mc > bestMc + 0.01f) { // small epsilon
                          take = true;
                        } else if (Math.abs(mc - bestMc) <= 0.01f) {
                          int wc = (r.words != null ? r.words.size() : 0);
                          int bestWc = (bestResult.words != null ? bestResult.words.size() : 0);
                          if (wc != bestWc) {
                            take = wc > bestWc;
                          } else {
                            int len = (r.text != null ? r.text.length() : 0);
                            int bestLen = (bestResult.text != null ? bestResult.text.length() : 0);
                            take = len > bestLen;
                          }
                        } else {
                          take = false;
                        }
                      }
                    }

                    if (take) {
                      bestResult = r;
                      bestTx = tx;
                      bestRot = extra;
                    }
                  }

                  // Vertical-layout guard: for genuinely vertical documents (CJK top-to-bottom
                  // columns) the 90°/270° attempts can "win" by confidence because vertical
                  // columns then look like horizontal lines to the detector — but the page is
                  // correctly oriented. When the 0° attempt already produced content whose
                  // boxes are predominantly tall, prefer the 0° result and do not rotate.
                  if (zeroResult != null && zeroTx != null) {
                    boolean zeroHasContent =
                        (zeroResult.words != null && !zeroResult.words.isEmpty())
                            || (zeroResult.text != null && !zeroResult.text.trim().isEmpty());
                    if (de.schliweb.makeacopy.utils.ocr.VerticalTextLayoutPolicy
                        .shouldPreferZeroRotation(bestRot, zeroHasContent, zeroResult.words)) {
                      Log.i(
                          TAG,
                          LP
                              + "Vertical text layout detected at 0°; overriding auto-rotate"
                              + " (was extra="
                              + bestRot
                              + "°)");
                      bestResult = zeroResult;
                      bestTx = zeroTx;
                      bestRot = 0;
                    }
                  }

                  if (bestResult == null || bestTx == null) {
                    postError("OCR failed (no result)");
                    return;
                  }

                  // Push transform of best attempt to VM on UI thread
                  OCRViewModel.OcrTransform finalTx = bestTx;
                  final int bestRotFinal = bestRot;
                  final boolean finalAllowOcrAutoRotate = allowOcrAutoRotate;
                  runOnUiThreadSafe(
                      () -> {
                        ocrViewModel.setTransform(finalTx);
                        try {
                          // Store best OCR rotation (relative extra rotation) for optional export
                          // alignment
                          if (cropViewModel != null) {
                            // Only persist the computed rotation if the feature is enabled;
                            // otherwise reset to 0
                            cropViewModel.setBestOcrRotationDegrees(
                                finalAllowOcrAutoRotate ? bestRotFinal : 0);
                          }
                        } catch (Throwable ignore) {
                          // Best-effort; failure is non-critical
                        }
                      });

                  long durMs = (System.nanoTime() - t0) / 1_000_000L;
                  // Never persist UI placeholder strings as OCR output.
                  // Persist only real OCR output; use "" when OCR returned nothing.
                  String ocrText =
                      (bestResult.text == null || bestResult.text.trim().isEmpty())
                          ? ""
                          : bestResult.text;
                  List<RecognizedWord> ocrWords =
                      (bestResult.words != null) ? bestResult.words : new ArrayList<>();

                  // Apply post-processing to correct common OCR errors (including dictionary-based
                  // correction)
                  // Only if the option is enabled (default: ON)
                  boolean postProcessingEnabled = true;
                  try {
                    android.content.SharedPreferences pp =
                        requireContext()
                            .getSharedPreferences(
                                "export_options", android.content.Context.MODE_PRIVATE);
                    postProcessingEnabled = pp.getBoolean(BUNDLE_OCR_POST_PROCESSING, true);
                  } catch (Throwable ignore) {
                    // Best-effort; failure is non-critical
                  }
                  boolean selectedPaddleMode = getSelectedOcrMode() == OCR_MODE_PADDLE;
                  if (postProcessingEnabled && !selectedPaddleMode) {
                    try {
                      // Process words with dictionary - this is the single source of truth
                      ocrWords =
                          OCRPostProcessor.processWithDictionary(ocrWords, lang, dictionaryManager);
                      // Derive text from processed words instead of processing text separately
                      ocrText = OCRPostProcessor.wordsToText(ocrWords);
                      if (ocrText == null || ocrText.trim().isEmpty()) ocrText = "";
                      // Log quality statistics
                      OCRPostProcessor.OcrQualityStats stats =
                          OCRPostProcessor.analyzeQuality(ocrWords);
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
                    ocrText = OCRPostProcessor.wordsToText(ocrWords); // TODO
                    if (ocrText == null || ocrText.trim().isEmpty()) ocrText = "";
                  }

                  // Create final variables for lambda
                  final String finalText = ocrText;
                  final List<RecognizedWord> words = ocrWords;

                  int appliedExtraRot = bestRot; // for logging only
                  Integer bestMeanConf = bestResult.meanConfidence;
                  boolean bestHasWords = bestResult.words != null && !bestResult.words.isEmpty();
                  boolean bestHasText =
                      bestResult.text != null && !bestResult.text.trim().isEmpty();
                  boolean bestHasContent = bestHasWords || bestHasText;
                  Log.d(
                      TAG,
                      LP
                          + "Best rotation extra="
                          + appliedExtraRot
                          + "°, meanConf="
                          + bestMeanConf
                          + ", hasContent="
                          + bestHasContent
                          + ", words="
                          + (bestResult.words != null ? bestResult.words.size() : 0)
                          + ", textLen="
                          + (bestResult.text == null ? 0 : bestResult.text.length()));

                  final Integer meanConfFinal = bestMeanConf;
                  runOnUiThreadSafe(
                      () -> {
                        ocrViewModel.setWords(words);
                        ocrViewModel.finishSuccess(finalText, words, durMs, meanConfFinal, finalTx);
                        // If Auto‑Rotate is enabled, show the found rotation to the user
                        try {
                          android.content.SharedPreferences p =
                              requireContext()
                                  .getSharedPreferences(
                                      "export_options", android.content.Context.MODE_PRIVATE);
                          boolean apply = p.getBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, false);
                          // UX guard: never show a high confidence score for an empty OCR result.
                          // Determine content based on the final values persisted to the ViewModel.
                          boolean hasWordsFinal = words != null && !words.isEmpty();
                          boolean hasTextFinal = finalText != null && !finalText.trim().isEmpty();
                          boolean hasContentFinal = hasWordsFinal || hasTextFinal;
                          int score =
                              hasContentFinal ? (meanConfFinal != null ? meanConfFinal : -1) : -1;

                          // UX hint: If no text was detected, show a neutral toast once per OCR
                          // run.
                          // In this case we also suppress any score/rotation toast to avoid
                          // multiple toasts.
                          if (!hasContentFinal) {
                            UIUtils.showToast(
                                requireContext(),
                                getString(R.string.ocr_no_text_detected),
                                Toast.LENGTH_SHORT);
                            return;
                          }
                          // When enabled, also apply the detected rotation to the current scan for
                          // export,
                          // respecting the unified rotation model (apply in-memory; persist will
                          // bake).
                          if (apply) {
                            try {
                              // Add bestRotFinal to the current user rotation in CropViewModel
                              if (cropViewModel != null) {
                                Integer cur = null;
                                try {
                                  cur = cropViewModel.getUserRotationDegrees().getValue();
                                } catch (Throwable ignore) {
                                  // Best-effort; failure is non-critical
                                }
                                int curDeg = (cur == null) ? 0 : cur.intValue();
                                int newDeg = ((curDeg + bestRotFinal) % 360 + 360) % 360;
                                try {
                                  cropViewModel.setUserRotationDegrees(newDeg);
                                } catch (Throwable ignore) {
                                  // Best-effort; failure is non-critical
                                }
                                // We have applied the OCR suggestion; clear the helper to avoid
                                // re-applying later.
                                try {
                                  cropViewModel.setBestOcrRotationDegrees(0);
                                } catch (Throwable ignore) {
                                  // Best-effort; failure is non-critical
                                }
                              }
                            } catch (Throwable ignore) {
                              // Best-effort; failure is non-critical
                            }
                          }
                          if (apply) {
                            // If we know the score, show rotation + score combined; otherwise, show
                            // rotation only.
                            if (score >= 0) {
                              UIUtils.showToast(
                                  requireContext(),
                                  getString(
                                      R.string.ocr_found_rotation_with_score, bestRotFinal, score),
                                  Toast.LENGTH_SHORT);
                            } else {
                              UIUtils.showToast(
                                  requireContext(),
                                  getString(R.string.ocr_found_rotation, bestRotFinal),
                                  Toast.LENGTH_SHORT);
                            }
                          } else if (score >= 0) {
                            // Auto‑Rotate not applied, but still useful to show the OCR score.
                            UIUtils.showToast(
                                requireContext(),
                                getString(R.string.ocr_score, score),
                                Toast.LENGTH_SHORT);
                          }
                        } catch (Throwable ignore) {
                          // Best-effort; failure is non-critical
                        }
                      });

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
              });
    } catch (java.util.concurrent.RejectedExecutionException ex) {
      UIUtils.showToast(requireContext(), "OCR service is shutting down", Toast.LENGTH_SHORT);
      Log.w(TAG, "performOCR: RejectedExecutionException (executor shutting down)", ex);
      ocrViewModel.finishError("Executor shutdown");
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

  /**
   * Rotates the given Bitmap by the specified degree in a clockwise direction. If the degrees are a
   * multiple of 360, the original Bitmap is returned unchanged.
   *
   * @param src The Bitmap to be rotated. Must not be null.
   * @param degreesCW The number of degrees to rotate the Bitmap clockwise. Values outside the range
   *     [0, 360) will be normalized.
   * @return A new rotated Bitmap object, or the original Bitmap if no rotation is applied.
   */
  private static Bitmap rotateBitmap(Bitmap src, int degreesCW) {
    int deg = ((degreesCW % 360) + 360) % 360;
    if (deg == 0) return src;
    Matrix m = new Matrix();
    m.postRotate(deg);
    return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
  }
}
