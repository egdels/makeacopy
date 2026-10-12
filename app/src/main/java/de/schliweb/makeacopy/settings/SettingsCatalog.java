/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.settings;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The one list of every user-facing setting: which SharedPreferences file holds it, under which
 * key, of which type, with which default, and in which section of the options dialog it belongs.
 *
 * <p>The list is the reference for the options dialog and for anything that has to capture all
 * settings at once (a future settings profile, an export). {@code SettingsCatalogTest} scans the
 * sources and fails when a preference key is written somewhere that is neither listed here nor in
 * {@link #TRANSIENT_KEYS}, so a new setting cannot be added without deciding where it belongs.
 *
 * <p>This class has no Android dependency on purpose so that it can be used from plain unit tests.
 */
public final class SettingsCatalog {

  private SettingsCatalog() {}

  /** Historic name of the main settings file; it holds far more than export options by now. */
  public static final String PREFS_MAIN = "export_options";

  public static final String PREFS_CROP = "crop_options";
  public static final String PREFS_CLEANUP = "cache_cleanup_prefs";
  public static final String PREFS_LANGUAGE = "app_language";

  /** Sections of the options dialog, in display order. */
  public enum Group {
    SCAN,
    CAMERA,
    OCR,
    EXPORT,
    CROP,
    LIBRARY,
    APP
  }

  public enum Type {
    BOOLEAN,
    STRING,
    INT,
    FLOAT
  }

  /**
   * One setting. {@code defaultValue} is the value the reading code assumes when the key is absent;
   * {@code null} means "no value" (for example: system default, nothing chosen yet). Enum-typed
   * settings are stored by name and listed here with the name as a string.
   */
  public record Setting(String prefsFile, String key, Type type, Object defaultValue, Group group) {
    public Setting {
      if (prefsFile == null || key == null || type == null || group == null) {
        throw new IllegalArgumentException("prefsFile, key, type and group are required");
      }
    }
  }

  private static Setting bool(String key, boolean def, Group group) {
    return new Setting(PREFS_MAIN, key, Type.BOOLEAN, def, group);
  }

  private static Setting str(String key, String def, Group group) {
    return new Setting(PREFS_MAIN, key, Type.STRING, def, group);
  }

  /** Every user-facing setting, grouped as in the options dialog. */
  public static final List<Setting> ALL =
      List.of(
          // Scan: what happens between capture and export
          bool("skip_ocr", false, Group.SCAN),
          bool("skip_cropping", false, Group.SCAN),
          bool("skip_edge_detection", false, Group.SCAN),
          bool("analysis_enabled", false, Group.SCAN),
          // Camera: how the picture is taken
          bool("exposure_compensation_enabled", false, Group.CAMERA),
          bool("manual_focus_enabled", false, Group.CAMERA),
          bool("focus_quality_indicator_enabled", false, Group.CAMERA),
          bool("low_light_prompt_enabled", true, Group.CAMERA),
          // OCR
          str("ocr_language", null, Group.OCR), // "eng" or "deu+eng"; null = system language
          new Setting(PREFS_MAIN, "ocr_prep_mode", Type.INT, 2, Group.OCR), // 2 = Robust
          bool("ocr_auto_rotate_apply_export", false, Group.OCR),
          bool("ocr_post_processing", true, Group.OCR),
          bool("layout_analysis", false, Group.OCR), // behind FeatureFlags.isLayoutAnalysisEnabled
          bool("paddle_best_ocr", false, Group.OCR), // paddle flavor only
          bool("multi_column_ocr", false, Group.OCR), // paddle flavor only
          // Export
          bool("include_ocr", false, Group.EXPORT),
          bool("export_as_jpeg", false, Group.EXPORT),
          str("document_cleanup_mode", "ORIGINAL", Group.EXPORT), // DocumentCleanupMode
          str("pdf_bw_mode", null, Group.EXPORT), // GRAYSCALE, ROBUST, CLASSIC; null = colour
          str("page_format", "FIT_TO_IMAGE", Group.EXPORT), // PageFormat
          str("pdf_text_layer_mode", "LINE_BASED", Group.EXPORT), // PdfCreator.TextLayerMode
          str("pdf_preset", null, Group.EXPORT), // PdfQualityPreset; null = by page count
          str("jpeg_mode", "NONE", Group.EXPORT), // JpegExportOptions.Mode
          bool("jpeg_output_grayscale", false, Group.EXPORT),
          bool("inbox_enabled", false, Group.EXPORT), // behind FeatureFlags.isInboxModeEnabled
          str("inbox_uri", null, Group.EXPORT),
          str("inbox_filename_template", "date_scan", Group.EXPORT),
          bool("inbox_auto_new_scan", false, Group.EXPORT),
          // Crop
          new Setting(PREFS_CROP, "aspect", Type.STRING, "AUTO", Group.CROP), // CropAspectRatio
          new Setting(PREFS_CROP, "aspect_custom_w", Type.FLOAT, null, Group.CROP),
          new Setting(PREFS_CROP, "aspect_custom_h", Type.FLOAT, null, Group.CROP),
          new Setting(PREFS_CROP, "snap_right_angle", Type.BOOLEAN, false, Group.CROP),
          // Library
          new Setting(
              PREFS_CLEANUP, "completed_scans_cleanup_policy", Type.STRING, "NONE", Group.LIBRARY),
          new Setting(PREFS_CLEANUP, "completed_scans_max_age_days", Type.INT, 30, Group.LIBRARY),
          new Setting(PREFS_CLEANUP, "completed_scans_max_count", Type.INT, 100, Group.LIBRARY),
          new Setting(
              PREFS_CLEANUP, "completed_scans_max_storage_mb", Type.INT, 500, Group.LIBRARY),
          // App
          bool("accessibility_mode", false, Group.APP),
          new Setting(PREFS_LANGUAGE, "tag", Type.STRING, "", Group.APP)); // "" = system default

  /**
   * Keys that live in the same preference files but are runtime state or legacy values, not
   * settings a user chooses: they are never shown in a dialog and would not belong in a profile.
   */
  public static final Set<String> TRANSIENT_KEYS =
      Set.of(
          // Remembered UI state
          "camera_zoom_ratio",
          "exposure_compensation_index",
          "manual_focus_position",
          "manual_focus_progress",
          "ocr_minimap_visible",
          "ocr_mode",
          "ocr_off_x",
          "ocr_off_y",
          "ocr_scale",
          "last_export_uri",
          "last_import_uri",
          "pending_add_page",
          "current_scan_id",
          "existing_index_done",
          // Cache cleanup service internals
          "last_cleanup_time",
          "cleanup_enabled",
          "cleanup_interval_hours",
          "max_debug_files",
          "max_temp_age_hours",
          "memory_threshold_percent",
          // Legacy keys that are read for migration only
          "pref_ocr_paddle_enabled",
          "convert_to_grayscale",
          "jpeg_force_bw");

  /** The settings of one group, in catalog order. */
  public static List<Setting> inGroup(Group group) {
    return Collections.unmodifiableList(
        ALL.stream().filter(s -> s.group() == group).collect(java.util.stream.Collectors.toList()));
  }

  /** The setting stored under {@code key} in {@code prefsFile}, or {@code null}. */
  public static Setting find(String prefsFile, String key) {
    for (Setting s : ALL) {
      if (s.prefsFile().equals(prefsFile) && s.key().equals(key)) return s;
    }
    return null;
  }
}
