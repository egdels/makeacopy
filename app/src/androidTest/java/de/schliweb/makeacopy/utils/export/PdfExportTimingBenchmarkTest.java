package de.schliweb.makeacopy.utils.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import de.schliweb.makeacopy.utils.ocr.OcrEngine;
import de.schliweb.makeacopy.utils.ocr.paddle.PaddleEngineFactory;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Times the PDF export the way the Export screen calls it, to split the cost into the fixed font
 * setup and the per-page image work (Play review 2026-10-23: "saving takes too long").
 *
 * <p>Disabled by default; run with {@code -e RUN_BENCHMARKS 1}. It measures, it never fails on
 * timings. Results go to logcat under the tag {@code PdfExportTiming}.
 */
@RunWith(AndroidJUnit4.class)
public class PdfExportTimingBenchmarkTest {

  private static final String TAG = "PdfExportTiming";
  private static final int RUNS = 3;
  // A cropped A4 page from a 12 MP capture (4080 x 3072 sensor output, portrait).
  private static final int PAGE_W = 2900;
  private static final int PAGE_H = 4100;
  private static final int MULTI_PAGES = 5;

  @Test
  public void timeExport() throws Exception {
    Assume.assumeTrue(
        "1".equals(InstrumentationRegistry.getArguments().getString("RUN_BENCHMARKS")));
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

    List<RecognizedWord> words = new ArrayList<>();
    Bitmap page = renderTextPage(words);
    Log.i(TAG, "page " + PAGE_W + "x" + PAGE_H + ", words=" + words.size());

    PdfQualityPreset high = PdfQualityPreset.HIGH;
    PdfQualityPreset std = PdfQualityPreset.STANDARD;
    timeSingle(ctx, "1p HIGH color words", page, words, high, false, false, null);
    timeSingle(ctx, "1p HIGH color no-words", page, null, high, false, false, null);
    timeSingle(ctx, "1p STANDARD color words", page, words, std, false, false, null);
    timeSingle(ctx, "1p HIGH gray words", page, words, high, true, false, null);
    timeSingle(
        ctx, "1p HIGH bw-robust words", page, words, high, false, true, null);
    timeSingle(
        ctx, "1p HIGH enhanced words", page, words, high, false, false,
        DocumentCleanupMode.ENHANCED);

    File[] files = writePageFiles(ctx, page);
    timeMulti(ctx, "5p STANDARD color words", files, words, std);
    timeMulti(ctx, "5p STANDARD color no-words", files, null, std);
    timeMulti(ctx, "5p HIGH color words", files, words, high);

    timeOcr(ctx, page);
  }

  /**
   * A large imported PDF without OCR: 90 A4 pages as the PDF import stores them (300 dpi, 2480 x
   * 3508), logging the elapsed time every 10 pages and the final save separately.
   */
  @Test
  public void timeLargeDocument() throws Exception {
    Assume.assumeTrue(
        "1".equals(InstrumentationRegistry.getArguments().getString("RUN_BENCHMARKS")));
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Bitmap full = renderTextPage(new ArrayList<>());
    Bitmap a4 = Bitmap.createScaledBitmap(full, 2480, 3508, true);
    full.recycle();
    File[] files = new File[90];
    for (int i = 0; i < files.length; i++) {
      files[i] = new File(ctx.getCacheDir(), "bench_a4_" + i + ".jpg");
      if (i == 0) {
        try (FileOutputStream os = new FileOutputStream(files[0])) {
          a4.compress(Bitmap.CompressFormat.JPEG, ScanPersister.PAGE_JPEG_QUALITY, os);
        }
      } else {
        java.nio.file.Files.copy(
            files[0].toPath(), files[i].toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
    a4.recycle();
    // Default page format FIT_TO_IMAGE. STANDARD (200 dpi) scales the pages down; HIGH (300 dpi)
    // can embed the stored JPEGs as they are, unless the source does not offer them.
    timeLarge(ctx, "90p STANDARD fit", files, PdfQualityPreset.STANDARD, PageFormat.FIT_TO_IMAGE, true);
    timeLarge(ctx, "90p HIGH fit stored", files, PdfQualityPreset.HIGH, PageFormat.FIT_TO_IMAGE, true);
    timeLarge(ctx, "90p HIGH fit decoded", files, PdfQualityPreset.HIGH, PageFormat.FIT_TO_IMAGE, false);
    timeLarge(ctx, "90p STANDARD A4", files, PdfQualityPreset.STANDARD, PageFormat.A4, true);
    // The same STANDARD export with page preparation on a single thread, for comparison.
    PdfCreator.setExportWorkersForTesting(1);
    try {
      timeLarge(
          ctx, "90p STANDARD fit 1 worker", files, PdfQualityPreset.STANDARD,
          PageFormat.FIT_TO_IMAGE, true);
    } finally {
      PdfCreator.setExportWorkersForTesting(0);
    }
  }

  private void timeLarge(
      Context ctx,
      String label,
      File[] files,
      PdfQualityPreset preset,
      PageFormat format,
      boolean offerStored) {
    File out = new File(ctx.getCacheDir(), "bench_large.pdf");
    if (out.exists() && !out.delete()) Log.w(TAG, "could not delete " + out);
    Uri targetUri = Uri.fromFile(out);
    Runtime rt = Runtime.getRuntime();
    long[] decode = new long[1];
    long[] lastPageEnd = new long[1];
    final long t0 = SystemClock.elapsedRealtime();
    lastPageEnd[0] = t0;
    PdfCreator.PageSource source =
        new PdfCreator.PageSource() {
          @Override
          public int getPageCount() {
            return files.length;
          }

          @Override
          public Bitmap loadBitmap(int index) {
            long t = SystemClock.elapsedRealtime();
            Bitmap b = BitmapFactory.decodeFile(files[index].getAbsolutePath());
            decode[0] += SystemClock.elapsedRealtime() - t;
            return b;
          }

          @Override
          public List<RecognizedWord> loadWords(int index) {
            return null;
          }

          @Override
          public File storedJpeg(int index) {
            return offerStored ? files[index] : null;
          }
        };
    Uri uri =
        PdfCreator.createSearchablePdf(
            ctx,
            source,
            targetUri,
            preset.jpegQuality,
            false,
            false,
            preset.targetDpi,
            (i, n) -> {
              long now = SystemClock.elapsedRealtime();
              if (i % 10 == 0 || i == n) {
                Log.i(
                    TAG,
                    String.format(
                        Locale.ROOT,
                        "%s: page %d/%d at %6d ms (last page %4d ms), heap used %d MB / max %d MB",
                        label, i, n, now - t0, now - lastPageEnd[0],
                        (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20));
              }
              lastPageEnd[0] = now;
            },
            format,
            DocumentCleanupMode.ORIGINAL,
            false);
    long total = SystemClock.elapsedRealtime() - t0;
    Log.i(
        TAG,
        String.format(
            Locale.ROOT,
            "%s: total %d ms, save after last page %d ms, decode %d ms, file %d KB%s",
            label, total, SystemClock.elapsedRealtime() - lastPageEnd[0], decode[0],
            out.length() / 1024, uri == null ? ", EXPORT FAILED" : ""));
  }

  /** For comparison: Paddle OCR of the same page (the OCR screen runs it once per page). */
  private void timeOcr(Context ctx, Bitmap page) throws Exception {
    long t0 = SystemClock.elapsedRealtime();
    OcrEngine engine = PaddleEngineFactory.create(ctx, "eng");
    report("paddle engine create", new long[] {SystemClock.elapsedRealtime() - t0});
    try {
      long[] ms = new long[RUNS];
      for (int r = 0; r < RUNS; r++) {
        long t = SystemClock.elapsedRealtime();
        engine.run(page);
        ms[r] = SystemClock.elapsedRealtime() - t;
      }
      report("paddle ocr 1 page", ms);
    } finally {
      engine.close();
    }
  }

  private void timeSingle(
      Context ctx,
      String label,
      Bitmap page,
      List<RecognizedWord> words,
      PdfQualityPreset preset,
      boolean gray,
      boolean bw,
      DocumentCleanupMode cleanup) {
    long[] ms = new long[RUNS];
    File out = new File(ctx.getCacheDir(), "bench_single.pdf");
    for (int r = 0; r < RUNS; r++) {
      long t0 = SystemClock.elapsedRealtime();
      Uri uri =
          PdfCreator.createSearchablePdf(
              ctx,
              page,
              words,
              Uri.fromFile(out),
              preset.jpegQuality,
              gray,
              bw,
              preset.targetDpi,
              PageFormat.FIT_TO_IMAGE,
              cleanup == null ? DocumentCleanupMode.ORIGINAL : cleanup,
              false);
      ms[r] = SystemClock.elapsedRealtime() - t0;
      if (uri == null) Log.w(TAG, label + ": export returned null");
    }
    report(label + " (" + out.length() / 1024 + " KB)", ms);
  }

  private void timeMulti(
      Context ctx, String label, File[] files, List<RecognizedWord> words, PdfQualityPreset preset) {
    long[] ms = new long[RUNS];
    long[] decode = new long[1];
    File out = new File(ctx.getCacheDir(), "bench_multi.pdf");
    for (int r = 0; r < RUNS; r++) {
      decode[0] = 0;
      PdfCreator.PageSource source =
          new PdfCreator.PageSource() {
            @Override
            public int getPageCount() {
              return files.length;
            }

            @Override
            public Bitmap loadBitmap(int index) {
              long t = SystemClock.elapsedRealtime();
              Bitmap b = BitmapFactory.decodeFile(files[index].getAbsolutePath());
              decode[0] += SystemClock.elapsedRealtime() - t;
              return b;
            }

            @Override
            public List<RecognizedWord> loadWords(int index) {
              return words;
            }
          };
      long t0 = SystemClock.elapsedRealtime();
      Uri uri =
          PdfCreator.createSearchablePdf(
              ctx,
              source,
              Uri.fromFile(out),
              preset.jpegQuality,
              false,
              false,
              preset.targetDpi,
              (i, n) -> {},
              PageFormat.FIT_TO_IMAGE,
              DocumentCleanupMode.ORIGINAL,
              false);
      ms[r] = SystemClock.elapsedRealtime() - t0;
      if (uri == null) Log.w(TAG, label + ": export returned null");
    }
    report(label + " (" + out.length() / 1024 + " KB, last decode " + decode[0] + " ms)", ms);
  }

  private static void report(String label, long[] ms) {
    long[] sorted = ms.clone();
    Arrays.sort(sorted);
    Log.i(
        TAG,
        String.format(
            Locale.ROOT, "%-55s first=%5d ms  median=%5d ms  all=%s",
            label, ms[0], sorted[sorted.length / 2], Arrays.toString(ms)));
  }

  /** Draws 45 lines of body text and records one word box per drawn word. */
  private static Bitmap renderTextPage(List<RecognizedWord> words) {
    Bitmap bmp = Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(bmp);
    c.drawColor(Color.rgb(246, 243, 236));
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(Color.rgb(20, 20, 20));
    p.setTextSize(52f);
    String[] vocab =
        "Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt"
            .split(" ");
    float space = p.measureText(" ");
    int k = 0;
    for (int line = 0; line < 45; line++) {
      float y = 220 + line * 84;
      float x = 200;
      while (true) {
        String w = vocab[k++ % vocab.length];
        float wWidth = p.measureText(w);
        if (x + wWidth > PAGE_W - 200) break;
        c.drawText(w, x, y, p);
        words.add(new RecognizedWord(w, new RectF(x, y - 40, x + wWidth, y + 12), 95f));
        x += wWidth + space;
      }
    }
    return bmp;
  }

  /** Stores the page as the session does (JPEG files that the export decodes one at a time). */
  private static File[] writePageFiles(Context ctx, Bitmap page) throws Exception {
    File[] files = new File[MULTI_PAGES];
    for (int i = 0; i < MULTI_PAGES; i++) {
      files[i] = new File(ctx.getCacheDir(), "bench_page_" + i + ".jpg");
      try (FileOutputStream os = new FileOutputStream(files[i])) {
        page.compress(Bitmap.CompressFormat.JPEG, 92, os);
      }
    }
    return files;
  }
}
