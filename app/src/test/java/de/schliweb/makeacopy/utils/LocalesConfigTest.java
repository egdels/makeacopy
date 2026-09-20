package de.schliweb.makeacopy.utils;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Guards that {@code res/xml/locales_config.xml} (the list behind the in-app language picker and
 * the system per-app language screen) stays in sync with the translated {@code values-*} folders.
 */
public class LocalesConfigTest {

  private static File resDir() {
    // Gradle runs unit tests with the module directory as working dir; IDEs may use the root.
    File dir = new File("src/main/res");
    return dir.isDirectory() ? dir : new File("app/src/main/res");
  }

  @Test
  public void localesConfig_matchesTranslatedValuesFolders() throws Exception {
    File res = resDir();
    assertTrue("res dir not found: " + res.getAbsolutePath(), res.isDirectory());

    Set<String> translated = new TreeSet<>();
    translated.add("en"); // default values/ folder
    File[] children = res.listFiles();
    assertNotNull(children);
    for (File child : children) {
      String name = child.getName();
      if (!name.startsWith("values-") || !new File(child, "strings.xml").isFile()) continue;
      // values-de -> de, values-pt-rBR -> pt-BR
      translated.add(name.substring("values-".length()).replace("-r", "-"));
    }

    String xml =
        new String(
            Files.readAllBytes(new File(res, "xml/locales_config.xml").toPath()),
            StandardCharsets.UTF_8);
    Set<String> configured = new TreeSet<>();
    Matcher m = Pattern.compile("<locale\\s+android:name=\"([^\"]+)\"").matcher(xml);
    while (m.find()) configured.add(m.group(1));

    assertEquals(translated, configured);
  }
}
