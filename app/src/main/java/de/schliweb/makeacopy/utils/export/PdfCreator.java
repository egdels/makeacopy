/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.VisibleForTesting;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import de.schliweb.makeacopy.utils.image.DocumentCleanupOptions;
import de.schliweb.makeacopy.utils.image.DocumentCleanupProcessor;
import de.schliweb.makeacopy.utils.image.ImageOps;
import de.schliweb.makeacopy.utils.ocr.MultiColumnLayoutPolicy;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The PdfCreator class provides static methods for generating searchable PDFs from bitmap images
 * and recognized OCR words. It supports advanced options such as grayscale or black-and-white
 * conversion, DPI scaling, and robust font fallback for text rendering. This class allows creation
 * of both single-page and multi-page searchable PDFs.
 */
public class PdfCreator {
  private static final String TAG = "PdfCreator";

  public enum PdfImageOutput {
    COLOR,
    GRAYSCALE,
    BLACK_WHITE
  }

  /**
   * Creates a searchable PDF document from a bitmap and a list of recognized words. The resulting
   * PDF includes an image of the provided bitmap and overlays it with a text layer derived from the
   * recognized words for searchability. The output PDF is written to the provided URI with
   * specified image quality and optional grayscale conversion.
   *
   * @param context the Android context, used to access system resources and file utilities
   * @param bitmap the bitmap to place into the PDF as an image
   * @param words the list of recognized words to overlay as a text layer for searchability
   * @param outputUri the URI of the output file where the generated PDF will be stored
   * @param jpegQuality the compression quality of the bitmap within the PDF (0-100)
   * @param convertToGrayscale a flag indicating whether to convert the image to grayscale
   * @return the URI of the generated searchable PDF
   */
  public static Uri createSearchablePdf(
      Context context,
      Bitmap bitmap,
      List<RecognizedWord> words,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale) {
    // Backward-compatible overload: no black-and-white flag -> false
    return createSearchablePdf(
        context, bitmap, words, outputUri, jpegQuality, convertToGrayscale, false, 300);
  }

  /**
   * Creates a searchable PDF from the given bitmap and recognized text words, and writes it to the
   * specified URI. The method allows customization of image processing, PDF properties, and OCR
   * text handling.
   *
   * @param context The application context used for PDF library initialization and resource access.
   * @param bitmap The input bitmap image to be included in the PDF.
   * @param words A list of recognized words with their coordinates, used to create the text layer
   *     in the PDF.
   * @param outputUri The URI where the generated PDF will be saved.
   * @param jpegQuality The quality of the JPEG compression for the image in the range of 0-100 (100
   *     for lossless).
   * @param convertToGrayscale True to convert the image to grayscale before adding it to the PDF.
   * @param convertToBlackWhite True to convert the image to black-and-white using thresholding
   *     techniques.
   * @param targetDpi The target resolution (dots per inch) for the output image in the PDF.
   * @return The URI pointing to the created PDF, or null if the PDF creation failed.
   */
  public static Uri createSearchablePdf(
      Context context,
      Bitmap bitmap,
      List<RecognizedWord> words,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale,
      boolean convertToBlackWhite,
      int targetDpi) {
    return createSearchablePdf(
        context,
        bitmap,
        words,
        outputUri,
        jpegQuality,
        convertToGrayscale,
        convertToBlackWhite,
        targetDpi,
        PageFormat.A4);
  }

  /**
   * Creates a searchable PDF from the given bitmap and recognized text words, and writes it to the
   * specified URI. The method allows customization of image processing, PDF properties, OCR text
   * handling, and page format.
   *
   * @param context The application context used for PDF library initialization and resource access.
   * @param bitmap The input bitmap image to be included in the PDF.
   * @param words A list of recognized words with their coordinates, used to create the text layer
   *     in the PDF.
   * @param outputUri The URI where the generated PDF will be saved.
   * @param jpegQuality The quality of the JPEG compression for the image in the range of 0-100 (100
   *     for lossless).
   * @param convertToGrayscale True to convert the image to grayscale before adding it to the PDF.
   * @param convertToBlackWhite True to convert the image to black-and-white using thresholding
   *     techniques.
   * @param targetDpi The target resolution (dots per inch) for the output image in the PDF.
   * @param pageFormat The page format to use for the PDF (e.g., A4, US Letter, Fit to Image).
   * @return The URI pointing to the created PDF, or null if the PDF creation failed.
   */
  public static Uri createSearchablePdf(
      Context context,
      Bitmap bitmap,
      List<RecognizedWord> words,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale,
      boolean convertToBlackWhite,
      int targetDpi,
      PageFormat pageFormat) {
    return createSearchablePdf(
        context,
        bitmap,
        words,
        outputUri,
        jpegQuality,
        convertToGrayscale,
        convertToBlackWhite,
        targetDpi,
        pageFormat,
        DocumentCleanupMode.ORIGINAL);
  }

  public static Uri createSearchablePdf(
      Context context,
      Bitmap bitmap,
      List<RecognizedWord> words,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale,
      boolean convertToBlackWhite,
      int targetDpi,
      PageFormat pageFormat,
      DocumentCleanupMode cleanupMode) {
    return createSearchablePdf(
        context,
        bitmap,
        words,
        outputUri,
        jpegQuality,
        convertToGrayscale,
        convertToBlackWhite,
        targetDpi,
        pageFormat,
        cleanupMode,
        false);
  }

  /**
   * Variant of {@link #createSearchablePdf(Context, Bitmap, List, Uri, int, boolean, boolean, int,
   * PageFormat, DocumentCleanupMode)} with optional multi-column layout reconstruction (newspapers,
   * magazines). When {@code multiColumn} is {@code true} and the box geometry forms a multi-column
   * layout (see {@link MultiColumnLayoutPolicy}), the invisible text layer is written segment by
   * segment in column reading order so that text extraction and copy/paste follow the columns
   * instead of interleaving them line by line.
   *
   * @param multiColumn whether multi-column layout reconstruction is enabled (user option)
   */
  public static Uri createSearchablePdf(
      Context context,
      Bitmap bitmap,
      List<RecognizedWord> words,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale,
      boolean convertToBlackWhite,
      int targetDpi,
      PageFormat pageFormat,
      DocumentCleanupMode cleanupMode,
      boolean multiColumn) {
    if (bitmap == null || outputUri == null) return null;
    // One page; the caller keeps the bitmap.
    PageSource source =
        new PageSource() {
          @Override
          public int getPageCount() {
            return 1;
          }

          @Override
          public Bitmap loadBitmap(int index) {
            return bitmap;
          }

          @Override
          public List<RecognizedWord> loadWords(int index) {
            return words;
          }

          @Override
          public void releasePage(int index, Bitmap page) {
            // The caller owns the bitmap.
          }
        };
    return createSearchablePdfStreaming(
        context,
        source,
        outputUri,
        jpegQuality,
        convertToGrayscale,
        convertToBlackWhite,
        targetDpi,
        null,
        pageFormat,
        cleanupMode,
        /* skipNullBitmaps= */ false,
        multiColumn);
  }

  /**
   * Calculates the scale factor required to fit an image within a given page size while maintaining
   * its aspect ratio.
   *
   * @param imageWidth the width of the image
   * @param imageHeight the height of the image
   * @param pageWidth the width of the page
   * @param pageHeight the height of the page
   * @return the scale factor to fit the image within the page dimensions
   */
  private static float calculateScale(
      int imageWidth, int imageHeight, float pageWidth, float pageHeight) {
    float sx = pageWidth / imageWidth;
    float sy = pageHeight / imageHeight;
    return Math.min(sx, sy);
  }

  /**
   * Largest image size in pixels that the page holds at {@code targetDpi}. For {@link
   * PageFormat#FIT_TO_IMAGE} the page follows the image's aspect ratio (long side as long as A4),
   * so the limit follows from that page and the DPI too.
   *
   * @return {@code {maxWidth, maxHeight}}
   */
  @VisibleForTesting
  static int[] maxImagePixels(PageFormat pageFormat, int targetDpi, int imageW, int imageH) {
    int effectiveDpi = targetDpi <= 0 ? 300 : targetDpi;
    int[] formatPx = pageFormat.pixelsForDpi(effectiveDpi);
    if (formatPx != null) return formatPx;
    float[] page = pageFormat.toPageSize(imageW, imageH);
    return new int[] {
      Math.max(1, Math.round(page[0] / 72f * effectiveDpi)),
      Math.max(1, Math.round(page[1] / 72f * effectiveDpi))
    };
  }

  /**
   * Whether an image of {@code w x h} must be scaled down to fit {@code maxW x maxH}. A size within
   * one pixel of the limit counts as already scaled (rounding of an earlier pass).
   */
  @VisibleForTesting
  static boolean needsDownscale(int w, int h, int maxW, int maxH) {
    boolean preScaled = Math.abs(w - maxW) <= 1 && Math.abs(h - maxH) <= 1;
    return !preScaled && (w > maxW || h > maxH);
  }

  private static Bitmap processImageForPdf(
      Bitmap original,
      boolean toGray,
      boolean toBw,
      int targetDpi,
      PageFormat pageFormat,
      DocumentCleanupMode cleanupMode) {
    if (original == null) return null;
    if (pageFormat == null) pageFormat = PageFormat.A4;

    int[] maxPx = maxImagePixels(pageFormat, targetDpi, original.getWidth(), original.getHeight());
    int maxW = maxPx[0];
    int maxH = maxPx[1];

    Bitmap base = original;

    if (needsDownscale(original.getWidth(), original.getHeight(), maxW, maxH)) {
      float sw = (float) maxW / original.getWidth();
      float sh = (float) maxH / original.getHeight();
      float scale = Math.min(sw, sh);
      int w = Math.max(1, Math.round(original.getWidth() * scale));
      int h = Math.max(1, Math.round(original.getHeight() * scale));
      base = Bitmap.createScaledBitmap(original, w, h, true);
    }

    PdfImageOutput output = resolvePdfImageOutput(toGray, toBw);

    if (cleanupMode != null && cleanupMode != DocumentCleanupMode.ORIGINAL) {
      DocumentCleanupOptions cleanupOptions = buildCleanupOptions(cleanupMode, output);
      Bitmap pre = DocumentCleanupProcessor.apply(null, base, cleanupOptions);
      if (pre != null) {
        if (base != original) {
          try {
            base.recycle();
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
        }
        base = pre;
      }
    }

    if (output == PdfImageOutput.BLACK_WHITE) {
      Bitmap bw = ImageOps.toBw(base);
      if (base != original) {
        try {
          base.recycle();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
      return bw; // may be null → caller will handle
    }

    if (output == PdfImageOutput.COLOR) return base;

    Bitmap gray = ImageOps.toGray(base);
    if (base != original) {
      try {
        base.recycle();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
    return gray; // may be null → caller will handle
  }

  public static PdfImageOutput resolvePdfImageOutput(boolean toGray, boolean toBw) {
    if (toBw) return PdfImageOutput.BLACK_WHITE;
    if (toGray) return PdfImageOutput.GRAYSCALE;
    return PdfImageOutput.COLOR;
  }

  public static DocumentCleanupOptions buildCleanupOptions(
      DocumentCleanupMode cleanupMode, PdfImageOutput output) {
    DocumentCleanupOptions cleanupOptions = new DocumentCleanupOptions(cleanupMode);
    cleanupOptions.preserveColor = output == PdfImageOutput.COLOR;
    return cleanupOptions;
  }

  /**
   * Returns an opaque copy of the bitmap (no alpha channel) to prevent {@link
   * JPEGFactory#createFromImage} from allocating a large alpha-extraction buffer that can cause
   * {@link OutOfMemoryError}. If the bitmap already has no alpha, returns it as-is.
   */
  private static Bitmap removeAlphaChannel(Bitmap src) {
    if (src == null) return null;
    if (!src.hasAlpha()) return src;
    Bitmap opaque = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.RGB_565);
    android.graphics.Canvas canvas = new android.graphics.Canvas(opaque);
    canvas.drawColor(android.graphics.Color.WHITE);
    canvas.drawBitmap(src, 0f, 0f, null);
    return opaque;
  }

  /**
   * Creates a searchable PDF from the given bitmaps and recognized words per page.
   *
   * @param context the application context
   * @param bitmaps a list of bitmaps representing the pages of the PDF
   * @param perPageWords a list of lists containing recognized words for each page
   * @param outputUri the URI where the generated PDF will be saved
   * @param jpegQuality the JPEG quality for image compression (0-100)
   * @param convertToGrayscale a flag indicating if the images should be converted to grayscale
   * @return the URI of the created searchable PDF
   */
  public static Uri createSearchablePdf(
      Context context,
      List<Bitmap> bitmaps,
      List<List<RecognizedWord>> perPageWords,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale) {
    // Backward-compatible: no BW flag
    return createSearchablePdf(
        context, bitmaps, perPageWords, outputUri, jpegQuality, convertToGrayscale, false);
  }

  /**
   * Creates a searchable PDF file from a list of image bitmaps and their corresponding recognized
   * words.
   *
   * @param context the application context used for accessing system resources
   * @param bitmaps the list of bitmaps representing pages of the PDF
   * @param perPageWords the list of lists, where each inner list contains recognized words for a
   *     corresponding page
   * @param outputUri the URI where the output PDF file will be written
   * @param jpegQuality the quality (0-100) for compressing images in the PDF
   * @param convertToGrayscale whether to convert images to grayscale before including them in the
   *     PDF
   * @param convertToBlackWhite whether to convert images to black-and-white before including them
   *     in the PDF
   * @return the URI of the generated searchable PDF file
   */
  public static Uri createSearchablePdf(
      Context context,
      List<Bitmap> bitmaps,
      List<List<RecognizedWord>> perPageWords,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale,
      boolean convertToBlackWhite) {
    // Default 300 dpi behavior
    return createSearchablePdf(
        context,
        bitmaps,
        perPageWords,
        outputUri,
        jpegQuality,
        convertToGrayscale,
        convertToBlackWhite,
        300,
        null);
  }

  /**
   * Creates a searchable PDF from a collection of bitmap images and their corresponding recognized
   * words.
   *
   * @param context the application context required for resource initialization and file handling
   * @param bitmaps a list of bitmap images representing the pages of the PDF
   * @param perPageWords a list containing recognized words for each page, where each sublist
   *     corresponds to the text on a specific page
   * @param outputUri the URI where the resulting PDF will be saved
   * @param jpegQuality the quality level for encoding images into the PDF as a percentage (0-100);
   *     lower values result in more compression
   * @param convertToGrayscale a flag indicating whether the images should be converted to grayscale
   * @param convertToBlackWhite a flag indicating whether the images should be converted to
   *     black-and-white
   * @param targetDpi the target resolution in dots per inch (DPI) for the PDF pages
   * @param listener a progress listener to receive updates on the processing of pages
   * @return the URI of the generated PDF file, or null if an error occurred
   */
  public static Uri createSearchablePdf(
      Context context,
      List<Bitmap> bitmaps,
      List<List<RecognizedWord>> perPageWords,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale,
      boolean convertToBlackWhite,
      int targetDpi,
      ProgressListener listener) {
    return createSearchablePdf(
        context,
        bitmaps,
        perPageWords,
        outputUri,
        jpegQuality,
        convertToGrayscale,
        convertToBlackWhite,
        targetDpi,
        listener,
        PageFormat.A4);
  }

  /**
   * Creates a searchable PDF from a collection of bitmap images and their corresponding recognized
   * words.
   *
   * @param context the application context required for resource initialization and file handling
   * @param bitmaps a list of bitmap images representing the pages of the PDF
   * @param perPageWords a list containing recognized words for each page
   * @param outputUri the URI where the resulting PDF will be saved
   * @param jpegQuality the quality level for encoding images into the PDF as a percentage (0-100)
   * @param convertToGrayscale a flag indicating whether the images should be converted to grayscale
   * @param convertToBlackWhite a flag indicating whether the images should be converted to
   *     black-and-white
   * @param targetDpi the target resolution in dots per inch (DPI) for the PDF pages
   * @param listener a progress listener to receive updates on the processing of pages
   * @param pageFormat the page format to use for the PDF
   * @return the URI of the generated PDF file, or null if an error occurred
   */
  public static Uri createSearchablePdf(
      Context context,
      List<Bitmap> bitmaps,
      List<List<RecognizedWord>> perPageWords,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale,
      boolean convertToBlackWhite,
      int targetDpi,
      ProgressListener listener,
      PageFormat pageFormat) {
    return createSearchablePdf(
        context,
        bitmaps,
        perPageWords,
        outputUri,
        jpegQuality,
        convertToGrayscale,
        convertToBlackWhite,
        targetDpi,
        listener,
        pageFormat,
        DocumentCleanupMode.ORIGINAL);
  }

  public static Uri createSearchablePdf(
      Context context,
      List<Bitmap> bitmaps,
      List<List<RecognizedWord>> perPageWords,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale,
      boolean convertToBlackWhite,
      int targetDpi,
      ProgressListener listener,
      PageFormat pageFormat,
      DocumentCleanupMode cleanupMode) {
    return createSearchablePdf(
        context,
        bitmaps,
        perPageWords,
        outputUri,
        jpegQuality,
        convertToGrayscale,
        convertToBlackWhite,
        targetDpi,
        listener,
        pageFormat,
        cleanupMode,
        false);
  }

  /**
   * Variant of the multi-page {@code createSearchablePdf} with optional multi-column layout
   * reconstruction (newspapers, magazines). When {@code multiColumn} is {@code true} and the box
   * geometry of a page forms a multi-column layout (see {@link MultiColumnLayoutPolicy}), the
   * invisible text layer of that page is written segment by segment in column reading order.
   *
   * @param multiColumn whether multi-column layout reconstruction is enabled (user option)
   */
  public static Uri createSearchablePdf(
      Context context,
      List<Bitmap> bitmaps,
      List<List<RecognizedWord>> perPageWords,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale,
      boolean convertToBlackWhite,
      int targetDpi,
      ProgressListener listener,
      PageFormat pageFormat,
      DocumentCleanupMode cleanupMode,
      boolean multiColumn) {
    if (bitmaps == null || bitmaps.isEmpty() || outputUri == null) return null;
    // Legacy eager path: wrap the pre-loaded lists into a PageSource. The caller keeps
    // ownership of the bitmaps (they are NOT recycled here), and null entries are skipped
    // defensively — both preserving the historical List<Bitmap> contract.
    PageSource source =
        new PageSource() {
          @Override
          public int getPageCount() {
            return bitmaps.size();
          }

          @Override
          public Bitmap loadBitmap(int index) {
            return bitmaps.get(index);
          }

          @Override
          public List<RecognizedWord> loadWords(int index) {
            return (perPageWords != null && index < perPageWords.size())
                ? perPageWords.get(index)
                : null;
          }

          @Override
          public void releasePage(int index, Bitmap bitmap) {
            // Caller owns the bitmaps in the legacy List-based API; do not recycle.
          }
        };
    return createSearchablePdfStreaming(
        context,
        source,
        outputUri,
        jpegQuality,
        convertToGrayscale,
        convertToBlackWhite,
        targetDpi,
        listener,
        pageFormat,
        cleanupMode,
        /* skipNullBitmaps= */ true,
        multiColumn);
  }

  /**
   * A lazy, page-wise provider for multi-page PDF creation. Implementations load exactly one page
   * bitmap (and its OCR words) at a time so that peak memory depends on a single page instead of
   * the document page count.
   *
   * <p><b>Bitmap ownership contract:</b> {@link PdfCreator} borrows the bitmap returned by {@link
   * #loadBitmap(int)} for the duration of one page render and then calls {@link #releasePage(int,
   * Bitmap)} exactly once for that page (also when rendering fails), on the same thread and before
   * that thread loads another page. {@code PdfCreator} itself never recycles a source bitmap.
   *
   * <p><b>Threads:</b> with more than one export worker (see {@code exportWorkerCount}) the methods
   * are called from several worker threads, for a few pages at a time, so implementations must be
   * thread-safe. With one worker, pages are loaded strictly one after another. The default {@code
   * releasePage} implementation recycles the bitmap; implementations that hand out shared/cached
   * bitmaps must override it to a no-op for those bitmaps to avoid double-recycle.
   */
  public interface PageSource {
    /** Total number of pages to export. */
    int getPageCount();

    /**
     * Loads the full-resolution, upright bitmap for the given page index. Called lazily, in page
     * order per worker thread; see the thread notes above. Returning {@code null} or throwing
     * aborts the export.
     */
    Bitmap loadBitmap(int index) throws Exception;

    /** Loads the OCR words for the given page, or {@code null}/empty for an image-only page. */
    List<RecognizedWord> loadWords(int index);

    /**
     * The page's stored JPEG file when its pixels are exactly what {@link #loadBitmap(int)} would
     * return (no rotation, no in-memory edit), so the export may embed the file without decoding
     * it. When the export does so it skips {@link #loadBitmap(int)} and calls {@link
     * #releasePage(int, Bitmap)} with {@code null}. Default: {@code null} (always decode).
     */
    default File storedJpeg(int index) {
      return null;
    }

    /**
     * Called exactly once after the page has been consumed (or rendering failed). Default
     * implementation recycles the bitmap.
     */
    default void releasePage(int index, Bitmap bitmap) {
      if (bitmap != null && !bitmap.isRecycled()) {
        try {
          bitmap.recycle();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
    }
  }

  /**
   * Creates a searchable multi-page PDF by streaming pages from a {@link PageSource}: each page
   * bitmap is loaded, rendered into the document, and released before the next page is requested.
   * Peak memory therefore depends on a single page, not on the document page count.
   *
   * <p>The export is atomic with respect to page errors: if any page fails to load or render, no
   * output is written to {@code outputUri} and {@code null} is returned (the output stream is only
   * opened after all pages rendered successfully).
   *
   * @return the URI of the generated PDF file, or {@code null} if an error occurred
   */
  public static Uri createSearchablePdf(
      Context context,
      PageSource source,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale,
      boolean convertToBlackWhite,
      int targetDpi,
      ProgressListener listener,
      PageFormat pageFormat,
      DocumentCleanupMode cleanupMode) {
    return createSearchablePdfStreaming(
        context,
        source,
        outputUri,
        jpegQuality,
        convertToGrayscale,
        convertToBlackWhite,
        targetDpi,
        listener,
        pageFormat,
        cleanupMode,
        /* skipNullBitmaps= */ false,
        /* multiColumn= */ false);
  }

  /**
   * Variant of {@link #createSearchablePdf(Context, PageSource, Uri, int, boolean, boolean, int,
   * ProgressListener, PageFormat, DocumentCleanupMode)} with optional multi-column layout
   * reconstruction (see {@link MultiColumnLayoutPolicy}).
   *
   * @param multiColumn whether multi-column layout reconstruction is enabled (user option)
   */
  public static Uri createSearchablePdf(
      Context context,
      PageSource source,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale,
      boolean convertToBlackWhite,
      int targetDpi,
      ProgressListener listener,
      PageFormat pageFormat,
      DocumentCleanupMode cleanupMode,
      boolean multiColumn) {
    return createSearchablePdfStreaming(
        context,
        source,
        outputUri,
        jpegQuality,
        convertToGrayscale,
        convertToBlackWhite,
        targetDpi,
        listener,
        pageFormat,
        cleanupMode,
        /* skipNullBitmaps= */ false,
        multiColumn);
  }

  private static Uri createSearchablePdfStreaming(
      Context context,
      PageSource source,
      Uri outputUri,
      int jpegQuality,
      boolean convertToGrayscale,
      boolean convertToBlackWhite,
      int targetDpi,
      ProgressListener listener,
      PageFormat pageFormat,
      DocumentCleanupMode cleanupMode,
      boolean skipNullBitmaps,
      boolean multiColumn) {
    if (source == null || source.getPageCount() <= 0 || outputUri == null) return null;
    if (pageFormat == null) pageFormat = PageFormat.A4;
    PageOptions options =
        new PageOptions(
            jpegQuality,
            convertToGrayscale,
            convertToBlackWhite,
            targetDpi,
            pageFormat,
            cleanupMode,
            skipNullBitmaps,
            multiColumn);
    int total = source.getPageCount();
    long startMs = android.os.SystemClock.elapsedRealtime();
    int workers = exportWorkerCount(context, total);
    try (OutputStream os = context.getContentResolver().openOutputStream(outputUri)) {
      if (os == null) {
        Log.e(TAG, "createSearchablePdf: openOutputStream returned null");
        return null;
      }
      PdfWriter writer = new PdfWriter(new java.io.BufferedOutputStream(os, 1 << 16), "MakeACopy");
      int embeddedAsStored = renderPages(writer, source, workers, options, listener);
      if (embeddedAsStored < 0) return null;
      if (writer.pageCount() == 0) {
        Log.e(TAG, "createSearchablePdf: no page to write");
        return null;
      }
      writer.finish();
      Log.i(
          TAG,
          "createSearchablePdf: "
              + total
              + " pages, "
              + embeddedAsStored
              + " embedded as stored JPEG, "
              + workers
              + " worker(s), "
              + (android.os.SystemClock.elapsedRealtime() - startMs)
              + " ms");
      return outputUri;
    } catch (Exception e) {
      Log.e(TAG, "Error creating PDF", e);
      return null;
    }
  }

  /** The export settings every page is prepared with. */
  private record PageOptions(
      int jpegQuality,
      boolean toGray,
      boolean toBw,
      int targetDpi,
      PageFormat pageFormat,
      DocumentCleanupMode cleanupMode,
      boolean skipNullBitmaps,
      boolean multiColumn) {}

  /** Upper bound for parallel page preparation; more threads mostly add memory, not speed. */
  private static final int MAX_EXPORT_WORKERS = 3;

  private static volatile int exportWorkersOverride = 0;

  /** Fixes the number of export workers for tests; {@code 0} picks it from the device again. */
  @VisibleForTesting
  public static void setExportWorkersForTesting(int workers) {
    exportWorkersOverride = Math.max(0, workers);
  }

  /**
   * How many threads prepare pages in parallel: one on low-RAM devices and for single pages
   * (strictly sequential, one page bitmap at a time), otherwise up to {@link #MAX_EXPORT_WORKERS}
   * leaving one core for the thread that writes the document.
   */
  private static int exportWorkerCount(Context context, int pageCount) {
    if (exportWorkersOverride > 0) return exportWorkersOverride;
    if (pageCount < 2) return 1;
    try {
      android.app.ActivityManager am =
          (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      if (am != null && am.isLowRamDevice()) return 1;
    } catch (Throwable ignore) {
      // Best-effort; fall through to the CPU count
    }
    int cpus = Runtime.getRuntime().availableProcessors();
    return Math.max(1, Math.min(MAX_EXPORT_WORKERS, cpus - 1));
  }

  /**
   * A page made ready by a worker: the encoded image (JPEG bytes, or a bitmap for lossless output)
   * plus what the text layer needs. {@code skipped} marks a null page of the legacy List API.
   */
  private static final class PreparedPage {
    byte[] jpeg;
    Bitmap lossless;
    int imgW;
    int imgH;
    int srcW;
    int srcH;
    List<RecognizedWord> words;
    boolean fromStoredJpeg;
    boolean skipped;

    void recycle() {
      if (lossless != null && !lossless.isRecycled()) lossless.recycle();
      lossless = null;
      jpeg = null;
    }
  }

  /**
   * Prepares the pages (on {@code workers} threads when more than one, at most {@code workers + 1}
   * pages ahead) and writes them in order.
   *
   * @return the number of pages embedded as their stored JPEG, or -1 on failure
   */
  private static int renderPages(
      PdfWriter writer,
      PageSource source,
      int workers,
      PageOptions options,
      ProgressListener listener)
      throws IOException {
    final int total = source.getPageCount();
    final java.util.concurrent.atomic.AtomicBoolean aborted =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.concurrent.ExecutorService pool =
        workers > 1
            ? java.util.concurrent.Executors.newFixedThreadPool(
                workers,
                r -> {
                  Thread th = new Thread(r, "pdf-page");
                  th.setPriority(Thread.NORM_PRIORITY - 1);
                  return th;
                })
            : null;
    java.util.ArrayDeque<java.util.concurrent.Future<PreparedPage>> inFlight =
        new java.util.ArrayDeque<>();
    int next = 0;
    int embeddedAsStored = 0;
    boolean ok = false;
    try {
      for (int i = 0; i < total; i++) {
        PreparedPage page;
        if (pool == null) {
          try {
            page = preparePage(source, i, aborted, options);
          } catch (Exception e) {
            Log.e(TAG, "Failed to prepare page " + (i + 1), e);
            return -1;
          }
        } else {
          while (next < total && inFlight.size() <= workers) {
            final int index = next++;
            inFlight.add(pool.submit(() -> preparePage(source, index, aborted, options)));
          }
          try {
            page = inFlight.poll().get();
          } catch (java.util.concurrent.ExecutionException e) {
            Log.e(TAG, "Failed to prepare page " + (i + 1), e.getCause());
            return -1;
          }
        }
        try {
          if (!page.skipped) {
            if (!addPreparedPage(writer, page, i, options)) return -1;
            if (page.fromStoredJpeg) embeddedAsStored++;
          }
        } finally {
          page.recycle();
        }
        if (listener != null) {
          try {
            listener.onPageProcessed(i + 1, total);
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
        }
      }
      ok = true;
      return embeddedAsStored;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Log.e(TAG, "Interrupted while preparing pages", e);
      return -1;
    } finally {
      if (pool != null) {
        if (!ok) {
          aborted.set(true);
          for (java.util.concurrent.Future<PreparedPage> f : inFlight) f.cancel(false);
        }
        pool.shutdown();
        try {
          if (!pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.w(TAG, "Page workers did not finish in time");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        if (!ok) {
          for (java.util.concurrent.Future<PreparedPage> f : inFlight) {
            if (f.isDone() && !f.isCancelled()) {
              try {
                PreparedPage p = f.get();
                if (p != null) p.recycle();
              } catch (Exception ignore) {
                // Best-effort; failure is non-critical
              }
            }
          }
        }
      }
    }
  }

  /**
   * Worker side of {@link #renderPagesParallel}: loads, processes and encodes one page without
   * touching the document. The page is released on this thread before it returns.
   */
  private static PreparedPage preparePage(
      PageSource source,
      int index,
      java.util.concurrent.atomic.AtomicBoolean aborted,
      PageOptions options)
      throws Exception {
    final int jpegQuality = options.jpegQuality();
    final boolean toGray = options.toGray();
    final boolean toBw = options.toBw();
    final int targetDpi = options.targetDpi();
    final PageFormat pageFormat = options.pageFormat();
    final DocumentCleanupMode cleanupMode = options.cleanupMode();
    final boolean skipNullBitmaps = options.skipNullBitmaps();
    PreparedPage page = new PreparedPage();
    if (aborted.get()) return page;
    int[] stored =
        storedJpegPassthrough(
            source, index, jpegQuality, toGray, toBw, targetDpi, pageFormat, cleanupMode);
    if (stored != null) {
      try {
        page.jpeg = java.nio.file.Files.readAllBytes(source.storedJpeg(index).toPath());
        page.imgW = page.srcW = stored[0];
        page.imgH = page.srcH = stored[1];
        page.words = loadWordsSafely(source, index);
        page.fromStoredJpeg = true;
        source.releasePage(index, null);
        return page;
      } catch (IOException e) {
        Log.w(TAG, "Reading the stored JPEG failed for page " + (index + 1), e);
        page.jpeg = null;
      }
    }

    Bitmap src = source.loadBitmap(index);
    if (src == null) {
      if (skipNullBitmaps) {
        page.skipped = true;
        return page;
      }
      throw new IOException("PageSource returned null bitmap for page " + (index + 1));
    }
    Bitmap prepared = null;
    try {
      page.words = loadWordsSafely(source, index);
      page.srcW = src.getWidth();
      page.srcH = src.getHeight();
      prepared = processImageForPdf(src, toGray, toBw, targetDpi, pageFormat, cleanupMode);
      if (prepared == null) {
        throw new IOException("Image preparation failed for page " + (index + 1));
      }
      page.imgW = prepared.getWidth();
      page.imgH = prepared.getHeight();
      if (jpegQuality < 100) {
        Bitmap opaque = removeAlphaChannel(prepared);
        try {
          java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 20);
          float q = Math.max(0f, Math.min(1f, jpegQuality / 100f));
          if (!opaque.compress(Bitmap.CompressFormat.JPEG, (int) (q * 100), out)) {
            throw new IOException("JPEG encoding failed for page " + (index + 1));
          }
          page.jpeg = out.toByteArray();
        } finally {
          if (opaque != prepared) opaque.recycle();
        }
      } else {
        // The source bitmap is released below, so lossless output needs its own copy.
        page.lossless = (prepared == src) ? src.copy(Bitmap.Config.ARGB_8888, false) : prepared;
        if (page.lossless == prepared) prepared = null;
      }
    } finally {
      if (prepared != null && prepared != src && !prepared.isRecycled()) prepared.recycle();
      source.releasePage(index, src);
    }
    if (aborted.get()) page.recycle();
    return page;
  }

  /** Writes {@code page}: its image fitted onto the page and the text layer over it. */
  private static boolean addPreparedPage(
      PdfWriter writer, PreparedPage page, int pageIndex, PageOptions options) {
    try {
      PdfWriter.Image image;
      if (page.jpeg != null) {
        try {
          image = PdfWriter.Image.jpeg(page.jpeg);
        } catch (IllegalArgumentException e) {
          if (!page.fromStoredJpeg) throw e;
          Log.w(TAG, "Embedding the stored JPEG failed for page " + (pageIndex + 1), e);
          Bitmap decoded =
              android.graphics.BitmapFactory.decodeByteArray(page.jpeg, 0, page.jpeg.length);
          if (decoded == null) return false;
          try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 20);
            if (!decoded.compress(
                Bitmap.CompressFormat.JPEG, ScanPersister.PAGE_JPEG_QUALITY, out)) {
              return false;
            }
            image = PdfWriter.Image.jpeg(out.toByteArray());
          } finally {
            decoded.recycle();
          }
          page.fromStoredJpeg = false;
        }
      } else {
        int w = page.lossless.getWidth();
        int h = page.lossless.getHeight();
        int[] px = new int[w * h];
        page.lossless.getPixels(px, 0, w, 0, 0, w, h);
        image = PdfWriter.Image.lossless(px, w, h);
      }
      float[] size = options.pageFormat().toPageSize(page.imgW, page.imgH);
      writer.addPage(size[0], size[1], image, pageContent(size, page, options.multiColumn()));
      return true;
    } catch (Exception e) {
      Log.e(TAG, "Error rendering page " + (pageIndex + 1), e);
      return false;
    }
  }

  /**
   * The page's content: the image fitted into the page and centred (letterboxed when the aspect
   * ratios differ), and the text layer in the image's own pixel space via the same transform.
   */
  private static String pageContent(float[] pageSize, PreparedPage page, boolean multiColumn) {
    float pageW = pageSize[0];
    float pageH = pageSize[1];
    float scale = calculateScale(page.imgW, page.imgH, pageW, pageH);
    float drawW = page.imgW * scale;
    float drawH = page.imgH * scale;
    float offsetX = (pageW - drawW) / 2f;
    float offsetY = (pageH - drawH) / 2f;
    StringBuilder c = new StringBuilder();
    c.append("q ")
        .append(PdfWriter.num(drawW))
        .append(" 0 0 ")
        .append(PdfWriter.num(drawH))
        .append(' ')
        .append(PdfWriter.num(offsetX))
        .append(' ')
        .append(PdfWriter.num(offsetY))
        .append(" cm /Im0 Do Q\n");
    List<RecognizedWord> words = page.words;
    if (words == null || words.isEmpty()) return c.toString();
    // OCR boxes are in the source bitmap's pixels; the image may have been scaled since.
    if (page.srcW != page.imgW || page.srcH != page.imgH) {
      float sx = (float) page.imgW / page.srcW;
      float sy = (float) page.imgH / page.srcH;
      List<RecognizedWord> scaled = new ArrayList<>(words.size());
      for (RecognizedWord w : words) {
        scaled.add(w.transform(sx, sy, 0f, 0f).clipTo(page.imgW, page.imgH));
      }
      words = scaled;
    }
    String text = PdfTextLayer.build(words, page.imgW, page.imgH, multiColumn);
    if (text.isEmpty()) return c.toString();
    c.append("q ")
        .append(String.format(java.util.Locale.ROOT, "%.6f", scale))
        .append(" 0 0 ")
        .append(String.format(java.util.Locale.ROOT, "%.6f", scale))
        .append(' ')
        .append(PdfWriter.num(offsetX))
        .append(' ')
        .append(PdfWriter.num(offsetY))
        .append(" cm\n")
        .append(text)
        .append("Q\n");
    return c.toString();
  }

  /** The page's OCR words; a broken or missing OCR payload yields {@code null} (image only). */
  private static List<RecognizedWord> loadWordsSafely(PageSource source, int index) {
    try {
      return source.loadWords(index);
    } catch (Throwable t) {
      Log.w(TAG, "Failed to load OCR words for page " + (index + 1) + "; exporting image only", t);
      return null;
    }
  }

  /**
   * Checks whether the page's stored JPEG can go into the PDF unchanged: color output without
   * cleanup, no downscale for the target DPI, a requested quality at most 5 below the stored one
   * (so the preset's size promise roughly holds), and a plain 8-bit 3-component JPEG (PdfBox tags
   * embedded JPEGs as DeviceRGB).
   *
   * @return {@code {width, height}} of the stored JPEG, or {@code null} when the page must be
   *     decoded and processed
   */
  private static int[] storedJpegPassthrough(
      PageSource source,
      int index,
      int jpegQuality,
      boolean toGray,
      boolean toBw,
      int targetDpi,
      PageFormat pageFormat,
      DocumentCleanupMode cleanupMode) {
    if (toGray || toBw) return null;
    if (cleanupMode != null && cleanupMode != DocumentCleanupMode.ORIGINAL) return null;
    if (jpegQuality >= 100 || jpegQuality < ScanPersister.PAGE_JPEG_QUALITY - 5) return null;
    File jpeg;
    try {
      jpeg = source.storedJpeg(index);
    } catch (Throwable t) {
      return null;
    }
    if (jpeg == null || !jpeg.isFile()) return null;
    int[] size = readPlainJpegSize(jpeg);
    if (size == null) return null;
    int[] maxPx = maxImagePixels(pageFormat, targetDpi, size[0], size[1]);
    if (needsDownscale(size[0], size[1], maxPx[0], maxPx[1])) return null;
    return size;
  }

  /**
   * Reads the frame header of a JPEG file.
   *
   * @return {@code {width, height}} for an 8-bit, 3-component baseline, extended or progressive
   *     JPEG; {@code null} for anything else or on a read error
   */
  @VisibleForTesting
  static int[] readPlainJpegSize(File file) {
    try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(file))) {
      if (in.read() != 0xFF || in.read() != 0xD8) return null;
      while (true) {
        int b = in.read();
        if (b != 0xFF) return null;
        int marker;
        do {
          marker = in.read();
        } while (marker == 0xFF);
        if (marker < 0 || marker == 0xD9 || marker == 0xDA) return null;
        if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue; // no length
        int len = (in.read() << 8) | in.read();
        if (len < 2) return null;
        if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
          int precision = in.read();
          int height = (in.read() << 8) | in.read();
          int width = (in.read() << 8) | in.read();
          int components = in.read();
          if (precision != 8 || components != 3 || width <= 0 || height <= 0) return null;
          return new int[] {width, height};
        }
        if (marker >= 0xC3
            && marker <= 0xCF
            && marker != 0xC4
            && marker != 0xC8
            && marker != 0xCC) {
          return null; // lossless, hierarchical or arithmetic coding
        }
        long skip = len - 2;
        while (skip > 0) {
          long s = in.skip(skip);
          if (s <= 0) return null;
          skip -= s;
        }
      }
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * An interface for listening to progress updates during a multi-step process, such as processing
   * pages, files, or any sequential tasks.
   *
   * <p>Implementations of this interface should provide behavior to handle updates when a single
   * step or unit of work is completed, including information about the progress relative to the
   * total work.
   */
  public interface ProgressListener {
    void onPageProcessed(int pageIndex, int totalPages);
  }
}
