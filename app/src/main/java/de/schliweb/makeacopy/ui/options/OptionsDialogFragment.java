/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.options;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.ui.DocumentActivity;
import de.schliweb.makeacopy.ui.export.ExportOptionsPanel;
import de.schliweb.makeacopy.ui.library.LibraryOptionsPanel;
import de.schliweb.makeacopy.ui.ocr.OcrOptionsPanel;
import de.schliweb.makeacopy.utils.ui.AppLanguage;
import de.schliweb.makeacopy.utils.ui.AppLock;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import de.schliweb.makeacopy.utils.ui.ScreenProtection;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import java.util.List;

/**
 * The options dialog of the camera screen, which is the start of the workflow and therefore the one
 * place that shows every setting: Scan, Camera, OCR, Export, Library and App. The OCR and Export
 * groups are the same panels the OCR and Export screens open, so each setting exists once.
 */
public class OptionsDialogFragment extends DialogFragment {

  public static final String REQUEST_KEY = "camera_options";
  public static final String BUNDLE_SKIP_OCR = "skip_ocr";
  public static final String BUNDLE_ANALYSIS_ENABLED = "analysis_enabled";
  public static final String BUNDLE_SKIP_CROPPING = "skip_cropping";
  public static final String BUNDLE_SKIP_EDGE_DETECTION = "skip_edge_detection";
  public static final String BUNDLE_ACCESSIBILITY_MODE = "accessibility_mode";
  public static final String BUNDLE_EXPOSURE_COMPENSATION = "exposure_compensation_enabled";
  public static final String BUNDLE_MANUAL_FOCUS = "manual_focus_enabled";
  public static final String BUNDLE_FOCUS_QUALITY_INDICATOR = "focus_quality_indicator_enabled";

  /** Whether the camera may ask to turn on the flashlight in low light. Default: yes. */
  public static final String BUNDLE_LOW_LIGHT_PROMPT = "low_light_prompt_enabled";

  private static final String FRAGMENT_TAG = "OptionsDialogFragment";
  private static final String ARG_SECTION = "section";
  private static final String SAVED_EXPANDED = "expanded_sections";

  /**
   * The groups of the dialog, in workflow order. Each screen opens the dialog at its own group and
   * sees only that group and the ones after it: what has already happened to the current document
   * (the scan, the camera) cannot be changed from the OCR or Export screen.
   */
  public enum Section {
    SCAN,
    CAMERA,
    OCR,
    EXPORT,
    /** Runs beside the workflow: shown wherever the groups after it are shown. */
    LIBRARY,
    APP
  }

  private ActivityResultLauncher<android.net.Uri> inboxFolderLauncher;
  @Nullable private ExportOptionsPanel exportPanel;

  public static void show(@NonNull FragmentManager fm) {
    show(fm, Section.SCAN);
  }

  /** Opens the dialog at {@code section}, showing that group and the groups after it. */
  public static void show(@NonNull FragmentManager fm, @NonNull Section section) {
    // Guard against rapid double-taps on the options button: if a dialog with this tag
    // is already added, do not show a second instance. showNow() commits synchronously,
    // so the tag is visible to the very next click event.
    if (fm.findFragmentByTag(FRAGMENT_TAG) != null || fm.isStateSaved()) return;
    OptionsDialogFragment f = new OptionsDialogFragment();
    Bundle args = new Bundle();
    args.putString(ARG_SECTION, section.name());
    f.setArguments(args);
    f.showNow(fm, FRAGMENT_TAG);
  }

  private Section requestedSection() {
    Bundle args = getArguments();
    String name = args != null ? args.getString(ARG_SECTION) : null;
    try {
      return name != null ? Section.valueOf(name) : Section.SCAN;
    } catch (IllegalArgumentException e) {
      return Section.SCAN;
    }
  }

  private static int headingId(Section section) {
    return switch (section) {
      case SCAN -> R.id.section_scan;
      case CAMERA -> R.id.section_camera;
      case OCR -> R.id.section_ocr;
      case EXPORT -> R.id.section_export;
      case LIBRARY -> R.id.section_library;
      case APP -> R.id.section_app;
    };
  }

  private static int groupId(Section section) {
    return switch (section) {
      case SCAN -> R.id.group_scan;
      case CAMERA -> R.id.group_camera;
      case OCR -> R.id.group_ocr;
      case EXPORT -> R.id.group_export;
      case LIBRARY -> R.id.group_library;
      case APP -> R.id.group_app;
    };
  }

  /** The groups that are open; only the requested one at first, so the sheet stays short. */
  private final java.util.EnumSet<Section> expanded = java.util.EnumSet.noneOf(Section.class);

  /**
   * Makes every group heading a row that opens or closes its group. The group opened at first is
   * the one the calling screen asked for (or those restored from {@code savedInstanceState}).
   */
  private void setupGroups(View view, @Nullable Bundle savedInstanceState) {
    expanded.clear();
    String[] saved =
        savedInstanceState != null ? savedInstanceState.getStringArray(SAVED_EXPANDED) : null;
    if (saved != null) {
      for (String name : saved) {
        try {
          expanded.add(Section.valueOf(name));
        } catch (IllegalArgumentException ignore) {
          // Stale state; ignore
        }
      }
    } else {
      expanded.add(requestedSection());
    }
    Section first = requestedSection();
    for (Section section : Section.values()) {
      TextView heading = view.findViewById(headingId(section));
      if (section.compareTo(first) < 0) {
        // A step of the workflow that lies behind the calling screen: not offered from here
        heading.setVisibility(View.GONE);
        view.findViewById(groupId(section)).setVisibility(View.GONE);
        expanded.remove(section);
        continue;
      }
      androidx.core.view.ViewCompat.setAccessibilityHeading(heading, true);
      heading.setOnClickListener(
          v -> {
            if (!expanded.remove(section)) expanded.add(section);
            showGroup(view, section);
          });
      showGroup(view, section);
    }
  }

  private void showGroup(View view, Section section) {
    boolean open = expanded.contains(section);
    view.findViewById(groupId(section)).setVisibility(open ? View.VISIBLE : View.GONE);
    TextView heading = view.findViewById(headingId(section));
    heading.setCompoundDrawablesRelativeWithIntrinsicBounds(
        0, 0, open ? R.drawable.ic_arrow_drop_up : R.drawable.ic_arrow_drop_down, 0);
    androidx.core.view.ViewCompat.setStateDescription(
        heading,
        getString(open ? R.string.options_group_expanded : R.string.options_group_collapsed));
    // Screen readers then offer "double tap to collapse" instead of "double tap to activate"
    androidx.core.view.ViewCompat.replaceAccessibilityAction(
        heading,
        androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
            .ACTION_CLICK,
        getString(open ? R.string.options_group_collapse : R.string.options_group_expand),
        null);
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    String[] names = new String[expanded.size()];
    int i = 0;
    for (Section s : expanded) names[i++] = s.name();
    outState.putStringArray(SAVED_EXPANDED, names);
  }

  /**
   * Greys out what the other choices make irrelevant, so the dialog cannot show contradicting
   * settings: without OCR there is nothing to auto-rotate, post-process, export as text or put into
   * a PDF text layer; without the crop screen there is no edge detection to skip.
   *
   * <p>Only while the Scan group is shown: opened from the OCR or Export screen, the document is
   * past that step, so a saved "skip OCR" must neither grey out the OCR options nor be hidden
   * behind them.
   */
  private static void applyDependencies(
      boolean scanGroupShown,
      CheckBox cbSkipOcr,
      CheckBox cbSkipCropping,
      CheckBox cbSkipEdgeDetection,
      OcrOptionsPanel ocrPanel,
      ExportOptionsPanel exportPanel) {
    boolean ocr = !scanGroupShown || !cbSkipOcr.isChecked();
    ocrPanel.setEnabled(ocr);
    exportPanel.setOcrDependentEnabled(ocr);
    de.schliweb.makeacopy.utils.ui.UIUtils.setEnabledWithAlpha(
        cbSkipEdgeDetection, !cbSkipCropping.isChecked());
  }

  /**
   * Shares relevant logs and environment information for debugging purposes. Depending on the size
   * of the logs, the data is either shared inline as plain text or written to a temporary file and
   * shared as an attachment through a FileProvider. The logs include application metadata, device
   * details, and the latest process-specific logcat output.
   *
   * @param ctx The Android context used for file operations, creating intents, and accessing
   *     resources.
   */
  private void shareLogs(@NonNull Context ctx) {
    try {
      String header = buildEnvHeader(ctx);
      String logs = collectLogcatForThisProcess();
      if (logs == null) logs = "";
      String body = header + "\n\n" + logs;

      // Binder safety thresholds
      final int INLINE_MAX_CHARS = 48 * 1024; // ~48 KiB safe inline payload
      final int FILE_MAX_CHARS = 1024 * 1024; // cap file to ~1 MiB

      if (body.length() <= INLINE_MAX_CHARS) {
        // Share inline as text
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.share_logs_subject));
        share.putExtra(Intent.EXTRA_TEXT, body);
        startActivity(
            Intent.createChooser(share, ctx.getString(R.string.share_logs_chooser_title)));
        return;
      }

      // For large payloads write to a temp file in cache and share via FileProvider
      String displayNote = "[saved as attachment due to size]";
      String content = body;
      if (content.length() > FILE_MAX_CHARS) {
        content = content.substring(content.length() - FILE_MAX_CHARS);
        content =
            header + "\n\n[truncated to last " + (FILE_MAX_CHARS / 1024) + " KB]\n\n" + content;
      }

      java.io.File cacheDir = new java.io.File(ctx.getCacheDir(), "debug");
      if (!cacheDir.exists()) {
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
      }
      String ts =
          new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
              .format(System.currentTimeMillis());
      java.io.File outFile = new java.io.File(cacheDir, "makeacopy-logs-" + ts + ".txt");
      java.io.FileOutputStream fos = null;
      try {
        fos = new java.io.FileOutputStream(outFile);
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        fos.write(data);
        fos.flush();
      } finally {
        if (fos != null)
          try {
            fos.close();
          } catch (Throwable ignored) {
            // Closing file output stream is best-effort
          }
      }

      androidx.core.content.FileProvider.getUriForFile(
          ctx, BuildConfig.APPLICATION_ID + ".fileprovider", outFile);
      android.net.Uri uri =
          androidx.core.content.FileProvider.getUriForFile(
              ctx, BuildConfig.APPLICATION_ID + ".fileprovider", outFile);

      Intent share = new Intent(Intent.ACTION_SEND);
      share.setType("text/plain");
      share.putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.share_logs_subject));
      share.putExtra(Intent.EXTRA_TEXT, displayNote);
      share.putExtra(Intent.EXTRA_STREAM, uri);
      share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(share, ctx.getString(R.string.share_logs_chooser_title)));
    } catch (Throwable t) {
      Toast.makeText(
              ctx, ctx.getString(R.string.share_logs_error, t.getMessage()), Toast.LENGTH_LONG)
          .show();
    }
  }

  /**
   * Builds and returns a string containing detailed environment information for debugging purposes.
   * The data includes application version, device details, system properties, and user preferences.
   *
   * @param ctx The Android context from which system and application-specific information is
   *     retrieved.
   * @return A string containing the environment header information, which includes details such as
   *     app version, device info, locale, and time. If an error occurs while retrieving this data,
   *     a partial or default header is returned instead.
   */
  private String buildEnvHeader(Context ctx) {
    StringBuilder sb = new StringBuilder();
    String versionName;
    long versionCode;
    try {
      android.content.pm.PackageManager pm = ctx.getPackageManager();
      android.content.pm.PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), 0);
      versionName = pi.versionName;
      versionCode = pi.getLongVersionCode();
    } catch (Exception e) {
      versionName = "unknown";
      versionCode = -1L;
    }

    try {
      java.util.Locale loc = java.util.Locale.getDefault();
      String abis =
          Build.SUPPORTED_ABIS != null
              ? java.util.Arrays.toString(Build.SUPPORTED_ABIS)
              : "unknown";
      boolean analysisPref =
          ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE)
              .getBoolean(BUNDLE_ANALYSIS_ENABLED, false);
      sb.append("MakeACopy logs\n");
      sb.append("App: ").append(versionName).append(" (code ").append(versionCode).append(")\n");
      sb.append("SDK: ")
          .append(android.os.Build.VERSION.SDK_INT)
          .append(" | Brand: ")
          .append(android.os.Build.BRAND)
          .append(" | Manuf: ")
          .append(android.os.Build.MANUFACTURER)
          .append(" | Model: ")
          .append(android.os.Build.MODEL)
          .append(" | Device: ")
          .append(android.os.Build.DEVICE)
          .append("\n");
      sb.append("Display: ")
          .append(android.os.Build.DISPLAY)
          .append(" | ABIs: ")
          .append(abis)
          .append("\n");
      sb.append("Locale: ")
          .append(loc != null ? loc.toLanguageTag() : "-")
          .append(" | Analysis enabled: ")
          .append(analysisPref)
          .append("\n");
      sb.append("Time: ")
          .append(
              new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US)
                  .format(System.currentTimeMillis()))
          .append("\n");
      sb.append("Process: pid=")
          .append(android.os.Process.myPid())
          .append(" package=")
          .append(BuildConfig.APPLICATION_ID)
          .append("\n");
    } catch (Throwable ignored) {
      // Building env header is best-effort; partial result is acceptable
    }
    return sb.toString();
  }

  /**
   * Collects and returns the logcat output specific to the current process. This method first
   * attempts to fetch logs using the modern logcat command with PID filtering. If this fails, it
   * falls back to collecting all logs and filtering them by application-specific identifiers and
   * tags.
   *
   * @return A string containing the logcat output for the current process. If no relevant logs are
   *     found or an error occurs, an empty string is returned.
   */
  private String collectLogcatForThisProcess() {
    StringBuilder out = new StringBuilder();
    java.io.BufferedReader reader = null;
    try {
      int pid = android.os.Process.myPid();
      // Prefer modern logcat with --pid support
      String[] cmd = new String[] {"logcat", "-d", "--pid", String.valueOf(pid), "-v", "time"};
      try {
        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        reader =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(
                    proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
          out.append(line).append('\n');
        }
        proc.waitFor();
        if (out.length() > 0) return out.toString();
      } catch (Throwable ignore) {
        // Modern logcat with --pid may not be available; fall back below
      }
      // Fallback: dump all and filter by app id/tag if possible
      out.setLength(0);
      Process proc2 =
          new ProcessBuilder("logcat", "-d", "-v", "time").redirectErrorStream(true).start();
      reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  proc2.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
      String line2;
      String appId = BuildConfig.APPLICATION_ID;
      while ((line2 = reader.readLine()) != null) {
        if (line2.contains(appId)
            || line2.contains("CameraFragment")
            || line2.contains("MakeACopy")) {
          out.append(line2).append('\n');
        }
      }
      proc2.waitFor();
    } catch (Throwable ignored) {
      // Logcat collection is best-effort
    } finally {
      try {
        if (reader != null) reader.close();
      } catch (Throwable ignored2) {
        // Closing reader is best-effort
      }
    }
    return out.toString();
  }

  /**
   * Shows a single-choice list of the available UI languages, each named in its own language, with
   * "System default" on top. The choice is applied immediately; AppCompat then recreates the
   * activity (and with it this dialog) in the new language.
   */
  private void showLanguagePicker(
      @NonNull Context ctx, @NonNull List<String> tags, @NonNull String current) {
    String[] labels = new String[tags.size() + 1];
    labels[0] = getString(R.string.app_language_system_default);
    for (int i = 0; i < tags.size(); i++) labels[i + 1] = AppLanguage.displayName(tags.get(i));
    int checked = tags.indexOf(current) + 1; // 0 = system default

    AlertDialog dialog =
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.app_language_label)
            .setSingleChoiceItems(
                labels,
                checked,
                (d, which) -> {
                  d.dismiss();
                  if (which == checked) return;
                  AppLanguage.apply(
                      ctx, which == 0 ? AppLanguage.SYSTEM_DEFAULT : tags.get(which - 1));
                })
            .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
            .create();
    dialog.setOnShowListener(
        dlg -> {
          try {
            DialogUtils.improveAlertDialogButtonContrastForNight(dialog, ctx);
          } catch (Throwable ignore) {
            // Dialog contrast improvement is best-effort
          }
        });
    dialog.show();
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    inboxFolderLauncher =
        registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
              if (uri != null && getContext() != null && exportPanel != null) {
                exportPanel.onInboxFolderPicked(getContext(), uri);
              }
            });
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
    Context ctx = requireContext();
    View view = getLayoutInflater().inflate(R.layout.dialog_options, null);

    // OCR and Export groups: the shared panels, showing both output formats side by side
    OcrOptionsPanel ocrPanel = new OcrOptionsPanel(view);
    ocrPanel.bind(ctx);
    exportPanel = new ExportOptionsPanel(view);
    exportPanel.bind(ctx, ExportOptionsPanel.Formats.ALL, inboxFolderLauncher);
    LibraryOptionsPanel libraryPanel = new LibraryOptionsPanel(view);
    libraryPanel.bind(ctx);

    CheckBox cbSkip = view.findViewById(R.id.dialog_checkbox_skip_ocr);
    CheckBox cbSkipCropping = view.findViewById(R.id.dialog_checkbox_skip_cropping);
    CheckBox cbSkipEdgeDetection = view.findViewById(R.id.dialog_checkbox_skip_edge_detection);
    CheckBox cbAnalysis = view.findViewById(R.id.dialog_checkbox_analysis_enabled);
    CheckBox cbAccessibility = view.findViewById(R.id.dialog_checkbox_accessibility_mode);
    CheckBox cbProtectScreen = view.findViewById(R.id.dialog_checkbox_protect_screen);
    CheckBox cbAppLock = view.findViewById(R.id.dialog_checkbox_app_lock);
    CheckBox cbExposure = view.findViewById(R.id.dialog_checkbox_exposure_compensation);
    CheckBox cbManualFocus = view.findViewById(R.id.dialog_checkbox_manual_focus);
    CheckBox cbFocusQuality = view.findViewById(R.id.dialog_checkbox_focus_quality);
    CheckBox cbLowLightPrompt = view.findViewById(R.id.dialog_checkbox_low_light_prompt);
    // Auto‑Capture/Auto‑Torch options removed to keep it simple

    SharedPreferences prefs = ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
    boolean skipOcr = prefs.getBoolean(BUNDLE_SKIP_OCR, false);
    boolean skipPerspective = prefs.getBoolean(BUNDLE_SKIP_CROPPING, false);
    boolean skipEdgeDetection = prefs.getBoolean(BUNDLE_SKIP_EDGE_DETECTION, false);
    boolean analysisEnabled = prefs.getBoolean(BUNDLE_ANALYSIS_ENABLED, false);
    boolean accessibilityMode = prefs.getBoolean(BUNDLE_ACCESSIBILITY_MODE, false);
    boolean exposureEnabled = prefs.getBoolean(BUNDLE_EXPOSURE_COMPENSATION, false);
    boolean manualFocusEnabled = prefs.getBoolean(BUNDLE_MANUAL_FOCUS, false);
    boolean focusQualityEnabled = prefs.getBoolean(BUNDLE_FOCUS_QUALITY_INDICATOR, false);
    boolean lowLightPromptEnabled = prefs.getBoolean(BUNDLE_LOW_LIGHT_PROMPT, true);
    cbSkip.setChecked(skipOcr);
    if (cbSkipCropping != null) cbSkipCropping.setChecked(skipPerspective);
    if (cbSkipEdgeDetection != null) cbSkipEdgeDetection.setChecked(skipEdgeDetection);
    if (cbAnalysis != null) cbAnalysis.setChecked(analysisEnabled);
    if (cbAccessibility != null) cbAccessibility.setChecked(accessibilityMode);
    if (cbProtectScreen != null) cbProtectScreen.setChecked(ScreenProtection.isEnabled(ctx));
    boolean appLockWasOn = AppLock.isEnabled(ctx);
    if (cbAppLock != null) cbAppLock.setChecked(appLockWasOn);
    if (cbExposure != null) cbExposure.setChecked(exposureEnabled);
    if (cbManualFocus != null) cbManualFocus.setChecked(manualFocusEnabled);
    if (cbLowLightPrompt != null) cbLowLightPrompt.setChecked(lowLightPromptEnabled);
    if (cbFocusQuality != null) {
      // The setting is only offered while the build-time feature flag is enabled.
      if (de.schliweb.makeacopy.utils.infra.FeatureFlags.isFocusQualityIndicatorEnabled()) {
        cbFocusQuality.setChecked(focusQualityEnabled);
      } else {
        cbFocusQuality.setVisibility(View.GONE);
      }
    }

    boolean scanGroupShown = requestedSection() == Section.SCAN;
    Runnable dependencies =
        () ->
            applyDependencies(
                scanGroupShown, cbSkip, cbSkipCropping, cbSkipEdgeDetection, ocrPanel, exportPanel);
    dependencies.run();
    cbSkip.setOnCheckedChangeListener((b, checked) -> dependencies.run());
    cbSkipCropping.setOnCheckedChangeListener((b, checked) -> dependencies.run());
    setupGroups(view, savedInstanceState);

    // App language row: shows the current choice and opens the picker
    View languageRow = view.findViewById(R.id.row_app_language);
    TextView languageValue = view.findViewById(R.id.text_app_language_value);
    if (languageRow != null && languageValue != null) {
      List<String> tags = AppLanguage.supportedTags(ctx);
      String current = AppLanguage.currentTag(tags);
      languageValue.setText(
          current.isEmpty()
              ? getString(R.string.app_language_system_default)
              : AppLanguage.displayName(current));
      languageRow.setOnClickListener(v -> showLanguagePicker(ctx, tags, current));
    }

    view.findViewById(R.id.row_help).setOnClickListener(v -> DocumentActivity.showHelp(ctx));
    view.findViewById(R.id.row_privacy_policy)
        .setOnClickListener(v -> DocumentActivity.showPrivacyPolicy(ctx));

    // Wire up the Share Logs button placed under the options
    View shareBtn = view.findViewById(R.id.button_share_logs);
    if (shareBtn != null) {
      shareBtn.setOnClickListener(v -> shareLogs(ctx));
    }

    return DialogUtils.createOptionsBottomSheet(
        ctx,
        getString(R.string.btn_options),
        view,
        () -> {
          boolean skip = cbSkip.isChecked();
          boolean skipCropping = cbSkipCropping != null && cbSkipCropping.isChecked();
          boolean skipEdge = cbSkipEdgeDetection != null && cbSkipEdgeDetection.isChecked();
          boolean analysis = cbAnalysis != null && cbAnalysis.isChecked();
          boolean accessibility = cbAccessibility != null && cbAccessibility.isChecked();
          boolean protectScreen = cbProtectScreen != null && cbProtectScreen.isChecked();
          boolean appLock = cbAppLock != null && cbAppLock.isChecked();
          if (appLock && !AppLock.isAvailable(ctx)) {
            // Nothing could unlock the app: refuse instead of locking the user out
            UIUtils.showToast(ctx, R.string.app_lock_no_screen_lock, Toast.LENGTH_LONG);
            appLock = false;
          }
          boolean exposure = cbExposure != null && cbExposure.isChecked();
          boolean manualFocus = cbManualFocus != null && cbManualFocus.isChecked();
          boolean focusQuality = cbFocusQuality != null && cbFocusQuality.isChecked();
          boolean lowLightPrompt = cbLowLightPrompt == null || cbLowLightPrompt.isChecked();
          // No extra A11y options persisted

          prefs
              .edit()
              .putBoolean(BUNDLE_SKIP_OCR, skip)
              .putBoolean(BUNDLE_SKIP_CROPPING, skipCropping)
              .putBoolean(BUNDLE_SKIP_EDGE_DETECTION, skipEdge)
              .putBoolean(BUNDLE_ANALYSIS_ENABLED, analysis)
              .putBoolean(BUNDLE_ACCESSIBILITY_MODE, accessibility)
              .putBoolean(BUNDLE_EXPOSURE_COMPENSATION, exposure)
              .putBoolean(BUNDLE_MANUAL_FOCUS, manualFocus)
              .putBoolean(BUNDLE_FOCUS_QUALITY_INDICATOR, focusQuality)
              .putBoolean(BUNDLE_LOW_LIGHT_PROMPT, lowLightPrompt)
              .putBoolean(ScreenProtection.KEY, protectScreen)
              .putBoolean(AppLock.KEY, appLock)
              .apply();
          // The user is here and has just chosen it: no unlock prompt right away
          if (appLock && !appLockWasOn) AppLock.markUnlocked();
          AppLock.applyRecentsPreview(getActivity());
          // Takes effect at once, not only after the next start
          ScreenProtection.apply(getActivity());

          OcrOptionsPanel.Choice ocr = ocrPanel.apply(ctx);
          if (!ocrPanel.isFixedPaddleMode()
              && ocr.mode() != de.schliweb.makeacopy.utils.ocr.OCRHelper.OCR_MODE_PADDLE) {
            // Leaving PaddleOCR: release the engine so the next run starts a clean session
            try {
              de.schliweb.makeacopy.utils.ocr.PaddleEngineProvider.releaseAll(ctx);
            } catch (Throwable ignore) {
              // Best-effort; failure is non-critical
            }
          }
          Bundle exportResult = exportPanel.apply(ctx);
          libraryPanel.apply(ctx);

          Bundle result = new Bundle();
          result.putBoolean(BUNDLE_SKIP_OCR, skip);
          result.putBoolean(BUNDLE_SKIP_CROPPING, skipCropping);
          result.putBoolean(BUNDLE_SKIP_EDGE_DETECTION, skipEdge);
          result.putBoolean(BUNDLE_ANALYSIS_ENABLED, analysis);
          result.putBoolean(BUNDLE_ACCESSIBILITY_MODE, accessibility);
          result.putBoolean(BUNDLE_EXPOSURE_COMPENSATION, exposure);
          result.putBoolean(BUNDLE_MANUAL_FOCUS, manualFocus);
          result.putBoolean(BUNDLE_FOCUS_QUALITY_INDICATOR, focusQuality);
          getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
          getParentFragmentManager()
              .setFragmentResult(ExportOptionsPanel.REQUEST_KEY, exportResult);
        });
  }
}
