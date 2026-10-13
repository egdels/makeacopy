/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.export;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

/**
 * Utility for exporting files into the configured Inbox directory using the Storage Access
 * Framework (SAF). The Inbox directory is a user-selected tree URI that is persisted across app
 * restarts.
 *
 * <p>This class handles automatic file naming with collision avoidance and validates that the
 * persisted URI permission is still valid before writing.
 */
public final class InboxExporter {

  private static final String TAG = "InboxExporter";

  private InboxExporter() {}

  /**
   * Builds a default file base name using the current date in {@code yyyy-MM-dd_scan} format.
   *
   * @return the base name without extension, e.g. {@code "2026-03-04_scan"}
   */
  @NonNull
  public static String buildInboxBaseName() {
    return buildInboxBaseName("date_scan");
  }

  /**
   * Builds a file base name according to the given template.
   *
   * <p>Supported templates:
   *
   * <ul>
   *   <li>{@code "date_scan"} → {@code 2026-03-04_scan}
   *   <li>{@code "date_time_scan"} → {@code 2026-03-04_143022_scan}
   *   <li>{@code "date_only"} → {@code 2026-03-04}
   * </ul>
   *
   * @param template the template identifier
   * @return the base name without extension
   */
  @NonNull
  public static String buildInboxBaseName(@NonNull String template) {
    java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneId.systemDefault());
    String date = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    switch (template) {
      case "date_time_scan":
        String time = now.format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
        return date + "_" + time + "_scan";
      case "date_only":
        return date;
      default: // "date_scan"
        return date + "_scan";
    }
  }

  /**
   * Resolves a unique file name inside the given directory by appending {@code _2}, {@code _3}, …
   * when a file with the same name already exists.
   *
   * @param directory the target directory
   * @param baseName the desired base name without extension (e.g. {@code "2026-03-04_scan"})
   * @param extension the file extension including the dot (e.g. {@code ".pdf"})
   * @return a unique file name such as {@code "2026-03-04_scan.pdf"} or {@code
   *     "2026-03-04_scan_2.pdf"}
   */
  @NonNull
  public static String resolveUniqueFileName(
      @NonNull DocumentFile directory, @NonNull String baseName, @NonNull String extension) {
    String candidate = baseName + extension;
    if (directory.findFile(candidate) == null) {
      return candidate;
    }
    for (int i = 2; i < 10000; i++) {
      candidate = baseName + "_" + i + extension;
      if (directory.findFile(candidate) == null) {
        return candidate;
      }
    }
    // Extremely unlikely fallback: use timestamp
    return baseName + "_" + System.currentTimeMillis() + extension;
  }

  /**
   * Checks whether the persisted tree URI still has write permission.
   *
   * @param context application context
   * @param treeUri the persisted tree URI
   * @return {@code true} if the URI is accessible and writable
   */
  public static boolean hasValidPermission(@NonNull Context context, @NonNull Uri treeUri) {
    try {
      DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);
      return dir != null && dir.exists() && dir.canWrite();
    } catch (Exception e) {
      Log.w(TAG, "Permission check failed for inbox URI", e);
      return false;
    }
  }

  /**
   * Creates a new file in the Inbox directory with a unique name and returns its URI.
   *
   * @param context application context
   * @param treeUri the persisted tree URI of the Inbox directory
   * @param mimeType the MIME type of the file (e.g. {@code "application/pdf"})
   * @param baseName the desired base name without extension
   * @param extension the file extension including the dot (e.g. {@code ".pdf"})
   * @return the URI of the newly created file, or {@code null} on failure
   */
  @Nullable
  public static Uri createFileInInbox(
      @NonNull Context context,
      @NonNull Uri treeUri,
      @NonNull String mimeType,
      @NonNull String baseName,
      @NonNull String extension) {
    try {
      DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);
      if (dir == null || !dir.exists() || !dir.canWrite()) {
        Log.w(TAG, "Inbox directory not accessible");
        return null;
      }
      String fileName = resolveUniqueFileName(dir, baseName, extension);
      DocumentFile newFile = dir.createFile(mimeType, fileName);
      if (newFile == null) {
        Log.w(TAG, "Failed to create file in inbox");
        return null;
      }
      Log.d(TAG, "Created inbox file: " + newFile.getUri());
      return newFile.getUri();
    } catch (Exception e) {
      Log.e(TAG, "Error creating file in inbox", e);
      return null;
    }
  }
}
