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

/** Optional document cleanup presets applied after perspective correction. */
public enum DocumentCleanupMode {
  /** Keep the corrected page unchanged. */
  ORIGINAL,
  /** Background flattening and local contrast for scanner-like output. */
  ENHANCED;

  /**
   * The mode for a saved preference value. The former presets Natural and Clean Text map to
   * Enhanced, anything unknown or missing to Original.
   */
  public static DocumentCleanupMode fromPreference(String saved) {
    if (saved == null) return ORIGINAL;
    return switch (saved) {
      case "ENHANCED", "NATURAL", "CLEAN_TEXT" -> ENHANCED;
      default -> ORIGINAL;
    };
  }
}
