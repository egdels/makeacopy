package de.schliweb.makeacopy.utils.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Tests how an RTL line is laid out for the left-to-right glyph stream of a PDF. */
public class PdfTextUtilsVisualOrderTest {

  private static String reversed(String s) {
    return new StringBuilder(s).reverse().toString();
  }

  @Test
  public void rtlLinePutsTheFirstWordOnTheRight() {
    // Glyphs advance to the right, so the last word is written first and each word back to front.
    assertEquals(
        reversed("مستند اختبار العربية"),
        PdfTextUtils.logicalToVisual("مستند اختبار العربية", true));
  }

  @Test
  public void embeddedNumbersAndLatinKeepTheirOrder() {
    assertEquals(
        "25/12/2025 :" + reversed("التاريخ"),
        PdfTextUtils.logicalToVisual("التاريخ: 25/12/2025", true));
    assertEquals(
        ".MakeACopy " + reversed("هذا مستند"),
        PdfTextUtils.logicalToVisual("هذا مستند MakeACopy.", true));
    assertEquals(
        "https://example.com/makeacopy " + reversed("- رابط:"),
        PdfTextUtils.logicalToVisual("- رابط: https://example.com/makeacopy", true));
  }

  @Test
  public void ltrLinesAndTrivialInputAreUnchanged() {
    assertEquals("Hello World", PdfTextUtils.logicalToVisual("Hello World", false));
    assertEquals("Hello مستند", PdfTextUtils.logicalToVisual("Hello مستند", false));
    assertEquals("x", PdfTextUtils.logicalToVisual("x", true));
    assertNull(PdfTextUtils.logicalToVisual(null, true));
  }
}
