package de.schliweb.makeacopy.ui.export;

import static org.junit.Assert.*;

import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.function.Predicate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** JVM-only tests for collecting the per-page OCR text of a TXT export. */
public class ExportTxtHelperCollectTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  /** No page owns the in-memory OCR text (e.g. a resumed document). */
  private static boolean nobody(CompletedScan p) {
    return false;
  }

  /** The page with {@code id} owns the in-memory OCR text (it last went through the scan flow). */
  private static Predicate<CompletedScan> owner(String id) {
    return p -> id.equals(p.id());
  }

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
            ExportTxtHelperCollectTest::nobody);
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
            ExportTxtHelperCollectTest::nobody);
    assertEquals("from words page\n\n", text);
  }

  @Test
  public void multiPage_withoutAnyOcr_leavesOnlySeparators() {
    String text =
        ExportTxtHelper.collectOcrText(
            Arrays.asList(
                page("a", null, null), page("b", "/does/not/exist/text.txt", "plain"), null),
            "in-memory text of a page that is not part of the session",
            ExportTxtHelperCollectTest::nobody);
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
        ExportTxtHelper.collectOcrText(
            java.util.Collections.singletonList(page), null, owner("bg")));
    assertEquals(
        "found later",
        ExportTxtHelper.collectOcrText(java.util.Collections.singletonList(page), "", owner("bg")));
  }

  @Test
  public void singlePage_prefersTheInMemoryTextOfItsOwnPage() throws IOException {
    // Back to Review after the page was persisted: the in-memory text is the newer one
    File text = write(tmp.newFolder("mem"), "text.txt", "stale");
    CompletedScan page = page("mem", text.getAbsolutePath(), "plain");
    assertEquals(
        "reviewed",
        ExportTxtHelper.collectOcrText(
            java.util.Collections.singletonList(page), "reviewed", owner("mem")));
  }

  @Test
  public void singlePage_leftAfterDeletingTheFreshPage_usesItsOwnPersistedText()
      throws IOException {
    // Two pages scanned, the second (fresh) one deleted on the export screen: the OCR view model
    // still holds the text of the deleted page, the remaining page has its own text on disk
    File text = write(tmp.newFolder("first"), "text.txt", "text of page one");
    CompletedScan remaining = page("first", text.getAbsolutePath(), "plain");
    assertEquals(
        "text of page one",
        ExportTxtHelper.collectOcrText(
            java.util.Collections.singletonList(remaining),
            "text of the deleted page two",
            owner("deleted-second")));
    // Without any ownership information the in-memory text is never attributed to the page
    assertEquals(
        "text of page one",
        ExportTxtHelper.collectOcrText(
            java.util.Collections.singletonList(remaining), "text of the deleted page two", null));
  }

  @Test
  public void singlePage_withoutOwnTextAndForeignInMemoryText_yieldsNothing() {
    // The remaining page never had OCR: the deleted page's text must not leak into the TXT
    CompletedScan remaining = page("first", null, null);
    assertNull(
        ExportTxtHelper.collectOcrText(
            java.util.Collections.singletonList(remaining),
            "text of the deleted page two",
            owner("deleted-second")));
  }

  @Test
  public void noSessionPages_useTheInMemoryText() {
    assertEquals("only", ExportTxtHelper.collectOcrText(null, "only", ExportTxtHelperCollectTest::nobody));
    assertEquals(
        "only", ExportTxtHelper.collectOcrText(java.util.Collections.emptyList(), "only", ExportTxtHelperCollectTest::nobody));
  }
}
