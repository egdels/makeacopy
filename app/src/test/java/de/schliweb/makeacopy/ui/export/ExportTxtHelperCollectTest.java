package de.schliweb.makeacopy.ui.export;

import static org.junit.Assert.*;

import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** JVM-only tests for collecting the per-page OCR text of a multi-page TXT export. */
public class ExportTxtHelperCollectTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static CompletedScan page(String id, String ocrTextPath, String ocrFormat) {
    return new CompletedScan(
        id, null, 0, ocrTextPath, ocrFormat, null, 0L, 100, 100, null, 2, "baked");
  }

  private File write(File dir, String name, String content) throws IOException {
    File f = new File(dir, name);
    Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    return f;
  }

  @Test
  public void multiPage_joinsPlainTextInPageOrder() throws IOException {
    File a = write(tmp.newFolder("a"), "text.txt", "first");
    File b = write(tmp.newFolder("b"), "text.txt", "second");
    String text =
        ExportTxtHelper.collectOcrText(
            Arrays.asList(
                page("a", a.getAbsolutePath(), "plain"), page("b", b.getAbsolutePath(), null)),
            null,
            null);
    assertEquals("first\n\nsecond", text);
  }

  @Test
  public void multiPage_wordsJsonUsesTheTextFileNextToIt() throws IOException {
    File dir = tmp.newFolder("w");
    File words = write(dir, "words.json", "[]");
    write(dir, "text.txt", "from words page");
    String text =
        ExportTxtHelper.collectOcrText(
            Arrays.asList(page("w", words.getAbsolutePath(), "words_json"), page("x", null, null)),
            null,
            null);
    assertEquals("from words page\n\n", text);
  }

  @Test
  public void multiPage_withoutAnyOcr_leavesOnlySeparators() {
    String text =
        ExportTxtHelper.collectOcrText(
            Arrays.asList(
                page("a", null, null), page("b", "/does/not/exist/text.txt", "plain"), null),
            "in-memory text of a page that is not part of the session",
            null);
    assertEquals("\n\n\n\n", text);
    assertFalse(ExportTxtHelper.hasOcrText(text));
  }

  @Test
  public void singlePage_withoutInMemoryText_usesTheTextPersistedByBackgroundOcr() throws IOException {
    // "Skip OCR" in the scan flow, OCR run later from the export screen: the result only exists in
    // the page's files, the OCR view model stays empty
    File text = write(tmp.newFolder("bg"), "text.txt", "found later");
    CompletedScan page = page("bg", text.getAbsolutePath(), "plain");
    assertEquals(
        "found later",
        ExportTxtHelper.collectOcrText(java.util.Collections.singletonList(page), null, null));
    assertEquals(
        "found later",
        ExportTxtHelper.collectOcrText(java.util.Collections.singletonList(page), "", null));
  }

  @Test
  public void singlePage_prefersTheInMemoryText() throws IOException {
    File text = write(tmp.newFolder("mem"), "text.txt", "stale");
    CompletedScan page = page("mem", text.getAbsolutePath(), "plain");
    assertEquals(
        "reviewed",
        ExportTxtHelper.collectOcrText(
            java.util.Collections.singletonList(page), "reviewed", null));
  }
}
