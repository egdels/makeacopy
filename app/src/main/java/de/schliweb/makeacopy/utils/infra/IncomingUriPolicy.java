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
    boolean allowed =
        isAllowed(uri.getScheme(), uri.getAuthority(), providerPackage, ctx.getPackageName());
    if (!allowed) Log.w(TAG, "Rejected incoming Uri with scheme " + uri.getScheme());
    return allowed;
  }

  /**
   * The rule itself: scheme {@code content}, an authority, and a provider that is not ours. When
   * the provider resolved, its package decides, so another edition of the app (the Light edition
   * has the id {@code <applicationId>.light}) may share with this one. When it did not resolve, the
   * name decides: the app's own providers are named {@code <applicationId>.<provider>} with a
   * single segment after the id ({@code .fileprovider}, {@code .androidx-startup}), while a sibling
   * package's provider has at least two ({@code .light.fileprovider}).
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
    if (providerPackage != null) return !providerPackage.equals(ownPackage);
    String lower = name.toLowerCase(Locale.ROOT);
    String own = ownPackage.toLowerCase(Locale.ROOT);
    if (lower.equals(own)) return false;
    if (!lower.startsWith(own + ".")) return true;
    return lower.substring(own.length() + 1).contains(".");
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
