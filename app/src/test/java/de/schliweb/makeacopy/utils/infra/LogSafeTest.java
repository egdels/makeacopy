package de.schliweb.makeacopy.utils.infra;

import static org.junit.Assert.*;

import java.io.File;
import org.junit.Test;

/** Log lines show which provider or file kind, never the user's folder and file names (F-06). */
public class LogSafeTest {

  @Test
  public void describe_keepsSchemeAndProviderOnly() {
    assertEquals(
        "content://com.android.externalstorage.documents/…",
        LogSafe.describe("content", "com.android.externalstorage.documents", true));
    assertEquals("content://media", LogSafe.describe("content", "media", false));
    assertEquals("?://", LogSafe.describe(null, null, false));
  }

  @Test
  public void file_and_path_dropTheFolders() {
    assertEquals(
        "page.jpg",
        LogSafe.file(new File("/data/user/0/de.schliweb.makeacopy/files/scans/abc/page.jpg")));
    assertEquals("page.jpg", LogSafe.path("/data/user/0/de.schliweb.makeacopy/files/scans/abc/page.jpg"));
    assertEquals("null", LogSafe.file(null));
    assertEquals("null", LogSafe.path(null));
  }

  @Test
  public void uri_null_isSpelledOut() {
    assertEquals("null", LogSafe.uri(null));
  }
}
