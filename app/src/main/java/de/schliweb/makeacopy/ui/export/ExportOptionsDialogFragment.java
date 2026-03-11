package de.schliweb.makeacopy.ui.export;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.utils.export.PageFormat;
import de.schliweb.makeacopy.utils.export.PdfQualityPreset;
import de.schliweb.makeacopy.utils.export.jpeg.JpegExportOptions;
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
  public static final String BUNDLE_CONVERT_TO_GRAYSCALE = "convert_to_grayscale";
  public static final String BUNDLE_JPEG_MODE = "jpeg_mode"; // enum name
  public static final String BUNDLE_PDF_PRESET = "pdf_preset"; // enum name
  public static final String BUNDLE_CONVERT_TO_BLACKWHITE = "convert_to_blackwhite";
  public static final String BUNDLE_PAGE_FORMAT = "page_format"; // enum name

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

    CheckBox cbIncludeOcr = view.findViewById(R.id.dialog_checkbox_include_ocr);
    CheckBox cbShareAttachOnly = view.findViewById(R.id.dialog_checkbox_share_attach_only);
    RadioButton rbExportPdf = view.findViewById(R.id.dialog_checkbox_export_pdf);
    RadioButton rbExportJpeg = view.findViewById(R.id.dialog_checkbox_export_jpeg);
    View pdfGroup = view.findViewById(R.id.dialog_pdf_group);
    RadioGroup pdfPresetGroup = view.findViewById(R.id.dialog_pdf_preset_group);
    RadioButton rbHigh = view.findViewById(R.id.dialog_radio_pdf_high);
    RadioButton rbStandard = view.findViewById(R.id.dialog_radio_pdf_standard);
    RadioButton rbSmall = view.findViewById(R.id.dialog_radio_pdf_small);
    RadioButton rbVerySmall = view.findViewById(R.id.dialog_radio_pdf_very_small);

    View jpegGroup = view.findViewById(R.id.dialog_jpeg_group);
    RadioGroup jpegModeGroup = view.findViewById(R.id.dialog_jpeg_mode_group);
    RadioButton rbJpegNone = view.findViewById(R.id.dialog_radio_jpeg_none);
    RadioButton rbJpegAuto = view.findViewById(R.id.dialog_radio_jpeg_auto);
    RadioButton rbJpegBw = view.findViewById(R.id.dialog_radio_jpeg_bw_text);
    RadioButton rbJpegBwRobust = view.findViewById(R.id.dialog_radio_jpeg_bw_robust);
    RadioButton rbJpegOcrRobust = view.findViewById(R.id.dialog_radio_jpeg_ocr_robust);

    RadioGroup pageFormatGroup = view.findViewById(R.id.dialog_page_format_group);
    RadioButton rbPageFit = view.findViewById(R.id.dialog_radio_page_fit);
    RadioButton rbPageA4 = view.findViewById(R.id.dialog_radio_page_a4);
    RadioButton rbPageLetter = view.findViewById(R.id.dialog_radio_page_letter);
    RadioButton rbPageLegal = view.findViewById(R.id.dialog_radio_page_legal);

    CheckBox rbPdfGray = view.findViewById(R.id.dialog_pdf_grayscale);
    CheckBox rbPdfBwRobust = view.findViewById(R.id.dialog_pdf_bw_robust);
    CheckBox rbPdfBwClassic = view.findViewById(R.id.dialog_pdf_bw_classic);
    CheckBox rbPdfOcrRobust = view.findViewById(R.id.dialog_pdf_ocr_robust);

    SharedPreferences prefs = ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
    boolean includeOcr = prefs.getBoolean("include_ocr", false);
    boolean exportAsJpeg = prefs.getBoolean("export_as_jpeg", false);
    // Legacy booleans removed; selection now driven solely by pdf_bw_mode
    String jpegModeSaved = prefs.getString("jpeg_mode", JpegExportOptions.Mode.AUTO.name());
    JpegExportOptions.Mode jpegMode;
    try {
      jpegMode = JpegExportOptions.Mode.valueOf(jpegModeSaved);
    } catch (Exception e) {
      jpegMode = JpegExportOptions.Mode.AUTO;
    }
    String pdfBwModeSaved = prefs.getString("pdf_bw_mode", null);
    String presetSaved = prefs.getString("pdf_preset", null);
    String pageFormatSaved = prefs.getString("page_format", PageFormat.FIT_TO_IMAGE.name());
    PageFormat pageFormat = PageFormat.fromName(pageFormatSaved, PageFormat.FIT_TO_IMAGE);

    cbIncludeOcr.setChecked(includeOcr);
    if (cbShareAttachOnly != null) {
      cbShareAttachOnly.setChecked(ExportPrefsHelper.isShareAttachOnly(ctx));
    }
    // Initialize format selection from preference (PDF default)
    if (exportAsJpeg) {
      rbExportJpeg.setChecked(true);
    } else {
      rbExportPdf.setChecked(true);
    }

    // Initialize page format selection
    if (pageFormat == PageFormat.FIT_TO_IMAGE) rbPageFit.setChecked(true);
    else if (pageFormat == PageFormat.A4) rbPageA4.setChecked(true);
    else if (pageFormat == PageFormat.US_LETTER) rbPageLetter.setChecked(true);
    else if (pageFormat == PageFormat.LEGAL) rbPageLegal.setChecked(true);

    // pick default preset if none saved: High for single page, Standard for multi (ExportFragment
    // will compute page count; here fallback Standard)
    PdfQualityPreset preset =
        presetSaved != null
            ? PdfQualityPreset.fromName(presetSaved, PdfQualityPreset.STANDARD)
            : PdfQualityPreset.STANDARD;
    if (preset == PdfQualityPreset.HIGH) rbHigh.setChecked(true);
    else if (preset == PdfQualityPreset.STANDARD) rbStandard.setChecked(true);
    else if (preset == PdfQualityPreset.SMALL) rbSmall.setChecked(true);
    else if (preset == PdfQualityPreset.VERY_SMALL) rbVerySmall.setChecked(true);

    if (jpegMode == JpegExportOptions.Mode.NONE) rbJpegNone.setChecked(true);
    else if (jpegMode == JpegExportOptions.Mode.AUTO) rbJpegAuto.setChecked(true);
    else if (jpegMode == JpegExportOptions.Mode.BW_TEXT) rbJpegBw.setChecked(true);
    else if (jpegMode == JpegExportOptions.Mode.BW_ROBUST) rbJpegBwRobust.setChecked(true);
    else if (jpegMode == JpegExportOptions.Mode.OCR_ROBUST) rbJpegOcrRobust.setChecked(true);

    // Initialize PDF mode radios (none selected if no saved value)
    if ("GRAYSCALE".equalsIgnoreCase(pdfBwModeSaved)) rbPdfGray.setChecked(true);
    else if ("CLASSIC".equalsIgnoreCase(pdfBwModeSaved)) rbPdfBwClassic.setChecked(true);
    else if ("ROBUST".equalsIgnoreCase(pdfBwModeSaved)) rbPdfBwRobust.setChecked(true);
    else if ("OCR_ROBUST".equalsIgnoreCase(pdfBwModeSaved)) rbPdfOcrRobust.setChecked(true);

    // ── Inbox Mode UI ──
    View inboxGroup = view.findViewById(R.id.dialog_inbox_group);
    cbInboxEnabled = view.findViewById(R.id.dialog_checkbox_inbox_enabled);
    inboxFolderLabel = view.findViewById(R.id.dialog_inbox_folder_label);
    View btnInboxSelect = view.findViewById(R.id.dialog_button_inbox_select);
    View btnInboxClear = view.findViewById(R.id.dialog_button_inbox_clear);

    if (FeatureFlags.isInboxModeEnabled() && inboxGroup != null) {
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

      if (btnInboxSelect != null) {
        btnInboxSelect.setOnClickListener(v2 -> inboxFolderLauncher.launch((Uri) null));
      }
      if (btnInboxClear != null) {
        btnInboxClear.setOnClickListener(
            v2 -> {
              ExportPrefsHelper.clearInbox(ctx);
              cbInboxEnabled.setChecked(false);
              updateInboxFolderLabel();
            });
      }

      // Filename template spinner
      android.widget.Spinner filenameSpinner =
          view.findViewById(R.id.dialog_inbox_filename_spinner);
      if (filenameSpinner != null) {
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

      // Auto new scan checkbox
      CheckBox cbAutoNewScan = view.findViewById(R.id.dialog_checkbox_inbox_auto_new_scan);
      if (cbAutoNewScan != null) {
        cbAutoNewScan.setChecked(ExportPrefsHelper.isInboxAutoNewScan(ctx));
        cbAutoNewScan.setOnCheckedChangeListener(
            (buttonView, isChecked) -> ExportPrefsHelper.setInboxAutoNewScan(ctx, isChecked));
      }
    }

    // Visibility toggle between PDF and JPEG groups based on selected format
    updateGroups(exportAsJpeg, pdfGroup, jpegGroup);
    RadioGroup formatGroup = view.findViewById(R.id.dialog_format_group);
    if (formatGroup != null) {
      formatGroup.setOnCheckedChangeListener(
          (group, checkedId) -> {
            boolean jpegSelected = checkedId == rbExportJpeg.getId();
            updateGroups(jpegSelected, pdfGroup, jpegGroup);
          });
    }

    // PDF color mode via CheckBoxes: allow none, but enforce mutual exclusivity when one is checked
    View.OnClickListener pdfModeClick =
        v -> {
          if (!(v instanceof CheckBox clicked)) return;
          boolean nowChecked = clicked.isChecked();
          if (nowChecked) {
            if (clicked != rbPdfGray) rbPdfGray.setChecked(false);
            if (clicked != rbPdfBwRobust) rbPdfBwRobust.setChecked(false);
            if (clicked != rbPdfBwClassic) rbPdfBwClassic.setChecked(false);
            if (clicked != rbPdfOcrRobust) rbPdfOcrRobust.setChecked(false);
          }
        };
    rbPdfGray.setOnClickListener(pdfModeClick);
    rbPdfBwRobust.setOnClickListener(pdfModeClick);
    rbPdfBwClassic.setOnClickListener(pdfModeClick);
    rbPdfOcrRobust.setOnClickListener(pdfModeClick);

    // JPEG modes use RadioGroup; mutual exclusivity is handled by the group.

    AlertDialog dialog =
        new AlertDialog.Builder(ctx)
            .setTitle(R.string.export_options_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
            .setPositiveButton(
                R.string.confirm,
                (d, w) -> {
                  boolean incOcr = cbIncludeOcr.isChecked();
                  boolean asJpeg = rbExportJpeg.isChecked();

                  // determine jpeg mode from RadioGroup
                  JpegExportOptions.Mode mode = JpegExportOptions.Mode.AUTO;
                  int jpegCheckedId = jpegModeGroup.getCheckedRadioButtonId();
                  if (jpegCheckedId == rbJpegNone.getId()) mode = JpegExportOptions.Mode.NONE;
                  else if (jpegCheckedId == rbJpegAuto.getId()) mode = JpegExportOptions.Mode.AUTO;
                  else if (jpegCheckedId == rbJpegBw.getId()) mode = JpegExportOptions.Mode.BW_TEXT;
                  else if (jpegCheckedId == rbJpegBwRobust.getId())
                    mode = JpegExportOptions.Mode.BW_ROBUST;
                  else if (jpegCheckedId == rbJpegOcrRobust.getId())
                    mode = JpegExportOptions.Mode.OCR_ROBUST;

                  // determine PDF color mode (null = none selected)
                  String pdfBwMode = null;
                  if (rbPdfGray.isChecked()) pdfBwMode = "GRAYSCALE";
                  else if (rbPdfBwClassic.isChecked()) pdfBwMode = "CLASSIC";
                  else if (rbPdfBwRobust.isChecked()) pdfBwMode = "ROBUST";
                  else if (rbPdfOcrRobust.isChecked()) pdfBwMode = "OCR_ROBUST";

                  // determine pdf preset
                  PdfQualityPreset sel = PdfQualityPreset.STANDARD;
                  int checkedId = pdfPresetGroup.getCheckedRadioButtonId();
                  if (checkedId == rbHigh.getId()) sel = PdfQualityPreset.HIGH;
                  else if (checkedId == rbStandard.getId()) sel = PdfQualityPreset.STANDARD;
                  else if (checkedId == rbSmall.getId()) sel = PdfQualityPreset.SMALL;
                  else if (checkedId == rbVerySmall.getId()) sel = PdfQualityPreset.VERY_SMALL;

                  // determine page format
                  PageFormat selFormat = PageFormat.FIT_TO_IMAGE;
                  int pageFormatCheckedId = pageFormatGroup.getCheckedRadioButtonId();
                  if (pageFormatCheckedId == rbPageFit.getId()) selFormat = PageFormat.FIT_TO_IMAGE;
                  else if (pageFormatCheckedId == rbPageA4.getId()) selFormat = PageFormat.A4;
                  else if (pageFormatCheckedId == rbPageLetter.getId())
                    selFormat = PageFormat.US_LETTER;
                  else if (pageFormatCheckedId == rbPageLegal.getId()) selFormat = PageFormat.LEGAL;

                  // persist
                  SharedPreferences.Editor editor =
                      prefs
                          .edit()
                          .putBoolean("include_ocr", incOcr)
                          .putBoolean("export_as_jpeg", asJpeg)
                          .putString("jpeg_mode", mode.name())
                          .putString("pdf_preset", sel.name())
                          .putString("page_format", selFormat.name());
                  if (pdfBwMode != null) editor.putString("pdf_bw_mode", pdfBwMode);
                  else editor.remove("pdf_bw_mode");
                  editor.apply();

                  if (cbShareAttachOnly != null) {
                    ExportPrefsHelper.setShareAttachOnly(ctx, cbShareAttachOnly.isChecked());
                  }

                  Bundle result = new Bundle();
                  result.putBoolean(BUNDLE_INCLUDE_OCR, incOcr);
                  result.putBoolean(BUNDLE_EXPORT_AS_JPEG, asJpeg);
                  result.putString(BUNDLE_JPEG_MODE, mode.name());
                  if (pdfBwMode != null) result.putString("pdf_bw_mode", pdfBwMode);
                  else result.remove("pdf_bw_mode");
                  result.putString(BUNDLE_PDF_PRESET, sel.name());
                  result.putString(BUNDLE_PAGE_FORMAT, selFormat.name());
                  getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
                })
            .create();

    // Improve dark mode contrast for dialog buttons similar to other dialogs
    dialog.setOnShowListener(
        dlg -> DialogUtils.improveAlertDialogButtonContrastForNight(dialog, ctx));

    return dialog;
  }

  private void updateGroups(boolean exportJpeg, View pdfGroup, View jpegGroup) {
    pdfGroup.setVisibility(exportJpeg ? View.GONE : View.VISIBLE);
    jpegGroup.setVisibility(exportJpeg ? View.VISIBLE : View.GONE);
  }
}
