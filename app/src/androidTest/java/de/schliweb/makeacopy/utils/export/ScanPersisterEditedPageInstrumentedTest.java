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

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import de.schliweb.makeacopy.data.CompletedScansRegistry;
import de.schliweb.makeacopy.data.RegistryCleaner;
import de.schliweb.makeacopy.data.library.OcrTextExtractor;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.ui.ocr.review.store.OcrJsonStore;
import de.schliweb.makeacopy.utils.ocr.PageOcrFiles;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The OCR artifacts of a page on real device storage: a re-crop ({@link
 * ScanPersister#persistEditedPage}) must drop every OCR result of the old image, including the
 * editable page.ocr.json that the export and the library read first, and a new OCR or Review result
 * ({@link PageOcrFiles#write}) must win over an older page.ocr.json.
 */
@RunWith(AndroidJUnit4.class)
public class ScanPersisterEditedPageInstrumentedTest {

  private final List<String> createdScanIds = new ArrayList<>();

  private Context ctx() {
    return ApplicationProvider.getApplicationContext();
  }

  @After
  public void tearDown() {
    for (String id : createdScanIds) {
      try {
        RegistryCleaner.removeEntryAndFiles(ctx(), id);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
    createdScanIds.clear();
  }

  @Test
  public void reCrop_dropsOcrTextWordsAndEditedJson() throws Exception {
    CompletedScan page = persistPageWithOcr("Hel1o world");
    File dir = PageOcrFiles.dir(ctx(), page.id());
    writeEditedJson(dir, "Hello world");
    assertEquals(CompletedScan.STATUS_OCR_COMPLETE, page.pageStatus());

    CompletedScan recropped = ScanPersister.persistEditedPage(ctx(), editedCopy(page));

    assertFalse(new File(dir, PageOcrFiles.TEXT_FILE).exists());
    assertFalse(new File(dir, PageOcrFiles.WORDS_FILE).exists());
    assertFalse(new File(dir, PageOcrFiles.EDITED_FILE).exists());
    assertNull(recropped.ocrTextPath());
    assertEquals(CompletedScan.STATUS_IMPORTED, recropped.pageStatus());
    CompletedScan registered = CompletedScansRegistry.get(ctx()).findById(page.id());
    assertNotNull(registered);
    assertNull("registry keeps no stale OCR path", registered.ocrTextPath());
    assertTrue(
        "no old text reaches the library",
        new OcrTextExtractor().extract(dir, null, null).isEmpty());
  }

  @Test
  public void reCrop_afterReviewSave_dropsReviewedText() throws Exception {
    CompletedScan page = persistPageWithOcr("Monday");
    File dir = PageOcrFiles.dir(ctx(), page.id());
    PageOcrFiles.write(ctx(), page.id(), "Tuesday", words("Tuesday"));

    ScanPersister.persistEditedPage(ctx(), editedCopy(page));

    assertTrue(new OcrTextExtractor().extract(dir, null, null).isEmpty());
  }

  @Test
  public void newResult_winsOverOlderEditedJson() throws Exception {
    CompletedScan page = persistPageWithOcr("Monday");
    File dir = PageOcrFiles.dir(ctx(), page.id());
    writeEditedJson(dir, "Edited long ago");

    File wordsFile = PageOcrFiles.write(ctx(), page.id(), "Tuesday", words("Tuesday"));

    assertFalse(new File(dir, PageOcrFiles.EDITED_FILE).exists());
    OcrTextExtractor.ExtractedText extracted =
        new OcrTextExtractor().extract(dir, wordsFile.getAbsolutePath(), "words_json");
    assertEquals("Tuesday", extracted.text);
    assertEquals(OcrTextExtractor.FORMAT_WORDS_JSON, extracted.sourceFormat);
  }

  /** Persists a small page with OCR text and words, as the scan flow does. */
  private CompletedScan persistPageWithOcr(String text) throws Exception {
    Bitmap bmp = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888);
    bmp.eraseColor(0xFFFFFFFF);
    CompletedScan inMemory =
        new CompletedScan(
            UUID.randomUUID().toString(),
            null,
            0,
            null,
            null,
            null,
            System.currentTimeMillis(),
            bmp.getWidth(),
            bmp.getHeight(),
            bmp,
            2,
            "baked",
            CompletedScan.SOURCE_PDF,
            0,
            CompletedScan.STATUS_IMPORTING);
    CompletedScan persisted = ScanPersister.persist(ctx(), inMemory, text, words(text));
    createdScanIds.add(persisted.id());
    bmp.recycle();
    return persisted;
  }

  /** The same page with a freshly cropped image, as the re-crop from the export screen hands it. */
  private static CompletedScan editedCopy(CompletedScan page) {
    Bitmap edited = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888);
    edited.eraseColor(0xFFEEEEEE);
    return new CompletedScan(
        page.id(),
        null,
        0,
        null,
        null,
        null,
        page.createdAt(),
        edited.getWidth(),
        edited.getHeight(),
        edited,
        page.schemaVersion(),
        page.orientationMode(),
        page.sourceType(),
        page.pdfPageIndex(),
        page.pageStatus());
  }

  private static void writeEditedJson(File dir, String text) {
    OcrDoc doc = new OcrDoc();
    OcrDoc.Word w = new OcrDoc.Word();
    w.id = 1;
    w.t = text;
    w.b = new int[] {4, 4, 40, 12};
    w.c = 1f;
    doc.words.add(w);
    assertTrue(OcrJsonStore.save(new File(dir, PageOcrFiles.EDITED_FILE), doc));
  }

  private static List<RecognizedWord> words(String text) {
    List<RecognizedWord> out = new ArrayList<>();
    out.add(new RecognizedWord(text, new RectF(4, 4, 44, 16), 0.9f));
    return out;
  }
}
