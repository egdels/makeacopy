package de.schliweb.makeacopy.utils;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import de.schliweb.makeacopy.utils.export.PdfCreator;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Verifies the streaming multi-page PDF export (Session 2): with one worker, pages are loaded
 * lazily and strictly sequentially via {@link PdfCreator.PageSource}, each loaded bitmap is
 * released exactly once before the next page is requested (peak memory ≈ one page); with several
 * workers, at most one page per worker is alive and the page order is kept. Pages without OCR
 * export image-only, and page errors fail the export atomically (no output written).
 */
@RunWith(AndroidJUnit4.class)
public class PdfCreatorStreamingTest {

  private static Context context;

  @BeforeClass
  public static void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    PDFBoxResourceLoader.init(context);
  }

  @Before
  public void oneWorker() {
    PdfCreator.setExportWorkersForTesting(1);
  }

  @After
  public void deviceWorkers() {
    PdfCreator.setExportWorkersForTesting(0);
  }

  /**
   * PageSource that creates one small bitmap per request and records the lifecycle. Synchronized,
   * since several export workers call it at once.
   */
  private static class TrackingPageSource implements PdfCreator.PageSource {
    final int pageCount;
    final List<List<RecognizedWord>> words;
    final List<String> events = new ArrayList<>();
    final List<Bitmap> live = new ArrayList<>();
    int maxLive = 0;
    int failLoadAt = -1; // page index whose loadBitmap throws
    int throwWordsAt = -1; // page index whose loadWords throws

    TrackingPageSource(int pageCount, List<List<RecognizedWord>> words) {
      this.pageCount = pageCount;
      this.words = words;
    }

    @Override
    public int getPageCount() {
      return pageCount;
    }

    @Override
    public synchronized Bitmap loadBitmap(int index) throws Exception {
      events.add("load:" + index);
      if (index == failLoadAt) throw new RuntimeException("decode failed for page " + index);
      Bitmap b = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888);
      b.eraseColor(Color.WHITE);
      live.add(b);
      maxLive = Math.max(maxLive, live.size());
      return b;
    }

    @Override
    public synchronized List<RecognizedWord> loadWords(int index) {
      events.add("words:" + index);
      if (index == throwWordsAt) throw new RuntimeException("broken OCR JSON for page " + index);
      return (words != null && index < words.size()) ? words.get(index) : null;
    }

    @Override
    public synchronized void releasePage(int index, Bitmap bitmap) {
      events.add("release:" + index);
      live.remove(bitmap);
      if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
  }

  private static List<RecognizedWord> word(String text) {
    List<RecognizedWord> l = new ArrayList<>();
    l.add(new RecognizedWord(text, new RectF(100, 200, 220, 240), 0.98f));
    return l;
  }

  private File outFile(String name) {
    File out = new File(context.getCacheDir(), name);
    if (out.exists()) //noinspection ResultOfMethodCallIgnored
    out.delete();
    return out;
  }

  private static Uri export(PdfCreator.PageSource src, Uri outUri, List<int[]> progress) {
    return PdfCreator.createSearchablePdf(
        context,
        src,
        outUri,
        85,
        false,
        false,
        300,
        (p, t) -> {
          if (progress != null) progress.add(new int[] {p, t});
        },
        null,
        null,
        null);
  }

  @Test
  public void streamingExport_isLazy_sequential_andReleasesEachPageOnce() throws Exception {
    List<List<RecognizedWord>> words = new ArrayList<>();
    words.add(word("Alpha"));
    words.add(word("Beta"));
    words.add(word("Gamma"));
    TrackingPageSource src = new TrackingPageSource(3, words);
    File out = outFile("streaming_3p.pdf");
    List<int[]> progress = new ArrayList<>();

    Uri res = export(src, Uri.fromFile(out), progress);
    assertNotNull("Streaming export failed", res);
    assertTrue(out.exists() && out.length() > 1024);

    // At most one page bitmap alive at any time
    assertEquals("More than one page bitmap was alive concurrently", 1, src.maxLive);
    assertTrue("Not all bitmaps released", src.live.isEmpty());

    // Strict lazy sequencing: load(i) ... release(i) before load(i+1)
    List<String> ev = src.events;
    assertTrue(ev.indexOf("release:0") < ev.indexOf("load:1"));
    assertTrue(ev.indexOf("release:1") < ev.indexOf("load:2"));
    // Each page released exactly once
    for (int i = 0; i < 3; i++) {
      final String rel = "release:" + i;
      assertEquals(1, ev.stream().filter(rel::equals).count());
    }

    // Progress: one event per page with correct total
    assertEquals(3, progress.size());
    assertEquals(1, progress.get(0)[0]);
    assertEquals(3, progress.get(0)[1]);
    assertEquals(3, progress.get(2)[0]);

    // Page order and count in the produced PDF
    try (PDDocument doc = PDDocument.load(out)) {
      assertEquals(3, doc.getNumberOfPages());
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setStartPage(1);
      stripper.setEndPage(1);
      assertTrue(stripper.getText(doc).contains("Alpha"));
      stripper.setStartPage(3);
      stripper.setEndPage(3);
      assertTrue(stripper.getText(doc).contains("Gamma"));
    }
  }

  @Test
  public void pageWithoutOcr_exportsImageOnly() throws Exception {
    // Page 2 has no OCR words (mixed OCR availability)
    List<List<RecognizedWord>> words = new ArrayList<>();
    words.add(word("Alpha"));
    words.add(null);
    TrackingPageSource src = new TrackingPageSource(2, words);
    File out = outFile("streaming_mixed_ocr.pdf");

    Uri res = export(src, Uri.fromFile(out), null);
    assertNotNull("Export must succeed with image-only pages", res);
    try (PDDocument doc = PDDocument.load(out)) {
      assertEquals(2, doc.getNumberOfPages());
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setStartPage(2);
      stripper.setEndPage(2);
      assertTrue("Page 2 must have no text layer", stripper.getText(doc).trim().isEmpty());
    }
  }

  @Test
  public void brokenOcrPayload_fallsBackToImageOnly() throws Exception {
    TrackingPageSource src = new TrackingPageSource(2, Collections.singletonList(word("Alpha")));
    src.throwWordsAt = 1;
    File out = outFile("streaming_broken_ocr.pdf");

    Uri res = export(src, Uri.fromFile(out), null);
    assertNotNull("Broken OCR JSON on one page must not abort export", res);
    try (PDDocument doc = PDDocument.load(out)) {
      assertEquals(2, doc.getNumberOfPages());
    }
  }

  @Test
  public void bitmapLoadFailure_failsAtomically_withoutOutputFile() {
    TrackingPageSource src = new TrackingPageSource(3, null);
    src.failLoadAt = 1;
    File out = outFile("streaming_fail.pdf");

    Uri res = export(src, Uri.fromFile(out), null);
    assertNull("Export must fail when a page cannot be loaded", res);
    // Atomicity: output is only written after all pages rendered successfully
    assertFalse("No (broken) output file must be written", out.exists() && out.length() > 0);
    // Page 0 was still released exactly once
    assertEquals(1, src.events.stream().filter("release:0"::equals).count());
    assertTrue(src.live.isEmpty());
  }

  /** Stress: 90 pages streamed with at most one live page bitmap. */
  @Test
  public void stress_90Pages_streamingExport() throws Exception {
    final int n = 90;
    TrackingPageSource src = new TrackingPageSource(n, null);
    File out = outFile("streaming_90p.pdf");

    Uri res = export(src, Uri.fromFile(out), null);
    assertNotNull("90-page streaming export failed", res);
    assertEquals("Peak live page bitmaps must be 1", 1, src.maxLive);
    assertTrue(src.live.isEmpty());
    try (PDDocument doc = PDDocument.load(out)) {
      assertEquals(n, doc.getNumberOfPages());
    }
  }

  /** Three workers: page order kept, each page released once, at most one live page per worker. */
  @Test
  public void parallel_90Pages_keepsOrderAndBoundsLivePages() throws Exception {
    PdfCreator.setExportWorkersForTesting(3);
    final int n = 90;
    List<List<RecognizedWord>> words = new ArrayList<>();
    for (int i = 0; i < n; i++) words.add(word("Page" + i));
    TrackingPageSource src = new TrackingPageSource(n, words);
    File out = outFile("streaming_parallel_90p.pdf");
    List<int[]> progress = Collections.synchronizedList(new ArrayList<>());

    Uri res = export(src, Uri.fromFile(out), progress);
    assertNotNull("parallel 90-page export failed", res);
    assertTrue("more live page bitmaps than workers: " + src.maxLive, src.maxLive <= 3);
    assertTrue(src.live.isEmpty());
    for (int i = 0; i < n; i++) {
      final String rel = "release:" + i;
      assertEquals(rel, 1, src.events.stream().filter(rel::equals).count());
    }
    // Progress is reported in page order from the writing thread.
    assertEquals(n, progress.size());
    for (int i = 0; i < n; i++) assertEquals(i + 1, progress.get(i)[0]);
    try (PDDocument doc = PDDocument.load(out)) {
      assertEquals(n, doc.getNumberOfPages());
      PDFTextStripper stripper = new PDFTextStripper();
      for (int i : new int[] {0, 1, 44, 89}) {
        stripper.setStartPage(i + 1);
        stripper.setEndPage(i + 1);
        assertEquals("Page" + i, stripper.getText(doc).trim());
      }
    }
  }

  /** Three workers: a failing page aborts the export and every loaded page is still released. */
  @Test
  public void parallel_loadFailure_failsAtomically_andReleasesLoadedPages() {
    PdfCreator.setExportWorkersForTesting(3);
    TrackingPageSource src = new TrackingPageSource(30, null);
    src.failLoadAt = 12;
    File out = outFile("streaming_parallel_fail.pdf");

    Uri res = export(src, Uri.fromFile(out), null);
    assertNull("Export must fail when a page cannot be loaded", res);
    assertFalse("No (broken) output file must be written", out.exists() && out.length() > 0);
    assertTrue("loaded pages left unreleased", src.live.isEmpty());
    for (int i = 0; i < 30; i++) {
      if (i == 12) continue;
      final String load = "load:" + i;
      final String rel = "release:" + i;
      long loads = src.events.stream().filter(load::equals).count();
      assertEquals(rel, loads, src.events.stream().filter(rel::equals).count());
    }
  }
}
