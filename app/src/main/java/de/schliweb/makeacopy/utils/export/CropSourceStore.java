/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.export;

import android.content.Context;
import android.graphics.PointF;
import android.net.Uri;
import android.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.schliweb.makeacopy.utils.image.DewarpState;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.experimental.UtilityClass;

/**
 * Keeps the crop source of a page next to its persisted page image: a byte copy of the original
 * (un-cropped) capture plus the accepted trapezoid corners, user rotation and, for a curved
 * selection (issue #91), its dewarp settings.
 *
 * <p>The persisted {@code page.jpg} is already perspective-corrected, so re-editing it can only cut
 * the page down further. With the crop source, ANY page of a multi-page document can be re-cropped
 * from its original image — not only the most recent capture that CameraViewModel still tracks.
 *
 * <p>Files live in {@code files/scans/<id>/} and therefore follow the existing registry cleanup. On
 * top of that, crop sources expire on their own, see {@link #purgeExpired}.
 */
@UtilityClass
public final class CropSourceStore {
  private static final String TAG = "CropSourceStore";

  static final String ORIGINAL_FILE = "original.img";
  static final String CROP_FILE = "crop.json";
  private static final String ORIGINAL_TMP_FILE = ORIGINAL_FILE + ".tmp";

  /**
   * How long a crop source is kept after it was last written. Originals are several times larger
   * than the page image and only serve re-editing, so they must not pile up forever.
   */
  public static final int MAX_AGE_DAYS = 30;

  /**
   * An interrupted copy leaves a temp file behind; after this time no copy can still be running.
   */
  private static final long STALE_TMP_MILLIS = TimeUnit.HOURS.toMillis(1);

  /**
   * The original image of a page together with the crop that produced its page image.
   *
   * @param dewarp the curved-selection settings of the crop (issue #91), or {@code null} for a
   *     straight trapezoid
   */
  public record CropSource(
      String originalPath, PointF[] corners, int userRotationDeg, DewarpState dewarp) {}

  /**
   * Stores the crop source for a page. The original is copied byte for byte (no re-encoding), so
   * decoding the copy yields the same bitmap — and thus the same coordinate space for the corners —
   * as decoding the capture itself.
   *
   * @param srcPath path of the original capture (nullable)
   * @param srcUri URI of the original image, used when there is no readable path (nullable)
   * @param corners accepted corners in coordinates of the rotated full-res original
   * @param userRotationDeg user rotation that was baked into the crop source
   * @param dewarp curved-selection settings of the crop, or {@code null} for a straight trapezoid
   * @return whether both the original and the crop parameters were stored
   */
  public static boolean save(
      Context ctx,
      String pageId,
      String srcPath,
      Uri srcUri,
      PointF[] corners,
      int userRotationDeg,
      DewarpState dewarp) {
    if (ctx == null || pageId == null) return false;
    String json = toJson(toArray(corners), userRotationDeg, dewarp);
    if (json == null) return false;
    File dir = scanDir(ctx, pageId);
    if (!dir.exists() && !dir.mkdirs()) return false;
    try {
      if (!copyOriginal(ctx, srcPath, srcUri, new File(dir, ORIGINAL_FILE))) return false;
      writeUtf8(new File(dir, CROP_FILE), json);
      return true;
    } catch (IOException | SecurityException e) {
      Log.w(TAG, "save: failed for page " + pageId, e);
      return false;
    }
  }

  /**
   * Replaces the crop parameters of a page whose original is already stored (page re-edit). Without
   * a stored original the parameters would be meaningless, so nothing is written then.
   */
  public static boolean updateCrop(
      Context ctx, String pageId, PointF[] corners, int userRotationDeg, DewarpState dewarp) {
    if (ctx == null || pageId == null) return false;
    File dir = scanDir(ctx, pageId);
    if (!isUsableFile(new File(dir, ORIGINAL_FILE))) return false;
    String json = toJson(toArray(corners), userRotationDeg, dewarp);
    if (json == null) return false;
    try {
      writeUtf8(new File(dir, CROP_FILE), json);
      return true;
    } catch (IOException e) {
      Log.w(TAG, "updateCrop: failed for page " + pageId, e);
      return false;
    }
  }

  /**
   * Drops the crop source of a page. Needed once a page is edited on its already cropped page image
   * instead: from then on the original and the stored corners no longer reproduce the page.
   */
  public static void delete(Context ctx, String pageId) {
    if (ctx == null || pageId == null) return;
    File dir = scanDir(ctx, pageId);
    for (String name : new String[] {ORIGINAL_FILE, CROP_FILE}) {
      File f = new File(dir, name);
      if (f.exists() && !f.delete()) Log.w(TAG, "delete: failed to delete " + f);
    }
  }

  /**
   * Removes crop sources that are older than {@link #MAX_AGE_DAYS}, independent of the completed
   * scans cleanup policy. Only the original and its crop parameters go; the page image, OCR result
   * and registry entry stay, and editing such a page falls back to its cropped page image.
   *
   * @param activePageIds pages of the active document session, whose crop sources are always kept
   * @return number of pages whose crop source was removed
   */
  public static int purgeExpired(Context ctx, Set<String> activePageIds, long nowMillis) {
    if (ctx == null) return 0;
    return purgeExpired(new File(ctx.getFilesDir(), "scans"), activePageIds, nowMillis);
  }

  static int purgeExpired(File scansBase, Set<String> activePageIds, long nowMillis) {
    File[] dirs = scansBase != null ? scansBase.listFiles() : null;
    if (dirs == null) return 0;
    int purged = 0;
    for (File dir : dirs) {
      if (dir == null || !dir.isDirectory()) continue;
      deleteStaleTempCopy(dir, nowMillis);
      boolean inActiveSession = activePageIds != null && activePageIds.contains(dir.getName());
      if (purgePageIfExpired(dir, inActiveSession, nowMillis)) purged++;
    }
    return purged;
  }

  private static void deleteStaleTempCopy(File pageDir, long nowMillis) {
    File tmp = new File(pageDir, ORIGINAL_TMP_FILE);
    if (tmp.isFile() && nowMillis - tmp.lastModified() > STALE_TMP_MILLIS && !tmp.delete()) {
      Log.w(TAG, "purgeExpired: failed to delete " + tmp);
    }
  }

  /** Returns whether the page had an expired crop source that is now completely gone. */
  private static boolean purgePageIfExpired(File pageDir, boolean inActiveSession, long nowMillis) {
    File original = new File(pageDir, ORIGINAL_FILE);
    File crop = new File(pageDir, CROP_FILE);
    if (!original.exists() && !crop.exists()) return false;
    // A re-edit rewrites crop.json: a page that is still being worked on keeps its original
    long lastWritten = Math.max(original.lastModified(), crop.lastModified());
    if (!isExpired(lastWritten, inActiveSession, nowMillis)) return false;
    boolean deleted = true;
    for (File f : new File[] {original, crop}) {
      if (f.exists() && !f.delete()) {
        deleted = false;
        Log.w(TAG, "purgeExpired: failed to delete " + f);
      }
    }
    return deleted;
  }

  /** Whether a crop source last written at {@code lastWrittenMillis} is due for removal. */
  static boolean isExpired(long lastWrittenMillis, boolean inActiveSession, long nowMillis) {
    if (inActiveSession) return false;
    return lastWrittenMillis < nowMillis - TimeUnit.DAYS.toMillis(MAX_AGE_DAYS);
  }

  /** Returns the stored crop source of a page, or {@code null} when there is none (or it broke). */
  public static CropSource load(Context ctx, String pageId) {
    if (ctx == null || pageId == null) return null;
    File dir = scanDir(ctx, pageId);
    File original = new File(dir, ORIGINAL_FILE);
    File crop = new File(dir, CROP_FILE);
    if (!isUsableFile(original) || !isUsableFile(crop)) return null;
    try {
      String json = new String(Files.readAllBytes(crop.toPath()), StandardCharsets.UTF_8);
      float[] flat = cornersFromJson(json);
      if (flat == null) return null;
      PointF[] corners = new PointF[4];
      for (int i = 0; i < 4; i++) corners[i] = new PointF(flat[2 * i], flat[2 * i + 1]);
      return new CropSource(
          original.getAbsolutePath(), corners, rotationFromJson(json), dewarpFromJson(json));
    } catch (IOException e) {
      Log.w(TAG, "load: failed for page " + pageId, e);
      return null;
    }
  }

  // ---- JSON (pure, JVM-testable) ----

  /**
   * Serializes the crop parameters.
   *
   * @param corners eight floats: x0, y0, … x3, y3
   * @param dewarp curved-selection settings, or {@code null} for a straight trapezoid; a state with
   *     non-finite values is dropped (the crop is stored as straight) rather than rejected
   * @return the JSON document, or {@code null} for anything but four finite corners
   */
  static String toJson(float[] corners, int userRotationDeg, DewarpState dewarp) {
    if (corners == null || corners.length != 8) return null;
    JsonArray arr = new JsonArray();
    for (int i = 0; i < 4; i++) {
      float x = corners[2 * i];
      float y = corners[2 * i + 1];
      if (Float.isNaN(x) || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y)) {
        return null;
      }
      JsonArray pt = new JsonArray();
      pt.add(x);
      pt.add(y);
      arr.add(pt);
    }
    JsonObject o = new JsonObject();
    o.addProperty("version", 2);
    o.addProperty("userRotationDeg", normalizeDeg(userRotationDeg));
    o.add("corners", arr);
    if (dewarp != null && dewarp.isFinite()) o.add("dewarp", dewarpToJson(dewarp));
    return o.toString();
  }

  private static JsonObject dewarpToJson(DewarpState d) {
    JsonObject o = new JsonObject();
    o.addProperty("topOffsetFrac", d.topOffsetFrac());
    o.addProperty("bottomOffsetFrac", d.bottomOffsetFrac());
    o.addProperty("topTangentFrac", d.topTangentFrac());
    o.addProperty("bottomTangentFrac", d.bottomTangentFrac());
    o.addProperty("depth", d.depth());
    if (d.topProfile() != null) o.add("topProfile", profileToJson(d.topProfile()));
    if (d.bottomProfile() != null) o.add("bottomProfile", profileToJson(d.bottomProfile()));
    return o;
  }

  private static JsonArray profileToJson(double[] profile) {
    JsonArray arr = new JsonArray();
    for (double v : profile) arr.add(v);
    return arr;
  }

  /**
   * Returns the curved-selection settings, or {@code null} when the crop is a straight trapezoid
   * (no {@code dewarp} object, e.g. a version 1 document) or the object is unusable. A broken
   * dewarp object degrades to a straight crop rather than invalidating the corners.
   */
  static DewarpState dewarpFromJson(String json) {
    try {
      JsonElement el = JsonParser.parseString(json).getAsJsonObject().get("dewarp");
      if (el == null || !el.isJsonObject()) return null;
      JsonObject o = el.getAsJsonObject();
      DewarpState state =
          new DewarpState(
              o.get("topOffsetFrac").getAsDouble(),
              o.get("bottomOffsetFrac").getAsDouble(),
              optionalDouble(o, "topTangentFrac"),
              optionalDouble(o, "bottomTangentFrac"),
              optionalDouble(o, "depth"),
              profileFromJson(o.get("topProfile")),
              profileFromJson(o.get("bottomProfile")));
      return state.isFinite() ? state : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static double optionalDouble(JsonObject o, String key) {
    JsonElement el = o.get(key);
    return el == null || el.isJsonNull() ? 0.0 : el.getAsDouble();
  }

  private static double[] profileFromJson(JsonElement el) {
    if (el == null || !el.isJsonArray()) return null;
    JsonArray arr = el.getAsJsonArray();
    if (arr.isEmpty()) return null;
    double[] out = new double[arr.size()];
    for (int i = 0; i < out.length; i++) out[i] = arr.get(i).getAsDouble();
    return out;
  }

  /** Returns the eight corner floats, or {@code null} when the document is not a valid crop. */
  static float[] cornersFromJson(String json) {
    try {
      JsonArray arr = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("corners");
      if (arr == null || arr.size() != 4) return null;
      float[] out = new float[8];
      for (int i = 0; i < 4; i++) {
        JsonArray pt = arr.get(i).getAsJsonArray();
        if (pt.size() != 2) return null;
        out[2 * i] = pt.get(0).getAsFloat();
        out[2 * i + 1] = pt.get(1).getAsFloat();
      }
      return out;
    } catch (RuntimeException e) {
      return null;
    }
  }

  static int rotationFromJson(String json) {
    try {
      JsonElement deg = JsonParser.parseString(json).getAsJsonObject().get("userRotationDeg");
      return deg == null ? 0 : normalizeDeg(deg.getAsInt());
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private static int normalizeDeg(int deg) {
    return ((deg % 360) + 360) % 360;
  }

  // ---- files ----

  private static File scanDir(Context ctx, String pageId) {
    return new File(ctx.getFilesDir(), "scans/" + pageId);
  }

  private static boolean isUsableFile(File f) {
    return f.isFile() && f.length() > 0;
  }

  private static float[] toArray(PointF[] corners) {
    if (corners == null || corners.length != 4) return null;
    float[] out = new float[8];
    for (int i = 0; i < 4; i++) {
      if (corners[i] == null) return null;
      out[2 * i] = corners[i].x;
      out[2 * i + 1] = corners[i].y;
    }
    return out;
  }

  private static boolean copyOriginal(Context ctx, String srcPath, Uri srcUri, File target)
      throws IOException {
    // Same precedence as ImageLoader.decode: readable path first, URI as fallback
    File src = (srcPath != null && !srcPath.isEmpty()) ? new File(srcPath) : null;
    if (src != null && isUsableFile(src)) {
      if (src.getCanonicalFile().equals(target.getCanonicalFile())) return true;
      try (InputStream in = new FileInputStream(src)) {
        copy(in, target);
      }
      return isUsableFile(target);
    }
    if (srcUri == null) return false;
    try (InputStream in = ctx.getContentResolver().openInputStream(srcUri)) {
      if (in == null) return false;
      copy(in, target);
    }
    return isUsableFile(target);
  }

  private static void copy(InputStream in, File target) throws IOException {
    // Write to a temp file first so an interrupted copy never leaves a truncated original behind
    File tmp = new File(target.getParentFile(), ORIGINAL_TMP_FILE);
    try (OutputStream out = new FileOutputStream(tmp)) {
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
      out.flush();
    }
    if (!tmp.renameTo(target)) {
      //noinspection ResultOfMethodCallIgnored
      tmp.delete();
      throw new IOException("rename failed: " + tmp);
    }
  }

  private static void writeUtf8(File target, String content) throws IOException {
    try (OutputStream out = new FileOutputStream(target)) {
      out.write(content.getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
  }
}
