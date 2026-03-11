package de.schliweb.makeacopy.ui.export;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import de.schliweb.makeacopy.utils.export.PageFormat;
import de.schliweb.makeacopy.utils.export.PdfCreator;
import de.schliweb.makeacopy.utils.export.PdfQualityPreset;
import de.schliweb.makeacopy.utils.export.jpeg.JpegExportOptions;
import lombok.experimental.UtilityClass;

/**
 * Helper class that centralizes reading export-related SharedPreferences. Extracted from
 * ExportFragment to reduce repetitive preference access code.
 *
 * <p>This class cannot be instantiated.
 */
@UtilityClass
public final class ExportPrefsHelper {

  private static final String PREFS_NAME = "export_options";

  public static SharedPreferences getPrefs(Context context) {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  public static boolean isIncludeOcr(Context context) {
    return getPrefs(context).getBoolean("include_ocr", false);
  }

  public static boolean isExportAsJpeg(Context context) {
    return getPrefs(context).getBoolean("export_as_jpeg", false);
  }

  public static boolean isSkipOcr(Context context) {
    return getPrefs(context).getBoolean("skip_ocr", false);
  }

  public static String getPdfBwMode(Context context) {
    return getPrefs(context).getString("pdf_bw_mode", null);
  }

  public static boolean isGrayscaleFromPdfMode(Context context) {
    return "GRAYSCALE".equalsIgnoreCase(getPdfBwMode(context));
  }

  public static String getPdfPreset(Context context) {
    return getPrefs(context).getString("pdf_preset", null);
  }

  public static String getPageFormatSaved(Context context) {
    return getPrefs(context).getString("page_format", null);
  }

  public static PageFormat resolvePageFormat(Context context) {
    return PageFormat.fromName(getPageFormatSaved(context), PageFormat.FIT_TO_IMAGE);
  }

  public static PdfQualityPreset resolvePreset(Context context, int pageCount) {
    String presetSaved = getPdfPreset(context);
    PdfQualityPreset def = (pageCount > 1) ? PdfQualityPreset.STANDARD : PdfQualityPreset.HIGH;
    return PdfQualityPreset.fromName(presetSaved, def);
  }

  public static PdfCreator.BwMode resolveBwMode(Context context) {
    String bw = getPdfBwMode(context);
    if ("CLASSIC".equalsIgnoreCase(bw)) return PdfCreator.BwMode.CLASSIC;
    if ("ROBUST".equalsIgnoreCase(bw)) return PdfCreator.BwMode.ROBUST;
    if ("OCR_ROBUST".equalsIgnoreCase(bw)) return PdfCreator.BwMode.OCR_ROBUST;
    return null;
  }

  /**
   * Resolves effective grayscale and BW flags based on the pdf_bw_mode preference and the preset.
   *
   * @return a boolean array: [0] = convertGrayEffective, [1] = convertBwEffective
   */
  public static boolean[] resolveGrayAndBwFlags(
      Context context, boolean presetForceGrayscale, boolean viewModelGrayscale) {
    String pdfModeSel = getPdfBwMode(context);
    String bwModeSaved = pdfModeSel;
    boolean convertBw =
        ("ROBUST".equalsIgnoreCase(bwModeSaved) || "CLASSIC".equalsIgnoreCase(bwModeSaved));
    boolean convertGray = presetForceGrayscale || viewModelGrayscale;

    if ("GRAYSCALE".equalsIgnoreCase(pdfModeSel)) {
      convertGray = true;
      convertBw = false;
    } else if ("CLASSIC".equalsIgnoreCase(pdfModeSel) || "ROBUST".equalsIgnoreCase(pdfModeSel)) {
      convertGray = false;
      convertBw = true;
    } else if ("OCR_ROBUST".equalsIgnoreCase(pdfModeSel)) {
      convertGray = false;
      convertBw = false;
    }
    return new boolean[] {convertGray, convertBw};
  }

  public static JpegExportOptions.Mode resolveJpegMode(Context context) {
    String saved = getPrefs(context).getString("jpeg_mode", JpegExportOptions.Mode.AUTO.name());
    try {
      return JpegExportOptions.Mode.valueOf(saved);
    } catch (IllegalArgumentException ex) {
      return JpegExportOptions.Mode.AUTO;
    }
  }

  public static boolean isPendingAddPage(Context context) {
    return getPrefs(context).getBoolean("pending_add_page", false);
  }

  public static void clearPendingAddPage(Context context) {
    getPrefs(context).edit().putBoolean("pending_add_page", false).apply();
  }

  public static void setPendingAddPage(Context context) {
    getPrefs(context).edit().putBoolean("pending_add_page", true).apply();
  }

  public static String getLastImportUri(Context context) {
    String value = getPrefs(context).getString("last_import_uri", null);
    Log.d("ExportPrefsHelper", "getLastImportUri: " + value);
    return value;
  }

  public static void setLastImportUri(Context context, String uri) {
    Log.d("ExportPrefsHelper", "setLastImportUri: " + uri);
    getPrefs(context).edit().putString("last_import_uri", uri).apply();
  }

  public static String getLastExportUri(Context context) {
    String value = getPrefs(context).getString("last_export_uri", null);
    Log.d("ExportPrefsHelper", "getLastExportUri: " + value);
    return value;
  }

  public static void setLastExportUri(Context context, String uri) {
    Log.d("ExportPrefsHelper", "setLastExportUri: " + uri);
    getPrefs(context).edit().putString("last_export_uri", uri).apply();
  }

  // ── Share: attach only (no subject/body pre-fill) ──

  private static final String KEY_SHARE_ATTACH_ONLY = "share_attach_only";

  /**
   * Returns {@code true} when the user wants to share documents as attachment only, without
   * pre-filling email subject or body (EXTRA_SUBJECT, EXTRA_TEXT, EXTRA_TITLE).
   */
  public static boolean isShareAttachOnly(Context context) {
    return getPrefs(context).getBoolean(KEY_SHARE_ATTACH_ONLY, false);
  }

  public static void setShareAttachOnly(Context context, boolean value) {
    getPrefs(context).edit().putBoolean(KEY_SHARE_ATTACH_ONLY, value).apply();
  }

  // ── Inbox Mode preferences ──

  private static final String KEY_INBOX_ENABLED = "inbox_enabled";
  private static final String KEY_INBOX_URI = "inbox_uri";

  /** Returns {@code true} when the user has enabled Inbox Mode in settings. */
  public static boolean isInboxEnabled(Context context) {
    return getPrefs(context).getBoolean(KEY_INBOX_ENABLED, false);
  }

  public static void setInboxEnabled(Context context, boolean enabled) {
    getPrefs(context).edit().putBoolean(KEY_INBOX_ENABLED, enabled).apply();
  }

  /** Returns the persisted tree URI string for the Inbox directory, or {@code null}. */
  public static String getInboxUri(Context context) {
    return getPrefs(context).getString(KEY_INBOX_URI, null);
  }

  public static void setInboxUri(Context context, String uri) {
    getPrefs(context).edit().putString(KEY_INBOX_URI, uri).apply();
  }

  /** Clears the Inbox directory URI and disables Inbox Mode. */
  public static void clearInbox(Context context) {
    getPrefs(context).edit().remove(KEY_INBOX_URI).putBoolean(KEY_INBOX_ENABLED, false).apply();
  }

  // ── Inbox filename template ──

  private static final String KEY_INBOX_FILENAME_TEMPLATE = "inbox_filename_template";

  /**
   * Returns the configured filename template for Inbox exports. Defaults to {@code "date_scan"}
   * which produces names like {@code 2026-03-04_scan.pdf}.
   *
   * <p>Supported values: {@code "date_scan"}, {@code "date_time_scan"}, {@code "date_only"}.
   */
  public static String getInboxFilenameTemplate(Context context) {
    return getPrefs(context).getString(KEY_INBOX_FILENAME_TEMPLATE, "date_scan");
  }

  public static void setInboxFilenameTemplate(Context context, String template) {
    getPrefs(context).edit().putString(KEY_INBOX_FILENAME_TEMPLATE, template).apply();
  }

  // ── Auto new scan after inbox export ──

  private static final String KEY_INBOX_AUTO_NEW_SCAN = "inbox_auto_new_scan";

  /** Returns {@code true} when the app should automatically start a new scan after inbox export. */
  public static boolean isInboxAutoNewScan(Context context) {
    return getPrefs(context).getBoolean(KEY_INBOX_AUTO_NEW_SCAN, false);
  }

  public static void setInboxAutoNewScan(Context context, boolean enabled) {
    getPrefs(context).edit().putBoolean(KEY_INBOX_AUTO_NEW_SCAN, enabled).apply();
  }
}
