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

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.Nullable;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * Decides whether a Uri handed to the app from outside (share, "Open with", a picker result) may be
 * read. Only content Uris of other apps are accepted. A file Uri or a content Uri of one of the
 * app's own providers would make the app read its own private files on behalf of the sender
 * (security audit F-01, "confused deputy"); legitimate senders never need either, because the app
 * has no storage permission and can read a file Uri only inside its own data directory.
 */
@UtilityClass
public class IncomingUriPolicy {

  private static final String TAG = "IncomingUriPolicy";

  /** Whether {@code uri} from outside the app may be opened; logs the reason when it may not. */
  public static boolean isAllowed(Context ctx, @Nullable Uri uri) {
    if (ctx == null || uri == null) return false;
    String authority = providerAuthority(uri.getAuthority());
    String providerPackage = null;
    if (authority != null) {
      try {
        ProviderInfo provider = ctx.getPackageManager().resolveContentProvider(authority, 0);
        if (provider != null) providerPackage = provider.packageName;
      } catch (RuntimeException ignore) {
        // Not resolvable here; the authority check below still applies
      }
    }
    boolean allowed = isAllowed(uri.getScheme(), authority, providerPackage, ctx.getPackageName());
    if (!allowed) Log.w(TAG, "Rejected incoming Uri with scheme " + uri.getScheme());
    return allowed;
  }

  /**
   * The rule itself: scheme {@code content}, an authority, and neither the authority nor the
   * resolved provider belongs to {@code ownPackage}. App providers are named after the application
   * id ({@code <applicationId>.fileprovider}, {@code <applicationId>.androidx-startup}), so the
   * authority check holds even when the provider cannot be resolved.
   */
  static boolean isAllowed(
      @Nullable String scheme,
      @Nullable String authority,
      @Nullable String providerPackage,
      String ownPackage) {
    if (scheme == null || !ContentResolver.SCHEME_CONTENT.equals(scheme.toLowerCase(Locale.ROOT)))
      return false;
    String name = providerAuthority(authority);
    if (name == null || name.isEmpty()) return false;
    String lower = name.toLowerCase(Locale.ROOT);
    String own = ownPackage.toLowerCase(Locale.ROOT);
    if (lower.equals(own) || lower.startsWith(own + ".")) return false;
    return providerPackage == null || !providerPackage.equals(ownPackage);
  }

  /**
   * The provider name in an authority: without a user prefix ({@code 0@...}, which Android accepts
   * for content Uris and which would otherwise hide the app's own provider) and without a port.
   */
  @Nullable
  static String providerAuthority(@Nullable String authority) {
    if (authority == null) return null;
    String name = authority.substring(authority.lastIndexOf('@') + 1);
    int port = name.indexOf(':');
    return port >= 0 ? name.substring(0, port) : name;
  }
}
