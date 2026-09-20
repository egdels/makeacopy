/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.LocaleList;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import de.schliweb.makeacopy.R;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;
import org.xmlpull.v1.XmlPullParser;

/**
 * In-app UI language selection, built on AppCompat's per-app locales. On Android 13+ the choice is
 * the same one the system shows under "App languages"; on older versions AppCompat persists it (see
 * {@code AppLocalesMetadataHolderService} in the manifest). The offered languages come from {@code
 * res/xml/locales_config.xml}.
 */
@UtilityClass
public class AppLanguage {

  private static final String TAG = "AppLanguage";
  private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

  /** Tag representing "follow the system language". */
  public static final String SYSTEM_DEFAULT = "";

  /**
   * Returns the BCP 47 tags of all languages the app is translated into, sorted by their display
   * name.
   */
  @NonNull
  public static List<String> supportedTags(@NonNull Context ctx) {
    List<String> tags = new ArrayList<>();
    try (XmlResourceParser parser = ctx.getResources().getXml(R.xml.locales_config)) {
      int event;
      while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && "locale".equals(parser.getName())) {
          String tag = parser.getAttributeValue(ANDROID_NS, "name");
          if (tag != null && !tag.isEmpty()) tags.add(tag);
        }
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to read locales_config", e);
    }
    Collator collator = Collator.getInstance();
    tags.sort((a, b) -> collator.compare(displayName(a), displayName(b)));
    return tags;
  }

  /**
   * Returns the name of the language in the language itself (e.g. "Deutsch", "Español"), so users
   * can find their language regardless of the current UI language.
   */
  @NonNull
  public static String displayName(@NonNull String tag) {
    Locale locale = Locale.forLanguageTag(tag);
    String name = locale.getDisplayName(locale);
    if (name.isEmpty()) return tag;
    int first = name.offsetByCodePoints(0, 1);
    return name.substring(0, first).toUpperCase(locale) + name.substring(first);
  }

  /**
   * Returns the currently selected tag out of {@code supported}, or {@link #SYSTEM_DEFAULT} if the
   * app follows the system language.
   */
  @NonNull
  public static String currentTag(@NonNull List<String> supported) {
    LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
    Locale current = locales.isEmpty() ? null : locales.get(0);
    if (current == null) return SYSTEM_DEFAULT;
    String currentTag = current.toLanguageTag();
    for (String tag : supported) {
      if (tag.equalsIgnoreCase(currentTag)) return tag;
    }
    // e.g. "pt-BR" chosen in the system settings while the app only lists "pt"
    for (String tag : supported) {
      if (Locale.forLanguageTag(tag).getLanguage().equals(current.getLanguage())) return tag;
    }
    return SYSTEM_DEFAULT;
  }

  /**
   * Switches the UI language. Running activities are recreated by AppCompat.
   *
   * @param tag a tag from {@link #supportedTags(Context)} or {@link #SYSTEM_DEFAULT}
   */
  public static void apply(@NonNull String tag) {
    AppCompatDelegate.setApplicationLocales(
        tag.isEmpty()
            ? LocaleListCompat.getEmptyLocaleList()
            : LocaleListCompat.forLanguageTags(tag));
  }

  /**
   * Returns a context whose resources honor the in-app language. Needed for non-activity contexts
   * (e.g. the application context) on Android 12 and older, where only activities are localized by
   * AppCompat. On Android 13+ the platform handles this and the context is returned unchanged.
   */
  @NonNull
  public static Context localize(@NonNull Context ctx) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return ctx;
    LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
    if (locales.isEmpty()) return ctx;
    Configuration config = new Configuration(ctx.getResources().getConfiguration());
    config.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()));
    return ctx.createConfigurationContext(config);
  }
}
