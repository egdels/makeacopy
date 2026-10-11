/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.infra;

import android.net.Uri;
import androidx.annotation.Nullable;
import java.io.File;
import lombok.experimental.UtilityClass;

/**
 * Renders Uris and files for log lines that stay in release builds and leave the device through
 * "Share logs" (security audit F-06). A document Uri often carries the user's folder and file names
 * (".../Download/tax-return.pdf"), so only its scheme and provider are printed; an app file is
 * printed by its generated name only, without the data directory.
 */
@UtilityClass
public class LogSafe {

  /** {@code content://com.android.externalstorage.documents/…}: which provider, not which file. */
  public static String uri(@Nullable Uri uri) {
    if (uri == null) return "null";
    return describe(uri.getScheme(), uri.getAuthority(), uri.getPath() != null);
  }

  /** The file's own name, e.g. {@code page.jpg}, without the folders above it. */
  public static String file(@Nullable File file) {
    return file == null ? "null" : file.getName();
  }

  /** Like {@link #file(File)} for a path string. */
  public static String path(@Nullable String path) {
    return path == null ? "null" : new File(path).getName();
  }

  static String describe(@Nullable String scheme, @Nullable String authority, boolean hasPath) {
    StringBuilder sb = new StringBuilder(scheme != null ? scheme : "?").append("://");
    if (authority != null) sb.append(authority);
    if (hasPath) sb.append("/…");
    return sb.toString();
  }
}
