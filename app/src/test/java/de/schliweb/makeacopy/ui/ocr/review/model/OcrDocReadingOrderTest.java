package de.schliweb.makeacopy.ui.ocr.review.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class OcrDocReadingOrderTest {

  @Test
  public void sortedWordsForReading_ordersArabicLineRightToLeft() {
    OcrDoc doc = new OcrDoc();
    doc.words =
        Arrays.asList(
            word(1, "المستندات", 220, 10),
            word(2, "على", 140, 10),
            word(3, "التعرف", 80, 10),
            word(4, "اختبار", 10, 10));

    List<OcrDoc.Word> sorted = OcrDocReadingOrder.sortedWordsForReading(doc);

    assertEquals("المستندات", sorted.get(0).t);
    assertEquals("على", sorted.get(1).t);
    assertEquals("التعرف", sorted.get(2).t);
    assertEquals("اختبار", sorted.get(3).t);
  }

  @Test
  public void sortedWordsForReading_ordersLatinLineLeftToRight() {
    OcrDoc doc = new OcrDoc();
    doc.words =
        Arrays.asList(
            word(1, "world", 90, 10), word(2, "hello", 10, 10), word(3, "again", 170, 10));

    List<OcrDoc.Word> sorted = OcrDocReadingOrder.sortedWordsForReading(doc);

    assertEquals("hello", sorted.get(0).t);
    assertEquals("world", sorted.get(1).t);
    assertEquals("again", sorted.get(2).t);
  }

  @Test
  public void sortedWordsForReading_keepsLinesTopToBottom() {
    OcrDoc doc = new OcrDoc();
    doc.words =
        Arrays.asList(
            word(1, "السطر", 120, 10),
            word(2, "الأول", 10, 10),
            word(3, "السطر", 120, 80),
            word(4, "الثاني", 10, 80));

    List<OcrDoc.Word> sorted = OcrDocReadingOrder.sortedWordsForReading(doc);

    assertEquals("السطر", sorted.get(0).t);
    assertEquals("الأول", sorted.get(1).t);
    assertEquals("السطر", sorted.get(2).t);
    assertEquals("الثاني", sorted.get(3).t);
  }

  @Test
  public void sortedWordsForReading_keepsLatinWordsInOrderInsideAnArabicLine() {
    // Issue #101: "Hello World" left of three Arabic words, the line has an Arabic majority.
    OcrDoc doc = new OcrDoc();
    doc.words =
        Arrays.asList(
            word(1, "Hello", 10, 10),
            word(2, "World", 70, 10),
            word(3, "المستندات", 300, 10),
            word(4, "على", 240, 10),
            word(5, "التعرف", 180, 10));

    List<OcrDoc.Word> sorted = OcrDocReadingOrder.sortedWordsForReading(doc);

    assertEquals("المستندات", sorted.get(0).t);
    assertEquals("على", sorted.get(1).t);
    assertEquals("التعرف", sorted.get(2).t);
    assertEquals("Hello", sorted.get(3).t);
    assertEquals("World", sorted.get(4).t);
  }

  @Test
  public void isRtlText_detectsArabicWithoutChangingWordText() {
    String arabic = "المستندات";

    assertTrue(OcrDocReadingOrder.isRtlText(arabic));
    assertEquals("المستندات", arabic);
  }

  @Test
  public void isRtlText_keepsLatinLeftToRight() {
    assertFalse(OcrDocReadingOrder.isRtlText("document"));
  }

  private static OcrDoc.Word word(int id, String text, int x, int y) {
    OcrDoc.Word word = new OcrDoc.Word();
    word.id = id;
    word.t = text;
    word.b = new int[] {x, y, 50, 20};
    return word;
  }
}