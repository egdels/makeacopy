package de.schliweb.makeacopy.utils.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import de.schliweb.makeacopy.testutil.PdfBoxTesting;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** A black and white page goes into the PDF as a one-bit image, a fraction of a JPEG. */
@RunWith(AndroidJUnit4.class)
public class PdfBlackWhiteImageTest {

  private static final int W = 1240;
  private static final int H = 1754;

  @Test
  public void blackAndWhitePageIsOneBitGrayAndSmall() throws Exception {
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    PdfBoxTesting.init();
    List<RecognizedWord> words = new ArrayList<>();
    Bitmap page = renderPage(words);
    try {
      File colour = export(ctx, page, words, false, "bw_test_colour.pdf");
      File bw = export(ctx, page, words, true, "bw_test_bw.pdf");
      assertTrue(
          "b/w " + bw.length() + " B, colour " + colour.length() + " B",
          bw.length() * 4 < colour.length());

      try (PDDocument doc = PDDocument.load(bw)) {
        PDImageXObject img =
            (PDImageXObject) doc.getPage(0).getResources().getXObject(COSName.getPDFName("Im0"));
        assertNotNull(img);
        assertEquals(1, img.getBitsPerComponent());
        assertEquals("DeviceGray", img.getColorSpace().getName());
        assertEquals(COSName.FLATE_DECODE, img.getCOSObject().getFilters());
        assertEquals(W, img.getWidth());
        assertEquals(H, img.getHeight());
        String text = new PDFTextStripper().getText(doc);
        assertTrue(text, text.contains("Schwarz Weiss"));
      }
    } finally {
      page.recycle();
    }
  }

  private static File export(
      Context ctx, Bitmap page, List<RecognizedWord> words, boolean bw, String name) {
    File out = new File(ctx.getCacheDir(), name);
    if (out.exists()) //noinspection ResultOfMethodCallIgnored
    out.delete();
    Uri uri =
        PdfCreator.createSearchablePdf(
            ctx,
            page,
            words,
            Uri.fromFile(out),
            85,
            false,
            bw,
            300,
            PageFormat.FIT_TO_IMAGE,
            DocumentCleanupMode.ORIGINAL,
            false);
    assertNotNull("export " + name, uri);
    return out;
  }

  /** A page of text lines on slightly uneven paper, as a photographed page would be. */
  private static Bitmap renderPage(List<RecognizedWord> words) {
    Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(bmp);
    c.drawColor(Color.rgb(236, 232, 224));
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(Color.rgb(30, 30, 40));
    p.setTextSize(40f);
    for (int i = 0; i < 25; i++) {
      float y = 120 + i * 62;
      String line = i == 0 ? "Schwarz Weiss" : "Zeile " + i + " eines Dokuments mit Text";
      c.drawText(line, 100, y, p);
      if (i == 0) {
        words.add(new RecognizedWord("Schwarz", new RectF(100, y - 34, 270, y + 8), 0.99f));
        words.add(new RecognizedWord("Weiss", new RectF(285, y - 34, 390, y + 8), 0.99f));
      }
    }
    return bmp;
  }
}
