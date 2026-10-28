package de.schliweb.makeacopy.ui.export;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.testutil.PdfBoxTesting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import de.schliweb.makeacopy.utils.export.PdfCreator;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import de.schliweb.makeacopy.utils.ocr.WordsJson;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented roundtrip test simulating Registry -> Export for multipage PDF.
 *
 * <p>Steps: - Create words.json on disk (as if written by registry flow) with two tokens ("Alpha",
 * "Beta"). - Parse it via WordsJson (simulating consumption in ExportFragment). - Create a 2-page
 * PDF via PdfCreator.createSearchablePdf(multi), providing words only for page 1. - Assert: PDF
 * exists, has 2 pages; page 1 text contains "Alpha Beta"; page 2 has no text.
 */
@RunWith(AndroidJUnit4.class)
public class ExportRoundtripInstrumentedTest {

  private static Context context;

  @BeforeClass
  public static void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    PdfBoxTesting.init();
  }

  @Test
  public void testRegistryRoundtripToMultipagePdf_Smoke() throws Exception {
    // Arrange: create two simple bitmaps (white background)
    Bitmap page1 = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888);
    Bitmap page2 = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888);
    page1.eraseColor(Color.WHITE);
    page2.eraseColor(Color.WHITE);

    // Create a temporary words.json file as persisted by registry/inline OCR
    File wordsDir = new File(context.getCacheDir(), "roundtrip_words");
    //noinspection ResultOfMethodCallIgnored
    wordsDir.mkdirs();
    File wordsJsonFile = new File(wordsDir, "words.json");
    String wordsJson = makeWordsJsonForAlphaBeta();
    try (FileOutputStream fos = new FileOutputStream(wordsJsonFile)) {
      fos.write(wordsJson.getBytes(StandardCharsets.UTF_8));
      fos.flush();
    }

    // Simulate ExportFragment's consumption from registry: parse words.json
    List<RecognizedWord> wordsPage1 = WordsJson.parseFile(wordsJsonFile);
    assertNotNull(wordsPage1);
    assertFalse("Expected some words from words.json", wordsPage1.isEmpty());

    // Act: create a multipage PDF with words only on the first page
    File out = new File(context.getCacheDir(), "roundtrip_multi.pdf");
    if (out.exists()) // noinspection ResultOfMethodCallIgnored
    out.delete();
    Uri outUri = Uri.fromFile(out);

    List<Bitmap> bitmaps = Arrays.asList(page1, page2);
    List<List<RecognizedWord>> perPageWords = new ArrayList<>();
    perPageWords.add(wordsPage1); // page 1 has a text layer
    perPageWords.add(null); // page 2 without text layer

    Uri res = PdfCreator.createSearchablePdf(context, bitmaps, perPageWords, outUri, 85, true);

    // Assert: file created and has 2 pages
    assertNotNull("PdfCreator returned null URI", res);
    assertTrue("Output PDF missing", out.exists());
    assertTrue("Output PDF too small", out.length() > 1024);

    try (PDDocument doc = PDDocument.load(out)) {
      assertEquals("Expected 2 pages", 2, doc.getNumberOfPages());

      // Extract text page by page
      PDFTextStripper stripper = new PDFTextStripper();

      // Page numbers are 1-based in PDFTextStripper
      stripper.setStartPage(1);
      stripper.setEndPage(1);
      String text1 = safeNormalize(stripper.getText(doc));

      stripper.setStartPage(2);
      stripper.setEndPage(2);
      String text2 = safeNormalize(stripper.getText(doc));

      assertTrue("Page 1 should contain 'Alpha'", text1.contains("Alpha"));
      assertTrue("Page 1 should contain 'Beta'", text1.contains("Beta"));
      assertTrue(
          "Page 1 should contain phrase 'Alpha Beta'", text1.matches("(?s).*Alpha\\s+Beta.*"));

      // Page 2 should not contain those tokens (no words layer provided)
      assertFalse("Page 2 unexpectedly contains 'Alpha'", text2.contains("Alpha"));
      assertFalse("Page 2 unexpectedly contains 'Beta'", text2.contains("Beta"));
    } finally {
      if (page1 != null && !page1.isRecycled()) page1.recycle();
      if (page2 != null && !page2.isRecycled()) page2.recycle();
    }
  }

  // Create a minimal words_json consistent with our WordsJson parser
  private static String makeWordsJsonForAlphaBeta() {
    // Positions are arbitrary but plausible within 800x1200 image space
    RecognizedWord w1 = new RecognizedWord("Alpha", new RectF(100, 200, 200, 240), 0.98f);
    RecognizedWord w2 = new RecognizedWord("Beta", new RectF(210, 200, 290, 240), 0.98f);

    StringBuilder sb = new StringBuilder();
    sb.append('[');
    appendWord(sb, w1);
    sb.append(',');
    appendWord(sb, w2);
    sb.append(']');
    return sb.toString();
  }

  private static void appendWord(StringBuilder sb, RecognizedWord w) {
    RectF r = w.getBoundingBox();
    sb.append('{')
        .append("\"text\":\"")
        .append(w.getText())
        .append("\",")
        .append("\"left\":")
        .append(fmt(r.left))
        .append(',')
        .append("\"top\":")
        .append(fmt(r.top))
        .append(',')
        .append("\"right\":")
        .append(fmt(r.right))
        .append(',')
        .append("\"bottom\":")
        .append(fmt(r.bottom))
        .append(',')
        .append("\"confidence\":")
        .append("0.98")
        .append('}');
  }

  private static String fmt(float f) {
    return String.format(java.util.Locale.US, "%.2f", f);
  }

  private static String safeNormalize(String s) {
    if (s == null) return "";
    return s.replace("\u00A0", " ").replaceAll("[\\s]+", " ").trim();
  }
}
