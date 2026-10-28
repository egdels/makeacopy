package de.schliweb.makeacopy.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;
import de.schliweb.makeacopy.utils.ocr.OCRPostProcessor;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link OCRPostProcessor} public static methods: processText, analyzeQuality,
 * wordsToText, and OcrQualityStats record.
 */
public class OCRPostProcessorTextTest {

  // ── processText ──────────────────────────────────────────────────────────

  // ── analyzeQuality ───────────────────────────────────────────────────────

  // ── OcrQualityStats ──────────────────────────────────────────────────────

  // ── wordsToText ──────────────────────────────────────────────────────────

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
        Collections.singletonList(new RecognizedWord("hello", new RectF(0, 0, 50, 20), 90.0f));
    String result = OCRPostProcessor.wordsToText(words);
    assertEquals("hello", result);
  }

  @Test
  public void wordsToText_multipleWords_spaceSeparated() {
    // With stub RectF (all coords 0), all words land on same "line"
    List<RecognizedWord> words =
        Arrays.asList(
            new RecognizedWord("hello", new RectF(0, 0, 0, 0), 90.0f),
            new RecognizedWord("world", new RectF(0, 0, 0, 0), 90.0f));
    String result = OCRPostProcessor.wordsToText(words);
    assertTrue(result.contains("hello"));
    assertTrue(result.contains("world"));
  }

  @Test
  public void wordsToText_htmlEntities_decoded() {
    List<RecognizedWord> words =
        Collections.singletonList(new RecognizedWord("don&apos;t", new RectF(0, 0, 0, 0), 90.0f));
    String result = OCRPostProcessor.wordsToText(words);
    assertEquals("don't", result);
  }

  @Test
  public void wordsToText_densePaddleBoxes_usesStableOrdering() {
    List<RecognizedWord> words =
        Arrays.asList(
            new RecognizedWord("A", new RectF(30, 0, 40, 100), 90.0f),
            new RecognizedWord("B", new RectF(10, 45, 20, 55), 90.0f),
            new RecognizedWord("C", new RectF(20, 90, 30, 100), 90.0f),
            new RecognizedWord("D", new RectF(0, 135, 10, 145), 90.0f));

    String result = OCRPostProcessor.wordsToText(words);

    assertTrue(result.contains("A"));
    assertTrue(result.contains("B"));
    assertTrue(result.contains("C"));
    assertTrue(result.contains("D"));
  }
}
