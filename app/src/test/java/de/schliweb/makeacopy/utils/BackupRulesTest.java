package de.schliweb.makeacopy.utils;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Guards that scans never leave the device through Android's backup or device transfer: every
 * document folder is excluded in {@code data_extraction_rules.xml} (both sections) and in {@code
 * backup_rules.xml}, and every folder the code creates under {@code getFilesDir()} is either
 * excluded or explicitly allowed here.
 */
public class BackupRulesTest {

  /** Folders, databases and preferences that hold scans or point to them. */
  private static final List<String> EXCLUDED =
      List.of(
          "file:scans",
          "file:scans_covers",
          "file:documents",
          "file:registry",
          "file:Pictures",
          "database:.",
          "external:.",
          "sharedpref:session_ids.xml");

  /** Folders under getFilesDir() that hold no document content and may be backed up. */
  private static final Set<String> BACKED_UP_FOLDERS = Set.of("tessdata");

  private static final Pattern EXCLUDE =
      Pattern.compile("<exclude\\s+domain=\"([a-z]+)\"\\s+path=\"([^\"]+)\"\\s*/>");

  private static final Pattern FILES_DIR_CHILD =
      Pattern.compile("getFilesDir\\(\\),\\s*\"([^\"/]+)");

  private static File moduleDir() {
    // Gradle runs unit tests with the module directory as working dir; IDEs may use the root.
    File dir = new File("src/main");
    return dir.isDirectory() ? new File(".") : new File("app");
  }

  private static String read(String relative) throws IOException {
    File file = new File(moduleDir(), relative);
    assertTrue("not found: " + file.getAbsolutePath(), file.isFile());
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

  private static Set<String> exclusions(String xml) {
    Set<String> found = new TreeSet<>();
    Matcher m = EXCLUDE.matcher(xml);
    while (m.find()) found.add(m.group(1) + ":" + m.group(2));
    return found;
  }

  private static String section(String xml, String name) {
    int start = xml.indexOf("<" + name + ">");
    int end = xml.indexOf("</" + name + ">");
    assertTrue("missing <" + name + ">", start >= 0 && end > start);
    return xml.substring(start, end);
  }

  @Test
  public void dataExtractionRules_excludeScansFromCloudBackupAndDeviceTransfer() throws Exception {
    String xml = read("src/main/res/xml/data_extraction_rules.xml");
    for (String section : List.of("cloud-backup", "device-transfer")) {
      Set<String> found = exclusions(section(xml, section));
      for (String path : EXCLUDED) {
        assertTrue(section + " must exclude " + path, found.contains(path));
      }
    }
  }

  @Test
  public void legacyBackupRules_excludeScans() throws Exception {
    Set<String> found = exclusions(read("src/main/res/xml/backup_rules.xml"));
    for (String path : EXCLUDED) {
      assertTrue("backup_rules.xml must exclude " + path, found.contains(path));
    }
  }

  @Test
  public void everyFolderUnderFilesDir_isExcludedOrAllowed() throws Exception {
    Set<String> created = new TreeSet<>();
    try (Stream<Path> sources = Files.walk(new File(moduleDir(), "src").toPath())) {
      for (Path source : (Iterable<Path>) sources.filter(p -> p.toString().endsWith(".java"))::iterator) {
        // Only app code: the test source sets (test, androidTest*) never run in a user's install
        String path = source.toString();
        if (path.contains(File.separator + "test" + File.separator)
            || path.contains(File.separator + "androidTest")) continue;
        Matcher m =
            FILES_DIR_CHILD.matcher(
                new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
        while (m.find()) created.add(m.group(1));
      }
    }
    assertFalse("no getFilesDir() folders found; the pattern no longer matches", created.isEmpty());
    for (String folder : created) {
      assertTrue(
          "getFilesDir()/" + folder + " is neither excluded from the backup nor allowed in "
              + "BackupRulesTest.BACKED_UP_FOLDERS",
          EXCLUDED.contains("file:" + folder) || BACKED_UP_FOLDERS.contains(folder));
    }
  }
}
