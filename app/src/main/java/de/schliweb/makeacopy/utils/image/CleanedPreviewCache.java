/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.image;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import androidx.annotation.Nullable;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import de.schliweb.makeacopy.utils.infra.LogSafe;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The export preview of a page with the Enhanced cleanup, in colour and at screen size, kept as
 * {@value #FILE} next to the page's {@code page.jpg}. The cleanup takes most of a second per page
 * in Java; with this file a page shows up cleaned as soon as it is picked in the filmstrip.
 *
 * <p>The file is made in the background when a page is written while Enhanced is selected, and
 * otherwise the first time the export preview cleans the page. It is derived data only:
 *
 * <ul>
 *   <li>{@link #invalidate} deletes it whenever {@code page.jpg} is written anew (crop, re-edit,
 *       import), and a preview made from an older {@code page.jpg} is never stored or used.
 *   <li>Deleting a page removes its folder and the file with it.
 *   <li>{@link #purgeInactive} removes the files of pages outside the active document, and leftover
 *       temporary files, with the periodic cache cleanup.
 * </ul>
 */
public final class CleanedPreviewCache {
  private static final String TAG = "CleanedPreviewCache";

  /** The cleaned preview inside the page folder. */
  public static final String FILE = "preview.enhanced.jpg";

  private static final String TMP_FILE = FILE + ".tmp";
  private static final int JPEG_QUALITY = 90;

  private static final ExecutorService EXEC =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "CleanedPreviewCache");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
          });

  /**
   * A page's {@code page.jpg} as it was when it was read: a cached preview made from it is only
   * valid while the file has not been written again.
   */
  record PageRef(File file, long stamp) {
    boolean current() {
      return file.isFile() && file.lastModified() == stamp;
    }
  }

  /** The page each decoded export preview bitmap belongs to. */
  private static final Map<Bitmap, PageRef> pageOfPreview = new WeakHashMap<>();

  private CleanedPreviewCache() {}

  /** Remembers that {@code preview} was decoded from {@code page}'s {@code page.jpg}. */
  public static void associate(Bitmap preview, CompletedScan page) {
    PageRef ref = pageRef(page);
    if (preview == null || ref == null) return;
    synchronized (pageOfPreview) {
      pageOfPreview.put(preview, ref);
    }
  }

  /** The {@code page.jpg} that {@code preview} was decoded from, or null if unknown. */
  @Nullable
  static PageRef pageOf(Bitmap preview) {
    synchronized (pageOfPreview) {
      return pageOfPreview.get(preview);
    }
  }

  /**
   * The cached cleaned preview of {@code page}, scaled to {@code width x height}, or null if there
   * is none or the page has changed since.
   */
  @Nullable
  static Bitmap load(PageRef page, int width, int height) {
    if (page == null || !page.current()) return null;
    File cache = cacheFor(page.file());
    if (cache == null || !cache.isFile()) return null;
    if (cache.lastModified() < page.stamp()) {
      delete(cache);
      return null;
    }
    Bitmap decoded = ImageDecodeUtils.decodeFull(cache.getAbsolutePath());
    if (decoded == null) {
      delete(cache);
      return null;
    }
    if (decoded.getWidth() == width && decoded.getHeight() == height) return decoded;
    Bitmap scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
    if (scaled != decoded) decoded.recycle();
    return scaled;
  }

  /** Stores {@code cleaned} as the cached preview of {@code page}, in the background. */
  static void storeAsync(PageRef page, Bitmap cleaned) {
    if (page == null || cleaned == null) return;
    EXEC.execute(() -> write(page, cleaned));
  }

  /**
   * Makes the cached preview of a page that was just written, in the background, if the Enhanced
   * cleanup is selected (otherwise the export preview makes it when it is first needed).
   */
  public static void prepareAsync(Context context, CompletedScan page) {
    PageRef ref = pageRef(page);
    if (context == null || ref == null) return;
    DocumentCleanupMode mode =
        DocumentCleanupMode.fromPreference(
            context
                .getSharedPreferences("export_options", Context.MODE_PRIVATE)
                .getString("document_cleanup_mode", null));
    if (mode != DocumentCleanupMode.ENHANCED) return;
    android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
    int reqW = Math.min(dm.widthPixels, dm.heightPixels);
    int reqH = Math.max(dm.widthPixels, dm.heightPixels);
    EXEC.execute(
        () -> {
          if (!ref.current()) return;
          File cache = cacheFor(ref.file());
          if (cache.isFile() && cache.lastModified() >= ref.stamp()) return;
          Bitmap source = ImageDecodeUtils.decodeSampled(ref.file().getAbsolutePath(), reqW, reqH);
          if (source == null) return;
          Bitmap safe = BitmapUtils.ensureDisplaySafe(source);
          Bitmap cleaned = ImageOps.enhance(safe, true);
          write(ref, cleaned);
          if (cleaned != safe) cleaned.recycle();
          if (safe != source) safe.recycle();
          source.recycle();
        });
  }

  /** Deletes the cached preview of the page in {@code pageDir}, before its page.jpg changes. */
  public static void invalidate(File pageDir) {
    if (pageDir == null) return;
    delete(new File(pageDir, FILE));
    delete(new File(pageDir, TMP_FILE));
  }

  /**
   * Deletes the cached previews of all pages outside {@code activePageIds}, and leftover temporary
   * files everywhere.
   *
   * @return number of files deleted
   */
  public static int purgeInactive(File scansBase, Set<String> activePageIds) {
    File[] dirs = scansBase != null ? scansBase.listFiles() : null;
    if (dirs == null) return 0;
    int deleted = 0;
    for (File dir : dirs) {
      if (dir == null || !dir.isDirectory()) continue;
      File tmp = new File(dir, TMP_FILE);
      if (tmp.isFile() && tmp.delete()) deleted++;
      if (activePageIds != null && activePageIds.contains(dir.getName())) continue;
      File cache = new File(dir, FILE);
      if (cache.isFile() && cache.delete()) deleted++;
    }
    return deleted;
  }

  @Nullable
  private static PageRef pageRef(CompletedScan page) {
    if (page == null || page.filePath() == null) return null;
    File f = new File(page.filePath());
    return f.isFile() ? new PageRef(f, f.lastModified()) : null;
  }

  @Nullable
  private static File cacheFor(File pageFile) {
    File dir = pageFile != null ? pageFile.getParentFile() : null;
    return dir != null ? new File(dir, FILE) : null;
  }

  /** Writes through a temporary file, so that a half-written file is never taken for the cache. */
  private static void write(PageRef page, Bitmap cleaned) {
    File dir = page.file().getParentFile();
    if (dir == null || !page.current() || cleaned.isRecycled()) return;
    File tmp = new File(dir, TMP_FILE);
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      if (!cleaned.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
        throw new java.io.IOException("compress failed");
      }
      out.flush();
    } catch (Exception e) {
      Log.w(TAG, "write failed for " + LogSafe.file(page.file()) + ": " + e);
      delete(tmp);
      return;
    }
    // The page may have been rewritten or deleted meanwhile: then the result is stale.
    if (!page.current()) {
      delete(tmp);
      return;
    }
    if (!tmp.renameTo(new File(dir, FILE))) delete(tmp);
  }

  private static void delete(File f) {
    if (f != null && f.exists() && !f.delete()) {
      Log.w(TAG, "failed to delete " + LogSafe.file(f));
    }
  }
}
