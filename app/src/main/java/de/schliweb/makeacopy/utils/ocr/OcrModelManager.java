/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Paddle flavor model manager. Tesseract traineddata import is not supported in this flavor. */
public final class OcrModelManager {
  public static final String ACTION_TESSDATA = "de.schliweb.makeacopy.ACTION_TESSDATA";
  public static final long MAX_FILE_SIZE_BYTES = 50L * 1024L * 1024L;
  private static final String[] AVAILABLE_LANGUAGE_CODES =
      new String[] {"en", "latin", "eslav", "cyrillic", "arabic", "devanagari", "th", "el", "zh"};

  private OcrModelManager() {
    // no instances
  }

  public static File getOrCreateTessdataDir(Context context) throws IOException {
    if (context == null) {
      throw new IOException("Context is required");
    }
    return new File(context.getFilesDir(), "tessdata");
  }

  public static List<String> discoverAddonPackages(Context context) {
    return Collections.emptyList();
  }

  public static List<String> listTrainedDataInPackage(Context context, String packageName) {
    return Collections.emptyList();
  }

  public static boolean isUsingBestModel(Context context, String code) {
    return false;
  }

  public static String[] getAvailableLanguageCodes(Context context) {
    return AVAILABLE_LANGUAGE_CODES.clone();
  }

  public static boolean importFromPackage(Context context, String packageName, String filename) {
    return false;
  }

  public static boolean importFromUri(Context context, Uri uri) {
    return false;
  }

  public static boolean deleteLocalModel(Context context, String langCode) {
    return false;
  }

  public static Intent createOpenTraineddataIntent() {
    return new Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/octet-stream");
  }
}