package de.schliweb.makeacopy.utils.export;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** JVM-only tests for the expiry of kept originals (crop sources). */
public class CropSourceStoreExpiryTest {

  private static final long NOW = TimeUnit.DAYS.toMillis(1000);
  private static final long DAY = TimeUnit.DAYS.toMillis(1);
  private static final Set<String> NO_ACTIVE_PAGES = Collections.emptySet();

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private File file(File dir, String name, long lastModified) throws IOException {
    File f = new File(dir, name);
    Files.write(f.toPath(), new byte[] {1, 2, 3});
    assertTrue(f.setLastModified(lastModified));
    return f;
  }

  // --- pure decision ---

  @Test
  public void isExpired_onlyAfterMaxAge() {
    long maxAge = CropSourceStore.MAX_AGE_DAYS * DAY;
    assertFalse(CropSourceStore.isExpired(NOW - maxAge + DAY, false, NOW));
    assertFalse(CropSourceStore.isExpired(NOW - maxAge, false, NOW));
    assertTrue(CropSourceStore.isExpired(NOW - maxAge - 1, false, NOW));
  }

  @Test
  public void isExpired_neverForActiveSessionPages() {
    assertFalse(CropSourceStore.isExpired(0L, true, NOW));
  }

  // --- files ---

  @Test
  public void purge_removesOnlyTheCropSourceOfOldPages() throws IOException {
    File scans = tmp.newFolder("scans");
    File oldPage = new File(scans, "old");
    File freshPage = new File(scans, "fresh");
    assertTrue(oldPage.mkdirs() && freshPage.mkdirs());
    long old = NOW - 31 * DAY;
    File oldOriginal = file(oldPage, CropSourceStore.ORIGINAL_FILE, old);
    File oldCrop = file(oldPage, CropSourceStore.CROP_FILE, old);
    File oldPageJpg = file(oldPage, "page.jpg", old);
    File oldText = file(oldPage, "text.txt", old);
    File freshOriginal = file(freshPage, CropSourceStore.ORIGINAL_FILE, NOW - 29 * DAY);
    File freshCrop = file(freshPage, CropSourceStore.CROP_FILE, NOW - 29 * DAY);

    assertEquals(1, CropSourceStore.purgeExpired(scans, NO_ACTIVE_PAGES, NOW));

    assertFalse(oldOriginal.exists());
    assertFalse(oldCrop.exists());
    assertTrue("the scan itself must stay", oldPageJpg.exists());
    assertTrue("the OCR result must stay", oldText.exists());
    assertTrue(freshOriginal.exists());
    assertTrue(freshCrop.exists());
  }

  @Test
  public void purge_keepsOldPagesOfTheActiveSession() throws IOException {
    File scans = tmp.newFolder("scans");
    File page = new File(scans, "active");
    assertTrue(page.mkdirs());
    File original = file(page, CropSourceStore.ORIGINAL_FILE, NOW - 400 * DAY);
    File crop = file(page, CropSourceStore.CROP_FILE, NOW - 400 * DAY);

    assertEquals(0, CropSourceStore.purgeExpired(scans, Set.of("active"), NOW));

    assertTrue(original.exists());
    assertTrue(crop.exists());
  }

  @Test
  public void purge_recentReEditKeepsAnOldOriginal() throws IOException {
    File scans = tmp.newFolder("scans");
    File page = new File(scans, "reedited");
    assertTrue(page.mkdirs());
    File original = file(page, CropSourceStore.ORIGINAL_FILE, NOW - 90 * DAY);
    file(page, CropSourceStore.CROP_FILE, NOW - 2 * DAY);

    assertEquals(0, CropSourceStore.purgeExpired(scans, NO_ACTIVE_PAGES, NOW));

    assertTrue(original.exists());
  }

  @Test
  public void purge_removesStaleTempCopiesButNotRunningOnes() throws IOException {
    File scans = tmp.newFolder("scans");
    File stalePage = new File(scans, "stale");
    File runningPage = new File(scans, "running");
    assertTrue(stalePage.mkdirs() && runningPage.mkdirs());
    File stale = file(stalePage, CropSourceStore.ORIGINAL_FILE + ".tmp", NOW - 2 * DAY);
    File running =
        file(runningPage, CropSourceStore.ORIGINAL_FILE + ".tmp", NOW - TimeUnit.MINUTES.toMillis(1));

    assertEquals(0, CropSourceStore.purgeExpired(scans, NO_ACTIVE_PAGES, NOW));

    assertFalse(stale.exists());
    assertTrue(running.exists());
  }

  @Test
  public void purge_toleratesMissingBaseDir() {
    assertEquals(0, CropSourceStore.purgeExpired(new File("/does/not/exist"), null, NOW));
    assertEquals(0, CropSourceStore.purgeExpired((File) null, null, NOW));
  }
}
