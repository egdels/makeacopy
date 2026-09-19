/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.data;

import android.content.Context;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Utility for cleaning up on-disk artifacts related to CompletedScansRegistry.
 *
 * <p>Strategy: - removeEntryAndFiles: remove a single entry from the registry and delete its folder
 * files/scans/<id>. - cleanupOrphans: prune registry entries whose primary file is missing and
 * delete orphaned scan folders that are not referenced by the registry.
 *
 * <p>This keeps the app storage tidy without requiring a background job. Can be invoked
 * opportunistically (e.g., when opening the Completed Scans picker).
 */
@UtilityClass
public class RegistryCleaner {
  private static final String TAG = "RegistryCleaner";

  /**
   * Represents a report detailing the outcomes of cleanup operations, such as orphaned directories
   * or files removed during the process.
   *
   * <p>This class is utilized by methods performing cleanup actions, providing a structured summary
   * of the tasks executed and their effects on the file and registry system.
   *
   * <p>Fields: - prunedMissingEntries: Number of registry entries removed due to the absence of
   * their primary file. - deletedOrphanDirs: Number of orphaned directories deleted, which were not
   * referenced by any registry entries. - deletedFilesInRemove: Number of files deleted as part of
   * removing registry entries and associated files.
   */
  public static class CleanupReport {
    public int prunedMissingEntries; // entries removed from index due to missing primary file
    public int deletedOrphanDirs; // scan directories deleted that weren't referenced
    public int deletedFilesInRemove; // files deleted when calling removeEntryAndFiles

    @Override
    public String toString() {
      return "CleanupReport{"
          + "prunedMissingEntries="
          + prunedMissingEntries
          + ", deletedOrphanDirs="
          + deletedOrphanDirs
          + ", deletedFilesInRemove="
          + deletedFilesInRemove
          + '}';
    }
  }

  /**
   * Removes the registry entry with the given id and deletes its on-disk folder under
   * files/scans/<id>.
   */
  public static CleanupReport removeEntryAndFiles(Context ctx, String id) {
    CleanupReport rep = new CleanupReport();
    if (ctx == null || id == null) return rep;
    try {
      // Delete folder first (best-effort)
      File dir = new File(ctx.getFilesDir(), "scans/" + id);
      rep.deletedFilesInRemove += deleteDirectoryRecursively(dir);
    } catch (Throwable t) {
      System.err.println(
          TAG + ": removeEntryAndFiles: failed deleting dir for id=" + id + ": " + t.getMessage());
    }
    try {
      CompletedScansRegistry.get(ctx).remove(id);
    } catch (IOException e) {
      System.err.println(
          TAG
              + ": removeEntryAndFiles: registry.remove failed for id="
              + id
              + ": "
              + e.getMessage());
    }
    return rep;
  }

  /**
   * Performs on-demand cleanup: - Removes registry entries whose primary filePath is missing; also
   * deletes their scan directory if present. - Deletes orphaned scan directories (files/scans/<id>)
   * not referenced by the registry.
   */
  public static CleanupReport cleanupOrphans(Context ctx) {
    CleanupReport rep = new CleanupReport();
    if (ctx == null) return rep;

    CompletedScansRegistry reg = CompletedScansRegistry.get(ctx);
    List<CompletedScan> items = reg.listAllOrderedByDateDesc();
    Set<String> ids = new HashSet<>();
    for (CompletedScan s : items) {
      if (s == null || s.id() == null) continue;
      ids.add(s.id());
      // Check primary filePath; if missing, prune entry and delete its directory
      String filePath = s.filePath();
      boolean missing = (filePath == null) || !new File(filePath).exists();
      if (missing) {
        // Delete its directory (best-effort)
        File dir = new File(ctx.getFilesDir(), "scans/" + s.id());
        rep.deletedFilesInRemove += deleteDirectoryRecursively(dir);
        try {
          reg.remove(s.id());
          rep.prunedMissingEntries++;
        } catch (IOException e) {
          System.err.println(
              TAG
                  + ": cleanupOrphans: failed to remove missing entry id="
                  + s.id()
                  + ": "
                  + e.getMessage());
        }
      } else {
        // Normalize paths to canonical post-crop outputs if available
        try {
          boolean updated = false;
          File canonicalDir = new File(ctx.getFilesDir(), "scans/" + s.id());
          File canonicalPage = new File(canonicalDir, "page.jpg");
          File canonicalThumb = new File(canonicalDir, "thumb.jpg");

          String newFilePath = s.filePath();
          if (canonicalPage.exists() && canonicalPage.isFile()) {
            String canon = canonicalPage.getAbsolutePath();
            if (!canon.equals(newFilePath)) {
              newFilePath = canon;
              updated = true;
            }
          }
          String newThumbPath = s.thumbPath();
          if (canonicalThumb.exists() && canonicalThumb.isFile()) {
            String canonT = canonicalThumb.getAbsolutePath();
            if (!canonT.equals(newThumbPath)) {
              newThumbPath = canonT;
              updated = true;
            }
          }
          if (updated) {
            CompletedScan normalized =
                new CompletedScan(
                    s.id(),
                    newFilePath,
                    s.rotationDeg(),
                    s.ocrTextPath(),
                    s.ocrFormat(),
                    newThumbPath,
                    s.createdAt(),
                    s.widthPx(),
                    s.heightPx(),
                    s.inMemoryBitmap(),
                    s.schemaVersion(),
                    s.orientationMode());
            try {
              reg.remove(s.id());
            } catch (IOException ignore) {
              // Best-effort; failure is non-critical
            }
            try {
              reg.insert(normalized);
            } catch (IOException ignore) {
              // Best-effort; failure is non-critical
            }
          }
        } catch (Throwable t) {
          // ignore normalization errors to keep cleanup best-effort
        }
      }
    }

    // Remove orphan directories not referenced by registry. Session 5 (page ownership):
    // directories whose id is still referenced by any persisted DocumentSession (active or
    // inactive) are never deleted here, even when the registry entry is missing — the document
    // reference keeps the page artifacts alive until an explicit user deletion.
    Set<String> sessionReferencedIds;
    try {
      sessionReferencedIds = DocumentSessionRepository.get(ctx).getAllReferencedPageIds();
    } catch (Throwable t) {
      // Fail safe: when the reference set cannot be determined, do not delete any directory.
      System.err.println(
          TAG + ": cleanupOrphans: cannot resolve session references, skipping orphan dirs");
      return rep;
    }
    File scansBase = new File(ctx.getFilesDir(), "scans");
    File[] children = scansBase.listFiles();
    if (children != null) {
      for (File child : children) {
        if (child == null || !child.isDirectory()) continue;
        String name = child.getName();
        if (!ids.contains(name) && !sessionReferencedIds.contains(name)) {
          int deleted = deleteDirectoryRecursively(child);
          if (deleted > 0) rep.deletedOrphanDirs++;
        }
      }
    }
    return rep;
  }

  // Returns number of files deleted (best-effort). Directories count as 1.
  private static int deleteDirectoryRecursively(File dir) {
    if (dir == null || !dir.exists()) return 0;
    int count = 0;
    File[] files = dir.listFiles();
    if (files != null) {
      for (File f : files) {
        if (f.isDirectory()) {
          count += deleteDirectoryRecursively(f);
        } else {
          if (f.delete()) count++;
        }
      }
    }
    if (dir.delete()) count++;
    return count;
  }
}
