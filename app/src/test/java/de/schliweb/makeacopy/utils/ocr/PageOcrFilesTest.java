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

import static org.junit.Assert.*;

import android.graphics.RectF;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests the persisted OCR result of a page: Review edits and new OCR runs both replace it, and an
 * older editable page.ocr.json must not shadow the newer result in the export.
 */
public class PageOcrFilesTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void write_createsDirAndWritesTextAndWords() throws Exception {
    File dir = new File(tmp.getRoot(), "scans/p1");
    List<RecognizedWord> words = List.of(new RecognizedWord("مرحبا", new RectF(), 0.9f));

    File wordsFile = PageOcrFiles.write(dir, "مرحبا", words);

    assertEquals(new File(dir, PageOcrFiles.WORDS_FILE), wordsFile);
    assertEquals("مرحبا", read(new File(dir, PageOcrFiles.TEXT_FILE)));
    assertTrue(read(wordsFile).contains("مرحبا"));
  }

  @Test
  public void write_replacesPreviousResult() throws Exception {
    File dir = tmp.newFolder("p2");
    PageOcrFiles.write(dir, "Hel1o", List.of(new RecognizedWord("Hel1o", new RectF(), 0.4f)));

    PageOcrFiles.write(dir, "Hello", List.of(new RecognizedWord("Hello", new RectF(), 0.4f)));

    assertEquals("Hello", read(new File(dir, PageOcrFiles.TEXT_FILE)));
    assertFalse(read(new File(dir, PageOcrFiles.WORDS_FILE)).contains("Hel1o"));
  }

  @Test
  public void write_removesStaleEditedJson() throws Exception {
    File dir = tmp.newFolder("p3");
    File edited = new File(dir, PageOcrFiles.EDITED_FILE);
    Files.write(edited.toPath(), "{\"schema\":1,\"words\":[]}".getBytes(StandardCharsets.UTF_8));

    PageOcrFiles.write(dir, "new", List.of());

    assertFalse(edited.exists());
  }

  @Test
  public void write_nullTextWritesEmptyFile() throws Exception {
    File dir = tmp.newFolder("p4");

    PageOcrFiles.write(dir, null, null);

    assertEquals("", read(new File(dir, PageOcrFiles.TEXT_FILE)));
    assertEquals("[]", read(new File(dir, PageOcrFiles.WORDS_FILE)));
  }

  private static String read(File f) throws Exception {
    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
  }
}
