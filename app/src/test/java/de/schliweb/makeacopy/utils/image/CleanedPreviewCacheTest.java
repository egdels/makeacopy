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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Cleaning up the cached export previews next to the pages. */
public class CleanedPreviewCacheTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private File page(File scans, String id, boolean cached, boolean leftoverTmp) throws Exception {
    File dir = new File(scans, id);
    assertTrue(dir.mkdirs());
    Files.write(new File(dir, "page.jpg").toPath(), new byte[] {1});
    if (cached) Files.write(new File(dir, CleanedPreviewCache.FILE).toPath(), new byte[] {2});
    if (leftoverTmp) {
      Files.write(new File(dir, CleanedPreviewCache.FILE + ".tmp").toPath(), new byte[] {3});
    }
    return dir;
  }

  @Test
  public void invalidateDeletesTheCacheAndItsTemporaryFileOnly() throws Exception {
    File dir = page(tmp.getRoot(), "a", true, true);
    CleanedPreviewCache.invalidate(dir);
    assertFalse(new File(dir, CleanedPreviewCache.FILE).exists());
    assertFalse(new File(dir, CleanedPreviewCache.FILE + ".tmp").exists());
    assertTrue(new File(dir, "page.jpg").exists());
  }

  @Test
  public void purgeKeepsTheActiveDocumentsCachesAndRemovesLeftoversEverywhere() throws Exception {
    File scans = tmp.getRoot();
    File active = page(scans, "active", true, true);
    File inactive = page(scans, "inactive", true, false);
    File plain = page(scans, "plain", false, false);

    assertEquals(2, CleanedPreviewCache.purgeInactive(scans, Set.of("active")));

    assertTrue(new File(active, CleanedPreviewCache.FILE).exists());
    assertFalse(new File(active, CleanedPreviewCache.FILE + ".tmp").exists());
    assertFalse(new File(inactive, CleanedPreviewCache.FILE).exists());
    for (File dir : new File[] {active, inactive, plain}) {
      assertTrue(new File(dir, "page.jpg").exists());
    }
  }

  @Test
  public void purgeWithoutScansFolderDoesNothing() {
    assertEquals(0, CleanedPreviewCache.purgeInactive(new File(tmp.getRoot(), "none"), Set.of()));
  }
}
