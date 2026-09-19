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
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The CompletedScansRegistry class manages the persistence and retrieval of completed scan records.
 * It uses JSON serialization to store scan data in a file and provides synchronized methods for
 * accessing and manipulating the registry data.
 *
 * <p>This is a singleton class designed to ensure a single instance of the registry is created and
 * used throughout the application. The registry is backed by a file to ensure data persistence
 * across app sessions.
 */
public final class CompletedScansRegistry {
  private static final String TAG = "CompletedScansRegistry";

  private static volatile CompletedScansRegistry instance;

  private final File indexFile;
  private final Gson gson;

  /**
   * Returns the singleton instance of the CompletedScansRegistry. If the instance does not already
   * exist, it initializes and creates one using the provided application context.
   *
   * @param ctx the application context used to determine the file directory for the registry
   * @return the singleton instance of the CompletedScansRegistry
   */
  public static CompletedScansRegistry get(Context ctx) {
    CompletedScansRegistry local = instance;
    if (local != null) return local;
    synchronized (CompletedScansRegistry.class) {
      if (instance == null) {
        File base = new File(ctx.getFilesDir(), "registry");
        if (!base.exists()) {
          //noinspection ResultOfMethodCallIgnored
          base.mkdirs();
        }
        File idx = new File(base, "completed_scans.json");
        instance = new CompletedScansRegistry(idx);
      }
      return instance;
    }
  }

  /**
   * Constructs a new instance of the CompletedScansRegistry.
   *
   * @param indexFile the file used to store the registry data
   */
  public CompletedScansRegistry(File indexFile) {
    this.indexFile = indexFile;
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
  }

  /**
   * Retrieves a list of all completed scans, sorted in descending order by their creation
   * timestamp. This method ensures thread safety using synchronization.
   *
   * <p>The scans are loaded from the registry file and converted to their runtime representations.
   * If the registry contains null entries or entries with null IDs, they are ignored. The resulting
   * list is then sorted by the creation date in descending order before being returned.
   *
   * @return a list of CompletedScan objects sorted by creation timestamp in descending order
   */
  public synchronized List<CompletedScan> listAllOrderedByDateDesc() {
    RegistryFile rf = safeLoad();
    List<CompletedScan> out = new ArrayList<>();
    if (rf.items != null) {
      for (CompletedScanEntry e : rf.items) {
        if (e == null || e.id == null) continue;
        out.add(toRuntime(e));
      }
    }
    // Order by createdAt desc
    out.sort(Comparator.comparingLong(CompletedScan::createdAt).reversed());
    return out;
  }

  /**
   * Inserts a completed scan into the registry, ensuring no duplicate entries by ID. This operation
   * is thread-safe and updates the registry file atomically. If the provided scan or its ID is
   * null, the method does nothing. If the entry does not already exist in the registry, it is added
   * and persisted to storage.
   *
   * @param s the completed scan to be inserted. It must contain a non-null ID.
   * @throws IOException if an error occurs while writing to the registry file.
   */
  public synchronized void insert(CompletedScan s) throws IOException {
    if (s == null || s.id() == null) return;
    RegistryFile rf = safeLoad();
    if (rf.items == null) rf.items = new ArrayList<>();
    // no duplicates by id
    boolean exists = false;
    for (CompletedScanEntry e : rf.items) {
      if (e != null && s.id().equals(e.id)) {
        exists = true;
        break;
      }
    }
    if (!exists) rf.items.add(fromRuntime(s));
    writeAtomically(rf);
  }

  /**
   * Removes a completed scan entry from the registry based on its unique identifier. This operation
   * is thread-safe and updates the registry file atomically. If the provided ID is null, the method
   * does nothing. If the registry is empty or does not contain the specified entry, the operation
   * ensures the registry file is still rewritten deterministically.
   *
   * @param id the unique identifier of the completed scan to be removed; must not be null.
   * @throws IOException if an error occurs while writing to the registry file.
   */
  public synchronized void remove(String id) throws IOException {
    if (id == null) return;
    RegistryFile rf = safeLoad();
    if (rf.items == null || rf.items.isEmpty()) {
      // still rewrite an empty index deterministically
      rf.items = new ArrayList<>();
      writeAtomically(rf);
      return;
    }
    List<CompletedScanEntry> next = new ArrayList<>();
    for (CompletedScanEntry e : rf.items) {
      if (e == null) continue;
      if (!id.equals(e.id)) next.add(e);
    }
    rf.items = next;
    writeAtomically(rf);
  }

  /**
   * Converts a {@link CompletedScanEntry} into a {@link CompletedScan} instance.
   *
   * @param e the {@link CompletedScanEntry} object containing metadata and file path information
   *     about a completed scan
   * @return a {@link CompletedScan} object representing the runtime representation of the completed
   *     scan
   */
  private CompletedScan toRuntime(CompletedScanEntry e) {
    // Default legacy entries to schemaVersion=1 and orientationMode="baked"
    int sv = (e.schemaVersion <= 0) ? 1 : e.schemaVersion;
    String mode =
        (e.orientationMode == null || e.orientationMode.isEmpty()) ? "baked" : e.orientationMode;
    return new CompletedScan(
        e.id,
        e.filePath,
        e.rotationDeg,
        e.ocrTextPath,
        e.ocrFormat,
        e.thumbPath,
        e.createdAt,
        e.widthPx,
        e.heightPx,
        null, // no in-memory bitmap from persistence
        sv,
        mode,
        e.sourceType,
        e.pdfPageIndex,
        e.pageStatus);
  }

  /**
   * Converts a {@link CompletedScan} object into a {@link CompletedScanEntry} instance.
   *
   * @param s the {@link CompletedScan} object containing information about a completed scan,
   *     including its metadata, file paths, rotation angle, and dimensions.
   * @return a {@link CompletedScanEntry} representing the stored format of the completed scan,
   *     preserving all metadata and file path properties.
   */
  private CompletedScanEntry fromRuntime(CompletedScan s) {
    return new CompletedScanEntry(
        s.id(),
        s.filePath(),
        s.rotationDeg(),
        s.ocrTextPath(),
        s.ocrFormat(),
        s.thumbPath(),
        s.createdAt(),
        s.widthPx(),
        s.heightPx(),
        s.schemaVersion(),
        s.orientationMode(),
        s.sourceType(),
        s.pdfPageIndex(),
        s.pageStatus());
  }

  // ===== Persistence =====

  private RegistryFile safeLoad() {
    try {
      return load();
    } catch (Exception e) {
      Log.w(TAG, "safeLoad: treating as empty due to error: " + e.getMessage());
      RegistryFile rf = new RegistryFile();
      rf.version = 1;
      rf.items = new ArrayList<>();
      return rf;
    }
  }

  private RegistryFile load() throws IOException {
    if (!indexFile.exists()) {
      RegistryFile rf = new RegistryFile();
      rf.version = 1;
      rf.items = new ArrayList<>();
      return rf;
    }
    try (FileInputStream fis = new FileInputStream(indexFile)) {
      byte[] buf = readAllBytesCompat(fis);
      String json = new String(buf, StandardCharsets.UTF_8);
      RegistryFile rf = gson.fromJson(json, RegistryFile.class);
      if (rf == null || rf.items == null) {
        RegistryFile empty = new RegistryFile();
        empty.version = 1;
        empty.items = new ArrayList<>();
        return empty;
      }
      return rf;
    }
  }

  private byte[] readAllBytesCompat(FileInputStream fis) throws IOException {
    byte[] buffer = new byte[8192];
    int read;
    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
    while ((read = fis.read(buffer)) != -1) {
      baos.write(buffer, 0, read);
    }
    return baos.toByteArray();
  }

  private void writeAtomically(RegistryFile rf) throws IOException {
    File dir = indexFile.getParentFile();
    if (dir != null && !dir.exists()) {
      //noinspection ResultOfMethodCallIgnored
      dir.mkdirs();
    }
    String json = gson.toJson(rf);
    File tmp = new File(indexFile.getParentFile(), indexFile.getName() + ".tmp");
    // Write
    try (FileOutputStream fos = new FileOutputStream(tmp)) {
      fos.write(json.getBytes(StandardCharsets.UTF_8));
      fos.flush();
      try {
        fos.getFD().sync();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
    // Replace
    if (indexFile.exists() && !indexFile.delete()) {
      Log.w(TAG, "writeAtomically: failed to delete old index, attempting overwrite via rename");
    }
    boolean renamed = tmp.renameTo(indexFile);
    if (!renamed) {
      // Fallback: copy via channel
      try (FileChannel in = new FileInputStream(tmp).getChannel();
          FileChannel out = new FileOutputStream(indexFile).getChannel()) {
        long size = in.size();
        long pos = 0;
        while (pos < size) {
          pos += out.transferFrom(in, pos, size - pos);
        }
        out.force(true);
      }
      //noinspection ResultOfMethodCallIgnored
      tmp.delete();
    }
  }

  /**
   * Represents the structure of the registry file used for storing completed scan entries. This
   * class defines the version and the list of scan entries, enabling serialization and
   * deserialization of registry data for persistent storage.
   *
   * <p>The registry file is internally managed by the containing class and serialized to a file
   * using JSON format. The `version` field ensures backward compatibility for future updates, while
   * the `items` list stores {@link CompletedScanEntry} objects representing individual completed
   * scans.
   *
   * <p>Fields: - `version` (int): The version of the registry file format. Defaults to 1. - `items`
   * (List<CompletedScanEntry>): A list of scan entries. Defaults to an empty list.
   */
  // Internal JSON layout
  static class RegistryFile {
    int version = 1;
    List<CompletedScanEntry> items = Collections.emptyList();
  }
}
