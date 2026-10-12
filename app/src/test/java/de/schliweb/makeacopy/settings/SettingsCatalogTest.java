/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Keeps {@link SettingsCatalog} and the code in step: every preference key the sources read or
 * write must be listed, and everything listed must still exist in the sources.
 */
public class SettingsCatalogTest {

  /** {@code KEY_X = "…"}, {@code PREF_X = "…"}, {@code BUNDLE_X = "…"}, {@code STATE_X = "…"}. */
  private static final Pattern CONSTANT =
      Pattern.compile("\\b(?:KEY|PREF|PREFS|BUNDLE|STATE)(?:_[A-Z0-9_]*)?\\s*=\\s*\"([^\"]+)\"");

  /** {@code prefs.getBoolean("…"}, {@code editor.putString("…"}, and so on. */
  private static final Pattern LITERAL =
      Pattern.compile("\\.(?:put|get)(?:Boolean|String|Int|Long|Float)\\(\\s*\"([^\"]+)\"");

  /** Constants and literals the patterns pick up that are not preference keys. */
  private static final Set<String> NOT_A_PREFERENCE =
      Set.of(
          // Fragment argument keys
          "collectionId", "collectionName", "scanId",
          // OcrSearchIndexer states
          "empty", "error", "indexed",
          // Preference files that hold runtime state only
          "scan_library", "session_ids");

  @Test
  public void keysAreUniquePerFile() {
    Set<String> seen = new HashSet<>();
    for (SettingsCatalog.Setting s : SettingsCatalog.ALL) {
      assertTrue("duplicate " + s.prefsFile() + "/" + s.key(), seen.add(s.prefsFile() + "/" + s.key()));
    }
  }

  @Test
  public void transientKeysAreNotSettings() {
    for (SettingsCatalog.Setting s : SettingsCatalog.ALL) {
      assertTrue(
          s.key() + " is both a setting and transient",
          !SettingsCatalog.TRANSIENT_KEYS.contains(s.key()));
    }
  }

  @Test
  public void defaultsMatchTheirType() {
    for (SettingsCatalog.Setting s : SettingsCatalog.ALL) {
      Object d = s.defaultValue();
      if (d == null) continue;
      Class<?> expected =
          switch (s.type()) {
            case BOOLEAN -> Boolean.class;
            case STRING -> String.class;
            case INT -> Integer.class;
            case FLOAT -> Float.class;
          };
      assertEquals(s.key() + " default", expected, d.getClass());
    }
  }

  @Test
  public void everyPreferenceKeyInTheSourcesIsCatalogued() throws IOException {
    Map<String, String> found = preferenceKeysInSources();
    Set<String> known = new HashSet<>(SettingsCatalog.TRANSIENT_KEYS);
    for (SettingsCatalog.Setting s : SettingsCatalog.ALL) known.add(s.key());
    known.add(SettingsCatalog.PREFS_MAIN);
    known.add(SettingsCatalog.PREFS_CROP);
    known.add(SettingsCatalog.PREFS_CLEANUP);
    known.add(SettingsCatalog.PREFS_LANGUAGE);
    known.addAll(NOT_A_PREFERENCE);

    Set<String> unknown = new TreeSet<>();
    for (Map.Entry<String, String> e : found.entrySet()) {
      if (!known.contains(e.getKey())) unknown.add(e.getKey() + " (" + e.getValue() + ")");
    }
    assertTrue(
        "Preference keys missing from SettingsCatalog.ALL or TRANSIENT_KEYS: " + unknown,
        unknown.isEmpty());
  }

  @Test
  public void everyCataloguedKeyStillExistsInTheSources() throws IOException {
    Set<String> found = preferenceKeysInSources().keySet();
    Set<String> stale = new TreeSet<>();
    for (SettingsCatalog.Setting s : SettingsCatalog.ALL) {
      if (!found.contains(s.key())) stale.add(s.key());
    }
    for (String key : SettingsCatalog.TRANSIENT_KEYS) {
      if (!found.contains(key)) stale.add(key);
    }
    assertTrue("Catalogued keys no longer used anywhere: " + stale, stale.isEmpty());
  }

  /** Key → first file it was seen in, from all production source sets. */
  private static Map<String, String> preferenceKeysInSources() throws IOException {
    Path src = sourceRoot();
    Map<String, String> found = new TreeMap<>();
    try (Stream<Path> dirs = Files.list(src)) {
      for (Path sourceSet : (Iterable<Path>) dirs::iterator) {
        String name = sourceSet.getFileName().toString();
        if (name.startsWith("test") || name.startsWith("androidTest")) continue;
        Path java = sourceSet.resolve("java");
        if (!Files.isDirectory(java)) continue;
        try (Stream<Path> files = Files.walk(java)) {
          for (Path f : (Iterable<Path>) files::iterator) {
            if (!f.toString().endsWith(".java")) continue;
            String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            collect(CONSTANT, text, f, found);
            collect(LITERAL, text, f, found);
          }
        }
      }
    }
    assertTrue("no preference keys found under " + src, found.size() > 20);
    return found;
  }

  private static void collect(Pattern p, String text, Path file, Map<String, String> into) {
    Matcher m = p.matcher(text);
    while (m.find()) into.putIfAbsent(m.group(1), file.getFileName().toString());
  }

  /** Gradle runs unit tests with the module directory as working directory. */
  private static Path sourceRoot() {
    for (String candidate : new String[] {"src", "app/src"}) {
      Path p = Paths.get(candidate);
      if (Files.isDirectory(p.resolve("main/java"))) return p.toAbsolutePath();
    }
    throw new AssertionError("source root not found from " + Paths.get("").toAbsolutePath());
  }
}
