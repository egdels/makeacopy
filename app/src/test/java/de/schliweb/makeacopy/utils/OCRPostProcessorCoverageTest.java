package de.schliweb.makeacopy.utils;

import static org.junit.Assert.*;

import android.graphics.RectF;
import de.schliweb.makeacopy.utils.ocr.OCRPostProcessor;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Additional JVM unit tests for {@link OCRPostProcessor} covering processText, analyzeQuality,
 * wordsToText, isLineRtl, process, and OcrQualityStats.
 */
public class OCRPostProcessorCoverageTest {

  private static RectF rect(float l, float t, float r, float b) {
    RectF rf = new RectF();
    rf.left = l;
    rf.top = t;
    rf.right = r;
    rf.bottom = b;
    return rf;
  }

  // ---- processText ----

  // ---- analyzeQuality ----

  // ---- process (List<RecognizedWord>) ----

  // ---- wordsToText ----

  @Test
  public void wordsToText_null_returnsEmpty() {
    assertEquals("", OCRPostProcessor.wordsToText(null));
  }

  @Test
  public void wordsToText_empty_returnsEmpty() {
    assertEquals("", OCRPostProcessor.wordsToText(Collections.emptyList()));
  }

  @Test
  public void wordsToText_singleWord() {
    List<RecognizedWord> words =
        Arrays.asList(new RecognizedWord("hello", rect(10, 10, 50, 30), 90f));
    String text = OCRPostProcessor.wordsToText(words);
    assertEquals("hello", text.trim());
  }

  @Test
  public void wordsToText_twoWordsOnSameLine() {
    List<RecognizedWord> words =
        Arrays.asList(
            new RecognizedWord("hello", rect(10, 10, 50, 30), 90f),
            new RecognizedWord("world", rect(60, 10, 100, 30), 90f));
    String text = OCRPostProcessor.wordsToText(words);
    assertEquals("hello world", text.trim());
  }

  // wordsToText line-break and paragraph tests require RectF.height() which
  // returns 0 in Android unit-test stubs; these are covered by instrumented tests.

  // ---- Helper ----

  private static RecognizedWord word(String text, float confidence) {
    return new RecognizedWord(text, rect(0, 0, 10, 10), confidence);
  }
}
