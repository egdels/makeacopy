/*
 * Copyright 2025 Christian Kierdorf
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
import android.graphics.RectF;
import android.net.Uri;
import android.util.Log;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode;
import com.tom_roush.pdfbox.util.Matrix;
import de.schliweb.makeacopy.utils.image.BinarizationUtils;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import de.schliweb.makeacopy.utils.image.DocumentCleanupOptions;
import de.schliweb.makeacopy.utils.image.DocumentCleanupProcessor;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The PdfCreator class provides static methods for generating searchable PDFs from bitmap images
 * and recognized OCR words. It supports advanced options such as grayscale or black-and-white
 * conversion, DPI scaling, and robust font fallback for text rendering. This class allows creation
 * of both single-page and multi-page searchable PDFs.
 */
public class PdfCreator {
  private static final String TAG = "PdfCreator";
  // Text sizing (relative to OCR box height in image space)
  private static final float TEXT_SIZE_RATIO = 0.70f;
  private static final float MIN_FONT_PT = 2f; // lower bound for tiny boxes
  private static final float MIN_LINE_TEXT_SCALE_X = 0.50f;
  private static final float MAX_LINE_TEXT_SCALE_X = 2.00f;

  public enum PdfImageOutput {
    COLOR,
    GRAYSCALE,
    BLACK_WHITE
  }

  public enum TextLayerMode {
    LINE_BASED,
    WORD_POSITIONED
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
   * Creates a searchable PDF document from a bitmap and a list of recognized words. The PDF
   * contains an image representation of the provided bitmap and includes a searchable text layer
   * based on the recognized words. The resulting file is saved to the specified output URI.
   *
   * @param context the Android context, used to access system resources and file utilities
   * @param bitmap the bitmap to include in the PDF as an image
   * @param words a list of recognized words to overlay as a searchable text layer
   * @param outputUri the URI where the generated PDF will be saved
   * @param jpegQuality the compression quality (0-100) for the bitmap image in the PDF
   * @param convertToGrayscale specifies whether the bitmap image should be converted to grayscale
   * @param convertToBlackWhite specifies whether the bitmap image should be converted to
   *     black-and-white
   * @param targetDpi the target DPI (dots per inch) at which the image should be rendered in the
   *     PDF
   * @return the URI of the generated searchable PDF file
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
    // Back-compat: default to ROBUST when BW is requested
    return createSearchablePdf(
        context,
        bitmap,
        words,
        outputUri,
        jpegQuality,
        convertToGrayscale,
        convertToBlackWhite,
        targetDpi,
        convertToBlackWhite ? BwMode.ROBUST : null);
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
   * @param bwMode The black-and-white conversion mode (e.g., robust or simple thresholding).
   *     Ignored if {@code convertToBlackWhite} is false.
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
      BwMode bwMode) {
    return createSearchablePdf(
        context,
        bitmap,
        words,
        outputUri,
        jpegQuality,
        convertToGrayscale,
        convertToBlackWhite,
        targetDpi,
        bwMode,
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
   * @param bwMode The black-and-white conversion mode (e.g., robust or simple thresholding).
   *     Ignored if {@code convertToBlackWhite} is false.
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
      BwMode bwMode,
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
        bwMode,
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
      BwMode bwMode,
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
        bwMode,
        pageFormat,
        cleanupMode,
        TextLayerMode.LINE_BASED);
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
      BwMode bwMode,
      PageFormat pageFormat,
      DocumentCleanupMode cleanupMode,
      TextLayerMode textLayerMode) {
    Log.d(
        TAG,
        "createSearchablePdf: uri="
            + outputUri
            + ", words="
            + (words == null ? 0 : words.size())
            + ", format="
            + pageFormat);
    if (bitmap == null || outputUri == null) return null;
    if (pageFormat == null) pageFormat = PageFormat.A4;
    if (textLayerMode == null) textLayerMode = TextLayerMode.LINE_BASED;

    try {
      PDFBoxResourceLoader.init(context);
      try {
        OpenCVUtils.init(context);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    } catch (Throwable t) {
      Log.e(TAG, "PDFBox init failed", t);
      return null;
    }

    Bitmap prepared = null;
    try {
      // Detect if text contains RTL scripts (Arabic, Persian, Hebrew) for gentle B/W processing
      boolean useGentleMode = convertToBlackWhite && containsRtlText(words);
      prepared =
          processImageForPdf(
              bitmap,
              convertToGrayscale,
              convertToBlackWhite,
              targetDpi,
              bwMode,
              useGentleMode,
              pageFormat,
              cleanupMode);
      if (prepared == null) {
        Log.e(TAG, "Image preparation via OpenCV failed");
        return null;
      }

      try (PDDocument document = new PDDocument()) {
        try {
          document.getDocument().setVersion(1.5f);
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
        document.getDocumentInformation().setCreator("MakeACopy");
        document.getDocumentInformation().setProducer("MakeACopy");

        PDRectangle pageSize =
            pageFormat.toPageRectangle(prepared.getWidth(), prepared.getHeight());
        float pageW = pageSize.getWidth();
        float pageH = pageSize.getHeight();

        PDPage page = new PDPage(pageSize);
        // Harmonize page boxes to avoid viewer-specific cropping/offset interpretations
        try {
          page.setMediaBox(pageSize);
          page.setCropBox(pageSize);
          page.setBleedBox(pageSize);
          page.setTrimBox(pageSize);
          page.setArtBox(pageSize);
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
        document.addPage(page);

        // Fit image into page while preserving aspect ratio (letterboxing if needed)
        float scale = calculateScale(prepared.getWidth(), prepared.getHeight(), pageW, pageH);
        float drawW = prepared.getWidth() * scale;
        float drawH = prepared.getHeight() * scale;
        float offsetX = (pageW - drawW) / 2f;
        float offsetY = (pageH - drawH) / 2f;

        float q = Math.max(0f, Math.min(1f, jpegQuality / 100f));
        // Remove alpha channel before JPEG encoding to avoid OOM in
        // JPEGFactory.createAlphaFromARGBImage which allocates a large ByteArrayOutputStream.
        Bitmap opaqueForJpeg = (jpegQuality < 100) ? removeAlphaChannel(prepared) : null;
        PDImageXObject pdImg;
        try {
          pdImg =
              (jpegQuality < 100)
                  ? JPEGFactory.createFromImage(document, opaqueForJpeg, q)
                  : LosslessFactory.createFromImage(document, prepared);
        } finally {
          if (opaqueForJpeg != null && opaqueForJpeg != prepared) {
            try {
              opaqueForJpeg.recycle();
            } catch (Throwable t) {
              Log.w(TAG, "Failed to recycle opaque bitmap", t);
            }
          }
        }

        // Load embedded fonts with fallbacks (file-based; subset-embedded by default)
        List<PDFont> fonts = loadFontsWithFallbacks(document, context);

        try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
          // 1) Draw image in page coordinates
          cs.drawImage(pdImg, offsetX, offsetY, drawW, drawH);

          // 2) Draw OCR text in the EXACT SAME transform as the image
          if (words != null && !words.isEmpty()) {
            cs.saveGraphicsState();
            cs.transform(new Matrix(scale, 0, 0, scale, offsetX, offsetY)); // identical CTM
            // Normalize OCR boxes from source bitmap space to prepared bitmap space if needed
            List<RecognizedWord> normWords;
            if (bitmap.getWidth() != prepared.getWidth()
                || bitmap.getHeight() != prepared.getHeight()) {
              float sxImg = (float) prepared.getWidth() / (float) bitmap.getWidth();
              float syImg = (float) prepared.getHeight() / (float) bitmap.getHeight();
              normWords = new ArrayList<>(words.size());
              for (RecognizedWord w : words) {
                normWords.add(
                    w.transform(sxImg, syImg, 0f, 0f)
                        .clipTo(prepared.getWidth(), prepared.getHeight()));
              }
            } else {
              normWords = words;
            }
            Log.d(TAG, "createSearchablePdf: " + normWords.size() + " OCR words");
            // now output text in IMAGE coordinates (0..imgW / 0..imgH)
            addTextLayerImageSpace(
                cs, normWords, fonts, prepared.getWidth(), prepared.getHeight(), textLayerMode);
            cs.restoreGraphicsState();
          }
        }

        try (OutputStream os = context.getContentResolver().openOutputStream(outputUri)) {
          if (os == null) {
            Log.e(TAG, "createSearchablePdf: openOutputStream returned null");
            return null;
          }
          document.save(os);
        }
        return outputUri;
      }
    } catch (Exception e) {
      Log.e(TAG, "Error creating PDF", e);
      return null;
    } finally {
      if (prepared != null && prepared != bitmap) {
        try {
          prepared.recycle();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
    }
  }

  private static List<PDFont> loadFontsWithFallbacks(PDDocument document, Context context) {
    return PdfTextUtils.loadFontsWithFallbacks(document, context);
  }

  private static void showTextWithFallbacks(
      PDPageContentStream cs, String token, float fontSize, List<PDFont> fonts) throws Exception {
    PdfTextUtils.showTextWithFallbacks(cs, token, fontSize, fonts);
  }

  /**
   * Adds a text layer to the PDF page content stream using image-space coordinates. This method
   * processes a list of recognized words, validates their bounding boxes, clusters them into lines,
   * and renders them as invisible but selectable text on the PDF document.
   *
   * @param cs The content stream of the PDF page where the text layer will be added.
   * @param words The list of recognized words that contain text and their respective bounding
   *     boxes.
   * @param fonts A list of fonts used to render the text. The method handles font fallbacks if
   *     necessary.
   * @param imageWidth The width of the image, used to clamp and transform bounding box coordinates.
   * @param imageHeight The height of the image, used to clamp and transform bounding box
   *     coordinates.
   * @throws Exception If there is an error while adding the text layer to the content stream.
   */
  private static void addTextLayerImageSpace(
      PDPageContentStream cs,
      List<RecognizedWord> words,
      List<PDFont> fonts,
      int imageWidth,
      int imageHeight,
      TextLayerMode textLayerMode)
      throws Exception {
    if (words == null || words.isEmpty()) return;
    if (textLayerMode == null) textLayerMode = TextLayerMode.LINE_BASED;

    // --- 1) Clean and validate ---
    List<RecognizedWord> clean = new ArrayList<>(words.size());
    for (RecognizedWord w : words) {
      if (w == null || w.getBoundingBox() == null) continue;
      RectF b = new RectF(w.getBoundingBox());

      // Replace NaN/Inf with 0
      if (!Float.isFinite(b.left)) b.left = 0f;
      if (!Float.isFinite(b.top)) b.top = 0f;
      if (!Float.isFinite(b.right)) b.right = 0f;
      if (!Float.isFinite(b.bottom)) b.bottom = 0f;

      // Ensure normal ordering
      if (b.right < b.left) {
        float t = b.left;
        b.left = b.right;
        b.right = t;
      }
      if (b.bottom < b.top) {
        float t = b.top;
        b.top = b.bottom;
        b.bottom = t;
      }

      // Clip to image bounds
      b.left = clamp(b.left, 0f, imageWidth);
      b.right = clamp(b.right, 0f, imageWidth);
      b.top = clamp(b.top, 0f, imageHeight);
      b.bottom = clamp(b.bottom, 0f, imageHeight);

      // Discard if too small/empty
      if (b.width() <= 0.5f || b.height() <= 0.5f) continue;

      // Use a copy with the clipped rect
      clean.add(new RecognizedWord(w.getText(), b, w.getConfidence()));
    }
    if (clean.isEmpty()) return;

    // --- 1b) Split off vertical CJK columns (issue #88) ---
    // PaddleOCR emits vertical Japanese columns as one word per column with a tall, narrow box
    // and the text in logical top-to-bottom order. They must not fall into the horizontal
    // Y-center line clustering below (which would merge all columns into one left-to-right
    // "line" and derive a huge font size from the column height). Columns are rendered as
    // rotated text runs in right-to-left order; all other words use the unchanged paths.
    List<RecognizedWord> verticalColumns = new ArrayList<>();
    for (int i = clean.size() - 1; i >= 0; i--) {
      RecognizedWord w = clean.get(i);
      RectF b = w.getBoundingBox();
      if (PdfVerticalTextUtils.isVerticalCjkColumn(w.getText(), b.width(), b.height())) {
        verticalColumns.add(w);
        clean.remove(i);
      }
    }
    if (!verticalColumns.isEmpty()) {
      verticalColumns.sort(
          (a, b) ->
              PdfVerticalTextUtils.compareColumnsRightToLeft(
                  a.getBoundingBox().centerX(), b.getBoundingBox().centerX()));
      addVerticalColumnsImageSpace(cs, verticalColumns, fonts, imageWidth, imageHeight);
    }
    if (clean.isEmpty()) return;

    // Compute dynamic tolerance based on median word height.
    // Words within this Y-distance are considered on the same line.
    final float lineToleranceY = calculateLineToleranceY(clean);

    // Stable tie-breaker: original index
    java.util.IdentityHashMap<RecognizedWord, Integer> idx = new java.util.IdentityHashMap<>();
    for (int i = 0; i < clean.size(); i++) idx.put(clean.get(i), i);

    // --- 2) Sort words by Y-center first, then by X (left-to-right) ---
    // This ensures we process words top-to-bottom, left-to-right
    clean.sort(
        (a, b) -> {
          float yCenterA = (a.getBoundingBox().top + a.getBoundingBox().bottom) * 0.5f;
          float yCenterB = (b.getBoundingBox().top + b.getBoundingBox().bottom) * 0.5f;
          int c = Float.compare(yCenterA, yCenterB);
          if (c != 0) return c;
          c = Float.compare(a.getBoundingBox().left, b.getBoundingBox().left);
          if (c != 0) return c;
          return Integer.compare(idx.get(a), idx.get(b));
        });

    // --- 3) Cluster into lines using proximity-based grouping ---
    // Words are on the same line if their Y-centers are within lineToleranceY
    // of the line's reference Y (first word's Y-center in that line)
    List<List<RecognizedWord>> lines = new ArrayList<>();
    for (RecognizedWord w : clean) {
      float yCenter = (w.getBoundingBox().top + w.getBoundingBox().bottom) * 0.5f;

      // Try to find an existing line where this word fits
      boolean added = false;
      for (List<RecognizedWord> line : lines) {
        if (line.isEmpty()) continue;
        // Use the average Y-center of the line for comparison
        float lineYSum = 0f;
        for (RecognizedWord lw : line) {
          lineYSum += (lw.getBoundingBox().top + lw.getBoundingBox().bottom) * 0.5f;
        }
        float lineYAvg = lineYSum / line.size();

        if (Math.abs(yCenter - lineYAvg) <= lineToleranceY) {
          line.add(w);
          added = true;
          break;
        }
      }

      if (!added) {
        // Start a new line
        List<RecognizedWord> newLine = new ArrayList<>();
        newLine.add(w);
        lines.add(newLine);
      }
    }

    // Sort lines by their average Y position (top to bottom)
    lines.sort(
        (lineA, lineB) -> {
          float avgYA = 0f, avgYB = 0f;
          for (RecognizedWord w : lineA)
            avgYA += (w.getBoundingBox().top + w.getBoundingBox().bottom) * 0.5f;
          for (RecognizedWord w : lineB)
            avgYB += (w.getBoundingBox().top + w.getBoundingBox().bottom) * 0.5f;
          avgYA /= lineA.size();
          avgYB /= lineB.size();
          return Float.compare(avgYA, avgYB);
        });

    if (textLayerMode == TextLayerMode.WORD_POSITIONED) {
      addWordPositionedTextLayerImageSpace(cs, lines, fonts, imageWidth, imageHeight, idx);
      return;
    }

    // --- 4) Render lines in logical reading order (for correct copy/paste and search) ---
    // Each OCR line is written as one text run and horizontally scaled to the OCR line box.
    // For RTL scripts: words must be written in logical order (first word of sentence first),
    // which means sorting by X position from RIGHT to LEFT (rightmost word = first in sentence).
    // For LTR scripts: words are sorted LEFT to RIGHT (leftmost word = first in sentence).
    for (List<RecognizedWord> line : lines) {
      if (line.isEmpty()) continue;

      // Detect if this line is predominantly RTL (Arabic/Persian/Hebrew)
      boolean isRtl = isRtlLine(line);

      // Sort words in LOGICAL reading order:
      // - RTL: rightmost word first (it's the first word of the sentence)
      // - LTR: leftmost word first (it's the first word of the sentence)
      line.sort(
          (a, b) -> {
            int c;
            if (isRtl) {
              // RTL: sort right→left (descending X) for logical order
              // The rightmost word is the FIRST word in the sentence
              c = Float.compare(b.getBoundingBox().left, a.getBoundingBox().left);
            } else {
              // LTR: sort left→right (ascending X) for logical order
              c = Float.compare(a.getBoundingBox().left, b.getBoundingBox().left);
            }
            if (c != 0) return c;
            c = Float.compare(a.getBoundingBox().top, b.getBoundingBox().top);
            if (c != 0) return c;
            return Integer.compare(idx.get(a), idx.get(b));
          });

      float medianH = medianHeight(line); // px in image
      float fontSize = Math.max(MIN_FONT_PT, medianH * TEXT_SIZE_RATIO);

      StringBuilder lineText = new StringBuilder();
      RecognizedWord anchor = null;
      for (RecognizedWord w : line) {
        String tokenRaw = safeText(w.getText()).trim();
        if (tokenRaw.isEmpty()) continue;
        if (anchor == null) anchor = w;
        if (lineText.length() > 0) lineText.append(' ');

        // PDFBox writes the complete line as one positioned PDF text run. Positioning every word
        // independently makes some PDF extractors interpret the visual gaps as multiple spaces or
        // line breaks. For RTL scripts the encoded glyph order still needs PDF-specific reordering,
        // isolated from the logical text used by OCRFragment/OCRReview/TXT export.
        lineText.append(PdfTextUtils.reorderRtlForPdf(tokenRaw));
      }
      if (anchor == null || lineText.length() == 0) continue;

      String token =
          java.text.Normalizer.normalize(lineText.toString(), java.text.Normalizer.Form.NFC);

      RectF lineBox = getLineBoundingBox(line);
      float boxH = lineBox.height();
      float targetTextWidth = Math.max(1f, lineBox.width());
      float measuredTextWidth = measureTextWidth(token, fontSize, fonts);
      float scaleX =
          measuredTextWidth > 0f
              ? clamp(
                  targetTextWidth / measuredTextWidth, MIN_LINE_TEXT_SCALE_X, MAX_LINE_TEXT_SCALE_X)
              : 1f;

      // Baseline inside the OCR line box (image Y increases downward). Placing it below the box
      // makes the selectable text layer hang under the visible scan text.
      float baselineImgY = lineBox.bottom - boxH * 0.20f;

      // Invert Y (image space → PDF Y-up)
      float x_img = clamp(lineBox.left, 0f, imageWidth);
      float y_img = clamp((imageHeight - baselineImgY), 0f, imageHeight);

      cs.beginText();
      try {
        cs.setRenderingMode(RenderingMode.NEITHER); // unsichtbar aber auswählbar
        cs.setTextMatrix(new Matrix(scaleX, 0f, 0f, 1f, x_img, y_img));
        showTextWithFallbacks(cs, token, fontSize, fonts);
      } finally {
        cs.endText();
      }
    }
  }

  /**
   * Renders vertical CJK columns (issue #88) as invisible, selectable text runs.
   *
   * <p>Each column is written as one text object whose text matrix rotates the writing direction by
   * -90° (down the page). The characters therefore stay in logical top-to-bottom Unicode order in
   * the content stream, so extraction/search/copy yield the correct text, while the glyph positions
   * overlay the visible vertical column. The caller passes the columns already sorted right-to-left
   * (Japanese vertical reading order).
   *
   * @param cs the content stream (image-space CTM already applied)
   * @param columns the vertical column words, sorted right-to-left
   * @param fonts fallback font list
   * @param imageWidth image width in pixels
   * @param imageHeight image height in pixels
   * @throws Exception if writing to the content stream fails
   */
  private static void addVerticalColumnsImageSpace(
      PDPageContentStream cs,
      List<RecognizedWord> columns,
      List<PDFont> fonts,
      int imageWidth,
      int imageHeight)
      throws Exception {
    for (RecognizedWord w : columns) {
      String tokenRaw = safeText(w.getText()).trim();
      if (tokenRaw.isEmpty()) continue;
      String token = java.text.Normalizer.normalize(tokenRaw, java.text.Normalizer.Form.NFC);

      RectF b = w.getBoundingBox();
      float fontSize = PdfVerticalTextUtils.columnFontSize(b.width());
      float measured = measureTextWidth(token, fontSize, fonts);
      float[] m =
          PdfVerticalTextUtils.columnTextMatrix(
              b.left, b.top, b.height(), measured, imageWidth, imageHeight);

      cs.beginText();
      try {
        cs.setRenderingMode(RenderingMode.NEITHER); // invisible but selectable
        cs.setTextMatrix(new Matrix(m[0], m[1], m[2], m[3], m[4], m[5]));
        showTextWithFallbacks(cs, token, fontSize, fonts);
      } finally {
        cs.endText();
      }
    }
  }

  private static void addWordPositionedTextLayerImageSpace(
      PDPageContentStream cs,
      List<List<RecognizedWord>> lines,
      List<PDFont> fonts,
      int imageWidth,
      int imageHeight,
      java.util.IdentityHashMap<RecognizedWord, Integer> idx)
      throws Exception {
    for (List<RecognizedWord> line : lines) {
      if (line == null || line.isEmpty()) continue;
      boolean isRtl = isRtlLine(line);
      line.sort(
          (a, b) -> {
            int c =
                isRtl
                    ? Float.compare(b.getBoundingBox().left, a.getBoundingBox().left)
                    : Float.compare(a.getBoundingBox().left, b.getBoundingBox().left);
            if (c != 0) return c;
            c = Float.compare(a.getBoundingBox().top, b.getBoundingBox().top);
            if (c != 0) return c;
            return Integer.compare(idx.get(a), idx.get(b));
          });

      float medianH = medianHeight(line);
      float fontSize = Math.max(MIN_FONT_PT, medianH * TEXT_SIZE_RATIO);

      for (RecognizedWord w : line) {
        String tokenRaw = safeText(w.getText());
        if (tokenRaw.trim().isEmpty()) continue;
        String token =
            java.text.Normalizer.normalize(
                PdfTextUtils.reorderRtlForPdf(tokenRaw) + " ", java.text.Normalizer.Form.NFC);
        RectF b = w.getBoundingBox();
        float boxH = b.height();
        float baselineImgY = b.bottom + boxH * 0.25f;
        float x_img = clamp(b.left, 0f, imageWidth);
        float y_img = clamp((imageHeight - baselineImgY), 0f, imageHeight);

        cs.beginText();
        try {
          cs.setRenderingMode(RenderingMode.NEITHER);
          cs.setTextMatrix(Matrix.getTranslateInstance(x_img, y_img));
          showTextWithFallbacks(cs, token, fontSize, fonts);
        } finally {
          cs.endText();
        }
      }
    }
  }

  private static RectF getLineBoundingBox(List<RecognizedWord> line) {
    RectF result = null;
    for (RecognizedWord w : line) {
      if (w == null || w.getBoundingBox() == null || safeText(w.getText()).trim().isEmpty()) {
        continue;
      }
      RectF b = w.getBoundingBox();
      if (result == null) {
        result = new RectF(b);
      } else {
        result.left = Math.min(result.left, b.left);
        result.top = Math.min(result.top, b.top);
        result.right = Math.max(result.right, b.right);
        result.bottom = Math.max(result.bottom, b.bottom);
      }
    }
    return result != null ? result : new RectF(0f, 0f, 1f, 1f);
  }

  private static float measureTextWidth(String text, float fontSize, List<PDFont> fonts) {
    if (text == null || text.isEmpty() || fonts == null || fonts.isEmpty()) return 0f;

    float width = 0f;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      String ch = new String(Character.toChars(cp));
      width += measureCharacterWidth(ch, fontSize, fonts);
      i += Character.charCount(cp);
    }
    return width;
  }

  private static float measureCharacterWidth(String ch, float fontSize, List<PDFont> fonts) {
    for (PDFont font : fonts) {
      try {
        font.encode(ch);
        return font.getStringWidth(ch) / 1000f * fontSize;
      } catch (Exception ignore) {
        // Try the next fallback font, matching showTextWithFallbacks behavior.
      }
    }
    return fontSize * 0.5f;
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
   * Processes an image to prepare it for inclusion in a PDF by resizing, and optionally converting
   * it to grayscale or black and white (BW) based on the parameters provided.
   *
   * @param original The original Bitmap image to process. Must not be null.
   * @param toGray If true, the image will be converted to grayscale.
   * @param toBw If true, the image will be converted to black and white. The conversion respects
   *     the specified bwMode. If both toGray and toBw are true, the image will be converted to
   *     black and white.
   * @param targetDpi The target DPI (dots per inch) for the processed image. Determines the
   *     resizing scale for the image. If targetDpi is less than or equal to 0, a default DPI of 300
   *     will be used.
   * @param bwMode The mode for black-and-white conversion. If null, the default "ROBUST" mode will
   *     be used. Other modes determine the specific method of B&W processing.
   * @param gentleMode If true, uses gentle B/W processing that preserves fine strokes and
   *     diacritics (important for Arabic, Persian, Hebrew scripts).
   * @return A processed Bitmap object resized to match the target DPI dimensions and optionally
   *     converted to grayscale or black and white. Returns null if the original Bitmap is null or
   *     if an error occurs during conversion.
   */
  @SuppressWarnings("UnusedMethod") // called from createPdf and multi-page export
  private static Bitmap processImageForPdf(
      Bitmap original,
      boolean toGray,
      boolean toBw,
      int targetDpi,
      BwMode bwMode,
      boolean gentleMode) {
    return processImageForPdf(original, toGray, toBw, targetDpi, bwMode, gentleMode, PageFormat.A4);
  }

  /**
   * Processes an image to prepare it for inclusion in a PDF by resizing, and optionally converting
   * it to grayscale or black and white (BW) based on the parameters provided.
   *
   * @param original The original Bitmap image to process. Must not be null.
   * @param toGray If true, the image will be converted to grayscale.
   * @param toBw If true, the image will be converted to black and white.
   * @param targetDpi The target DPI (dots per inch) for the processed image.
   * @param bwMode The mode for black-and-white conversion.
   * @param gentleMode If true, uses gentle B/W processing that preserves fine strokes and
   *     diacritics.
   * @param pageFormat The page format determining the maximum pixel dimensions for scaling.
   * @return A processed Bitmap object, or null if an error occurs.
   */
  private static Bitmap processImageForPdf(
      Bitmap original,
      boolean toGray,
      boolean toBw,
      int targetDpi,
      BwMode bwMode,
      boolean gentleMode,
      PageFormat pageFormat) {
    return processImageForPdf(
        original,
        toGray,
        toBw,
        targetDpi,
        bwMode,
        gentleMode,
        pageFormat,
        DocumentCleanupMode.ORIGINAL);
  }

  private static Bitmap processImageForPdf(
      Bitmap original,
      boolean toGray,
      boolean toBw,
      int targetDpi,
      BwMode bwMode,
      boolean gentleMode,
      PageFormat pageFormat,
      DocumentCleanupMode cleanupMode) {
    if (original == null) return null;
    if (pageFormat == null) pageFormat = PageFormat.A4;

    int effectiveDpi = targetDpi <= 0 ? 300 : targetDpi;
    int[] formatPx = pageFormat.pixelsForDpi(effectiveDpi);
    int maxW, maxH;
    if (formatPx != null) {
      maxW = formatPx[0];
      maxH = formatPx[1];
    } else {
      // FIT_TO_IMAGE: no fixed-format constraint, but cap at a sensible maximum
      // to avoid OOM when very large images are passed through without downscaling.
      int fitCap =
          Math.round(effectiveDpi / 72f * 14400); // ~14400pt ≈ 200in at 72dpi → 60000px@300dpi
      maxW = Math.min(original.getWidth(), fitCap);
      maxH = Math.min(original.getHeight(), fitCap);
    }

    boolean preScaled =
        Math.abs(original.getWidth() - maxW) <= 1 && Math.abs(original.getHeight() - maxH) <= 1;

    Bitmap base = original;

    if (!preScaled) {
      float scale = 1f;
      if (original.getWidth() > maxW || original.getHeight() > maxH) {
        float sw = (float) maxW / original.getWidth();
        float sh = (float) maxH / original.getHeight();
        scale = Math.min(sw, sh);
      }

      if (scale < 1f) {
        int w = Math.max(1, Math.round(original.getWidth() * scale));
        int h = Math.max(1, Math.round(original.getHeight() * scale));
        base = Bitmap.createScaledBitmap(original, w, h, true);
      }
    }

    PdfImageOutput output = resolvePdfImageOutput(toGray, toBw);

    if (cleanupMode != null
        && cleanupMode != DocumentCleanupMode.ORIGINAL
        && !(output == PdfImageOutput.BLACK_WHITE
            && cleanupMode == DocumentCleanupMode.CLEAN_TEXT)) {
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
      Bitmap viaCv = applyPdfBwCleanup(base, bwMode, targetDpi, gentleMode);
      if (base != original) {
        try {
          base.recycle();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
      return viaCv; // may be null → caller will handle
    }

    if (output == PdfImageOutput.COLOR) return base;

    Bitmap viaCvGray = OpenCVUtils.toGray(base);
    if (base != original) {
      try {
        base.recycle();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
    return viaCvGray; // may be null → caller will handle
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
    cleanupOptions.optimizeForOcr =
        output == PdfImageOutput.BLACK_WHITE && cleanupMode != DocumentCleanupMode.CLEAN_TEXT;
    return cleanupOptions;
  }

  private static Bitmap applyPdfBwCleanup(
      Bitmap base, BwMode bwMode, int targetDpi, boolean gentleMode) {
    if (bwMode == null || bwMode == BwMode.ROBUST) {
      BinarizationUtils.BwOptions opt = new BinarizationUtils.BwOptions();
      opt.gentleMode = gentleMode;
      opt.targetDpi = targetDpi > 0 ? targetDpi : 300;
      return OpenCVUtils.toBw(base, opt);
    }
    BinarizationUtils.BwOptions opt = new BinarizationUtils.BwOptions();
    opt.mode = BinarizationUtils.BwOptions.Mode.OTSU_ONLY;
    opt.useClahe = true;
    opt.removeShadows = true;
    opt.gentleMode = gentleMode;
    opt.targetDpi = targetDpi > 0 ? targetDpi : 300;
    return OpenCVUtils.toBw(base, opt);
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

  private static float medianHeight(List<RecognizedWord> line) {
    List<Float> heights = new ArrayList<>();
    for (RecognizedWord w : line) heights.add(w.getBoundingBox().height());
    Collections.sort(heights);
    int n = heights.size();
    if (n == 0) return 0;
    if (n % 2 == 1) return heights.get(n / 2);
    return (heights.get(n / 2 - 1) + heights.get(n / 2)) / 2f;
  }

  static float calculateLineToleranceY(List<RecognizedWord> words) {
    float medianH = medianHeight(words);
    return Math.max(4f, medianH * 0.45f);
  }

  /**
   * Ensures that the given text is sanitized by replacing all control characters, except for
   * carriage return, new line, and tab, with a space.
   *
   * @param t the input text to sanitize; can be null.
   * @return the sanitized text, or an empty string if the input is null.
   */
  private static String safeText(String t) {
    if (t == null) return "";
    return t.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ");
  }

  /**
   * Restricts a given value to lie within the specified minimum and maximum bounds.
   *
   * @param v The value to be clamped.
   * @param min The minimum bound to which the value can be clamped.
   * @param max The maximum bound to which the value can be clamped.
   * @return The clamped value, lying between the specified minimum and maximum bounds.
   */
  private static float clamp(float v, float min, float max) {
    return Math.max(min, Math.min(max, v));
  }

  private static boolean isRtlLine(List<RecognizedWord> line) {
    return PdfTextUtils.isRtlLine(line);
  }

  private static boolean containsRtlText(List<RecognizedWord> words) {
    return PdfTextUtils.containsRtlText(words);
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
   * Creates a searchable PDF document from the given list of bitmaps and recognized text data. The
   * resulting PDF is written to the specified output URI. Options such as grayscale conversion,
   * black-and-white conversion, JPEG quality, and target DPI are supported for customization.
   *
   * @param context The application context, required for accessing resources and file paths.
   * @param bitmaps A list of bitmap images representing the pages of the document.
   * @param perPageWords A list containing recognized words for each corresponding bitmap page.
   * @param outputUri The URI where the generated PDF will be saved.
   * @param jpegQuality The quality of JPEG compression (0-100) for the bitmap images.
   * @param convertToGrayscale If true, the bitmaps will be converted to grayscale before addition
   *     to the PDF.
   * @param convertToBlackWhite If true, the bitmaps will be converted to black-and-white using the
   *     default mode.
   * @param targetDpi The target resolution in DPI for the bitmap images in the PDF.
   * @param listener A listener to track and report the progress of the PDF creation process.
   * @return The URI of the generated searchable PDF.
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
    // Back-compat: default to ROBUST when BW is requested
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
        convertToBlackWhite ? BwMode.ROBUST : null);
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
   * @param bwMode the black-and-white processing mode to be used if convertToBlackWhite is true
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
      BwMode bwMode) {
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
        bwMode,
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
   * @param bwMode the black-and-white processing mode
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
      BwMode bwMode,
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
        bwMode,
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
      BwMode bwMode,
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
        bwMode,
        pageFormat,
        cleanupMode,
        TextLayerMode.LINE_BASED);
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
      BwMode bwMode,
      PageFormat pageFormat,
      DocumentCleanupMode cleanupMode,
      TextLayerMode textLayerMode) {
    if (bitmaps == null || bitmaps.isEmpty() || outputUri == null) return null;
    if (pageFormat == null) pageFormat = PageFormat.A4;
    if (textLayerMode == null) textLayerMode = TextLayerMode.LINE_BASED;
    try {
      PDFBoxResourceLoader.init(context);
      try {
        OpenCVUtils.init(context);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    } catch (Throwable t) {
      Log.e(TAG, "PDFBox init failed", t);
      return null;
    }
    try (PDDocument document = new PDDocument()) {
      try {
        document.getDocument().setVersion(1.5f);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      document.getDocumentInformation().setCreator("MakeACopy");
      document.getDocumentInformation().setProducer("MakeACopy");

      // Load fonts once (file-based; subset-embedded)
      List<PDFont> fonts = loadFontsWithFallbacks(document, context);

      int total = bitmaps.size();
      for (int i = 0; i < bitmaps.size(); i++) {
        Bitmap src = bitmaps.get(i);
        if (src == null) {
          if (listener != null)
            try {
              listener.onPageProcessed(i + 1, total);
            } catch (Throwable ignore) {
              // Best-effort; failure is non-critical
            }
          continue; // skip nulls defensively
        }
        Bitmap prepared = null;
        try {
          // Detect if text contains RTL scripts for gentle B/W processing
          List<RecognizedWord> pageWords =
              (perPageWords != null && i < perPageWords.size()) ? perPageWords.get(i) : null;
          boolean useGentleMode = convertToBlackWhite && containsRtlText(pageWords);
          prepared =
              processImageForPdf(
                  src,
                  convertToGrayscale,
                  convertToBlackWhite,
                  targetDpi,
                  bwMode,
                  useGentleMode,
                  pageFormat,
                  cleanupMode);
          if (prepared == null) {
            Log.e(TAG, "Image preparation via OpenCV failed for page " + (i + 1));
            return null;
          }

          PDRectangle pageSize =
              pageFormat.toPageRectangle(prepared.getWidth(), prepared.getHeight());
          float pageW = pageSize.getWidth();
          float pageH = pageSize.getHeight();

          PDPage page = new PDPage(pageSize);
          // Harmonize page boxes to avoid viewer-specific cropping/offset interpretations
          try {
            page.setMediaBox(pageSize);
            page.setCropBox(pageSize);
            page.setBleedBox(pageSize);
            page.setTrimBox(pageSize);
            page.setArtBox(pageSize);
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
          document.addPage(page);

          float scale = calculateScale(prepared.getWidth(), prepared.getHeight(), pageW, pageH);
          float drawW = prepared.getWidth() * scale;
          float drawH = prepared.getHeight() * scale;
          float offsetX = (pageW - drawW) / 2f;
          float offsetY = (pageH - drawH) / 2f;

          float q = Math.max(0f, Math.min(1f, jpegQuality / 100f));
          // Remove alpha channel before JPEG encoding to avoid OOM in
          // JPEGFactory.createAlphaFromARGBImage.
          Bitmap opaqueForJpeg = (jpegQuality < 100) ? removeAlphaChannel(prepared) : null;
          PDImageXObject pdImg;
          try {
            pdImg =
                (jpegQuality < 100)
                    ? JPEGFactory.createFromImage(document, opaqueForJpeg, q)
                    : LosslessFactory.createFromImage(document, prepared);
          } finally {
            if (opaqueForJpeg != null && opaqueForJpeg != prepared) {
              try {
                opaqueForJpeg.recycle();
              } catch (Throwable t) {
                Log.w(TAG, "Failed to recycle opaque bitmap", t);
              }
            }
          }

          try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
            cs.drawImage(pdImg, offsetX, offsetY, drawW, drawH);
            List<RecognizedWord> words =
                (perPageWords != null && i < perPageWords.size()) ? perPageWords.get(i) : null;
            if (words != null && !words.isEmpty()) {
              cs.saveGraphicsState();
              cs.transform(new Matrix(scale, 0, 0, scale, offsetX, offsetY));
              // Normalize OCR boxes from source bitmap space to prepared bitmap space if needed
              List<RecognizedWord> normWords;
              if (src.getWidth() != prepared.getWidth()
                  || src.getHeight() != prepared.getHeight()) {
                float sxImg = (float) prepared.getWidth() / (float) src.getWidth();
                float syImg = (float) prepared.getHeight() / (float) src.getHeight();
                normWords = new ArrayList<>(words.size());
                for (RecognizedWord w : words) {
                  normWords.add(
                      w.transform(sxImg, syImg, 0f, 0f)
                          .clipTo(prepared.getWidth(), prepared.getHeight()));
                }
              } else {
                normWords = words;
              }
              addTextLayerImageSpace(
                  cs, normWords, fonts, prepared.getWidth(), prepared.getHeight(), textLayerMode);
              cs.restoreGraphicsState();
            }
          }
          if (listener != null) {
            try {
              listener.onPageProcessed(i + 1, total);
            } catch (Throwable ignore) {
              // Best-effort; failure is non-critical
            }
          }
        } catch (Exception e) {
          Log.e(TAG, "Error rendering page " + (i + 1), e);
          return null;
        } finally {
          if (prepared != null && prepared != src) {
            try {
              prepared.recycle();
            } catch (Throwable ignore) {
              // Best-effort; failure is non-critical
            }
          }
        }
      }

      try (OutputStream os = context.getContentResolver().openOutputStream(outputUri)) {
        if (os == null) {
          Log.e(TAG, "createSearchablePdf(multi): openOutputStream returned null");
          return null;
        }
        document.save(os);
      }
      return outputUri;
    } catch (Exception e) {
      Log.e(TAG, "Error creating multi-page PDF", e);
      return null;
    }
  }

  public enum BwMode {
    ROBUST,
    CLASSIC
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
