package de.schliweb.makeacopy.utils.docs;

import static org.junit.Assert.*;

import de.schliweb.makeacopy.utils.docs.MarkdownDocument.Block;
import de.schliweb.makeacopy.utils.docs.MarkdownDocument.BlockType;
import de.schliweb.makeacopy.utils.docs.MarkdownDocument.Span;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Parser of the help and the privacy policy, and a guard that the shipped files
 * (docs/_includes/privacy.md, help/HELP*.md) use only the Markdown subset it understands.
 */
public class MarkdownDocumentTest {

  @Test
  public void parse_titleHeadingsParagraphsAndBullets() {
    MarkdownDocument doc =
        MarkdownDocument.parse(
            "# Title\n\nFirst line\nsecond line.\n\n## Heading\n\n- one\n  continued\n- two\n",
            "Fallback");
    assertEquals("Title", doc.title());
    List<Block> blocks = doc.blocks();
    assertEquals(4, blocks.size());
    assertEquals(BlockType.PARAGRAPH, blocks.get(0).type());
    assertEquals("First line second line.", text(blocks.get(0)));
    assertEquals(BlockType.HEADING, blocks.get(1).type());
    assertEquals("Heading", text(blocks.get(1)));
    assertEquals(BlockType.BULLET, blocks.get(2).type());
    assertEquals("one continued", text(blocks.get(2)));
    assertEquals("two", text(blocks.get(3)));
  }

  @Test
  public void parse_withoutTitle_usesFallback() {
    assertEquals("Fallback", MarkdownDocument.parse("Just text.", "Fallback").title());
  }

  @Test
  public void parse_paragraphAfterBullet_startsNewBlock() {
    List<Block> blocks = MarkdownDocument.parse("- item\nAfter.", "x").blocks();
    assertEquals(2, blocks.size());
    assertEquals(BlockType.BULLET, blocks.get(0).type());
    assertEquals(BlockType.PARAGRAPH, blocks.get(1).type());
  }

  @Test
  public void inline_boldItalicAndCode() {
    List<Span> spans = MarkdownDocument.inline("a **b** *c `d`* e");
    assertEquals(
        List.of(
            new Span("a ", false, false, false),
            new Span("b", true, false, false),
            new Span(" ", false, false, false),
            new Span("c ", false, true, false),
            new Span("d", false, true, true),
            new Span(" e", false, false, false)),
        spans);
  }

  @Test
  public void inline_angleBracketAddresses_becomePlainText() {
    assertEquals(
        List.of(
            new Span("see ", false, false, false),
            new Span("https://example.org/a", false, false, false),
            new Span(" or ", false, false, false),
            new Span("me@example.org", false, false, false)),
        MarkdownDocument.inline("see <https://example.org/a> or <me@example.org>"));
  }

  @Test
  public void shippedDocuments_useOnlyTheSupportedSubset() throws Exception {
    List<File> files = shippedDocuments();
    for (File file : files) {
      String markdown = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
      String[] lines = markdown.split("\\r?\\n", -1);
      for (int i = 0; i < lines.length; i++) {
        String line = lines[i];
        String where = file.getName() + ":" + (i + 1);
        assertFalse(where + ": no deeper headings", line.startsWith("###"));
        assertFalse(where + ": no numbered lists", line.matches("\\s*\\d+[.)] .*"));
        assertFalse(where + ": no tables", line.trim().startsWith("|"));
        assertFalse(where + ": no code blocks", line.trim().startsWith("```"));
        assertFalse(where + ": no link syntax", line.matches(".*\\]\\(.*"));
        assertFalse(where + ": no '*' bullets", line.startsWith("* "));
      }
      MarkdownDocument doc = MarkdownDocument.parse(markdown, "none");
      assertNotEquals(file.getName() + " needs a '#' title", "none", doc.title());
      for (Block block : doc.blocks()) {
        String text = text(block);
        assertFalse(file.getName() + ": unmatched markup in \"" + text + "\"", text.contains("*"));
        assertFalse(file.getName() + ": unmatched markup in \"" + text + "\"", text.contains("`"));
        // Liquid would run on the policy when GitHub Pages includes it into privacy.html
        assertFalse(file.getName() + ": no Liquid tags", text.contains("{{") || text.contains("{%"));
      }
    }
  }

  @Test
  public void help_hasSameSectionCountInEveryLanguage() throws Exception {
    File help = new File(root(), "help");
    int expected = headingCount(new File(help, "HELP.md"));
    for (File file : shippedDocuments()) {
      if (file.getName().startsWith("HELP.")) {
        assertEquals(file.getName(), expected, headingCount(file));
      }
    }
  }

  private static int headingCount(File file) throws Exception {
    MarkdownDocument doc =
        MarkdownDocument.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), "x");
    int n = 0;
    for (Block block : doc.blocks()) if (block.type() == BlockType.HEADING) n++;
    return n;
  }

  private static File root() {
    // Gradle runs unit tests with the module directory as working dir; IDEs may use the root.
    return new File("../help").isDirectory() ? new File("..") : new File(".");
  }

  private static List<File> shippedDocuments() {
    File root = root();
    List<File> files = new ArrayList<>();
    File privacy = new File(root, "docs/_includes/privacy.md");
    assertTrue("privacy.md not found: " + privacy.getAbsolutePath(), privacy.isFile());
    files.add(privacy);
    File[] help = new File(root, "help").listFiles((dir, name) -> name.endsWith(".md"));
    assertNotNull("help/ not found", help);
    assertTrue("help/HELP.md missing", new File(root, "help/HELP.md").isFile());
    files.addAll(List.of(help));
    return files;
  }

  private static String text(Block block) {
    StringBuilder sb = new StringBuilder();
    for (Span span : block.spans()) sb.append(span.text());
    return sb.toString();
  }
}
