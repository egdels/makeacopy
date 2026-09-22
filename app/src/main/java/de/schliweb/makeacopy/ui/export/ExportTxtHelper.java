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

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Helper class that encapsulates OCR text export logic (TXT file creation). Extracted from
 * ExportFragment to reduce its size.
 *
 * <p>This class cannot be instantiated.
 */
@UtilityClass
final class ExportTxtHelper {

  private static final String TAG = "ExportTxtHelper";

  static String readAllUtf8(File file) throws IOException {
    byte[] buf = Files.readAllBytes(file.toPath());
    return new String(buf, StandardCharsets.UTF_8);
  }

  /** What to do with the TXT companion file after a successful document export. */
  enum TxtExportAction {
    /** No TXT file is written. */
    NONE,
    /** The TXT file goes straight into the inbox folder (no file picker). */
    INBOX,
    /** The user picks the TXT location in the file picker. */
    PICKER
  }

  /**
   * Decides whether and how the TXT file is exported. A TXT file is only ever written for a real
   * OCR result: without recognized text (e.g. "Skip OCR" was active) nothing is exported, no matter
   * how "Include TXT" is set.
   *
   * @param includeTxt the "Include TXT" export option
   * @param inboxExportInProgress whether the document itself was exported to the inbox
   * @param ocrText the text that would be written, see {@link #collectOcrText}
   */
  static TxtExportAction decideTxtExport(
      boolean includeTxt, boolean inboxExportInProgress, String ocrText) {
    if (!includeTxt || !hasOcrText(ocrText)) return TxtExportAction.NONE;
    return inboxExportInProgress ? TxtExportAction.INBOX : TxtExportAction.PICKER;
  }

  /** Whether the text holds an actual OCR result (more than whitespace/page separators). */
  static boolean hasOcrText(String text) {
    return text != null && !text.trim().isEmpty();
  }

  /**
   * Collects the OCR text that a TXT export would contain. For multi-page sessions, concatenates
   * per-page OCR text in filmstrip order.
   *
   * @param pages the session pages (nullable)
   * @param currentText the in-memory OCR text for the current page
   * @param currentPreviewBitmap the currently previewed bitmap (to match in-memory OCR)
   */
  static String collectOcrText(
      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages,
      String currentText,
      Bitmap currentPreviewBitmap) {
    boolean isMulti = pages != null && pages.size() > 1;

    // Single-page: the in-memory OCR text, or — when OCR was skipped in the scan flow and run
    // later from the export screen — the text the background job persisted for the page
    if (!isMulti) {
      if (currentText != null && !currentText.isEmpty()) return currentText;
      return (pages != null && pages.size() == 1) ? readPersistedPageText(pages.get(0)) : null;
    }

    // Multi-page: concatenate per-page OCR from registry
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < pages.size(); i++) {
      de.schliweb.makeacopy.ui.export.session.CompletedScan s = pages.get(i);
      String pageText = readPersistedPageText(s);
      if ((pageText == null || pageText.isEmpty())
          && s != null
          && s.inMemoryBitmap() != null
          && currentPreviewBitmap == s.inMemoryBitmap()) {
        pageText = currentText;
      }
      if (pageText != null) sb.append(pageText);
      if (i < pages.size() - 1) sb.append("\n\n");
    }
    return sb.toString();
  }

  /**
   * Reads the persisted OCR text of a page: the plain text file itself, or — for words_json — the
   * text.txt that is written next to it.
   */
  private static String readPersistedPageText(
      de.schliweb.makeacopy.ui.export.session.CompletedScan page) {
    String path = (page != null) ? page.ocrTextPath() : null;
    if (path == null) return null;
    String fmt = page.ocrFormat();
    boolean isPlain = (fmt == null) || "plain".equalsIgnoreCase(fmt);
    File textFile = new File(path);
    if (!isPlain) {
      File dir = textFile.getParentFile();
      if (dir == null) return null;
      textFile = new File(dir, "text.txt");
    }
    if (!textFile.exists() || !textFile.isFile()) return null;
    try {
      return readAllUtf8(textFile);
    } catch (IOException e) {
      Log.w(TAG, "Failed reading OCR text for page: " + textFile.getAbsolutePath(), e);
      return null;
    }
  }

  /**
   * Exports OCR text to a TXT file at the given URI. For multi-page sessions, concatenates per-page
   * OCR text in filmstrip order. Nothing is written when there is no OCR text.
   *
   * @param context the context for content resolver access
   * @param exportViewModel the export view model to update TXT URI
   * @param exportSessionViewModel the session view model for multi-page access
   * @param txtUri the target URI for the TXT file
   * @param currentText the in-memory OCR text for the current page
   * @param currentPreviewBitmap the currently previewed bitmap (to match in-memory OCR)
   * @param deferAssignCallback callback to clear the deferAssignUntilTxt flag on success
   */
  static void exportOcrTextToTxt(
      Context context,
      ExportViewModel exportViewModel,
      de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel exportSessionViewModel,
      Uri txtUri,
      String currentText,
      Bitmap currentPreviewBitmap,
      Runnable deferAssignCallback) {
    if (txtUri == null) return;

    List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
        exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
    String text = collectOcrText(pages, currentText, currentPreviewBitmap);
    if (!hasOcrText(text)) {
      Log.d(TAG, "exportOcrTextToTxt: No OCR text available to export");
      return;
    }
    writeTxtToUri(context, exportViewModel, txtUri, text, deferAssignCallback);
  }

  static void writeTxtToUri(
      Context context,
      ExportViewModel exportViewModel,
      Uri txtUri,
      String content,
      Runnable deferAssignCallback) {
    try (OutputStream os = context.getContentResolver().openOutputStream(txtUri)) {
      if (os == null) {
        Log.e(TAG, "writeTxtToUri: Failed to open output stream for TXT file");
        return;
      }
      byte[] bytes = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);
      os.write(bytes);
      exportViewModel.setTxtExportUri(txtUri);
      UIUtils.showToast(
          context, context.getString(R.string.ocr_text_exported_as_txt), Toast.LENGTH_SHORT);

      if (deferAssignCallback != null) {
        deferAssignCallback.run();
      }
    } catch (java.io.FileNotFoundException | SecurityException e) {
      Log.e(TAG, "writeTxtToUri: Permission or file error during TXT export", e);
      UIUtils.showToast(
          context,
          context.getString(R.string.error_exporting_ocr_text_with_reason, e.getMessage()),
          Toast.LENGTH_SHORT);
    } catch (IOException e) {
      Log.e(TAG, "writeTxtToUri: I/O error during TXT export", e);
      UIUtils.showToast(
          context,
          context.getString(R.string.error_exporting_ocr_text_with_reason, e.getMessage()),
          Toast.LENGTH_SHORT);
    }
  }
}
