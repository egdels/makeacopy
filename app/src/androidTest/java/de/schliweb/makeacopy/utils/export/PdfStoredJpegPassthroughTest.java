package de.schliweb.makeacopy.utils.export;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.testutil.PdfBoxTesting;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.io.IOUtils;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The multi-page export embeds a stored page JPEG unchanged when nothing would alter it, and the
 * result matches the decode path: same page size, searchable text, same look.
 */
@RunWith(AndroidJUnit4.class)
public class PdfStoredJpegPassthroughTest {

  private static final int W = 1240;
  private static final int H = 1754;

  @Test
  public void storedJpegIsEmbeddedUnchangedAndMatchesTheDecodePath() throws Exception {
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    PdfBoxTesting.init();
    List<RecognizedWord> words = new ArrayList<>();
    Bitmap page = renderPage(words);
    File jpeg = new File(ctx.getCacheDir(), "passthrough_page.jpg");
    try (FileOutputStream os = new FileOutputStream(jpeg)) {
      page.compress(Bitmap.CompressFormat.JPEG, ScanPersister.PAGE_JPEG_QUALITY, os);
    }
    page.recycle();

    File stored = export(ctx, jpeg, words, true, "passthrough_stored.pdf");
    File decoded = export(ctx, jpeg, words, false, "passthrough_decoded.pdf");

    try (PDDocument a = PDDocument.load(stored);
        PDDocument b = PDDocument.load(decoded)) {
      PDPage pa = a.getPage(0);
      PDRectangle boxA = pa.getMediaBox();
      PDRectangle boxB = b.getPage(0).getMediaBox();
      assertEquals(boxB.getWidth(), boxA.getWidth(), 0.01f);
      assertEquals(boxB.getHeight(), boxA.getHeight(), 0.01f);

      PDImageXObject img = firstImage(pa);
      assertNotNull("page holds an image", img);
      assertEquals(W, img.getWidth());
      assertEquals(H, img.getHeight());
      byte[] embedded;
      try (InputStream in = img.getCOSObject().createRawInputStream()) {
        embedded = IOUtils.toByteArray(in);
      }
      assertEquals(COSName.DCT_DECODE, img.getCOSObject().getFilters());
      assertArrayEquals("stored JPEG embedded byte for byte", Files.readAllBytes(jpeg.toPath()), embedded);

      String text = new PDFTextStripper().getText(a);
      assertTrue("text layer is searchable: " + text, text.contains("Lorem"));
    }

    Bitmap rendered = renderFirstPage(stored);
    Bitmap original = BitmapFactory.decodeFile(jpeg.getAbsolutePath());
    double diff = meanAbsDiff(rendered, original);
    assertTrue("rendered page looks like the stored JPEG, mean diff " + diff, diff < 6.0);
  }

  private static File export(
      Context ctx, File jpeg, List<RecognizedWord> words, boolean offerStored, String name) {
    File out = new File(ctx.getCacheDir(), name);
    if (out.exists()) assertTrue(out.delete());
    PdfQualityPreset high = PdfQualityPreset.HIGH;
    PdfCreator.PageSource source =
        new PdfCreator.PageSource() {
          @Override
          public int getPageCount() {
            return 1;
          }

          @Override
          public Bitmap loadBitmap(int index) {
            return BitmapFactory.decodeFile(jpeg.getAbsolutePath());
          }

          @Override
          public List<RecognizedWord> loadWords(int index) {
            return words;
          }

          @Override
          public File storedJpeg(int index) {
            return offerStored ? jpeg : null;
          }
        };
    Uri uri =
        PdfCreator.createSearchablePdf(
            ctx,
            source,
            Uri.fromFile(out),
            high.jpegQuality,
            false,
            false,
            high.targetDpi,
            null,
            PageFormat.FIT_TO_IMAGE,
            DocumentCleanupMode.ORIGINAL,
            false);
    assertNotNull("export of " + name, uri);
    return out;
  }

  private static PDImageXObject firstImage(PDPage page) throws Exception {
    for (COSName n : page.getResources().getXObjectNames()) {
      if (page.getResources().getXObject(n) instanceof PDImageXObject) {
        return (PDImageXObject) page.getResources().getXObject(n);
      }
    }
    return null;
  }

  private static Bitmap renderFirstPage(File pdf) throws Exception {
    try (ParcelFileDescriptor fd =
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = new PdfRenderer(fd);
        PdfRenderer.Page p = renderer.openPage(0)) {
      Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
      bmp.eraseColor(Color.WHITE);
      p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
      return bmp;
    }
  }

  private static double meanAbsDiff(Bitmap a, Bitmap b) {
    long sum = 0;
    int n = 0;
    for (int y = 0; y < H; y += 7) {
      for (int x = 0; x < W; x += 7) {
        int pa = a.getPixel(x, y);
        int pb = b.getPixel(x, y);
        sum += Math.abs(Color.red(pa) - Color.red(pb));
        sum += Math.abs(Color.green(pa) - Color.green(pb));
        sum += Math.abs(Color.blue(pa) - Color.blue(pb));
        n += 3;
      }
    }
    return (double) sum / n;
  }

  private static Bitmap renderPage(List<RecognizedWord> words) {
    Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(bmp);
    c.drawColor(Color.rgb(250, 248, 240));
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(Color.rgb(30, 30, 90));
    p.setTextSize(40f);
    for (int line = 0; line < 20; line++) {
      float y = 160 + line * 70;
      String w = line % 2 == 0 ? "Lorem" : "ipsum";
      c.drawText(w, 120, y, p);
      words.add(new RecognizedWord(w, new RectF(120, y - 32, 120 + p.measureText(w), y + 8), 95f));
    }
    return bmp;
  }
}
