package de.schliweb.makeacopy.utils;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Guards that the FileProvider shares only named subfolders, never a whole root such as the data
 * directory or the cache (security audit F-05).
 */
public class ProviderPathsTest {

  private static final Pattern PATH =
      Pattern.compile("<([a-z-]+-path)\\s+name=\"([^\"]+)\"\\s+path=\"([^\"]*)\"");

  @Test
  public void everyProviderPath_isANamedSubfolder() throws Exception {
    File file = new File("src/main/res/xml/provider_paths.xml");
    if (!file.isFile()) file = new File("app/src/main/res/xml/provider_paths.xml");
    String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    Matcher m = PATH.matcher(xml);
    int count = 0;
    while (m.find()) {
      count++;
      String where = m.group(1) + " " + m.group(2);
      String path = m.group(3);
      assertFalse(where + " shares a whole root", path.isEmpty() || path.equals(".") || path.equals("/"));
      assertFalse(where + " must not climb out", path.contains(".."));
      assertTrue(where + " should name a folder ending in /", path.endsWith("/"));
    }
    assertTrue("no paths found in provider_paths.xml", count > 0);
    assertFalse("root-path shares the whole file system", xml.contains("<root-path"));
  }
}
