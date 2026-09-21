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

import android.app.Dialog;
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
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.utils.export.PageFormat;
import de.schliweb.makeacopy.utils.export.PdfCreator;
import de.schliweb.makeacopy.utils.export.PdfQualityPreset;
import de.schliweb.makeacopy.utils.export.jpeg.JpegExportOptions;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.ui.DialogUtils;

/**
 * A dialog fragment that displays export options for the user to configure. Options include
 * selecting whether to include OCR data, exporting as JPEG or PDF, enabling grayscale conversion,
 * and choosing specific PDF or JPEG settings.
 *
 * <p>This dialog allows users to modify their preferences for exporting content and persists these
 * settings for future use. Once the user confirms their choices, the selected options are sent back
 * via a result bundle.
 *
 * <p>Constants: - REQUEST_KEY: The key for retrieving the fragment result. - BUNDLE_INCLUDE_OCR:
 * Key for including or excluding OCR data in export. - BUNDLE_EXPORT_AS_JPEG: Key for exporting the
 * output as JPEG format. - BUNDLE_CONVERT_TO_GRAYSCALE: Key for converting the output to grayscale.
 * - BUNDLE_JPEG_MODE: Key for specifying the JPEG export mode, represented as an enum name. -
 * BUNDLE_PDF_PRESET: Key for defining the PDF export quality preset, also represented as an enum
 * name.
 *
 * <p>Overrides: - onCreateDialog(Bundle): Creates and initializes the dialog with its UI and logic.
 *
 * <p>Methods: - show(FragmentManager): Static method to show the dialog using the provided
 * FragmentManager. - updateGroups(boolean, View, View): Private helper method to toggle visibility
 * between PDF and JPEG option groups within the dialog.
 */
public class ExportOptionsDialogFragment extends DialogFragment {

  private ActivityResultLauncher<Uri> inboxFolderLauncher;
  private TextView inboxFolderLabel;
  private CheckBox cbInboxEnabled;

  public static final String REQUEST_KEY = "export_options";
  public static final String BUNDLE_INCLUDE_OCR = "include_ocr";
  public static final String BUNDLE_EXPORT_AS_JPEG = "export_as_jpeg";
  public static final String BUNDLE_JPEG_MODE = "jpeg_mode"; // enum name
  public static final String BUNDLE_PDF_PRESET = "pdf_preset"; // enum name
  public static final String BUNDLE_PAGE_FORMAT = "page_format"; // enum name
  public static final String BUNDLE_PDF_TEXT_LAYER_MODE = "pdf_text_layer_mode"; // enum name

  public static void show(@NonNull FragmentManager fm) {
    new ExportOptionsDialogFragment().show(fm, "ExportOptionsDialogFragment");
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    inboxFolderLauncher =
        registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
              if (uri != null && getContext() != null) {
                // Persist permission across reboots
                int flags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getContext().getContentResolver().takePersistableUriPermission(uri, flags);
                ExportPrefsHelper.setInboxUri(getContext(), uri.toString());
                ExportPrefsHelper.setInboxEnabled(getContext(), true);
                if (cbInboxEnabled != null) cbInboxEnabled.setChecked(true);
                updateInboxFolderLabel();
              }
            });
  }

  private void updateInboxFolderLabel() {
    if (inboxFolderLabel == null || getContext() == null) return;
    String uri = ExportPrefsHelper.getInboxUri(getContext());
    if (uri != null) {
      // Show last path segment for readability
      Uri parsed = Uri.parse(uri);
      String display = parsed.getLastPathSegment();
      if (display == null) display = uri;
      inboxFolderLabel.setText(getString(R.string.inbox_folder_set, display));
    } else {
      inboxFolderLabel.setText(R.string.inbox_folder_none);
    }
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
    Context ctx = requireContext();
    View view = getLayoutInflater().inflate(R.layout.dialog_export_options, null);
    SharedPreferences prefs = ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);

    CheckBox cbIncludeOcr = view.findViewById(R.id.dialog_checkbox_include_ocr);
    cbIncludeOcr.setChecked(prefs.getBoolean("include_ocr", false));
    restoreRadioSelections(ctx, view, prefs);
    setupInboxMode(ctx, view);

    // Format (PDF/JPEG) is selected inline on the Export screen; the dialog only shows the
    // option groups matching the currently selected format.
    updateGroups(
        prefs.getBoolean("export_as_jpeg", false),
        view.findViewById(R.id.dialog_pdf_group),
        view.findViewById(R.id.dialog_jpeg_group));

    return DialogUtils.createOptionsBottomSheet(
        ctx,
        getString(R.string.export_options_title),
        view,
        () -> applySelections(ctx, view, prefs, cbIncludeOcr.isChecked()));
  }

  /**
   * Pre-selects the radio buttons from the saved options. Mutual exclusivity is the groups' job.
   */
  private void restoreRadioSelections(Context ctx, View view, SharedPreferences prefs) {
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
        view,
        R.id.dialog_document_cleanup_group,
        cleanupRadioId(ExportPrefsHelper.resolveCleanupMode(ctx)));
    check(
        view,
        R.id.dialog_page_format_group,
        pageFormatRadioId(PageFormat.fromName(pageFormatSaved, PageFormat.FIT_TO_IMAGE)));
    check(
        view,
        R.id.dialog_pdf_text_layer_mode_group,
        textLayerRadioId(ExportPrefsHelper.resolveTextLayerMode(ctx)));
    check(view, R.id.dialog_pdf_preset_group, presetRadioId(preset));
    check(
        view,
        R.id.dialog_jpeg_mode_group,
        jpegRadioId(jpegMode, prefs.getBoolean("jpeg_output_grayscale", false)));
    // "none" selected if no saved value
    check(view, R.id.dialog_pdf_bw_mode_group, pdfBwRadioId(prefs.getString("pdf_bw_mode", null)));
  }

  /** Checks the radio button, or keeps the layout's default when there is none to check. */
  private static void check(View view, int groupId, int radioId) {
    if (radioId == View.NO_ID) return;
    RadioGroup group = view.findViewById(groupId);
    group.check(radioId);
  }

  private void setupInboxMode(Context ctx, View view) {
    View inboxGroup = view.findViewById(R.id.dialog_inbox_group);
    cbInboxEnabled = view.findViewById(R.id.dialog_checkbox_inbox_enabled);
    inboxFolderLabel = view.findViewById(R.id.dialog_inbox_folder_label);
    if (!FeatureFlags.isInboxModeEnabled() || inboxGroup == null) return;

    inboxGroup.setVisibility(View.VISIBLE);
    cbInboxEnabled.setChecked(ExportPrefsHelper.isInboxEnabled(ctx));
    updateInboxFolderLabel();

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

    View btnInboxSelect = view.findViewById(R.id.dialog_button_inbox_select);
    if (btnInboxSelect != null) {
      btnInboxSelect.setOnClickListener(v2 -> inboxFolderLauncher.launch(null));
    }
    View btnInboxClear = view.findViewById(R.id.dialog_button_inbox_clear);
    if (btnInboxClear != null) {
      btnInboxClear.setOnClickListener(
          v2 -> {
            ExportPrefsHelper.clearInbox(ctx);
            cbInboxEnabled.setChecked(false);
            updateInboxFolderLabel();
          });
    }

    setupInboxFilenameSpinner(ctx, view.findViewById(R.id.dialog_inbox_filename_spinner));

    CheckBox cbAutoNewScan = view.findViewById(R.id.dialog_checkbox_inbox_auto_new_scan);
    if (cbAutoNewScan != null) {
      cbAutoNewScan.setChecked(ExportPrefsHelper.isInboxAutoNewScan(ctx));
      cbAutoNewScan.setOnCheckedChangeListener(
          (buttonView, isChecked) -> ExportPrefsHelper.setInboxAutoNewScan(ctx, isChecked));
    }
  }

  private void setupInboxFilenameSpinner(Context ctx, android.widget.Spinner filenameSpinner) {
    if (filenameSpinner == null) return;
    String[] templateLabels = {
      getString(R.string.inbox_filename_date_scan),
      getString(R.string.inbox_filename_date_time_scan),
      getString(R.string.inbox_filename_date_only)
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

  /** Reads the dialog's selections, persists them and hands them to the Export screen. */
  private void applySelections(
      Context ctx, View view, SharedPreferences prefs, boolean includeOcr) {
    boolean asJpeg = ExportPrefsHelper.isExportAsJpeg(ctx);
    int jpegCheckedId = checkedId(view, R.id.dialog_jpeg_mode_group);
    JpegExportOptions.Mode mode = jpegModeFor(jpegCheckedId);
    boolean jpegGray = jpegCheckedId == R.id.dialog_radio_jpeg_auto;
    // null = none/original
    String pdfBwMode = pdfBwModeFor(checkedId(view, R.id.dialog_pdf_bw_mode_group));
    DocumentCleanupMode cleanupMode =
        cleanupModeFor(checkedId(view, R.id.dialog_document_cleanup_group));
    PdfQualityPreset preset = presetFor(checkedId(view, R.id.dialog_pdf_preset_group));
    PageFormat pageFormat = pageFormatFor(checkedId(view, R.id.dialog_page_format_group));
    PdfCreator.TextLayerMode textLayerMode =
        textLayerModeFor(checkedId(view, R.id.dialog_pdf_text_layer_mode_group));

    // persist
    SharedPreferences.Editor editor =
        prefs
            .edit()
            .putBoolean("include_ocr", includeOcr)
            .putBoolean("export_as_jpeg", asJpeg)
            .putString("jpeg_mode", mode.name())
            .putBoolean("jpeg_output_grayscale", jpegGray)
            .putString("document_cleanup_mode", cleanupMode.name())
            .putString("pdf_preset", preset.name())
            .putString("page_format", pageFormat.name())
            .putString("pdf_text_layer_mode", textLayerMode.name());
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
    result.putString(BUNDLE_PDF_TEXT_LAYER_MODE, textLayerMode.name());
    getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
  }

  private static int checkedId(View view, int groupId) {
    RadioGroup group = view.findViewById(groupId);
    return group.getCheckedRadioButtonId();
  }

  // ---- saved option <-> radio button (pure mappings) ----

  static int cleanupRadioId(DocumentCleanupMode mode) {
    if (mode == DocumentCleanupMode.NATURAL) return R.id.dialog_document_cleanup_natural;
    if (mode == DocumentCleanupMode.ENHANCED) return R.id.dialog_document_cleanup_enhanced;
    if (mode == DocumentCleanupMode.CLEAN_TEXT) return R.id.dialog_document_cleanup_clean_text;
    return R.id.dialog_document_cleanup_original;
  }

  static DocumentCleanupMode cleanupModeFor(int radioId) {
    if (radioId == R.id.dialog_document_cleanup_natural) return DocumentCleanupMode.NATURAL;
    if (radioId == R.id.dialog_document_cleanup_enhanced) return DocumentCleanupMode.ENHANCED;
    if (radioId == R.id.dialog_document_cleanup_clean_text) return DocumentCleanupMode.CLEAN_TEXT;
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

  static int textLayerRadioId(PdfCreator.TextLayerMode mode) {
    return mode == PdfCreator.TextLayerMode.WORD_POSITIONED
        ? R.id.dialog_pdf_text_layer_word_positioned
        : R.id.dialog_pdf_text_layer_line_based;
  }

  static PdfCreator.TextLayerMode textLayerModeFor(int radioId) {
    return radioId == R.id.dialog_pdf_text_layer_word_positioned
        ? PdfCreator.TextLayerMode.WORD_POSITIONED
        : PdfCreator.TextLayerMode.LINE_BASED;
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

  /** A saved CLASSIC is shown as "robust"; only an explicit choice of "classic" saves CLASSIC. */
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
    if (radioId == R.id.dialog_pdf_bw_classic) return "CLASSIC";
    if (radioId == R.id.dialog_pdf_bw_robust) return "ROBUST";
    return null;
  }

  private void updateGroups(boolean exportJpeg, View pdfGroup, View jpegGroup) {
    pdfGroup.setVisibility(exportJpeg ? View.GONE : View.VISIBLE);
    jpegGroup.setVisibility(exportJpeg ? View.VISIBLE : View.GONE);
  }
}
