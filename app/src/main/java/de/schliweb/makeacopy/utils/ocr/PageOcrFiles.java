/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * The persisted OCR result of one scan page under {@code filesDir/scans/<id>/}: {@code text.txt}
 * and {@code words.json}. Written by the background OCR job and by the Review screen when it edits
 * a session page, read by the export (PDF text layer and TXT) and the library.
 */
@UtilityClass
public final class PageOcrFiles {
  public static final String TEXT_FILE = "text.txt";
  public static final String WORDS_FILE = "words.json";

  /**
   * Editable OCR JSON (schema 1). The export and the library prefer it over {@link #WORDS_FILE}, so
   * it is removed whenever a newer result is written.
   */
  public static final String EDITED_FILE = "page.ocr.json";

  /** The directory of the page with the given id. */
  public static File dir(Context ctx, String pageId) {
    return new File(ctx.getFilesDir(), "scans/" + pageId);
  }

  /**
   * Writes {@code text} and {@code words} as the page's OCR result, replacing the previous one.
   *
   * @return the written {@code words.json}
   */
  public static File write(Context ctx, String pageId, String text, List<RecognizedWord> words)
      throws IOException {
    return write(dir(ctx, pageId), text, words);
  }

  /** {@link #write(Context, String, String, List)} into the given page directory. */
  static File write(File dir, String text, List<RecognizedWord> words) throws IOException {
    if (!dir.exists()) {
      //noinspection ResultOfMethodCallIgnored
      dir.mkdirs();
    }
    writeUtf8(new File(dir, TEXT_FILE), text != null ? text : "");
    File wordsFile = new File(dir, WORDS_FILE);
    writeUtf8(wordsFile, WordsJson.toWordsJson(words));
    File edited = new File(dir, EDITED_FILE);
    if (edited.exists() && !edited.delete()) {
      throw new IOException("Could not remove stale " + EDITED_FILE);
    }
    return wordsFile;
  }

  private static void writeUtf8(File file, String content) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(file)) {
      fos.write(content.getBytes(StandardCharsets.UTF_8));
      fos.flush();
    }
  }
}
