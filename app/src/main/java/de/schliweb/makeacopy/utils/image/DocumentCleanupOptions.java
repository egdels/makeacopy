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

/** Options for the offline document cleanup. */
public class DocumentCleanupOptions {
  public DocumentCleanupMode mode = DocumentCleanupMode.ORIGINAL;
  public boolean preserveColor = true;
  public int targetLongEdgePx = 0;

  public DocumentCleanupOptions() {}

  public DocumentCleanupOptions(DocumentCleanupMode mode) {
    this.mode = mode == null ? DocumentCleanupMode.ORIGINAL : mode;
  }

  public DocumentCleanupOptions(
      DocumentCleanupMode mode, boolean preserveColor, int targetLongEdgePx) {
    this.mode = mode == null ? DocumentCleanupMode.ORIGINAL : mode;
    this.preserveColor = preserveColor;
    this.targetLongEdgePx = Math.max(0, targetLongEdgePx);
  }

  public static DocumentCleanupOptions original() {
    return new DocumentCleanupOptions(DocumentCleanupMode.ORIGINAL);
  }

  public static DocumentCleanupOptions enhanced(boolean preserveColor) {
    return new DocumentCleanupOptions(DocumentCleanupMode.ENHANCED, preserveColor, 0);
  }
}
