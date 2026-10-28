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
import lombok.experimental.UtilityClass;

/** Applies the chosen document cleanup (see {@link DocumentCleanupMode}) to a corrected page. */
@UtilityClass
public final class DocumentCleanupProcessor {

  /** The cleaned page, or {@code input} itself for Original. {@code context} may be null. */
  public static Bitmap apply(Context context, Bitmap input, DocumentCleanupOptions options) {
    if (input == null) return null;
    DocumentCleanupOptions safeOptions =
        options == null ? DocumentCleanupOptions.original() : options;
    if (safeOptions.mode == null || safeOptions.mode == DocumentCleanupMode.ORIGINAL) return input;
    Bitmap working = maybeDownscale(input, safeOptions.targetLongEdgePx);
    return ImageOps.enhance(working, safeOptions.preserveColor);
  }

  private static Bitmap maybeDownscale(Bitmap input, int targetLongEdgePx) {
    if (targetLongEdgePx <= 0 || input == null) return input;
    int longEdge = Math.max(input.getWidth(), input.getHeight());
    if (longEdge <= targetLongEdgePx) return input;
    double scale = targetLongEdgePx / (double) longEdge;
    int w = Math.max(1, (int) Math.round(input.getWidth() * scale));
    int h = Math.max(1, (int) Math.round(input.getHeight() * scale));
    return Bitmap.createScaledBitmap(input, w, h, true);
  }
}
