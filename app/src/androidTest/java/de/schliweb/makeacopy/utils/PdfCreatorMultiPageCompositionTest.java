package de.schliweb.makeacopy.utils;

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
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Additional multipage PDF composition test ensuring: - Correct page count for N pages - OCR text
 * layer is embedded for every page that has words provided
 */
@RunWith(AndroidJUnit4.class)
public class PdfCreatorMultiPageCompositionTest {

  private static Context context;

  @BeforeClass
  public static void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    PdfBoxTesting.init();
  }

  @Test
  public void test_MultipagePdf_WithWords_OnAllPages() throws Exception {
    // Two simple white pages
    Bitmap page1 = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888);
    Bitmap page2 = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888);
    page1.eraseColor(Color.WHITE);
    page2.eraseColor(Color.WHITE);

    try {
      // Words on both pages
      List<RecognizedWord> words1 = new ArrayList<>();
      words1.add(new RecognizedWord("Alpha", new RectF(100, 200, 220, 240), 0.98f));
      words1.add(new RecognizedWord("Beta", new RectF(230, 200, 310, 240), 0.98f));

      List<RecognizedWord> words2 = new ArrayList<>();
      words2.add(new RecognizedWord("Gamma", new RectF(120, 220, 220, 260), 0.98f));
      words2.add(new RecognizedWord("Delta", new RectF(230, 220, 320, 260), 0.98f));

      List<Bitmap> bitmaps = Arrays.asList(page1, page2);
      List<List<RecognizedWord>> perPage = Arrays.asList(words1, words2);

      File out = new File(context.getCacheDir(), "multi_all_words.pdf");
      if (out.exists()) // noinspection ResultOfMethodCallIgnored
      out.delete();
      Uri outUri = Uri.fromFile(out);

      Uri res = PdfCreator.createSearchablePdf(context, bitmaps, perPage, outUri, 85, true);
      assertNotNull("PdfCreator returned null URI", res);
      assertTrue("Output PDF missing", out.exists());
      assertTrue("Output PDF too small", out.length() > 1024);

      try (PDDocument doc = PDDocument.load(out)) {
        assertEquals("Expected 2 pages", 2, doc.getNumberOfPages());

        PDFTextStripper stripper = new PDFTextStripper();

        stripper.setStartPage(1);
        stripper.setEndPage(1);
        String t1 = normalize(stripper.getText(doc));
        assertTrue("Page 1 should contain 'Alpha'", t1.contains("Alpha"));
        assertTrue("Page 1 should contain 'Beta'", t1.contains("Beta"));

        stripper.setStartPage(2);
        stripper.setEndPage(2);
        String t2 = normalize(stripper.getText(doc));
        assertTrue("Page 2 should contain 'Gamma'", t2.contains("Gamma"));
        assertTrue("Page 2 should contain 'Delta'", t2.contains("Delta"));
      }
    } finally {
      if (page1 != null && !page1.isRecycled()) page1.recycle();
      if (page2 != null && !page2.isRecycled()) page2.recycle();
    }
  }

  private static String normalize(String s) {
    if (s == null) return "";
    return s.replace('\u00A0', ' ').replaceAll("[\\s]+", " ").trim();
  }
}
