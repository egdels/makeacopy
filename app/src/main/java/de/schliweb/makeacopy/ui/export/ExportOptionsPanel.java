/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.export;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.settings.SettingsCatalog;
import de.schliweb.makeacopy.utils.export.PageFormat;
import de.schliweb.makeacopy.utils.export.PdfQualityPreset;
import de.schliweb.makeacopy.utils.export.jpeg.JpegExportOptions;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.ui.UIUtils;

/**
 * The export options (OCR text file, document cleanup, PDF and JPEG output, inbox mode) as one
 * reusable block: {@code panel_export_options.xml} plus the code that fills it from the saved
 * settings and writes the choices back. The export options dialog and the camera options dialog
 * both embed it, so the options exist once.
 */
public final class ExportOptionsPanel {

  /** Which output format groups the panel shows. */
  public enum Formats {
    /** Only the group of the format currently selected on the Export screen. */
    CURRENT,
    /** PDF and JPEG side by side, each with its own heading. */
    ALL
  }

  /** Result key and bundle keys the Export screen listens for after the dialog is confirmed. */
  public static final String REQUEST_KEY = "export_options";

  public static final String BUNDLE_INCLUDE_OCR = "include_ocr";
  public static final String BUNDLE_EXPORT_AS_JPEG = "export_as_jpeg";
  public static final String BUNDLE_JPEG_MODE = "jpeg_mode"; // enum name
  public static final String BUNDLE_PDF_PRESET = "pdf_preset"; // enum name
  public static final String BUNDLE_PAGE_FORMAT = "page_format"; // enum name

  private final View root;
  private final CheckBox cbIncludeOcr;
  private final CheckBox cbInboxEnabled;
  private final TextView inboxFolderLabel;
  @Nullable private ActivityResultLauncher<Uri> inboxFolderLauncher;

  public ExportOptionsPanel(@NonNull View root) {
    this.root = root;
    cbIncludeOcr = root.findViewById(R.id.dialog_checkbox_include_ocr);
    cbInboxEnabled = root.findViewById(R.id.dialog_checkbox_inbox_enabled);
    inboxFolderLabel = root.findViewById(R.id.dialog_inbox_folder_label);
  }

  /**
   * Fills the controls from the saved settings.
   *
   * @param inboxFolderLauncher the host fragment's document-tree launcher, registered in its {@code
   *     onCreate}; its result goes to {@link #onInboxFolderPicked}. {@code null} hides the folder
   *     button.
   */
  public void bind(
      @NonNull Context ctx,
      @NonNull Formats formats,
      @Nullable ActivityResultLauncher<Uri> inboxFolderLauncher) {
    this.inboxFolderLauncher = inboxFolderLauncher;
    SharedPreferences prefs = prefs(ctx);
    cbIncludeOcr.setChecked(prefs.getBoolean("include_ocr", false));
    restoreRadioSelections(ctx, prefs);
    setupInboxMode(ctx);
    showFormatGroups(formats, ExportPrefsHelper.isExportAsJpeg(ctx));
  }

  /** Persists the choices and returns them as the result bundle the Export screen listens for. */
  @NonNull
  public Bundle apply(@NonNull Context ctx) {
    boolean includeOcr = cbIncludeOcr.isChecked();
    boolean asJpeg = ExportPrefsHelper.isExportAsJpeg(ctx);
    int jpegCheckedId = checkedId(R.id.dialog_jpeg_mode_group);
    JpegExportOptions.Mode mode = jpegModeFor(jpegCheckedId);
    boolean jpegGray = jpegCheckedId == R.id.dialog_radio_jpeg_auto;
    // null = none/original
    String pdfBwMode = pdfBwModeFor(checkedId(R.id.dialog_pdf_bw_mode_group));
    DocumentCleanupMode cleanupMode = cleanupModeFor(checkedId(R.id.dialog_document_cleanup_group));
    PdfQualityPreset preset = presetFor(checkedId(R.id.dialog_pdf_preset_group));
    PageFormat pageFormat = pageFormatFor(checkedId(R.id.dialog_page_format_group));

    SharedPreferences.Editor editor =
        prefs(ctx)
            .edit()
            .putBoolean("include_ocr", includeOcr)
            .putBoolean("export_as_jpeg", asJpeg)
            .putString("jpeg_mode", mode.name())
            .putBoolean("jpeg_output_grayscale", jpegGray)
            .putString("document_cleanup_mode", cleanupMode.name())
            .putString("pdf_preset", preset.name())
            .putString("page_format", pageFormat.name());
    if (pdfBwMode != null) editor.putString("pdf_bw_mode", pdfBwMode);
    else editor.remove("pdf_bw_mode");
    editor.apply();

    Bundle result = new Bundle();
    result.putBoolean(BUNDLE_INCLUDE_OCR, includeOcr);
    result.putBoolean(BUNDLE_EXPORT_AS_JPEG, asJpeg);
    result.putString(BUNDLE_JPEG_MODE, mode.name());
    result.putBoolean("jpeg_output_grayscale", jpegGray);
    result.putString("document_cleanup_mode", cleanupMode.name());
    if (pdfBwMode != null) result.putString("pdf_bw_mode", pdfBwMode);
    result.putString(BUNDLE_PDF_PRESET, preset.name());
    result.putString(BUNDLE_PAGE_FORMAT, pageFormat.name());
    return result;
  }

  /** Greys out the options that only make sense with OCR: the text file. */
  public void setOcrDependentEnabled(boolean enabled) {
    UIUtils.setEnabledWithAlpha(cbIncludeOcr, enabled);
  }

  /** The host's document-tree launcher delivered a folder: remember it and switch inbox mode on. */
  public void onInboxFolderPicked(@NonNull Context ctx, @NonNull Uri uri) {
    // Persist permission across reboots
    int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    ctx.getContentResolver().takePersistableUriPermission(uri, flags);
    ExportPrefsHelper.setInboxUri(ctx, uri.toString());
    ExportPrefsHelper.setInboxEnabled(ctx, true);
    if (cbInboxEnabled != null) cbInboxEnabled.setChecked(true);
    updateInboxFolderLabel(ctx);
  }

  private static SharedPreferences prefs(Context ctx) {
    return ctx.getSharedPreferences(SettingsCatalog.PREFS_MAIN, Context.MODE_PRIVATE);
  }

  private void showFormatGroups(Formats formats, boolean exportJpeg) {
    View pdfGroup = root.findViewById(R.id.dialog_pdf_group);
    View jpegGroup = root.findViewById(R.id.dialog_jpeg_group);
    boolean all = formats == Formats.ALL;
    pdfGroup.setVisibility(all || !exportJpeg ? View.VISIBLE : View.GONE);
    jpegGroup.setVisibility(all || exportJpeg ? View.VISIBLE : View.GONE);
    root.findViewById(R.id.dialog_pdf_heading).setVisibility(all ? View.VISIBLE : View.GONE);
    root.findViewById(R.id.dialog_jpeg_heading).setVisibility(all ? View.VISIBLE : View.GONE);
  }

  /**
   * Pre-selects the radio buttons from the saved options. Mutual exclusivity is the groups' job.
   */
  private void restoreRadioSelections(Context ctx, SharedPreferences prefs) {
    // Legacy booleans removed; selection now driven solely by pdf_bw_mode
    JpegExportOptions.Mode jpegMode;
    try {
      jpegMode =
          JpegExportOptions.Mode.valueOf(
              prefs.getString("jpeg_mode", JpegExportOptions.Mode.NONE.name()));
    } catch (Exception e) {
      jpegMode = JpegExportOptions.Mode.NONE;
    }
    String pageFormatSaved = prefs.getString("page_format", PageFormat.FIT_TO_IMAGE.name());
    // pick default preset if none saved: High for single page, Standard for multi (ExportFragment
    // will compute page count; here fallback Standard)
    String presetSaved = prefs.getString("pdf_preset", null);
    PdfQualityPreset preset =
        presetSaved != null
            ? PdfQualityPreset.fromName(presetSaved, PdfQualityPreset.STANDARD)
            : PdfQualityPreset.STANDARD;

    check(
        R.id.dialog_document_cleanup_group,
        cleanupRadioId(ExportPrefsHelper.resolveCleanupMode(ctx)));
    check(
        R.id.dialog_page_format_group,
        pageFormatRadioId(PageFormat.fromName(pageFormatSaved, PageFormat.FIT_TO_IMAGE)));
    check(R.id.dialog_pdf_preset_group, presetRadioId(preset));
    check(
        R.id.dialog_jpeg_mode_group,
        jpegRadioId(jpegMode, prefs.getBoolean("jpeg_output_grayscale", false)));
    // "none" selected if no saved value
    check(R.id.dialog_pdf_bw_mode_group, pdfBwRadioId(prefs.getString("pdf_bw_mode", null)));
  }

  /** Checks the radio button, or keeps the layout's default when there is none to check. */
  private void check(int groupId, int radioId) {
    if (radioId == View.NO_ID) return;
    RadioGroup group = root.findViewById(groupId);
    group.check(radioId);
  }

  private int checkedId(int groupId) {
    RadioGroup group = root.findViewById(groupId);
    return group.getCheckedRadioButtonId();
  }

  private void setupInboxMode(Context ctx) {
    View inboxGroup = root.findViewById(R.id.dialog_inbox_group);
    if (!FeatureFlags.isInboxModeEnabled() || inboxGroup == null) return;

    inboxGroup.setVisibility(View.VISIBLE);
    cbInboxEnabled.setChecked(ExportPrefsHelper.isInboxEnabled(ctx));
    updateInboxFolderLabel(ctx);

    cbInboxEnabled.setOnCheckedChangeListener(
        (buttonView, isChecked) -> {
          if (isChecked && ExportPrefsHelper.getInboxUri(ctx) == null) {
            buttonView.setChecked(false);
            android.widget.Toast.makeText(
                    ctx, R.string.inbox_no_folder_selected, android.widget.Toast.LENGTH_SHORT)
                .show();
            return;
          }
          ExportPrefsHelper.setInboxEnabled(ctx, isChecked);
        });

    View btnInboxSelect = root.findViewById(R.id.dialog_button_inbox_select);
    if (btnInboxSelect != null) {
      btnInboxSelect.setVisibility(inboxFolderLauncher != null ? View.VISIBLE : View.GONE);
      btnInboxSelect.setOnClickListener(
          v2 -> {
            if (inboxFolderLauncher != null) inboxFolderLauncher.launch(null);
          });
    }
    View btnInboxClear = root.findViewById(R.id.dialog_button_inbox_clear);
    if (btnInboxClear != null) {
      btnInboxClear.setOnClickListener(
          v2 -> {
            ExportPrefsHelper.clearInbox(ctx);
            cbInboxEnabled.setChecked(false);
            updateInboxFolderLabel(ctx);
          });
    }

    setupInboxFilenameSpinner(ctx, root.findViewById(R.id.dialog_inbox_filename_spinner));

    CheckBox cbAutoNewScan = root.findViewById(R.id.dialog_checkbox_inbox_auto_new_scan);
    if (cbAutoNewScan != null) {
      cbAutoNewScan.setChecked(ExportPrefsHelper.isInboxAutoNewScan(ctx));
      cbAutoNewScan.setOnCheckedChangeListener(
          (buttonView, isChecked) -> ExportPrefsHelper.setInboxAutoNewScan(ctx, isChecked));
    }
  }

  private void updateInboxFolderLabel(Context ctx) {
    if (inboxFolderLabel == null) return;
    String uri = ExportPrefsHelper.getInboxUri(ctx);
    if (uri != null) {
      // Show last path segment for readability
      Uri parsed = Uri.parse(uri);
      String display = parsed.getLastPathSegment();
      if (display == null) display = uri;
      inboxFolderLabel.setText(ctx.getString(R.string.inbox_folder_set, display));
    } else {
      inboxFolderLabel.setText(R.string.inbox_folder_none);
    }
  }

  private void setupInboxFilenameSpinner(Context ctx, android.widget.Spinner filenameSpinner) {
    if (filenameSpinner == null) return;
    String[] templateLabels = {
      ctx.getString(R.string.inbox_filename_date_scan),
      ctx.getString(R.string.inbox_filename_date_time_scan),
      ctx.getString(R.string.inbox_filename_date_only)
    };
    String[] templateValues = {"date_scan", "date_time_scan", "date_only"};
    android.widget.ArrayAdapter<String> adapter =
        new android.widget.ArrayAdapter<>(
            ctx, android.R.layout.simple_spinner_item, templateLabels);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    filenameSpinner.setAdapter(adapter);

    String current = ExportPrefsHelper.getInboxFilenameTemplate(ctx);
    for (int i = 0; i < templateValues.length; i++) {
      if (templateValues[i].equals(current)) {
        filenameSpinner.setSelection(i);
        break;
      }
    }
    filenameSpinner.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(
              android.widget.AdapterView<?> parent, View v, int pos, long id) {
            ExportPrefsHelper.setInboxFilenameTemplate(ctx, templateValues[pos]);
          }

          @Override
          public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
  }

  // ---- saved option <-> radio button (pure mappings) ----

  static int cleanupRadioId(DocumentCleanupMode mode) {
    if (mode == DocumentCleanupMode.ENHANCED) return R.id.dialog_document_cleanup_enhanced;
    return R.id.dialog_document_cleanup_original;
  }

  static DocumentCleanupMode cleanupModeFor(int radioId) {
    if (radioId == R.id.dialog_document_cleanup_enhanced) return DocumentCleanupMode.ENHANCED;
    return DocumentCleanupMode.ORIGINAL;
  }

  /** Returns {@link View#NO_ID} for a format without a radio button. */
  static int pageFormatRadioId(PageFormat format) {
    if (format == PageFormat.FIT_TO_IMAGE) return R.id.dialog_radio_page_fit;
    if (format == PageFormat.A4) return R.id.dialog_radio_page_a4;
    if (format == PageFormat.US_LETTER) return R.id.dialog_radio_page_letter;
    if (format == PageFormat.LEGAL) return R.id.dialog_radio_page_legal;
    return View.NO_ID;
  }

  static PageFormat pageFormatFor(int radioId) {
    if (radioId == R.id.dialog_radio_page_a4) return PageFormat.A4;
    if (radioId == R.id.dialog_radio_page_letter) return PageFormat.US_LETTER;
    if (radioId == R.id.dialog_radio_page_legal) return PageFormat.LEGAL;
    return PageFormat.FIT_TO_IMAGE;
  }

  /** Returns {@link View#NO_ID} for a preset without a radio button. */
  static int presetRadioId(PdfQualityPreset preset) {
    if (preset == PdfQualityPreset.HIGH) return R.id.dialog_radio_pdf_high;
    if (preset == PdfQualityPreset.STANDARD) return R.id.dialog_radio_pdf_standard;
    if (preset == PdfQualityPreset.SMALL) return R.id.dialog_radio_pdf_small;
    if (preset == PdfQualityPreset.VERY_SMALL) return R.id.dialog_radio_pdf_very_small;
    return View.NO_ID;
  }

  static PdfQualityPreset presetFor(int radioId) {
    if (radioId == R.id.dialog_radio_pdf_high) return PdfQualityPreset.HIGH;
    if (radioId == R.id.dialog_radio_pdf_small) return PdfQualityPreset.SMALL;
    if (radioId == R.id.dialog_radio_pdf_very_small) return PdfQualityPreset.VERY_SMALL;
    return PdfQualityPreset.STANDARD;
  }

  /** "Grayscale" is not a JPEG mode of its own but mode NONE plus the grayscale output flag. */
  static int jpegRadioId(JpegExportOptions.Mode mode, boolean outputGrayscale) {
    if (mode == JpegExportOptions.Mode.BW_TEXT) return R.id.dialog_radio_jpeg_bw_text;
    return outputGrayscale ? R.id.dialog_radio_jpeg_auto : R.id.dialog_radio_jpeg_none;
  }

  static JpegExportOptions.Mode jpegModeFor(int radioId) {
    return radioId == R.id.dialog_radio_jpeg_bw_text
        ? JpegExportOptions.Mode.BW_TEXT
        : JpegExportOptions.Mode.NONE;
  }

  /** A saved CLASSIC (no longer offered) is shown as "robust". */
  static int pdfBwRadioId(String savedMode) {
    if ("GRAYSCALE".equalsIgnoreCase(savedMode)) return R.id.dialog_pdf_grayscale;
    if ("CLASSIC".equalsIgnoreCase(savedMode) || "ROBUST".equalsIgnoreCase(savedMode)) {
      return R.id.dialog_pdf_bw_robust;
    }
    return R.id.dialog_pdf_bw_none;
  }

  /** Returns {@code null} for none/original. */
  static String pdfBwModeFor(int radioId) {
    if (radioId == R.id.dialog_pdf_grayscale) return "GRAYSCALE";
    if (radioId == R.id.dialog_pdf_bw_robust) return "ROBUST";
    return null;
  }
}
