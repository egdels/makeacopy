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
import android.content.SharedPreferences;
import de.schliweb.makeacopy.utils.export.PageFormat;
import de.schliweb.makeacopy.utils.export.PdfCreator;
import de.schliweb.makeacopy.utils.export.PdfQualityPreset;
import de.schliweb.makeacopy.utils.export.jpeg.JpegExportOptions;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
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

  /**
   * The folders and files the user picked (SAF Uris with their folder and file names). They live in
   * their own preference file, which the backup rules exclude: a persisted Uri permission does not
   * survive a restore to another device anyway, and the names would otherwise leave the device with
   * the cloud backup (security audit F-02).
   */
  public static final String PREFS_LOCATIONS = "export_locations";

  private static final String KEY_LAST_IMPORT_URI = "last_import_uri";
  private static final String KEY_LAST_EXPORT_URI = "last_export_uri";

  public static SharedPreferences getPrefs(Context context) {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  private static SharedPreferences locations(Context context) {
    return context.getSharedPreferences(PREFS_LOCATIONS, Context.MODE_PRIVATE);
  }

  /** Reads a picked location; a value still in the old file is moved over once. */
  private static String location(Context context, String key) {
    SharedPreferences prefs = locations(context);
    String value = prefs.getString(key, null);
    if (value != null) return value;
    SharedPreferences legacy = getPrefs(context);
    value = legacy.getString(key, null);
    if (value != null) {
      prefs.edit().putString(key, value).apply();
      legacy.edit().remove(key).apply();
    }
    return value;
  }

  private static void setLocation(Context context, String key, String value) {
    locations(context).edit().putString(key, value).apply();
    getPrefs(context).edit().remove(key).apply();
  }

  public static boolean isIncludeOcr(Context context) {
    return getPrefs(context).getBoolean("include_ocr", false);
  }

  public static boolean isExportAsJpeg(Context context) {
    return getPrefs(context).getBoolean("export_as_jpeg", false);
  }

  public static void setExportAsJpeg(Context context, boolean exportAsJpeg) {
    getPrefs(context).edit().putBoolean("export_as_jpeg", exportAsJpeg).apply();
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
    return null;
  }

  public static PdfCreator.TextLayerMode resolveTextLayerMode(Context context) {
    String saved = getPrefs(context).getString("pdf_text_layer_mode", null);
    try {
      return saved == null
          ? PdfCreator.TextLayerMode.LINE_BASED
          : PdfCreator.TextLayerMode.valueOf(saved);
    } catch (IllegalArgumentException ex) {
      return PdfCreator.TextLayerMode.LINE_BASED;
    }
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
    }
    return new boolean[] {convertGray, convertBw};
  }

  public static JpegExportOptions.Mode resolveJpegMode(Context context) {
    String saved = getPrefs(context).getString("jpeg_mode", JpegExportOptions.Mode.NONE.name());
    try {
      return JpegExportOptions.Mode.valueOf(saved);
    } catch (IllegalArgumentException ex) {
      return JpegExportOptions.Mode.NONE;
    }
  }

  public static boolean isJpegOutputGrayscale(Context context) {
    return getPrefs(context).getBoolean("jpeg_output_grayscale", false);
  }

  public static DocumentCleanupMode resolveCleanupMode(Context context) {
    String saved =
        getPrefs(context).getString("document_cleanup_mode", DocumentCleanupMode.ORIGINAL.name());
    try {
      return DocumentCleanupMode.valueOf(saved);
    } catch (IllegalArgumentException ex) {
      return DocumentCleanupMode.ORIGINAL;
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
    String value = location(context, KEY_LAST_IMPORT_URI);
    return value;
  }

  public static void setLastImportUri(Context context, String uri) {
    setLocation(context, KEY_LAST_IMPORT_URI, uri);
  }

  public static String getLastExportUri(Context context) {
    String value = location(context, KEY_LAST_EXPORT_URI);
    return value;
  }

  public static void setLastExportUri(Context context, String uri) {
    setLocation(context, KEY_LAST_EXPORT_URI, uri);
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
    return location(context, KEY_INBOX_URI);
  }

  public static void setInboxUri(Context context, String uri) {
    setLocation(context, KEY_INBOX_URI, uri);
  }

  /** Clears the Inbox directory URI and disables Inbox Mode. */
  public static void clearInbox(Context context) {
    locations(context).edit().remove(KEY_INBOX_URI).apply();
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
